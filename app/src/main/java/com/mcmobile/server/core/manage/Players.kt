package com.mcmobile.server.core.manage

import com.mcmobile.server.core.files.ManagedFiles
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

object Players {
    data class Player(val name: String, val uuid: String?, val lists: Set<String>)
    val lists = linkedMapOf("ops.json" to "OP", "whitelist.json" to "白名单", "banned-players.json" to "封禁", "usercache.json" to "历史玩家")
    fun read(root: File): List<Player> {
        val players = linkedMapOf<String, Player>()
        lists.forEach { (filename, label) ->
            val file = ManagedFiles(root).child(root, filename)
            if (file.isFile) {
                require(file.length() < 8 * 1048576) { "$filename 过大" }
                Json.parseToJsonElement(file.readText()).jsonArray.forEach { item ->
                    val obj = item.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull
                    val key = uuid ?: name.lowercase()
                    val previous = players[key]
                    players[key] = Player(name, uuid, (previous?.lists ?: emptySet()) + label)
                }
            }
        }
        return players.values.sortedBy { it.name.lowercase() }
    }
    fun validName(name: String): String = name.trim().also { require(it.matches(Regex("[A-Za-z0-9_]{1,16}"))) { "请输入合法的玩家名称" } }
    fun command(action: String, name: String, reason: String = ""): String {
        val player = validName(name)
        require(action in setOf("op", "deop", "whitelist add", "whitelist remove", "ban", "pardon", "kick"))
        require(reason.none { it == '\n' || it == '\r' || it == '\u0000' }) { "原因不能包含换行" }
        return "$action $player" + if (action in setOf("kick", "ban") && reason.isNotBlank()) " $reason" else ""
    }
    fun offline(root: File, action: String, name: String, uuidInput: String, reason: String = "") {
        val player = validName(name)
        val (filename, remove) = when (action) {
            "op" -> "ops.json" to false; "deop" -> "ops.json" to true
            "whitelist add" -> "whitelist.json" to false; "whitelist remove" -> "whitelist.json" to true
            "ban" -> "banned-players.json" to false; "pardon" -> "banned-players.json" to true
            else -> error("该操作需要服务器运行")
        }
        val file = ManagedFiles(root).child(root, filename)
        val items = if (file.isFile) Json.parseToJsonElement(file.readText()).jsonArray.toMutableList() else mutableListOf()
        val old = items.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.content?.equals(player, true) == true }
        items.removeAll { it.jsonObject["name"]?.jsonPrimitive?.content?.equals(player, true) == true }
        if (!remove) {
            val uuid = uuidInput.trim().takeIf { it.isNotBlank() }
                ?: old?.jsonObject?.get("uuid")?.jsonPrimitive?.content
                ?: read(root).firstOrNull { it.name.equals(player, true) }?.uuid
                ?: run {
                    val props = com.mcmobile.server.core.props.ServerProperties.load(File(root, "server.properties"))
                    require(props?.get("online-mode") == "false") { "正版模式下请填写玩家 UUID，或开服后通过命令添加" }
                    UUID.nameUUIDFromBytes("OfflinePlayer:$player".toByteArray(Charsets.UTF_8)).toString()
                }
            UUID.fromString(uuid)
            items += buildJsonObject {
                put("uuid", uuid); put("name", player)
                if (filename == "ops.json") { put("level", 4); put("bypassesPlayerLimit", false) }
                if (filename == "banned-players.json") {
                    put("created", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.ROOT).format(java.util.Date()))
                    put("source", "MC Mobile Server"); put("expires", "forever"); put("reason", reason.ifBlank { "由管理员封禁" })
                }
            }
        }
        JsonArray(items).toString().byteInputStream().use { ManagedFiles(root).write(file, it) }
    }
}
