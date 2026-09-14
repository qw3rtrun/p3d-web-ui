# 02 — Number representation: `BigDecimal` stays, and the spec now says so

**Status: done.** The decision was made rather than deferred; what shipped is the decision, the
hygiene that came with it, and the liability written down where a reader will find it.

**Decision.** Option 3 of the three below: **keep `java.math.BigDecimal`** as the parsed value of a
decimal token, and state in [`GCODE_spec.md` Appendix B](../specs/GCODE_spec.md) and on `GNumber`
that this is the core's one admitted portability liability, so that a port meets it as a documented
choice rather than as a surprise.

**Depended on:** nothing. **Blocked:** [03](./03-word-and-command-layer.md) and
[04](./04-encoder-and-checksum.md) — the block is now lifted; the number type's public shape is
settled and will not move under them.

## Why this, and not the other two

`BigDecimal` is the core's last external type and it sits in the hot path. Arbitrary-precision
decimal exists in none of the three target languages without a library, so a port either
reimplements it or silently changes behaviour. That is a real cost and the reason this file exists.

What made keeping it affordable is that **the pressure is already off**. Since number tokens started
carrying their lexeme, `rawText()` reads the lexeme — a plain `String` — and never touches the
numeric value. So `BigDecimal` is needed only by callers that do *arithmetic* on a parsed number, and
there are none: the only main-source consumers of a number token are `XorCheckSum.kt:18`
(`GInt(sum and 0xff)`, an `Int`) and the tokenizer itself. A port that carries only the lexeme and
drops the parsed value still lexes and re-emits correctly — the round-trip invariant the whole token
layer rests on does not depend on the type at all.

So the choice was between paying a public-shape change across every caller *now*, for portability the
module cannot yet spend, or naming the debt and paying it when a port is actually written. The
options as they were weighed:

1. **`(lexeme: String, mantissa: Int, scale: Int)`** — value is `mantissa × 10^-scale`. Exact for
   every decimal a G-code file can contain, ports as two integers, arithmetic becomes the caller's
   explicit problem. **Not taken now**, but recorded in Appendix B as *the* replacement a port should
   reach for, because it preserves the scale-sensitive token identity that a binary float destroys.
   Whoever takes it must decide what happens to a mantissa that does not fit an `Int` — probably the
   same answer as an over-long integer today: keep the lexeme, classify as `GUnknown`.
2. **Lexeme only**, no parsed value on the token. Simplest and most portable; the cost is that
   `GNumber.number` and `GInt.int` disappear, which a lot of tests and the `G.kt` DSL use.
3. **Keep `BigDecimal`** and say so in the spec rather than leaving the liability implicit. ← taken.

## Done

- [x] Decision made, recorded here, on `GNumber`'s KDoc, and in Appendix B. `GNumber`'s KDoc carries
      the full reasoning — it already owned the lexeme-identity rule and is the right home.
- [x] **Deleted the `Double` path into `GFloat`** — `constructor(float: Double)` and
      `fun Double.toToken()`.

      *Why, and why it survives keeping `BigDecimal`.* This item was never about portability. A
      `Double` cannot hold most authored decimals and — unlike every other construction path — gives
      the caller no way to state the lexeme the value should render as. `BigDecimal.valueOf` keeps
      `1.05` readable but is equally faithful to accumulated error: `(0.1 + 0.2).toToken().rawText()`
      is `0.30000000000000004`, 19 characters against the ≤ 76 payload budget
      ([spec §1.3](../specs/GCODE_spec.md#13-line-length)) and against the 3–5 decimals
      [§3.1](../specs/GCODE_spec.md#31-numeric-values) asks generators to round to. That rounding is
      a call-site decision, and hiding it in a constructor is what produced the original precision
      bug.

      *Callers.* There were none in production. The only use anywhere was
      `GTokensTest.float from a double does not expand the binary representation`, which existed only
      to pin that bug; it retired with the constructor and nothing replaced it, because the hazard is
      now gone by construction rather than guarded against. A caller holding a `Double` passes a
      `String` or rounds explicitly. If a `Double` entry point is ever wanted it belongs on the
      `G.kt` DSL or on `GDescription` with an explicit scale parameter, and the rounding test belongs
      there too.
- [x] **`GDDoubleField` → `GDDecimalField`** (`code/core/GDescription.kt`) — the third `BigDecimal`
      site, missed by the original pass. The type name said `Double` while the field held a
      `BigDecimal`. It is the descriptor a future encoder ([04](./04-encoder-and-checksum.md)) reads
      defaults from, so it gained a KDoc saying why scale matters there.
- [x] **`GFloat.float` → `GFloat.value`** — the property holds a `BigDecimal`, so the old name read
      as a lie (`GFloat("1.0").float.compareTo(…)`). Four test lines, no production call sites.

## Verified

Run on the changed tree, not read:

- `:gcode:test` — **499 tests, 0 failures, 0 skipped**, against a 500-test baseline. The one missing
  test is the retired `Double` case; nothing else moved.
- **No round-trip test needed editing**, which is the point: the lexeme is what `rawText()` uses, so
  the whole `marlin.gcode` corpus still round-trips byte for byte and every `GTokenizerTest` number
  case passes unchanged.
- Scale-sensitive equality still holds: `GFloat("1.0") != GFloat("1.00")`, `GInt(1) != GInt(1, "01")`.
- `grep -rn 'BigDecimal\|BigInteger\|Double\|Float' gcode/src/main/kotlin/.../code/core/` leaves
  exactly three `BigDecimal` value sites — `GTokens.GFloat`, `GTokenizer.number()`,
  `GDescription.GDDecimalField` — each now carrying a comment saying why, plus the `GFloat` type name
  and prose in KDoc. No `Double` or `Float` type remains anywhere in the core.

## What a later reader should know

If a port is started, this file and `GNumber`'s KDoc are where the replacement decision has already
been half-made: go to option 1. Do not reach for a binary float — `1.0` and `1.00` are deliberately
different tokens, and that is what makes `rawText()` round-trip.
