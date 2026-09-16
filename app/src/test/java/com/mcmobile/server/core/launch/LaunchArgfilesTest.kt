package com.mcmobile.server.core.launch

import org.junit.Assert.assertEquals
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
        assertEquals(5, parsed.jvmArgs.size)
        assertTrue(parsed.jvmArgs.any { it.startsWith("-DignoreList=") })
        assertTrue(parsed.jvmArgs.none { it.contains("Xmx") })
    }

    @Test(expected = IllegalStateException::class)
    fun parse_throws_when_not_installed() {
        LaunchArgfiles.parse(tmp.newFolder("empty"))
    }
}
