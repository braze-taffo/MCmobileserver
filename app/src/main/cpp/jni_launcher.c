/*
 * libjvmlauncher — 在 :server 进程内直接创建 HotSpot JVM 并调用普通 Java 主类。
 *
 * 流程（独立 pthread，避免占用 ART 附着的线程）：
 *   dlopen(libjvm.so, RTLD_GLOBAL)
 *   → 把解压出来的 JRE lib 目录加入链接器搜索路径，并预加载其中的原生库
 *     （libjava/libjimage/libzip 等的 DT_NEEDED 是 libjvm.so，必须先有 libjvm 在场）
 *   → JNI_CreateJavaVM（-Djava.home/-Djava.class.path/-Xmx 等由 Kotlin 侧以 jvmOpts 传入）
 *   → FindClass(mainClass) → main(String[])
 *   → 主类返回/抛异常后，附着回 ART 虚拟机回调 JvmLauncherCallback.onJvmExited
 *
 * 不调用 DestroyJavaVM 之外的收尾：主类 main 返回后调用 DestroyJavaVM，等所有非守护
 * 线程结束（= 服务器真正停止）才回调 JvmLauncherCallback.onJvmExited，由 Kotlin 侧退出进程。
 * 服务器自行 System.exit() 时 HotSpot 直接结束本进程，UI 以 Socket 断开识别。
 * stdout/stderr/stdin 由 Kotlin 在调用前用 pipe()+dup2() 接管。
 */
#include <android/api-level.h>
#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef M_BIONIC_SET_HEAP_TAGGING_LEVEL
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL (-108)
#endif
#ifndef M_HEAP_TAGGING_LEVEL_NONE
#define M_HEAP_TAGGING_LEVEL_NONE 0
#endif
#define M_SET_HEAP_TAGGING_LEVEL_OPCODE 8

#define LOG_TAG "mcs-jvmlauncher"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef jint (*CreateJavaVM_fn)(JavaVM **vm, void **env, void *args);

typedef struct {
    JavaVM *art_vm;
    char *libjvm_path;
    char *java_home;
    char *main_class;
    char *init_log_path;
    char **jvm_opts;
    int jvm_opt_count;
    char **args;
    int arg_count;
    /* main 返回后是否等非守护线程结束（见 LaunchSpec.waitForNonDaemonThreads） */
    jboolean wait_for_threads;
} launch_req;

/* 回调类/方法在 nativeStart（有应用 classloader 上下文）时缓存为全局引用；
 * 裸 attach 的线程 FindClass 找不到应用类，只能用缓存。 */
static JavaVM *g_art_vm = NULL;
static jobject g_callback_class = NULL;
static jmethodID g_callback_method = NULL;

/* ------------------------------------------------------------------ *
 * 关闭 bionic 的堆指针标记（heap tagging）
 *
 * Android 12+ 默认给进程开启 tagged pointers：malloc 返回的指针顶字节带标记。
 * HotSpot 的 AArch64 代码自己也用顶字节，两套标记叠加后 free() 会拿到"标记被
 * 截断"的指针，bionic 直接 SIGABRT（表现：第一次 GC 之后必崩，abort message
 * "Pointer tag for 0x... was truncated"）。
 *
 * Termux 的 OpenJDK 用同样的 workaround，但放在 libjli 的 JLI_Launch 里；
 * 我们直接调 JNI_CreateJavaVM、不走 libjli，所以必须自己关一次。
 * ------------------------------------------------------------------ */
static void disable_heap_tagging(void) {
    void *libc = dlopen("libc.so", RTLD_LAZY);
    if (libc == NULL) {
        LOGI("heap tagging: dlopen(libc.so) failed, skip");
        return;
    }
    if (android_get_device_api_level() >= 31) {
        int (*mallopt_fn)(int, int) = (int (*)(int, int)) dlsym(libc, "mallopt");
        if (mallopt_fn != NULL) {
            int rc = mallopt_fn(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE);
            LOGI("heap tagging disabled by mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, 0) rc=%d", rc);
        } else {
            LOGI("heap tagging: mallopt not found, skip");
        }
    } else {
        bool (*android_mallopt_fn)(int, void *, size_t) =
                (bool (*)(int, void *, size_t)) dlsym(libc, "android_mallopt");
        if (android_mallopt_fn != NULL) {
            int level = M_HEAP_TAGGING_LEVEL_NONE;
            android_mallopt_fn(M_SET_HEAP_TAGGING_LEVEL_OPCODE, &level, sizeof(level));
            LOGI("heap tagging disabled by android_mallopt(M_SET_HEAP_TAGGING_LEVEL, 0)");
        }
    }
}

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
    free(r->init_log_path);
    for (int i = 0; i < r->jvm_opt_count; i++) free(r->jvm_opts[i]);
    free(r->jvm_opts);
    for (int i = 0; i < r->arg_count; i++) free(r->args[i]);
    free(r->args);
    free(r);
}

/* 附着回 ART 虚拟机，把 JVM 退出事件报告给 Kotlin 侧（用 nativeStart 缓存的全局引用） */
static void report_exit(JavaVM *art_vm, jint code, jboolean exception) {
    JNIEnv *env = NULL;
    JavaVMAttachArgs attach_args = {JNI_VERSION_1_6, (char *) "JVMExitReporter", NULL};
    if ((*art_vm)->AttachCurrentThread(art_vm, &env, &attach_args) != JNI_OK || env == NULL) {
        LOGE("AttachCurrentThread(ART) failed, cannot report exit %d", code);
        return;
    }
    if (g_callback_class == NULL || g_callback_method == NULL) {
        LOGE("callback not cached, drop exit event %d", code);
    } else {
        (*env)->CallStaticVoidMethod(env, (jclass) g_callback_class, g_callback_method,
                                     code, exception);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionDescribe(env);
    }
    (*art_vm)->DetachCurrentThread(art_vm);
}

static void fail(launch_req *req, jint code) {
    report_exit(req->art_vm, code, JNI_TRUE);
    free_req(req);
}

/* JNI 的 FindClass 要内部名（斜杠分隔），Kotlin 侧给的是二进制名（点分隔）。
 * 默认包下的类看不出区别，带包名的类不加转换必然 NoClassDefFoundError。 */
static char *to_internal_name(const char *binary_name) {
    if (binary_name == NULL) return NULL;
    char *out = strdup(binary_name);
    if (out == NULL) return NULL;
    for (char *p = out; *p != '\0'; p++) {
        if (*p == '.') *p = '/';
    }
    return out;
}

/* ------------------------------------------------------------------ *
 * JVM 初始化期的 stdout/stderr 落盘
 *
 * HotSpot 初始化失败时会自己 exit(1)，进程内所有线程一起消失，管道里的最后
 * 几行（也就是真正的原因）会随进程一起丢掉。因此创建 VM 期间把 fd1/fd2 临时
 * 指向 logs/jvm-init.log（写文件是即时系统调用，进程暴毙也不丢），创建成功后再切回管道。
 * ------------------------------------------------------------------ */
typedef struct {
    int log_fd;
    int saved_out;
    int saved_err;
} init_capture;

static void capture_begin(const char *path, const char *note, init_capture *c) {
    c->log_fd = -1;
    c->saved_out = -1;
    c->saved_err = -1;
    if (path == NULL || path[0] == '\0') return;
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        LOGE("open(init log %s) failed: %s", path, strerror(errno));
        return;
    }
    c->log_fd = fd;
    c->saved_out = dup(1);
    c->saved_err = dup(2);
    dup2(fd, 1);
    dup2(fd, 2);
    if (note != NULL) dprintf(fd, "[jvm-launcher] %s\n", note);
}

static void capture_end(init_capture *c) {
    if (c->log_fd < 0) return;
    if (c->saved_out >= 0) {
        dup2(c->saved_out, 1);
        close(c->saved_out);
    }
    if (c->saved_err >= 0) {
        dup2(c->saved_err, 2);
        close(c->saved_err);
    }
    close(c->log_fd);
    c->log_fd = -1;
}

/* ------------------------------------------------------------------ *
 * 让链接器能找到解压出来的 JRE 原生库
 *
 * JRE 里 libjava/libjimage/libzip 等 .so 的 DT_NEEDED 都写着 libjvm.so，
 * 而我们的 libjvm 在 APK 内（叫 libjvm<major>.so，SONAME 仍是 libjvm.so）。
 * 做法与 PojavLauncher 一致：
 *   1. 先把 libjvm 以 RTLD_GLOBAL 加载 —— 之后链接器按 SONAME 就能满足 libjvm.so 依赖
 *   2. 用 linker 私有 API android_update_LD_LIBRARY_PATH 把 <javaHome>/lib 加进搜索路径
 *   3. 按绝对路径预加载 JRE 原生库（顺序在 libjvm 之后）
 * ------------------------------------------------------------------ */

/* AWT/字体/信号链在无头服务器上用不到，且会因缺 libX11 之类加载失败刷屏。
 * libjli 不预加载但也不排除：libinstrument（-javaagent）依赖它。 */
static const char *const PRELOAD_SKIP[] = {
        "libjsig.so", "libawt.so", "libawt_headless.so", "libjawt.so",
        "libfontmanager.so", "libfreetype.so", "liblcms.so", "libmlib_image.so",
        "libjavajpeg.so",
};

static int should_skip(const char *name) {
    for (size_t i = 0; i < sizeof(PRELOAD_SKIP) / sizeof(PRELOAD_SKIP[0]); i++) {
        if (strcmp(name, PRELOAD_SKIP[i]) == 0) return 1;
    }
    return 0;
}

/* 把 <dir>/*.so 以 RTLD_GLOBAL 加载；返回值仅用于日志 */
static int preload_dir(const char *dir) {
    DIR *d = opendir(dir);
    if (d == NULL) return 0;

    /* 先收集名单：readdir 顺序是任意的，而库之间存在依赖
     * （libnet/libnio/libinstrument 的 DT_NEEDED 都含 libjava.so），
     * 顺序不对就会加载失败，所以下面要按"多轮直到没有新进展"来加载。 */
    char **names = NULL;
    int n = 0, cap = 0;
    struct dirent *ent;
    while ((ent = readdir(d)) != NULL) {
        const char *name = ent->d_name;
        size_t len = strlen(name);
        if (len < 4 || strcmp(name + len - 3, ".so") != 0) continue;
        if (should_skip(name)) continue;
        if (n == cap) {
            cap = cap > 0 ? cap * 2 : 16;
            names = realloc(names, sizeof(char *) * (size_t) cap);
        }
        names[n++] = strdup(name);
    }
    closedir(d);
    if (n == 0) {
        free(names);
        return 0;
    }

    char *state = calloc((size_t) n, 1);   /* 0=待加载 1=已加载 2=放弃 */
    int loaded = 0;
    for (int pass = 0; pass < n; pass++) {
        int progressed = 0;
        for (int i = 0; i < n; i++) {
            if (state[i] != 0) continue;
            char path[4096];
            if (snprintf(path, sizeof(path), "%s/%s", dir, names[i]) >= (int) sizeof(path)) {
                state[i] = 2;
                continue;
            }
            if (dlopen(path, RTLD_LAZY | RTLD_GLOBAL) != NULL) {
                state[i] = 1;
                loaded++;
                progressed = 1;
            }
        }
        if (!progressed) break;
    }

    int skipped = 0;
    for (int i = 0; i < n; i++) {
        if (state[i] == 0) {
            skipped++;
            char path[4096];
            snprintf(path, sizeof(path), "%s/%s", dir, names[i]);
            dlerror();
            dlopen(path, RTLD_LAZY | RTLD_GLOBAL);
            LOGI("preload skip %s: %s", names[i], dlerror());
        }
        free(names[i]);
    }
    free(names);
    free(state);
    LOGI("preload %s: %d ok, %d skipped", dir, loaded, skipped);
    return loaded;
}

static void prepare_linker_path(const launch_req *req) {
    char lib[4096], server[4096];
    snprintf(lib, sizeof(lib), "%s/lib", req->java_home);
    snprintf(server, sizeof(server), "%s/lib/server", req->java_home);

    const char *old = getenv("LD_LIBRARY_PATH");
    char val[8192];
    snprintf(val, sizeof(val), "%s:%s:%s", server, lib, old != NULL ? old : "");
    setenv("LD_LIBRARY_PATH", val, 1);

    /* Android 的 linker 默认忽略环境变量里的 LD_LIBRARY_PATH，
     * 要用 libdl 的私有入口把它塞进默认命名空间的搜索路径（失败不影响绝对路径加载） */
    void *dl = dlopen("libdl.so", RTLD_LAZY);
    if (dl != NULL) {
        typedef void (*update_fn)(const char *);
        update_fn fn = (update_fn) dlsym(dl, "android_update_LD_LIBRARY_PATH");
        if (fn == NULL) {
            fn = (update_fn) dlsym(dl, "__loader_android_update_LD_LIBRARY_PATH");
        }
        if (fn != NULL) {
            fn(val);
            LOGI("linker LD_LIBRARY_PATH updated: %s", val);
        } else {
            LOGI("linker has no update entry, rely on preload/RTLD_GLOBAL");
        }
    }
}

static void *thread_main(void *arg) {
    launch_req *req = (launch_req *) arg;
    jint exit_code = 0;
    jboolean exception = JNI_FALSE;

    /* 1) 关堆标记 + libjvm 必须最先、且以 RTLD_GLOBAL 加载 */
    disable_heap_tagging();
    void *handle = dlopen(req->libjvm_path, RTLD_LAZY | RTLD_GLOBAL);
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

    /* 2) JRE 原生库就位（libjava 等依赖 libjvm.so，靠上面的全局加载满足） */
    prepare_linker_path(req);
    char lib_dir[4096], server_dir[4096];
    snprintf(lib_dir, sizeof(lib_dir), "%s/lib", req->java_home);
    snprintf(server_dir, sizeof(server_dir), "%s/lib/server", req->java_home);
    preload_dir(lib_dir);
    preload_dir(server_dir);

    /* 3) 创建 VM；期间 fd1/fd2 落盘，避免初始化失败时原因丢失 */
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

    char note[4096];
    snprintf(note, sizeof(note), "creating JVM: main=%s opts=%d args=%d java_home=%s libjvm=%s",
             req->main_class, req->jvm_opt_count, req->arg_count, req->java_home,
             req->libjvm_path);
    init_capture cap;
    capture_begin(req->init_log_path, note, &cap);

    LOGI("creating JVM: main=%s opts=%d args=%d",
         req->main_class, req->jvm_opt_count, req->arg_count);
    jint rc = create_vm(&vm, (void **) &env, &vm_args);
    capture_end(&cap);
    free(options);
    if (rc != JNI_OK || env == NULL) {
        LOGE("JNI_CreateJavaVM failed: rc=%d env=%p", rc, env);
        /* 给 Kotlin 侧日志泵留出排空管道的时间 */
        usleep(500 * 1000);
        fail(req, -102);
        return NULL;
    }

    char *main_name = to_internal_name(req->main_class);
    jclass main_cls = (*env)->FindClass(env, main_name != NULL ? main_name : req->main_class);
    if (main_cls == NULL) {
        LOGE("FindClass(%s) failed", req->main_class);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionDescribe(env);
        free(main_name);
        fail(req, -103);
        return NULL;
    }
    jmethodID main_method = (*env)->GetStaticMethodID(
            env, main_cls, "main", "([Ljava/lang/String;)V");
    free(main_name);
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
        (*env)->ExceptionClear(env);
    }
    LOGI("JVM main returned (exception=%d)", exception);

    /* 是否等非守护线程结束由调用方决定：
     *  - 服务器（true）：vanilla 的 bundler 把 net.minecraft.server.Main 放在独立线程
     *    "ServerMain" 里跑，自己的 main 立刻返回；直接收尾会把刚起来的服务器杀掉。
     *    DestroyJavaVM 等到所有非守护线程结束才返回，那时才是真正的"服务器已停止"。
     *  - 安装器等跑完即止的工具（false）：它们的 main 返回就代表工作完成，但常残留
     *    停住不动的非守护线程（实测 Forge 安装器留下 Thread-0），DestroyJavaVM 会
     *    永远等下去，进程不退、App 收不到结束事件。
     * 注意：DestroyJavaVM 返回后 env/vm 均已失效，不能再碰 Java 对象。 */
    if (req->wait_for_threads) {
        if ((*vm)->DestroyJavaVM(vm) != JNI_OK) {
            LOGI("DestroyJavaVM returned non-OK");
        }
        LOGI("JVM destroyed, exiting");
    } else {
        LOGI("not waiting for non-daemon threads (one-shot main), exiting");
    }

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
        jstring libjvmPath, jstring javaHome, jstring mainClass, jstring initLogPath,
        jobjectArray jvmOpts, jobjectArray args, jboolean waitForThreads) {
    (void) clazz;
    JavaVM *art_vm = NULL;
    if ((*env)->GetJavaVM(env, &art_vm) != JNI_OK || art_vm == NULL) {
        return -1;
    }
    g_art_vm = art_vm;

    /* 缓存回调类与方法（调用线程有应用 classloader 上下文） */
    jclass cb_cls = (*env)->FindClass(env, "com/mcmobile/server/core/launch/JvmLauncherCallback");
    if (cb_cls == NULL) {
        LOGE("JvmLauncherCallback not found at nativeStart");
        (*env)->ExceptionClear(env);
        return -3;
    }
    if (g_callback_class != NULL) {
        (*env)->DeleteGlobalRef(env, g_callback_class);
    }
    g_callback_class = (*env)->NewGlobalRef(env, cb_cls);
    g_callback_method = (*env)->GetStaticMethodID(
            env, cb_cls, "onJvmExited", "(IZ)V");
    (*env)->DeleteLocalRef(env, cb_cls);
    if (g_callback_method == NULL) {
        LOGE("onJvmExited not found at nativeStart");
        (*env)->ExceptionClear(env);
        return -3;
    }

    launch_req *req = calloc(1, sizeof(launch_req));
    req->art_vm = art_vm;
    req->libjvm_path = jstrdup(env, libjvmPath);
    req->java_home = jstrdup(env, javaHome);
    req->main_class = jstrdup(env, mainClass);
    req->init_log_path = jstrdup(env, initLogPath);
    req->jvm_opts = jstrarray(env, jvmOpts, &req->jvm_opt_count);
    req->args = jstrarray(env, args, &req->arg_count);
    req->wait_for_threads = waitForThreads;

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
