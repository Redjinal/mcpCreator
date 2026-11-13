package bb.produce.invoicing.shared

import java.net.URI
import java.net.URISyntaxException

/**
 * A validated office-server base URL.
 *
 * The URL is deployment configuration (build property or, from Task 04,
 * device provisioning) — never a hard-coded LAN IP in source. Rules:
 *  - scheme must be `https`, or `http` only when the caller explicitly allows
 *    cleartext (dev flavor talking to a developer workstation);
 *  - a host is required; user-info (embedded credentials), query and fragment
 *    are rejected so secrets cannot ride along in configuration;
 *  - any path is normalised to have no trailing slash.
 */
@ConsistentCopyVisibility
data class ServerEndpoint private constructor(val baseUrl: String) {

    val isCleartext: Boolean get() = baseUrl.startsWith("http://")

    /** Absolute URL for a versioned API path such as `/health`. */
    fun apiUrl(path: String): String {
        require(path.startsWith("/")) { "path must start with '/'" }
        return baseUrl + ApiVersion.PATH_PREFIX + path
    }

    override fun toString(): String = baseUrl

    sealed interface ParseResult {
        data class Valid(val endpoint: ServerEndpoint) : ParseResult
        /** Nothing configured: the app must show "not configured", never a fake connection. */
        data object NotConfigured : ParseResult
        data class Invalid(val reason: String) : ParseResult
    }

    companion object {
        fun parse(raw: String?, allowCleartext: Boolean): ParseResult {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return ParseResult.NotConfigured

            val uri = try {
                URI(trimmed)
            } catch (e: URISyntaxException) {
                return ParseResult.Invalid("not a valid URL")
            }
            val scheme = uri.scheme?.lowercase()
                ?: return ParseResult.Invalid("URL must start with https://")
            when (scheme) {
                "https" -> Unit
                "http" -> if (!allowCleartext) {
                    return ParseResult.Invalid("cleartext http is not allowed in this build; use https")
                }
                else -> return ParseResult.Invalid("unsupported scheme '$scheme'")
            }
            if (uri.host.isNullOrBlank()) return ParseResult.Invalid("URL has no host")
            if (uri.rawUserInfo != null) return ParseResult.Invalid("credentials must not be embedded in the URL")
            if (uri.rawQuery != null || uri.rawFragment != null) {
                return ParseResult.Invalid("URL must not contain a query or fragment")
            }
            if (uri.port != -1 && uri.port !in 1..65535) return ParseResult.Invalid("invalid port")

            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = (uri.rawPath ?: "").trimEnd('/')
            return ParseResult.Valid(ServerEndpoint("$scheme://${uri.host.lowercase()}$port$path"))
        }
    }
}
