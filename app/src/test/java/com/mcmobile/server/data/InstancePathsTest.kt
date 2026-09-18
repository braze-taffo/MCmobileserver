package com.mcmobile.server.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstancePathsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun internalInstanceLivesUnderFilesServers() {
        val dir = InstancePaths.resolve(tmp.root, StorageKind.INTERNAL, "paper-26.2-abcd", null)
        assertEquals(File(tmp.root, "servers/paper-26.2-abcd"), dir)
    }

    @Test
    fun internalInstanceIgnoresExternalRoot() {
        // 老实例升级上来时字段可能是空的，也可能带着历史垃圾值；INTERNAL 必须只认内部根目录
        val dir = InstancePaths.resolve(
            tmp.root, StorageKind.INTERNAL, "paper-26.2-abcd", "/storage/emulated/0/Documents",
        )
        assertEquals(File(tmp.root, "servers/paper-26.2-abcd"), dir)
    }

    @Test
    fun externalInstanceLivesUnderChosenRoot() {
        val chosen = File(tmp.root, "Documents/MCServers")
        val dir = InstancePaths.resolve(tmp.root, StorageKind.EXTERNAL, "paper-26.2-abcd", chosen.path)
        assertEquals(File(chosen, "paper-26.2-abcd"), dir)
    }

    @Test
    fun externalInstanceWithoutRootFallsBackToInternalRoot() {
        // 清单被手工改坏时返回一个不存在的路径让上层报错，而不是抛异常把列表带崩
        for (broken in listOf(null, "", "   ")) {
            val dir = InstancePaths.resolve(tmp.root, StorageKind.EXTERNAL, "paper-26.2-abcd", broken)
            assertEquals(File(tmp.root, "servers/paper-26.2-abcd"), dir)
        }
    }
}
