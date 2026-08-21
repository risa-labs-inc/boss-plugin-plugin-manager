package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.BossCompat
import ai.rever.boss.plugin.dynamic.pluginmanager.api.DownloadInfoResponse
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decoding `POST /plugin-store/download`, which is the last check before a jar replaces a working
 * one.
 *
 * Three shapes have to decode, and the reason is that the field this test exists for is **newer
 * than the deployed store**. An installed Toolbox meets a store that omits it; a new Toolbox meets
 * a store that sends it; and because `plugin_versions.min_boss_version` is `TEXT DEFAULT '1.0.0'`
 * with no NOT NULL, a row can send it as an explicit null. A default covers only the absent case,
 * so the null one needs a nullable type or it throws and takes the whole install with it - the same
 * trap that emptied every secret panel when a column was added ahead of installed builds.
 *
 * The key names are matched against `supabase/functions/plugin-store/routes/download.ts`, which
 * returns camelCase. A fixture derived from the model instead would agree with the model by
 * construction and prove nothing, and `ignoreUnknownKeys` means a wrong name is silently null.
 */
class DownloadInfoDecodingTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    private val property = "boss.app.version"

    @AfterTest
    fun clearHostVersion() {
        System.clearProperty(property)
    }

    /** The response shape as `download.ts` writes it, floor included. */
    private fun body(floor: String) =
        """
        {
          "downloadUrl": "https://example.invalid/signed",
          "sha256": "6f1b",
          "signature": "c2ln",
          "version": "1.2.22",
          "size": 1234,
          "versionId": "9799e05b-2a78-467b-9160-8bef6558c6f4",
          "minIpcVersion": "1.0.0",
          "minBossVersion": $floor,
          "requiredPermissions": []
        }
        """.trimIndent()

    @Test
    fun `a floor on the wire is read`() {
        val info = json.decodeFromString<DownloadInfoResponse>(body("\"9.4.23\""))
        assertEquals("9.4.23", info.minBossVersion)
        System.setProperty(property, "9.4.22")
        assertEquals(
            "Needs BOSS 9.4.23 (you have 9.4.22)",
            BossCompat.requirement(info.minBossVersion),
            "the decoded floor is not the one the gate refuses on",
        )
    }

    @Test
    fun `an explicit null floor decodes`() {
        // Reachable from the database, and the case a non-nullable field would throw on - failing
        // an install because a floor was absent.
        val info = json.decodeFromString<DownloadInfoResponse>(body("null"))
        assertNull(info.minBossVersion)
        System.setProperty(property, "9.4.22")
        assertNull(BossCompat.requirement(info.minBossVersion), "a null floor must not block")
    }

    @Test
    fun `a store that does not send the field decodes`() {
        // Every currently deployed store. This plugin has to install from it exactly as before.
        val legacy =
            """
            {
              "downloadUrl": "https://example.invalid/signed",
              "sha256": "6f1b",
              "version": "1.2.22",
              "size": 1234,
              "versionId": "9799e05b-2a78-467b-9160-8bef6558c6f4",
              "minIpcVersion": "1.0.0",
              "requiredPermissions": []
            }
            """.trimIndent()
        val info = json.decodeFromString<DownloadInfoResponse>(legacy)
        assertNull(info.minBossVersion)
        System.setProperty(property, "9.4.22")
        assertNull(
            BossCompat.requirement(info.minBossVersion),
            "an older store must not make every install fail",
        )
    }

    @Test
    fun `the rest of the response still decodes`() {
        // The floor was added to a model the install path already depended on; nothing else may
        // have shifted.
        val info = json.decodeFromString<DownloadInfoResponse>(body("\"9.4.23\""))
        assertEquals("https://example.invalid/signed", info.downloadUrl)
        assertEquals("1.2.22", info.version)
        assertEquals(1234L, info.size)
        assertEquals("1.0.0", info.minIpcVersion)
        assertEquals("c2ln", info.signature)
        assertTrue(info.requiredPermissions.isEmpty())
    }
}
