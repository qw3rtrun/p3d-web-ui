package org.qw3rtrun.p3d.g.code.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.code.core.token.*

/**
 * Tests for the encoder: GCODE_spec.md section 4 for a command's text, sections 7 and 8 for the
 * framing around it.
 *
 * The rule the whole thing turns on is [spec 8.3]: the checksum covers the bytes as transmitted, so
 * the encoder has to build the final byte string and only then checksum it. That is why these
 * assertions are on strings rather than on token lists - a token list has no whitespace in it, and
 * whitespace changes the answer.
 */
class GEncoderTest {

    private fun cmd(letter: Char, number: Int, vararg params: GWord) =
        GCommand(GLetter(letter), GInt(number), params.toList())

    @Nested
    inner class Commands {

        @Test
        fun `a bare command is its letter and its number`() {
            assertEquals("G28", GEncoder.encode(cmd('G', 28)))
            assertEquals("T0", GEncoder.encode(cmd('T', 0)))
            assertEquals("M105", GEncoder.encode(cmd('M', 105)))
        }

        @Test
        fun `parameters are separated from the command and from each other`() {
            assertEquals("G1 X10 F1800", GEncoder.encode(
                cmd('G', 1, GParameterWord(GLetter('X'), GInt(10)), GParameterWord(GLetter('F'), GInt(1800)))))
        }

        @Test
        fun `adjacent words never fuse`() {
            // Finding 1.10: `GCommand.print()` concatenated rawText() with no separator, so the
            // bytes it produced depended on the values being self-delimiting. The exact reproducer
            // from that finding - a command built from a bare list of values - stopped compiling
            // when 03 gave GCommand its head/params shape, so this is the live equivalent: two
            // number-bearing words in a row, asserted to stay two words.
            val encoded = GEncoder.encode(
                cmd('G', 1, GParameterWord(GLetter('X'), GInt(2))))

            assertEquals("G1 X2", encoded)
            assert(!encoded.contains("G1X2"))
        }

        @Test
        fun `a flag parameter carries no value`() {
            assertEquals("G28 X Y", GEncoder.encode(
                cmd('G', 28, GFlagWord(GLetter('X')), GFlagWord(GLetter('Y')))))
        }

        @Test
        fun `a subcode is part of the command word`() {
            // spec 4.1: `G29.1` is one word whose value is a decimal, and the lexeme is what keeps
            // it from re-emitting as `29` followed by `.1`.
            val command = GCommand(GParameterWord(GLetter('G'), GFloat("29.1")))

            assertEquals("G29.1", GEncoder.encode(command))
        }

        @Test
        fun `a decimal parameter keeps the digits it was given`() {
            // The lexeme is the value's identity (see GNumber): an encoder that re-rendered from
            // the number would turn `X10.50` into `X10.5` and change the checksum with it.
            assertEquals("G1 X10.50", GEncoder.encode(
                cmd('G', 1, GParameterWord(GLetter('X'), GFloat("10.50")))))
        }

        @Test
        fun `a quoted string parameter is re-quoted`() {
            assertEquals("M117 S\"Hello!\"", GEncoder.encode(
                cmd('M', 117, GParameterWord(GLetter('S'), GQuotedString("Hello!")))))
        }

        @Test
        fun `encoding is canonical and does not inherit the spacing a line was parsed with`() {
            // A parsed word carries its original bytes in `raw` - `X  10` is one word with two
            // spaces inside it. The encoder renders the field, not the bytes it came from, so that
            // what it emits is always the same shape and can be checksummed predictably.
            val parsed = GSemanticParser(GTokenizer().parse("G1  X  10").iterator()).next()
            val command = GCommandParser().parse(parsed).single()

            assertEquals("G1 X10", GEncoder.encode(command))
        }
    }

    @Nested
    inner class Framing {

        @Test
        fun `the spec worked example round-trips both ways`() {
            // spec 8.3's example, in both directions: encoding T0 as line 3 yields N3 T0*57, and
            // parsing N3 T0*57 yields a verified packet.
            val encoded = GEncoder.frame(3, cmd('T', 0))

            assertEquals("N3 T0*57", encoded)
            assertInstanceOf(
                GPacketLine::class.java,
                GSemanticParser(GTokenizer().parse(encoded).iterator()).next()
            )
        }

        @Test
        fun `the line number prefix is inside the checksum`() {
            // spec 8.3: the N and its digits are covered, so the checksum must be computed after
            // the prefix is prepended, not over the payload alone.
            assertEquals("N1 G28*18", GEncoder.frame(1, cmd('G', 28)))
            assertEquals("N2 G28*17", GEncoder.frame(2, cmd('G', 28)))
        }

        @Test
        fun `there is no space before the marker`() {
            // spec 8.3: the convention, and it is a byte decision - a space before `*` would be
            // covered and would change the value.
            val framed = GEncoder.frame(1, cmd('G', 28))

            assertEquals("N1 G28*18", framed)
            assert(!framed.contains(" *"))
        }

        @Test
        fun `a crc can be asked for instead`() {
            // spec 8.4, same bytes, the other algorithm - and five digits, zero-padded.
            assertEquals("N3 T0*06939", GEncoder.frame(3, cmd('T', 0), Crc16CheckSum()))
            assertEquals("N1 G28*14291", GEncoder.frame(1, cmd('G', 28), Crc16CheckSum()))
        }

        @Test
        fun `everything the encoder frames parses back as a verified packet`() {
            // The property that matters: encode then parse is the identity on commands, and the
            // checksum the encoder computed is the one the verifier recomputes. If the encoder and
            // the verifier ever disagree about the covered byte range, this is what says so.
            val commands = listOf(
                cmd('G', 28),
                cmd('T', 0),
                cmd('G', 1, GParameterWord(GLetter('X'), GFloat("10.5")), GParameterWord(GLetter('F'), GInt(1800))),
                cmd('G', 28, GFlagWord(GLetter('X')), GFlagWord(GLetter('Y'))),
                cmd('M', 117, GParameterWord(GLetter('S'), GQuotedString("Hello World!")))
            )

            for (number in commands.indices) {
                for (calculator in listOf(XorCheckSum(), Crc16CheckSum())) {
                    val command = commands[number]
                    val framed = GEncoder.frame(number, command, calculator)
                    val line = GSemanticParser(GTokenizer().parse(framed).iterator()).next()

                    assertInstanceOf(GPacketLine::class.java, line, "did not verify: $framed")
                    assertEquals(GInt(number), (line as GPacketLine).number)
                    assertEquals(
                        GEncoder.encode(command),
                        GCommandParser().parse(line).joinToString(" ") { GEncoder.encode(it) },
                        "command did not survive: $framed"
                    )
                }
            }
        }

        @Test
        fun `parsing then encoding is the identity on bytes the encoder could have produced`() {
            for (gcode in listOf("N0 G28*19", "N1 G28*18", "N3 T0*57", "N7 G1 X10 F1800*57")) {
                val line = GSemanticParser(GTokenizer().parse(gcode).iterator()).next()

                assertInstanceOf(GPacketLine::class.java, line, "fixture is not a packet: $gcode")
                val packet = line as GPacketLine
                val command = GCommandParser().parse(packet).single()

                assertEquals(gcode, GEncoder.frame(packet.number.int, command))
            }
        }

        @Test
        fun `a one byte change anywhere before the marker is caught`() {
            val good = GEncoder.frame(1, cmd('G', 28))

            for (i in 0 until good.indexOf('*')) {
                val corrupted = good.substring(0, i) + corrupt(good[i]) + good.substring(i + 1)
                val line = GSemanticParser(GTokenizer().parse(corrupted).iterator()).next()

                assert(line !is GPacketLine) { "corruption at $i survived verification: $corrupted" }
            }
        }

        /** Flips a character to another legal one, so the line stays lexically well formed. */
        private fun corrupt(ch: Char): Char = when {
            ch in '0'..'8' -> ch + 1
            ch == '9' -> '0'
            ch == ' ' -> '\t'
            ch in 'A'..'Y' -> ch + 1
            else -> 'Z'
        }
    }
}
