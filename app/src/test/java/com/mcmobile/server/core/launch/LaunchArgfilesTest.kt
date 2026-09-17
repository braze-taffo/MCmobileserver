package com.mcmobile.server.core.launch

import org.junit.Assert.assertEquals
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LaunchArgfilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun tokenize_handlesQuotesAndWhitespace() {
        assertEquals(
            listOf("-cp", "a.jar:b.jar", "net.example.Main", "--arg", "hello world"),
            LaunchArgfiles.tokenize("-cp\na.jar:b.jar\nnet.example.Main\n--arg\n\"hello world\"\n"),
        )
        assertEquals(listOf("a b"), LaunchArgfiles.tokenize("'a b'"))
    }

    @Test
    fun parse_unix_args_and_user_jvm_args() {
        val dir = tmp.newFolder("inst")
        val libDir = dir.resolve("libraries/net/neoforged/neoforge/21.1.57").apply { mkdirs() }
        libDir.resolve("unix_args.txt").writeText(
            """
            -cp
            libraries/net/neoforged/neoforge/21.1.57/loader-1.0.jar:libraries/net/minecraft/server/1.21.1-20240429/server-1.21.1.jar
            net.neoforged.devlaunch.Main
            --gameDir
            .
            --launchTarget
            forgeserver
            --nogui
            """.trimIndent(),
        )
        dir.resolve("user_jvm_args.txt").writeText(
            """
            # Xmx and Xms will not be used
            # -Xmx4G
            -DignoreList=bootstraplauncher,securejarhandler,asm-commons,asm-util,asm-analysis,asm-tree,asm,JarJarFileSystems,client-extra,fmlcore,javafmllanguage,lowcodelanguage,mclanguage,forge-,neoforge-
            -DmergeModules=jna-5.10.0.jar,jna-platform-5.10.0.jar
            -XX:+IgnoreUnrecognizedVMOptions
            --add-modules=ALL-MODULE-PATH
            -Djava.net.preferIPv6Addresses=system
            """.trimIndent(),
        )

        val parsed = LaunchArgfiles.parse(dir)

        assertEquals(2, parsed.classpathEntries.size)
        assertTrue(parsed.classpathEntries[0].startsWith("libraries/net/neoforged"))
        // 主类
        assertEquals("net.neoforged.devlaunch.Main", parsed.mainClass)
        // 程序参数
        assertEquals(
            listOf("--gameDir", ".", "--launchTarget", "forgeserver", "--nogui"),
            parsed.programArgs,
        )
        // user_jvm_args：去掉注释与空行
        assertEquals(5, parsed.userJvmArgs.size)
        assertTrue(parsed.userJvmArgs.any { it.startsWith("-DignoreList=") })
        assertTrue(parsed.userJvmArgs.none { it.contains("Xmx") })
    }

    /** NeoForge 1.21 的模块路径形态：-p + --add-opens + 主类 */
    @Test
    fun parse_neoforge_module_path_form() {
        val dir = tmp.newFolder("nf")
        val libDir = dir.resolve("libraries/net/neoforged/neoforge/21.1.250").apply { mkdirs() }
        libDir.resolve("unix_args.txt").writeText(
            """
            -p
            libraries/cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar:libraries/cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8.jar
            --add-modules ALL-MODULE-PATH
            --add-opens java.base/java.util.jar=cpw.mods.securejarhandler
            --add-exports java.base/sun.security.util=cpw.mods.securejarhandler
            -DlibraryDirectory=libraries
            -DlegacyClassPath=a.jar:b.jar
            cpw.mods.bootstraplauncher.BootstrapLauncher
            --launchTarget
            forgeserver
            """.trimIndent(),
        )

        val parsed = LaunchArgfiles.parse(dir)

        // -p 的 jar 同时当 classpath（FindClass 只能看 classpath）
        assertEquals(2, parsed.classpathEntries.size)
        assertEquals("cpw.mods.bootstraplauncher.BootstrapLauncher", parsed.mainClass)
        assertEquals(listOf("--launchTarget", "forgeserver"), parsed.programArgs)

        // 模块路径要拼成 VM 认的 `=` 形式，并且是绝对路径（不依赖 cwd）
        // 断言不要按 ':' 拆开再判断——Windows 盘符自带冒号，那个写法只在 Linux 成立
        val expectedModulePath = listOf(
            "libraries/cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar",
            "libraries/cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8.jar",
        ).joinToString(":") { File(dir, it).absolutePath }
        assertEquals(
            "--module-path=$expectedModulePath",
            parsed.unixJvmArgs.single { it.startsWith("--module-path=") },
        )

        // 空格形式的 --add-opens/--add-exports 要合并，并按未具名模块再补一份
        assertTrue("--add-opens=java.base/java.util.jar=cpw.mods.securejarhandler" in parsed.unixJvmArgs)
        assertTrue("--add-opens=java.base/java.util.jar=ALL-UNNAMED" in parsed.unixJvmArgs)
        assertTrue("--add-exports=java.base/sun.security.util=ALL-UNNAMED" in parsed.unixJvmArgs)

        // -D 属性与 --add-modules 原样透传
        assertTrue("--add-modules=ALL-MODULE-PATH" in parsed.unixJvmArgs)
        assertTrue("-DlegacyClassPath=a.jar:b.jar" in parsed.unixJvmArgs)
        assertTrue("-DlibraryDirectory=libraries" in parsed.unixJvmArgs)
        assertTrue(parsed.userJvmArgs.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun parse_throws_when_not_installed() {
        LaunchArgfiles.parse(tmp.newFolder("empty"))
    }
}
