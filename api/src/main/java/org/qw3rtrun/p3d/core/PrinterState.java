package org.qw3rtrun.p3d.core;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.core.msg.ConnectCmd;
import org.qw3rtrun.p3d.firmware.FirmwareInfo;
import org.qw3rtrun.p3d.g.G;
import org.qw3rtrun.p3d.g.code.AutoReportHotendTemperature;
import org.qw3rtrun.p3d.g.code.ReportHotendTemperature;
import org.qw3rtrun.p3d.g.code.SetHotendTemperature;
import org.qw3rtrun.p3d.g.event.CapabilityReportEvent;
import org.qw3rtrun.p3d.g.event.ConnectedEvent;
import org.qw3rtrun.p3d.g.event.DisconnectedEvent;
import org.qw3rtrun.p3d.g.event.FirmwareInfoReportEvent;
import org.qw3rtrun.p3d.core.msg.GEvent;
import org.qw3rtrun.p3d.g.event.TemperatureReport;
import org.qw3rtrun.p3d.g.event.TemperatureReportedEvent;

import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
public class PrinterState {

    @Getter
    private final UUID id = UUID.randomUUID();

    private TemperatureReport th1 = new TemperatureReport(0d, 0d, 0);
    private Consumer<GEvent> emitter;
    private G g;

    private FirmwareInfo firmware = new FirmwareInfo();

    public TemperatureReport getTemperature() {
        return th1;
    }

    protected boolean isConnected() {
        return g != null;
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

    public void onConnected(G g) {
        log.info("Printer connected");
        this.g = g;
        emitter.accept(new ConnectedEvent());
        g.m155(0);
        g.m115();
        g.m155(1);
    }

    //TODO
    public void handle(ConnectCmd cmd) {
        if (!cmd.connect()) {
            log.warn("Handle disconnect");
            g.m155(0);
        }
    }

    public void onDisconnected() {
        log.warn("Printer disconnected");
        this.g = null;
        emitter.accept(new DisconnectedEvent());
    }

    public void on(TemperatureReportedEvent tempEvent) {
        if (!th1.equals(tempEvent.hotend())) {
            this.th1 = tempEvent.hotend();
            emitter.accept(tempEvent);
        }
    }

    public void on(FirmwareInfoReportEvent firmwareInfoReportEvent) {
        if (this.firmware.on(firmwareInfoReportEvent)) {
            emitter.accept(firmwareInfoReportEvent);
        }
    }

    public void on(CapabilityReportEvent capabilityReportEvent) {
        if (this.firmware.on(capabilityReportEvent)) {
            emitter.accept(capabilityReportEvent);
        }
    }

    protected void setEmitter(Consumer<GEvent> emitter) {
        this.emitter = emitter;
    }
}
