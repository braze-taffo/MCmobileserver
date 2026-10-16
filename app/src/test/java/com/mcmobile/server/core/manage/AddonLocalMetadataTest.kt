package com.mcmobile.server.core.manage

import com.mcmobile.server.data.ServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AddonLocalMetadataTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun fabricUtf8NameAndDescription() {
        val addon = localFabric("utf8.jar", fabricJson().toByteArray(StandardCharsets.UTF_8))
        assertEquals("中文测试模组", addon.name)
        assertEquals("这是一段中文描述，用于复现乱码。", addon.description)
        assertEquals("zh-test-mod", addon.id)
        assertEquals("1.0.0", addon.version)
        assertTrue(addon.enabled)
    }

    @Test fun fabricUtf8BomNameAndDescription() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val addon = localFabric("utf8-bom.jar", bom + fabricJson().toByteArray(StandardCharsets.UTF_8))
        assertEquals("中文测试模组", addon.name)
        assertEquals("这是一段中文描述，用于复现乱码。", addon.description)
        assertEquals("zh-test-mod", addon.id)
    }

    @Test fun fabricGbkNameAndDescription() {
        val addon = localFabric("gbk.jar", fabricJson().toByteArray(Charset.forName("GBK")))
        assertEquals("中文测试模组", addon.name)
        assertEquals("这是一段中文描述，用于复现乱码。", addon.description)
        assertEquals("zh-test-mod", addon.id)
    }

    @Test fun fabricAsciiNameAndDescription() {
        val json = """{"schemaVersion":1,"id":"ascii-mod","version":"0.1.0","name":"ASCII Mod","description":"An ASCII description"}"""
        val addon = localFabric("ascii.jar", json.toByteArray(StandardCharsets.US_ASCII))
        assertEquals("ASCII Mod", addon.name)
        assertEquals("An ASCII description", addon.description)
        assertEquals("ascii-mod", addon.id)
        assertEquals("0.1.0", addon.version)
    }

    @Test fun quiltJsonIsReadWhenFabricIsAbsent() {
        val json = """{"schema_version":1,"quilt_loader":{"id":"quilt-zh","version":"2.0.0","metadata":{"name":"Quilt中文","description":"Quilt描述"}}}"""
        val addon = localJar("quilt-zh.jar", mapOf("quilt.mod.json" to json.toByteArray(StandardCharsets.UTF_8)), ServerType.FABRIC)
        assertEquals("Quilt中文", addon.name)
        assertEquals("Quilt描述", addon.description)
        assertEquals("quilt-zh", addon.id)
        assertEquals("2.0.0", addon.version)
    }

    @Test fun forgeTomlGbkDisplayNameAndDescription() {
        val toml = """
            modLoader="javafml"
            [[mods]]
            modId="forge-zh"
            version="3.0.0"
            displayName="Forge中文"
            description="Forge描述"
        """.trimIndent()
        val addon = localJar(
            "forge-zh.jar",
            mapOf("META-INF/mods.toml" to toml.toByteArray(Charset.forName("GBK"))),
            ServerType.FORGE,
        )
        assertEquals("Forge中文", addon.name)
        assertEquals("Forge描述", addon.description)
        assertEquals("forge-zh", addon.id)
        assertEquals("3.0.0", addon.version)
    }

    @Test fun missingDescriptionStaysNull() {
        val json = """{"schemaVersion":1,"id":"plain","version":"1","name":"Plain"}"""
        val addon = localFabric("plain.jar", json.toByteArray(StandardCharsets.UTF_8))
        assertEquals("Plain", addon.name)
        assertNull(addon.description)
    }

    private fun fabricJson(): String =
        """{"schemaVersion":1,"id":"zh-test-mod","version":"1.0.0","name":"中文测试模组","description":"这是一段中文描述，用于复现乱码。"}"""

    private fun localFabric(fileName: String, fabricModJson: ByteArray): Addons.Local =
        localJar(fileName, mapOf("fabric.mod.json" to fabricModJson), ServerType.FABRIC)

    private fun localJar(fileName: String, entries: Map<String, ByteArray>, type: ServerType): Addons.Local {
        val root = temp.newFolder()
        val dir = File(root, Addons.directory(type)).apply { mkdirs() }
        writeZip(File(dir, fileName), entries)
        val addons = Addons.local(root, type)
        assertEquals(listOf(fileName), addons.map { it.file.name })
        return addons.single()
    }

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
