#!/usr/bin/env python3
"""Distil Marlin's G-code documentation into `doc/marlin-gcode/commands.json`.

Input is a checkout of MarlinFirmware/MarlinDocumentation, whose `_gcode/*.md` files carry the
command reference as YAML front matter. Only the *interface facts* are taken -- the code, the
parameter letter, its type, whether it is optional, and the doc's short name for its value. The
prose (descriptions, notes, examples, the article body) is deliberately left upstream: those docs
are GPL-3.0 and this repository publishes a Maven artifact, so the prose is cited by URL rather
than copied.

Usage:
    curl -sSL -o docs.tar.gz \
      https://codeload.github.com/MarlinFirmware/MarlinDocumentation/tar.gz/refs/heads/master
    tar xzf docs.tar.gz
    python3 tools/marlin/extract_marlin_docs.py MarlinDocumentation-master/_gcode <commit-sha>

There is no YAML library in the loop on purpose -- this front matter is regular (fixed two-space
indentation, scalar values, one level of nesting under `parameters`), and a hand-rolled reader keeps
the tool runnable with a bare Python.
"""
import glob
import json
import os
import re
import sys

# Value types the docs use, mapped to how this project can represent them. The counts in the
# comments are from the 2026-08-24 snapshot and are what the choices were made against.
FLOAT_ALIASES = {"float", "mm", "offset", "value"}   # 317 + 18 + 8 + 1
INT_ALIASES = {"int", "style", "linear"}             # 255 + 4 + 1

KOTLIN_KEYWORDS = {
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
    "try", "typealias", "typeof", "val", "var", "when", "while",
}


def front_matter(path):
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().split("\n")
    if lines and lines[0].strip() == "---":
        lines = lines[1:]
    out = []
    for line in lines:
        if line.strip() == "---":
            break
        out.append(line)
    return out


def parse_doc(path):
    """Read one `_gcode/*.md` into {title, group, codes, parameters}."""
    doc = {"parameters": []}
    in_params = False
    param = None
    in_values = False

    for raw in front_matter(path):
        if not raw.strip():
            continue
        indent = len(raw) - len(raw.lstrip())
        line = raw.strip()
        kv = re.match(r"^([a-z_]+):\s*(.*)$", line)

        if indent == 0 and kv and not line.startswith("- "):
            key, val = kv.group(1), kv.group(2).strip()
            in_params = key == "parameters"
            param = None
            in_values = False
            if key == "codes" and val.startswith("[") and val.endswith("]"):
                doc["codes"] = [c.strip().strip("'\"") for c in val[1:-1].split(",") if c.strip()]
            elif key in ("title", "group"):
                doc[key] = val
            continue

        if not in_params:
            continue

        if indent == 0 and line.startswith("- "):
            param = {"values": []}
            doc["parameters"].append(param)
            in_values = False
            kv2 = re.match(r"^([a-z_]+):\s*(.*)$", line[2:])
            if kv2:
                param[kv2.group(1)] = kv2.group(2).strip()
            continue

        if param is None:
            continue

        if indent == 2 and line.startswith("- "):
            in_values = True
            value = {}
            param["values"].append(value)
            kv2 = re.match(r"^([a-z_]+):\s*(.*)$", line[2:])
            if kv2:
                value[kv2.group(1)] = kv2.group(2).strip()
            continue

        kv3 = re.match(r"^([a-z_]+):\s*(.*)$", line)
        if not kv3:
            continue
        key, val = kv3.group(1), kv3.group(2).strip()
        if in_values and indent >= 4 and param["values"]:
            param["values"][-1][key] = val
        elif indent == 2:
            if key == "values":
                in_values = True
            else:
                param[key] = val
                in_values = False
    return doc


def kind_of(param):
    """The one representation this project will use for a documented parameter.

    The docs are not uniform, so the order of these tests is the decision record:

    - `flag` wins outright, and a parameter with **no** `values:` block at all is a flag too --
      all 97 of those read "Flag to ..." / "Include X ..." in their descriptions.
    - `char` (M860-M869 X/Y/Z/E) is also a flag. The docs type it `char` with a value named
      `axis`, but the described usage is `M860 X` with no value, and an axis letter as the *value*
      of an `X` parameter is not a thing. Recorded here because it is a judgement call.
    - a declared scalar type wins next, widest first, so a mixed list does not lose information.
    - otherwise, a list whose value tags are all integer literals is an enumeration of ints
      (`G29 P0`..`P5`); anything else falls back to a decimal, which is the safe superset.
    """
    types = {v.get("type") for v in param["values"] if v.get("type")}
    if "flag" in types or not param["values"] or "char" in types:
        return "flag"
    if "string" in types:
        return "string"
    if "long" in types:
        return "long"
    if "bool" in types:
        return "bool"
    if types & INT_ALIASES:
        return "int"
    if types & FLOAT_ALIASES:
        return "decimal"
    tags = [v.get("tag") or "" for v in param["values"]]
    if tags and all(re.match(r"^-?\d+$", t) for t in tags):
        return "int"
    return "decimal"


def value_name(param):
    """The doc's short name for the value, e.g. `index` for `M105 T<index>`."""
    for v in param["values"]:
        tag = v.get("tag")
        if tag and re.match(r"^[a-z][a-z0-9]*([ \-_][a-z0-9]+)*$", tag):
            return tag
    return None


def examples(path):
    """The example command lines a doc page shows.

    Two shapes: `code: G28 X Y` inline, and a `code: |` block whose command lines are the ones
    prefixed `>` (the rest of the block is the firmware's reply). Trailing `;` comments are cut,
    since it is the command that is being collected and not the note beside it.

    These are the independent check on the extracted parameter letters: they are what Marlin's own
    authors typed, so a letter appearing here that no command class models is a real gap.
    """
    out = []
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().split("\n")
    in_block = False
    for raw in lines:
        stripped = raw.strip()
        m = re.match(r"^code:\s*(.*)$", stripped)
        if m:
            rest = m.group(1).strip()
            if rest == "|":
                in_block = True
                continue
            in_block = False
            if rest:
                out.append(rest)
            continue
        if in_block:
            if not raw.startswith(" ") and stripped:
                in_block = False
            elif stripped.startswith(">"):
                out.append(stripped.lstrip("> ").strip())
            continue
    cleaned = []
    for line in out:
        line = line.split(";")[0].strip()
        # Only lines that actually start with a command word are useful here.
        if re.match(r"^[A-Za-z]\d", line):
            cleaned.append(line)
    return cleaned


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    docs_dir = sys.argv[1]
    commit = sys.argv[2] if len(sys.argv) > 2 else "unknown"

    commands = []
    skipped = []
    example_lines = []
    for path in sorted(glob.glob(os.path.join(docs_dir, "*.md"))):
        example_lines.extend(examples(path))
        doc = parse_doc(path)
        codes = doc.get("codes") or []
        title = doc.get("title")
        if not codes or not title:
            skipped.append({"file": os.path.basename(path), "why": "no codes or no title"})
            continue

        params = []
        bare_string = False
        for p in doc["parameters"]:
            letter = (p.get("tag") or "").strip().strip("'\"")

            # A handful of commands take a rest-of-line string with no parameter letter in front
            # of it -- M117's message, M23/M28/M30/M928's filename, M33's path. The docs model it
            # as a pseudo-parameter named `string` / `filename` / `path`. Spec 3.4a covers the
            # form and it is deferred to todo 09, so the model cannot hold it yet; record that the
            # command wants one so the generated class can say so, rather than dropping it.
            if letter.lower() in ("string", "filename", "path", "message"):
                bare_string = True
                continue

            # M17 / M18 put the whole axis set in one tag: 'X, Y, Z, E, A, B, C, U, V, W'.
            # Each is an independent flag, so expand rather than lose all ten.
            if "," in letter:
                pieces = [x.strip() for x in letter.split(",")]
                if all(re.match(r"^[A-Za-z]$", x) for x in pieces) and pieces:
                    for x in pieces:
                        params.append({"letter": x.upper(), "kind": "flag",
                                       "optional": p.get("optional", "true") != "false",
                                       "name": None})
                    continue

            # A parameter tag is one letter. Anything else -- `B1`, `A1`, `E1`, which are a letter
            # with a fixed value baked in -- has no faithful shape here.
            if not re.match(r"^[A-Za-z]$", letter):
                skipped.append({"file": os.path.basename(path),
                                "why": "parameter tag is not a single letter: %r" % letter})
                continue
            params.append({
                "letter": letter.upper(),
                "kind": kind_of(p),
                "optional": p.get("optional", "true") != "false",
                "name": value_name(p),
            })

        # One letter can be documented twice in a file (different conditions). Keep the first.
        seen = set()
        deduped = []
        for p in params:
            if p["letter"] in seen:
                skipped.append({"file": os.path.basename(path),
                                "why": "duplicate parameter letter %r, kept the first" % p["letter"]})
                continue
            seen.add(p["letter"])
            deduped.append(p)
        params = deduped

        for code in codes:
            # A command is a letter plus an unsigned number, optionally with a subcode (spec 4.1).
            # `T?`, `Tc`, `Tx` (MMU2) and `M43 T` are not, and no builder can express them.
            if not re.match(r"^[A-Za-z]\d+(\.\d+)?$", code):
                skipped.append({"file": os.path.basename(path),
                                "why": "not a letter+number command: %r" % code})
                continue
            commands.append({
                "code": code,
                "letter": code[0].upper(),
                "number": code[1:],
                "title": title,
                "group": doc.get("group"),
                "shared": len([c for c in codes if re.match(r"^[A-Za-z]\d+(\.\d+)?$", c)]) > 1,
                "bareString": bare_string,
                "params": params,
            })

    commands.sort(key=lambda c: (c["letter"], float(c["number"])))
    out = {
        "_comment": "GENERATED by tools/marlin/extract_marlin_docs.py -- do not edit by hand.",
        "source": {
            "repo": "MarlinFirmware/MarlinDocumentation",
            "commit": commit,
            "docs": "https://marlinfw.org/meta/gcode/",
            "license": "GPL-3.0",
            "extracted": "interface facts only (code, parameter letter, type, optional, value "
                         "name); prose is not copied and is cited by URL",
        },
        "commands": commands,
        "examples": sorted(set(example_lines)),
        "skipped": skipped,
    }
    dest = os.path.join("doc", "marlin-gcode", "commands.json")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(out, fh, indent=2, sort_keys=False)
        fh.write("\n")

    kinds = {}
    for c in commands:
        for p in c["params"]:
            kinds[p["kind"]] = kinds.get(p["kind"], 0) + 1
    print("wrote %s" % dest)
    print("  commands: %d  (G %d, M %d, T %d)" % (
        len(commands),
        sum(1 for c in commands if c["letter"] == "G"),
        sum(1 for c in commands if c["letter"] == "M"),
        sum(1 for c in commands if c["letter"] == "T")))
    print("  parameter slots: %d  kinds: %s" % (
        sum(len(c["params"]) for c in commands), kinds))
    print("  example command lines: %d" % len(set(example_lines)))
    print("  skipped: %d" % len(skipped))
    for s in skipped:
        print("     %s: %s" % (s["file"], s["why"]))


if __name__ == "__main__":
    main()
