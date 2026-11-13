package bb.produce.invoicing.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HealthRouteTest {

    @Test
    fun `health is ok when database answers`() = testApplication {
        application { module { "1" } }
        val response = client.get("/api/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("1", response.headers["X-PI-Api-Version"])
        val body = response.bodyAsText()
        assertTrue(body.contains("\"database\":\"up\""), body)
        assertTrue(body.contains("\"schemaVersion\":\"1\""), body)
    }

    @Test
    fun `health is 503 and leaks no error detail when database is down`() = testApplication {
        application { module { throw java.sql.SQLException("connection refused to secret-host:5432") } }
        val response = client.get("/api/v1/health")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"database\":\"down\""), body)
        assertFalse(body.contains("secret-host"), body)
    }

    @Test
    fun `no other routes are exposed in task 00`() = testApplication {
        application { module { "1" } }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/invoices").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/").status)
    }
}
