cmake_minimum_required(VERSION 3.22.1)
project("jvmlauncher" C)

add_library(jvmlauncher SHARED jni_launcher.c)

target_link_libraries(jvmlauncher log dl)
