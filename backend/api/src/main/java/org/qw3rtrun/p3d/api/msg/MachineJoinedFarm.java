package org.qw3rtrun.p3d.api.msg;

/**
 * A machine is part of the farm - announced on the farm-wide stream so a page can add a row.
 *
 * <p>Plan item D2's "a printer added while the page is open appears without a reload". It carries
 * the name because that is all a dashboard needs to draw something; anything more detailed is one
 * {@code GET /api-manager/{id}} away, and putting a whole {@code Descriptor} on a stream that also
 * carries a temperature every second would be a lot of bytes to repeat.
 *
 * @param name what a human calls it
 */
public record MachineJoinedFarm(String name) implements GEvent {
}
