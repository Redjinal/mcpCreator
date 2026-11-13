package bb.produce.invoicing

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import bb.produce.invoicing.config.AppConfig
import bb.produce.invoicing.core.data.FieldReadModel
import bb.produce.invoicing.core.data.InvoicingRepository
import bb.produce.invoicing.core.data.db.AppDatabase
import bb.produce.invoicing.session.AuthGateway
import bb.produce.invoicing.session.DemoAuthGateway
import bb.produce.invoicing.session.NotProvisionedAuthGateway
import bb.produce.invoicing.session.SessionStore

/** Explicit dependency container (no DI framework yet). One per process. */
class AppContainer(
    val database: AppDatabase,
    val config: AppConfig,
    val seedSyntheticData: Boolean,
) {
    val repository = InvoicingRepository(database)
    val readModel = FieldReadModel(database)
    val sessions = SessionStore()
    val auth: AuthGateway = if (seedSyntheticData) DemoAuthGateway() else NotProvisionedAuthGateway()
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

class ProduceInvoicingApplication : Application() {
    val container: AppContainer by lazy {
        AppContainer(
            database = AppDatabase.builder(this).build(),
            config = AppConfig.from(BuildConfig.FLAVOR, BuildConfig.VERSION_NAME, BuildConfig.API_BASE_URL, BuildConfig.ALLOW_CLEARTEXT),
            seedSyntheticData = BuildConfig.SEED_SYNTHETIC_DATA,
        )
    }
    val database: AppDatabase get() = container.database
    val repository: InvoicingRepository get() = container.repository
}
