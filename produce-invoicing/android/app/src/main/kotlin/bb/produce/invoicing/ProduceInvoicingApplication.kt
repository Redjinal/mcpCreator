package bb.produce.invoicing

import android.app.Application
import bb.produce.invoicing.core.data.InvoicingRepository
import bb.produce.invoicing.core.data.db.AppDatabase

/** Explicit DI container (no framework yet). One database per process. */
class ProduceInvoicingApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.builder(this).build() }
    val repository: InvoicingRepository by lazy { InvoicingRepository(database) }
}
