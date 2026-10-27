# Backend plan of record

The work queue for the five submodels of `doc/sub_boundaries.txt` — `api`, `core`,
`printer-management`, `frontend` and `app` — and the modules this plan adds. The style authority is the
[`spring-backend-dev`](../.agents/skills/spring-backend-dev/SKILL.md) skill; the agent that executes
it is [`backend-dev`](../.agents/agents/backend-dev.md). The `:gcode` queue lives in
[GitHub issue #18](https://github.com/qw3rtrun/p3d-web-ui/issues/18) and is not duplicated here.

**The numbers are an execution order**, chosen so that no item has to be reopened because a later one
changed a type underneath it. Each is one self-contained piece of work with a goal, a reason, and a
definition of done, and each should land as its own commit with its tests green.

---

## What the product is

A control plane for a **group** of FDM 3D printers. Three verbs:

| Verb | What a user does | What exists today |
|---|---|---|
| **Set up** | register a machine, learn what it is, describe what it can print, group it | name, host, port, and a `connected` flag |
| **Assign jobs** | store a G-code file, queue it, send it to a machine that can run it | **nothing** |
| **Control** | connect, start/pause/cancel, set temperatures, watch it work | connect, set hotend/bed temperature, watch temperatures |

The protocol below it is finished and well tested — `:gcode` is 927 tests and a written spec, and
`:backend:terminal` now numbers, checksums and replays blocks against a real firmware. **The gap is
entirely above the transport.**

### Out of scope for this plan

Slicing, camera streams, cloud sync, filament inventory, and multi-tenant isolation beyond ordinary
authentication. Named so nobody has to ask twice.

---

## Where we are

Verified by reading the source, not assumed:

- **One printer, one page.** `PrinterController` exposes `/api-printer/{id}` — state, an SSE stream,
  set-temp, set-bed-temp, connect, report-temp. `FDMPrinterManagementController` does CRUD over
  printer rows. Thymeleaf pages list them and link to a monitor page.
- **The live channel is SSE**, consumed by `EventSource` in `static/js/lib/client.js`. The
  WebSocket handler is dead code and cannot work — it reads a session attribute nothing writes.
- **Nothing about a printer survives a restart except its row.** `PrinterAggregateManager` is
  `PrinterState::new`; firmware info, capabilities and every temperature are in-memory only.
- **Every database call runs on the Netty event loop.** There is no `subscribeOn` and no
  `boundedElastic` anywhere in `backend/` or `app/`.
- **Spring Modulith is five dependencies and a logging level.** No `@ApplicationModule`, no
  `@NamedInterface`, no `ApplicationModules.verify()`.
- **Test coverage of this layer is 19 tests** — `:backend:api` 13, `:backend:printer-management` 1 (a
  context load that asserts nothing), `:app` 1 — against `:gcode`'s 927 and `:backend:terminal`'s 41.

### Two structural facts worth knowing before anything is planned on top

**`:backend:api` and `:backend:core` share a package tree.** `:backend:api` owns
`org.qw3rtrun.p3d.core` itself — `ApiApplication`, `PrinterController`, `PrinterState` — while
`:backend:core` owns its subpackages `bus`, `model`, `msg`, `service`. A split package across two
jars is already a JPMS hazard; more immediately, **Spring Modulith decides what a module is by
package**, so it would see one module spanning both Gradle modules and could describe neither.

**`PrinterState` — the aggregate — lives in the web module.** It holds connection state, firmware
info, capabilities and temperature control, and it is constructed by a lambda in `ApiApplication`.
Everything in phase B and C needs it; none of them should need WebFlux to get at it.

---

## The queue

| # | Item | What it is | Blocks |
|---|---|---|---|
| ~~**A1**~~ | [package split and module boundaries](#a1) | ~~Move `:backend:api` out of `org.qw3rtrun.p3d.core`; declare `@ApplicationModule`s; add `ApplicationModules.verify()`~~ **done** — four modules declared, each depending on nothing but the shared kernel, and the verification bites | A2–D3 |
| ~~**A2**~~ | [blocking work off the event loop](#a2) | ~~`subscribeOn(boundedElastic)` at every JPA boundary, and a test that catches the next one~~ **done** — three controllers, nine call sites, and a test that records the thread | C1, C2 |
| ~~**A3**~~ | [the reactor has an owner](#a3) | ~~Stop and evict a `PrinterReactor` on disconnect and delete; bound the map~~ **done** — `openReactor`/`release`, driven by an `MDeletedEvent` | B3 |
| ~~**A4**~~ | [delete the half-built](#a4) | ~~The dead WebSocket handler, the null-returning stub service, the one-entry command bus~~ **done** — seven files gone, nothing replaced them | A5 |
| ~~**A5**~~ | [one dispatch, checked by the compiler](#a5) | ~~Replace `PrinterReactor`'s reflective invoker with a sealed interface and an exhaustive switch~~ **done** — a pattern switch, in the aggregate rather than the reactor | B1 |
| ~~**A6**~~ | [close the two declared crossings](#a6) | ~~`core` gets a replica fed by events; `frontend` reaches `core` through an `api` interface; `api` closes so the check enforces both~~ **done** — no submodel names another, and the check says so | B2 |
| ~~**B1**~~ | [the aggregate leaves the web module](#b1) | ~~`PrinterState` into a domain module that does not depend on WebFlux~~ **done** — it is the `core` submodel now | B2, C3 |
| ~~**B2**~~ | [the machine remembers what it is](#b2) | ~~Persist firmware, capabilities and last-known state; restore on boot~~ **done** — the roster keeps it, `core` seeds from it | B4, D1 |
| ~~**B3**~~ | [a connection lifecycle worth the name](#b3) | ~~An explicit state machine, retry with backoff, health, and events for each transition~~ **done** - one enum, bounded retry, and a roster column that is derived rather than guessed | C5 |
| ~~**B4**~~ | [machine profile and groups](#b4) | ~~Bed volume, extruders, nozzle, materials, tags — what a scheduler matches against~~ **done** — a declared spec beside the reported profile, neither able to write the other | D1 |
| ~~**C1**~~ | [the G-code file store](#c1) | ~~Upload, content hash, size, line count, estimates; streamed in and out, never buffered~~ **done** — a fifth module, content-addressed on disk, one chunk in flight | C2 |
| ~~**C2**~~ | [the job and its lifecycle](#c2) | ~~A job entity, a state machine, and the events each transition publishes~~ **done** — a sixth module, a typed refusal instead of an exception, and the event registry on | C3 |
| **C3** | [the streaming engine](#c3) | A file fed through `PublisherQueue` at `BACKGROUND` while interactive commands preempt | C4, C5 |
| **C4** | [progress and telemetry](#c4) | Lines accepted, elapsed, estimated remaining — driven by acknowledgements, not by what was sent | C5 |
| **C5** | [pause, cancel, and survive a disconnect](#c5) | The operations an eight-hour print actually needs | D1 |
| **D1** | [assignment](#d1) | Match a queued job to a capable, idle machine; a farm queue and a per-machine queue | D2 |
| **D2** | [the farm view](#d2) | One live stream for N printers instead of N streams of one | — |
| **D3** | [operations](#d3) | Authentication, a real database, and the observability the dependencies already allow for | — |

---

## Phase A — foundations

Nothing here adds a feature. All five exist because the layer has defects that get more expensive
the more is built on them, and because phases B–D add modules to a boundary that is currently
undeclared.

### A1 — package split and module boundaries {#a1}

**Goal.** `:backend:api` moves out of `org.qw3rtrun.p3d.core` into a package of its own. Each
Gradle module declares its Modulith boundary in a `package-info.java`, exports its public surface
with `@NamedInterface`, and a single test asserts the whole thing.

**Why.** Modulith reads packages, not Gradle modules, and today two Gradle modules share one package
tree — so there is no boundary for it to verify and no way to state one. Everything phases B–D add
is a new module; installing the boundary now costs one rename, and installing it at ten modules
costs ten.

It also settles a question the repo keeps paying for in small ways: `MachineManagementModule`'s
`@Bean` wiring, `ApiApplication`'s `@ConditionalOnMissingBean` stub, and the `ObjectFactory`
indirection in `FDMPrinterManagementController` are all workarounds for a boundary nobody declared.

**Decide first (see [Decision 1](#d1-modulith)):** whether Modulith is kept at all. If it is
dropped, this item shrinks to the package rename, which is worth doing on its own.

**Done when.** `ApplicationModules.of(StandaloneApp.class).verify()` passes as an ordinary unit
test; `./gradlew build` is green; the Modulith actuator endpoint lists the modules; no package is
owned by two Gradle modules. Also fix `machine-mng/build.gradle`'s `mainClass`, which names
`org.qw3rtrun.p3d.machine-mng.PrintersmngApplication` — a package with a hyphen in it, and a class
that does not exist.

**Done.** Four modules — `core` (open, shared), `api`, `mng`, `mvc` — with `:gcode` and
`:backend:terminal` excluded from the model, because neither may depend on Spring and so neither can
carry a Modulith annotation; they are consumed as libraries. All three closed modules declare
`allowedDependencies = {}`, which is Modulith's "no other application module", and that is true
today: every cross-module call goes through the shared kernel. The check was proved to bite before
it was trusted — `mvc` importing `PrinterEntity` fails with *"Allowed targets: core"*. 987 tests
green across the repo.

**Two things learned while doing it**, recorded because the next module will hit both.
`@Modulithic(sharedModules = …)` exempts a module from the **dependency** check but does **not**
open its nested packages, so `core` needed `@ApplicationModule(type = OPEN)` as well — the two
settings solve different halves and a shared kernel wants both. And the default for
`allowedDependencies` is a sentinel string (`¯\_(ツ)_/¯`) meaning "unrestricted", so an explicit
`{}` is how a module says it depends on nothing.

### A2 — blocking work off the event loop {#a2}

**Goal.** Every JPA call reached from a reactive path runs on `boundedElastic`.

**Why.** WebFlux serves requests on a handful of event-loop threads; blocking one blocks every
request sharing it. `Mono.fromSupplier(...)` is lazy and runs its supplier on the subscribing
thread, so the three `Mono.fromSupplier` / `Flux.fromStream` / `Mono.fromRunnable` wrappers in
`FDMPrinterManagementController` move nothing, and `MvcPrinterController` does not even wrap — it
calls `manager.fetchAll()` from a handler body. At one printer and one user this is invisible. It is
the first thing that breaks at forty.

**Done when.** `grep -rn 'fromSupplier\|fromStream\|fromRunnable' backend/ app/ --include=*.java`
shows a scheduler on every hit; a `BlockHound` agent (or an equivalent assertion) runs in the test
task and fails on a blocking call from a non-blocking thread; the management endpoints still pass a
`WebTestClient` test.

**Done.** Three controllers and nine call sites. `FDMPrinterManagementController` gained one
`offLoop` helper; `PrinterController` defers `getReactor` into a `Mono` - it was not merely
unscheduled but *eager*, running a JPA read inside a `synchronized` block before the returned
publisher existed; `MvcPrinterController`'s four handlers read the printer list through one
scheduled supplier, draining the JPA stream inside it so a template cannot iterate a lazy stream
during rendering.

**No BlockHound.** It needs a JVM agent and `-XX:+AllowRedefinitionToAddDeleteMethods`, and what it
proves - "something blocked on a non-blocking thread" - is weaker than what is wanted here.
`BlockingBoundaryTest` instead records `Thread.currentThread()` from inside a fake
`MachineManagementService` and insists on a `boundedElastic` worker. That tests the property
directly, needs no agent, and is deterministic: removing a single `subscribeOn` fails four of its
seven cases, which was checked before the test was trusted.

### A3 — the reactor has an owner {#a3}

**Goal.** A `PrinterReactor` is stopped and evicted when its printer is disconnected or deleted.

**Why.** `PrinterReactorManager.getReactor` does `computeIfAbsent` into a `HashMap` and nothing ever
removes an entry. Deleting a printer through `/api-manager/{uuid}` leaves a live reactor, a live TCP
connection, a `Schedulers.single()` thread and a replay sink behind it, and `getReactor` on a fresh
id *connects* as a side effect — so a query path can open a socket.

**Done when.** Delete and disconnect both release the reactor and its socket; a test asserts the map
is empty afterwards and that the `Schedulers.single()` worker is disposed; `getReactor` no longer
connects implicitly, or is renamed to say that it does.

**Done, with two corrections to the above.** `Schedulers.single()` is Reactor's *shared* scheduler,
not one per reactor, so there was never a worker to dispose — what leaked was the socket, the send
window and the replay sink. And **disconnect does not release**: a disconnected printer is a state
the machine can come back from, and its aggregate is worth keeping; only a *deleted* one gives up
its runtime. `MachineManagementService.disconnect` turns out to be called by nothing in production
anyway, which is a B3 finding recorded in the skill.

`getReactor` is now `openReactor`, paired with `release`, and `management` publishes `MDeletedEvent`
rather than reaching across the boundary. Releasing completes the update sink, so an SSE subscriber
is told the machine is gone instead of waiting on it — which is both the correctness question and
the leak, since `Sinks.Many` keeps every subscriber referenced until it terminates.

Proved end to end against the assembled app, not only in unit tests: creating a printer, opening its
runtime, then deleting it logs `releasing the runtime of <id>` from `boundedElastic-1`. The unit
tests were checked to bite first — turning `release` into a lookup fails two of them.

### A4 — delete the half-built {#a4}

**Goal.** Remove `WSGPrinterHandler` and its handler-mapping bean, `ApiApplication`'s stub
`MachineManagementService`, and `CommandQueryBus`/`CommandQueryService`/`CommandQueryGateway` with
its single registration.

**Why.** Each is a thing that looks like infrastructure and is not. The WebSocket handler cannot
work and nothing connects to it. The stub answers every management query with `null` under
`@ConditionalOnMissingBean`, so a misconfiguration reads as an empty farm instead of a failure. The
bus holds one entry, `UUID → Descriptor`, dispatches by predicate with an unchecked cast, and
ignores the response type it is handed — `PrinterReactorManager` could call
`MachineManagementService.fetch` directly and be shorter and type-safe.

If a WebSocket channel is wanted later it is a day's work on top of a working SSE contract, and it
will be written against the farm stream of **D2** rather than the per-printer one.

**Done when.** The three are gone, `:backend:api` and `:app` still boot, the SSE stream and the
management endpoints still pass their tests, and `spring-backend-dev`'s debt list loses three
entries.

**Done.** Seven files deleted and nothing put in their place. `PrinterReactorManager` now calls
`MachineManagementService.fetch` - the interface the bus's single registration pointed at all
along - and `FDMPrinterManagementController` injects that service directly, which is the
`ObjectFactory` indirection gone too and the A2 follow-up the skill recorded.

**One thing the deletion exposed**, which is the point of deleting a stub: `BootVuejsApplicationTests`
started failing, because the frontend submodel cannot boot without a `MachineManagementService` and
what answers it lives in `printer-management`. The stub had been hiding that, in *main*, by
answering every query with `null`. The test supplies the bean itself now, and its version throws
rather than returning empties - a module needing a collaborator is a fact about the design, not
something to paper over with a shipped bean.

Verified on the assembled app: create, list, delete still work with no stub behind them, the
dashboard renders, and `/ws-printer/{id}` is a 404.

### A5 — one dispatch, checked by the compiler {#a5}

**Goal.** Replace `PrinterReactor.invoke`'s reflective invoker with a sealed interface over the
messages `PrinterState` handles and an exhaustive `switch`.

**Why.** The invoker reflects over `PrinterState.class.getDeclaredMethods()` at construction, keys
handlers by parameter type, and resolves a message by a breadth-first walk of its supertype graph.
Nothing about it is checked: renaming a handler method silently unregisters it, and a `:gcode` reply
class that gains an interface changes which handler wins. The only symptom is `No invoker for` at
WARN — which is exactly how the temperature-report regression documented in `findInvoker`'s javadoc
reached production.

Java 25 has sealed interfaces and pattern-matching `switch`; `HostTerminal.ReplaySubscriber.onNext`
in `:backend:terminal` is the shape to copy.

**Done when.** No reflection in the dispatch path; adding a message type without handling it fails
the build; the existing `PrinterReactorDispatchTest` passes unchanged or is replaced by a stronger
one.

**Done, and the switch moved somewhere the item did not anticipate.** It is in `PrinterState`, not
`PrinterReactor` - routing a message is the business of whatever acts on it, and the reactor's job
is to carry messages rather than work out who wants them. That is also what made it checkable: the
handlers and the arms are now in one class, so a renamed handler fails the build
(`no suitable method found for on(TemperatureReportedEvent)`), which was verified by renaming one.
`PrinterReactor` lost 60 lines and all of its reflection.

**One part of the done-when is not met, and should not be.** "Adding a message type without
handling it fails the build" needs sealing, and neither type can be sealed for a good reason:
`GEvent` sealed would close the application's event vocabulary to the modules about to extend it
(C2 adds job events), and `GRq` has 295 implementations in `:gcode`. So both switches carry a
`default`, and the two differ on purpose - an unhandled event is a debug line because most replies
are genuinely not the aggregate's business, while an unhandled command is a warning because a user
asked for it and nothing happened.

The test moved with the dispatch, to `PrinterStateDispatchTest`, and gained the half the reflective
version never had one for: that a **command** routed through the dispatch still reaches the wire.

### A6 — close the two declared crossings {#a6}

**Goal.** `allowedDependencies = {}` on all three closed submodels, which is rule 1 of
`doc/sub_boundaries.txt` holding without exception.

**Why.** The realignment declared two crossings rather than hiding them, and each needs a different
fix:

* **`frontend` → `core`.** The controllers call `PrinterReactorManager` directly. Closing it means
  an interface in `api` carrying what they actually need — the state, the update stream, and a way
  to hand a command to one machine. **Settle first whether `api` may carry Reactor types**, because
  every one of those methods is a `Mono` or a `Flux`. Today `api` depends on nothing at all, and
  `:gcode` depends on `api`, so adding Reactor there would put Reactor on the protocol core's
  compile path — which is the one thing the layering forbids. The likely answer is a separate
  package, or `Publisher` from the Reactive Streams API rather than Reactor's own types.
* **`core` → `management`.** `PrinterReactorManager` asks `MachineManagementService` where to dial.
  Rule 2 says core communicates through events, so management publishes `PrinterRegistered`,
  `PrinterChanged` and `PrinterDeleted`, and core keeps its own small replica of the static data it
  needs — id, name, host, port, and later capabilities. **The open question is bootstrap**: a replica
  is empty on restart, so either management re-publishes its rows on startup, or Modulith's event
  publication registry replays them. Decide that before writing the replica, not after.

Neither crossing is visible to the verification today, because both go through types that live in
`api` and `api` is open. **Closing them is also what makes them checkable**: once nothing in `core`
names a service, `api` can become a closed module with a `@NamedInterface` per nested package, and
`core` can be denied the service package outright.

**Done when.** All three closed modules declare `allowedDependencies = {}`; `api` is closed with
named interfaces; `core` starts a printer from its own replica with the management module stubbed
out entirely in the test.

**Half done: `core` no longer queries `management`.** `PrinterDirectory` is the replica, fed by
`MRegisteredEvent` and `MDeletedEvent`. `PrinterReactorManagerTest` has no management module in it
at all - no service, no repository, no fake - which is the clause above and the real payoff: this
module can be started and reasoned about without a database behind it.

Two things fell out of it. `PrinterAggregate` and `PrinterAggregateManager` **moved from `api` into
`core`**: nothing outside `core` ever used them, so they were core's internal seam parked in the
shared kernel, and moving them leaves `api.service` holding exactly `MachineManagementService` -
which is what a named-interface split needs. And `openReactor` **stopped opening sockets**: the
auto-connect branch read `descriptor.connected()`, which the A3 finding showed is never written in
production, so the replica simply does not carry it. A query path no longer dials a machine.

**Bootstrap, settled by running rather than argued.** `printer-management` re-announces every row on
`ApplicationReadyEvent`, so `MRegisteredEvent` is a statement of current fact rather than of a
change and arriving twice is normal. Verified across a real restart: a printer created in one
process is reachable in the next, which never received a live registration for it.

**One failure only running could have found.** The first version used
`@TransactionalEventListener`, which is the textbook choice - and with `spring-modulith-starter-jpa`
on the persistence module's classpath it silently switches on Modulith's *durable event publication
registry*, which writes every such event to an `EVENT_PUBLICATION` table. Liquibase owns this schema
and has no changeset for it, so registering a printer died on commit with
`Table "EVENT_PUBLICATION" not found`. The replica is derived state rebuilt at every startup, so it
wants in-process notification and not durable publication; a plain `@EventListener` is the right
tool, at the cost of firing before the commit. **The registry is still wanted for C2 and C5** - job
events that must survive a restart - and this records what turning it on will need: a changeset for
its table.

**Done: both crossings closed, and the check enforces them.**

`frontend` names no submodel. Its controllers go through `PrinterControl` in `api`, and
`:backend:core` and `:gcode` are off its compile path entirely. Closing it needed the thing that
made this item bigger than it looked: an **application command model**. The obvious interface could
not be written, because what a controller wanted to send was a `:gcode` command class and `api` must
not depend on `:gcode`. So `PrinterCommand` is a sealed set of four records in `api`, `frontend`
binds those as request bodies, and `PrinterControlService` in `core` translates them into Marlin's -
the outbound twin of `ReplyEvents` in `:backend:terminal`.

That switch **is** exhaustive with no default arm, which is the property A5 wanted and could not
have: `PrinterCommand` is sealed and `api` owns every case, unlike `GEvent` (which must stay open to
modules still to be written) and `GRq` (295 implementations).

`api` is closed now, exporting `model`, `msg`, `management` and `control` as named interfaces, and
is no longer `sharedModules` - that exemption was what let both crossings hide. Each module lists
the interfaces it uses, and the check was proved to bite: `core` naming `MachineManagementService`
fails with *"Allowed targets: api :: model, api :: msg, api :: control"*.

**It fixed a second silent bug, the same shape as the first.** `/auto-report-temp` bound `:gcode`'s
generated class, whose property is `seconds`, while the browser has always posted `{period}` - so
the field matched nothing and every request silently asked for the firmware's default instead of the
period the user chose. Naming the field `period`, in a record the application owns, fixes it;
`PrinterCommandBindingTest` now posts the browser's exact payloads and pins what they become. This
is the second contract that drifted because generated `:gcode` classes were exposed directly (the
first was `TemperatureReported`), which is the argument for the two bridges rather than one shared
model.

**A finding for B3**, from running the assembled app: a command sent to an *offline* printer is a
500 with a `NullPointerException`, because `PrinterState` holds its `GSender` only while connected.
The request binds and routes correctly; the aggregate simply has no way to refuse work it cannot do.

**Reactor in `api`: settled.** `org.reactivestreams.Publisher` - the interop SPI `Mono` and `Flux`
implement, not a framework. A `Flux` already *is* a `Publisher` and WebFlux already accepts one, so
neither side adapts anything, and `printer-management` does not get Reactor on its compile path.

**Previously recorded as remaining, now done:**

* **`frontend` → `core` needs an `api` command model.** The obvious fix is an interface in `api`
  carrying what the controllers need, but `send` takes a `:gcode` command class, and `api` must not
  depend on `:gcode`. So closing this crossing means api-level commands - `SetHotendTarget`,
  `SetBedTarget` and so on - with `frontend` building them and `core` mapping them to Marlin
  commands. That is the **outbound** half of the two-models split, matching the inbound bridge in
  `:backend:terminal`, and it also fixes something the skill already flags: today the REST contract
  follows the generated Marlin command set, so a generated-class rename is a breaking API change.
* **Making either crossing enforceable needs `api` closed.** Both go through types in the shared
  module, so the dependency check cannot see them. Closing `api`, exporting `model`, `msg` and
  `service` as `@NamedInterface`s and dropping `sharedModules` would let `core` be denied
  `api::service` outright. Mechanical, but it touches every module's `allowedDependencies`.

**Reactor in `api` is no longer the blocker it was.** When this item was written, `:gcode` depended
on `api`, so Reactor there would have reached the protocol core. `:gcode` no longer does. The
remaining question is smaller - whether `printer-management` should carry Reactor on its compile
path - and `org.reactivestreams.Publisher` sidesteps it if not.

---

## Phase B — the machine as a durable thing

The "set up" verb. After phase B a printer is something the system knows, not just something it can
dial.

## The submodel realignment

Between A1 and A2 the module structure was realigned to
[`doc/sub_boundaries.txt`](./sub_boundaries.txt), which named five submodels the repository did not
have. What changed, once:

| Submodel | Gradle module | Package | Was |
|---|---|---|---|
| **api** | `:backend:api` | `org.qw3rtrun.p3d.api` | `:backend:core` — the shared kernel |
| **core** | `:backend:core` | `org.qw3rtrun.p3d.core` | the aggregate and runtime, stranded in the web module |
| **printer-management** | `:backend:printer-management` | `org.qw3rtrun.p3d.management` | `:backend:printer-management` |
| **frontend** | `:frontend:web-ui` | `org.qw3rtrun.p3d.frontend` | controllers in `:backend:api`, pages in `:app` |
| **app** | `:app` | `org.qw3rtrun.p3d` | the same, plus templates and static assets |

`api` and `core` **exchanged meaning**, which is the confusing part of reading the history: what was
called core is now api, and core is a new thing. `:gcode` and `:backend:terminal` are unchanged and
stay outside the module model as libraries.

Three things the move forced, each a real finding:

* **`:gcode` depends on the shared kernel**, for the event interfaces its reply types implement.
  Repointing it at the old `:backend:core` — which is now the aggregate — produced a dependency
  cycle, and that is the reason `api` must stay free of Spring and Reactor: anything added there is
  on the protocol core's compile path.
* **`ApiApplication` could not move as a unit.** It was a `@SpringBootApplication` in what is now
  the shared API module, wiring three submodels' beans from one place. It split along the boundary
  it was straddling: `CoreConfiguration` and `FrontendConfiguration`, every bean carried over
  unchanged including the two A4 deletes.
* **A nested package must not carry `@ApplicationModule`.** The annotation is what creates a module,
  so the one that travelled with `mvc` made Modulith report `frontend.mvc` as a module beside its
  own parent.

B1 is done as a side effect — the aggregate is the `core` submodel now — and A6 is new: the
realignment declared two crossings rather than hiding them, and closing them is what makes rule 1
hold without exception.

Verified by running: 987 tests green, and the assembled app serves the dashboard, the static assets
and the management API, with a printer POSTed, listed and deleted through real JPA rather than the
stub.

---

## Two models, and the bridge between them

A correction to the realignment above, taken after it landed:
**`:gcode` must not depend on any backend module, `api` must not depend on `:gcode`, and
`:backend:terminal` is the bridge that converts between them.**

What it was. `GRs` - the root of every decoded reply - extended `api`'s `GEvent`; `OkRs` extended
`OKReceivedEvent`; `WaitRs` extended `WaitReceivedEvent`; `TemperatureRs` implemented
`TemperatureReportedEvent` and carried `hotend()`/`bed()` methods that existed for no reason except
to satisfy it; `FirmwareInfoRs` and `CapabilityRs` the same. Five files, seven borrowed types, and a
`implementation project(':backend:api')` in `gcode/build.gradle`.

What that cost: the one module whose entire claim is that it depends on nothing depended on the
application's vocabulary, so every change to what the host cares about reached into the protocol -
and the protocol's class hierarchy became something the application's dispatch had to know about.
`PrinterReactor.findInvoker` walks a supertype graph *because* `OkTemperatureRs` reached
`TemperatureReportedEvent` two interfaces out.

What it is now:

| | models | because |
|---|---|---|
| `:gcode` `GRs` | what the wire can say: seven sensor kinds, a map of hotends, nullable targets, `BigDecimal` | it is what a printer sends, and the module ports to JS, C and Rust |
| `api` `GEvent` | what the application cares about: one hotend, one bed, no nulls, `double` | it is the host's vocabulary, and it changes when the product does |

`:backend:terminal` routes `GRs` internally - the send window still matches `OkRs`, `BusyRs`,
`ResendRs` - and publishes `api` events: `messageFlux()` is `Flux<GEvent>`, and `ReplyEvents` is the
conversion. A reply no arm there claims becomes an `UnknownReply` rather than vanishing, so the
application's model looking small is visible instead of looking complete.

`api` gained the five types it had been borrowing implementations of: `TemperatureReported`,
`ErrorReportEvent`/`ErrorReport`, `MachineBusyEvent`/`BusyReport`, `UnknownReply`.

**It fixed a live bug nobody had noticed.** `static/js/component/printer.js` switches on
`payload.type === "TemperatureReported"` and reads `payload.event.hotend.target`. That has matched
nothing since the `marlin/decoder` classes were deleted - what the stream actually carried was
`OkTemperatureRs` - so every temperature fell through the `if` and the page stopped updating. The
application naming its own events is what makes the name stable, and `EventStreamContractTest` now
pins the JSON the browser reads, because that contract is with code the build does not compile.

Verified by running: 995 tests green, `:gcode`'s compile classpath is `kotlin-stdlib` and
`commons-lang3` and nothing else, and the assembled app boots and round-trips a printer. The live
SSE check against a real printer did **not** run - the simulator on `localhost:8099` was down - so
the JSON shape is pinned by test rather than observed on the wire.

---

### B1 — the aggregate leaves the web module {#b1}

**Goal.** `PrinterState`, `TemperatureControl`, `FirmwareInfo` and `CapabilitiesInfo` move into a
domain module that does not depend on WebFlux. `PrinterAggregate` either becomes the real interface
or is deleted.

**Why.** State that outlives a request does not belong beside the controller, and B2 cannot persist
an aggregate that only the web module can see. The test for whether a class is in the right module:
could `:backend:printer-management` use it without pulling in WebFlux?

**Done when.** `:backend:api` holds controllers, the reactor and its manager, and nothing else; the
new module has no `org.springframework.web` import; the Modulith verification from A1 still passes.

### B2 — the machine remembers what it is {#b2}

**Goal.** Firmware info, capabilities and last-known state are persisted and restored on boot.

**Why.** `M115` and `M503` are asked once at connect and held in memory, so a restart means the farm
knows nothing about any machine until each one is dialled again — and `PrinterState.handle` uses
`firmware.getRaw().extruderCount()` to validate a tool index, which before the first `M115` is
whatever the empty `FirmwareInfo` says. A scheduler (**D1**) cannot match a job to a machine whose
capabilities are only known while it is connected.

**Done when.** A restart with no printer connected still shows each machine's firmware, extruder
count and capabilities; a Liquibase changeset carries the columns; a `@DataJpaTest` covers the
round-trip; stale data is marked with the timestamp it was learned.

**Done, and the boundary decided the shape.** `doc/sub_boundaries.txt` puts "capabilities, firmware
versions" in `printer-management` as static data - so this is not `core` persisting its own state,
it is `core` *reporting* what only it can learn. Asking a machine what it is means talking to it,
which is `core`'s job alone; keeping the answer is the roster's. The fact crosses twice: out as
`MFirmwareReportedEvent` / `MCapabilityReportedEvent`, back inside the `MRegisteredEvent` the roster
announces - so A6's replica is what carries it, and the aggregate seeds from that rather than from
nothing.

Firmware is a table of key-values rather than a column per field, because what `M115` reports
differs between builds and a schema naming only today's keys drops whatever a later Marlin adds.
A firmware report **replaces** (it is a complete statement about one build); capabilities are
**upserted** (the firmware sends them one `Cap:` line at a time and nothing marks the last).
`profile_learned_at` is the staleness marker the item asked for - a printer reflashed while the farm
was down reports differently next time, so a reader has to be able to tell how old this is.

**It collapsed two classes rather than adding any.** `FirmwareInfo` and `CapabilitiesInfo` were thin
wrappers that only tracked "did this change", both starting empty every time the process did. They
are one `MachineProfile` on the aggregate now, seeded from the roster - which also fixes the
`NullPointerException` A6 found: a command to a machine the process had never connected to
dereferenced a null extruder count, and an unknown machine now claims one extruder instead.

**Temperatures are deliberately not persisted.** The item said "last-known state"; a temperature
from before the restart is not state, it is a stale number that would read as current. A machine
starts with no reading and says so.

**Two deviations from the done-when**, both forced:

* **`@DataJpaTest` does not exist on this classpath.** Spring Boot 4 moved the test slices out of
  `spring-boot-test-autoconfigure`. `PrinterProfilePersistenceTest` is `@SpringBootTest` with
  `@Transactional`, which gives the same round-trip-and-roll-back against the real Liquibase schema
  at the cost of booting the module.
* **The restart was proved by test, not by restarting.** The simulator on `localhost:8099` was down,
  so nothing could learn a real `M115`. `PrinterProfileFlowTest` boots the assembled application and
  asserts the whole loop, including that `announceTheFarm` carries the profile - which is the
  mechanism a restart depends on.

**Two findings worth keeping**, both from running rather than reading. A column named `value` is a
reserved word: Hibernate emitted it happily and the database rejected the query, which is the
argument for testing against the Liquibase schema rather than a generated one. And a test-scope
`application.properties` **replaces** `:app`'s rather than merging with it, silently dropping the
Liquibase changelog path - a profile overlay is the fix.

### B3 — a connection lifecycle worth the name {#b3}

**Goal.** An explicit state machine — `OFFLINE → CONNECTING → ONLINE → {IDLE, BUSY, ERROR}` —
with retry and backoff, a health signal, and a published event per transition.

**Why.** Today the state is three booleans (`connected`, `online`, and the entity's `connected`
column) across two objects, and they can disagree: `PrinterReactor.connect()` subscribes and relies
on `doOnTerminate(printer::onOffline)`, with no retry and nothing that reconnects a machine that
dropped. `ConnectCmd` is handled in two places with a `//TODO` on one of them. An eight-hour print
(**C5**) cannot be built on that.

**Done when.** One enum is the state; the entity's column is derived from it, not a parallel truth;
a dropped connection retries with bounded backoff and says so on the event stream; a test drives
every transition with `VirtualTimeScheduler` and no sleeps.

**Done, and it collapsed four truths into one.** `MachineConnectionState` in `api` is the enum -
`OFFLINE -> CONNECTING -> ONLINE -> {IDLE, BUSY, ERROR}` - and `ConnectionLifecycle` in `core` is the
machine that moves it. What it replaced was `PrinterState.connected`, `PrinterState.online`,
`PrinterEntity.connected` and `PrinterReactor`'s implicit `terminal == null`, any two of which could
disagree, plus four event records (`ConnectedEvent`, `DisconnectedEvent`, `MachineOnlineEvent`,
`MachineOfflineEvent`) that between them said less than the one `ConnectionStateChanged` does.

**The trick is that intent is held apart from state.** A link that drops while a connection is still
wanted goes back to `CONNECTING`; the identical drop a moment after Disconnect goes to `OFFLINE`.
That distinction is the reason the old code had no retry at all - there was nothing to ask whether a
reconnect would be welcome.

**`ONLINE` and `IDLE` are not synonyms.** `ONLINE` means a socket is open; `IDLE` means the firmware
answered. A terminal server with nothing plugged into it stops at `ONLINE`, which is worth seeing,
and the old pair of booleans called that "online" without qualification.

**Leaving `BUSY` is timed by a clock and not by a timer.** Marlin repeats `echo:busy: processing`
every couple of seconds and the handshake's `M155 S1` interleaves a temperature report every second,
so "busy until something else arrives" would flicker once a second. A busy notice stamps the clock;
any later reply asks whether the machine has gone quiet for the timeout. No scheduler in the
aggregate, and the steady reply stream is the tick.

**The retry wraps the whole conversation, not just the dial.** Everything from `TerminalManager.connect()`
to the end of the session is one chain, so a link that drops after an hour re-enters it exactly where
a refused connection does - "reconnect a machine that dropped" needed no second mechanism. A clean
close is turned into an error on purpose, because a clean close is still the connection being gone.
The failure is announced from `doBeforeRetry` rather than a `doOnError`, so a machine never announces
that it is backing off towards an attempt that will never be made.

**The persisted column is now `connection_state`, and it finally has a reader.** `connected` was
write-only: `MachineManagementService.connect`/`disconnect` were called by nothing but a test, since
the endpoint has always gone to the runtime, so every row said `false` from registration onwards.
Both methods, `PrinterEntity.connect`/`disconnect`, and the `MConnectedEvent`/`MDisconnectedEvent`
pair they published are deleted. `core` reports each transition as `MConnectionChangedEvent` -
only `core` can know this, because knowing it means holding the socket - the roster keeps it, and
**`PrinterReactorManager` redials at startup whatever was connected when the process went down.**
Without that last step B3 would have moved a write-only flag rather than removed one. The connection
change is deliberately *not* re-announced, unlike a firmware report: the fact came from `core`, and
handing it back to the module that redials on announcements has exactly one possible effect.

**It fixed the open NPE.** A command to a machine that is not connected dereferenced the `GSender`
the aggregate holds only while a link is up, so a request that had bound and routed perfectly
returned a 500 pointing at the aggregate rather than at the machine being unreachable. `onCommand`
refuses and logs the state instead.

**The page gets one state rather than two booleans it could not keep in step.** `connection.js` and
`printer.js` switched on four event names and set `online` from *any* event arriving at all, so a
printer that was connected but quiet read as offline. Both read `ConnectionStateChanged` now, and
`GET /api-printer/{id}/connection` is the health signal for a page that has not seen an event yet -
the replay sink is also seeded with the current state, so a late subscriber is told immediately.

**Verified by running**, first against a 50-line TCP stand-in and then against the real simulator
on `localhost:8099` (Marlin `bugfix-2.1.x`, `MACHINE_TYPE:MarlinSim`) once it came back up:

* a machine at a closed port announced `OFFLINE -> CONNECTING(1) -> CONNECTING(2..5) ->
  OFFLINE("ConnectException: Connection refused: getsockopt")` on the real SSE stream, which is five
  attempts over a bounded backoff and then silence - not a farm redialling forever;
* against the real firmware, `OFFLINE -> CONNECTING(1) -> ONLINE -> IDLE`, 38 capabilities and the
  real `M115` string learned and persisted, and `connection_state` written as `IDLE`;
* a `set-temp` posted to the REST surface reached the wire as `M104 S60.00 T0` and the simulator
  heated 25.0 -> 44.5 C, with `current`, `target` and `power` arriving in the shape
  `printer.js` reads;
* **killed and restarted, the process redialled the simulator on its own**, with no request from
  anybody - which is the headline of this item and it does work;
* Disconnect wrote `OFFLINE` through to the row and sent `M155 S0` first, and a `set-bed-temp` to the
  disconnected machine returned 200 having sent nothing, where it used to be a 500.

**And the live run found a defect that the stand-in could not, which is the argument for having
done it.** The redial reconnects, but *the reconnected session cannot send anything*:

```
-> N1 M115*39
<- Error:Line Number is not Last Line Number+1, Last Line: 4
<- Resend: 5
OUT: Resend: 5 - not held by the send window, so the sequence cannot be repaired from here;
     the session is out of sync
```

A new session starts its send window at `N1` while the firmware - still powered, never reset -
remembers line 4. Every block is rejected on its line number, the window has nothing to replay, and
the outbound half of the session is dead for good. A `set-temp` afterwards returns 200 and the target
never moves.

**It is not a B3 regression.** A plain Disconnect then Connect, with no process restart anywhere
near it, produces the identical error - so this has been true since reconnecting was possible at
all, and only the first session after a printer powers on has ever worked. B3 is simply the first
thing that reconnects without being asked, and the first thing that put the firmware's `Error:` on
the event stream where somebody could see it.

**It belonged to `:backend:terminal`, and it is fixed.** `HostTerminal`'s own javadoc already named
the repair - *"`GSendWindow.reset` and an `M110` is the repair, and it is not this method's to
decide"*, and *"the counter is this layer's to set and not a caller's to send"*. It does both now:

* **every session opens with `M110 N0`**, framed and written through the drain loop like any other
  block, so it takes a unit of demand and a buffer slot and the accounting stays honest;
* **a `Resend:` the window cannot answer resynchronises** rather than being logged and left, which
  is the hole the `reactive-transport-dev` skill had on its debt list.

Verified against the simulator: **five consecutive sessions, five `M110 N0` resets, zero
`Line Number is not` errors, and an `M104` delivered on each one** - where before only the first
session after a power-on had ever been able to send. `TerminalRecoveryProbe` passes all six of its
checks with the reset in front of the conversation, including the byte-for-byte replay of a block
corrupted on the wire.

**What it exposed in B3 itself** was recorded as debt rather than patched over, and has since been
fixed: `IDLE` meant "a reply arrived", and a reply can be auto-report traffic left running by a
*previous* session, so a machine whose outbound half was dead still read `IDLE`. The live run
demonstrated it twice from two different causes - once with the broken numbering, and once with a
session whose first block sat unacknowledged behind a 937-line backlog the simulator had queued
while nothing was connected. The honest proof is an acknowledgement of something *this* session
sent, which is exactly the signal [decision 3](#d3-acks) said the transport did not expose. It does
now, `ONLINE -> IDLE` is the one arrow that waits for it, and a stand-in that talks without ever
acknowledging anything stays `ONLINE` - see decision 3 for the run.

1065 tests green, and the four done-when conditions all hold. Two deviations in how, both
deliberate: `VirtualTimeScheduler` drives
the backoff in `PrinterReactorRetryTest` but the pure transitions are driven by a test `Clock` in
`ConnectionLifecycleTest`, because a state machine with no scheduler in it should not need one to be
tested; and the retry test advances time generously rather than by the exact backoff, because the
policy jitters its delays on purpose - several printers dropped by one blip must not all redial on
the same tick - so the sequence is deterministic and the instants are not.

### B4 — machine profile and groups {#b4}

**Goal.** Bed volume, extruder count and nozzle diameters, supported materials, and free-form tags
or a group id.

**Why.** This is the data **D1** matches a job against, and the data a user needs in order to set a
farm up at all. Most of it can be defaulted from what `M115`/`M503` report in B2 and then overridden
by hand, which is the right division: discovered where possible, declared where not.

**Done when.** The management API and the add/edit page carry the profile; a changeset carries the
columns; what was discovered is distinguishable from what was typed.

**Done, and "distinguishable" turned out to be the whole design.** `MachineSpec` in `api` is what a
person declared - bed volume, extruder count, nozzle diameters, materials and tags - and it sits
beside `MachineProfile`, which stays exactly what it was: what the machine reported. Neither can
write the other. `PrinterEntity.learnedFirmware` touches the profile columns, `declare` touches the
spec columns, and the separation is in the schema rather than in a flag beside each field.

**A flag per field was the alternative and it is worse in three ways.** Every writer becomes
responsible for setting it; the discovered value is lost the moment somebody overrides it, so a
reflashed machine cannot be told that a typed-in value has gone stale; and a reader has no way to
show both, which is precisely what the edit page shows - declared fields on the left, what the
firmware said read-only on the right with when it was learned.

**Where the two meet, declared wins, and the rule lives once.**
`MachineSpec.extruderCountOr(profile)` is called by `Descriptor.extruderCount()` and by
`PrinterState`, rather than copied into both. Only the extruder count has two sources today: bed
volume and nozzle diameters are in an `M503` dump that B2 did not ask for, so they are declared-only
until something does, at which point they resolve the same way and no type changes shape.

**Groups are tags.** The item offered "free-form tags or a group id"; a group is a tag a reader
filters on, costs the same to store, and lets a machine belong to two of them.

**An edit reaches a machine that is already running**, which is the step that keeps the spec from
being another write-only column. `update` re-publishes `MRegisteredEvent`, `PrinterDirectory`
updates its replica, and `PrinterReactorManager.redeclare` hands the new spec to a live aggregate.
It deliberately never *builds* a runtime: that event is re-published for every machine at startup
and on every firmware report, and opening a reactor per row would undo A3.

**The edit page edits everything, not just the spec** - name and connection too. A form that can set
a nozzle diameter but cannot fix a mistyped hostname is a worse product than one extra parameter is
worth. `add` keeps its two-argument form as a default, because registering a printer you have just
plugged in should not require knowing its bed size.

**Verified by running**, against the TCP stand-in again - MarlinSim is still down:

* changeset 4 ran as a **migration against the existing database**, not a fresh one:
  `ChangeSet db/changelog/db.changelog-master.xml::4::agentic ran successfully in 54ms`;
* `POST /api-manager` with a spec, read back whole, both halves separate in the JSON;
* the edit page rendered the declared values as form fields and the reported firmware read-only
  beside them - `FIRMWARE_NAME Marlin bugfix-2.1.x … EXTRUDER_COUNT 1` under "What the machine
  reported", while the form carried what a person had typed;
* the form round-tripped: renamed, bed changed, a blank extruder count cleared to null, and
  `tags=prototyping, loud` parsed into two;
* **a firmware report left the declaration alone.** The machine connected, reported
  `EXTRUDER_COUNT:1`, and the declared spec was untouched;
* **the override reached a connected machine with no restart.** The same `set-temp` for tool 1 was
  refused before the declaration and reached the wire as `N3 M104 S60.00 T1*59` after it.

What it leaves open is in the skill's debt list: nothing warns when a declared spec contradicts what
the firmware reported - four nozzles on a machine claiming one extruder is accepted in silence.
That is deliberate while the override exists for firmware that is wrong, but it is worth a warning
before **D1** matches jobs against any of it.

---

## Phase C — jobs

The missing half of the product, and the largest phase. After phase C a file can be sent to a
machine and watched to completion.

### C1 — the G-code file store {#c1}

**Goal.** Upload a `.gcode` file, store it, and record what is known about it: content hash, byte
size, line count, and whatever the slicer wrote in its header comments (estimated time, filament
length, layer height, the slicer's name).

**Why.** A job references a file; a file must be addressable and immutable. The content hash is what
makes a re-upload of the same file the same file, which matters for the queue and for recovery.

**Watch.** A G-code file is tens to hundreds of megabytes. Nothing in the path may read it into
memory — not the upload, not the line count, not the streaming of **C3**. `DataBuffer` in,
`DataBuffer` out, and a filesystem or object store rather than a BLOB column.

**Done when.** Upload and download are streamed and provably bounded in memory; the same bytes
uploaded twice do not duplicate storage; the header metadata is parsed by `:gcode`'s comment
handling rather than a new regex.

**Done, and it is a fifth module.** `:backend:files` holds the rows and the bytes; `api::files` is
its whole contract — `GcodeFileService` in, `GcodeFile` out. The upload and download endpoints are
in `frontend`, because `doc/sub_boundaries.txt` puts every server API there.

**The signatures are what make "bounded" true, not the comments.** `api` may not name a Spring
type, so the store takes `Publisher<ByteBuffer>` and the web layer converts at its own edge, where
it already owns the pooled `DataBuffer`'s lifetime. One copy per chunk, which is the price of
keeping spring-core off the compile path of the protocol side. Inside, the write is
`concatMap(..., 1)`: one chunk requested, processed, and only then the next, so demand propagates
back to the socket. The obvious shapes all fail — `reduce` and `doOnNext` request `Long.MAX_VALUE`,
and `toIterable` parks the overflow in a 256-element queue, which at this chunk size is sixteen
megabytes nothing asked for.

**The memory proof is a demand assertion, not a heap measurement.** `GcodeBlobStoreTest` records
the largest single `request(n)` the store makes of its source and asserts it is one. A test that
watched the heap would prove nothing a garbage collector could not undo; this one fails with
*"the store asked for 256 chunks at once"* the moment the prefetch moves, which was checked by
moving it.

**Content-addressing makes dedupe fall out rather than be checked for.** A blob's name is the
SHA-256 of its bytes, fanned out two levels. The bytes stream to a temp file while the hash and the
line count are computed in the same pass — the hash is not known until the last byte, and the final
path is named after it, so they have to land somewhere first — and the move is skipped if something
already lives there. The row's `content_hash` is unique, so **a re-upload of identical bytes returns
the existing record, under the existing name.** That is the plan's own "the content hash is what
makes a re-upload of the same file the same file", and it is what lets C2's job hold a file id for
eight hours.

**Slicer metadata is read by `:gcode`'s tokenizer**, from two bounded 64 KiB windows — the head and
the tail, because Cura writes its summary at the top and PrusaSlicer repeats its configuration at
the bottom. What is *not* `:gcode`'s business is which keys mean what: `;TIME:3661` against
`; estimated printing time (normal mode) = 1h 2m 3s` is two programs' habits, so that table lives in
`SliceInfoReader`. A file from an unknown slicer stores fine with nothing read.

**It forced the changelog apart, which was already recorded debt.** One master changelog in
`printer-management` was workable with one persistence module and wrong with two — the files module
would have had its schema owned by the roster, and the roster's own tests would have created tables
for a module they have never heard of. The master is in `:app` now and includes one fragment per
module. Each fragment carries a `logicalFilePath` pinning the name Liquibase records, because a
changeset's identity is (id, author, filename): without it every existing database would think
changesets 1–4 had never run.

**Verified by running**, with the application in a jar under `-Xmx256m` and `ExitOnOutOfMemoryError`:

* **a 210,554,463-byte file uploaded through a 256 MB heap in 3.4s.** The SHA-256 the server
  recorded matches `sha256sum` exactly, and the line count — 5,650,005 — matches `wc -l` exactly;
* **downloaded back byte-for-byte identical** (`cmp` clean), with `Content-Length` set from the row
  so a client can show progress without the server buffering to find out;
* the same bytes re-uploaded under another name returned **the same id and the first name**, and
  the blob directory held **one** file;
* **three concurrent 120 MB uploads** left the heap at 153 MB of 256 MB — `jcmd GC.heap_info` after
  moving over half a gigabyte through the process;
* a delete removed the row and the blob;
* Cura's header was read correctly end to end: `Cura_SteamEngine 5.4.0`, 7265s, and `12.3456m`
  converted to `12345.6` mm.

Open, and in the skill's debt list: the metadata windows are bounded, so a slicer that writes its
summary in the middle of a very large file is not read; a blob whose row is lost is not collected
by anything; and the upload has no size limit, so the only thing stopping a client filling the disk
is the disk.

### C2 — the job and its lifecycle {#c2}

**Goal.** A `Job` entity and its state machine: `QUEUED → ASSIGNED → RUNNING → {COMPLETED, FAILED,
CANCELLED}` with `PAUSED` off `RUNNING`, each transition publishing an event.

**Why.** It is the thing a user creates, a scheduler assigns and an operator watches, and every
other item in phases C and D refers to it. Getting the states and their legal transitions right
before anything streams is cheaper than discovering them afterwards.

**Done when.** The state machine rejects illegal transitions with a typed failure rather than an
exception from the database; every transition publishes a past-tense event through the Modulith
event registry; a job survives a restart in whatever state it was in.

**Done, and it is a sixth module.** `:backend:jobs`, with `api::jobs` as its contract. It names no
other submodel: a job holds a file id and asks `GcodeFileService` whether it is real, and it hears
about a deleted printer through `MDeletedEvent` the way `core` does.

**The transition table is on `JobState` and nowhere else.** A page deciding which buttons to show
and a scheduler deciding what it may assign are asking the same question as the service that
enforces it, and three separate answers is how a UI comes to offer an action the backend refuses.
`JobStateTest` asserts the whole table - every state against every state, forty-nine pairs - rather
than the handful of edges that came to mind.

**A refusal is a value and never reaches the database.** `JobEntity.moveTo` answers null for an
illegal move; the service turns that into `JobOutcome.Refused` carrying where the job *actually*
is; the REST layer turns that into a 409 with the reason. The usual cause is an ordinary race - a
user clicking Pause as the last line is acknowledged - and the right answer to that is a sentence,
not a stack trace. Verified live: `POST /api-jobs/{id}/pause` on a queued job returns

```
http=409
{"jobId":"…","state":"QUEUED","attempted":"PAUSED","reason":"a job that is QUEUED cannot become PAUSED"}
```

**Nothing leaves a terminal state, including to another terminal state.** Re-running a file is a
new job. That is what keeps a finished job a record of what happened, which **C5** depends on: it
writes the last acknowledged line onto a failed job, and a job that could be revived in place would
overwrite it.

**One event, not seven.** `JobStateChanged` carries from/to, so `releasedAMachine()` is a question a
scheduler can ask - and it is unanswerable from any single `JobStartedEvent`-style record. The same
argument that made `ConnectionStateChanged` one event out of four.

**The event publication registry is on**, with its table as a Liquibase changeset rather than
Modulith's own schema initialisation. The one durable listener today is the queue's own: a deleted
printer releases what it was holding, and the two cases differ - an **assigned** job goes back to
the queue, a **running** one fails, because the file has been partly consumed and a second machine
starting it from line one would print the top half of the part onto an empty bed.

**It found a real recovery bug.** The registry serialises an event to JSON on publish and reads it
back to replay it, and `api`'s management events are Kotlin data classes:

```
Cannot construct instance of `org.qw3rtrun.p3d.api.msg.MDeletedEvent`
(no Creators, like default constructor, exist)
```

Publishing worked, delivery worked, and only *recovery* - the half the registry exists for - was
broken. The fix is a `JacksonModule` bean, which is Modulith's own extension point; a `JsonMapper`
bean would have quietly become the whole application's. It was invisible until a test asked the
registry for the publication it had just written.

**Verified by running**, against a fresh database and then a restart of the same one:

* the happy path over HTTP - queue, assign, start, pause, resume - with `startedAt` unmoved by the
  resume, because the job did not start twice;
* **a job survived a restart in `RUNNING` with its timestamps intact**, and zero changesets re-ran,
  which also confirms C1's `logicalFilePath` split is stable now that there are four fragments;
* deleting the printer moved its assigned job to `QUEUED` with the machine cleared and its running
  job to `FAILED` with the machine kept - the reason on each says which happened and why;
* the registry held exactly one row afterwards, `MDeletedEvent ->
  JobServiceImpl.onMachineDeleted`, marked `COMPLETE`.

**What that last line also shows is the honest limit.** `JobStateChanged` is published through the
same publisher and is a Java record that round-trips, but nothing listens to it durably yet, so the
registry records nothing for it. It will the moment **C3** starts a print on one - the wiring and
the schema are what C2 owed, and they are there.

### C3 — the streaming engine {#c3}

**Goal.** A running job is a `Flux<String>` of G-code lines, read lazily from the file store and
handed to `PublisherQueue` at `Priority.BACKGROUND`, while interactive commands continue to preempt
it at `REGULAR` and an emergency stop at `EMERGENCY`.

**Why.** This is the item everything below the application layer was built for. The priority queue,
the send window, the block framing and the resend loop all exist; nothing uses them for the one job
they were written for.

**Three prerequisites in `:backend:terminal`** — not this layer's to fix, and each one a
`terminal-dev` item that should be raised as its own piece of work:

1. ~~**`PublisherQueue` is partially synchronised over a non-thread-safe `PriorityQueue`.**~~
   **Done** — a `PriorityBlockingQueue` behind the same `wip` drain loop `CommandProcessor` uses.
   The rewrite turned up four further rules it broke, none of which had a symptom: equal priorities
   came out in heap order rather than arrival order, a second subscriber silently replaced the
   first, `request(0)` was ignored, and a source that over-produced was forwarded straight at the
   send window. `addPublisher` now also answers with a `Mono<Void>` that completes when the source
   is exhausted, which is what this item was needed *for* — see the note under prerequisite 2.
2. ~~**`CommandProcessor.onComplete` has `subscriber.onComplete()` commented out**, so an exhausted
   source signals nothing downstream.~~ **Done** — an exhausted source completes the stream, once
   everything it handed over is on the wire; completing directly from `onComplete` would have
   dropped the blocks still owed. Removing the `onComplete` that `cancel()` answered with — a
   Reactive Streams violation that also cost a debugging session every time a test expectation
   failed — **leaked the connection**, because completing the outbound was what ended
   reactor-netty's send. `HostTerminal` takes a dispose hook now.

   **The stated reason for this prerequisite was wrong, though, and it is worth recording why.** A
   finished job is *not* observed through this: C3 queues a job as one publisher among several in a
   `PublisherQueue`, and the queue outlives every source in it, so `CommandProcessor.onComplete`
   never fires in a live terminal. What C3 actually needs is the per-source completion that
   prerequisite 1 added to `addPublisher`.
3. **The transport publishes no acknowledgement signal.** `messageFlux()` deliberately drops
   `SimpleOkRs` and `AdvancedOkRs` because they "say nothing a subscriber did not already learn from
   the send window" — but no subscriber can see the send window. **C4** needs that signal; see
   [Decision 3](#d3-acks).

**Done when.** A multi-megabyte file prints to a simulator end to end with bounded memory; a
temperature query issued mid-job is answered without corrupting the sequence; cancelling mid-stream
stops the source and drains cleanly.

### C4 — progress and telemetry {#c4}

**Goal.** Lines accepted of lines total, bytes sent, elapsed time, estimated remaining, and the
current layer where the file says so — published on the event stream and persisted often enough to
survive a restart.

**Why.** "What is it doing" is the question a farm page exists to answer.

**The measurement has to be acknowledgements, not sends.** A line handed to the transport is a line
that may still be replayed after a `Resend:`; progress counted on the way out would run ahead of the
machine and go backwards on recovery. The number that means something is the last line the firmware
acknowledged, which only the send window knows — hence the prerequisite in C3.

**Done when.** Progress never moves backwards except across a deliberate rewind; it survives a
restart to within one persisted checkpoint; a resend during a print does not corrupt the count.

### C5 — pause, cancel, and survive a disconnect {#c5}

**Goal.** Pause (stop feeding, hold temperatures, optionally park), resume, cancel (stop, cool down,
retract), and a defined answer to "the connection dropped at line 400,000 of 900,000".

**Why.** It is what an eight-hour print needs, and it is the hardest item in the plan.

**Pause and cancel are cheap** once C3 exists — stop pulling from the source, and send the cooldown
or park sequence at `REGULAR`.

**Recovery is not**, and the plan does not pretend otherwise: resuming a print means restoring
temperatures, position, extrusion mode and feedrate as of the last acknowledged line, which is a
replay of the file's state-setting commands and not a seek. The honest first version is **"resume is
offered only when the disconnect was shorter than the time the bed stays hot, and otherwise the job
fails with its last acknowledged line recorded"** — and the recorded line is what makes a better
version possible later.

**Done when.** Pause/resume/cancel work against a simulator and are covered by tests; a killed
connection leaves a job in `FAILED` with its last acknowledged line; the restart path is documented
in this file rather than inferred.

---

## Phase D — the farm

### D1 — assignment {#d1}

**Goal.** A queued job with no target is matched to a machine that is `IDLE` and whose profile
(**B4**) satisfies the job's requirements; a job with a target waits for that machine.

**Why.** This is the "group" in "group of 3D printers". Without it the farm is N single-printer
pages.

**Keep it small.** First-fit over capability and availability, with manual override, is the whole
feature. A cost model, bin-packing or a scheduling framework is not warranted at this size and
should be argued for separately if ever.

**Done when.** A job queued with no target starts on an eligible idle machine without a human; an
ineligible farm leaves it queued with a stated reason; assignment is a published event.

### D2 — the farm view {#d2}

**Goal.** One live stream carrying every machine's state and job progress, replacing N per-printer
SSE subscriptions.

**Why.** The dashboard opens one `EventSource` per printer today, and each one instantiates a
reactor as a side effect (**A3**). At forty machines that is forty streams, forty sinks and forty
single-threaded schedulers to render one page.

**Done when.** The dashboard holds one subscription; per-printer streams still work for the monitor
page; a printer added or removed while the page is open appears or disappears without a reload.

### D3 — operations {#d3}

**Goal.** Authentication and authorisation, a database that is not a local H2 file, and the
Modulith observability and actuator endpoints actually turned on.

**Why.** Everything above assumes a trusted operator on a trusted network. That assumption is fine
on a bench and nowhere else — a printer is a heater, and an unauthenticated `M104 S300` is a safety
problem rather than a data one.

**Done when.** Named deliberately as the item to do when the project leaves the bench, so it is not
mistaken for something phases A–C depend on.

---

## Why this order

**A1 first** because it moves files. A package rename conflicts with every concurrent change, and
every later item adds a module to a boundary that does not yet exist. Doing it first costs one
rename; doing it after phase C costs the same rename across three more modules.

**A2 before anything that adds a query.** Phases B and C add persistence to paths that are already
reactive, and the blocking rule is much cheaper to install as a habit than to retrofit — the current
four call sites become twenty.

**A4 before A5** because deleting the command bus removes a dispatch mechanism outright, which makes
A5's "one dispatch" a smaller argument. They are otherwise independent.

**A3 and A5 can swap**, and either can be skipped if the phase is being done piecemeal — they block
B3 and B1 respectively and nothing else.

**B before C** because a job needs a machine that is a durable, describable thing. B2 is what lets a
scheduler reason about a disconnected printer; B3 is what a long print's failure handling is built
on. Doing C first means writing the job lifecycle against three booleans and redoing it.

**C1 → C2 → C3 → C4 → C5** is a strict chain: a job references a file, a stream runs a job, progress
measures a stream, and the pause/recovery operations act on all four.

**D1 last among features** because it is the only item that needs everything before it — capability
data from B4, job states from C2, and availability from B3. It is also the smallest of the three D
items, which is the usual sign that the ordering is right.

**The order is not by value.** C3 is worth more than all of phase A together: it is the item that
makes the application do the thing it is for, and it is the first time `:gcode`'s session layer and
`:backend:terminal`'s priority queue reach a printer in production. It is seventh because doing it
first means doing it twice.

---

## Decisions this plan takes

### Decision 1 — keep Spring Modulith {#d1-modulith}

Five Modulith dependencies are declared and none is used. Two honest options: wire it up (A1) or
remove the dependencies and let Gradle modules be the boundary.

**This plan keeps it.** The argument is the shape of what follows: phases B–D add at least three
modules (domain, files, jobs, scheduling), several of which must talk to each other without becoming
a ball of mutual `@Autowired`. A verified boundary and the event publication registry are both worth
more at eight modules than at five, and the registry in particular is what **C2**'s job events and
**C5**'s recovery need — at-least-once delivery across a transaction boundary is not something to
hand-roll on `Sinks`.

**If the user would rather drop it**, A1 shrinks to the package rename, `@ApplicationModuleTest`
disappears from the testing guidance, and C2 needs an explicit decision about event durability
instead. Nothing else in the plan changes.

### Decision 2 — Java stays the default, Kotlin where it pays

`:gcode` is Kotlin because it must port to other languages; `:backend:core` has the Kotlin plugin for
exactly one file. The application layer is Java 25 with records, sealed interfaces and pattern
matching, which covers most of what the Kotlin in this repo is used for.

**New work is Java unless it is data-shaped.** Event and message types — C2's job events above all —
are a good case for Kotlin data classes, and the `kotlin-tooling-java-to-kotlin` skill exists for a
module-at-a-time conversion if that is ever wanted. What should not happen is the current state:
one Kotlin file per module, paying for the plugin and settling nothing.

### Decision 3 — progress is measured by acknowledgements {#d3-acks}

**C4** counts lines the firmware acknowledged, not lines the application sent. That requires a
signal `:backend:terminal` does not currently emit: `messageFlux()` drops both `ok` forms on the
stated grounds that they say nothing a subscriber did not learn from the send window, and no
subscriber can see the send window.

**The recommended fix is in the transport, not here** — expose the last acknowledged line number, or
publish a typed progress signal alongside the reply bus. The alternative, counting what the job
feeder handed over, is wrong in exactly the case that matters: after a `Resend:` the transport
replays blocks the job already counted, so progress would overstate and then have to move backwards.

**Done, and it closed B3's own debt on the way.** `BlockAcknowledged(lineNumber, inFlight)` is a
`GEvent` on `messageFlux()`, emitted when `GSendWindow.acknowledge()` releases a block — the window
now returns the number it released rather than a boolean, because it is the only thing that knows
which block an `ok` answered. The bare `ok` most builds send carries no number, so the
correspondence is the window's FIFO order and nothing on the wire.

**It had to move the reply bus to a safe sink.** `replays` was `Sinks.unsafe()`, which promises
external serialisation; that promise held while every emission came from the netty inbound thread,
and a block is released inside the drain loop, which runs on whichever thread got there first.

**The second consumer is B3's.** `ONLINE → IDLE` was "any reply", which B3 recorded against itself
as a lie: `M155 S1` left running by an earlier session, and a backlog queued while nothing was
listening, both arrive at a host whose outbound half is dead. `ConnectionLifecycle.blockAcknowledged()`
owns that arrow now and `replyReceived()` no longer does. It costs nothing in the normal case —
`onOnline` sends `M115` the moment the socket opens, so a healthy machine acknowledges within a
round trip.

**Verified by running**, against a TCP stand-in rather than MarlinSim, which was down:

* `TerminalRecoveryProbe` passes eight checks including the two new ones. The one that needed a real
  peer is the replay: a block corrupted on the wire is rejected, replayed byte for byte, and the
  `ok` that closes the resend announces nothing while the real `ok` announces block 2 — six blocks
  written, one of them twice, four acknowledgements `[1, 2, 3, 4]`, all distinct. A counter of what
  was *sent* would have said five;
* the assembled application dialled a printer and announced `ONLINE -> IDLE` after
  `-> N1 M115*39` / `<- ok N1 P15 B3`, and **not** after the `ok` for its own `M110`, which releases
  no block;
* a stand-in that accepts TCP, writes a temperature every second and never acknowledges anything
  **stayed `ONLINE` for as long as it was watched**, with the temperatures arriving and decoding
  normally. That is the case B3 could only describe.

What it does not close is recorded as debt in the `reactive-transport-dev` skill: the number is the
wire's, so it restarts at 1 when a session resynchronises — the same moment blocks are abandoned,
which is why both halves belong to **C5**'s recovery rather than here. And `ok` means *queued*, not
executed, so **C4**'s "estimated remaining" has a planner's worth of motion to account for at the
end of a job.

---

## Conventions

Every item follows the `spring-backend-dev` skill:

- **TDD** — the failing test first, and the failure read before the fix. A compile error is not a
  red.
- **Nothing blocking on an event loop**, and a stream asserted with `StepVerifier` rather than
  collected into a list.
- **Liquibase owns the schema.** A column is a changeset; `ddl-auto` never appears.
- **Everything created has an owner that releases it** — reactors, sinks, sockets, subscriptions,
  file handles.
- **A contract callers depend on is written down in the same change** — in the skill, or here if it
  is about the plan.
- **Findings are verified by running and quoting real output**, not by reading. Against a printer or
  a simulator where the change touches the wire.
- When an item lands, its row here is struck through with the commit that did it, the way
  [issue #18](https://github.com/qw3rtrun/p3d-web-ui/issues/18) does.
