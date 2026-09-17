package com.mcmobile.server.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LanAddressTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun portOf_readsServerProperties() {
        val dir = tmp.newFolder("inst")
        File(dir, "server.properties").writeText("server-port=25570\nmotd=hi\n")
        assertEquals(25570, LanAddress.portOf(dir))
    }

    @Test
    fun portOf_fallsBackToDefault() {
        assertEquals(LanAddress.DEFAULT_PORT, LanAddress.portOf(tmp.newFolder("empty")))
    }

    @Test
    fun of_joinsIpAndPort() {
        val dir = tmp.newFolder("i2")
        File(dir, "server.properties").writeText("server-port=25599\n")
        assertEquals("192.168.1.5:25599", LanAddress.of(dir, ip = "192.168.1.5"))
    }

    @Test
    fun of_returnsNullWithoutLanIp() {
        // 设备没联网时不该显示半截地址
        assertNull(LanAddress.of(tmp.newFolder("i3"), ip = null))
    }
}
