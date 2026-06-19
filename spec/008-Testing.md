# Spec 008 — Testing

> Forward-compatible with [Spec 007 — Stories](007-Stories.md), which builds on this infrastructure.
>
> The testing primitives in this Spec — JVM-runnable handler invocation, headless sub computation, pure machine transitions — are *pattern-level* contracts, not CLJS-only conveniences. Other-language implementations supply equivalent headless-evaluation surfaces. The CLJS-specific framework adapters (`cljs.test`/`clojure.test`/`kaocha`/`re-frame-test` compatibility) are reference-implementation details. Pure hiccup → string emission via `render-to-string` (per Spec 011) is JVM-runnable; React-driven view mounting is CLJS-only.

## Abstract

Testing is a [first-class principle](000-Vision.md#goals) (Goal 11) in re-frame2. This Spec details the **testing surface** — the concrete API, patterns, and adapter shape that re-frame2 ships so users can write small, fast, isolated tests without ceremony or global-state pollution.

The testing surface is built entirely from foundation primitives in [002-Frames.md](002-Frames.md): `make-frame` / `destroy-frame!`, `with-frame`, `dispatch-sync` with the opts map, per-frame and per-call overrides, the public registrar query API, drain semantics, and pure machine transition functions. This Spec doesn't introduce new framework primitives — it documents how to compose the existing ones into a test-friendly experience.

## Normative surface

The concrete API for testing, satisfying [Goal 11 (Deterministic, testable runtime)](000-Vision.md#goals). The surface lives across **three CLJS-reference namespaces** — every public def in the table below has a single canonical home, and the three-namespace split is the inventory's single-source-of-truth:

| Namespace | Role | Surfaces |
|---|---|---|
| `re-frame.core` | Production primitives, also the testing entry points | `make-frame`, `destroy-frame!`, `reset-frame!`, `with-frame`, `dispatch-sync`, `with-fx-overrides`, `app-db-value`, `snapshot-of`, `subscribe-once`, `sub-topology`, `compute-sub`, `machine-transition` |
| `re-frame.test-support` | Test-only fixture machinery + test-flavoured helpers (runtime-state axis — see [§Audience-split](#audience-split--re-frametest-support-vs-re-frametest-helpers)) | `snapshot-registrar`, `restore-registrar!`, `with-fresh-registrar`, `make-reset-runtime-fixture`, `dispatch-sequence`, `assert-path-equals`, `assert-db-equals`, `poll-until` |
| `re-frame.test-helpers` | View-assertion helpers (hiccup-walk + `testid` authoring) + single-frame e2e fixture (view-tree axis — see [§Audience-split](#audience-split--re-frametest-support-vs-re-frametest-helpers)) | `expand-tree`, `find-by-attr` / `find-all-by-attr` / `find-by-attr-prefix`, `find-by-testid` / `find-all-by-testid` / `find-by-testid-prefix`, `attrs`, `children`, `text-content`, `extract-handler`, `invoke-handler`, `testid`, `with-app-fixture`, `expect-text`, `wait-until` |

`re-frame.test-support` does **not** re-export from `re-frame.core` — a test file requires both namespaces (`[re-frame.core :as rf]` for primitives, `[re-frame.test-support :as ts]` for fixture machinery and helpers). View-assertion test files additionally `:require [re-frame.test-helpers :as th]`. The split is deliberate: `re-frame.core` carries surfaces that compose into production code paths as well as tests; `re-frame.test-support` is a require-gated test-only convenience surface; `re-frame.test-helpers` is the view-assertion surface used only by tests (per [§View-assertion helpers](#view-assertion-helpers-re-frametest-helpers)).

### Audience-split — `re-frame.test-support` vs `re-frame.test-helpers`

The two test-only namespaces ship in the same artefact and address the same audience (test authors) but split on the **assertion axis**:

| Namespace | Axis | Reach |
|---|---|---|
| `re-frame.test-support` | **Runtime state** — registrar, frames, `app-db`, drain, in-flight requests | Fixture machinery (`make-reset-runtime-fixture`, `with-fresh-registrar`), event sequencing (`dispatch-sequence`), state assertions (`assert-path-equals`, `assert-db-equals` — the `assert-*-equals` fn-family mirroring the `:rf.assert/*` event-family used inside a Story `:script` block), bounded-deadline polling (`poll-until`) |
| `re-frame.test-helpers` | **View tree** — hiccup data, `:data-testid` selectors, attached handlers | Hiccup walk (`find-by-testid`, `text-content`, `extract-handler`), handler invocation (`invoke-handler`), single-frame e2e fixture trio (`with-app-fixture`, `expect-text`, `wait-until`), authoring helper (`testid`) |

A test that exercises events / subs / machines reaches `re-frame.test-support`. A test that asserts what the user sees in the rendered view reaches `re-frame.test-helpers`. A test that does both `:require`s both. The names carry the role, not the audience; the v1 community noun "test-helpers" referred to the broader surface, so v1 muscle-memory may look for fixture machinery under the wrong namespace — the runtime-state vs view-tree split is the axis that disambiguates.

### Adapter-aware test helpers — `flush-views!`

Some test helpers are **per-adapter** by design. The React-based adapters (`re-frame.adapter.reagent`, `re-frame.adapter.uix`, `re-frame.adapter.helix`) each ship a `flush-views!` fn that wraps React's `act()` so tests dispatching against a mounted tree can settle pending React effects before reading the DOM. The function NAME is shared across adapters (substrate uniformity); the entry point is **per-adapter-require**, not centralised through `re-frame.test-support`:

```clojure
(:require [re-frame.adapter.reagent :as reagent-adapter])
;; ...
(reagent-adapter/flush-views!)
```

This is intentional per [Spec 006 §Revertibility constraints — What an adapter MUST NOT do](006-ReactiveSubstrate.md#what-an-adapter-must-not-do): the adapter-dependency direction is `adapter → core`, never the reverse. `re-frame.test-support` ships in core and cannot reach into adapter namespaces without inverting the direction. Test code knows its adapter at compile time (the same require that boots the adapter at app boot, repeated in the test ns) — the `(reagent-adapter/flush-views!)` shape is structurally identical to how production code calls `(reagent-adapter/render ...)`.

The plain-atom adapter (JVM, SSR, headless) does NOT ship `flush-views!` — there is no React tree to settle. Tests targeting the plain-atom adapter use `dispatch-sync` (already drain-to-fixed-point) and read app-db / hiccup directly.

| Need | API |
|---|---|
| Per-test frame fixture | `(rf/make-frame opts)` / `(rf/destroy-frame! f)` |
| Scoped REPL/test block | `(rf/with-frame :frame-id body...)` (pin) *or* `(rf/with-new-frame [sym expr] body...)` (eval-bind-run-destroy) — see [§`with-frame` and `with-new-frame`](#with-frame-and-with-new-frame) |
| Synchronous test trigger | `(rf/dispatch-sync event)` or `(rf/dispatch-sync event opts)` |
| Stub fx (per-call) | `(rf/dispatch-sync ev {:fx-overrides {:my-app/http stub-fn}})` |
| Stub fx (per-frame) | `(rf/reg-frame :test-frame {:fx-overrides {…}})` |
| Replace interceptor | `{:interceptor-overrides {:logger nil}}` per-call or per-frame |
| Add interceptor (recorder) | `(rf/reg-frame :test-frame {:interceptors [event-recorder]})` |
| Assertion: read app-db | `(rf/app-db-value :test-frame)` |
| Assertion: read snapshot | `@(rf/subscribe [:rf/machine :auth/state-machine])` (or `(get-in (rf/runtime-db-value f) [:rf.runtime/machines :snapshots :auth/state-machine])` for storage-layer assertions) |
| Pure machine simulation | `(machine-transition definition snapshot event)` — no frame needed |
| Machine cleanup on destroy | `(rf/destroy-frame! f)` — disposes sub-cache, stops router, clears overrides |
| Static sub-graph inspection | `(rf/sub-topology)` |
| Sub computation against an `app-db` | `(rf/compute-sub query-v db)` — query-v is `[:sub-id arg1 arg2]`, JVM-runnable |
| Test-flavoured helpers | `(ts/dispatch-sequence events)` — chained `dispatch-sync`; `(ts/assert-path-equals path expected)` — clojure.test-aware assertion mirroring the `:rf.assert/path-equals` event used inside a Story `:script` block (see [007 §Play functions](007-Stories.md#play-functions)); `(ts/assert-db-equals expected-db)` — companion full-db form (no event analog). All three ship with `re-frame.test-support`. |
| Single-frame e2e fixture | `(th/with-app-fixture {:install f :root-view v} :frame-id body...)` — create + bind frame, run `:install`, stash `:root-view`, destroy on exit. Pair with `(th/expect-text testid expected)` and `(th/wait-until pred-or-testid expected)` for the two-line single-frame test pattern. |

### `with-frame` and `with-new-frame`

Two sibling macros — split per concern, the macro name telegraphs the intent. Both are normative and both required of every host. The canonical definition lives in [002 §`with-frame` and `with-new-frame`](002-Frames.md#with-frame-and-with-new-frame); this section gathers the test-surface usage notes.

#### `with-frame` — pin to an existing frame

```clojure
(rf/with-frame :scratch
  (rf/dispatch-sync [:init])
  @(rf/subscribe [:status]))
```

Pins `*current-frame*` to the supplied frame id for the body's dynamic extent. The frame is **not** created or destroyed by the macro — the keyword is used as-is. Used when the frame already exists (registered via `reg-frame` or created earlier via `make-frame`), e.g. shared fixtures across multiple `deftest` blocks, REPL sessions. Rejects a vector argument at compile time (`:rf.error/with-frame-vector-form`); use `with-new-frame` for eval-bind-run-destroy.

#### `with-new-frame` — create, use, destroy

```clojure
(rf/with-new-frame [binding-sym expr] body...)
```

Evaluates `expr` (typically `(rf/make-frame opts)`), binds the result to `binding-sym` (so the body can refer to it for `app-db-value`, `dispatch-sync` opts, etc.), sets that frame as the implicit `*current-frame*` for the body's dynamic extent (so `dispatch-sync` and `subscribe` inside the body resolve to it without needing `{:frame ...}`), and on body exit (success or exception) calls `destroy-frame!` on whatever was bound. Modelled on `with-open`. Used when the frame's lifetime is exactly the body — per-test fixtures, devcard widgets, REPL sessions wanting guaranteed teardown. Rejects a keyword argument at compile time (`:rf.error/with-new-frame-keyword-form`); use `with-frame` to pin.

Both macros are part of the normative test surface; tests, fixtures, and helper macros MAY freely use either, and hosts MUST support both.

### JVM-runnable boundary (authoritative)

Every entry in the table above is JVM-runnable, with the exceptions listed below — this is the single authoritative statement of the test-surface's JVM/CLJS split, per [C2](000-Vision.md#c2-cross-platform-jvm-interop-preserved):

- ✓ `make-frame` / `destroy-frame!` / `reset-frame!` / `with-frame` / `with-new-frame`
- ✓ `dispatch-sync` and the entire dispatch pipeline (router, drain, interceptors)
- ✓ All `reg-event-*` handler invocation
- ✓ Override application (`:fx-overrides`, `:interceptor-overrides`, `:interceptors`)
- ✓ `app-db` mutation and snapshot reading
- ✓ Cofx injection
- ✓ `machine-transition` (pure function)
- ✓ `compute-sub` (sub computation against an `app-db` value)
- ✓ Public registrar queries (`registrations`, `frame-meta`, `sub-topology`, etc.)
- ✓ **Hiccup → HTML string emission** (per [011](011-SSR.md)) — pure function over hiccup data, JVM-runnable. Snapshot tests, SSR conformance tests, and visual-regression diffs all run headlessly.
- ✓ **Hiccup-walk** (`re-frame.test-helpers`, per [§View-assertion helpers](#view-assertion-helpers-re-frametest-helpers)) — `find-by-testid`, `text-content`, `invoke-handler` and siblings. Pure walkers over hiccup data; expand fn-components and Form-3 class components without instantiating React. The reagent-slim Form-3 discriminator is a CLJS-only branch (reader-conditional); the JVM sees the same hiccup tree.
- ✗ React-actually-mounting (mount lifecycle, `:on-click` event firing into the real DOM, scroll events) — CLJS-only.
- ✗ Reactive subscription *tracking* (auto-subscribe-on-deref, dispose lifecycle) — CLJS-only. Subscription *computation* (running the body against an `app-db` value) is JVM-runnable via `compute-sub`.

In practice: every business-logic test runs on the JVM. View *content* tests (does the rendered hiccup contain the expected text? does the structure match the schema?) also run on the JVM via `render-to-string` or hiccup-walk — `render-to-string` for HTML-markup assertions, hiccup-walk for structure / handler assertions. Only tests that exercise actual React mounting, click events firing through DOM listeners, or scroll-position-style interactive behaviour need a CLJS runtime. The split is clean and SSR-friendly.

## Test fixture lifecycle patterns

### Pattern 1 — anonymous fixture per test

The most common shape. Each test creates a frame, runs assertions, tears down.

```clojure
(rf/reg-event :auth/init-idle (fn [_ _] {:db {:auth/state :idle}}))

(deftest auth-flow
  (let [f (rf/make-frame {})]
    (try
      (rf/dispatch-sync [:auth/init-idle] {:frame f})   ;; seed via a setup dispatch
      (rf/dispatch-sync [:auth/login-pressed] {:frame f})
      (is (= :validating (get-in (rf/app-db-value f) [:auth :state])))
      (finally
        (rf/destroy-frame! f)))))
```

### Pattern 2 — `with-new-frame` for tighter blocks

For tests that don't need explicit teardown logic, `with-new-frame` handles the lifecycle:

```clojure
(deftest auth-flow
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:auth/init-idle])             ;; seed via a setup dispatch (uses :frame f)
    (rf/dispatch-sync [:auth/login-pressed])         ;; uses :frame f via the binding
    (is (= :validating (get-in (rf/app-db-value f) [:auth :state])))))
```

`with-new-frame` evaluates the bound expression, binds the frame for the body's duration, dispatch-syncs/subscribes inside the body resolve to that frame via the dynamic-var tier of the resolution chain, and the frame is destroyed on exit (success or exception).

### Pattern 3 — named fixture across many tests

For test groups that share setup, register a named test frame once and reset between tests:

```clojure
(use-fixtures :each
  (fn [test-fn]
    (rf/reg-frame :test-fixture {:on-create [:auth/init-idle]})   ;; create once
    (try
      (test-fn)
      (finally
        (rf/reset-frame! :test-fixture)))))                          ;; reset between tests

(deftest one-thing
  (rf/dispatch-sync [:auth/login-pressed] {:frame :test-fixture})
  (is (= :validating (get-in (rf/app-db-value :test-fixture) [:auth :state]))))
```

`reset-frame!` (per [002 §reset-frame!](002-Frames.md#reset-frame--full-replace-opt-in)) clears `app-db` to `{}` and re-fires `:on-create`. State is fresh between tests; the registration cost is paid once.

### Pattern 4 — pure machine simulation (no frame)

For testing state machine transitions, skip the frame entirely:

```clojure
(deftest auth-machine-transitions
  (let [snap-1 {:state :idle :data {}}
        [snap-2 effects] (rf/machine-transition auth-machine-table snap-1
                                                [:auth/login-pressed])]
    (is (= :validating (:state snap-2)))
    (is (= [[:dispatch [:auth/check-credentials]]] effects))))
```

`machine-transition` is a pure function — no frame, no `app-db`, no router. Test the logic in isolation; integration tests cover the wiring. See [005 §Testing](005-StateMachines.md#testing) for the full three-level test pyramid (pure `machine-transition`, unregistered handler fn from `make-machine-handler`, registered in test frame).

### Pattern 5 — single-frame e2e fixture

The dominant shape for app-developer end-to-end tests: one frame, one install hook (the app's `install!` fn that registers events / subs / views), one root view, and an assertion that the rendered text matches after dispatching. Patterns 1–3 carry the long-form fixture; `with-app-fixture` is the two-line shorthand:

```clojure
(deftest counter-increments
  (th/with-app-fixture {:install   counter/install!
                        :root-view counter/main}
                       :test-app
    (rf/dispatch-sync [:counter/inc])
    (rf/dispatch-sync [:counter/inc])
    (th/expect-text :counter-display "2")))
```

The macro:

1. Creates the named frame (or gensym's an anonymous `:rf.frame/*` id when the frame-id positional arg is omitted).
2. Binds `*current-frame*` to that frame for the body's dynamic extent — `dispatch-sync` and `subscribe` inside the body resolve to it without any explicit `{:frame ...}` opt.
3. Calls the `:install` fn (zero-arg) inside the frame's scope. Typical body: `reg-event` / `reg-sub` / `reg-view` calls that the test relies on. Registrations land in the global registrar; pair with `re-frame.test-support/make-reset-runtime-fixture` (or `with-fresh-registrar`) to roll them back between tests.
4. Stashes the `:root-view` fn in `*current-root-view*` so `expect-text` / `wait-until`'s testid form can find it without an explicit tree argument. `:root-view-args` (default `[]`) rides into `*current-root-view-args*` for views that take a props map.
5. Runs `body`.
6. In a `finally`, destroys the frame regardless of whether `body` returned normally or threw — no leaked frames across tests.

`opts-map` keys (all optional): `:install`, `:root-view`, `:root-view-args`, `:frame-config` (the record-config map seated onto the fixture frame — `:on-create`, `:fx-overrides`, `:interceptor-overrides`, `:interceptors` and the rest of the frame-shape contract per [Spec 002 §`reg-frame`](002-Frames.md#reg-frame--atomic-create-and-register-and-the-canonical-metadata-grammar); these are record-config keys, so the fixture seats them via `reg-frame` / the advanced `re-frame.frame/make-frame`, not the EP-0023 object constructor `rf/make-frame`).

The companion helpers:

- **`(th/expect-text testid expected)`** — 2-arity: render the fixture-stashed root view, walk for `:data-testid testid`, assert `(text-content node) = expected`. Reports `:pass` / `:fail` via `clojure.test/is`. The 3-arity `(expect-text tree testid expected)` takes an explicit hiccup tree — useful for view-only tests that don't need a full fixture. `testid` may be a keyword (`:counter-display`, coerced via `name`) or a string.
- **`(th/wait-until pred-or-testid)`** / **`(th/wait-until testid expected)`** / **`(th/wait-until pred opts)`** / **`(th/wait-until testid expected opts)`** — bounded-deadline poll for async-stable assertions. The view-test counterpart to `re-frame.test-support/poll-until`: same per-platform shape (JVM-synchronous returning the truthy value; CLJS-async returning a `js/Promise` that resolves on success and rejects on timeout). The testid form polls the fixture-stashed root view until the text matches; the predicate form polls an arbitrary fn. `opts`: `:timeout-ms` (default 2000), `:interval-ms` (default 5), `:label`. Use for async event flows (HTTP, scheduled events, machine `:after` transitions) that drain past `dispatch-sync`. Timer-semantics sleeps (grace-period elapse, throttle/debounce window) keep their explicit sleep and annotate the intent locally — `wait-until` is for *settles*, not *windows*.

When NOT to use Pattern 5:

- **Multi-frame setups** (Xray, Story, cross-frame tests) — Pattern 1 / 2 with explicit `rf/with-frame` calls each frame is clearer; the fixture stash is single-slot by design.
- **Tests that don't render** — the install + frame lifecycle of Pattern 5 is overkill for pure-event tests. Reach for `(rf/with-new-frame [f (rf/make-frame opts)] ...)` and skip the view-stash entirely.

### Pattern 6 — pure event-handler simulation (no frame)

Under the one event form ([EP-0018](../docs/EP/EP-0018-one-event-registration.md)), every event handler is a plain **two-arg function** — `(fn [coeffects event-vec] effects-map)` — that takes a coeffects map and the event vector and returns the closed effects map (`{:db … :fx [...]}`) or `nil` (per [002 §The event handler contract](002-Frames.md#the-event-handler-contract)). Because it is just a function, the handler under test can be called **directly** with a hand-built coeffects map and asserted on the returned effects map — no frame, no router, no `app-db`, exactly as Pattern 4 does for `machine-transition`:

```clojure
(defn create-handler                                  ;; the named fn registered with reg-event
  [{:keys [db rf/time-ms]} [_ {:keys [text]}]]
  {:db (assoc-in db [:todos :last] {:text text :created-at time-ms})})

(rf/reg-event :todo/create {:rf.cofx/requires [:rf/time-ms]} create-handler)

(deftest create-handler-pure
  (let [effects (create-handler {:db {} :rf/time-ms 1781078400123}    ;; coeffects in
                                [:todo/create {:text "milk"}])]
    (is (= {:text "milk" :created-at 1781078400123}                   ;; effects out
           (get-in effects [:db :todos :last])))))
```

Hand-build the coeffects map (`:db`, the declared `:rf.cofx/requires` leaves flat, `:event` if the body reads it) and assert on the **returned effects map** — `:db`, `:fx`, any reserved effect. This is the cheapest event-handler test: it bypasses interceptor assembly, drain, and commit entirely, isolating the fold's logic the way Pattern 4 isolates a transition. Reach for it for a pure reducer whose logic is the whole point; reach for the dispatch-driven form ([Pattern 1](#pattern-1--anonymous-fixture-per-test) / [§Asserting on effects](#asserting-on-effects-without-firing-them)) when the test needs the real coeffect assembly, interceptor chain, or `:fx` cascade to run.

### HTTP test surfaces — single namespace

The managed-HTTP artefact (Spec 014) ships its entire test surface in a single namespace, `re-frame.http.test-support`. The previous split — macros in `re-frame.http.managed`, registration gate in `re-frame.http.test-support` — was closed per (audit-of-audits #15): one require, one home, namespace name matches content.

| Namespace | Role | Surfaces |
|---|---|---|
| `re-frame.http.test-support` | **All HTTP test machinery.** Loading the namespace registers `:rf.http/managed-canned-success` and `:rf.http/managed-canned-failure`, defines the stub-routing helpers (`with-managed-request-stubs` / `with-managed-request-stubs*` / `install-managed-request-stubs!` / `uninstall-managed-request-stubs!`), and publishes the matching `:http/install-managed-request-stubs!` / `:http/uninstall-managed-request-stubs!` / `:http/with-managed-request-stubs*` late-bind hooks (the path `re-frame.core` re-exports resolve through). Production code must NOT `:require` this namespace. | `(reg-fx :rf.http/managed-canned-success ...)`, `(reg-fx :rf.http/managed-canned-failure ...)`, `with-managed-request-stubs`, `with-managed-request-stubs*`, `install-managed-request-stubs!`, `uninstall-managed-request-stubs!` (per [API.md rows 283–286](API.md)) |
| `re-frame.http.managed` | **Production fx home only.** Hosts the production-eligible `:rf.http/managed` / `:rf.http/managed-abort` fxs, the middleware family, and the registry helpers. No test surfaces ship here. | `:rf.http/managed`, `:rf.http/managed-abort`, `reg-http-interceptor`, `clear-http-interceptor`, `clear-all-in-flight!`, the privacy denylist surface |

A test reaching for "the HTTP stub helper" — macros, canned-stub fx ids in a `:fx-overrides` map, or the `re-frame.core` stub re-exports — `:require`s `re-frame.http.test-support`. Production / SSR code paths require only `re-frame.http.managed`; the test-support namespace stays out of the require closure so the canned-stub fxs and the stub-family late-bind hooks remain unregistered (classpath absence on JVM/SSR; module-graph DCE on CLJS `:advanced`).

## Fixture-granularity ladder

Four ways to "reset between tests" ship in `re-frame.test-support`. They form a ladder from finest-grained primitives to per-process resets. Reach for the level that matches the test's isolation need — finer is cheaper; coarser is more thorough.

| Level | Surface | Granularity | Use when |
|---|---|---|---|
| **L1** | `snapshot-registrar` + `restore-registrar!` | Atomic primitives. Capture the registrar map; restore later. | A test body manages its own snapshot/restore lifecycle (e.g. a custom fixture, nested brackets, snapshot pinning across multiple `deftest` blocks). |
| **L2** | `with-fresh-registrar` | Bracketed thunk — snapshot, run `body-fn`, restore. | An ad-hoc `deftest` body or a one-off REPL block where the cleanup is exactly the body's exit and nothing else needs resetting (no frames, no flows, no schemas, no adapter). |
| **L3** | `make-reset-runtime-fixture` | Full per-process `:each` fixture. Snapshot/restore the registrar AND reset `frame/frames`, flows, schemas, machines' in-flight timers, routing counters, in-flight HTTP, epoch history, adapter warn-once caches, marks, and trace listeners. Optionally installs an adapter and runs an `:init-fn`. | The default for any test suite that boots an adapter or exercises any per-process state (frames, flows, schemas, machines, routing, http, epoch). This is the standard `(use-fixtures :each (test-support/make-reset-runtime-fixture {:adapter ...}))` shape. |
| **L4** | Direct late-bind reset hooks | Per-artefact reset fns (`:flows/reset-flows!`, `:machines/reset-timers!`, `:epoch/clear-history!`, …) reached via `re-frame.late-bind/get-fn`. | The custom fixture wants to reset exactly one artefact's state — a flows-only fixture that doesn't touch machines, or a regression test pinning a specific reset ordering. L3 already composes these in `:phase`-ordered runs; reach for the hooks directly only when L3's scope is the wrong shape. |

**Granularity guidance.** Default to L3 (`make-reset-runtime-fixture`) unless the test needs less reset (L1 / L2 for ad-hoc bracketing) or finer composition (L4 for one-artefact fixtures). L3 is the cheap default — late-bind no-ops when an artefact is absent, so JVM tests that don't pull flows / schemas / epoch don't pay for those resets. L1 and L2 do NOT reset `frame/frames` or any artefact state; a test using L2 in a suite that mounts an adapter will see cross-test pollution from a sibling test's frames. L4 is for fixture-machinery authors, not test authors.

`make-reset-runtime-fixture` is a **factory**: the call shape is `(make-reset-runtime-fixture opts) → fixture-fn`. Use the returned fn in `(use-fixtures :each ...)`. Contrast `with-fresh-registrar`, which takes a thunk and runs it directly — the names differ deliberately to mark the call-shape axis.

## Hermetic-frame testing — a fresh frame from images + injected capabilities

The fixture-granularity ladder above isolates tests by **snapshot/restore of the process-global registrar** (L1–L3) — capture the one shared registrar, run, restore it. An **image-loaded frame** (per [EP-0023 §Image / §Public API](../docs/EP/EP-0023-image-loaded-frames.md)) offers a different isolation axis: build a frame from exactly the images under test, supply exactly the capabilities those images require, and destroy it on teardown — there is no process-global registrar to clear, because the test never touched one. The frame *is* the hermetic unit: it owns its own resolved image generation (the sealed registration set), app state, subscription cache, and capability map.

```clojure
;; A hermetic test frame — the images under test and exactly the capabilities
;; they need, with no process-global state to clear or restore.
(deftest cart-checkout
  (let [frame (rf/make-frame
                {:id           :test/cart
                 :images       [cart-image]                    ;; the images under test
                 :adapter      :plain-atom
                 :capabilities {:rf.capability/http   fake-http
                                :rf.capability/clock  fixed-clock
                                :rf.capability/random seeded-random}})]
    (try
      ;; The frame is the carried target — an explicit :frame dispatch opt (or
      ;; the carried frame established by scope); there is no ambient default
      ;; (per 002 §Frame target resolution). The image's registrations resolve
      ;; against this frame's resolved generation.
      (rf/dispatch-sync [:cart/checkout] {:frame :test/cart})
      (is (= :complete (get-in (rf/app-db-value :test/cart) [:cart :state])))
      (finally
        (rf/destroy-frame! :test/cart)))))               ;; drop the frame + its state for GC
```

**Capability injection is the explicit dependency seam.** A frame's `:capabilities` map names the runtime services its images depend on — `:rf.capability/http` (request execution), `:rf.capability/clock` (time), `:rf.capability/random` (id/randomness generation), and the rest (per [Runtime-Subsystems §Capability maps](Runtime-Subsystems.md#capability-maps)). A hermetic test injects test doubles for exactly those it needs; image assembly capability-checks first, failing with `:rf.error/image-missing-capability` (naming the missing + supplied capabilities) *before* the frame runs any event if an image `:rf.image/requires` a capability the frame does not supply. This is the **injectable** alternative to process-global stubs discovered by namespace load order.

**The public surface** (all from `re-frame.core`, per [API §Registration](API.md#registration)): `(rf/make-frame {:id … :images [...] :adapter … :capabilities {…}})` builds + registers a hermetic-by-default frame from the named images; `(rf/destroy-frame! frame-or-id)` drops it (runs `:on-destroy`, the machine teardown cascade, and sub-cache disposal). Each frame runs its own resolved image generation, so two frames can hold different handlers for the same id without collision.

**Reconciling with the snapshot/restore ladder.** Both isolate; pick by what the test needs:

| Approach | Isolation mechanism | Reach for it when |
|---|---|---|
| **L1–L3 snapshot/restore** ([§Fixture-granularity ladder](#fixture-granularity-ladder)) | capture + restore the **process-global registrar** and per-process state | the global `reg-*` sugar is the program under test — the common single-app suite. L3 is the cheap default. |
| **Hermetic frame** (this section) | a **fresh frame built from its own images**; nothing process-global is touched | parallel-app / multi-tenant isolation; two adapters in one process (two frames); a test that wants explicit capability injection rather than global stubbing; the fastest hermetic-test payoff. |

A suite that needs neither global cleanup nor capability injection stays on L3; a suite that wants a truly isolated program with injected doubles builds a frame from images.

> **Disambiguation — `make-frame` `:images` vs the `with-app-fixture` `:install` hook.** These are unrelated despite both seating registrations. `rf/make-frame` (this section) **builds a frame from image values** — it assembles a sealed resolved generation (a registration set plus capability requirements) the frame runs in isolation. The `:install` key on [Pattern 5](#pattern-5--single-frame-e2e-fixture)'s `with-app-fixture` opts map is a **zero-arg setup fn** the test author supplies — typically the app's own setup that runs `reg-event` / `reg-sub` / `reg-view` calls into the global registrar. Pattern 5's `:install` is the long-standing single-frame e2e hook; it does not assemble an image or build an isolated frame. A test using Pattern 5's `:install` rolls its registrations back with L3's `make-reset-runtime-fixture`; a test using `rf/make-frame` rolls back by destroying the frame.

## Per-test stubbing patterns

### Stubbing coeffects — supply exact world facts in the dispatch envelope

A handler that folds a **recordable coeffect** — wall-clock time, a generated id, browser location, a storage read (per [002 §Recordable coeffects](002-Frames.md#recordable-coeffects)) — is deterministic with respect to the facts on its causal token. A test makes those facts exact by supplying them in the dispatch envelope's `:rf.cofx` map, **not** by stubbing an fx or overriding an interceptor:

```clojure
(rf/reg-event :todo/create
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [text]}]]
    {:db (assoc-in db [:todos :last] {:text text :created-at time-ms})}))

(deftest todo-stamps-supplied-time
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:todo/create {:text "milk"}]
                      {:rf.cofx {:rf/time-ms 1781078400123}})
    (is (= 1781078400123
           (get-in (rf/app-db-value f) [:todos :last :created-at])))))
```

The `:rf.cofx` dispatch opt is the canonical "make this fact exact" affordance:

- **Envelope-supply, not fx-stub.** Deterministic time / id / location is supplied *on the token*, never injected via `:fx-overrides` or `:interceptor-overrides` — those tier-stub the effect/chain, not the fold's input. (See [002 §`:dispatched-at` is retired](002-Frames.md#dispatched-at-is-retired): the durable causal-time fact is `(:rf/time-ms (:rf.cofx envelope))`, supplied here.)
- **Supplied verbatim, never overwritten.** Values in the supplied `:rf.cofx` map are preserved exactly as given; the runtime fills only the framework-required `:rf/time-ms` when the caller omits it, and never overwrites a value the test supplied (per [002 §Supplied values win](002-Frames.md#declaration-and-delivery--rfcofxrequires)). This is the same path replay fixtures, SSR hydration, and host integrations use.
- **Flat, fact-name → value.** The map is flat (`{:rf/time-ms … :app/fact …}`), no grouping sub-maps; a handler receives exactly the leaves it declared in `:rf.cofx/requires`.

The grades, the registrar (`reg-cofx`, value-returning + graded), and the satisfaction algorithm are owned by [001 §Coeffects](001-Registration.md#coeffects--reg-cofx-value-returning-graded); the envelope field, delivery, and stamping rules are owned by [002 §Recordable coeffects](002-Frames.md#recordable-coeffects). This Spec only records the test idiom: supply the fact, assert the durable write.

### The `:test` frame preset — strict-mint by default

A test frame declares its intent — and gets the deterministic defaults — with `{:preset :test}` (per [002 §Frame presets](002-Frames.md#frame-presets--capability-bundles-for-common-configurations)). The preset expands to three fixed entries: `:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}` (the canonical Spec 014 HTTP fx redirected to its canned-success stub so test frames never reach the network), `:drain-depth 100` (the framework default, surfaced so tooling reads "this is a test frame" from `frame-meta`), and **`:rf.cofx/mint-policy :strict`**.

```clojure
(rf/reg-frame :test/auth-flow {:preset :test})
;; or anonymous — :preset is a record-config key, so it rides the advanced
;; record-config `re-frame.frame/make-frame`, not the EP-0023 object constructor:
(rf/with-new-frame [f (re-frame.frame/make-frame {:preset :test :on-create [:auth/init-idle]})]
  ...)
```

**Strict mint is the failure mode that matters in tests.** Under `:rf.cofx/mint-policy :strict`, a handler that **declares** a generator-backed recordable coeffect (`:rf.cofx/requires [:app/new-id]` where `:app/new-id` is a `reg-cofx` with a value-returning supplier) but for which the token carries **no** supplied value is **`:rf.error/missing-required-cofx`** — the generator does **not** run, no fresh per-run value is minted (per [002 §Mint policies](002-Frames.md#mint-policies)). The deterministic path is the default; nondeterminism is opt-in:

- A test that genuinely wants a fresh generated value per run opts back in with `{:rf.cofx/mint-policy :explicit-live}` — a per-call dispatch opt, or a per-frame override (user-supplied keys win over the preset expansion).
- `:rf/time-ms` always succeeds even under `:strict` (the router stamp guarantees it); the strict failure fires only for *declared-absent, generator-backed* facts.

This is why a test frame's missing-cofx failure surfaces as a loud `:rf.error/missing-required-cofx` rather than a silently-green test that minted a different value than production will — supply the fact in `:rf.cofx` (above) or opt into `:explicit-live`.

### Stubbing an HTTP fx for an entire frame

(`:my-app/http` here is a placeholder for a user-supplied fx; the framework ships `:rf.http/managed` — see [014-HTTPRequests](014-HTTPRequests.md). The stubbing mechanism is identical regardless of which fx-id is being overridden.)

```clojure
(rf/reg-frame :test/auth-flow
  {:on-create   [:auth/init-idle]
   :fx-overrides {:my-app/http (fn [_m _args] {:status 200 :body {:user/id 42}})}})

;; every event handled in :test/auth-flow uses the stub :my-app/http
```

### Stubbing an HTTP fx for a single dispatch

```clojure
(rf/dispatch-sync [:auth/load-user]
                  {:frame :test/auth-flow
                   :fx-overrides {:my-app/http (fn [_m _args] {:status 401 :body "unauthorised"})}})
;; only this dispatch sees the 401 stub; subsequent events use the frame's default
```

### Disabling a logging interceptor in tests

Under EP-0022, `:interceptor-overrides` is **reference-based**: keys are interceptor **references** (a bare keyword matches that registered interceptor; a parameterized `[id arg]` 2-vector matches the full reference), and values are either a replacement reference or `nil` to remove the matched interceptor (per [002 §`:interceptor-overrides` — exact-reference substitution](002-Frames.md#interceptor-overrides--exact-reference-substitution)). The keys are never inline interceptor values — the matched interceptor was registered via `reg-interceptor` and is referenced by id:

```clojure
;; Bare-keyword reference — remove the registered :my-app/logger interceptor.
(rf/reg-frame :test/silent
  {:on-create             [:test/init]
   :interceptor-overrides {:my-app/logger nil}})       ;; nil removes the interceptor

;; Parameterized [id arg] reference — match the exact factory-built interceptor.
;; A chain entry [:rf.interceptor/path [:cart]] is matched (and here replaced)
;; by its full reference, NOT by the bare :rf.interceptor/path id — an id-only
;; key could not say which parameterization it meant.
(rf/reg-frame :test/cart
  {:interceptor-overrides {[:rf.interceptor/path [:cart]] :test/recording-path
                           :my-app/audit                  nil}})
```

Matching is by the canonical (CEDN-1) reference, so a parameterized override must spell the exact `[id arg]` it targets; a replacement value is itself a registered reference resolved through the same registrar. Per-call overrides (in the `dispatch-sync` opts map) win over per-frame on key conflict (per [002 §`:interceptor-overrides`](002-Frames.md#interceptor-overrides--replace-or-remove-interceptors-by-exact-reference)).

### Recording dispatched events without firing handlers

```clojure
(def recorded (atom []))

(rf/reg-interceptor :test/event-recorder
  {:before (fn [ctx]
             (swap! recorded conj (-> ctx :coeffects :event))
             ctx)})

(rf/reg-frame :test/recorder-frame
  {:interceptors [:test/event-recorder]})
```

After running a test sequence, `@recorded` contains the events that fired, in order. Useful for verifying control flow without checking every state transition.

## Headless evaluation

### Sub computation without the reactive runtime

`(rf/compute-sub query-v db)` runs the sub's body against the given `app-db` value and returns the computed value. No reactive cache, no Reagent, no JS runtime needed. JVM-runnable.

`query-v` is a vector — exactly the shape `subscribe` takes (`[:sub-id arg1 arg2]`).

The recommended pattern is to drive `db` state via dispatches against a fixture frame, then compute the sub against the resulting `app-db`. This tests the sub against state produced by the same code paths the application uses, and survives `app-db` schema changes — if `:items` becomes `:todos`, the events update, the sub updates, the test keeps working unmodified.

```clojure
(rf/reg-event :todos/add  (fn [{:keys [db]} [_ todo]] {:db (update db :items (fnil conj []) todo)}))
(rf/reg-sub      :pending-todos
                 (fn [db _] (filter #(= :pending (:status %)) (:items db))))

(deftest pending-todos-sub
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:todos/add {:id 1 :status :pending}])
    (rf/dispatch-sync [:todos/add {:id 2 :status :done}])
    (rf/dispatch-sync [:todos/add {:id 3 :status :pending}])
    (is (= 2 (count (rf/compute-sub [:pending-todos] (rf/app-db-value f)))))))
```

Composed subs are computed transitively — the inputs are computed first, then the output. All without spinning up the reactive cache. This holds for both static `:<-` inputs and parametric `input-fn` inputs: `compute-sub` produces the input query vectors from the sub's input producer (per [006 §Subscription input producers](006-ReactiveSubstrate.md#subscription-input-producers--app-db-reader-static-parametric-input-fn)), then resolves each recursively. Because an `input-fn` is **pure** over the outer `query-v` (it must not call `subscribe`, deref `app-db`, dispatch, mutate, or perform IO — and must return a vector of query vectors, never a live reaction), the parametric path stays as pure and JVM-runnable as the static path. `compute-sub` and `subscribe-once` agree for parametric subscriptions.

#### `compute-sub` algorithm

`compute-sub` is **pure**: same `(query-v, db)` always returns the same value. No reactive cache, no Reagent reactions, no `app-db` deref — the function takes `db` as a value argument.

Pseudocode (the contract every implementation matches):

```
compute-sub(query-v, db):
  let sub-id   = head(query-v)
  let reg      = handler-meta(:sub, sub-id)
  if reg is nil:
     emit :rf.error/no-such-sub trace; return nil       ; per 009 default :replaced-with-default

  ; Produce this sub's input query-vectors from its input producer, then
  ; resolve each recursively (the chained-sub case). The input producer is the
  ; same three-mode model as the reactive cache path (per [006 §Subscription
  ; input producers]): no producer (layer-1), the literal :<- list, or a
  ; parametric input-fn over the outer query-v.
  let input-qs = match reg.input-kind:
                   :db          -> []                          ; root sub: body reads db directly
                   :static      -> reg.input-signals           ; literal :<- query-vectors
                   :parametric  -> normalize-sub-inputs((reg.input-fn query-v))
                                   ; (input-fn query-v) MUST return a vector of query
                                   ; vectors; a bad shape signals
                                   ; :rf.error/sub-input-fn-bad-return; a throw signals
                                   ; :rf.error/sub-input-fn-exception (per 009)
  let values   = map (q -> compute-sub(q, db)) input-qs
  let body-arg = match reg.input-kind:
                   :db          -> db                          ; root sub: body reads db directly
                   :parametric  -> vector(values)              ; always vector, even at one input
                   :static      -> if count(values) == 0 then nil
                                   else if count(values) == 1 then first(values)
                                   else vector(values)          ; v1 :<- delivery convention

  ; Run the body with resolved input VALUES (or with db, for root subs).
  return reg.computation-fn(body-arg, query-v)
```

Notes on the contract:

- **Recursive resolution.** `compute-sub` recursively calls itself on each input `query-v`. Layered subs (`A` ← `B` ← `C`) resolve depth-first: `C` is computed first against `db`, then `B` receives `C`'s value, then `A` receives `B`'s value. The argument shape handed to each `computation-fn` mirrors the runtime: app-db readers receive `db`; static `:<-` keeps the v1 convention (single input as a bare value, multiple inputs as a vector); parametric `input-fn` subscriptions receive a vector of resolved input values even when the vector has one element.
- **No memoisation across calls.** `compute-sub` is a pure function over `(query-v, db)`. Implementations may memoise *within* a single call (the same input sub appearing twice in one tree is computed once and reused) but **must not** carry a cache between calls — it is not a substitute for the reactive runtime, and an `app-db` value that has changed must produce a fresh result. Per-call memoisation is an optimisation; tests must not depend on it.
- **No cycles.** A cycle in the static `:<-` topology is a registration-time error (per [001](001-Registration.md)); `compute-sub` does not need to detect cycles at call time. If a host bypasses the registration-time check, `compute-sub` may stack-overflow — surface a structured error trace if cheap; otherwise let the host's stack overflow propagate.
- **Errors.** If a sub's `computation-fn` throws, emit `:rf.error/sub-exception` per [009 §Error contract](009-Instrumentation.md#error-contract); default recovery `:replaced-with-default` returns `nil`. An unresolved input sub (`:rf.error/no-such-sub`) substitutes `nil` and the body still runs (default `:replaced-with-default`). For a parametric sub, an `input-fn` that throws emits `:rf.error/sub-input-fn-exception`, and an `input-fn` that returns a non-vector-of-query-vectors (a scalar, map, bare keyword, reaction, derefable, or malformed query vector) emits `:rf.error/sub-input-fn-bad-return` — both follow the same fail-loud posture (a bad input return is never silently treated as no inputs). Per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue).
- **Determinism.** `compute-sub` is JVM-runnable, deterministic, and free of side effects. It is the function the conformance corpus invokes for `:sub-values` assertions per [conformance/README.md](conformance/README.md).

For both static `:<-` and parametric `input-fn` inputs, the body receives resolved input *values*, not input `query-v`s. Producer order is preserved. The outer `query-v` (the one being computed) remains the second argument to `computation-fn`, identical to in-runtime behaviour.

For unit-testing a sub in pure isolation against a literal `db` (rare, but useful for very simple readers where the dispatch path adds no value), pass a literal map directly:

```clojure
(deftest pending-todos-sub-pure
  (is (= 2 (count (rf/compute-sub [:pending-todos]
                                  {:items [{:id 1 :status :pending}
                                           {:id 2 :status :done}
                                           {:id 3 :status :pending}]})))))
```

The dispatch-driven form is the recommended pattern; the pure form is the escape hatch.

### Machine simulation

Already covered in Pattern 4 — `machine-transition` is pure and JVM-runnable.

## View-assertion helpers (`re-frame.test-helpers`)

State-only assertions catch bugs in events / subs / machines / fx — but two bug classes live in the **view-vs-state gap**, where `app-db` is correct yet the user sees a broken screen:

1. **State-correct, view-broken** — the handler updated `app-db`, the sub computes the right value, but the view reads from the wrong path / formats it wrong / forgets to render one branch. State-only assertions pass; the UI is wrong.
2. **Wrong-frame dispatch** — the view wires `:on-click` to dispatch into the wrong frame (or no frame at all). State-assertions in the host frame stay green; the click in production fires into a sibling and nothing happens.

Both bug classes are caught by a single shape: dispatch → call the view-fn directly → walk the returned hiccup → assert on content (class 1) or invoke `:on-click` (class 2). The view-fn is just a function; the returned hiccup is just a vector. No JSDOM, no React, no `act()`. JVM-runnable.

### When to reach for hiccup-walk vs `render-to-string`

Two flavours of view-content test:

- **`render-to-string`** (per [011-SSR §The render-tree → HTML emitter](011-SSR.md#the-render-tree--html-emitter-cljs-reference)) — renders the whole view to an HTML string. Best when the assertion is about the rendered markup ("is the `<button>` disabled?", "does the `<h1>` carry the right class?"). Output is a string.
- **hiccup-walk** (`re-frame.test-helpers`) — calls the view-fn directly and walks the returned hiccup. Best when the assertion is about the **structure** (testid presence, layout) or **handlers** (which `:on-click` is wired) or when the test wants to **invoke** a handler to drive interaction. Output is hiccup data; assertions read keys.

Both are JVM-runnable and require no DOM. Reach for `render-to-string` when the test cares about HTML; reach for hiccup-walk when the test cares about handlers or testid-keyed structure.

### Normative surface — `re-frame.test-helpers`

Sixteen public defs, organised by role. Every entry except `with-app-fixture` (which threads through `re-frame.frame/reg-frame` and `destroy-frame!`) is JVM-runnable purely against `clojure.string`; the namespace pulls `re-frame.frame` for the fixture-macro expansion and `clojure.test` / `cljs.test` for `expect-text`'s `do-report` path.

| Helper | Form | Signature | Purpose |
|---|---|---|---|
| `expand-tree` | Fn | `(expand-tree tree) → tree` | Recursively expand a hiccup tree, invoking any fn-components (and Form-3 class components, per the reagent-slim discriminator) with their args. After expansion every vector's first element is a keyword tag or a non-component value, never a fn / class. Lazy seqs are walked through `map`; vectors through `mapv`. Public so test files mid-walk can re-expand a sub-tree. |
| `attrs` | Fn | `(attrs node) → map?` | Return the attrs map of a hiccup node, or `nil` if the node has no attrs map. A hiccup vector's second element is the attrs map iff it is a map. |
| `children` | Fn | `(children node) → vector` | Return the child elements — everything after the tag (and optional attrs map). Always a vector (empty if no children). `nil` for non-hiccup input. |
| `text-content` | Fn | `(text-content node) → string` | Recursively collect string leaves under `node` and join into a single string. Numbers coerce to strings; nils are skipped. Useful for `(is (= "Count: 5" (text-content label)))`. |
| `extract-handler` | Fn | `(extract-handler node event-key) → fn?` | Return the value of `event-key` (e.g. `:on-click`, `:on-change`) from `node`'s attrs map, or `nil`. Reads better than `(get (attrs node) event-key)` at call sites. |
| `find-by-attr` | Fn | `(find-by-attr tree attr val) → node?` | Walk `tree` (expanding fn / class components) and return the FIRST hiccup node whose attrs map carries `attr == val`, or `nil` if no node matches. Generic over the attribute keyword — pick whichever the codebase uses (`:data-testid`, `:data-test`, `:id`, custom). |
| `find-all-by-attr` | Fn | `(find-all-by-attr tree attr val) → vector` | Like `find-by-attr` but returns every matching node, in depth-first order. Empty vector when no match. |
| `find-by-attr-prefix` | Fn | `(find-by-attr-prefix tree attr prefix) → vector` | Every hiccup node whose `attr` value (a string) STARTS with `prefix`. Non-string attr values do not match. |
| `find-by-testid` | Fn | `(find-by-testid tree test-id) → node?` | The first node whose attrs map carries `:data-testid == test-id`, or `nil`. Equivalent to `(find-by-attr tree :data-testid test-id)`. |
| `find-all-by-testid` | Fn | `(find-all-by-testid tree test-id) → vector` | Every node carrying `:data-testid test-id`, in depth-first order. Equivalent to `(find-all-by-attr tree :data-testid test-id)`. |
| `find-by-testid-prefix` | Fn | `(find-by-testid-prefix tree prefix) → vector` | Every node whose `:data-testid` STARTS with `prefix`. Equivalent to `(find-by-attr-prefix tree :data-testid prefix)`. |
| `invoke-handler` | Fn | `(invoke-handler node event-key & args) → any` | Find the handler under `event-key` on `node` and call it. Returns the handler's return value (typically `nil` for `dispatch`-side-effecting `:on-click`s). **Throws** when `node` is not a hiccup vector, the node has no attrs map, or no handler is registered — the throwing failure mode is deliberate (a missing handler is almost always a test bug, not a passing case). |
| `testid` | Fn | `(testid id)` / `(testid id extra) → map` | Build an attrs map carrying `:data-testid id`. The 2-arity merges `extra` into the map; `:data-testid` always wins on collision. Use at the view call site: `[:button (testid "counter-inc" {:on-click ...}) "+"]`. |
| `with-app-fixture` | Macro | `(with-app-fixture opts-map frame-id body+)` / `(with-app-fixture opts-map body+)` | Single-frame e2e fixture. Creates the frame, binds `*current-frame*` for the body's dynamic extent, calls `:install` (zero-arg) inside the scope, stashes `:root-view` / `:root-view-args` for `expect-text` / `wait-until`, and destroys the frame on exit (success or exception). Frame-id is positional and optional; omitting it gensym's an anonymous `:rf.frame/*` id. Opts keys: `:install`, `:root-view`, `:root-view-args`, `:frame-config` (record-config seated via `reg-frame` / the advanced `re-frame.frame/make-frame`). See [§Pattern 5 — single-frame e2e fixture](#pattern-5--single-frame-e2e-fixture). |
| `expect-text` | Fn | `(expect-text testid expected)` / `(expect-text tree testid expected) → bool?` | Locate `:data-testid testid` in the (fixture-stashed) root view's rendered hiccup and assert `(text-content node) = expected` via `clojure.test/is` (`do-report`). `testid` accepts a keyword (coerced via `name`) or a string. The 2-arity reads the fixture-stashed root view from `*current-root-view*`; the 3-arity walks an explicit tree. Throws (with a clear `ex-info` message) if neither a fixture nor an explicit tree is present. |
| `wait-until` | Fn | `(wait-until pred)` / `(wait-until pred opts)` / `(wait-until testid expected)` / `(wait-until testid expected opts)` | Bounded-deadline poll for async-stable assertions. JVM: synchronous — returns the truthy value, throws `ex-info` carrying `:rf.error/id` `:rf.error/wait-until-timeout` (the canonical discriminator, per [Spec 009](009-Instrumentation.md)) on timeout. CLJS: returns a `js/Promise` that resolves with the truthy value or rejects on timeout. The testid form polls the fixture-stashed root view until `(text-content (find-by-testid tree testid)) = expected`; the predicate form polls an arbitrary fn. `opts`: `:timeout-ms` (2000), `:interval-ms` (5), `:label`. Sister of `re-frame.test-support/poll-until` — same shape, tuned for the hiccup-walk pattern. |

### Function-component expansion

Reagent hiccup admits a function in the first slot of a vector — `[my-component {...}]` — and lazily invokes it during render. The walkers expand nested function components by calling them with their args (just like Reagent's renderer would) before walking, so a test that calls a parent view-fn sees the leaf hiccup the user sees. Expansion is recursive but terminating: a non-vector / non-fn leaf is a fixed point.

Form-3 components built via `r/create-class` are detected (the reagent-slim class tag + the stashed `:reagent-render` slot) and expanded by invoking the render fn directly with the hiccup args. **The walker does NOT instantiate React or run lifecycle methods** — if a Form-3 view's hiccup output depends on lifecycle state (`componentDidMount`-style behaviour), the test sees the initial render only. JVM runs identically: class-3 detection is a no-op because the JVM has no JS class instances.

### Selector convention — `:data-testid` vs `:data-test` vs custom

React conventionally uses `:data-testid`; some codebases (notably Story) standardised on `:data-test` before the rename; framework tools may use their own prefix (Xray uses `:data-rf-xray-*`). The namespace ships two layers:

- `find-by-attr` / `find-all-by-attr` / `find-by-attr-prefix` — the underlying. Match against any attr key the caller supplies. Use directly when the codebase keys on `:data-test` or a custom attribute.
- `find-by-testid` / `find-all-by-testid` / `find-by-testid-prefix` — thin wrappers that pre-bind the attr to `:data-testid`. Use for the common React-convention case.

The Conventions doc does not pin one form as canonical — the framework's view-test seam is the generic `find-by-attr` family, and `find-by-testid` is the recommended convenience for the React-conventional case.

### Examples

Drive a click and assert state changed downstream:

```clojure
(deftest counter-inc
  (rf/with-new-frame [_ (rf/make-frame {})]
    (rf/dispatch-sync [:counter/init])   ;; seed via a setup dispatch
    (let [tree (counter-view {})
          btn  (th/find-by-testid tree "counter-inc")]
      (th/invoke-handler btn :on-click)
      (is (= 1 (:n (rf/app-db-value (rf/current-frame-id))))))))
```

Assert rendered text after dispatching:

```clojure
(deftest counter-label
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:counter/init])   ;; seed via a setup dispatch
    (rf/dispatch-sync [:counter/set 5])
    (let [tree  (counter-view {})
          label (th/find-by-testid tree "counter-label")]
      (is (= "Count: 5" (th/text-content label))))))
```

Authoring side — emit a testid at the view call site:

```clojure
(defn counter-button [label dispatch-ev]
  [:button (th/testid (str "counter-" label)
                      {:on-click #(rf/dispatch dispatch-ev)})
   label])
```

### JVM-runnable boundary for hiccup-walk

Every helper in `re-frame.test-helpers` is JVM-runnable. The hiccup-walk core (everything ) is classpath-clean against `clojure.string` alone; the fixture trio (`with-app-fixture` / `expect-text` / `wait-until`) additionally reaches `re-frame.frame` (for `reg-frame` / `make-frame` / `destroy-frame!` / `*current-frame*`) and `clojure.test` / `cljs.test` (for `expect-text`'s `do-report` failure path). The fixture deps are framework-internal — they do not pull React, Reagent, or any substrate adapter into the classpath. The reagent-slim Form-3 detection uses a reader-conditional (`#?(:cljs ...)`) that's a no-op on the JVM (the JVM has no `.-cljsReagentClass` property access on plain fns), so Form-3 expansion is a CLJS-only optimisation and JVM tests see the same hiccup tree. `wait-until`'s per-platform shape is reader-conditional (JVM synchronous; CLJS `js/Promise`) per [§Pattern 5](#pattern-5--single-frame-e2e-fixture).

This complements the JVM-runnable list in [§Normative surface §JVM-runnable boundary](#jvm-runnable-boundary-authoritative): hiccup-walk joins `render-to-string` as a JVM-runnable view-test path.

## Judgement — AI-first test-authoring guidance

The mechanics above (fixture patterns, JVM-runnable surfaces, view-assertion helpers) describe *what is possible*. This section captures the **judgement** for choosing among them. Match the spec/Principles.md voice: terse, fact-dense, AI-first.

### Sub testing — `compute-sub` vs dispatch-sync + `app-db-value`

- **`compute-sub` against a dispatch-driven `app-db`** is the recommended pattern (per [§Sub computation without the reactive runtime](#sub-computation-without-the-reactive-runtime)). Drive state via `dispatch-sync` calls against a fixture frame, then `(compute-sub [:sub-id arg] (app-db-value f))`. This tests the sub against state produced by the same code paths the application uses, and survives `app-db` schema changes — when `:items` becomes `:todos`, the events update, the sub updates, the test keeps working.
- **`compute-sub` against a literal `db`** is the escape hatch for very simple readers where the dispatch path adds no value. Pass a literal map directly. Use sparingly — a sub against a hand-rolled `app-db` shape decouples the test from event behaviour and silently rots when handler-side schema evolves.
- **Avoid `subscribe` + deref in tests.** The reactive runtime is overhead for an assertion against an `app-db` value. `compute-sub` is JVM-runnable and pure; `subscribe` requires a live reactive cache and an installed adapter.

### Event testing — `dispatch-sync` + `app-db-value` vs the `assert-*-equals` fn-family

- **`dispatch-sync` + `(get-in (app-db-value f) [path])` + `(is (= ...))`** is the canonical shape for event handler tests. Dispatch settles synchronously; the assertion reads committed state.
- **`assert-path-equals`** is the `clojure.test`-aware sugar for the path/value form. Reaches for `do-report` directly so the failure message names the frame and path. Reach for it when the test asserts on many path/value pairs in sequence; the inline form reads better for a single assertion. **Mirrors the `:rf.assert/path-equals` event** used inside a Story `:script` block (per [007 §Play functions](007-Stories.md#play-functions)) — same name root so a reader navigating between the fn-side and the event-side does not need a translation table.
- **`assert-db-equals`** is the companion full-db form (no `:rf.assert/*` event analog — the event-family is path-keyed). Reach for it in small fixtures where "the whole thing should equal this" is the natural assertion shape.
- **`dispatch-sequence`** composes a vector of events through `dispatch-sync` and optionally runs an `:after-each` between dispatches. Reach for it when the test asserts on a fan-out chain (3+ events with intermediate state) — the `doseq` shape reads worse than the vector form.

### View testing — hiccup-walk vs `render-to-string`

- **hiccup-walk** (`re-frame.test-helpers`) — call the view-fn directly, walk the returned hiccup data. Reach for it when the assertion is about **structure** (testid presence, layout shape) or **handlers** (which `:on-click` is wired, what frame it dispatches into) or when the test needs to **invoke** a handler. Output is hiccup data; assertions read keys.
- **`render-to-string`** (per [011 §The render-tree → HTML emitter](011-SSR.md#the-render-tree--html-emitter-cljs-reference)) — render the whole view to an HTML string. Reach for it when the assertion is about the **rendered markup** ("is the `<button>` disabled?", "does the `<h1>` carry the right class?"). Output is a string.
- **Both are JVM-runnable**, no DOM. Reach for `render-to-string` when the test cares about HTML; reach for hiccup-walk when the test cares about handlers or testid-keyed structure.
- **The `with-app-fixture` + `expect-text` pair** is the two-line shorthand for single-frame e2e tests — install hook + root view + dispatch + testid text assertion. Use it for the common shape; fall back to bare hiccup-walk for multi-frame setups (see [§Pattern 5](#pattern-5--single-frame-e2e-fixture) — When NOT to use Pattern 5).

### Async tests — `poll-until` vs explicit sleeps

- **`poll-until`** for *settles* — the post-condition is observable in state and the test wants to wait for the cascade / drain / HTTP reply to land. Bounded deadline; fails fast on a truly stuck condition. JVM-synchronous; CLJS returns a `js/Promise` for composition with `cljs.test/async`.
- **Explicit `Thread/sleep` / `js/setTimeout`** for *windows* — the sleep IS the contract under test (grace-period elapse, throttle/debounce window, "prove no event fires within window N"). Annotate the intent locally with a `;; Timer-semantics sleep: ...` comment so audits don't re-flag it.
- **`wait-until`** is the view-test counterpart of `poll-until` — same shape, the testid form polls a fixture-stashed root view's rendered text.

### Per-test granularity heuristic

- **One assertion per `deftest` is too granular** — overhead dominates when each deftest re-installs an adapter. Cluster the dispatches + assertions that exercise one feature into one `deftest`.
- **One feature per `deftest` is too coarse** — a 200-line `deftest` exercising auth + routing + http loses its diagnostic value when one assertion fails and the cascade halts. Split when the dispatches involve unrelated paths.
- **Sweet spot: one feature-slice + 3-10 assertions per `deftest`**, with `testing` blocks to label sub-shapes. Use `dispatch-sequence` + `:after-each` to capture intermediate state when the cascade is the test target.

### Fixture-granularity heuristic

- **Default to L3 (`make-reset-runtime-fixture`)** for any suite that boots an adapter or exercises per-process state. The late-bind machinery no-ops when an artefact is absent, so the L3 fixture is cheap for thin test suites and complete for thick ones.
- **Drop to L2 (`with-fresh-registrar`)** only when the test body is purely registrar-bound — registers some `reg-event` / `reg-sub`, never mounts an adapter, never dispatches against a long-lived frame.
- **Reach for L1 (`snapshot-registrar` / `restore-registrar!`)** only when the test body needs nested or shared snapshots that L2's bracket can't express.
- **Reach for L4 (direct late-bind hooks)** only when authoring a fixture, not when authoring a test.

### Assertion preference order

Rank by signal-to-noise:

1. **`(is (= expected-value (compute-sub [:sub] db)))`** — pure value, no runtime, smallest blast radius.
2. **`(is (= expected-value (get-in (app-db-value f) [path])))`** — direct state read after a dispatch.
3. **`(th/expect-text testid expected)`** — view content under the fixture-stashed root.
4. **`(is (= expected (render-to-string view-fn args)))`** — HTML markup.
5. **`(th/invoke-handler node :on-click) → assert downstream state`** — handler-wiring test, catches wrong-frame dispatch.

Earlier ranks are cheaper and catch a tighter bug class; later ranks catch view-vs-state and handler-wiring bugs. Reach for the lowest rank that proves the property under test.

## Assertion patterns

### Reading app-db

```clojure
(is (= :validating (get-in (rf/app-db-value :test-frame) [:auth :state])))
(is (= 3 (count (get-in (rf/app-db-value :test-frame) [:items]))))
```

### Reading machine snapshots

```clojure
(is (= :authenticated (:state @(rf/subscribe [:rf/machine :auth/state-machine] {:frame :test-frame}))))
```

A machine snapshot is read through the ordinary `subscribe` test surface — there is no machine-special call site. The canonical read is the registered `[:rf/machine <machine-id>]` subscription vector (the framework-shipped `:rf/machine` sub, per [005 §Subscribing to machines via the `:rf/machine` sub](005-StateMachines.md#subscribing-to-machines-via-the-rfmachine-sub)), so no extra namespace beyond the core test surface is required. For a non-reactive storage-layer read, `(get-in (rf/runtime-db-value f) [:rf.runtime/machines :snapshots :auth/state-machine])` reads the snapshot directly from runtime-db with no subscription. Pure transition logic is tested without a frame at all via `machine-transition` ([Pattern 4](#pattern-4--pure-machine-simulation-no-frame)).

### Asserting on effects (without firing them)

When you want to verify what *would* dispatch without actually running the cascade, stub the `:dispatch` fx **per-call** — pass the override in the `dispatch-sync` opts so it is scoped to exactly the one event under assertion:

```clojure
(let [dispatched (atom [])]
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:auth/init-idle])   ;; seed via a setup dispatch
    (rf/dispatch-sync [:auth/login-pressed]
                      {:frame        f
                       :fx-overrides {:dispatch (fn [_m ev] (swap! dispatched conj ev))}})
    (is (= [[:auth/check-credentials]] @dispatched))))
```

`:dispatch` (and `:dispatch-later`) are in the **OVERRIDABLE** tier of the reserved fx-ids (per [Conventions §Reserved fx-id override tiering](Conventions.md#reserved-fx-id-override-tiering)): a fn-value override pre-empts the reserved body, so the captured event vector is recorded and **not** queued — the cascade does not run. This is the canonical "assert what would dispatch" affordance.

**Scope the `:dispatch` override per-call, not per-frame.** A `:dispatch` override placed on a frame's `:fx-overrides` config (a record-config key, so via `reg-frame` / the advanced `re-frame.frame/make-frame`) is re-merged into the envelope on **every** dispatch routed to that frame for the frame's whole lifetime — including framework-internal dispatches (machine actor messages, spawned-actor `:start`, router internals, HTTP reply settles). That silently re-routes traffic the test never meant to touch and is a sharp footgun. The per-call form above scopes the stub to the single event you are asserting on.

**State-installing reserved fxs cannot be stubbed.** The `:fx-overrides` map is tiered: the routing primitives (`:dispatch`, `:dispatch-later`, `:rf.machine/dispatch-to-system`) and the navigation primitives (`:rf.nav/*`) are OVERRIDABLE, but the **state-installing** reserved fxs — `:rf.machine/spawn`, `:rf.machine/destroy`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow`, `:rf.route/with-nav-token` — are **HARD-REJECTED**. An override targeting one of those is ignored (the runtime emits [`:rf.error/reserved-fx-override`](009-Instrumentation.md#error-event-catalogue) and runs the real reserved body), because stubbing them would leave the frame's runtime-db in an inconsistent state that breaks later behaviour far from the override site (e.g. a spawned actor whose snapshot was never installed → every later actor dispatch is `:rf.error/no-such-handler`). To assert on *those* operations, drive the real fx and read the resulting runtime-db state (machine snapshot, flow registry) directly.

### Time travel — assertion after rewind

For tests that exercise event sequences and want to assert at intermediate points, dispatch one event at a time and assert between dispatches:

```clojure
(rf/with-new-frame [f (rf/make-frame {})]
  (rf/dispatch-sync [:auth/init-idle])   ;; seed via a setup dispatch
  (rf/dispatch-sync [:auth/email-changed "alice@example.com"])
  (is (= "alice@example.com" (get-in (rf/app-db-value f) [:auth :form :email])))
  (rf/dispatch-sync [:auth/password-changed "hunter2"])
  (is (some? (get-in (rf/app-db-value f) [:auth :form :password])))
  (rf/dispatch-sync [:auth/login-pressed])
  (is (= :validating (get-in (rf/app-db-value f) [:auth :state]))))
```

Because run-to-completion drain settles each `dispatch-sync` before returning, assertions between dispatches reflect committed state — no race conditions.

## Test-framework adapters

The testing surface is framework-agnostic — `make-frame` and friends work from any host that can require re-frame. Test framework integration is a thin layer of conventions per framework.

### cljs.test (CLJS)

```clojure
(require '[cljs.test :refer [deftest is testing use-fixtures]]
         '[re-frame.core :as rf])

(use-fixtures :each
  (fn [t]
    (rf/reg-frame :test-fixture {:on-create [:test/init]})
    (try (t) (finally (rf/destroy-frame! :test-fixture)))))

(deftest example-test
  (rf/dispatch-sync [:some-event] {:frame :test-fixture})
  (is (= ... (get-in (rf/app-db-value :test-fixture) [...]))))
```

### clojure.test on the JVM

Identical shape, just the require:

```clojure
(require '[clojure.test :refer [deftest is testing use-fixtures]]
         '[re-frame.core :as rf])

;; same patterns as cljs.test — re-frame2's testing surface is JVM-runnable
;; per the boundary in §Normative surface above.
```

### Kaocha / Cognitect's test-runner

No special integration — works because `cljs.test` and `clojure.test` work. Kaocha picks up tests; the underlying re-frame2 fixtures function the same.

### `re-frame-test` (existing community library)

The `day8/re-frame-test` library provides `run-test-sync` and similar helpers. re-frame2 does **not** ship a `run-test-sync` shim — the macro existed in v1 to wrap test bodies in a synchronous drain, and v2's `dispatch-sync` is already settle-by-default, so the shim was pure migration tax. Existing test suites built against `re-frame-test` rewrite the `run-test-sync` body to inline `dispatch-sync` calls under the standard per-test `make-reset-runtime-fixture` (or `with-fresh-registrar` for ad-hoc bodies) — see [MIGRATION §M-52](../migration/from-re-frame-v1/README.md#m-52-run-test-sync-removed--use-dispatch-sync-under-make-reset-runtime-fixture). The other two re-frame-test helpers ship in `re-frame.test-support`: `dispatch-sequence` keeps its v1 name; `assert-state` is split into `assert-path-equals` + `assert-db-equals` per [MIGRATION §M-62](../migration/from-re-frame-v1/README.md#m-62-test-assertion-fn-family-alignment--assert-state--assert-path-equals--assert-db-equals) so the fn-side shares a name root with the `:rf.assert/*` Story event-family. The require move is a mechanical `re-frame.test` → `re-frame.test-support` rewrite per [MIGRATION §M-25](../migration/from-re-frame-v1/README.md#m-25-re-frametest-helpers-renamed-to-re-frametest-support).

## Forward compatibility with stories

A test fixture is a story-variant minus the rendering — the story library's `run-variant` consumes the same primitives a test does (see [007 §Portable into tests](007-Stories.md#portable-into-tests)). The testing surface guarantees these shapes for 007:

- `(make-frame {:images [...] :id … :initial-db {…} :capabilities {…} :adapter …})` — the EP-0023 object-constructor opts shape (record-config keys like `:on-create` / `:fx-overrides` / `:interceptor-overrides` / `:interceptors` fail loud here; they ride `reg-frame` / the advanced `re-frame.frame/make-frame`).
- `(reg-frame :id {:on-create [:event-id] :fx-overrides {…} :interceptor-overrides {…} :interceptors [...]})` — exact record-config opts shape.
- `(dispatch-sync ev {:frame f :fx-overrides {…}})` — exact opts shape.
- `(app-db-value f)` — current `app-db` value (a plain map) for the named frame.
- `(snapshot-of path {:frame f})` — exact opts arg.
- `(destroy-frame! f)` — exact teardown contract.
- Inclusion-tag schema is open (additive `set` on `reg-frame` metadata).

## Story plan execution surface and evidence tools

> Forward-reference normative section (NewTestStory EPIC). The
> Story-as-test work introduces a variant-plan execution model and a set
> of low-level evidence tools. The tools below live **at the testing-
> substrate level**, below Story, and run without the Story UI; Story
> consumes them but does not own them. The full Story-facing contract —
> variant plans, the three execution verbs (`run` / `is` / `explain`),
> `:cannot-run`, composition, the schema floor, and the run-result shape
> — is normative in
> [`tools/story/spec/017-Testing-Story.md`](../tools/story/spec/017-Testing-Story.md).
> This section states the substrate primitives that contract depends on,
> kept consistent with the existing 008 surfaces (`re-frame.test-support`
> / `re-frame.test-helpers` / `compute-sub`). These are NET-NEW for P1
> unless explicitly marked otherwise; an implementer must not assume a
> hook that does not yet exist.

### `settled-boundary`

`settled-boundary` is the author-facing settlement contract for a
`[:dispatch event-vector]` step. It is **not** a new headless scheduler:
in the `:headless` runner it is the existing `dispatch-sync`
run-to-fixed-point drain (§Normative surface), renamed/projected rather
than reimplemented. Richer runners add adapter-supplied flushes with a
declared bound:

- `:headless` — the frame's event queue is drained AND all synchronous
  re-dispatches have settled (the `dispatch-sync` semantics this Spec
  already guarantees);
- `:cljs-reactive` — the above AND reaction recomputation has flushed;
- `:dom` / `:browser` — the above AND the adapter's `act()` / microtask
  flush has completed, within a declared maximum (the per-adapter
  `flush-views!` of [§Adapter-aware test helpers](#adapter-aware-test-helpers--flush-views)).

A runner takes its flush-fn from the adapter-aware caller and MUST NOT
hard-code `dispatch-sync`. A step that requires a React/DOM flush MUST
require `>= :cljs-reactive`; a `:headless` runner refuses it
(`:cannot-run`) rather than under-flushing and passing falsely.

### Inline plans

An inline plan is an executable plan map that is not registered as a
Story variant. Unit/integration tests MAY run a flow-shaped test through
the Story plan runner without registering a visible Story:

```clojure
(story/is
  {:setup      [[:dispatch [:auth/init]]]
   :script     [[:dispatch [:auth/login-pressed]]]
   :checks     [:check/no-runtime-errors]
   :assertions [[:rf.assert/path-equals [:auth :state] :error]]}
  {:runner :headless})
```

`story/is` reports to `clojure.test` / `cljs.test` with per-assertion
granularity, sharing the one assertion vocabulary (§Resolved decisions —
`assert-path-equals` / `assert-db-equals` mirror `:rf.assert/*`). Inline
plans MUST return the same run-result shape as registered variants;
equivalent inline plans and registered variants SHOULD be a metamorphic
relation (same final app-db and assertion records after `canonicalize`,
below).

### Invariant sentinels and first-bad-epoch

The testing substrate SHOULD add two evidence utilities over committed
epochs:

```clojure
(test/with-invariants [invariant-spec ...] body...)
(test/first-bad-epoch epoch-tape invariant)
```

Invariants run after each committed epoch (via the existing epoch-listener
seam) and report through the test framework; they MUST NOT throw from the
listener. Each spec SHOULD carry frame id, epoch id, event, path,
expected, actual, and source where possible. `first-bad-epoch` is a pure
utility returning the first epoch where an invariant fails (with trigger
event, db-diff, and trace events), or `nil`.

### Run-artifact replay and determinism gate

The testing substrate SHOULD add three pure-over-evidence utilities:

```clojure
(test/replay-run-artifact   artifact opts)
(test/assert-deterministic  plan-or-artifact opts)   ; N fresh runs, compared via canonicalize
(test/diff-run-artifacts    baseline current opts)
```

A **run artifact** is the low-level evidence emitted by generated tests,
failed runs, replay, determinism checks, or tool/agent exploration —
`{:artifact/kind :rf.test/run-artifact :seed … :event-program […]
:fx-decisions […] :epoch-tape […] :trace […] :result run-result …}`. It
is not a Story variant; it MAY be promoted into one.

**Determinism guarantees apply only to plans free of wall-clock steps.**
`[:wait-until pred]` (queue/state-based) is preferred and deterministic;
bare `[:wait ms]` is the explicit opt-out. `assert-deterministic` MUST
refuse (`:cannot-run`) a plan containing `:wait` rather than running it
flakily. A virtual clock stays a non-goal (consistent with the post-v1
items below).

### `canonicalize` / fingerprinting

`canonicalize` is the single canonical projection/hash primitive that
determinism, semantic diff, snapshot identity, `:plan-hash` / `:run-hash`,
future golden-slice comparison, and the inline-plan-to-registered-variant
metamorphic relation all consume. It MUST live in a fingerprinting
namespace (not the canonical-vocabulary installer), fold the existing
snapshot-identity `canonical-form` / `content-hash` / `snapshot-tuple`
path into one implementation, strip accumulator/volatile fields, impose a
total per-slot ordering, enumerate the `:plan-hash` inputs, and compute
`:run-hash` over the canonical epoch slice. It MUST be built **before**
anything consumes it (else the metamorphic gate is vacuous and
near-duplicate canonicalizers drift) and ship with an adversarial corpus
proving semantic differences change the hash while volatile fields do
not.

### One epoch tape, many projections

Run results, schema failures, narrative, semantic diffs, and run artifacts
SHOULD be **projections from the one epoch tape**, not separately
accumulated facts that can disagree. In particular, schema violations are
projected from `:rf.error/schema-validation-failure` trace events in the
tape rather than via a parallel per-frame accumulator — a second capture
path can drift from the trace evidence the UI already reads. This is the
substrate guarantee the Story narrative projection and schema-fail-the-run
rule (017 §Schema rule) depend on.

## Notes

### Why testing has its own Spec

Testing and stories share infrastructure (frames, overrides, drain, dispatch-sync) but have different requirements:

| Concern | Testing | Stories |
|---|---|---|
| Run mode | Headless, JVM or CLJS | Browser only (rendered) |
| Per-fixture rendering | Optional / skipped | Required |
| Decorators | Minimal | Rich (theme/auth/router/mocks) |
| Args / controls | No | Yes |
| Play functions | Sometimes (assertions) | Yes (interaction simulation) |
| Workspace layout | No | Yes |
| Tag system | Simple | Rich (`:dev`/`:docs`/`:test`/...) |
| Test-runner adapters | Primary client | No |
| Tool UI | None | Story-tool UI |

## Open questions

> **SA-4 classification.** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md): all three items are **post-v1, untracked notes** — design directions beyond v1 with no tracking bead filed yet (so none qualifies as `:post-v1 tracked`, which requires a `rf2-<id>`). "Snapshot / fixture serialization" — foundation exists; a packaged helper is user-space. "Property-based testing integration" — a pattern doc, no framework change. "Model-based testing harness over `machine-transition`" — library territory, not framework (the pure `machine-transition` contract is sufficient). A tracking bead is filed for each only when its reconsideration trigger below fires.

### Snapshot / fixture serialization (post-v1)

Some tests want to capture a frame's `app-db` and replay it later (golden-master testing, regression checks). Foundation supports this trivially (`(spit "fixture.edn" (pr-str (app-db-value f)))`); a helper is user-space. Deferred to a post-v1 cycle (untracked note — no bead filed yet).

#### Post-v1 Tracking

- **Foundation in v1.** `app-db-value` returns a plain value; `pr-str` / EDN reader round-trips it. No framework change is needed for the raw capture/replay path.
- **Scope deferred.** A packaged helper (`golden-master`, `regression-check`) with the ergonomic API (file-naming convention, diff rendering, `clojure.test`-style failure report) is user-space library work.
- **Reconsideration trigger.** A repeated pattern emerging across `examples/` or downstream tests that all hand-roll the same snapshot/diff scaffolding.
- **Out of scope for this note.** Cross-process replay (record-on-prod, replay-on-dev) — that wants the trace-buffer surface, not a snapshot helper.

### Property-based testing integration (post-v1)

`test.check`-style generative testing fits cleanly into re-frame2 — `make-frame` is cheap, generators produce event sequences, properties check invariants. Documented as a pattern post-v1. Deferred to a post-v1 cycle (untracked note — no bead filed yet).

#### Post-v1 Tracking

- **Foundation in v1.** `make-frame` is cheap and isolated; `dispatch-sync` settles synchronously per [Resolved decisions](#resolved-decisions); the schema-validator hook (Spec 010) gives invariants a place to live.
- **Scope deferred.** A guide-tier pattern document: generators for event sequences, invariants expressed as schemas, shrinking strategies for `dispatch-sequence` failures. No framework primitive missing.
- **Reconsideration trigger.** If schema-driven generation (per [010 §Schema-driven generative tests](010-Schemas.md#schema-driven-generative-tests-post-v1)) lands first, the pattern doc folds in directly.
- **Out of scope for this note.** A bundled `test.check` dependency — re-frame2 stays library-agnostic.

### Model-based testing harness over `machine-transition` (post-v1)

`@xstate/test`-style: treat a transition table as a graph and *generate* test cases automatically — paths, state-coverage, transition-coverage, shortest-path-to-state, guard-coverage. The pure `machine-transition` function makes this cheap; the transition contract is sufficient to build the harness externally without runtime changes. Deferred to a post-v1 cycle (untracked note — no bead filed yet).

#### Post-v1 Tracking

- **Foundation in v1.** `machine-transition` is pure and JVM-runnable; `:guards` and `:actions` are machine-scoped fns the harness can call directly; the corpus shape per [005 §Future — Model-based testing harness](005-StateMachines.md#model-based-testing-harness--re-framemachinestest) is locked.
- **Scope deferred.** The packaged library (`rf/test/machine-paths`, `rf/test/shortest-path-to`, coverage strategy selectors, EDN fixture emitter) ships as `re-frame.machines.test` post-v1.
- **Reconsideration trigger.** Either an AI-implementor needs the coverage corpus for cross-language conformance, or app-side machines start exhibiting edge-case bugs that hand-written tests miss.
- **Out of scope for this note.** Time-travel / step-debugger over the generated paths — separate concern, lives in the tool layer (xray/re-frame2-pair).
- **Cross-link.** See [005 §Future — Model-based testing harness](005-StateMachines.md#model-based-testing-harness--re-framemachinestest) for the substrate-side framing.

Sketch of the surface:

```clojure
(rf/test/machine-paths definition {:coverage :transition-coverage})
;; → seq of [<event-vec> ...] sequences that together visit every transition

(rf/test/shortest-path-to definition target-state)
;; → seq of event vectors that drives a fresh snapshot to target-state
```

Effectful actions (HTTP, dispatch) need stubbing in the harness — same pattern as `:fx-overrides`. The harness emits an EDN fixture corpus per machine, and tooling can ask "cover every transition" and receive deterministic test data back.

This is library territory, not framework. See [005 §Future](005-StateMachines.md#future) for the state-machine-side forward-pointer.

## Resolved decisions

### Built-in test-runner namespace

re-frame2 ships a `re-frame.test-support` convenience namespace (renamed from v1's `re-frame.test`). Users `(:require [re-frame.test-support :as ts])` to reach the fixture machinery and the test-flavoured helpers, paired with `(:require [re-frame.core :as rf])` for the dispatch / frame / sub primitives. `re-frame.test-support` does NOT re-export from `re-frame.core` — keeping the two namespaces separate preserves the rule that `re-frame.core` is the production primitive surface (used by application code) and `re-frame.test-support` is the test-only convenience surface (required only by test files). View-assertion test files additionally `:require [re-frame.test-helpers :as th]` per [§View-assertion helpers](#view-assertion-helpers-re-frametest-helpers).

The canonical helper inventory is the union of three namespaces:

| Helper | Namespace | Purpose |
|---|---|---|
| `with-frame`, `make-frame`, `destroy-frame!`, `reset-frame!`, `dispatch-sync`, `with-fx-overrides`, `app-db-value`, `snapshot-of`, `subscribe-once`, `compute-sub`, `sub-topology`, `machine-transition` | `re-frame.core` | Production primitives, also the testing entry points. Same defs the rest of the framework uses; tests reach them through `re-frame.core` (no re-export shim). `subscribe-once` is the **canonical read-then-discard primitive** — `(rf/subscribe-once [:query])` returns the current sub value and synchronously disposes its ref-count contribution (per [006 §`subscribe-once`](006-ReactiveSubstrate.md#subscribe-once-query-v--value--subscribe-once-frame-id-query-v--value)). Use it from JVM SSR pre-hydration assertions and CLJS post-hydration assertions alike: same call shape, same semantics, no live ratom returned. Pairs with `compute-sub` (pure / cache-bypassing JVM unit-test form) — pick `subscribe-once` when you want what the running frame would see right now (cache-aware), `compute-sub` when you want to assert sub-body correctness against an explicit `app-db` snapshot. |
| `dispatch-sequence` | `re-frame.test-support` | `(dispatch-sequence events)` / `(dispatch-sequence events opts)` — fires each event via `dispatch-sync` in order against the resolved frame. Returns the final `app-db` value. Optional `:after-each (fn [db ev] ...)` runs after each event's drain settles, useful for capturing intermediate state. Optional `:frame` defaults to `(current-frame-id)` (typically `:rf/default`). Equivalent to a `doseq` of `dispatch-sync` calls; reads better in tests. |
| `assert-path-equals` / `assert-db-equals` | `re-frame.test-support` | `(assert-path-equals path expected-val)` for a path check, `(assert-db-equals expected-db)` for a full-db check. Both shapes accept a trailing `{:frame ...}` opt. Mismatch fires a `clojure.test/is`-style failure (delivered via `do-report`). **The `assert-*-equals` fn-family shares a name root with the `:rf.assert/*` event-vector family** (`:rf.assert/path-equals`, `:rf.assert/sub-equals`, …) used inside a Story `:script` block — that surface lives in Spec 007 §Play functions (`:rf.assert/*` is registered, enumerable, and reserved under `:rf.assert/*` per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)). The fn-side is the in-process `clojure.test` sync surface (reports via `do-report`); the event-side is dispatches handled by the story library's test runner (rendered as a checked-step list in dev/docs, fail loudly in test mode, simulation breakpoints in agent mode). Same intent (db-shape assertion), shared `path-equals` root so a reader navigating between the two surfaces does not need a translation table — see [007 §Play functions](007-Stories.md#play-functions). |
| `poll-until` | `re-frame.test-support` | `(poll-until pred)` / `(poll-until pred opts)` — bounded-deadline poll for `(pred)` to be truthy. JVM returns the truthy value synchronously (throws `ex-info` carrying `:rf.error/id` `:rf.error/poll-until-timeout`, the canonical discriminator per [Spec 009](009-Instrumentation.md), on timeout); CLJS returns a `js/Promise` that resolves with the truthy value or rejects on timeout. Opts: `:timeout-ms` (default 2000), `:interval-ms` (default 5), `:label` (string/keyword for the timeout message). Replaces incidental fixed `Thread/sleep N` / `js/setTimeout` whose intent is "wait for an observable state change" — NOT for timer-semantics tests (grace-period elapse, throttle/debounce window, "prove a thing did NOT happen within window N"); those should keep their sleep and annotate that intent locally. |
| `snapshot-registrar`, `restore-registrar!`, `with-fresh-registrar`, `make-reset-runtime-fixture` | `re-frame.test-support` | Snapshot/restore the registrar (and per-process state — frames, flows, schemas, trace listeners) around a test or fixture. The standard `:each` fixture for re-frame2 test suites. `make-reset-runtime-fixture` is a **factory**: `(make-reset-runtime-fixture opts) → fixture-fn` returns the fn used in `(use-fixtures :each ...)`; the `make-…` prefix marks the call shape (contrast `with-fresh-registrar`, which takes a thunk and runs it directly). The four-rung granularity ladder is documented in [§Fixture-granularity ladder](#fixture-granularity-ladder). |
| `expand-tree`, `find-by-attr` / `find-all-by-attr` / `find-by-attr-prefix`, `find-by-testid` / `find-all-by-testid` / `find-by-testid-prefix`, `attrs`, `children`, `text-content`, `extract-handler`, `invoke-handler`, `testid` | `re-frame.test-helpers` | Hiccup-walk view-assertion surface — call the view-fn directly, walk the returned hiccup, assert on content or invoke a handler. JVM-runnable; no JSDOM, no React, no `act()`. Full inventory and contract: [§View-assertion helpers](#view-assertion-helpers-re-frametest-helpers). |
| `with-app-fixture`, `expect-text`, `wait-until` | `re-frame.test-helpers` | Single-frame e2e fixture trio. `with-app-fixture` brackets a body with a fresh frame + `:install` hook + `:root-view` stash; `expect-text` walks the stashed view for a testid'd node and asserts text content; `wait-until` polls a condition or a testid's text until a deadline elapses (JVM-sync / CLJS-Promise). Compresses the 5-line single-frame e2e pattern to 2 lines. See [§Pattern 5 — single-frame e2e fixture](#pattern-5--single-frame-e2e-fixture). |

This is the full surface. Anything else a test needs is composed from `dispatch-sync` / `app-db-value` / `compute-sub` / `machine-transition` directly — there is no hidden helper layer.

#### `dispatch-sequence` example

```clojure
(rf/reg-event :counter/inc (fn [{:keys [db]} _] {:db (update db :n inc)}))
(rf/reg-event :counter/dec (fn [{:keys [db]} _] {:db (update db :n dec)}))

(deftest counter-walk
  (rf/dispatch-sync [:counter/init])
  (let [final (ts/dispatch-sequence [[:counter/inc] [:counter/inc] [:counter/dec]])]
    (is (= 1 (:n final)))))
```

Capturing intermediate states:

```clojure
(let [seen (atom [])]
  (ts/dispatch-sequence [[:counter/inc] [:counter/inc]]
                        {:after-each (fn [db ev] (swap! seen conj [ev db]))}))
```

#### `assert-path-equals` / `assert-db-equals` example

```clojure
(rf/dispatch-sync [:auth/login-pressed])

;; path form — mirrors :rf.assert/path-equals
(ts/assert-path-equals [:auth :state] :validating)

;; full-db form — companion (no event analog)
(ts/assert-db-equals {:auth {:state :validating}})

;; with a non-default frame
(ts/assert-path-equals [:auth :state] :validating {:frame :test/auth-flow})
(ts/assert-db-equals   {:auth {:state :validating}} {:frame :test/auth-flow})
```

> **Two assertion surfaces sharing one name root — pick by test context.** `(ts/assert-path-equals path expected)` / `(ts/assert-db-equals expected-db)` is the sync `clojure.test`-aware fn-family for in-process tests (reports via `do-report`). The sibling surface is the `:rf.assert/*` event-vector family (`:rf.assert/path-equals`, `:rf.assert/sub-equals`, `:rf.assert/state-is`, `:rf.assert/dispatched?`, `:rf.assert/no-warnings`, `:rf.assert/effect-emitted`, `:rf.assert/path-matches`) used inside a Story `:script` block — see [007 §Play functions](007-Stories.md#play-functions) for the canonical vocabulary and its dual-mode behaviour (checked-step list in dev/docs, loud failures in test mode, simulation breakpoints in agent mode). Choose by test surface: `assert-path-equals` from a `deftest` body; `:rf.assert/path-equals` from a story variant's `:script` / `:assertions`. The shared `path-equals` root is deliberate — same intent (db-shape assertion), different runner/reporting channel; readers navigating between the two surfaces do not need a translation table.

#### `poll-until` example

Use for async settles whose post-condition is observable in state. Replaces incidental `Thread/sleep N` / `js/setTimeout` whose intent is "give the cascade time to drain". NOT a substitute for timer-semantics sleeps that prove behaviour within / past a specific window (grace, throttle, debounce, "no event fires within N ms").

JVM (synchronous — returns the truthy value, throws on timeout):

```clojure
(rf/dispatch [:cross-frame/fan-out])
(ts/poll-until #(= 3 (:count (rf/app-db-value :other-frame)))
               {:timeout-ms 5000 :label "fan-out reached :other-frame"})
(is (= 3 (:count (rf/app-db-value :other-frame))))
```

CLJS (returns a `js/Promise` — compose with `(.then ...)` under `cljs.test/async`):

```clojure
(deftest cross-frame-drain
  (async done
    (-> (ts/poll-until #(= 3 (:count (rf/app-db-value :other-frame)))
                       {:timeout-ms 5000 :label "fan-out drained"})
        (.then (fn [_]
                 (is (= 3 (:count (rf/app-db-value :other-frame))))
                 (done)))
        (.catch (fn [e]
                  (is false (str "poll-until timed out: " (.-message e)))
                  (done))))))
```

Timer-semantics sleeps that must stay (grace-period elapse, throttle/debounce, "prove no event fires within window N", host-clock advancement) keep their `Thread/sleep` / `js/setTimeout` but annotate the intent inline with a `;; Timer-semantics sleep: ...` comment so audits don't re-flag them.

### `re-frame-test` library compatibility

re-frame2 does **not** ship a `run-test-sync` shim — `dispatch-sync` is already settle-by-default, so the v1 macro was pure migration tax. The full disposition (the `run-test-sync` rewrite, the `dispatch-sequence` / `assert-state` → `assert-path-equals` + `assert-db-equals` split, and the `re-frame.test` → `re-frame.test-support` namespace rename) is stated once in [§`re-frame-test` (existing community library)](#re-frame-test-existing-community-library) under Test-framework adapters; this entry records it as a resolved decision and does not restate it.

### Headless rendering for visual regression

Spec 011 (SSR & Hydration) ships a pure hiccup → HTML string emitter that is JVM-runnable (per [011 §The render-tree → HTML emitter](011-SSR.md#the-render-tree--html-emitter-cljs-reference)). Snapshot tests, visual-regression diffs, and SSR conformance tests all use this emitter — `(rf/render-to-string view-or-hiccup {:frame f})` returns a string suitable for diffing without JSDOM. Tests that need React mount/commit lifecycle (interactive event firing) still require CLJS; everything else runs JVM-side.
