package com.mcmobile.server.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun UpdateDialog(updates: UpdateViewModel) {
    val state by updates.state.collectAsState()
    val release = state.release ?: return
    val context = LocalContext.current
    if (!state.showDialog) return
    AlertDialog(
        onDismissRequest = updates::dismissRelease,
        title = { Text("发现新版本 ${release.version}") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(release.notes)
                TextButton(onClick = { AppLinks.open(context, release.pageUrl) }) { Text("查看 GitHub 发行页") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                updates.dismissRelease()
                AppLinks.open(context, release.apkUrl)
            }) { Text("下载 APK") }
        },
        dismissButton = { TextButton(onClick = updates::dismissRelease) { Text("稍后") } },
    )
}
