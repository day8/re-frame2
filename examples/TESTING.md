# Testing the examples

> Reference for everyone touching the example tree and its browser-test harness.

This document explains the two test surfaces that exercise the examples, the
contracts each surface relies on, and the conventions a new example must follow
to avoid leaking side effects into its neighbours.

It complements [`README.md`](README.md) (which describes the example layout)
and the per-example `README.md` files (which describe what each example
demonstrates).

CI tiering is defined in [`../TESTING.md`](../TESTING.md). Example browser
gates are not part of the always-on PR spine; they run when example/browser
surfaces change and in the scheduled/manual expensive workflow.

## The surfaces

| Command                            | What it runs                                                                                                        | Where the orchestrator lives                                                                                                |
|------------------------------------|---------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| `npm run test:browser`             | The shadow-cljs `:browser-test` bundle — every `*-cljs-test` namespace, including example wrappers (`re-frame.nine-states-cljs-test`, `re-frame.realworld-cljs-test`, ...). All of them load into a single Chromium page. | [`implementation/scripts/serve-and-run-browser-tests.cjs`](../implementation/scripts/serve-and-run-browser-tests.cjs)        |
| `npm run test:examples`            | The three adapter-level smokes at `implementation/adapters/{reagent,uix,helix}/testbed/spec.cjs` — mount + dispatch + assert per substrate. The `examples/` tree itself is test-free; this orchestrator drives the adapter smokes only. | [`scripts/serve-and-run-examples-tests.cjs`](scripts/serve-and-run-examples-tests.cjs)                                       |
| `npm run test:examples-compile`    | **Compile-coverage gate.** `shadow-cljs compile` over EVERY declared standalone `:examples/*` build (the list is derived from `shadow-cljs.edn`, so a new example build is swept automatically). Fails on any compile-time error AND on any warning (a typo'd init-fn surfaces as an `:undeclared-var` warning, and `compile` exits 0 on warnings). NOT a Playwright/runtime check — no `spec.cjs` involved. | [`implementation/scripts/check-examples-compile.cjs`](../implementation/scripts/check-examples-compile.cjs) |

The two surfaces are independent:

- `test:browser` is **one bundle, one page, every test**. Every example
  namespace that is `:require`'d by a `*-cljs-test` wrapper is loaded into
  the same JS runtime and runs against a single shared DOM. This is where
  ns-load side effects bite.
- `test:examples` is **N bundles, N pages, N specs** — one per adapter
  testbed. Each adapter owns its own runtime; no cross-adapter
  interaction is possible.
- `test:examples-compile` is **compile-only** — it never serves a page or
  runs a spec. It exists to close a coverage gap, described next.

## Compile-coverage gate (`test:examples-compile`)

Most `:examples/*` builds are standalone shadow-cljs `:browser` targets
with their own `:init-fn` but **no `spec.cjs`** (the `examples/` tree is
test-free — see below). Before this gate, only the counter trio
(`:examples/counter` / `-uix` / `-helix`) was compiled by any automated
check (release-built by `test:bundle-isolation`). Every other standalone
example — `login-uix`, `dashboard-uix`, `login-helix`,
`process-monitor-helix`, and the rest — was declared but compiled by
nothing, so a namespace / `:init-fn` / `:require` / schema / machine /
substrate-form regression in any of them shipped **green** until a human
manually opened the page (rf2-0vav5.1 + rf2-cn6kc.1).

`test:examples-compile` closes that gap with the lightest possible check:

- It **derives** the build list from `shadow-cljs.edn` (`:examples/*`
  build ids) rather than hardcoding it, so a newly-declared example build
  is swept the moment it lands — no second edit, no drift. `shadow-cljs.edn`
  is read-only here (it is a hot-zone file).
- It runs a single `shadow-cljs compile` (not `release`) over the whole
  set; shadow shares the compilation cache across builds, so the cost is
  far below N independent compiles, and `compile` does no Closure externs
  prebuild (no shared-`externs.zip` race).
- It **fails on warnings as well as errors.** `shadow-cljs compile` exits
  0 even when a build emits warnings, and `:warnings-as-errors` only bites
  on `release` — so a typo'd `:init-fn` (an `:undeclared-var` warning)
  would otherwise ship green. The gate parses each build's summary line and
  fails on any non-zero warning count. All example builds compile with zero
  warnings today, so this is a clean, real-teeth bar.

This is **not** a Playwright spec and does **not** add anything under
`examples/` — it preserves the test-free examples policy. The teeth (the
derived list can't silently under-count; a warning turns the gate red) are
pinned by
[`implementation/scripts/check-examples-compile.test.cjs`](../implementation/scripts/check-examples-compile.test.cjs),
which runs in the always-on `test:script-policy` suite. The compile gate
itself runs in the `cljs-browser` CI job (the `cljs_browser` changed
surface, which fires on both `examples/**` source edits and
`shadow-cljs.edn` build-decl changes).

## Server-ownership contract (`test:browser`)

The browser-test orchestrator at
[`implementation/scripts/serve-and-run-browser-tests.cjs`](../implementation/scripts/serve-and-run-browser-tests.cjs)
positively identifies the static-asset server it owns. The contract is:

1. **Per-run nonce.** On startup the orchestrator writes a 128-bit hex
   token to `<asset-root>/.rf-harness-token` *before* spawning
   `http-server`. The path is published as soon as `http-server` starts
   serving the directory.
2. **Token-gated readiness.** The readiness loop is not satisfied by a
   200 on `/`. It is satisfied only when `GET /.rf-harness-token` returns
   the exact nonce the orchestrator wrote. A foreign server bound to the
   same port — including a stale `http-server` child from a previous
   aborted run — will return a different body (or 404) and the
   orchestrator will refuse to run the suite against it.
3. **Bounded teardown.** On exit (success, failure, `SIGINT`, `SIGTERM`,
   or uncaught error) the orchestrator kills the `http-server` PID it
   spawned (and only that PID) and removes the token file. The handlers
   are idempotent.

Failure modes the contract eliminates:

- Running tests against an unrelated `http-server` that happens to be
  bound to the same port.
- Running tests against a stale child from a previous aborted run that
  re-bound the port between the orchestrator's `isPortFree()` check and
  its own `spawn`.
- Leaving stray `http-server` processes alive after teardown.

Port selection (still honoured): `BROWSER_TEST_PORT` first, then the
default `8021`, then an OS-chosen free port if both are busy. The
ownership token rides whichever port is selected.

## Example mount-isolation convention

Every browser-test wrapper namespace `:require`s its example's `core`
namespace (e.g. `re-frame.realworld-cljs-test` requires `realworld.core`).
Because the `:browser-test` shadow-cljs target produces **one** bundle
loaded into **one** page, every example `core` namespace co-required by
any wrapper is loaded into the same JS runtime. They all see the same
DOM.

The page generated by shadow-cljs's `:browser-test` target has an
otherwise empty `<body>`. The orchestrator patches it to include a
single hidden `<div id="app">` mount point (so a regression that does
re-introduce ns-load mounts still doesn't crash the runner before the
cljs.test summary prints). But the convention examples follow is:

> **Do not perform DOM mount side effects at namespace-load time.**
> Defer `create-root` (or your substrate's equivalent) to the example's
> `run` fn.

Each example's `run` fn is its bundle entry point, wired as the
`:init-fn` of the example's shadow-cljs build target in
[`implementation/shadow-cljs.edn`](../implementation/shadow-cljs.edn)
(e.g. `:examples/realworld` → `:init-fn realworld.core/run`). shadow-cljs
emits a call to that fn as the bundle's bootstrap, resolving the symbol
*inside* the compiled bundle — so the fn needs **no** `^:export` metadata.
`^:export` only matters for symbols called by name from outside the bundle
(hand-written HTML/JS via `window....`); the example host pages just load
`main.js` and let the `:init-fn` fire, so no example needs it.

The shape every Reagent example uses:

```clojure
;; ns-load: produce no DOM side effects. The atom holds the React root
;; once `run` has materialised it.
(defonce react-root (atom nil))

;; Bundle entry point — wired as this example's :init-fn in
;; implementation/shadow-cljs.edn. No ^:export needed (see above).
(defn run []
  (rf/init! adapter)
  ;; ... per-example app boot ...
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root [root-view])))
```

This is enforced informally — by code review — rather than by a test.
The anti-pattern to avoid is:

```clojure
;; ANTI-PATTERN: mount side effect runs at ns-load.
(defonce react-root
  (when (exists? js/document)
    (rdc/create-root (js/document.getElementById "app"))))
```

Under `test:examples` (one ns per page) this is harmless. Under
`test:browser` (every ns in one page) every example namespace that
`:require`s into the test bundle would race `create-root` calls on the
same shared `#app` element — leaking example-A's mount into example-B's
tests and emitting `createRoot is being called on the same container
twice` warnings. The lazy-mount pattern keeps ns-load DOM-side-effect
free; `run` only fires when the example is actually being driven as a
standalone page by `test:examples`.

The same lazy-mount shape is also used by the UIx and Helix examples
(see `examples/uix/login_uix/core.cljs` and friends) — the substrate
differs, the contract does not.

## Event-id and subscription-id namespacing

The cross-example bundle also shares the re-frame registries (handlers,
subs, machines, ...). Examples avoid collisions by **prefixing every
registered id with the example's name**:

- `realworld.core` registers events under `:auth/login`, `:articles/load`,
  `:comments/submit`, `:settings/save`, ... — never bare keywords like
  `:login` that a sibling example might also register.
- `nine-states.core` registers under `:nine-states.app/initialise`,
  `:nine-states.demo/load`, `:new-todo/submit`, ... — again, prefixed.

The same applies to schemas, machines, frames, and any other registry
the example writes to. The wrapper test namespaces install a
`test-support/make-reset-runtime-fixture` per-test fixture, so each test
runs against a fresh frame — but the *registrations* themselves are
shared across the whole bundle. Prefix discipline is what keeps them
non-overlapping.

## Adding a new browser-test example

A new example added to the `test:browser` surface needs:

1. **Namespace-load side effects only register, never mount.** All DOM
   creation lives in the example's `run` fn (the bundle's `:init-fn`).
   Per the convention above.
2. **A prefixed id namespace.** Pick a stem (your example's folder name
   in `kebab-case` is a good default) and stick to it for every
   `reg-event-*`, `reg-sub`, `reg-machine`, `reg-frame`, schema, etc.
3. **A wrapper test ns** under
   `implementation/adapters/<substrate>/test/re_frame/<example>_cljs_test.cljs`
   ending in `-cljs-test` so the `:browser-test` build picks it up via
   its `ns-regexp`. The wrapper installs
   `test-support/make-reset-runtime-fixture` and drives the example's
   headless fixtures (defined under `examples/<substrate>/<example>/test/`).
4. **A shadow-cljs source-path entry** in
   [`implementation/shadow-cljs.edn`](../implementation/shadow-cljs.edn)
   for the example's `test/` directory so its fixture namespaces resolve.

After these are in place, `npm run test:browser` will compile and run
the new example's wrapper alongside every other wrapper, in the same
bundle, in the same page, with the same shared `#app` mount point. If
mount-isolation discipline holds, the new example will not affect any
sibling's tests.

## Adding a new example to `test:examples`

Per the test-free examples policy the `examples/` tree
itself carries no Playwright specs; `test:examples` drives only the
three adapter testbeds at
`implementation/adapters/{reagent,uix,helix}/testbed/`. **Do NOT add
a `*.spec.cjs` under `examples/`.**

The example set is declared **once** in
[`scripts/examples-filter.cjs`](scripts/examples-filter.cjs) — each entry
pairs a shadow-cljs build id with its `index.html` source, its
`out/examples/` staging dir, and the `spec.cjs` it runs. Both the
orchestrator (compile + stage) and the Playwright runner (spec
selection) import that manifest and call its shared `selectEntries`, so a
narrow `--filter`/`EXAMPLES_FILTER` value selects the *identical* set in
both phases regardless of whether it is build-id-shaped
(`adapters/reagent-testbed`, `reagent-testbed`) or path-shaped
(`adapters/reagent/testbed`, `reagent/testbed`). The runner also
reconciles the manifest against the `spec.cjs` files on disk and fails
loudly on drift. To add a smoke, append an entry there (build + htmlSrc +
outDir + specPath) and add the build to `implementation/shadow-cljs.edn`.

A new standalone `:examples/*` build needs **no** test wiring to get
compile coverage: `test:examples-compile` derives its build list from
`shadow-cljs.edn`, so declaring the build there is enough — the next CI
run compiles it (and fails on any compile error or warning). See
[Compile-coverage gate](#compile-coverage-gate-testexamples-compile)
above. You only touch `examples-filter.cjs` when the build also needs a
Playwright *runtime* smoke (the three adapter testbeds), which the
test-free examples policy reserves for adapter-level surfaces.

If a new framework contract needs end-to-end browser coverage that
the existing gates (`test:cljs`, `test:xray-feature-gate`,
`test:bundle-isolation`, `test:perf-bundle`, `test:examples-compile`,
mcp-conformance) don't already provide, extend the appropriate gate — or,
for a genuinely new cross-cutting surface, add a top-level
`testbeds/<surface>/` with its own `spec.cjs`.

The `(defonce react-root (atom nil))` mount-isolation shape above is
still the convention for any example's `core.cljs` so it can be
required by a `test:browser` wrapper later without rewriting.
