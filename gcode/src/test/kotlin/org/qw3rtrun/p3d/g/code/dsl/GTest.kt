package org.qw3rtrun.p3d.g.code.dsl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.qw3rtrun.p3d.g.code.core.Crc16CheckSum
import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.code.core.token.*
import java.math.BigDecimal

/**
 * The command-building DSL.
 *
 * The rule it exists to satisfy: **any valid G-code can be written with it**. Most of these tests
 * are therefore one assertion each, naming a shape GCODE_spec.md allows and pinning the bytes it
 * produces. `GDslCorpusTest` turns the rule itself into a number.
 */
class GTest {

    private fun enc(c: GCommand) = GEncoder.encode(c)
    private fun enc(b: GBlock) = GEncoder.encode(b)

    @Nested
    inner class Commands {

        @Test
        fun `a bare command`() {
            assertEquals("G28", enc(G(28)))
            assertEquals("M105", enc(M(105)))
            assertEquals("T0", enc(T(0)))
        }

        @Test
        fun `parameters follow the command`() {
            assertEquals("G1 X10 F1800", enc(G(1, X(10), F(1800))))
        }

        @Test
        fun `a decimal keeps the digits it was written with`() {
            // spec 3.1 and GNumber: the lexeme is the value's identity, so X10.50 must not become
            // X10.5 - the bytes differ and so would the checksum.
            assertEquals("G1 X10.50", enc(G(1, X("10.50"))))
            assertEquals("G1 X10.5", enc(G(1, X(BigDecimal("10.5")))))
            assertEquals("G1 X.5 Y1. Z+3", enc(G(1, X(".5"), Y("1."), Z("+3"))))
        }

        @Test
        fun `a flag parameter carries no value`() {
            // spec 3.2, and 226 of the corpus's parameter words are these.
            assertEquals("G28 X Y", enc(G(28, X, Y)))
        }

        @Test
        fun `a subcode is written through the lexeme overload`() {
            // spec 4.1: `G29.1` is one command word whose value is a decimal.
            assertEquals("G29.1", enc(G("29.1")))
            assertEquals("M0.1 S1", enc(M("0.1", S(1))))
        }

        @Test
        fun `leading zeros survive`() {
            // spec 4.1: G1 = G01 = G001 in meaning, but not in bytes, and the checksum follows bytes.
            assertEquals("G01", enc(G("01")))
            assertEquals("G001 X010", enc(G("001", X("010"))))
        }

        @Test
        fun `any command letter can be written`() {
            assertEquals("G1 X10", enc(command('G', 1, X(10))))
            // spec 2.2: the dialects are case-insensitive, so a lowercase command is valid G-code
            // and the DSL has to be able to say it.
            assertEquals("g1", enc(command('g', 1)))
        }

        @Test
        fun `T is a command and its parameter form is spelled out`() {
            // spec 4.1 makes `T` a command letter; GCommandParser's note records 40 corpus lines
            // using it as a *parameter* of a G command instead. A Kotlin name cannot return both,
            // so the command reading wins - matching the parser - and the parameter is spelled out.
            assertEquals("T0", enc(T(0)))
            assertEquals("M105 T1", enc(M(105, word('T', 1))))
            assertEquals("G29 T", enc(G(29, flag('T'))))
        }

        @Test
        fun `D has no builder of its own, and that mirrors the parser`() {
            // spec 4.1 lists `D` as a Marlin debug command; spec 4.2 also lists it as a parameter
            // letter (diameter, PID D). GCommandParser resolves that clash in favour of the
            // parameter reading, because it is the common one - so `D(3)` here is the *parameter*
            // word, and a D command has to be spelled out.
            assertEquals("D3", enc(GCommand(GLetter('D'), GInt(3))))
            assertEquals("G1 D3", enc(G(1, D(3))))
            assertEquals("D3", enc(command('D', 3)))

            // ...and the asymmetry is real rather than hidden: what the DSL writes as a D *command*,
            // the parser reads back as a parameter with no command at all. That is the spec's own
            // ambiguity, not a bug in either half.
            val line = GSemanticParser(GTokenizer().parse("D3").iterator()).next()
            assertEquals(emptyList<GCommand>(), GCommandParser().parse(line))
        }

        @Test
        fun `a quoted string parameter`() {
            // spec 3.4, with the RepRapFirmware doubling rule on the way out.
            assertEquals("M117 S\"Hello!\"", enc(M(117, S(text("Hello!")))))
            assertEquals("M117 S\"say \"\"hi\"\"\"", enc(M(117, S(text("say \"hi\"")))))
        }

        @Test
        fun `an expression parameter`() {
            // spec 3.5.
            assertEquals("M140 S{bed[0]}", enc(M(140, S(expr("bed[0]")))))
        }

        @Test
        fun `a parameter letter outside the conventional table`() {
            // spec 4.2's table is convention, not a closed set.
            assertEquals("G1 O5", enc(G(1, word('O', 5))))
            assertEquals("G28 O", enc(G(28, flag('O'))))
        }

        @Test
        fun `a command number the parser would refuse is refused here too`() {
            // spec 4.1: `<unsigned-int>` with an optional subcode. The builder shares the parser's
            // rule, so the DSL cannot produce a command that will not parse back.
            assertThrows(IllegalArgumentException::class.java) { G("-1") }
            assertThrows(IllegalArgumentException::class.java) { G("29.") }
            assertThrows(IllegalArgumentException::class.java) { G(".1") }
            assertThrows(IllegalArgumentException::class.java) { G("abc") }
        }

        @Test
        fun `a value that is not a number is refused`() {
            assertThrows(IllegalArgumentException::class.java) { X("abc") }
            assertThrows(IllegalArgumentException::class.java) { X("1.2.3") }
            assertThrows(IllegalArgumentException::class.java) { X("") }
            assertThrows(IllegalArgumentException::class.java) { word('1', 5) }
        }
    }

    @Nested
    inner class Lines {

        @Test
        fun `a command becomes a line`() {
            assertEquals("G28", enc(G(28).line()))
        }

        @Test
        fun `a trailing comment`() {
            // spec 6, and 125 of the corpus's 303 non-blank lines carry one. Without this the DSL
            // cannot express 41% of real G-code.
            assertEquals("G1 X10 ; move", enc(G(1, X(10)) comment " move"))
        }

        @Test
        fun `a comment-only line`() {
            assertEquals("; LAYER:42", enc(commentLine(" LAYER:42")))
        }

        @Test
        fun `an inline comment`() {
            assertEquals("G28 (homing)", enc(block(G(28), inlineComment("homing"))))
        }

        @Test
        fun `a comment between two commands`() {
            // Not common, but spec 5 allows it and the rule says *any* valid G-code.
            assertEquals("G53 (machine) G0 X0", enc(block(G(53), inlineComment("machine"), G(0, X(0)))))
        }

        @Test
        fun `several commands on one line`() {
            // spec 4.3 calls this non-portable and says generators should not emit it - but it is
            // valid and the corpus contains three, so the DSL has to be able to say it.
            assertEquals("G53 G0 X0 Y0 Z0", enc(block(G(53), G(0, X(0), Y(0), Z(0)))))
        }
    }

    @Nested
    inner class Framing {

        @Test
        fun `a line frames into a packet`() {
            assertEquals("N3 T0*57", GEncoder.frame(3, T(0).line()))
            assertEquals("N1 G28*14291", GEncoder.frame(1, G(28).line(), Crc16CheckSum()))
        }

        @Test
        fun `a trailing comment sits after the checksum and is not covered by it`() {
            // spec 5 puts the `*` field last before any comment; spec 8.3 excludes what follows it.
            // So this line's checksum is the one `N1 G28` alone produces.
            assertEquals("N1 G28*18 ; homing", GEncoder.frame(1, G(28) comment " homing"))
        }

        @Test
        fun `a comment among the commands is covered`() {
            // It is transmitted before the `*`, so it is part of the checksummed bytes - which is
            // why the value here is not 18.
            val framed = GEncoder.frame(1, block(G(28), inlineComment("x"), G(90)))

            assertTrue(framed.startsWith("N1 G28 (x) G90*")) { framed }
            assertFalse(framed.endsWith("*18"))
        }

        @Test
        fun `everything the DSL frames parses back as a verified packet`() {
            val blocks = listOf(
                G(28).line(),
                G(1, X("10.5"), F(1800)).line(),
                G(28, X, Y).line(),
                M(117, S(text("Hello"))).line(),
                G(1, X(10)) comment " move"
            )
            val tokenizer = GTokenizer()

            for (i in blocks.indices) {
                val framed = GEncoder.frame(i + 1, blocks[i])
                val line = GSemanticParser(tokenizer.parse(framed).iterator()).next()

                assertInstanceOf(GPacketLine::class.java, line) { "did not verify: $framed" }
            }
        }
    }

    @Nested
    inner class TheSink {

        private val sent = mutableListOf<String>()
        private val g = GSender { sent.add(it) }

        @Test
        fun `a builder returns a value and the sink is separate`() {
            // The old facade took the sink in its constructor and returned Unit, so a command could
            // not be built and inspected - GTest had to keep a mutable list to assert anything.
            val command = G(28)

            assertEquals("G28", enc(command))
            assertEquals(emptyList<String>(), sent)

            g.send(command)
            assertEquals(listOf("G28"), sent)
        }

        @Test
        fun `the named operations of the old Java facade still work`() {
            g.m115()
            g.m105()
            g.m105(1)
            g.m155(2)
            g.m140(BigDecimal("60"))

            assertEquals(listOf("M115", "M105", "M105 T1", "M155 S2", "M140 S60"), sent)
        }

        @Test
        fun `the aliases agree with the numbered operations`() {
            g.firmwareInfo()
            g.m115()
            g.tempReport(1)
            g.m105(1)
            g.autoReportTemp(2)
            g.m155(2)
            g.setBedTemperature(BigDecimal("60"))
            g.m140(BigDecimal("60"))

            assertEquals(sent[0], sent[1])
            assertEquals(sent[2], sent[3])
            assertEquals(sent[4], sent[5])
            assertEquals(sent[6], sent[7])
        }
    }
}
