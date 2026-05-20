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
`{{namespace}}.core/init` (the `:devtools/after-load` hook in
`shadow-cljs.edn`). The entry fn is **idempotent**: re-frame2's
`rf/init!` accepts being called repeatedly, the registrar
re-registers in place, and `dispatch-sync [:counter/initialise]`
re-seeds app-db.

Under the hood re-frame2 guarantees a **per-frame re-init contract**:
each call to `init!` on a frame snapshots the registrar, re-installs
the adapter, and resets the frame's app-db to a known state — your
handlers and subs come back wired to the new code without leaking
state from the previous build. Add new `reg-event-db` / `reg-sub` /
`reg-view` forms and they show up live; rename or remove a handler
and the next reload drops the old registration.

## In-app devtools (Causa)

`shadow-cljs.edn` wires `day8.re-frame2-causa.preload` into
`:devtools/preloads` on the `:app` build — the scaffold ships Causa
**on by default** for development. `resources/public/index.html`
includes the `[data-rf-causa-host]` left layout column, so Causa
auto-opens beside your app once `rf/init!` runs. Press
**Ctrl+Shift+C** to hide/show it: per-epoch dispatch log, app-db diff,
causality graph, time-travel scrubber.
Release builds drop the preload automatically (shadow only runs
preloads under `watch` / `compile`, never `release`).

Stack traces in dev also get click-to-source via the
`:rf.trace/trigger-handler` preload — clicking a frame in the dev
console jumps straight to the offending form.

## Build for release

```sh
npx shadow-cljs release app
```

Production output lands in `resources/public/js/`. Serve `resources/public/`
from any static host.

## Production hardening

The generated `resources/public/index.html` ships a `<meta http-equiv="Content-Security-Policy" ...>`
tag so an unconfigured static host still gets a strict default-safe
baseline. **Prefer setting the same policy as a real response header**
on your server — meta-tag CSPs are evaluated late (some directives are
ignored entirely, e.g. `frame-ancestors`) and can be removed by an
upstream proxy that rewrites HTML.

The scaffold loads only same-origin JS/CSS and has no inline
script/style, so the strict default policy holds without weakening:

```
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'
X-Content-Type-Options: nosniff
Referrer-Policy: strict-origin-when-cross-origin
```

If you add a CDN, embed in an iframe, inline a `<style>` block, or
load an analytics snippet, **explicitly widen the policy** for that
origin / hash — don't drop it to `'unsafe-inline'` wholesale.

Example **nginx** server block:

```nginx
location / {
  add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'" always;
  add_header X-Content-Type-Options "nosniff" always;
  add_header Referrer-Policy "strict-origin-when-cross-origin" always;
}
```

Example **Caddy** Caddyfile snippet:

```caddyfile
header {
  Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"
  X-Content-Type-Options "nosniff"
  Referrer-Policy "strict-origin-when-cross-origin"
}
```

Always deploy the `release` build (not `watch`) to production — the
release build sets `:closure-defines {goog.DEBUG false}` (next
section), strips the Causa preload, and ships the minified bundle.

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
(trace-tooling/register-trace-listener!
  ::error-sink
  (fn [trace-event]
    (when (= :error (:op-type trace-event))
      (js/console.error "[your-app]"
                        (:operation trace-event)
                        (clj->js (:tags trace-event))))))
```

(The listener API lives in `re-frame.trace.tooling`, not `re-frame.core`
— CLJS production bundles DCE the tooling namespace wholesale, so the
`rf/...` alias for these fns is JVM-only. Per Spec 009 §JVM-only
aliases — rf2-qwm0a.)

The sink surfaces every error to the console — silent regressions are
impossible to miss. Replace `js/console.error` with whatever your app
does for user-visible errors (toast, error boundary, Sentry / Rollbar /
etc.).

The same-key registration form means hot-reload re-runs without
leaking listeners; the listener registry elides in production builds
(per [Spec 009 §`register-trace-listener!`](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md#the-listener-api)).

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

```clojure
(def CounterDb
  [:map {:closed true}
   [:counter/value :int]])

(rf/reg-app-schema [] CounterDb)
```

Closed maps catch typos (`:countr/value` → schema rejection); open
maps admit new keys during development. The starter uses closed —
flip to `{:closed false}` if you want laxer registration while you
sketch.

For multi-feature apps, register **per-feature schemas at their
prefix path** rather than one giant root schema:

```clojure
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

### HTTP — closed failure-category set and a single `:on-failure` branch

`events.cljs` ships a commented-out `:rf.http/managed` handler showing
the canonical call shape per
[Spec 014 §`:rf.http/managed`](https://github.com/day8/re-frame2/blob/main/spec/014-HTTPRequests.md).
Uncomment and adapt when your app starts talking to a backend.

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
`[re-frame.http-managed]` at app boot (the side-effecting load that
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
