package io.github.evgenykon.mybooks.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Windows build used to be updated by hand: nothing told the user that a release exists and
 * the MSI kept version 1.0.0, so Windows Installer never saw an upgrade. These tests cover the
 * release parsing and the version comparison that drive the banner.
 */
class UpdateCheckerTest {

    private val release = """
        {
          "tag_name": "v0.4.6",
          "assets": [
            {"name": "my-books-android.apk", "browser_download_url": "https://x/apk", "size": 100},
            {"name": "MyBooks-1.0.6.msi", "browser_download_url": "https://x/msi", "size": 99000000}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesTheMsiAsset() {
        val update = assertNotNull(UpdateChecker.parse(release))
        assertEquals("0.4.6", update.version)
        assertEquals("https://x/msi", update.msiUrl)
        assertEquals("MyBooks-1.0.6.msi", update.msiName)
        assertEquals(99000000L, update.size)
    }

    @Test
    fun ignoresPayloadWithoutMsi() {
        val body = """{"tag_name":"v1.0.0","assets":[{"name":"x.zip","browser_download_url":"https://x","size":1}]}"""
        assertNull(UpdateChecker.parse(body))
    }

    @Test
    fun brokenPayloadIsNull() {
        assertNull(UpdateChecker.parse("not json at all"))
    }

    @Test
    fun comparesVersions() {
        assertTrue(UpdateChecker.isNewer("0.4.6", "0.4.5"))
        assertTrue(UpdateChecker.isNewer("0.5.0", "0.4.9"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"))
        assertTrue(!UpdateChecker.isNewer("0.4.5", "0.4.5"))
        assertTrue(!UpdateChecker.isNewer("0.4.5", "0.4.6"))
        assertTrue(UpdateChecker.isNewer("0.4.10", "0.4.9"))
    }

    @Test
    fun currentVersionComesFromResources() {
        // The resource is generated from version.txt; a missing resource would silently make
        // every release look newer than the running app.
        assertTrue(UpdateChecker.currentVersion.matches(Regex("\\d+\\.\\d+\\.\\d+")), UpdateChecker.currentVersion)
    }
}
