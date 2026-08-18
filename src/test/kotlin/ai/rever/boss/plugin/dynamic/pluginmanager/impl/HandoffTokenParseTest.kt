package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reading the mint response.
 *
 * Null is the ORDINARY answer here, not a fault: minting is members-only, so anyone outside the
 * owning organisation is refused and opens the plugin page signed out. Treating a refusal as an
 * error - or worse, as an empty token - is what would put `?t=` on the URL with nothing after it.
 */
class HandoffTokenParseTest {

    @Test
    fun `a successful mint yields the token`() {
        assertEquals("abc123", parseHandoffToken("""{"success":true,"token":"abc123"}"""))
    }

    @Test
    fun `a refusal yields null, not an empty token`() {
        assertNull(parseHandoffToken("""{"success":false,"error":"Permission denied"}"""))
        // success true but no token: the envelope changed shape. Better none than "".
        assertNull(parseHandoffToken("""{"success":true}"""))
        assertNull(parseHandoffToken("""{"success":true,"token":""}"""))
        assertNull(parseHandoffToken("""{"success":true,"token":"   "}"""))
    }

    @Test
    fun `garbage does not throw`() {
        // rpc() returns whatever the transport gave it; this runs on the UI path.
        assertNull(parseHandoffToken(null))
        assertNull(parseHandoffToken(""))
        assertNull(parseHandoffToken("not json"))
        assertNull(parseHandoffToken("[]"))
        assertNull(parseHandoffToken("""{"token":"orphan"}"""))
    }
}
