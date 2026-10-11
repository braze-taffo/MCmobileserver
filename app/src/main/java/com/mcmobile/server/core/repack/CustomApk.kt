package com.mcmobile.server.core.repack

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.FileProvider
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal

object CustomApk {
    private const val ALIAS = "mcs-custom-apk-v1"
    data class Result(val file: File, val fingerprint: String)
    private class Counted(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(value: Int) { out.write(value); count++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length); count += length }
    }

    @Synchronized fun generate(context: Context, packageName: String, progress: (String) -> Unit): Result {
        require(BinaryManifest.validPackage(packageName)) { "包名格式不正确" }
        require(packageName != context.packageName) { "请使用与当前应用不同的包名" }
        require(context.applicationInfo.splitSourceDirs.isNullOrEmpty()) { "当前为拆分 APK，请先安装项目提供的完整 APK" }
        val folder = File(context.cacheDir, "custom-apks").apply { mkdirs() }
        val unsigned = File(folder, "unsigned.part")
        val pending = File(folder, "signed.part")
        val output = File(folder, "$packageName.apk")
        try {
            progress("准备本地签名证书…")
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                    initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setCertificateSubject(X500Principal("CN=MC Mobile Server Custom APK"))
                        .setCertificateSerialNumber(BigInteger.ONE).setCertificateNotBefore(Date(0))
                        .setCertificateNotAfter(Date(4102444800000L)).build())
                }.generateKeyPair()
            }
            val cert = store.getCertificate(ALIAS) as X509Certificate
            progress("修改包名并打包…")
            ZipFile(context.applicationInfo.sourceDir).use { zip ->
                val counted = Counted(unsigned.outputStream().buffered())
                ZipOutputStream(counted).use { out ->
                    out.setLevel(1)
                    val entries = zip.entries()
                    var manifest = false
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name
                        if (name.startsWith("META-INF/", true) &&
                            (name.endsWith(".RSA", true) || name.endsWith(".DSA", true) || name.endsWith(".EC", true) ||
                             name.endsWith(".SF", true) || name.equals("META-INF/MANIFEST.MF", true))) continue
                        val rewritten = ZipEntry(name)
                        // Android 11+ requires resources.arsc to be stored and aligned.
                        if (name == "resources.arsc") {
                            rewritten.method = ZipEntry.STORED
                            rewritten.size = entry.size; rewritten.crc = entry.crc
                            val remainder = ((counted.count + 30 + name.toByteArray(Charsets.UTF_8).size) % 4).toInt()
                            if (remainder != 0) rewritten.extra = ByteArray(4 + (4 - remainder) % 4).apply {
                                this[0] = 0xfe.toByte(); this[1] = 0xca.toByte(); this[2] = (size - 4).toByte()
                            }
                        }
                        out.putNextEntry(rewritten)
                        zip.getInputStream(entry).use { input ->
                            if (name == "AndroidManifest.xml") {
                                require(entry.size in 1..2097152) { "清单大小异常" }
                                out.write(BinaryManifest.rename(input.readBytes(), context.packageName, packageName)); manifest = true
                            } else input.copyTo(out, 64 * 1024)
                        }
                        out.closeEntry()
                    }
                    check(manifest) { "安装包没有清单" }
                }
            }
            progress("签名并验证 APK…")
            val config = ApkSigner.SignerConfig.Builder("local", store.getKey(ALIAS, null) as PrivateKey, listOf(cert)).build()
            ApkSigner.Builder(listOf(config)).setInputApk(unsigned).setOutputApk(pending)
                .setMinSdkVersion(26).setV1SigningEnabled(false).setV2SigningEnabled(true)
                .setV3SigningEnabled(true).setV4SigningEnabled(false).build().sign()
            check(ApkVerifier.Builder(pending).setMinCheckedPlatformVersion(26).build().verify().isVerified) { "APK 签名校验失败" }
            val info = context.packageManager.getPackageArchiveInfo(pending.absolutePath, 0)
            check(info?.packageName == packageName) { "生成包名校验失败" }
            java.nio.file.Files.move(pending.toPath(), output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
            return Result(output, fingerprint)
        } finally { unsigned.delete(); pending.delete() }
    }

    fun install(context: Context, file: File) {
        check(file.isFile) { "APK 已过期，请重新生成" }
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.apkfiles", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
