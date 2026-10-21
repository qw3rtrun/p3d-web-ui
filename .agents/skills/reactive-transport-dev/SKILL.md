---
name: reactive-transport-dev
description: Write or review the reactive transport in this repo - `:backend:terminal`, the Java/Reactor layer that carries G-code to a printer over TCP and reply lines back. Use for Flux/Mono pipelines, hand-written Publisher/Subscriber/Subscription implementations, backpressure and the send window, Sinks, reactor-netty connections, line framing on the wire, and anything measured by StepVerifier. Enforces the Reactive Streams rules the module currently hand-rolls, and rules out blocking calls, unchecked `tryEmit*`, signals after a terminal event, and a second copy of the reply model that `:gcode` already owns.
---

# Reactive transport development

This skill covers `:backend:terminal` only: `HostTerminal`, `PublisherQueue`, `TerminalManager`,
`GFlux`, `GSender`, `Requested` and `msg/`. It is Java 25, plain Project Reactor and
reactor-netty - **no Spring in this module** - and it is the one place in the repository where the
Reactive Streams interfaces are implemented by hand rather than consumed.

## Why this module is hard

Three things make it unlike the rest of the repo, and all three are load-bearing.

**Demand here is not a number a subscriber picked. It is how much room the printer has.**
`CommandProcessor.canProcess()` is `min(subscriptionRequested, blockQueue - sentWithoutAck -
requestedFromGCode)`: the subscriber's demand, bounded by Marlin's own block-queue count read off
the `ok` lines coming back the other way. A change that treats demand as ordinary Reactive Streams
demand will overrun the printer's buffer, and the printer answers that by dropping commands
silently. There is no exception to catch.

**The two directions are coupled.** Replies feed the send window: `onOk` gives credit back,
`onBusy` resets `sentWithoutAck` to 0. So the inbound pipeline is not a read-only observer - it is
half the flow-control loop, and a bug in reply parsing shows up as a stalled or flooded outbound
stream.

**Reactive Streams is implemented, not used.** `CommandProcessor implements Processor<String,
String>, Subscription`, `PublisherQueue implements Publisher<T>, Subscription`, and
`PublisherDescr implements Subscriber<T>, Subscription`. Operators and `Sinks` have the spec
already satisfied inside them; these classes do not. Every rule below exists because something
here can break it.

## The contracts callers depend on

There is no spec document for this module, so the contracts live here. Change one of these and the
change is a breaking change one layer up, whatever the compiler says.

### The send window

`canProcess()` is `min(subscriptionRequested, blockQueue - sentWithoutAck - requestedFromGCode)`,
and all three counters are `Requested`: atomic, saturating at `Long.MAX_VALUE` for "unbounded", and
**clamped at zero**.

The clamp is a contract and not a detail. More acknowledgements than sends is a state the transport
cannot rule out - `onBusy` zeroes the in-flight count because nothing in flight will ever be
acknowledged now, and then the `ok` for the command sent just before the stall arrives anyway, as
does an `ok` that reaches the inbound thread before the producer thread has finished counting the
command it answers. A count that went negative would read as *extra* room and send one command past
the printer's buffer for the rest of the session. Marlin answers an overrun by dropping the
overflow in silence.

What a caller may rely on: between two acknowledgements, the number of commands written is never
more than the last reported block queue. Nothing wider, and no credit invented from a reply.

### `messageFlux()` terminates when the connection does, and at no other time

A firmware error is a **value**. `ErrorRs` goes down the flux like any other `GRs`, because on a
Marlin an error is routine protocol traffic that the printer recovers from by itself:

```
>> N3 M105*1
<< Error:checksum mismatch, Last Line: 2
<< Resend: 3
<< ok
```

`onError` on this flux means the inbound side is gone - the socket, or the decoder - and
`onComplete` means it closed. Those are the only two. Routing `ErrorRs` to `tryEmitError` used to
terminate the replay sink, which is permanent: one mistyped checksum killed the reply bus for the
rest of the session, for the subscriber that was there and for every subscriber that resubscribed
afterwards, while the printer carried on answering normally.

Which errors are fatal is **not** decided here. `:gcode` keeps the machine's own words as text on
purpose, and the host is the layer that judges them.

## The layering rule

| Layer | Where | May depend on |
|---|---|---|
| **Portable core** | `:gcode` `code/core/**` | nothing but the Kotlin stdlib - see `low-level-protocol-dev` |
| **Protocol edge** | `:gcode` `marlin/**`, `protocol/**` | the core, `:backend:core` message types |
| **Transport** | `:backend:terminal` - this skill | the two above, Reactor, reactor-netty, Lombok, SLF4J |
| **Application** | `:backend:api`, `:app` | anything |

**The transport may use `:gcode`; `:gcode` may never use the transport.** The dependency is
one-way in `build.gradle` and must stay that way: the core's whole claim is that it ports to JS/TS,
C and Rust, and nothing that imports Reactor ports anywhere.

## IMPORTANT - one reply model, and it lives in `:gcode`

A firmware reply is decoded **once**, by `MarlinRsDecoder`, in `ReplyFlux`. From there the transport
routes typed `GRs` values and never looks at the text again. `messageFlux()` is `Flux<GRs<?>>` for
the same reason.

**Do not add a transport-local reply type.** If a line needs reading that `GRs` cannot express, the
change belongs in `:gcode`'s `protocol/` or `marlin/event/`, where the corpus and ~170 tests are -
not here.

This module ran a second parser (`terminal.msg.Replay`) until it was deleted, and the cost is worth
remembering, because every item was invisible until something was measured against a real printer:

- its advanced-`ok` pattern read `ok P15 B3 N100`, line number **last**, while Marlin writes it
  **first**. `HostTerminalTest` pinned the wrong form, so the suite agreed with the bug.
- it claimed bare `busy:` only, so Marlin's `echo:busy: processing` fell through to a plain message
  and `onBusy` never fired - the send window was never told the printer had stalled.
- it parsed line numbers with `Integer.parseInt`, throwing on malformed input arriving off a serial
  link, where `:gcode`'s rule is that errors are values.
- `PrinterReactor` one layer up was decoding the same line a *second* time with `MarlinRsDecoder`,
  so every reply was read by two parsers that disagreed.

Route on the **family interfaces** - `OkRs<?>`, `BusyRs<?>` - rather than on concrete classes. That
is what they are for: `SimpleBusyRs` and `BusyNotice` are one notice in two spellings, and a reply
type added to `:gcode` under an existing interface then arrives here already routed.

## Reactive Streams rules this module must obey

These are the parts of the specification the hand-written classes are responsible for. Each one is
listed because the current code either depends on it or breaks it.

### Never signal after a terminal signal

`onComplete` and `onError` are final. `PublisherQueue.onError` calls `subscriber.onError(t)` and
then `request0(...)`, which can push more `onNext` at a subscriber that has already been told the
stream is over. Terminal state needs a flag that every path checks.

### Never call `onNext` without demand

`PublisherQueue.onNext` forwards straight to `subscriber.onNext(t)` with no check that the
downstream asked for it. It happens to work because the only producer is request-driven; it is not
guaranteed by anything.

### `request(n)` must be safe to call from any thread, and `n <= 0` is an error

Demand accounting is one consistent mechanism, never half of one. In `CommandProcessor` that
mechanism is **atomics plus a drain loop**, and both halves are needed:

- `Requested` is an `AtomicLong` behind compare-and-set, with `Operators.addCap` for the saturating
  add. It was a plain `long` read-modify-written from `request`, `onNext`, `handleBackpressure` and
  `cancel` - four methods, at least two threads - and `sentWithoutAck` was a `volatile int` doing
  `+= 1` on the producer thread and `--` on the netty inbound thread. `volatile` publishes a write;
  it does not make a read-modify-write atomic.
- `processRequest()` is a `wip` drain loop. Atomic counters make each one safe on its own; they do
  not make `canProcess() -> inc/dec -> subscription.request(n)` one step, and two threads that each
  read the same room will each claim it.

`PublisherQueue.request0` is `synchronized` but the `onNext` / `onComplete` / `onError` paths that
mutate the same state are not, and the `PriorityQueue` itself is not thread-safe. Its counters are
atomic now because they are `Requested`; its queue and its signal paths are still half-synchronised.

### A `Subscription` may arrive before or after the first `request`

`PublisherDescr.request(n)` calls `subscribe()` and then `subscription.request(n)` on the next
line. That only works while every upstream calls `onSubscribe` synchronously from inside
`subscribe`. An asynchronous publisher - any `Mono.fromFuture`, anything with `publishOn` -
NullPointers there. Buffer the demand and replay it in `onSubscribe`.

### Cancellation must be idempotent and must not resurrect the stream

`HostTerminal.stop()` cancels both halves. `ReplaySubscriber.handleCancel` nulls its subscription,
which makes a second call a no-op; `CommandProcessor.cancel` calls `subscriber.onComplete()`, which
is a terminal signal emitted from a cancel path.

### Check what `tryEmit*` returns

`Sinks.tryEmitNext` returns an `EmitResult` and **fails silently when it is not `OK`**.
`HostTerminal` makes five such calls - two `tryEmitNext`, two `tryEmitError`, one
`tryEmitComplete` - and discards every result. Either use
`emitNext(..., Sinks.EmitFailureHandler)` or branch on the result; a dropped reply here is a
temperature reading that never reaches the UI.

### `Sinks.unsafe()` means you promise external serialisation

`replays = Sinks.unsafe().many().replay().latest()` opts out of the serialisation the safe variant
does for you. That is only correct while every emission comes from one thread. Emissions currently
come from `ReplaySubscriber.onNext` (the netty event loop) and from `onOk` - check that invariant
before adding a fourth caller, or move to the safe sink.

## Patterns that work here

### Frame lines on the wire, do not stream characters

`ReplyFlux` currently does
`flatMap(s -> Flux.fromStream(s.chars()...map(Object::toString))).bufferUntil("\n"::equals)` - it
boxes every byte of every reply into a `String` and pushes it through an inner `Flux`. For a
transport that is the hot path. The shape that belongs here is a stateful buffer over the incoming
chunks that cuts at `\n` and keeps the remainder, or reactor-netty's own line decoder. Whatever
replaces it must keep the two properties the current one has: a reply split across TCP reads is
rejoined, and a chunk holding several replies yields several.

### Keep blocking out of the pipeline

No `block()`, `blockFirst()`, `toIterable()`, `Thread.sleep`, or synchronous socket reads in
anything that runs on an event loop. The probes under `src/test` are the exception - they are
`main()` programs driving a real printer, not pipeline code.

### Log traffic on its own logger

`HostTerminal` keeps `TRAFFIC_LOGGER` (`...HostTerminal#traffic`) separate from the class logger so
that wire bytes can be turned on without the rest. Put `-> ` and `<- ` lines there, nothing else.
`TerminalManager` currently has three `System.out.println` calls in its `doOn*` hooks and a
`printStackTrace` in the fourth: those are debris, and new ones are not acceptable.

### Prefer an operator to a hand-written Subscriber

Every hand-rolled `Subscriber` is another copy of the rules above. If a change can be expressed
with `handle`, `concatMap`, `limitRate`, `onBackpressureBuffer` or a `Sinks` type, that is the
version to write. `CommandProcessor` is hand-written because its demand depends on the *reply*
stream, which no operator knows about - that is a real reason. "It was easier to see" is not.

## Testing

`reactor-test` is a dependency and is used in exactly one place (`PublisherQueueTest`). It should be
the default for anything with demand in it.

- **`StepVerifier` for pipelines.** `expectNext`, `thenRequest`, `expectNoEvent`, `verifyComplete`.
  A test that subscribes and collects into a list proves the values but says nothing about demand,
  and demand is what this module is about.
- **`TestPublisher`** for driving a subscriber under test, including `TestPublisher.createNoncompliant`
  to prove the code survives a badly-behaved upstream.
- **`VirtualTimeScheduler`** for anything with a timeout - never a real sleep.
- **Sinks for the wire.** `HostTerminalTest` constructs `HostTerminal` over
  `Sinks.many().multicast().onBackpressureBuffer()` and feeds it lines. That is the right shape for
  a protocol-level test: no socket, no sleep, deterministic.
- **The probes are not tests.** `EmulatorTemperatureProbe`, `GCodeLiveProbe` and `GCodeCorpusProbe`
  need a printer on `localhost:8099` and must never be wired into `check`. They exist because the
  unit tests cannot catch a reply shape the firmware actually sends - which is how the `ok N…` bug
  was found. Run one when you change reply handling.

Run the module's tests with `:backend:terminal:test`. The Gradle summary line only prints on
failure, so count them out of the XML the way `protocol-dev` does.

## Review checklist

- [ ] no transport-local reply type - decoding goes through `:gcode`'s `MarlinRsDecoder`, and
      routing matches the family interfaces rather than concrete classes
- [ ] `:gcode` still does not depend on `:backend:terminal`
- [ ] every `tryEmit*` result is handled
- [ ] no signal after `onComplete` / `onError`; terminal state is checked on every path
- [ ] no `onNext` without demand
- [ ] demand accounting is one mechanism, not half-synchronised
- [ ] `onSubscribe` may arrive late - no `subscription.request` on a field that may be null
- [ ] no `block()`, `sleep`, or blocking I/O on a pipeline thread
- [ ] traffic goes to `#traffic`, not to `System.out`
- [ ] demand is asserted with `StepVerifier`, not inferred from a collected list
- [ ] a change to reply handling was run against a live printer with one of the probes

## Known debt in this module

Live examples of what this skill rules out, so nobody copies them as precedent:

- **`HostTerminal.onBusy` frees the slot but does not pull.** It zeroes `sentWithoutAck` and then
  nothing moves until the next `request(n)` or `ok` arrives; `processRequest()` is not called. The
  tests work around it with an explicit `thenRequest`. Adding the call is a flow-control change
  against hardware and wants a probe behind it.
- **`messageFlux()` no longer carries lines no decoder claims** - the boot banner, an `M503` dump, a
  calibration report. They are dropped with a `#traffic` debug line, which is what the layer above
  did with them anyway. A terminal or console view needs a separate `Flux<String>` of received
  lines off `ReplyFlux`, not a nullable field on a value type.
- **`:backend:terminal` declares `implementation project(':gcode')`** while `messageFlux()` now has
  a `:gcode` type in its public signature. It compiles only because `:backend:api` declares `:gcode`
  itself; a consumer that does not would fail. The fix is `api` rather than `implementation`, which
  needs the `java-library` plugin in `p3d.java-conventions`.
- **`HostTerminal.onResend` and `onWait` are empty** with `// TODO Unsupported yet`. A `Resend:`
  from the printer is how it asks for a line back after a checksum failure; ignoring it means the
  print is already wrong and the host does not know. `:gcode`'s `GSendWindow` holds the replay
  buffer this needs.
- **`CommandProcessor.onComplete` has `subscriber.onComplete()` commented out**, so upstream
  completion is swallowed. Deliberate or not, it is undocumented.
- **`PublisherQueue` uses a `PriorityQueue` with partial synchronisation** - `request0` is
  `synchronized`, the signal paths are not, and `PriorityQueue` is not thread-safe. Its `Requested`
  counters are atomic now; the queue and the signal paths around them are not.
- **`CommandProcessor`'s `wip` drain guard has no test that fails without it.** It is the half of
  the demand-accounting fix that closes the compound race - two threads each reading the same room
  out of `canProcess()` and each claiming it - and there is no way to schedule two threads onto a
  two-instruction window on demand. What is pinned instead is everything around it: `RequestedTest`
  stress-tests the counters, and `HostTerminalTest` pins the window arithmetic deterministically,
  including an acknowledgement that lands mid-emission. Removing the guard breaks no test, which is
  the thing to know before someone simplifies it away.
- **`TerminalManager` prints to `System.out`** from three of its four lifecycle hooks, calls
  `printStackTrace` from the fourth, and has no error handling on `connect()`.
- **`GFlux` captures its `FluxSink` from `Flux.create` into a field**, so `onG` NullPointers if
  anything emits before the first subscribe, and a second subscriber overwrites the field.
- **There is no written spec for this module.** `:gcode` has `doc/specs/GCODE_spec.md` and its
  Appendix B; the transport has this skill and nothing else. Two contracts are written down now -
  the send window and what a terminal signal on `messageFlux()` means, both under *The contracts
  callers depend on* above - and anything else callers come to rely on goes in the same place, in
  the change that establishes it.
