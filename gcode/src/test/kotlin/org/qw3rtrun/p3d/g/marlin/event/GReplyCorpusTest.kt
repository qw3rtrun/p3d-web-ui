package org.qw3rtrun.p3d.g.marlin.event

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * A census of a real conversation: every line a printer actually sent, through [MarlinRsDecoder],
 * recording which class claimed it and how many nothing claimed at all.
 *
 * `marlin.gcode` is 414 lines of what a printer was *told* and is the single most valuable asset in
 * this module - it is what surfaced several of the lexer bugs. `marlin-replies.txt` is the other
 * direction, captured off MarlinSim by `backend/terminal/.../ReplyCorpusProbe.java`, and it exists
 * because the reply decoders had a circularity the command side does not: each was specified by the
 * pattern it contained and tested by cases written from the same reading of the same wiki page, so a
 * format this project had mis-read was mis-read identically in its own test. Bytes off a machine are
 * the only thing that breaks that circle.
 *
 * **The unclaimed count is the point, and it is asserted to be above zero.** A corpus where every
 * line decodes is a corpus that was trimmed to fit what the module already knew; the honest number is
 * how much of a real conversation this module does *not* understand, and
 * [the census snapshot][assertMatchesSnapshot] is what turns a change in it into a reviewable diff
 * instead of drift. It is asserted as a *property* and never as a figure, because the figure is in
 * the snapshot where it reads as a diff; a test that pinned it would have to be edited by whoever
 * recaptured, which is the drift it exists to catch.
 *
 * The capture of 2026-11-09 is **156 lines, 148 claimed and 8 unclaimed**, where the one before it
 * was 137, 131 and 6. Six of the eight are the M114 D block - `Logical:`, `Raw:`, `Stepper:`,
 * `FromStp:`, `Diff:` and the empty line it opens with - and none of those has a type here. The
 * other two are unread on purpose rather than by omission: `TEST.GCO 47`, which only
 * [SdFileListReader] may read, and `File deleted:test.gco`, which nothing models yet. Of the
 * seventeen claimed lines gained, sixteen are the `M28`/`M29` section #66 added and the last is a
 * cold extrusion refused, where the earlier capture's machine had been left with a hot hotend and
 * merely acknowledged the same move. **Every type in the 2026-10-29 census is still in this one and
 * no count below it fell**, which is the check a recapture has to pass: it replaces evidence rather
 * than adding to it.
 *
 * **Regenerating the snapshot**: delete it from `src/test/resources` and run this test once. It
 * writes the file and fails, so regeneration is always deliberate and always leaves a diff to review
 * - the same protocol as `GCorpusDecompositionTest`.
 *
 * **Recapturing the corpus itself is a different act**, and a heavier one:
 * `./gradlew :backend:terminal:probeReplyCorpus` against a printer, review that diff *by claimed
 * type* as well as by line, then regenerate this snapshot. The capture is a record of one session
 * with one firmware, so replacing it discards evidence rather than refreshing it - a run that drops
 * the only `Resend:` in the repo to gain two SD lines is a bad trade however green it is.
 */
class GReplyCorpusTest {

    /**
     * The corpus lines, with the probe's `;`-prefixed provenance header dropped.
     *
     * Marlin never begins a reply with `;`, so dropping that prefix cannot swallow a real line -
     * which is why the probe marks its header that way. `//` and `echo:` *are* reply prefixes and
     * are kept, and so is the empty line the `M114 D` block opens with: an empty reply is a reply,
     * and a filter that dropped it would hide the one line in here with no content at all.
     */
    private val corpus: List<String> = requireNotNull(
        javaClass.getResourceAsStream("/marlin-replies.txt")
    ) { "the reply corpus is missing from the test resources" }
        .readBytes().decodeToString()
        .lines()
        .let { if (it.last().isEmpty()) it.dropLast(1) else it }
        .filterNot { it.startsWith(";") }

    /** One entry per received line: `<claiming class>\t<the line as it arrived>`. */
    private val census: List<String> = corpus.map { line ->
        val claimed = MarlinRsDecoder.decode(line)?.javaClass?.simpleName ?: NONE
        "$claimed\t$line"
    }

    private fun typeOf(entry: String) = entry.substringBefore('\t')

    private fun unclaimed() = census.count { typeOf(it) == NONE }

    /**
     * Compares [actual] against the snapshot resource [name], writing it and failing when there is
     * no snapshot yet.
     */
    private fun assertMatchesSnapshot(name: String, actual: List<String>) {
        val golden = javaClass.getResourceAsStream(name)?.readBytes()?.decodeToString()
            ?: run {
                File("src/test/resources$name").writeText(actual.joinToString("\n", postfix = "\n"))
                fail("snapshot $name was missing and has been written from the current census - review it and rerun")
            }

        val expected = golden.lines().let { if (it.last().isEmpty()) it.dropLast(1) else it }

        assertEquals(expected.size, actual.size) { "$name: the corpus censused into a different number of lines" }
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i]) { "$name: line ${i + 1} is claimed differently" }
        }
    }

    @Nested
    inner class Census {

        @Test
        fun `the census matches the snapshot`() {
            assertMatchesSnapshot("/marlin-replies.census.txt", census)
        }

        @Test
        fun `some of a real conversation is still unread, and the count is the measure of it`() {
            // Asserted as above-zero rather than as a figure: the figure lives in the snapshot,
            // where a change to it reads as a diff. Zero here would mean the capture was trimmed to
            // what the module already knew.
            assertTrue(unclaimed() > 0) {
                "every line of the corpus decodes, which means the capture was trimmed to fit"
            }
            assertTrue(unclaimed() < corpus.size / 2) {
                "more than half the corpus is unread - ${unclaimed()} of ${corpus.size}"
            }
        }

        @Test
        fun `nothing throws on any line of it`() {
            // Spec 9, over real bytes rather than invented ones: a decoder answers with a value or
            // with absence, and a reply it cannot read is not an exception. The census above has
            // already called every decoder once; this says so as its own claim.
            for (line in corpus) {
                MarlinRsDecoder.decode(line)
            }
        }
    }

    /**
     * What the capture is for. These are the lines that are hard to provoke and easy to lose: each
     * is a shape a hand-written test would only contain if its author already knew to write it, and
     * the point of a capture is the ones nobody knew.
     */
    @Nested
    inner class WhatItCovers {

        private fun claims(prefix: String) =
            census.filter { it.substringAfter('\t').startsWith(prefix) }

        @Test
        fun `the acknowledgement of a numbered line is in it, and decodes`() {
            // `ok N2 P15 B3` - the form that fell through every decoder until #23, because the
            // decoder read `N` last and encode() wrote it last, so the type agreed only with itself.
            val numbered = census.filter { it.substringAfter('\t').startsWith("ok N") }
            assertTrue(numbered.isNotEmpty()) { "no acknowledgement of a numbered line in the corpus" }
            assertTrue(numbered.all { typeOf(it) == "AdvancedOkRs" }) { "unread: $numbered" }
        }

        @Test
        fun `the checksum failure and the resend it provokes are both in it`() {
            val errors = claims("Error:")
            val resends = claims("Resend:")
            assertTrue(errors.isNotEmpty()) { "no error line in the corpus" }
            assertTrue(resends.isNotEmpty()) { "no resend request in the corpus" }
            assertTrue(errors.all { typeOf(it) == "ErrorRs" }) { "unread: $errors" }
            assertTrue(resends.all { typeOf(it) == "ResendRs" }) { "unread: $resends" }
        }

        @Test
        fun `the unbidden auto-report is in it, and is not an acknowledgement`() {
            // The capture's reason for existing: a request/response sweep never sees these. Note
            // the leading space - MarlinSim writes one, and a decoder that anchored on `T` would
            // read none of them.
            val reports = census.filter { typeOf(it) == "BareTemperatureRs" }
            assertTrue(reports.isNotEmpty()) { "no unbidden temperature report in the corpus" }
            assertTrue(reports.any { it.substringAfter('\t').startsWith(" ") }) {
                "no auto-report with the leading space MarlinSim writes"
            }
        }

        @Test
        fun `a temperature report carried on an ok is in it`() {
            assertTrue(census.any { typeOf(it) == "OkTemperatureRs" }) {
                "no ok-carried temperature report - M105's answer is how M109 ends its wait"
            }
        }

        @Test
        fun `the busy keepalive is in it`() {
            // Three of them, from the homing move. This is the line a live probe found arriving as
            // undifferentiated prose while the machine homed.
            assertTrue(census.any { typeOf(it) == "BusyNotice" }) { "no busy keepalive in the corpus" }
        }

        @Test
        fun `a host action command is in it`() {
            assertTrue(census.any { typeOf(it) == "ActionRs" }) {
                "no //action: line - these are instructions to the host, not information"
            }
        }

        @Test
        fun `the SD write is in it, both ends of the exchange`() {
            // #66. Both types had classes and unit tests and no measurement: the probe sent no
            // `M28`, so neither line could reach a capture, and the transcript that proved them
            // lived in INTEGRATION_TESTS_spec.md instead. Pinned here because a later recapture
            // that lost the M28/M29 section would otherwise only show up as a snapshot diff.
            val writing = census.filter { typeOf(it) == "SdWritingToFile" }
            val done = census.filter { typeOf(it) == "SdDoneSaving" }
            assertTrue(writing.isNotEmpty()) { "no `Writing to file:` - M28's answer is not in the capture" }
            assertTrue(done.isNotEmpty()) { "no `Done saving file.` - M29's answer is not in the capture" }
            assertTrue(census.any { typeOf(it) == "SdFreshFile" }) {
                "no `echo:Now fresh file:` - the third line of M28's answer"
            }
        }

        @Test
        fun `the M114 D block is the unread bulk, and says what has no type yet`() {
            // Not a defect to fix here: it is the census doing its job. Recorded as a test so that
            // a decoder which starts claiming these has to say so.
            val unread = census.filter { typeOf(it) == NONE }.map { it.substringAfter('\t') }
            assertTrue(unread.any { it.startsWith("Logical:") }) { "no detailed-position block in the corpus" }
            assertTrue(unread.any { it.startsWith("Stepper:") }) { "no stepper line in the corpus" }
            assertTrue(unread.any { it.isEmpty() }) { "no empty reply line in the corpus" }
            // A listing entry is unread *on purpose* - `<word> <number>` is too common a shape on
            // this wire for a registry decoder to claim, so only SdFileListReader, which already
            // knows a list is open, may read one. The census is where that decision is visible.
            assertTrue(unread.any { it == "TEST.GCO 47" }) { "no M20 listing entry in the corpus" }
        }

        @Test
        fun `the M503 settings dump is in it, headings and values both`() {
            // 25 lines of it, and the whole block used to census as undifferentiated EchoMessage
            // prose - which it only was because nothing read it. The capture is the evidence that
            // the two shapes are what the firmware writes rather than what the decoder expects.
            val sections = census.filter { typeOf(it) == "SettingsSection" }
            val values = census.filter { typeOf(it) == "SettingsValue" }
            assertEquals(12, sections.size) { "the M503 dump's headings: $sections" }
            assertEquals(13, values.size) { "the M503 dump's settings: $values" }
            assertTrue(values.any { it.substringAfter('\t') == "echo:  M92 X80.00 Y80.00 Z400.00 E500.00" }) {
                "no steps-per-unit line - the one a Settings view is for"
            }
            assertTrue(sections.any { it.substringAfter('\t').contains("<print-accel>") }) {
                "no heading with punctuation in it - the title runs to the last colon, not the first"
            }
        }

        @Test
        fun `the firmware block and its capabilities are in it`() {
            assertTrue(census.any { typeOf(it) == "FirmwareInfoRs" }) { "no M115 identity line in the corpus" }
            assertTrue(census.count { typeOf(it) == "CapabilityRs" } > 20) {
                "the capability report is short - M115 writes one Cap: line per feature"
            }
        }

        @Test
        fun `a rejected command is in it, each of the ways a printer rejects one`() {
            assertTrue(census.any { typeOf(it) == "UnknownCommand" }) { "no unknown command in the corpus" }
            assertTrue(census.any { typeOf(it) == "InvalidExtruder" }) {
                "no invalid-extruder notice - the other echo a host must not ignore"
            }
            // The third way, and the one the 2026-11-09 capture gained by accident: the same
            // `G1 E10 F100` answered a bare `ok` in the 2026-10-29 capture because that machine's
            // hotend was at 210 degrees, and `echo: cold extrusion prevented` when it was at 25.
            // A refusal that depends on the machine's state is exactly the kind a hand-written
            // test would not contain, so it is pinned now that something has seen it.
            assertTrue(census.any { typeOf(it) == "ColdExtrusionPrevented" }) {
                "no cold-extrusion refusal - a move the printer acknowledged and did not make"
            }
        }
    }

    private companion object {

        /** What the census writes for a line no decoder claimed. */
        const val NONE = "(none)"
    }
}
