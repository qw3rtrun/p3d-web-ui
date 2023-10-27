package org.qw3rtrun.p3d.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.core.model.PrinterAggregate;
import org.qw3rtrun.p3d.core.msg.*;
import org.qw3rtrun.p3d.firmware.FirmwareInfo;
import org.qw3rtrun.p3d.g.G;
import org.qw3rtrun.p3d.g.code.AutoReportHotendTemperature;
import org.qw3rtrun.p3d.g.code.ReportHotendTemperature;
import org.qw3rtrun.p3d.g.code.SetBedTemperature;
import org.qw3rtrun.p3d.g.code.SetHotendTemperature;

import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
@RequiredArgsConstructor
public class PrinterState implements PrinterAggregate {

    @Getter
    private final UUID id;

    private boolean connected;

    private boolean online;

    private TemperatureReport th1 = new TemperatureReport(0d, 0d, 0);
    private Consumer<GEvent> emitter;
    private G g;

    private FirmwareInfo firmware = new FirmwareInfo();

    public TemperatureReport getTemperature() {
        return th1;
    }

    public void handle(SetHotendTemperature temperature) {
        log.info("{}", temperature);
        g.code(temperature);
    }

    public void handle(SetBedTemperature temperature) {
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

    public void onOnline(G g) {
        this.g = g;
        if (!this.online) {
            log.info("Printer online");
            this.online = true;
            emitter.accept(new MachineOnlineEvent());
            g.m155(0);
            g.m115();
            g.m155(1);
        }
    }

    //TODO
    public void handle(ConnectCmd cmd) {
        if (!cmd.connect() && this.connected) {
            log.warn("Handle disconnect");
            g.m155(0);
            emitter.accept(new DisconnectedEvent());
        } else if (cmd.connect() && !this.connected) {
            emitter.accept(new ConnectedEvent());
        }
        this.connected = cmd.connect();
    }

    public void onOffline() {
        if (this.online) {
            this.online = false;
            log.warn("Printer offline");
            this.g = null;
            emitter.accept(new MachineOfflineEvent());
        }
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
