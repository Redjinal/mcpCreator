package bb.produce.invoicing.shared

import bb.produce.invoicing.shared.ServerEndpoint.ParseResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ServerEndpointTest {

    private fun valid(raw: String, cleartext: Boolean = false): ServerEndpoint {
        val result = ServerEndpoint.parse(raw, cleartext)
        assertTrue(result is ParseResult.Valid, "expected valid for '$raw' but was $result")
        return (result as ParseResult.Valid).endpoint
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `blank or missing configuration is NotConfigured, not a default host`(raw: String) {
        assertEquals(ParseResult.NotConfigured, ServerEndpoint.parse(raw, allowCleartext = true))
        assertEquals(ParseResult.NotConfigured, ServerEndpoint.parse(null, allowCleartext = true))
    }

    @Test
    fun `https endpoint is normalised without trailing slash`() {
        val e = valid("https://Office-Server.lan:8443/")
        assertEquals("https://office-server.lan:8443", e.baseUrl)
        assertFalse(e.isCleartext)
        assertEquals("https://office-server.lan:8443/api/v1/health", e.apiUrl("/health"))
    }

    @Test
    fun `path prefix is preserved for reverse-proxy deployments`() {
        assertEquals("https://example.test/pi/api/v1/health", valid("https://example.test/pi/").apiUrl("/health"))
    }

    @Test
    fun `cleartext is rejected unless the build explicitly allows it`() {
        assertTrue(ServerEndpoint.parse("http://office.lan:8080", allowCleartext = false) is ParseResult.Invalid)
        assertTrue(valid("http://office.lan:8080", cleartext = true).isCleartext)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "ftp://office.lan",
            "office.lan:8080",
            "https://user:secret@office.lan",
            "https://office.lan/?token=abc",
            "https://office.lan/#frag",
            "https:///nohost",
            "https://office lan",
        ],
    )
    fun `unsafe or malformed URLs are rejected`(raw: String) {
        val result = ServerEndpoint.parse(raw, allowCleartext = true)
        assertTrue(result is ParseResult.Invalid, "expected invalid for '$raw' but was $result")
    }

    @Test
    fun `api version prefix is v1`() {
        assertEquals("/api/v1", ApiVersion.PATH_PREFIX)
    }
}
