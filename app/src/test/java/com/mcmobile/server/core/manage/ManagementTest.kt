package com.mcmobile.server.core.manage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ManagementTest {
    @Test fun relocatedInstanceKeepsExistingBackupsReachable() {
        val privateFiles = temp.newFolder(); val root = server()
        val original = com.mcmobile.server.data.ServerInstance("id", "pack", com.mcmobile.server.data.ServerType.FABRIC, "1.21.1", dirName = "pack", createdMs = 0)
        val oldBackups = Backups.directory(privateFiles, original, root)
        val relocated = original.copy(storage = com.mcmobile.server.data.StorageKind.EXTERNAL, externalRoot = "/shared", backupRoot = oldBackups.absolutePath)
        val saved = com.mcmobile.server.data.InstanceManifest.decode(com.mcmobile.server.data.InstanceManifest.encode(listOf(relocated)))!!.single()
        assertEquals(oldBackups, Backups.directory(privateFiles, saved, File("/shared/pack")))
    }
    @get:Rule val temp = TemporaryFolder()
    private fun server(): File = temp.newFolder().apply {
        File(this, "server.properties").writeText("level-name=world\nonline-mode=false\n")
        File(this, "world").mkdir(); File(this, "world/level.dat").writeText("saved world")
        File(this, "mods").mkdir(); File(this, "mods/a.jar").writeText("mod")
        File(this, ".mcs-instance.json").writeText("original registration")
    }
    @Test fun fullBackupRestoresDirectoriesAndPreservesRegistration() {
        val root = server(); val archive = Backups.create(root, temp.newFolder(), true)
        File(root, "world/level.dat").writeText("changed"); File(root, "extra.txt").writeText("remove me")
        val preview = Backups.preview(root, archive)
        assertEquals("server", preview.info.kind); assertTrue("mods" in preview.replacements)
        Backups.restore(root, preview)
        assertEquals("saved world", File(root, "world/level.dat").readText()); assertFalse(File(root, "extra.txt").exists())
        assertEquals("original registration", File(root, ".mcs-instance.json").readText())
    }
    @Test fun worldBackupLeavesModsAndOtherWorldsUntouched() {
        val root = server(); val archive = Backups.create(root, temp.newFolder(), false)
        File(root, "world/level.dat").writeText("changed")
        File(root, "other").mkdir(); File(root, "other/level.dat").writeText("other world")
        Backups.restore(root, Backups.preview(root, archive))
        assertEquals("saved world", File(root, "world/level.dat").readText()); assertEquals("mod", File(root, "mods/a.jar").readText())
        assertEquals("other world", File(root, "other/level.dat").readText())
    }
    @Test fun invalidBackupNeverMutatesServer() {
        val root = server(); val archive = temp.newFile().apply { writeText("not a ZIP") }
        assertThrows(java.util.zip.ZipException::class.java) { Backups.preview(root, archive) }
        assertEquals("saved world", File(root, "world/level.dat").readText())
    }
    @Test fun backupCannotBeStoredInsideServer() {
        val root = server()
        assertThrows(IllegalArgumentException::class.java) { Backups.create(root, File(root, "backups"), true) }
    }
    @Test fun offlinePlayersUseStableUuidsAndPreserveOtherEntries() {
        val root = server()
        Players.offline(root, "op", "Alice", ""); Players.offline(root, "op", "Bob", "")
        val alice = Players.read(root).first { it.name == "Alice" }
        Players.offline(root, "whitelist add", "Alice", "")
        assertEquals(alice.uuid, Players.read(root).first { it.name == "Alice" }.uuid)
        Players.offline(root, "deop", "Alice", "")
        assertTrue("白名单" in Players.read(root).first { it.name == "Alice" }.lists)
        assertTrue("OP" in Players.read(root).first { it.name == "Bob" }.lists)
    }
    @Test fun onlineModeNeverGuessesAnOfflineUuid() {
        val root = server(); File(root, "server.properties").writeText("online-mode=true\n")
        assertThrows(IllegalArgumentException::class.java) { Players.offline(root, "op", "Alice", "") }
        assertFalse(File(root, "ops.json").exists())
    }
    @Test fun playerAndScheduledCommandsRejectInjectedLines() {
        assertThrows(IllegalArgumentException::class.java) { Players.command("op", "Alice\nstop") }
        assertThrows(IllegalArgumentException::class.java) { Players.command("ban", "Alice", "reason\nstop") }
        assertThrows(IllegalArgumentException::class.java) { TaskStore.validate(ServerTask("id", "server", "command", "COMMAND", 5, "say hello\nstop")) }
    }
    @Test fun taskStorePersistsDisabledTasksAndRejectsUnknownActions() {
        val store = TaskStore(File(temp.root, "tasks.json")); val task = ServerTask("id", "server", "world backup", "BACKUP_WORLD", 30, enabled = false)
        store.save(listOf(task)); assertEquals(listOf(task), store.load())
        assertThrows(IllegalArgumentException::class.java) { store.save(listOf(task.copy(action = "START"))) }
        assertEquals(listOf(task), store.load())
    }
    @Test fun exportedWorldBackupChoosesMainWorldWithoutSidecar() {
        val root = server()
        File(root, "world_nether").mkdir(); File(root, "world_nether/level.dat").writeText("nether")
        val archive = Backups.create(root, temp.newFolder(), false)
        File(archive.parentFile, "${archive.name}.json").delete()
        val preview = Backups.preview(root, archive)
        assertEquals("world", preview.info.kind); assertEquals("world", preview.info.level)
        preview.staging.deleteRecursively()
    }
    @Test fun untrustedBackupSidecarCannotSelectOutsideWorld() {
        val root = server(); val archive = Backups.create(root, temp.newFolder(), false)
        File(archive.parentFile, "${archive.name}.json").writeText("{\"kind\":\"world\",\"level\":\"../outside\",\"created\":0}")
        assertThrows(IllegalArgumentException::class.java) { Backups.preview(root, archive) }
        assertEquals("saved world", File(root, "world/level.dat").readText())
    }
}
