package bb.produce.invoicing.config

import bb.produce.invoicing.shared.ServerEndpoint.ParseResult
import bb.produce.invoicing.ui.serverStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTest {

    @Test
    fun emptyBuildPropertyMeansNotConfigured() {
        val config = AppConfig.from("pilot", "0.0.1", "", allowCleartext = false)
        assertEquals(ParseResult.NotConfigured, config.server)
        assertEquals("Not configured", serverStatus(config.server))
    }

    @Test
    fun pilotRejectsCleartextUrl() {
        val config = AppConfig.from("pilot", "0.0.1", "http://office.lan:8080", allowCleartext = false)
        assertTrue(config.server is ParseResult.Invalid)
        assertTrue(serverStatus(config.server).startsWith("Invalid configuration"))
    }

    @Test
    fun validUrlIsReportedAsNotContactedRatherThanConnected() {
        val config = AppConfig.from("dev", "0.0.1", "http://dev-workstation.lan:8080/", allowCleartext = true)
        assertEquals("Configured: http://dev-workstation.lan:8080 (not contacted)", serverStatus(config.server))
    }
}
