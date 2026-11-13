package bb.produce.invoicing.domain.printing

import bb.produce.invoicing.domain.common.EventId
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.common.PrinterProfileId
import bb.produce.invoicing.domain.common.UserId
import java.time.Instant

/** Transports are distinct; BLE is never treated as equivalent to Classic SPP (invariant 16). */
enum class PrinterTransport { BLUETOOTH_CLASSIC_SPP, BLE_VENDOR_GATT, WIFI_TCP_ESCPOS, VENDOR_SDK, ANDROID_PRINT_PDF }

sealed interface Certification {
    /** Default for every profile: architecture exists, model not tested on real hardware. */
    data object Unverified : Certification

    /** Only after a physical 1/8/30-line test on this model (docs/10). */
    data class Certified(val evidenceRef: String, val certifiedAt: Instant, val testedOn: List<String>) : Certification {
        init {
            require(evidenceRef.isNotBlank()) { "Certification needs evidence" }
            require(testedOn.isNotEmpty()) { "Certification needs at least one tested phone/tablet" }
        }
    }
}

/** A 58 mm printer model profile. Paper width alone does not identify the protocol (docs/04). */
data class PrinterProfile(
    val id: PrinterProfileId,
    val manufacturer: String,
    val model: String,
    val firmware: String?,
    val paperWidthMm: Int,
    val printableDots: Int,
    val charsPerLine: Int,
    val codePage: String,
    val transport: PrinterTransport,
    val supportsCut: Boolean,
    /** True only if the device has a status protocol that can confirm paper output. */
    val supportsStatusQuery: Boolean,
    val certification: Certification = Certification.Unverified,
) {
    init {
        require(paperWidthMm == 58) { "MVP supports 58 mm thermal paper only (C02)" }
        require(printableDots in 128..576) { "Implausible printable width $printableDots dots" }
        require(charsPerLine in 16..64) { "Implausible characters per line $charsPerLine" }
        require(manufacturer.isNotBlank() && model.isNotBlank()) { "Manufacturer and model required" }
    }

    val isCertified: Boolean get() = certification is Certification.Certified
}

/**
 * Print status of one attempt (docs/03). TRANSPORT_SENT means bytes were written — NOT that paper
 * printed (invariant 7). CONFIRMED needs a device status acknowledgement or the operator's confirmation.
 */
enum class PrintStatus { NOT_PRINTED, QUEUED, ATTEMPTING, TRANSPORT_SENT, CONFIRMED, UNCERTAIN, FAILED }

enum class PrintEvidence {
    STARTED,
    /** Failed before any bytes were sent (e.g. cannot connect). Safe to retry. */
    CONNECT_FAILED,
    TRANSPORT_WRITE_OK,
    /** Timeout or error while/after sending: it may have printed. */
    WRITE_INTERRUPTED,
    DEVICE_STATUS_CONFIRMED,
    OPERATOR_CONFIRMED_PRINTED,
    OPERATOR_REPORTED_NOT_PRINTED,
}

enum class PrintError { INVALID_TRANSITION, DEVICE_CANNOT_CONFIRM, REASON_REQUIRED_FOR_COPY }

/**
 * A persisted print attempt (persist BEFORE sending bytes). copyNumber 0 = original print; >0 = COPY,
 * which needs a reason and never creates or renumbers an invoice.
 */
data class PrintAttempt(
    val id: EventId,
    val invoiceId: InvoiceId,
    val printerProfileId: PrinterProfileId,
    val transport: PrinterTransport,
    val copyNumber: Int,
    val operator: UserId,
    val createdAt: Instant,
    val status: PrintStatus = PrintStatus.QUEUED,
    val reprintReason: String? = null,
    val updatedAt: Instant = createdAt,
) {
    init {
        require(copyNumber >= 0) { "copyNumber must be >= 0" }
        require(status != PrintStatus.NOT_PRINTED) { "An attempt is never NOT_PRINTED" }
    }

    val isCopy: Boolean get() = copyNumber > 0

    fun advance(evidence: PrintEvidence, profile: PrinterProfile, at: Instant): Outcome<PrintAttempt, PrintError> {
        val next = when (status to evidence) {
            PrintStatus.QUEUED to PrintEvidence.STARTED -> PrintStatus.ATTEMPTING
            PrintStatus.ATTEMPTING to PrintEvidence.CONNECT_FAILED -> PrintStatus.FAILED
            PrintStatus.ATTEMPTING to PrintEvidence.TRANSPORT_WRITE_OK -> PrintStatus.TRANSPORT_SENT
            PrintStatus.ATTEMPTING to PrintEvidence.WRITE_INTERRUPTED -> PrintStatus.UNCERTAIN
            PrintStatus.TRANSPORT_SENT to PrintEvidence.WRITE_INTERRUPTED -> PrintStatus.UNCERTAIN
            PrintStatus.TRANSPORT_SENT to PrintEvidence.DEVICE_STATUS_CONFIRMED,
            PrintStatus.UNCERTAIN to PrintEvidence.DEVICE_STATUS_CONFIRMED -> {
                if (!profile.supportsStatusQuery) return Outcome.Err(PrintError.DEVICE_CANNOT_CONFIRM)
                PrintStatus.CONFIRMED
            }
            PrintStatus.TRANSPORT_SENT to PrintEvidence.OPERATOR_CONFIRMED_PRINTED,
            PrintStatus.UNCERTAIN to PrintEvidence.OPERATOR_CONFIRMED_PRINTED -> PrintStatus.CONFIRMED
            PrintStatus.TRANSPORT_SENT to PrintEvidence.OPERATOR_REPORTED_NOT_PRINTED,
            PrintStatus.UNCERTAIN to PrintEvidence.OPERATOR_REPORTED_NOT_PRINTED -> PrintStatus.FAILED
            else -> return Outcome.Err(PrintError.INVALID_TRANSITION)
        }
        return Outcome.Ok(copy(status = next, updatedAt = at))
    }

    companion object {
        fun queue(
            invoiceId: InvoiceId,
            profile: PrinterProfile,
            operator: UserId,
            previousAttempts: List<PrintAttempt>,
            at: Instant,
            reprintReason: String? = null,
            id: EventId = EventId.random(),
        ): Outcome<PrintAttempt, PrintError> {
            // Any earlier attempt that may have reached paper makes this one a COPY.
            val mayHavePrinted = previousAttempts.any {
                it.invoiceId == invoiceId && it.status in setOf(PrintStatus.TRANSPORT_SENT, PrintStatus.CONFIRMED, PrintStatus.UNCERTAIN)
            }
            val copyNumber = if (mayHavePrinted) previousAttempts.filter { it.invoiceId == invoiceId }.maxOf { it.copyNumber } + 1 else 0
            if (copyNumber > 0 && reprintReason.isNullOrBlank()) return Outcome.Err(PrintError.REASON_REQUIRED_FOR_COPY)
            return Outcome.Ok(PrintAttempt(id, invoiceId, profile.id, profile.transport, copyNumber, operator, at, reprintReason = reprintReason?.trim()))
        }

        /** Invoice-level print badge. Never collapses "sent" into "printed". */
        fun aggregate(attempts: List<PrintAttempt>): PrintStatus = when {
            attempts.isEmpty() -> PrintStatus.NOT_PRINTED
            attempts.any { it.status == PrintStatus.CONFIRMED } -> PrintStatus.CONFIRMED
            else -> attempts.maxBy { it.updatedAt }.status
        }
    }
}
