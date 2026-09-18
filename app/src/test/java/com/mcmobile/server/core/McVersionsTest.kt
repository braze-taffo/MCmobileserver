package com.mcmobile.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McVersionsTest {

    @Test
    fun compare_old_vs_new() {
        assertTrue(McVersions.compare("1.21.1", "1.20.6") > 0)
        assertTrue(McVersions.compare("26.3", "1.21.1") > 0)
        assertTrue(McVersions.compare("1.20.5", "1.20.4") > 0)
        assertEquals(0, McVersions.compare("1.21.1", "1.21.1"))
    }

    @Test
    fun requiredJava_byEra() {
        assertEquals(8, McVersions.requiredJava("1.16.5"))
        assertEquals(16, McVersions.requiredJava("1.17"))
        assertEquals(16, McVersions.requiredJava("1.17.1"))
        assertEquals(17, McVersions.requiredJava("1.18.2"))
        assertEquals(17, McVersions.requiredJava("1.20.4"))
        assertEquals(21, McVersions.requiredJava("1.20.5"))
        assertEquals(21, McVersions.requiredJava("1.20.6"))
        assertEquals(21, McVersions.requiredJava("1.21.1"))
        assertEquals(21, McVersions.requiredJava("1.21.11"))
        assertEquals(25, McVersions.requiredJava("26.1"))
        assertEquals(25, McVersions.requiredJava("26.3"))
    }

    @Test
    fun bundledRuntime_selection() {
        assertEquals(21, McVersions.bundledJavaMajor("1.17"))
        assertEquals(21, McVersions.bundledJavaMajor("1.21.1"))
        assertEquals(25, McVersions.bundledJavaMajor("26.3"))
    }

    @Test
    fun bundledRuntime_fromAuthoritativeJava() {
        // 核心 jar 的 version.json 里声明的 Java 要求优先于版本号启发式
        assertEquals(21, McVersions.bundledJavaMajorFor(17))
        assertEquals(21, McVersions.bundledJavaMajorFor(21))
        assertEquals(25, McVersions.bundledJavaMajorFor(25))
    }

    @Test
    fun neoforgeCoreVersion_toMcVersion() {
        assertEquals("1.21.1", McVersions.mcVersionOfNeoForge("21.1.219"))
        assertEquals("1.20.2", McVersions.mcVersionOfNeoForge("20.2.86"))
        assertEquals("26.2", McVersions.mcVersionOfNeoForge("26.2.0.88"))
        assertNull(McVersions.mcVersionOfNeoForge("0.25w14craftmine.3-beta"))
        assertNull(McVersions.mcVersionOfNeoForge("neoforge"))
    }

    @Test
    fun rcSuffix_keepsJavaRequirementButLosesPatchWhenComparing() {
        // parse 逐段 toIntOrNull，"-rc-3" 段解析失败按 0 处理：Java 要求仍然正确。
        // 比较会把 26.3-rc-3 当成 26.0，故列表必须先按 isRelease 过滤（已在用例中锁定）。
        assertEquals(25, McVersions.requiredJava("26.3-rc-3"))
        assertTrue(McVersions.compare("26.3-rc-3", "26.2") < 0)
    }

    @Test
    fun supportWindow() {
        assertTrue(McVersions.isSupported("1.17"))
        assertTrue(McVersions.isSupported("1.21.1"))
        assertTrue(McVersions.isSupported("26.3"))
        assertFalse(McVersions.isSupported("1.16.5"))
        assertFalse(McVersions.isSupported("1.12.2"))
    }

    @Test
    fun releaseFilter() {
        assertTrue(McVersions.isRelease("1.21.1"))
        assertTrue(McVersions.isRelease("26.2"))
        assertFalse(McVersions.isRelease("1.21.2-rc1"))
        assertFalse(McVersions.isRelease("26.3-snapshot"))
        assertFalse(McVersions.isRelease("26.2-rc-2"))
        assertFalse(McVersions.isRelease("1.21.11-pre5"))
        assertFalse(McVersions.isRelease("25w14craftmine"))
        assertFalse(McVersions.isRelease("25w14a"))
    }
}
