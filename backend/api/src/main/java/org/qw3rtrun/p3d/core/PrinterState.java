package org.qw3rtrun.p3d.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.core.model.PrinterAggregate;
import org.qw3rtrun.p3d.core.msg.*;
import org.qw3rtrun.p3d.firmware.CapabilitiesInfo;
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

    private TemperatureControl temperatureControl = new TemperatureControl();
    private Consumer<GEvent> emitter;
    private G g;

    private FirmwareInfo firmware = new FirmwareInfo();

    private CapabilitiesInfo capabilities = new CapabilitiesInfo();

    public TemperatureReport getTemperature() {
        return temperatureControl.getHotend();
    }

    public void handle(SetHotendTemperature temperature) {
        log.info("{}", temperature);
        if (temperature.index() < firmware.getRaw().extruderCount()) {
            g.code(temperature);
        } else {
            log.warn("According to FirmwareInfoReport, this machine has only {} extruders. But command's hotend index is {}",
                    firmware.getRaw().extruderCount(), temperature.index());
        }
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
            g.firmwareInfo(); //M115
            g.autoReportTemp(1); //M105
            emitter.accept(new MachineOnlineEvent());
        }
    }

    //TODO
    public void handle(ConnectCmd cmd) {
        if (!cmd.connect() && this.connected) {
            log.warn("Handle disconnect");
            if (this.online) {
                g.m155(0);
            }
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
        if (temperatureControl.on(tempEvent)) {
            emitter.accept(tempEvent);
        }
    }

    public void on(FirmwareInfoReportEvent firmwareInfoReportEvent) {
        if (this.firmware.on(firmwareInfoReportEvent)) {
            emitter.accept(firmwareInfoReportEvent);
        }
    }

    public void on(CapabilityReportEvent capabilityReportEvent) {
        if (this.capabilities.on(capabilityReportEvent)) {
            emitter.accept(capabilityReportEvent);
        }
    }

    protected void setEmitter(Consumer<GEvent> emitter) {
        this.emitter = emitter;
    }
}
