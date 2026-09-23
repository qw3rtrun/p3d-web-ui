package org.qw3rtrun.p3d.g.marlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.code.core.XorCheckSum
import org.qw3rtrun.p3d.g.code.core.token.GLiner
import org.qw3rtrun.p3d.g.code.core.token.GTokenizer
import org.qw3rtrun.p3d.g.marlin.command.AutoHome
import org.qw3rtrun.p3d.g.marlin.command.BedLeveling3Point
import org.qw3rtrun.p3d.g.marlin.command.Dwell
import org.qw3rtrun.p3d.g.marlin.command.GetCurrentPosition
import org.qw3rtrun.p3d.g.marlin.command.LinearMoveG1
import org.qw3rtrun.p3d.g.marlin.command.MachineName
import org.qw3rtrun.p3d.g.marlin.command.MoveInMachineCoordinates
import org.qw3rtrun.p3d.g.marlin.command.SelectOrReportToolT0
import org.qw3rtrun.p3d.g.marlin.command.SelectSDFile
import org.qw3rtrun.p3d.g.marlin.command.SetGetLineNumber
import org.qw3rtrun.p3d.g.marlin.command.SetHotendTemperature
import org.qw3rtrun.p3d.g.protocol.GRq
import java.math.BigDecimal

/**
 * Decoding **real lines** into typed commands: the reading half of `:gcode`, exercised the way a
 * host meets it - text off the wire, through the tokenizer, into a command class.
 *
 * Three tests cover parts of this already and none covers it from here:
 *
 * - `MarlinCommandsTest` is *generated from the same data as the classes it tests*, so it can only
 *   prove that encoding and decoding agree with each other - a letter extracted wrongly would be
 *   wrong on both sides and it would still pass. Every input it uses also comes out of `GEncoder`,
 *   so no line it decodes is spelled in a way the encoder would not have chosen.
 * - `MarlinRQTest` states the per-class contract by hand, on `M105` alone.
 * - `MarlinDocExamplesTest` checks *coverage* - that every letter in a Marlin doc example is
 *   modelled somewhere - not what a decoded command holds.
 *
 * So what is written here is the input shapes the wire carries and the encoder cannot produce: a
 * space inside a field, a non-canonical number, a lowercase letter, comments between parameters, a
 * framed line, a flag standing where a value was documented, two commands on one line. Every case
 * is one a printer, a slicer or a host actually sends.
 *
 * Where the reading is *lossy* that is pinned here too, under [Limits], with the reason. Those are
 * claims about the model as it stands: change one deliberately, do not re-baseline it.
 */
class MarlinDecoderTest {


    /** What a host does with a line it is about to interpret: tokens in, one typed command out. */
    private fun decode(line: String): GRq<*>? = MarlinCommands.decode(GTokenizer.parse(line).toList())

    /** The same through the liner, which is what strips `N…` and `*…` (spec 7 and 8). */
    private fun decodeLine(line: String): GRq<*>? =
        MarlinCommands.decode(GTokenizer.lines(line).first().body)

    private fun encode(rq: GRq<*>) = GEncoder.encode(rq.encode())

    /** `N1 <payload>*<xor>`, checksummed over the bytes as transmitted (spec 8.3). */
    private fun framed(payload: String): String {
        val covered = "N1 $payload"
        val sum = XorCheckSum()
        sum.add(covered)
        return "$covered*${sum.get().lexeme}"
    }

    @Nested
    inner class Values {

        @Test
        fun `a decimal keeps the digits that were written`() {
            val move = decode("G1 X10.50 Y-3 F1500") as LinearMoveG1

            // Scale is part of a BigDecimal's identity, and that is the point: `10.50` was sent,
            // so `10.50` is what a re-emitted line has to say - the checksum moves with it.
            assertEquals(BigDecimal("10.50"), move.pos)
            assertNotEquals(BigDecimal("10.5"), move.pos)
            assertEquals(BigDecimal("-3"), move.y)
            assertEquals(BigDecimal("1500"), move.rate)
            assertNull(move.z)
        }

        @Test
        fun `a sign, a leading dot and a trailing dot are all numbers`() {
            // spec 3.1 accepts all three: a slicer writes `.5`, hand-written G-code writes `+5`.
            val move = decode("G1 X+5 Y.5 Z1.") as LinearMoveG1

            assertEquals(BigDecimal("5"), move.pos)
            assertEquals(BigDecimal("0.5"), move.y)
            assertEquals(BigDecimal("1"), move.z)
        }

        @Test
        fun `an int parameter takes the integral part of a decimal`() {
            // `M104 I1.0` where an index was documented: keep the word rather than lose it.
            assertEquals(SetHotendTemperature(index = 1), decode("M104 I1.0"))
        }

        @Test
        fun `a bool is any non-zero number, and absent stays null`() {
            assertEquals(true, (decode("G28 L1") as AutoHome).l)
            assertEquals(false, (decode("G28 L0") as AutoHome).l)
            assertNull((decode("G28") as AutoHome).l)
        }

        @Test
        fun `a flag is presence, and a value does not hide it`() {
            val homed = decode("G28 X Y") as AutoHome
            assertTrue(homed.x)
            assertTrue(homed.y)
            assertFalse(homed.z)

            // `G28 X10` is not a G28 the docs describe, but the letter is there, and reporting it
            // as present beats dropping the word silently.
            assertTrue((decode("G28 X10") as AutoHome).x)
        }

        @Test
        fun `a quoted string is read without its quotes`() {
            assertEquals(MachineName(name = "My Printer"), decode("M550 P\"My Printer\""))
        }

        @Test
        fun `a bare rest-of-line string is the argument itself`() {
            // spec 3.4a: no letter in front of it, so `P` stays null and the text is the name.
            // Which commands take one, and where it starts, is `stringArg`'s business.
            assertEquals(MachineName(message = "My Printer"), decode("M550 My Printer"))
            assertEquals(
                SelectSDFile(filename = "/path/to/file.gco"),
                decode("M23 /path/to/file.gco"),
            )
        }
    }

    @Nested
    inner class Fields {

        @Test
        fun `a space may sit between a letter and its value`() {
            // spec 2.1. The encoder never writes this, so only hand-written input reaches it -
            // and printers do receive it.
            assertEquals(SetHotendTemperature(temp = BigDecimal("200")), decode("M104 S 200"))
        }

        @Test
        fun `a parameter letter is case-insensitive`() {
            // spec 2.2, through GIdentifier's explicit ASCII folding.
            assertEquals(SetHotendTemperature(temp = BigDecimal("200")), decode("M104 s200"))
        }

        @Test
        fun `a comment is not a parameter`() {
            // 41% of the corpus's non-blank lines carry one, in both of spec 6's spellings.
            assertEquals(SetHotendTemperature(temp = BigDecimal("200")), decode("M104 S200 ; heat"))
            assertEquals(
                LinearMoveG1(pos = BigDecimal("10"), rate = BigDecimal("1500")),
                decode("G1 X10 (mid) F1500"),
            )
        }

        @Test
        fun `a letter the command does not document is ignored`() {
            // A firmware-specific or newer letter must not disturb the ones that are modelled.
            assertEquals(SetHotendTemperature(temp = BigDecimal("200")), decode("M104 S200 Q9"))
        }

        @Test
        fun `the first spelling of a letter wins`() {
            // A line carrying one letter twice is malformed; quietly preferring the second would
            // hide that, and which one a firmware takes is not something to guess at.
            assertEquals(SetHotendTemperature(temp = BigDecimal("200")), decode("M104 S200 S210"))
        }

        @Test
        fun `a command with nothing after it decodes to nothing set`() {
            assertEquals(AutoHome(), decode("G28"))
            assertEquals(GetCurrentPosition(), decode("M114"))
            // The same through the class's own decoder, handed no parameter tokens at all.
            assertEquals(SetHotendTemperature(), SetHotendTemperature.decodeParams(emptyList()))
        }
    }

    @Nested
    inner class Framing {

        @Test
        fun `a framed line decodes from its body`() {
            // The liner removes `N1 ` and `*57`; the decoder is handed the body between them
            // (spec 7, 8). Decoders never see the framing and never have to skip it.
            assertEquals(
                SetHotendTemperature(temp = BigDecimal("200")),
                decodeLine(framed("M104 S200")),
            )
        }

        @Test
        fun `the framing is not something a decoder strips itself`() {
            // Handed the whole line, the registry finds `N` at the head - not a command letter -
            // and answers null. Going through the liner is the reading path, not a convenience.
            assertNull(decode(framed("M104 S200")))
        }

        @Test
        fun `the checksum ends the payload`() {
            // spec 5 puts the `*` field last and Marlin terminates the command there, so what
            // follows the marker is outside the frame: `G1 X5` here is not a second command.
            assertEquals(AutoHome(), decodeLine("N1 G28*18 G1 X5"))
        }

        @Test
        fun `M110's argument is a line number in second position`() {
            // The one command whose parameter is an `N`: spec 7.1 makes only the *first* field of
            // a line a line number, which is why the parser cannot skip every `N` it meets.
            assertEquals(SetGetLineNumber(line = 7), decode("M110 N7"))
        }

        @Test
        fun `a line that commands nothing decodes to nothing`() {
            assertNull(decodeLine("; only a comment"))
            assertNull(decodeLine("   "))
            assertNull(MarlinCommands.decode(emptyList()))
        }
    }

    @Nested
    inner class Resolution {

        @Test
        fun `a head no Marlin command claims does not decode`() {
            assertNull(decode("M998"))
            assertNull(decode("G199 X1"))
        }

        @Test
        fun `matching is on the head as written`() {
            // The lexeme is part of a number's identity here, so `M0105` is not `M105`.
            assertNull(decode("M0105 S1"))
            // Spacing is not part of it: spec 2.1 lets `M 104` be one word.
            assertEquals(SetHotendTemperature(temp = BigDecimal("200")), decode("M 104 S200"))
        }

        @Test
        fun `T heads a command at the start of a line and is a parameter after one`() {
            // spec 4.1 and 4.2 both claim `T`. It heads a command only where none has started,
            // which is exactly the position a line opens in...
            assertEquals(SelectOrReportToolT0(feedrate = BigDecimal("1000")), decode("T0 F1000"))
            // ...and is an ordinary parameter elsewhere, which is what the corpus's 40 other uses
            // of it are.
            assertEquals(SetHotendTemperature(t = 1), decode("M104 T1"))
        }

        @Test
        fun `an ambiguous head resolves to the first class that claims it`() {
            // Marlin documents six G29 pages, one per bed-leveling system, and which one a printer
            // means depends on how its firmware was compiled - nothing in the line can say. The
            // registry keeps the first and says so; build with the variant you mean.
            assertTrue(MarlinCommands.ambiguousCodes.contains("G29"))
            assertInstanceOf(BedLeveling3Point::class.java, decode("G29"))
            assertEquals("G29", encode(decode("G29")!!))
        }

        @Test
        fun `a command letter is case-insensitive`() {
            // spec 2.2, the same rule the parameter letters follow (see Fields). It is the head's
            // *letter* that folds, through `headKey`; the number keeps its lexeme,
            // which is the assertion above.
            assertEquals(SetHotendTemperature(temp = BigDecimal("200")), decode("m104 s200"))
            assertEquals(AutoHome(x = true), decode("g28 X"))
            assertEquals(SelectOrReportToolT0(), decode("t0"))
            // Through a class's own decoder as well, which matches heads for itself.
            assertEquals(AutoHome(x = true), AutoHome.decode(GTokenizer.parse("g28 x").toList()))
        }

        @Test
        fun `a decoder answers only for its own head`() {
            assertEquals(AutoHome(x = true), AutoHome.decode(GTokenizer.parse("G28 X").toList()))
            assertNull(AutoHome.decode(GTokenizer.parse("M104 S200").toList()))
            assertNull(Dwell.decode(GTokenizer.parse("G28").toList()))
        }
    }

    @Nested
    inner class RoundTrip {

        /**
         * Lines as a printer would receive them, re-emitted byte for byte.
         *
         * Each is already canonical - one space between fields, the digits the encoder would
         * choose - so a difference here is a decoder that dropped or moved something, not the
         * encoder's spacing rule. Non-canonical input is [Limits]' business.
         */
        @ParameterizedTest
        @ValueSource(
            strings = [
                "G1 X10.50 Y-3 F1500",
                "G1 E-5.2",
                "G4 P500",
                "G28 X Y",
                "M104 S200 T1",
                "M110 N7",
                "M114",
                "M20 F",
                "M84 S60 X Y",
                "M92 X80.00 Y80.00 Z400.00 E93.00",
                "M117 Hello World",
                "M220 S50",
                "M301 P22.2 I1.08 D114",
                "M550 P\"My Printer\"",
                "M810 G0 X0 Y0|G0 Z10",
                "T0 F1000",
            ]
        )
        fun `a real line decodes and re-emits unchanged`(line: String) {
            val decoded = decode(line)
            assertNotNull(decoded) { "no command class decoded $line" }
            assertEquals(line, encode(decoded!!))
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "M104 S200 T1",
                "G1 X10.50 F1500",
                "G28 X Y",
                "M117 Hello World",
                "M550 My Printer",
            ]
        )
        fun `what a line decodes to decodes again the same`(line: String) {
            // Idempotence, which is what a host relies on when it re-reads its own output: after
            // the first pass, decode-encode-decode must not drift.
            val once = decode(line)!!
            assertEquals(once, decode(encode(once)))
        }
    }

    /**
     * What the reading side does **not** carry. Each is a consequence of a decision recorded
     * elsewhere, and each is pinned so that changing it is a choice rather than an accident.
     */
    @Nested
    inner class Limits {

        @Test
        fun `a non-canonical number comes back canonical`() {
            // A command class holds a BigDecimal, not the lexeme it was read from, so a sender's
            // digits survive only where they are significant: `10.50` keeps its scale, `+5`, `.5`
            // and `1.` do not keep their spelling. Reproducing *bytes* is the token layer's job
            // (`GLine.raw`), not this one's.
            assertEquals("G1 X5 Y0.5 Z1", encode(decode("G1 X+5 Y.5 Z1.")!!))
        }

        @Test
        fun `a valued parameter sent bare is read as absent`() {
            // The one place the module loses bytes it lexed correctly, recorded in spec Appendix
            // B.3: `M104 F` has `F` present and carrying nothing, and a `BigDecimal?` property has
            // no third state between "absent" and "present without a value". `hasWord()` is the
            // only accessor that can see the difference, so a parameter modelled as a flag is
            // unaffected - which is the case below.
            assertEquals(SetHotendTemperature(), decode("M104 F"))
            assertEquals("M104", encode(decode("M104 F")!!))
        }

        @Test
        fun `a flag keeps its presence when it carries a value it cannot hold`() {
            // `G28 X10`: the model has one `Boolean` for `X`, so "present" is all it can say -
            // which is the better end of the trade above, since the letter is not lost.
            assertEquals("G28 X", encode(decode("G28 X10")!!))
        }

        @Test
        fun `the registry reads the first command on a line and no more`() {
            // `G53 G0 X0` is two commands (spec 5): `G53` is a modal prefix and `X0` belongs to
            // the `G0`. The registry decodes from one head, so a caller that needs both splits the
            // line with `GWordReader` first - a reader that does not need the command number.
            assertInstanceOf(MoveInMachineCoordinates::class.java, decode("G53 G0 X0"))
        }
    }
}
