package com.mcmobile.server.core.update

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {
    private fun release(tag: String = "v1.2.0", name: String = "mcmobileserver-1.2.0-arm64-v8a.apk", extra: String = ""): String = """
        {"tag_name":"$tag","draft":false,"prerelease":false,"body":"New features",
        "assets":[{"name":"$name","state":"uploaded","size":1234,
        "browser_download_url":"https://github.com/braze-taffo/MCmobileserver/releases/download/$tag/$name"}]$extra}
    """.trimIndent()

    @Test fun comparesNumericVersionsInsteadOfStrings() {
        assertTrue(UpdateChecker.newer("v1.10.0", "1.9.9"))
        assertTrue(UpdateChecker.newer("v2.0.0", "1.99.99"))
        assertFalse(UpdateChecker.newer("v1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.newer("v1.0.9", "1.1.0"))
        assertThrows(IOException::class.java) { UpdateChecker.newer("v2.0.0-beta", "1.0.0") }
    }

    @Test fun returnsNotesAndMatchingApk() {
        val result = UpdateChecker.parse(release(), "1.0.0", listOf("arm64-v8a")) as UpdateResult.Available
        assertEquals("1.2.0", result.release.version)
        assertEquals("New features", result.release.notes)
        assertTrue(result.release.apkUrl.endsWith("mcmobileserver-1.2.0-arm64-v8a.apk"))
    }

    @Test fun excludesDraftsAndPrereleases() {
        assertEquals(UpdateResult.NoRelease, UpdateChecker.parse(release().replace("\"draft\":false", "\"draft\":true"), "1.0.0", listOf("arm64-v8a")))
        assertEquals(UpdateResult.NoRelease, UpdateChecker.parse(release().replace("\"prerelease\":false", "\"prerelease\":true"), "1.0.0", listOf("arm64-v8a")))
    }

    @Test fun doesNotOfferEqualOrOlderVersion() {
        assertEquals(UpdateResult.Current, UpdateChecker.parse(release(), "1.2.0", listOf("arm64-v8a")))
        assertEquals(UpdateResult.Current, UpdateChecker.parse(release(), "2.0.0", listOf("arm64-v8a")))
    }

    @Test fun rejectsWrongAbiDebugAndMissingApk() {
        assertEquals(UpdateResult.NoCompatibleApk, UpdateChecker.parse(release(), "1.0.0", listOf("x86_64")))
        assertEquals(UpdateResult.NoCompatibleApk, UpdateChecker.parse(release(name = "app-debug.apk"), "1.0.0", listOf("arm64-v8a")))
        assertEquals(UpdateResult.NoCompatibleApk, UpdateChecker.parse(release().replace("\"size\":1234", "\"size\":0"), "1.0.0", listOf("arm64-v8a")))
    }

    @Test fun allowsUniversalApkFallback() {
        assertTrue(UpdateChecker.parse(release(name = "mcmobileserver-1.2.0-universal.apk"), "1.0.0", listOf("x86_64")) is UpdateResult.Available)
    }

    @Test fun rejectsDownloadOutsideProject() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateChecker.parse(release().replace("https://github.com/braze-taffo/MCmobileserver", "https://example.org/other"), "1.0.0", listOf("arm64-v8a"))
        }
    }

    @Test fun noReleaseIsDifferentFromHttpFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(429))
            val checker = UpdateChecker(OkHttpClient(), server.url("/releases/latest").toString())
            assertEquals(UpdateResult.NoRelease, checker.check("1.0.0", listOf("arm64-v8a")))
            try {
                checker.check("1.0.0", listOf("arm64-v8a"))
                fail("Rate limit must not be reported as up-to-date")
            } catch (expected: IOException) {
                assertTrue(expected.message!!.contains("请求受限"))
            }
        }
    }

    @Test fun checksHttpResponseAndSendsNoCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(release()))
            val checker = UpdateChecker(OkHttpClient(), server.url("/releases/latest").toString())
            assertTrue(checker.check("1.0.0", listOf("arm64-v8a")) is UpdateResult.Available)
            val request = server.takeRequest()
            assertEquals("application/vnd.github+json", request.getHeader("Accept"))
            assertNull(request.getHeader("Authorization"))
        }
    }
}
