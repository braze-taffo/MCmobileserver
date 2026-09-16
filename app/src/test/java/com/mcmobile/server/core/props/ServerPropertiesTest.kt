package com.mcmobile.server.core.props

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPropertiesTest {

    private val sample = """
        #Minecraft server properties
        #Wed Sep 17 00:00:00 CST 2025
        server-port=25565
        motd=A Minecraft Server
        online-mode=true
        view-distance=10
    """.trimIndent() + "\n"

    @Test
    fun parse_and_read() {
        val p = ServerProperties.parse(sample)
        assertEquals("25565", p["server-port"])
        assertEquals("A Minecraft Server", p["motd"])
        assertEquals(
            listOf("server-port", "motd", "online-mode", "view-distance"),
            p.keys,
        )
    }

    @Test
    fun set_updates_in_place_and_preserves_comments() {
        val p = ServerProperties.parse(sample)
        p["view-distance"] = "6"
        p["motd"] = "手机服务器"

        val text = p.toText()
        assertTrue(text.startsWith("#Minecraft server properties"))
        assertTrue(text.contains("view-distance=6"))
        assertTrue(text.contains("motd=手机服务器"))

        val reparsed = ServerProperties.parse(text)
        assertEquals("6", reparsed["view-distance"])
        assertEquals("手机服务器", reparsed["motd"])
    }

    @Test
    fun set_appends_new_key() {
        val p = ServerProperties.parse(sample)
        p["white-list"] = "true"
        assertEquals("true", p["white-list"])
        assertTrue(p.keys.last() == "white-list")
    }
}
