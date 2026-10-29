---
name: vue-ui-dev
description: >-
  Write or review the user interface of this repo - the Thymeleaf pages in
  `frontend/web-ui/src/main/resources/templates`, the TypeScript and Vue sources in
  `frontend/web-ui/src/frontend`, and the controllers that render them. Use for pages, Vue
  components, the SSE-driven dashboard and monitor views, the TS client that talks to
  `/api-*`, CSRF and sign-in handling in the browser, role-aware rendering, and the
  Vite/Gradle build that produces the assets. Enforces that the server is the authority on
  what a user may do, that the dashboard holds one stream and not one per printer, and that a
  control with no backend command is a backend change rather than a UI workaround.
---

# UI development

This skill covers the browser half of `frontend/web-ui`: the Thymeleaf templates, the
TypeScript/Vue sources under `src/frontend`, the static assets, and the controllers whose only job
is to render a page. The REST and SSE endpoints themselves are `spring-backend-dev`'s - **if the
task needs an endpoint that does not exist, that is a backend change, not something to work around
in the browser.** See *When a control has no command* below; it is the most common way this layer
goes wrong.

## The architecture, and what it is not

**Server-side rendering with interactive islands.** Thymeleaf renders the page - navigation,
layout, the data that is true at request time - and Vue components are mounted into it where the
page needs to *keep changing*: temperatures, connection state, job progress. 

**It is not a single-page application.** There is no client-side router, no client-side session, and
no "load an empty shell and fetch everything". Navigation is a normal HTTP request to a Thymeleaf
controller, which is what makes the server the authority on what each page may show, and what keeps
an unauthenticated visitor from ever receiving a page they cannot use.

Three consequences worth stating, because each one is a thing people reflexively add:

- **No client-side auth state.** The browser never holds a token, decodes a JWT, or decides whether
  somebody is logged in. A session cookie is sent automatically and the server answers 401 or a
  redirect; see *Signing in* below.
- **No duplicate model in the browser.** The vocabulary is `api`'s - `Descriptor`, `EventMessage`,
  `ConnectionStateChanged`. TypeScript types mirror those; they do not invent their own.
- **A component owns a region, not the page.** Mount points are explicit. If a whole page would be
  one Vue component, the page probably wanted to be a Thymeleaf template with one island in it.

## Where things live

```
frontend/web-ui/
  build.gradle                       the siouan frontend plugin drives npm; assembleScript = "run build"
  src/frontend/                      the TypeScript and Vue sources - this is the real UI source
    package.json                     vue 3.5, vite 8, typescript 6, vue-tsc
    vite.config.ts                   one entry -> build/dist/assets/p3d-ui.js, names NOT hashed
    src/
      main.ts                        entry: imports the theme CSS and Bootstrap, then mounts islands
      theme.ts                       the two sidebar controls nav.html hooks into, without jQuery
      theme/style.css                the admin theme, bundled into assets/p3d-ui.css
      api/types.ts                   the `api` vocabulary, mirrored in TypeScript
      api/client.ts                  the one place that talks to the server
      components/*.vue               the islands
  src/main/resources/templates/      Thymeleaf pages: container, nav, sidebar, dashboard,
                                     monitor, add, edit, login, profile
  src/main/resources/static/         images/ and NOTHING ELSE - four files the pages link by path
  src/main/java/.../frontend/
    mvc/MvcPrinterController         renders the pages
    *Controller                      REST and SSE - spring-backend-dev's half
    security/                        the filter chain, login page, CSRF model advice
```

**The build**: `./gradlew :frontend:web-ui:assembleFrontend` runs Vite, which writes
`build/dist`; `copyVueFiles` copies that into `build/resources/main/static`, so a built asset is
served from the same origin as the pages - which matters, because the session and CSRF cookies are
same-origin.

**Everything a page loads is either `/assets/**` or a webjar.** Vite emits exactly two files,
`assets/p3d-ui.js` (Vue, Bootstrap, the islands, the theme behaviour) and `assets/p3d-ui.css`, so
a template links one stylesheet and one script. `src/main/resources/static` holds four images and
is otherwise empty; nothing is generated into it, and nothing new should be added to it - a new
asset belongs in `src/frontend`, where the build can see it.

## IMPORTANT - the server decides, the UI reflects

Roles are VIEWER, OPERATOR and ADMIN (plan item D3). The filter chain enforces them; **the UI must
never be the enforcement**. Hiding a button is a courtesy to the user, not a control: anybody can
issue the request anyway, and the server answers 403.

So:

- **Render what the user may do, and let the server refuse the rest.** A viewer should not see a
  "heat nozzle" control, because offering an action that will fail is a bad interface - but the
  reason it is safe is the filter chain, not the `v-if`.
- **The current user reaches a template as `${currentUser}` and `${currentRole}`**, put there by
  `PageModelAdvice`. There is no endpoint that tells the browser who it is, and adding one to drive
  rendering would be building client-side auth state.
- **A 403 is a bug in the UI, not in the user.** It means the page offered something the role
  cannot do. A 401 is different and normal - the session expired.

## Talking to the server

**One module does it.** `src/frontend/src/api/client.ts` is where `fetch` and `EventSource` live, and
every component goes through it. That is what keeps the three rules below from having to be
remembered at each call site.

### CSRF: every write carries a header

The farm authenticates with a **session cookie**, because `EventSource` cannot set an
`Authorization` header - a bearer token would mean credentials in a query string. A
cookie-authenticated API needs CSRF protection, so:

- the server issues a readable `XSRF-TOKEN` cookie;
- every non-GET request must echo it back as the `X-XSRF-TOKEN` header;
- a Thymeleaf `<form>` must carry the hidden field by hand -
  `th:name="${_csrf.parameterName}" th:value="${_csrf.token}"` - because WebFlux has no
  `RequestDataValueProcessor` and Thymeleaf will *not* add it for you. A form without it is
  rejected 403 by the very filter that issued the token, on a page that looks completely normal.

A page on another origin can make the browser send the cookie but cannot read it to build the
header; that is the whole mechanism, and it is why the token must travel in a header and not only
in a cookie.

### Signing in, and the 401

Sign-in is a normal form POST to `/login` handled by Spring Security. There is nothing for the UI
to implement beyond the page.

**A `fetch` that receives 401 must send the browser to `/login`.** It must not try to render the
response: the entry point answers machine callers (`/api-*`, `/actuator`) with a bare 401 precisely
so a fetch does not follow a redirect and receive *200 and a page of HTML* that it then tries to
parse as JSON. `client.ts` handles this once, centrally.

Sign-out is a **POST** to `/logout` with the CSRF field, never a link - a GET would let any page on
the internet sign a user out with an `<img>` tag.

### Streams: one for the farm, one per open monitor page

| Page | Stream | Why |
|---|---|---|
| Dashboard | `GET /api-farm/stream` | **One** subscription for every printer |
| Monitor (one printer) | `GET /api-printer/{id}/stream` | That machine only |

**Never open one `EventSource` per printer on the dashboard.** That is the mistake plan item D2
exists to undo: `PrinterControl.updates(id)` instantiates a reactor as a side effect, so forty
rows on a page would dial forty printers that nobody asked to connect. The farm stream opens
nothing.

**The farm stream opens with a snapshot** - the current state of every machine - and then carries
live events, so a page can draw itself from the stream alone instead of fetching a list and then
subscribing, which has a gap where an event goes missing.

Both carry the same envelope, so one handler shape works for both:

```ts
type EventMessage = { id: string; type: string; event: unknown }
```

`id` is the machine, `type` is the event's simple class name, `event` is its payload.

### The event type names are a contract with code the compiler cannot see

`EventStreamContractTest` in `frontend/web-ui/src/test/java` pins the JSON. It exists because this
broke once already in production-shaped silence: the UI switched on `"TemperatureReported"` while
the stream had started carrying `"OkTemperatureRs"`, so every reading fell through the `if` and the
page simply stopped updating. Nothing in the build noticed.

**Add a case to that test whenever the UI starts depending on a new `type` string.** It is the only
thing that will fail when somebody renames an event class.

**A reply arrives under `:gcode`'s own class name, and one reading can have two of them.** The
server stopped keeping a parallel event model, so the stream carries `BareTemperatureRs` (an
`M155` auto-report) and `OkTemperatureRs` (the answer to an `M105`) for the same thing, plus
`PositionRs`, `ErrorRs` and so on. Match a family with a helper - `isTemperature(type)` in
`types.ts` - rather than writing one of the names, which is the exact shape of the bug above. The
farm's own events are still named by the application: `ConnectionStateChanged`,
`MachineJoinedFarm`, `MachineLeftFarm`.

**A reading's `target` and `power` are nullable, and null is not zero.** A build that reports no
target has not been asked for one; a target of zero is a heater on its way off. The server used to
flatten the first into the second before the browser saw it and no longer does, so a control that
renders `target` has to handle null itself.

## The controls that exist

`client.ts` carries the whole command surface, and it is the application's vocabulary rather than
G-code: temperature (hotend, bed, on-demand read, auto-report), `home`, `move`, `setFanSpeed`,
`storeSettings` and `requestPosition`.

Three things a control has to get right, because the browser is where the unit is chosen:

- **Fan speed is Marlin's 0..255, not a percentage.** Show a percentage to the person and convert
  at the control, so only one unit travels.
- **An omitted axis is not zero.** Send only the axes being moved: zero in relative mode means "do
  not move this axis" and in absolute mode means "go to the origin".
- **`relative: true` is what a jog button means.** The operator knows nothing about where the
  machine is, and the server sends the mode switch around the move so the browser never has to.

**The answer to `requestPosition` arrives on the event stream** as `PositionRs`, not from the
call. There is no auto-report for position the way `M155` auto-reports temperature, so ask on
demand and after a move - never on a timer, which fills a busy printer's command queue with
questions while it is trying to print.

## Your own account, and everybody else's

`/api-users` is ADMIN-only on purpose: reading the list of accounts is reconnaissance and creating
one is privilege granting. That left a farm where a viewer could not change their own password at
all, so `/profile` is the one narrow hole in it - **your own password, and nothing else**.

**The current password is verified, and that is not ceremony.** Without it a session someone walked
away from is not merely a session, it is the account: whoever holds it sets a new password and the
owner is locked out. Requiring the old one means a borrowed session expires instead of becoming
permanent.

The profile page is Thymeleaf, not an island: a username, a role and a form, none of which keep
changing.

## When a control has no command

The detail page's controls - homing, moving an axis, a fan, saving settings - are **application
commands**, not raw G-code. A browser never sends G-code text.

The chain is: a request-body record in `frontend/command` whose `toMarlin()` builds the `:gcode`
command -> a `POST` endpoint on `PrinterController` -> an arm in `PrinterState.onCommand` that
actually sends it -> the transport.

`frontend/command` carries temperature, homing, moves, the fan, `M500` and a position request.
**Anything beyond that needs the backend chain built first**, and `:gcode` almost certainly already
has the command - what is missing is the endpoint and the aggregate's arm.

Note that nothing in the chain is compiler-checked end to end: a payload that translates correctly
but hits no arm in `PrinterState.onCommand` compiles and is dropped at runtime with one WARN line.
The same is true inbound - a reply the transport propagates still never reaches a browser unless
the aggregate has a case for it. Both are written up in `spring-backend-dev`.

**Do not work around this in the browser.** There is no endpoint that accepts G-code text and there
must not be one: it would hand any operator the ability to send arbitrary commands to a heater,
bypassing every check the endpoints exist to make. The payload records are the browser's shape on
purpose - the server speaks G-code behind them, and the browser does not. If a control needs a
command that does not exist, say so and build it on the backend - that is `spring-backend-dev`'s
work.

## TypeScript and Vue rules

- **Vue 3 `<script setup lang="ts">`** with the Composition API. The POC under
  `static/js/component` uses the global build and `defineComponent` by hand; that is what is being
  replaced, not a pattern to copy.
- **`vue-tsc` must pass.** It is part of `npm run build`, so a type error fails the Gradle build.
  That is the UI's equivalent of the backend's compiler check and it is the reason the islands are
  TypeScript rather than JavaScript.
- **Props and events are typed.** `defineProps<{...}>()` and `defineEmits<{...}>()`, never the
  runtime-array forms, or the types stop being checked.
- **No `any` on anything that came from the server.** Parse into a declared type at the boundary -
  in `client.ts` - so a shape change is a type error in one place rather than an undefined at
  render time.
- **A component unsubscribes what it subscribed.** `onUnmounted` closes listeners it added; this
  repository's rule that everything created has an owner that releases it applies to an
  `EventSource` listener as much as to a socket.
- **Keep the theme's markup.** The pages are built on an existing Bootstrap admin theme; a
  component that renders into one should use the same classes rather than bringing its own styling,
  or the page ends up half in one design and half in another.

## Which side a thing belongs on

The test is **does it keep changing**, not "is it data".

- **Firmware, capabilities and the declared spec are rendered by Thymeleaf.** They are true at
  request time and static afterwards, so an island there would open a fetch to re-learn what the
  server already knew when it rendered the page. The monitor page's machine panel is plain
  `th:each` over `printer.profile.firmware()`.
- **Temperature, connection state, position and job progress are islands.** They change while
  somebody is looking.

### Two Thymeleaf traps, each of which cost a 500

**A derived boolean on a record is `isX()` to Thymeleaf and `x` in JSON.** `MachineProfile` has
`isKnown()`; the JSON says `"known": false`, so a template written from the payload says
`${printer.profile.known()}` and the page answers 500 - *"Method known() cannot be found on type
MachineProfile"*. Read the record, not the payload.

**`${param.x}` is an empty list when the parameter is absent, not null - and Thymeleaf reads that
as true.** So `th:if="${param.error}"` renders the block on a URL with no query string at all, and
`param.error[0]` inside it throws *"The collection has '0' elements"*. Check emptiness explicitly:
`th:if="${not #lists.isEmpty(param.error)}"`.

**A redirect is not evidence the page works.** Both of those sat behind passing tests, because the
tests drove the POST and asserted its `Location` and never rendered what it pointed at. When a
controller redirects with a result in the query string, assert the **rendered page** for every
value it can carry.

## Testing

- **`vue-tsc`** on every build - see above.
- **`EventStreamContractTest`** for anything the UI reads out of the stream by name.
- **The pages are verified by running.** This repository's convention is that findings are proved
  with real output, and for the UI that means the assembled application, a browser or `curl`, and a
  printer or the simulator - not a screenshot of a component in isolation. A control is done when
  it has moved a real machine.

## Known debt

- ~~**The POC and the real UI exist side by side.**~~ **Gone.** `static/js/component/*.js`,
  `static/js/lib/client.js` and `printer-state.js` are deleted, and the
  `printer`/`connection`/`client`/`vue` entries are out of `container.html`'s import map, which now
  carries Bootstrap only. The entry is kept because the *shape* of the mistake is worth
  remembering: the two static pages that appeared to block removal, `pages/index_old.html` and
  `pages/index_new.html`, turned out to import `./js/printer.js` and `./js/component/printer.js` -
  paths that resolve under `/pages/` and **have never existed**. They had been dead since the POC
  moved, and a grep for the filename made them look like live callers. **Check that a reference
  resolves before treating it as a dependency.**
- **There is no test runner for the TypeScript.** `vue-tsc` checks types and nothing checks
  behaviour. A component with real logic in it - unit conversion, a state machine, throttling -
  wants Vitest before that logic grows.
- **The theme ships a lot of dead markup.** Unused dropdowns, fake notification counts and demo
  charts came with it. Leave them alone unless the task touches them; removing them wholesale is
  its own change.
- **The bundled theme stylesheet is 598 KB and most of it is unreachable.** It styles an auth
  flow, a chat panel, a calendar and an email client none of which exist here, and it asks for
  background images under `/images/auth/` and `/images/dashboard/` that were never shipped - so
  those `url()`s 404 today exactly as they did when it was served from `/static`. Tree-shaking CSS
  needs a tool that can see the Thymeleaf templates, which Vite cannot.
- **No user-management page.** A person can see and change their *own* account at `/profile`, but
  creating an operator or resetting somebody else's password is still `/api-users` over `curl`.
- **Changing a password does not sign out that user's other sessions.** Sessions are rows now, so
  the fix is reachable - delete the user's other rows on a change - but it is not done, and until
  it is, a password change does not evict whoever prompted it.
- **The machine panel goes stale while the page is open.** Firmware and capabilities are learned
  when a machine first connects, and the panel is server-rendered, so connecting a machine while
  looking at its page leaves the panel empty until a reload. The honest fix is to re-render on
  `MachineJoinedFarm`-style news rather than to poll, and it is not worth it until somebody is
  actually bothered.
- **A fan speed is what this page last asked for, not a reading.** Marlin does not report fan speed
  the way it reports a temperature, so the control is labelled "requested" and is wrong the moment
  a print's own `M106` changes it.
- **Nothing stops a jog driving the toolhead into a frame.** The backend does not validate a move
  against the declared bed volume and neither does the UI, so the step buttons will happily walk a
  machine past its limits. Checking it needs tracked position for relative moves; see the backend
  skill's debt.
