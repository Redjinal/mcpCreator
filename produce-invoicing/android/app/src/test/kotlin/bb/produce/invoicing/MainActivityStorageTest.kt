package bb.produce.invoicing

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Launches the real activity: opens the Room database and reports storage honestly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityStorageTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun storageOpensAndSyntheticSeedOnlyInDevBuilds() {
        val suffix = if (BuildConfig.SEED_SYNTHETIC_DATA) " · SYNTHETIC demo data" else ""
        val expected = "Local invoice storage: Ready · schema v2 · 0 issued · 0 drafts · 0 waiting to sync$suffix"
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(expected)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(expected).assertExists()
        assertEquals(BuildConfig.FLAVOR == "dev", BuildConfig.SEED_SYNTHETIC_DATA)
    }
}
