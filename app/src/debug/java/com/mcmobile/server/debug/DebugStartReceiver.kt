package com.mcmobile.server.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mcmobile.server.core.launch.LaunchSpec
import com.mcmobile.server.service.ServerForegroundService
import kotlinx.serialization.json.Json

/**
 * 仅 debug 变体：供 adb 驱动 E2E / 最小化复现。
 * adb shell am broadcast -a com.mcmobile.server.DEBUG_START --es spec '<json>'
 */
class DebugStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val json = intent.getStringExtra("spec")
        android.util.Log.d("mcs-debug", "DEBUG_START received, spec=${json?.take(80)}")
        if (json == null) return
        try {
            val spec = Json.decodeFromString<LaunchSpec>(json)
            ServerForegroundService.start(context, spec)
        } catch (t: Throwable) {
            android.util.Log.e("mcs-debug", "DEBUG_START failed", t)
        }
    }
}
