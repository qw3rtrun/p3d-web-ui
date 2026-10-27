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

## Spring Modulith - the declared structure

**Four modules**, declared in `package-info.java` and verified by `ApplicationModulesTest` in
`:app`:

| Module | Package | Exports / depends on |
|---|---|---|
| Shared API | `org.qw3rtrun.p3d.api` | closed, exporting four `@NamedInterface`s: `model`, `msg`, `management`, `control` |
| Printer core | `org.qw3rtrun.p3d.core` | `api::model`, `api::msg`, `api::control` — **not** `api::management` |
| Machine management | `org.qw3rtrun.p3d.management` | `api::model`, `api::msg`, `api::management` |
| Frontend | `org.qw3rtrun.p3d.frontend` | all four. Names no other submodel |

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
CONNECTING --socket up--------->   ONLINE   --first reply-->  IDLE
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
- **`ONLINE` is a socket; `IDLE` is a firmware answering.** A terminal server with nothing plugged
  into it stops at `ONLINE`. Do not treat the two as synonyms.
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

## REST, SSE and WebSocket

- **REST** is `@RestController` returning `Mono`/`Flux`, under `/api-manager` for the farm and
  `/api-printer/{id}` for one machine.
- **SSE is what the UI actually uses**: `produces = TEXT_EVENT_STREAM_VALUE` on
  `/api-printer/{id}/stream`, consumed by `EventSource` in `static/js/lib/client.js`. It is the
  working live channel and the one to extend. **The replay sink is seeded with the current
  connection state**, so a late subscriber learns where a machine is without waiting for something
  to happen to it; `GET /api-printer/{id}/connection` answers the same question without a stream.
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
- **One changelog, included from the master.** `:backend:printer-management` points at
  `classpath:db/changelog/db.changelog-master.xml`; `:app` points at `/db/changelog/...`. Those
  resolve to the same file today by accident of the classpath - make it one spelling.
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
- **No job, file or queue concept exists.** The product is a print farm; nothing in the backend
  models a G-code file, a print job, or an assignment of one to a machine. `PublisherQueue`'s
  `Priority` enum is the only piece of the machinery that is already there.
- **Giving up on a machine is silent except on that machine's own event stream.** When the attempts
  run out the lifecycle goes to `OFFLINE` with the reason and the roster persists it, but nothing
  pushes that to a user who is not watching that printer's SSE channel. The farm-wide stream of plan
  item D2 is where it belongs; a notification surface is not in this plan at all.
- **`BUSY` can outlast the machine's busy phase by one reply.** It is left when a reply arrives and
  the busy notices have been quiet for the timeout, so a machine that finishes work and then says
  nothing stays `BUSY` until it speaks again. Survivable because `M155 S1` makes it speak every
  second, and it is the price of keeping a scheduler out of the aggregate.
- **`IDLE` proves a reply arrived, not that this session can send.** The lifecycle moves on inbound
  traffic, and auto-report traffic left running by a *previous* session satisfies that - so a
  machine whose outbound half is broken still reads `IDLE`. Observed live: see the next entry. The
  honest proof is an acknowledgement of something *this* session sent, which is the signal
  [decision 3 of the plan](../../../doc/backend-plan.md) says `:backend:terminal` does not expose
  yet. Do not add a second health flag for this; the fix is the ack signal.
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
