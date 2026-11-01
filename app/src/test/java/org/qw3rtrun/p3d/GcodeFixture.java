package org.qw3rtrun.p3d;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * A G-code file for a test to upload - generated, not checked in.
 *
 * <p>Issue #50 framed this as "a decision about what a test-sized G-code file looks like", and the
 * decision is that there is no such single file. Three tests want three different things from one:
 *
 * <ul>
 *   <li><strong>#50, the file store</strong>, wants one file whose slicer comments are at the
 *       <em>top</em>, one whose summary is at the <em>bottom</em>, one with no comments at all, and
 *       one larger than a single network read;</li>
 *   <li><strong>#51, the print-job queue</strong>, wants a known {@code estimatedSeconds} and a
 *       known line count, because those are what a progress figure is divided by;</li>
 *   <li><strong>#53, the SD upload</strong>, wants one small enough that every line the farm writes
 *       to the card can be read back out of {@code MockPrinter.commands()} and compared.</li>
 * </ul>
 *
 * One resource file cannot be all four and six resource files are six things to keep in step, so
 * the bytes are built here and the two axes that matter - the dialect and the size - are arguments.
 *
 * <p><strong>The decisive reason it is not a resource, though, is line endings.</strong> A G-code
 * file is line-oriented, the store counts its lines and hashes its bytes, and a checked-in
 * {@code .gcode} is subject to Git's end-of-line normalisation on checkout. On a Windows working
 * copy with {@code core.autocrlf=true} every {@code \n} becomes {@code \r\n}: the size changes, the
 * SHA-256 changes, and a test that pinned either would fail on one developer's machine and nobody
 * else's. Bytes built in the JVM have the line endings this class says they have.
 *
 * <h2>The mark</h2>
 *
 * Every fixture is constructed with a <strong>mark</strong>, and it is not optional on purpose.
 * The store is content-addressed - the same bytes stored twice are one file, under the first name -
 * and the whole suite shares one database. Two tests that happened to build the same fixture would
 * find each other's record, which is the store working correctly and the tests lying. The mark goes
 * into the file as a coordinate rather than a comment, so an {@link #unsliced} fixture stays
 * genuinely comment-free.
 *
 * <p>It follows that {@code cura("x").named("a.gcode")} and {@code cura("x").named("b.gcode")} are
 * the <em>same bytes under two names</em>, which is exactly what the de-duplication test needs to
 * say.
 *
 * <p>Everything here is ASCII, so a character is a byte and {@link #sizeBytes()} can be taken from
 * the text's length.
 */
final class GcodeFixture {

    /**
     * Larger than one buffer anywhere on the path, in both senses that matter.
     *
     * <p>Two numbers are being cleared. A socket read hands WebFlux at most 64 KiB at a time, so a
     * file above that arrives as several {@code DataBuffer}s and a path that quietly accumulated
     * them would have to say so; and the store reads slicer comments from bounded windows at each
     * end of the file rather than from the whole of it, so a file above twice that window has a
     * middle that is in neither. 192 KiB is comfortably past both.
     *
     * <p>It is also small enough for the test client to hold a download in memory, which the server
     * never does - see {@code FarmIntegrationTest.signIn}.
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
     * Cura's dialect, with everything worth knowing in the first few lines.
     *
     * <p>The values are the ones {@code SliceInfoReaderTest} in {@code :backend:core} measures
     * against, so a test here asserting 3661 seconds is asserting that the same reading survived
     * the trip through HTTP, and not re-deciding what the reading should be.
     */
    static GcodeFixture cura(String mark) {
        return new GcodeFixture(Dialect.CURA, mark, "cura.gcode", 8, 0);
    }

    /**
     * PrusaSlicer's dialect, which names itself at the top and summarises at the bottom.
     *
     * <p>Only the slicer's name is in the header here - the time, the filament and the layer height
     * are in the trailer. That split is what makes {@link #ofAtLeast} meaningful: on a file big
     * enough, the two are read from two different windows, and a reader that only looked at the top
     * would come back with the least useful subset.
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
     * The blocks a print job will put on the wire, in file order - #51's denominator.
     *
     * <p>Not the same as {@link #lineCount()}, and the difference is the point: a feed drops blank
     * and comment-only lines rather than spending a line number and a slot in the printer's buffer
     * to say nothing, so a sliced fixture sends fewer blocks than it has lines. A test that asserts
     * what reached the machine needs the smaller number, and needs it independently of the server -
     * taking the list from the response would be comparing the server to itself.
     *
     * <p><strong>This is not a G-code reader and must not become one.</strong> {@code GcodeLines} in
     * {@code :backend:core} is, and it tokenizes every line through {@code :gcode} because
     * {@code G1 X10 ; wipe} and {@code (a note ; not a comment)} are both real. What is relied on
     * here is a property of these fixtures alone: every comment they contain is a whole line of its
     * own, and no line carries a trailing one. Adding a fixture that breaks that would make this
     * quietly wrong, so do not - put the trailing comment in the test that wants to prove it is
     * stripped, and assert that against {@code GcodeLinesTest}'s subject instead.
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

    /**
     * SHA-256 of the bytes, lower-case hex - computed here rather than asked of the server.
     *
     * <p>A test that took the hash from the response and compared it to itself would pass whatever
     * the server stored. This is the independent answer the response has to match.
     */
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
     * How long the slicer thinks it takes.
     *
     * <p>The two dialects deliberately do <em>not</em> agree: Cura's {@code ;TIME:3661} and
     * PrusaSlicer's {@code 1h 2m 3s} are 3661 and 3723, sixty-two seconds apart. A test that read
     * the wrong dialect's value would otherwise still pass.
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

        // The mark, as a coordinate: it makes these bytes this fixture's own without being a
        // comment, which an unsliced fixture must not have.
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
