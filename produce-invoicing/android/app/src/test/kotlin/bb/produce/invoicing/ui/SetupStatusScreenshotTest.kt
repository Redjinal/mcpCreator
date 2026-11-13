package bb.produce.invoicing.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import bb.produce.invoicing.config.AppConfig
import bb.produce.invoicing.ui.theme.ProduceInvoicingTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the screen with Robolectric native graphics and writes PNGs to
 * app/build/outputs/screenshots/ as review evidence. JVM rendering — not a substitute
 * for real phone/tablet checks (docs/06).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupStatusScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
    fun phonePortrait() = capture("setup-status-phone-pilot.png", AppConfig.from("pilot", "0.0.1-task00", "", false))

    @Test
    @Config(sdk = [35], qualifiers = "w1180dp-h820dp-land-mdpi")
    fun tabletLandscape() = capture(
        "setup-status-tablet-dev.png",
        AppConfig.from("dev", "0.0.1-task00-dev", "http://dev-workstation.lan:8080", true),
    )

    private fun capture(name: String, config: AppConfig) {
        val storage = storageStatus(
            bb.produce.invoicing.core.data.InvoicingRepository.StorageSummary(2, 0, 0, 0), synthetic = config.flavor == "dev",
        )
        compose.setContent { ProduceInvoicingTheme { SetupStatusScreen(config, storage = storage) } }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
