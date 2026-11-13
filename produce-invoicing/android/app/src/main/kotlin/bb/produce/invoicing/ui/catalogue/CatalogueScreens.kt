package bb.produce.invoicing.ui.catalogue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import bb.produce.invoicing.AppContainer
import bb.produce.invoicing.LocalAppContainer
import bb.produce.invoicing.core.data.InvoiceListItem
import bb.produce.invoicing.domain.catalogue.ConversionTable
import bb.produce.invoicing.domain.catalogue.Customer
import bb.produce.invoicing.domain.catalogue.Product
import bb.produce.invoicing.domain.common.CustomerId
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.ProductId
import bb.produce.invoicing.domain.stock.StockBalance
import bb.produce.invoicing.session.Session
import bb.produce.invoicing.ui.common.BadgeRow
import bb.produce.invoicing.ui.common.EmptyState
import bb.produce.invoicing.ui.common.PiCard
import bb.produce.invoicing.ui.common.SectionLabel
import bb.produce.invoicing.ui.common.issueBadge
import bb.produce.invoicing.ui.common.paymentBadge
import bb.produce.invoicing.ui.common.timeLabel
import bb.produce.invoicing.ui.draft.CustomerPicker
import bb.produce.invoicing.ui.draft.collectAsStateWithLifecycleCompat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CatalogueUi(
    val customers: List<Customer> = emptyList(),
    val products: List<Product> = emptyList(),
    val stock: Map<ProductId, StockBalance> = emptyMap(),
    val conversions: ConversionTable = ConversionTable(emptyList()),
    val invoices: List<InvoiceListItem> = emptyList(),
    val vehicleName: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueViewModel(c: AppContainer, session: Session) : ViewModel() {
    private val shift = MutableStateFlow<bb.produce.invoicing.core.data.ShiftContext?>(null)
    val ui: StateFlow<CatalogueUi> = combine(
        c.readModel.customers(session.businessId),
        c.readModel.products(session.businessId),
        shift.flatMapLatest { s -> s?.let { c.readModel.stockBalances(it.vehicle) } ?: flowOf(emptyMap()) },
        c.readModel.conversions(),
        c.readModel.invoiceRows(session.actor, session.businessId),
    ) { cust, prod, stock, conv, inv -> CatalogueUi(cust, prod, stock, conv, inv, shift.value?.vehicleName) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CatalogueUi())

    init { viewModelScope.launch { shift.value = c.readModel.shiftContext(session.actor) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogueScreen(session: Session, onOpenCustomer: (CustomerId) -> Unit) {
    val c = LocalAppContainer.current
    val vm: CatalogueViewModel = viewModel(key = "catalogue-${session.actor.userId}") { CatalogueViewModel(c, session) }
    val ui by vm.ui.collectAsStateWithLifecycleCompat()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Customers") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Products") })
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (tab == 0) {
                TextButton(onClick = { adding = true }) { Text("+ New customer") }
                val shown = ui.customers.filter { it.name.contains(query.trim(), ignoreCase = true) }
                if (shown.isEmpty()) EmptyState("No customers", "Add a customer, or check the search.")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.id.toString() }) { cust ->
                        PiCard(Modifier.clickable { onOpenCustomer(cust.id) }) {
                            Text(cust.name, style = MaterialTheme.typography.titleMedium)
                            Text(listOfNotNull(cust.phone, cust.address).joinToString(" · ").ifBlank { "No contact details" },
                                style = MaterialTheme.typography.bodySmall)
                            Text("${ui.invoices.count { it.customerId == cust.id }} invoice(s) on this device", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                Text(
                    if (session.isOwner) "Catalogue editing and price publishing arrive in Task 08." else "Prices are set by the owner. Drivers cannot change the catalogue.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.products.filter { it.name.contains(query.trim(), ignoreCase = true) || it.sku.contains(query.trim(), ignoreCase = true) },
                        key = { it.id.toString() }) { p ->
                        PiCard {
                            Text(p.name + if (!p.active) " (inactive)" else "", style = MaterialTheme.typography.titleMedium)
                            Text("SKU ${p.sku}", style = MaterialTheme.typography.bodySmall)
                            Text(p.units.joinToString(" · ") { "${it.unit}: ${it.price?.money?.format() ?: "price on invoice"}" })
                            val bal = ui.stock[p.id]
                            val base = p.units.firstNotNullOfOrNull { ui.conversions.find(p.id, it.unit)?.baseUnit }
                            if (bal != null && base != null) {
                                Text("${ui.vehicleName ?: "Vehicle"}: ${bal.saleable.stripTrailingZeros().toPlainString()} $base saleable" +
                                    if (bal.quarantined.signum() != 0) " · ${bal.quarantined.stripTrailingZeros().toPlainString()} $base held (returns)" else "",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) CustomerPicker(emptyList(), session, onPick = { adding = false; onOpenCustomer(it.id) }, onDismiss = { adding = false })
}

@Composable
fun CustomerDetailScreen(session: Session, id: CustomerId, onNewInvoice: (CustomerId) -> Unit, onOpenInvoice: (InvoiceId) -> Unit, canIssue: Boolean) {
    val c = LocalAppContainer.current
    val vm: CatalogueViewModel = viewModel(key = "catalogue-${session.actor.userId}") { CatalogueViewModel(c, session) }
    val ui by vm.ui.collectAsStateWithLifecycleCompat()
    val cust = ui.customers.firstOrNull { it.id == id }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (cust == null) { EmptyState("Customer not found", ""); return@Column }
        SectionLabel("Customer")
        Text(cust.name, style = MaterialTheme.typography.headlineSmall)
        PiCard {
            Text("Phone: ${cust.phone ?: "—"}")
            Text("Address: ${cust.address ?: "—"}")
            Text("Editing customer details is owner-only and arrives in Task 08.", style = MaterialTheme.typography.bodySmall)
        }
        if (!session.isOwner) {
            Button(onClick = { onNewInvoice(cust.id) }, enabled = canIssue, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("New invoice for ${cust.name}") }
        }
        SectionLabel("Invoices")
        val history = ui.invoices.filter { it.customerId == cust.id }
        if (history.isEmpty()) Text("No invoices for this customer on this device.")
        history.forEach { inv ->
            PiCard(Modifier.clickable { onOpenInvoice(inv.id) }) {
                Row { Text(inv.number, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); Text(inv.total.format()) }
                Text(timeLabel(inv.issuedAtDevice), style = MaterialTheme.typography.bodySmall)
                BadgeRow(issueBadge(inv.voided), paymentBadge(inv.paymentStatus))
            }
        }
    }
}
