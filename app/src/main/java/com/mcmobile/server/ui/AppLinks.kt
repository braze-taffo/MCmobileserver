package com.mcmobile.server.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object AppLinks {
    const val AUTHOR = "迷迭香のねこ"
    const val GROUP = "1121454408"
    const val QQ = "https://qm.qq.com/q/QfgpHHPe4m"
    const val BILIBILI = "https://space.bilibili.com/499259948"

    fun open(context: Context, url: String) {
        if (!launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            Toast.makeText(context, "无法打开链接，已复制到剪贴板", Toast.LENGTH_LONG).show()
            copy(context, url, false)
        }
    }

    fun joinGroup(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(
            "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$GROUP&card_type=group&source=qrcode",
        )).setPackage("com.tencent.mobileqq")
        if (!launch(context, intent)) open(context, QQ)
    }

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

    fun copy(context: Context, text: String, notify: Boolean = true) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("MC服务器", text))
        if (notify) Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }
}
