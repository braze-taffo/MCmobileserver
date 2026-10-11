package com.mcmobile.server.core.files

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ManagedFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun zip(vararg pairs: Pair<String, String>): File = temp.newFile().also { archive ->
        ZipOutputStream(archive.outputStream()).use { out -> pairs.forEach { (name, value) -> out.putNextEntry(ZipEntry(name)); out.write(value.toByteArray()); out.closeEntry() } }
    }
    @Test fun importPoliciesPreserveOverwriteAndKeepBoth() {
        val root = temp.newFolder(); val ops = ManagedFiles(root); val target = File(root, "配置.toml").apply { writeText("old") }
        assertFalse(ops.write(target, "skip".byteInputStream(), ManagedFiles.Conflict.SKIP)); assertEquals("old", target.readText())
        assertTrue(ops.write(target, "new".byteInputStream(), ManagedFiles.Conflict.OVERWRITE)); assertEquals("new", target.readText())
        ops.write(target, "other".byteInputStream(), ManagedFiles.Conflict.KEEP_BOTH)
        assertEquals("new", target.readText()); assertEquals("other", File(root, "配置 (1).toml").readText())
    }
    @Test fun interruptedWriteNeverTruncatesAnExistingFile() {
        val root = temp.newFolder(); val target = File(root, "settings.json").apply { writeText("original") }
        val input = object : java.io.InputStream() { override fun read(): Int = throw java.io.IOException("read failed") }
        assertThrows(java.io.IOException::class.java) { ManagedFiles(root).write(target, input) }
        assertEquals("original", target.readText()); assertEquals(1, root.listFiles()!!.size)
    }
    @Test fun editorDetectsConcurrentModification() {
        val root = temp.newFolder(); val file = File(root, "server.properties").apply { writeText("a=1") }
        val before = file.lastModified(); val size = file.length(); file.writeText("server changed the file")
        assertThrows(IllegalArgumentException::class.java) { ManagedFiles(root).saveText(file, "a=2", before, size) }
        assertEquals("server changed the file", file.readText())
    }
    @Test fun badNamesRootAndOutsidePathsAreRejected() {
        val root = temp.newFolder(); val ops = ManagedFiles(root)
        listOf("..", "a/b", "a\\b", "c:drive", "bad\nname").forEach { name -> assertThrows(IllegalArgumentException::class.java) { ops.child(root, name) } }
        assertThrows(IllegalArgumentException::class.java) { ops.delete(root) }
        assertThrows(IllegalArgumentException::class.java) { ops.checked(File(root, "../outside")) }
        val marker = File(root, ".mcs-instance.json").apply { writeText("marker") }
        assertThrows(IllegalArgumentException::class.java) { ops.write(marker, "replace".byteInputStream()) }
        assertEquals("marker", marker.readText())
    }
    @Test fun unsafeArchivesNeverLeaveAnEarlierValidEntry() {
        listOf("../escape", "/absolute", "dir\\..\\escape", "C:\\escape").forEach { unsafe ->
            val destination = File(temp.newFolder(), "stage")
            assertThrows(IllegalArgumentException::class.java) { ManagedFiles.unpack(zip("config/a.txt" to "good", unsafe to "bad"), destination) }
            assertFalse(destination.exists())
        }
    }
    @Test fun archivePreservesAllFoldersAndEnforcesBounds() {
        val destination = File(temp.newFolder(), "stage")
        val archive = zip("mods/a.jar" to "mod", "config/a.toml" to "config", "world/level.dat" to "world", "libraries/x.jar" to "lib")
        ManagedFiles.unpack(archive, destination)
        assertEquals("config", File(destination, "config/a.toml").readText()); assertEquals("lib", File(destination, "libraries/x.jar").readText())
        val oversized = File(temp.newFolder(), "stage")
        assertThrows(IllegalArgumentException::class.java) { ManagedFiles.unpack(archive, oversized, maxBytes = 2) }
        assertFalse(oversized.exists())
    }
    @Test fun copyAndMoveRetainSkippedSources() {
        val root = temp.newFolder(); val source = File(root, "source").apply { mkdir() }; val destination = File(root, "destination").apply { mkdir() }
        File(source, "same.txt").writeText("source"); File(source, "new.txt").writeText("new"); File(destination, "same.txt").writeText("destination")
        val result = ManagedFiles(root).transfer(source.listFiles()!!.toList(), destination, true, ManagedFiles.Conflict.SKIP)
        assertEquals(1, result.written); assertEquals(1, result.skipped)
        assertTrue(File(source, "same.txt").isFile); assertFalse(File(source, "new.txt").exists()); assertEquals("destination", File(destination, "same.txt").readText())
    }
    @Test fun cannotCopyOrCompressIntoItsOwnTree() {
        val root = temp.newFolder(); val directory = File(root, "world").apply { mkdir() }; val child = File(directory, "sub").apply { mkdir() }
        val ops = ManagedFiles(root)
        assertThrows(IllegalArgumentException::class.java) { ops.transfer(listOf(directory), child, false, ManagedFiles.Conflict.OVERWRITE) }
        assertThrows(IllegalArgumentException::class.java) { ops.compress(listOf(directory), File(directory, "backup.zip")) }
    }
    @Test fun extractionMergesAccordingToSelectedPolicy() {
        val root = temp.newFolder(); File(root, "config").mkdir(); File(root, "config/a.toml").writeText("original")
        val result = ManagedFiles(root).extract(zip("config/a.toml" to "replacement", "mods/b.jar" to "new"), root, ManagedFiles.Conflict.OVERWRITE)
        assertEquals(2, result.written); assertEquals("replacement", File(root, "config/a.toml").readText()); assertEquals("new", File(root, "mods/b.jar").readText())
    }
}
