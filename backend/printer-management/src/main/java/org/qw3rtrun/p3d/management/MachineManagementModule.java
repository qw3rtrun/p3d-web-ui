package org.qw3rtrun.p3d.management;

import org.qw3rtrun.p3d.api.bus.CommandQueryService;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.qw3rtrun.p3d.management.service.MachineEventBus;
import org.qw3rtrun.p3d.management.service.MachineManagementImpl;
import org.qw3rtrun.p3d.management.service.PrinterRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.UUID;

@Configuration
@EnableJpaRepositories
public class MachineManagementModule {
    @Bean
    public static MachineEventBus machineEventBus(ApplicationEventPublisher publisher) {
        return new MachineEventBus(publisher);
    }

    @Bean
    @Primary
    public static MachineManagementService dbPrinterManager(PrinterRepository repository, MachineEventBus eventBus) {
        return new MachineManagementImpl(eventBus, repository);
    }

    @Bean
    public static CommandQueryService<UUID, Descriptor> uuidDescriptorCommandQueryService(MachineManagementService service) {
        return new CommandQueryService<>(UUID.class, service::fetch);
    }

}
