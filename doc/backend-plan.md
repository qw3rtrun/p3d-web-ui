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
| **A6** | [close the two declared crossings](#a6) | ~~`core` gets a replica fed by events instead of querying `management`~~ **done**; `frontend` reaches `core` through an `api` interface, and `api` closes so the check can enforce both | B2 |
| ~~**B1**~~ | [the aggregate leaves the web module](#b1) | ~~`PrinterState` into a domain module that does not depend on WebFlux~~ **done** — it is the `core` submodel now | B2, C3 |
| **B2** | [the machine remembers what it is](#b2) | Persist firmware, capabilities and last-known state; restore on boot | B4, D1 |
| **B3** | [a connection lifecycle worth the name](#b3) | An explicit state machine, retry with backoff, health, and events for each transition | C5 |
| **B4** | [machine profile and groups](#b4) | Bed volume, extruders, nozzle, materials, tags — what a scheduler matches against | D1 |
| **C1** | [the G-code file store](#c1) | Upload, content hash, size, line count, estimates; streamed in and out, never buffered | C2 |
| **C2** | [the job and its lifecycle](#c2) | A job entity, a state machine, and the events each transition publishes | C3 |
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

**What is left of A6**, and why it is bigger than it looked:

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

### B4 — machine profile and groups {#b4}

**Goal.** Bed volume, extruder count and nozzle diameters, supported materials, and free-form tags
or a group id.

**Why.** This is the data **D1** matches a job against, and the data a user needs in order to set a
farm up at all. Most of it can be defaulted from what `M115`/`M503` report in B2 and then overridden
by hand, which is the right division: discovered where possible, declared where not.

**Done when.** The management API and the add/edit page carry the profile; a changeset carries the
columns; what was discovered is distinguishable from what was typed.

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

### C2 — the job and its lifecycle {#c2}

**Goal.** A `Job` entity and its state machine: `QUEUED → ASSIGNED → RUNNING → {COMPLETED, FAILED,
CANCELLED}` with `PAUSED` off `RUNNING`, each transition publishing an event.

**Why.** It is the thing a user creates, a scheduler assigns and an operator watches, and every
other item in phases C and D refers to it. Getting the states and their legal transitions right
before anything streams is cheaper than discovering them afterwards.

**Done when.** The state machine rejects illegal transitions with a typed failure rather than an
exception from the database; every transition publishes a past-tense event through the Modulith
event registry; a job survives a restart in whatever state it was in.

### C3 — the streaming engine {#c3}

**Goal.** A running job is a `Flux<String>` of G-code lines, read lazily from the file store and
handed to `PublisherQueue` at `Priority.BACKGROUND`, while interactive commands continue to preempt
it at `REGULAR` and an emergency stop at `EMERGENCY`.

**Why.** This is the item everything below the application layer was built for. The priority queue,
the send window, the block framing and the resend loop all exist; nothing uses them for the one job
they were written for.

**Three prerequisites in `:backend:terminal`** — not this layer's to fix, and each one a
`terminal-dev` item that should be raised as its own piece of work:

1. **`PublisherQueue` is partially synchronised over a non-thread-safe `PriorityQueue`.** Preempting
   a long background stream with an interactive command is precisely its weakest case, and it is the
   case this item creates. Already on the transport's debt list.
2. **`CommandProcessor.onComplete` has `subscriber.onComplete()` commented out**, so an exhausted
   source signals nothing downstream — a finished job would never be observed as finished.
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

This is a `terminal-dev` item and should be raised as one before **C3** starts.

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
