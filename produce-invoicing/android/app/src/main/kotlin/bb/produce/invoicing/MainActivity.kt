package bb.produce.invoicing

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import bb.produce.invoicing.ui.AppRoot
import bb.produce.invoicing.ui.theme.ProduceInvoicingTheme


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ProduceInvoicingApplication).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                ProduceInvoicingTheme { AppRoot() }
            }
        }
    }
}
