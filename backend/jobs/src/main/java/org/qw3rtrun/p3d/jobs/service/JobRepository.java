package org.qw3rtrun.p3d.jobs.service;

import org.qw3rtrun.p3d.api.jobs.JobState;
import org.qw3rtrun.p3d.jobs.model.JobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<JobEntity, UUID> {

    List<JobEntity> findAllByOrderByCreatedAtDesc();

    List<JobEntity> findByMachineIdOrderByCreatedAtDesc(UUID machineId);

    /** What a machine is holding - the query a deleted printer's cleanup runs. */
    List<JobEntity> findByMachineIdAndStateIn(UUID machineId, Collection<JobState> states);

    /** The queue, oldest first, which is the order plan item D1 will assign in. */
    List<JobEntity> findByStateOrderByCreatedAtAsc(JobState state);
}
