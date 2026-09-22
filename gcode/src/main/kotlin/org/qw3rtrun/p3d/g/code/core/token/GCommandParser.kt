package org.qw3rtrun.p3d.g.code.core.token

/**
 * Groups a line's words into commands, per GCODE_spec.md sections 4 and 5.
 *
 * The third and last pass over a line. `GTokenizer` says what the bytes are, `GSemanticParser` says
 * what shape the line has and which of its elements are words, and this says which command each word
 * belongs to. It reads words only, and skips the two structural ones (spec 4): `N` and `*`.
 *
 * Stateless: one instance is interchangeable with another.
 */
class GCommandParser {

    /** The commands of [line], reading its words in wire order. */
    fun parse(line: GLine): List<GCommand> = parse(line.meaningful())

    fun parse(words: List<GWord>): List<GCommand> {
        val commands = ArrayList<GCommand>()
        var head: GParameterWord<GNumber>? = null
        var params = ArrayList<GWord>()

        for (i in words.indices) {
            val word = words[i]
            if (isStructural(word, i == 0)) continue
            // `head == null` rather than `i == 0` is what makes `T` a command only until one has
            // started: it lets `N1 T0` (an unpaired line number keeps its word) still find `T0`,
            // while keeping the `T` of `G29 T` a parameter.
            val next = commandHead(word, head == null)
            if (next == null) {
                params.add(word)
                continue
            }
            if (head != null) {
                commands.add(GCommand(head, params))
                params = ArrayList()
            }
            // else: whatever came before the first command word stays in `params`. spec 4.3 makes
            // parameter order insignificant, so a leading run belongs to the command it precedes;
            // dropping it here is how a flag goes missing without any test noticing.
            head = next
        }

        if (head != null) commands.add(GCommand(head, params))
        return commands
    }

    /**
     * spec 4: `N` (spec 7) and `*` (spec 8) are structural rather than parametric, so neither is a
     * command or a parameter of one.
     *
     * They are absent from a `GPacketLine`'s payload, which is why this looked unnecessary at first -
     * but a line that is *not* a well-formed packet keeps them. `N100 M110` is a `GMissingChecksum`
     * whose words are `[N100, M110]`, and without this the line number became a parameter of `M110`,
     * which also re-emitted the line as `M110N100`.
     *
     * **Only the first word can be the line number**, which is why [first] exists. An `N` anywhere
     * else is an ordinary parameter, and exactly one command needs it to be: `M110 N<n>` sets the
     * line-number counter (spec 7.2), and its argument is an `N` word in second position. Skipping
     * every `N` dropped that argument, so `M110 N7` assembled as a bare `M110` and the one command
     * that exists to carry a line number could not be read - found while building
     * [05](https://github.com/qw3rtrun/p3d-web-ui/issues/7).
     *
     * The narrower rule is also the one the firmware uses: Marlin takes a line number only when `N`
     * is the first character of the line (`queue.cpp`: `npos = (*command == 'N') ? command : nullptr`),
     * and then looks for a *second* `N` precisely because `M110`'s argument is one.
     */
    private fun isStructural(word: GWord, first: Boolean): Boolean {
        val id = word.id
        if (id == GChecksum) return true
        if (!first) return false
        return id is GLetter && (id.letter == 'N' || id.letter == 'n')
    }

    /**
     * [word] as a command word, or null when it is not one. [first] is true while no command has
     * started yet, which is the only position a `T` may head one.
     */
    private fun commandHead(word: GWord, first: Boolean): GParameterWord<GNumber>? {
        // spec 4.3: only A-Z are identifiers. `*` is a GChecksum, structural, never a command.
        val id = word.id
        if (id !is GLetter) return null
        if (!isCommandLetter(id.letter, first)) return null

        // spec 4.1: a command word is the letter *followed by a number*. A letter alone is a flag
        // (spec 3.2), so a bare `G` heads nothing.
        if (word !is GParameterWord<*>) return null
        val value = word.value
        if (value !is GNumber) return null
        if (!isCommandNumber(value.lexeme)) return null

        // Rebuilt rather than cast so the type is honest, and carrying `word.raw` keeps the original
        // bytes - including any whitespace the word absorbed, as in `G 1`.
        return GParameterWord(id, value, word.raw)
    }

    /**
     * spec 4.1: `G`, `M` and `T` head commands. `T` only while [first], because spec 4.2 also lists
     * it as a conventional parameter letter and 40 corpus lines use it that way - `G29 T`,
     * `G12 P1 S1 T3`, `G26 C P T3.0`. Splitting at every `T` misparses all of them.
     *
     * spec 2.2: the dialects are case-insensitive, compared as explicit ASCII rather than folded -
     * `uppercase()` is locale-dependent, and a Turkish locale changes `i`/`I`.
     *
     * `D` (spec 4.1, Marlin debug builds) is deliberately absent: spec 4.2 also lists `D` as a
     * parameter letter (diameter, PID `D`), so treating it as a command would misread those.
     */
    private fun isCommandLetter(c: Char, first: Boolean): Boolean =
        c == 'G' || c == 'g' ||
            c == 'M' || c == 'm' ||
            (first && (c == 'T' || c == 't'))

    /**
     * spec 4.1: `<unsigned-int>` optionally followed by a subcode, `.` plus `<unsigned-int>`.
     *
     * Reads the lexeme, not the value: leading zeros are insignificant (`G1` = `G01` = `G001`) so the
     * value cannot tell them apart, while a sign or a bare trailing dot has to be rejected and only
     * the lexeme still shows it.
     */
    private fun isCommandNumber(lexeme: String): Boolean = Companion.isCommandNumber(lexeme)

    private fun isDigit(c: Char) = c >= '0' && c <= '9'

    companion object {

        /**
         * The same rule, reachable without a parser instance.
         *
         * Shared with the command builders in `code/dsl/G.kt` so that the DSL cannot construct a command
         * this parser would refuse to read back. That symmetry is the point: a builder and a parser
         * disagreeing about what a command number is would let a round-trip test pass on input no
         * firmware accepts.
         */
        fun isCommandNumber(lexeme: String): Boolean {
            var i = 0
            while (i < lexeme.length && digit(lexeme[i])) i++
            if (i == 0) return false                    // a sign, a leading dot, or no digits at all
            if (i == lexeme.length) return true         // plain command number
            if (lexeme[i] != '.') return false
            i++
            val subcodeStart = i
            while (i < lexeme.length && digit(lexeme[i])) i++
            return i > subcodeStart && i == lexeme.length
        }

        private fun digit(c: Char) = c >= '0' && c <= '9'
    }
}
