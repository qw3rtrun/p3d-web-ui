package org.qw3rtrun.p3d.core;

import java.time.Duration;

/**
 * How hard to try, and how long to wait between tries.
 *
 * <p>Bounded on purpose. A farm reconnecting forever to a printer somebody unplugged is a socket
 * and a log line every second for as long as the process runs, and it hides the thing a user
 * actually wants to know: that the machine is not coming back. When the attempts run out the
 * lifecycle goes to {@code OFFLINE} carrying the reason, and the next connect command starts a
 * fresh series.
 *
 * <p>Exponential from {@link #firstBackoff} and capped at {@link #maxBackoff}, which is Reactor's
 * {@code Retry.backoff} with jitter: several printers dropped by one network blip must not all
 * redial on the same tick.
 *
 * @param maxAttempts  how many times to dial before giving up, counting the first attempt
 * @param firstBackoff how long to wait after the first failure
 * @param maxBackoff   the longest the wait is ever allowed to grow to
 */
public record ConnectionPolicy(int maxAttempts, Duration firstBackoff, Duration maxBackoff) {

    /**
     * Five attempts over roughly a minute.
     *
     * <p>Long enough to ride out a printer rebooting or a switch re-learning a port, short enough
     * that a machine which is genuinely gone is reported as gone while someone is still looking at
     * the page.
     */
    public static ConnectionPolicy standard() {
        return new ConnectionPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(30));
    }
}
