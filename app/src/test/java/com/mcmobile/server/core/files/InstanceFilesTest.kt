package com.mcmobile.server.core.files

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstanceFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun archive(vararg entries: Pair<String, String>): File = tmp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    @Test fun importsNestedJarsAndSkipsExistingFiles() {
        val root = tmp.newFolder()
        File(root, "mods").mkdirs()
        File(root, "mods/a.jar").writeText("original")
        val zip = archive("pack/mods/a.jar" to "replacement", "pack/mods/b.JAR" to "new", "config/a.txt" to "ignore")
        assertEquals(InstanceFiles.Result(1, 1), InstanceFiles(root).importModsZip(zip, tmp.root))
        assertEquals("original", File(root, "mods/a.jar").readText())
        assertEquals("new", File(root, "mods/b.JAR").readText())
        assertFalse(File(root, "config").exists())
    }

    @Test fun unsafeArchiveDoesNotImportEarlierValidEntry() {
        for (path in listOf("../bad.jar", "/bad.jar", "C:\\bad.jar", "dir\\..\\bad.jar")) {
            val root = tmp.newFolder()
            val zip = archive("good.jar" to "good", path to "bad")
            assertThrows(IllegalArgumentException::class.java) { InstanceFiles(root).importModsZip(zip, tmp.root) }
            assertFalse(File(root, "mods/good.jar").exists())
        }
    }

    @Test fun duplicateBasenamesAreRejectedBeforeImport() {
        val root = tmp.newFolder()
        val zip = archive("a/mod.jar" to "one", "b/MOD.jar" to "two")
        assertThrows(IllegalArgumentException::class.java) { InstanceFiles(root).importModsZip(zip, tmp.root) }
        assertFalse(File(root, "mods").exists())
    }

    @Test fun oversizedArchiveLeavesNoModsOrStagingFiles() {
        val root = tmp.newFolder()
        val staging = tmp.newFolder()
        val zip = archive("mod.jar" to "too large")
        assertThrows(IllegalArgumentException::class.java) { InstanceFiles(root).importModsZip(zip, staging, 2) }
        assertFalse(File(root, "mods").exists())
        assertTrue(staging.listFiles()!!.isEmpty())
    }

    @Test fun rejectsInvalidZipAndZipWithoutMods() {
        val root = tmp.newFolder()
        val invalid = tmp.newFile().apply { writeText("not a zip") }
        assertThrows(java.util.zip.ZipException::class.java) { InstanceFiles(root).importModsZip(invalid, tmp.root) }
        val empty = archive("config.txt" to "config")
        assertThrows(IllegalArgumentException::class.java) { InstanceFiles(root).importModsZip(empty, tmp.root) }
    }

    @Test fun deletesFilesAndFoldersButProtectsRootAndOutside() {
        val root = tmp.newFolder()
        val files = InstanceFiles(root)
        val file = File(root, "a.txt").apply { writeText("a") }
        files.delete(file)
        assertFalse(file.exists())
        val folder = File(root, "mods/sub").apply { mkdirs() }
        File(folder, "a.jar").writeText("a")
        files.delete(File(root, "mods"))
        assertFalse(folder.exists())
        assertThrows(IllegalArgumentException::class.java) { files.delete(root) }
        val outside = tmp.newFile()
        assertThrows(IllegalArgumentException::class.java) { files.delete(outside) }
        assertTrue(outside.exists())
    }

    @Test fun importsFilesWithoutOverwriteAndRejectsUnsafeName() {
        val root = tmp.newFolder()
        val files = InstanceFiles(root)
        assertEquals(InstanceFiles.Result(1, 0), files.importFile(root, "模组.jar", "one".byteInputStream()))
        assertEquals(InstanceFiles.Result(0, 1), files.importFile(root, "模组.jar", "two".byteInputStream()))
        assertEquals("one", File(root, "模组.jar").readText())
        assertThrows(IllegalArgumentException::class.java) { files.importFile(root, "../outside", "".byteInputStream()) }
    }

    @Test fun failedReadLeavesNoPartialFile() {
        val root = tmp.newFolder()
        val broken = object : InputStream() {
            override fun read(): Int = throw java.io.IOException("read failed")
        }
        assertThrows(java.io.IOException::class.java) { InstanceFiles(root).importFile(root, "mod.jar", broken) }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun importsWorldZipFromTopLevelFolderAndIgnoresSiblings() {
        val root = tmp.newFolder()
        val zip = archive(
            "MyWorld/level.dat" to "ld",
            "MyWorld/region/r.0.0.mca" to "region",
            // 旧版存档的深层 level.dat 只是下界标记，不能让它干扰存档根的定位
            "MyWorld/DIM1/level.dat" to "nether",
            "readme.txt" to "ignore",
            "__MACOSX/junk" to "junk",
            "MyWorld/._region" to "junk",
        )
        assertEquals("MyWorld", InstanceFiles(root).importWorldZip(zip, "fallback"))
        assertEquals("ld", File(root, "MyWorld/level.dat").readText())
        assertEquals("region", File(root, "MyWorld/region/r.0.0.mca").readText())
        assertEquals("nether", File(root, "MyWorld/DIM1/level.dat").readText())
        assertFalse(File(root, "readme.txt").exists())
        assertFalse(File(root, "MyWorld/._region").exists())
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".world-import-") })
    }

    @Test fun importsBareWorldZipUsingFallbackName() {
        val root = tmp.newFolder()
        val zip = archive("level.dat" to "ld", "region/r.0.0.mca" to "r", "playerdata/x.dat" to "p")
        assertEquals("我的存档", InstanceFiles(root).importWorldZip(zip, "我的存档"))
        assertEquals("ld", File(root, "我的存档/level.dat").readText())
        assertEquals("p", File(root, "我的存档/playerdata/x.dat").readText())
    }

    @Test fun wrapperDirectoriesAroundWorldAreStripped() {
        val root = tmp.newFolder()
        val zip = archive("ExtractMe/Save/level.dat" to "ld", "ExtractMe/Save/region/r.mca" to "r", "ExtractMe/readme.txt" to "x")
        assertEquals("Save", InstanceFiles(root).importWorldZip(zip, "fallback"))
        assertTrue(File(root, "Save/level.dat").isFile)
        assertFalse(File(root, "ExtractMe").exists())
    }

    @Test fun duplicateLevelDatEntriesDoNotLookAmbiguous() {
        val root = tmp.newFolder()
        // ZipOutputStream 拒绝完全同名条目，用 ./level.dat 构造“归一化后路径相同”的重复
        val zip = archive("level.dat" to "first", "./level.dat" to "second", "region/r.mca" to "r")
        assertEquals("w", InstanceFiles(root).importWorldZip(zip, "w"))
        assertEquals("second", File(root, "w/level.dat").readText())
    }

    @Test fun rejectsAmbiguousOrMissingWorldAndLeavesNothingBehind() {
        val root = tmp.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            InstanceFiles(root).importWorldZip(archive("a/level.dat" to "1", "b/level.dat" to "2"), "f")
        }
        assertThrows(IllegalArgumentException::class.java) {
            InstanceFiles(root).importWorldZip(archive("level.dat" to "1", "w/level.dat" to "2"), "f")
        }
        assertThrows(IllegalArgumentException::class.java) {
            InstanceFiles(root).importWorldZip(archive("mods/x.jar" to "j"), "f")
        }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun unsafeOrOversizedWorldZipLeavesNothingBehind() {
        val root = tmp.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            InstanceFiles(root).importWorldZip(archive("good/level.dat" to "g", "../evil" to "e"), "f")
        }
        assertFalse(File(root, "good").exists())
        assertThrows(IllegalArgumentException::class.java) {
            InstanceFiles(root).importWorldZip(archive("w/level.dat" to "toolarge"), "f", 2)
        }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun refusesToOverwriteExistingWorldFolder() {
        val root = tmp.newFolder()
        File(root, "MyWorld/region").mkdirs()
        File(root, "MyWorld/region/old.mca").writeText("old")
        assertThrows(IllegalArgumentException::class.java) {
            InstanceFiles(root).importWorldZip(archive("MyWorld/level.dat" to "new"), "f")
        }
        assertEquals("old", File(root, "MyWorld/region/old.mca").readText())
        assertFalse(File(root, "MyWorld/level.dat").exists())
    }

    @Test fun invalidFallbackWorldNameRejected() {
        val root = tmp.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            InstanceFiles(root).importWorldZip(archive("level.dat" to "d"), "../bad")
        }
        assertTrue(root.listFiles()!!.isEmpty())
    }
}
