#!/usr/bin/env python3
"""Generate the Marlin command classes and their tests from `doc/marlin-gcode/commands.json`.

    python3 tools/marlin/gen_mcommands.py

Writes, all under gcode/src/.../org/qw3rtrun/p3d/g/marlin/:
    command/<ClassName>.kt                    - one file per command class, 295 of them
    MarlinRQ.kt                               - the MarlinCommands registry and the MarlinG facade
    MarlinCommandsTest.kt (test source root)  - the generated cover for all of it

The command directory is emptied before it is written, so a class that loses its name upstream
does not leave a stale file behind to be compiled alongside its replacement.

Both are checked in, so the Gradle build never runs this. Re-run it only after re-running
`extract_marlin_docs.py` against a newer Marlin documentation checkout.

Shape of a generated class, and why:

- **Every parameter is nullable with a default.** 831 of Marlin's 882 documented parameters are
  optional, and an absent one must be absent from the wire - `M105` and `M105 T0` are different
  commands. A flag is `Boolean = false` rather than nullable because absent and false mean the
  same thing for a letter that carries no value.
- **The 46 documented-as-required parameters also get defaults.** Every class is then constructible
  bare, which is what makes `all` an enumeration of what this module can write and what the
  generated cover asserts against. The KDoc marks them required; the type system does not.
- **Decoding lives on the companion, not on the instance.** `head()` and `decodeParams()` describe
  the command *type*, so they sit in a `companion object : GRqDecoder<T>` and call sites read
  `ReportHotendTemperature.decode(tokens)`. This mirrors `GRS`/`GRSDecoder`.
- **Decoding reads tokens, not words.** A decoder is handed the tokens that followed its head and
  pairs them itself, because whether a line's tail is two words or one unquoted string depends on
  the command number and nothing below the decoder knows it. See `GRqDecoder`.
- **Decimals go through the lexeme.** `word(letter, v.toPlainString())` rather than
  `word(letter, v)`, so the number is validated as a G-code number on the way in and never
  reaches the wire in scientific notation.
"""
import json
import os
import re
import sys
import zlib

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
    # spec 3.4a's bare rest-of-line string - Marlin's `string_arg`. It has no letter, so it is
    # built and read by position: last in the command, because everything to the end of the line
    # belongs to it. See `bareString` in the DSL and `stringArg` in MarlinWords.
    #
    # Its decode call is the one that is not a function of its own letter: where the string starts
    # depends on *this command's* letters - `M117 H1 ello` is all message, `M118 P1 ello` is not -
    # so `l` here is the command's letter list and `gen_class` is what fills it in.
    "bare":    ("String?",    "null",  lambda l, n: "bareString(%s)" % n,
                lambda l: "all.stringArg(%s)" % l),
}

# A representative value per kind, for the generated "every parameter round-trips" test.
SAMPLES = {
    "flag": "true",
    "bool": "true",
    "int": "1",
    "long": "1L",
    "decimal": 'BigDecimal("1.5")',
    "string": '"x"',
    # Not "x": a one-letter sample is indistinguishable from a flag, and the point of this sample
    # is to prove the string survives the trip whole, spaces and all.
    "bare": '"a tail"',
}

# Where the generated Kotlin goes. One file per command class, named after the class it holds,
# in a package of their own: 295 classes is 14k lines, which is more than any editor, reviewer or
# diff wants to open at once, and the class is the only boundary that needs no judgement. Naming
# by class and not by G-code because 8 classes share a code with another - Marlin documents six
# G29 variants and two each of G34, M665 and M666 - so `G29.kt` could not hold one command.
MARLIN_MAIN = os.path.join("gcode", "src", "main", "kotlin", "org", "qw3rtrun", "p3d", "g",
                           "marlin")
COMMAND_DIR = os.path.join(MARLIN_MAIN, "command")
COMMAND_PACKAGE = "org.qw3rtrun.p3d.g.marlin.command"
REGISTRY_FILE = "MarlinRQ.kt"

# Class names that do not come from the doc title. `M105`'s page is titled "Report Temperatures",
# but this project already calls it ReportHotendTemperature -- the name the Java record it replaces
# used, and the name the hand-written reference class established.
NAME_OVERRIDES = {
    "M105": "ReportHotendTemperature",
}

# What one command's rest-of-line string is *called*. The docs name it - the pseudo-parameter is
# tagged `string`, `filename`, `path` or `message` - but `commands.json` records only that the
# command takes one, so the few whose argument is not a message are named here. `message` is the
# default and is right for the rest.
BARE_STRING_NAMES = {
    "M23": "filename", "M28": "filename", "M30": "filename", "M928": "filename",
    "M33": "path",
    "M810": "gcode", "M811": "gcode", "M812": "gcode", "M813": "gcode", "M814": "gcode",
    "M815": "gcode", "M816": "gcode", "M817": "gcode", "M818": "gcode", "M819": "gcode",
}


def stable_hash(name):
    """The `hashCode` constant for a parameterless command class.

    CRC32 and not Python's own `hash()`: string hashing is salted per process (PYTHONHASHSEED), so
    `hash()` emitted a different constant on every run and every regeneration produced a diff of
    93 meaningless line changes. Any deterministic function of the name will do; this one is in the
    standard library and is checked for collisions in main().
    """
    return zlib.crc32(name.encode()) % 1000000


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
        (r"\bGToken\b", "org.qw3rtrun.p3d.g.code.core.token.GToken"),
        (r"\bGWord\b", "org.qw3rtrun.p3d.g.code.core.token.GWord"),
        (None, "org.qw3rtrun.p3d.g.code.dsl.%s" % letter),
        (None, "org.qw3rtrun.p3d.g.protocol.GRq"),
        (None, "org.qw3rtrun.p3d.g.protocol.GRqDecoder"),
        # The `params.<kind>Of(letter)` readers are `internal` extensions in the marlin package,
        # over `List<GToken>` - the tokens `decodeParams` was handed, materialised once.
        # They resolved implicitly while the classes lived there; from `marlin.command` they have
        # to be imported, and only the ones a given class actually calls.
        (r"\bboolOf\(", "org.qw3rtrun.p3d.g.marlin.boolOf"),
        (r"\bdecimalOf\(", "org.qw3rtrun.p3d.g.marlin.decimalOf"),
        (r"\bhasWord\(", "org.qw3rtrun.p3d.g.marlin.hasWord"),
        (r"\bintOf\(", "org.qw3rtrun.p3d.g.marlin.intOf"),
        (r"\blongOf\(", "org.qw3rtrun.p3d.g.marlin.longOf"),
        (r"\bstringOf\(", "org.qw3rtrun.p3d.g.marlin.stringOf"),
        (r"\bstringArg\(", "org.qw3rtrun.p3d.g.marlin.stringArg"),
        (r"\bbeforeStringArg\(", "org.qw3rtrun.p3d.g.marlin.beforeStringArg"),
        (r"\bbareString\(", "org.qw3rtrun.p3d.g.code.dsl.bareString"),
        (r"\bflag\(", "org.qw3rtrun.p3d.g.code.dsl.flag"),
        (r"\btext\(", "org.qw3rtrun.p3d.g.code.dsl.text"),
        (r"\bword\(", "org.qw3rtrun.p3d.g.code.dsl.word"),
        (r"\bBigDecimal\b", "java.math.BigDecimal"),
    ]
    used = [fqn for pattern, fqn in candidates if pattern is None or re.search(pattern, text)]
    other = sorted(f for f in used if not f.startswith("java."))
    jvm = sorted(f for f in used if f.startswith("java."))
    return ["import %s" % f for f in other + jvm]


def bare_string(fields):
    """Whether these fields include the command's rest-of-line string (spec 3.4a)."""
    return any(f["kind"] == "bare" for f in fields)


def gen_class(c, name):
    """One data class implementing GRq."""
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

    # The rest-of-line string goes **last**, and that is the whole of spec 3.4a: everything to the
    # end of the line belongs to it, so a lettered parameter after it would be inside it. Last in
    # `fields` is last in the constructor, last in `encode` and last on the wire.
    if c["bareString"]:
        ktype, default, _, _ = KINDS["bare"]
        bare_name = BARE_STRING_NAMES.get(c["code"], "message")
        fields.append({
            "prop": prop(bare_name, "string", used),
            "letter": None,
            "kind": "bare",
            "ktype": ktype,
            "default": default,
            "optional": True,
            "name": bare_name,
        })

    out = []
    doc_url = "https://marlinfw.org/docs/gcode/%s.html" % c["code"].replace(".", "-")

    # The first KDoc line is the command's signature, the way the reference class writes it:
    #   M105 [R] [T<index>]
    sig = [c["code"]]
    for f in fields:
        if f["kind"] == "bare":
            piece = "<%s>" % f["name"]
        elif f["kind"] == "flag":
            piece = f["letter"]
        else:
            piece = "%s<%s>" % (f["letter"], f["name"] or "value")
        sig.append(piece if not f["optional"] else "[%s]" % piece)

    out.append("/**")
    out.append(" * %s" % " ".join(sig))
    out.append(" *")
    out.append(" * %s%s." % (c["title"], " (%s)" % c["group"] if c["group"] else ""))
    if c["bareString"]:
        bare = [f for f in fields if f["kind"] == "bare"][0]
        out.append(" *")
        out.append(" * **`%s` is a bare rest-of-line string** (spec 3.4a)." % bare["prop"])
        out.append(" * It carries no letter, it is written last because everything to the end of the")
        out.append(" * line belongs to it, and it cannot contain `;` - every parser reads that as the")
        out.append(" * start of a comment.")
    if any(not f["optional"] for f in fields):
        req = ", ".join("`%s`" % f["letter"] for f in fields if not f["optional"])
        out.append(" *")
        out.append(" * Marlin documents %s as required; every property here still defaults to" % req)
        out.append(" * absent, so that every command class is constructible bare.")
    out.append(" *")
    out.append(" * @see <a href=\"%s\">MarlinFirmare %s doc</a>" % (doc_url, c["code"]))
    out.append(" */")

    if fields:
        out.append("data class %s(" % name)
        for f in fields:
            label = "the rest of the line" if f["kind"] == "bare" else "`%s`" % f["letter"]
            if f["name"] and f["kind"] != "bare":
                label += " - %s" % f["name"]
            if not f["optional"]:
                label += " (required)"
            out.append("    /** %s */" % label)
            out.append("    val %s: %s = %s," % (f["prop"], f["ktype"], f["default"]))
        out.append(") : GRq<%s> {" % name)
    else:
        # No parameters at all: 63 commands are just their code.
        out.append("class %s : GRq<%s> {" % (name, name))

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
    if not fields:
        # No properties to compare, so the generated equality is "same command", which is what
        # keeps `X() == X()` true now that decodeParams can no longer answer `this`.
        out.append("")
        out.append("    override fun equals(other: Any?): Boolean {")
        out.append("        return other is %s" % name)
        out.append("    }")
        out.append("")
        out.append("    override fun hashCode(): Int {")
        out.append("        return %d" % stable_hash(name))
        out.append("    }")
    out.append("")
    out.append("    override fun toString(): String {")
    out.append("        return javaClass.simpleName + \"(\" + GEncoder.encode(encode()) + ')'")
    out.append("    }")
    out.append("")
    # The reading half is a property of the command *type*, not of one command, so it lives on the
    # companion - the same split GRS/GRSDecoder already uses. `%s.decode(tokens)` is the call site.
    out.append("    companion object : GRqDecoder<%s> {" % name)
    out.append("")
    out.append("        override fun head(): GParameterWord<*> {")
    out.append("            return %s.head" % head_call)
    out.append("        }")
    out.append("")
    out.append("        override fun decodeParams(tokens: Sequence<GToken>): %s {" % name)
    if fields:
        letter_list = ", ".join("'%s'" % f["letter"] for f in fields if f["letter"])
        # Materialised once: every parameter below is its own scan of the tokens, and a Sequence
        # makes no promise that it can be walked twice.
        if bare_string(fields):
            out.append("            val all = tokens.toList()")
            # A letter *inside* the rest-of-line string is text, not a parameter, so the lettered
            # ones are read from in front of it only: `M118 Hello World P1` has no `P`. A command
            # that is *nothing but* its string - M117, M23, the macros - has no such region and
            # would only get an unused `params`.
            if letter_list:
                out.append("            val params = all.beforeStringArg(%s)" % letter_list)
        else:
            out.append("            val params = tokens.toList()")
        out.append("            return %s(" % name)
        for f in fields:
            arg = letter_list if f["kind"] == "bare" else f["letter"]
            out.append("                %s = %s," % (f["prop"], KINDS[f["kind"]][3](arg)))
        out.append("            )")
    else:
        # Not `this`: the companion is not an instance of the command. A fresh one compares equal
        # by the hand-rolled equals above.
        out.append("            return %s()" % name)
    out.append("        }")
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
    # command never moves between them. Only MarlinRQ.kt below has to know they were split - the
    # classes are top-level in one package either way, so no call site changes.
    bodies = []
    all_fields = {}
    for i, c in enumerate(commands):
        body, fields = gen_class(c, names[i])
        bodies.append(body)
        all_fields[i] = fields

    # The parameterless classes carry a hand-rolled hashCode, one literal per class. Two of them
    # sharing a literal is legal but makes two commands collide in every HashMap for no reason, so
    # the generator refuses rather than emitting it.
    hashes = {}
    for i in range(len(commands)):
        if all_fields[i]:
            continue
        h = stable_hash(names[i])
        if h in hashes:
            sys.exit("hashCode collision: %s and %s both hash to %d"
                     % (hashes[h], names[i], h))
        hashes[h] = names[i]

    # One file per class. The directory is emptied first: a class renamed upstream would
    # otherwise leave its old file behind, and two classes with the same code would both compile.
    if os.path.isdir(COMMAND_DIR):
        for stale in os.listdir(COMMAND_DIR):
            if stale.endswith(".kt"):
                os.remove(os.path.join(COMMAND_DIR, stale))
    else:
        os.makedirs(COMMAND_DIR)

    for i, c in enumerate(commands):
        body = bodies[i]
        header = [
            "// GENERATED FILE - do not edit.",
            "//",
            "// Regenerate with:  python3 tools/marlin/gen_mcommands.py",
            "// Source of truth:  doc/marlin-gcode/commands.json",
            "// Extracted from:   %s @ %s" % (src["repo"], src["commit"]),
            "// How and why:      tools/marlin/README.md, github.com/qw3rtrun/p3d-web-ui/issues/13",
            "",
            "package %s" % COMMAND_PACKAGE,
            "",
        ]
        header += imports_for(c["letter"], body)
        header.append("")
        path = os.path.join(COMMAND_DIR, "%s.kt" % names[i])
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("\n".join(header) + "\n" + body + "\n")

    print("wrote %s/*.kt  (%d files, one per command class)" % (COMMAND_DIR, len(commands)))

    # The three by-letter files this replaced. Removed here rather than by hand so that a checkout
    # that still has them is repaired by a regeneration.
    for stale in ("MarlinGRQ.kt", "MarlinMRQ.kt", "MarlinTRQ.kt"):
        path = os.path.join(MARLIN_MAIN, stale)
        if os.path.exists(path):
            os.remove(path)
            print("removed %s  (superseded by command/)" % path)

    registry = [
        "/**",
        " * Every Marlin command this module knows how to write, and the lookup that reads one back.",
        " *",
        " * Three views of the same %d commands - [all], [info] and [decoders] - all built in `G`"
        % len(commands),
        " * then `M` then `T` order and all **index-aligned**, so `all[i]`, `info[i]` and",
        " * `decoders[i]` are the same command. Callers that need to pair a class with its metadata",
        " * or its decoder may rely on that.",
        " */",
        "object MarlinCommands {",
        "",
        "    /**",
        "     * One bare instance per command - every parameter absent.",
        "     *",
        "     * This is the enumeration of what the module can *write*: a bare instance encodes to",
        "     * exactly its code, which is the claim the generated cover checks class by class.",
        "     * Reading is [decoders]' job; nothing here needs an instance to decode against any",
        "     * more, because `head()` and `decodeParams()` moved to each class's companion.",
        "     */",
        "    val all: List<GRq<*>> = listOf(",
    ]
    for i, c in enumerate(commands):
        registry.append("        %s()," % names[i])
    registry += [
        "    )",
        "",
        "    /**",
        "     * The reading half: every command's companion object, which is its [GRqDecoder].",
        "     *",
        "     * A bare class name here *is* the companion - `LinearMoveG0`, not `LinearMoveG0()`.",
        "     */",
        "    val decoders: List<GRqDecoder<*>> = listOf(",
    ]
    for i, c in enumerate(commands):
        registry.append("        %s," % names[i])
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
        # `None` is the rest-of-line string's letter, because it has none; the `bareString`
        # flag in the same Info is what says the command takes one.
        letters = "".join(sorted({f["letter"] for f in all_fields[i] if f["letter"]}))
        letter_set = ("setOf(" + ", ".join("'%s'" % ch for ch in letters) + ")"
                      if letters else "emptySet()")
        registry.append('        Info("%s", "%s", %s, %s),'
                        % (c["code"], names[i], letter_set,
                           "true" if c["bareString"] else "false"))
    registry += [
        "    )",
        "",
        "    /**",
        "     * Decoders by command head, so [decode] is a map lookup and not %d comparisons."
        % len(commands),
        "     *",
        "     * **A head can be claimed by more than one class** and this keeps the first. Marlin",
        "     * documents %s once per variant - six G29 pages, one per bed-leveling" % ", ".join(
            "`%s`" % a for a in ambiguous),
        "     * system, and two each for the others - and which one a printer means depends on how",
        "     * its firmware was compiled, which no amount of reading the line can tell you. Build",
        "     * with the variant class you mean; [decode] is a best effort for the rest.",
        "     */",
        "    private val byHead: Map<GParameterWord<*>, GRqDecoder<*>> =",
        "        decoders.groupBy { GCommandParser.headKey(it.head()) }",
        "            .mapValues { (_, claimants) -> claimants.first() }",
        "",
        "    /** The codes above, whose [decode] is therefore approximate. */",
        "    val ambiguousCodes: List<String> = listOf(%s)"
        % ", ".join('"%s"' % a for a in ambiguous),
        "",
        "    /**",
        "     * The command [tokens] spell, head included, or null if no Marlin command has that head.",
        "     *",
        "     * Matching is on the head's number as written, so a non-canonical `M0105` does not",
        "     * resolve - the lexeme is part of a number's identity in this model - but not on its",
        "     * spacing, and not on its case (spec 2.2: `m104` is `M104`).",
        "     */",
        "    fun decode(tokens: Sequence<GToken>): GRq<*>? {",
        "        val all = tokens.toList()",
        "        val head = GCommandParser.headWord(all) ?: return null",
        "        val decoder = byHead[GCommandParser.headKey(head)] ?: return null",
        "        return decoder.decodeParams(all.asSequence().drop(GCommandParser.headEnd(all)))",
        "    }",
        "}",
    ]

    # The hand-written facade that lived in the registry file before it was generated. Kept in
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
        "// How and why:      tools/marlin/README.md, github.com/qw3rtrun/p3d-web-ui/issues/13",
        "//",
        "// The registry over every Marlin command and the named facade this project calls. The %d"
        % len(commands),
        "// command classes themselves are one file each under `command/`, named after the class",
        "// they hold, and are star-imported here rather than named 295 times.",
        "",
        "package org.qw3rtrun.p3d.g.marlin",
        "",
        "import org.qw3rtrun.p3d.g.code.core.token.GCommandParser",
        "import org.qw3rtrun.p3d.g.code.core.token.GParameterWord",
        "import org.qw3rtrun.p3d.g.code.core.token.GToken",
        "import org.qw3rtrun.p3d.g.marlin.command.*",
        "import org.qw3rtrun.p3d.g.protocol.GRq",
        "import org.qw3rtrun.p3d.g.protocol.GRqDecoder",
        "import java.math.BigDecimal",
        "",
    ]
    main_kt = ("\n".join(registry_header) + "\n" + "\n".join(registry) + "\n\n"
               + "\n".join(facade) + "\n")
    main_path = os.path.join(MARLIN_MAIN, REGISTRY_FILE)
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
        "import org.qw3rtrun.p3d.g.code.core.token.GCommand",
        "import org.qw3rtrun.p3d.g.code.core.token.GCommandParser",
        "import org.qw3rtrun.p3d.g.code.core.token.GToken",
        "import org.qw3rtrun.p3d.g.code.core.token.GTokenizer",
        "import org.qw3rtrun.p3d.g.code.dsl.M",
        "import org.qw3rtrun.p3d.g.marlin.command.*",
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
        "    /** A built command as the tokens a printer would receive - through the real encoder. */",
        "    private fun tokens(cmd: GCommand): Sequence<GToken> =",
        "        GTokenizer().parse(GEncoder.encode(cmd))",
        "",
        "    /** The same, less the head: what `decodeParams` is handed. */",
        "    private fun paramTokens(cmd: GCommand): Sequence<GToken> {",
        "        val all = tokens(cmd).toList()",
        "        return all.asSequence().drop(GCommandParser.headEnd(all))",
        "    }",
        "",
        "    @Test",
        "    fun `every command has a prototype`() {",
        "        assertEquals(%d, MarlinCommands.all.size)" % len(commands),
        "        assertEquals(MarlinCommands.all.size, MarlinCommands.decoders.size)",
        "        assertEquals(MarlinCommands.all.size, MarlinCommands.info.size)",
        "        // Fewer heads than classes, by exactly the variants Marlin documents separately.",
        "        assertEquals(",
        "            %d," % len({c["code"] for c in commands}),
        "            MarlinCommands.decoders.map { it.head() }.toSet().size,",
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
        "        // Through the class's own decoder and not the registry: six classes answer to",
        "        // `G29`, so the registry can only return one of them and equality would fail for",
        "        // the other five. `all` and `decoders` are index-aligned, so zip pairs each",
        "        // command with its own companion.",
        "        for ((proto, decoder) in MarlinCommands.all.zip(MarlinCommands.decoders)) {",
        "            assertEquals(proto, decoder.decodeParams(paramTokens(proto.encode()))) {",
        "                \"round trip failed for \" + GEncoder.encode(proto.encode())",
        "            }",
        "        }",
        "    }",
        "",
        "    @Test",
        "    fun `the registry resolves a head it has and no other`() {",
        "        assertNotNull(MarlinCommands.decode(tokens(M(105))))",
        "        // `M0105` is the same number written differently, and the lexeme is part of a",
        "        // number's identity here, so it deliberately does not resolve.",
        "        assertNull(MarlinCommands.decode(tokens(M(\"0105\"))))",
        "        assertNull(MarlinCommands.decode(tokens(M(998))))",
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
                # A rest-of-line string has no letter to look for, so the sample text itself is
                # what has to be on the wire - the space inside it included, which is what makes
                # it one string and not two words.
                shown = SAMPLES["bare"].strip(chr(34)) if f["kind"] == "bare" else f["letter"]
                t.append('            assertTrue(text.contains(" %s")) { "%s missing %s in $text" }'
                         % (shown, c["code"], shown))
            t.append("            assertEquals(it, %s.decodeParams(paramTokens(it.encode()))) "
                     "{ \"round trip: $text\" }" % name)
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
