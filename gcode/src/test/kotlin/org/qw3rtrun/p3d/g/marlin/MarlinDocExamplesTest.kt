package org.qw3rtrun.p3d.g.marlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.code.core.token.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GWordReader
import org.qw3rtrun.p3d.g.code.core.token.GLetter
import org.qw3rtrun.p3d.g.code.core.token.GLiner
import org.qw3rtrun.p3d.g.code.core.token.GSpace
import org.qw3rtrun.p3d.g.code.core.token.GToken
import org.qw3rtrun.p3d.g.code.core.token.GTokenizer

/**
 * The independent check on [MarlinCommands].
 *
 * `MarlinCommandsTest` is generated from the same data as the classes it tests, so it can only
 * prove that encoding and decoding agree with *each other* - a parameter letter extracted wrongly
 * would be wrong on both sides and the test would still pass. This one is different:
 * `marlin-doc-examples.txt` holds the example command lines **Marlin's own authors wrote** on the
 * command reference pages, so a letter that appears there and in no command class is a real gap.
 *
 * It found four things when it was first run, and each is pinned below rather than smoothed over.
 */
class MarlinDocExamplesTest {


    private val examples: List<String> =
        requireNotNull(javaClass.getResourceAsStream("/marlin-doc-examples.txt")) {
            "marlin-doc-examples.txt is missing from the test resources"
        }.readBytes().decodeToString()
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun parse(line: String) =
        GTokenizer.lines(line)
            .flatMap { GWordReader.parse(it).asSequence() }
            .toList()

    private fun codeOf(line: String) = line.trim().split(Regex("\\s+")).first().uppercase()

    /**
     * The code of a *parsed* command, from its own head.
     *
     * Not the first word of the line: `G53 G0 X0 Y0 Z0` is two commands, `G53` being a modal
     * prefix, and `X0 Y0 Z0` belong to the `G0`. Reading the code off the line instead of off each
     * command is what made this test blame G53 for parameters it never had.
     */
    private fun codeOf(cmd: GCommand): String? {
        val letter = (cmd.head.id as? GLetter)?.letter?.uppercaseChar() ?: return null
        return letter + cmd.head.value.lexeme
    }

    /**
     * A parsed command's parameters back as tokens - what a decoder is handed.
     *
     * Rebuilt from each word's `raw` rather than from the encoder, so a doc line's own spelling
     * reaches the decoder unchanged; the separator the parser dropped between two words is put
     * back, because a command whose argument runs to the end of the line can see it.
     */
    private fun paramTokens(cmd: GCommand): List<GToken> =
        cmd.params.flatMap { listOf(GSpace) + it.raw }

    /**
     * Examples whose argument is a rest-of-line string, not lettered parameters.
     *
     * `M117 Hello World` has no parameters at all - the text *is* the argument - and `M32`'s
     * example wraps a filename in `!...#`. The *decoder* reads these now (spec 3.4a, through
     * `stringArg()`), but the lexer still cannot: the command number is what says where the string
     * starts, so at this level they tokenize letter by letter into words that were never
     * parameters, and the letters read off them are meaningless. A named list, not a silent catch.
     */
    private val restOfLineArgument = setOf(
        "M0", "M1", "M16", "M23", "M28", "M30", "M32", "M33", "M75", "M117", "M118", "M550",
        "M928",
        // M810-M819 store a macro whose argument is itself a run of G-code:
        // `M815 G0 X0 Y0|G0 Z10|M300 S440 P50`.
        "M810", "M811", "M812", "M813", "M814", "M815", "M816", "M817", "M818", "M819",
    )

    /**
     * Letters a doc *example* uses that the doc's own **parameter list omits**, so they are absent
     * from `commands.json` for the honest reason that upstream does not document them.
     *
     * Verified by reading the pages: `M569.md` lists X Y Z E I T and no `S`; `M123.md` and
     * `M672.md` list no parameters at all. Report these upstream rather than inventing them here -
     * guessing a letter's type from an example is how a builder starts emitting commands no
     * firmware accepts.
     */
    private val undocumentedUpstream = mapOf(
        "M569" to setOf('S'),
        "M123" to setOf('S'),
        "M672" to setOf('S'),
        // `M919.md` lists X Y Z A B C U V W I O P S T but not `E`, which its own example uses.
        "M919" to setOf('E'),
        // `M43 L` and `M43 R` are documented on `M043-T.md`, a page whose `codes:` entry is the
        // string `M43 T` - not a letter-plus-number command, so the extractor cannot attach its
        // parameters to M43. The pin-watch sub-mode is effectively undocumented here.
        "M43" to setOf('L', 'R'),
    )

    /**
     * `G92.1` is used in `G092.md`'s examples but the page's `codes:` list is just `[ G92 ]`, so
     * no class is generated for the subcode. The DSL can still write it - `command('G', "92.1")` -
     * there is simply no named command for it.
     */
    private val undocumentedCodes = setOf("G92.1")

    @Test
    fun `the fixture is the size the extractor reported`() {
        assertEquals(302, examples.size)
    }

    /**
     * The real completeness assertion: every parameter letter in a Marlin doc example is a letter
     * some class for that code has a property for.
     */
    @Test
    fun `every parameter letter in a doc example is modelled`() {
        val missing = ArrayList<String>()

        for (line in examples) {
            if (codeOf(line) in restOfLineArgument || codeOf(line) in undocumentedCodes) continue

            for (cmd in parse(line)) {
                val code = codeOf(cmd) ?: continue
                if (code in restOfLineArgument || code in undocumentedCodes) continue

                val known = MarlinCommands.info.filter { it.code == code }
                if (known.isEmpty()) {
                    missing.add("$line  ->  no command class for $code")
                    continue
                }
                // A code can be documented as several variants (six G29 pages, one per
                // bed-leveling system); an example belongs to one of them, so the union is the
                // right measure of what is modelled.
                val modelled = known.flatMap { it.letters }.toSet() +
                    (undocumentedUpstream[code] ?: emptySet())

                for (word in cmd.params) {
                    val letter = (word.id as? GLetter)?.letter?.uppercaseChar() ?: continue
                    if (letter !in modelled) {
                        missing.add(
                            "$line  ->  $code has no property for '$letter' " +
                                "(modelled: ${modelled.sorted()})"
                        )
                    }
                }
            }
        }

        assertEquals(emptyList<String>(), missing) { "${missing.size} doc examples use an unmodelled letter" }
    }

    @Test
    fun `every example resolves to a command class`() {
        val unresolved = examples
            .filter { codeOf(it) !in undocumentedCodes }
            .filter { line -> MarlinCommands.info.none { it.code == codeOf(line) } }

        assertEquals(emptyList<String>(), unresolved)
    }

    /**
     * The limitation this layer *does* have, as a number.
     *
     * Marlin lets a parameter documented with a value be sent bare, meaning "on": `M104 F`,
     * `G61 S0 XY`, `M919 XYZE`, `M420 C`. A `BigDecimal?` / `Int?` property cannot hold
     * "present, no value" - null already means absent - so decoding such a line drops that letter.
     *
     * This is pinned rather than fixed because fixing it means a second property per valued
     * parameter (1044 of them) to carry presence separately, and nothing in this project sends
     * those forms. Building the valued form always works; it is only reading a bare one back that
     * loses it. If this count moves, decide deliberately - do not re-baseline it.
     *
     * Three of the sixteen have a different cause. `M552`/`M553`/`M554` take a dotted quad -
     * `P192.168.1.55` - which is not a number by [spec 3.1](../../../../../../../doc/specs/GCODE_spec.md#31-numeric-values)
     * because it has more than one `.`, and is not a quoted string either. Upstream types `P` as
     * `string`, so this layer emits `P"192.168.1.55"`, which is not the form Marlin reads. There
     * is no value the model can hold for these until todo 09 lands bare strings.
     */
    @Test
    fun `a valued parameter sent bare is not read back`() {
        val lost = ArrayList<String>()

        for (line in examples) {
            if (codeOf(line) in restOfLineArgument || codeOf(line) in undocumentedCodes) continue

            for (cmd in parse(line)) {
                val code = codeOf(cmd) ?: continue
                if (code in restOfLineArgument || code in undocumentedCodes) continue

                // `info` and `decoders` are index-aligned, so the metadata picks the variants and
                // the same indices give their decoders. Decoding is on the companion now, so the
                // instances in `all` have nothing to answer with.
                val variants = MarlinCommands.info.indices
                    .filter { MarlinCommands.info[it].code == code }
                    .map { MarlinCommands.decoders[it] }
                if (variants.isEmpty()) continue

                val undocumented = undocumentedUpstream[code] ?: emptySet()
                val wanted = cmd.params
                    .mapNotNull { (it.id as? GLetter)?.letter?.uppercaseChar() }
                    .filter { it !in undocumented }
                if (wanted.isEmpty()) continue

                val bestKept = variants.maxOf { decoder ->
                    val kept = decoder.decodeParams(paramTokens(cmd)).encode().params
                        .mapNotNull { (it.id as? GLetter)?.letter?.uppercaseChar() }
                        .toSet()
                    wanted.count { it in kept }
                }
                if (bestKept < wanted.size) lost.add(line)
            }
        }

        assertEquals(
            listOf(
                // A parameter the docs give a value is sent bare, meaning "on".
                "G29 X4 Y8 L50 R150 F50 B150 T V4",
                "G33 T",
                "G60 Q5 X10 Y Z E",
                "G61 S0 XY",
                "G61 S4 X Y50 Z E",
                "M104 F S180 B190",
                "M109 F S180 B190",
                "M3 S204 I",
                "M4 S204 I",
                "M420 C",
                "M420 L0 V T4",
                "M420 V T2",
                // These three are the dotted-quad case, not the bare-value one - see the KDoc.
                // They sit here because the list is in sorted order, not grouped by cause.
                "M552 S-1 P192.168.1.55",
                "M553 P255.255.255.192",
                "M554 P192.168.1.1",
                "M710 R",
            ),
            lost.sorted(),
        )
    }
}
