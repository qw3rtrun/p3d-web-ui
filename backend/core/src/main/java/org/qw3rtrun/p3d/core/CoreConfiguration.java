package org.qw3rtrun.p3d.core;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
public class CoreConfiguration {

    @Bean
    public PrinterDirectory directory() {
        return new PrinterDirectory();
    }

    @Bean
    public PrinterReactorManager printer(PrinterDirectory directory, PrinterAggregateManager aggregates) {
        return new PrinterReactorManager(directory, aggregates);
    }

    /** The only way into this module from the web - see {@link PrinterControlService}. */
    @Bean
    public PrinterControlService printerControl(PrinterReactorManager manager) {
        return new PrinterControlService(manager);
    }

    /**
     * A new, empty {@link PrinterState} per printer id - which is to say, no persistence at all.
     *
     * <p>Firmware info, capabilities and every temperature a machine ever reported live only as
     * long as the process does. Plan item B2 replaces this with something that reads the aggregate
     * back; until then the name says what it is.
     */
    @Bean
    public static PrinterAggregateManager stubStateManager() {
        return PrinterState::new;
    }
}
