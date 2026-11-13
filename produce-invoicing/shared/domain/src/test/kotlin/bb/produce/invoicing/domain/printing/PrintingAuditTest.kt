package bb.produce.invoicing.domain.printing

import bb.produce.invoicing.domain.Fx
import bb.produce.invoicing.domain.audit.AuditAction
import bb.produce.invoicing.domain.audit.AuditEvent
import bb.produce.invoicing.domain.audit.OutboxEvent
import bb.produce.invoicing.domain.audit.OutboxEventType
import bb.produce.invoicing.domain.common.BarbadosTime
import bb.produce.invoicing.domain.common.EventId
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.common.PrinterProfileId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class PrintingAuditTest {

    private fun profile(statusQuery: Boolean = false) = PrinterProfile(
        PrinterProfileId(Fx.uuid(80)), "Generic", "Test58", null, 58, 384, 32, "CP437",
        PrinterTransport.BLUETOOTH_CLASSIC_SPP, supportsCut = false, supportsStatusQuery = statusQuery,
    )

    private val invId = Fx.issue().invoice.id

    @Test
    fun `profiles are 58 mm only and unverified until certified with evidence`() {
        assertFalse(profile().isCertified)
        assertThrows<IllegalArgumentException> { profile().copy(paperWidthMm = 80) }
        assertThrows<IllegalArgumentException> { Certification.Certified(" ", Instant.EPOCH, listOf("phone")) }
        assertThrows<IllegalArgumentException> { Certification.Certified("photo-001", Instant.EPOCH, emptyList()) }
        assertTrue(profile().copy(certification = Certification.Certified("photo-001", Instant.EPOCH, listOf("Phone A"))).isCertified)
    }

    @Test
    fun `transport sent is not proof of printing - operator or device must confirm`() {
        val p = profile()
        var a = PrintAttempt.queue(invId, p, Fx.driverAbUser, emptyList(), Fx.T0).getOrThrow()
        assertEquals(PrintStatus.QUEUED, a.status)
        assertFalse(a.isCopy)
        a = a.advance(PrintEvidence.STARTED, p, Fx.T0).getOrThrow()
        a = a.advance(PrintEvidence.TRANSPORT_WRITE_OK, p, Fx.T0).getOrThrow()
        assertEquals(PrintStatus.TRANSPORT_SENT, a.status)
        // This printer has no status protocol, so a "device confirmed" claim is refused.
        assertEquals(Outcome.Err(PrintError.DEVICE_CANNOT_CONFIRM), a.advance(PrintEvidence.DEVICE_STATUS_CONFIRMED, p, Fx.T0))
        assertEquals(PrintStatus.CONFIRMED, a.advance(PrintEvidence.OPERATOR_CONFIRMED_PRINTED, p, Fx.T0).getOrThrow().status)
        val withStatus = profile(statusQuery = true)
        assertEquals(PrintStatus.CONFIRMED, a.advance(PrintEvidence.DEVICE_STATUS_CONFIRMED, withStatus, Fx.T0).getOrThrow().status)
    }

    @Test
    fun `interrupted send is uncertain and the next print is a COPY that needs a reason`() {
        val p = profile()
        var a = PrintAttempt.queue(invId, p, Fx.driverAbUser, emptyList(), Fx.T0).getOrThrow()
        a = a.advance(PrintEvidence.STARTED, p, Fx.T0).getOrThrow().advance(PrintEvidence.WRITE_INTERRUPTED, p, Fx.T0).getOrThrow()
        assertEquals(PrintStatus.UNCERTAIN, a.status)
        assertEquals(PrintStatus.UNCERTAIN, PrintAttempt.aggregate(listOf(a)))
        assertEquals(Outcome.Err(PrintError.REASON_REQUIRED_FOR_COPY), PrintAttempt.queue(invId, p, Fx.driverAbUser, listOf(a), Fx.T0))
        val copy = PrintAttempt.queue(invId, p, Fx.driverAbUser, listOf(a), Fx.T0, "Paper jammed").getOrThrow()
        assertTrue(copy.isCopy)
        assertEquals(1, copy.copyNumber)
        assertEquals(invId, copy.invoiceId) // same invoice: no new sale, no new number
    }

    @Test
    fun `connect failure before sending is safe to retry as an original`() {
        val p = profile()
        val failed = PrintAttempt.queue(invId, p, Fx.driverAbUser, emptyList(), Fx.T0).getOrThrow()
            .advance(PrintEvidence.STARTED, p, Fx.T0).getOrThrow()
            .advance(PrintEvidence.CONNECT_FAILED, p, Fx.T0).getOrThrow()
        assertEquals(PrintStatus.FAILED, failed.status)
        val retry = PrintAttempt.queue(invId, p, Fx.driverAbUser, listOf(failed), Fx.T0).getOrThrow()
        assertFalse(retry.isCopy)
        assertEquals(PrintStatus.NOT_PRINTED, PrintAttempt.aggregate(emptyList()))
        assertEquals(Outcome.Err(PrintError.INVALID_TRANSITION), failed.advance(PrintEvidence.TRANSPORT_WRITE_OK, p, Fx.T0))
    }

    @Test
    fun `audit events refuse PII or secret keys in details`() {
        val ok = AuditEvent(EventId.random(), Fx.businessId, Fx.ownerUser, Fx.ownerTablet, AuditAction.INVOICE_VOIDED,
            "invoice", invId.value, Fx.T0, mapOf("reasonCode" to "WRONG_CUSTOMER"))
        assertEquals(AuditAction.INVOICE_VOIDED, ok.action)
        assertThrows<IllegalArgumentException> { ok.copy(details = mapOf("phone" to "000")) }
        assertThrows<IllegalArgumentException> { ok.copy(details = mapOf("Token" to "x")) }
    }

    @Test
    fun `outbox retries count up and ACK is idempotent`() {
        val e = OutboxEvent(EventId.random(), Fx.businessId, OutboxEventType.INVOICE_ISSUED, invId.value, 1, Fx.T0)
        val retried = e.recordFailedAttempt(Fx.T0.plusSeconds(30))
        assertEquals(1, retried.attempts)
        val acked = retried.acknowledge(Fx.T0.plusSeconds(60))
        assertEquals(acked, acked.acknowledge(Fx.T0.plusSeconds(999)))
        assertEquals(Fx.T0.plusSeconds(60), acked.ackedAt)
        assertThrows<IllegalStateException> { acked.recordFailedAttempt(Fx.T0) }
    }

    @Test
    fun `display time is Barbados local with AST`() {
        assertEquals("13 Nov 2026, 09:00 AST", BarbadosTime.display(Fx.T0))
        assertEquals(java.time.LocalDate.of(2026, 11, 12), BarbadosTime.localDate(Instant.parse("2026-11-13T03:59:00Z")))
    }
}
