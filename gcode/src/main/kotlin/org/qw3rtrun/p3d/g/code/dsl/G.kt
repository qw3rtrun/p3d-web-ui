package org.qw3rtrun.p3d.g.code.dsl

import org.qw3rtrun.p3d.g.code.core.command.GBlockDraft
import org.qw3rtrun.p3d.g.code.core.command.GBlockPart
import org.qw3rtrun.p3d.g.code.core.command.GCommand
import org.qw3rtrun.p3d.g.code.core.command.GNamedWord
import org.qw3rtrun.p3d.g.code.core.command.GWord
import org.qw3rtrun.p3d.g.code.core.command.isCommandNumber
import org.qw3rtrun.p3d.g.code.core.token.*

/**
 * The command-building DSL: a facade over the model in `code/core`, for writing G-code rather than
 * reading it. The rule it exists to satisfy is **any valid G-code can be written with it**, which
 * `GDslCorpusTest` keeps honest by rewriting a real corpus line by line and comparing bytes -
 * comments (41% of the corpus's non-blank lines carry one), flags, subcodes, non-canonical lexemes
 * like `G01` and `X10.50`, lowercase letters, strings, expressions and multi-command blocks all have
 * to round-trip.
 *
 * ```
 * G(1, X("10.5"), F(1800)) comment " move"      // G1 X10.5 F1800 ; move
 * G(28, X, Y)                                    // G28 X Y
 * G("29.1", P(1))                                // G29.1 P1
 * M(117, S(text("Hello")))                       // M117 S"Hello"
 * block(G(53), G(0, X(0)))                       // G53 G0 X0
 * commentBlock(" LAYER:42")                      // ; LAYER:42
 * ```
 *
 * Builders return values and never emit, so a command can be built, inspected and asserted on before
 * anything is sent. `GSender`, in `:backend:terminal`, is a thin sink over this and is kept separate
 * on purpose - emitting is transport's business, and keeping it out is what lets this file be tested
 * without one. (The Java facade this replaces took its sink in the constructor and had to keep a
 * mutable list in its own test to see what it had produced.)
 *
 * Unlike `code/core`, this file is allowed full Kotlin - extension and infix functions, default
 * arguments, overloads - because the layering rule makes only `code/core` portable; a port
 * re-implements that and writes its own facade.
 *
 * Two halves: the **command** builders of spec 4 - [G], [M], [T] and the general [command] - and the
 * **block** builders of spec 5 - [block], [comment] and [commentBlock]. Parameter words live in
 * `GWords.kt`.
 */

/**
 * `G<number>` with [params].
 *
 * ```
 * GEncoder.encode(G(1, X(10), F(1800)))   // "G1 X10 F1800"
 * ```
 */
fun G(number: Int, vararg params: GWord): GCommand = command('G', number, *params)

/**
 * `G<lexeme>`, for a subcode or a non-canonical number. Throws `IllegalArgumentException` when
 * [lexeme] is not a command number (spec 4.1).
 *
 * ```
 * GEncoder.encode(G("29.1", P(1)))   // "G29.1 P1"
 * GEncoder.encode(G("01"))           // "G01"
 * ```
 */
fun G(lexeme: String, vararg params: GWord): GCommand = command('G', lexeme, *params)

/**
 * `M<number>` with [params].
 *
 * ```
 * GEncoder.encode(M(104, S(200)))   // "M104 S200"
 * ```
 */
fun M(number: Int, vararg params: GWord): GCommand = command('M', number, *params)

/**
 * `M<lexeme>`, for a subcode or a non-canonical number. Throws `IllegalArgumentException` when
 * [lexeme] is not a command number (spec 4.1).
 *
 * ```
 * GEncoder.encode(M("0105"))   // "M0105"
 * ```
 */
fun M(lexeme: String, vararg params: GWord): GCommand = command('M', lexeme, *params)

/**
 * `T<number>` - tool select, spec 4.1.
 *
 * ```
 * GEncoder.encode(T(0))   // "T0"
 * ```
 */
fun T(number: Int, vararg params: GWord): GCommand = command('T', number, *params)

/**
 * `T<lexeme>` - tool select with the number written exactly as given. Throws
 * `IllegalArgumentException` when [lexeme] is not a command number (spec 4.1).
 *
 * ```
 * GEncoder.encode(T("1"))   // "T1"
 * ```
 */
fun T(lexeme: String, vararg params: GWord): GCommand = command('T', lexeme, *params)

/**
 * `<letter><number>` for any command letter, including the ones spec 4.1 leaves to a dialect.
 *
 * **Where a letter is both a command and a parameter, one spelling has to win**, because a Kotlin
 * name cannot return two types. Two letters are affected, and each is resolved the way the parser
 * resolves it, so that builder and parser agree:
 *
 * - **`T`** heads a command (`T(0)` is `T0`, spec 4.1) - the reading `isCommandLetter` takes while
 *   no command has started. As a *parameter* - `M105 T1`, `G29 T`, 40 corpus lines - write
 *   `word('T', 1)` or `flag('T')`.
 * - **`D`** is a parameter (`D(3)` is `D3`, spec 4.2's diameter and PID `D`) - the reading the
 *   parser takes, because it is the common one. As the Marlin debug *command* spec 4.1 lists, write
 *   `command('D', 3)`; the parser will still read it back as a parameter, and that asymmetry is the
 *   spec's own ambiguity rather than this file's.
 *
 * ```
 * GEncoder.encode(command('D', 3))                   // "D3"
 * GEncoder.encode(command('M', 110, word('N', 7)))   // "M110 N7"
 * ```
 */
fun command(letter: Char, number: Int, vararg params: GWord): GCommand =
    command(letter, number.toString(), *params)

/**
 * `<letter><lexeme>` for any command letter - the one builder every other command builder is.
 *
 * Throws `IllegalArgumentException` at the call site for three programmer errors:
 *
 * - [letter] is not A-Z (spec 4.3).
 * - [lexeme] is not a command number (spec 4.1), judged by the reader's own `isCommandNumber` so the
 *   DSL cannot build a command that will not read back. `G-1` and `G29.` are not commands, and
 *   finding that out at the call site beats discovering it when a printer answers
 *   `echo:Unknown command`.
 * - A parameter is `*` (spec 8). It is never a parameter - it is the block's checksum field, and
 *   `GEncoder.frame` is what puts one there. A draft carrying its own `*` would be checksummed twice.
 *
 * `N` is deliberately *not* refused, even though it is structural too. Spec 7.1 makes only the
 * **first field of a line** a line number, so an `N` inside a command is an ordinary parameter - and
 * one command's argument is exactly that: `M110 N7` sets the line-number counter (spec 7.2).
 * Refusing it here would have made `M110` unwritable, which is the same over-wide rule todo 05 had to
 * narrow when a line number is read.
 *
 * ```
 * GEncoder.encode(command('G', "38.2", Z(-10)))   // "G38.2 Z-10"
 * command('G', "-1")                             // throws IllegalArgumentException
 * command('G', 1, GFlagWord(GChecksum))          // throws IllegalArgumentException
 * ```
 */
fun command(letter: Char, lexeme: String, vararg params: GWord): GCommand {
    require(isCommandNumber(lexeme)) {
        "spec 4.1: a command number is an unsigned integer with an optional subcode, got '$lexeme'"
    }
    for (param in params) {
        require(param !is GNamedWord || param.id != GChecksum) {
            "spec 8: `*` is the block's checksum field, not a parameter - use GEncoder.frame"
        }
    }
    return GCommand(identifier(letter), number(lexeme), params.toList())
}

/**
 * A block made of [parts] in order: commands and comments, as spec 5 allows.
 *
 * ```
 * GEncoder.encode(block(G(53), G(0, X(0))))   // "G53 G0 X0"
 * ```
 */
fun block(vararg parts: GBlockPart): GBlockDraft = GBlockDraft(parts.toList())

/**
 * This command as a block of its own - the common case.
 *
 * ```
 * GEncoder.encode(G(28).block())   // "G28"
 * ```
 */
fun GCommand.block(): GBlockDraft = GBlockDraft(listOf(this))

/**
 * This command with a trailing `;` comment. A leading space in [text] is the caller's to include.
 *
 * ```
 * GEncoder.encode(G(1, X(10)) comment " move")   // "G1 X10 ; move"
 * ```
 */
infix fun GCommand.comment(text: String): GBlockDraft = GBlockDraft(listOf(this, tailComment(text)))

/**
 * This block with a trailing `;` comment appended.
 *
 * ```
 * GEncoder.encode(block(G(53), G(0, X(0))) comment " machine")   // "G53 G0 X0 ; machine"
 * ```
 */
infix fun GBlockDraft.comment(text: String): GBlockDraft = GBlockDraft(parts + tailComment(text))

/**
 * A block that is nothing but a `;` comment - spec 5 calls it a no-op.
 *
 * ```
 * GEncoder.encode(commentBlock(" LAYER:42"))   // "; LAYER:42"
 * ```
 */
fun commentBlock(text: String): GBlockDraft = GBlockDraft(listOf(tailComment(text)))
