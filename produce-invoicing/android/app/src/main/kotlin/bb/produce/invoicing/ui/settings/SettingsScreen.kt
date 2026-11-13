package bb.produce.invoicing.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bb.produce.invoicing.LocalAppContainer
import bb.produce.invoicing.core.data.InvoicingRepository
import bb.produce.invoicing.domain.catalogue.Business
import bb.produce.invoicing.session.Session
import bb.produce.invoicing.ui.common.KeyValue
import bb.produce.invoicing.ui.common.Notice
import bb.produce.invoicing.ui.common.PiCard
import bb.produce.invoicing.ui.common.SectionLabel
import bb.produce.invoicing.ui.common.Tone
import bb.produce.invoicing.ui.serverStatus

private data class SettingsFacts(
    val business: Business?,
    val deviceId: String,
    val storage: InvoicingRepository.StorageSummary,
    val catalogueVersion: Int?,
    val negativePolicy: String?,
    val leaseInfo: String,
)

/** Settings & diagnostics. Read-only facts; owner editing of business details is Task 08. */
@Composable
fun SettingsScreen(session: Session, onSignOut: () -> Unit) {
    val c = LocalAppContainer.current
    val pending by c.readModel.pendingOutboxCount().collectAsState(initial = 0)
    val printers by c.readModel.printerProfiles().collectAsState(initial = emptyList())
    val facts by produceState<SettingsFacts?>(null, pending) {
        val lease = c.readModel.issuingLease(session.actor)
        value = SettingsFacts(
            c.readModel.business(), c.repository.deviceId().toString(), c.repository.storageSummary(),
            c.readModel.catalogueVersion(), c.readModel.negativeStockPolicy()?.name,
            when {
                lease == null -> "None on this device"
                lease.revoked -> "Revoked"
                else -> "Driver ${lease.driverCode} · epoch ${lease.epoch} · next number ${lease.nextSequence ?: "exhausted"}"
            },
        )
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        PiCard {
            SectionLabel("Signed in")
            KeyValue("User", session.displayName)
            KeyValue("Role", if (session.isOwner) "Owner" else "Driver")
            KeyValue("Invoice numbers", facts?.leaseInfo ?: "…")
            OutlinedButton(onClick = onSignOut) { Text("Sign out") }
        }
        facts?.business?.let { b ->
            PiCard {
                SectionLabel("Business (read-only)")
                KeyValue("Name", b.name)
                KeyValue("Invoice prefix", b.code.value)
                KeyValue("Currency", b.currency.code)
                KeyValue("Catalogue version", facts?.catalogueVersion?.toString() ?: "—")
                KeyValue("Negative stock", facts?.negativePolicy?.let { if (it == "BLOCK") "Blocked (client to confirm)" else "Allowed and flagged" } ?: "—")
            }
        }
        PiCard {
            SectionLabel("Printers · 58 mm")
            if (printers.isEmpty()) {
                Notice("No printer set up. Printing will be available after a supported 58 mm printer model is configured and tested on real hardware (Tasks 06–07).", Tone.NEUTRAL)
            } else printers.forEach { p ->
                KeyValue("${p.manufacturer} ${p.model}", if (p.certificationRef == null) "Not certified" else "Certified")
            }
        }
        PiCard {
            SectionLabel("Sync & storage")
            KeyValue("Office server", serverStatus(c.config.server))
            KeyValue("Local storage", facts?.storage?.let { "schema v${it.schemaVersion} · ${it.invoices} invoices · ${it.drafts} drafts" } ?: "…")
            KeyValue("Changes waiting to send", pending.toString())
            KeyValue("Last successful sync", "Never (sync not implemented yet, Task 05)")
            KeyValue("Device ID", facts?.deviceId?.take(8)?.plus("…") ?: "…")
            KeyValue("Build", "${c.config.flavor} · ${c.config.versionName}")
        }
        if (c.seedSyntheticData) Notice("Developer build with SYNTHETIC demo data. Not for pilot use.", Tone.WARN)
    }
}

@Suppress("unused") private val fill = Modifier.fillMaxWidth()
