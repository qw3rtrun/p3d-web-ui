package org.qw3rtrun.p3d.g.code.core.block

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.qw3rtrun.p3d.g.code.core.GEncoder
import java.io.File

/**
 * A snapshot of what the corpora *mean*, block by block: the kind of block the reader decided on, the
 * framing fields it read off it, and the commands the command parser found, rendered canonically by
 * [GEncoder].
 *
 * It exists as a net under structural change. The reader's own tests assert the shape of the values
 * it builds, so a refactor that changes those values has to edit them - and an edited test cannot
 * tell you whether the behaviour survived. This one is written in terms nothing structural can
 * touch: block *kinds*, block *numbers*, checksum *lexemes* and encoded *commands*, over 440 blocks of
 * real G-code. Any refactor of the block or word model that leaves this green preserved the parse,
 * and any diff here is a behaviour change to be explained rather than accepted.
 *
 * **Regenerating**: delete the snapshot under `src/test/resources` and run this test once. It writes
 * the file and fails, so a regeneration is always a deliberate act with a diff to review - never
 * something that quietly happens on a green build.
 */
class GCorpusDecompositionTest {



    /**
     * One entry per parsed block: `<kind>\t<line number>\t<checksum lexeme>\t<commands>`, the two
     * framing fields as `-` when the block carries none, and the commands `' | '`-separated.
     */
    private fun decompose(gcode: String): List<String> =
        GBlockReader.blocks(gcode).map { block ->
            val kind = block.javaClass.simpleName
            val number = if (block is GOrdered) block.number.lexeme else "-"
            val checksum = if (block is GCheckSumControlled) block.checksum.lexeme else "-"
            val parsed = GWordReader.parse(block).joinToString(" | ") { GEncoder.encode(it) }
            "$kind\t$number\t$checksum\t$parsed"
        }.toList()

    private fun fixture(name: String): String = requireNotNull(javaClass.getResourceAsStream(name)) {
        "$name fixture is missing from the test resources"
    }.readBytes().decodeToString()

    /**
     * Compares [actual] against the snapshot resource [name], writing it and failing when there is
     * no snapshot yet.
     */
    private fun assertMatchesSnapshot(name: String, actual: List<String>) {
        val golden = javaClass.getResourceAsStream(name)?.readBytes()?.decodeToString()
            ?: run {
                File("src/test/resources$name").writeText(actual.joinToString("\n", postfix = "\n"))
                fail("snapshot $name was missing and has been written from the current parse - review it and rerun")
            }

        val expected = golden.lines().let { if (it.last().isEmpty()) it.dropLast(1) else it }

        assertEquals(expected.size, actual.size) { "$name: the corpus parsed into a different number of blocks" }
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i]) { "$name: block ${i + 1} decomposes differently" }
        }
    }

    @Nested
    inner class FileCorpus {

        private val actual = decompose(fixture("/marlin.gcode"))

        @Test
        fun `the corpus decomposition matches the snapshot`() {
            assertMatchesSnapshot("/marlin.decomposition.txt", actual)
        }

        @Test
        fun `the corpus exercises the block kinds a file contains`() {
            val kinds = actual.map { it.substringBefore('\t') }.toSet()

            // A file, so it carries no framed: these three are what a slicer emits.
            assertTrue("GPlainBlock" in kinds) { "no ordinary command line in the corpus" }
            assertTrue("GMeaninglessBlock" in kinds) { "no blank or comment-only block in the corpus" }
            assertTrue("GMissingChecksum" in kinds) { "no numbered block without a checksum in the corpus" }
        }
    }

    /**
     * The framed corpus, which is where this net earns its keep: the line number, the checksum
     * field and the body the commands are read from are exactly what a token-level block model has
     * to get right, and `marlin-framed.gcode` carries all three over both checksum algorithms.
     */
    @Nested
    inner class FramedCorpus {

        private val actual = decompose(fixture("/marlin-framed.gcode"))

        @Test
        fun `the framed decomposition matches the snapshot`() {
            assertMatchesSnapshot("/marlin-framed.decomposition.txt", actual)
        }

        @Test
        fun `the framed corpus is framed and verified`() {
            val kinds = actual.map { it.substringBefore('\t') }.toSet()

            assertTrue("GFramedBlock" in kinds) { "no verified framed block in the framed corpus" }
        }

        @Test
        fun `the framed corpus carries both checksum widths`() {
            val widths = actual.filter { it.substringBefore('\t') == "GFramedBlock" }
                .map { it.split('\t')[2].length }
                .toSet()

            assertTrue(widths.any { it <= 3 }) { "no xor checksum in the framed corpus" }
            assertTrue(5 in widths) { "no crc16 checksum in the framed corpus" }
        }
    }

    /**
     * The shapes no real corpus contains: every pairing and field-syntax fault of
     * [GCODE_spec.md](../../../../../../../../../../doc/specs/GCODE_spec.md) sections 7.3, 8.1 and
     * 8.5, plus the positional oddities the reader is documented to tolerate. One block each, in the
     * order of the table in Appendix B.3, so the snapshot reads as that table's executable form.
     */
    @Nested
    inner class EdgeCases {

        private val gcode = """
            N1 G28*18
             N1 G28*18
            (c)N1 G28*18
            n1 g28*18
            N1 M110 N7*123
            N2 G1 X10 *115
            N1 G28*19
            N1 G28
            G28*18
            *12
            * 12
            *
            *ABC
            N*
            NX*12
            N1 G28*
            N1*
            N1 G28*X
            N1 G28*10.5
            N1 G28*1234
            N1 G28*12*59
            N1 G28*18 G1 X5
            G28 ; not a *12 marker
            G28 G1 X5
            M117 Hello World
            G29.1 T
            T0 X1
            G53 G0 X0
            ; comment only

            ?
        """.trimIndent() + "\n"

        @Test
        fun `the edge cases decompose as the snapshot records`() {
            assertMatchesSnapshot("/edge-cases.decomposition.txt", decompose(gcode))
        }

        @Test
        fun `the edge cases exercise every block kind the reader can produce`() {
            val kinds = decompose(gcode).map { it.substringBefore('\t') }.toSet()

            // This is every kind there is: a block with no identifier is a GMeaninglessBlock, not
            // an error, and the error type that used to claim otherwise was never produced.
            val expected = setOf(
                "GFramedBlock", "GPlainBlock", "GMeaninglessBlock", "GCheckSumFailedBlock",
                "GMissingChecksum", "GMissingLineNumber", "GMalformedLineNumber", "GMalformedChecksum",
            )
            assertEquals(expected, kinds.intersect(expected), "a block kind lost its example")
        }
    }
}
