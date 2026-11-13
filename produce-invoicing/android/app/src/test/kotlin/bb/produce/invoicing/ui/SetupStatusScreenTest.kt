package bb.produce.invoicing.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import bb.produce.invoicing.config.AppConfig
import bb.produce.invoicing.ui.theme.ProduceInvoicingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** JVM (Robolectric) Compose test; on-device instrumentation runs are tracked separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupStatusScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unconfiguredServerIsShownHonestly() {
        compose.setContent {
            ProduceInvoicingTheme { SetupStatusScreen(AppConfig.from("pilot", "0.0.1", "", false)) }
        }
        compose.onNodeWithContentDescription("Office server: Not configured").assertExists()
        compose.onNodeWithText("Invoicing is not available", substring = true).assertExists()
    }

    @Test
    fun noFakeSuccessLabelsAreRendered() {
        compose.setContent {
            ProduceInvoicingTheme {
                SetupStatusScreen(AppConfig.from("dev", "0.0.1", "http://dev-workstation.lan:8080", true))
            }
        }
        for (claim in listOf("Synced", "Printed", "Connected", "Online")) {
            assertEquals(
                "Unexpected success label '$claim'",
                0,
                compose.onAllNodesWithTextIgnoringCase(claim).size,
            )
        }
    }

    private fun ComposeContentTestRule.onAllNodesWithTextIgnoringCase(text: String) =
        onAllNodes(
            SemanticsMatcher("text equals '$text' ignoring case") { node ->
                node.config.getOrNull(SemanticsProperties.Text)
                    ?.any { it.text.equals(text, ignoreCase = true) } == true
            },
        ).fetchSemanticsNodes()
}
