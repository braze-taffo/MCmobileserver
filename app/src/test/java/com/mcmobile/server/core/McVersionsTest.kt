package com.mcmobile.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(McVersions.isRelease("1.21.2-rc1"))
        assertFalse(McVersions.isRelease("26.3-snapshot"))
        assertFalse(McVersions.isRelease("25w14craftmine"))
    }
}
