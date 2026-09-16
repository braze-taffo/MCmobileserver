/*
 * libjvmlauncher — 在 :server 进程内直接创建 HotSpot JVM 并调用普通 Java 主类。
 *
 * 流程（独立 pthread，避免占用 ART 附着的线程）：
 *   dlopen(nativeLibraryDir/libjvm.so)
 *   → JNI_CreateJavaVM（-Djava.home/-Djava.class.path/-Xmx 等由 Kotlin 侧以 jvmOpts 传入）
 *   → FindClass(mainClass) → main(String[])
 *   → 主类返回/抛异常后，附着回 ART 虚拟机回调 JvmLauncherCallback.onJvmExited
 *
 * 不调用 DestroyJavaVM：MC 服务器 main 返回即视为已停止，由 Kotlin 侧收尾并退出进程。
 * stdout/stderr/stdin 由 Kotlin 在调用前用 pipe()+dup2() 接管。
 */
#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "mcs-jvmlauncher"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef jint (*CreateJavaVM_fn)(JavaVM **vm, void **env, void *args);

typedef struct {
    JavaVM *art_vm;
    char *libjvm_path;
    char *java_home;
    char *main_class;
    char **jvm_opts;
    int jvm_opt_count;
    char **args;
    int arg_count;
} launch_req;

static char *jstrdup(JNIEnv *env, jstring s) {
    if (s == NULL) return NULL;
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    if (c == NULL) return NULL;
    char *out = strdup(c);
    (*env)->ReleaseStringUTFChars(env, s, c);
    return out;
}

static char **jstrarray(JNIEnv *env, jobjectArray arr, int *count) {
    if (arr == NULL) {
        *count = 0;
        return NULL;
    }
    jsize n = (*env)->GetArrayLength(env, arr);
    char **out = calloc((size_t) (n > 0 ? n : 1), sizeof(char *));
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        out[i] = jstrdup(env, s);
        if (s != NULL) (*env)->DeleteLocalRef(env, s);
    }
    *count = (int) n;
    return out;
}

static void free_req(launch_req *r) {
    free(r->libjvm_path);
    free(r->java_home);
    free(r->main_class);
    for (int i = 0; i < r->jvm_opt_count; i++) free(r->jvm_opts[i]);
    free(r->jvm_opts);
    for (int i = 0; i < r->arg_count; i++) free(r->args[i]);
    free(r->args);
    free(r);
}

/* 附着回 ART 虚拟机，把 JVM 退出事件报告给 Kotlin 侧 */
static void report_exit(JavaVM *art_vm, jint code, jboolean exception) {
    JNIEnv *env = NULL;
    JavaVMAttachArgs attach_args = {JNI_VERSION_1_6, (char *) "JVMExitReporter", NULL};
    if ((*art_vm)->AttachCurrentThread(art_vm, &env, &attach_args) != JNI_OK || env == NULL) {
        LOGE("AttachCurrentThread(ART) failed, cannot report exit %d", code);
        return;
    }
    jclass cls = (*env)->FindClass(env, "com/mcmobile/server/core/launch/JvmLauncherCallback");
    if (cls == NULL) {
        LOGE("JvmLauncherCallback not found");
        (*env)->ExceptionClear(env);
    } else {
        jmethodID m = (*env)->GetStaticMethodID(env, cls, "onJvmExited", "(IZ)V");
        if (m == NULL) {
            LOGE("onJvmExited not found");
            (*env)->ExceptionClear(env);
        } else {
            (*env)->CallStaticVoidMethod(env, cls, m, code, exception);
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionDescribe(env);
        }
    }
    (*art_vm)->DetachCurrentThread(art_vm);
}

static void fail(launch_req *req, jint code) {
    report_exit(req->art_vm, code, JNI_TRUE);
    free_req(req);
}

static void *thread_main(void *arg) {
    launch_req *req = (launch_req *) arg;
    jint exit_code = 0;
    jboolean exception = JNI_FALSE;

    void *handle = dlopen(req->libjvm_path, RTLD_LAZY | RTLD_LOCAL);
    if (handle == NULL) {
        LOGE("dlopen(%s) failed: %s", req->libjvm_path, dlerror());
        fail(req, -100);
        return NULL;
    }
    CreateJavaVM_fn create_vm = (CreateJavaVM_fn) dlsym(handle, "JNI_CreateJavaVM");
    if (create_vm == NULL) {
        LOGE("dlsym(JNI_CreateJavaVM) failed: %s", dlerror());
        fail(req, -101);
        return NULL;
    }

    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    JavaVMInitArgs vm_args;
    JavaVMOption *options = calloc(
            (size_t) (req->jvm_opt_count > 0 ? req->jvm_opt_count : 1),
            sizeof(JavaVMOption));
    for (int i = 0; i < req->jvm_opt_count; i++) {
        options[i].optionString = req->jvm_opts[i];
        options[i].extraInfo = NULL;
    }
    vm_args.version = JNI_VERSION_1_6;
    vm_args.nOptions = req->jvm_opt_count;
    vm_args.options = options;
    vm_args.ignoreUnrecognized = JNI_FALSE;

    LOGI("creating JVM: main=%s opts=%d args=%d",
         req->main_class, req->jvm_opt_count, req->arg_count);
    jint rc = create_vm(&vm, (void **) &env, &vm_args);
    free(options);
    if (rc != JNI_OK || env == NULL) {
        LOGE("JNI_CreateJavaVM failed: %d", rc);
        fail(req, -102);
        return NULL;
    }

    jclass main_cls = (*env)->FindClass(env, req->main_class);
    if (main_cls == NULL) {
        LOGE("FindClass(%s) failed", req->main_class);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionDescribe(env);
        fail(req, -103);
        return NULL;
    }
    jmethodID main_method = (*env)->GetStaticMethodID(
            env, main_cls, "main", "([Ljava/lang/String;)V");
    if (main_method == NULL) {
        LOGE("main([Ljava/lang/String;)V not found in %s", req->main_class);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionDescribe(env);
        fail(req, -104);
        return NULL;
    }
    jclass string_cls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray arg_array = (*env)->NewObjectArray(
            env, req->arg_count, string_cls, NULL);
    for (int i = 0; i < req->arg_count; i++) {
        jstring s = (*env)->NewStringUTF(env, req->args[i]);
        (*env)->SetObjectArrayElement(env, arg_array, i, s);
        (*env)->DeleteLocalRef(env, s);
    }

    (*env)->CallStaticVoidMethod(env, main_cls, main_method, arg_array);
    if ((*env)->ExceptionCheck(env)) {
        exception = JNI_TRUE;
        exit_code = 1;
        /* 栈回溯打到 stderr（已被 dup2 接管为控制台管道） */
        (*env)->ExceptionDescribe(env);
    }
    LOGI("JVM main returned (exception=%d)", exception);

    report_exit(req->art_vm, exit_code, exception);
    free_req(req);
    return NULL;
}

/* android.system.Os 未暴露 chdir，这里补一个（服务器以 workDir 为 cwd） */
JNIEXPORT jint JNICALL
Java_com_mcmobile_server_core_launch_JvmLauncher_nativeChdir(
        JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    const char *c = (*env)->GetStringUTFChars(env, path, NULL);
    if (c == NULL) return -1;
    int rc = chdir(c);
    (*env)->ReleaseStringUTFChars(env, path, c);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_mcmobile_server_core_launch_JvmLauncher_nativeStart(
        JNIEnv *env, jclass clazz,
        jstring libjvmPath, jstring javaHome, jstring mainClass,
        jobjectArray jvmOpts, jobjectArray args) {
    (void) clazz;
    JavaVM *art_vm = NULL;
    if ((*env)->GetJavaVM(env, &art_vm) != JNI_OK || art_vm == NULL) {
        return -1;
    }
    launch_req *req = calloc(1, sizeof(launch_req));
    req->art_vm = art_vm;
    req->libjvm_path = jstrdup(env, libjvmPath);
    req->java_home = jstrdup(env, javaHome);
    req->main_class = jstrdup(env, mainClass);
    req->jvm_opts = jstrarray(env, jvmOpts, &req->jvm_opt_count);
    req->args = jstrarray(env, args, &req->arg_count);

    /* mobile 构建的路径补丁可能依赖 JAVA_HOME 定位自身 */
    setenv("JAVA_HOME", req->java_home, 1);

    pthread_t tid;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    int rc = pthread_create(&tid, &attr, thread_main, req);
    pthread_attr_destroy(&attr);
    if (rc != 0) {
        LOGE("pthread_create failed: %s", strerror(rc));
        free_req(req);
        return -2;
    }
    return 0;
}
