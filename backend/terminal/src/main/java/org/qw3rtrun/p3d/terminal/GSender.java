package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.g.code.core.GEncoder;
import org.qw3rtrun.p3d.g.code.core.token.GBlock;
import org.qw3rtrun.p3d.g.code.core.token.GCommand;
import org.qw3rtrun.p3d.g.code.dsl.GKt;
import org.qw3rtrun.p3d.g.protocol.GRq;
import org.qw3rtrun.p3d.g.marlin.MarlinG;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Renders what the builders produce and hands the text to {@code out}.
 *
 * <p>Deliberately thin, and deliberately separate from the builders: it holds no line number and no
 * window, because that is {@code GCodeReader}/{@code GSendWindow}'s job (todo 05) and duplicating it here would
 * give a caller two counters that could disagree. Send framed lines by passing
 * {@code GEncoder.frame(...)} output to a window instead.
 *
 * <p>The named operations below exist to carry over the Java facade this replaces, so its callers can
 * move across unchanged. They are a convenience layer with no privileges - each is one line of DSL.
 */
public class GSender {

    private final Consumer<String> out;

    public GSender(Consumer<String> out) {
        this.out = Objects.requireNonNull(out, "out must not be null");
    }

    public void send(GBlock block) {
        out.accept(GEncoder.INSTANCE.encode(block));
    }

    public void send(GCommand command) {
        send(GKt.line(command));
    }

    public void send(GRq<?> rq) {
        send(rq.encode());
    }

    /** {@code M105} - report hotend temperature, optionally for one tool. */
    public void m105() {
        m105((Integer) null);
    }

    /** {@code M105} - report hotend temperature, optionally for one tool. */
    public void m105(Integer index) {
        send(MarlinG.INSTANCE.tempReport(index));
    }

    /** {@code M115} - firmware info. */
    public void m115() {
        send(MarlinG.INSTANCE.firmwareInfo());
    }

    /** {@code M155} - auto-report temperature, optionally every {@code period} seconds. */
    public void m155() {
        m155((Integer) null);
    }

    /** {@code M155} - auto-report temperature, optionally every {@code period} seconds. */
    public void m155(Integer period) {
        send(MarlinG.INSTANCE.autoReportTemp(period));
    }

    /** {@code M140} - set bed temperature. */
    public void m140(BigDecimal temp) {
        send(MarlinG.INSTANCE.setBedTemperature(temp));
    }

    /**
     * {@code M104} - set the hotend temperature of tool {@code index}.
     *
     * <p>{@code temp} is written with the digits it carries, so the caller chooses the wire format by
     * choosing the scale: {@code BigDecimal("60.00")} is {@code S60.00} and {@code BigDecimal("60")} is {@code S60}. That
     * decision belongs to the caller and not here, because it is what the firmware sees.
     */
    public void m104(int index, BigDecimal temp) {
        send(MarlinG.INSTANCE.setHotendTemperature(temp, index));
    }

    public void tempReport() {
        m105();
    }

    public void tempReport(Integer tool) {
        m105(tool);
    }

    public void autoReportTemp() {
        m155();
    }

    public void autoReportTemp(Integer period) {
        m155(period);
    }

    public void firmwareInfo() {
        m115();
    }

    public void setBedTemperature(BigDecimal temp) {
        m140(temp);
    }

    public void setHotendTemperature(int index, BigDecimal temp) {
        m104(index, temp);
    }
}
