package org.qw3rtrun.p3d.g.decoder

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.qw3rtrun.p3d.core.msg.TemperatureReport
import org.qw3rtrun.p3d.g.marlin.decoder.TemperatureReportedDecoder
import org.qw3rtrun.p3d.g.marlin.event.OkTemperatureReported

class TemperatureReportedDecoderTest {

    private val decoder = TemperatureReportedDecoder()

    @ParameterizedTest
    @ValueSource(strings = [
        "ok T:125.25/220.00 B:35.00/60.00 @:128 B@:90",
        "ok T:125.25 /220.00 B:35.00 /60.00 @:128 B@:90\n",
        "ok t:125.25 /220.00 b:35.00 /60.00 @:128 B@:90\n",
        "ok b:35.00 /60.00 t:125.25 /220.00 @:128 B@:90"
    ])
    fun matchWithOk(line: String) {
        val report = decoder.decode(line)
        Assertions.assertTrue(report.isPresent)
        Assertions.assertEquals(
            OkTemperatureReported(
                TemperatureReport(125.25, 220.0, 128),
                TemperatureReport(35.0, 60.0, 90)
            ),
            report.get()
        )
    }

    // A reply off a serial link is malformed as a matter of course (spec 9): every one of these
    // must come back as absence, and none of them may throw.
    @ParameterizedTest
    @ValueSource(strings = [
        "ok T:21.0 /0.0 B:x1 /0.0 @:0 B@:0",
        "ok T:21.0 /0.0 B: /0.0 @:0 B@:0",
        "ok T:21.0 /0.0 B:- /0.0 @:0 B@:0",
        "ok T:21.0 /0.0 B:. /0.0 @:0 B@:0",
        "ok T:21.0 /0.0 B:1.0 /0.0 @:99999999999 B@:0",
        "ok T:21.0 /0.0 B:1.0 /0.0 @:2147483648 B@:0",
        "ok T:21.0 /0.0 B:1.0 /0.0 @:0 B@:2147483648"
    ])
    fun `malformed field yields absence and never throws`(line: String) {
        Assertions.assertFalse(decoder.decode(line).isPresent, "should not decode: $line")
    }

    @Test
    fun `a power at exactly Int MAX_VALUE still decodes`() {
        val report = decoder.decode("ok T:21.0 /0.0 B:1.0 /0.0 @:2147483647 B@:0")
        Assertions.assertTrue(report.isPresent)
        Assertions.assertEquals(
            OkTemperatureReported(
                TemperatureReport(21.0, 0.0, Int.MAX_VALUE),
                TemperatureReport(1.0, 0.0, 0)
            ),
            report.get()
        )
    }
}
