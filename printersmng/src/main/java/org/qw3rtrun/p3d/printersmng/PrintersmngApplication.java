package org.qw3rtrun.p3d.printersmng;

import org.qw3rtrun.p3d.core.model.PrinterAggregate;
import org.qw3rtrun.p3d.core.service.MachineDescriptionManager;
import org.qw3rtrun.p3d.core.service.PrinterAggregateManager;
import org.qw3rtrun.p3d.printersmng.service.MachineDescriptionManagerImpl;
import org.qw3rtrun.p3d.printersmng.service.PrinterRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.UUID;

@SpringBootApplication
@EnableJpaRepositories
public class PrintersmngApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrintersmngApplication.class, args);
    }

    @Bean
    public static MachineDescriptionManager dbPrinterManager(PrinterRepository repository) {
        return new MachineDescriptionManagerImpl(repository);
    }

}
