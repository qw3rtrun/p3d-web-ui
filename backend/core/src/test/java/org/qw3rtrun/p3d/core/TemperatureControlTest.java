package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.marlin.event.BareTemperatureRs;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.marlin.event.OkTemperatureRs;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What counts as a new temperature, and what "nothing reported yet" looks like.
 *
 * <p>Both have broken: the first version of #85 kept the whole report and read through it, so a
 * machine that had not reported yet threw on every read - and the stream that opens with this
 * value answered every browser with a 500 before the first {@code M155} arrived.
 */
class TemperatureControlTest {

    private final TemperatureControl control = new TemperatureControl();

    @Test
    void nothingReportedIsNothingAndNotAFailure() {
        assertTrue(control.current().isEmpty());
        assertNull(control.getHotend());
        assertNull(control.getBed());
    }

    @Test
    void theFirstReportIsAChange() {
        assertTrue(control.on(bare(reading("24.31"), reading("23.87"))));
        assertEquals(reading("24.31"), control.getHotend());
        assertEquals(reading("23.87"), control.getBed());
    }

    @Test
    void theSameFiguresAgainAreNotAChange() {
        control.on(bare(reading("24.31"), reading("23.87")));
        assertFalse(control.on(bare(reading("24.31"), reading("23.87"))));
    }

    /**
     * The figures an auto-report gave, repeated on the {@code ok} that answers an {@code M105}, are
     * not news. The two reports are different classes and never equal, which is why the comparison
     * is of readings and not of reports.
     */
    @Test
    void theSameFiguresOnAnOkAreNotAChange() {
        control.on(bare(reading("210.00"), reading("60.00")));

        assertFalse(control.on(new OkTemperatureRs(
                reading("210.00"), reading("60.00"), null, null, null, null, null, Map.of())));
        assertTrue(control.current().orElseThrow().getOk(), "and the latest report is what is kept");
    }

    /** Any sensor counts, not only the two a page draws. */
    @Test
    void aChamberThatMovedIsAChange() {
        control.on(new BareTemperatureRs(
                reading("210.00"), null, reading("30.00"), null, null, null, null, Map.of()));

        assertTrue(control.on(new BareTemperatureRs(
                reading("210.00"), null, reading("31.00"), null, null, null, null, Map.of())));
    }

    /** A bed that stops being reported is a change, and reads as absent rather than as zero. */
    @Test
    void aSensorThatIsNoLongerReportedIsAChangeToNothing() {
        control.on(bare(reading("210.00"), reading("60.00")));

        assertTrue(control.on(bare(reading("210.00"), null)));
        assertNull(control.getBed());
    }

    private static BareTemperatureRs bare(HeaterReading hotend, HeaterReading bed) {
        return new BareTemperatureRs(hotend, bed, null, null, null, null, null, Map.of());
    }

    private static HeaterReading reading(String current) {
        return new HeaterReading(new BigDecimal(current), BigDecimal.ZERO, 0);
    }
}
