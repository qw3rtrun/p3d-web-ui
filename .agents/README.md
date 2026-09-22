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
| [`protocol-dev`](./agents/protocol-dev.md) | yes | Implementing and fixing low-level protocol code — tokenizer, framer, checksums, encoders, decoders. Picking up an item from the :gcode issue queue. |
| [`protocol-reviewer`](./agents/protocol-reviewer.md) | **no** | Auditing protocol code against `doc/specs/GCODE_spec.md` and the style rules. Pre-commit passes, "does this match the spec", test-quality review. |
| [`lead-dev`](./agents/lead-dev.md) | **no** (notes only) | Design, architecture, module boundaries, refactoring strategy, concurrency and reactive-pipeline design. Turning a vague ask into task notes someone can execute. |

The first two are a pair on purpose. The reviewer has no `Edit` or `Write` tool, so it structurally
cannot "helpfully" fix what it finds — which keeps its findings honest and keeps the fix in a diff
someone chose to make. Hand a reviewer report to `protocol-dev` to act on it.

`lead-dev` sits above both. It has `Write` for notes and designs but **no `Edit`**, so it cannot
drift into implementing what it just designed; it has the `Agent` tool instead and is told to hand
execution to `protocol-dev`, `Explore` or `general-purpose`. Its output is a decision plus a task
note — `:gcode` work goes into the GitHub issue queue and its index issue, everything else somewhere
adjacent and linked. It runs on the expensive model on the explicit condition that it only does work
that needs one.

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

`model: opus` suits exacting protocol work; drop `protocol-dev` or `protocol-reviewer` to `sonnet`
for routine items — `lead-dev` is opus by definition, since being the thinking agent is its whole
premise. Neither protocol agent has the `Agent` tool, so they cannot fan out further; `lead-dev`
does, deliberately, so it can delegate execution. None has `AskUserQuestion`, since a subagent cannot
interact with the user — all three are told to state assumptions plainly and report back instead.
