package bb.produce.invoicing

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import bb.produce.invoicing.config.AppConfig
import bb.produce.invoicing.ui.SetupStatusScreen
import bb.produce.invoicing.ui.theme.ProduceInvoicingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val config = AppConfig.from(
            flavor = BuildConfig.FLAVOR,
            versionName = BuildConfig.VERSION_NAME,
            rawApiBaseUrl = BuildConfig.API_BASE_URL,
            allowCleartext = BuildConfig.ALLOW_CLEARTEXT,
        )
        setContent {
            ProduceInvoicingTheme {
                SetupStatusScreen(config)
            }
        }
    }
}
