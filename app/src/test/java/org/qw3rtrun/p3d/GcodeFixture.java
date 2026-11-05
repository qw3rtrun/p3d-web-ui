package org.qw3rtrun.p3d;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * A G-code file for a test to upload - generated, not checked in. Issue #50 found no single
 * test-sized file would do: #50 (the file store) wants comments at the top, at the bottom, absent,
 * and a file larger than one network read; #51 (the job queue) wants a known
 * {@code estimatedSeconds} and line count to divide progress by; #53 (the SD upload) wants one
 * small enough to read every line back out of {@code MockPrinter.commands()}. One resource file
 * cannot be all of that and six resource files are six things to keep in step, so the bytes are
 * built here, with dialect and size as arguments.
 *
 * <p>Not a resource file because of line endings: this is line-oriented content whose byte count
 * and hash are asserted, and Git's checkout normalisation (e.g. Windows {@code core.autocrlf=true})
 * would change both per developer. Bytes built in the JVM have the line endings this class says.
 *
 * <p>Every fixture carries a <strong>mark</strong>, not optional: the store is content-addressed
 * and the whole suite shares one database, so two tests building the same bytes would collide on
 * each other's record. The mark is written in as a coordinate rather than a comment, so
 * {@link #unsliced} stays genuinely comment-free, and {@code cura("x").named("a.gcode")} /
 * {@code .named("b.gcode")} are deliberately the same bytes under two names, for the
 * de-duplication test.
 *
 * <p>Everything here is ASCII, so {@link #sizeBytes()} can be taken from the text's length.
 */
final class GcodeFixture {

    /**
     * Past two buffer sizes at once: a WebFlux socket read hands over at most 64 KiB, and the
     * store's slicer-comment windows are bounded at each end, so a file above twice that has a
     * middle neither reads. 192 KiB clears both, and is small enough for the test client to hold in
     * memory too (see {@code FarmIntegrationTest.signIn}).
     */
    static final int BIGGER_THAN_ONE_BUFFER = 192 * 1024;

    /** Which program's habits the comments imitate. There is no standard for any of it. */
    private enum Dialect {
        /** Colons, seconds, filament in metres, and the summary at the top. */
        CURA,
        /** Equals signs, a spelled-out duration, and the summary at the <em>bottom</em>. */
        PRUSA,
        /** Hand-written: no comments at all, and still a perfectly printable file. */
        NONE
    }

    private final Dialect dialect;
    private final String mark;
    private final String name;
    private final int bodyLines;
    private final int minimumBytes;

    private GcodeFixture(Dialect dialect, String mark, String name, int bodyLines, int minimumBytes) {
        this.dialect = dialect;
        this.mark = mark;
        this.name = name;
        this.bodyLines = bodyLines;
        this.minimumBytes = minimumBytes;
    }

    /**
     * The values are the ones {@code SliceInfoReaderTest} in {@code :backend:core} measures
     * against, so asserting 3661 seconds here asserts the reading survived the trip through HTTP,
     * not re-deciding it.
     */
    static GcodeFixture cura(String mark) {
        return new GcodeFixture(Dialect.CURA, mark, "cura.gcode", 8, 0);
    }

    /**
     * Only the slicer's name is in the header; time, filament and layer height are in the trailer -
     * the split that makes {@link #ofAtLeast} meaningful, since on a big enough file the two come
     * from different windows and a top-only reader gets the least useful subset.
     */
    static GcodeFixture prusaSlicer(String mark) {
        return new GcodeFixture(Dialect.PRUSA, mark, "prusa.gcode", 8, 0);
    }

    /** Hand-written G-code. Nothing can be read out of it, and that is not an error. */
    static GcodeFixture unsliced(String mark) {
        return new GcodeFixture(Dialect.NONE, mark, "handwritten.gcode", 2, 0);
    }

    /** The name it is uploaded under. The bytes do not depend on it - see the mark. */
    GcodeFixture named(String name) {
        return new GcodeFixture(dialect, mark, name, bodyLines, minimumBytes);
    }

    /** How many motion lines the body carries, for a test that wants to count them. */
    GcodeFixture withBodyLines(int lines) {
        return new GcodeFixture(dialect, mark, name, lines, minimumBytes);
    }

    /** Pads the body with real motion lines until the file is at least this large. */
    GcodeFixture ofAtLeast(int bytes) {
        return new GcodeFixture(dialect, mark, name, bodyLines, bytes);
    }

    String name() {
        return name;
    }

    byte[] bytes() {
        return text().getBytes(StandardCharsets.UTF_8);
    }

    long sizeBytes() {
        return text().length();
    }

    /** Lines as the store counts them: every file built here ends with a terminator. */
    long lineCount() {
        return text().chars().filter(c -> c == '\n').count();
    }

    /**
     * The blocks a print job puts on the wire, in file order - #51's denominator. Not
     * {@link #lineCount()}: a feed drops blank and comment-only lines, so a sliced fixture sends
     * fewer blocks than it has lines, and a test needs that number independently of the server.
     *
     * <p>Not a G-code reader and must not become one - {@code GcodeLines} in {@code :backend:core}
     * tokenizes through {@code :gcode} because {@code G1 X10 ; wipe} is real. This relies solely on
     * every comment in these fixtures being a whole line, never trailing; a fixture that breaks
     * that invariant belongs in a test against {@code GcodeLinesTest} instead.
     */
    List<String> blocks() {
        var blocks = new ArrayList<String>();
        for (var line : text().split("\n")) {
            var trimmed = line.trim();
            if (!trimmed.isEmpty() && trimmed.charAt(0) != ';') {
                blocks.add(trimmed);
            }
        }
        return List.copyOf(blocks);
    }

    /** SHA-256 of the bytes, lower-case hex, computed here so a test is not comparing the server to itself. */
    String sha256() {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(bytes());
            var hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    /** What the slicer said, for a test to assert the server read the same thing. */
    String slicer() {
        return switch (dialect) {
            case CURA -> "Cura_SteamEngine 5.2.1";
            case PRUSA -> "PrusaSlicer 2.6.0+win64";
            case NONE -> null;
        };
    }

    /**
     * The dialects deliberately disagree: Cura's {@code ;TIME:3661} and PrusaSlicer's
     * {@code 1h 2m 3s} are 3661 vs 3723, so a test reading the wrong dialect's value would
     * otherwise still pass.
     */
    Long estimatedSeconds() {
        return switch (dialect) {
            case CURA -> 3661L;
            case PRUSA -> 3723L;
            case NONE -> null;
        };
    }

    /** Millimetres, which is what the record keeps whichever unit the slicer wrote. */
    Double filamentMillimetres() {
        return dialect == Dialect.NONE ? null : 1234.5;
    }

    Double layerHeight() {
        return dialect == Dialect.NONE ? null : 0.2;
    }

    private String text() {
        var sb = new StringBuilder();
        switch (dialect) {
            case CURA -> sb.append(";FLAVOR:Marlin\n")
                    .append(";TIME:3661\n")
                    .append(";Filament used: 1.2345m\n")
                    .append(";Layer height: 0.2\n")
                    .append(";Generated with Cura_SteamEngine 5.2.1\n");
            case PRUSA -> sb.append("; generated by PrusaSlicer 2.6.0+win64 on 2026-01-01 at 12:00:00 UTC\n");
            case NONE -> {
                // Nothing. A file nothing can be read from is still a file.
            }
        }

        sb.append("G21\n").append("G90\n").append("G28\n");

        // The mark as a coordinate, not a comment - an unsliced fixture must stay comment-free.
        sb.append("G1 X").append(Integer.toUnsignedString(mark.hashCode())).append(" Y0 E0\n");

        // The body. Real motion, nothing a slicer-comment reader could mistake for a key-value.
        int line = 0;
        while (line < bodyLines || sb.length() < minimumBytes) {
            sb.append("G1 X").append(10 + line % 190)
                    .append(" Y").append(10 + line % 170)
                    .append(" E").append(line)
                    .append('\n');
            line++;
        }

        sb.append("M104 S0\n").append("M84\n");

        if (dialect == Dialect.PRUSA) {
            sb.append("; filament used [mm] = 1234.5\n")
                    .append("; estimated printing time (normal mode) = 1h 2m 3s\n")
                    .append("; layer_height = 0.2\n");
        }
        return sb.toString();
    }
}
