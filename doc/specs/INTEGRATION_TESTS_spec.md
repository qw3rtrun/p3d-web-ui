# Integration Test Specification

**Scope.** What an integration test is in this repository, where one lives, what it may assert on,
and how a printer is mocked for it. It covers the tests in `:app` and nothing else.

**Out of scope.** Unit tests and module-slice tests, which belong to the module that owns the code
and are governed by that layer's skill. The browser: no test here drives a page or a Vue component
(issue #43 item 2). The simulator: no test here opens a connection to `localhost:8099` (item 3).

**Terminology.** *Must*, *should* and *may* describe what a conforming test is expected to do.
A **feature** is defined in §1. An **observable** is defined in §3.

**Authority.** This document is the written authority for `:app`'s tests. It was established by
[issue #43](https://github.com/qw3rtrun/p3d-web-ui/issues/43), whose eight numbered constraints it
implements; where this document and the issue differ, the issue is the requirement and the
difference is called out in §8.

---

## 1. What a feature is

> *"Tests should be split by Feature, like TemperatureControlIntegrationTest, SdCardIntegrationTest,
> ConnectivityIntegrationTest, PrinterManagmentIntegrationTest, etc."* — #43 item 5

A **feature** is *something a person can ask the farm to do, named by the endpoints that do it*. It
is not a class, a module, or a layer. The test is for the whole vertical: a request enters at HTTP
and the consequences are observed at HTTP, at the SSE stream, and at the machine.

Three tests of whether something is a feature:

* **it has at least one endpoint.** A capability with no HTTP surface is not a feature yet; it is
  either internal or unfinished, and §7 records the one exception that exists.
* **a user would name it.** "Set the hotend temperature" is part of *temperature control*; "the send
  window widens on an advanced `ok`" is not a feature, it is how the transport works.
* **it fails as a unit.** If half of it breaking would leave the other half useful, those are two
  features.

**Every GitHub issue labelled `feature` must leave behind an integration test here** (#43 item 8).
It may extend an existing class where the work extends an existing feature; it introduces a new
class where the work introduces a new one.

---

## 2. The shape of a test

| | |
|---|---|
| **Where** | `app/src/test/java/org/qw3rtrun/p3d/` |
| **Named** | `<Feature>IntegrationTest` |
| **Extends** | `FarmIntegrationTest` |
| **Boots** | the assembled application on a random port, `@ActiveProfiles("test")` |
| **Signs in** | as the administrator, through the browser's own form-login flow |
| **Talks to** | a `MockPrinter` on a loopback port, registered through `POST /api-manager` |

### 2.1 The package is flat, and must stay flat

Every test class sits directly in `org.qw3rtrun.p3d`, beside `ApplicationModulesTest`. It is
tempting to group them into `org.qw3rtrun.p3d.it`, and that **must not be done**: Spring Modulith
derives the module model from package layout, a direct sub-package of the base package *is* a
module, and `ApplicationModules.of(StandaloneApp.class, …)` reads the classpath of the JVM running
the tests. A new sub-package here is a new module in the application's declared structure, reported
by the only test that verifies it.

### 2.2 One context for the whole module

Every class carries the same annotations, so Spring caches one application context and the suite
pays for one boot. Two consequences are load-bearing:

* **state leaks between classes**, so `FarmIntegrationTest.tidyUp()` deletes every machine a test
  registered. Deleting is what releases the runtime — `MDeletedEvent` reaches
  `PrinterReactorManager.release`, which stops the reactor, disposes any retry in flight, closes the
  terminal and completes the update sink. A mock closed without that leaves a reactor redialling a
  dead port for the rest of the suite;
* **a class that adds a `@TestConfiguration`, a `@MockitoBean` or a property forks the context** and
  costs a second boot. Do not, unless the thing being tested is the configuration itself.

### 2.3 Sign-in

Once per suite, cached by port, performing the flow a browser performs: `GET /login` for the
`XSRF-TOKEN` cookie, `POST /login` with the form, keep the `SESSION` cookie, send `X-XSRF-TOKEN` on
every write. A mock exchange would skip the CSRF hand-off, which is issued by one filter and checked
by another.

**Everything runs as the administrator.** Which role may do what is a separate question with a
separate test — `FarmSecurityTest` in `:frontend:web-ui` drives every role against every surface —
and restating that matrix here would be a second copy to keep in step.

### 2.4 The file a test uploads

Three features need a G-code file in the store before they can be driven at all: the store itself,
a print job that names a file the farm holds, and an SD upload that writes one onto a card. Putting
one there by any means other than `POST /api-files` is what §3 forbids, so `FarmIntegrationTest`
carries `upload(…)` — the browser's own request, raw body and the name in `X-File-Name` — and
tidies away what it stored, for the same reason it deletes machines.

**The bytes are generated, not checked in**, by `GcodeFixture`. Three reasons, in increasing order
of how much they cost to find out the hard way:

* **one file cannot be all of them.** #50 needs slicer comments at the top, a summary at the
  *bottom*, none at all, and one file larger than a single socket read; #51 needs a known estimated
  time and line count to divide a progress figure by; #53 needs one small enough that every line the
  farm writes to a card can be read back out of `MockPrinter.commands()`. Six resources are six
  things to keep in step;
* **a checked-in `.gcode` has the line endings Git felt like giving it.** The store counts lines and
  hashes bytes. On a Windows working copy with `core.autocrlf=true` every `\n` becomes `\r\n`, so
  the size and the SHA-256 both change and a test that pinned either fails on one developer's
  machine and nobody else's;
* **a fixture carries a mark, and it is not optional.** The store is content-addressed and the whole
  module shares one database, so two tests that happened to build the same file would be handed each
  other's record — the store working exactly as designed and the tests lying. The mark goes in as a
  coordinate rather than a comment, so an unsliced fixture stays genuinely comment-free. It follows
  that `cura("x").named("a.gcode")` and `cura("x").named("b.gcode")` are *the same bytes under two
  names*, which is what the de-duplication test needs to say and the only way to say it.

`GcodeFixture.blocks()` is the second observable's half of this: **the blocks a feed will actually
put on the wire**, which is not the line count, because blank and comment-only lines are dropped
rather than sent. A test asserting what reached the machine needs that list and needs it
independently of the server. It is not a G-code reader and must not become one — `GcodeLines` in
`:backend:core` is, and it tokenizes every line through `:gcode` because `G1 X10 ; wipe` is a real
line. What `blocks()` relies on is a property of these fixtures alone: every comment in one is a
whole line of its own. A fixture that broke that would make it quietly wrong.

---

## 3. The two observables

A test here **may assert on exactly two things**, and both are at the system's boundary:

1. **what came back over HTTP or SSE** — the status, the JSON body, the event stream;
2. **what reached the machine** — `MockPrinter.commands()`, the bare G-code the farm wrote, and
   `MockPrinter.written(name)`, what ended up in a file on its card.

The second of those two is one observable and not a third. A card is part of the machine, and an
upload is the one feature where *what the machine did with what it was sent* is not deducible from
the traffic: between `M28` and `M29` a line is stored rather than run and is acknowledged either
way, so `commands()` is identical whether exclusivity held or not. The difference is only visible in
the file. See §4.5.

It **must not** assert on anything between them. No autowired service, no repository, no
`ApplicationEventPublisher`, no aggregate, no `PrinterDirectory` (#43 item 4). A test that needs one
has found a gap in the HTTP surface, and that is a finding to report rather than a licence to reach
inside.

### 3.1 Why the wire counts as a boundary and not a probe

The machine is the far end of the system under test, exactly as the browser is the near end.
Asserting what arrived there is not reaching into the middle.

It is also the only assertion that has ever caught this repository's recurring failure. **Three
features shipped complete, green and silent**: the motion commands, then the Storage tab's five
buttons, then the Storage tab's two replies. Each answered 200, each had passing tests at both ends,
and each put nothing on the wire or dropped everything coming back. A test that stopped at the HTTP
response would have passed on all three.

So the rule is: **a test of a command asserts the G-code that reached the machine; a test of a reply
asserts the event that reached the stream.** A feature with both halves asserts both.

**It caught a fourth one before it was merged.** The first CI runs of this suite failed
intermittently, three runs in five, and never locally. The message was the whole diagnosis:

```
The machine received [M110 N0, M115, M155 S1]
and the stream carried [ConnectionStateChanged, ConnectionStateChanged, ...]
```

`M115` went out and the printer answered — the `ok` must have been processed, because `M155 S1` is
only pulled after it — and the `FirmwareInfoRs` with five `CapabilityRs` from that same answer
reached nobody. The cause was a *safe* `Sinks.Many` being handed two threads: it refuses the second
with `FAIL_NON_SERIALIZED`, and both `HostTerminal.emit` and `PrinterReactor.onEvent` treated that
as something to log rather than something to retry. Two of the three instances were fixed with this
work; the third is [#55](https://github.com/qw3rtrun/p3d-web-ui/issues/55).

Three things about that are worth keeping, because they are what this document is arguing for:

- **no unit test in either module could have caught it.** The loss is on the seam between
  `:backend:terminal` and `:backend:core`, and it needs two threads genuinely running at once;
- **the module's own suite agreed with the bug.** Every test in `HostTerminalTest` subscribed the
  reply bus *before* `start()` — which no real caller can do, since `start()` is the call that
  creates the thing you subscribe to;
- **the failure was only diagnosable because the wait printed both observables.** See §3.2.

### 3.2 Waiting

Everything is asynchronous — a dial, a handshake, a round trip, an event reaching a subscriber. Use
`await(what, condition)`, which polls to a deadline and reports *what it was waiting for* when it
expires.

A fixed pause is permitted **only where the claim is that something did not happen**, and must say
so at the call site. Waiting a fixed time for a result is a test that is slow when it passes and
flaky when it fails. It is `quiet()` on the fixture and nowhere else: four classes had grown a
private copy at two different durations, which made "how long is long enough to disprove this"
something each one answered on its own instead of once, against the positive case it is measured
against — a dial, a handshake and a round trip on loopback.

Three rules that were each learned by a CI failure nobody could reproduce:

- **Wait for something that actually guarantees what comes next.** `connectAndWait` returns at
  `IDLE`, which is the firmware acknowledging the *first* block of the session — `M110 N0`.
  `M115` and `M155 S1` are queued behind it and are usually still in flight, so a test that then
  cleared the machine's command log found the handshake landing in the cleared log and read it as
  its own traffic. Worse, "`M155 S1` reached the machine" does **not** mean the aggregate read the
  firmware report: the `ok` closing `M115` is handled on the netty inbound thread, which writes the
  next block itself, while the `FirmwareInfoRs` from the same reply is queued onto the aggregate's
  scheduler. Two threads, no happens-before. Use `connectAndLearn` or `connectAndSettle`, which
  wait on the aggregate's own emission.
- **Build the evidence into the failure message, lazily.** A CI-only failure is diagnosed by what
  its message carries and nothing else. Use the `Supplier<String>` overload of `await` so the
  evidence is sampled *after* the wait rather than before it, and name both observables — what the
  machine received and what the stream carried. The diagnosis above is one such message.
- **`:app` sets `testLogging { exceptionFormat 'full' }`** for the same reason. Gradle's default
  prints the exception class and a line number, which discards the message entirely.

### 3.3 A prefix, or the whole list

`MockPrinter.sent("M104")` asks whether something arrived. `assertEquals(List.of(…),
printer.commands())` asks what the request caused and nothing else. They are not interchangeable,
and which one a test wants follows from what the feature can get wrong:

* **a prefix is enough when the command stands alone.** "`M104` went out carrying `S210`" is the
  whole of setting a temperature; a stray extra command would be a different feature misbehaving.
* **the whole list is required when the unit is more than one command**, because then the bugs are
  an element *missing* and an element *extra*, and a prefix check sees neither. `M23` without its
  `M24` selects a file and prints nothing; `G91` and a move without the closing `G90` leaves the
  machine in relative mode, and what breaks is the *next* absolute move somebody makes. Both are
  silent at the moment they happen.
* **the whole list is also how a parameter letter is pinned.** `M106 S200 P1` and `M106 I1 S200`
  both start with `M106` and both are accepted by the firmware without complaint; only one of them
  sets fan 1.

Wait on the **last element** and then compare the list, rather than waiting on the comparison. A
wait on list equality reports a missing final command as a timeout with no diff, which is the one
failure the form exists to diagnose; waiting on the last element names the expectation and the
arrivals in its own message, and the `assertEquals` after it gives the diff for everything else.

---

## 4. The mocked printer

`MockPrinter` binds an ephemeral loopback port and speaks Marlin over it. A machine is registered at
that port through `POST /api-manager`, so **nothing about the application is reconfigured for a
test** — the farm simply has a printer there.

### 4.1 Why the socket is the seam

There is no bean to override. `PrinterReactorManager.startReactor` says
`new TerminalManager(entry.connection())` inline, so nothing between the aggregate and the wire is
replaceable; what *is* replaceable is the address, because a machine carries a host and a port.

That is the better seam anyway, and it is the reason this issue needed no production change at all:

| mocking at | would skip |
|---|---|
| `PrinterControl` | `:backend:core` — the aggregate, the lifecycle, the reactor |
| `TerminalManager` / `HostTerminal` | `:backend:terminal` and `:gcode` — framing, the send window, decoding |
| **the machine** | **nothing** |

With the machine mocked, the send window numbers and checksums real blocks, `GEncoder` frames them,
`MarlinRsDecoder` reads real reply lines, `SdFileListReader` assembles a real listing, and the
aggregate routes all of it.

### 4.2 What it says is measured

Every reply string in `MockPrinter` is a line from
`gcode/src/test/resources/marlin-replies.census.txt`, captured from a running MarlinSim by
`ReplyCorpusProbe` and carrying the decoded type beside each line. Nothing is invented.

| line | type it decodes to |
|---|---|
| `ok N2 P15 B3` | `AdvancedOkRs(planner=15, blockQueue=3, lineNumber=2)` — what a **numbered** block is answered with, and so what every block this farm sends is answered with |
| `ok P15 B3` | `AdvancedOkRs(planner=15, blockQueue=3, lineNumber=null)` — the same firmware answering an **unnumbered** block |
| `ok` | `SimpleOkRs` |
| `ok T:210.00 /210.00 B:25.00 /0.00 @:11 B@:0` | `OkTemperatureRs` — the answer *is* the acknowledgement |
| ` T:210.00 /210.00 …` (**leading space**) | `BareTemperatureRs` |
| `FIRMWARE_NAME:Marlin bugfix-2.1.x … EXTRUDER_COUNT:1 …` | `FirmwareInfoRs` |
| `Cap:AUTOREPORT_TEMP:1` | `CapabilityRs` |
| `echo:SD card ok` | `SdCardOk` |
| `Begin file list` / `NAME.GCO 47` / `End file list` | assembled into one `SdFileList` |
| `File opened: TEST.GCO Size: 47` / `File selected` | `SdFileOpened` / `SdFileSelected` |
| `X:10.00 Y:10.00 Z:5.00 E:10.00 Count X:800 Y:800 Z:2000` | `PositionRs` |
| `echo:busy: processing` | `BusyNotice` |
| `Error:…` | `ErrorRs` |
| `echo:Unknown command: "…"` | `UnknownCommand` |

**`M28` and `M29` are measured too, and not out of the census.** #53 needed them and neither was in
the capture, so they were measured with `SdWriteProbe` against MarlinSim bugfix-2.1.x on
2026-11-01. Verbatim, from the transcript that run printed:

```
>> N1 M28 test.gco*107  << echo:Now fresh file: test.gco
                        << Writing to file: test.gco
                        << //action:notification test.gco
                        << ok N1 P15 B3
>> G28                  << Error:No Checksum with line number, Last Line: 1
                        << Resend: 2
                        << ok
>> N2 G28*17            << ok N2 P15 B3
>> N6 M29*30            << Done saving file.
                        << ok N6 P15 B3
>> N7 M20*22            << Begin file list
                        << TEST.GCO 47
                        << End file list
```

| line | type it decodes to |
|---|---|
| `echo:Now fresh file: test.gco` | `SdFreshFile` |
| `Writing to file: test.gco` | `SdWritingToFile(file)` |
| `//action:notification test.gco` | `ActionRs` |
| `Done saving file.` | `SdDoneSaving` |

All four are reproduced in `MockPrinter`, rather than only the two the feature reads. Unlike the
busy notices around an `M84` — timing chatter, deliberately left out — these are a fixed part of
`M28`'s answer.

Three more facts are in that capture and all three are load-bearing:

* **the wire is a file sink and every stored line is still acknowledged.** `N2 G28*17` was answered
  `ok N2 P15 B3` and went into the file. That acknowledgement is why a stray command during a
  transfer is invisible from the host, and it is what `MockPrinter` reproduces;
* **the stored line carries no `N` and no `*`.** The four payloads came back as `TEST.GCO 47`, which
  is `G28`, `M117 Created`, `M118 Done` and `M118 P0 Done-P0` plus a `CRLF` each — 5 + 14 + 11 + 17.
  That is what lets a test compare the card's contents against a fixture's blocks directly;
* **numbering is required while saving** and is not while executing — the bare `G28` above.

**It is deliberately not added to `marlin-replies.census.txt`.** That file is a *derived snapshot*
of `marlin-replies.txt`, which is one `ReplyCorpusProbe` session; `GReplyCorpusTest` recomputes the
census from the corpus and compares it line for line, so a line can only be added to the census by
being added to the capture. Hand-writing one into a capture is forging it. `ReplyCorpusProbe` does
not send `M28` — `GCodeCorpusProbe` quarantines it for the obvious reason — and teaching it to, then
recapturing and regenerating, is its own piece of work and churns the whole snapshot. **Measured is
measured; here is where this measurement lives.**

**UNMEASURED around it, and marked as such in `MockPrinter`:** what a machine with no card answers an
`M28`, and what an `M29` with no file open answers. Neither could be provoked — the simulator always
has a card, and the probe never sent a stray `M29` — so the mock acknowledges and says nothing more,
and no test drives either. `M20` also upcases a name to DOS 8.3 (`test.gco` listed as `TEST.GCO`)
where the mock stores it verbatim; the upload tests use names already in that form, so nothing
depends on the difference.

**Unmeasured, and marked as such in the code:**

* **everything a machine with no card says.** The simulator always has a card, so the no-card replies
  could not be captured. `MockPrinter` answers `echo:No SD card`, which is Marlin's documented
  wording, and **no test may assert on that text.** The no-card tests assert the *absence* of
  `SdCardOk` and `SdFileList`, which is what the Storage tab actually keys on and is independent of
  the wording.
* **`File deleted:…` and `open failed, File:…`.** Neither is in the census and nothing decodes
  either, so they are dropped as unread. Deletion is therefore asserted by listing the card again:
  "it was deleted" can only honestly mean "it is no longer there".
* **`start`.** Decoded by `StartRs` but absent from the capture, because the probe attaches to an
  already-running simulator. `MockPrinter` does not send a boot banner.

Anything added here that is not in the census **must** be marked UNMEASURED at the call site and
added to this list, or measured first with a probe.

**Which commands are acknowledged is measured too, not only what the answers say.** `G21`, `M83` and
`M84` were added to the plain-`ok` arm with #51 because a print job and the cool-down after a
cancelled one send them, and they were falling to the `default` arm — so every print the suite drove
would have put three `echo:Unknown command` lines on the event stream that a real machine does not
send. Measured against a running MarlinSim rather than assumed:

```
>> N2 G21*24          << ok N2 P15 B3
>> N9 M84*22          << echo:busy: processing (x2), ok N9 P15 B3
>> N13 M83*42         << ok N13 P15 B3
>> N14 G1 E-2 F2400*7 << echo: cold extrusion prevented, ok N14 P15 B3
```

### 4.2.1 How an acknowledgement names its block — #61

The capture above contradicted what this document and `MockPrinter.ok()` used to say:

> *"No `N` on the advanced form, because the census shows MarlinSim does not write one on an ordinary
> `ok`"* — `MockPrinter.ok()`, before #61

**That was a census line read out of its exchange.** `ReplyCorpusProbe` sends most of its steps
unnumbered and five of them framed, so the census records both answers side by side and the rule
that picks between them is visible in it:

| probe step | block sent | census line |
|---|---|---|
| `position after the move` | `M114` | `AdvancedOkRs ok P15 B3` |
| `framed position` | `N2 M114*…` | `AdvancedOkRs ok N2 P15 B3` |
| `the resend the error asked for` | `N3 M114*…` | `AdvancedOkRs ok N3 P15 B3` |
| `framed temperature` | `N1 M105*…` | `OkTemperatureRs ok T:210.00 …` — no `N`, no counts |

That is Marlin's `ok_to_send`: with `ADVANCED_OK` built in it always writes `P` and `B`, and it
copies the `N` field **only when the command it is answering carried one**. `M105` never reaches
`ok_to_send` at all — it answers from the gcode handler and returns — which is why the fourth row
has neither. The eleven numbered blocks in the §4.2 transcript measure the same rule a second time.

Put to a running MarlinSim directly, both arms and the switch between them:

```
>> M110 N0        << ok P15 B3                                  unnumbered
>> N1 M114*38     << X:0.00 Y:0.00 …   << ok N1 P15 B3          numbered
>> N2 M114*37     << X:0.00 Y:0.00 …   << ok N2 P15 B3          numbered
>> M114           << X:0.00 Y:0.00 …   << ok P15 B3             unnumbered, mid-session
>> N3 M114*36     << X:0.00 Y:0.00 …   << ok N3 P15 B3          numbered, and N3 is still accepted
```

**The fourth line corrects something the §4.2 capture is easy to over-read.** There, an unnumbered
`G28` was answered `Error:No Checksum with line number`, a `Resend:` and a bare `ok` — but that
capture was inside an `M28` transfer, where Marlin requires every line to be numbered and
checksummed. Outside one, an unnumbered block is ordinary traffic: it is acknowledged without an
`N`, it costs the sequence nothing, and the next numbered block is accepted as though it had never
happened. So "a session that is not numbering" is not a second acknowledgement dialect — it is the
`ok P15 B3` arm, which `MockPrinter` has and no test can reach.

**So one firmware speaks two shapes and the block decides which, and this farm only ever sees the
first.** `GSendWindow` numbers every block, so a real session is numbered throughout; the un-numbered
`ok P15 B3` is a shape production never produces. `MockPrinter.ok()` now follows the firmware's rule
rather than one half of it, and the unnumbered arm is kept and unreached.

**What that was costing.** `CommandProcessor.applyWindowEvents` chooses between
`window.acknowledge(number)` and the FIFO `window.acknowledge()` on whether the reply named a line.
With the mock never naming one, **the whole suite ran the FIFO branch** and the named branch — added
by [#30](https://github.com/qw3rtrun/p3d-web-ui/pull/30) after an `ACK = -1` sentinel in a
`Queue<Integer>` let a stray `ok` shift the window permanently — was exercised by nothing. It is now
the branch every test in this module takes, which is checkable rather than asserted: mutate the mock
to answer `ok N<n+1000> P15 B3` and no block is ever released, so
`ConnectivityIntegrationTest.connectingReachesIdleAndSendsTheOpeningHandshake` fails with
`timed out after PT30S waiting for machine … to reach IDLE`. Before #61 that mutation could not be
written down, because there was no number to make wrong.

**The two branches only differ when the wire misbehaves**, which is why nothing in the suite changed
when the shape did: a numbered `ok` names exactly the block FIFO order would have released, so in a
well-behaved exchange the releases, `inFlight` and therefore `canProcess` are identical and the same
commands go out in the same order. The difference appears on a **stray** acknowledgement — one for a
line this host never sent — where the named branch releases nothing and the counted one releases
whatever is at the front. `ConnectivityIntegrationTest` puts the same stray to both dialects and
asserts the two different command lists that reach the machine: a byte-identical replay against
`marlin()`, a second `M110 N0` against `legacyOk()`.

**The census is not changed by this and must not be.** `GReplyCorpusTest` recomputes
`marlin-replies.census.txt` from the raw capture and compares line for line, so a line can only enter
it by being captured; hand-writing one would be forging a measurement. Nothing needed adding here —
both shapes were already in it. What the census still lacks is covered by
[#66](https://github.com/qw3rtrun/p3d-web-ui/issues/66).

**#53's probe run settles it beyond doubt, and adds the converse.** Every one of the eleven numbered
blocks in the `SdWriteProbe` transcript above came back as `ok N<n> P15 B3` — `ok N1 P15 B3` through
`ok N11 P15 B3`, with no exception, including the ones answered from inside a file save. The one
bare `ok` in the whole run is the answer to the one line that was sent **without** a number, behind
an `Error:` and a `Resend:`. So the rule is not "mostly numbered": the acknowledgement carries the
number of the block it answers whenever that block had one. That is the evidence
[#61](https://github.com/qw3rtrun/p3d-web-ui/issues/61) wants, captured while the probe was running
for something else. `MockPrinter.ok()` is still unchanged — changing it rewrites the acknowledgement
every test in this module receives, and that is #61's work and not #53's.

### 4.3 It is deterministic

Nothing in `MockPrinter` runs on a timer. A real Marlin with `M155 S1` writes a temperature every
second; a test that waited for one would be asserting on a clock. A test that wants a reading calls
`reportTemperature(…)`, a busy notice `reportBusy()`, a fault `reportError(…)`.

### 4.4 Stopping the conversation, for a feature that has one

A print job is the first feature here that is not a round trip. It is tens of thousands of blocks
pulled through the send window, and against a mock that answers instantly it is **over before the
test's next line runs** — so the two facts #51 exists to prove, that an interactive command overtakes
a running print and that the print then carries on where it was, have nothing left to observe.

`holdRepliesFrom(block)` stops the machine answering from a named command onwards, and
`answerOne()` / `answerFreely()` move it on again. **What makes that deterministic is the send
window and not a clock**: the host may not have more outstanding than the printer's buffer count
allows — three on `marlin()`, one on `legacyOk()` — so a machine that stops acknowledging stops the
conversation within a few blocks and stops it for good. A test can then sample what is on the wire
and know the number will not move under it, which `FarmIntegrationTest.stalled` asserts
rather than assumes.

It is the same rule as §4.3 rather than an exception to it: the test decides when the machine
answers, and nothing is waited out.

**The ordering of two sources is not a race either, and that is a property of the application rather
than of the mock.** `PrinterReactor.handle` applies a command on the aggregate's own executor and
completes its `Mono` afterwards, so a 200 from `/report-temp` means the `M105` is already in the
priority queue — before any acknowledgement is released, and therefore before the job can be handed
another unit of demand.

### 4.5 The wire as a file sink

`M28` is the second feature here that is not a round trip, and it is a stranger one than a print
job: it stops the firmware *executing* what it is sent and makes it store the lines instead,
**acknowledging each one exactly as if it had run it**, until `M29`. `MockPrinter` reproduces that,
which is what makes an upload assertable at all. `written(name)` is the file; `writingTo()` is the
file that is open, or null.

Three things follow, and each is a test that could not otherwise exist:

* **a stray command during a transfer is invisible in the traffic.** `commands()` shows the `M105`
  either way; whether it was *run* or *written into the G-code* is only legible in `written(name)`.
  That is the fault the whole feature exists to prevent and the reason the file is an observable.
* **a cancel is distinguishable from a tear-out.** A transfer stopped properly has an `M29` behind
  it, so `writingTo()` is null and the card holds a short but closed file; one torn out leaves the
  file open, and the next command goes into it. `SdCardIntegrationTest` asserts the second half by
  sending an interactive command afterwards and checking the file did not grow — which is exactly
  what `SdUploadProbe.stopRun` does against a real firmware, by asking `M23` for the size twice.
* **the card can be listed afterwards.** The size the mock reports is the payloads plus a `CRLF`
  each, because that is what the probe measured (§4.2) — so "it is really there, at really that
  length" is sayable through `/sd/list` rather than by trusting the transfer.

**What this level cannot prove is exclusivity itself**, and that was established by mutation rather
than assumed. Replacing `addExclusivePublisher` with `addPublisher` in
`PrinterReactor.uploadToCard` leaves every test in `SdCardIntegrationTest` green; demoting the
transfer from `REALTIME` to `BACKGROUND` fails one. Both are correct, because the two mechanisms are
not the same guarantee: priority decides who gets the *next* unit of demand, and exclusivity covers
the case priority cannot — a source handed demand *before* the transfer was queued and still holding
it. **No endpoint can produce that**, because every source this farm has is request-driven and
spends what it is given at once; it takes a deliberately slow publisher, which is what
`SdUploadProbe`'s `SlowSource` is (675 bytes against 687) and what `PublisherQueueExclusivityTest`
builds. Exclusivity is belt over braces today, and this module tests the braces.

---

## 5. The configuration matrix

> *"Every IntegrationTest should contain a configurations of mocked printer(s), probably with
> different capabilities. And check feature over several configurtions"* — #43 item 6

| Profile | `EXTRUDER_COUNT` | `Cap:SDCARD` | acknowledges with | answers at all | what it is for |
|---|---|---|---|---|---|
| `marlin()` | 1 | 1 | `ok N<n> P15 B3` | yes | the baseline — MarlinSim |
| `withoutCard()` | 1 | **0** | `ok N<n> P15 B3` | yes | the Storage tab's empty state |
| `dualExtruder()` | **2** | 1 | `ok N<n> P15 B3` | yes | a command for tool 1 is legal |
| `legacyOk()` | 1 | 1 | **`ok`** | yes | a firmware built without `ADVANCED_OK`: no buffer counts and no line number |
| `mute()` | 1 | 1 | — | **no** | a terminal server with nothing behind it |
| `deadPort()` | — | — | — | — | an address with nothing listening |

`<n>` is the number of the block being answered — §4.2.1. A profile with `advancedOk` answers an
unnumbered block `ok P15 B3`, which no row above ever reaches, because nothing in this farm sends an
unnumbered block.

A feature is driven over **every configuration where the answer should differ**, and the test says
what the difference is. The three that have earned their place so far:

* **extruder count** decides whether a target for tool 1 reaches the wire at all. The same request is
  driven against `marlin()` and `dualExtruder()`, and the assertion is the opposite each time.
* **card present** decides whether the browser is ever told there is a card. Marlin has no command
  that asks, so the whole feature rests on inferring it from `echo:SD card ok`.
* **how a machine acknowledges** decides how much the host may have outstanding. `mute()` proves
  `ONLINE` and `IDLE` are a real distinction — a socket, against a firmware that has taken something
  this session sent — and that exactly one block reaches a machine that never answers, because the
  send window will not pull another until one comes back. The same axis is the one a **print job**
  has: a job is tens of thousands of blocks through exactly that gate, so `marlin()`'s window of
  three and `legacyOk()`'s window of one are two genuinely different ways of feeding one file, and
  `PrintJobIntegrationTest` asserts the file that comes out is identical. Nothing else about a
  printer changes what a job sends — a feed does not consult an extruder count and does not involve
  a card — so no other profile is driven. **An SD upload has the same axis and only that axis**: it
  is the same stream of blocks through the same gate, so `marlin()` and `legacyOk()` are driven and
  the file that lands on the card has to be identical.

**`withoutCard()` is deliberately not driven against an upload**, and that is §4.2 being obeyed
rather than a gap in the matrix. It is the one configuration where the answer genuinely should
differ, and what a machine without a card answers an `M28` has never been measured — the simulator
always has one. A profile whose reply was invented and then asserted on would be a test of the
invention. It stays uncovered and named, which is the honest state.

  **The same axis decides something narrower and sharper, which is why `legacyOk()` was kept rather
  than folded into `marlin()` when #61 made the baseline number its acknowledgements.** A firmware
  that names the block it is acknowledging lets the host refuse a stray `ok`; one that does not
  leaves it counting, and a single stray then shifts the correspondence permanently. The two
  `ConnectivityIntegrationTest` tests at the end of §4.2.1 are that difference, asserted as two
  different command lists at the machine.

  **"Acknowledges without a number" was ambiguous and `legacyOk()` is the first reading of it.** It
  is *a firmware that does not report numbers* — `ADVANCED_OK` off, a real Marlin build option, a
  property of the machine, which is the only kind of thing a `Profile` may be. It is **not** *a
  session that is not numbering*: that is a property of a host, no endpoint can put the farm in it
  since `GSendWindow` numbers every block, and §4.2.1 measures that it is not a second dialect in
  any case — an unnumbered block answers `ok P15 B3`, which is an arm `MockPrinter.ok()` already
  has and no test can reach.

Adding a profile is cheap and adding a *field* to `Profile` is the thing to be careful about: each
one multiplies the matrix. Add one when a feature behaves differently, not when a machine could.

### 5.1 A feature with no machine in it

`GcodeFileIntegrationTest` drives over **no** configuration, because it drives over no printer. A
stored file is the farm's and not a machine's: it is uploaded once and can be sent to any number of
them, or to none. The rule in this section is being applied rather than suspended — *every
configuration where the answer should differ*, and here nothing about a printer changes any answer.

Two consequences, and both are fine:

* the second observable has nothing to observe. A feature with no machine asserts only the first;
* **nothing in that class waits.** Every assertion is on the response to a request the test just
  made, so there is no `await` in it at all. That is the exception rather than the rule — see §3.2 —
  and it is worth noticing which features are asynchronous and which only look it.

### 5.2 A feature whose configuration is a second machine

`FarmDashboardIntegrationTest` drives over **no profile distinction either**, and for the mirror
image of §5.1's reason: a connection state is announced the same way by a machine with a card and
one without, so no row of the table above changes any answer. The axis that does is one no other
class varies — **how many machines** — because `GET /api-farm/stream` exists so that a dashboard
holds one `EventSource` rather than one per printer, and a farm that put every machine's events
under the first id it thought of would pass every other test in this module.

So the matrix for that feature is *one machine against two*, and the pairs are deliberately two
different profiles so the demultiplexing is proved across machines that are genuinely not the same.
Three things it can assert that a single-machine test cannot:

* **each machine's events arrive under its own `EventMessage.id`.** Asking one machine for a
  temperature and finding the reading under the other is a failure one stream can have and forty
  cannot — a per-printer stream is addressed by the id in its URL and cannot mislabel anything;
* **each machine's own order survives the merge.** A page told `ONLINE` after `IDLE` draws a
  machine that has just become unusable;
* **a removal reaches one id and not the rest.** On a shared stream, a `MachineLeftFarm` that went
  to every subscriber's every row is the whole farm vanishing from a page while the printers carry
  on printing.

**Subscribing dials nothing, and that is the feature's own assertion.** The obvious wiring for a
farm view is `PrinterControl.updates(id)` per machine, which instantiates a runtime as a side effect
— forty reactors to render a page that is only looking. `FarmEvents` listens on the application
event bus instead. What is observable from out here is the *dial* and not the reactor: whether a
runtime exists is visible through no endpoint, and §3 forbids reaching in to look, so the assertion
is `MockPrinter.sessions() == 0`.

### 5.3 The farm stream has no replay, and what a test must do about it

Two consequences that only bite on `/api-farm/stream`, both written into `watchTheFarm()`:

* **register the machines first.** A per-printer sink replays the machine's current state, so a
  subscriber is handed something immediately. This one has no replay — a replaying sink would hold
  the *farm's* last event rather than each machine's, which is whichever printer spoke most
  recently and is useless — and what a subscriber gets instead is a snapshot built per subscription
  from the machines the farm knows about *at that moment*. Subscribe before registering and the
  stream is correct and silent;
* **wait for the snapshot before causing anything.** `FarmEvents.updates()` is the snapshot
  `concatWith` the live sink, so the sink is subscribed only once the snapshot completes. Each
  machine's snapshot entry arriving is therefore the only signal a test gets that the subscription
  is live.

**There is a window between those two**, milliseconds wide, in which an event reaches nobody — see
[#52](https://github.com/qw3rtrun/p3d-web-ui/issues/52). It is open, closing it means subscribing
the sink before prepending the snapshot, and **no test may depend on it in either direction.** The
way to not depend on it is to cause every asserted live event with *a request made after the
snapshot has arrived*: an HTTP round trip is orders of magnitude outside the window, where a direct
push to a mock is not.

A snapshot entry is also distinguishable from a transition, which is worth knowing when asserting
on one: it states where a machine *is*, so `from` and `to` are the same state.

---

## 6. Feature inventory and coverage

As of issue #43. **Covered** means there is a per-feature integration test in `:app` driving it
through its endpoints against a mocked printer.

| # | Feature | Endpoints | Test | Status |
|---|---|---|---|---|
| 1 | Printer management | `/api-manager` (GET, POST, PUT, DELETE) | `PrinterManagementIntegrationTest` | **covered** |
| 2 | Connectivity | `/api-printer/{id}/connect`, `/connection`, `/stream` | `ConnectivityIntegrationTest` | **covered** |
| 3 | Temperature control | `/set-temp`, `/set-bed-temp`, `/report-temp`, `/auto-report-temp`, `GET /api-printer/{id}` | `TemperatureControlIntegrationTest` | **covered** |
| 4 | SD-card storage | `/sd/mount`, `/release`, `/list`, `/delete`, `/print` | `SdCardIntegrationTest` | **covered** |
| 5 | Motion and machine control | `/home`, `/move`, `/fan`, `/position`, `/store-settings` | `MotionControlIntegrationTest` | **covered** — and the one that asserts whole command lists, §3.3 |
| 6 | G-code file store | `/api-files` | `GcodeFileIntegrationTest` | **covered** — and the one with no machine in it, §5.1 |
| 7 | Print jobs and queue | `/api-jobs` | `PrintJobIntegrationTest` | **covered** |
| 8 | Farm dashboard | `/api-farm/stream` | `FarmDashboardIntegrationTest` | **covered** — and the one with two machines in it, §5.2 |
| 9 | SD-card *upload* | `/sd/upload`, `/sd/upload/cancel` | `SdCardIntegrationTest` | **covered** — [#53](https://github.com/qw3rtrun/p3d-web-ui/issues/53); and the one with a file on the card as an observable, §4.5 |
| 10 | Identity and access | `/login`, `/logout`, `/profile`, `/api-users` | `FarmSecurityTest` (`:frontend:web-ui`) | covered **elsewhere**, deliberately |
| 11 | Pages | `/dashboard.html`, `/printers/{id}` | `MonitorPageTest` (`:frontend:web-ui`) | **out of scope** — #43 item 2 |
| 12 | Restart recovery | *none* | `StartupAnnouncementFlowTest` | covered, **and it is the exception** — §7 |

**There is deliberately no endpoint that accepts G-code text**, so there is no console feature to
test. A control that needs something not listed above needs a backend change rather than a raw-send
escape hatch.

### 6.1 Why 9 was blocked on 6

An upload writes a file *the server already holds* onto a card: the browser posts the bytes to
`/api-files` first and names the result at `/sd/upload`. Testing the second without the first would
mean putting a file into the store by some other means, which §3 forbids. **That block is lifted**:
`upload(…)` and `GcodeFixture` landed with #50 and are described in §2.4.

### 6.2 What the deferred ones need

**#49 is done**, and it needed nothing new: no profile, no `MockPrinter` reply and no production
change. What it added is §3.3. **#51 is done** — it needed the fixture to be able to upload bytes
and to stop a machine answering, and both now exist, §2.4 and §4.4. **#52 is done** — it
needed two machines in one test, and the fixture supported that except for the parts that are only
a question when a stream carries more than one: `Events` was id-blind, and nothing could remove a
machine mid-test without `tidyUp()` removing it a second time. `Events.from(id)` and its id-scoped
companions, and `deregister(id)`, are what closed that; §5.2 and §5.3 are the rest.

**#53 is done**, and it needed no production change. It needed two things that did not exist:
`M28`/`M29` measured against the simulator, because `Writing to file: <name>` and
`Done saving file.` are decoded types that nothing had ever captured — §4.2 holds the transcript —
and the card itself as an observable, which is §4.5. `stalled(…)` moved up to `FarmIntegrationTest`
in the same work, because a second feature now needs it and "how long is long enough" is a question
to answer once; `number(…)` moved with it, having been privately copied into three classes.

---

## 7. The one exception to "tests call endpoints"

`StartupAnnouncementFlowTest` autowires `MachineManagementImpl` and `PrinterDirectory` and reads
`core`'s replica directly. **This is the only class permitted to**, and it is listed here so that it
cannot grow quietly.

The fact it covers is that `announceTheFarm()` — which runs once, on `ApplicationReadyEvent` —
carries enough for `core` to rebuild its replica from nothing. A replica is empty when the process
begins, so an announcement carrying only a host and a port would leave the farm knowing where to
dial every machine and what none of them were; every declared override would be lost at every
restart.

There is no request that triggers it, no response that reveals it, and a test cannot restart a
context mid-suite. The alternative to an exception is no coverage.

**A new exception needs a paragraph here saying why no endpoint can see the fact.** "It was easier"
is not one.

---

## 8. How this differs from issue #43 as written

Two deviations, both deliberate:

* **item 6 says every integration test should carry several printer configurations.** Two of the
  five classes drive one feature over one configuration throughout — a listing is a listing whatever
  the extruder count — and padding them with profiles that change nothing would be ceremony. The
  rule as applied is in §5: *every configuration where the answer should differ*, and the test says
  what the difference is.
* **item 4 says "not in-the-middle probes"**, and §3 admits `MockPrinter.commands()` as an
  observable. §3.1 is the argument: it is the far boundary, not the middle, and it is the only thing
  that catches the failure this application has shipped three times.

Item 7 is absent from the issue's numbering, which runs 1–6 then 8. Nothing is missing.

---

## 9. Adding a feature test

1. **Name the feature and the endpoints.** If it has no endpoint, it is not ready for a test here.
2. **Add a class** `…/app/src/test/java/org/qw3rtrun/p3d/<Feature>IntegrationTest.java` extending
   `FarmIntegrationTest`. Flat package — §2.1.
3. **Pick the configurations** where the answer should differ, from §5. Add a `Profile` only if none
   fits, and record it in §5.
4. **Teach `MockPrinter` to answer** any command the feature sends, using a line from the census. If
   the line is not in the census, measure it with a probe against the simulator first, or mark it
   UNMEASURED in §4.2 and assert around it.
5. **Assert both halves** — the G-code that reached the machine, and the event that reached the
   stream. §3. A feature with no machine in it asserts the one half it has, and says so — §5.1.
   If it needs a file in the store first, upload one; §2.4.
6. **Move the row in §6 to covered.**

```
./gradlew :app:test
```

The Gradle summary line only prints on failure; count them out of
`app/build/test-results/test/TEST-*.xml`.
