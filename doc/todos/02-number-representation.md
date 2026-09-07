# 02 — Number representation: get `BigDecimal` out of the core

**Goal.** A number token holds something that exists in JS/TS, C and Rust without a library, and the
only way to build one is a way that states its lexeme.

**Depends on:** nothing (though [01](./01-ascii-and-lexer-portability.md) touches the same file).
**Blocks:** [03](./03-word-and-command-layer.md) and [04](./04-encoder-and-checksum.md) — both build
on the number type, and doing them first would mean writing them twice.

This is the one genuinely hard decision in the queue. It is worth making before anything is built on
top of the current shape.

## Why

`java.math.BigDecimal` is the core's last external type and it sits in the hot path. Arbitrary-precision
decimal exists in none of the three target languages without pulling in a library, so a port either
reimplements it or silently changes behaviour.

The good news is that the pressure is already mostly off. Since number tokens started carrying their
lexeme, `rawText()` reads the lexeme — a plain `String` — and never touches the numeric value. So
`BigDecimal` is now needed only by callers that do *arithmetic* on a parsed number, and there are
none in the module.

## Decide first

The options, in the order they should be considered:

1. **`(lexeme: String, mantissa: Int, scale: Int)`** — the value is `mantissa × 10^-scale`. Exact for
   every decimal a G-code file can contain, ports as two integers, and arithmetic becomes the
   caller's explicit problem. `X10.5` → `mantissa = 105, scale = 1`. Watch the overflow boundary:
   decide what happens to a mantissa that does not fit an `Int` (probably the same answer as an
   over-long integer today — keep the lexeme, classify as `GUnknown`).
2. **Lexeme only**, with no parsed value at all on the token. The tokenizer stops parsing numbers
   entirely and callers convert when they need to. Simplest and most portable; the cost is that
   `GInt.int` disappears, which a lot of tests and the `G.kt` DSL use.
3. **Keep `BigDecimal`** and accept that the core is not portable yet. Legitimate, but then say so in
   the spec's Appendix B rather than leaving the liability implicit.

Option 1 is the one the review leaned towards. Whichever is chosen, **write the reasoning into
`GNumber`'s KDoc** — the existing KDoc already explains the lexeme-identity rule and is the right
home for this.

## Do

- [ ] Make the decision above and record it here and on `GNumber`.
- [ ] Apply it to `GInt` and `GFloat` in `code/core/token/GTokens.kt`, and to the tokenizer's
      `number()` in `GTokenizer.kt:128`, which is the only place that constructs a number from input.
- [ ] **Delete the `Double` path into `GFloat`** — `constructor(float: Double)`
      (`GTokens.kt:102-103`) and `fun Double.toToken()` (`GTokens.kt:119`).

      *Why.* A `Double` cannot hold most authored decimals, and — unlike every other construction
      path — it gives the caller no way to state the lexeme the value should render as. Using
      `BigDecimal.valueOf` keeps `1.05` readable but is equally faithful to accumulated error:
      `(0.1 + 0.2).toToken().rawText()` is `0.30000000000000004`, 19 characters against the ≤ 76
      payload budget ([spec §1.3](../specs/GCODE_spec.md#13-line-length)) and against the 3–5
      decimals [§3.1](../specs/GCODE_spec.md#31-numeric-values) asks generators to round to. That
      rounding is a call-site decision, and hiding it in a constructor is exactly what produced the
      original precision bug.

      *Callers.* There are none in production. The only use anywhere is
      `GTokensTest.float from a double does not expand the binary representation`, which exists only
      to pin that bug; it retires with the constructor and nothing replaces it, because the hazard is
      then gone by construction rather than guarded against. A caller holding a `Double` passes a
      `String`, a scaled `Int`, or rounds explicitly.

      If a `Double` entry point is ever wanted it belongs on the `G.kt` DSL or on `GDescription`
      with an explicit scale parameter, and the rounding test belongs there too.
- [ ] **`code/core/GDescription.kt` is the third `BigDecimal` site** and was missed by the original
      pass: `GDDoubleField.default: BigDecimal?` (`GDescription.kt:18-22`). It has to move to the new
      representation in the same change, because it is the descriptor a future encoder
      ([04](./04-encoder-and-checksum.md)) reads defaults from. The type name also says `Double`
      while the field holds a `BigDecimal` — rename it while it is open.
- [ ] **Rename `GFloat.float`** — the property holds a `BigDecimal`, so the name reads as a lie
      (`GFloat("1.0").float.compareTo(…)`). `value` or `decimal` says what it is. Mechanical, about
      four test lines, no production call sites. Do it here so `GFloat`'s public shape changes once
      rather than three times.

## Verify

- [ ] The whole `marlin.gcode` corpus still round-trips byte for byte, and every `GTokenizerTest`
      number case passes unchanged — the lexeme is what `rawText()` uses, so a representation change
      must be invisible to round-tripping. If a round-trip test needs editing, the change went wrong.
- [ ] Scale-sensitive equality still holds: `GFloat("1.0") != GFloat("1.00")`, and
      `GInt(1) != GInt(1, "01")`. This is deliberate — the lexeme is part of token identity — and is
      documented on `GNumber`.
- [ ] No `BigDecimal` left in the core:

      ```bash
      grep -rn 'BigDecimal\|BigInteger\|Double\|Float' gcode/src/main/kotlin/org/qw3rtrun/p3d/g/code/core/
      ```

- [ ] The spec's Appendix B token table is updated to describe what a number token now holds.
