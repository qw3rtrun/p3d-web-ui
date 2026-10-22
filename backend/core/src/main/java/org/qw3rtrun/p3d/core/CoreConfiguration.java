package org.qw3rtrun.p3d.core;

import org.qw3rtrun.p3d.api.bus.CommandQueryBus;
import org.qw3rtrun.p3d.api.bus.CommandQueryGateway;
import org.qw3rtrun.p3d.api.bus.CommandQueryService;
import org.qw3rtrun.p3d.api.service.PrinterAggregateManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The core submodel's beans: the per-printer runtime and what it is built from.
 *
 * <p>Was half of {@code ApiApplication}, which was a {@code @SpringBootApplication} in what is now
 * the shared API module and wired three submodels' beans from one place. The shared vocabulary has
 * no business owning an entry point or a bean definition, so the class split along the boundary it
 * was straddling - the web half is {@code FrontendConfiguration}, and this is the rest.
 *
 * <p>Every bean below is carried over unchanged, including the two that are scheduled for deletion:
 * {@link CommandQueryGateway} holds a single registration and ignores the response type it is
 * handed, and {@link #stubStateManager()} is why no printer state survives a restart. Plan items A4
 * and B2 deal with them; moving them is not the moment to.
 */
@Configuration
public class CoreConfiguration {

    @Bean
    public CommandQueryGateway gateway(List<CommandQueryService<?, ?>> services) {
        return new CommandQueryBus(services);
    }

    @Bean
    public PrinterReactorManager printer(CommandQueryGateway gateway, PrinterAggregateManager aggregates) {
        return new PrinterReactorManager(gateway, aggregates);
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
