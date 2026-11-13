package bb.produce.invoicing.core.printing

import bb.produce.invoicing.domain.catalogue.BusinessSnapshot
import bb.produce.invoicing.domain.catalogue.CatalogueVersion
import bb.produce.invoicing.domain.catalogue.CustomerSnapshot
import bb.produce.invoicing.domain.common.Actor
import bb.produce.invoicing.domain.common.BusinessId
import bb.produce.invoicing.domain.common.CustomerId
import bb.produce.invoicing.domain.common.DeviceId
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.LineId
import bb.produce.invoicing.domain.common.Role
import bb.produce.invoicing.domain.common.UserId
import bb.produce.invoicing.domain.identity.BusinessCode
import bb.produce.invoicing.domain.identity.DriverCode
import bb.produce.invoicing.domain.identity.DriverSequence
import bb.produce.invoicing.domain.identity.DriverSequenceLease
import bb.produce.invoicing.domain.identity.DriverSnapshot
import bb.produce.invoicing.domain.identity.RegisterReference
import bb.produce.invoicing.domain.identity.RegisterSequence
import bb.produce.invoicing.domain.invoice.InvoiceDraft
import bb.produce.invoicing.domain.invoice.InvoiceIssuer
import bb.produce.invoicing.domain.invoice.IssueContext
import bb.produce.invoicing.domain.invoice.IssuedInvoice
import bb.produce.invoicing.domain.invoice.ServerRegistration
import bb.produce.invoicing.domain.money.Currency
import bb.produce.invoicing.domain.money.Quantity
import bb.produce.invoicing.domain.money.TaxSnapshot
import bb.produce.invoicing.domain.money.UnitCode
import bb.produce.invoicing.domain.money.UnitPrice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class Receipt58Test {
    private val t0 = Instant.parse("2026-11-13T13:00:00Z")
    private val user = UserId(UUID.randomUUID())
    private val device = DeviceId(UUID.randomUUID())
    private val business = BusinessSnapshot(BusinessId(UUID.randomUUID()), BusinessCode.of("DEMO"), "Demo Produce Co (SYNTHETIC)",
        "1 Example Road (synthetic)", "+1 246 000 0000", null, "Thank you (synthetic footer)", Currency.BBD)

    private fun invoice(lines: Int, longName: Boolean = false): IssuedInvoice {
        var d = InvoiceDraft.start(business.businessId, user, CatalogueVersion(1), t0).withCustomer(CustomerId(UUID.randomUUID()), t0)
        repeat(lines) { i ->
            val desc = if (longName && i == 0) "Extra-long synthetic product description that must wrap neatly" else "Item $i"
            d = d.addCustomLine(desc, UnitCode.of("lb"), t0, LineId(UUID.randomUUID()))
            d = d.updateQuantity(d.lines[i].id, Quantity.of("12.5"), t0).getOrThrow()
            d = d.updateUnitPrice(d.lines[i].id, UnitPrice.ofMinor(450), t0).getOrThrow()
        }
        val ctx = IssueContext(InvoiceId.random(), business, CustomerSnapshot(d.customerId!!, "Sample Grocer A (synthetic)", null, null, null),
            DriverSnapshot(user, DriverCode.of("AB"), "Demo Driver (synthetic)"), Actor(user, Role.DRIVER, device), t0,
            CatalogueVersion(1), emptyMap(), TaxSnapshot.NotApplied(1))
        return InvoiceIssuer.issue(d, ctx, DriverSequenceLease(DriverCode.of("AB"), device, 1, DriverSequence(100))).getOrThrow().invoice
    }

    @Test
    fun `every line fits 32 columns for 1, 8 and 30 line invoices`() {
        for (n in listOf(1, 8, 30)) {
            val r = Receipt58.render(invoice(n, longName = true))
            assertTrue(r.all { it.length <= 32 }, "overflow in $n-line receipt: ${r.filter { it.length > 32 }}")
            assertEquals(n, r.count { it.trimStart().startsWith("12.5 lb x 4.50") })
        }
    }

    @Test
    fun `customer number is primary, driver separate, internal ref only when registered`() {
        val inv = invoice(2)
        val r = Receipt58.render(inv)
        assertTrue("Invoice: DEMO-100-AB" in r)
        assertTrue("Driver: Demo Driver (synthetic)" in r)
        assertFalse(r.any { it.contains("Office reference") })
        assertTrue(r.any { it.startsWith("TOTAL BBD") && it.endsWith("112.50") })

        val reg = inv.withRegistration(ServerRegistration(inv.id, inv.number, RegisterReference(BusinessCode.of("DEMO"), RegisterSequence(7)), t0, t0)).getOrThrow()
        val r2 = Receipt58.render(reg)
        assertTrue(r2.joinToString(" ").contains("Office reference (internal): DEMO-7")) // 35 chars: wraps at 32
        assertTrue(r2.all { it.length <= 32 })
        assertTrue("Invoice: DEMO-100-AB" in r2)
    }

    @Test
    fun `copies are marked at top and bottom and keep the same number`() {
        val r = Receipt58.render(invoice(1), copy = true)
        assertTrue(r.first().contains("*** COPY ***"))
        assertTrue(r.last().contains("*** COPY ***"))
        assertTrue("Invoice: DEMO-100-AB" in r)
    }

    @Test
    fun `wrap hard-splits words longer than the width`() {
        assertEquals(listOf("abcde", "fgh", "ij k"), Receipt58.wrap("abcdefgh ij k", 5))
    }
}
