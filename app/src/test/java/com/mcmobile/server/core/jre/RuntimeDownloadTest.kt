package com.mcmobile.server.core.jre

import org.apache.commons.compress.archivers.tar.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.*
import java.io.File

class RuntimeDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    private fun archive(major: Int, badLink: Boolean = false): File = temp.newFile().also { file ->
        XZOutputStream(file.outputStream(), LZMA2Options()).use { xz -> TarArchiveOutputStream(xz).use { tar ->
            fun content(path: String, body: String) {
                val bytes = body.toByteArray(); val entry = TarArchiveEntry(path); entry.size = bytes.size.toLong(); tar.putArchiveEntry(entry); tar.write(bytes); tar.closeArchiveEntry()
            }
            if (major == 8) {
                content("./lib/rt.jar", "rtjar"); content("./lib/aarch64/server/libjvm.so", "nativevm")
                content("./lib/aarch64/libjsig.so", "signal")
                val link = TarArchiveEntry("./lib/aarch64/server/libjsig.so", TarConstants.LF_SYMLINK)
                link.linkName = if (badLink) "../../../../escape" else "../libjsig.so"; tar.putArchiveEntry(link); tar.closeArchiveEntry()
                val manual = TarArchiveEntry("./man/ja", TarConstants.LF_SYMLINK); manual.linkName = "ja_JP.UTF-8"; tar.putArchiveEntry(manual); tar.closeArchiveEntry()
            } else { content("./lib/modules", "modules"); content("./lib/server/libjvm.so", "nativevm") }
        } }
    }
    @Test fun java8LayoutKeepsArchitectureDirectoryAndMaterializesFileLinks() {
        val output = temp.newFolder(); RuntimeDownload.unpack(archive(8), output, 8, "arm64-v8a", "test")
        assertEquals("lib/aarch64/server/libjvm.so", File(output, ".mcs-libjvm").readText())
        assertEquals("lib/aarch64", File(output, ".mcs-lib-dir").readText())
        assertEquals("signal", File(output, "lib/aarch64/server/libjsig.so").readText())
        assertTrue(File(output, ".mcs-extracted").isFile); assertFalse(File(output, "man").exists())
    }
    @Test fun java17UsesModularRuntimeLayout() {
        val output = temp.newFolder(); RuntimeDownload.unpack(archive(17), output, 17, "arm64-v8a", "test")
        assertEquals("lib/server/libjvm.so", File(output, ".mcs-libjvm").readText())
        assertTrue(File(output, "lib/modules").isFile)
    }
    @Test fun runtimeLinkCannotEscapeStagingDirectory() {
        val output = temp.newFolder()
        assertThrows(IllegalArgumentException::class.java) { RuntimeDownload.unpack(archive(8, true), output, 8, "arm64-v8a", "test") }
        assertFalse(File(output, ".mcs-extracted").exists())
    }
    @Test fun pinnedRealArm64ArchivesExtractCompleteRuntimeFiles() {
        // Optional local upstream sample audit, configured explicitly by the validation command.
        val path = System.getProperty("mcs.runtime.samples")
        assumeTrue(path != null)
        for (major in listOf(8, 17)) {
            val archive = File(path!!, "jre$major-android-arm64.tar.xz"); assumeTrue(archive.isFile)
            val output = temp.newFolder()
            RuntimeDownload.unpack(archive, output, major, "arm64-v8a", "sample")
            val lib = File(output, File(output, ".mcs-libjvm").readText())
            assertEquals(if (major == 8) 12149424L else 17409080L, lib.length())
            assertEquals(if (major == 8) 65705769L else 100299533L, File(output, if (major == 8) "lib/rt.jar" else "lib/modules").length())
        }
    }
}
