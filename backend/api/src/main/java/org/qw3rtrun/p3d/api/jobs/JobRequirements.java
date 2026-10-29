package org.qw3rtrun.p3d.api.jobs;

import org.qw3rtrun.p3d.api.model.BedVolume;
import org.qw3rtrun.p3d.api.model.MachineSpec;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a job needs of a machine - the question plan item D1 matches against B4's spec.
 *
 * <p><strong>Every field is optional and null means "do not care".</strong> A job queued with
 * nothing stated runs on the first idle printer, which is what a farm of identical machines wants
 * and is the common case. Each field that <em>is</em> stated narrows the set.
 *
 * <p><strong>{@code machineId} is the manual override.</strong> The plan calls for first-fit over
 * capability and availability "with manual override", and this is it: a job that names a machine
 * waits for that machine and no other, however free the rest of the farm is. An operator who knows
 * something the farm does not - this printer has the right filament loaded right now - needs to be
 * able to say so without the scheduler second-guessing it.
 *
 * @param machineId      the only machine this may run on, or null for any eligible one
 * @param bedVolume      the smallest bed it fits on, or null
 * @param extruders      how many tools it needs, or null
 * @param nozzleDiameter the nozzle it was sliced for, or null
 * @param material       what it must be printed in, or null
 * @param tag            a label the machine must carry - how a farm expresses a group
 */
public record JobRequirements(UUID machineId,
                              BedVolume bedVolume,
                              Integer extruders,
                              BigDecimal nozzleDiameter,
                              String material,
                              String tag) {

    /** A job that will run anywhere. Not null, so a caller never has to check. */
    public static JobRequirements any() {
        return new JobRequirements(null, null, null, null, null, null);
    }

    /** Whether anything at all was asked for. */
    public boolean isAny() {
        return machineId == null && bedVolume == null && extruders == null
                && nozzleDiameter == null && material == null && tag == null;
    }

    /** A job pinned to one machine, which no capability check can widen. */
    public boolean isPinned() {
        return machineId != null;
    }

    /**
     * Whether a machine described this way can run this job.
     *
     * <p><strong>A requirement the machine has not been described against is not satisfied.</strong>
     * "Nobody said" is not "yes": a job that needs PETG must not land on a printer whose materials
     * nobody has filled in, because the answer there is unknown and the cost of being wrong is a
     * failed print on the wrong filament. The effect is that stating requirements forces B4's data
     * to be filled in, which is the right pressure - and a job that states nothing is unaffected.
     *
     * <p>{@code machineId} is deliberately not checked here. Which machine a pinned job may use is
     * a different question from whether a machine is capable, and conflating them would let a
     * capability match quietly override an operator's explicit choice.
     */
    public boolean satisfiedBy(MachineSpec spec) {
        if (spec == null) {
            return isAny();
        }
        if (bedVolume != null) {
            var bed = spec.bedVolume();
            if (bed == null || !bed.fits(bedVolume.x(), bedVolume.y(), bedVolume.z())) {
                return false;
            }
        }
        if (extruders != null && (spec.extruderCount() == null || spec.extruderCount() < extruders)) {
            return false;
        }
        if (nozzleDiameter != null && spec.nozzleDiameters().stream()
                .noneMatch(d -> BigDecimal.valueOf(d).compareTo(nozzleDiameter) == 0)) {
            return false;
        }
        if (material != null && spec.materials().stream().noneMatch(material::equalsIgnoreCase)) {
            return false;
        }
        return tag == null || spec.taggedWith(tag);
    }

    /** Why this job does not fit a machine described that way, for a human to read. */
    public String whyNot(MachineSpec spec) {
        if (satisfiedBy(spec)) {
            return null;
        }
        if (spec == null || !spec.isDeclared()) {
            return "nothing has been declared about it";
        }
        if (bedVolume != null && (spec.bedVolume() == null
                || !spec.bedVolume().fits(bedVolume.x(), bedVolume.y(), bedVolume.z()))) {
            return "its bed is " + (spec.bedVolume() == null ? "not declared" : spec.bedVolume())
                    + " and the job needs " + bedVolume;
        }
        if (extruders != null && (spec.extruderCount() == null || spec.extruderCount() < extruders)) {
            return "it has " + spec.extruderCount() + " extruders and the job needs " + extruders;
        }
        if (nozzleDiameter != null) {
            return "its nozzles are " + spec.nozzleDiameters() + " and the job needs " + nozzleDiameter;
        }
        if (material != null) {
            return "it is set up for " + spec.materials() + " and the job needs " + material;
        }
        return "it is not tagged " + tag;
    }
}
