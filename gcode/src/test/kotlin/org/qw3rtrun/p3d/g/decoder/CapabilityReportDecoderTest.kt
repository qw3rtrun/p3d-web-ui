package org.qw3rtrun.p3d.g.decoder

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.qw3rtrun.p3d.g.marlin.decoder.CapabilityReportDecoder

class CapabilityReportDecoderTest {

    private val decoder = CapabilityReportDecoder()

    @Test
    fun `an enabled capability decodes with its name and the whole line`() {
        val line = "Cap:AUTOREPORT_TEMP:1"
        val report = decoder.decode(line)
        Assertions.assertTrue(report.isPresent)
        Assertions.assertEquals("AUTOREPORT_TEMP", report.get().capability())
        Assertions.assertTrue(report.get().enabled())
        Assertions.assertEquals(line, report.get().fullReportString())
    }

    @Test
    fun `a zero value decodes as disabled`() {
        val report = decoder.decode("Cap:EEPROM:0")
        Assertions.assertTrue(report.isPresent)
        Assertions.assertFalse(report.get().enabled())
    }

    @Test
    fun `a value at exactly Int MAX_VALUE decodes as enabled`() {
        val report = decoder.decode("Cap:AUTOREPORT_TEMP:2147483647")
        Assertions.assertTrue(report.isPresent)
        Assertions.assertTrue(report.get().enabled())
    }

    // Characterisation, not a rule: an empty or non-numeric value is reported as "not enabled"
    // rather than rejected. Only a value that cannot be read at all yields absence, below.
    @Test
    fun `an empty value decodes as disabled`() {
        val report = decoder.decode("Cap:AUTOREPORT_TEMP:")
        Assertions.assertTrue(report.isPresent)
        Assertions.assertFalse(report.get().enabled())
    }

    // spec 9: malformed replies are the normal case on a serial link, so these yield absence
    // rather than an exception out of the decoder.
    @ParameterizedTest
    @ValueSource(strings = [
        "Cap:AUTOREPORT_TEMP:99999999999",
        "Cap:AUTOREPORT_TEMP:2147483648"
    ])
    fun `a value too wide for Int yields absence and never throws`(line: String) {
        Assertions.assertFalse(decoder.decode(line).isPresent, "should not decode: $line")
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "",
        "ok",
        "Cap:",
        "Cap::1",
        "Cap:AUTOREPORT_TEMP",
        "Cap:AUTOREPORT_TEMP:1:2"
    ])
    fun `a line that is not a capability report yields absence`(line: String) {
        Assertions.assertFalse(decoder.decode(line).isPresent, "should not decode: $line")
    }
}
