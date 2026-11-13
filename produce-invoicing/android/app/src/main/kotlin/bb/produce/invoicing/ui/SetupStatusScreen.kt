package bb.produce.invoicing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import bb.produce.invoicing.config.AppConfig
import bb.produce.invoicing.shared.ServerEndpoint.ParseResult
import bb.produce.invoicing.ui.theme.ProduceInvoicingTheme

/**
 * Task 00 bootstrap screen. Every row states what actually exists in this build.
 * It deliberately shows no "synced", "printed" or "connected" success states —
 * those require the real implementations from later tasks.
 */
@Composable
fun SetupStatusScreen(
    config: AppConfig,
    modifier: Modifier = Modifier,
    storage: String = "Not opened",
    intro: String = "Device status. Invoicing is not available until someone signs in.",
    actions: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("PRODUCE INVOICING", style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Setup status", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(intro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                actions()
                StatusRow("Build", "${config.flavor} · ${config.versionName}")
                StatusRow("Office server", serverStatus(config.server), testTag = "server-status")
                StatusRow("Local invoice storage", storage, testTag = "storage-status")
                StatusRow("Printer", "No printer configured (Task 06/07)")
                StatusRow("Sync", "Not implemented yet (Task 05)")
            }
        }
    }
}

/** Honest storage line: counts only, "waiting to sync" never implies anything reached the office. */
fun storageStatus(s: bb.produce.invoicing.core.data.InvoicingRepository.StorageSummary, synthetic: Boolean): String =
    "Ready · schema v${s.schemaVersion} · ${s.invoices} issued · ${s.drafts} drafts · ${s.pendingOutbox} waiting to sync" +
        if (synthetic) " · SYNTHETIC demo data" else ""

internal fun serverStatus(result: ParseResult): String = when (result) {
    ParseResult.NotConfigured -> "Not configured"
    is ParseResult.Invalid -> "Invalid configuration: ${result.reason}"
    is ParseResult.Valid -> "Configured: ${result.endpoint.baseUrl} (not contacted)"
}

@Composable
private fun StatusRow(label: String, value: String, testTag: String? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { if (testTag != null) contentDescription = "$label: $value" },
        ) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun SetupStatusPhonePreview() {
    ProduceInvoicingTheme {
        SetupStatusScreen(AppConfig.from("pilot", "0.0.1-task00", "", allowCleartext = false))
    }
}
