package org.qw3rtrun.p3d.g.decoder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qw3rtrun.p3d.g.event.OkTemperatureReported;
import org.qw3rtrun.p3d.g.event.TemperatureReport;

class TemperatureReportedDecoderTest {

    private TemperatureReportedDecoder decoder = new TemperatureReportedDecoder();

    @ParameterizedTest
    @ValueSource(strings = {
            "ok T:125.25/220.00 B:35.00/60.00 @:128 B@:90",
            "ok T:125.25 /220.00 B:35.00 /60.00 @:128 B@:90\n",
            "ok t:125.25 /220.00 b:35.00 /60.00 @:128 B@:90\n",
            "ok b:35.00 /60.00 t:125.25 /220.00 @:128 B@:90",
    })
    void matchWithOk(String line) {
        var report = decoder.decode(line);
        Assertions.assertTrue(report.isPresent());
        Assertions.assertEquals(new OkTemperatureReported(
                new TemperatureReport(125.25, 220, 128),
                new TemperatureReport(35, 60, 90)
                ), report.get()
        );
    }
}
