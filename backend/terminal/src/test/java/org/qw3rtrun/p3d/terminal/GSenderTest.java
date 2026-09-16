package org.qw3rtrun.p3d.terminal;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.code.core.token.GCommand;
import org.qw3rtrun.p3d.g.code.dsl.GKt;
import org.qw3rtrun.p3d.g.marlin.MarlinG;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GSenderTest {

    private final List<String> sent = new ArrayList<>();
    private final GSender g = new GSender(this::onG);

    private void onG(String gcode) {
        sent.add(gcode);
    }

    @Test
    void aJavaMethodReferenceIsAValidSink() {
        GSender sender = new GSender(this::onG);

        sender.firmwareInfo();
        sender.autoReportTemp(1);
        sender.setBedTemperature(new BigDecimal("60"));

        assertEquals(List.of("M115", "M155 S1", "M140 S60"), sent);
    }

    @Test
    void sendGCommandAndGRQ() {
        GCommand command = GKt.G(28);
        g.send(command);
        g.send(MarlinG.INSTANCE.firmwareInfo());

        assertEquals(List.of("G28", "M115"), sent);
    }

    @Test
    void namedOperationsOfTheOldJavaFacadeStillWork() {
        g.m115();
        g.m105();
        g.m105(1);
        g.m155(2);
        g.m140(new BigDecimal("60"));

        assertEquals(List.of("M115", "M105", "M105 T1", "M155 S2", "M140 S60"), sent);
    }

    @Test
    void m104CarriesTheToolIndexAndTheTemperature() {
        g.m104(0, new BigDecimal("60.00"));
        g.m104(1, new BigDecimal("210.50"));

        assertEquals(List.of("M104 S60.00 T0", "M104 S210.50 T1"), sent);
    }

    @Test
    void aTemperatureIsWrittenWithTheScaleItCarries() {
        g.m104(0, new BigDecimal("60"));
        g.m140(new BigDecimal("60.000"));

        assertEquals(List.of("M104 S60 T0", "M140 S60.000"), sent);
    }

    @Test
    void aliasesAgreeWithTheNumberedOperations() {
        g.firmwareInfo();
        g.m115();
        g.tempReport(1);
        g.m105(1);
        g.autoReportTemp(2);
        g.m155(2);
        g.setBedTemperature(new BigDecimal("60"));
        g.m140(new BigDecimal("60"));
        g.setHotendTemperature(1, new BigDecimal("60"));
        g.m104(1, new BigDecimal("60"));

        assertEquals(sent.get(0), sent.get(1));
        assertEquals(sent.get(2), sent.get(3));
        assertEquals(sent.get(4), sent.get(5));
        assertEquals(sent.get(6), sent.get(7));
        assertEquals(sent.get(8), sent.get(9));
    }
}
