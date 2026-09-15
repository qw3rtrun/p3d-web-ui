package org.qw3rtrun.p3d.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.core.model.PrinterAggregate;
import org.qw3rtrun.p3d.core.msg.*;
import org.qw3rtrun.p3d.firmware.CapabilitiesInfo;
import org.qw3rtrun.p3d.firmware.FirmwareInfo;
import org.qw3rtrun.p3d.g.code.AutoReportHotendTemperature;
import org.qw3rtrun.p3d.g.code.ReportHotendTemperature;
import org.qw3rtrun.p3d.g.code.SetBedTemperature;
import org.qw3rtrun.p3d.g.code.SetHotendTemperature;
import org.qw3rtrun.p3d.g.code.dsl.GSender;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private GSender g;

    private FirmwareInfo firmware = new FirmwareInfo();

    private CapabilitiesInfo capabilities = new CapabilitiesInfo();

    public TemperatureReport getTemperature() {
        return temperatureControl.getHotend();
    }

    /**
     * Two decimals, locale-independently.
     *
     * <p>The {@code String.format("%.2f", …)} in the encoder this replaces used the default locale,
     * so under a comma-decimal locale it put {@code M140 S60,00} on the wire — not a number at all
     * by the spec's §3.1, and a command the firmware would reject. The scale is kept at two so the
     * bytes are otherwise unchanged; it is chosen here rather than in the DSL because the wire
     * format of a temperature is this layer's business.
     */
    private static BigDecimal wireTemp(double celsius) {
        return BigDecimal.valueOf(celsius).setScale(2, RoundingMode.HALF_UP);
    }

    public void handle(SetHotendTemperature temperature) {
        log.info("{}", temperature);
        if (temperature.index() < firmware.getRaw().extruderCount()) {
            g.m104(temperature.index(), wireTemp(temperature.temp()));
        } else {
            log.warn("According to FirmwareInfoReport, this machine has only {} extruders. But command's hotend index is {}",
                    firmware.getRaw().extruderCount(), temperature.index());
        }
    }

    public void handle(SetBedTemperature temperature) {
        log.info("{}", temperature);
        g.m140(wireTemp(temperature.temp()));
    }

    public void handle(ReportHotendTemperature report) {
        log.info("{}", report);
        g.m105(report.index());
    }

    public void handle(AutoReportHotendTemperature report) {
        log.info("{}", report);
        g.m155(report.period());
    }

    public void onOnline(GSender g) {
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
