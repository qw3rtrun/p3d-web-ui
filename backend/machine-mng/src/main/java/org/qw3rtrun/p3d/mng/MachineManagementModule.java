package org.qw3rtrun.p3d.mng;

import org.qw3rtrun.p3d.core.bus.CommandQueryService;
import org.qw3rtrun.p3d.core.model.Descriptor;
import org.qw3rtrun.p3d.core.service.MachineManagementService;
import org.qw3rtrun.p3d.mng.service.MachineEventBus;
import org.qw3rtrun.p3d.mng.service.MachineManagementImpl;
import org.qw3rtrun.p3d.mng.service.PrinterRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    public static MachineManagementService dbPrinterManager(PrinterRepository repository, MachineEventBus eventBus) {
        return new MachineManagementImpl(eventBus, repository);
    }

    @Bean
    public static CommandQueryService<UUID, Descriptor> uuidDescriptorCommandQueryService(MachineManagementService service) {
        return new CommandQueryService<>(UUID.class, service::fetch);
    }

}
