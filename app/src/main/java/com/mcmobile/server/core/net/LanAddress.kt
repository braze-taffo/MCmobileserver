package com.mcmobile.server.core.net

import com.mcmobile.server.core.props.ServerProperties
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 给玩家用的「局域网地址 ip:端口」。
 *
 * 手机热点 / 家用路由下，电脑上的 MC Java 客户端直接填这个地址就能进服务器。
 * 端口以实例的 server.properties 为准（服务器首次启动后才生成该文件），缺省 25565。
 */
object LanAddress {

    const val DEFAULT_PORT = 25565

    /**
     * 本机在局域网里的 IPv4：跳过回环与未启用的接口，优先无线网卡（wlan0）。
     * 私网地址（10./172.16-31./192.168.）优先，拿不到就退而求其次用任意非回环 IPv4。
     */
    fun primaryIpv4(): String? = runCatching {
        val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni ->
                ni.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress }
                    .map { ni.name.orEmpty() to it }
            }
        val privateOnes = all.filter { it.second.isSiteLocalAddress }
        val pick = privateOnes.firstOrNull { it.first.startsWith("wlan") }
            ?: privateOnes.firstOrNull()
            ?: all.firstOrNull { it.first.startsWith("wlan") }
            ?: all.firstOrNull()
        pick?.second?.hostAddress
    }.getOrNull()

    fun portOf(instanceDir: File): Int =
        ServerProperties.load(File(instanceDir, "server.properties"))
            ?.get("server-port")?.toIntOrNull()
            ?: DEFAULT_PORT

    /** `ip:端口`；拿不到局域网 IPv4 时返回 null（例如设备当前没联网） */
    fun of(instanceDir: File, ip: String? = primaryIpv4()): String? =
        ip?.let { "$it:${portOf(instanceDir)}" }
}
