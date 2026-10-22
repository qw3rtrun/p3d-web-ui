package org.qw3rtrun.p3d.api.msg;

/**
 * The machine reported a fault, in its own words.
 *
 * <p><strong>A value, not a failure.</strong> On a Marlin an error is routine traffic the printer
 * recovers from by itself - a bad checksum is answered with {@code Error:}, {@code Resend:} and then
 * {@code ok}, and the session carries on. So this arrives on the event stream like any other event,
 * and a terminal signal there still means only that the connection is gone.
 *
 * <p>The text is kept verbatim because the host, not the protocol, decides which errors are fatal:
 * the spec sorts them into recoverable, non-fatal and shut-the-machine-down on prose it only gives
 * as examples.
 */
public interface ErrorReportEvent extends GEvent {

    String message();

    /** The last line the firmware accepted, when it said - {@code null} when it did not. */
    Integer lastLine();
}
