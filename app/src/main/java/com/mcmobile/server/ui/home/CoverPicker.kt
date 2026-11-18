package com.mcmobile.server.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mcmobile.server.core.files.ManagedFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal fun homeCoverFile(directory: File): File = File(directory, ".mcs-home-cover.webp")

@Composable
internal fun CoverPicker(directory: File, onClose: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun changeCover(action: () -> Unit) {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                onChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "无法保存封面，请重试"
            } finally {
                saving = false
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) changeCover { saveImportedCover(context, directory, uri) }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("设置首页封面", Modifier.weight(1f))
                IconButton(enabled = !saving, onClick = onClose) {
                    Icon(Icons.Default.Close, "关闭")
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(coverPresets.chunked(2)) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (preset in row) {
                                Surface(
                                    onClick = {
                                        changeCover {
                                            writeCover(directory) { output ->
                                                context.resources.openRawResource(preset.image).use { it.copyTo(output) }
                                            }
                                        }
                                    },
                                    enabled = !saving,
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Column {
                                        PresetThumbnail(preset, Modifier.fillMaxWidth().aspectRatio(2.2f))
                                        Text(preset.name, Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = { imagePicker.launch(arrayOf("image/*")) }) { Text("选择图片") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = {
                changeCover {
                    val cover = homeCoverFile(directory)
                    check(!cover.exists() || cover.delete()) { "无法恢复自动封面" }
                }
            }) { Text("恢复自动") }
        },
    )
}

private fun saveImportedCover(context: Context, directory: File, uri: Uri) {
    val resolver = context.contentResolver
    val maxSize = 1440
    val image = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(coverSampleSize(info.size.width, info.size.height, maxSize))
        }
    } else {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        check(options.outWidth > 0 && options.outHeight > 0) { "无法读取所选图片" }
        options.inJustDecodeBounds = false
        options.inSampleSize = coverSampleSize(options.outWidth, options.outHeight, maxSize)
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("无法读取所选图片")
    }
    try {
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        writeCover(directory) { output -> check(image.compress(format, 88, output)) { "无法保存所选图片" } }
    } finally {
        image.recycle()
    }
}

private fun writeCover(directory: File, write: (java.io.FileOutputStream) -> Unit) {
    val temporary = File.createTempFile(".mcs-cover-", ".tmp", directory)
    try {
        temporary.outputStream().use(write)
        ManagedFiles.replace(temporary, homeCoverFile(directory))
    } finally {
        temporary.delete()
    }
}
