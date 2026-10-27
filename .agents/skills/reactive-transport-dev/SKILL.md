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

`canProcess()` is the tightest of three things - the subscriber's demand, the printer's command
buffer, and what the host can still replay:

```
min(subscriptionRequested,
    blockQueue        - sentWithoutAck   - requestedFromGCode - awaitingFrame,
    window.capacity   - window.inFlight  - requestedFromGCode - awaitingFrame)
```

and every counter but the window's own is a `Requested`: atomic, saturating at `Long.MAX_VALUE` for
"unbounded", and **clamped at zero**.

**`sentWithoutAck` and `window.inFlight` are not the same count and must not be merged.** The first
is how much of the printer's buffer this host believes it occupies, and a `busy:` zeroes it because
nothing in flight will be acknowledged while the machine is stalled. The second is what the host
must still be able to *replay*, and a `busy:` must leave it alone, because those are exactly the
bytes a later `Resend:` asks for. They diverge at `onBusy` by design.

The third term is what makes a refused `send` impossible: the slot is reserved before the command is
pulled off the source, so `GSendWindow.send` never returns null with a command in hand. A command
pulled with nowhere to go is a silent drop, which is the failure this module exists to stop.

The clamp is a contract and not a detail. More acknowledgements than sends is a state the transport
cannot rule out - `onBusy` zeroes the in-flight count because nothing in flight will ever be
acknowledged now, and then the `ok` for the command sent just before the stall arrives anyway, as
does an `ok` that reaches the inbound thread before the producer thread has finished counting the
command it answers. A count that went negative would read as *extra* room and send one command past
the printer's buffer for the rest of the session. Marlin answers an overrun by dropping the
overflow in silence.

What a caller may rely on: between two acknowledgements, the number of commands written is never
more than the last reported block queue, nor more than the window can replay. Nothing wider, and no
credit invented from a reply.

### A session opens with `M110 N0`

The first block of every session is the counter reset, framed and written through the drain loop
like any other block: `CommandProcessor` builds it in its constructor from `GSendWindow.reset(0)`
and `write()` puts it in front of everything, including a replay.

**Why it is not optional.** The window numbers from `N1`; a firmware that was not power-cycled
remembers the line it last accepted. So the first block of a reconnection is answered with
`Error:Line Number is not Last Line Number+1, Last Line: n` and a `Resend: n+1` for a block this
host has never held - the window has nothing to replay, and every block after it is rejected on the
same grounds. The session is alive, reads as healthy, and cannot send anything ever again.

That was not hypothetical. Against the simulator, a plain Disconnect/Connect with no process
restart reached exactly that state, which means **only the first session after a printer powers on
had ever worked**. Spec 7.2 names `M110 N0` as how a host starts a session and exempts `M110` from
the continuity check precisely so that it can be sent when the sequence is not yet agreed.

Three consequences worth knowing before changing this:

- **It costs a unit of demand and a buffer slot**, like any other block. A printer that has
  reported nothing is credited with one command, so the first G-code block waits for the reset's
  `ok`. That is why `canProcess()` returns zero while the reset is owed - the same rule a pending
  replay obeys, and for the same reason: there is no agreed sequence for a block to take a place in
  yet.
- **Its `ok` acknowledges no block in the window.** `reset` does not record the `M110` as
  outstanding, so `GSendWindow.acknowledge()` answers false for it and logs at debug. The credit
  (`sentWithoutAck`) is still applied, which is the half that matters - without it the host would
  believe it has a slot the printer does not.
- **Every send-window test now opens with `N1 M110 N0*124` and an `ok`.** That prelude is the
  handshake, not ceremony.

### An acknowledged block is announced

An `ok` that releases a block from the window is republished on `messageFlux()` as
`BlockAcknowledged(lineNumber, inFlight)`. `GSendWindow.acknowledge()` returns the number it
released (null for a stray), `applyWindowEvents` emits, and the layer above counts.

**Why an `ok` stopped being swallowed.** The stated reason was that it said nothing a subscriber had
not already learned from the send window. No subscriber can see a send window, so what it actually
said was nothing to anybody - and two things needed it:

- **Progress has to be measured in acknowledgements.** The alternative is counting what the host
  handed over, and that is wrong in the one case that matters: after a `Resend:` the transport
  replays blocks the counter already counted, so progress overstates and then walks backwards. A
  block is announced under the number it was *first sent with*, so a replay does not double-count.
- **It is the only proof a session can send.** "A reply arrived" is satisfied by `M155 S1`
  auto-reports an earlier session left running and by a backlog queued while nothing was listening -
  both of which reach a host whose outbound half is dead. An acknowledgement cannot be produced that
  way.

Three things to know before changing it:

- **It is emitted from the drain loop, and must be.** The window is the only thing that knows which
  block an `ok` answered - the bare `ok` most builds send carries no number, so the correspondence
  is the window's FIFO order and nothing on the wire - and the window may only be touched there.
- **That is why `replays` is no longer `Sinks.unsafe()`.** The unsafe sink promised external
  serialisation, which held while every emission came from the netty inbound thread. The drain loop
  runs on whichever thread got there first, so the promise broke and the safe sink replaced it.
- **The `ok` that closes a resend announces nothing**, and neither does the `ok` for the session's
  own `M110` - neither releases a block. The first is `rewinding`; the second is `reset` holding
  nothing outstanding.

**It is not a count of what the firmware has *executed*.** `ok` means queued, which is the whole
reason the send window exists. A block acknowledged is a block the planner accepted.

### The queue hands demand to one source at a time, and says when one finishes

`PublisherQueue` drains several sources into one subscriber, highest priority first, so an
emergency stop reaches the wire ahead of a print that is already streaming. Four things about it
that a caller depends on:

- **All the outstanding demand goes to the head source and none is held back.** That is what makes
  preemption work - the demand follows whatever is now highest priority - and what the previous
  head did not spend returns to the pool when it completes.
- **Equal priorities come out in the order they were added.** A heap is not stable and two
  interactive commands at `REGULAR` are the normal case; without the tie-break a user who pressed
  Home and then Set Temperature could get them the other way round, which on a printer is a
  different thing happening.
- **`addPublisher` returns a `Mono<Void>` that completes when that source is exhausted.** The queue
  used to remove a finished source and tell nobody, so a print that reached the end of its file was
  invisible to the layer that queued it. Plan item C3 is the first caller that needs it.
- **A source that emits without demand is cancelled and its excess dropped**, loudly. Forwarding it
  would be an `onNext` the send window never asked for, and the send window is the only thing
  stopping the printer's buffer being overrun - which Marlin answers by dropping commands in
  silence. A loud drop is the better of two bad outcomes; a well-behaved source never reaches it.

**The demand accounting is atomics plus a drain loop**, the same mechanism `CommandProcessor` uses
and the only one this module has. It was half of one, and the compound race a lock could not close
is `peek` then `reset` then `request(n)`: two threads that each peeked the same head would each have
claimed the demand, and one of them would have handed a source a zero.

### Resynchronising a session that has drifted

A `Resend: n` for a block the window no longer holds means the firmware has named a block this host
cannot produce. `CommandProcessor.resynchronise` answers it by starting the numbering again rather
than patching it, and owns the two decisions `GSendWindow` cannot make:

- **When a session is deemed lost:** at the first `Resend:` that cannot be answered. Not a judgement
  call - there is no sequence left that both ends agree on.
- **What happens to the abandoned blocks:** they are dropped, with a WARN naming how many. They
  cannot be replayed under the new numbering without re-framing them, and the window holds the
  *bytes* it emitted rather than the commands behind them - spec 8.3 makes the checksum a property
  of the bytes, which is the whole reason it keeps them that way. Re-reading bytes this module
  itself framed in order to renumber them would put a second parser back in the transport. It would
  also be unsafe: a relative move replayed under a new number is a move made twice.

**The layer above is not told.** That is the gap to close when plan item C5 builds job recovery; see
the debt list.

### What a caller may hand to the outbound side

**One bare block per item.** No `N` field, no `*` checksum, no line terminator, and no trailing
comment. `CommandProcessor` frames what it is given verbatim through `GEncoder.frame`, which does
not parse it: a terminator inside a payload splits the block and leaves the `N` on one side of the
break and the `*` on the other, and a comment runs to end of line and swallows the checksum field
behind it.

**The line counter is the transport's, not a caller's.** An `M110` sent as text moves the firmware's
counter without moving `GSendWindow`'s, and the session desynchronises on the next block. The
window's own `reset(to)` is the way to move it; nothing calls it yet (see the debt list).

**Numbering happens in `CommandProcessor.onNext` and nowhere earlier.** `PublisherQueue` reorders by
`Priority`, so a number assigned upstream of it reaches the wire out of sequence, which is the one
thing Marlin rejects outright.

### The drain loop owns the send window

`GSendWindow` is portable Kotlin with no atomics in it and is not thread-safe, and the three things
that touch it arrive on different threads - a command on whichever thread the source produces on, an
`ok` and a `Resend:` on the netty inbound loop. So none of them touches it directly: `onNext` parks
the payload, `handleBackpressure` and `rewind` park an event, and the `wip` drain loop applies all of
it on one thread, which is also the only thread that writes. The guard is an `AtomicInteger`, so the
hand-off carries the happens-before the window's plain fields need.

That single writer is also what the replay needs. A `Resend:` must put its blocks on the wire in
their original order and *before* anything pulled since; a write issued from the inbound thread
could promise neither, and would break Reactive Streams rule 1.3 into the bargain.

**A replayed block takes a unit of the subscriber's demand of its own.** The unit it spent when it
was first written is gone - this is a second `onNext`, and the rule that none goes out unasked has no
exception for a retransmission. `canProcess()` is zero while a replay is waiting for that demand.

### `messageFlux()` carries one thing that is not a reply

Everything else on that flux is something the machine said, translated by `ReplyEvents`.
`BlockAcknowledged` is something the transport knows - see *An acknowledged block is announced*
above. Anything else added to the bus that is not a decoded reply wants the same justification: a
fact the layer above needs and genuinely cannot see from here.

### `messageFlux()` terminates when the connection does, and at no other time

A firmware error is a **value**. It goes down the flux as an `ErrorReport` like any other event,
because on a Marlin an error is routine protocol traffic that the printer recovers from by itself:

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
| **Protocol edge** | `:gcode` `marlin/**`, `protocol/**` | the core, and nothing else - no backend module |
| **Transport** | `:backend:terminal` - this skill | the two above, Reactor, reactor-netty, Lombok, SLF4J |
| **Application** | `:backend:api`, `:app` | anything |

**The transport may use `:gcode`; `:gcode` may never use the transport.** The dependency is
one-way in `build.gradle` and must stay that way: the core's whole claim is that it ports to JS/TS,
C and Rust, and nothing that imports Reactor ports anywhere.

## IMPORTANT - one parser, two models, and this module is the bridge

A firmware reply is decoded **once**, by `MarlinRsDecoder`, in `ReplyFlux`. From there the transport
routes typed `GRs` values and never looks at the text again.

**Do not add a transport-local reply type.** If a line needs reading that `GRs` cannot express, the
change belongs in `:gcode`'s `protocol/` or `marlin/event/`, where the corpus and ~170 tests are -
not here.

**But `GRs` is not what leaves this module.** `messageFlux()` is `Flux<GEvent>` - the `api`
submodel's event model - and `ReplyEvents` converts. The two models are separate on purpose:

| | models | because |
|---|---|---|
| `:gcode` `GRs` | what the wire can say: seven sensor kinds, a map of hotends, nullable targets, `BigDecimal` | it is what a printer sends, and the module must port to JS, C and Rust |
| `api` `GEvent` | what the application decided to care about: one hotend, one bed, no nulls, `double` | it is the host's vocabulary, and it changes when the product does |

They used to be one: `GRs extended GEvent`, `OkRs extended OKReceivedEvent`, and `TemperatureRs`
carried `hotend()`/`bed()` methods that existed only to satisfy an interface two modules away. That
made `:gcode` - whose whole claim is that it depends on nothing - depend on the application's
vocabulary, and every change to what the host cares about reached into the protocol.

So: **route on `GRs` inside, publish `GEvent` outside.** The send window still matches `OkRs<?>`,
`BusyRs<?>` and `ResendRs`; what those say to a subscriber is decided by `ReplyEvents`, and a reply
no arm there claims becomes an `UnknownReply` rather than vanishing - seeing one is the signal that
a reply has earned a typed event.

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

`onComplete` and `onError` are final. `PublisherQueue` has that flag now: `terminate` sets it before signalling and every path reads it,
so a source that completes or fails after a cancel reaches nobody. It used to signal `onError` and
*then* hand the failed source's demand on, which is an `onNext` after a terminal event - and it did
not fire only because the cancel one line above had already zeroed the count it passed.

### Never call `onNext` without demand

`PublisherQueue` counts what it handed each source and drops what comes back over that, rather than
forwarding it. It used to forward straight to `subscriber.onNext(t)` with no check, which happened
to work because every producer was request-driven and was guaranteed by nothing.

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

`PublisherQueue` is the same mechanism now - a `PriorityBlockingQueue` behind a `wip` drain loop -
and it got there by the same route. Half-synchronising it (a `synchronized` request path, unguarded
signal paths, a `PriorityQueue` that is not thread-safe) was correct only while nothing raced, and
the case this class exists for is precisely the race: preempting a running print means
`addPublisher` on a request thread while the netty loop is draining.

### A `Subscription` may arrive before or after the first `request`

`PublisherDescr` buffers the demand and replays it in `onSubscribe` now. It used to call
`subscribe()` and then `subscription.request(n)` on the next line, which works only while every
upstream calls `onSubscribe` synchronously from inside `subscribe`.

**Every Reactor operator tried does** - `publishOn`, `delaySubscription` and `Mono.fromFuture` all
hand over a subscription immediately and defer only the values - so the test for it needed a
publisher written to defer it. That is worth knowing before dismissing this class of bug as
theoretical: the specification allows late `onSubscribe`, a hand-written publisher may well do it,
and a subscriber that assumes otherwise is correct only by the grace of its sources.

### Cancellation must be idempotent and must not resurrect the stream

`HostTerminal.stop()` cancels both halves and then disposes the connection. All three are needed
and the third is the one that is easy to leave out: cancelling a `Subscription` does not close a
socket, and `CommandProcessor.cancel` used to paper over that by answering a cancel with
`subscriber.onComplete()` - a terminal signal from a cancel path, which also completed
`reactor-netty`'s outbound and so happened to end the session.

**Removing the violation without replacing what it was accidentally doing leaks the connection.**
That is not hypothetical: a disconnect reported `OFFLINE`, sent its `M155 S0`, and left the socket
`Established` on the printer. `TerminalManager` passes `conn::dispose` in now. The rule to take
from it: when a wrong mechanism is also load-bearing, find out what it was holding up before
taking it away.

### Check what `tryEmit*` returns

`Sinks.tryEmitNext` returns an `EmitResult` and **fails silently when it is not `OK`**.
`HostTerminal` makes five such calls - two `tryEmitNext`, two `tryEmitError`, one
`tryEmitComplete` - and discards every result. Either use
`emitNext(..., Sinks.EmitFailureHandler)` or branch on the result; a dropped reply here is a
temperature reading that never reaches the UI.

### `Sinks.unsafe()` means you promise external serialisation

`replays` was `Sinks.unsafe().many().replay().latest()`, which opts out of the serialisation the
safe variant does for you and is correct only while every emission comes from one thread. That held
while emissions came from `ReplaySubscriber.onNext` and the `onOk` it calls - both the netty event
loop. `BlockAcknowledged` broke it, because a block is released inside the drain loop and the drain
loop runs on whichever thread got there first.

**It is the safe sink now**, which is the move this entry used to recommend. The lesson stands for
the next one: an unsafe sink is a promise about threads, so adding an emitter means re-checking the
promise rather than the sink.

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
- **The probes are not tests.** `EmulatorTemperatureProbe`, `GCodeLiveProbe`, `GCodeCorpusProbe` and
  `TerminalRecoveryProbe` need a printer on `localhost:8099` and must never be wired into `check`.
  They exist because the unit tests cannot catch a reply shape the firmware actually sends - which
  is how the `ok N…` bug was found. Run one when you change reply handling.
- **`TerminalRecoveryProbe` is the one for the send window**, and the only thing that measures the
  resend loop end to end: it taps the wire between the processor and the socket, breaks one framed
  block's checksum, and checks that the firmware rejects it, that the host sends the same bytes
  again, and that the session carries on. No unit test can do that, because it needs a peer that
  rejects something.

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
- **A resynchronised session drops the blocks it abandoned, and nothing above is told.** The
  counter reset is in - see *Resynchronising a session that has drifted* - and the blocks the window
  was holding are discarded with a WARN naming the count. For a bare terminal session that is the
  honest answer; for a print it is not, because those are lines of a job that will never reach the
  machine. Plan item C5 owns recovery and is where the signal belongs. Do not solve it by having
  the transport re-frame them: the window holds bytes rather than commands on purpose, and a
  relative move replayed under a new number is a move made twice.
- ~~**A failing expectation in a send-window test hangs the JVM instead of failing.**~~ **Fixed**,
  and the warning it came with was exactly right. `cancel()` no longer answers with
  `subscriber.onComplete()`, so a mismatched expectation now reports in about three seconds instead
  of spinning at 100% CPU - but the entry's other half came true the moment it was removed:
  completing the outbound was what ended `reactor-netty`'s send, so `stop()` cancelled both halves
  and left the TCP connection `Established`. Observed on a live disconnect, not reasoned about.
  `HostTerminal` now takes a third constructor argument - how to dispose the connection - because
  it never held one and so could only ever have ended a session by faking a completion.
- **`onWait` is still an empty `// TODO Unsupported yet`.** `wait` means the firmware has an empty
  buffer and nothing to do, which is a different statement from `busy:` and currently says nothing
  to the window at all.
- **An `ok` that arrives while the window is empty is logged and lost.** `applyWindowEvents` calls
  `GSendWindow.acknowledge()`, which answers null for a stray acknowledgement, so the credit is not
  held for the next block sent and nothing is announced. A printer cannot acknowledge a block it has
  not received, so this is only reachable by a test that fakes the ordering -
  `anAcknowledgementArrivingMidEmissionIsCreditAndNotExtraRoom` does exactly that, and leaves the
  window one block narrower for the rest of its run. Worth knowing before that test is extended, not
  worth a deferred-credit counter today.
- **The line number in a `BlockAcknowledged` restarts at 1 when a session resynchronises.** It is
  the wire's number, and `GSendWindow.reset` is what moves it - so a consumer that treats the
  sequence as monotonic over the life of a connection is wrong after the first resync, which is also
  the moment blocks were abandoned. Both halves belong to the same fix: C5 owns recovery and is
  where a job learns that its numbering started again and which lines went missing. Until then, read
  a `BlockAcknowledged` as "the firmware took this block" and not as a position in a job.
- **It counts blocks *queued*, not blocks executed.** `ok` covers acceptance into the planner, which
  is the whole reason a send window exists. For C4's "estimated remaining" that is the right signal
  to start from and not the right signal to stop at: at the end of a job the planner still holds
  seconds of motion that has been acknowledged and not performed.
- ~~**`CommandProcessor.onComplete` has `subscriber.onComplete()` commented out.**~~ **Fixed** - an
  exhausted source now completes the stream, once everything it handed over is on the wire.
  Completing directly from `onComplete` would have dropped commands: a payload sits in `pending`
  until there is demand to write it, and `write0` refuses to write once the stream is terminated.
  So the source's completion is recorded and the drain loop decides when it becomes one.
  **It does not wait for acknowledgements** - this stream carries blocks to the wire, and whether
  the firmware took them is what `BlockAcknowledged` answers block by block.
- ~~**`PublisherQueue` uses a `PriorityQueue` with partial synchronisation.**~~ **Fixed** - it is
  a `PriorityBlockingQueue` behind the same `wip` drain loop `CommandProcessor` uses, and the entry
  is kept because of what the rewrite turned up. Four of the rules it broke were invisible:
  equal-priority sources came out in heap order rather than arrival order (two interactive commands
  are the normal case), a second subscriber silently replaced the first, `request(0)` was ignored
  where rule 3.9 makes it an error, and a source that over-produced was forwarded straight at the
  send window. Two others - signals after a cancel and after an error - were *latent*: the code
  shape was wrong and the wrong path happened to read a zero and return. A test that pins a rule the
  implementation gets right by accident is worth having before the accident stops happening.
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
