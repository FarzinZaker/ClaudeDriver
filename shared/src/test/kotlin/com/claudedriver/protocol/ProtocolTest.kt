package com.claudedriver.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtocolTest {

    @Test
    fun `same major-minor is compatible, different is not`() {
        val current = ProtocolVersion.parse("0.1.0")
        assertTrue(current.isCompatibleWith(ProtocolVersion.parse("0.1.9")))
        assertFalse(current.isCompatibleWith(ProtocolVersion.parse("0.2.0")))
        assertFalse(current.isCompatibleWith(ProtocolVersion.parse("1.1.0")))
    }

    @Test
    fun `codec round-trips a sample event unchanged`() {
        val sample = SampleEvent(machineId = "m-1", message = "hello", at = "2026-08-16T00:00:00Z")
        val envelope = Codec.envelope(MessageType.SAMPLE_EVENT, seq = 7, payload = sample)
        val decoded = Codec.decode(Codec.encode(envelope))

        assertEquals(MessageType.SAMPLE_EVENT, decoded.type)
        assertEquals(7, decoded.seq)
        assertEquals(sample, Codec.decodePayload<SampleEvent>(decoded))
    }

    @Test
    fun `isCompatible rejects an incompatible protocol version`() {
        val good = Codec.envelope(MessageType.HELLO, 1, Hello("host", "0.1.0"))
        assertTrue(Codec.isCompatible(good))

        val bad = good.copy(protocolVersion = "9.9.9")
        assertFalse(Codec.isCompatible(bad))
    }
}
