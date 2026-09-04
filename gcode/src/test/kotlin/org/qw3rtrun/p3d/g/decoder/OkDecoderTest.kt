package org.qw3rtrun.p3d.g.decoder

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.qw3rtrun.p3d.core.msg.AdvancedOkReceivedEvent
import org.qw3rtrun.p3d.g.event.AdvancedOKReceived

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
}
