package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [tokenPermissions] — the JWT `user_permissions` decode behind
 * the Create tab (`canPublish`) and the install lock chip (`canInstall`).
 *
 * These live here rather than against `PluginManagerAPIImpl` because that
 * constructor builds a Supabase client. The decode is the fragile part anyway:
 * base64url uses `-`/`_` and drops padding, while `Base64.getDecoder()` demands
 * the standard alphabet and correct padding — get it wrong and every gate
 * silently denies.
 */
class PluginPermissionGateTest {
    /** Build a structurally valid JWT whose payload is [payloadJson]. */
    private fun jwt(payloadJson: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString(payloadJson.toByteArray())
        return "$header.$payload.not-a-real-signature"
    }

    private fun tokenWithPermissions(vararg permissions: String): String =
        jwt("""{"sub":"u1","user_permissions":[${permissions.joinToString(",") { "\"$it\"" }}]}""")

    // -----------------------------------------------------------------------
    // The gate itself
    // -----------------------------------------------------------------------

    @Test
    fun `plugins-create admits the Create tab`() {
        val perms = tokenPermissions(tokenWithPermissions(PLUGIN_CREATE_PERMISSION, "api_key.create"))
        assertTrue(PLUGIN_CREATE_PERMISSION in perms)
    }

    @Test
    fun `plugins-admin-publish does not admit the Create tab`() {
        // The moderation permission is not a substitute for the authoring one:
        // its RLS policy authorizes updates to any plugin, with no author scoping.
        val perms = tokenPermissions(tokenWithPermissions("plugins.admin.publish"))
        assertFalse(PLUGIN_CREATE_PERMISSION in perms)
    }

    @Test
    fun `the gate constant is the authoring permission`() {
        assertEquals("plugins.create", PLUGIN_CREATE_PERMISSION)
    }

    @Test
    fun `a boss_plugin_admin claim set satisfies the tool-creator requirements`() {
        // What the role actually carries, against what tool-creator's manifest asks for.
        val perms = tokenPermissions(
            tokenWithPermissions(
                "plugins.create", "api_key.create",
                "user.read", "user.write", "user.update", "user.delete",
            ),
        )
        assertTrue(perms.containsAll(listOf("plugins.create", "api_key.create")))
        assertFalse(perms.any { it.startsWith("plugins.admin.") })
    }

    // -----------------------------------------------------------------------
    // Gate composition — the short-circuits, which a refactor can invert silently
    // -----------------------------------------------------------------------

    @Test
    fun `the global right requires plugins-create for a non-admin`() {
        assertTrue(canPublishGloballyWith(isAdmin = false, token = tokenWithPermissions("plugins.create")))
        assertFalse(canPublishGloballyWith(isAdmin = false, token = tokenWithPermissions("plugins.admin.publish")))
        assertFalse(canPublishGloballyWith(isAdmin = false, token = null))
    }

    @Test
    fun `the global right admits an admin holding nothing`() {
        assertTrue(canPublishGloballyWith(isAdmin = true, token = tokenWithPermissions()))
        // Even with no token at all — admin is decided by the host, not the claim.
        assertTrue(canPublishGloballyWith(isAdmin = true, token = null))
    }

    // -----------------------------------------------------------------------
    // The second way to be allowed: an organisation that admits you
    // -----------------------------------------------------------------------

    private fun target(slug: String, isSystem: Boolean = false) =
        PublishTarget(orgId = "id-$slug", slug = slug, name = slug.uppercase(), isSystem = isSystem)

    @Test
    fun `an organisation admits the Create tab with no permission at all`() {
        // The whole feature. An approved organisation's admin role carries organisation.admin and
        // organisation.read - no plugins.create anywhere - and its default publish_policy is
        // 'admins', so the server says can_publish for it.
        assertTrue(canPublishAnywhereWith(globalRight = false, targets = listOf(target("risa"))))
    }

    @Test
    fun `the system organisation alone does not admit anything`() {
        // Every signup is a member of @boss. If this admitted, "publish for your organisation"
        // would mean "publish to the platform's own store" for every user in the system - and the
        // Create tab would appear for all of them.
        assertFalse(canPublishAnywhereWith(globalRight = false, targets = listOf(target("boss", isSystem = true))))
    }

    @Test
    fun `no targets and no permission admits nothing`() {
        assertFalse(canPublishAnywhereWith(globalRight = false, targets = emptyList()))
    }

    @Test
    fun `the global right needs no target`() {
        // A boss_plugin_admin belonging to no organisation still publishes, to the BOSS store.
        assertTrue(canPublishAnywhereWith(globalRight = true, targets = emptyList()))
    }

    @Test
    fun `orgPublishTargets drops only the system organisation`() {
        val targets = listOf(target("boss", isSystem = true), target("risa"), target("acme"))
        assertEquals(listOf("risa", "acme"), orgPublishTargets(targets).map { it.slug })
    }

    @Test
    fun `canInstall treats an empty requirement list as open to everyone`() {
        // Legacy plugins predate the field and rely on this.
        assertTrue(canInstallWith(isAdmin = false, token = null, requiredPermissions = emptyList()))
    }

    @Test
    fun `canInstall needs every required permission, not just one`() {
        val token = tokenWithPermissions("plugins.create")
        assertFalse(
            canInstallWith(isAdmin = false, token = token, requiredPermissions = listOf("plugins.create", "api_key.create")),
            "holding one of two must not admit",
        )
        assertTrue(
            canInstallWith(
                isAdmin = false,
                token = tokenWithPermissions("plugins.create", "api_key.create"),
                requiredPermissions = listOf("plugins.create", "api_key.create"),
            ),
        )
    }

    @Test
    fun `canInstall lets an admin bypass requirements`() {
        assertTrue(
            canInstallWith(isAdmin = true, token = null, requiredPermissions = listOf("secret.read", "role.assign")),
        )
    }

    // -----------------------------------------------------------------------
    // base64url padding — one case per `length % 4` branch
    // -----------------------------------------------------------------------

    @Test
    fun `payloads of every padding length decode`() {
        // Vary the permission name's length so the encoded payload lands on each
        // residue class; assert every one round-trips rather than trusting that
        // the branch we happened to exercise is the only one that matters.
        val seen = mutableSetOf<Int>()
        for (pad in 0..7) {
            val name = "plugins.create" + "x".repeat(pad)
            val token = tokenWithPermissions(name)
            seen += token.split(".")[1].length % 4
            assertTrue(name in tokenPermissions(token), "failed to decode payload with filler '$name'")
        }
        // base64url without padding never yields length % 4 == 1.
        assertEquals(setOf(0, 2, 3), seen, "expected to exercise every reachable padding branch")
    }

    @Test
    fun `a payload containing base64url-only characters decodes`() {
        // '-' and '_' appear only in the URL alphabet; the standard decoder
        // rejects them, so the substitution in tokenPermissions is load-bearing.
        // ">>>" and "???" encode to sequences containing '+' and '/' in the
        // standard alphabet, hence '-' and '_' in the URL one.
        val token = jwt("""{"note":">>>???>>>???","user_permissions":["plugins.create"]}""")
        val body = token.split(".")[1]
        assertTrue(body.contains("-") || body.contains("_"), "test fixture did not exercise the URL alphabet")
        assertEquals(setOf("plugins.create"), tokenPermissions(token))
    }

    // -----------------------------------------------------------------------
    // Anything unreadable must DENY, never admit
    // -----------------------------------------------------------------------

    @Test
    fun `a null or blank token yields no permissions`() {
        assertEquals(emptySet(), tokenPermissions(null))
        assertEquals(emptySet(), tokenPermissions(""))
        assertEquals(emptySet(), tokenPermissions("   "))
    }

    @Test
    fun `a token without three segments yields no permissions`() {
        assertEquals(emptySet(), tokenPermissions("not-a-jwt"))
        assertEquals(emptySet(), tokenPermissions("only.two"))
        assertEquals(emptySet(), tokenPermissions("a.b.c.d"))
    }

    @Test
    fun `a token with no user_permissions claim yields no permissions`() {
        assertEquals(emptySet(), tokenPermissions(jwt("""{"sub":"u1","is_admin":false}""")))
    }

    @Test
    fun `an empty user_permissions claim yields no permissions`() {
        assertEquals(emptySet(), tokenPermissions(jwt("""{"user_permissions":[]}""")))
    }

    @Test
    fun `undecodable or non-JSON payloads yield no permissions`() {
        assertEquals(emptySet(), tokenPermissions("aaa.!!!not-base64!!!.ccc"))
        assertEquals(
            emptySet(),
            tokenPermissions("aaa." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not json at all".toByteArray()) + ".ccc"),
        )
    }

    @Test
    fun `a claim that is not an array yields no permissions`() {
        assertEquals(emptySet(), tokenPermissions(jwt("""{"user_permissions":"plugins.create"}""")))
    }

    @Test
    fun `non-string entries are skipped rather than crashing the decode`() {
        assertEquals(
            setOf("plugins.create"),
            tokenPermissions(jwt("""{"user_permissions":["plugins.create",{"a":1},[2]]}""")),
        )
    }
}
