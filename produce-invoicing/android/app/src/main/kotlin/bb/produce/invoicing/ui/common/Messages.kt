package bb.produce.invoicing.ui.common

import bb.produce.invoicing.core.data.CustomerError
import bb.produce.invoicing.core.data.IssueRejection
import bb.produce.invoicing.domain.identity.LeaseError
import bb.produce.invoicing.domain.invoice.DraftProblemCode
import bb.produce.invoicing.domain.invoice.IssueError
import bb.produce.invoicing.domain.invoice.VoidError
import bb.produce.invoicing.domain.money.QuantityError
import bb.produce.invoicing.domain.money.UnitPriceError
import bb.produce.invoicing.domain.payment.PaymentError
import bb.produce.invoicing.domain.stock.StockError

/** Plain-language messages for every rejection the data/domain layers can return. */
object Messages {
    fun quantity(e: QuantityError) = when (e) {
        QuantityError.EMPTY -> "Enter a quantity"
        QuantityError.NOT_A_NUMBER -> "Use digits, e.g. 12.5"
        QuantityError.NEGATIVE -> "Quantity cannot be negative"
        QuantityError.ZERO -> "Quantity must be more than 0"
        QuantityError.TOO_MANY_DECIMALS -> "At most 3 decimal places"
        QuantityError.TOO_LARGE -> "At most 99,999"
    }

    fun price(e: UnitPriceError) = when (e) {
        UnitPriceError.EMPTY -> "Enter a price"
        UnitPriceError.NOT_A_NUMBER -> "Use digits, e.g. 4.50"
        UnitPriceError.NEGATIVE -> "Price cannot be negative"
        UnitPriceError.TOO_MANY_DECIMALS -> "At most 2 decimal places (cents)"
        UnitPriceError.TOO_LARGE -> "At most 999,999.00"
    }

    fun problem(p: DraftProblemCode) = when (p) {
        DraftProblemCode.NO_CUSTOMER -> "Choose a customer"
        DraftProblemCode.NO_LINES -> "Add at least one item"
        DraftProblemCode.MISSING_DESCRIPTION -> "Describe the item"
        DraftProblemCode.MISSING_QUANTITY -> "Enter a valid quantity"
        DraftProblemCode.MISSING_PRICE -> "Enter a price"
        DraftProblemCode.ZERO_PRICE_NOT_CONFIRMED -> "Confirm that this item is free of charge"
        DraftProblemCode.UNIT_NOT_ALLOWED -> "This unit is no longer sold for this product"
        DraftProblemCode.PRODUCT_NOT_IN_CATALOGUE -> "Product is not in the current catalogue"
        DraftProblemCode.PRODUCT_INACTIVE -> "Product is no longer sold"
        DraftProblemCode.PRICE_OVERRIDE_NOT_AUTHORIZED -> "Price differs from the catalogue price; changing prices needs owner approval"
        DraftProblemCode.TOTAL_TOO_LARGE -> "Invoice total is above the allowed maximum"
    }

    fun issue(r: IssueRejection): String = when (r) {
        IssueRejection.DraftNotFound -> "This draft no longer exists."
        IssueRejection.BusinessNotFound -> "Business details are missing on this device."
        IssueRejection.NoLease -> "You have no invoice-number allocation on this device, so you cannot issue invoices here."
        IssueRejection.NoOpenShift -> "No open shift for you on this device. Stock must be loaded on a shift before issuing."
        is IssueRejection.MissingConversion -> "A unit on this invoice has no stock conversion set up. Ask the owner to configure it."
        is IssueRejection.Stock -> when (r.error) {
            StockError.INSUFFICIENT_STOCK -> "Not enough stock on the vehicle for this invoice. Nothing was issued."
            else -> "Stock check failed (${r.error}). Nothing was issued."
        }
        is IssueRejection.Domain -> when (val e = r.error) {
            is IssueError.Invalid -> "Fix the highlighted items: " + e.problems.map { problem(it.code) }.distinct().joinToString("; ")
            is IssueError.Lease -> when (e.error) {
                LeaseError.REVOKED -> "Your invoice-number allocation was revoked. Issuing is blocked until the office re-provisions this device."
                LeaseError.EXPIRED -> "Your invoice-number allocation has expired. Return to the office to renew it."
                LeaseError.WRONG_DEVICE -> "Your invoice numbers are allocated to another device."
                LeaseError.WRONG_DRIVER -> "This allocation belongs to another driver."
                LeaseError.SEQUENCE_EXHAUSTED -> "Your invoice numbers are used up. The owner must assign a new driver code."
            }
            IssueError.CustomerMismatch -> "The customer changed while issuing. Try again."
            IssueError.ActorNotDriver -> "Only the driver who owns this draft can issue it."
            IssueError.BusinessMismatch -> "This draft belongs to another business."
        }
    }

    fun payment(e: PaymentError?) = when (e) {
        null -> "Invoice not found."
        PaymentError.ALREADY_PAID -> "Already marked paid."
        PaymentError.NOT_PAID -> "This invoice is not marked paid."
        PaymentError.INVOICE_VOIDED -> "Void invoices cannot be marked paid."
        PaymentError.METHOD_REQUIRED -> "Choose how the customer paid."
        PaymentError.METHOD_NOT_ENABLED -> "That payment method is not enabled."
        PaymentError.AMOUNT_MUST_EQUAL_OUTSTANDING -> "Only full payment of the invoice total can be recorded."
        PaymentError.NOT_AUTHORIZED -> "You are not allowed to do this."
        PaymentError.REASON_REQUIRED -> "A reason is required."
        PaymentError.UNKNOWN_EVENT -> "That payment entry no longer applies."
        PaymentError.EXTERNAL_REFERENCE_REJECTED -> "Reference refused: never enter card numbers (max 40 characters)."
        PaymentError.EVENT_ID_CONFLICT -> "Conflicting payment entry. Nothing was changed."
    }

    fun void(e: VoidError?) = when (e) {
        null -> "Invoice not found."
        VoidError.NOT_AUTHORIZED -> "Only the owner can void an invoice."
        VoidError.REASON_REQUIRED -> "A reason is required."
        VoidError.ALREADY_VOIDED -> "Already void."
    }

    fun customer(e: CustomerError) = when (e) {
        CustomerError.NAME_REQUIRED -> "Enter the customer name"
        CustomerError.DUPLICATE_NAME -> "A customer with this name already exists"
        CustomerError.INVALID_PHONE -> "Phone: digits, spaces, dashes or brackets, at least 6"
        CustomerError.NOT_AUTHORIZED -> "Not allowed"
    }
}
