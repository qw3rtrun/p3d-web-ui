package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.code.core.block.GLineLimit;
import org.qw3rtrun.p3d.g.code.dsl.GWordsKt;

/**
 * Checks a filename the browser sent before it is put on the wire.
 *
 * <p><strong>Which characters a filename may hold is not this class's rule.</strong> {@code M23},
 * {@code M30} and {@code M28} all take a filename as a bare rest-of-line string, and
 * {@code bareString} in {@code :gcode}'s DSL is what refuses the characters that would change what
 * the block means - a line terminator that splits it, a {@code ;} that comments out the checksum, a
 * {@code *} that is read as the checksum delimiter, and anything outside printable ASCII. This asks
 * that function rather than keeping a second copy of the list, because two validators for one rule
 * drift apart and the one further from the wire is the one that goes stale. It is asked
 * <em>here</em>, at the request boundary, only so that a bad filename is a 400 on the request that
 * carried it rather than an exception raised on the far side of the aggregate.
 *
 * <p><strong>The whole block has to fit, and that rule is this class's.</strong> Spec 1.3: Marlin's
 * buffer is 96 bytes including the {@code N} word and the checksum, and a line over it is truncated
 * rather than refused - so a long name would silently become a different name. The DSL cannot check
 * this, because the length a payload may use depends on the whole block and on the line number the
 * transport will prepend; {@link GLineLimit#payloadBudget} is what answers it, and this takes the
 * worst case, a six-digit line number.
 */
public final class SdFileName {

    private SdFileName() {
    }

    /**
     * The longest payload that still fits once the transport has numbered and checksummed it.
     *
     * <p>The worst case on purpose - a session that has sent a hundred thousand blocks, which an
     * upload reaches on its own. Taking the budget at the number the <em>next</em> block happens to
     * carry would mean a name that was accepted at {@code N900} and truncated at {@code N1000}.
     */
    private static final int PAYLOAD_BUDGET = GLineLimit.INSTANCE.payloadBudget(
            100_000, GLineLimit.MARLIN, GLineLimit.XOR_DIGITS);

    /** What is left for the name once the longest command that carries one has had its share. */
    private static final int MAX_LENGTH = PAYLOAD_BUDGET - "M28 ".length();

    /**
     * The name, trimmed, or an {@link IllegalArgumentException} naming what is wrong with it.
     *
     * <p>Throws rather than returning a result, because there is exactly one caller shape - a
     * record's compact constructor - and a bad filename is a bad request rather than a state the
     * application carries around. WebFlux turns a failure to construct a request body into a 400.
     *
     * <p>The character check is the bare-string builder's own, asked for its refusal and not for
     * the word it returns: if the name cannot be built into the command that will carry it, it
     * cannot be sent, and the message it raises names the character and where it was found.
     *
     * @param name what the browser sent
     * @return the same name with surrounding blanks removed
     */
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
