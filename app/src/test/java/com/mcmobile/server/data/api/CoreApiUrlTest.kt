package com.mcmobile.server.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** URL 拼接是纯字符串逻辑，但错一段就 404，值得锁死 */
class CoreApiUrlTest {

    @Test
    fun fabricServerJarUrl_containsInstallerSegment() {
        // meta 只提供 /{game}/{loader}/{installer}/server/jar；缺 installer 段的旧路径实测 404
        val url = CoreApi.fabricServerJarUrl("1.21.1", "0.19.5", "1.1.2")
        assertEquals(
            "https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.19.5/1.1.2/server/jar",
            url,
        )
        assertTrue(url.endsWith("/0.19.5/1.1.2/server/jar"))
    }

    @Test
    fun installerUrls() {
        assertEquals(
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/26.2.0.88/neoforge-26.2.0.88-installer.jar",
            CoreApi.neoForgeInstallerUrl("26.2.0.88"),
        )
        assertEquals(
            "https://maven.minecraftforge.net/net/minecraftforge/forge/1.21.1-52.0.1/forge-1.21.1-52.0.1-installer.jar",
            CoreApi.forgeInstallerUrl("1.21.1", "52.0.1"),
        )
    }

    @Test
    fun newestStable_prefersStableAndFallsBackToFirst() {
        val mixed = listOf(
            CoreApi.FabricArtifact("0.19.5", stable = true),
            CoreApi.FabricArtifact("0.20.0-beta", stable = false),
        )
        assertEquals("0.19.5", CoreApi.newestStable(mixed))

        val allUnstable = listOf(
            CoreApi.FabricArtifact("0.20.0", stable = false),
            CoreApi.FabricArtifact("0.19.9", stable = false),
        )
        assertEquals("0.20.0", CoreApi.newestStable(allUnstable))
        assertEquals(null, CoreApi.newestStable(emptyList()))
    }
}
