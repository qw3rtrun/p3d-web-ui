package org.qw3rtrun.p3d.api;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.g.G;
import org.qw3rtrun.p3d.g.code.AutoReportHotendTemperature;
import org.qw3rtrun.p3d.g.code.ReportHotendTemperature;
import org.qw3rtrun.p3d.g.code.SetHotendTemperature;
import org.qw3rtrun.p3d.g.event.TemperatureReport;
import org.qw3rtrun.p3d.g.event.TemperatureReportedEvent;

import java.util.function.Consumer;

@Slf4j
public class PrinterState {

    private TemperatureReport th1 = new TemperatureReport(0d, 0d, 0);
    private Consumer<TemperatureReport> emitter;
    private G g;

    public TemperatureReport getTemperature() {
        return th1;
    }

    public void onConnected() {
        log.info("Printer connected");
        g.m155(1);
    }

    public void handle(SetHotendTemperature temperature) {
        log.info("{}", temperature);
        g.code(temperature);
    }

    public void handle(ReportHotendTemperature report) {
        log.info("{}", report);
        g.code(report);
    }

    public void handle(AutoReportHotendTemperature report) {
        log.info("{}", report);
        g.code(report);
    }

    public void on(TemperatureReportedEvent tempEvent) {
        if (!th1.equals(tempEvent.hotend())) {
            this.th1 = tempEvent.hotend();
            emitter.accept(th1);
        }
    }

    protected void setEmitter(Consumer<TemperatureReport> emitter) {
        this.emitter = emitter;
    }

    public void setG(G g) {
        this.g = g;
    }
}
