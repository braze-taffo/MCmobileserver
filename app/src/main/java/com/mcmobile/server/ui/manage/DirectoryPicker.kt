package com.mcmobile.server.ui.manage

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.mcmobile.server.core.storage.StorageLocation
import java.io.File

@Composable
fun rememberDirectoryPicker(onPicked: (File) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val picked by rememberUpdatedState(onPicked)
    val error by rememberUpdatedState(onError)
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) when (val resolution = StorageLocation.resolvePicked(uri)) {
            is StorageLocation.Resolution.Ok -> picked(resolution.dir)
            is StorageLocation.Resolution.Rejected -> error(resolution.reason)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (StorageLocation.hasWriteAccess(context)) tree.launch(null) else error("请授予所有文件访问权限")
    }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) tree.launch(null) else error("未授予存储权限")
    }
    return {
        when {
            StorageLocation.hasWriteAccess(context) -> tree.launch(null)
            Build.VERSION.SDK_INT >= 30 -> permission.launch(StorageLocation.permissionSettingsIntent(context))
            else -> legacy.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}
