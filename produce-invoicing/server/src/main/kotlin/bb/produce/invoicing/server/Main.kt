package bb.produce.invoicing.server

import bb.produce.invoicing.shared.ApiVersion
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if ("--healthcheck" in args) exitProcess(healthcheck())

    val log = LoggerFactory.getLogger("bb.produce.invoicing.server")
    val config = ServerConfig.fromEnv()
    log.info("Starting with {}", config)

    val dataSource = Database.dataSource(config)
    val schema = Database.migrate(dataSource)
    log.info("Database schema at version {}", schema)
    if (config.seedSyntheticData) {
        Database.seedSynthetic(dataSource)
        log.warn("SYNTHETIC demo fixtures applied (PI_SEED_SYNTHETIC=true). Do not enable in pilot.")
    }

    embeddedServer(Netty, port = config.httpPort, host = config.httpHost) {
        module(JdbcDatabaseProbe(dataSource))
    }.start(wait = true)
}

/**
 * Container healthcheck without curl/wget in the runtime image:
 * `server --healthcheck` exits 0 only when the local health endpoint returns 200.
 */
private fun healthcheck(): Int {
    val port = System.getenv("PI_HTTP_PORT")?.toIntOrNull() ?: ServerConfig.DEFAULT_PORT
    return try {
        val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port${ApiVersion.PATH_PREFIX}/health"))
                .timeout(Duration.ofSeconds(3)).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        if (response.statusCode() == 200) 0 else 1
    } catch (e: Exception) {
        1
    }
}
