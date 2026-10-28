package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.api.msg.BusyReport;
import org.qw3rtrun.p3d.api.msg.PositionReported;
import org.qw3rtrun.p3d.api.msg.CapabilityReport;
import org.qw3rtrun.p3d.api.msg.ErrorReport;
import org.qw3rtrun.p3d.api.msg.FirmwareReport;
import org.qw3rtrun.p3d.api.msg.GEvent;
import org.qw3rtrun.p3d.api.msg.TemperatureReport;
import org.qw3rtrun.p3d.api.msg.TemperatureReported;
import org.qw3rtrun.p3d.api.msg.UnknownReply;
import org.qw3rtrun.p3d.g.marlin.event.CapabilityRs;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.marlin.event.PositionRs;
import org.qw3rtrun.p3d.g.marlin.event.TemperatureRs;
import org.qw3rtrun.p3d.g.protocol.BusyRs;
import org.qw3rtrun.p3d.g.protocol.ErrorRs;
import org.qw3rtrun.p3d.g.protocol.GRs;

/**
 * The bridge: one firmware reply, as the application models it.
 *
 * <p><strong>Two models, on purpose, and this is the only place that knows both.</strong>
 * {@code :gcode} models what the wire can say - seven kinds of sensor, a map of hotends, nullable
 * targets, {@code BigDecimal} everywhere, because that is what a printer sends and the module has
 * to stay portable to JS, C and Rust. {@code api} models what the application has decided to care
 * about - one hotend and one bed, no nulls, plain {@code double}. Neither is wrong and neither is a
 * subset of the other, so they are not one model wearing two hats.
 *
 * <p>They used to be. {@code GRs} extended {@code GEvent}, {@code OkRs} extended
 * {@code OKReceivedEvent}, and {@code TemperatureRs} carried {@code hotend()} and {@code bed()}
 * methods that existed for no reason except to satisfy an interface two modules away. That made
 * {@code :gcode} - the one module whose whole claim is that it depends on nothing - depend on the
 * application's vocabulary, and it meant every change to what the host cares about reached into the
 * protocol.
 *
 * <p><strong>What is not translated is not published.</strong> An {@code ok}, a {@code wait} and a
 * {@code Resend:} drive the send window; {@link HostTerminal} consumes those before they reach
 * here. Everything else that no arm below claims becomes an {@link UnknownReply} rather than
 * disappearing - {@code :gcode} decodes a good deal more than the host acts on, and one of those
 * on the bus is the signal that a reply has earned a typed event.
 *
 * <p><strong>The {@code ok} is the exception, and it is not translated here.</strong> It used to be
 * consumed on the grounds that it said nothing a subscriber had not already learned from the send
 * window - which was never true, because no subscriber can see one. What it says is republished as
 * a {@code BlockAcknowledged}, built by {@link HostTerminal} rather than by this class: the line
 * number an {@code ok} carries is the window's, not the reply's, so it is not a translation of the
 * reply and does not belong on this side of the bridge.
 */
final class ReplyEvents {

    private ReplyEvents() {
    }

    /**
     * Matched on the family interfaces rather than the concrete classes, in the order that makes
     * the overlaps come out right.
     *
     * <p>The arms are not a partition. {@code OkTemperatureRs} is an {@code OkRs} and a
     * {@code TemperatureRs}; by the time a reply reaches here its acknowledgement has already been
     * taken by the send window, so what is left of it is the temperature - which is why the
     * temperature arm is first and why an {@code M105} asked on demand is answered at all.
     */
    static GEvent of(GRs<?> reply) {
        return switch (reply) {
            case TemperatureRs<?> t -> new TemperatureReported(report(t.getHotend()), report(t.getBed()));
            case FirmwareInfoRs f -> new FirmwareReport(f.getKeyValues());
            case CapabilityRs c -> new CapabilityReport(c.encode(), c.getCapability(), c.getEnabled());
            case ErrorRs e -> new ErrorReport(e.getMessage(), e.getLastLine());
            case BusyRs<?> b -> new BusyReport(b.getReason());
            case PositionRs p -> new PositionReported(axes(p));
            default -> new UnknownReply(reply.encode());
        };
    }

    /**
     * Where the machine says its axes are, as plain numbers keyed by axis letter.
     *
     * <p>The protocol reports {@code BigDecimal} per axis and a second map of stepper counts; the
     * application carries the first as {@code double}, because that is what a page renders and
     * what a move command is expressed in. The counts are dropped here rather than in
     * {@code :gcode} - they are the firmware's internal position and differ from the planner's
     * while moves are still queued, so publishing both would invite a reader to pick the wrong one.
     */
    private static java.util.Map<String, Double> axes(PositionRs position) {
        var out = new java.util.LinkedHashMap<String, Double>();
        position.getAxes().forEach((axis, value) -> out.put(String.valueOf(axis), value.doubleValue()));
        return out;
    }

    /**
     * One sensor reading, flattened.
     *
     * <p>The application's record has no nullable fields, so an absent target reads as 0.0 and an
     * absent power as 0 - which is a lossy conversion and the reason it lives here rather than in
     * {@code :gcode}: the protocol must keep "the firmware did not say" distinct from "the firmware
     * said zero", and the application has decided it does not need to.
     *
     * <p>A sensor the reply did not mention at all stays null, because that is a different
     * statement again: no bed reading is not a bed reading of zero.
     */
    private static TemperatureReport report(HeaterReading reading) {
        if (reading == null) {
            return null;
        }
        return new TemperatureReport(
                reading.getCurrent().doubleValue(),
                reading.getTarget() == null ? 0.0 : reading.getTarget().doubleValue(),
                reading.getPower() == null ? 0 : reading.getPower());
    }
}
