package com.mcmobile.server.core.frp

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Private app storage, excluded from backup by the application manifest. */
object FrpStore {
    fun load(context: Context): FrpConfig {
        val value = context.getSharedPreferences("frp", Context.MODE_PRIVATE).getString("config", null)
        return value?.let { Json.decodeFromString<FrpConfig>(it) } ?: FrpConfig()
    }

    fun save(context: Context, config: FrpConfig) {
        config.validate()
        check(context.getSharedPreferences("frp", Context.MODE_PRIVATE).edit()
            .putString("config", Json.encodeToString(config)).commit()) { "保存 FRP 配置失败" }
    }
}
