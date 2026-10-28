---
name: spring-backend-dev
description: >-
  Write or review the Spring application submodels of this repo - `api`, `core`,
  `printer-management`, `frontend` and `app`. Use for WebFlux controllers and SSE/WebSocket endpoints, Spring
  wiring and configuration, JPA entities and repositories, Liquibase changelogs, Spring Modulith
  module boundaries and their verification, the command/query and event buses, the printer aggregate
  and its lifecycle, and anything that joins a blocking persistence layer to a non-blocking web
  layer. Enforces the rule that nothing blocking runs on an event loop, that a module boundary is
  declared and verified rather than assumed, and that routing a message is the business of whatever
  acts on it. Does NOT cover `:gcode` (that is `low-level-protocol-dev`) or `:backend:terminal` (that is
  `reactive-transport-dev`).
---

# Spring backend development

This skill covers the five submodels of `doc/sub_boundaries.txt`: `api`, `core`,
`printer-management`, `frontend` and `app`. It is Java 25 with Spring Boot 4.1, WebFlux, Spring Data JPA,
Liquibase, Spring Modulith 2.1 and Lombok, with Kotlin available and used in one file so far.

The two modules it does *not* cover have their own skills and their own, different rules.
`:gcode` is dependency-free portable Kotlin where malformed input is a value and nothing throws;
`:backend:terminal` is plain Reactor with no Spring in it at all. **If the task is in either, stop
and say so** rather than applying this skill's rules to them.

## The layering rule

| Submodel | Gradle module | Package | May depend on |
|---|---|---|---|
| **api** | `:backend:api` | `org.qw3rtrun.p3d.api` | nothing. Not even Spring at runtime |
| **core** | `:backend:core` | `org.qw3rtrun.p3d.core` | `api`, `:backend:terminal`, `:gcode`, Reactor, spring-context |
| **printer-management** | `:backend:printer-management` | `org.qw3rtrun.p3d.management` | `api`, Spring Data JPA, Liquibase |
| **files** | `:backend:files` | `org.qw3rtrun.p3d.files` | `api`, `:gcode`, Spring Data JPA, Liquibase, Reactor |
| **jobs** | `:backend:jobs` | `org.qw3rtrun.p3d.jobs` | `api`, Spring Data JPA, Liquibase, Modulith events |
| **scheduling** | `:backend:scheduling` | `org.qw3rtrun.p3d.scheduling` | `api`, spring-context, Reactor |
| **identity** | `:backend:identity` | `org.qw3rtrun.p3d.identity` | `api`, Spring Data JPA, Liquibase, spring-security-crypto |
| **frontend** | `:frontend:web-ui` | `org.qw3rtrun.p3d.frontend` | `api`, WebFlux, Thymeleaf, the Vue build |
| **app** | `:app` | `org.qw3rtrun.p3d` | everything. Assembly only |
| *library* | `:gcode` | `org.qw3rtrun.p3d.g` | the Kotlin stdlib. **Nothing else** (`low-level-protocol-dev`) |
| *library* | `:backend:terminal` | `org.qw3rtrun.p3d.terminal` | `:gcode`, `api`, Reactor (`reactive-transport-dev`) |

The five submodels and their rules are `doc/sub_boundaries.txt`; this table is how they are
realised. **Rule 1 is that every submodel depends on `api` and on no other submodel**, and
`allowedDependencies` on each module declaration is where that is enforced.

`api` must stay free of WebFlux, JPA, Reactor and Spring itself. It is the vocabulary both the web
side and the persistence side are written in, and a framework dragged in through it reaches both.

**`api` and `:gcode` do not know about each other, in either direction.** The protocol models what
the wire can say; `api` models what the application decided to care about; `:backend:terminal`
converts, in `ReplyEvents`. They were one model until the protocol's `GRs` stopped extending `api`'s
`GEvent` - which had made the one module that depends on nothing depend on the application's
vocabulary. If a new reply needs to reach the host, the change is an event in `api` and an arm in
the bridge, never an `api` interface implemented inside `:gcode`.

**`api` is the only place a type crosses submodels.** A `PrinterEntity` must not leave
`printer-management`, and the aggregate must not leave `core`.

**`core` is live data; `printer-management` is static data, and the awkward part is that only
`core` can *learn* the static kind.** Asking a machine what it is means talking to it, which is
`core`'s job alone; keeping the answer is the roster's. So the fact crosses twice - out as
`MFirmwareReportedEvent`/`MCapabilityReportedEvent`, back inside the `MRegisteredEvent` the roster
announces - and `core` seeds its aggregates from the replica rather than from nothing.

**`core` is live data; `printer-management` is static data.** What a machine is called, where to
dial it and what it can do belong to management. What it is doing right now - connected, hot, busy,
what it last reported - belongs to core. Rule 2 says core talks to other submodels through events,
and it does: `PrinterDirectory` is core's own replica of where to dial, fed by `MRegisteredEvent`
and `MDeletedEvent`, so core needs no database to start a printer. `frontend` reaches core through
`PrinterControl`, an interface in `api`, and names nothing in it.

## IMPORTANT - nothing blocking runs on an event loop

This is the rule the application layer most reliably breaks, and the one with no symptom until the
farm is under load.

WebFlux serves every request on a small fixed pool of Netty event-loop threads. Spring Data JPA is
blocking JDBC. Putting the second inside the first stalls every other request on that thread, and
`Mono.fromSupplier(...)` does **not** move the work anywhere - a `Mono` is lazy, so the supplier runs
on whichever thread subscribes, which is the event loop.

```java
// WRONG - the query runs on the event loop
return Mono.fromSupplier(() -> management.fetch(uuid));

// RIGHT - the query runs on a thread pool that is allowed to block
return Mono.fromSupplier(() -> management.fetch(uuid))
           .subscribeOn(Schedulers.boundedElastic());
```

The same applies to `Flux.fromStream` over a repository, to anything that touches a `JpaRepository`
from a controller, and to a Thymeleaf controller returning `Mono<String>` that calls a repository
while building the model.

Three ways out, in order of preference:

1. **`subscribeOn(Schedulers.boundedElastic())`** at the edge of the blocking call. Cheapest, and
   correct as long as the boundary is honest about where it is.
2. **Keep the blocking work behind a service that returns an already-scheduled publisher**, so a
   caller cannot forget. Preferred once more than one caller exists.
3. **Move the management surface off WebFlux entirely.** The printer control path needs to be
   reactive because it is a long-lived stream; CRUD over a handful of printer rows does not.

Never `block()` on a pipeline thread, and never `Thread.sleep`. If a test needs time to pass, use
`VirtualTimeScheduler`.

**A unit test will not catch a `block()` on an event loop, and D2 proved it in production.**
`FarmEvents` built its snapshot with `reactor.connection().block()`; all nine of its tests passed
and `GET /api-farm/stream` answered 500 to every browser - *"block()/blockFirst()/blockLast() are
blocking, which is not supported in thread reactor-http-nio-10"*. The gap is that **a pipeline runs
on whichever thread subscribed**: a test subscribes on an ordinary thread, where blocking is merely
rude, and WebFlux subscribes on a netty event loop, where Reactor refuses it outright.

So when a publisher is going to be served over SSE, **subscribe the test on
`Schedulers.parallel()`** - its workers carry the same `NonBlocking` marker an event loop does, and
the same check fires:

```java
StepVerifier.create(Flux.from(farm.updates())
                .subscribeOn(Schedulers.parallel())   // NonBlocking, like a netty loop
                .take(1))
        .assertNext(...)
        .verifyComplete();
```

Treat Reactor's refusal as a gift: the alternative to a 500 is not a working farm, it is one that
stalls an event loop under load with no symptom at all.

## Spring Modulith - the declared structure

**Eight modules**, declared in `package-info.java` and verified by `ApplicationModulesTest` in
`:app`:

| Module | Package | Exports / depends on |
|---|---|---|
| Shared API | `org.qw3rtrun.p3d.api` | closed, exporting seven `@NamedInterface`s: `model`, `msg`, `management`, `control`, `files`, `jobs`, `identity` |
| Printer core | `org.qw3rtrun.p3d.core` | `api::model`, `api::msg`, `api::control` — **not** `api::management` |
| Machine management | `org.qw3rtrun.p3d.management` | `api::model`, `api::msg`, `api::management` |
| G-code files | `org.qw3rtrun.p3d.files` | `api::files` |
| Print jobs | `org.qw3rtrun.p3d.jobs` | `api::jobs`, `api::files`, `api::msg`, `api::model` |
| Scheduling | `org.qw3rtrun.p3d.scheduling` | `api::jobs`, `api::management`, `api::control`, `api::model` |
| Identity | `org.qw3rtrun.p3d.identity` | `api::identity` |
| Frontend | `org.qw3rtrun.p3d.frontend` | all seven named interfaces. Names no other submodel |

**`api` is not `sharedModules` and must not become it again.** A shared module is exempt from the
dependency checks entirely, which is precisely the exemption that let `core` call
`printer-management`'s service and `frontend` reach into `core` while the verification reported
nothing. Four named interfaces say which part of the vocabulary each module may use, and the check
holds it: `core` naming `MachineManagementService` fails with *"Module 'core' depends on named
interface(s) 'api :: management' … Allowed targets: api :: model, api :: msg, api :: control"*.

**`:gcode` and `:backend:terminal` are excluded from the model**, by a predicate in the test. They
live under the base package but are libraries, not parts of the application: neither may depend on
Spring, so neither can carry a Modulith annotation, and treating them as modules would mean either
annotating them or reporting every use of them as a violation. They are consumed the way Reactor or
Jackson is.

The rules this leaves for new work:

- **A new module is a direct sub-package of `org.qw3rtrun.p3d`** with a `package-info.java` carrying
  `@ApplicationModule`. Everything in a nested package is internal unless exported.
- **No submodel names another.** Everything crosses through an interface in `api`:
  `MachineManagementService` for the roster, `PrinterControl` for a machine. A module that genuinely
  needs another names it in `allowedDependencies`, and that is a decision visible in a diff - but
  none does today, and the `@NamedInterface` split is what keeps "depends on `api`" from quietly
  meaning "depends on everything".
- **A nested package must not carry `@ApplicationModule`.** The annotation is what creates a
  module, so one on `frontend.mvc` made Modulith report a nested module sitting beside its own
  parent. An internal package gets a plain `package-info.java` with no annotation.
- **Exported types of a closed module are `@NamedInterface`**. An entity, a repository and an
  `*Impl` are internal by definition - `PrinterEntity` must not leave `mng`.
- **`ApplicationModules.verify()` is the test**, and it is what makes any of the above real. It was
  proved to bite before it was trusted: `mvc` importing `PrinterEntity` fails with *"Module 'mvc'
  depends on module 'mng' … Allowed targets: core"*.
- **`@ApplicationModuleTest`** boots one module with the rest stubbed, which is the test slice to
  reach for before `@SpringBootTest`.

Two behaviours of the annotations that are easy to get wrong, both established by running:

- **`sharedModules` and `type = OPEN` solve different halves.**
  `@Modulithic(sharedModules = "core")` exempts the module from the *dependency* check; it does
  **not** open its nested packages, so without `@ApplicationModule(type = OPEN)` every use of
  `core.model.Descriptor` is reported as "depends on non-exposed type". A shared kernel wants both.
- **The default of `allowedDependencies` is a sentinel** (`¯\_(ツ)_/¯`) meaning unrestricted. An
  explicit `{}` is how a module states that it depends on no other.

The annotation reaches `:backend:core` and `:backend:api` as `compileOnly`, so neither gains a
Spring runtime dependency; the declaration is read out of the bytecode by the verification in
`:app`, which has Modulith on its own classpath.

## One dispatch mechanism

**Spring events**, and nothing else. `MachineEventBus` (`printer-management`) wraps
`ApplicationEventPublisher`, and that is how a module tells another module something happened - it
is what Modulith understands, and its event publication registry, `@ApplicationModuleListener` and
the observability actuator all hang off it. `core` learns a machine was deleted this way.

Two others existed and are gone:

- **`CommandQueryBus`** walked a `List<CommandQueryService>` asking each `isApplicable(Object)`,
  took the first, and cast unchecked; `send(req, responseType)` ignored `responseType`, so the class
  literal at every call site was decoration. It held one registration, `UUID -> Descriptor`, which
  `PrinterReactorManager` now reaches by calling the interface it pointed at. Deleted in A4.
- **`PrinterReactor.invoke`** reflected over `PrinterState`'s declared methods at construction and
  resolved each message by a breadth-first walk of its supertype graph. Deleted in A5; the
  aggregate dispatches for itself, with a pattern switch, in `PrinterState.onEvent` and
  `onCommand`.

**Routing a message is the business of whatever acts on it.** The reactor's job is to carry
messages, not to work out who wants them, and moving the switch into the aggregate is what made it
checkable: a handler that is renamed or deleted now fails the build (`no suitable method found for
on(TemperatureReportedEvent)`), where the reflective version compiled happily and went quiet at
WARN level.

**The switches are not exhaustive in the compiler's sense, deliberately.** `GEvent` is not sealed,
and sealing it would close the application's event vocabulary to the modules that are going to add
to it - plan item C2 adds job events. `GRq` cannot be sealed at all: it has 295 implementations in
`:gcode`. So each switch carries a `default`, and the two defaults differ on purpose: an unhandled
**event** is a debug line, because most replies genuinely are not the aggregate's business; an
unhandled **command** is a warning, because a user asked for it and nothing happened.

**New work does not add a second mechanism back.** When a change touches the dispatch, reducing it
is in scope and growing it is not.

## What CQRS means here

Commands change state and return nothing useful; queries return state and change nothing. The split
is worth keeping because the two halves have genuinely different transports in this application - a
command arrives as a `POST` and ends as bytes on a serial link, a query is answered from memory or
from JPA - but it is **not** worth a framework, an event store or a separate read model at this
size.

Concretely:

- A command is a record in `:backend:core` (`ConnectCmd`) or a `:gcode` command class
  (`SetHotendTemperature`), handled by exactly one handler, returning `Mono<Void>`.
- A query returns a projection record (`Descriptor`, `TemperatureReport`), never an entity and never
  a mutable aggregate.
- An event is a record implementing `ManagementEvent` or `GEvent`, published after the state change
  it describes, and carries the id and a timestamp.
- **Events are facts in the past tense** and are named that way: `MConnectedEvent`, not
  `ConnectEvent`.

## The printer aggregate and its lifecycle

`PrinterState` in `:backend:core` is the aggregate - connection state, machine profile,
temperatures. It is seeded from the roster's `MachineProfile`, so a restart knows what each machine
is before anything connects to it; temperatures are deliberately not restored, because a reading
from before the restart is a stale number that would read as current.

### One enum is the connection state

`MachineConnectionState` in `api` is the single answer to "what is this printer doing":

```
OFFLINE    --connect asked for-->  CONNECTING
CONNECTING --socket up--------->   ONLINE   --block acknowledged-->  IDLE
CONNECTING --attempt failed---->   CONNECTING (backoff, attempt+1)
CONNECTING --attempts spent---->   OFFLINE  (with the reason)
IDLE       --busy notice------->   BUSY     --notices stop-->  IDLE
linked     --Error: reported--->   ERROR    --next good reply-->  IDLE
linked     --link dropped------>   CONNECTING, if a connection is still wanted
any        --disconnect asked-->   OFFLINE
```

It replaced `PrinterState.connected`, `PrinterState.online`, `PrinterEntity.connected` and
`PrinterReactor`'s implicit `terminal == null` - four truths, any two of which could disagree - plus
four event records that between them said less than the one `ConnectionStateChanged` does. The rules
it leaves:

- **Intent is held apart from state.** `ConnectionLifecycle.wanted` is what the user asked for; the
  state is where the machine is. A drop while a connection is wanted retries; the identical drop
  after Disconnect rests. Code that needs to know "should this come back" asks the first, not the
  second - and the absence of that distinction is why the code this replaced had no retry at all.
- **`ONLINE` is a socket; `IDLE` is a firmware that took something this host sent.** A terminal
  server with nothing plugged into it stops at `ONLINE`. Do not treat the two as synonyms.
- **A reply does not leave `ONLINE`; an acknowledgement does.** B3 shipped "any reply" on that
  arrow and a live run disproved it: `M155 S1` left running by an earlier session writes a
  temperature a second into whatever socket is open, and a printer that queued output while nothing
  was listening delivers the backlog the moment something is. Both reach a host whose outbound half
  is dead. `ConnectionLifecycle.blockAcknowledged()` owns the arrow and is fed by the transport's
  `BlockAcknowledged`, which names a block this session put on the wire and got back. It costs
  nothing in the normal case because `onOnline` sends `M115` and `M155 S1` the moment the socket
  opens, so a healthy machine acknowledges within a round trip of connecting. **Anything that needs
  to know "is this machine actually usable" asks for `IDLE` and not for `linked()`.**
- **`ERROR` is still linked.** On a Marlin an `Error:` is routine traffic the printer recovers from,
  so it clears on the next good reply. A fault is not a lost connection.
- **The lifecycle is a plain object with no Reactor in it.** Every transition returns the event it
  caused, or `null` when nothing moved; the caller emits. Timing - the backoff, and the clock that
  ends `BUSY` - lives in `PrinterReactor` and in an injected `Clock`, which is what makes every
  arrow unit-testable without a socket or a scheduler.
- **Leaving `BUSY` is clock-driven: not a timer, and not the next event.** Marlin repeats
  `echo:busy:` every couple of seconds while the handshake's `M155 S1` interleaves a temperature
  report every second, so "busy until something else arrives" flickers once a second. A busy notice
  stamps the clock and a later reply checks the gap.
- **A command is refused when the state is not linked.** It used to be a 500 and a
  `NullPointerException`: the `GSender` is held only while a link is up and every arm dereferences
  it, so the stack trace pointed at the aggregate instead of at the machine being unreachable.

### Two halves describe a machine, and neither may write the other

`MachineProfile` is what a machine **reported** - `M115`'s key-values and the `Cap:` lines after it.
`MachineSpec` is what a person **declared** - bed volume, extruder count, nozzle diameters,
materials and tags. `Descriptor` carries both, apart.

**The separation is structural, not a flag per field**, and that is plan item B4's "what was
discovered is distinguishable from what was typed". `PrinterEntity.learnedFirmware` writes the
profile columns, `PrinterEntity.declare` writes the spec columns, and neither can name the other's.
A flag would have been worse in three ways: every writer becomes responsible for setting it, the
discovered value is lost the moment somebody overrides it - so a reflashed machine cannot be told
its typed-in value is now wrong - and a page cannot show both, which is exactly what the edit page
does show.

The rules that follow:

- **A firmware report replaces the profile wholesale; an edit replaces the spec wholesale.** Both
  are complete statements from their source. The one exception is a capability, which upserts,
  because a firmware sends them one line at a time with nothing marking the last.
- **Where the two meet, declared wins**, and the rule lives once in
  `MachineSpec.extruderCountOr(profile)`. `Descriptor.extruderCount()` and `PrinterState` both call
  it rather than keeping a copy. A field that later gains a discovered counterpart - bed volume and
  nozzles are both in an `M503` dump nothing asks for yet - resolves there too.
- **Null means "nobody said", and it is a third state.** A bed of zero is not a bed; a declared
  extruder count of zero would refuse every command rather than deferring to the firmware. The
  collections normalise to empty instead, because they arrive from a web form.
- **Groups are tags.** B4 offered "free-form tags or a group id"; a group is a tag a reader filters
  on, costs the same, and lets a machine belong to two.
- **An edit reaches a running machine.** `MachineManagementImpl.update` re-publishes
  `MRegisteredEvent`, `PrinterDirectory` updates its replica, and `PrinterReactorManager.redeclare`
  hands the new spec to an aggregate that is already running. Without that last step a declared
  extruder count would reach the database and not the object that refuses the command - the same
  write-only defect B3 removed. `redeclare` must never *build* a runtime: that event is re-published
  for every machine at startup and on every firmware report.

### Retry belongs to the reactor, and wraps the whole conversation

`PrinterReactor.connect()` puts everything from `TerminalManager.connect()` to the end of the
session inside one `retryWhen`, so a link that drops after an hour re-enters the chain exactly where
a refused connection does - "reconnect a machine that dropped" needs no second mechanism with its
own backoff and its own bugs. Three things about it that are easy to get wrong:

- **A clean close is turned into an error on purpose.** `onConnected` completes when the socket
  closes cleanly, and left as a completion it falls straight out of the chain - the printer would
  sit in `ONLINE` forever with nothing attached.
- **Announce the failure from `doBeforeRetry`, not from a `doOnError` before the retry.**
  `doBeforeRetry` runs only once the filter has passed and another dial will genuinely happen, so a
  machine never announces that it is backing off towards an attempt that will never be made. The two
  ways the series can end - attempts spent, or the intent withdrawn - both reach the `doOnError`
  after it, and both mean `OFFLINE`.
- **`ConnectionPolicy.maxAttempts` counts the first dial; `Retry.backoff` counts retries.** Hence
  the minus one. Jitter is left at Reactor's default on purpose, so several printers dropped by one
  network blip do not all redial on the same tick - which means a test asserts the *sequence* and
  not the instants.

### The persisted connection state is derived, and it has a reader

`printer_entity.connection_state` is written from `MConnectionChangedEvent` and from nowhere else -
only `core` can know this, because knowing it means holding the socket - and
`PrinterEntity.isConnected()` is computed from it rather than stored beside it. It is read once, at
startup: `PrinterReactorManager.onRegistered` redials whatever was connected when the process went
down.

**Both halves are required.** The `connected` boolean this replaced was write-only - nothing in
production ever set it - so a column written by the runtime and read by nothing would have been the
same defect in a new place.

**A connection change is deliberately not re-announced**, unlike a firmware report. The fact came
from `core`, and handing it back to the module that redials on announcements has exactly one
possible effect.

Two rules for anything that touches the runtime:

- **A reactor is a resource and must be released.** `PrinterReactorManager` owns the map and
  `release(uuid)` is how one goes away: it stops the reactor, which disposes any retry in flight,
  closes the terminal and *completes* the update sink, so an SSE subscriber is told rather than left
  hanging. Anything that creates a resource here owns stopping it, and says in a test that it did.
- **`openReactor(id)` builds a resource** - which is why it is not called `get` - but it does not
  dial. Its pair is `release(id)`; a printer deleted from the farm releases its runtime through
  `MDeletedEvent` rather than through a call across the module boundary.

## A state machine is a table, a refusal is a value

Plan item C2 added the print queue. The shape it establishes is the one to copy for the next thing
with states in it, and it is the same shape `ConnectionLifecycle` arrived at from the other end.

- **The legal moves live on the enum.** `JobState.canMoveTo` is the whole table. A page choosing
  which buttons to show, a scheduler choosing what it may assign, and the service enforcing it are
  one question; three answers is how a UI comes to offer an action the backend refuses. Assert the
  whole table in a test - every state against every state - rather than the edges that came to mind.
- **The entity moves itself.** `JobEntity.moveTo` returns the event it caused or null, and sets the
  timestamps that belong to the transition: `startedAt` only on the first `RUNNING`, so a resume
  does not make a job look as though it started twice.
- **A refusal is a sealed type, not an exception.** `JobOutcome.Refused` carries where the job
  actually is, what was asked, and a sentence. The REST layer maps it to 409 and 404 without
  guessing from an exception's class. Nothing reaches the database to discover that a move was
  illegal.
- **Terminal means terminal**, including to other terminal states. Re-running means a new row. A
  finished job is a record, and C5 writes recovery information onto exactly those.
- **One event with from/to beats one event per transition.** `releasedAMachine()` is a question a
  scheduler asks and no single `JobStartedEvent`-style record can answer.

## The event publication registry

`@ApplicationModuleListener` is `@TransactionalEventListener` + `@Async` + `@Transactional`, and
turning it on has three consequences worth knowing before the first one is written:

- **It needs its table as a changeset.** `EVENT_PUBLICATION` lives in
  `backend/jobs/src/main/resources/db/changelog/modulith-events.xml`, carried by the first module
  that publishes durably and included by `:app`. Without it every publishing transaction dies on
  commit. Liquibase owns it rather than `spring.modulith.events.jdbc.schema-initialization`, for
  the same reason nothing here uses `ddl-auto`.
- **Every event type must round-trip through JSON, and that is not free.** The registry serialises
  on publish and *deserialises to replay*. `api`'s management events are Kotlin data classes, which
  Jackson cannot construct - so publishing and delivery worked and only recovery was broken, which
  is the half the registry exists for. The fix is a `tools.jackson.databind.JacksonModule` bean
  (Modulith's own extension point); **a `JsonMapper` bean would quietly become the whole
  application's** and let one module decide how the web layer renders JSON.
- **It only works for an event published inside a transaction, and that rules out whole callers.**
  `JobFeedCompleted` is published by `core` from a reactor-netty thread, which has no transaction
  and no database to tie one to - so an `@ApplicationModuleListener` on it was *silently dropped*,
  and a 96,152-block print finished with the job still reading `RUNNING`. Those listeners are plain
  `@EventListener` with their own `@Transactional`, which makes the outcome **at-most-once** and is
  recorded as such. **Before reaching for the annotation, ask where the event is published from.**
- **`@Transactional` goes on the listener, not only on the service method it calls.** A listener
  that calls its own service method is a self-invocation and does not go through the proxy, so the
  transaction the service method asked for never starts.
- **Testing one needs a transaction and a wait.** An event published outside a transaction is
  dropped - that is the point, there is no commit to tie it to - so a test publishes through a
  `TransactionTemplate`, and then awaits, because `@Async` means the publisher returns first. Assert
  on `CompletedEventPublications.findAll()`: that something was written is not the claim, that it
  was *completed* is, because an incomplete publication is what gets replayed on the next start.

## Large content never touches the heap

Plan item C1 added the file store, and the rule it establishes applies to anything else that moves
bulk bytes: a sliced part is tens to hundreds of megabytes, and the failure mode is an
out-of-memory error on the first real print rather than anything a test at one-kilobyte scale would
show.

- **The signature is the enforcement.** `GcodeFileService` takes and returns
  `Publisher<ByteBuffer>`. A `byte[]`, a `String` or a `Resource` anywhere on that path is the whole
  bug. `@RequestBody byte[]` in a controller is the same bug one layer up.
- **`ByteBuffer` and not `DataBuffer`, because `api` carries no framework.** The web layer converts
  at its own edge - which is also where the pooled buffer must be released, so the two jobs belong
  together. It costs one copy per chunk; the alternative was a buffer-lifetime contract across a
  module boundary, which is a rule that holds until somebody changes something that looks unrelated.
- **Demand has to be one at a time, and nothing else is bounded.** `concatMap(f, 1)` requests one
  element, processes it, then asks for the next, so backpressure reaches the socket. `reduce`,
  `doOnNext` and `collectList` all request `Long.MAX_VALUE`; `toIterable` queues 256 elements. Any
  of them turns a bounded pipeline into a buffered one with no visible symptom.
- **Prove it by counting demand, not by measuring the heap.** `GcodeBlobStoreTest` asserts the
  largest single `request(n)` is one. A heap assertion is at the mercy of the collector; this one
  fails deterministically the moment the prefetch changes.
- **Bulk bytes go on a filesystem, not in a column.** Content-addressed by SHA-256, two levels of
  fan-out, which also makes storing idempotent: the same bytes produce the same path, so the dedupe
  is the naming rather than a check.

## REST, SSE and WebSocket

- **REST** is `@RestController` returning `Mono`/`Flux`, under `/api-manager` for the farm and
  `/api-printer/{id}` for one machine.
- **SSE is what the UI actually uses**: `produces = TEXT_EVENT_STREAM_VALUE` on
  `/api-printer/{id}/stream`, consumed by `EventSource` in `static/js/lib/client.js`. It is the
  working live channel and the one to extend. **The replay sink is seeded with the current
  connection state**, so a late subscriber learns where a machine is without waiting for something
  to happen to it; `GET /api-printer/{id}/connection` answers the same question without a stream.
- **The farm-wide stream is `GET /api-farm/stream`** (plan item D2), and it is what a dashboard
  should hold instead of one `EventSource` per machine. **It opens nothing**: `FarmEvents` listens
  on the application event bus, where every machine's events already go, rather than subscribing
  through `PrinterControl.updates(id)` - which instantiates a reactor as a side effect and would
  dial forty printers to render a page that is only looking. Each message is an `EventMessage`
  carrying the machine id, so one stream is demultiplexed by the client.
- **A farm-wide sink must not replay, and must not buffer.** A replaying sink holds the last event
  for the farm rather than per machine, which is useless - so a new subscriber gets a *built*
  snapshot (every machine's current state) concatenated with the live sink. And the sink is
  `Sinks.many().multicast().directBestEffort()`: `onBackpressureBuffer` **warms up**, buffering
  everything emitted while nothing is subscribed and handing the whole backlog to the first
  subscriber to arrive. On a farm that runs for days with no dashboard open that is every
  temperature reading since boot. Lossy is also correct downstream - a browser that stops reading
  must never stall the reactor feeding it.
## Security - who may do what to a farm of heaters

Plan item D3. The rules are written around **consequence**, which is why there are three roles and
not a permission matrix: VIEWER may look, OPERATOR may run the machines, ADMIN may change what the
farm *is* and read the actuator. The middle boundary is the one that matters - *a printer is a
heater, and an unauthenticated `M104 S300` is a safety problem rather than a data one*.

**`.anyExchange().authenticated()` is the last rule, so a path nobody thought about is closed.**
Adding an endpoint without adding a rule makes it require a login rather than making it public.

### The session cookie is forced, not chosen

`EventSource` cannot set an `Authorization` header. A bearer token would therefore mean credentials
in a query string, where they end up in access logs and `Referer` headers. So the farm authenticates
with a session cookie - and a cookie-authenticated API **needs CSRF protection**, which is why the
token, the `X-XSRF-TOKEN` header in `client.js` and the hidden fields in the Thymeleaf forms are all
part of the same change.

Three things about that are not obvious and each one was found by running, not by reading:

- **The token the server issues is not the token it accepts, by default.** Spring Security's default
  request handler masks the rendered token (BREACH mitigation), so a client that reads the
  `XSRF-TOKEN` cookie and echoes it back is refused with **403 Access Denied** - while the cookie
  plainly holds the value being sent. State `ServerCsrfTokenRequestAttributeHandler` explicitly;
  that is the documented configuration for a client that reads the cookie.
- **`CookieServerCsrfTokenRepository` writes the cookie lazily.** Nothing subscribes to the token on
  a plain GET, so no cookie is issued and the first POST fails. The `WebFilter` in
  `SecurityConfiguration` that subscribes to it is load-bearing, not boilerplate.
- **Thymeleaf does not add the CSRF field on WebFlux.** The `RequestDataValueProcessor` that does it
  automatically is a servlet-stack feature, and nearly every example online relies on it. A form
  rendered without the field is posted without a token and rejected by the filter that issued one -
  a 403 on a page that looks completely normal. `PageModelAdvice` puts `_csrf` in every page's
  model; a new template with a form needs the hidden field.

### One password encoder, declared by the module that writes hashes

`IdentityModule` declares the `PasswordEncoder`, and `SecurityConfiguration` **hands it to the
authentication manager explicitly**. Leaving that out is not a subtle bug: the manager defaults to a
*delegating* encoder that reads a `{bcrypt}` prefix off the stored hash, finds none, and throws - so
the login page answers **500** rather than "wrong password".

Two encoders configured differently would be worse: the farm would write passwords with one scheme
and check them with another. There is exactly one bean, and `frontend` injects it as a framework
type rather than naming `identity`.

### `identity` owns the people; `frontend` owns the request

Authenticating a *request* needs a request, and `frontend` is the only submodel that has one. So
the filter chain, the login page and the user-admin endpoints are there, and the table, the hashing
and the bootstrap are in `:backend:identity`, which has **no web dependency at all**.

That split is what lets `FarmUserDirectory.add` take a **raw password** and return a user carrying
only a hash: cleartext exists for the length of one call, and no other module can store, log or
forward one. Nothing in `api` can carry a password - `FarmUser` has a hash and `api` names no
framework, which is what keeps Spring Security out of the module both the web side and the
persistence side depend on.

Users are deliberately **not** part of `printer-management`. A module that answers "what printers
are there" has no business also answering "who may log in".

### Rules for new work here

- **`ReactiveUserDetailsService` is called on an event loop.** `FarmUserDetailsService` wraps the
  blocking directory in `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`, and
  `fromCallable` rather than `just` because `just` evaluates eagerly on the calling thread. A login
  is the one request every user makes, at shift change, when the farm is busiest.
- **Never return `FarmUser` from a controller.** It carries the hash. `UserController.Account` is
  the wire shape and has no such field *by construction* rather than by remembering to strip it.
- **A machine caller gets 401; a browser gets a redirect.** One entry point for the whole
  application would send a `fetch` to the login page, which follows the redirect and receives **200
  and a page of HTML** - so an expired session shows up as a JSON parse error, and a test asserting
  "this endpoint is protected" passes against a 200.
- **An anonymous write is refused by CSRF (403) before authentication is consulted (401).** Both are
  correct and the order is fixed. A test that accepts either would keep passing if authentication
  were removed altogether, so assert the specific one and cover the other case separately.
- **There is no default password.** `InitialAdmin` runs only when the roster is **empty** - not
  "when the admin is missing", so a deliberately deleted account does not come back on restart - and
  either uses `p3d.security.initial-admin.password` or generates 192 bits and logs them once.

### Testing it

`FarmSecurityTest` binds `WebTestClient` to a **real port** and performs the browser's actual
sign-in: fetch the page, read the `XSRF-TOKEN` cookie, post the form, keep the session. A mock
exchange skips exactly the hand-off most likely to be wrong.

**The useful signal is 500.** The collaborators in `BootVuejsApplicationTests.Collaborators` throw
on every call, so a 500 proves the request reached application code and a 401 or 403 proves it did
not. A test that only checked for "not 200" would pass against an application that was merely
broken.


- **There is no WebSocket channel, and that is deliberate.** `WSGPrinterHandler` was deleted in A4:
  it read a session attribute nothing wrote, so its id was always null, and no client ever connected
  to `/ws-printer/*`. If one is wanted later it is a day's work on a *working* SSE contract, and it
  should be written against the farm-wide stream of plan item D2 rather than the per-printer one.
- **A request body is an `api` record, never a `:gcode` command class.** The REST contract used to
  follow the Marlin command set, so a rename by the generator was a breaking API change - and one
  had already happened silently, with `/auto-report-temp` binding a `seconds` field while the browser
  sent `period`. `PrinterCommand` is sealed and `core` translates it.
- **The browser's contracts are pinned by test, because the build does not compile the browser.**
  `PrinterCommandBindingTest` for what it posts and `EventStreamContractTest` for what it reads. A
  renamed field or event name on this side fails there rather than in a browser.

## Persistence

- **Liquibase owns the schema. Hibernate does not.** There is no `ddl-auto` anywhere and there must
  not be. A new column is a changeset.
- **One fragment per module, assembled by `:app`.** `app/src/main/resources/db/changelog/db.changelog-master.xml`
  includes `printers.xml` from `printer-management` and `gcode-files.xml` from `files`; each module
  points its own tests at its own fragment. A module owns its tables, which is the same rule as
  everything else here - the single application-wide changelog that preceded this would have given
  the files module's schema to the roster.
- **A fragment carries a `logicalFilePath`, and moving a changeset never changes it.** Liquibase
  identifies a changeset by (id, author, **filename**), so renaming the file it lives in makes every
  existing database believe it has not run. `printers.xml` therefore declares
  `logicalFilePath="db/changelog/db.changelog-master.xml"` - the path is a historical fact now, not
  a location. Verified by migrating a populated database rather than reasoning about it.
- **H2 file-backed is the development store** (`./data/printersdb`). Nothing may depend on an
  H2-specific feature, because it is not the production store.
- An entity is internal to its module. A controller returns a record mapped from it.

## Testing

- **`WebTestClient`** for controllers, bound to the application context or to a single controller.
  Assert status and body; for a stream, use `.returnResult(T.class).getResponseBody()` and then
  `StepVerifier`.
- **`StepVerifier`** for anything with demand or timing in it, exactly as `:backend:terminal` does.
  A test that collects a `Flux` into a list proves the values and nothing about the stream.
- **`ApplicationModules.verify()`** as a plain unit test, once module boundaries are declared. It is
  fast and it is the only thing that keeps them true.
- **`@ApplicationModuleTest`** in preference to `@SpringBootTest` - it boots one module and stubs the
  rest, so a failure names the module that broke.
- **Repository tests run against the real Liquibase schema**, never a Hibernate-generated one - the
  two disagreeing is the failure worth catching, and `PrinterProfilePersistenceTest` caught one
  (a column named `value`, which is a reserved word; Hibernate emitted it and the database rejected
  the query). **`@DataJpaTest` does not exist on this classpath**: Spring Boot 4 moved the slice
  annotations out of `spring-boot-test-autoconfigure`. `@SpringBootTest` with `@Transactional` gives
  the same round-trip-and-roll-back at the cost of booting the module.
- **A context-load test is not a test of behaviour.** `MachineManagementApplicationTests` adds a
  printer and connects it and asserts nothing; treat that as a placeholder rather than coverage.
- Run a module's tests with `:backend:api:test`, `:backend:printer-management:test`, `:app:test`. The
  Gradle summary line only prints on failure, so count them out of the XML under
  `<module>/build/test-results/test/`.

## Patterns that work here

### Prefer a record and an exhaustive switch to reflection

Java 25 has sealed interfaces and pattern-matching `switch`. A dispatch written that way is checked
by the compiler, reads as a table, and fails the build when a case is added - all three of which the
reflective invoker in `PrinterReactor` gives up.

### Keep the aggregate out of the web module

State that survives a request belongs below the controller, not beside it. The test for whether a
class is in the right module is whether `:backend:printer-management` could use it without depending on
WebFlux.

### Match the delivery to what the state is

Three mechanisms, and picking the wrong one has already cost a debugging session each:

- **A replica of current state** wants a plain `@EventListener` and a re-announcement at startup.
  It is derived, it is rebuilt every boot, and nothing about it must survive the process.
  `PrinterDirectory` is the example.
- **A fact that must not be lost** - a job that was running when the printer went offline - wants
  Modulith's event publication registry: at-least-once delivery across a transaction boundary, with
  persistence and replay. **Turning it on needs a Liquibase changeset for its `EVENT_PUBLICATION`
  table.** It switches itself on the moment a `@TransactionalEventListener` exists alongside
  `spring-modulith-starter-jpa`, and without the table every publishing transaction dies on commit
  with `Table "EVENT_PUBLICATION" not found`. That is not a theory; it is how the replica's first
  version failed.
- **A live UI stream** wants `Sinks`, and nothing durable at all.

### Configuration belongs to `:app`

A library module that carries its own `application.properties` with a datasource URL in it will
fight the assembly that imports it. `:backend:printer-management` and `:app` both define the same datasource
today; the module's copy should be test-scope or gone.

## Review checklist

- [ ] no blocking call on an event loop - every JPA/JDBC touch from a reactive path has
      `subscribeOn(boundedElastic())` or an equivalent boundary
- [ ] no `block()`, `Thread.sleep`, or synchronous I/O in a controller or a pipeline
- [ ] module boundaries declared with `@ApplicationModule` and verified by a test, for any module
      the change touches
- [ ] no new dispatch mechanism; an existing one reduced if the change touched it
- [ ] no entity outside its own module; controllers return records
- [ ] schema changes are Liquibase changesets, never `ddl-auto`
- [ ] every resource a change creates has an owner that releases it - reactors, sinks, sockets,
      subscriptions
- [ ] events are past-tense facts carrying an id and a timestamp
- [ ] connection state comes from the lifecycle enum, and nothing keeps a second copy of it
- [ ] a stream is asserted with `StepVerifier`, not collected into a list
- [ ] `:backend:core` still has no WebFlux, JPA or Reactor import

## Known debt in this layer

Live examples of what this skill rules out, so nobody copies them as precedent. Each is verified in
the source, not suspected.

- **The blocking boundary is per-call-site, not behind a service.** Every JPA touch is now
  scheduled onto `boundedElastic` and `BlockingBoundaryTest` records the thread the service was
  called on, so forgetting one fails a test rather than a farm. What is not done is the stronger
  shape the skill prefers once there are several callers: a service that hands back an
  already-scheduled publisher, so a caller cannot forget in the first place. That needs either
  Reactor in `api` or a frontend-local facade, and A4 is about to delete the `ObjectFactory`
  indirection those controllers use, so it is worth doing after rather than before.
- **The module boundaries are declared but not yet tight.** Four modules are verified, and the
  three closed ones depend on nothing but the shared kernel - but `core` is OPEN, so nothing stops
  a type that should belong to one module from being parked in the shared vocabulary instead.
  `@NamedInterface` is not used anywhere yet; the first closed module that needs a partial export
  introduces it.
- **A module that needs a collaborator it does not contain gets it in test scope.** The frontend
  submodel holds controllers; what answers them is in `printer-management`, which it must not depend
  on. That was papered over by a `@ConditionalOnMissingBean` stub in *main* answering every query
  with `null` - so a misconfigured farm read as an empty one. It is deleted; `BootVuejsApplicationTests`
  supplies the bean itself, and its version throws rather than returning empties.
- **Temperatures still do not survive a restart, and should not.** The aggregate is seeded from the
  roster's `MachineProfile` - firmware and capabilities - but a temperature from before the process
  restarted is not state, it is a stale number that would read as current. A machine starts with no
  reading and says so until it reports one.
- **A feed outcome is at-most-once.** `JobFeedCompleted` and `JobFeedFailed` are published from a
  reactive thread with no transaction, so they cannot go through the event registry - see above. If
  the process dies between the last block and the listener, the job is left `RUNNING` with nothing
  feeding it. C5 has to reconcile that at startup, and already has to for a job whose machine went
  away mid-print.
- **A cancel does not home, park or disable the steppers.** It cools and retracts. Homing drags the
  nozzle across the part; disabling the steppers drops Z on a machine without a brake. Both need a
  machine profile richer than B4's, and getting either wrong breaks a part or a printer.
- **A pause does not park either**, so the nozzle sits over the part and will ooze onto it. Real
  firmware has `M125`/`M601` for this and both need safe coordinates the farm does not know.
- **Progress is approximate by design, and the column names do not say so.** `blocks_sent` and the
  rest are a checkpoint written about once a second, not a ledger - a restart reads them to within
  one interval. Anything that needs an exact count needs a different mechanism and a reason.
- **Resuming after a *disconnect* is still not implemented, and resuming after a *pause* is.** The
  difference is whether the machine kept its state. A paused printer is connected, hot and
  positioned, so a resume is a seek - skip the blocks it has and carry on. A reconnected one has
  none of that and would need the file's state-setting commands replayed up to the resume point;
  that job fails with its position recorded instead. Do not collapse the two.
- **A resume reads its position from the checkpoint, so the checkpoint has to be exact when a feed
  stops.** It was not, and the cost was a printer extruding over what it had already printed: the
  timer's readings lag by up to a second, so a resume restarted thousands of blocks early. The feed
  takes one exact reading when it stops and the checkpoint is monotone rather than state-gated.
  **A "latest wins" checkpoint is a different thing from a "highest wins" one, and only the second
  is safe to resume from.**
- **Sessions are in memory.** A restart signs everybody out, and a second instance would not share
  them. Fine for one host; the first thing to fix if this is ever run as a pair.
- **Nothing rate-limits the login**, so the sign-in form is open to guessing at network speed.
  BCrypt makes that slow, not impossible.
- **No password policy, and the generated initial password is never required to be changed.**
- **HTTPS is not enforced**, so the session cookie is not `Secure`. Deliberate on a workshop LAN and
  wrong anywhere else.
- **Users are managed over the API only** - there is no page, so creating an operator means a
  `curl`.
- **The whole actuator is ADMIN-only**, so there is no anonymous liveness probe. A deployment that
  needs one needs a deliberate exception rather than opening the lot.
- **An event published while a farm subscriber is mid-snapshot is dropped.** `updates()` is the
  snapshot `concatWith` the live sink, so the sink is not subscribed until the snapshot completes -
  and the snapshot is now asynchronous, one `Mono` per machine. The window is milliseconds and the
  sink is lossy by design, so nothing is *wrong*; but a dashboard can miss a state change that
  lands exactly as it attaches, and the fix (subscribe the sink first, then prepend the snapshot)
  wants its own change rather than being smuggled into D2.
- **First fit does not prefer the least capable machine that fits.** A job with no requirements
  takes the first eligible printer in roster order, so it can occupy the one big machine a
  restricted job was waiting for. Observed live. Fixing it means ranking candidates, which is a
  cost model and which D1 explicitly rules out - so it wants arguing for before it is built.
- **A module that only reads has no obvious place to be tested end to end.** `FarmScheduler` is
  unit-tested against three stubs and verified by hand against two stand-in printers; there is no
  `:app` test that drives a whole farm, because standing two printers up in one needs the probe
  machinery rather than a context.
- **`JobStateChanged` has no durable listener, so the registry records nothing for it.** The
  registry is wired, its table is there, and it is proven by the queue's own `MDeletedEvent`
  listener - but a job's own events are at-most-once until something listens with
  `@ApplicationModuleListener`. C3 will be the first.
- **A job holds a file id with no foreign key, by design, and nothing stops the file being
  deleted.** Deleting a file a queued job points at leaves a job that will fail when it starts. The
  right fix is for the file store to refuse, or for the queue to listen for a file deletion the way
  it listens for a machine's - and that needs a file-deleted event, which does not exist yet.
- **A blob whose row is lost is never collected.** Deleting a file removes both, but a crash
  between writing the bytes and committing the row leaves bytes nothing refers to. That order is
  deliberate - the other one leaves a row pointing at content that is not there, which is a file
  that lists fine and fails when a print starts - so the debt is the sweep, not the ordering.
- **An upload has no size limit.** Nothing stops a client filling the disk; the memory is bounded
  and the storage is not. A limit wants to be a configured maximum checked while streaming, which
  is a few lines in `GcodeBlobStore.write` and a decision about what the number should be.
- **The slicer metadata windows are bounded to 64 KiB at each end of a file.** A slicer that writes
  its summary somewhere in the middle of a large file is not read. That is the price of not
  tokenizing two hundred megabytes of motion commands to find four numbers, and it is the right
  trade until a real file turns up that needs otherwise.
- **Only the extruder count has both a declared and a discovered source.** Bed volume and nozzle
  diameters are declared-only, not because that is right but because B2 asked `M115` and nothing
  asks `M503`, which is where Marlin reports axis limits and the configured nozzle. When something
  does, they become overrides resolved the way the extruder count is and no type changes shape.
- **Nothing validates a spec against reality.** A farm can declare four nozzles on a machine whose
  firmware reports one extruder, and nothing says so - not the form, not the API, not the page that
  shows both halves side by side. That is deliberate for now, because the override exists precisely
  for machines whose firmware is wrong, but a warning where the two disagree would be worth having
  before D1 starts matching jobs against it.
- **Giving up on a machine is silent except on that machine's own event stream.** When the attempts
  run out the lifecycle goes to `OFFLINE` with the reason and the roster persists it, but nothing
  pushes that to a user who is not watching that printer's SSE channel. The farm-wide stream of plan
  item D2 is where it belongs; a notification surface is not in this plan at all.
- **`BUSY` can outlast the machine's busy phase by one reply.** It is left when a reply arrives and
  the busy notices have been quiet for the timeout, so a machine that finishes work and then says
  nothing stays `BUSY` until it speaks again. Survivable because `M155 S1` makes it speak every
  second, and it is the price of keeping a scheduler out of the aggregate.
- ~~**`IDLE` proves a reply arrived, not that this session can send.**~~ **Fixed.**
  `ONLINE -> IDLE` waits for a `BlockAcknowledged` now - see *One enum is the connection state*
  above. The entry is kept because the shape of the mistake is worth remembering: the lifecycle
  moved on *inbound traffic*, and auto-report traffic left running by a previous session satisfies
  that, so a machine whose outbound half was dead read as healthy. A health signal has to be
  something the failure cannot produce.
- **A reconnected session used to be unable to send anything, and that is fixed in the transport.**
  A new session started its window at `N1` while the firmware, still powered and never reset,
  remembered its last line, so every block was rejected and the outbound half was dead for good -
  **only the first session after a printer powered on had ever worked.** `:backend:terminal` now
  opens each session with `M110 N0` and resynchronises a `Resend:` it cannot answer; see
  `reactive-transport-dev`. Nothing in this layer sends `M110`: the line counter belongs to the
  transport, and a caller that sends one as text desynchronises the window without moving it.
- **Kotlin is one file in this layer** (`core/msg/ManagementEvents.kt`), which is enough to pay for
  the Kotlin plugin on `:backend:core` but not enough to settle a language convention. Decide per
  module, and use the `kotlin-tooling-java-to-kotlin` skill when converting.
- **`backend/printer-management/src/main/resources/application.properties` ships a datasource URL**
  from a library module, duplicating `:app`'s and differing from it in the Liquibase changelog path
  (`classpath:db/changelog/...` against `/db/changelog/...`). Related trap, learned the hard way: a
  test-scope `application.properties` **replaces** the main one rather than merging, so a test that
  only wants a different datasource must use a profile overlay (`application-test.properties` plus
  `@ActiveProfiles`) or it silently drops every other setting.
- **There is no written spec for this layer.** This skill is the only authority, so a behaviour that
  callers come to depend on gets written down here, in the change that establishes it.
