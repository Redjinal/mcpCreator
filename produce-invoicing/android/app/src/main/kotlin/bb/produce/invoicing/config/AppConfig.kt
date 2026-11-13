package bb.produce.invoicing.config

import bb.produce.invoicing.shared.ServerEndpoint

/**
 * Build-time configuration surfaced honestly to the UI. "Configured" only means a
 * URL is present and valid — it says nothing about reachability; the app never
 * reports a connection it has not made.
 */
data class AppConfig(
    val flavor: String,
    val versionName: String,
    val server: ServerEndpoint.ParseResult,
) {
    companion object {
        fun from(flavor: String, versionName: String, rawApiBaseUrl: String, allowCleartext: Boolean) =
            AppConfig(flavor, versionName, ServerEndpoint.parse(rawApiBaseUrl, allowCleartext))
    }
}
