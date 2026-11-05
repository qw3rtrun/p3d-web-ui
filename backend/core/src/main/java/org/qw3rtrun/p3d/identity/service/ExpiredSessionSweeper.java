package org.qw3rtrun.p3d.identity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * Deletes sessions that have expired - nothing else does. An expired session stops working the
 * moment its expiry passes (the repository will not return it), but the row and its attribute rows
 * stay; Spring Session's own configuration class normally schedules this, and wiring the repository
 * by hand means inheriting that obligation too. Without it the table grows by one row per sign-in
 * forever - not a slow leak on a farm where a wall display reconnects all day. The delete is a range
 * scan over {@code EXPIRY_TIME}, which is what index {@code IX2} in the changelog is for.
 */
@Slf4j
@RequiredArgsConstructor
public class ExpiredSessionSweeper {

    private final JdbcIndexedSessionRepository sessions;

    /** Once a minute by default (Spring Session's own cadence). {@code fixedDelay}, not {@code fixedRate}: a sweep slower than the interval should wait rather than queue another behind it, worst exactly when the table is large. */
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
