package bb.produce.invoicing.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ServerConfigTest {
    private val base = mapOf(
        "PI_DB_URL" to "jdbc:postgresql://db:5432/pi",
        "PI_DB_USER" to "pi_app",
        "PI_DB_PASSWORD" to "s3cret-value",
    )

    @Test
    fun `missing password fails fast instead of defaulting`() {
        assertThrows<IllegalStateException> { ServerConfig.fromEnv(base - "PI_DB_PASSWORD") }
    }

    @Test
    fun `defaults are port 8080, all interfaces, no seeding`() {
        val c = ServerConfig.fromEnv(base)
        assertEquals(8080, c.httpPort)
        assertEquals("0.0.0.0", c.httpHost)
        assertFalse(c.seedSyntheticData)
    }

    @Test
    fun `toString never prints the database password`() {
        assertFalse(ServerConfig.fromEnv(base).toString().contains("s3cret-value"))
    }
}
