package org.qw3rtrun.p3d.printersmng;

import org.qw3rtrun.p3d.core.service.PrinterManager;
import org.qw3rtrun.p3d.printersmng.service.PrinterManagerImpl;
import org.qw3rtrun.p3d.printersmng.service.PrinterRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories
public class PrintersmngApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrintersmngApplication.class, args);
    }

    @Bean
    public static PrinterManager dbPrinterManager(PrinterRepository repository) {
        return new PrinterManagerImpl(repository);
    }
}
