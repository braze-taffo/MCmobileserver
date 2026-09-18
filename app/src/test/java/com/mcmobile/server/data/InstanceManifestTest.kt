package com.mcmobile.server.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstanceManifestTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun instance(id: String) = ServerInstance(
        id = id,
        name = "实例 $id",
        type = ServerType.PAPER,
        mcVersion = "26.2",
        dirName = "paper-26.2-$id",
        javaMajor = 25,
        createdMs = 1L,
    )

    @Test
    fun encodeDecodeRoundTrip() {
        val list = listOf(instance("a"), instance("b"))
        val decoded = InstanceManifest.decode(InstanceManifest.encode(list))
        assertEquals(list, decoded)
    }

    @Test
    fun corruptJsonDecodesToNullInsteadOfEmptyList() {
        // null 与空列表必须区分开：空列表会让上层以为"没有实例"而覆盖掉真实清单
        assertNull(InstanceManifest.decode("{ this is not json"))
        assertEquals(emptyList<ServerInstance>(), InstanceManifest.decode("[]"))
    }

    @Test
    fun writeAtomicallyReplacesExistingFile() {
        val target = File(tmp.root, "instances.json")
        target.writeText("old content that must not survive")
        InstanceManifest.writeAtomically(target, InstanceManifest.encode(listOf(instance("a"))))
        assertEquals(1, InstanceManifest.decode(target.readText())!!.size)
    }

    @Test
    fun writeAtomicallyCreatesMissingParent() {
        val target = File(tmp.root, "nested/dir/instances.json")
        InstanceManifest.writeAtomically(target, "[]")
        assertTrue(target.exists())
    }

    @Test
    fun oldManifestWithoutStorageFieldsLoadsAsInternal() {
        // 升级前的清单没有 storage/externalRoot：必须原样读成内部实例，
        // 否则老用户一升级实例就"跑到别处去了"
        val legacy = """
            [{"id":"a","name":"老实例","type":"PAPER","mcVersion":"26.2",
              "dirName":"paper-26.2-a","maxHeapMb":1024,"javaMajor":25,"createdMs":1}]
        """.trimIndent()
        val decoded = InstanceManifest.decode(legacy)
        assertEquals(1, decoded!!.size)
        assertEquals(StorageKind.INTERNAL, decoded[0].storage)
        assertNull(decoded[0].externalRoot)
    }

    @Test
    fun externalInstanceRoundTripsThroughManifest() {
        val external = instance("ext").copy(
            storage = StorageKind.EXTERNAL,
            externalRoot = "/storage/emulated/0/Documents/MCServers",
        )
        val decoded = InstanceManifest.decode(InstanceManifest.encode(listOf(external)))
        assertEquals(external, decoded!![0])
    }

    @Test
    fun backupCorruptMovesFileAsideAndKeepsContent() {
        val target = File(tmp.root, "instances.json")
        target.writeText("broken but precious")
        val backup = InstanceManifest.backupCorrupt(target, stamp = 42L)
        assertNotNull(backup)
        assertEquals("instances.json.corrupt-42", backup!!.name)
        assertEquals("broken but precious", backup.readText())
        assertTrue(!target.exists())
    }
}
