package org.qw3rtrun.p3d.files;

import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.files.service.GcodeBlobStore;
import org.qw3rtrun.p3d.files.service.GcodeFileRepository;
import org.qw3rtrun.p3d.files.service.GcodeFileServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.nio.file.Path;

@Configuration
@EnableJpaRepositories
public class GcodeFilesModule {

    /**
     * Where the bytes are kept.
     *
     * <p>Configurable because it is the one thing about this module an operator has to decide: a
     * farm's G-code outgrows the directory the application happens to start in, and the answer is
     * a mount rather than a code change. The default sits beside the database for the same reason
     * that one defaults to {@code ./data} - a development machine should need no configuration at
     * all.
     */
    @Bean
    public GcodeBlobStore gcodeBlobStore(@Value("${p3d.files.root:./data/gcode}") Path root) {
        return new GcodeBlobStore(root);
    }

    @Bean
    public GcodeFileService gcodeFileService(GcodeFileRepository repo, GcodeBlobStore blobs) {
        return new GcodeFileServiceImpl(repo, blobs);
    }
}
