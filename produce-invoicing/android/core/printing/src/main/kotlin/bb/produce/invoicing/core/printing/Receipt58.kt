package bb.produce.invoicing.core.printing

import bb.produce.invoicing.domain.common.BarbadosTime
import bb.produce.invoicing.domain.invoice.InvoiceStatus
import bb.produce.invoicing.domain.invoice.IssuedInvoice
import bb.produce.invoicing.domain.money.Money

/**
 * Plain-text 58 mm receipt layout used for on-screen preview (Task 03). Task 06 drives the same layout
 * from a certified printer profile (columns, code page, glyph fallback) and produces ESC/POS bytes.
 *
 * Rules (docs/04, docs/11):
 *  - the customer-facing `PRE-INV-SUF` is the invoice number; the driver name is printed separately;
 *  - the internal register reference is printed only once assigned, and labelled as internal;
 *  - copies say COPY at top and bottom; voided invoices say VOID;
 *  - wording for tax/legal fields is NOT client-approved yet, so the layout says what it does not include.
 */
object Receipt58 {
    /** Typical 58 mm printable width at the default font (384 dots / 12-dot glyphs). Profile-specific in Task 06. */
    const val DEFAULT_COLUMNS = 32

    fun render(invoice: IssuedInvoice, copy: Boolean = false, columns: Int = DEFAULT_COLUMNS): List<String> {
        require(columns in 24..64) { "Unsupported column count $columns" }
        val out = mutableListOf<String>()
        val rule = "-".repeat(columns)
        fun center(s: String) = wrap(s, columns).forEach { line -> out += " ".repeat((columns - line.length) / 2) + line }
        fun pair(left: String, right: String) {
            val space = columns - left.length - right.length
            if (space >= 1) out += left + " ".repeat(space) + right
            else { wrap(left, columns).forEach { out += it }; out += right.padStart(columns) }
        }
        fun para(s: String) = wrap(s, columns).forEach { out += it }

        if (copy) center("*** COPY ***")
        if (invoice.status == InvoiceStatus.VOIDED) center("*** VOID ***")
        center(invoice.business.name)
        center(invoice.business.address)
        center(invoice.business.phone)
        out += rule
        para("Invoice: ${invoice.number.text}")
        para("Date: ${BarbadosTime.display(invoice.issuedAtDevice)}")
        para("Driver: ${invoice.driver.displayName}")
        para("Bill to: ${invoice.customer.name}")
        out += rule
        for (line in invoice.lines) {
            para(line.description + if (line.unitPrice.isZero) " (no charge)" else "")
            pair("  ${line.quantity} ${line.unit} x ${line.unitPrice.money.formatAmount()}", line.lineTotal.formatAmount())
        }
        out += rule
        pair("TOTAL ${invoice.total.currency.code}", invoice.total.formatAmount())
        if (!invoice.taxTotal.isZero) pair("of which tax", invoice.taxTotal.formatAmount())
        para("Prices in ${invoice.total.currency.code}. Tax not applied.")
        invoice.registerReference?.let { para("Office reference (internal): ${it.text}") }
        invoice.business.invoiceFooter?.let { out += rule; para(it) }
        if (copy) { out += rule; center("*** COPY ***") }
        return out
    }

    /** Greedy word wrap; words longer than the width are hard-split. */
    internal fun wrap(text: String, width: Int): List<String> {
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            var w = word
            while (w.length > width) {
                if (current.isNotEmpty()) { lines += current.toString(); current = StringBuilder() }
                lines += w.take(width); w = w.drop(width)
            }
            if (current.isEmpty()) current.append(w)
            else if (current.length + 1 + w.length <= width) current.append(' ').append(w)
            else { lines += current.toString(); current = StringBuilder(w) }
        }
        if (current.isNotEmpty() || lines.isEmpty()) lines += current.toString()
        return lines
    }

    @Suppress("unused")
    private fun Money.plain() = formatAmount()
}
