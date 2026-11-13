package bb.produce.invoicing

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import bb.produce.invoicing.core.data.DemoSeeder
import bb.produce.invoicing.config.AppConfig
import bb.produce.invoicing.ui.SetupStatusScreen
import bb.produce.invoicing.ui.storageStatus
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
        val app = application as ProduceInvoicingApplication
        setContent {
            val storage by produceState<String?>(initialValue = null) {
                value = runCatching {
                    if (BuildConfig.SEED_SYNTHETIC_DATA) {
                        DemoSeeder(app.database).seedIfEmpty(app.repository.deviceId(), java.time.Instant.now())
                    }
                    storageStatus(app.repository.storageSummary(), BuildConfig.SEED_SYNTHETIC_DATA)
                }.getOrElse { "Error opening local storage: ${it.javaClass.simpleName}" }
            }
            ProduceInvoicingTheme {
                SetupStatusScreen(config, storage = storage ?: "Opening…")
            }
        }
    }
}
