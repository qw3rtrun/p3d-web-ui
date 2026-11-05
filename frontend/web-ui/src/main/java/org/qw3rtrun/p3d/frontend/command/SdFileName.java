package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.code.core.block.GLineLimit;
import org.qw3rtrun.p3d.g.code.dsl.GWordsKt;

/**
 * Checks a filename the browser sent before it is put on the wire.
 *
 * <p>Character legality is asked of {@code bareString} in {@code :gcode}'s DSL, not duplicated
 * here: {@code M23}/{@code M30}/{@code M28} all take a filename as a bare rest-of-line string, and
 * one validator avoids two that drift apart. Checked here, at the request boundary, so a bad name is
 * a 400 on the request rather than an exception on the far side of the aggregate.
 *
 * <p>Fitting the whole block is this class's own rule (spec 1.3): Marlin's buffer is 96 bytes
 * including the {@code N} word and checksum, and an overlong line is truncated rather than refused -
 * so a long name would silently become a different one. {@link GLineLimit#payloadBudget}, taken at
 * the worst case of a six-digit line number, gives the room actually available.
 */
public final class SdFileName {

    private SdFileName() {
    }

    // Worst case on purpose: a session a hundred thousand blocks in, which an upload alone can
    // reach. Budgeting at the number the *next* block happens to carry would accept a name at N900
    // and truncate it at N1000.
    private static final int PAYLOAD_BUDGET = GLineLimit.INSTANCE.payloadBudget(
            100_000, GLineLimit.MARLIN, GLineLimit.XOR_DIGITS);

    /** What is left for the name once the longest command that carries one has had its share. */
    private static final int MAX_LENGTH = PAYLOAD_BUDGET - "M28 ".length();

    // Throws rather than returning a result: the one caller is a record's compact constructor, and
    // WebFlux turns a failed body construction into a 400. The character check is bareString's own
    // refusal, asked for its message rather than the word it builds.
    public static String checked(String name) {
        if (name == null) {
            throw new IllegalArgumentException("a filename is required");
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("a filename is required");
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("the filename is " + trimmed.length()
                    + " characters, and a G-code block has room for " + MAX_LENGTH);
        }
        GWordsKt.bareString(trimmed);
        return trimmed;
    }
}
