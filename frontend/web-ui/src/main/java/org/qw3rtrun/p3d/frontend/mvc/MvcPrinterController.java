package org.qw3rtrun.p3d.frontend.mvc;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.BedVolume;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Renders the printer pages: list, add, edit, monitor, dashboard.
 *
 * <p>Every blocking JPA call here runs off the event loop via {@code subscribeOn} - a handler
 * returning {@code Mono<String>} is still running on the loop otherwise; what moves work is where
 * it is scheduled, not the method signature. {@link #printers()} drains the JPA stream to a list
 * inside the supplier, because a lazy stream handed to a template would be iterated during
 * rendering, outside the transaction.
 *
 * <p>{@link #edit} is where an unknown machine becomes a 404 on this HTML surface - the roster
 * itself only answers an empty {@code Optional}; the REST side maps that same empty value through
 * {@code Outcomes} instead of throwing. The page also puts a declared and a discovered half side by
 * side (plan item B4): the form writes the declared half, the discovered half is read-only, so a
 * reader can see a machine reporting one extruder against two declared.
 */
@Controller()
@RequiredArgsConstructor
public class MvcPrinterController {

    private final MachineManagementService manager;

    private Mono<List<Descriptor>> printers() {
        return Mono.fromSupplier(() -> manager.fetchAll().toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping({
            "/",
            "/index.html",
    })
    public Mono<String> defaultPage() {
        return Mono.just("redirect:/dashboard.html");
    }

    @GetMapping("/printers/add.html")
    public Mono<String> add(Model model) {
        model.addAttribute("machine", new MachineInfo());
        model.addAttribute("discovered", MachineProfile.unknown());
        return printers()
                .doOnNext(printers -> model.addAttribute("printers", printers))
                .thenReturn("add.html");
    }

    @PostMapping("/printers/add.html")
    public Mono<String> postAdd(@ModelAttribute("machine") MachineInfo newMachine) {
        return Mono.fromSupplier(() -> manager.add(newMachine.name,
                        new ConnectionDetails(newMachine.host, newMachine.port), newMachine.toSpec()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(added -> "redirect:/printers/" + added.uuid() + "/edit.html");
    }

    @GetMapping("/printers/{uuid}/edit.html")
    public Mono<String> edit(@PathVariable UUID uuid, Model model) {
        return Mono.fromSupplier(() -> manager.fetch(uuid)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "the farm has no machine " + uuid)))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(machine -> {
                    model.addAttribute("machine", MachineInfo.of(machine));
                    model.addAttribute("discovered", machine.profile());
                    model.addAttribute("uuid", uuid);
                })
                .then(printers().doOnNext(printers -> model.addAttribute("printers", printers)))
                .thenReturn("edit.html");
    }

    @PostMapping("/printers/{uuid}/edit.html")
    public Mono<String> postEdit(@PathVariable UUID uuid, @ModelAttribute("machine") MachineInfo edited) {
        return Mono.fromSupplier(() -> manager.update(uuid, edited.name,
                        new ConnectionDetails(edited.host, edited.port), edited.toSpec()))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn("redirect:/printers/" + uuid + "/edit.html");
    }

    @GetMapping("/printers/{uuid}")
    public Mono<String> monitor(@PathVariable UUID uuid, Model model) {
        return printers()
                .doOnNext(printers -> {
                    model.addAttribute("printers", printers);
                    model.addAttribute("printer", printers.stream()
                            .filter(p -> p.uuid().equals(uuid)).findFirst().orElseThrow());
                })
                .thenReturn("monitor.html");
    }

    @GetMapping("/dashboard.html")
    public Mono<String> dashboard(Model model) {
        return printers()
                .doOnNext(printers -> model.addAttribute("printers", printers))
                .thenReturn("dashboard.html");
    }

    // The add/edit form, flat because HTML forms are - not MachineSpec itself, so the form's own
    // conventions (comma-joined lists, blank meaning absent) do not leak into that vocabulary.
    // Bed/extruder fields are boxed because blank must not bind to zero: a bed of zero is not a bed,
    // and an extruder count of zero refuses every command instead of deferring to the firmware.
    @Data
    public static final class MachineInfo {
        String name;
        String host;
        int port;

        Integer bedX;
        Integer bedY;
        Integer bedZ;
        Integer extruderCount;

        /** Comma-separated, in tool order - {@code 0.4, 0.6}. */
        String nozzleDiameters = "";
        /** Comma-separated - {@code PLA, PETG}. */
        String materials = "";
        /** Comma-separated. A group is a tag here; see {@link MachineSpec}. */
        String tags = "";

        static MachineInfo of(Descriptor machine) {
            var info = new MachineInfo();
            info.name = machine.name();
            info.host = machine.connection().host();
            info.port = machine.connection().port();
            var spec = machine.spec();
            if (spec.bedVolume() != null) {
                info.bedX = spec.bedVolume().x();
                info.bedY = spec.bedVolume().y();
                info.bedZ = spec.bedVolume().z();
            }
            info.extruderCount = spec.extruderCount();
            info.nozzleDiameters = spec.nozzleDiameters().stream()
                    .map(String::valueOf).collect(Collectors.joining(", "));
            info.materials = String.join(", ", spec.materials());
            info.tags = String.join(", ", spec.tags());
            return info;
        }

        MachineSpec toSpec() {
            return new MachineSpec(bedVolume(), extruderCount,
                    split(nozzleDiameters).map(Double::valueOf).toList(),
                    split(materials).collect(Collectors.toSet()),
                    split(tags).collect(Collectors.toSet()));
        }

        /** A bed is all three dimensions or none - a half-filled form declared nothing. */
        private BedVolume bedVolume() {
            if (bedX == null || bedY == null || bedZ == null) {
                return null;
            }
            return new BedVolume(bedX, bedY, bedZ);
        }

        /** Blank entries are dropped rather than becoming an empty tag, which is what `a,,b` means. */
        private static Stream<String> split(String csv) {
            if (csv == null || csv.isBlank()) {
                return Stream.empty();
            }
            return Stream.of(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty());
        }
    }
}
