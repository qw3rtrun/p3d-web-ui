package org.qw3rtrun.p3d.core;

import org.qw3rtrun.p3d.api.control.FarmView;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.jobs.JobService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The core submodel's beans: the per-printer runtime and what it is built from.
 *
 * <p>Was half of {@code ApiApplication}, which was a {@code @SpringBootApplication} in what is now
 * the shared API module and wired three submodels' beans from one place. The shared vocabulary has
 * no business owning an entry point or a bean definition, so the class split along the boundary it
 * was straddling - the web half went to the frontend submodel, and this is the rest.
 *
 * <p>{@link #stubStateManager()} is still here and is still why no printer state survives a
 * restart; that is plan item B2.
 */
@Configuration
@EnableScheduling
public class CoreConfiguration {

    @Bean
    public PrinterDirectory directory() {
        return new PrinterDirectory();
    }

    /**
     * How hard a dropped machine is chased, in one place rather than per reactor.
     *
     * <p>A bean so that an assembly can replace it - a farm on a flaky link wants longer backoff
     * than one in the same rack - without any of this module's classes gaining a property to read.
     */
    @Bean
    public ConnectionPolicy connectionPolicy() {
        return ConnectionPolicy.standard();
    }

    @Bean
    public PrinterReactorManager printer(PrinterDirectory directory, PrinterAggregateManager aggregates,
                                         ApplicationEventPublisher roster,
                                         ConnectionPolicy policy) {
        return new PrinterReactorManager(directory, aggregates, roster, policy);
    }

    /** The only way into this module from the web - see {@link PrinterControlService}. */
    @Bean
    public PrinterControlService printerControl(PrinterReactorManager manager, SdUploader uploads) {
        return new PrinterControlService(manager, uploads);
    }

    /**
     * Writes a stored file onto a machine's SD card - the other half of {@code JobFeeder}'s job.
     *
     * <p>Same two collaborators for the same reason: it reads the file through {@code api::files}
     * and hands the blocks to the machine's runtime. What is different is that the transfer holds
     * the connection exclusively, because between {@code M28} and {@code M29} every line goes into
     * the file rather than being executed.
     */
    @Bean
    public SdUploader sdUploader(PrinterReactorManager reactors, GcodeFileService files) {
        return new SdUploader(reactors, files);
    }

    /**
     * A {@link PrinterState} seeded with what the roster remembers about the machine.
     *
     * <p>It used to be {@code PrinterState::new} - a blank aggregate every time, which is why a
     * restart knew nothing about any printer until each was dialled again, and why a command to an
     * unconnected one dereferenced a null extruder count.
     *
     * <p>What is seeded is the *static* half, and it is now both halves of that: the profile the
     * machine reported, and the spec a human declared (plan item B4). Both are
     * {@code printer-management}'s to keep and both arrive on the same announcement, which is why
     * seeding reads one replica entry rather than two. Temperatures are not seeded and should not
     * be - a reading from before the process restarted is not state, it is a stale number that
     * would read as current. The aggregate starts with no temperature and says so until the machine
     * reports one.
     */
    /**
     * The thing that actually prints a file - plan item C3.
     *
     * <p>It names no other module: it hears that a job started from {@code api::jobs}'s event,
     * reads the file through {@code api::files}, and announces what became of the feed. What that
     * does to the job's state is the queue's business and not this module's.
     */
    @Bean
    public JobFeeder jobFeeder(PrinterReactorManager reactors,
                               GcodeFileService files,
                               JobService jobs,
                               ApplicationEventPublisher events) {
        return new JobFeeder(reactors, files, jobs, events);
    }

    /**
     * The farm-wide stream - plan item D2.
     *
     * <p>It opens nothing: it listens where every machine's events already go, so watching forty
     * printers costs forty listeners rather than forty sockets.
     */
    @Bean
    public FarmView farmView(PrinterDirectory directory,
                                                          PrinterReactorManager reactors) {
        return new FarmEvents(directory, reactors);
    }

    @Bean
    public PrinterAggregateManager aggregates(PrinterDirectory directory) {
        return id -> directory.find(id)
                .map(entry -> new PrinterState(id, entry.profile(), entry.spec()))
                .orElseGet(() -> new PrinterState(id));
    }
}
