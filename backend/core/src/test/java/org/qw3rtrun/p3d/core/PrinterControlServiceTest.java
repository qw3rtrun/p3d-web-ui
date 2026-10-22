package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import org.qw3rtrun.p3d.api.msg.RequestTemperatureReport;
import org.qw3rtrun.p3d.api.msg.SetBedTarget;
import org.qw3rtrun.p3d.api.msg.SetHotendTarget;
import org.qw3rtrun.p3d.api.msg.SetTemperatureAutoReport;
import org.qw3rtrun.p3d.g.code.core.GEncoder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The outbound translation, and the thread it happens on.
 *
 * <p>This is the twin of {@code ReplyEvents} in {@code :backend:terminal}: that one turns the
 * wire's reply model into the application's, and {@code PrinterControlService} turns the
 * application's commands into Marlin's. Having both is what lets {@code frontend} speak only
 * {@code api} - its request bodies used to be `:gcode`'s generated classes, which made the HTTP
 * contract follow a code generator.
 *
 * <p>The mapping is asserted as <strong>the G-code that comes out</strong> rather than as the
 * command object, because the bytes are what a printer sees and the object is an implementation
 * detail of getting there.
 */
class PrinterControlServiceTest {

    private String marlin(org.qw3rtrun.p3d.api.msg.PrinterCommand command) {
        return GEncoder.INSTANCE.encode(PrinterControlService.toMarlin(command).encode());
    }

    @Test
    void aHotendTargetBecomesM104WithItsTool() {
        assertEquals("M104 S60.0 T0", marlin(new SetHotendTarget(0, new BigDecimal("60.0"))));
        assertEquals("M104 S210.5 T1", marlin(new SetHotendTarget(1, new BigDecimal("210.5"))));
    }

    /**
     * No tool means the first one.
     *
     * <p>The browser sends {@code {index, temp}} and has always been able to leave {@code index}
     * out. Defaulting here rather than leaving a null to travel keeps the aggregate's
     * extruder-count check meaningful.
     */
    @Test
    void aHotendTargetWithNoToolMeansTheFirst() {
        assertEquals("M104 S60.0 T0", marlin(new SetHotendTarget(null, new BigDecimal("60.0"))));
    }

    @Test
    void aBedTargetBecomesM140() {
        assertEquals("M140 S60.0", marlin(new SetBedTarget(new BigDecimal("60.0"))));
    }

    @Test
    void aTemperatureRequestBecomesM105() {
        assertEquals("M105", marlin(new RequestTemperatureReport(null)));
        assertEquals("M105 T2", marlin(new RequestTemperatureReport(2)));
    }

    /**
     * Auto-reporting becomes {@code M155}, carrying the period the user actually chose.
     *
     * <p>It did not, before this. The browser posts {@code {period}}, the endpoint bound `:gcode`'s
     * generated class whose property is {@code seconds}, so the field matched nothing and every
     * request silently asked for the firmware's default. The application naming its own commands is
     * what fixes it: {@code period} is now a field that exists.
     */
    @Test
    void autoReportingCarriesThePeriod() {
        assertEquals("M155 S1", marlin(new SetTemperatureAutoReport(1)));
        assertEquals("M155 S0", marlin(new SetTemperatureAutoReport(0)));
        assertEquals("M155", marlin(new SetTemperatureAutoReport(null)));
    }

    /**
     * Opening a runtime happens off the caller's thread.
     *
     * <p>This assertion used to live in the frontend module, because the controllers did the
     * scheduling. It moved here with the lookup: which thread a resource is opened on is this
     * module's business, and the web layer no longer knows what a reactor is.
     *
     * <p>{@code openReactor} no longer reads a database - {@link PrinterDirectory} answers that -
     * but it is {@code synchronized} and builds a resource, and an event-loop thread should not be
     * queueing on a monitor when several requests arrive for one printer.
     */
    @Test
    void openingARuntimeRunsOffTheCallersThread() {
        var calledOn = new AtomicReference<String>();
        var directory = new PrinterDirectory() {
            @Override
            public Optional<Entry> find(UUID id) {
                calledOn.set(Thread.currentThread().getName());
                return super.find(id);
            }
        };
        var id = UUID.randomUUID();
        directory.onRegistered(new MRegisteredEvent(id, "a printer", "localhost", 8099,
                MachineProfile.unknown(), LocalDateTime.now()));
        var control = new PrinterControlService(
                new PrinterReactorManager(directory, PrinterState::new, event -> {
                }));

        control.state(id).block();

        var thread = calledOn.get();
        assertNotNull(thread, "the directory was never consulted, so the test proved nothing");
        assertTrue(thread.startsWith("boundedElastic"),
                "the runtime was opened on '" + thread + "', which is the caller's thread");
    }
}
