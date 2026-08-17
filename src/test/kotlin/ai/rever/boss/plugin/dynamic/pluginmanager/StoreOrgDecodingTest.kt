package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.StoreOrgListResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Decoding the store's `/list` response for the owning organisation.
 *
 * The body below is a VERBATIM row captured from
 * `https://api.risaboss.com/functions/v1/plugin-store/list`, trimmed to two entries. That matters:
 * the failure this guards is a wire name that does not exist, and a fixture invented from the
 * model would agree with the model by construction. The same mistake shipped in the organisation
 * plugin, where `requested_by_email` was decoded from an RPC that projects `requester_email` - it
 * silently produced null for every row, because the decoder ignores unknown keys.
 *
 * `ignoreUnknownKeys` is what makes it silent, and it cannot be turned off: the store returns
 * twenty fields this lookup has no interest in, and the database is deployed ahead of the plugin.
 * So the only protection is asserting against a real body.
 */
class StoreOrgDecodingTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val liveBody = """
        {
          "plugins": [
            {
              "id": "9799e05b-2a78-467b-9160-8bef6558c6f4",
              "url": "https://github.com/risa-labs-inc/boss-plugin-plugin-manager",
              "tags": [],
              "type": "panel",
              "orgId": "b2e45a31-8183-4ba4-9066-10ce916622a9",
              "author": "Risa Labs",
              "rating": 0,
              "iconUrl": "",
              "orgSlug": "boss",
              "version": "1.9.13",
              "pluginId": "ai.rever.boss.plugin.dynamic.pluginmanager",
              "verified": false,
              "updatedAt": "2026-08-15T08:42:55.430238+00:00",
              "apiVersion": "1.0.73",
              "visibility": "public",
              "description": "Toolbox - self-contained plugin store",
              "displayName": "Toolbox",
              "ratingCount": 0,
              "downloadCount": 291,
              "requiredPermissions": []
            },
            {
              "id": "11111111-2222-4333-8444-555555555555",
              "orgId": "b2e45a31-8183-4ba4-9066-10ce916622a9",
              "orgSlug": "boss",
              "pluginId": "ai.rever.boss.plugin.dynamic.organisation",
              "displayName": "Organisation",
              "visibility": "public"
            }
          ],
          "page": 1,
          "pageSize": 20,
          "totalCount": 40
        }
    """.trimIndent()

    @Test
    fun `the org fields decode from a real store response`() {
        val decoded = json.decodeFromString(StoreOrgListResponse.serializer(), liveBody)

        assertEquals(2, decoded.plugins.size)
        val toolbox = decoded.plugins.first()
        // The three names the enrichment depends on. If any of them drifts, the badge silently
        // disappears rather than erroring, which is why they are asserted individually.
        assertEquals("ai.rever.boss.plugin.dynamic.pluginmanager", toolbox.pluginId)
        assertEquals("boss", toolbox.orgSlug)
        assertEquals("b2e45a31-8183-4ba4-9066-10ce916622a9", toolbox.orgId)
    }

    @Test
    fun `the envelope carries the paging fields the lookup stops on`() {
        val decoded = json.decodeFromString(StoreOrgListResponse.serializer(), liveBody)
        // The lookup pages until it has totalCount rows. A pageSize the server clamps below what
        // was asked for is exactly why it cannot trust a single response to be complete: this real
        // body asked for nothing and came back capped at 20 of 40.
        assertEquals(40, decoded.totalCount)
        assertEquals(20, decoded.pageSize)
        assertEquals(1, decoded.page)
        assertTrue(decoded.plugins.size < decoded.totalCount, "the fixture must be a partial page")
    }

    @Test
    fun `a row missing the org fields decodes to null rather than throwing`() {
        // A plugin whose org_id is NULL is reachable: the column is nullable, and the trigger that
        // fills it resolves `slug = 'boss'` with SELECT INTO, which leaves NULL if that row is
        // absent. The badge must then be absent, not a crash on the whole list.
        val body = """{"plugins":[{"pluginId":"a.b.c"}],"totalCount":1}"""
        val decoded = json.decodeFromString(StoreOrgListResponse.serializer(), body)
        assertEquals("a.b.c", decoded.plugins.single().pluginId)
        // isNullOrEmpty rather than assertEquals(null, ...) on purpose. This test has to compile
        // against a NON-nullable model too, or the mutation that reintroduces the bug fails at
        // compile time and proves only the declared type - not that an explicit null survives the
        // decode, which is the actual property.
        assertTrue(decoded.plugins.single().orgSlug.isNullOrEmpty())
        assertTrue(decoded.plugins.single().orgId.isNullOrEmpty())
    }

    @Test
    fun `an explicit null org slug decodes rather than failing the whole list`() {
        // Distinct from the key being absent: the RPC projects `orgSlug` from a subquery, so a
        // plugin with a NULL org_id yields the key present and null.
        val body = """{"plugins":[{"pluginId":"a.b.c","orgId":null,"orgSlug":null}],"totalCount":1}"""
        // The decode itself is the assertion: with a non-nullable field this line throws
        // JsonDecodingException and takes the WHOLE list with it, so no plugin gets a badge.
        val decoded = json.decodeFromString(StoreOrgListResponse.serializer(), body)
        assertEquals(1, decoded.plugins.size)
        assertTrue(decoded.plugins.single().orgSlug.isNullOrEmpty())
        assertTrue(decoded.plugins.single().orgId.isNullOrEmpty())
    }

    @Test
    fun `an unexpected top-level shape yields no rows rather than throwing`() {
        // The store answers errors as {"error":"..."} with no plugins array. The lookup treats
        // that as "no organisations known" and renders no badges.
        val decoded = json.decodeFromString(
            StoreOrgListResponse.serializer(),
            """{"error":"Plugin not found"}""",
        )
        assertTrue(decoded.plugins.isEmpty())
        assertEquals(0, decoded.totalCount)
    }
}
