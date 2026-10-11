package com.mcmobile.server

import androidx.test.platform.app.InstrumentationRegistry
import com.mcmobile.server.core.repack.CustomApk
import com.mcmobile.server.core.manage.Addons
import com.mcmobile.server.core.manage.AddonInstall
import com.mcmobile.server.data.ServerType
import junit.framework.TestCase
import java.io.File

/** Exercises real Android Keystore signing and Android's package parser, outside production APKs. */
class CustomApkDeviceTest : TestCase() {
    fun testGenerateAndReuseCertificate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "com.mcmobile.validation.custom"
        val first = CustomApk.generate(context, name) { android.util.Log.i("CustomApkTest", it) }
        val second = CustomApk.generate(context, name) {}
        assertEquals(first.fingerprint, second.fingerprint)
        val info = context.packageManager.getPackageArchiveInfo(second.file.absolutePath, android.content.pm.PackageManager.GET_ACTIVITIES or android.content.pm.PackageManager.GET_PROVIDERS)
        assertEquals(name, info?.packageName)
        assertTrue(info?.activities?.any { it.name == "com.mcmobile.server.MainActivity" } == true)
        assertTrue(info?.providers?.any { it.authority == "$name.apkfiles" } == true)
        assertEquals(context.packageManager.getPackageInfo(context.packageName, 0).versionName, info?.versionName)
    }
    fun testModrinthSearchAndInstall() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val page = Addons.searchPage("FerriteCore", ServerType.FABRIC, "1.21.1")
        val hit = page.hits.first { it.title.equals("FerriteCore", true) }
        val version = Addons.versions(hit.id, ServerType.FABRIC, "1.21.1").first { it.releaseType == "release" }
        val plan = Addons.plan(version, ServerType.FABRIC, "1.21.1")
        val root = File(context.cacheDir, "addon-device-validation").apply { mkdirs() }
        try {
            AddonInstall.install(root, ServerType.FABRIC, plan, null, File(context.cacheDir, "addon-validation-history")) {}
            assertTrue(Addons.local(root, ServerType.FABRIC).any { it.id == "ferritecore" && it.enabled })
        } finally { root.deleteRecursively(); File(context.cacheDir, "addon-validation-history").deleteRecursively() }
    }
}
