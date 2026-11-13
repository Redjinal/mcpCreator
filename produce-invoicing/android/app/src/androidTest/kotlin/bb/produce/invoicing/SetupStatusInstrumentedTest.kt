package bb.produce.invoicing

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a device/emulator: ./gradlew connectedDevDebugAndroidTest */
@RunWith(AndroidJUnit4::class)
class SetupStatusInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesToSetupStatus() {
        compose.onNodeWithText("Setup status").assertExists()
    }
}
