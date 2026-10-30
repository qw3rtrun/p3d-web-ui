package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.code.core.block.GLineLimit;

/**
 * Checks a filename the browser sent before it is put on the wire.
 *
 * <p><strong>A filename reaches the printer verbatim.</strong> {@code M23}, {@code M30} and
 * {@code M28} all take it as a bare rest-of-line string - no letter, no quoting, everything to the
 * end of the line belongs to it - so whatever the browser posts is what the firmware reads. That
 * makes it the one field on this surface where a character can mean something:
 *
 * <ul>
 *   <li><strong>A line terminator splits the block.</strong> {@code "a.gco\nM112"} is not a
 *       filename, it is a delete <em>and</em> an emergency stop, and the transport frames what it is
 *       given without parsing it - so the {@code N} word lands on one side of the break and the
 *       checksum on the other. This is the one that matters.
 *   <li><strong>A {@code ;} starts a comment</strong> that runs to end of line and swallows the
 *       checksum field the transport appends behind it, so the firmware rejects the block and asks
 *       for it again, forever.
 *   <li><strong>A {@code *} is the checksum delimiter</strong> (spec 8.3), so a name containing one
 *       makes the firmware read part of the name as the checksum.
 * </ul>
 *
 * <p>Everything outside printable ASCII goes too, because the wire is 7-bit (spec 1.1) and a card
 * that reports {@code TEST.GCO} in DOS 8.3 has no use for the rest of Unicode anyway.
 *
 * <p><strong>And the whole block has to fit.</strong> Spec 1.3: Marlin's buffer is 96 bytes
 * including the {@code N} word and the checksum, and a line over it is truncated rather than
 * refused - so a long name would silently become a different name. {@link GLineLimit#payloadBudget}
 * is what says how much of that a payload may use; this takes the worst case, which is a six-digit
 * line number.
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
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < ' ' || c > '~') {
                throw new IllegalArgumentException(
                        "the filename contains a character the wire cannot carry at position " + i);
            }
            if (c == ';' || c == '*') {
                throw new IllegalArgumentException(
                        "the filename contains '" + c + "', which G-code reads as punctuation");
            }
        }
        return trimmed;
    }
}
