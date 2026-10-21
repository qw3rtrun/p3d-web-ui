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

## IMPORTANT - do not grow a second reply model

`msg/Replay.java` parses firmware replies into `Replay.Ok/Error/Busy/Wait/Resend/Message`.
`:gcode`'s `protocol/` and `marlin/event/` packages parse the *same lines* into
`OkRs/ErrorRs/BusyRs/WaitRs/ResendRs/...`, with a corpus and ~170 tests behind them.

**Two parsers for one wire format is the standing defect of this module.** It is not a style
complaint - the copies have already diverged:

- `Replay.Ok.ADVANCED_PATTERN` reads `ok P15 B3 N100`, with the line number **last**. Marlin writes
  it **first** (`ok N2 P15 B3`), which a live probe against MarlinSim confirmed and `:gcode`'s
  `AdvancedOkRs` was fixed for. The copy here still has the bug, and `HostTerminalTest`'s
  `anAdvancedOkIsWindowCreditAndNotAReply` pins the wrong form.
- `Replay.parse` claims `busy:` only. Marlin sends `echo:busy: processing`, which `:gcode` models
  as `BusyNotice`. Here it falls through to `Message`, so `onBusy` never fires and the send window
  never learns the printer is stalling.
- `Replay.lineNumber` calls `Integer.parseInt` on wire text and throws on anything malformed, in a
  parser whose input arrives from a serial link. `:gcode`'s rule is the opposite: errors are values.

So: **do not add a case to `Replay`.** When a reply needs reading, the work is to delete that copy
and decode through `MarlinRsDecoder`, keeping `Replay` only if a transport-local shape is genuinely
needed that `GRs` cannot express. Record it if you cannot do it in the change you are making.

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

`Requested` is a plain `long` with no synchronisation, read and written from `request`, `onNext`,
`handleBackpressure` and `cancel`. `PublisherQueue.request0` is `synchronized` but the `onNext` /
`onComplete` / `onError` paths that mutate the same state are not. If you touch demand accounting,
make the whole accounting one consistent mechanism - `AtomicLong` with
`Operators.addCap`/`produced`, or synchronise every path, not half.

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

- [ ] no new case in `msg/Replay` - decoding goes through `:gcode`'s `MarlinRsDecoder`
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

- **`msg/Replay.java` is a second reply parser**, diverged from `:gcode`'s - see above for the three
  concrete differences. This is the first thing to fix.
- **`HostTerminal.onResend` and `onWait` are empty** with `// TODO Unsupported yet`. A `Resend:`
  from the printer is how it asks for a line back after a checksum failure; ignoring it means the
  print is already wrong and the host does not know. `:gcode`'s `GSendWindow` holds the replay
  buffer this needs.
- **`CommandProcessor.onComplete` has `subscriber.onComplete()` commented out**, so upstream
  completion is swallowed. Deliberate or not, it is undocumented.
- **`PublisherQueue` uses a `PriorityQueue` with partial synchronisation** - `request0` is
  `synchronized`, the signal paths are not, and `PriorityQueue` is not thread-safe.
- **`TerminalManager` prints to `System.out`** from three of its four lifecycle hooks, calls
  `printStackTrace` from the fourth, and has no error handling on `connect()`.
- **`GFlux` captures its `FluxSink` from `Flux.create` into a field**, so `onG` NullPointers if
  anything emits before the first subscribe, and a second subscriber overwrites the field.
- **There is no written spec for this module.** `:gcode` has `doc/specs/GCODE_spec.md` and its
  Appendix B; the transport has this skill and nothing else. When behaviour here becomes a contract
  - the send window especially - it needs the same treatment.
