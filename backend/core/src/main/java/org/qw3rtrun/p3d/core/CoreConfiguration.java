package org.qw3rtrun.p3d.core;

import org.qw3rtrun.p3d.api.control.FarmView;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.jobs.JobService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import reactor.core.scheduler.Schedulers;

/**
 * The core submodel's beans: the per-printer runtime and what it is built from.
 *
 * <p>Was half of {@code ApiApplication}, a {@code @SpringBootApplication} that used to live in the
 * shared API module and wire three submodels' beans from one place; the shared vocabulary has no
 * business owning an entry point, so the class split along the boundary it was straddling - the web
 * half went to the frontend submodel, this is the rest.
 *
 * <p>{@link #aggregates(PrinterDirectory)} is still here and is still why no printer state survives
 * a restart; that is plan item B2.
 */
@Configuration
@EnableScheduling
public class CoreConfiguration {

    @Bean
    public PrinterDirectory directory() {
        return new PrinterDirectory();
    }

    /** A bean so an assembly can replace the backoff (a flaky-link farm wants more than one in the same rack) without any class here gaining a property to read. */
    @Bean
    public ConnectionPolicy connectionPolicy() {
        return ConnectionPolicy.standard();
    }

    /**
     * A bean for the same reason {@link #connectionPolicy()} is one - an assembly can replace the
     * threading model (one scheduler for the whole farm to reproduce something, or platform threads
     * where virtual ones are awkward). Default is one virtual thread per printer for the aggregate
     * and one more for its listeners; until #54 it was {@code Schedulers.single()} for the entire
     * farm, the JVM-wide shared thread rather than a fresh one.
     */
    @Bean
    public PrinterThreads printerThreads() {
        return PrinterThreads.virtualPerPrinter();
    }

    @Bean
    public PrinterReactorManager printer(PrinterDirectory directory, PrinterAggregateManager aggregates,
                                         ApplicationEventPublisher roster,
                                         ConnectionPolicy policy,
                                         PrinterThreads threads) {
        return new PrinterReactorManager(directory, aggregates, roster, policy,
                threads, Schedulers.parallel());
    }

    /** The only way into this module from the web - see {@link PrinterControlService}. */
    @Bean
    public PrinterControlService printerControl(PrinterReactorManager manager, SdUploader uploads) {
        return new PrinterControlService(manager, uploads);
    }

    /**
     * Writes a stored file onto a machine's SD card - the other half of {@code JobFeeder}'s job,
     * same two collaborators, except the transfer holds the connection exclusively (between
     * {@code M28} and {@code M29} every line goes into the file rather than being executed).
     */
    @Bean
    public SdUploader sdUploader(PrinterReactorManager reactors, GcodeFileService files) {
        return new SdUploader(reactors, files);
    }

    /**
     * The thing that actually prints a file - plan item C3. Names no other module: it hears that a
     * job started from {@code api::jobs}'s event, reads the file through {@code api::files}, and
     * announces what became of the feed; what that does to the job's state is the queue's business.
     */
    @Bean
    public JobFeeder jobFeeder(PrinterReactorManager reactors,
                               GcodeFileService files,
                               JobService jobs,
                               ApplicationEventPublisher events) {
        return new JobFeeder(reactors, files, jobs, events);
    }

    /**
     * The farm-wide stream - plan item D2. Opens nothing: listens where every machine's events
     * already go, so watching forty printers costs forty listeners rather than forty sockets.
     * Returns the concrete type, not {@link FarmView} - {@code frontend} still gets it injected by
     * {@code FarmView}, since the advertised type is a subtype, but
     * {@link #farmShutdown(PrinterReactorManager, FarmEvents)} needs the concrete type for
     * {@link FarmEvents#close()}, which must not become part of {@code api::control} since it is
     * this submodel's own shutdown path.
     */
    @Bean
    public FarmEvents farmEvents(PrinterDirectory directory, PrinterReactorManager reactors) {
        return new FarmEvents(directory, reactors);
    }

    /** Lets the farm go when the application is told to stop - issue #87; see {@link FarmShutdown} for why this is a {@code SmartLifecycle} bean and not, say, a {@code @PreDestroy} method. */
    @Bean
    public FarmShutdown farmShutdown(PrinterReactorManager reactors, FarmEvents farmEvents) {
        return new FarmShutdown(reactors, farmEvents);
    }

    /**
     * A {@link PrinterState} seeded with what the roster remembers - it used to be
     * {@code PrinterState::new}, a blank aggregate every time, which is why a restart knew nothing
     * about any printer until dialled again and why a command to an unconnected one dereferenced a
     * null extruder count. Seeds both static halves (the reported profile and the declared spec,
     * plan item B4) from the one replica entry that carries both. Temperatures are deliberately not
     * seeded: a reading from before the restart is a stale number that would read as current, so the
     * aggregate starts with none and says so until the machine reports one.
     */
    @Bean
    public PrinterAggregateManager aggregates(PrinterDirectory directory) {
        return id -> directory.find(id)
                .map(entry -> new PrinterState(id, entry.profile(), entry.spec()))
                .orElseGet(() -> new PrinterState(id));
    }
}
