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

**A Gradle module is not a Modulith module, and after the merge most of them are not.** There are
six Gradle modules and eight Modulith modules; the table below is the build, and the one in
*Spring Modulith - the declared structure* is the architecture. Do not confuse them.

| Gradle module | Holds | May depend on |
|---|---|---|
| `:backend:api` | `org.qw3rtrun.p3d.api` | nothing. **Not even Spring**, at compile time or runtime |
| `:backend:core` | `core`, `management`, `files`, `jobs`, `scheduling`, `identity` | `api`, `:backend:terminal`, `:gcode`, Spring, JPA, Liquibase, Reactor |
| `:frontend:web-ui` | `org.qw3rtrun.p3d.frontend` | `api`, WebFlux, Thymeleaf, the Vue build |
| `:app` | `org.qw3rtrun.p3d` | everything. Assembly only |
| *library* | `:gcode` — `org.qw3rtrun.p3d.g` | the Kotlin stdlib. **Nothing else** (`low-level-protocol-dev`) |
| *library* | `:backend:terminal` — `org.qw3rtrun.p3d.terminal` | `:gcode`, `api`, Reactor (`reactive-transport-dev`) |

**Six packages share one Gradle module now, and that moved a guarantee.** Each of them used to be
a module of its own, so `files` naming `jobs` would not have *compiled*. It compiles today, and
`ApplicationModulesTest` is the only thing that catches it - which it does: the merge was not
trusted until a deliberate `files -> jobs` import was shown to compile cleanly and fail that test.
**When you touch a package boundary here, run `:app:test` - the compiler will not tell you.**

**`api` stays a Gradle module of its own, for two reasons.** It cannot merge: `:backend:terminal`
is written in `api`'s event vocabulary and `core` depends on `terminal`, so folding `api` into
`core` makes `core -> terminal -> core`, which Gradle refuses. And it should not: its rule is that
it names no framework at all, and having none on its compile path is an enforcement no test
replaces as cheaply.

The five submodels and their rules are `doc/sub_boundaries.txt`; this table is how they are
realised. **Rule 1 is that every submodel depends on `api` and on no other submodel**, and
`allowedDependencies` on each module declaration is where that is enforced.

`api` must stay free of WebFlux, JPA, Reactor and Spring itself. It is the vocabulary both the web
side and the persistence side are written in, and a framework dragged in through it reaches both.

**`api` depends on `:gcode`, and `:gcode` depends on nothing.** The dependency rule is
`backend -> terminal -> gcode` (`doc/sub_boundaries.txt` rules 6-8), and the direction is the whole
of it: `api` may name a `GRq` or a `GRs`, and nothing may make the protocol name an application
type. An `api` interface implemented inside `:gcode` is the one thing that is never the answer -
that was the state the modules were rescued from once, when `GRs` extended `api`'s `GEvent`.

**The backend does not mirror the `:gcode` model.** There used to be an `api.msg` record for every
reply and every command - `TemperatureReported` beside `TemperatureRs`, `api.msg.SetFanSpeed`
beside `marlin.command.SetFanSpeed` - with `ReplyEvents` translating inbound and
`PrinterControlService.toMarlin` outbound. All of it is deleted. This farm drives Marlin machines
and a Marlin *is* G-code, so there is no second kind of printer to abstract over and no second
vocabulary to keep in step. A new reply that needs to reach the host is now one change (an arm in
`PrinterState.onEvent`) rather than three.

**What `api.msg` still holds is what no firmware has a word for**: a connection moving, a block
acknowledged or abandoned, how far an upload to a card has got, a machine joining or leaving the
farm, and the `M`-prefixed roster events - plus `ConnectCmd`, which is a command rather than a fact.
If a new message is a fact about the host rather than something a machine said, it belongs there;
otherwise it is already a `GRs`.

**The enumeration of that package lives in a test, and the prose deliberately no longer tries.**
`EventMessage`'s javadoc names the farm's own facts and `api.msg`'s `package-info` says the same set
in words; both went stale on `SdUploadProgressed`, and the sentence you are reading was a fifth copy
that had gone stale on two. That is issue #69, and the repository had found three other
hand-maintained lists wrong in the two days before it. `EventMessageCatalogueTest` in
`:backend:api` is the one that cannot: it reads the **compiled** package - not the source files,
because `ManagementEvents.kt` is one file holding four types - and asserts that every type in it is
classified as either travelling as an `EventMessage.event` or not, each of the latter with a reason.

**The contract is that a type added to `api.msg` and classified as neither fails the build.** That
is aimed at the failure the lists exist to prevent, which is not untidy javadoc: it is a new message
reaching a browser that has no `case` for it. `EventStreamContractTest` pins those strings, and it
lives in `:frontend:web-ui`, which this module cannot see - so the guard catches the *moment of
addition* and its failure message is where the next person is told what the addition costs. It does
not check `package-info`'s prose, which names these things in words rather than by type; that
remains documentation, and the failure message says to go and edit it.

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
`:app`. Six of them now live in one Gradle module, which changes nothing here - a Modulith module
is a *package*, and the annotations, the named interfaces and the verification are all unchanged
by how the build groups them:

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
on(TemperatureRs)`), where the reflective version compiled happily and went quiet at WARN level.

**The switches are not exhaustive in the compiler's sense, deliberately.** `onEvent` takes an
`Object`, because what travels is either a `:gcode` reply or one of the farm's own events and no
type spans both - inventing one would mean `:gcode` implementing an application interface, which is
the dependency that is never for sale. `GRq` cannot be sealed either: it has 295 implementations in
`:gcode`. So each switch carries a `default` - and **both defaults now pass the message along**;
see the next section for why they used to drop it and what that cost.

**New work does not add a second mechanism back.** When a change touches the dispatch, reducing it
is in scope and growing it is not.

## IMPORTANT - every operating message crosses the aggregate, in both directions

**This is the rule this layer has broken most often, three times, each time shipping a finished
feature that did nothing.** Read it before touching `PrinterState`.

Every operating command and event passes through `PrinterAggregate`, and **every known one has a
named handler on it**:

| Direction | Entry point | Handler |
|---|---|---|
| command, going out | `PrinterState.onCommand(GRq<?>)` | `handle(<Command>)` |
| event, coming back | `PrinterState.onEvent(Object)` | `on(<Event>)` |

That is the CQRS seam of this application. The write side leaves through `handle`, the read side
arrives through `on`, and nothing crosses between the farm and a machine unaccounted for. **A
handler may be one line** - most of the motion and card handlers delegate straight to
`passThrough` - and it still earns its place: it is where the domain rule lands when there is one
(`handle(ReleaseSDCard)` names the `M22`-during-a-print refusal it does not yet make), and it makes
the set of things this farm can do readable in one screen.

### The defaults are the safety net, not the gate

Both `default` arms **pass the message on**: `passThrough(command)` outbound, `republish(event)`
inbound. Forgetting a handler now costs whatever the aggregate would have decided, which for most
messages is nothing. It used to cost the whole feature, and here is the bill:

| What shipped dead | Direction | How it was found |
|---|---|---|
| the motion commands | outbound | a live run; endpoints answered 200 and nothing reached the printer |
| the Storage tab's five buttons | outbound | a live run; `no handler for InitSDCard(M21), so nothing was sent` |
| the Storage tab's two replies | inbound | a live run, *after* fixing the outbound half |

Each time the translation was tested and passing, the route returned 200, and the page rendered.

**The reason it kept happening is structural, not careless, and it is why an allow-list cannot work
here.** The arms live in `:backend:core` and the request records live in `:frontend:web-ui`, which
**does not depend on `:backend:core` by design** - `frontend/web-ui/build.gradle` says so in a
comment, and rule 1 of `doc/sub_boundaries.txt` is why. So no test can name both sides of that
seam. The arm and the test that guards it are written by the same act of remembering, and they fail
in exactly the same cases.

The trade is stated rather than buried: **a command that needed a decision and did not get a handler
is now sent raw instead of dropped.** That has never happened; the other failure has happened three
times.

### Refusal is a decision and looks different in the log

Four commands are declined rather than sent, each because this route cannot carry them correctly:

- **`M28`/`M29`** (`StartSDWrite`, `StopSDWrite`) - `M28` makes the wire a file sink until `M29`, so
  anything queued behind it is written into the file instead of run. An upload goes through
  `PrinterControl.uploadToCard`, which takes the connection exclusively.
- **`M110`** (`SetGetLineNumber`) - the line counter belongs to the send window. One sent as a
  command moves the firmware's and not the host's, and every block after it is rejected for the
  rest of the session.
- **`M112`** (`FullShutdown`) - an emergency stop queued behind a print is not one. It needs
  `Priority.EMERGENCY`.

`refuse(command, why)` is a separate method from the old warn-and-drop on purpose. The two read
identically in a log and mean opposite things: *"nobody has got round to this yet"*, which was a bug
every time it fired, against *"this is the wrong way to ask"*, which names the right route.

`ErrorRs` is the one inbound exception: it is announced as a `ConnectionStateChanged` and **not**
also republished as itself, because one fault on the stream twice in two shapes is for a browser to
reconcile and it should not have to.

### Route on the family interface

`case SdCardRs<?> e -> on(e)` covers `SdCardOk`, `SdFileList`, `SdFileOpened`, `SdFileSelected`,
`SdWritingToFile`, `SdDoneSaving`, `SdDonePrinting`, `SdFreshFile` and the three `SdStatusRs` - a
reply added to that family in `:gcode` arrives here already routed. That is the same rule
`reactive-transport-dev` states for `OkRs` and `BusyRs`, and it is the difference between one arm
and eleven that each have to be remembered.

### Republishing is not keeping

The position arm and the card arm emit and store nothing. There is no rule the aggregate enforces
with either and no handler that reads them, and a stored field with no reader is the write-only
`PrinterEntity.connected` defect in a new place. **Add the handler; add the field when something
reads it.** The card is the live candidate: `SdPrinting`/`SdNotPrinting` reach `on(SdCardRs)` now,
so the state `handle(ReleaseSDCard)` needs to refuse an `M22` mid-print is one field away.

### A host-side fact crosses the aggregate too, and a handler can decide nothing on purpose

`BlocksAbandoned` has a named `on(...)` which **republishes and moves no lifecycle state at all** -
not `faulted()`, and, less obviously, not `heard()` either. It is a fact about the *host* rather
than something the machine said, like `BlockAcknowledged`, and what acts on it is `JobFeeder`, which
fails the job.

Both halves of that are paid for:

- **No fault.** A resynchronisation never arrives alone. The firmware answers a block with
  `Error:Line Number is not Last Line Number+1`, then `Resend:`, and the `Error:` has already
  reached `on(ErrorRs)` and moved the connection. Faulting again here is one fault on the stream
  twice, which `on(ErrorRs)` already refuses from the other side.
- **No `heard()`.** A reply after a fault is what *clears* a recoverable `ERROR`, and this is not a
  reply. Before the handler existed the `default` arm called `heard()` and took the machine from
  `ERROR` straight back to `IDLE` on the strength of the host's own conclusion that it had lost
  blocks - a fault cleared by the news of its own damage. `PrinterStateDispatchTest` pins it by
  driving the real sequence: `ErrorRs` first, then the abandonment, and the machine is still in
  `ERROR` with the firmware's own words on it afterwards.

The shape generalises: **a handler whose whole content is "the aggregate has nothing to decide
here" still has to exist**, because the `default` arm is not neutral - it decides something, and
for a host-side fact what it decides is wrong.

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

### A safe sink refuses a concurrent emission; it does not serialise one

**`PrinterReactor.onEvent` is reached from several threads and every event it is given reaches a
subscriber of `updates()`. `FarmEvents` makes the same promise to `/api-farm/stream`.** Those are
contracts callers depend on - the per-machine SSE stream, the dashboard's one stream, and
`printer-management`'s persisted connection state all hang off them - and both are held by an
**outbox plus a drain loop**, the same shape `HostTerminal`, `GSendWindow`'s send window and
`PublisherQueue` use. A thread that finds another one already emitting parks its message on a
`ConcurrentLinkedQueue` and leaves; the thread inside goes round again and picks it up. Nothing
blocks, nothing spins, and `FAIL_NON_SERIALIZED` becomes unreachable.

**`Outbox<T>` in `:backend:core` is the one implementation of that loop in this layer**, and
anything else here that feeds a `Sinks.Many` from more than one thread uses it rather than writing
a fourth copy: `new Outbox<>(this::publish)`, then `emit` instead of `tryEmitNext`. It is
package-private, it holds the queue and the `AtomicInteger`, and its contract is that **the
consumer is called by exactly one thread at a time** - which is what lets `PrinterReactor` keep
`streamEnded` as a plain field - and that **the consumer does not run on the thread that offered
the item**. `HostTerminal.drainBus` stays its own copy: `:backend:terminal` may not depend on
`:backend:core`, the only home both could see is the transport itself, and moving twenty lines
there is a module-boundary decision for `reactive-transport-dev` rather than a side effect of
fixing a farm stream.

The rules that follow, each paid for:

- **`Sinks.many()` is *safe*, which means it refuses rather than serialises.** Two threads emitting
  at once get `FAIL_NON_SERIALIZED` on one of them. The emitters here are real and named: the
  aggregate's executor, the backoff scheduler on a failed attempt, and netty's on a dropped link.
  The transport measured **26,640 of 40,000 emissions refused** with two threads on one safe sink -
  the collision is a question of load, not of correctness, so it reproduces on a loaded CI runner
  and never on a developer's machine.
- **`EmitFailureHandler.busyLooping(...)` is the wrong remedy twice over, and a `static final` one
  is the wrong remedy three times.** It spins on *whichever thread emitted* - as readily a netty
  event loop - which is this layer's cardinal rule broken outright; and when the spin runs out it
  **throws `Sinks.EmissionException`**, which skips every statement after it. Worse, Reactor's own
  javadoc says *"don't cache the returning handler for later usage"*: the deadline is computed **at
  construction**, so a handler in a static field is expired one second after the class loads and
  every collision from then on throws immediately without retrying at all.
- **Put the durable work before the volatile work.** `onEvent` tells the roster, then reports the
  firmware/capability/connection fact, and only then puts the message on the sink. A failure to
  reach a browser must never cost the farm the record of what the machine said - and under the old
  order it did: a collision threw before `roster.publishEvent`, so the firmware never reached the
  database. The loss is **permanent, not late**, because `PrinterState.on(FirmwareInfoRs)` sets its
  profile *before* emitting, so the identical `M115` of any later redial is suppressed.
- **Ordering is the second reason, and it is easy to miss.** The drain loop can hand a *later* event
  to a subscriber before the thread carrying the earlier one has got as far as the roster. With the
  roster first, the roster is told in the order the aggregate decided - which matters because
  `printer_entity.connection_state` is persisted from exactly those messages.
- **A terminal signal goes through the outbox too.** `stop()` parks a `StreamEnd` rather than
  calling `tryEmitComplete` from the caller's thread, so it cannot terminate the sink underneath an
  event another thread is still publishing. `FarmEvents` parks no such marker and needs none: the
  farm stream belongs to the farm rather than to a machine and nothing ever ends it, so a subscriber
  leaves by cancelling.
- **A drop for backpressure and a drop for a collision must not read alike in the log.** The farm
  sink is `directBestEffort` and is *meant* to drop - one slow browser, one event, for that
  subscriber only - so `the farm stream dropped X (FAIL_NON_SERIALIZED)` read exactly like the
  design working, while it was in fact costing *every* dashboard the same event. Once the drain
  loop is in, `FAIL_NON_SERIALIZED` is unreachable and `FAIL_ZERO_SUBSCRIBER` is the ordinary case
  on a farm nobody is watching; anything else says something real and the wording has to say so.
- **A late subscriber gets a snapshot, never a replay.** `PrinterReactor.updates` is a multicast
  `directBestEffort` sink that keeps nothing; `updates()` opens with `PrinterState.snapshot()` -
  connection state first, then firmware, capabilities, the card and the last temperature - built from what
  the aggregate *holds*. It answers *"where is this machine"*; a replay answers *"what did it last
  say"*, which hands a position or a `BlocksAbandoned` from an earlier job to a new watcher as if it
  had just happened (#85), and a backlog is every temperature since boot handed to the first page
  that opens. A new fact for the snapshot belongs in a `*Control` beside `TemperatureControl`,
  `ProfileControl` and `SdCardControl`, with an `on(report)` and a `snapshot()`, never in a field
  nothing hands out. Whether a repeat is re-announced is the reply's business: a temperature that
  did not move is suppressed, a card reply never is - someone asked for it and is waiting.
  `SdCardControl` forgets everything on a new session and on `M22`. **The live half is subscribed before the snapshot is read** (`CatchUp`): the other
  order loses whatever is emitted between the two, and the cost of this one is at most a repeated
  value. `HostTerminal` keeps a 256-deep replay for the opposite reason: its caller cannot subscribe
  until `start()` has already begun reading the socket.
- **Prove it by forcing the collision, not by hoping for one.** Reactor's serialisation guard is
  re-entrant for the thread that holds it, so a refusal needs a *different* thread inside the sink
  at that moment. `PrinterReactorEmissionTest` and `FarmEventsEmissionTest` park the first emitter
  inside `tryEmitNext` with a latch in the subscriber and emit from a second thread while it is
  held. That makes the race a certainty rather than a 2-core-CI-runner coincidence - and the
  farm-stream instance survived two rounds of fixing this elsewhere precisely because a test that
  emits from two threads and hopes passes against the bug on nearly every run.

### One aggregate, one thread - and a listener is never on it

Issue #54. Two properties, two mechanisms, and they are separate on purpose.

- **A scheduler is allocated per `PrinterReactor`, not shared across the farm.** `PrinterThreads`
  is the factory and `CoreConfiguration` declares it as a bean, so an assembly can replace the
  threading model without any class in `core` gaining a property to read.
  `virtualPerPrinter()` hands out **two single-threaded virtual-thread executors per machine** -
  `p3d-printer-<uuid>` for the aggregate, `p3d-listener-<uuid>` for its listeners. Java 25 is the
  toolchain and a serialised virtual thread costs almost nothing idle, which is what makes this
  hold at forty machines rather than one.
- **The thread is named for the printer, with the whole UUID.** A farm-wide stall is diagnosed from
  a thread dump; `single-1` says nothing, and a short prefix says something ambiguous, which is
  worse because it reads as certain. The name can be pasted into the roster.
- **`PrinterThreads` is a factory, not a cache.** Each call returns a new scheduler; the reactor
  that asked owns it and `stop()` disposes it. `release(id)` followed by `openReactor(id)` must get
  live threads, which a cache would not give it.
- **A listener never blocks the aggregate's thread.** `PrinterReactor.onEvent` offers to a
  `Handoff` instead of calling `ApplicationEventPublisher.publishEvent` inline. Before #54 the
  roster's `@Transactional` JPA writes ran on the aggregate's thread, which was the farm's.
- **Ordering is per aggregate and deliberately not global.** It comes from a **single consumer per
  printer**, not from `@Async` - a `ThreadPoolTaskExecutor` with more than one thread reorders
  freely. Events from different printers have no relative order worth preserving, and enforcing one
  rebuilds the shared thread this change removed. Say that in the code when it comes up.
- **The two hand-offs have opposite overflow policies, and that is the decision rather than an
  accident.** *A browser missing an event is a refresh; the roster missing one is a fact about the
  machine that is gone for good.* So the **farm SSE stream** (`EventMessage`, ~1-2/s/machine) is
  `Handoff.droppable` at 256 - bounded, drops, and counts the drop so it is visible - and the
  **roster facts** (`MFirmwareReportedEvent`, `MCapabilityReportedEvent`, `MConnectionChangedEvent`)
  are `Handoff.lossless`. 256 is `publishOn`'s prefetch, which is the bound this replaces; what
  changed is the consequence, because overflowing `publishOn` raised an `onError` that **terminated
  that session's reply stream**.
- **Both hand-offs are on the same listener thread**, so a stuck roster write also fills the farm
  queue. The WARN that logs a farm-stream drop therefore does not only mean "a slow browser"; it
  can equally mean a JPA write is stuck.
- **A roster event is built on the aggregate's thread, including its `LocalDateTime.now()`.** The
  timestamp says when the machine said it, not when the database got round to it - and the gap is
  longest exactly when somebody would be reading those timestamps to find out what happened.
- **A scheduler is disposed by scheduling the disposal as a task on it**, never by calling
  `dispose()` directly. `DelegateServiceScheduler.dispose()` is `shutdownNow()`, which discards the
  queue - and `tryEmitComplete()` has just handed `onComplete` to each subscriber's `publishOn`
  worker *as a task on that scheduler*, so a direct dispose leaves an SSE browser on a deleted
  printer waiting forever. `Schedulers.immediate()`, `single()`, `parallel()` and every cached
  scheduler have a **no-op `dispose()`** (verified in reactor-core 3.8.7), so a test that passes one
  in is unaffected.
- **And it is disposed from `PrinterReactor.publish`, not from `stop()`** - queueing behind the
  completion only helps if the completion is already queued. `stop()` *parks* a `StreamEnd`, and
  `Outbox.emit` drains only when no other thread is already draining; a machine being deleted
  mid-conversation has its aggregate thread inside that loop, so `stop()` returns with the marker
  still queued and nothing completed. Disposing from there schedules `shutdownNow()` onto an
  executor whose queue is *empty* - the `onComplete` does not exist yet - so it runs at once, and
  the completion the other thread produces a moment later is submitted to a scheduler that has
  gone. **The same browser waits forever, one thread later.** So whichever thread drains the marker
  calls `tryEmitComplete()` and then, on that thread and strictly after it, schedules both
  disposals. `PrinterReactorManagerTest.aReleasedReactorCompletesItsUpdateStream` cannot see this -
  nothing else is emitting there, so the marker is published inline and the order is accidentally
  right; `PrinterReactorThreadingTest.aStopThatRacesAnEmissionStillCompletesTheUpdateStream` holds
  a subscriber inside the sink and releases the printer from another thread, which makes it
  certain. **A guard that admits one caller is what makes "dispose once" true** - `stop()`'s
  `stopped` flag means exactly one `StreamEnd` is ever parked, so the `streamEnded` early exit in
  front of the disposal is unreachable for it.

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

**A roster announcement is acted on once, when this module has no runtime for the machine, and a
machine that is already running is left entirely alone.** That is a contract, not an optimisation.
`MRegisteredEvent` is a statement of current fact re-published about seven times per connect - once
per firmware report, once per capability line, each from a `@Transactional` listener in
`MachineManagementImpl` that reads the row back and announces it again - so `onRegistered` sees "it
was linked" long after it stopped being true. The javadoc used to argue the repetition was harmless
because `connect()` "declines to start a second attempt while one is in flight"; that clause was
load-bearing and false at the one moment it mattered, because `disconnect()` disposes the attempt
and nulls it, so a republication queued behind a disconnect found nothing in flight and redialled.
Thirty "dialling it again" lines and eleven `-> CONNECTING` transitions in one test window, and
`ConnectivityIntegrationTest.disconnectingClosesTheSocket` timing out after thirty seconds on a
machine that never settled.

**The gate is whether a runtime exists; the announcement's own `getState()` cannot be it.** That
state is the *persisted* one and it lags the live aggregate by however much of the printer's
listener queue is still in front of the `MConnectionChangedEvent` that would correct it - the
republication triggers and the connection fact travel the same single-consumer queue, and the
triggers are ahead. The copies that still say `IDLE` for a machine told to disconnect a millisecond
ago are exactly the ones that do the damage. Whether the module holds a runtime is not stale: at
startup there is none, and afterwards there is one that has already been told what to do by
something closer to the truth.

**The startup redial crosses the aggregate, like every other operating command.**
`openReactor(id).handleConnectCmd(new ConnectCmd(true))`, not `openReactor(id).connect()`. The bare
`connect()` was the write side going round `PrinterState.handle(ConnectCmd)`, and it cost two
things: the intent was never set, so a stale announcement could redial a machine whose aggregate
said OFFLINE; and `connect()`'s own `Retry.backoff(...).filter(cause ->
printer.wantsToBeConnected())` was consulting an intent nobody had established, so **a machine
redialled at startup gave up after its first refusal** and nothing said so. Nothing covered that,
which is why it was never seen.

**`PrinterReactor.connect()` refuses to dial a machine whose aggregate has not asked for a
connection**, as its first statement and before the in-flight check, and it logs the refusal. That
is what makes its "safe to call in any state" claim true rather than nearly true, and it is the
backstop for any future caller that skips the aggregate the way the startup redial did. It is not
silent because thirty seconds of a test timing out is what the silence cost.

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
- **An empty `Mono` returned from a handler is 200 with a zero-length body, not 404.** A GET-by-id
  answers for an id the farm does not hold by mapping the `Optional` its service returns onto a
  `ResponseEntity`, which is `Outcomes.found(Optional)` and nothing else. The browser reaches these
  endpoints through `getJson`, which calls `response.json()` on whatever arrives and throws a
  syntax error on nothing at all, so the 200-and-empty version surfaces in the UI as a parse
  failure instead of as a status a caller can act on.
  **It is not signalled by an exception** - no `ResponseStatusException` and no `@ResponseStatus`;
  nothing on the REST surface throws for this, and a second mechanism for the same answer is what
  this skill exists to prevent. **The blocking lookup still goes through `offLoop`** - the status
  decision is a `map` downstream of the subscription, so choosing it moves no JPA call back onto an
  event loop.
- **`Optional`-returning `fetch` is the shape of every GET-by-id now, and there is no outlier
  left.** The roster, the file store and the job queue all answer one id with an `Optional`, and
  all four handlers map it the same way: `FDMPrinterManagementController.fetch`,
  `JobController.fetch`, `GcodeFileController.fetch` and `GcodeFileController.download` (which uses
  `defaultIfEmpty` because its body is a stream rather than a record). `/api-manager/{uuid}` was
  the outlier until issue #65 and answered **500** on an unknown id, because
  `MachineManagementService.fetch` returned a `Descriptor` and ended in `orElseThrow` - the
  signature, not the handler, was the bug. `MachineManagementService.update` still `orElseThrow`s
  and is deliberately untouched; it is adjacent work, not this rule.
- **The module's status mapping is stated once, in `Outcomes`, and nowhere else.**
  `ABSENT`/absent -> **404**, `WRONG_STATE` -> **409** with a legible body, accepted -> 200. 409
  and not 400 because the request was well formed and the caller was entitled to make it - the job,
  the file or the machine simply is not where they thought. A refused *command* carries the
  `Refused` record as its body, because it has a sentence worth putting in front of a person; an
  absent *query* carries nothing, because its status says the whole of it. Both `of` methods are
  exhaustive switches over a sealed type with no `default`, so a third variant fails the build.
- **A service states a refusal as a value and the REST layer maps the value.** `JobOutcome` for the
  queue, `ControlOutcome` for the control surface - sealed, with a `reason` a page can show.
  **An `@ExceptionHandler`, a `@RestControllerAdvice` keyed on exception class, a `@ResponseStatus`
  or a `ResponseStatusException` on the REST surface is forbidden here**: an exception-to-status
  table drifts away from the code it describes, and it makes the web layer guess which of "not
  found", "that is not allowed" and "the database said no" an `IllegalStateException` meant. Before
  #65, `POST /api-printer/{id}/sd/upload` answered 500 to a file the store did not hold and to a
  printer that was not connected, both ordinary outcomes of clicking a button.
- **`MvcPrinterController.edit` is the one place a `ResponseStatusException` appears, and it is not
  the same mechanism.** It is an HTML page stating a 404 at the point it knows the fact - the
  roster answered empty - rather than a handler classifying something thrown at it from below.
  Nothing there catches an exception; it creates one because a Thymeleaf handler's return type is a
  view name and has no room for a status. The REST surface maps the identical empty `Optional`
  through `Outcomes.found`.
- **SSE is what the UI actually uses**: `produces = TEXT_EVENT_STREAM_VALUE` on
  `/api-printer/{id}/stream`, consumed by `EventSource` in `static/js/lib/client.js`. It is the
  working live channel and the one to extend. **It opens with the aggregate's snapshot** - the
  connection state, the profile and the last temperature - so a late subscriber learns where a
  machine is without waiting for something to happen to it; `GET /api-printer/{id}/connection` answers the same question without a stream.
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

## Shutdown - the farm lets go before the web layer waits for it

Issue #87. An SSE subscription is a request with no natural end, and Spring Boot 4 turns on
graceful shutdown by default: `WebServerGracefulShutdownLifecycle` waits for every in-flight
request before it gives up, and an open `EventSource` never finishes on its own. Before this, the
farm's two long-lived streams made that wait unconditional - thirty seconds, every time, whether or
not a browser was actually watching - because nothing released a printer's runtime when the
context closed (`PrinterReactorManager.release` was reached only from `MDeletedEvent`) and
`FarmEvents`'s stream had no end-of-stream marker at all.

- **`FarmShutdown`, a `SmartLifecycle` bean, is what lets the farm go.** Its `stop()` does two
  things: `PrinterReactorManager.releaseAll()` stops every running printer (which disconnects it,
  parks completion on that machine's `PrinterReactor.updates()` sink and disposes its two
  schedulers) and closes the manager's gate against opening another - and then **waits, with one
  shared bounded budget for the whole farm, for confirmation that every one of those streams has
  actually finished at its subscribers**, through `PrinterReactor.awaitSubscribersGone(Duration)`,
  before returning; `FarmEvents.close()` ends the farm-wide stream, which needs no such wait because
  it owns no scheduler and completes synchronously. Both are idempotent and neither builds anything.
  Blocking inside `releaseAll()` is correct here and not the rule this module otherwise enforces -
  see *Letting go is not the same as having ended* below for why.
- **`getPhase()` must answer `SmartLifecycle.DEFAULT_PHASE` (`Integer.MAX_VALUE`), and staying above
  it is the entire point of the bean.** `DefaultLifecycleProcessor.stopBeans()` stops the
  highest-numbered phase first, and Boot's own `WebServerGracefulShutdownLifecycle` runs at
  `Integer.MAX_VALUE - 1024` - the exact number in its own log line
  (`Shutdown phase 2147482623 ends with 1 bean still running...`). A bean at the default phase
  therefore runs *before* the web layer starts draining in-flight requests, so by the time that
  drain begins there is nothing left to wait for. Lower this phase and the thirty-second hang
  returns silently - nothing except a real graceful-shutdown integration test will ever notice,
  because every unit test and every `@SpringBootTest` that does not boot a real web server and
  send it a real stop is blind to phase ordering altogether.
- **`@PreDestroy` and `DisposableBean` are the wrong hook for anything that must happen before the
  web layer drains, and the reason is ordering, not taste.** Bean destruction runs after every
  `Lifecycle.stop()` phase has already finished - in particular, after
  `WebServerGracefulShutdownLifecycle` has already spent its whole timeout waiting. A
  `@PreDestroy` version of this fix would still close every socket correctly and would still leave
  the entire hang in place, because by the time it ran the wait would already be over.
- **The standing rule this establishes: any publisher this module exposes to an HTTP response must
  have an end-of-stream.** An SSE subscription with no terminal signal is an in-flight request that
  never finishes, and graceful shutdown will wait the whole of its timeout for exactly that. A new
  long-lived stream built the way `FarmEvents.updates()` and `PrinterReactor.updates()` are built
  needs its own `close()`/`stop()` wired into `FarmShutdown` (or a bean like it) the day it is
  added, not discovered the next time someone measures a shutdown.
- **A terminal signal for a stream fed from several threads goes through its outbox, not straight
  at the sink** - `FarmEvents.close()` mirrors `PrinterReactor.stop()` exactly: a sealed `Parked`
  with an `Update` and an `End` arm, `tryEmitComplete()` moved into the `publish` switch, and a
  plain `closed`/`streamEnded` field guarded by the same single-draining-thread guarantee
  `Outbox` gives every other consumer in this module. Calling `tryEmitComplete()` from the
  caller's thread while another thread is mid-drain would terminate the sink underneath an event
  still being published, which is refused with `FAIL_TERMINATED`.

**Letting go of a stream is not the same as the stream having ended.** A `SmartLifecycle.stop()`
that releases a resource feeding an HTTP response must wait, bounded, for that response to have
finished, because the phase that follows it tears the web server down. Parking a terminal signal on
an outbox is not an ending - the thread that drains it may be behind a backlog.

That is why `PrinterReactor` carries an explicit, testable witness for "every subscriber of
`updates()` has actually gone" - `liveSubscribers`, an `AtomicInteger` touched from
`doOnSubscribe`/`doFinally` on whichever thread delivers that subscriber's terminal signal
(completion, error, **or cancellation**, so a browser that simply disappears counts as gone rather
than as a subscriber waited on forever), and `awaitSubscribersGone(Duration)`, a bounded,
non-throwing wait on a `CompletableFuture<Void>` completed from two legitimate racing edges: `stop()`
itself, for a machine nobody was watching, and the last subscriber's own `doFinally`, for one that
was. `PrinterReactorManager.releaseAll()` stops every reactor first and only then awaits them, all
against **one shared budget for the whole farm** (five seconds, well under
`spring.lifecycle.timeout-per-shutdown-phase`'s thirty) - two passes rather than one, because
stopping and awaiting one machine at a time could spend up to forty times that budget on a farm of
forty. The manager's monitor is held only long enough to flip `shuttingDown` and take the map, never
across the wait itself, which is the one place in this class where "do not hold the lock while
blocking" is load-bearing rather than tidiness: holding it would block every concurrent
`openReactorIfKnown`/`runtimeOf` caller for the length of the budget, which is a worse bug than the
one being fixed. `PrinterReactorAwaitSubscribersGoneTest` and
`PrinterReactorManagerTest`'s `releaseAll*` tests pin all of this with real schedulers - a slow
consumer draining a genuine backlog one item at a time makes the hold deterministic, which is what a
test for this class of bug needs: a test that merely releases and hopes passes against a design that
does not wait, on nearly every run, which is exactly how the original fire-and-forget version of
`releaseAll()` passed every test that existed for it.

**And that witness is still not enough, measured against the real integration test
(`GracefulShutdownIntegrationTest`, in `:app`) rather than assumed from reading the code - this is
the live finding to read carefully before trusting the paragraph above.** With
`awaitSubscribersGone` wired in exactly as described, a subscriber consuming its SSE stream slowly
(one element at a time, paced) was cut off with `PrematureCloseException: Connection prematurely
closed DURING response` on **5 of 5** runs, and the original, unpaced case - thought fixed - still
failed on about 1 of those 5, the same order of magnitude as before this witness existed. Read
against the log, `FarmShutdown.stop()` returns within single-digit milliseconds of being called:
`awaitSubscribersGone` genuinely does see `PrinterReactor`'s own Flux complete that fast, because the
whole backlog (a handful of snapshot events) fits in the TCP send buffer and the server writes it
regardless of how slowly the client is actually reading - so the *pacing* the test uses to build a
backlog does not, in fact, hold the server-side Flux open for any measurable time. **The gap this
witness cannot see is one hop further down than `PrinterReactor.updates()`'s own `doFinally`**: that
`onComplete` is delivered on this machine's own listener/aggregate thread, not on the HTTP
connection's Netty event-loop thread, and completing a reactor-netty response from a foreign thread
schedules the actual channel write/flush onto that event loop asynchronously
(`channel.eventLoop().execute(...)`) rather than performing it inline. `awaitSubscribersGone`
returning `true` says this module's own publisher has finished; it says nothing about whether that
scheduled write has actually reached the socket before Boot's `disposeNow()` runs. Closing that gap
needs a witness at the HTTP response itself - something that observes `ServerHttpResponse`'s own
completion on the connection's event loop, which only `:frontend:web-ui` or `:app` can provide, not
`:backend:core`. The one thing this does **not** reach is the final flush of an HTTP response to a
client that is not reading it; see the *Known debt* entry below for where that boundary is, how it
was measured, and why it is deliberately left there.

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

### Sessions live in the database, and Boot will not wire that for you

A signed-in session is a row, not a field: `:backend:identity` keeps them with Spring Session's
JDBC repository so a restart does not sign the whole farm out. Four things about that setup are
load-bearing.

- **Boot's reactive session auto-configuration covers Redis and MongoDB only.** Given a
  `ReactiveSessionRepository` of any other kind it configures nothing and **logs nothing**, and
  WebFlux carries on with `InMemoryWebSessionStore`. Everything looks right - the tables exist, the
  repository is a bean, the tests pass - and a restart still signs everybody out. That is how it
  was found: the implementation was finished and the live check still answered 401.
  `SessionStoreConfiguration` in `frontend` declares the `WebSessionManager` explicitly, and it has
  to stay there because `WebSessionManager` is a WebFlux type.
- **There is no reactive JDBC repository in Spring Session**, so `ReactiveJdbcSessions` wraps the
  blocking one with `subscribeOn(boundedElastic())`. This is the cardinal rule at its most
  load-bearing: a session is read on **every single request**, so a JDBC call left on the event
  loop is the whole farm serialised behind one pool. Do not reimplement the repository - session
  identifiers, expiry and cascade deletion are security-sensitive and Spring Session's version has
  had far more eyes on it.
- **Wiring it by hand means owning the housekeeping.** Spring Session's own configuration class
  schedules the expiry sweep; `ExpiredSessionSweeper` is that obligation, and without it the table
  grows by a row per sign-in forever. Everything created has an owner that releases it.
- **The transaction template is `REQUIRES_NEW`,** as Spring Session's own is. A session is saved at
  the end of a request that may already be in a transaction, and joining it would tie staying
  signed in to whether that unrelated work commits.

Two smaller traps: Spring Session's `JdbcSession` is **package-private**, so a generic helper
method is how the compiler infers a type that cannot be written down; and a **negative**
`maxInactiveInterval` means *never expires*, not *already expired*, so ageing a session out in a
test needs a short interval and a last-accessed time in the past.

The schema is a Liquibase fragment with **logical** types rather than Spring Session's shipped DDL:
`ATTRIBUTE_BYTES` is `blob`, which resolves to `LONGVARBINARY` on H2 and `BYTEA` on PostgreSQL. The
table and column *names* are Spring Session's and its SQL is written against them literally, so a
rename there is a runtime failure rather than a compile one.

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
- **A request body is a `frontend/command` record, never a `:gcode` command class.** The REST
  contract used to follow the Marlin command set, so a rename by the generator was a breaking API
  change - and one had already happened silently, with `/auto-report-temp` binding a `seconds` field
  while the browser sent `period`. The payload records live in the web module and translate
  themselves; everything behind `PrinterControl` is `GRq`.
- **The browser's contracts are pinned by test, because the build does not compile the browser.**
  `PrinterCommandBindingTest` for what it posts and `EventStreamContractTest` for what it reads. A
  renamed field or event name on this side fails there rather than in a browser.

## The command model, end to end

A control on a page becomes bytes on a serial link through three places, and **a change that misses
one of them fails silently**. In order:

| Step | Where | What it is |
|---|---|---|
| 1 | `frontend/command` | a record in the browser's shape, with a `toMarlin()` of its own |
| 2 | `frontend` | a `@PostMapping` under `/api-printer/{id}` binding that record |
| 3 | `core` | `PrinterState.handle(<Command>)`, reached from an arm in `onCommand` |

It was four places, with an `api::msg` command model and a `toMarlin` switch in `core` between
them. That step is gone: `PrinterControl.send` takes `List<GRq<?>>`, so a command crosses the
boundary as itself.

**Step 3 has no compiler protection**, because `GRq` cannot be sealed (295 implementations). It no
longer *drops* what it does not recognise - see *every operating message crosses the aggregate* -
but a command without a named `handle` still skips whatever decision that handler would have made,
so write the handler.

The two halves are tested either side of the boundary, and **neither module can test the join**:
`PrinterCommandTranslationTest` in `:frontend:web-ui` for what a payload becomes,
`PrinterStateMotionTest` and `PrinterStateStorageTest` in `:backend:core` for the bytes the
aggregate writes. Writing only the first is how the motion commands and then the whole Storage tab
shipped dead.

### One intent is sometimes several commands

`toMarlin()` returns a **`List<GRq<?>>`**, because Marlin has no per-command relative flag: a
relative move is `G91`, the move, then `G90`. Two consequences:

- **`G91` is modal.** Forgetting the trailing `G90` leaves the machine in relative mode, so every
  later absolute move - including a print's - lands somewhere else entirely. The mode switch is
  returned *with* the move for exactly this reason; it is not the caller's to remember.
- **`PrinterReactor.handle` applies the whole list as one task** on the aggregate's thread. Emitting
  the three as separate elements would make them three tasks, so another request's command could be
  scheduled between them and be interpreted in relative mode. One `doOnNext` over the list is what
  makes the batch atomic with respect to everything else that printer is asked to do.

### A reply reaching a browser

**The inbound path has the same shape as the outbound one.** To make a firmware reply reach a
browser:

1. `:gcode` decodes it - already true for everything in `marlin/event/`;
2. `:backend:terminal` propagates the decoded `GRs` as it is - no mapping step any more;
3. `PrinterState.on(<Event>)` - add the named handler, per the rule above.

**Step 3 no longer eats a reply that has no handler**, and that is a recent change paid for twice
over. A position report was decoded correctly all the way through the transport - the log showed
`IN: onNext(PositionRs(axes={X=15.00, ...}))` - and never appeared in a browser. The same thing
then happened to `SdCardOk` and `SdFileList` while the Storage tab was being built, *after* the
command half of the same bug had been fixed. Every layer looked right each time, which is why each
took a live run to find.

**The noise objection to republishing by default was measured, not assumed.** Over a live session
the only replies that reached the inbound `default` at all were `SdCardOk` and `SdFileList`:
`AdvancedOkRs` never gets there (the send window consumes it and republishes `BlockAcknowledged`),
and busy, temperature, firmware and capability all have handlers. Echo and comment lines are what
newly reach the stream, both low-volume, and the client ignores a type it does not know.

**A new reply type reaches the browser under its own class name.** `EventMessage.type` is the
event's simple class name, so a new reply is visible to the UI as a new string - and a reply with
two concrete classes (`BareTemperatureRs` and `OkTemperatureRs` are one reading) is two strings the
TypeScript side has to match as a family. `EventStreamContractTest` pins them. **A family interface
like `SdCardRs` is one handler and many strings**, so the TypeScript side is where that family is
re-stated.

### What the farm can tell a machine to do

Homing, moving, the fan, `M500`, a position request and the four temperature commands - the
payload records in `frontend/command`, one endpoint each. Three notes that are easy to get wrong
and were verified on the wire:

- **A fan index travels in `P`, not `I`.** Marlin's documentation lists both as "index" and the
  generated command class carries both fields; `P` is what mainline firmware reads as the fan
  number, and it is what `M107` uses, so the pair stays consistent.
- **Fan speed is Marlin's 0..255 PWM scale, not a percentage.** The UI converts, so only one unit
  ever travels.
- **A null axis is not zero.** Null is an axis the move does not mention; zero in relative mode
  means "do not move this axis" and in absolute mode means "go to the origin". A binding that
  defaulted null to zero would turn a single-axis jog into a move of everything.

**There is deliberately no endpoint that accepts G-code text**, and there must not be. It would
hand any operator arbitrary control of a heater past every check the endpoints exist to make. Note
that this is a rule about the *HTTP surface* - inside the application G-code is exactly what
travels, and that is rule 7 of `doc/sub_boundaries.txt`.

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
fight the assembly that imports it: Spring Boot reads one `application.properties` from the
classpath root, so two jars shipping one is a coin toss. `:backend:printer-management` shipped a
datasource URL duplicating `:app`'s for a long time; both it and `core`'s empty one are gone with
the merge, and `:app` is the only module that configures anything.

**One Gradle module means one test classpath and therefore one test `application.properties`.**
Four of them merged into `backend/core/src/test/resources/application.properties`, pointing at one
in-memory database built from `db/changelog/core-test.xml` - the same set of fragments `:app`
assembles. A test now runs against the schema the application actually has rather than its own
module's slice of it, which is strictly better: a constraint between two of those tables would
have been invisible before.

### Documentation duty

Agents wrote this layer's comments and over-wrote them - 47% of `:backend:core`'s main lines before
issue #86. Four rules:

- **The class block is where a thread/lifecycle contract and a measured fact live** - which thread
  mutates `PrinterReactor.attempts`, why `stop()` disposes a scheduler by scheduling the disposal onto
  it, the 26,640-of-40,000 refusal rate behind `Outbox`. A class block over ~25 lines needs a reason;
  over ~40 it is almost certainly still prose that should be three lines.
- **A `handle(<Command>)`/`on(<Event>)` method named after its message carries no javadoc of its
  own** - the name is the contract; the *why* belongs in the class block once, not under every arm
  that delegates to `passThrough` or `republish`.
- **`@param`/`@return` earn their line only when they add a unit, a nullability or a bound** the
  signature does not already say.
- **A one-line `//` is for one tricky statement, not a paragraph.**

Losing a measured fact while compressing - a byte count, a firmware transcript, a "why not X" - is a
regression, not a cleanup: move it into the class block before deleting the paragraph that carried it.

## Review checklist

- [ ] no blocking call on an event loop - every JPA/JDBC touch from a reactive path has
      `subscribeOn(boundedElastic())` or an equivalent boundary
- [ ] no `block()`, `Thread.sleep`, or synchronous I/O in a controller or a pipeline
- [ ] module boundaries declared with `@ApplicationModule` and verified by a test, for any module
      the change touches
- [ ] **every command the change adds has a `PrinterState.handle(<Command>)`, and every event a
      `PrinterState.on(<Event>)`** - both directions cross the aggregate, and a handler is where
      the domain rule lands
- [ ] **the bytes are asserted, not the routing.** A new command needs a test in `:backend:core`
      saying what the aggregate wrote; a new reply needs one saying it reached a subscriber.
      Translation passing in `:frontend:web-ui` proves nothing about either, and no test can span
      the two modules
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

- **A client that is not reading its socket while the server is torn down may see the connection
  reset rather than finished, and that is where #87 deliberately stopped.** `releaseAll()` now waits,
  bounded, for each `PrinterReactor`'s `updates()` Flux to have delivered its terminal signal before
  it returns - that witness is real and is what makes "the farm has let go" a statement rather than a
  hope. It is still one hop short of the socket: a reactor-netty response completed from a thread
  other than the connection's own event loop - which this machine's aggregate thread always is -
  schedules its final write and flush onto that event loop rather than performing it inline, so the
  last flush can lose a race with `WebServerGracefulShutdownLifecycle`'s teardown. Measured against
  `GracefulShutdownIntegrationTest`: a subscriber that *paces its own reading* fails with
  `PrematureCloseException: Connection prematurely closed DURING response` on 5 of 5 shutdowns, while
  the same paced subscriber against a *deleted printer on a farm that is still running* completes
  cleanly on 5 of 5 - which is the pair that locates the boundary, and the second of those is frozen
  as a test. **A normally-consuming subscriber completes cleanly: measured 15 of 15 shutdowns**, and
  that is the case the integration test asserts strictly.

  **This is not being chased further, and the reason is the client, not the difficulty.** `EventSource`
  has no completion callback - see `subscribe` in `frontend/web-ui/src/frontend/src/api/client.ts` -
  so a browser answers a cleanly finished stream and a reset one identically: `onerror`, then a
  reconnect attempt at a server that is going away either way. There is no user-visible difference at
  shutdown to buy, so buying it with a witness plumbed from `:backend:core` out to the HTTP response's
  own event loop would be cost with no observable benefit. What a browser is actually owed is that the
  farm stops promptly and holds nothing in flight; that is asserted, and it is what #87 was.

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
- **A job is failed when the transport abandons blocks, and the count it is failed on is a lower
  bound.** `BlocksAbandoned` says how many framed blocks the send window was still holding when an
  unanswerable `Resend:` made it renumber the session; `JobFeeder.watchForAbandonedBlocks` turns the
  first one into a `JobFeedFailed` whose reason contains the word *abandoned*, because a job that
  lost blocks cannot be resumed from its checkpoint - a checkpoint records what was *sent*, and what
  went missing is the difference between sent and accepted. What the count does **not** include is a
  block released early by a stray `ok`: that one has already left the window and is gone from the
  tally, which is reachable on the unnumbered acknowledgement dialect
  (`ConnectivityIntegrationTest.aStrayAcknowledgementResynchronisesAnUnnumberedSession` is exactly
  that shape and reaches the resynchronisation holding nothing at all). So the number in the reason
  is the floor of what was lost, never the ceiling, and nothing at this layer can produce the
  ceiling - an `ok` covers acceptance into the planner and what a firmware did with a block after
  that is not something a host is told.
- **A watcher started by a new feed is no longer handed the previous feed's last event** (#85).
  `PrinterReactor.updates()` used to replay its latest message, so `watchForAbandonedBlocks` could
  see an earlier job's `BlocksAbandoned`. It now opens with the snapshot, which carries no event
  about blocks, and `watchTheLink` sees a linked connection state its filter drops. Keep it that
  way: nothing that *happened* - as opposed to something the aggregate *holds* - goes into
  `PrinterState.snapshot()`.
- **A decision taken on `updates()` races a decision taken on the feed's own `Mono`, and on a short
  file the race is even.** Both halves of "did this print finish or break" travel asynchronously and
  on different threads: a `BlocksAbandoned` crosses `gEvent()`'s `publishOn(executor)` and then
  `updates()`'s, both onto that machine's aggregate thread - the shared `Schedulers.single()` when
  this was measured, its own `p3d-printer-<id>` since #54 - while the feed's completion arrives on
  the netty thread through `PublisherQueue`. Measured against `MockPrinter`, which answers instantly:
  the transport announced the abandonment at `T+0`, the aggregate saw it at `T+4ms`, `updates()`
  delivered it at `T+9ms`, and an eighteen-block file ran out at `T+9ms` - so
  `PrintJobIntegrationTest.aJobWhoseBlocksWereAbandonedFailsRatherThanReportingCompleted` passes
  about half the time (4 of 9 runs) against a correct implementation. It is not a problem on a real
  print, where the abandonment lands thousands of blocks before the end. **#54 did not close it**:
  the per-printer thread removed the farm's queueing from the gap and left the race itself exactly
  as measured - it is two paths, not two speeds. The fix is to make the completion path consult the
  fact rather than race it - a count on the aggregate
  beside `blocksInFlight`, read where `JobFeeder.finished` decides - which is one hop rather than
  two, and still not a guarantee. A guarantee needs the transport to answer "did this session
  abandon anything" synchronously.
- **A move is not validated against the machine.** Nothing checks a target against the declared
  bed volume, so an operator can drive the toolhead into a frame. An absolute move could be checked
  against `MachineSpec.bedVolume`; a relative one could not without tracking position, and the
  declared volume is often absent anyway. Left undone rather than half-done.
- **A cancel does not home, park or disable the steppers.** It cools and retracts. Homing drags the
  nozzle across the part; disabling the steppers drops Z on a machine without a brake. Both need a
  machine profile richer than B4's, and getting either wrong breaks a part or a printer.
- **A pause does not park either**, so the nozzle sits over the part and will ooze onto it. Real
  firmware has `M125`/`M601` for this and both need safe coordinates the farm does not know.
- **Progress is approximate while a job runs and exact once it ends, and the column names say
  neither.** `blocks_sent` and the rest are a checkpoint written about once a second, not a ledger,
  so a *running* job is read to within one interval and anything needing an exact count mid-print
  needs a different mechanism and a reason. **A job that has ended is different and is now a
  contract**: see *every way a feed ends writes down an exact reading* below.
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
- **Every way a feed ends writes down an exact reading, through one method** - `JobFeeder.checkpoint`,
  called by the timer, by `stop` and by `finished`. That is a contract callers may rely on: **a job
  in a terminal state records the blocks it actually sent, not a timer reading**, and on a
  `COMPLETED` job that number is the file's block count exactly - the same number
  `JobFeedCompleted.lines()` carries, because both read the counter the source increments.
  It took issue #62 to get there, and the shape of the mistake is the thing to remember: the exact
  reading **existed and was correct** and simply was not on the path a job takes when it ends
  *normally*, because `finished` removed the feed before `stop` could have published one. Pause had
  it; completion did not. Measured live at a 60-second checkpoint interval, a twelve-block file
  finished `COMPLETED` with `blocksSent 0, bytesSent 0/0`, and with `blocksSent 12, bytesSent
  136/136` after. **Nothing read the number, which is why it survived** - a resume reads it and a
  resume only follows a pause - so a field with one reader on one path is a field that is wrong on
  every other path and nobody notices.
- **The exact reading goes *before* the event that moves the job, on every path.** `JobFeedCompleted`
  and `JobFeedFailed` are what `jobs` turns into a terminal state, so a checkpoint published after
  one lands on a row an operator has already been shown as finished. `watchForAbandonedBlocks`
  stated this for `stop`-before-`fail`; it is the same argument and it now holds in `finished` too.
- **`inFlight` on a `COMPLETED` job is routinely non-zero, and that is the field working.** A feed
  ends when the *file* runs out, not when the firmware has caught up, so the last exact reading
  genuinely records a gap - live runs show `blocksSent 12, inFlight 2`. `blocksSent` is blocks this
  host handed to the transport and `blocksAccepted()` is what came back; reading the first as "what
  the machine printed" is the mistake plan item C4 exposed the second to prevent.
- **On the abandoned-blocks path `blocksSent` still means *sent*, and nothing subtracts from it.**
  The window had framed those blocks and written them; it then renumbered the session and dropped
  what it held. `BlocksAbandoned.blocks` is a lower bound on what was lost - a block released early
  by a stray `ok` has already left the window and is gone from the tally - and subtracting a lower
  bound from `blocksSent` would produce a figure that is neither what was sent nor what was
  accepted, which a resume would then seek to. So the number stays honest about sending, the job is
  `FAILED` rather than resumable, and the reason says in words why its checkpoint cannot be resumed
  from. **Nothing at this layer can produce the number of blocks the machine actually ran; do not
  invent one.**
- **A session carries serialised Java objects**, so changing a class that ends up in one - the
  security context, a principal - can make existing sessions undeserialisable on the next deploy.
  The failure mode is everybody signed out at once, which is survivable, but it is a reason not to
  put application types into a session.
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
- ~~**`backend/printer-management` ships a datasource URL from a library module.**~~ **Gone** with
  the merge - `:app` is the only module that configures anything now. The trap it taught is worth
  keeping: a test-scope `application.properties` **replaces** the main one rather than merging, so
  a test that only wants a different datasource must use a profile overlay
  (`application-test.properties` plus `@ActiveProfiles`) or it silently drops every other setting.
- **A package boundary is no longer a compile error.** Six Modulith modules share one Gradle
  module, so only `ApplicationModulesTest` stands between `files` and `jobs`. It is fast and it is
  proven to bite, but it runs in `:app` - a change compiled in isolation will look fine.
- ~~**`FarmEvents` has the same unserialised-sink bug `PrinterReactor` was just fixed for, and it
  fires on a laptop.**~~ **Fixed.** `FarmEvents` feeds its sink through `Outbox` now, and
  `FarmEventsEmissionTest` forces the collision. Two things it taught are kept: the bug was found
  *live* - a two-minute simulator session dropped a real `IDLE -> CONNECTING` with
  `the farm stream dropped ConnectionStateChanged (FAIL_NON_SERIALIZED)` - and **the log line is
  part of the bug**, because that wording is indistinguishable from the backpressure drop
  `directBestEffort` is chosen for. It survived two earlier rounds of fixing this exact class of
  defect elsewhere, which is the argument for grepping for `tryEmitNext` rather than fixing the
  instance in front of you.
- **A listener that throws still skips the roster report behind it.** `PrinterReactor.onEvent` runs
  `roster.publishEvent(message)` then `report(event)`, and Spring's multicaster propagates a
  listener's exception - so a synchronous `@EventListener` on `EventMessage` that throws costs the
  `MFirmwareReportedEvent` after it. The sink is now immune (see *A safe sink refuses a concurrent
  emission*) and these two are not. Fixing it means deciding what a thrown listener should mean,
  which is a change to the dispatch rather than to this method.
- **`PrinterReactor.onConnected` holds a terminal it has not started yet, and a concurrent
  disconnect would trip over it.** `this.terminal = terminal` is assigned, the handshake is sent,
  and `terminal.start(...)` only runs when the zip is subscribed; `HostTerminal.stop()` dereferences
  an `outSide` that `start` creates, so a `disconnect()` landing in that window is a
  `NullPointerException` out of a REST call. The window is microseconds and has always existed;
  deferring the start widened it slightly. The honest fix is in `:backend:terminal` - `stop()` on a
  terminal that never started should be a no-op - which is `reactive-transport-dev`'s to make.
- ~~**One `Schedulers.single()` serialises every aggregate in the farm.**~~ **Fixed** by issue #54 -
  see *One aggregate, one thread* above. The entry is kept because the shape of the mistake is
  worth remembering: `Schedulers.single()` reads like "a single thread for this" and returns the
  **JVM-wide cached one**, so a per-object scheduler was never allocated and nothing said so. The
  symptom was farm-wide and the code looked per-printer.
- **A concurrent `connect()` and `disconnect()` can still both decide to act.** `PrinterReactor.attempts`
  is volatile now, which it had to become once a listener thread could reach `connect()` - the
  roster's `@Transactional` `on(MFirmwareReportedEvent)` republishes `MRegisteredEvent`, which
  `PrinterReactorManager.onRegistered` turns into a dial - so three threads reach it: an HTTP
  thread, the aggregate's own, and the listener's. Volatile buys the happens-before and not
  atomicity: "is an attempt already in flight" remains a check-then-act. Closing it means deciding
  what a concurrent connect and disconnect *mean*, which is a command-semantics question rather
  than a threading one. **The two gates above narrow it without closing it**: the listener thread
  now reaches `connect()` only for a machine with no runtime at all, and the intent check makes a
  dial that races a disconnect lose whenever the disconnect's `wanted = false` is visible first.
  It is still a check-then-act on two fields.
- **A stale `MachineSpec` can overwrite a fresh edit, in `PrinterReactorManager.redeclare`.** It
  takes the same republished `MRegisteredEvent` the redial is now gated against, and hands its spec
  to a running aggregate - so an edit that commits between the republication's `repo.findById` and
  its listeners running is overwritten. The window is one transaction rather than the whole listener
  queue, because the republication reads the row fresh at publication time, which is why this is
  lower stakes than the connection case and is left alone. The same shape, and no observed failure.
- **`PrinterDirectory.Entry.state` has no production reader.** `PrinterReactorManager` reads
  `event.getState()`, `FarmEvents` reads the runtime, and `CoreConfiguration.aggregates()` reads
  only `profile()` and `spec()`; the one reader anywhere is `StartupAnnouncementFlowTest` in `:app`.
  So the stale announcement that overwrites it costs nothing today - but it is a write-only field,
  which is the defect B3 removed from `PrinterEntity.connected`, and the next person to read it
  will read a value that can be behind the aggregate.
- **Three methods still touch the aggregate from the caller's thread.** `handleConnectCmd(ConnectCmd)`
  calls `printer.handle(cmd)` on the HTTP thread - and, since the startup redial became a command,
  on the printer's listener thread as well - and `declared(spec)` and `blocksInFlight()` read
  the aggregate from whatever thread arrives. #54 did not close these - they predate it and fixing
  them changes command semantics (a POST would stop being able to report a refusal synchronously).
  So "one aggregate, one thread" is true of the *reply* path and aspirational on the command path.
- **There is no written spec for this layer.** This skill is the only authority, so a behaviour that
  callers come to depend on gets written down here, in the change that establishes it.
