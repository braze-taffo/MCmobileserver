package com.mcmobile.server.core.frp

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FrpConfigTest {
    @Test fun clientConfigurationPreservesSecretsAndUsesTcpLoopback() {
        val config = FrpConfig(server = "frp.example.com", token = "quote\"\\line\nsecret", localPort = "25566", remotePort = "30001")
        val json = Json.parseToJsonElement(config.clientJson()).jsonObject
        assertEquals(config.token, json["auth"]!!.jsonObject["token"]!!.jsonPrimitive.content)
        assertFalse(json["loginFailExit"]!!.jsonPrimitive.boolean)
        assertTrue(json["transport"]!!.jsonObject["tls"]!!.jsonObject["enable"]!!.jsonPrimitive.boolean)
        val proxy = json["proxies"]!!.jsonArray.single().jsonObject
        assertEquals("tcp", proxy["type"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", proxy["localIP"]!!.jsonPrimitive.content)
        assertEquals(25566, proxy["localPort"]!!.jsonPrimitive.int)
        assertEquals(30001, proxy["remotePort"]!!.jsonPrimitive.int)
    }

    @Test fun rejectsInvalidPortsAndHosts() {
        val valid = FrpConfig(server = "frp.example.com")
        listOf("0", "65536", "", "-1", "abc", "7000.0").forEach { bad ->
            listOf(valid.copy(serverPort = bad), valid.copy(localPort = bad), valid.copy(remotePort = bad)).forEach {
                assertThrows(IllegalArgumentException::class.java) { it.validate() }
            }
        }
        listOf("", "https://example.com", "host name", "example.com/path", "example.com:7000").forEach {
            assertThrows(IllegalArgumentException::class.java) { valid.copy(server = it).validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(token = "{{ .Envs.SECRET }}").validate() }
    }

    @Test fun formatsPublicAddressAndIpv6() {
        assertEquals("frp.example.com:25565", FrpConfig(server = "frp.example.com").address)
        val config = FrpConfig(server = "::1", publicHost = "2001:db8::2", remotePort = "30000")
        config.validate()
        assertEquals("[2001:db8::2]:30000", config.address)
        assertEquals("play.example.com:30000", config.copy(publicHost = "play.example.com").address)
    }
}
