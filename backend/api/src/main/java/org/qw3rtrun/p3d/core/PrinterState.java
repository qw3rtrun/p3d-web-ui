package org.qw3rtrun.p3d.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.core.model.PrinterAggregate;
import org.qw3rtrun.p3d.core.msg.*;
import org.qw3rtrun.p3d.firmware.CapabilitiesInfo;
import org.qw3rtrun.p3d.firmware.FirmwareInfo;
import org.qw3rtrun.p3d.g.marlin.command.ReportHotendTemperature;
import org.qw3rtrun.p3d.g.marlin.command.SetBedTemperature;
import org.qw3rtrun.p3d.g.marlin.command.SetHotendTemperature;
import org.qw3rtrun.p3d.g.marlin.command.TemperatureAutoReport;
import org.qw3rtrun.p3d.terminal.GSender;

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
     * Two decimals.
     *
     * <p>The {@code String.format("%.2f", …)} in the encoder this replaces used the default locale,
     * so under a comma-decimal locale it put {@code M140 S60,00} on the wire — not a number at all
     * by the spec's §3.1, and a command the firmware would reject. The value now arrives as a
     * {@code BigDecimal} and never passes through a locale, so only the scale is left to set; it is
     * set here rather than in the DSL because the wire format of a temperature is this layer's
     * business.
     */
    private static BigDecimal wireTemp(BigDecimal celsius) {
        return celsius.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * {@code M104} — set a hotend's target.
     *
     * <p>The tool is read from {@code T} and not from {@code I}: Marlin's M104 documents both, with
     * {@code I} a material-preset index, and {@code T} is the one {@code GSender.m104} sends. An
     * absent tool means the active one, which is tool 0 here as it was before.
     */
    public void handle(SetHotendTemperature temperature) {
        log.info("{}", temperature);
        if (temperature.getTemp() == null) {
            log.warn("M104 with no S parameter carries no target temperature: {}", temperature);
            return;
        }
        int index = temperature.getT() == null ? 0 : temperature.getT();
        if (index < firmware.getRaw().extruderCount()) {
            g.m104(index, wireTemp(temperature.getTemp()));
        } else {
            log.warn("According to FirmwareInfoReport, this machine has only {} extruders. But command's hotend index is {}",
                    firmware.getRaw().extruderCount(), index);
        }
    }

    public void handle(SetBedTemperature temperature) {
        log.info("{}", temperature);
        if (temperature.getTemp() == null) {
            log.warn("M140 with no S parameter carries no target temperature: {}", temperature);
            return;
        }
        g.m140(wireTemp(temperature.getTemp()));
    }

    public void handle(ReportHotendTemperature report) {
        log.info("{}", report);
        g.m105(report.getIndex());
    }

    public void handle(TemperatureAutoReport report) {
        log.info("{}", report);
        g.m155(report.getSeconds());
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
