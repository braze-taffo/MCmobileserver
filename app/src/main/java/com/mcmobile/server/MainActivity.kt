package com.mcmobile.server

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.mcmobile.server.ui.MCServerApp
import com.mcmobile.server.ui.theme.MCServerTheme

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        handleDebugSpec(intent)
        setContent {
            MCServerTheme {
                MCServerApp()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleDebugSpec(intent)
    }

    /** debug 构建下 adb 注入 LaunchSpec：am start ... --es mcs.spec '<base64(json)>' */
    private fun handleDebugSpec(intent: android.content.Intent?) {
        val raw = intent?.getStringExtra("mcs.spec") ?: return
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        try {
            val json = if (raw.startsWith("{")) raw
            else String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8)
            val spec = kotlinx.serialization.json.Json.decodeFromString(
                com.mcmobile.server.core.launch.LaunchSpec.serializer(), json,
            )
            com.mcmobile.server.service.ServerForegroundService.start(this, spec)
        } catch (t: Throwable) {
            android.util.Log.e("mcs-debug", "debug spec start failed", t)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
