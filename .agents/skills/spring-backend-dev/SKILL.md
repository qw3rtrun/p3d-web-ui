---
name: spring-backend-dev
description: >-
  Write or review the Spring application layers of this repo - `:backend:core`, `:backend:api`,
  `:backend:machine-mng` and `:app`. Use for WebFlux controllers and SSE/WebSocket endpoints, Spring
  wiring and configuration, JPA entities and repositories, Liquibase changelogs, Spring Modulith
  module boundaries and their verification, the command/query and event buses, the printer aggregate
  and its lifecycle, and anything that joins a blocking persistence layer to a non-blocking web
  layer. Enforces the rule that nothing blocking runs on an event loop, that a module boundary is
  declared and verified rather than assumed, and that there is one dispatch mechanism rather than
  three. Does NOT cover `:gcode` (that is `low-level-protocol-dev`) or `:backend:terminal` (that is
  `reactive-transport-dev`).
---

# Spring backend development

This skill covers the four application modules: `:backend:core`, `:backend:api`,
`:backend:machine-mng` and `:app`. It is Java 25 with Spring Boot 4.1, WebFlux, Spring Data JPA,
Liquibase, Spring Modulith 2.1 and Lombok, with Kotlin available and used in one file so far.

The two modules it does *not* cover have their own skills and their own, different rules.
`:gcode` is dependency-free portable Kotlin where malformed input is a value and nothing throws;
`:backend:terminal` is plain Reactor with no Spring in it at all. **If the task is in either, stop
and say so** rather than applying this skill's rules to them.

## The layering rule

| Layer | Module | May depend on |
|---|---|---|
| **Protocol** | `:gcode` | nothing but the Kotlin stdlib (`low-level-protocol-dev`) |
| **Transport** | `:backend:terminal` | `:gcode`, `:backend:core`, Reactor, reactor-netty (`reactive-transport-dev`) |
| **Shared domain** | `:backend:core` | nothing but the JDK and Spring's `ApplicationEventPublisher` |
| **Persistence** | `:backend:machine-mng` | `:backend:core`, Spring Data JPA, Liquibase |
| **Web** | `:backend:api` | `:backend:core`, `:backend:terminal`, `:gcode`, WebFlux |
| **Assembly** | `:app` | everything |

The arrows never reverse. `:backend:core` in particular is the shared vocabulary - records, service
interfaces, event types - and it must stay free of WebFlux, JPA and Reactor so that both the web and
the persistence side can depend on it without dragging the other in.

**`:backend:core` is the only place a type crosses modules.** A `PrinterEntity` must not leave
`:backend:machine-mng` and a `GRs` must not reach a controller signature without a deliberate
decision - see the named-interface rule below.

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

## Spring Modulith - declared, not assumed

The dependencies are on the classpath (`spring-modulith-starter-core`, `-jpa`, `-actuator`,
`-observability`, and the test starter) and **nothing uses them**: there is no `@ApplicationModule`,
no `package-info.java`, no `@NamedInterface`, and no `ApplicationModules.verify()` test anywhere in
the repository. Today's module boundary is the Gradle module and the compiler, which cannot see
inside a jar.

What that buys when it is actually wired up, and what this skill expects of new work:

- **A module is a package under the application's base package**, with its boundary declared in a
  `package-info.java` carrying `@ApplicationModule`. Everything in a nested package is internal
  unless exported.
- **Exported types are `@NamedInterface`**, and that is what a sibling module may import. An entity,
  a repository and an `*Impl` are internal by definition.
- **`ApplicationModules.of(App.class).verify()` is a test**, and it is the one that fails the build
  when someone reaches into another module's internals. A Modulith setup without that test is
  decoration.
- **Cross-module calls go through an interface in `:backend:core` or through an event**, not through
  a concrete class. The existing `MachineManagementService` is the right shape already.
- **`@ApplicationModuleTest`** boots one module with the rest stubbed, which is the test slice to
  reach for before `@SpringBootTest`.

Gradle modules and Modulith modules are allowed to be the same thing here, and mostly should be -
but the declaration still has to exist, because it is what the verification reads.

## One dispatch mechanism, not three

The application currently routes a message three different ways, and no two of them agree on what a
message is:

- **`CommandQueryBus`** (`:backend:core`) walks a `List<CommandQueryService>` asking each
  `isApplicable(Object)`, takes the first, and casts the result unchecked. `send(req, responseType)`
  **ignores `responseType` entirely** and delegates to the one-argument form, so the class literal at
  every call site is decoration and a wrong one fails as a `ClassCastException` at the caller. One
  service is registered: `UUID → Descriptor`.
- **`MachineEventBus`** (`:backend:machine-mng`) wraps `ApplicationEventPublisher` and publishes
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
- **One changelog, included from the master.** `:backend:machine-mng` points at
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
- Run a module's tests with `:backend:api:test`, `:backend:machine-mng:test`, `:app:test`. The
  Gradle summary line only prints on failure, so count them out of the XML under
  `<module>/build/test-results/test/`.

## Patterns that work here

### Prefer a record and an exhaustive switch to reflection

Java 25 has sealed interfaces and pattern-matching `switch`. A dispatch written that way is checked
by the compiler, reads as a table, and fails the build when a case is added - all three of which the
reflective invoker in `PrinterReactor` gives up.

### Keep the aggregate out of the web module

State that survives a request belongs below the controller, not beside it. The test for whether a
class is in the right module is whether `:backend:machine-mng` could use it without depending on
WebFlux.

### Let the event registry do the delivery

Spring Modulith's event publication registry gives at-least-once delivery across a transaction
boundary with persistence and replay, which is exactly what "the printer went offline while the job
was running" needs. Hand-rolled `Sinks` are right for a live UI stream and wrong for a fact that must
not be lost.

### Configuration belongs to `:app`

A library module that carries its own `application.properties` with a datasource URL in it will
fight the assembly that imports it. `:backend:machine-mng` and `:app` both define the same datasource
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
- **Spring Modulith is a dependency and nothing else.** No `@ApplicationModule`, no
  `@NamedInterface`, no `ApplicationModules.verify()`. The only trace of it in the source is a
  logging level set to DEBUG in two `application.properties` files.
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
- **`backend/machine-mng/src/main/resources/application.properties` ships a datasource URL** from a
  library module, duplicating `:app`'s and differing from it in the Liquibase changelog path.
- **There is no written spec for this layer.** This skill is the only authority, so a behaviour that
  callers come to depend on gets written down here, in the change that establishes it.
