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

    @Test fun forgeAndNeoForgeMultilineLiteralDescriptions() {
        listOf("META-INF/mods.toml" to ServerType.FORGE, "META-INF/neoforge.mods.toml" to ServerType.NEOFORGE).forEach { (path, type) ->
            val toml = "[[mods]]\r\nmodId='multiline'\r\nversion='1.2'\r\ndisplayName='中文模组'\r\n" +
                "description = '''\r\n第一行包含 '单引号' 和 \"双引号\"。\r\n第二行保留 \\n 字面量。\r\n''' # comment\r\n" +
                "[[dependencies.multiline]]\r\nmodId='minecraft'"
            val addon = localJar("literal.jar", mapOf(path to toml.toByteArray()), type)
            assertEquals("第一行包含 '单引号' 和 \"双引号\"。\n第二行保留 \\n 字面量。\n", addon.description)
            assertEquals("multiline", addon.id)
            assertEquals("中文模组", addon.name)
            assertEquals("1.2", addon.version)
        }
    }

    @Test fun forgeMultilineBasicDescriptionDecodesEscapesAndContinuation() {
        val toml = "[[mods]]\nmodId='basic'\ndescription = \"\"\"\n中文 \\" +
            "\n    续行\\n\\\"引号\\\"，Unicode: \\u4E2D\\U00020000\n\"\"\"\n"
        val addon = localJar("basic.jar", mapOf("META-INF/mods.toml" to toml.toByteArray()), ServerType.FORGE)
        assertEquals("中文 续行\n\"引号\"，Unicode: 中𠀀\n", addon.description)
    }

    @Test fun forgeSingleLineQuotedDescriptionKeepsApostrophesAndIgnoresComments() {
        val toml = "[[mods]]\nmodId='single'\ndescription = \"It's a \\\"中文\\\" mod #1\" # outside comment"
        val addon = localJar("single.jar", mapOf("META-INF/mods.toml" to toml.toByteArray()), ServerType.FORGE)
        assertEquals("It's a \"中文\" mod #1", addon.description)
    }

    @Test fun pluginCommandDescriptionIsNotUsedAsPluginDescription() {
        listOf("plugin.yml", "paper-plugin.yml").forEach { path ->
            val yaml = "name: Example\nversion: '2.1'\ncommands:\n  help:\n    description: 命令帮助\n"
            val addon = localJar("commands.jar", mapOf(path to yaml.toByteArray()), ServerType.PAPER)
            assertEquals("Example", addon.name)
            assertEquals("2.1", addon.version)
            assertNull(addon.description)
        }
    }

    @Test fun pluginTopLevelDescriptionWinsEvenAfterCommands() {
        val yaml = "name: Example\ncommands:\n  help:\n    description: 命令帮助\ndescription: 插件说明\n"
        val addon = localJar("plugin.jar", mapOf("plugin.yml" to yaml.toByteArray()), ServerType.PAPER)
        assertEquals("插件说明", addon.description)
    }

    @Test fun unusableQuiltMetadataDoesNotBlockTomlYamlOrManifest() {
        listOf("{broken", "[]", "null", "{}", "{\"quilt_loader\":[]}", "{\"quilt_loader\":{}}").forEach { quilt ->
            val invalid = mapOf("quilt.mod.json" to quilt.toByteArray())
            listOf("META-INF/mods.toml" to ServerType.FORGE, "META-INF/neoforge.mods.toml" to ServerType.NEOFORGE).forEach { (path, type) ->
                val toml = "[[mods]]\nmodId='fallback'\nversion='3.1'\ndisplayName='中文模组'\ndescription='模组说明'\n"
                val addon = localJar("fallback.jar", invalid + (path to toml.toByteArray()), type)
                assertEquals("fallback", addon.id)
                assertEquals("中文模组", addon.name)
                assertEquals("3.1", addon.version)
                assertEquals("模组说明", addon.description)
            }
            val yaml = "name: 中文插件\nversion: '4.2'\ndescription: 插件说明\n"
            val plugin = localJar("plugin.jar", invalid + ("plugin.yml" to yaml.toByteArray()), ServerType.PAPER)
            assertEquals("中文插件", plugin.id)
            assertEquals("中文插件", plugin.name)
            assertEquals("4.2", plugin.version)
            assertEquals("插件说明", plugin.description)

            val manifest = "Manifest-Version: 1.0\r\nImplementation-Version: 5.3\r\n\r\n"
            val plain = localJar("plain.jar", invalid + ("META-INF/MANIFEST.MF" to manifest.toByteArray()), ServerType.FORGE)
            assertEquals("plain", plain.name)
            assertEquals("5.3", plain.version)
            assertNull(plain.description)
        }
    }

    @Test fun fabricMetadataStillTakesPrecedenceOverInvalidQuilt() {
        val addon = localJar("fabric.jar", mapOf(
            "fabric.mod.json" to fabricJson().toByteArray(),
            "quilt.mod.json" to "{broken".toByteArray(),
        ), ServerType.FABRIC)
        assertEquals("中文测试模组", addon.name)
        assertEquals("zh-test-mod", addon.id)
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
