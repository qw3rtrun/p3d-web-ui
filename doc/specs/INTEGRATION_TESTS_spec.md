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

---

## 3. The two observables

A test here **may assert on exactly two things**, and both are at the system's boundary:

1. **what came back over HTTP or SSE** — the status, the JSON body, the event stream;
2. **what reached the machine** — `MockPrinter.commands()`, the bare G-code the farm wrote.

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
flaky when it fails.

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
| `ok P15 B3` | `AdvancedOkRs(planner=15, blockQueue=3, lineNumber=null)` |
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

### 4.3 It is deterministic

Nothing in `MockPrinter` runs on a timer. A real Marlin with `M155 S1` writes a temperature every
second; a test that waited for one would be asserting on a clock. A test that wants a reading calls
`reportTemperature(…)`, a busy notice `reportBusy()`, a fault `reportError(…)`.

---

## 5. The configuration matrix

> *"Every IntegrationTest should contain a configurations of mocked printer(s), probably with
> different capabilities. And check feature over several configurtions"* — #43 item 6

| Profile | `EXTRUDER_COUNT` | `Cap:SDCARD` | acknowledges with | answers at all | what it is for |
|---|---|---|---|---|---|
| `marlin()` | 1 | 1 | `ok P15 B3` | yes | the baseline — MarlinSim |
| `withoutCard()` | 1 | **0** | `ok P15 B3` | yes | the Storage tab's empty state |
| `dualExtruder()` | **2** | 1 | `ok P15 B3` | yes | a command for tool 1 is legal |
| `legacyOk()` | 1 | 1 | **`ok`** | yes | a firmware reporting no buffer counts |
| `mute()` | 1 | 1 | — | **no** | a terminal server with nothing behind it |
| `deadPort()` | — | — | — | — | an address with nothing listening |

A feature is driven over **every configuration where the answer should differ**, and the test says
what the difference is. The three that have earned their place so far:

* **extruder count** decides whether a target for tool 1 reaches the wire at all. The same request is
  driven against `marlin()` and `dualExtruder()`, and the assertion is the opposite each time.
* **card present** decides whether the browser is ever told there is a card. Marlin has no command
  that asks, so the whole feature rests on inferring it from `echo:SD card ok`.
* **how a machine acknowledges** decides how much the host may have outstanding. `mute()` proves
  `ONLINE` and `IDLE` are a real distinction — a socket, against a firmware that has taken something
  this session sent — and that exactly one block reaches a machine that never answers, because the
  send window will not pull another until one comes back.

Adding a profile is cheap and adding a *field* to `Profile` is the thing to be careful about: each
one multiplies the matrix. Add one when a feature behaves differently, not when a machine could.

---

## 6. Feature inventory and coverage

As of issue #43. **Covered** means there is a per-feature integration test in `:app` driving it
through its endpoints against a mocked printer.

| # | Feature | Endpoints | Test | Status |
|---|---|---|---|---|
| 1 | Printer management | `/api-manager` (GET, POST, PUT, DELETE) | `PrinterManagementIntegrationTest` | **covered** |
| 2 | Connectivity | `/api-printer/{id}/connect`, `/connection`, `/stream` | `ConnectivityIntegrationTest` | **covered** |
| 3 | Temperature control | `/set-temp`, `/set-bed-temp`, `/report-temp`, `/auto-report-temp`, `GET /api-printer/{id}` | `TemperatureControlIntegrationTest` | **covered** |
| 4 | SD-card storage | `/sd/mount`, `/release`, `/list`, `/delete`, `/print` | `SdCardIntegrationTest` | **covered** except upload — see below |
| 5 | Motion and machine control | `/home`, `/move`, `/fan`, `/position`, `/store-settings` | — | **deferred** — [#49](https://github.com/qw3rtrun/p3d-web-ui/issues/49) |
| 6 | G-code file store | `/api-files` | — | **deferred** — [#50](https://github.com/qw3rtrun/p3d-web-ui/issues/50) |
| 7 | Print jobs and queue | `/api-jobs` | — | **deferred** — [#51](https://github.com/qw3rtrun/p3d-web-ui/issues/51) |
| 8 | Farm dashboard | `/api-farm/stream` | — | **deferred** — [#52](https://github.com/qw3rtrun/p3d-web-ui/issues/52) |
| 9 | SD-card *upload* | `/sd/upload`, `/sd/upload/cancel` | — | **deferred** — [#53](https://github.com/qw3rtrun/p3d-web-ui/issues/53), blocked on 6 |
| 10 | Identity and access | `/login`, `/logout`, `/profile`, `/api-users` | `FarmSecurityTest` (`:frontend:web-ui`) | covered **elsewhere**, deliberately |
| 11 | Pages | `/dashboard.html`, `/printers/{id}` | `MonitorPageTest` (`:frontend:web-ui`) | **out of scope** — #43 item 2 |
| 12 | Restart recovery | *none* | `StartupAnnouncementFlowTest` | covered, **and it is the exception** — §7 |

**There is deliberately no endpoint that accepts G-code text**, so there is no console feature to
test. A control that needs something not listed above needs a backend change rather than a raw-send
escape hatch.

### 6.1 Why 9 is blocked on 6

An upload writes a file *the server already holds* onto a card: the browser posts the bytes to
`/api-files` first and names the result at `/sd/upload`. Testing the second without the first would
mean putting a file into the store by some other means, which §3 forbids. The two land together.

### 6.2 What the deferred ones need

**#49** needs nothing new — it is the same shape as temperature control and was left out only for
size. **#50**, **#51** and **#53** need the fixture to be able to upload bytes, which is a helper on
`FarmIntegrationTest` and a decision about what a test-sized G-code file looks like; #50 lands first
because the other two depend on it. **#52** needs two machines in one test, which the fixture
already supports but nothing has needed yet.

**#53 also needs `MockPrinter` to learn `M28`/`M29`** — `Writing to file: <name>` and
`Done saving file.` are decoded types and neither is in the reply census, so they want a probe
against the simulator before they are written down here.

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
   stream. §3.
6. **Move the row in §6 to covered.**

```
./gradlew :app:test
```

The Gradle summary line only prints on failure; count them out of
`app/build/test-results/test/TEST-*.xml`.
