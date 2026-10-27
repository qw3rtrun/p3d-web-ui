package org.qw3rtrun.p3d.files.service;

import org.qw3rtrun.p3d.files.model.GcodeFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GcodeFileRepository extends JpaRepository<GcodeFileEntity, UUID> {

    /** The lookup that makes a re-upload of the same bytes resolve to the file already held. */
    Optional<GcodeFileEntity> findByContentHash(String contentHash);

    /** Whether any row still refers to these bytes, which is what a delete asks before removing. */
    boolean existsByContentHash(String contentHash);
}
