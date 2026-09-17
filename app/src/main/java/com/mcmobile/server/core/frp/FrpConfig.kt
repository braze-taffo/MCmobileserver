package com.mcmobile.server.core.frp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class FrpConfig(
    val server: String = "",
    val serverPort: String = "7000",
    val token: String = "",
    val user: String = "",
    val proxyName: String = "minecraft",
    val localPort: String = "25565",
    val remotePort: String = "25565",
    val publicHost: String = "",
) {
    fun validate() {
        fun host(value: String) = value.isNotBlank() &&
            value.none { it.isWhitespace() || it in "/\\\"[]?#@" } &&
            (':' !in value || value.count { it == ':' } >= 2)
        require(host(server)) { "节点地址只填写域名或 IP，不带协议、端口或方括号" }
        require(publicHost.isBlank() || host(publicHost)) { "公网地址只填写域名或 IP" }
        listOf("节点端口" to serverPort, "本地端口" to localPort, "远程端口" to remotePort).forEach { (name, value) ->
            require(value.toIntOrNull() in 1..65535) { "$name 必须在 1–65535 之间" }
        }
        require(proxyName.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "隧道名称请使用 1–64 位字母、数字、下划线或短横线" }
        require(listOf(server, token, user, proxyName, publicHost).none { "{{" in it }) { "配置不能包含 FRP 模板表达式" }
    }

    val address: String get() {
        val host = publicHost.ifBlank { server }
        return (if (':' in host) "[$host]" else host) + ":$remotePort"
    }

    fun clientJson(): String {
        validate()
        return buildJsonObject {
            put("serverAddr", server)
            put("serverPort", serverPort.toInt())
            put("user", user)
            put("loginFailExit", false)
            putJsonObject("auth") { put("method", "token"); put("token", token) }
            putJsonObject("log") {
                put("to", "console"); put("level", "info"); put("disablePrintColor", true)
            }
            putJsonObject("transport") {
                putJsonObject("tls") { put("enable", true) }
                put("heartbeatInterval", 10)
                put("heartbeatTimeout", 30)
            }
            putJsonArray("proxies") {
                addJsonObject {
                    put("name", proxyName); put("type", "tcp")
                    put("localIP", "127.0.0.1"); put("localPort", localPort.toInt())
                    put("remotePort", remotePort.toInt())
                }
            }
        }.toString()
    }
}
