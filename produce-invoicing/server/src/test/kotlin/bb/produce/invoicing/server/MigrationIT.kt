package bb.produce.invoicing.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.SQLException

/** Real PostgreSQL via Testcontainers (requires a Docker daemon). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationIT {
    private val pg = PostgreSQLContainer("postgres:17-alpine")
    private lateinit var ds: HikariDataSource

    @BeforeAll
    fun start() {
        pg.start()
        ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = pg.jdbcUrl; username = pg.username; password = pg.password
        })
    }

    @AfterAll
    fun stop() {
        ds.close(); pg.stop()
    }

    @Test
    fun `migrations apply, are repeatable, and synthetic seed is idempotent`() {
        assertEquals("1", Database.migrate(ds))
        assertEquals("1", Database.migrate(ds)) // second run is a no-op
        Database.seedSynthetic(ds)
        Database.seedSynthetic(ds)
        ds.connection.use { c ->
            c.createStatement().executeQuery("SELECT count(*), min(currency), min(time_zone) FROM business").use { rs ->
                rs.next()
                assertEquals(1, rs.getInt(1))
                assertEquals("BBD", rs.getString(2))
                assertEquals("America/Barbados", rs.getString(3))
            }
        }
        assertEquals("1", JdbcDatabaseProbe(ds).schemaVersion())
    }

    @Test
    fun `schema rejects invalid business code and non-BBD currency`() {
        Database.migrate(ds)
        ds.connection.use { c ->
            assertThrows<SQLException> {
                c.createStatement().execute(
                    "INSERT INTO business (id, code, name) VALUES (gen_random_uuid(), 'bg-i', 'x')",
                )
            }
            assertThrows<SQLException> {
                c.createStatement().execute(
                    "INSERT INTO business (id, code, name, currency) VALUES (gen_random_uuid(), 'XYZ', 'x', 'USD')",
                )
            }
        }
    }
}
