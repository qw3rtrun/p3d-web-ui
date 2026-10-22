package org.qw3rtrun.p3d.api.msg;

/**
 * A reply the protocol read but the application has no event for.
 *
 * <p>The catch-all at the bridge, and deliberately the dullest type here: {@code :gcode} decodes
 * far more than the host currently does anything with - positions, echo messages, SD card
 * listings - and dropping those silently would make the application's model look complete when it
 * is merely small.
 *
 * <p>Seeing one of these on the bus is the signal that a reply deserves a typed event. The text is
 * the reply as the protocol would re-encode it, so it is readable and nothing more; a consumer that
 * wants to act on it should ask for a real event instead of parsing this.
 */
public record UnknownReply(String text) implements UnknownEvent {
}
