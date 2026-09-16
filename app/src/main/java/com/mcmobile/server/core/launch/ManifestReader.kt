package com.mcmobile.server.core.launch

import java.io.File
import java.util.jar.JarFile

/** 读取 jar Manifest 的 Main-Class */
object ManifestReader {

    fun readMainClass(jar: File): String? = runCatching {
        JarFile(jar).use { jf ->
            jf.manifest?.mainAttributes?.getValue("Main-Class")
        }
    }.getOrNull()
}
