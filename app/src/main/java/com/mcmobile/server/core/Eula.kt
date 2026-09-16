package com.mcmobile.server.core

import java.io.File

/** Mojang EULA 处理 */
object Eula {

    fun isAccepted(instanceDir: File): Boolean {
        val f = File(instanceDir, "eula.txt")
        if (!f.exists()) return false
        return f.readLines().any {
            it.trim().startsWith("eula=", ignoreCase = true) &&
                    it.substringAfter('=').trim().equals("true", ignoreCase = true)
        }
    }

    fun accept(instanceDir: File) {
        File(instanceDir, "eula.txt").writeText(
            "# 由 MC Mobile Server 写入：用户已同意 Mojang EULA\n" +
                    "# https://account.mojang.com/documents/minecraft_eula\n" +
                    "eula=true\n" +
                    "acceptedAt=${System.currentTimeMillis()}\n",
        )
    }
}
