package bb.produce.invoicing.domain.identity

import bb.produce.invoicing.domain.Fx
import bb.produce.invoicing.domain.common.InvoiceId
import bb.produce.invoicing.domain.common.Outcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

class InvoiceIdentityTest {

    private val bgi = BusinessCode.of("BGI")

    @Test
    fun `N01 - BGI-100-AB and BGI-100-FD are both valid and distinct`() {
        val ab = CustomerInvoiceNumber(bgi, DriverSequence(100), DriverCode.of("AB"))
        val fd = CustomerInvoiceNumber(bgi, DriverSequence(100), DriverCode.of("FD"))
        assertEquals("BGI-100-AB", ab.text)
        assertEquals("BGI-100-FD", fd.text)
        assertNotEquals(ab, fd)
        assertEquals(ab, CustomerInvoiceNumber.parse("BGI-100-AB"))
        assertEquals(fd, CustomerInvoiceNumber.parse("BGI-100-FD"))
    }

    @Test
    fun `register reference is a separate PRE-N identifier`() {
        val ref = RegisterReference(bgi, RegisterSequence(101))
        assertEquals("BGI-101", ref.text)
        assertEquals(ref, RegisterReference.parse("BGI-101"))
        // The two formats can never be confused with each other.
        assertNull(CustomerInvoiceNumber.parse("BGI-101"))
        assertNull(RegisterReference.parse("BGI-100-AB"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["BGI-0100-AB", "BGI-100-ab", "bgi-100-AB", "BGI-100", "BGI--AB", "BGI-1.0-AB", "B-100-AB",
        "BGI-100-AB-X", "BG I-100-AB", "BGI-+100-AB", "BGI-1000000000-AB", "BGI-0-AB", "BGI--100-AB"])
    fun `non-canonical or invalid customer numbers do not parse`(text: String) {
        assertNull(CustomerInvoiceNumber.parse(text))
    }

    @ParameterizedTest
    @CsvSource("bgi,BGI", "' Bgi ',BGI", "AB12,AB12", "ABCDEFGH,ABCDEFGH")
    fun `business codes normalise to upper case`(raw: String, expected: String) {
        assertEquals(expected, BusinessCode.of(raw).value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "B", "BG-I", "B_G", "ABCDEFGHI", "BGÍ", "B G"])
    fun `invalid business codes are rejected`(raw: String) {
        assertNull(BusinessCode.parse(raw))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "A-B", "ABCDEFGHI", "Ä", "A B"])
    fun `invalid driver codes are rejected`(raw: String) {
        assertNull(DriverCode.parse(raw))
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1, -100, 1_000_000_000])
    fun `zero, negative and over-max sequences are rejected`(value: Long) {
        assertThrows<IllegalArgumentException> { DriverSequence(value) }
    }

    @Test
    fun `sequence overflow is explicit, never a wrap-around`() {
        assertEquals(DriverSequence(101), DriverSequence(100).next())
        assertNull(DriverSequence(DriverSequence.MAX).next())
        assertThrows<IllegalArgumentException> { RegisterSequence(0) }
    }

    @Test
    fun `invoice UUIDs are unique`() {
        val ids = List(50_000) { InvoiceId.random() }
        assertEquals(ids.size, ids.toSet().size)
    }

    // ---- fenced lease ----

    @Test
    fun `lease allocates a strictly increasing per-driver sequence`() {
        var lease = Fx.lease(Fx.ab, Fx.deviceAb, next = 100)
        val seen = mutableListOf<Long>()
        repeat(25) {
            val a = lease.allocate(Fx.ab, Fx.deviceAb, Fx.T0).getOrThrow()
            seen += a.sequence.value
            lease = a.updatedLease
        }
        assertEquals((100L..124L).toList(), seen)
        assertEquals(DriverSequence(125), lease.nextSequence)
    }

    @Test
    fun `lease starting number is configurable, not assumed to be 100`() {
        val a = Fx.lease(Fx.fd, Fx.deviceFd, next = 1).allocate(Fx.fd, Fx.deviceFd, Fx.T0).getOrThrow()
        assertEquals(1, a.sequence.value)
    }

    @Test
    fun `lease rejects another device, another driver, revocation, expiry and exhaustion`() {
        val lease = Fx.lease(Fx.ab, Fx.deviceAb)
        assertEquals(Outcome.Err(LeaseError.WRONG_DEVICE), lease.allocate(Fx.ab, Fx.deviceFd, Fx.T0))
        assertEquals(Outcome.Err(LeaseError.WRONG_DRIVER), lease.allocate(Fx.fd, Fx.deviceAb, Fx.T0))
        assertEquals(Outcome.Err(LeaseError.REVOKED), lease.copy(revoked = true).allocate(Fx.ab, Fx.deviceAb, Fx.T0))
        assertEquals(
            Outcome.Err(LeaseError.EXPIRED),
            lease.copy(validUntil = Fx.T0).allocate(Fx.ab, Fx.deviceAb, Fx.T0.plus(Duration.ofSeconds(1))),
        )
        val last = Fx.lease(Fx.ab, Fx.deviceAb, next = DriverSequence.MAX)
        val after = last.allocate(Fx.ab, Fx.deviceAb, Fx.T0).getOrThrow().updatedLease
        assertEquals(Outcome.Err(LeaseError.SEQUENCE_EXHAUSTED), after.allocate(Fx.ab, Fx.deviceAb, Fx.T0))
    }

    @Test
    fun `older epoch is stale - fencing for a returning replaced device`() {
        val current = Fx.lease(Fx.ab, Fx.deviceFd, epoch = 2)
        assertTrue(current.isStale(1))
        assertFalse(current.isStale(2))
        assertThrows<IllegalArgumentException> { Fx.lease(Fx.ab, Fx.deviceAb, epoch = 0) }
    }
}
