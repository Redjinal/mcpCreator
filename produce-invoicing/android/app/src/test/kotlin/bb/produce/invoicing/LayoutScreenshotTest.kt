package bb.produce.invoicing

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import bb.produce.invoicing.core.data.DemoData
import bb.produce.invoicing.core.data.DemoSeeder
import bb.produce.invoicing.core.data.IssueResult
import bb.produce.invoicing.domain.common.Actor
import bb.produce.invoicing.domain.common.Role
import bb.produce.invoicing.domain.money.Quantity
import bb.produce.invoicing.domain.money.UnitCode
import bb.produce.invoicing.session.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/**
 * Phone/tablet × portrait/landscape renders of the main screens (JVM, Robolectric native graphics),
 * written to app/build/outputs/screenshots/task03. Evidence for layout review, NOT device testing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun prepare(): Session = runBlocking {
        val c = ApplicationProvider.getApplicationContext<ProduceInvoicingApplication>().container
        val device = c.repository.deviceId()
        DemoSeeder(c.database).seedIfEmpty(device, Instant.now())
        val driver = Actor(DemoData.driverUser, Role.DRIVER, device)
        repeat(2) { i ->
            var d = c.repository.newDraft(DemoData.businessId, DemoData.driverUser).withCustomer(DemoData.customers[i].id, Instant.now())
            d = d.addProduct(DemoData.products[0], UnitCode.of("lb"), Instant.now()).getOrThrow()
            d = d.updateQuantity(d.lines[0].id, Quantity.of(if (i == 0) "12.5" else "3"), Instant.now()).getOrThrow()
            d = d.addProduct(DemoData.products[1], UnitCode.of("case"), Instant.now()).getOrThrow()
            c.repository.saveDraft(d)
            check(c.repository.issue(d.id, driver) is IssueResult.Issued)
        }
        Session(driver, DemoData.businessId, "Demo Driver (synthetic)", "AB").also { c.sessions.signIn(it) }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val dir = File("build/outputs/screenshots/task03").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text, substring = true, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun tour(prefix: String) {
        assumeTrue(BuildConfig.SEED_SYNTHETIC_DATA)
        prepare()
        waitText("Issued today"); shot("$prefix-1-home")
        compose.onAllNodes(hasTestTag("nav-Invoices"))[0].performClick()
        waitText("DEMO-101-AB"); shot("$prefix-2-invoices")
        compose.onAllNodes(hasTestTag("invoice-row-DEMO-100-AB")).let { rows ->
            // On short screens the second row may be below the fold; search narrows the list to it.
            if (rows.fetchSemanticsNodes().isEmpty()) {
                compose.onAllNodes(hasTestTag("invoice-search"))[0].performTextReplacement("100-AB")
                waitText("DEMO-100-AB")
            }
        }
        compose.onAllNodes(hasTestTag("invoice-row-DEMO-100-AB"))[0].performClick()
        waitText("Office reference (internal)"); shot("$prefix-3-detail")
        compose.onAllNodes(hasText("58 mm print preview"))[0].performScrollTo().performClick()
        waitText("Invoice: DEMO-100-AB"); shot("$prefix-4-receipt")
        compose.onAllNodes(hasTestTag("nav-New invoice"))[0].performClick()
        waitText("Choose customer"); shot("$prefix-5-new-invoice")
    }

    @Test @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xhdpi") fun phonePortrait() = tour("phone-portrait")
    @Test @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-xhdpi") fun phoneLandscape() = tour("phone-landscape")
    @Test @Config(sdk = [35], qualifiers = "w800dp-h1280dp-port-mdpi") fun tabletPortrait() = tour("tablet-portrait")
    @Test @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-mdpi") fun tabletLandscape() = tour("tablet-landscape")
}
