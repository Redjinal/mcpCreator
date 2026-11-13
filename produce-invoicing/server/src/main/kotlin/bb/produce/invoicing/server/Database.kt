package bb.produce.invoicing.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

object Database {
    fun dataSource(config: ServerConfig): HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.dbUrl
            username = config.dbUser
            password = config.dbPassword
            maximumPoolSize = 10
            // Fail fast so /health reports 503 promptly instead of hanging for Hikari's 30 s default.
            connectionTimeout = 3_000
            validationTimeout = 2_000
            // Start even if the database is briefly unavailable; Flyway migration still runs first.
            initializationFailTimeout = 30_000
            isAutoCommit = true
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            poolName = "pi-db"
        },
    )

    /** Applies versioned schema migrations. Returns the resulting schema version. */
    fun migrate(dataSource: DataSource): String {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .validateMigrationNaming(true)
            .load()
        flyway.migrate()
        return flyway.info().current()?.version?.version ?: "none"
    }

    /**
     * Inserts clearly-labelled SYNTHETIC fixtures (never real customer data, CLAUDE.md
     * invariant 9). Idempotent; only runs when PI_SEED_SYNTHETIC=true.
     */
    fun seedSynthetic(dataSource: DataSource) {
        val sql = Database::class.java.getResource("/seed/synthetic.sql")?.readText()
            ?: error("seed/synthetic.sql missing from classpath")
        dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
    }
}

/** Liveness of the database, abstracted for tests. */
fun interface DatabaseProbe {
    /** Returns the current schema version, or throws if the database is unreachable. */
    fun schemaVersion(): String
}

class JdbcDatabaseProbe(private val dataSource: DataSource) : DatabaseProbe {
    override fun schemaVersion(): String = dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
        ).use { ps ->
            ps.queryTimeout = 2
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else "none" }
        }
    }
}
