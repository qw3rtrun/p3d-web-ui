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
      main.ts                        entry: finds [data-p3d] mount points and mounts islands
      api/types.ts                   the `api` vocabulary, mirrored in TypeScript
      api/client.ts                  the one place that talks to the server
      components/*.vue               the islands
  src/main/resources/templates/      Thymeleaf pages: container, nav, sidebar, dashboard,
                                     monitor, add, edit, login
  src/main/resources/static/         the admin theme (css, vendors, images) and the POC JS
    js/component/*.js                the POC Vue components, being replaced by src/frontend
    js/lib/client.js                 the POC client, being replaced by src/frontend/src/client.ts
  src/main/java/.../frontend/
    mvc/MvcPrinterController         renders the pages
    *Controller                      REST and SSE - spring-backend-dev's half
    security/                        the filter chain, login page, CSRF model advice
```

**The build**: `./gradlew :frontend:web-ui:assembleFrontend` runs Vite, which writes
`build/dist`; `copyVueFiles` copies that into `build/resources/main/static`, so a built asset is
served from the same origin as the pages. Nothing in `src/main/resources/static` is generated -
that is the hand-written theme - so do not write build output there.

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

## When a control has no command

The detail page's controls - homing, moving an axis, a fan, saving settings - are **application
commands**, not raw G-code. A browser never sends G-code text.

The chain is: a `PrinterCommand` record in `api::msg` -> a `POST` endpoint on `PrinterController`
-> an arm in `PrinterState.onCommand` that translates it into a `:gcode` command -> the transport.

Today `api::msg` carries temperature only: `SetHotendTarget`, `SetBedTarget`,
`RequestTemperatureReport`, `SetTemperatureAutoReport`. **Everything else the UI wants needs the
backend chain built first** - the `:gcode` side already exists (`AutoHome`, `LinearMoveG0`/`G1`,
`SetFanSpeed`, `FanOff`, `SaveSettings`, `GetCurrentPosition`, `AbsolutePositioning`,
`RelativePositioning`), so what is missing is the application model in between.

**Do not work around this in the browser.** There is no endpoint that accepts G-code text and there
must not be one: it would hand any operator the ability to send arbitrary commands to a heater,
bypassing every check the command model exists to make, and it would put a second command vocabulary
in front of the one `api` defines. If a control needs a command that does not exist, say so and
build it on the backend - that is `spring-backend-dev`'s work.

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

## Testing

- **`vue-tsc`** on every build - see above.
- **`EventStreamContractTest`** for anything the UI reads out of the stream by name.
- **The pages are verified by running.** This repository's convention is that findings are proved
  with real output, and for the UI that means the assembled application, a browser or `curl`, and a
  printer or the simulator - not a screenshot of a component in isolation. A control is done when
  it has moved a real machine.

## Known debt

- **The POC and the real UI exist side by side.** `static/js/component/*.js` and
  `static/js/lib/client.js` are the POC; `src/frontend` is the replacement. Every **Thymeleaf**
  page has been ported, so the `printer`/`connection`/`client`/`vue` entries in `container.html`'s
  import map are dead - but two loose static pages, `static/pages/index_old.html` and
  `index_new.html`, still `import Printer from 'printer'`. They are POC leftovers of their own and
  nothing links to them. **Deleting the POC means deciding what those two pages are for first**;
  until then the import map stays, which costs nothing because an import map only matters to code
  that imports those specifiers.
- **There is no test runner for the TypeScript.** `vue-tsc` checks types and nothing checks
  behaviour. A component with real logic in it - unit conversion, a state machine, throttling -
  wants Vitest before that logic grows.
- **The theme ships a lot of dead markup.** Unused dropdowns, fake notification counts and demo
  charts came with it. Leave them alone unless the task touches them; removing them wholesale is
  its own change.
- **No user-management page.** D3 left `/api-users` reachable only by `curl`.
