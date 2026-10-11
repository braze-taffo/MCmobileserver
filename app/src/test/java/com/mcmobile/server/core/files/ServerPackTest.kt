package com.mcmobile.server.core.files

import com.mcmobile.server.core.launch.LaunchSpecBuilder
import com.mcmobile.server.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.*
import java.util.zip.ZipEntry

class ServerPackTest {
    @get:Rule val temp = TemporaryFolder()
    private fun jar(root: File, name: String, main: String, classpath: String? = null, installer: Boolean = false) {
        val manifest = Manifest().apply { mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"; mainAttributes[Attributes.Name.MAIN_CLASS] = main; if (classpath != null) mainAttributes[Attributes.Name.CLASS_PATH] = classpath }
        File(root, name).parentFile!!.mkdirs()
        JarOutputStream(File(root, name).outputStream(), manifest).use { if (installer) { it.putNextEntry(ZipEntry("install_profile.json")); it.write("{}".toByteArray()); it.closeEntry() } }
    }
    private fun instance(root: File, type: ServerType, jar: String? = null, args: String? = null) = ServerInstance("id", "pack", type, "1.21.1", dirName = root.name, createdMs = 0, installed = true, launchJar = jar, launchArgsFile = args)
    @Test fun detectsWrapperFoldersWithoutDiscardingConfigOrWorlds() {
        val outer = temp.newFolder(); val root = File(outer, "distribution/server").apply { mkdirs() }
        jar(root, "custom-1.21.1.jar", "net.minecraft.bundler.Main")
        File(root, "config").mkdir(); File(root, "config/custom.toml").writeText("keep")
        File(root, "world").mkdir(); File(root, "world/level.dat").writeText("world")
        assertEquals(listOf(root), ServerPack.findRoots(outer))
        val detection = ServerPack.inspect(root); assertEquals("1.21.1", detection.mcVersion); assertEquals("custom-1.21.1.jar", detection.launchJar)
        val target = File(temp.newFolder(), "copy"); ServerPack.copy(root, target)
        assertEquals("keep", File(target, "config/custom.toml").readText()); assertEquals("world", File(target, "world/level.dat").readText())
    }
    @Test fun ambiguousJarsRequireExplicitSelection() {
        val root = temp.newFolder(); jar(root, "one-1.21.1.jar", "one.Main"); jar(root, "two.jar", "two.Main")
        assertNull(ServerPack.inspect(root).launchJar); assertEquals(2, ServerPack.inspect(root).jars.size)
        ServerPack.verify(instance(root, ServerType.VANILLA, "two.jar"), root)
        assertEquals("two.Main", LaunchSpecBuilder.build(instance(root, ServerType.VANILLA, "two.jar"), root, temp.root).spec!!.mainClass)
    }
    @Test fun installersAreNotAcceptedAsReadyPacks() {
        val root = temp.newFolder(); jar(root, "forge-installer.jar", "net.minecraftforge.installer.SimpleInstaller", installer = true)
        assertTrue(ServerPack.inspect(root).jars.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { ServerPack.verify(instance(root, ServerType.FORGE, "forge-installer.jar"), root) }
    }
    @Test fun neoforge1211KeepsArgfilesAndChecksLibraries() {
        val root = temp.newFolder(); val relative = "libraries/net/neoforged/neoforge/21.1.219/unix_args.txt"
        File(root, relative).apply { parentFile!!.mkdirs(); writeText("-cp libraries/bootstrap.jar net.neoforged.Main --launchTarget neoforgeserver") }
        val detection = ServerPack.inspect(root); assertEquals(ServerType.NEOFORGE, detection.type); assertEquals("1.21.1", detection.mcVersion)
        val inst = instance(root, ServerType.NEOFORGE, args = relative)
        assertThrows(IllegalArgumentException::class.java) { ServerPack.verify(inst, root) }
        jar(root, "libraries/bootstrap.jar", "net.neoforged.Main"); ServerPack.verify(inst, root)
        val spec = LaunchSpecBuilder.build(inst, root, temp.root).spec!!
        assertEquals("net.neoforged.Main", spec.mainClass); assertTrue("neoforgeserver" in spec.args)
    }
    @Test fun legacyForgeUsesManifestClassPathAndJava8() {
        val root = temp.newFolder(); jar(root, "libraries/dependency.jar", "unused.Main")
        jar(root, "forge-1.12.2.jar", "net.minecraftforge.fml.relauncher.ServerLaunchWrapper", "libraries/dependency.jar")
        val inst = instance(root, ServerType.FORGE, "forge-1.12.2.jar").copy(mcVersion = "1.12.2", javaMajor = 8)
        ServerPack.verify(inst, root)
        val spec = LaunchSpecBuilder.build(inst, root, temp.root).spec!!
        assertEquals(8, spec.javaMajor); assertTrue(spec.classpath.contains(File(root, "libraries/dependency.jar").absolutePath))
    }
    @Test fun explicitEntryPointsCannotEscapeRoot() {
        val root = temp.newFolder(); val outer = File(root.parentFile, "outside.jar"); jar(root.parentFile, outer.name, "outside.Main")
        assertThrows(IllegalArgumentException::class.java) { ServerPack.verify(instance(root, ServerType.VANILLA, "../outside.jar"), root) }
        assertNull(LaunchSpecBuilder.build(instance(root, ServerType.VANILLA, "../outside.jar"), root, temp.root).spec)
    }
    @Test fun installedPackLaunchSettingsRoundTrip() {
        val inst = instance(temp.newFolder(), ServerType.FABRIC, "custom-fabric.jar").copy(programArgs = listOf("nogui", "--safeMode"), javaMajor = 17)
        assertEquals(inst, InstanceManifest.decode(InstanceManifest.encode(listOf(inst)))!!.single())
    }
    @Test fun literalStartupScriptSelectsJarAndPreservesOptions() {
        val root = temp.newFolder(); jar(root, "one-1.21.1.jar", "one.Main"); jar(root, "two.jar", "two.Main")
        File(root, "run.bat").writeText("@echo off\njava -Xmx4G -Dcustom=true -jar two.jar nogui --safeMode %*\npause\n")
        val found = ServerPack.inspect(root)
        assertEquals("two.jar", found.launchJar); assertEquals(listOf("-Dcustom=true"), found.jvmArgs)
        assertEquals(listOf("nogui", "--safeMode"), found.programArgs)
    }
    @Test fun scriptVariablesAreNotExecutedOrGuessed() {
        val root = temp.newFolder(); jar(root, "one.jar", "one.Main"); jar(root, "two.jar", "two.Main")
        File(root, "run.sh").writeText("java -Dpath=" + "$" + "HOME -jar one.jar\n")
        assertNull(ServerPack.inspect(root).launchJar)
    }
    @Test fun jniClasspathExpandsOnlyJarFilesInWildcardDirectory() {
        val root = temp.newFolder(); jar(root, "lib/b.jar", "b.Main"); jar(root, "lib/a.jar", "a.Main")
        File(root, "lib/readme.txt").writeText("skip")
        val inst = instance(root, ServerType.VANILLA).copy(launchMainClass = "a.Main", launchClasspath = listOf("lib/*"))
        val spec = LaunchSpecBuilder.build(inst, root, temp.root).spec!!
        assertEquals(listOf(File(root, "lib/a.jar").absolutePath, File(root, "lib/b.jar").absolutePath).joinToString(":"), spec.classpath)
    }
    @Test fun fabricBootstrapWithoutVanillaCoreIsIncomplete() {
        val root = temp.newFolder(); jar(root, "fabric-server-launch.jar", "net.fabricmc.installer.ServerLauncher")
        assertThrows(IllegalArgumentException::class.java) { ServerPack.verify(instance(root, ServerType.FABRIC, "fabric-server-launch.jar"), root) }
        jar(root, "server.jar", "net.minecraft.bundler.Main")
        ServerPack.verify(instance(root, ServerType.FABRIC, "fabric-server-launch.jar"), root)
    }
}
