package bb.produce.invoicing

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end field flow on the JVM: real MainActivity, real Room database (Robolectric SQLite),
 * real navigation. Dev flavor only (it uses the synthetic demo sign-in).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class FieldFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<ProduceInvoicingApplication>()
    private fun count(table: String) =
        app.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    private fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.waitFor(
        matcher: androidx.compose.ui.test.SemanticsMatcher, timeout: Long = 10_000,
    ): SemanticsNodeInteraction {
        try {
            waitUntil(timeout) { onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            System.err.println("TIMEOUT waiting for ${matcher.description}\n" + onRoot(useUnmergedTree = true).printToString())
            throw e
        }
        return onAllNodes(matcher, useUnmergedTree = true)[0]
    }

    private fun signInAsDriver() {
        compose.waitFor(hasTestTag("sign-in-Demo Driver (synthetic)")).performClick()
        compose.waitFor(hasText("Today", substring = true))
    }

    private fun createAndIssueTomatoInvoice(qty: String = "12.5", doubleTap: Boolean = false) {
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("home-new-invoice").assertIsEnabledCompat(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("home-new-invoice").performClick()
        compose.waitFor(hasText("Choose customer")).performClick()
        compose.waitFor(hasText("Sample Grocer A (synthetic)")).performClick()
        compose.waitFor(hasText("Add product", substring = true)).performClick()
        compose.waitFor(hasText("+ lb · 4.50")).performClick()
        compose.onNodeWithText("Done").performClick()
        compose.waitFor(hasTestTag("qty-Tomatoes")).performTextReplacement(qty)
        compose.onNodeWithText("Review & issue").performClick()
        val confirm = compose.waitFor(hasTestTag("confirm-issue"))
        confirm.performClick()
        if (doubleTap) runCatching { confirm.performClick() }
        compose.waitFor(hasTestTag("invoice-number"))
    }

    @Test
    fun driverCreatesIssuesAndRecordsPaymentWithHonestStatuses() {
        assumeTrue(BuildConfig.SEED_SYNTHETIC_DATA)
        signInAsDriver()
        createAndIssueTomatoInvoice(doubleTap = true)

        compose.waitFor(hasText("DEMO-100-AB"))
        compose.waitFor(hasContentDescription("Status: Issued"))
        compose.waitFor(hasContentDescription("Status: Unpaid"))
        compose.waitFor(hasContentDescription("Status: Not synced"))
        compose.waitFor(hasContentDescription("Status: Not printed"))
        compose.onNodeWithText("Not assigned yet").assertExistsCompat() // no office reference offline
        assertEquals("double tap issued once", 1, count("invoice"))
        assertEquals(0, count("draft"))

        compose.onNodeWithText("Mark paid…").performScrollTo().performClick()
        compose.waitFor(hasText("Cash")).performClick()
        compose.onNodeWithText("Record payment").performClick()
        compose.waitFor(hasContentDescription("Status: Paid · Cash"))
        assertEquals(1, count("payment_event"))
        assertEquals("invoice total unchanged by payment", 5625L, runBlocking {
            app.repository.invoiceByNumber(bb.produce.invoicing.core.data.DemoData.businessId, "DEMO-100-AB")!!.total.minor
        })

        // Invoice list: search by number finds it; a nonsense query does not.
        compose.onNodeWithTag("nav-Invoices").performClick()
        compose.waitFor(hasTestTag("invoice-search")).performTextReplacement("100-AB")
        compose.waitFor(hasTestTag("invoice-row-DEMO-100-AB"))
        compose.onNodeWithTag("invoice-search").performTextReplacement("zzz")
        compose.waitFor(hasText("No matches"))
    }

    @Test
    fun draftIsAutoSavedAndListedOnHome() {
        assumeTrue(BuildConfig.SEED_SYNTHETIC_DATA)
        signInAsDriver()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("home-new-invoice").assertIsEnabledCompat(); true }.getOrDefault(false) }
        compose.onNodeWithTag("home-new-invoice").performClick()
        compose.waitFor(hasText("Choose customer")).performClick()
        compose.waitFor(hasText("Sample Restaurant B (synthetic)")).performClick()
        compose.waitFor(hasText("Draft saved on this device"))
        compose.onNodeWithTag("nav-Home").performClick()
        compose.waitFor(hasText("Sample Restaurant B (synthetic)"))
        compose.waitFor(hasText("Continue"))
        assertEquals(1, count("draft"))
        assertEquals(0, count("invoice"))
    }

    @Test
    fun ownerSeesDriverInvoiceAndCanVoidWithReason() {
        assumeTrue(BuildConfig.SEED_SYNTHETIC_DATA)
        signInAsDriver()
        createAndIssueTomatoInvoice()
        compose.onNodeWithTag("nav-Settings").performClick()
        compose.waitFor(hasText("Sign out")).performScrollTo().performClick()

        compose.waitFor(hasTestTag("sign-in-Demo Owner (synthetic)")).performClick()
        compose.waitFor(hasText("Today by driver", substring = true, ignoreCase = true))
        compose.onNodeWithTag("nav-Invoices").performClick()
        compose.waitFor(hasTestTag("invoice-row-DEMO-100-AB")).performClick()
        compose.waitFor(hasText("Void…")).performScrollTo().performClick()
        compose.waitFor(hasText("Reason (required)"))
        compose.onNodeWithTag("reason-input").performTextReplacement("Wrong customer (test)")
        compose.onNodeWithText("Void invoice").performClick()
        compose.waitFor(hasContentDescription("Status: Void"))
        assertEquals(1, count("invoice"))
    }

    @Test
    fun pilotBuildOffersNoSignInAndSaysWhy() {
        assumeFalse(BuildConfig.SEED_SYNTHETIC_DATA)
        compose.waitFor(hasText("not provisioned", substring = true))
        assertEquals(0, compose.onAllNodes(hasTestTag("sign-in-Demo Driver (synthetic)")).fetchSemanticsNodes().size)
        assertEquals(0, count("business")) // no synthetic data in pilot builds
    }
}

private fun SemanticsNodeInteraction.assertIsEnabledCompat() = assertIsEnabled()
private fun SemanticsNodeInteraction.assertExistsCompat() = apply { assertExists() }
