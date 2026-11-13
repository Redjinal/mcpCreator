package bb.produce.invoicing.ui.draft

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bb.produce.invoicing.LocalAppContainer
import bb.produce.invoicing.domain.catalogue.Customer
import bb.produce.invoicing.domain.catalogue.Product
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.money.UnitCode
import bb.produce.invoicing.session.Session
import bb.produce.invoicing.ui.common.Messages
import bb.produce.invoicing.ui.common.Notice
import bb.produce.invoicing.ui.common.PiCard
import bb.produce.invoicing.ui.common.SectionLabel
import bb.produce.invoicing.ui.common.Tone
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftEditorScreen(
    vm: DraftEditorViewModel,
    session: Session,
    onIssued: (InvoiceId) -> Unit,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycleCompat()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pickCustomer by remember { mutableStateOf(false) }
    var pickProduct by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf(false) }

    LaunchedEffect(ui.issuedInvoiceId) { ui.issuedInvoiceId?.let { vm.consumeIssued(); review = false; onIssued(it) } }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("New invoice")
                        Text(if (ui.savedLocally) "Draft saved on this device" else "Not saved yet",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Total")
                        Text(ui.total.format(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("draft-total"))
                    }
                    Button(
                        onClick = { vm.revealProblems(); review = true },
                        enabled = !ui.loading && ui.canIssue,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Review & issue") }
                }
            }
        },
    ) { padding ->
        if (ui.loading) return@Scaffold
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ui.cannotIssueReason?.let { item { Notice(it, Tone.WARN) } }
                    ui.shift?.let { s -> item { Notice("${s.vehicleName} · shift ${s.localDate} (${s.status.lowercase()}) · works offline", Tone.NEUTRAL) } }
                    item {
                        SectionLabel("1 · Customer")
                        Spacer(Modifier.padding(2.dp))
                        val c = ui.customer
                        if (c == null) {
                            OutlinedButton(onClick = { pickCustomer = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("Choose customer") }
                        } else {
                            PiCard(Modifier.clickable { pickCustomer = true }) {
                                Text(c.name, style = MaterialTheme.typography.titleMedium)
                                listOfNotNull(c.phone, c.address).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · ")) }
                                TextButton(onClick = { pickCustomer = true }) { Text("Change customer") }
                            }
                        }
                    }
                    item { SectionLabel("2 · Items") }
                    items(ui.lines, key = { it.line.id.toString() }) { lineUi ->
                        LineEditor(
                            lineUi,
                            onQty = { vm.setQuantityText(lineUi.line.id, it) },
                            onStep = { vm.stepQuantity(lineUi.line.id, it) },
                            onPrice = { vm.setPriceText(lineUi.line.id, it) },
                            onUnit = { vm.setUnit(lineUi.line.id, it, lineUi.product) },
                            onZero = { vm.confirmZeroPrice(lineUi.line.id, it) },
                            onDescription = { vm.setDescription(lineUi.line.id, it) },
                            onRemove = {
                                vm.removeLine(lineUi.line.id)?.let { (line, index) ->
                                    scope.launch {
                                        val r = snackbar.showSnackbar("Removed ${line.description}", actionLabel = "Undo", withDismissAction = true)
                                        if (r == SnackbarResult.ActionPerformed) vm.undoRemove(line, index)
                                    }
                                }
                            },
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { pickProduct = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Filled.Add, contentDescription = null); Text(" Add product")
                            }
                            OutlinedButton(onClick = { vm.addCustomLine("", UnitCode.of("each")) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Custom line") }
                        }
                    }
                    if (ui.showProblems && ui.problems.isNotEmpty()) {
                        item { Notice("Before issuing: " + ui.problems.map { Messages.problem(it.code) }.distinct().joinToString("; "), Tone.BAD) }
                    }
                }
                if (wide) {
                    DraftPreview(ui, session, Modifier.width(380.dp).fillMaxHeight().padding(16.dp))
                }
            }
        }
    }

    if (pickCustomer) CustomerPicker(ui.customers, session, onPick = { vm.setCustomer(it.id); pickCustomer = false }, onDismiss = { pickCustomer = false })
    if (pickProduct) ProductPicker(ui, onPick = { p, u -> vm.addProduct(p, u) }, onDismiss = { pickProduct = false })
    if (review) ReviewDialog(ui, session, onIssue = vm::issue, onDismiss = { review = false })
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LineEditor(
    l: LineUi,
    onQty: (String) -> Unit,
    onStep: (Int) -> Unit,
    onPrice: (String) -> Unit,
    onUnit: (UnitCode) -> Unit,
    onZero: (Boolean) -> Unit,
    onDescription: (String) -> Unit,
    onRemove: () -> Unit,
) {
    PiCard(Modifier.testTag("line-${l.line.description}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (l.line.isCustom) {
                OutlinedTextField(l.line.description, onDescription, label = { Text("Description") }, modifier = Modifier.weight(1f), singleLine = true)
            } else {
                Text(l.line.description, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove ${l.line.description}") }
        }
        val units = l.product?.units?.map { it.unit } ?: listOf(l.line.unit)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            units.forEach { u -> FilterChip(selected = u == l.line.unit, onClick = { onUnit(u) }, label = { Text(u.code) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onStep(-1) }, modifier = Modifier.width(52.dp).heightIn(min = 48.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("−") }
            OutlinedTextField(
                l.qtyText, onQty, label = { Text("Qty (${l.line.unit})") }, singleLine = true,
                isError = l.qtyError != null, supportingText = l.qtyError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).testTag("qty-${l.line.description}"),
            )
            OutlinedButton(onClick = { onStep(1) }, modifier = Modifier.width(52.dp).heightIn(min = 48.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("+") }
        }
        OutlinedTextField(
            l.priceText, onPrice, label = { Text("Unit price (BBD per ${l.line.unit})") }, singleLine = true,
            isError = l.priceError != null, supportingText = (l.priceError ?: l.priceNote)?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag("price-${l.line.description}"),
        )
        if (l.line.unitPrice?.isZero == true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(l.line.zeroPriceConfirmed, onZero)
                Text("Confirm no charge for this line")
            }
        }
        l.stockNote?.let { Notice(it, Tone.WARN) }
        l.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text("Line total: " + (l.line.lineTotal?.format() ?: "—"), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DraftPreview(ui: DraftUi, session: Session, modifier: Modifier) {
    PiCard(modifier) {
        SectionLabel("Live preview · draft, not issued")
        Text("Invoice number: assigned when issued", style = MaterialTheme.typography.bodyMedium)
        Text("Driver: ${session.displayName}")
        Text("Bill to: ${ui.customer?.name ?: "No customer selected"}")
        HorizontalDivider()
        ui.lines.forEach { l ->
            Text(l.line.description.ifBlank { "(no description)" }, fontWeight = FontWeight.Medium)
            Text("${l.line.quantity ?: "—"} ${l.line.unit} × ${l.line.unitPrice?.money?.formatAmount() ?: "—"} = ${l.line.lineTotal?.formatAmount() ?: "—"}")
        }
        HorizontalDivider()
        Text("Total ${ui.total.format()}", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ReviewDialog(ui: DraftUi, session: Session, onIssue: () -> Unit, onDismiss: () -> Unit) {
    val blocking = ui.problems.isNotEmpty()
    AlertDialog(
        onDismissRequest = { if (!ui.issuing) onDismiss() },
        title = { Text(if (blocking) "Not ready to issue" else "Issue this invoice?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (blocking) {
                    ui.problems.map { Messages.problem(it.code) }.distinct().forEach { Text("• $it") }
                } else {
                    Text("Customer: ${ui.customer?.name}")
                    Text("Items: ${ui.lines.size}")
                    Text("Total: ${ui.total.format()}", fontWeight = FontWeight.SemiBold)
                    Text("Issued by: ${session.displayName}")
                    Text(
                        "The invoice number (${session.driverCode?.let { "…-$it" } ?: "driver number"}) is assigned when you issue. " +
                            "It is saved on this device first; after issuing it cannot be edited. Printing and syncing are separate steps.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (!blocking) Button(onClick = onIssue, enabled = ui.canIssue, modifier = Modifier.testTag("confirm-issue")) {
                Text(if (ui.issuing) "Issuing…" else "Issue invoice")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !ui.issuing) { Text(if (blocking) "Back to edit" else "Cancel") } },
    )
}

@Composable
fun CustomerPicker(customers: List<Customer>, session: Session, onPick: (Customer) -> Unit, onDismiss: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    FullScreenDialog("Choose customer", onDismiss) {
        if (!adding) {
            OutlinedTextField(query, { query = it }, label = { Text("Search customers") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { adding = true; name = query }) { Text("+ New customer") }
            val shown = customers.filter { it.name.contains(query.trim(), ignoreCase = true) }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(shown, key = { it.id.toString() }) { cust ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(cust) }.padding(vertical = 14.dp)) {
                        Text(cust.name, style = MaterialTheme.typography.titleMedium)
                        cust.phone?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    HorizontalDivider()
                }
                if (shown.isEmpty()) item { Text("No customers match “$query”.", Modifier.padding(16.dp)) }
            }
        } else {
            Text("New customers are saved on this device and sent to the office at the next sync.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it }, label = { Text("Name (required)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(address, { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        when (val r = c.readModel.createCustomer(session.businessId, session.actor, name, phone, address)) {
                            is Outcome.Ok -> onPick(r.value)
                            is Outcome.Err -> error = Messages.customer(r.error)
                        }
                    }
                }) { Text("Save customer") }
                TextButton(onClick = { adding = false }) { Text("Cancel") }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProductPicker(ui: DraftUi, onPick: (Product, UnitCode) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    FullScreenDialog("Add products", onDismiss) {
        OutlinedTextField(query, { query = it }, label = { Text("Search products") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val shown = ui.products.filter { it.name.contains(query.trim(), ignoreCase = true) || it.sku.contains(query.trim(), ignoreCase = true) }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(shown, key = { it.id.toString() }) { p ->
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    val bal = ui.stock[p.id]
                    val base = p.units.firstNotNullOfOrNull { ui.conversions.find(p.id, it.unit)?.baseUnit }
                    Text(
                        if (bal != null && base != null) "On vehicle: ${bal.saleable.stripTrailingZeros().toPlainString()} $base" else "Not on vehicle stock",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        p.units.forEach { u ->
                            OutlinedButton(onClick = { onPick(p, u.unit) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("+ ${u.unit} · ${u.price?.money?.formatAmount() ?: "price on invoice"}")
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Done") }
    }
}

@Composable
fun FullScreenDialog(title: String, onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().widthIn(max = 780.dp), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                content()
            }
        }
    }
}

/** Lifecycle-aware collection (stops collecting while the screen is in the background). */
@Composable
fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithLifecycleCompat(): androidx.compose.runtime.State<T> =
    collectAsStateWithLifecycle()
