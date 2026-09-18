package com.mcmobile.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * 探测器只依赖 jar 内容：这里用合成 jar 覆盖各家真实字段布局
 * （字段名与位置都取自 2026-09 的现网样本）。
 */
class CoreJarInspectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeJar(name: String, entries: Map<String, String>): File {
        val f = File(tmp.root, name)
        JarOutputStream(f.outputStream()).use { jos ->
            for ((entry, content) in entries) {
                jos.putNextEntry(ZipEntry(entry))
                jos.write(content.toByteArray(Charsets.UTF_8))
                jos.closeEntry()
            }
        }
        return f
    }

    @Test
    fun paperJar_isIdentifiedWithAuthoritativeJava() {
        val jar = makeJar(
            "paper-26.2-124.jar",
            mapOf(
                "version.json" to """{"id":"26.2","name":"26.2","java_version":25,"stable":true}""",
                "META-INF/versions.list" to "16aada2b\t26.2\t26.2/paper-26.2.jar\n",
            ),
        )
        val d = CoreJarInspector.detect(jar)!!
        assertEquals("26.2", d.mcVersion)
        assertEquals(25, d.requiredJava)
        assertEquals(CoreJarInspector.Kind.PAPER, d.kind)
    }

    @Test
    fun foliaJar_isDistinguishedFromPaper() {
        val jar = makeJar(
            "folia-26.2-7.jar",
            mapOf(
                "version.json" to """{"id":"26.2","java_version":25}""",
                "META-INF/versions.list" to "a43ba2b1\t26.2\t26.2/folia-26.2.jar\n",
            ),
        )
        val d = CoreJarInspector.detect(jar)!!
        assertEquals("26.2", d.mcVersion)
        assertEquals(CoreJarInspector.Kind.FOLIA, d.kind)
    }

    @Test
    fun vanillaJar_isIdentified() {
        val jar = makeJar(
            "server.jar",
            mapOf(
                "version.json" to """{"id":"26.3","java_version":25}""",
                "META-INF/versions.list" to "a362163e\t26.3\t26.3/server-26.3.jar\n",
            ),
        )
        val d = CoreJarInspector.detect(jar)!!
        assertEquals("26.3", d.mcVersion)
        assertEquals(CoreJarInspector.Kind.VANILLA, d.kind)
    }

    @Test
    fun fabricLauncher_readsInstallProperties() {
        val jar = makeJar(
            "fabric.jar",
            mapOf("install.properties" to "fabric-loader-version=0.19.5\ngame-version=1.21.1\n"),
        )
        val d = CoreJarInspector.detect(jar)!!
        assertEquals("1.21.1", d.mcVersion)
        assertEquals("0.19.5", d.coreVersion)
        assertEquals(CoreJarInspector.Kind.FABRIC, d.kind)
        assertNull(d.requiredJava)
    }

    @Test
    fun forgeInstaller_doesNotMistakeCombinedIdForMcVersion() {
        // Forge 的 version.json.id 是 "1.21.1-forge-52.0.1"，不能当 MC 版本用
        val jar = makeJar(
            "forge-installer.jar",
            mapOf(
                "version.json" to """{"id":"1.21.1-forge-52.0.1","inheritsFrom":"1.21.1"}""",
                "install_profile.json" to """{"minecraft":"1.21.1","version":"1.21.1-forge-52.0.1"}""",
            ),
        )
        val d = CoreJarInspector.detect(jar)!!
        assertEquals("1.21.1", d.mcVersion)
        assertEquals("52.0.1", d.coreVersion)
        assertEquals(CoreJarInspector.Kind.FORGE, d.kind)
    }

    @Test
    fun neoforgeInstaller_readsProfileVersion() {
        // NeoForge 的 version.json.id 是 "neoforge-26.2.0.88"，里面根本没有 MC 版本
        val jar = makeJar(
            "neoforge-installer.jar",
            mapOf(
                "version.json" to """{"id":"neoforge-26.2.0.88","inheritsFrom":"26.2"}""",
                "install_profile.json" to """{"minecraft":"26.2","version":"neoforge-26.2.0.88"}""",
            ),
        )
        val d = CoreJarInspector.detect(jar)!!
        assertEquals("26.2", d.mcVersion)
        assertEquals("26.2.0.88", d.coreVersion)
        assertEquals(CoreJarInspector.Kind.NEOFORGE, d.kind)
    }

    @Test
    fun installerWithoutProfile_fallsBackToInheritsFrom() {
        val jar = makeJar(
            "neoforge-slim.jar",
            mapOf("version.json" to """{"id":"neoforge-26.2.0.88","inheritsFrom":"26.2"}"""),
        )
        val d = CoreJarInspector.detect(jar)!!
        assertEquals("26.2", d.mcVersion)
        assertEquals(CoreJarInspector.Kind.NEOFORGE, d.kind)
    }

    @Test
    fun jarWithoutVersionInfo_returnsNull() {
        val jar = makeJar(
            "plain.jar",
            mapOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\nMain-Class: a.B\r\n\r\n"),
        )
        assertNull(CoreJarInspector.detect(jar))
    }

    @Test
    fun corruptOrMissingFile_returnsNullInsteadOfThrowing() {
        val broken = File(tmp.root, "broken.jar")
        broken.writeText("this is not a zip at all")
        assertNull(CoreJarInspector.detect(broken))
        assertNull(CoreJarInspector.detect(File(tmp.root, "does-not-exist.jar")))
    }
}
