package com.mcmobile.server.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstanceMarkerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun instance(id: String) = ServerInstance(
        id = id,
        name = "我的世界 $id",
        type = ServerType.PAPER,
        mcVersion = "26.2",
        dirName = "paper-26.2-$id",
        storage = StorageKind.EXTERNAL,
        externalRoot = tmp.root.absolutePath,
        javaMajor = 25,
        createdMs = 7L,
    )

    @Test
    fun writeThenReadRoundTrip() {
        val dir = File(tmp.root, "paper-26.2-a").apply { mkdirs() }
        assertTrue(InstanceMarkers.write(dir, instance("a")))

        val marker = InstanceMarkers.read(dir)
        assertNotNull(marker)
        assertEquals("a", marker!!.id)
        assertEquals("我的世界 a", marker.name)
        assertEquals(ServerType.PAPER, marker.type)
        assertEquals("26.2", marker.mcVersion)
        assertEquals(7L, marker.createdMs)
        assertEquals(InstanceMarker.CURRENT_FORMAT, marker.format)
    }

    @Test
    fun readReturnsNullWhenMarkerMissing() {
        assertNull(InstanceMarkers.read(tmp.root))
    }

    @Test
    fun readReturnsNullForCorruptMarker() {
        val dir = File(tmp.root, "broken").apply { mkdirs() }
        InstanceMarkers.fileOf(dir).writeText("{ not json")
        assertNull(InstanceMarkers.read(dir))
        assertTrue(InstanceMarkers.fileOf(dir).isFile)
    }

    @Test
    fun writeFailureIsReportedButDoesNotThrow() {
        // 目录不存在时写失败必须只是返回 false：外部目录被拔卡不该让创建实例整体失败
        val missing = File(tmp.root, "no-such-dir")
        assertFalse(InstanceMarkers.write(missing, instance("a")))
    }

    @Test
    fun unknownFieldsAreIgnored() {
        // 未来版本可能往标记里加字段，老版本读到必须还能认出实例
        val dir = File(tmp.root, "future").apply { mkdirs() }
        InstanceMarkers.fileOf(dir).writeText(
            """{"format":1,"id":"x","name":"n","type":"VANILLA","mcVersion":"1.21.1",""" +
                    """"createdMs":1,"somethingNew":true}""",
        )
        assertEquals("x", InstanceMarkers.read(dir)?.id)
    }
}
