#!/usr/bin/env python3
"""Generate the Marlin command classes and their tests from `doc/marlin-gcode/commands.json`.

    python3 tools/marlin/gen_mcommands.py

Writes, all under gcode/src/.../org/qw3rtrun/p3d/g/marlin/:
    MarlinGRQ.kt, MarlinMRQ.kt, MarlinTRQ.kt  - the command classes, split by command letter
    MCommands.kt                              - the MarlinCommands registry and the MarlinG facade
    MarlinCommandsTest.kt (test source root)  - the generated cover for all of it

Both are checked in, so the Gradle build never runs this. Re-run it only after re-running
`extract_marlin_docs.py` against a newer Marlin documentation checkout.

Shape of a generated class, and why:

- **Every parameter is nullable with a default.** 831 of Marlin's 882 documented parameters are
  optional, and an absent one must be absent from the wire - `M105` and `M105 T0` are different
  commands. A flag is `Boolean = false` rather than nullable because absent and false mean the
  same thing for a letter that carries no value.
- **The 46 documented-as-required parameters also get defaults.** `GRQ.decode` is an instance
  method, so the registry needs a no-argument prototype of every command to decode against. The
  KDoc marks them required; the type system does not.
- **Decimals go through the lexeme.** `word(letter, v.toPlainString())` rather than
  `word(letter, v)`, so the number is validated as a G-code number on the way in and never
  reaches the wire in scientific notation.
"""
import json
import os
import re
import sys

KOTLIN_KEYWORDS = {
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
    "try", "typealias", "typeof", "val", "var", "when", "while",
}

# How a parameter kind becomes a Kotlin property, an encode call and a decode call.
#   type, default, encode(letter, name) -> expression, decode(letter) -> expression
KINDS = {
    "flag":    ("Boolean",    "false", lambda l, n: "flag('%s')" % l,
                lambda l: "params.hasWord('%s')" % l),
    "bool":    ("Boolean?",   "null",  lambda l, n: "word('%s', if (%s) 1 else 0)" % (l, n),
                lambda l: "params.boolOf('%s')" % l),
    "int":     ("Int?",       "null",  lambda l, n: "word('%s', %s)" % (l, n),
                lambda l: "params.intOf('%s')" % l),
    "long":    ("Long?",      "null",  lambda l, n: "word('%s', BigDecimal.valueOf(%s))" % (l, n),
                lambda l: "params.longOf('%s')" % l),
    "decimal": ("BigDecimal?", "null", lambda l, n: "word('%s', %s.toPlainString())" % (l, n),
                lambda l: "params.decimalOf('%s')" % l),
    "string":  ("String?",    "null",  lambda l, n: "word('%s', text(%s))" % (l, n),
                lambda l: "params.stringOf('%s')" % l),
}

# A representative value per kind, for the generated "every parameter round-trips" test.
SAMPLES = {
    "flag": "true",
    "bool": "true",
    "int": "1",
    "long": "1L",
    "decimal": 'BigDecimal("1.5")',
    "string": '"x"',
}

# Where the generated Kotlin goes, and the one file per command letter the classes are split
# across. The split is by size alone: 295 classes in one file is 14k lines, and the letter is the
# only boundary the source data guarantees is stable - a command never changes its letter.
MARLIN_MAIN = os.path.join("gcode", "src", "main", "kotlin", "org", "qw3rtrun", "p3d", "g",
                           "marlin")
LETTER_FILES = [("G", "MarlinGRQ.kt"), ("M", "MarlinMRQ.kt"), ("T", "MarlinTRQ.kt")]

# Class names that do not come from the doc title. `M105`'s page is titled "Report Temperatures",
# but this project already calls it ReportHotendTemperature -- the name the Java record it replaces
# used, and the name the hand-written reference class established.
NAME_OVERRIDES = {
    "M105": "ReportHotendTemperature",
}


def pascal(text):
    """A Kotlin class name from a doc title: 'Bezier Cubic Spline Move' -> BezierCubicSplineMove."""
    folded = (text.replace("é", "e").replace("è", "e").replace("ê", "e")
                  .replace("ü", "u").replace("ö", "o").replace("ä", "a")
                  .replace("ß", "ss").replace("’", "").replace("'", ""))
    parts = re.split(r"[^A-Za-z0-9]+", folded)
    name = "".join(p[:1].upper() + p[1:] for p in parts if p)
    if not name or name[0].isdigit():
        name = "Cmd" + name
    return name


def prop(name_hint, letter, used):
    """A property name: the doc's value name when it is usable and free, else the letter."""
    candidate = None
    if name_hint:
        cleaned = re.sub(r"[^a-z0-9]+", " ", name_hint.lower()).strip()
        if cleaned:
            words = cleaned.split()
            candidate = words[0] + "".join(w[:1].upper() + w[1:] for w in words[1:])
    if not candidate or candidate in used or candidate in KOTLIN_KEYWORDS:
        candidate = letter.lower()
    if candidate in used or candidate in KOTLIN_KEYWORDS:
        candidate = letter.lower() + "Value"
    n = 2
    base = candidate
    while candidate in used:
        candidate = "%s%d" % (base, n)
        n += 1
    used.add(candidate)
    return candidate


def class_names(commands):
    """One unique Kotlin class name per command *entry*, indexed by position.

    Keyed by position and not by code, because a code can be documented more than once: Marlin
    has six G29 pages, one per bed-leveling system, each with its own parameter set, and two each
    for G34, M665 and M666. Their titles differ ("Bed Leveling (Unified)" vs "(Bilinear)"), so
    each becomes its own class and none is lost.
    """
    names = {}
    used = set()
    for i, c in enumerate(commands):
        if c["code"] in NAME_OVERRIDES:
            name = NAME_OVERRIDES[c["code"]]
            used.add(name)
            names[i] = name
            continue
        base = pascal(c["title"])
        name = base
        if c["shared"]:
            # Several codes share one doc page (G0/G1, M810-M819, T0-T7), so the title alone
            # cannot tell them apart. The code is the discriminator.
            name = base + c["code"].replace(".", "_")
        if name in used:
            name = base + c["code"].replace(".", "_")
        while name in used:
            name += "_"
        used.add(name)
        names[i] = name
    return names


def imports_for(letter, text):
    """The import lines one command file needs, and no others.

    Computed from the generated text rather than listed, because an unused import is a warning in
    every Kotlin build and the `T` file, with eight commands, uses far less than the `M` file does.
    Ordered the way IntelliJ lays imports out: everything else alphabetically, then `java.*`.
    """
    candidates = [
        (r"\bGEncoder\b", "org.qw3rtrun.p3d.g.code.core.GEncoder"),
        (r"\bGCommand\b", "org.qw3rtrun.p3d.g.code.core.token.GCommand"),
        (r"\bGParameterWord\b", "org.qw3rtrun.p3d.g.code.core.token.GParameterWord"),
        (r"\bGWord\b", "org.qw3rtrun.p3d.g.code.core.token.GWord"),
        (None, "org.qw3rtrun.p3d.g.code.dsl.%s" % letter),
        (None, "org.qw3rtrun.p3d.g.code.dsl.GRQ"),
        (r"\bflag\(", "org.qw3rtrun.p3d.g.code.dsl.flag"),
        (r"\btext\(", "org.qw3rtrun.p3d.g.code.dsl.text"),
        (r"\bword\(", "org.qw3rtrun.p3d.g.code.dsl.word"),
        (r"\bBigDecimal\b", "java.math.BigDecimal"),
    ]
    used = [fqn for pattern, fqn in candidates if pattern is None or re.search(pattern, text)]
    other = sorted(f for f in used if not f.startswith("java."))
    jvm = sorted(f for f in used if f.startswith("java."))
    return ["import %s" % f for f in other + jvm]


def gen_class(c, name):
    """One data class implementing GRQ."""
    params = c["params"]
    used = set()
    fields = []
    for p in params:
        ktype, default, _, _ = KINDS[p["kind"]]
        fields.append({
            "prop": prop(p["name"], p["letter"], used),
            "letter": p["letter"],
            "kind": p["kind"],
            "ktype": ktype,
            "default": default,
            "optional": p["optional"],
            "name": p["name"],
        })

    out = []
    doc_url = "https://marlinfw.org/docs/gcode/%s.html" % c["code"].replace(".", "-")

    # The first KDoc line is the command's signature, the way the reference class writes it:
    #   M105 [R] [T<index>]
    sig = [c["code"]]
    for f in fields:
        if f["kind"] == "flag":
            piece = f["letter"]
        else:
            piece = "%s<%s>" % (f["letter"], f["name"] or "value")
        sig.append(piece if not f["optional"] else "[%s]" % piece)

    out.append("/**")
    out.append(" * %s" % " ".join(sig))
    out.append(" *")
    out.append(" * %s%s." % (c["title"], " (%s)" % c["group"] if c["group"] else ""))
    if c["bareString"]:
        out.append(" *")
        out.append(" * **This command also takes a rest-of-line string** (spec 3.4a) which this")
        out.append(" * model cannot hold yet - see todo 09. Only its lettered parameters are here.")
    if any(not f["optional"] for f in fields):
        req = ", ".join("`%s`" % f["letter"] for f in fields if not f["optional"])
        out.append(" *")
        out.append(" * Marlin documents %s as required; every property here still defaults to" % req)
        out.append(" * absent, because the decode registry needs a no-argument prototype.")
    out.append(" *")
    out.append(" * @see <a href=\"%s\">MarlinFirmare %s doc</a>" % (doc_url, c["code"]))
    out.append(" */")

    if fields:
        out.append("data class %s(" % name)
        for f in fields:
            label = "`%s`" % f["letter"]
            if f["name"]:
                label += " - %s" % f["name"]
            if not f["optional"]:
                label += " (required)"
            out.append("    /** %s */" % label)
            out.append("    val %s: %s = %s," % (f["prop"], f["ktype"], f["default"]))
        out.append(") : GRQ<%s> {" % name)
    else:
        # No parameters at all: 63 commands are just their code.
        out.append("class %s : GRQ<%s> {" % (name, name))

    # The head builder, in the DSL's own spelling: `M(105)`, `T(0)`, `G("38.2")` for a subcode.
    if "." in c["number"]:
        head_call = '%s("%s")' % (c["letter"], c["number"])
        head_args = '%s("%s", ' % (c["letter"], c["number"])
    else:
        head_call = "%s(%s)" % (c["letter"], c["number"])
        head_args = "%s(%s, " % (c["letter"], c["number"])

    out.append("")
    if fields:
        out.append("    override fun encode(): GCommand {")
        out.append("        val words = ArrayList<GWord>(%d)" % len(fields))
        for f in fields:
            enc = KINDS[f["kind"]][2](f["letter"], f["prop"])
            cond = f["prop"] if f["kind"] == "flag" else "%s != null" % f["prop"]
            out.append("        if (%s) words.add(%s)" % (cond, enc))
        out.append("        return %s*words.toTypedArray())" % head_args)
        out.append("    }")
    else:
        out.append("    override fun encode(): GCommand {")
        out.append("        return %s" % head_call)
        out.append("    }")
    out.append("")
    out.append("    override fun head(): GParameterWord<*> {")
    out.append("        return %s.head" % head_call)
    out.append("    }")
    out.append("")
    out.append("    override fun decodeParams(params: List<GWord>): %s {" % name)
    if fields:
        out.append("        return %s(" % name)
        for f in fields:
            out.append("            %s = %s," % (f["prop"], KINDS[f["kind"]][3](f["letter"])))
        out.append("        )")
    else:
        out.append("        return this")
    out.append("    }")
    if not fields:
        out.append("")
        out.append("    override fun equals(other: Any?): Boolean {")
        out.append("        return other is %s" % name)
        out.append("    }")
        out.append("")
        out.append("    override fun hashCode(): Int {")
        out.append("        return %d" % (abs(hash(name)) % 1000000))
        out.append("    }")
    out.append("")
    out.append("    override fun toString(): String {")
    out.append("        return javaClass.simpleName + \"(\" + GEncoder.encode(encode()) + ')'")
    out.append("    }")
    out.append("}")
    return "\n".join(out), fields


def main():
    with open(os.path.join("doc", "marlin-gcode", "commands.json"), encoding="utf-8") as fh:
        data = json.load(fh)
    commands = data["commands"]
    src = data["source"]
    names = class_names(commands)
    ambiguous = sorted({c["code"] for c in commands
                        if sum(1 for o in commands if o["code"] == c["code"]) > 1})

    # ---- the command classes, three files, one per command letter ------------------------------
    # 14k lines in one file is more than an editor, a reviewer or a diff wants to open, and the
    # command letter is the one split that needs no judgement: G, M and T are disjoint sets and a
    # command never moves between them. Only MCommands.kt below has to know they were split - the
    # classes are top-level in one package either way, so no call site changes.
    bodies = []
    all_fields = {}
    for i, c in enumerate(commands):
        body, fields = gen_class(c, names[i])
        bodies.append(body)
        all_fields[i] = fields

    for letter, filename in LETTER_FILES:
        group = [i for i, c in enumerate(commands) if c["letter"] == letter]
        text = "\n\n".join(bodies[i] for i in group)
        codes = len({commands[i]["code"] for i in group})
        slots = sum(len(all_fields[i]) for i in group)
        header = [
            "// GENERATED FILE - do not edit.",
            "//",
            "// Regenerate with:  python3 tools/marlin/gen_mcommands.py",
            "// Source of truth:  doc/marlin-gcode/commands.json",
            "// Extracted from:   %s @ %s" % (src["repo"], src["commit"]),
            "// How and why:      tools/marlin/README.md, doc/todos/11-marlin-commands.md",
            "//",
            "// Marlin's `%s` commands, one class each, all implementing GRQ and all written the same"
            % letter,
            "// way: `encode()` builds the command with the code/dsl builders, `head()` names it, and",
            "// `decodeParams` reads one back. Every parameter is optional and absent by default, so a",
            "// bare instance encodes to the bare command - `M105` and `M105 T0` are different commands",
            "// and both have to be sayable.",
            "//",
            "// %d classes over %d distinct codes and %d parameter slots. `G`, `M` and `T` are in three"
            % (len(group), codes, slots),
            "// files only because there are %d classes in all; MCommands.kt registers every one of them"
            % len(commands),
            "// and is the single place that sees all three. Generated rather than typed because a",
            "// transposed parameter letter is invisible in review and shows up when a printer answers",
            "// `echo:Unknown command`.",
            "",
            "package org.qw3rtrun.p3d.g.marlin",
            "",
        ]
        header += imports_for(letter, text)
        header.append("")
        path = os.path.join(MARLIN_MAIN, filename)
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("\n".join(header) + "\n" + text + "\n")
        print("wrote %s  (%d classes, %d lines)"
              % (path, len(group), len(header) + text.count("\n") + 1))

    registry = [
        "/**",
        " * Every Marlin command this module knows how to write, and the lookup that reads one back.",
        " *",
        " * The list holds one prototype per command - all parameters absent - which is what makes",
        " * [decode] possible: `GRQ.decode` is an instance method, so it needs an instance to ask.",
        " */",
        "object MarlinCommands {",
        "",
        "    /** One prototype per command, in `G` then `M` then `T` order. */",
        "    val all: List<GRQ<*>> = listOf(",
    ]
    for i, c in enumerate(commands):
        registry.append("        %s()," % names[i])
    registry += [
        "    )",
        "",
        "    /** What one command class models: its code, its name, and the letters it accepts. */",
        "    data class Info(",
        "        val code: String,",
        "        val className: String,",
        "        val letters: Set<Char>,",
        "        val bareString: Boolean,",
        "    )",
        "",
        "    /**",
        "     * The same commands as metadata, for callers and tests that need to ask what a command",
        "     * accepts without building one. `letters` is every parameter letter the class has a",
        "     * property for, which is the honest measure of coverage against Marlin's own docs.",
        "     */",
        "    val info: List<Info> = listOf(",
    ]
    for i, c in enumerate(commands):
        letters = "".join(sorted({f["letter"] for f in all_fields[i]}))
        letter_set = ("setOf(" + ", ".join("'%s'" % ch for ch in letters) + ")"
                      if letters else "emptySet()")
        registry.append('        Info("%s", "%s", %s, %s),'
                        % (c["code"], names[i], letter_set,
                           "true" if c["bareString"] else "false"))
    registry += [
        "    )",
        "",
        "    /**",
        "     * Prototypes by command head, so [decode] is a map lookup and not %d comparisons."
        % len(commands),
        "     *",
        "     * **A head can be claimed by more than one class** and this keeps the first. Marlin",
        "     * documents %s once per variant - six G29 pages, one per bed-leveling" % ", ".join(
            "`%s`" % a for a in ambiguous),
        "     * system, and two each for the others - and which one a printer means depends on how",
        "     * its firmware was compiled, which no amount of reading the line can tell you. Build",
        "     * with the variant class you mean; [decode] is a best effort for the rest.",
        "     */",
        "    private val byHead: Map<GParameterWord<*>, GRQ<*>> =",
        "        all.groupBy { it.head() }.mapValues { (_, protos) -> protos.first() }",
        "",
        "    /** The codes above, whose [decode] is therefore approximate. */",
        "    val ambiguousCodes: List<String> = listOf(%s)"
        % ", ".join('"%s"' % a for a in ambiguous),
        "",
        "    /**",
        "     * The command [cmd] says it is, or null if no Marlin command has that head.",
        "     *",
        "     * Matching is on the head as written, so a non-canonical `M0105` does not resolve -",
        "     * the lexeme is part of a number's identity in this model.",
        "     */",
        "    fun decode(cmd: GCommand): GRQ<*>? {",
        "        val proto = byHead[cmd.head] ?: return null",
        "        return proto.decodeParams(cmd.params)",
        "    }",
        "}",
    ]

    # The hand-written facade that lived in MCommands.kt before it was generated. Kept verbatim in
    # spirit, with `index` now nullable so `m105()` can say a bare `M105`.
    facade = [
        "/**",
        " * Named shortcuts for the commands this project sends most.",
        " *",
        " * Each is one line over the classes above and has no privileges they do not - the point is",
        " * a call site that reads as an intention rather than a code number.",
        " */",
        "object MarlinG {",
        "",
        "    fun m105(index: Int? = null): ReportHotendTemperature {",
        "        return ReportHotendTemperature(index = index)",
        "    }",
        "",
        "    fun tempReport(tool: Int? = null): ReportHotendTemperature {",
        "        return m105(tool)",
        "    }",
        "",
        "    fun m115(): FirmwareInfo {",
        "        return FirmwareInfo()",
        "    }",
        "",
        "    fun firmwareInfo(): FirmwareInfo {",
        "        return m115()",
        "    }",
        "",
        "    fun m155(period: Int? = null): TemperatureAutoReport {",
        "        return TemperatureAutoReport(seconds = period)",
        "    }",
        "",
        "    fun autoReportTemp(period: Int? = null): TemperatureAutoReport {",
        "        return m155(period)",
        "    }",
        "",
        "    /**",
        "     * `M104 T<tool> S<temp>`.",
        "     *",
        "     * The tool goes in `T` and not in `I`: M104 documents both, `I` being a material",
        "     * preset index, and `T` is the one the old Java facade and `GSender.m104` send.",
        "     */",
        "    fun m104(temp: BigDecimal, tool: Int? = null): SetHotendTemperature {",
        "        return SetHotendTemperature(temp = temp, t = tool)",
        "    }",
        "",
        "    fun setHotendTemperature(temp: BigDecimal, tool: Int? = null): SetHotendTemperature {",
        "        return m104(temp, tool)",
        "    }",
        "",
        "    fun m140(temp: BigDecimal): SetBedTemperature {",
        "        return SetBedTemperature(temp = temp)",
        "    }",
        "",
        "    fun setBedTemperature(temp: BigDecimal): SetBedTemperature {",
        "        return m140(temp)",
        "    }",
        "}",
    ]

    registry_header = [
        "// GENERATED FILE - do not edit.",
        "//",
        "// Regenerate with:  python3 tools/marlin/gen_mcommands.py",
        "// Source of truth:  doc/marlin-gcode/commands.json",
        "// Extracted from:   %s @ %s" % (src["repo"], src["commit"]),
        "// How and why:      tools/marlin/README.md, doc/todos/11-marlin-commands.md",
        "//",
        "// The registry over every Marlin command and the named facade this project calls. The %d"
        % len(commands),
        "// command classes themselves are split by letter across MarlinGRQ.kt, MarlinMRQ.kt and",
        "// MarlinTRQ.kt; they are all in this package, so this file names them without importing.",
        "",
        "package org.qw3rtrun.p3d.g.marlin",
        "",
        "import org.qw3rtrun.p3d.g.code.core.token.GCommand",
        "import org.qw3rtrun.p3d.g.code.core.token.GParameterWord",
        "import org.qw3rtrun.p3d.g.code.dsl.GRQ",
        "import java.math.BigDecimal",
        "",
    ]
    main_kt = ("\n".join(registry_header) + "\n" + "\n".join(registry) + "\n\n"
               + "\n".join(facade) + "\n")
    main_path = os.path.join(MARLIN_MAIN, "MCommands.kt")
    with open(main_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(main_kt)

    # ---- tests -------------------------------------------------------------------------------
    t = [
        "// GENERATED FILE - do not edit.  Regenerate with: python3 tools/marlin/gen_mcommands.py",
        "",
        "package org.qw3rtrun.p3d.g.marlin",
        "",
        "import org.junit.jupiter.api.Assertions.assertEquals",
        "import org.junit.jupiter.api.Assertions.assertNotNull",
        "import org.junit.jupiter.api.Assertions.assertNull",
        "import org.junit.jupiter.api.Assertions.assertTrue",
        "import org.junit.jupiter.api.Test",
        "import org.qw3rtrun.p3d.g.code.core.GEncoder",
        "import org.qw3rtrun.p3d.g.code.dsl.M",
        "import java.math.BigDecimal",
        "",
        "/**",
        " * Generated cover for the generated commands. Three claims, one per test:",
        " *",
        " * 1. a command with nothing set encodes to exactly its code, so no parameter leaks onto",
        " *    the wire uninvited;",
        " * 2. a command with **every** parameter set names every one of its letters, and survives a",
        " *    round trip through encode and decode;",
        " * 3. the registry resolves each command's own head and nothing else.",
        " */",
        "class MarlinCommandsTest {",
        "",
        "    @Test",
        "    fun `every command has a prototype`() {",
        "        assertEquals(%d, MarlinCommands.all.size)" % len(commands),
        "        // Fewer heads than classes, by exactly the variants Marlin documents separately.",
        "        assertEquals(",
        "            %d," % len({c["code"] for c in commands}),
        "            MarlinCommands.all.map { it.head() }.toSet().size,",
        "        )",
        "        assertEquals(%s, MarlinCommands.ambiguousCodes)"
        % ("listOf(" + ", ".join('"%s"' % a for a in ambiguous) + ")"),
        "    }",
        "",
        "    @Test",
        "    fun `a bare command encodes to just its code`() {",
    ]
    for i, c in enumerate(commands):
        t.append('        assertEquals("%s", GEncoder.encode(%s().encode()))'
                 % (c["code"], names[i]))
    t += [
        "    }",
        "",
        "    @Test",
        "    fun `a bare command round-trips through its own class`() {",
        "        // Through the class and not the registry: six classes answer to `G29`, so the",
        "        // registry can only return one of them and equality would fail for the other five.",
        "        for (proto in MarlinCommands.all) {",
        "            assertEquals(proto, proto.decodeParams(proto.encode().params)) {",
        "                \"round trip failed for \" + GEncoder.encode(proto.encode())",
        "            }",
        "        }",
        "    }",
        "",
        "    @Test",
        "    fun `the registry resolves a head it has and no other`() {",
        "        assertNotNull(MarlinCommands.decode(M(105)))",
        "        // `M0105` is the same number written differently, and the lexeme is part of a",
        "        // number's identity here, so it deliberately does not resolve.",
        "        assertNull(MarlinCommands.decode(M(\"0105\")))",
        "        assertNull(MarlinCommands.decode(M(998)))",
        "    }",
        "",
    ]

    # one test method per ~40 commands, so a failure names a small group and the method stays
    # inside the JVM's 64 KB bytecode limit per method
    populated = [(i, c) for i, c in enumerate(commands) if all_fields[i]]
    chunk = 40
    for start in range(0, len(populated), chunk):
        group = populated[start:start + chunk]
        t.append("    @Test")
        t.append("    fun `every parameter is written and read back %d`() {"
                 % (start // chunk + 1))
        for i, c in group:
            name = names[i]
            fields = all_fields[i]
            args = ", ".join("%s = %s" % (f["prop"], SAMPLES[f["kind"]]) for f in fields)
            t.append("        %s(%s).let {" % (name, args))
            t.append('            val text = GEncoder.encode(it.encode())')
            for f in fields:
                t.append('            assertTrue(text.contains(" %s")) { "%s missing %s in $text" }'
                         % (f["letter"], c["code"], f["letter"]))
            t.append("            assertEquals(it, it.decodeParams(it.encode().params)) "
                     "{ \"round trip: $text\" }")
            t.append("        }")
        t.append("    }")
        t.append("")
    t.append("}")

    test_path = os.path.join("gcode", "src", "test", "kotlin", "org", "qw3rtrun", "p3d", "g",
                             "marlin", "MarlinCommandsTest.kt")
    with open(test_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(t) + "\n")

    # The doc examples become a test fixture, the way marlin.gcode already is: real command lines
    # written by Marlin's own authors, used to check that no parameter letter is missing.
    ex_path = os.path.join("gcode", "src", "test", "resources", "marlin-doc-examples.txt")
    ex_lines = ["# Example command lines from %s @ %s" % (src["repo"], src["commit"]),
                "# Extracted by tools/marlin/extract_marlin_docs.py - do not edit by hand."]
    ex_lines += data.get("examples", [])
    with open(ex_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(ex_lines) + "\n")
    print("wrote %s  (%d example lines)" % (ex_path, len(data.get("examples", []))))

    print("wrote %s  (%d classes registered, %d lines)"
          % (main_path, len(commands), main_kt.count("\n") + 1))
    print("wrote %s  (%d lines)" % (test_path, len(t)))
    slots = sum(len(v) for v in all_fields.values())
    print("  distinct codes: %d   classes: %d   ambiguous codes: %s"
          % (len({c["code"] for c in commands}), len(commands), ambiguous))
    print("  parameter properties: %d   commands with none: %d"
          % (slots, sum(1 for v in all_fields.values() if not v)))


if __name__ == "__main__":
    main()
