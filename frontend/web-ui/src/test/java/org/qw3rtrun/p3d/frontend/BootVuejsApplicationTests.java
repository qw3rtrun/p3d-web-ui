package org.qw3rtrun.p3d.frontend;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.control.FarmView;
import org.qw3rtrun.p3d.api.control.PrinterControl;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.identity.FarmUserDirectory;
import org.qw3rtrun.p3d.api.jobs.Job;
import org.qw3rtrun.p3d.api.jobs.JobOutcome;
import org.qw3rtrun.p3d.api.jobs.JobRequirements;
import org.qw3rtrun.p3d.api.jobs.JobService;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.protocol.GRq;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.reactivestreams.Publisher;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.ReactiveMapSessionRepository;
import org.springframework.session.ReactiveSessionRepository;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
                public Publisher<HeaterReading> state(UUID id) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public Publisher<EventMessage> updates(UUID id) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public Publisher<Void> send(UUID id, List<GRq<?>> commands) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public Publisher<Void> connect(UUID id, ConnectCmd command) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public Publisher<ConnectionStatus> connection(UUID id) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }
            };
        }

        /**
         * Where sessions are kept, which the real assembly gets from {@code identity} over JDBC.
         *
         * <p>An in-memory one here, because this submodel is being booted alone and has no
         * database - but a <em>real</em> {@code ReactiveSessionRepository}, so the
         * {@code WebSessionManager} wiring in {@link org.qw3rtrun.p3d.frontend.security
         * .SessionStoreConfiguration} is exercised rather than skipped. That wiring is the piece
         * Spring Boot does not do for a JDBC store, and silently.
         */
        @Bean
        ReactiveSessionRepository<?> sessions() {
            return new ReactiveMapSessionRepository(
                    new ConcurrentHashMap<>());
        }

        /**
         * The one password encoder, which the real assembly gets from {@code identity}.
         *
         * <p>It must be the same scheme the roster hashes with, or every sign-in fails - which is
         * why the real application declares exactly one and this test double matches it.
         */
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }

        /**
         * The people roster, which lives in {@code identity} - plan item D3.
         *
         * <p>A working in-memory store rather than a throwing stub; see {@link InMemoryFarmUsers}
         * for why this one is the exception.
         */
        @Bean
        FarmUserDirectory farmUsers() {
            return new InMemoryFarmUsers();
        }

        /** The farm-wide stream, which lives in core - plan item D2. */
        @Bean
        FarmView farmView() {
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
                                      MachineSpec spec) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public Descriptor update(UUID uuid, String name, ConnectionDetails connection,
                                         MachineSpec spec) {
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
        GcodeFileService gcodeFiles() {
            return new GcodeFileService() {
                @Override
                public Publisher<GcodeFile> store(
                        String name, Publisher<ByteBuffer> content) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public Optional<GcodeFile> fetch(UUID id) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public Stream<GcodeFile> fetchAll() {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public Publisher<ByteBuffer> read(UUID id) {
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
        JobService jobs() {
            return new JobService() {
                @Override
                public JobOutcome queue(UUID fileId,
                        JobRequirements requires) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public JobOutcome blocked(UUID jobId, String why) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public List<Job> queued() {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public JobOutcome assign(UUID jobId, UUID machineId) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public JobOutcome unassign(UUID jobId, String reason) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public JobOutcome start(UUID jobId) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public JobOutcome pause(UUID jobId) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public JobOutcome resume(UUID jobId) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public JobOutcome complete(UUID jobId) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public JobOutcome fail(UUID jobId, String reason) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public JobOutcome cancel(UUID jobId, String reason) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public Optional<Job> fetch(UUID jobId) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public List<Job> fetchAll() {
                    throw new UnsupportedOperationException("not expected during a context load");
                }

                @Override
                public List<Job> forMachine(UUID machineId) {
                    throw new UnsupportedOperationException("not expected during a context load");
                }
            };
        }
    }

    @Test
    void contextLoads() {
    }
}
