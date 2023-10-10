package org.qw3rtrun.p3d.printersmng.service;

import org.qw3rtrun.p3d.printersmng.model.PrinterEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PrinterRepository extends JpaRepository<PrinterEntity, UUID> {
}
