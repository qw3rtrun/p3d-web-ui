# G-code Base Syntax Specification

**Scope.** This document specifies the *common, command-agnostic* syntax of G-code as it is used by
3D-printer and CNC firmware: the lexical structure (tokens), value types, identifiers (field letters),
line/block structure, line numbering and checksums, comments, quoting and the transport-level
framing rules that depend on them.

**Out of scope.** The semantics of individual commands (`G0`, `G28`, `M104`, …), their parameter sets,
coordinate systems, motion planning, and any firmware-specific behaviour that is not part of the
general syntax.

**Terminology.** *Must*, *should* and *may* describe what a conforming parser/generator is expected to
do. Because G-code has no single normative standard, rules that differ between dialects are marked
and collected in [§10 Dialect matrix](#10-dialect-matrix).

---

## 1. Document model

G-code is a **line-oriented, flat, textual** format. There is no nesting, no block scoping and no
statement terminator other than the end of line.

```
file    ::= { line }
line    ::= sequence of words, comments and separators, terminated by an end-of-line marker
word    ::= a single-letter identifier optionally followed by a value
```

A line is also called a **block** (RS274/NGC terminology). One line is the unit of:

* transmission and acknowledgement over a serial/USB/network link,
* line numbering (`N`),
* checksum/CRC protection (`*`),
* error reporting and retransmission.

### 1.1 Character set and encoding

* G-code is **7-bit ASCII**. Bytes `0x20`–`0x7E` plus the line terminators are the portable set.
* Non-ASCII bytes are only meaningful inside comments and quoted strings, and only where the
  firmware documents an encoding (most firmware treats them as opaque bytes). Generators **should
  not** emit non-ASCII outside comments and quoted strings.
* `NUL` (`0x00`) must not appear in a line; firmware commonly uses it internally as the
  line terminator of the receive buffer.

### 1.2 End of line

| Terminator | Notes |
|---|---|
| `LF` (`\n`) | Canonical form; always accepted. |
| `CR LF` (`\r\n`) | Accepted; the `CR` is not part of the line content. |
| `CR` (`\r`) alone | Historic; accepted by many firmware, but **should not** be generated. |

A lone `CR` that is not followed by `LF` is treated by strict parsers as an unexpected character
rather than as a terminator.

An end-of-line marker **terminates a comment** started with `;` (see [§6](#6-comments)) and closes the
current block. The last line of a file need not be terminated.

### 1.3 Line length

Lines are bounded by the firmware's receive buffer. Exceeding the limit is a hard error (the line is
truncated or rejected, and — when line numbering is in use — a retransmission is requested).

| Dialect | Practical limit |
|---|---|
| Marlin | `MAX_CMD_SIZE` — 96 bytes by default, including the `N…` prefix and `*…` checksum, excluding the terminator |
| RepRapFirmware | 256 characters |
| RS274/NGC (LinuxCNC) | "The maximum line length is 256 characters" |

Generators that must interoperate with unknown firmware should keep lines **≤ 76 characters** of
payload so that the `N` prefix and `*` suffix still fit in a 96-byte buffer.

---

## 2. Lexical structure (tokens)

A conforming tokenizer produces the following token kinds. Token boundaries are determined by the
first character of the token (the grammar is LL(1) except for the number/`.`-lookahead case).

| Token | First character(s) | Lexeme |
|---|---|---|
| **Letter** (identifier) | `A`–`Z`, `a`–`z` | exactly one character |
| **Checksum marker** | `*` | exactly one character |
| **Integer** | `0`–`9` | `digit+` |
| **Decimal** | `0`–`9`, `.` | `digit* '.' digit*` (at least one digit overall) |
| **Sign** | `+`, `-` | part of a numeric value, see [§3.1](#31-numeric-values) |
| **Quoted string** | `"` | up to the closing quote, escapes applied |
| **Expression** | `{` (RRF) / `[` (RS274) | balanced, up to the matching close |
| **Parameter reference** | `#` (RS274) | `#digit+`, `#<name>`, `#<_name>` |
| **Tail comment** | `;` | up to (not including) the end of line |
| **Inline comment** | `(` | up to the matching `)` |
| **Block delete** | `/` (RS274, first non-blank char) | exactly one character |
| **Whitespace** | space, tab | one or more |
| **End of line** | `LF`, `CR LF` | see [§1.2](#12-end-of-line) |

Anything else is an **unknown/invalid token**. A robust parser should preserve it (for diagnostics and
round-tripping) rather than silently dropping it.

### 2.1 Whitespace

* Space and tab are **separators only**; they carry no meaning and may appear between any two tokens,
  including *between a letter and its value* (`X 10` ≡ `X10`) and *inside a number* in the most
  permissive dialects (`X1 0` ≡ `X10` in RS274/NGC — do not rely on this).
* RS274/NGC states: "Spaces and tabs are allowed anywhere on a line of code and do not change the
  meaning of the line, except inside comments."
* Whitespace is **significant inside comments and quoted strings**.
* Whitespace is **significant for checksums**: the checksum covers the bytes actually transmitted
  (see [§8.3](#83-what-the-checksum-covers)).
* Hosts routinely **strip** all whitespace and comments before transmission to save bandwidth.

### 2.2 Case

* The original NIST specification is **case-insensitive** outside comments; `g1x10` ≡ `G1 X10`.
* Marlin is case-insensitive only when `GCODE_CASE_INSENSITIVE` is enabled, and the fast parser
  (`FASTER_GCODE_PARSER`) requires **uppercase**: *"Arguments MUST be uppercase for fast G-Code
  parsing."*
* RepRapFirmware ≥ 1.19 is case-insensitive except inside quoted strings; ≤ 1.18 was case-sensitive.

**Rule for generators: always emit uppercase letters.** Case inside quoted strings and comments is
always preserved.

### 2.3 Grammar (EBNF)

```ebnf
file           = { line } ;
line           = [ ws ] [ block-delete ] [ line-number ] content [ checksum ] [ ws ] [ tail-comment ] eol ;

content        = { word | inline-comment | ws } ;
word           = letter [ ws ] [ value ] ;

letter         = "A".."Z" | "a".."z" ;
value          = number | quoted-string | expression | parameter-ref | bare-string ;

command-word   = ( "G" | "M" | "T" ) [ ws ] unsigned-int [ "." unsigned-int ] ;   (* subcode *)

line-number    = ( "N" | "n" ) [ ws ] [ sign ] unsigned-int [ ws ] ;
checksum       = "*" [ ws ] unsigned-int ;

number         = [ sign ] ( digit { digit } [ "." { digit } ] | "." digit { digit } ) ;
sign           = "+" | "-" ;
unsigned-int   = digit { digit } ;
digit          = "0".."9" ;

quoted-string  = '"' { char-or-escape } '"' ;
expression     = "{" { any - "}" | expression } "}"            (* RepRapFirmware *)
               | "[" { any - "]" | expression } "]" ;          (* RS274/NGC      *)
parameter-ref  = "#" ( unsigned-int | "<" name ">" ) ;         (* RS274/NGC      *)
bare-string    = { char - eol } ;                              (* command-specific, see §3.4 *)

tail-comment   = ";" { char - eol } ;
inline-comment = "(" { char - ")" } ")" ;
block-delete   = "/" ;
ws             = ( " " | tab ) { " " | tab } ;
eol            = "\n" | "\r\n" ;
```

Note that `command-word`, `line-number` and `checksum` are *specialisations* of `word` — they are
lexically ordinary words whose letters (`G`/`M`/`T`, `N`, `*`) are given structural meaning by the
parser.

---

## 3. Value types

A value is bound to the letter that immediately precedes it. Types are **not declared**; they follow
from the lexeme and from what the receiving command expects.

### 3.1 Numeric values

```
[+|-] digits [ . digits ]      |      [+|-] . digits
```

RS274/NGC: *"a number consists of an optional plus or minus sign, followed by zero to many digits,
followed, possibly, by one decimal point, followed by zero to many digits — provided that there is at
least one digit somewhere in the number."*

| Type | Lexical form | Notes |
|---|---|---|
| **Integer** | `123`, `-7`, `+0` | Used for counts, indices, tool/heater numbers, milliseconds. Range is firmware-defined; assume 32-bit signed and, for many parameters, 16-bit. |
| **Real / float** | `12.42`, `-0.5`, `.5`, `2.` | Single-precision (`float`) in Marlin and RepRapFirmware. Do not expect more than ~7 significant digits. |
| **Exponent form** | `1e3` | **Not** part of the base syntax. Never generate it. |
| **Hex / octal** | `0x1F` | Not supported. |

Recommendations for generators:

* Always write at least one digit before the decimal point (`0.5`, not `.5`).
* Do not emit thousands separators, spaces inside numbers, or a trailing `.`.
* Round coordinates to the precision the firmware can use (typically 3–5 decimals); extra digits only
  consume the line-length budget and the checksum surface.

### 3.2 Flag (value-less) parameters

A letter with **no** following value is a valid word. Its meaning is "parameter present", i.e. a
boolean flag:

```
G28 X Y          ; home X and Y, no values supplied
M18 X Y E        ; disable those steppers
```

Marlin's parser records such a parameter with `has_val == false`; `parser.seen('X')` is true while
`parser.seenval('X')` is false.

### 3.3 Boolean values

There is no boolean literal. Booleans are encoded as integers, conventionally `0` = off/false and
`1` = on/true, most often on `S` or `P`. Marlin's `value_bool()` is defined as *"seen and (no value or
value ≠ 0)"*, so `S1`, `S2` and a bare `S` are all true, and `S0` is false.

### 3.4 String values

Two mechanisms exist:

**(a) Bare rest-of-line strings.** A parameter with no numeric value may consume the remainder of the
line verbatim (Marlin calls this `string_arg`). This is enabled only for a small, fixed set of commands
(file names, LCD messages). Consequences for the syntax:

* the string is **not** delimited — everything up to the end of line belongs to it,
* it may contain spaces,
* a `;` inside such a string is still treated as a comment start by most parsers, so `;` cannot be
  embedded,
* a `*checksum` still terminates the payload when line numbering is in use.

**(b) Quoted strings.** Delimited by `"` — optional in Marlin (`GCODE_QUOTED_STRINGS`), standard in
RepRapFirmware. Quoting makes strings composable with other parameters on the same line:

```
M23 "long file name.gco"
M587 S"MyNetwork" P"pa ss;word"
```

Escaping is **dialect-specific**:

| Dialect | Embedded `"` | Other escapes |
|---|---|---|
| RepRapFirmware | doubled: `""` | `'x` forces the next character to lower case |
| Marlin | backslash: `\"` | `\\` for a literal backslash (`unescape_string()`) |

A quoted string protects its content from comment and whitespace stripping. Hosts that strip
whitespace **must** be quote-aware.

### 3.5 Expressions and parameters

Not part of the classic base syntax; two families exist, and both are lexically *values* that may
appear where a number is expected.

* **RS274/NGC (LinuxCNC):** `[ ... ]` expressions with `+ - * / **`, `MOD`, comparison operators
  (`EQ NE GT GE LT LE`), logical `AND OR XOR`, and `#`-prefixed parameters — numbered (`#3`), named
  local (`#<name>`), named global (`#<_name>`). Uninitialised parameters read as `0`.
* **RepRapFirmware meta-commands:** `{ ... }` expressions over object-model values and `var`/`set`
  variables, plus the meta-commands `if`/`elif`/`else`/`while`/`echo`/`abort`. Example shape:
  `G1 X{move.axes[0].max - 5}`.

A parser targeting either dialect must at minimum **lex balanced `{}`/`[]` groups as a single opaque
value token** so that expression bodies are not mistaken for words.

---

## 4. Identifiers (field letters)

An identifier is a **single letter**. Together with its value it forms a *word* (RepRap calls it a
*field*). Two letters are structural rather than parametric: `N` ([§7](#7-line-numbering)) and the
pseudo-letter `*` ([§8](#8-checksum-and-crc)).

### 4.1 Command letters

| Letter | Role |
|---|---|
| `G` | Standard (NIST-derived) command — motion, coordinate handling |
| `M` | Miscellaneous / firmware-defined command |
| `T` | Tool select |
| `D` | Debug command (Marlin, development builds only) |

* A command word is `G`/`M`/`T` followed by an **unsigned integer**, optionally followed by a
  **subcode**: `.` plus an unsigned integer (`G29.1`, `M0.1`; Marlin `USE_GCODE_SUBCODES`).
* Leading zeros are insignificant: `G1` ≡ `G01` ≡ `G001`.
* With `GCODE_MOTION_MODES` (Marlin) an axis letter may start a line, inheriting the last motion
  command — `X10 Y10` continues the previous `G0`/`G1`.

### 4.2 Parameter letters

The letters below are the conventional assignments. **Meaning is per-command**; this table is only the
common convention (RepRap wiki):

| Letter | Conventional meaning |
|---|---|
| `X` `Y` `Z` | Axis coordinates (integer or fractional) |
| `U` `V` `W` `A` `B` `C` | Additional / rotational axes |
| `E` | Extrudate length |
| `F` | Feedrate, mm/min |
| `S` | Generic parameter — seconds, temperature, voltage, on/off |
| `P` | Generic parameter — milliseconds, index, PID `P` |
| `I` `J` `K` | Arc offsets (X/Y/Z); PID `I` |
| `D` | Diameter; PID `D` |
| `H` | Heater number; endstop/offset selector |
| `L` | Generic count/parameter |
| `R` | Temperature; radius; restore-point number |
| `Q` | Reserved / unused |
| `N` | **Line number** — structural |
| `*` | **Checksum or CRC** — structural |

### 4.3 Rules

* Only `A`–`Z` are identifiers. No multi-character identifiers exist in the base syntax.
* Each letter **should** appear at most once per line. RS274/NGC forbids repeats within a block; most
  printer firmware silently uses the *first* occurrence (Marlin) — do not rely on either.
* **Word order is not significant** for parameters: `G1 X10 F600` ≡ `G1 F600 X10`. The exceptions are
  structural: `N` must come first and `*` must come last.
* Multiple commands on one line are not portable. Some parsers accept `G90 G0 X0` (splitting at each
  `G`/`M`); Marlin executes only the first command of a line. Generators **should emit one command per
  line**.
* An unrecognised parameter letter is normally ignored by the receiving command; an unrecognised
  *command* produces an error (`echo:Unknown command: …`).

---

## 5. Line (block) structure

The canonical, fully-decorated line is:

```
[N<line>] <cmd-letter><cmd-number>[.<subcode>] [<param>...] [*<checksum>] [; comment]
```

Example, with every optional element present:

```
N42 G1 X10.5 Y-3 E0.42 F1800*118 ; move and extrude
```

Ordering constraints:

1. An optional **block-delete** `/` (RS274/NGC only) is the first non-blank character; when block
   delete is active such lines are skipped.
2. `N<line>` — *"if present, the line number should be the first field in a line."*
3. The **payload**: command word and parameter words, in any order.
4. `*<checksum>` — last field **before** any comment.
5. A **tail comment** to the end of line.

A line may be **empty** or contain only whitespace and/or comments; such a line is a no-op. Empty
lines still count for nothing in line numbering — a line number is only consumed by a line that is
actually transmitted.

---

## 6. Comments

| Form | Syntax | Terminates at |
|---|---|---|
| **Tail comment** | `;` … | end of line |
| **Inline comment** | `(` … `)` | the closing parenthesis |

```
; a whole-line comment
G28 ; home all axes
G28 (home) X Y          ; inline comment between words
```

Rules:

* A tail comment runs to the end of the line and cannot be closed; the rest of the line is comment.
* An inline comment **must open and close on the same line**. An unterminated `(` is a syntax error.
* A `;` inside an inline comment, and a `(` inside a tail comment, are ordinary characters.
* A `;` or `(` inside a **quoted string** is an ordinary character.
* Comments are semantically **inert**, except for "active comments" in RS274/NGC:
  `(MSG,…)`, `(DEBUG,…)`, `(PRINT,…)`, `(LOG,…)`.
* Comments occupy line-length budget and are covered by the checksum if transmitted; hosts usually
  strip them before sending.
* Nesting of `(` … `)` is not portable (RS274/NGC does not allow it; some tokenizers track depth).

---

## 7. Line numbering

### 7.1 Syntax

```
N<unsigned-int>
```

* `N` **should be the first field** of the line.
* RS274/NGC additionally allows a fractional line number: `N` followed by an unsigned integer,
  *"optionally followed by a period and another unsigned integer."*
* Marlin's parser skips `N` followed by any run of `-` and digits, i.e. a sign is tolerated.

### 7.2 Semantics

Line numbers exist for **transport reliability**, not for flow control: there is no `GOTO`, and a line
number never identifies a jump target.

* The number must be **exactly the previous number + 1**. A gap or repeat means a line was lost or
  duplicated; the firmware rejects the line
  (`Error:Line Number is not Last Line Number+1, Last Line: <n>`) and asks for retransmission.
* `M110 N<n>` sets the current line-number counter, which is how a host resynchronises or starts a
  session (`M110 N0`).
* Line numbers are **optional**, and are conventionally omitted for G-code stored in files
  (SD card / internal storage), where there is no lossy link to protect.
* Because a line number changes the byte content of the line, it changes the checksum: the checksum
  must be computed **after** the `N` prefix has been prepended (see [§8.3](#83-what-the-checksum-covers)).

### 7.3 Pairing rule

**A line number and a checksum must both be present or both be absent.** Mixing them is an error:

* `N` without `*` → `Error:No Checksum with line number, Last Line: <n>`
* `*` without `N` → `Error:No Line Number with checksum, Last Line: <n>`

---

## 8. Checksum and CRC

### 8.1 Syntax

```
*<unsigned-int>
```

`*` is the last field before any comment. The value is written in **decimal**, without padding. The
number of digits distinguishes the two algorithms in RepRapFirmware: **1–3 digits = XOR checksum,
5 digits = CRC16**.

### 8.2 XOR checksum (the common algorithm)

The checksum is the bitwise XOR of every byte of the line preceding the `*`, masked to 8 bits. The
canonical RepRap formulation is:

```c
int cs = 0;
for (i = 0; cmd[i] != '*' && cmd[i] != NULL; i++)
   cs = cs ^ cmd[i];
cs &= 0xff;   /* Defensive masking */
```

Properties:

* Result range: `0`–`255`.
* Order-independent (XOR is commutative) — it detects single-byte corruption and most burst errors,
  but **not** transposed bytes and not two bytes corrupted in the same bit positions.
* Written as a plain decimal integer: `N3 T0*57`.

### 8.3 What the checksum covers

The XOR runs over **exactly the bytes that are transmitted before the `*`** — nothing is normalised
first. Therefore:

* the `N` and the line-number digits **are** included;
* every space that is actually sent **is** included — `N1 G1 X0*x` and `N1 G1X0*y` have different
  checksums;
* a comment placed *after* the `*` is **not** covered;
* the line terminator is **not** covered;
* consequently, generators must checksum the final byte string, and **must not** add or remove
  whitespace after computing it. Emitting no space before `*` is the convention.

Worked example — the line `N3 T0`:

| byte | `N` | `3` | ` ` | `T` | `0` |
|---|---|---|---|---|---|
| dec | 78 | 51 | 32 | 84 | 48 |

`78 ^ 51 = 125`; `125 ^ 32 = 93`; `93 ^ 84 = 9`; `9 ^ 48 = 57` → `N3 T0*57`.

### 8.4 CRC16 (RepRapFirmware)

RepRapFirmware also accepts a CRC in the same `*` field: CCITT CRC-16, polynomial `0x1021`, emitted as
**5 decimal digits** (zero-padded), computed over the same byte range. It is strictly stronger than the
XOR checksum and is preferred where supported.

### 8.5 Failure handling and the resend protocol

Checksums are only useful together with line numbers, because retransmission is addressed by line
number. The classic host↔firmware loop is:

1. Host sends `N<k> <payload>*<cs>`.
2. Firmware validates the checksum, then the line number.
3. On success it executes/queues the line and replies `ok` (optionally with buffer/position data).
4. On failure it replies with an error naming the last good line and requests a resend:

```
Error:checksum mismatch, Last Line: 41
Resend: 42
ok
```

5. The host rewinds its send window to the requested number and resends from there.

Related framing facts that constrain the syntax:

* Motion commands (`G0`–`G3`, `G28`–`G32`, …) are **buffered**; the `ok` acknowledges *queuing*, not
  completion. Non-buffered commands are acknowledged only once the queue has drained. Hosts therefore
  keep a small window of unacknowledged lines (Marlin's `BUFSIZE`, default 4).
* Because retransmission is line-based, a line must never be split across two writes in a way that
  lets another line interleave.

---

## 9. Error handling

A parser should distinguish these classes:

| Class | Examples |
|---|---|
| **Lexical error** | unterminated `(` comment, unterminated `"` string, unbalanced `{`/`[`, stray character (`?`, `,`, `@`) |
| **Structural error** | `N` not first, `*` not last, `N` without `*` or vice versa, line too long |
| **Framing error** | checksum mismatch, line number not *last + 1* |
| **Semantic error** | unknown command, missing required parameter, value out of range — *outside this document's scope* |

Recommended behaviour:

* Framing errors → reject the whole line and request retransmission; do **not** execute it partially.
* Lexical/structural errors on a checksummed line are also framing errors from the host's point of
  view (the line as received is not usable).
* A parser used for tooling (linting, visualisation, round-tripping) should keep unknown tokens and
  original spelling rather than normalise, so that a file can be re-emitted byte-identically.

---

## 10. Dialect matrix

| Feature | RS274/NGC (LinuxCNC) | Marlin | RepRapFirmware |
|---|---|---|---|
| Case-insensitive | Yes | Only with `GCODE_CASE_INSENSITIVE`; uppercase required by `FASTER_GCODE_PARSER` | Yes since 1.19 (not inside strings) |
| Whitespace anywhere | Yes | Between words; not inside numbers | Between words |
| `;` comment | Yes | Yes | Yes |
| `( )` comment | Yes | Yes | Yes |
| Line number `N` | Yes, fractional allowed | Yes, sign tolerated | Yes |
| Checksum `*` | No | XOR, 1–3 digits | XOR (1–3 digits) or CRC16 (5 digits) |
| `N`/`*` must pair | n/a | Yes | Yes |
| Subcodes `G29.1` | n/a | With `USE_GCODE_SUBCODES` | Yes |
| Quoted strings | n/a | With `GCODE_QUOTED_STRINGS`, `\` escapes | Yes, `""` escape, `'` lower-casing |
| Value-less flags (`G28 X`) | No (words need values) | Yes | Yes |
| Expressions | `[ … ]`, `#params` | No | `{ … }`, object model, `var`/`set` |
| Block delete `/` | Yes | No | No |
| Multiple commands per line | Words combine in one block | First command only | First command only |
| Max line length | 256 | 96 (`MAX_CMD_SIZE`) | 256 |

---

## Appendix A — Reference checksum implementations

**C (canonical RepRap form)**

```c
uint8_t gcode_checksum(const char *line) {
    int cs = 0;
    for (int i = 0; line[i] != '*' && line[i] != '\0'; i++)
        cs ^= line[i];
    return cs & 0xff;
}
```

**Kotlin (streaming, as used by `:gcode`)**

```kotlin
interface CheckSumCalculator {
    fun add(ch: Char)
    fun get(): GInt
}

class XorCheckSum : CheckSumCalculator {
    private var sum = 0
    override fun add(ch: Char) { sum = sum xor ch.code }
    override fun get(): GInt = GInt(sum and 0xff)
}
```

**Python (framing a line for transmission)**

```python
def frame(payload: str, n: int) -> str:
    line = f"N{n} {payload}"
    cs = 0
    for b in line.encode("ascii"):
        cs ^= b
    return f"{line}*{cs & 0xff}"

frame("T0", 3)          # 'N3 T0*57'
frame("G1 X10 F600", 1)  # checksum computed over the exact bytes above
```

---

## Appendix B — Mapping to the `:gcode` module

This repository implements the syntax above in `gcode/src/main/kotlin/org/qw3rtrun/p3d/g/code/core/`.
The mapping is given here so that the spec and the code can be kept in step.

That package is written as a **portable core**: a deliberately small language surface (hand-written
iterators, integer and bitwise math, records, arrays, explicit state machines, no third-party
dependencies) so that it can be re-implemented in JS/TS, C or Rust as a transliteration rather than a
rewrite. Consequences visible in the types below: characters are classified by explicit comparison
rather than by Unicode-category predicates, malformed input yields a token that carries the offending
bytes instead of an exception, and every token exposes `rawText()` so a parse can be diffed against a
raw capture. The rules are recorded in the `low-level-protocol-dev` skill
(`.agents/skills/low-level-protocol-dev/SKILL.md`) and the outstanding gaps in
[`doc/todos/`](../todos/00-index.md).

### B.1 Tokens — `token/GTokens.kt`

| Spec token ([§2](#2-lexical-structure-tokens)) | Type |
|---|---|
| Letter identifier | `GLetter(letter: Char)` : `GIdentifier` |
| Checksum marker `*` | `GChecksum` (object, `name == "*"`) : `GIdentifier` |
| Integer | `GInt(int: Int, lexeme: String = int.toString())` : `GNumber` |
| Decimal | `GFloat(float: BigDecimal, lexeme: String = float.toString())` : `GNumber` |
| Quoted string | `GQuotedString(string: String)` : `GString` — `rawText()` re-doubles `"` |
| Expression `{ … }` | `GRawExpression` : `GExpression` |
| Tail comment `;` | `GTailComment(string, key = ";")` : `GComment` |
| Inline comment `( )` | `GInlineComment(string, "(", ")")` : `GComment` |
| Whitespace | `GSpace`, `GTab` : `GWhitespace` : `GSeparator` |
| End of line | `GLineBreak(breaker)` : `GSeparator` |
| Invalid token | `GUnknown(str)` |

Every token exposes `rawText()`, so a token stream round-trips to the original text.

Notable choices: **decimals are `BigDecimal`**, so no precision is lost at parse time
([§3.1](#31-numeric-values) precision limits apply only when the value reaches firmware); the
quoted-string escape is the **RepRapFirmware doubling** rule; and a **number carries the lexeme it
was read from** alongside its value, so `+5`, `01`, `.5` and `1.` re-print as written. The lexeme is
part of token identity — `GInt(1) != GInt(1, "01")` — and value comparison goes through `int` /
`float.compareTo`.

### B.2 Tokenizer — `token/GTokenizer.kt`

`GTokenizer.parse(…)` yields a lazy `Sequence<GToken>`/`Iterator<GToken>` over a character stream,
dispatching on the first character exactly as in the [§2](#2-lexical-structure-tokens) table:
whitespace → `GSpace`/`GTab`/`GLineBreak` (with `\r\n` lookahead, lone `\r` → `GUnknown`), letter →
`GLetter`, digit, `.`, `+` or `-` → number, `"` → string, `{` → balanced expression, `;` → tail comment,
`(` → balanced inline comment, `*` → `GChecksum`, otherwise `GUnknown`.

Deviations from this spec, as currently written (all verified by running the module):

* `GInlineComment.string` **keeps the closing `)`** (`inlineComment()` appends the character before
  decrementing the nesting counter) while `rawText()` appends another one, so
  `G1 (feedrate) F1500` re-prints as `G1 (feedrate)) F1500`;
* an unterminated `(` loses its opening delimiter (`M(abc` → `Mabcc`) and an unterminated `"` gains a
  closing one (`M"asd` → `M"asd"`); both degrade to a token rather than raising a lexical error;
* `parseLines(Sequence<String>)` does not re-insert line terminators, so consecutive lines are
  concatenated (`["G28", "M104 S200"]` → `G28M104 S200`);
* a lone `.`, a bare sign, a number with two decimal points (`1.2.3`) and an integer too large for
  `Int` all degrade to `GUnknown` carrying the original lexeme, rather than to a typed lexical error
  ([§9](#9-error-handling));
* a subcode ([§4.1](#41-command-letters)) is lexed as a decimal: `G29.1` → `GLetter(G), GFloat(29.1)`;
* bare rest-of-line strings ([§3.4](#34-string-values)) are not recognised — `M117 Hello World`
  becomes one `GLetter` per character.

Conforming as of the number-lexeme pass: the optional sign of [§3.1](#31-numeric-values) is part of
the number token (`G1 E-5` → `… GLetter(E), GInt(-5)`), a tab is `GTab`
([§2.1](#21-whitespace)), and `rawText()` round-trips **every** construct the lexer accepts,
non-canonical numbers included — `.5`, `01` and `1.` come back byte-identical.

Both line terminators of [§1.2](#12-end-of-line) are handled: `\r\n` is one `GLineBreak("\r\n")`, and
a lone `\r` degrades to `GUnknown` without consuming the following character.

### B.3 Line model — `token/GSemantics.kt`, `token/GLiner.kt`

| Spec concept | Type |
|---|---|
| Any line/block ([§5](#5-line-block-structure)) | `GLine { val payload: List<GToken> }` |
| Empty line | `GEmptyLine` (currently unreachable — blank lines become `GSimpleLine`) |
| Unnumbered line | `GSimpleLine` |
| `N…*…` framed line ([§7](#7-line-numbering), [§8](#8-checksum-and-crc)) | `GPacketLine(number, payload, checksum, tail)` — also `GOrdered`, `GCheckSumControlled` |
| Checksum field | `GCheckSumValue(ident: GChecksum, value: GInt)` |
| Word list grouped into commands | `GCommandLine(cmds: List<GCommand>, payload)` |
| One command + its parameters ([§4](#4-identifiers-field-letters)) | `GCommand(head: GIdentifier, params: List<GElement>)` |
| Structural error ([§9](#9-error-handling)) | `GError`, e.g. `GNotIdentifierError` |

`GLineIterator` splits a token stream at `GLineBreak` and classifies each line: a line is a *packet*
when it starts with `GLetter('N')` **and** contains `GChecksum` — i.e. it implements the
[§7.3](#73-pairing-rule) pairing rule as a recognition condition, though the match is
case-sensitive and position-exact, so `n1 G28*12` and ` N1 G28*12` fall back to `GSimpleLine`.
`GCommandParser.isCommand()` treats `G`, `M` and (line-initially) `T` as command letters, which is
the [§4.1](#41-command-letters) set minus Marlin's development-only `D`; the class is not reachable
from anywhere yet, so `GCommandLine`/`GError` are never produced.

Not yet covered by the implementation: line-number continuity checking, checksum
*verification*/generation at the line level (`XorCheckSum` is correct — it yields `57` for `N3 T0` —
but has no callers), line-length limits, subcodes, and the `N`-without-`*` / `*`-without-`N` error
cases ([§7.3](#73-pairing-rule)) — a mismatched line falls back to `GSimpleLine` or yields
`GPacketLine` fields of `-1`. `GPacketLine.tail` also starts at the checksum *value* rather than
after it, and is computed as `subList(indexOfCheckSum + 1, size - 1)`, which assumes a trailing line
break: without one it drops the last real token, and `N*` throws
`IllegalArgumentException: fromIndex(2) > toIndex(1)`.

---

## References

1. RepRap wiki — *G-code*: <https://reprap.org/wiki/G-code> (fields table, `N`/`*` special fields,
   XOR checksum, CRC16, comments, case sensitivity, quoted strings, buffering)
2. Marlin Firmware — *G-code index / parser*: <https://marlinfw.org/meta/gcode/> and
   `Marlin/src/gcode/parser.cpp`, `parser.h`:
   <https://github.com/MarlinFirmware/Marlin/blob/2.1.x/Marlin/src/gcode/parser.cpp>
3. Marlin Firmware — *GCode quoted string support* (PR #16818):
   <https://github.com/MarlinFirmware/Marlin/pull/16818/files>
4. Marlin Firmware — *Code structure*: <https://marlinfw.org/docs/development/code_structure.html>
5. LinuxCNC — *G-code Overview* (RS274/NGC block syntax, numbers, parameters, expressions, comments,
   256-char limit, case insensitivity): <https://linuxcnc.org/docs/html/gcode/overview.html>
6. Duet3D — *G-code meta commands* (RepRapFirmware `{}` expressions, variables, conditional G-code):
   <https://docs.duet3d.com/User_manual/Reference/Gcode_meta_commands>
7. Kramer, T. R., Proctor, F. M., Messina, E. — *The NIST RS274/NGC Interpreter — Version 3*,
   NISTIR 6556, 2000.
</content>
