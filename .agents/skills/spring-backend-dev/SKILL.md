---
name: spring-backend-dev
description: >-
  Write or review the Spring application submodels of this repo - `api`, `core`,
  `printer-management`, `frontend` and `app`. Use for WebFlux controllers and SSE/WebSocket endpoints, Spring
  wiring and configuration, JPA entities and repositories, Liquibase changelogs, Spring Modulith
  module boundaries and their verification, the command/query and event buses, the printer aggregate
  and its lifecycle, and anything that joins a blocking persistence layer to a non-blocking web
  layer. Enforces the rule that nothing blocking runs on an event loop, that a module boundary is
  declared and verified rather than assumed, and that there is one dispatch mechanism rather than
  three. Does NOT cover `:gcode` (that is `low-level-protocol-dev`) or `:backend:terminal` (that is
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
| *library* | `:gcode` | `org.qw3rtrun.p3d.g` | the Kotlin stdlib and `api` (`low-level-protocol-dev`) |
| *library* | `:backend:terminal` | `org.qw3rtrun.p3d.terminal` | `:gcode`, `api`, Reactor (`reactive-transport-dev`) |

The five submodels and their rules are `doc/sub_boundaries.txt`; this table is how they are
realised. **Rule 1 is that every submodel depends on `api` and on no other submodel**, and
`allowedDependencies` on each module declaration is where that is enforced.

`api` must stay free of WebFlux, JPA, Reactor and Spring itself, for a harder reason than taste:
`:gcode` depends on it for the event interfaces its reply types implement, and `:gcode` has to stay
portable to JS, C and Rust. A framework dependency in `api` reaches the protocol core.

**`api` is the only place a type crosses submodels.** A `PrinterEntity` must not leave
`printer-management`, and the aggregate must not leave `core`.

**`core` is live data; `printer-management` is static data.** What a machine is called, where to
dial it and what it can do belong to management. What it is doing right now - connected, hot, busy,
what it last reported - belongs to core. Rule 2 says core talks to other submodels through events,
which is not yet true and is plan item A6.

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

| Module | Package | Type | Depends on |
|---|---|---|---|
| Shared API | `org.qw3rtrun.p3d.api` | **open**, and declared `sharedModules` on `StandaloneApp` | — |
| Printer core | `org.qw3rtrun.p3d.core` | closed, `allowedDependencies = {}` | `api` only |
| Machine management | `org.qw3rtrun.p3d.management` | closed, `allowedDependencies = {}` | `api` only |
| Frontend | `org.qw3rtrun.p3d.frontend` | closed, `allowedDependencies = {"core"}` | `api`, and **`core`, which rule 1 forbids** — see A6 |

**`:gcode` and `:backend:terminal` are excluded from the model**, by a predicate in the test. They
live under the base package but are libraries, not parts of the application: neither may depend on
Spring, so neither can carry a Modulith annotation, and treating them as modules would mean either
annotating them or reporting every use of them as a violation. They are consumed the way Reactor or
Jackson is.

The rules this leaves for new work:

- **A new module is a direct sub-package of `org.qw3rtrun.p3d`** with a `package-info.java` carrying
  `@ApplicationModule`. Everything in a nested package is internal unless exported.
- **`allowedDependencies = {}` is the default to aim for.** Two of the three closed modules hold
  it; `frontend` declares `{"core"}` because its controllers reach `PrinterReactorManager`
  directly. That is declared rather than hidden on purpose - a boundary relaxed the moment it is
  declared teaches nobody anything, whereas one crossing named in a file is a thing someone can
  close. A module that genuinely needs another names it, and that is a decision visible in a diff.
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

## One dispatch mechanism, not three

The application currently routes a message three different ways, and no two of them agree on what a
message is:

- **`CommandQueryBus`** (`:backend:core`) walks a `List<CommandQueryService>` asking each
  `isApplicable(Object)`, takes the first, and casts the result unchecked. `send(req, responseType)`
  **ignores `responseType` entirely** and delegates to the one-argument form, so the class literal at
  every call site is decoration and a wrong one fails as a `ClassCastException` at the caller. One
  service is registered: `UUID → Descriptor`.
- **`MachineEventBus`** (`:backend:printer-management`) wraps `ApplicationEventPublisher` and publishes
  `ManagementEvent`s. This is the one that is conventional, and the one Modulith understands - its
  event publication registry, `@ApplicationModuleListener` and the observability actuator all hang
  off Spring events.
- **`PrinterReactor.invoke`** reflects over `PrinterState`'s declared methods at construction, keys
  them by parameter type, and dispatches by a breadth-first walk of the message's supertype graph.
  It is a hand-rolled visitor that the compiler cannot check: a renamed handler method or a reply
  type that gains an interface silently changes what runs, and the only sign is a "No invoker for"
  at WARN.

**New work does not add a fourth, and prefers Spring events for anything that is genuinely an
event.** When a change touches one of the three, reducing the count is in scope and growing it is
not. A sealed interface with an exhaustive `switch` is the Java 25 replacement for the reflective
visitor, and it fails at compile time instead of at WARN level.

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

`PrinterState` is the aggregate - connection state, firmware info, capabilities, temperatures - and
it currently lives in `:backend:api`, which is the web module. `PrinterAggregate` in `:backend:core`
is a marker interface with one method, and `PrinterAggregateManager` is implemented as
`PrinterState::new`, so **no printer state is persisted or restored**; a restart loses everything the
machine reported.

Two rules for anything that touches it:

- **A reactor is a resource and must be released.** `PrinterReactorManager` keeps a `HashMap<UUID,
  PrinterReactor>` that is only ever added to. A printer deleted through the management API leaves a
  live reactor, a live socket and a live `Sinks.Many` behind it. Anything that creates one owns
  stopping it.
- **`getReactor(id)` has a side effect** - it connects, if the descriptor says the printer was
  connected - so it is not a getter and must not be called from a query path on a whim.

## REST, SSE and WebSocket

- **REST** is `@RestController` returning `Mono`/`Flux`, under `/api-manager` for the farm and
  `/api-printer/{id}` for one machine.
- **SSE is what the UI actually uses**: `produces = TEXT_EVENT_STREAM_VALUE` on
  `/api-printer/{id}/stream`, consumed by `EventSource` in `static/js/lib/client.js`. It is the
  working live channel and the one to extend.
- **The WebSocket handler is dead and broken.** `WSGPrinterHandler` reads
  `session.getAttributes().get("id")`, and nothing anywhere writes that attribute -
  `SimpleUrlHandlerMapping` does not extract path variables - so the id is always null and the
  lookup throws. No client connects to it. Either make it work by parsing the path from
  `session.getHandshakeInfo().getUri()` and registering a `WebSocketHandlerAdapter`, or delete it;
  do not leave it as a third half-channel.
- **A `:gcode` command class in a `@RequestBody` is a deliberate choice**, not an accident: it is why
  `jackson-module-kotlin` is a dependency. Keep it, but know that it makes the REST contract follow
  the Marlin command set, so a generated-class rename is a breaking API change.

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
- **`@DataJpaTest`** for repositories, against the real Liquibase schema rather than a generated one.
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

### Let the event registry do the delivery

Spring Modulith's event publication registry gives at-least-once delivery across a transaction
boundary with persistence and replay, which is exactly what "the printer went offline while the job
was running" needs. Hand-rolled `Sinks` are right for a live UI stream and wrong for a fact that must
not be lost.

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
- [ ] a stream is asserted with `StepVerifier`, not collected into a list
- [ ] `:backend:core` still has no WebFlux, JPA or Reactor import

## Known debt in this layer

Live examples of what this skill rules out, so nobody copies them as precedent. Each is verified in
the source, not suspected.

- **Every JPA call in the application runs on the Netty event loop.** There is no `subscribeOn` and
  no `boundedElastic` anywhere in `backend/` or `app/`. `FDMPrinterManagementController` wraps the
  repository in `Mono.fromSupplier`/`Flux.fromStream`, and `MvcPrinterController` calls
  `manager.fetchAll()` straight from a handler returning `Mono<String>`.
- **The module boundaries are declared but not yet tight.** Four modules are verified, and the
  three closed ones depend on nothing but the shared kernel - but `core` is OPEN, so nothing stops
  a type that should belong to one module from being parked in the shared vocabulary instead.
  `@NamedInterface` is not used anywhere yet; the first closed module that needs a partial export
  introduces it.
- **`WSGPrinterHandler` cannot work.** It reads a session attribute nothing writes, and no client
  connects to `/ws-printer/*` - the UI uses SSE. It is also the only consumer of the
  `webSocketHandlerMapping` bean.
- **`PrinterReactorManager` never releases a reactor.** `computeIfAbsent` into a `HashMap` keyed by
  printer id, no removal, no `stop()`, and `delete(uuid)` on the management side does not reach it.
- **`ApiApplication` registers a `MachineManagementService` that returns `null` from every method**
  under `@ConditionalOnMissingBean`, so `:backend:api` run on its own answers every management query
  with a null body instead of failing.
- **`CommandQueryBus.send(req, responseType)` ignores `responseType`.** The bus holds exactly one
  registration, `UUID → Descriptor`, added from `MachineManagementModule`.
- **`PrinterState` - the aggregate - lives in `:backend:api`**, and `PrinterAggregateManager` is
  `PrinterState::new`, so a restart loses firmware info, capabilities and every temperature the
  machine ever reported.
- **No job, file or queue concept exists.** The product is a print farm; nothing in the backend
  models a G-code file, a print job, or an assignment of one to a machine. `PublisherQueue`'s
  `Priority` enum is the only piece of the machinery that is already there.
- **Kotlin is one file in this layer** (`core/msg/ManagementEvents.kt`), which is enough to pay for
  the Kotlin plugin on `:backend:core` but not enough to settle a language convention. Decide per
  module, and use the `kotlin-tooling-java-to-kotlin` skill when converting.
- **`backend/printer-management/src/main/resources/application.properties` ships a datasource URL** from a
  library module, duplicating `:app`'s and differing from it in the Liquibase changelog path.
- **There is no written spec for this layer.** This skill is the only authority, so a behaviour that
  callers come to depend on gets written down here, in the change that establishes it.
