package org.qw3rtrun.p3d.identity.service;

import org.qw3rtrun.p3d.identity.model.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** Internal to {@code identity}; the exported surface is {@code FarmUserDirectory}. */
public interface UserRepository extends JpaRepository<UserEntity, String> {
}
