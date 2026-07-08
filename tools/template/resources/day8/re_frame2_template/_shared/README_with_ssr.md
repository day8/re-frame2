# {{name}}

[![Built with re-frame2](https://img.shields.io/badge/built%20with-re--frame2-blue.svg)](https://github.com/day8/re-frame2)
[![Substrate]({{substrate-badge-url}})](https://github.com/day8/re-frame2/tree/main/implementation/adapters/{{substrate}})
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A new **server-side-rendered** [re-frame2](https://github.com/day8/re-frame2)
application, scaffolded from `day8/re-frame2-template` (deps-new) with
`:include-ssr? true`.

Substrate: **{{substrate}}**.

The app is written **once** and runs **twice**: the JVM renders the page to
HTML so the first paint arrives ready-made, and the browser then *hydrates*
that same HTML and takes over, fully interactive. Because both runs share the
same events / subscription / schema / view, the registration root is a single
`.cljc` file (`src/{{nested-dirs}}/core.cljc`) — the `#?(:clj …)` / `#?(:cljs …)`
reader conditionals carve out only the few spots that genuinely differ (the JVM
render path vs the browser mount). See Spec 011 — SSR & Hydration.

## Run the development build

SSR runs two processes side by side: the **shadow-cljs watcher** compiles the
client bundle (`main.js`), and the **JVM render server** (Ring/Jetty) renders
each request to HTML and serves that bundle so the page can hydrate.

```sh
npm install
npx shadow-cljs watch app        # terminal 1 — builds + watches the client bundle
```

```sh
clojure -X:server                # terminal 2 — the SSR / Jetty host
```

Then open <http://127.0.0.1:8030> in your browser.

The `:server` alias (`deps.edn`) runs `{{namespace}}.server/-main`, which
installs the JVM-side SSR render adapter and boots Jetty on `127.0.0.1:8030`.
The first request renders the counter on the server; view source and you will
see the fully-formed markup plus an embedded hydration payload — no blank shell,
no client round-trip before first paint.

Rebuild the client on save with the watcher above; to pick up a change to the
**server** side (`server.clj`), restart `clojure -X:server` (or
`(require '{{namespace}}.server :reload)` from a connected REPL).

## Server render + client hydration — how the two halves meet

`src/{{nested-dirs}}/core.cljc` holds the whole app, shared by both runtimes:

- **`CounterDb`** — the whole-app-db Malli schema, kept as a plain value so it
  can be registered against whichever frame the entry point stands up (the
  throwaway per-request server frame, or the fixed client frame). `reg-app-schema`
  is frame-local (EP-0002), so it is attached under a live frame scope, never at
  namespace load.
- **`:counter/initialise` / `:counter/increment`** — the shared events.
- **`:counter/value`** — the shared subscription.
- **`counter-app`** (`^{:rf/id :app/root}`) — the shared root view. `reg-view`
  injects a frame-bound `subscribe` / `dispatch` resolved at render time, which
  is exactly what lets one view run twice: on the JVM a deref reads the current
  value; on the client the same deref registers a reaction.

The **server entry** (`server-init-events` + `root-view`) is what
`src/{{nested-dirs}}/server.clj` hands to `re-frame.ssr.ring/ssr-handler`. The
handler owns the per-request lifecycle — frame create → drain → response read →
render → frame destroy — and gensyms the per-request frame id internally, so the
schema is attached via the `:ssr/register-schema` step that rides *first* in
`:initial-events` (it runs under the ambient per-request frame the handler is
constructing, so the whole-app-db schema is live before `:rf/server-init` seeds
the counter).

The **client entry** (`init`, `:cljs` branch) boots through `ssr/hydrate!`,
which rolls three ordered steps into one:

1. **READ** the embedded `__rf_payload` `<script>`. A malformed payload fails
   closed (nothing replaces app-db); a missing one just means "nobody
   server-rendered this" — a plain first load.
2. **HYDRATE** — `dispatch-sync [:rf/hydrate payload]` *before* the first render,
   swapping in the server's app-db slice and stashing the server's render hash.
3. **VERIFY** — hash the first render tree and compare it to the server's. On
   disagreement the runtime raises `:rf.ssr/hydration-mismatch`, carrying the
   `data-rf-render-hash` tripwire the server stamped on the root element.

## HTTP responses and error projection

Server-side effects live under the reserved `:rf.server/*` fx namespace — an
event handler can `:rf.server/set-status`, `:rf.server/set-header` /
`:rf.server/append-header`, `:rf.server/set-cookie` / `:rf.server/delete-cookie`,
and `:rf.server/redirect` / `:rf.server/safe-redirect`. The runtime accumulates
these in a framework-private per-request side-channel (never in app-db, so they
never ride the hydration payload to the client) and the Ring host materialises
them into the wire response.

When a view or subscription **throws** during the server render, the internal
trace event (rich, monitor-bound) is projected to a **sanitised public-error**
shape written to the HTTP response — the two surfaces have different audiences.
The default projector maps `:rf.error/no-such-handler` → 404, client-input schema
failures → 400, and everything else → a locked generic 500. Override it per app
with `reg-error-projector` and name it on the frame:

```clojure
(rf/reg-error-projector :myapp/public-error
  (fn [trace-event]
    (case (:operation trace-event)
      :auth/unauthorised {:status 401 :code :unauthorised
                          :message "Sign in to continue" :retryable? false}
      {:status 500 :code :internal-error
       :message "Something went wrong" :retryable? false})))

;; on the per-request frame's :ssr config (server.clj's ssr-handler opts)
:ssr {:public-error-id   :myapp/public-error
      :dev-error-detail? false}
```

Naming a `:public-error-id` that is **not registered** is surfaced as a
`:rf.error/sanitised-on-projection` diagnostic (reason `:missing-projector`) — a
recognised-but-unhonourable config never silently degrades your intended 404/400
mappings into a 500. See [Spec 011 §Server error projection](https://github.com/day8/re-frame2/blob/main/spec/011-SSR.md).

## Build for release

```sh
npx shadow-cljs release app      # minified client bundle → resources/public/js/
clojure -X:server                # the same JVM host serves the release bundle
```

`shadow-cljs release` sets `:closure-defines {goog.DEBUG false}` (asserts and
dev-only branches compile away). For production, put a real static-asset server
/ CDN in front of `/main.js` and reverse-proxy the SSR route; the Jetty host
here is the zero-ceremony dev/test default. Any Ring-shaped host (HttpKit,
Pedestal, Reitit-ring) consumes the same `ssr-handler` without change.

## Run tests

```sh
clojure -M:test
```

The `:test` alias runs the headless JVM gate `test/{{nested-dirs}}/ssr_test.clj`.
It boots the SSR adapter, drives the **real** `ssr-ring/ssr-handler` construction
(the exact path `server.clj` serves), renders the `:app/root` view to a string
with `render-to-string`, and asserts the HTML content plus the structural
`data-rf-render-hash` marker the client compares against — no React / JSDOM, it
runs end-to-end on the JVM. It also proves the per-request schema attach is live
by rejecting a deliberately schema-violating commit on that same production path.

## Project layout

```
.
├── deps.edn                 ; Clojure deps — re-frame2 + ssr + ssr-ring + jetty; :server + :test aliases
├── shadow-cljs.edn          ; CLJS build config — the :app client bundle
├── package.json             ; npm deps (react, react-dom, shadow-cljs runtime)
├── README.md                ; this file
├── .gitignore .editorconfig .cljfmt.edn .clj-kondo/config.edn
├── lefthook.yml             ; pre-commit format + lint hook
├── .github/workflows/
│   └── ci.yml               ; baseline GitHub Actions CI
├── resources/public/
│   ├── index.html           ; static host page (the SSR server renders its own shell per request)
│   ├── css/app.css          ; minimal plain CSS
│   └── js/                  ; shadow-cljs writes main.js here; server.clj's :static-root serves it
├── src/{{nested-dirs}}/
│   ├── core.cljc            ; THE app — events / sub / schema / view, shared JVM render + CLJS hydration
│   └── server.clj           ; the Ring / Jetty SSR host (:server alias entry point)
├── test/{{nested-dirs}}/
│   └── ssr_test.clj         ; headless JVM SSR gate (render-to-string + render-hash + schema enforcement)
└── dev/
    ├── user.clj             ; JVM-side (user/refresh) entry
    └── scratch.cljs         ; REPL scratch namespace
```

The per-slice sources a non-SSR scaffold emits are **folded into `core.cljc`** on
this branch — the events, subscription, schema, and view are written once so the
server and client share exactly the same registrations.

Xray devtools ship in the client `:app` build's dev preloads (`shadow-cljs.edn`)
and attach when the hydrated client runs; release builds drop the preload
automatically.

## What's in the scaffold

A minimal **counter app** demonstrates the SSR dataflow end-to-end:

- The server seeds `:counter/value` (default `0`) via `:rf/server-init`, renders
  the counter to HTML, and embeds the hydration payload.
- The client reads that payload, hydrates into it (no flash, no re-fetch), and
  takes over — the `+1` button then dispatches `:counter/increment` live.
- `:counter/value` is the subscription that derives the displayed number on both
  sides.

The state key is intentionally feature-scoped (`:counter/value`, not a bare
`:count`) so generated applications start with AI-readable, non-colliding app-db
slices. This is the same counter walked through in
[the re-frame2 guide](https://github.com/day8/re-frame2/tree/main/docs/core).

## Best practices baked into the scaffold

re-frame2 ships distinctive postures on **error visibility**, **typed
boundaries**, **privacy / egress**, and **HTTP failure handling**. They apply to
any re-frame2 app — SSR or SPA.

### Errors are events too

Every error inside the dispatch pipeline — schema violation, handler exception,
sub exception, fx exception, drain-depth overflow — emits a structured trace
event with `:op-type :error` (per
[Spec 009 §Error contract](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md)).
On the **server** the SSR error projector (above) maps those trace events onto
sanitised HTTP-response shapes; on the **client**, an app-level listener
(`re-frame.trace.tooling/register-listener!`) projects them onto the UI. The two
are separate surfaces — the projector owns the wire response, the client listener
owns what the interactive UI shows — but both start from the same structured
error event.

### Typed app-db boundaries

`core.cljc` registers a **whole-app-db schema** (`CounterDb`) at the empty path
`[]`. The runtime validates every write against the registered schemas; a
non-conforming write rolls back the `:db` effect and emits
`:rf.error/schema-validation-failure`. App-db schemas are **frame-local**
(EP-0002): the scaffold attaches the schema under a live frame scope
(`register-schema!` — the server via the `:ssr/register-schema` initial-event, the
client via the explicit-frame arity), never at namespace load. Closed maps
(`{:closed true}`) catch typos; schema validation elides automatically under
`:advanced` `goog.DEBUG=false` release builds.

For multi-feature apps, register **per-feature schemas at their prefix path**
rather than one giant root schema. Full detail:
[Spec 010 §`app-db` schemas — path-based](https://github.com/day8/re-frame2/blob/main/spec/010-Schemas.md#app-db-schemas--path-based).

### Privacy / egress classification — declare it on the frame

re-frame2 makes runtime state highly observable (Xray, the trace stream, off-box
monitors) — a productivity feature **and** a privacy surface. **App-db schemas
validate shape; they do NOT classify egress.** Egress classification for durable
app-db paths is owned by the **frame** (declared on `reg-frame`), never
re-attached to a schema. As soon as you add auth/API data to app-db, classify it
from the event that writes it — return the `:sensitive` / `:large` commit-plane
effects alongside `:db` (EP-0025). On SSR the `:payload` allowlist on
`ssr-handler` is a second privacy gate: ship only the slices the client needs in
the hydration payload (this scaffold ships `[:counter/value]`), so server-only
secrets never cross to the wire. Full model:
[Spec 015 §The three-layer model](https://github.com/day8/re-frame2/blob/main/spec/015-Data-Classification.md).

### HTTP — closed failure-category set and a single `:on-failure` branch

When your app talks to a backend, add `day8/re-frame2-http` and use
`:rf.http/managed` (per
[Spec 014](https://github.com/day8/re-frame2/blob/main/spec/014-HTTPRequests.md)).
Every managed-async completion delivers the framework's
[uniform reply envelope](https://github.com/day8/re-frame2/blob/main/spec/Managed-Effects.md#the-uniform-reply-envelope)
— one canonical reply map with a single **closed** `:status` (`:ok` / `:error` /
`:cancelled` / `:stale`), `:value` / `:error`, `:work/id`, and `:completed-at`.
`:on-success` / `:on-failure` are pure ROUTING sugar over the one direct reply
target; both receive the identical canonical map. Only the *retryable* subset of
the `:rf.http/*` failure categories is admissible under `:retry :on`; branch on
`(:kind (:error reply))` to project each category onto a UI-facing message.

### Naming conventions

The scaffold follows the **`:domain/action` keyword shape** throughout — the id
prefix identifies the feature. Reserved namespaces (`:rf/*`, `:rf.<area>/*`,
`:rf.error/*`) are framework-owned; app ids live under your own domain prefix. A
feature reads another feature's state through its subs and writes it through its
events — never by reaching into another slice directly. Full normative
catalogue: [spec/Conventions.md](https://github.com/day8/re-frame2/blob/main/spec/Conventions.md).

## Next steps

- Read [the re-frame2 guide](https://github.com/day8/re-frame2/tree/main/docs/core).
- Study the canonical SSR worked example this scaffold mirrors,
  [`examples/capabilities/ssr`](https://github.com/day8/re-frame2/tree/main/examples/capabilities/ssr).
- Browse the [Pattern Specification](https://github.com/day8/re-frame2/tree/main/spec)
  — in particular [Spec 011 — SSR & Hydration](https://github.com/day8/re-frame2/blob/main/spec/011-SSR.md).

## Migrating from re-frame v1?

If you are porting an existing re-frame app, see
[`migration/from-re-frame-v1/README.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md)
and the [`re-frame-migration`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-migration)
skill — a Claude Code workflow that walks the port mechanically and flags the
cases that need a human decision.

## License

Generated by `day8/re-frame2-template` — see the template repo for licensing of
the scaffold. Your project's code is yours; pick whatever license you like.
