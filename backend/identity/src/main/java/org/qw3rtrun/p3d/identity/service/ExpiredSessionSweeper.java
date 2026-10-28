package org.qw3rtrun.p3d.identity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * Deletes sessions that have expired.
 *
 * <p><strong>Nothing else does.</strong> An expired session stops <em>working</em> the moment its
 * expiry passes - the repository will not return it - but the row stays, and so do its attribute
 * rows. Spring Session's own configuration class schedules this; wiring the repository by hand
 * means inheriting the obligation with it, and this repository's rule is that everything created
 * has an owner that releases it.
 *
 * <p>Without it the table grows by one row per sign-in forever. On a farm where a wall display
 * reconnects all day that is not a slow leak.
 *
 * <p>The delete is a range scan over {@code EXPIRY_TIME}, which is what index {@code IX2} in the
 * changelog is for.
 */
@Slf4j
@RequiredArgsConstructor
public class ExpiredSessionSweeper {

    private final JdbcIndexedSessionRepository sessions;

    /**
     * Once a minute by default, which is Spring Session's own cadence.
     *
     * <p>{@code fixedDelay} and not {@code fixedRate}: a sweep that takes longer than the interval
     * should wait rather than queue another one behind it, because the slow case is a large table,
     * which is exactly when piling up concurrent deletes is worst.
     */
    @Scheduled(fixedDelayString = "${p3d.security.session-sweep-interval:60000}")
    public void sweep() {
        try {
            sessions.cleanUpExpiredSessions();
        } catch (RuntimeException e) {
            // A failed sweep must not kill the scheduled task - the next one should try again.
            log.warn("could not clean up expired sessions: {}", e.toString());
        }
    }
}
