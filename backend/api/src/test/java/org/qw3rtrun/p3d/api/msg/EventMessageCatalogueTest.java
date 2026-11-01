package org.qw3rtrun.p3d.api.msg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every type in this package is classified by whether it travels as an {@link EventMessage#event()},
 * and a new one that nobody classified fails the build.
 *
 * <p><strong>This exists because the hand-maintained list is a known failure mode, four times
 * over.</strong> {@link EventMessage}'s javadoc enumerates the farm's own facts and
 * {@code package-info} says the same set in prose; both went stale on {@link SdUploadProgressed}
 * (#69), {@code MockPrinter}'s census claim went stale (#61), and the TypeScript {@code GcodeFile}
 * type is still missing a field the server sends (#50). Each was found by a person reading a diff.
 *
 * <p><strong>What this guards is the moment of addition, not the prose.</strong> The question those
 * lists exist to answer is *does this new message need a {@code case} in the browser* - a
 * {@code type} string the TypeScript side switches on, pinned by {@code EventStreamContractTest} in
 * {@code :frontend:web-ui}, which this module cannot see. So the assertion that earns its place is
 * the two-way one below: a type added to this package is in neither table, and the build stops until
 * somebody says which it is. The table is still maintained by hand, but it is a hand-maintained list
 * whose staleness is loud, which is the whole difference from the two javadoc lists.
 *
 * <p><strong>What it deliberately does not check:</strong> the prose in {@code package-info}, which
 * names these things in words rather than by type and cannot be matched mechanically without
 * rewriting it into a list; and whether a type that does <em>not</em> travel has been wrongly linked
 * into {@link EventMessage}'s sentence. Both omissions are failures of documentation with nothing
 * downstream of them. The failure this one catches costs a browser a {@code case}.
 *
 * <p>It reads the compiled package rather than the source directory on purpose:
 * {@code ManagementEvents.kt} is one file holding four types, so a scan of {@code *.java} and
 * {@code *.kt} would let three of them through.
 */
class EventMessageCatalogueTest {

    private static final String PACKAGE_PATH = "org/qw3rtrun/p3d/api/msg";

    /**
     * The farm's own facts, which travel to a browser wrapped in an {@link EventMessage}.
     *
     * <p>Adding one here means adding it to {@link EventMessage}'s javadoc, to the prose in
     * {@code package-info}, and to the {@code switch} in the TypeScript client that will otherwise
     * ignore it.
     */
    private static final Set<String> TRAVELS_AS_AN_EVENT_MESSAGE = Set.of(
            "BlockAcknowledged",
            "BlocksAbandoned",
            "ConnectionStateChanged",
            "MachineJoinedFarm",
            "MachineLeftFarm",
            "SdUploadProgressed");

    /** Everything else in the package, and the reason it is not one of the above. */
    private static final Map<String, String> DOES_NOT = new LinkedHashMap<>(Map.of(
            "EventMessage", "the envelope itself",
            "ConnectCmd", "a command the farm gives a machine, not a fact about one",
            "SdUploadState", "SdUploadProgressed's companion enum, not a message of its own",
            "ManagementEvent", "the roster's own event interface",
            "MRegisteredEvent", "roster news on the Spring bus; never wrapped for a browser",
            "MDeletedEvent", "roster news on the Spring bus; never wrapped for a browser",
            "MConnectionChangedEvent", "roster news on the Spring bus; never wrapped for a browser",
            "MFirmwareReportedEvent", "roster news on the Spring bus; never wrapped for a browser",
            "MCapabilityReportedEvent", "roster news on the Spring bus; never wrapped for a browser"));

    @Test
    @DisplayName("every type in this package is classified, and the classification names nothing gone")
    void everyTypeInThisPackageIsClassified() {
        var classified = new TreeSet<>(TRAVELS_AS_AN_EVENT_MESSAGE);
        classified.addAll(DOES_NOT.keySet());

        assertEquals(classified, declaredTypes(), """
                The types in org.qw3rtrun.p3d.api.msg and the tables in this test disagree.

                A type on the right and not on the left is new and nobody has decided what it is. \
                If it travels as an EventMessage.event, add it to TRAVELS_AS_AN_EVENT_MESSAGE, to \
                EventMessage's javadoc, to the prose in package-info, and to the switch in the \
                TypeScript client - a browser ignores a type string it does not know, silently. If \
                it does not travel, add it to DOES_NOT with the reason.

                A type on the left and not on the right was renamed or deleted; fix the table.""");
    }

    @Test
    @DisplayName("everything that travels is named in EventMessage's javadoc")
    void everythingThatTravelsIsNamedInEventMessagesJavadoc() {
        var javadoc = read(moduleRoot()
                .resolve("src/main/java")
                .resolve(PACKAGE_PATH)
                .resolve("EventMessage.java"));

        var missing = new ArrayList<String>();
        for (var type : new TreeSet<>(TRAVELS_AS_AN_EVENT_MESSAGE)) {
            if (!javadoc.contains("{@link " + type + "}")) {
                missing.add(type);
            }
        }

        assertTrue(missing.isEmpty(),
                "EventMessage's javadoc enumerates the farm's own facts and is what somebody reads "
                        + "to decide whether a new message needs a type the browser knows about. "
                        + "Not named there: " + missing);
    }

    /**
     * Every class compiled into this package, whatever language it was written in.
     *
     * <p>A nested type arrives here under its binary name ({@code Outer$Inner}) rather than being
     * folded into its enclosing one, so it has to be classified too and cannot hide.
     */
    private static Set<String> declaredTypes() {
        var found = new TreeSet<String>();
        var classes = buildClassesDir();
        for (var language : list(classes)) {
            var pkg = language.resolve("main").resolve(PACKAGE_PATH);
            if (!Files.isDirectory(pkg)) {
                continue;
            }
            for (var file : list(pkg)) {
                var name = file.getFileName().toString();
                if (!name.endsWith(".class")) {
                    continue;
                }
                name = name.substring(0, name.length() - ".class".length());
                if (!name.equals("package-info")) {
                    found.add(name);
                }
            }
        }
        assertTrue(!found.isEmpty(),
                "nothing compiled found under " + classes + "/*/main/" + PACKAGE_PATH
                        + ", which means this test is looking in the wrong place rather than that "
                        + "the package is empty - fix the lookup before trusting a green run");
        return found;
    }

    private static List<Path> list(Path directory) {
        try (var entries = Files.list(directory)) {
            return entries.toList();
        } catch (IOException e) {
            throw new IllegalStateException("could not list " + directory, e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + file, e);
        }
    }

    /** {@code <module>/build/classes}, derived from where this test class itself was compiled to. */
    private static Path buildClassesDir() {
        try {
            var here = Path.of(EventMessageCatalogueTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            // .../build/classes/java/test -> .../build/classes
            return here.getParent().getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("could not locate this module's build output", e);
        }
    }

    /** The Gradle module, found from the build output rather than from the working directory. */
    private static Path moduleRoot() {
        var module = buildClassesDir().getParent().getParent();
        assertTrue(Files.isDirectory(module.resolve("src/main/java")),
                module + " does not look like this module: no src/main/java under it");
        return module;
    }
}
