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
| [`terminal-dev`](./agents/terminal-dev.md) | yes | The reactive transport — `:backend:terminal`. Flux pipelines, the send window and backpressure, hand-written Publishers, line framing, StepVerifier. |
| [`backend-dev`](./agents/backend-dev.md) | yes | The Spring application — the `api`, `core`, `management` and `frontend` submodels. WebFlux and SSE, JPA and Liquibase, Modulith boundaries, the printer aggregate, print jobs and scheduling. **Not `:app`.** |
| [`ui-developer`](./agents/ui-developer.md) | yes | The browser: Thymeleaf pages, the Vue islands, the SSE-driven dashboard and monitor, the Vite build. |
| [`lead-dev`](./agents/lead-dev.md) | **`:app` only** | Taking a feature from an issue to a mergeable branch: probe the simulator, write the spec and the cross-layer interfaces, own the integration tests, delegate each layer, review, prove it end to end, push. Also pure design questions. |

The writing agents are split by **layer, not by task**, because the layers have genuinely opposite
rules: in `:gcode` nothing throws and nothing depends on anything; in `:backend:terminal` there is no
Spring and the Reactive Streams contract is hand-rolled; in the application modules Spring and
blocking persistence are the whole point, and the rule that matters is keeping the second off the
event loop. Each agent is told to stop and hand over if the task turns out to belong to another
layer.

`protocol-dev` and `protocol-reviewer` are a pair on purpose. The reviewer has no `Edit` or `Write`
tool, so it structurally cannot "helpfully" fix what it finds — which keeps its findings honest and
keeps the fix in a diff someone chose to make. Hand a reviewer report to `protocol-dev` to act on it.

`lead-dev` sits above the rest and orchestrates them ([#46](https://github.com/qw3rtrun/p3d-web-ui/issues/46)).
It runs the nine-step procedure in its own file: probe a live simulator for evidence, write the spec
and the interfaces between layers, write the integration tests, split the work, delegate, review the
diffs, prove the feature end to end, push the branch.

**It owns `:app` and may edit nothing else.** That is the point of the split — the module that proves
the others fit together cannot be edited by the agent whose work it is proving — and it is why
`backend-dev`'s description now stops short of `:app`. For every other tree the lead prescribes and
delegates. It has the `Agent` tool so it can fan out, and it runs on the expensive model on the
explicit condition that it only does work that needs one.

## The plans of record

| Layer | Plan |
|---|---|
| `:gcode` | [GitHub issue #18](https://github.com/qw3rtrun/p3d-web-ui/issues/18) and the issues it indexes; `doc/gcode-completed.md` is what is already done |
| `:backend:terminal` | the *Known debt* section of `reactive-transport-dev` |
| The application | [`doc/backend-plan.md`](../doc/backend-plan.md) — four phases, eighteen items, in execution order. Its boundaries are [`doc/sub_boundaries.txt`](../doc/sub_boundaries.txt) |
| Integration tests | [GitHub issue #43](https://github.com/qw3rtrun/p3d-web-ui/issues/43) — one per feature, endpoint-level, mocked printers. `lead-dev` writes them |

## Skills

`skills/` holds seven, two of them vendored (see `skills-lock.json` at the repo root). Written here:
`low-level-protocol-dev` (`:gcode`'s core), `gcode-dsl-dev` (`code/dsl`, the inverse rules),
`reactive-transport-dev` (`:backend:terminal`), `spring-backend-dev` (the application modules) and
`vue-ui-dev` (the browser). Vendored: `tdd` and `kotlin-tooling-java-to-kotlin`.

Each layer skill is the **only written authority** for its layer — only `:gcode` has a spec document
— so a behaviour that callers come to depend on is recorded in the skill, in the change that
establishes it. Each also carries a *known debt* list, which doubles as that layer's backlog.

## What the agents are built from

Each loads its layer's skill (`.agents/skills/`) as its first action rather than restating it, so
the rules have one home: `low-level-protocol-dev` for the two protocol agents,
`reactive-transport-dev` for `terminal-dev`, `spring-backend-dev` for `backend-dev`, `vue-ui-dev`
for `ui-developer`. `protocol-dev` and `backend-dev` additionally load `tdd` for the red-green loop
and `kotlin-tooling-java-to-kotlin` for migration work. `lead-dev` loads whichever layer skill it is
designing into — that is why it carries no copy of the rules itself.

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
premise. Only `lead-dev` has the `Agent` tool, so only it can fan out; the layer agents cannot
delegate further, deliberately. None has `AskUserQuestion`, since a subagent cannot interact with the
user — each is told to state its assumptions plainly and report back instead.

**Two things a subagent cannot do here, and the definitions say so rather than failing quietly.**
It cannot start the Marlin simulator, so `lead-dev` is told to stop and ask the operator when
`localhost:8099` is not listening rather than designing around the gap. And it has no GitHub tools
unless they are in its own `tools:` list, so it pushes the branch — `GITHUB_MCP_PAT` from the
environment, since no credential helper is configured — and hands the PR body back to whoever
invoked it.
