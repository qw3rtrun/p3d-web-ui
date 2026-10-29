package org.qw3rtrun.p3d.frontend.mvc;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.BedVolume;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Controller()
@RequiredArgsConstructor
public class MvcPrinterController {

    private final MachineManagementService manager;

    /**
     * The printer list, read off the event loop.
     *
     * <p>Every page here needs it, and every page used to call {@code manager.fetchAll()} straight
     * from its handler body - blocking JDBC on a Netty event-loop thread, with not even a
     * {@code Mono} wrapper to disguise it. A handler returning {@code Mono<String>} is still a
     * handler running on the loop; what makes work non-blocking is where it is scheduled, not what
     * the method signature says.
     *
     * <p>The stream is drained to a list inside the supplier on purpose: a lazy JPA stream handed
     * to a template would be iterated during rendering, which is back on the loop and outside the
     * transaction.
     */
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

    /**
     * The edit page: what a person declared, beside what the machine said about itself.
     *
     * <p><strong>Both halves on one page is the point of plan item B4's "distinguishable".</strong>
     * The form writes the declared half only; the discovered half is rendered read-only beside it,
     * with when it was learned. A reader can then see that a machine reports one extruder while
     * somebody has declared two, which is a thing worth knowing and which a single merged set of
     * fields could not show.
     */
    @GetMapping("/printers/{uuid}/edit.html")
    public Mono<String> edit(@PathVariable UUID uuid, Model model) {
        return Mono.fromSupplier(() -> manager.fetch(uuid))
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

    /**
     * The add/edit form, which is flat because an HTML form is.
     *
     * <p>It is not {@link MachineSpec}, and the gap between the two is the work this class does.
     * A browser posts {@code nozzleDiameters=0.4,0.6} and an empty {@code bedX} as the empty
     * string; the model records "0.4 and 0.6, in tool order" and "nobody declared a bed". Binding
     * the record directly would mean either boxing every field of it for the form's benefit or
     * letting the form's conventions - comma-joined lists, blank meaning absent - leak into the
     * vocabulary every submodel is written in.
     *
     * <p>Boxed integers for the bed and the extruder count because blank must not bind to zero: a
     * bed of zero is not a bed, and an extruder count of zero refuses every command rather than
     * deferring to what the firmware said.
     */
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
