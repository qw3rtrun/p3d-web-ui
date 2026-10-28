package org.qw3rtrun.p3d.api.jobs;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.BedVolume;
import org.qw3rtrun.p3d.api.model.MachineSpec;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a job needs against what a machine is - plan item D1's matching rule.
 *
 * <p>The rule is small and the edges are where it matters: an unstated requirement must not
 * narrow anything, and an undeclared machine must not be assumed capable.
 */
class JobRequirementsTest {

    private static final MachineSpec BIG = new MachineSpec(
            new BedVolume(300, 300, 400), 2, List.of(0.4, 0.6), Set.of("PLA", "PETG"),
            Set.of("prusa-shelf"));

    private static JobRequirements needs(BedVolume bed, Integer extruders, String nozzle,
                                         String material, String tag) {
        return new JobRequirements(null, bed, extruders,
                nozzle == null ? null : new BigDecimal(nozzle), material, tag);
    }

    /** A job that asks for nothing runs anywhere, which is the common case and must stay cheap. */
    @Test
    void aJobThatAsksForNothingFitsAnything() {
        assertTrue(JobRequirements.any().isAny());
        assertTrue(JobRequirements.any().satisfiedBy(BIG));
        assertTrue(JobRequirements.any().satisfiedBy(MachineSpec.undeclared()));
    }

    @Test
    void eachStatedRequirementNarrowsIt() {
        assertTrue(needs(new BedVolume(200, 200, 200), null, null, null, null).satisfiedBy(BIG));
        assertTrue(needs(null, 2, null, null, null).satisfiedBy(BIG));
        assertTrue(needs(null, null, "0.6", null, null).satisfiedBy(BIG));
        assertTrue(needs(null, null, null, "PETG", null).satisfiedBy(BIG));
        assertTrue(needs(null, null, null, null, "prusa-shelf").satisfiedBy(BIG));
    }

    @Test
    void aRequirementTheMachineCannotMeetIsRefused() {
        assertFalse(needs(new BedVolume(400, 200, 200), null, null, null, null).satisfiedBy(BIG));
        assertFalse(needs(null, 3, null, null, null).satisfiedBy(BIG));
        assertFalse(needs(null, null, "0.8", null, null).satisfiedBy(BIG));
        assertFalse(needs(null, null, null, "ABS", null).satisfiedBy(BIG));
        assertFalse(needs(null, null, null, null, "other-shelf").satisfiedBy(BIG));
    }

    /**
     * An undeclared machine satisfies nothing that was asked for.
     *
     * <p>"Nobody said" is not "yes". A job that needs PETG must not land on a printer whose
     * materials nobody has filled in - the answer is unknown, and the cost of being wrong is a
     * failed print on the wrong filament. The effect is that stating requirements forces B4's data
     * to be filled in, which is the right pressure.
     */
    @Test
    void anUndeclaredMachineSatisfiesNothingThatWasAskedFor() {
        var blank = MachineSpec.undeclared();

        assertFalse(needs(new BedVolume(100, 100, 100), null, null, null, null).satisfiedBy(blank));
        assertFalse(needs(null, 1, null, null, null).satisfiedBy(blank));
        assertFalse(needs(null, null, "0.4", null, null).satisfiedBy(blank));
        assertFalse(needs(null, null, null, "PLA", null).satisfiedBy(blank));
        assertFalse(needs(null, null, null, null, "anything").satisfiedBy(blank));
    }

    /** A bed exactly the size asked for is big enough. */
    @Test
    void aBedTheExactSizeFits() {
        assertTrue(needs(new BedVolume(300, 300, 400), null, null, null, null).satisfiedBy(BIG));
    }

    /** More tools than asked for is fine; fewer is not. */
    @Test
    void moreExtrudersThanNeededIsStillEligible() {
        assertTrue(needs(null, 1, null, null, null).satisfiedBy(BIG));
    }

    @Test
    void materialsAreMatchedWithoutCaringAboutCase() {
        assertTrue(needs(null, null, null, "petg", null).satisfiedBy(BIG));
    }

    /**
     * Pinning is not a capability, and the capability check does not look at it.
     *
     * <p>Conflating them would let a capability match quietly override an operator who named a
     * machine on purpose - which is the whole point of the override.
     */
    @Test
    void pinningIsSeparateFromCapability() {
        var pinned = new JobRequirements(UUID.randomUUID(), null, null, null, null, null);

        assertTrue(pinned.isPinned());
        assertFalse(pinned.isAny());
        assertTrue(pinned.satisfiedBy(BIG), "pinning says which machine, not whether it is capable");
    }

    /** A refusal comes with a sentence an operator can act on. */
    @Test
    void aRefusalExplainsItself() {
        assertNull(JobRequirements.any().whyNot(BIG), "nothing to explain when it fits");

        assertTrue(needs(null, null, null, "ABS", null).whyNot(BIG).contains("ABS"));
        assertTrue(needs(null, 3, null, null, null).whyNot(BIG).contains("3"));
        assertNotNull(needs(null, null, null, "PLA", null).whyNot(MachineSpec.undeclared()));
        assertTrue(needs(null, null, null, "PLA", null).whyNot(MachineSpec.undeclared())
                .contains("nothing has been declared"));
    }
}
