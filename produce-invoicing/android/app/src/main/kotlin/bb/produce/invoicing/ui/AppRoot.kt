package bb.produce.invoicing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import bb.produce.invoicing.LocalAppContainer
import bb.produce.invoicing.core.data.DemoSeeder
import bb.produce.invoicing.domain.common.CustomerId
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.session.Session
import bb.produce.invoicing.session.SignInOption
import bb.produce.invoicing.ui.catalogue.CatalogueScreen
import bb.produce.invoicing.ui.catalogue.CustomerDetailScreen
import bb.produce.invoicing.ui.common.Notice
import bb.produce.invoicing.ui.common.Tone
import bb.produce.invoicing.ui.draft.DraftEditorScreen
import bb.produce.invoicing.ui.draft.DraftEditorViewModel
import bb.produce.invoicing.ui.home.HomeScreen
import bb.produce.invoicing.ui.invoices.InvoiceDetailContent
import bb.produce.invoicing.ui.invoices.InvoicesScreen
import bb.produce.invoicing.ui.invoices.ReceiptPreviewScreen
import bb.produce.invoicing.ui.settings.SettingsScreen
import java.time.Instant
import java.util.UUID

private class StartupFacts(val storage: String, val options: List<SignInOption>)

/** Sign-in gate, then the adaptive shell. */
@Composable
fun AppRoot() {
    val c = LocalAppContainer.current
    val session by c.sessions.session.collectAsState()
    val startup by produceState<StartupFacts?>(null) {
        value = runCatching {
            val device = c.repository.deviceId()
            if (c.seedSyntheticData) DemoSeeder(c.database).seedIfEmpty(device, Instant.now())
            StartupFacts(storageStatus(c.repository.storageSummary(), c.seedSyntheticData), c.auth.options(device))
        }.getOrElse { StartupFacts("Error opening local storage: ${it.javaClass.simpleName}", emptyList()) }
    }
    val s = session
    if (s == null) {
        SetupStatusScreen(
            c.config,
            storage = startup?.storage ?: "Opening…",
            intro = c.auth.unavailableReason ?: "Device status. Invoicing is not available until someone signs in.",
        ) {
            startup?.options?.forEach { option ->
                Button(onClick = { c.sessions.signIn(option.session) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("sign-in-${option.label}")) {
                    Column {
                        Text("Continue as ${option.label}")
                        Text(option.detail, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (startup?.options?.isNotEmpty() == true) {
                Notice("Developer sign-in: no password and no server check. Real enrollment and sign-in arrive in Task 04.", Tone.WARN)
            }
        }
    } else {
        Shell(s)
    }
}

private object Routes {
    const val HOME = "home"
    const val INVOICES = "invoices"
    const val CATALOGUE = "catalogue"
    const val SETTINGS = "settings"
    const val DRAFT = "draft?id={id}&customer={customer}"
    const val INVOICE = "invoice/{id}"
    const val RECEIPT = "receipt/{id}"
    const val CUSTOMER = "customer/{id}"
    fun draft(id: String? = null, customer: String? = null) = "draft?id=${id ?: ""}&customer=${customer ?: ""}"
}

private data class TopLevel(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun Shell(session: Session) {
    val c = LocalAppContainer.current
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val canIssue by produceState(false, session) { value = c.readModel.issuingLease(session.actor)?.revoked == false }
    val items = buildList {
        add(TopLevel(Routes.HOME, "Home", Icons.Filled.Home))
        if (!session.isOwner) add(TopLevel(Routes.DRAFT, "New invoice", Icons.Filled.Add))
        add(TopLevel(Routes.INVOICES, "Invoices", Icons.Filled.List))
        add(TopLevel(Routes.CATALOGUE, "Catalogue", Icons.Filled.Person))
        add(TopLevel(Routes.SETTINGS, "Settings", Icons.Filled.Settings))
    }
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            items.forEach { item ->
                item(
                    selected = current == item.route,
                    onClick = { nav.navigateTop(if (item.route == Routes.DRAFT) Routes.draft() else item.route) },
                    icon = { Icon(item.icon, contentDescription = item.label) },
                    label = { Text(item.label) },
                    modifier = Modifier.testTag("nav-${item.label}"),
                )
            }
        },
    ) {
        NavHost(nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(session, onNewInvoice = { nav.navigate(Routes.draft()) }, onOpenDraft = { nav.navigate(Routes.draft(it)) },
                    onOpenInvoices = { nav.navigateTop(Routes.INVOICES) })
            }
            composable(
                Routes.DRAFT,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                    navArgument("customer") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { e ->
                val id = e.arguments?.getString("id").orEmpty().ifBlank { null }
                val customer = e.arguments?.getString("customer").orEmpty().ifBlank { null }
                val vm: DraftEditorViewModel = viewModel(key = "draft-${id ?: e.id}") { DraftEditorViewModel(c, session, id, customer) }
                DraftEditorScreen(vm, session,
                    onIssued = { inv -> nav.navigate("invoice/$inv") { popUpTo(Routes.HOME) } },
                    onBack = { nav.popBackStack() })
            }
            composable(Routes.INVOICES) {
                InvoicesScreen(session, onOpen = { nav.navigate("invoice/$it") }, onPreview = { nav.navigate("receipt/$it") })
            }
            composable(Routes.INVOICE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                InvoiceDetailContent(session, InvoiceId(UUID.fromString(e.arguments!!.getString("id"))), onPreview = { nav.navigate("receipt/$it") })
            }
            composable(Routes.RECEIPT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                ReceiptPreviewScreen(session, InvoiceId(UUID.fromString(e.arguments!!.getString("id"))))
            }
            composable(Routes.CATALOGUE) { CatalogueScreen(session, onOpenCustomer = { nav.navigate("customer/$it") }) }
            composable(Routes.CUSTOMER, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                CustomerDetailScreen(session, CustomerId(UUID.fromString(e.arguments!!.getString("id"))),
                    onNewInvoice = { nav.navigate(Routes.draft(customer = it.toString())) },
                    onOpenInvoice = { nav.navigate("invoice/$it") }, canIssue = canIssue)
            }
            composable(Routes.SETTINGS) { SettingsScreen(session, onSignOut = { c.sessions.signOut() }) }
        }
    }
}

/**
 * Top-level tab navigation: clear back to Home, then open the tab. Deliberately NO saveState/restoreState:
 * restoring Home's saved stack re-opened the draft editor when the user tapped Home (found by FieldFlowTest).
 * Drafts are auto-saved to Room, so leaving the editor loses nothing.
 */
private fun NavHostController.navigateTop(route: String) = navigate(route) {
    popUpTo(graph.startDestinationId) { inclusive = route == Routes.HOME }
    launchSingleTop = true
}

@Suppress("unused") private val spacing = Arrangement.spacedBy(8.dp)
