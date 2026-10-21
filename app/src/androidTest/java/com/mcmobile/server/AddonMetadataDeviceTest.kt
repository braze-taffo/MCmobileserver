package com.mcmobile.server

import androidx.test.platform.app.InstrumentationRegistry
import com.mcmobile.server.core.manage.Addons
import com.mcmobile.server.core.manage.ModMetadataText
import com.mcmobile.server.data.InstanceManifest
import com.mcmobile.server.data.ServerInstance
import com.mcmobile.server.data.ServerType
import junit.framework.TestCase
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Local metadata regressions on Android ART; no network or server startup required. */
class AddonMetadataDeviceTest : TestCase() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun writeJar(root: File, type: ServerType, entries: Map<String, ByteArray>) {
        val dir = File(root, Addons.directory(type)).apply { mkdirs() }
        ZipOutputStream(File(dir, "metadata-fixture.jar").outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun checkJar(type: ServerType, entries: Map<String, ByteArray>, check: (Addons.Local) -> Unit) {
        val root = File(context.cacheDir, "metadata-device-${System.nanoTime()}").apply { mkdirs() }
        try {
            writeJar(root, type, entries)
            check(Addons.local(root, type).single())
        } finally {
            root.deleteRecursively()
        }
    }

    fun testForgeAndNeoForgeMultilineDescriptions() {
        listOf("META-INF/mods.toml" to ServerType.FORGE, "META-INF/neoforge.mods.toml" to ServerType.NEOFORGE).forEach { (path, type) ->
            val toml = "[[mods]]\nmodId='chinese'\nversion='1.2'\ndisplayName='中文模组'\ndescription='''\n第一行：中文描述\n第二行：包含 '引号'\n'''\n"
            checkJar(type, mapOf(path to toml.toByteArray())) { addon ->
                assertEquals("chinese", addon.id)
                assertEquals("中文模组", addon.name)
                assertEquals("1.2", addon.version)
                assertEquals("第一行：中文描述\n第二行：包含 '引号'\n", addon.description)
            }
        }
    }

    fun testAndroidCharsetFallback() {
        val chinese = """{"name":"中文测试模组","description":"中文描述"}"""
        listOf("UTF-8", "GBK", "GB18030").forEach { encoding ->
            assertEquals(chinese, ModMetadataText.decode(chinese.toByteArray(Charset.forName(encoding))))
        }
        val extended = """{"name":"中文𠀀模组"}"""
        assertEquals(extended, ModMetadataText.decode(extended.toByteArray(Charset.forName("GB18030"))))
        listOf("ISO-8859-1", "windows-1252").forEach { encoding ->
            val bytes = """{"name":"für schöne Größe"}""".toByteArray(Charset.forName(encoding))
            assertEquals(String(bytes, Charsets.UTF_8), ModMetadataText.decode(bytes))
        }
    }

    fun testPluginDescriptionIsTopLevel() {
        listOf("plugin.yml", "paper-plugin.yml").forEach { path ->
            val yaml = "name: 中文插件\nversion: '2.0'\ncommands:\n  help:\n    description: 命令帮助不应显示\n"
            checkJar(ServerType.PAPER, mapOf(path to yaml.toByteArray())) { assertNull(it.description) }
            checkJar(ServerType.PAPER, mapOf(path to (yaml + "description: 顶层插件说明\n").toByteArray())) {
                assertEquals("顶层插件说明", it.description)
            }
        }
    }

    fun testBadQuiltContinuesToOtherDescriptors() {
        listOf("{broken", "[]", "null", "{}").forEach { quilt ->
            val invalid = mapOf("quilt.mod.json" to quilt.toByteArray())
            val toml = "[[mods]]\nmodId='fallback'\nversion='3.0'\ndescription='中文回退描述'\n"
            checkJar(ServerType.FORGE, invalid + ("META-INF/mods.toml" to toml.toByteArray())) {
                assertEquals("fallback", it.id)
                assertEquals("3.0", it.version)
                assertEquals("中文回退描述", it.description)
            }
            val yaml = "name: 中文插件\nversion: '4.0'\ndescription: 插件回退描述\n"
            checkJar(ServerType.PAPER, invalid + ("plugin.yml" to yaml.toByteArray())) {
                assertEquals("中文插件", it.id)
                assertEquals("4.0", it.version)
                assertEquals("插件回退描述", it.description)
            }
            checkJar(ServerType.FORGE, invalid + ("META-INF/MANIFEST.MF" to
                "Manifest-Version: 1.0\r\nImplementation-Version: 5.0\r\n\r\n".toByteArray())) {
                assertEquals("5.0", it.version)
            }
        }
    }

    /** Explicit opt-in leaves four isolated instances for checking the real installed-addon UI. */
    fun testPrepareUiFixturesWhenRequested() {
        if (InstrumentationRegistry.getArguments().getString("prepareUi") != "true") return
        val fixtures = listOf(
            Triple(ServerType.FABRIC, "Fabric中文显示检查", mapOf("fabric.mod.json" to
                """{"id":"fixture-gbk","version":"1.0","name":"中文测试模组","description":"中文名称与描述正常显示"}""".toByteArray(Charset.forName("GBK")))),
            Triple(ServerType.FORGE, "Forge多行描述检查", mapOf("quilt.mod.json" to "{broken".toByteArray(),
                "META-INF/mods.toml" to "[[mods]]\nmodId='fixture-forge'\nversion='1.0'\ndisplayName='Forge中文模组'\ndescription='''\n第一行：Forge中文描述\n第二行：元数据回退正常\n'''\n".toByteArray())),
            Triple(ServerType.NEOFORGE, "NeoForge多行描述检查", mapOf("META-INF/neoforge.mods.toml" to
                "[[mods]]\nmodId='fixture-neoforge'\nversion='1.0'\ndisplayName='NeoForge中文模组'\ndescription='''\n第一行：NeoForge中文描述\n第二行：多行读取正常\n'''\n".toByteArray())),
            Triple(ServerType.PAPER, "Paper插件描述检查", mapOf("quilt.mod.json" to "[]".toByteArray(),
                "plugin.yml" to "name: 中文插件\nversion: '1.0'\ncommands:\n  help:\n    description: 命令帮助不应显示\ndescription: 顶层插件说明\n".toByteArray())),
        )
        val manifest = File(context.filesDir, "instances.json")
        val existing = if (manifest.exists()) InstanceManifest.decode(manifest.readText())
            ?: error("Existing instance manifest is invalid; refusing to overwrite") else emptyList()
        val instances = fixtures.map { (type, name, entries) ->
            val key = "pr1-metadata-${type.name.lowercase()}"
            val root = File(context.filesDir, "servers/$key").apply { mkdirs() }
            writeJar(root, type, entries)
            ServerInstance(key, name, type, "1.21.1", dirName = key, createdMs = System.currentTimeMillis())
        }
        InstanceManifest.writeAtomically(manifest, InstanceManifest.encode(existing.filterNot { old -> instances.any { it.id == old.id } } + instances))
        assertEquals(4, instances.size)
    }
}
