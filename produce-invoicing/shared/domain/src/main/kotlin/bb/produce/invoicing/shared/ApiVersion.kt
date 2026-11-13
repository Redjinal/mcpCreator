package bb.produce.invoicing.shared

/**
 * Versioning contract between the Android client and the office API.
 * Paths are prefixed with [PATH_PREFIX]; the OpenAPI document lives in
 * `api/openapi/v1.yaml`. A breaking change requires a new major version.
 */
object ApiVersion {
    const val MAJOR = 1
    const val PATH_PREFIX = "/api/v$MAJOR"
    const val HEADER = "X-PI-Api-Version"
}
