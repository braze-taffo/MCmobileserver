package com.mcmobile.server.core.launch

import com.mcmobile.server.data.ServerInstance
import com.mcmobile.server.data.ServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

class LaunchSpecBuilderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun serverJar(dir: File, mainClass: String) {
        JarOutputStream(File(dir, "server.jar").outputStream()).use { jos ->
            jos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            jos.write("Manifest-Version: 1.0\r\nMain-Class: $mainClass\r\n\r\n".toByteArray())
            jos.closeEntry()
        }
    }

    private fun instance(type: ServerType, mcVersion: String, javaMajor: Int) = ServerInstance(
        id = "test",
        name = "test",
        type = type,
        mcVersion = mcVersion,
        dirName = "test",
        javaMajor = javaMajor,
        createdMs = 0L,
    )

    private fun build(type: ServerType, mcVersion: String, javaMajor: Int): LaunchSpec? {
        val dir = tmp.newFolder()
        serverJar(dir, "io.papermc.paperclip.Main")
        val home = File(tmp.root, "jre/arm64-v8a/$javaMajor").apply { mkdirs() }
        return LaunchSpecBuilder.build(instance(type, mcVersion, javaMajor), dir, home).spec
    }

    @Test
    fun javaMajorReachesLaunchSpec() {
        // 落到 LaunchSpec 的默认值 21 会让 26.x 在 javaHome 指向 25 时仍加载 21 的 libjvm
        val spec = build(ServerType.PAPER, "26.2", 25)
        assertNotNull(spec)
        assertEquals(25, spec!!.javaMajor)
        assertEquals("25", File(spec.javaHome).name)
    }

    @Test
    fun java21InstanceKeepsItsRuntime() {
        val spec = build(ServerType.PAPER, "1.21.1", 21)
        assertEquals(21, spec!!.javaMajor)
        assertEquals("21", File(spec.javaHome).name)
    }

    @Test
    fun foliaUsesTheSameJarLaunchPathAsPaper() {
        val spec = build(ServerType.FOLIA, "26.2", 25)
        assertNotNull(spec)
        assertEquals("io.papermc.paperclip.Main", spec!!.mainClass)
        assertEquals(listOf("nogui"), spec.args)
        assertEquals(25, spec.javaMajor)
    }

    @Test
    fun paperAndFoliaDisableTheOfficialJavaVersionCheck() {
        // 内置 JVM 的 java.version 是 "25.0.5-internal"，不关掉这个校验服务器会直接退出
        assertTrue(build(ServerType.PAPER, "26.2", 25)!!.jvmOpts.contains("-DPaper.IgnoreJavaVersion=true"))
        assertTrue(build(ServerType.FOLIA, "26.2", 25)!!.jvmOpts.contains("-DPaper.IgnoreJavaVersion=true"))
        // 其余核心不该带这个 Paper 专有属性
        assertFalse(build(ServerType.VANILLA, "1.21.1", 21)!!.jvmOpts.contains("-DPaper.IgnoreJavaVersion=true"))
        assertFalse(build(ServerType.FABRIC, "1.21.1", 21)!!.jvmOpts.contains("-DPaper.IgnoreJavaVersion=true"))
    }

    @Test
    fun instanceJvmArgsCanOverrideCoreDefaults() {
        // 实例自定义参数排在核心默认值之后，所以玩家仍可自行关回该属性
        val dir = tmp.newFolder()
        serverJar(dir, "io.papermc.paperclip.Main")
        val home = File(tmp.root, "jre/arm64-v8a/25").apply { mkdirs() }
        val inst = instance(ServerType.PAPER, "26.2", 25)
            .copy(extraJvmArgs = listOf("-DPaper.IgnoreJavaVersion=false"))
        val opts = LaunchSpecBuilder.build(inst, dir, home).spec!!.jvmOpts
        assertTrue(opts.indexOf("-DPaper.IgnoreJavaVersion=false") > opts.indexOf("-DPaper.IgnoreJavaVersion=true"))
    }

    @Test
    fun missingCoreJarReportsError() {
        val dir = tmp.newFolder()
        val home = File(tmp.root, "jre/arm64-v8a/21").apply { mkdirs() }
        val result = LaunchSpecBuilder.build(instance(ServerType.PAPER, "1.21.1", 21), dir, home)
        assertEquals(null, result.spec)
        assertNotNull(result.error)
    }
}
