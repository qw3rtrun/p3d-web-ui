package org.qw3rtrun.p3d.frontend;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.control.PrinterControl;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.PrinterCommand;
import org.qw3rtrun.p3d.api.msg.TemperatureReport;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.reactivestreams.Publisher;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.UUID;
import java.util.stream.Stream;

@SpringBootTest
class BootVuejsApplicationTests {

    /**
     * The collaborators this module does not contain.
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
     * <p>They throw rather than returning empties on purpose. Nothing in this test should call
     * either, and if something starts to, the failure should say so rather than look like a farm
     * with no printers in it.
     *
     * <p>There are two now, which is the module boundary showing through: this module reaches the
     * roster through {@link MachineManagementService} and a machine through {@link PrinterControl},
     * and it implements neither.
     */
    @TestConfiguration
    static class Collaborators {

        @Bean
        PrinterControl printers() {
            return new PrinterControl() {
                @Override
                public Publisher<TemperatureReport> state(UUID id) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public Publisher<EventMessage> updates(UUID id) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public Publisher<Void> send(UUID id, PrinterCommand command) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public Publisher<Void> connect(UUID id, ConnectCmd command) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public Publisher<org.qw3rtrun.p3d.api.model.ConnectionStatus> connection(UUID id) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }
            };
        }

        /** The farm-wide stream, which lives in core - plan item D2. */
        @Bean
        org.qw3rtrun.p3d.api.control.FarmView farmView() {
            return () -> {
                throw new UnsupportedOperationException("not expected during a context load");
            };
        }

        @Bean
        MachineManagementService machines() {
            return new MachineManagementService() {
                @Override
                public Descriptor fetch(UUID uuid) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public Descriptor add(String name, ConnectionDetails connection,
                                      org.qw3rtrun.p3d.api.model.MachineSpec spec) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public Descriptor update(UUID uuid, String name, ConnectionDetails connection,
                                         org.qw3rtrun.p3d.api.model.MachineSpec spec) {
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
            };
        }

        /**
         * The G-code store, which lives in a module this one must not depend on.
         *
         * <p>Same shape and same reason as the roster above: {@code GcodeFileController} needs the
         * bean to exist, and what answers it is in {@code :backend:files}, which this submodel may
         * not name. It throws rather than returning empties, because a stub that quietly answers
         * "no files" would let a misconfigured assembly pass this test and then show an empty
         * library to a user with a full one.
         */
        @Bean
        org.qw3rtrun.p3d.api.files.GcodeFileService gcodeFiles() {
            return new org.qw3rtrun.p3d.api.files.GcodeFileService() {
                @Override
                public org.reactivestreams.Publisher<org.qw3rtrun.p3d.api.files.GcodeFile> store(
                        String name, org.reactivestreams.Publisher<java.nio.ByteBuffer> content) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public java.util.Optional<org.qw3rtrun.p3d.api.files.GcodeFile> fetch(UUID id) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public Stream<org.qw3rtrun.p3d.api.files.GcodeFile> fetchAll() {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public org.reactivestreams.Publisher<java.nio.ByteBuffer> read(UUID id) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public void delete(UUID id) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }
            };
        }

        /** The print queue, which lives in a module this one must not depend on. */
        @Bean
        org.qw3rtrun.p3d.api.jobs.JobService jobs() {
            return new org.qw3rtrun.p3d.api.jobs.JobService() {
                @Override
                public org.qw3rtrun.p3d.api.jobs.JobOutcome queue(UUID fileId,
                        org.qw3rtrun.p3d.api.jobs.JobRequirements requires) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public org.qw3rtrun.p3d.api.jobs.JobOutcome blocked(UUID jobId, String why) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public java.util.List<org.qw3rtrun.p3d.api.jobs.Job> queued() {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public org.qw3rtrun.p3d.api.jobs.JobOutcome assign(UUID jobId, UUID machineId) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public org.qw3rtrun.p3d.api.jobs.JobOutcome unassign(UUID jobId, String reason) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public org.qw3rtrun.p3d.api.jobs.JobOutcome start(UUID jobId) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public org.qw3rtrun.p3d.api.jobs.JobOutcome pause(UUID jobId) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public org.qw3rtrun.p3d.api.jobs.JobOutcome resume(UUID jobId) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public org.qw3rtrun.p3d.api.jobs.JobOutcome complete(UUID jobId) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public org.qw3rtrun.p3d.api.jobs.JobOutcome fail(UUID jobId, String reason) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public org.qw3rtrun.p3d.api.jobs.JobOutcome cancel(UUID jobId, String reason) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public java.util.Optional<org.qw3rtrun.p3d.api.jobs.Job> fetch(UUID jobId) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public java.util.List<org.qw3rtrun.p3d.api.jobs.Job> fetchAll() {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public java.util.List<org.qw3rtrun.p3d.api.jobs.Job> forMachine(UUID machineId) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }
            };
        }
    }

    @Test
    void contextLoads() {
    }
}
