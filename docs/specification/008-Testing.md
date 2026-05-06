# EP 008 — Testing

> Status: Drafting. **v1-required.** A re-frame2 release without a coherent testing story is unshippable; this EP captures the v1 testing API and patterns. Forward-compatible with [EP 007 — Stories](007-Stories.md), which builds on this infrastructure post-v1.
>
> **Per [reorient.md](reorient.md):** the testing primitives in this EP — JVM-runnable handler invocation, headless sub computation, pure machine transitions — are *pattern-level* contracts, not CLJS-only conveniences. Other-language implementations supply equivalent headless-evaluation surfaces. The CLJS-specific framework adapters (`cljs.test`/`clojure.test`/`kaocha`/`re-frame-test` compatibility) are reference-implementation details. Note also that "JVM-runnable view rendering" — currently marked CLJS-only — moves across the line as EP 011 (SSR) lands; pure hiccup → string emission is JVM-runnable.

## Abstract

Testing is a [first-class principle](000-Vision.md#goals-summary) (Goal 10 per the reorient ordering — was G4) in re-frame2. This EP details the **v1 testing surface** — the concrete API, patterns, and adapter shape that re-frame2 ships so users can write small, fast, isolated tests without ceremony or global-state pollution.

The v1 testing surface is built entirely from foundation primitives in [002-Frames.md](002-Frames.md): `make-frame` / `destroy-frame`, `with-frame`, `dispatch-sync` with the opts map, per-frame and per-call overrides, the public registrar query API, drain semantics, and pure machine transition functions. This EP doesn't introduce new framework primitives — it documents how to compose the existing ones into a test-friendly experience.

## Why testing has its own EP

Testing and stories share infrastructure (frames, overrides, drain, dispatch-sync) but have different requirements:

| Concern | Testing (v1) | Stories (post-v1) |
|---|---|---|
| Run mode | Headless, JVM or CLJS | Browser only (rendered) |
| Per-fixture rendering | Optional / skipped | Required |
| Decorators | Minimal | Rich (theme/auth/router/mocks) |
| Args / controls | No | Yes |
| Play functions | Sometimes (assertions) | Yes (interaction simulation) |
| Workspace layout | No | Yes |
| Tag system | Simple | Rich (`:dev`/`:docs`/`:test`/...) |
| Test-runner adapters | Primary client | No |
| Tool UI | None | Storybook-class |

Their lifecycles also differ: testing ships in v1; stories may slip past v1 but must not be precluded by v1 testing decisions.

## v1 test surface

The concrete API available *in v1* for testing, satisfying [Goal 10 (Deterministic, testable runtime)](000-Vision.md#goals-summary):

| Need | API |
|---|---|
| Per-test frame fixture | `(rf/make-frame opts)` / `(rf/destroy-frame f)` |
| Scoped REPL/test block | `(rf/with-frame :scratch ...)` |
| Synchronous test trigger | `(rf/dispatch-sync event)` or `(rf/dispatch-sync event opts)` |
| Stub fx (per-call) | `(rf/dispatch-sync ev {:fx-overrides {:http stub-fn}})` |
| Stub fx (per-frame) | `(rf/reg-frame :test-frame {:fx-overrides {…}})` |
| Replace interceptor | `{:interceptor-overrides {:logger nil}}` per-call or per-frame |
| Add interceptor (recorder) | `(rf/reg-frame :test-frame {:interceptors [event-recorder]})` |
| Assertion: read app-db | `@(rf/get-frame-db :test-frame)` |
| Assertion: read snapshot | `(rf/snapshot-of [:auth :state-machine])` |
| Pure machine simulation | `(machine-transition definition snapshot event)` — no frame needed |
| Machine cleanup on destroy | `(rf/destroy-frame f)` — disposes sub-cache, stops router, clears overrides |
| Static sub-graph inspection | `(rf/sub-topology)` |
| Sub computation against an `app-db` | `(rf/compute-sub query-v db)` — query-v is `[:sub-id arg1 arg2]`, JVM-runnable |

All of these are JVM-runnable except where noted, per [C2 in 000](000-Vision.md#c2-cross-platform-jvm-interop-preserved).

## Test fixture lifecycle patterns

### Pattern 1 — anonymous fixture per test

The most common shape. Each test creates a frame, runs assertions, tears down.

```clojure
(rf/reg-event-db :auth/init-idle (fn [_ _] {:auth/state :idle}))

(deftest auth-flow
  (let [f (rf/make-frame {:on-create [:auth/init-idle]})]
    (try
      (rf/dispatch-sync [:auth/login-pressed] {:frame f})
      (is (= :validating (get-in @(rf/get-frame-db f) [:auth :state])))
      (finally
        (rf/destroy-frame f)))))
```

### Pattern 2 — `with-frame` for tighter blocks

For tests that don't need explicit teardown logic, `with-frame` handles the lifecycle:

```clojure
(deftest auth-flow
  (rf/with-frame [f (rf/make-frame {:on-create [:auth/init-idle]})]
    (rf/dispatch-sync [:auth/login-pressed])         ;; uses :frame f via with-frame's binding
    (is (= :validating (get-in @(rf/get-frame-db f) [:auth :state])))))
```

`with-frame` binds the frame for the body's duration, dispatch-syncs/subscribes inside the body resolve to that frame via the dynamic-var tier of the resolution chain, and the frame is destroyed on exit (success or exception).

### Pattern 3 — named fixture across many tests

For test groups that share setup, register a named test frame once and reset between tests:

```clojure
(use-fixtures :each
  (fn [test-fn]
    (rf/reg-frame :test-fixture {:on-create [:auth/init-idle]})   ;; create once
    (try
      (test-fn)
      (finally
        (rf/reset-frame :test-fixture)))))                          ;; reset between tests

(deftest one-thing
  (rf/dispatch-sync [:auth/login-pressed] {:frame :test-fixture})
  (is (= :validating (get-in @(rf/get-frame-db :test-fixture) [:auth :state]))))
```

`reset-frame` (per [002 §reset-frame](002-Frames.md#reset-frame--full-replace-opt-in)) clears `app-db` to `{}` and re-fires `:on-create`. State is fresh between tests; the registration cost is paid once.

### Pattern 4 — pure machine simulation (no frame)

For testing state machine transitions, skip the frame entirely:

```clojure
(deftest auth-machine-transitions
  (let [snap-1 {:state :idle :context {}}
        [snap-2 effects] (rf/machine-transition auth-machine-table snap-1
                                                [:auth/login-pressed])]
    (is (= :validating (:state snap-2)))
    (is (= [{:dispatch [:auth/check-credentials]}] effects))))
```

`machine-transition` is a pure function — no frame, no `app-db`, no router. Test the logic in isolation; integration tests cover the wiring.

## Per-test stubbing patterns

### Stubbing `:http` for an entire frame

```clojure
(rf/reg-frame :test/auth-flow
  {:on-create   [:auth/init-idle]
   :fx-overrides {:http (fn [_m _args] {:status 200 :body {:user/id 42}})}})

;; every event handled in :test/auth-flow uses the stub :http
```

### Stubbing `:http` for a single dispatch

```clojure
(rf/dispatch-sync [:auth/load-user]
                  {:frame :test/auth-flow
                   :fx-overrides {:http (fn [_m _args] {:status 401 :body "unauthorised"})}})
;; only this dispatch sees the 401 stub; subsequent events use the frame's default
```

### Disabling a logging interceptor in tests

```clojure
(rf/reg-frame :test/silent
  {:on-create             [:test/init]
   :interceptor-overrides {:my-app/logger nil}})       ;; nil removes the interceptor
```

### Recording dispatched events without firing handlers

```clojure
(def recorded (atom []))

(def event-recorder
  (rf/->interceptor
    :id :test/event-recorder
    :before (fn [ctx]
              (swap! recorded conj (-> ctx :coeffects :event))
              ctx)))

(rf/reg-frame :test/recorder-frame
  {:interceptors [event-recorder]})
```

After running a test sequence, `@recorded` contains the events that fired, in order. Useful for verifying control flow without checking every state transition.

## Headless evaluation

### Sub computation without the reactive runtime

`(rf/compute-sub query-v db)` runs the sub's body against the given `app-db` value and returns the computed value. No reactive cache, no Reagent, no JS runtime needed. JVM-runnable.

`query-v` is a vector — exactly the shape `subscribe` takes (`[:sub-id arg1 arg2]`).

The recommended pattern is to drive `db` state via dispatches against a fixture frame, then compute the sub against the resulting `app-db`. This tests the sub against state produced by the same code paths the application uses, and survives `app-db` schema changes — if `:items` becomes `:todos`, the events update, the sub updates, the test keeps working unmodified.

```clojure
(rf/reg-event-db :todos/add  (fn [db [_ todo]] (update db :items (fnil conj []) todo)))
(rf/reg-sub      :pending-todos
                 (fn [db _] (filter #(= :pending (:status %)) (:items db))))

(deftest pending-todos-sub
  (rf/with-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:todos/add {:id 1 :status :pending}])
    (rf/dispatch-sync [:todos/add {:id 2 :status :done}])
    (rf/dispatch-sync [:todos/add {:id 3 :status :pending}])
    (is (= 2 (count (rf/compute-sub [:pending-todos] @(rf/get-frame-db f)))))))
```

Composed subs (`:<-`) are computed transitively — the inputs are computed first, then the output. All without spinning up the reactive cache.

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

## Assertion patterns

### Reading app-db

```clojure
(is (= :validating (get-in @(rf/get-frame-db :test-frame) [:auth :state])))
(is (= 3 (count (get-in @(rf/get-frame-db :test-frame) [:items]))))
```

### Reading machine snapshots

```clojure
(is (= :authenticated (:state (rf/snapshot-of [:auth :state-machine] {:frame :test-frame}))))
```

### Asserting on effects (without firing them)

When you want to verify what *would* dispatch without actually running the cascade, stub the dispatch fx:

```clojure
(let [dispatched (atom [])
      f (rf/make-frame
          {:on-create   [:auth/init-idle]
           :fx-overrides {:dispatch (fn [_m ev] (swap! dispatched conj ev))}})]
  (try
    (rf/dispatch-sync [:auth/login-pressed] {:frame f})
    (is (= [[:auth/check-credentials]] @dispatched))
    (finally
      (rf/destroy-frame f))))
```

### Time travel — assertion after rewind

For tests that exercise event sequences and want to assert at intermediate points, dispatch one event at a time and assert between dispatches:

```clojure
(rf/with-frame [f (rf/make-frame {:on-create [:auth/init-idle]})]
  (rf/dispatch-sync [:auth/email-changed "alice@example.com"])
  (is (= "alice@example.com" (get-in @(rf/get-frame-db f) [:auth :form :email])))
  (rf/dispatch-sync [:auth/password-changed "hunter2"])
  (is (some? (get-in @(rf/get-frame-db f) [:auth :form :password])))
  (rf/dispatch-sync [:auth/login-pressed])
  (is (= :validating (get-in @(rf/get-frame-db f) [:auth :state]))))
```

Because run-to-completion drain settles each `dispatch-sync` before returning, assertions between dispatches reflect committed state — no race conditions.

## Test-framework adapters

The v1 surface is framework-agnostic — `make-frame` and friends work from any host that can require re-frame. Test framework integration is a thin layer of conventions per framework.

### cljs.test (CLJS)

```clojure
(require '[cljs.test :refer [deftest is testing use-fixtures]]
         '[re-frame.core :as rf])

(use-fixtures :each
  (fn [t]
    (rf/reg-frame :test-fixture {:on-create [:test/init]})
    (try (t) (finally (rf/destroy-frame :test-fixture)))))

(deftest example-test
  (rf/dispatch-sync [:some-event] {:frame :test-fixture})
  (is (= ... (get-in @(rf/get-frame-db :test-fixture) [...]))))
```

### clojure.test on the JVM

Identical shape, just the require:

```clojure
(require '[clojure.test :refer [deftest is testing use-fixtures]]
         '[re-frame.core :as rf])

;; same patterns as cljs.test — re-frame2's testing surface is JVM-runnable
;; for everything except view rendering and reactive subscription tracking
```

### Kaocha / Cognitect's test-runner

No special integration — works because `cljs.test` and `clojure.test` work. Kaocha picks up tests; the underlying re-frame2 fixtures function the same.

### `re-frame-test` (existing community library)

The current `re-frame-test` library (`day8/re-frame-test`) provides `run-test-sync` and similar helpers for today's re-frame. v1 should ship with a *re-frame2-aware* version (probably the same library, updated): `run-test-sync` becomes a thin wrapper over `with-frame` + `dispatch-sync`. Migration of existing `re-frame-test` users is mechanical — same API shape, frame-aware under the hood.

## JVM-runnable testing

The full v1 test surface is JVM-runnable, with the exceptions explicitly called out in [C2](000-Vision.md#c2-cross-platform-jvm-interop-preserved):

- ✓ `make-frame` / `destroy-frame` / `reset-frame` / `with-frame`
- ✓ `dispatch-sync` and the entire dispatch pipeline (router, drain, interceptors)
- ✓ All `reg-event-*` handler invocation
- ✓ Override application (`:fx-overrides`, `:interceptor-overrides`, `:interceptors`)
- ✓ `app-db` mutation and snapshot reading
- ✓ Cofx injection
- ✓ `machine-transition` (pure function)
- ✓ `compute-sub` (sub computation against an `app-db` value)
- ✓ Public registrar queries (`handlers`, `frame-meta`, `sub-topology`, etc.)
- ✓ **Hiccup → HTML string emission** (per [011](011-SSR.md)) — pure function over hiccup data, JVM-runnable. Snapshot tests, SSR conformance tests, and visual-regression diffs all run headlessly.
- ✗ React-actually-mounting (mount lifecycle, `:on-click` event firing into the real DOM, scroll events) — CLJS-only.
- ✗ Reactive subscription *tracking* (auto-subscribe-on-deref, dispose lifecycle) — CLJS-only. Subscription *computation* (running the body against an `app-db` value) is JVM-runnable via `compute-sub`.

In practice: every business-logic test runs on the JVM (faster, simpler test setup, no JS runtime). View *content* tests (does the rendered hiccup contain the expected text? does the structure match the schema?) also run on the JVM via `render-to-string`. Only tests that exercise actual React mounting, click events firing through DOM listeners, or scroll-position-style interactive behaviour need a CLJS runtime. The split is clean and SSR-friendly.

## Forward compatibility with stories

A forcing function: every API choice in 008 must not preclude what stories ([007](007-Stories.md), post-v1) will need on top. A test fixture is a story-variant minus the rendering — the story library's `run-variant` consumes the same primitives a test does (see [007 §Portable into tests](007-Stories.md#portable-into-tests)).

### Shapes 008 guarantees for 007

- `(make-frame {:on-create [:event-id] :fx-overrides {…} :interceptor-overrides {…} :interceptors [...]})` — exact opts shape.
- `(dispatch-sync ev {:frame f :fx-overrides {…}})` — exact opts shape.
- `(get-frame-db f)` — deref-able atom.
- `(snapshot-of path {:frame f})` — exact opts arg.
- `(destroy-frame f)` — exact teardown contract.
- Inclusion-tag schema is open (additive `set` on `reg-frame` metadata) — testing must not lock it to test-only values.

If 008's testing patterns ever require a *different* shape than 007 will need, that's a forcing-function violation and the design must change to align.

## Open questions

### T-1. ~~Built-in test-runner namespace?~~ — *resolved.*

re-frame2 ships a `re-frame.test` convenience namespace as part of v1. Mostly re-exports of `with-frame`, `make-frame`, `destroy-frame`, `dispatch-sync`, plus a small number of test-flavoured helpers (`dispatch-sequence` for chained dispatches; `assert-state` macro for asserting on a frame's `app-db` at a path). Lowers cognitive load for new users; matches `cljs.test` / `clojure.test` shape. See [Disposition](#disposition) below.

### T-2. ~~`re-frame-test` library compatibility~~ — *resolved.*

re-frame2 ships a compatibility wrapper for `day8/re-frame-test`'s `run-test-sync` API as part of v1. The wrapper delegates to `with-frame` + `dispatch-sync`. Existing test suites built against `re-frame-test` work unchanged after the migration; new tests use the v1 API directly. See [Disposition](#disposition) below.

### T-3. Snapshot / fixture serialization

Some tests want to capture a frame's `app-db` and replay it later (golden-master testing, regression checks). Foundation supports this trivially (`(spit "fixture.edn" (pr-str @(get-frame-db f)))`); the question is whether to ship a helper.

Recommendation: not in v1 framework; user-space.

### T-4. Property-based testing integration

`test.check`-style generative testing fits cleanly into re-frame2 — `make-frame` is cheap, generators produce event sequences, properties check invariants. Worth a documented pattern, not a special API.

Recommendation: document the pattern; don't add specific support.

### T-5. ~~Headless rendering for visual regression~~ — *resolved by EP 011.*

EP 011 (SSR & Hydration) ships a pure hiccup → HTML string emitter that is JVM-runnable (per [011 §The render-tree → HTML emitter](011-SSR.md#the-render-tree--html-emitter-cljs-reference)). Snapshot tests, visual-regression diffs, and SSR conformance tests all use this emitter — `(rf/render-to-string view-or-hiccup {:frame f})` returns a string suitable for diffing without JSDOM. Tests that need React mount/commit lifecycle (interactive event firing) still require CLJS; everything else runs JVM-side.

## Disposition

**v1.** Testing must ship in v1; the v1 framework provides every primitive listed above. The patterns and adapter shape are documented here. The `re-frame.test` namespace (T-1) and `re-frame-test` compatibility wrapper (T-2) ship as part of v1 — they're thin layers.

The forward-compatibility-with-stories section is the discipline check: any change to 008's surface must be vetted against what the post-v1 story library will need.
