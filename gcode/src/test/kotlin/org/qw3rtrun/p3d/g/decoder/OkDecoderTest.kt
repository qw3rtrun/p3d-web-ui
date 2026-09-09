package org.qw3rtrun.p3d.g.decoder

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.qw3rtrun.p3d.core.msg.AdvancedOkReceivedEvent
import org.qw3rtrun.p3d.g.marlin.decoder.OkDecoder
import org.qw3rtrun.p3d.g.marlin.event.AdvancedOKReceived

class OkDecoderTest {
    private val decoder = OkDecoder()

    @ParameterizedTest
    @ValueSource(strings = [
        "ok",
        "ok\n",
        "OK\n",
        "OK "
    ])
    fun matchOk(line: String) {
        val report = decoder.decode(line)
        Assertions.assertTrue(report.isPresent)
        Assertions.assertFalse(report.get() is AdvancedOkReceivedEvent)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "ok P15 b3",
        "OK p15 B3",
        "ok P15 B3\n",
        "OK p15 B3\n",
        "Ok b3 p15\n"
    ])
    fun matchAdvancedOk(line: String) {
        val report = decoder.decode(line)
        Assertions.assertTrue(report.isPresent)
        Assertions.assertEquals(AdvancedOKReceived(15, 3, -1), report.get())
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "OK B3 P15 n100",
        "Ok P15 b3 N100\n"
    ])
    fun matchAdvancedOkWithNumber(line: String) {
        val report = decoder.decode(line)
        Assertions.assertTrue(report.isPresent)
        Assertions.assertEquals(AdvancedOKReceived(15, 3, 100), report.get())
    }

    // spec 9: malformed replies are the normal case on a serial link, so every one of these must
    // come back as absence rather than an exception out of the decoder.
    @ParameterizedTest
    @ValueSource(strings = [
        "ok P9999999999 B1",
        "ok P2147483648 B1",
        "ok B1 P2 N9999999999",
        "ok P B1",
        "ok P-1 B1",
        "ok P+1 B1"
    ])
    fun `malformed field yields absence and never throws`(line: String) {
        Assertions.assertFalse(decoder.decode(line).isPresent, "should not decode: $line")
    }

    @Test
    fun `values at exactly Int MAX_VALUE still decode`() {
        val report = decoder.decode("ok P2147483647 B2147483647 N2147483647")
        Assertions.assertTrue(report.isPresent)
        Assertions.assertEquals(
            AdvancedOKReceived(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
            report.get()
        )
    }
}
