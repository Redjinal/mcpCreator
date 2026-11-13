package bb.produce.invoicing.server

/**
 * All deployment configuration comes from the environment (see deploy/.env.example).
 * Nothing secret or site-specific is compiled in; startup fails fast when the
 * database password is missing rather than falling back to a default.
 */
data class ServerConfig(
    val httpHost: String,
    val httpPort: Int,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val seedSyntheticData: Boolean,
) {
    override fun toString(): String =
        "ServerConfig(httpHost=$httpHost, httpPort=$httpPort, dbUrl=$dbUrl, dbUser=$dbUser, dbPassword=***, seedSyntheticData=$seedSyntheticData)"

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            fun required(name: String) = env[name]?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Missing required environment variable $name")
            val port = env["PI_HTTP_PORT"]?.toIntOrNull() ?: DEFAULT_PORT
            require(port in 1..65535) { "PI_HTTP_PORT out of range" }
            return ServerConfig(
                httpHost = env["PI_HTTP_HOST"]?.takeIf { it.isNotBlank() } ?: "0.0.0.0",
                httpPort = port,
                dbUrl = required("PI_DB_URL"),
                dbUser = required("PI_DB_USER"),
                dbPassword = required("PI_DB_PASSWORD"),
                seedSyntheticData = env["PI_SEED_SYNTHETIC"].equals("true", ignoreCase = true),
            )
        }

        const val DEFAULT_PORT = 8080
    }
}
