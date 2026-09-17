# jni_launcher.c exports Java_com_mcmobile_server_core_launch_JvmLauncher_*.
-keep class com.mcmobile.server.core.launch.JvmLauncher {
    public static final com.mcmobile.server.core.launch.JvmLauncher INSTANCE;
    native <methods>;
}

# Called only by native FindClass/GetStaticMethodID; R8 cannot see this edge.
-keep class com.mcmobile.server.core.launch.JvmLauncherCallback {
    public static void onJvmExited(int, boolean);
}

# kotlinx.serialization supplies its own consumer rules for generated serializers.
# Do not keep the whole application or disable optimization/obfuscation globally.
