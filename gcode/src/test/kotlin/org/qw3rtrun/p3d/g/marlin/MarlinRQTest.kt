package org.qw3rtrun.p3d.g.marlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.code.core.token.GTokenizer
import org.qw3rtrun.p3d.g.marlin.command.*
import java.math.BigDecimal

/**
 * Hand-written cover for the shape every generated command class has - `MarlinGRQ.kt`,
 * `MarlinMRQ.kt` and `MarlinTRQ.kt` - using `M105` as the case in point, plus the `MarlinG`
 * shortcuts from `MarlinRQ.kt`.
 *
 * `MarlinCommandsTest` is generated and sweeps all 295 commands; this one is written out so that
 * the contract is stated in prose somewhere a reader will find it, and so the two bugs the
 * original reference had can never come back.
 */
class MarlinRQTest {

    private val tokenizer = GTokenizer()

    private fun enc(rq: ReportHotendTemperature) = GEncoder.encode(rq.encode())

    /**
     * Decoding starts from a line, not from a built command.
     *
     * That is the whole shape of the reading side: a decoder is handed the tokens as lexed and
     * decides for itself what they mean, because which letters are parameters at all depends on
     * the command number - and the layer that produces words does not know it. Feeding these
     * tests a `GCommand` would have let them pass over a split the tokenizer cannot make.
     */
    private fun decode(line: String) = ReportHotendTemperature.decode(tokenizer.parse(line))

    @Test
    fun `an unset parameter is not written`() {
        // The whole reason every parameter is nullable: `M105` and `M105 T0` are different
        // commands, and a bare report is the one actually sent. The first version of this class
        // took a non-null `Int` defaulting to 0 and could only ever say `M105 T0`.
        assertEquals("M105", enc(ReportHotendTemperature()))
    }

    @Test
    fun `a tool index is written as T`() {
        assertEquals("M105 T0", enc(ReportHotendTemperature(index = 0)))
        assertEquals("M105 T2", enc(ReportHotendTemperature(index = 2)))
    }

    @Test
    fun `the redundant sensor flag is written as a bare R`() {
        // Marlin documents `R` as well as `T`; the hand-written reference modelled only `T`.
        assertEquals("M105 R", enc(ReportHotendTemperature(r = true)))
        assertEquals("M105 R T1", enc(ReportHotendTemperature(r = true, index = 1)))
    }

    @Test
    fun `decoding reads the index from the T word`() {
        // Regression: this searched the params for 'M' - the command letter, which is never a
        // param - so find() returned null and the non-null cast threw for every input.
        assertEquals(
            ReportHotendTemperature(index = 2),
            decode("M105 T2"),
        )
    }

    @Test
    fun `decoding reads the R flag`() {
        assertEquals(
            ReportHotendTemperature(r = true, index = 1),
            decode("M105 R T1"),
        )
    }

    @Test
    fun `a bare M105 decodes to nothing set`() {
        assertEquals(ReportHotendTemperature(), decode("M105"))
    }

    @Test
    fun `what it encodes, it decodes`() {
        val cases = listOf(
            ReportHotendTemperature(),
            ReportHotendTemperature(index = 0),
            ReportHotendTemperature(index = 3),
            ReportHotendTemperature(r = true),
            ReportHotendTemperature(r = true, index = 2),
        )
        for (original in cases) {
            // Decoding goes through the companion, so the instance under test is only the input -
            // which is the point: one decoder per command type, not one per command built.
            assertEquals(original, decode(enc(original))) {
                "round trip failed for " + enc(original)
            }
        }
    }

    @Test
    fun `a different command does not decode`() {
        assertNull(decode("M115"))
        assertNull(decode("M155 S1"))
    }

    @Test
    fun `the head is matched on what it says, not on how it is spaced`() {
        // spec 2.1 lets a space separate a field from its value, so all three spell `M105 T2`.
        // The head the decoder compares against is canonical, which is what makes that true
        // without every decoder having to know what whitespace a line happened to carry.
        assertEquals(ReportHotendTemperature(index = 2), decode("M105 T2"))
        assertEquals(ReportHotendTemperature(index = 2), decode("M105T2"))
        assertEquals(ReportHotendTemperature(index = 2), decode("M 105 T 2"))
        // The lexeme is still part of a number's identity, so a non-canonical code is not M105.
        assertNull(decode("M0105 T2"))
    }

    @Test
    fun `toString shows the encoded command and not the token tree`() {
        // Regression: `encode()` returns a GCommand, not the String the Java record returned, so
        // interpolating it dumped the whole parse tree - and PrinterReactor logs commands.
        assertEquals(
            "ReportHotendTemperature(M105 T2)",
            ReportHotendTemperature(index = 2).toString(),
        )
    }

    @Test
    fun `the MarlinG shortcuts build the commands they name`() {
        assertEquals("M105", GEncoder.encode(MarlinG.m105().encode()))
        assertEquals("M105 T1", GEncoder.encode(MarlinG.m105(1).encode()))
        assertEquals("M115", GEncoder.encode(MarlinG.m115().encode()))
        assertEquals("M155", GEncoder.encode(MarlinG.m155().encode()))
        assertEquals("M155 S1", GEncoder.encode(MarlinG.m155(1).encode()))
        assertEquals("M140 S60.00", GEncoder.encode(MarlinG.m140(BigDecimal("60.00")).encode()))
        assertEquals(
            "M104 S200 T1",
            GEncoder.encode(MarlinG.m104(BigDecimal("200"), 1).encode()),
        )
    }

    @Test
    fun `the aliases agree with the numbered shortcuts`() {
        assertEquals(MarlinG.m105(1), MarlinG.tempReport(1))
        assertEquals(MarlinG.m115(), MarlinG.firmwareInfo())
        assertEquals(MarlinG.m155(2), MarlinG.autoReportTemp(2))
        assertEquals(MarlinG.m140(BigDecimal("60")), MarlinG.setBedTemperature(BigDecimal("60")))
        assertEquals(
            MarlinG.m104(BigDecimal("200"), 1),
            MarlinG.setHotendTemperature(BigDecimal("200"), 1),
        )
    }
}
