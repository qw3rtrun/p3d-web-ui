package org.qw3rtrun.p3d.g;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.g.code.AutoReportHotendTemperature;
import org.qw3rtrun.p3d.g.code.FirmwareInfo;
import org.qw3rtrun.p3d.g.code.GCode;
import org.qw3rtrun.p3d.g.code.ReportHotendTemperature;

import java.util.function.Consumer;

@RequiredArgsConstructor
public class G {

    private final Consumer<String> consumer;

    public G() {
        this(System.out::print);
    }

    public void m105(int index) {
        code(ReportHotendTemperature.m105(index));
    }

    public void m105() {
        code(ReportHotendTemperature.m105());
    }

    public void tempReport(int toolNumber) {
        m105(toolNumber);
    }

    public void tempReport() {
        m105();
    }

    public void m155(int period) {
        code(AutoReportHotendTemperature.m155(period));
    }

    public void m155() {
        code(AutoReportHotendTemperature.m155());
    }

    public void autoReportTemp(int period) {
        m155(period);
    }

    public void autoReportTemp() {
        m155();
    }

    public void firmwareInfo() {
        m115();
    }

    public void m115() {
        code(new FirmwareInfo());
    }

    public void code(GCode code) {
        consumer.accept(code.encode() + "\n");
    }
}
