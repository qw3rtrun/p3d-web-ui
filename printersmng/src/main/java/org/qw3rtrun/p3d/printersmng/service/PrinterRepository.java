package org.qw3rtrun.p3d.printersmng.service;

import org.qw3rtrun.p3d.printersmng.model.Printer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrinterRepository extends JpaRepository<Printer, Integer> {
}
