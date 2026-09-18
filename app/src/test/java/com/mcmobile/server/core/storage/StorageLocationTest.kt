package com.mcmobile.server.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageLocationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val primary get() = File(tmp.root, "emulated/0")

    /** 设备上 primary 就是 /storage/emulated/0，这里用临时目录当卷根，断言的是相对部分 */
    private val volumes get() = mapOf(
        "primary" to primary,
        "69FF-E461" to File(tmp.root, "sdcard"),
    )

    private fun ok(uri: String): File {
        val r = StorageLocation.resolveTree(uri, volumes)
        assertTrue("期望解析成功，实际：$r", r is StorageLocation.Resolution.Ok)
        return (r as StorageLocation.Resolution.Ok).dir
    }

    private fun reason(uri: String): String {
        val r = StorageLocation.resolveTree(uri, volumes)
        assertTrue("期望被拒绝，实际：$r", r is StorageLocation.Resolution.Rejected)
        return (r as StorageLocation.Resolution.Rejected).reason
    }

    @Test
    fun mapsTreeUriToRealPath() {
        val dir = ok("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FMCServers")
        assertEquals(File(primary, "Documents/MCServers"), dir)
    }

    @Test
    fun mapsRemovableVolume() {
        val dir = ok("content://com.android.externalstorage.documents/tree/69FF-E461%3AMCServers")
        assertEquals(File(tmp.root, "sdcard/MCServers"), dir)
    }

    @Test
    fun decodesNonAsciiFolderNames() {
        // 中文目录名在 URI 里是 %E4%B8%96... ，解不出来就会往错误路径建实例
        val dir = ok(
            "content://com.android.externalstorage.documents/tree/primary%3A" +
                    "%E6%88%91%E7%9A%84%E4%B8%96%E7%95%8C",
        )
        assertEquals(File(primary, "我的世界"), dir)
    }

    @Test
    fun keepsPlusSignsInFolderNames() {
        // URLDecoder 会把 '+' 解成空格，文件夹名里的 '+' 就没了
        val dir = ok("content://com.android.externalstorage.documents/tree/primary%3Amods%2Ba%2Bb")
        assertEquals(File(primary, "mods+a+b"), dir)
    }

    @Test
    fun rejectsCloudProvidersWithoutRealPath() {
        val why = reason("content://com.google.android.apps.docs.storage/tree/abc%3A%2Froot")
        assertTrue(why, why.contains("真实"))
    }

    @Test
    fun rejectsStorageRoot() {
        assertTrue(reason("content://com.android.externalstorage.documents/tree/primary%3A").contains("专用文件夹"))
    }

    @Test
    fun rejectsAndroidDirectory() {
        assertTrue(
            reason("content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata")
                .contains("Android"),
        )
        assertTrue(
            reason("content://com.android.externalstorage.documents/tree/primary%3Aandroid")
                .contains("Android"),
        )
    }

    @Test
    fun rejectsUnknownVolume() {
        assertTrue(
            reason("content://com.android.externalstorage.documents/tree/XXXX-XXXX%3Afoo").contains("存储卷"),
        )
    }

    @Test
    fun rejectsPathTraversal() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2F..%2FDownload"
        assertTrue(reason(uri).contains("不合法"))
    }

    @Test
    fun rejectsNonContentUri() {
        assertTrue(reason("file:///storage/emulated/0/Documents").isNotBlank())
        assertTrue(reason("content://com.android.externalstorage.documents").isNotBlank())
        assertTrue(reason("content://com.android.externalstorage.documents/document/primary%3ADocuments").isNotBlank())
    }

    @Test
    fun percentDecodeLeavesInvalidEscapesAlone() {
        assertEquals("a%ZZb", StorageLocation.percentDecode("a%ZZb"))
        assertEquals("plain", StorageLocation.percentDecode("plain"))
        assertEquals("a b", StorageLocation.percentDecode("a%20b"))
    }

    @Test
    fun checkWritableReportsMissingAndUnwritable() {
        assertEquals(null, StorageLocation.checkWritable(tmp.root))
        assertTrue(StorageLocation.checkWritable(File(tmp.root, "nope"))!!.contains("不存在"))

        val file = File(tmp.root, "a-file").apply { writeText("x") }
        assertTrue(StorageLocation.checkWritable(file)!!.contains("不是一个文件夹"))
    }

    @Test
    fun availabilityErrorDistinguishesMissingDirectory() {
        assertTrue(StorageLocation.availabilityError(File(tmp.root, "moved-away"))!!.contains("不存在"))
        assertEquals(null, StorageLocation.availabilityError(tmp.root))
    }
}
