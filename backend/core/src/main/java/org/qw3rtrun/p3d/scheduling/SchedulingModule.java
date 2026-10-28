package org.qw3rtrun.p3d.scheduling;

import org.qw3rtrun.p3d.api.control.PrinterControl;
import org.qw3rtrun.p3d.api.jobs.JobService;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SchedulingModule {

    @Bean
    public FarmScheduler farmScheduler(JobService jobs, MachineManagementService machines,
                                       PrinterControl printers) {
        return new FarmScheduler(jobs, machines, printers);
    }
}
