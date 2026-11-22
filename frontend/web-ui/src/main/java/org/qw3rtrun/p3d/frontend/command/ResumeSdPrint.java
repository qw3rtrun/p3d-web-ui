package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.StartOrResumeSDPrint;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Resume the print paused on the card - {@code M24}, the same command {@link PrintFile} ends with.
 *
 * <p>One command does both because the firmware's {@code M24} does both: {@link PrintFile}'s javadoc
 * records that an {@code M24} after an {@code M23} select is taken as a <em>resume</em> - it answers
 * {@code //action:resume} and a {@code //action:prompt_begin Resuming SD} sequence - which is
 * cosmetically wrong there and is exactly the semantics wanted here. No {@code S} or {@code T} word
 * is sent: resuming from a byte offset or a stated elapsed time is a different feature from the
 * button this serves, and neither has been measured against this farm.
 */
public record ResumeSdPrint() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new StartOrResumeSDPrint());
    }
}
