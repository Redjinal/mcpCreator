package bb.produce.invoicing.server

import bb.produce.invoicing.shared.ApiVersion
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.generate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class HealthResponse(
    val status: String,
    val database: String,
    val schemaVersion: String?,
    val apiVersion: Int,
)

@Serializable
data class ErrorResponse(val error: String)

private val log = LoggerFactory.getLogger("bb.produce.invoicing.server")

/**
 * Task 00 surface: only an unauthenticated, data-free health endpoint.
 * Authenticated provisioning, sync and owner endpoints arrive in Tasks 04/05/08.
 */
fun Application.module(probe: DatabaseProbe) {
    install(ContentNegotiation) { json() }
    install(CallId) { generate(length = 16) }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error"))
        }
    }
    routing {
        route(ApiVersion.PATH_PREFIX) {
            get("/health") {
                call.response.header(ApiVersion.HEADER, ApiVersion.MAJOR.toString())
                val version = runCatching { probe.schemaVersion() }
                version.onSuccess {
                    call.respond(HealthResponse("ok", "up", it, ApiVersion.MAJOR))
                }.onFailure {
                    log.warn("Health check: database unavailable ({})", it.javaClass.simpleName)
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        HealthResponse("degraded", "down", null, ApiVersion.MAJOR),
                    )
                }
            }
        }
    }
}
