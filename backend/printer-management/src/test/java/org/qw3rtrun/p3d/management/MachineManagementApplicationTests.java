package org.qw3rtrun.p3d.management;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

//@Disabled
@SpringBootTest
class MachineManagementApplicationTests {

    @Autowired
    private MachineManagementService service;

    @Test
    void contextLoads() {
        var added = service.add("test", new ConnectionDetails("host", 123));
        service.connect(added.uuid());
    }

}
