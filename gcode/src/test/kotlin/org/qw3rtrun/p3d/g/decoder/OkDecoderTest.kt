package org.qw3rtrun.p3d.g.decoder

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.qw3rtrun.p3d.g.protocol.AdvancedOkRs
import org.qw3rtrun.p3d.g.protocol.OkRsDecoder

class OkDecoderTest {
    private val decoder = OkRsDecoder

    @ParameterizedTest
    @ValueSource(
        strings = [
            "ok",
            "ok\n",
            "OK\n",
            "OK "
        ]
    )
    fun matchOk(line: String) {
        val report = decoder.decode(line)
        Assertions.assertTrue(report != null)
        Assertions.assertFalse(report is AdvancedOkRs)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "ok P15 b3",
            "OK p15 B3",
            "ok P15 B3\n",
            "OK p15 B3\n",
            "Ok b3 p15\n"
        ]
    )
    fun matchAdvancedOk(line: String) {
        val report = decoder.decode(line)
        Assertions.assertTrue(report != null)
        Assertions.assertEquals(AdvancedOkRs(15, 3), report)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "OK n100 B3 P15",
            "Ok N100 P15 b3\n"
        ]
    )
    fun matchAdvancedOkWithNumber(line: String) {
        val report = decoder.decode(line)
        Assertions.assertTrue(report != null)
        Assertions.assertEquals(AdvancedOkRs(15, 3, 100), report)
    }

    // spec 9: malformed replies are the normal case on a serial link, so every one of these must
    // come back as absence rather than an exception out of the decoder.
    @ParameterizedTest
    @ValueSource(
        strings = [
            "ok P9999999999 B1",
            "ok P2147483648 B1",
            "ok N9999999999 B1 P2",
            "ok P B1",
            "ok P-1 B1",
            "ok P+1 B1"
        ]
    )
    fun `malformed field yields absence and never throws`(line: String) {
        Assertions.assertFalse(decoder.decode(line) != null, "should not decode: $line")
    }

    @Test
    fun `what it encodes, it decodes`() {
        // Regression: an absent `N` decoded to -1 while encode() writes out any non-null line
        // number, so a bare advanced ok re-encoded to `ok P15 B3 N-1` and then decoded to null.
        val cases = listOf(AdvancedOkRs(15, 3), AdvancedOkRs(15, 3, 100), AdvancedOkRs(0, 0, 0))
        for (original in cases) {
            Assertions.assertEquals(original, decoder.decode(original.encode())) {
                "round trip failed for " + original.encode()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["ok N2 P15 B3", "ok N3 P15 B3", "ok N4 P15 B3"])
    fun `the acknowledgement a real Marlin sends for a numbered line decodes`(line: String) {
        // Captured from MarlinSim (bugfix-2.1.x) answering N2 M114*37, N3 M114*36 and N4 M31*21.
        // Marlin's ok_to_send() echoes the `N` off the command it is acknowledging *before* ` P`
        // and ` B`; this decoder read it last, and encode() wrote it last to match, so the type
        // round-tripped with itself and matched nothing a printer sends. Every ack of a numbered
        // line fell through every decoder - the whole point of line numbering, unreadable.
        //
        // Pinned to the captured bytes and not to encode(), because agreeing with ourselves is
        // exactly what hid it.
        val report = decoder.decode(line)
        Assertions.assertNotNull(report) { "did not decode: $line" }
        Assertions.assertEquals(15, (report as AdvancedOkRs).planner)
        Assertions.assertEquals(3, report.blockQueue)
        Assertions.assertEquals(line.substring(4, 5).toInt(), report.lineNumber)
    }

    @Test
    fun `encode writes the line number first, the way Marlin does`() {
        Assertions.assertEquals("ok N2 P15 B3", AdvancedOkRs(15, 3, 2).encode())
        Assertions.assertEquals("ok P15 B3", AdvancedOkRs(15, 3).encode())
    }

    @Test
    fun `values at exactly Int MAX_VALUE still decode`() {
        val report = decoder.decode("ok N2147483647 P2147483647 B2147483647")
        Assertions.assertTrue(report != null)
        Assertions.assertEquals(
            AdvancedOkRs(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
            report
        )
    }
}
