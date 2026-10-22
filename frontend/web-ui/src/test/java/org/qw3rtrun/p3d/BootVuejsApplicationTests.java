package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.UUID;
import java.util.stream.Stream;

@SpringBootTest
class BootVuejsApplicationTests {

    /**
     * The collaborator this module does not contain.
     *
     * <p>The frontend submodel holds controllers; the thing that answers them lives in
     * {@code printer-management}, which it does not depend on and must not. So booting this module
     * alone needs a {@link MachineManagementService} from somewhere, and <strong>test scope is
     * where that belongs</strong>.
     *
     * <p>It used to come from main: a {@code @ConditionalOnMissingBean} stub that answered every
     * query with {@code null}. That made this test pass and made a misconfigured farm look like an
     * empty one instead of failing - the same bean, shipped. A4 deleted it, and this is what it was
     * actually for.
     *
     * <p>It throws rather than returning empties on purpose. Nothing in this test should call it,
     * and if something starts to, the failure should say so rather than look like a farm with no
     * printers in it.
     */
    @TestConfiguration
    static class Collaborators {

        @Bean
        MachineManagementService machines() {
            return new MachineManagementService() {
                @Override
                public Descriptor fetch(UUID uuid) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public Descriptor add(String name, ConnectionDetails connection) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public Stream<Descriptor> fetchAll() {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public void delete(UUID uuid) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public void disconnect(UUID uuid) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public void connect(UUID uuid) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }
            };
        }
    }

    @Test
    void contextLoads() {
    }
}
