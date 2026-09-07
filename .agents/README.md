# `.agents/` — Claude Code configuration

The tracked source for this repository's agent and skill definitions. Claude Code reads them through
gitignored junctions: `.claude/agents` → `.agents/agents` and `.claude/skills` → `.agents/skills`.
Keeping the real files here means the configuration is shared through git while the tool-specific
`.claude/` directory stays out of the repository.

**After a fresh clone the junctions do not exist** — they are gitignored. Recreate them:

```
scripts\link-claude-config.cmd
```

Then restart Claude Code so it re-reads the configuration.

## The agents

| Agent | Writes code? | Use it for |
|---|---|---|
| [`protocol-dev`](./agents/protocol-dev.md) | yes | Implementing and fixing low-level protocol code — tokenizer, framer, checksums, encoders, decoders. Picking up an item from `doc/todos/`. |
| [`protocol-reviewer`](./agents/protocol-reviewer.md) | **no** | Auditing protocol code against `doc/specs/GCODE_spec.md` and the style rules. Pre-commit passes, "does this match the spec", test-quality review. |

They are a pair on purpose. The reviewer has no `Edit` or `Write` tool, so it structurally cannot
"helpfully" fix what it finds — which keeps its findings honest and keeps the fix in a diff someone
chose to make. Hand a reviewer report to `protocol-dev` to act on it.

## Skills

`skills/` holds three, two of them vendored (see `skills-lock.json` at the repo root):
`low-level-protocol-dev` (written here), `tdd` and `kotlin-tooling-java-to-kotlin`.

## What the agents are built from

Both load the `low-level-protocol-dev` skill (`.agents/skills/`) as their first action rather than
restating it, so the rules have one home. `protocol-dev` additionally loads `tdd` for the
red-green loop and `kotlin-tooling-java-to-kotlin` for migration work.

Beyond the skill, each definition carries what the skill does not: the repo map, the three
authorities (spec / work queue / style), the Gradle invocations that actually work here, and a
couple of environment traps that otherwise cost a cycle each.

## House style

The protocol code is written **C-in-Kotlin**: dense, few functions, locals and loops rather than
pipelines, indices rather than intermediate collections — optimised for machine efficiency and for
transliteration to C/Rust/JS, not for reading pleasure. `protocol-dev` has the full statement of it;
`protocol-reviewer` is told not to report density or decomposition as findings, so the two do not
pull in opposite directions. If they ever do, the house style wins.

## Changing them

`model: opus` suits exacting protocol work; drop either to `sonnet` for routine items. Neither has
the `Agent` tool, so they cannot fan out further, and neither has `AskUserQuestion`, since a subagent
cannot interact with the user — both are told to state assumptions plainly and report back instead.
