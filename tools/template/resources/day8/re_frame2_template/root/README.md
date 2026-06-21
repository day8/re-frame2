# {{name}}

[![Built with re-frame2](https://img.shields.io/badge/built%20with-re--frame2-blue.svg)](https://github.com/day8/re-frame2)
[![Substrate]({{substrate-badge-url}})](https://github.com/day8/re-frame2/tree/main/implementation/adapters/{{substrate}})
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A new [re-frame2](https://github.com/day8/re-frame2) application,
scaffolded from `day8/re-frame2-template` (deps-new).

Substrate: **{{substrate}}**.

## Run the development build

```sh
npm install
npx shadow-cljs watch app
```

Then open <http://localhost:8280> in your browser.

The first build does take a moment — shadow-cljs is downloading
dependencies and compiling the ClojureScript. Subsequent rebuilds (on
file save) are fast.

## Hot reload

`shadow-cljs watch` rebuilds on every file save and re-invokes
`{{namespace}}.core/init` — shadow's `:browser` target re-runs the
module's `:init-fn` automatically after each hot reload, so no
`:after-load` hook is needed. Two distinct mechanisms combine to give
you a clean reload:

1. **Namespace reload re-runs your registrations.** Reloading
   `events.cljs` / `subs.cljs` / `views.cljs` re-evaluates the
   `reg-event` / `reg-sub` / `reg-view` forms at the top level, so
   each handler / sub / view re-registers in place against the new
   code. Editing an existing `reg-*` form replaces that exact `id` live
   (the registry swaps the slot for the same `id`), and adding a new
   `reg-*` form registers it live. **Deleting or renaming a handler does
   NOT prune the old `id`, though** — a reload only re-runs the forms
   that still exist; it never sweeps a namespace to drop ids whose
   `reg-*` form you removed, so the old registration lingers in the
   running process. To clear it, refresh the browser tab (or restart the
   dev process) — that rebuilds the registry from an empty slate, so only
   the forms still in your source survive. (A rename is a delete plus an
   add: the new `id` shows up live, but the old `id` stays registered
   until that refresh.)

2. **`rf/init!` is safe to re-call but does NOT reset anything by
   itself.** It is idempotent and installs the substrate adapter
   **only when none is already seated**. Per EP-0002 (Spec 002 §Frame
   target resolution) `init!` does **not** create the `:rf/default`
   frame — the runtime never synthesises a frame from absence. The
   scaffold's `core/init` registers the app frame explicitly with
   `(rf/reg-frame :rf/default {})` right after `init!`, then runs its
   frame-local boot (schema attach + seed dispatch) inside a
   `(rf/with-frame :rf/default …)` scope. A second `init!` call (which
   every hot reload makes) does **not** re-install the adapter, does
   **not** snapshot the registrar, and does **not** touch app-db; a
   second `reg-frame :rf/default` is an idempotent update of the
   already-live frame. So the reload is a no-op for your state.

The thing that re-seeds the demo state on reload is the entry fn's own
explicit `rf/dispatch-sync [:counter/initialise]` call in `core.cljs`
(inside the `with-frame :rf/default` scope) — not `init!`. That is the
reset boundary: the `:counter/initialise` event handler writes the
starter app-db, so editing it (or removing the `dispatch-sync` call) is
what changes what a reload re-seeds.

## In-app devtools (Xray)

`shadow-cljs.edn` wires `day8.re-frame2-xray.preload` into
`:devtools/preloads` on the `:app` build — the scaffold ships Xray
**on by default** for development. `resources/public/index.html`
includes the `[data-rf-xray-host]` right-side layout host (the
`<aside>` follows `<main id="app">`, so it lays out to the right), so
Xray auto-opens beside your app once `rf/init!` runs. Press
**Ctrl+Shift+C** to hide/show it: per-epoch dispatch log, app-db diff,
causality graph, time-travel scrubber.
Release builds drop the preload automatically (shadow only runs
preloads under `watch` / `compile`, never `release`).

Xray's panel also offers click-to-source: each trace row that carries
a source coordinate (the `:rf.trace/trigger-handler` that re-frame2
tags onto view-render trace events, plus the `:source-coord` on event
/ fx / interceptor rows) renders a jump-to-source link, so you can
click straight from a dispatch in the log to the form that defined the
handler. No extra preload or wiring — it ships with the Xray preload
above.

## Story playground (if scaffolded with `:include-story? true`)

If you generated this app with `:include-story? true`, the scaffold
ships a [Story](https://github.com/day8/re-frame2/tree/main/tools/story)
playground — a Storybook-class component workbench — alongside the
live counter. Two surfaces share `#app`, one at a time, via hash
routing on the same `:app` build:

- **`#/`** — the live counter app.
- **`#/stories`** — the Story shell: every registered story / variant /
  workspace, with the `:script` play steps and their `:rf.assert/*`
  checkpoints and the canonical tag set.

`npx shadow-cljs watch app` serves both — no second build. Visit
<http://localhost:8280/#/stories> for the playground and
<http://localhost:8280/#/> for the live app; reloading either hash
lands on the right surface.

The story registrations live in `src/{{nested-dirs}}/stories.cljs`
(emitted next to `events.cljs` / `subs.cljs` / `views.cljs`). It uses
the four shipped `reg-*` macros — `reg-story`, `reg-variant`,
`reg-tag`, `reg-workspace` — referencing the counter's existing
event / sub / view ids. Add more `reg-variant` / `reg-tag` /
`reg-decorator` / `reg-mode` calls there as your app grows.

### Eliding Story from your production bundle (opt-in)

Story body code can elide entirely under an `:advanced` release build
via the `re-frame.story.config/enabled?` closure-define — every `reg-*`
form collapses to `nil`, `mount-shell!` short-circuits, and Closure
DCE drops the registration-side namespace graph. **This elision is
opt-in, not automatic.** The define defaults to `true`, and the
scaffolded `shadow-cljs.edn` does **not** set it — so a plain
`npx shadow-cljs release app` SHIPS the Story shell, the `#/stories`
route, and every registration to production (a bundle-size cost and a
possible internal-state exposure). To elide Story from release, add a
`:release` block to the `:app` build in `shadow-cljs.edn`:

```clojure
;; shadow-cljs.edn :builds :app
:release {:compiler-options
          {:closure-defines {re-frame.story.config/enabled? false}}}
```

Leave it out (keep `enabled?` true in release) only when you want a
release-flavoured Story build for visual regression.

Story is Reagent-only in this template release; UIx + Helix variants
follow once Story's adapter coverage matches Reagent's. If you did
**not** opt in, none of the above is present and you can ignore this
section.

## Build for release

```sh
npx shadow-cljs release app
```

Production output lands in `resources/public/js/`. Serve `resources/public/`
from any static host.

## Production hardening

The generated `resources/public/index.html` ships a `<meta http-equiv="Content-Security-Policy" ...>`
tag so an unconfigured static host still gets a baseline. **Prefer
setting CSP as a real response header** on your server — meta-tag CSPs
are evaluated late, some directives are ignored entirely when delivered
via `<meta>` (notably **`frame-ancestors`**, which is why the meta tag
omits it), and a meta CSP can be removed by an upstream proxy that
rewrites HTML.

**The shipped meta CSP is development-flavoured.** It sets
`style-src 'self' 'unsafe-inline'` because the generated views use
inline `:style` props and the default-on Xray devtools surface injects
`<style>` blocks and inline styles — a strict `style-src 'self'` would
emit CSP violations on the first page and block Xray styling. The meta
tag also drops `frame-ancestors` (browsers ignore it from `<meta>`).

For production, serve the **stricter** policy below as a response
header. It tightens three things relative to the dev meta tag:
**drops `'unsafe-inline'`** (do this only after you have externalised
all inline styles — move the views' `:style` props to `css/app.css`
classes, and either drop the dev-only Xray preload from your release
build [it already is — see "In-app devtools" above] or serve Xray under
a nonce); **drops `ws: wss:`** (no dev hot-reload in production); and
**adds `frame-ancestors 'none'`** — which is where anti-clickjacking
protection actually takes effect (NOT the meta tag):

```
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'
X-Content-Type-Options: nosniff
Referrer-Policy: strict-origin-when-cross-origin
```

If you have not externalised inline styles, keep `style-src 'self'
'unsafe-inline'` in the production header too — but prefer a nonce or
hash over wholesale `'unsafe-inline'` for a public deployment.

**`connect-src` — tighten it in production.** The shipped
`index.html` meta CSP sets `connect-src 'self' ws: wss:` so
shadow-cljs's hot-reload websocket connects in **development** (it can
route to a different port than the page, which `'self'` alone would
block). Production has no hot-reload websocket, so the production
header above drops `ws: wss:` and pins `connect-src` to `'self'`. If
your app calls a **cross-origin API** (XHR/fetch to a different
origin), `'self'` will block it — add that origin explicitly, e.g.
`connect-src 'self' https://api.example.com`. The strict default
silently forbids any cross-origin request you haven't whitelisted, so
add API origins here as you wire them up.

`script-src` stays strict (`'self'`, no `'unsafe-inline'`) in both the
dev meta tag and the production header — the scaffold has no inline
`<script>`. If you add a CDN, embed in an iframe, inline a `<script>`,
or load an analytics snippet, **explicitly widen the policy** for that
origin / hash — don't drop it to `'unsafe-inline'` wholesale. (For
inline *styles*, see the `style-src` note above: the dev meta tag
allows them; externalise to `css/app.css` to tighten the production
header.)

Example **nginx** server block:

```nginx
location / {
  add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'" always;
  add_header X-Content-Type-Options "nosniff" always;
  add_header Referrer-Policy "strict-origin-when-cross-origin" always;
}
```

Example **Caddy** Caddyfile snippet:

```caddyfile
header {
  Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"
  X-Content-Type-Options "nosniff"
  Referrer-Policy "strict-origin-when-cross-origin"
}
```

Always deploy the `release` build (not `watch`) to production — the
release build sets `:closure-defines {goog.DEBUG false}` (next
section), strips the Xray preload, and ships the minified bundle.

## Production builds

`shadow-cljs release` already sets `:closure-defines {goog.DEBUG false}` —
asserts compile away, dev-only branches drop, the bundle shrinks. If
you want to verify or override it, the explicit form is:

```clojure
;; shadow-cljs.edn :builds :app
:release {:compiler-options {:closure-defines {goog.DEBUG false}}}
```

For production timing data, flip re-frame2's performance instrumentation
on at compile time. Add this block alongside `:closure-defines` (it's
off by default — turning it on costs a few cycles per dispatch):

```clojure
;; :compiler-options {:closure-defines {re-frame.performance/enabled? true}}
```

## REPL workflow

Once `npx shadow-cljs watch app` is running, connect your editor to
the shadow-cljs nREPL:

- **Calva (VS Code):** `Calva: Connect to a Running REPL` →
  `shadow-cljs` → pick the `:app` build.
- **CIDER (Emacs):** `M-x cider-jack-in-cljs` → `shadow-cljs` →
  `:app`.
- **Cursive (IntelliJ):** add a `Clojure REPL → Remote` config
  pointing at the nREPL port shadow-cljs prints on startup.

shadow-cljs prints the nREPL port on its first line of output
(default 7002 if you set `:nrepl {:port 7002}` in `shadow-cljs.edn`,
or a randomly-assigned port otherwise). Once connected, evaluate
forms in `dev/scratch.cljs` to drive the running app from the REPL.

## Run tests

```sh
npx shadow-cljs compile test
node out/node-test.js
```

The `:test` build in `shadow-cljs.edn` is a `:node-test` target that
picks up every `*_test.cljs` namespace under `test/` and runs it via
`cljs.test`. The scaffold ships one such file —
`test/{{nested-dirs}}/events_test.cljs` — exercising
`:counter/initialise` and `:counter/increment` against the plain-atom
reactive substrate (no DOM needed). Add more `*_test.cljs` files
alongside it as your app grows.

**Windows note:** shadow-cljs's `node_modules` resolution can rely on
filesystem symlinks. Symlink creation on Windows requires either
**Developer Mode** enabled (Settings → For developers → Developer
Mode) or an admin-elevated shell. If `npm test` fails with a symlink-
related error, that's the fix.

## Project layout

```
.
├── deps.edn                 ; Clojure deps — re-frame2 + the {{substrate}} adapter
├── shadow-cljs.edn          ; CLJS build config — watch / release / test targets
├── package.json             ; npm deps (react, react-dom, shadow-cljs runtime)
├── README.md                ; this file
├── .gitignore               ; CLJS standard
├── .editorconfig            ; 2-space indent, LF, trim trailing whitespace
├── .clj-kondo/
│   └── config.edn           ; linter config (empty by default)
├── .cljfmt.edn              ; cljfmt formatter config — `clojure -M:cljfmt check` / `fix`
├── lefthook.yml             ; pre-commit format + lint hook (see lefthook.dev/installation)
├── .github/workflows/
│   └── ci.yml               ; baseline GitHub Actions CI — JDK 21 + Clojure CLI + Node 22 + `npm test`
├── resources/public/
│   ├── index.html           ; host page; loads shadow-cljs's compiled output
│   └── css/app.css          ; minimal plain CSS — body / button / h1
├── src/{{nested-dirs}}/
│   ├── core.cljs            ; entry point — mounts the root view
│   ├── events.cljs          ; `:counter/initialise`, `:counter/increment` handlers
│   ├── schema.cljs          ; whole-app-db Malli schema + `reg-app-schema`
│   ├── subs.cljs            ; `:counter/value` subscription
│   └── views.cljs           ; the counter view ({{substrate}})
├── test/{{nested-dirs}}/
│   └── events_test.cljs     ; substrate-agnostic event-handler tests
└── dev/
    ├── user.clj             ; JVM-side `(user/refresh)` entry
    └── scratch.cljs         ; REPL scratch namespace for `(rf/dispatch …)` experiments
```

## What's in the scaffold

A minimal **counter app** demonstrates the re-frame2 dataflow end-to-end:

- `:counter/value` is the slice of app-db (default `0`).
- `:counter/increment` is the event handler — pure `(update db ... inc)`.
- `:counter/value` is the subscription that derives the displayed number.
- `views.cljs` renders a `<button>` that dispatches the increment event
  plus a `<span>` that reads the subscription.

This is the same counter walked through in
[the re-frame2 guide](https://github.com/day8/re-frame2/tree/main/docs/guide) —
the state key is intentionally feature-scoped (`:counter/value`, not a
bare `:count`) so generated applications start with AI-readable,
non-colliding app-db slices.

## Best practices baked into the scaffold

re-frame2 ships with distinctive postures on **error visibility**,
**typed boundaries**, and **HTTP failure handling**. The starter app
demonstrates each one inline so new apps inherit the conventions
without ceremony.

### Errors are events too

Every error inside the dispatch pipeline — schema violation, handler
exception, sub exception, fx exception, drain-depth overflow — emits a
structured trace event with `:op-type :error` (per
[Spec 009 §Error contract](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md)).
The framework does NOT decide what the user sees; an **app-level
listener projects errors onto the UI**.

The scaffold registers a default error sink at the top of `events.cljs`:

```clojure
(trace-tooling/register-listener!
  ::error-sink
  (fn [trace-event]
    (when (= :error (:op-type trace-event))
      (js/console.error "[your-app]"
                        (:operation trace-event)
                        (clj->js (:tags trace-event))))))
```

(The listener API lives in `re-frame.trace.tooling`, not `re-frame.core`
— CLJS production bundles DCE the tooling namespace wholesale, so the
`rf/...` alias for these fns is JVM-only. See Spec 009 §JVM-only
aliases.)

The sink surfaces every error to the console — silent regressions are
impossible to miss. Replace `js/console.error` with whatever your app
does for user-visible errors (toast, error boundary, Sentry / Rollbar /
etc.).

The same-key registration form means hot-reload re-runs without
leaking listeners; the listener registry elides in production builds
(per [Spec 009 §`register-listener!`](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md#the-listener-api)).

Note: re-frame2 also ships `reg-error-projector` for **server-side
rendering** (SSR), where the projector maps internal trace events to
HTTP-response shapes. That projector is a separate surface from the
client-side error sink above — see
[Spec 011 §SSR](https://github.com/day8/re-frame2/blob/main/spec/011-SSR.md)
if you're rendering server-side.

### Typed app-db boundaries

`schema.cljs` registers a **whole-app-db schema** at the empty path
`[]`. The framework validates every write against the registered
schemas; a non-conforming write rolls back the `:db` effect and emits
`:rf.error/schema-validation-failure`. The error then flows through
the error sink above — wrong writes are caught at the boundary, not
N renders downstream.

App-db schemas are **frame-local** (EP-0002, Spec 002 §Frame target
resolution): `reg-app-schema` targets a frame and the runtime never
synthesises one from absence. Registering with no established scope and
no explicit `:frame` raises `:rf.error/no-frame-context` — so a bare
`reg-app-schema` call at namespace-load time would throw. The scaffold
therefore puts the registration in a `register-schema!` fn that
`core/init` calls **after** `reg-frame` makes the app's `:rf/default`
frame live, inside a `(rf/with-frame :rf/default …)` scope:

```clojure
(def CounterDb
  [:map {:closed true}
   [:counter/value :int]])

;; in schema.cljs — NOT a load-time side-effect
(defn register-schema! []
  (rf/reg-app-schema [] {:schema CounterDb}))

;; in core.cljs — called under a live frame scope at boot
(rf/with-frame :rf/default
  (schema/register-schema!))
```

If you prefer not to wrap a scope, name the frame explicitly in the
metadata map under `:frame`:
`(rf/reg-app-schema [] {:schema CounterDb :frame :rf/default})`.

Closed maps catch typos (`:countr/value` → schema rejection); open
maps admit new keys during development. The starter uses closed —
flip to `{:closed false}` if you want laxer registration while you
sketch.

For multi-feature apps, register **per-feature schemas at their
prefix path** rather than one giant root schema — same frame-scoped
contract (run under a frame scope, or pass an explicit `:frame`):

```clojure
;; under a (rf/with-frame :rf/default …) scope, or pass :rf/default last
(rf/reg-app-schemas
  {[:cart]                  CartSlice
   [:cart :items]           [:vector CartItem]
   [:auth]                  AuthSlice
   [:auth :login-form]      FormSlice})
```

Per-feature schemas compose with the root schema; both validate. Full
detail: [Spec 010 §`app-db` schemas — path-based](https://github.com/day8/re-frame2/blob/main/spec/010-Schemas.md#app-db-schemas--path-based).

Schema validation elides automatically under `:advanced`
`goog.DEBUG=false` builds — registrations stay in source but cost
nothing in production hot paths.

### Privacy / egress classification — declare it on the frame

re-frame2 makes runtime state highly observable: one trace stream feeds
Xray (default-on in dev — see above), Story (ships in release if you opt
in), the dev error sink (logs to the console), and any off-box monitor
or export you wire later. That observability is a productivity feature
**and** a privacy surface — an auth token, a session id, a partner
credential, or a multi-megabyte upload can cross a framework-mediated
observation boundary and land in a record that is shown in a panel,
handed to an LLM tool, or shipped off-box. The classification model
([Spec 015 §The three-layer model](https://github.com/day8/re-frame2/blob/main/spec/015-Data-Classification.md))
exists so the framework can redact / elide those values at egress.

**App-db schemas validate shape; they do NOT classify egress.** The
`schema.cljs` schema above says what app-db *looks like*; it says nothing
about which paths are sensitive or large. Egress classification for
**durable app-db paths** is owned by the **frame**, declared on
`reg-frame` — never re-attached to a schema. (Spec 015 deliberately keeps
one route per fact: frame config owns durable app-db classification;
schemas own shape — see
[§Schemas describe shape, not durable app-db egress policy](https://github.com/day8/re-frame2/blob/main/spec/015-Data-Classification.md#schemas-describe-shape-not-durable-app-db-egress-policy).)

The starter counter has nothing sensitive. As soon as you add auth/API
data to app-db — say an `[:auth]` slice with a token, or a `[:documents]`
slice that holds a large upload — classify it from the event that writes
it: return the `:sensitive` / `:large` commit-plane effects alongside
`:db` (EP-0025). Frame-local HTTP carrier names stay on the frame config.

```clojure
;; events.cljs — classify durable app-db paths from the event that writes
;; them. The :sensitive / :large effects ride alongside :db.
(rf/reg-event :auth/init
  (fn [{:keys [db]} _]
    {:db        (assoc db :auth {})
     ;; Durable app-db paths whose VALUES must never reach an observation
     ;; surface. They project to :rf/redacted at every egress boundary.
     :sensitive [[:auth :token]
                 [:auth :refresh-token]]
     ;; Durable app-db paths too large to ship whole — they project to
     ;; :rf.size/large-elided (sensitive wins over large).
     :large     [[:documents :upload]]}))

;; core.cljs — frame-local HTTP carrier names (headers / query params) that
;; carry secret material on this app's requests stay on the frame config.
(rf/reg-frame :rf/default
  {:sensitive {:http {:headers      ["Authorization"]
                      :query-params ["api_key"]}}})
```

Real values still flow through events → handlers → app-db → subs → views
unchanged — projection runs **only** at the observation boundary, and the
whole dev trace stream rides the `goog.DEBUG` gate, so there is no
happy-path runtime cost.

For **HTTP response bodies**, classification rides the request's
`:decode` schema, not the frame (the body is a transient payload owned by
its request) — see the HTTP section below.

### HTTP — closed failure-category set and a single `:on-failure` branch

`events.cljs` ships a commented-out `:rf.http/managed` handler showing
the canonical call shape per
[Spec 014 §`:rf.http/managed`](https://github.com/day8/re-frame2/blob/main/spec/014-HTTPRequests.md).
Uncomment and adapt when your app starts talking to a backend.

**The `(:rf/reply msg)` / `{:kind :success|:failure …}` payload in the
example is managed-HTTP public compatibility sugar — not the
framework-wide managed-async model.** Every managed-HTTP completion
lowers internally onto the framework's
[uniform reply envelope](https://github.com/day8/re-frame2/blob/main/spec/Managed-Effects.md#the-uniform-reply-envelope)
(Managed-Effects property 9; the rationale record is
[EP-0011](https://github.com/day8/re-frame2/blob/main/docs/EP/EP-0011-uniform-async-reply-envelope.md)):
one canonical reply map with a single **closed** `:status`, `:value` /
`:error`, `:work/id`, and `:completed-at`. The HTTP outcomes map onto it
as:

| HTTP compat `:kind` | Envelope `:status` | Carries |
|---|---|---|
| `:success` | `:ok` | `:value` (decoded body), `:work/id`, `:completed-at` |
| `:failure` (any `:rf.http/*`) | `:error` | `:error` map with `:kind`, `:work/status` (`:failed` / `:timed-out`) |
| abort | `:cancelled` | `:cancelled? true`, `:cancel/reason`, `:rf.http/aborted` under `:error` |
| superseded / late | `:stale` | **never delivered to your app target** — stale replies are suppressed before dispatch |

`:rf.http/managed` accepts `:on-success` / `:on-failure` (and the
co-located `(:rf/reply msg)` form) as sugar that lowers to the framework
target `:rf/reply-to`; both reshape the canonical reply back into the
public `{:kind …}` payload, so the event shape in the exemplar is exactly
what your handler sees. Full lowering contract:
[Spec 014 §Lowering onto the uniform reply envelope](https://github.com/day8/re-frame2/blob/main/spec/014-HTTPRequests.md#lowering-onto-the-uniform-reply-envelope).
**Do not read the `{:kind …}` payload as the general async model** — any
non-HTTP managed-async surface you build (machines, resources, timers)
reports completion through the same envelope's `:status` / `:value` /
`:error` / `:completed-at` directly, with mandatory stale suppression.

**Response-body classification — `:decode :auto` is the simple,
non-sensitive case.** The exemplar decodes with `:decode :auto`, which is
fine for a public counter response. A real response body that carries
secrets (login / refresh tokens, partner credentials, opaque session
material) or is large should use a **`:decode` schema** and classify
per-slot with `:sensitive?` / `:large?` Malli props — the body is a
transient payload owned by its request's `:decode` schema (not the frame;
see the Privacy section above). An **unschematized body is
whole-sensitive (fail-closed)**: off-box production traces and captures
omit it entirely unless a classified projection is explicitly requested.
See
[Spec 015 §HTTP response bodies](https://github.com/day8/re-frame2/blob/main/spec/015-Data-Classification.md#http-response-bodies).

Two distinctive postures land in the example:

1. **Closed `:retry :on` set.** Only the *retryable* subset of the
   `:rf.http/*` failure-category vocabulary is admissible:
   `:rf.http/transport`, `:rf.http/http-5xx`, `:rf.http/timeout`. The
   non-retryable categories (`:rf.http/cors`,
   `:rf.http/decode-failure`, `:rf.http/http-4xx`, `:rf.http/aborted`,
   `:rf.http/accept-failure`) are rejected at fx-call time with
   `:rf.error/http-bad-retry-on` — misuse fails fast at the dispatch
   site, not silently across the request's lifetime.

2. **Single `:on-failure` branch, project on the kind.** Exactly one
   `:on-failure` dispatch fires per request (even with retry — per
   Spec 014 §Retry × `:on-failure` semantics). Branch on
   `(:kind (:failure reply))` to project each `:rf.http/*` category
   onto the UI-facing message:

   ```clojure
   (case (:kind failure)
     :rf.http/transport      "Network unavailable."
     :rf.http/http-5xx       "Server error — try again later."
     :rf.http/timeout        "Server took too long to respond."
     :rf.http/decode-failure "Bad response from server."
     ...)
   ```

   Body-conditional retry (e.g. honour a `:retry-after` header) is
   **out of scope** for `:retry` — that's semantic, not transport.
   Lift it into a state machine per Spec 014 §Boundary — transport vs
   semantic retry.

To enable HTTP, add `day8/re-frame2-http` to `deps.edn` and require
`[re-frame.http.managed]` at app boot (the side-effecting load that
registers `:rf.http/managed`).

### Naming conventions

The scaffold follows the **`:domain/action` keyword shape** throughout
— `:counter/value` for the app-db slice, `:counter/initialise` and
`:counter/increment` for events, `:counter/value` for the sub. Same
shape for views and fx; the **id prefix identifies the feature**.

Two rules cover most of the surface:

- **Reserved namespaces are framework-owned.** Anything under `:rf/*`,
  `:rf.<area>/*` (e.g. `:rf.http/*`, `:rf.machine/*`), and
  `:rf.error/*` belongs to the framework. App ids live under your own
  domain prefix — never `:rf` / `:rf.*`.

- **Per-feature `:rf.<area>/*` patterns for fx.** A feature with
  prefix `:cart` namespaces its events under `:cart/...` and
  `:cart.<area>/...`; its subs under `:cart/...`; its app-db slice at
  `[:cart]`; its schemas under `[:cart]` paths; its private fx under
  `:cart.<sub-area>/...` (e.g. `:cart.persistence/save`). A feature
  does NOT reach into another feature's slice directly — it goes
  through the other feature's subs (to read) and dispatches the other
  feature's events (to write).

Full normative catalogue:
[spec/Conventions.md](https://github.com/day8/re-frame2/blob/main/spec/Conventions.md)
— reserved namespaces, fx-id sub-namespaces, reserved app-db keys,
and the feature-modularity prefix convention.

## Next steps

- Read the [the re-frame2 guide](https://github.com/day8/re-frame2/tree/main/docs/guide).
- Browse the
  [Pattern Specification](https://github.com/day8/re-frame2/tree/main/spec)
  for the contract.
- Check out the [worked examples](https://github.com/day8/re-frame2/tree/main/examples).

## Migrating from re-frame v1?

If you are porting an existing re-frame app, see:

- [`migration/from-re-frame-v1/README.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md)
  — the v1→v2 migration contract.
- The [`re-frame-migration`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-migration)
  skill — Claude Code workflow that walks the port mechanically and
  flags the cases that need a human decision.

v2 introduces a per-frame architecture, schema-typed app-db, managed
HTTP effects, and the `reg-view` macro on the Reagent adapter (which
both defines a view symbol and registers it under `(keyword *ns* name)`,
auto-injecting `dispatch` / `subscribe` as frame-scoped lexical
bindings). The migration skill walks you through each of those v1→v2
shape changes mechanically.

## Future: skill install

re-frame2 ships first-class Claude Code skills (under [`skills/`](https://github.com/day8/re-frame2/tree/main/skills))
that pair-program against a running app, drive scaffolds, and walk
the v1→v2 migration. Once those publish to the Claude Code skills
marketplace this template will install them into your project on
scaffold; until then, clone the `re-frame2` repo and copy the
relevant skill directory into your `.claude/skills/`.

## License

Generated by `day8/re-frame2-template` — see the template repo for
licensing of the scaffold. Your project's code is yours; pick whatever
license you like.
