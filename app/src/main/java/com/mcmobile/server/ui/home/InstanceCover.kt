package com.mcmobile.server.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.res.Resources
import androidx.annotation.RawRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.mcmobile.server.R
import com.mcmobile.server.data.ServerInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.random.Random

internal data class CoverPreset(@RawRes val image: Int, val name: String)

internal val coverPresets = listOf(
    CoverPreset(R.raw.server_cover_alpine, "晨光松谷"),
    CoverPreset(R.raw.server_cover_coast, "暖风海岸"),
    CoverPreset(R.raw.server_cover_autumn, "秋叶溪谷"),
    CoverPreset(R.raw.server_cover_snow, "雪原木屋"),
    CoverPreset(R.raw.server_cover_desert, "赤砂峡谷"),
    CoverPreset(R.raw.server_cover_orchard, "樱雨山坳"),
    CoverPreset(R.raw.server_cover_cave, "晶石洞窟"),
    CoverPreset(R.raw.server_cover_night, "星夜村落"),
)

@Composable
internal fun InstanceCover(
    directory: File,
    instance: ServerInstance,
    modifier: Modifier,
    featured: Boolean = false,
    revision: Int = 0,
) {
    val owner = LocalLifecycleOwner.current
    val resources = LocalContext.current.resources
    // One draw per confirmed launch, with a stable default before the first launch.
    val seed = instance.id.hashCode().toLong() xor (instance.lastStartedMs ?: instance.createdMs)
    val preset = coverPresets[Random(seed).nextInt(coverPresets.size)]
    key(directory, featured) {
        val artwork by produceState<Artwork?>(null, owner, revision, preset.image) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                value = withContext(Dispatchers.IO) {
                    readArtwork(homeCoverFile(directory), featured, serverIcon = false)
                        ?: readArtwork(File(directory, "server-icon.png"), featured, serverIcon = true)
                        ?: loadPreset(resources, preset, if (featured) 1 else 4)?.let { Artwork(it, null, false) }
                }
            }
        }
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            artwork?.let { bitmap ->
                bitmap.backdrop?.let {
                    Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, alpha = 0.65f)
                }
                Image(bitmap.image, null, Modifier.fillMaxSize(),
                    contentScale = if (bitmap.serverIcon) ContentScale.Fit else ContentScale.Crop,
                    filterQuality = if (bitmap.serverIcon) FilterQuality.None else FilterQuality.Low)
            }
        }
    }
}

@Composable
internal fun PresetThumbnail(preset: CoverPreset, modifier: Modifier) {
    val resources = LocalContext.current.resources
    val image by produceState<ImageBitmap?>(null, preset.image, resources) {
        value = withContext(Dispatchers.IO) { loadPreset(resources, preset, 2) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

private fun loadPreset(resources: Resources, preset: CoverPreset, sample: Int): ImageBitmap? =
    resources.openRawResource(preset.image).use { stream ->
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
    }

private data class Artwork(val image: ImageBitmap, val backdrop: ImageBitmap?, val serverIcon: Boolean)

private fun readArtwork(file: File, featured: Boolean, serverIcon: Boolean): Artwork? {
    return try {
        file.inputStream().use { stream ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(stream.fd, null, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            if (serverIcon && options.outMimeType != "image/png") return null
            options.inJustDecodeBounds = false
            options.inSampleSize = coverSampleSize(options.outWidth, options.outHeight, if (featured) 1100 else 192)
            val bitmap = BitmapFactory.decodeFileDescriptor(stream.fd, null, options) ?: return null
            val backdrop = if (featured && serverIcon) Bitmap.createScaledBitmap(bitmap, 3, 3, true).asImageBitmap() else null
            Artwork(bitmap.asImageBitmap(), backdrop, serverIcon)
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

internal fun coverSampleSize(width: Int, height: Int, limit: Int): Int {
    var sample = 1
    while (maxOf(width, height) / sample > limit) sample *= 2
    return sample
}
