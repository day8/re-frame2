# Spec 008 — Testing

> Forward-compatible with [Spec 007 — Stories](007-Stories.md), which builds on this infrastructure.
>
> The testing primitives in this Spec — JVM-runnable handler invocation, headless sub computation, pure machine transitions — are *pattern-level* contracts, not CLJS-only conveniences. Other-language implementations supply equivalent headless-evaluation surfaces. The CLJS-specific framework adapters (`cljs.test`/`clojure.test`/`kaocha`/`re-frame-test` compatibility) are reference-implementation details. Pure hiccup → string emission via `render-to-string` (per Spec 011) is JVM-runnable; React-driven view mounting is CLJS-only.

## Abstract

Testing is a [first-class principle](000-Vision.md#goals) (Goal 11) in re-frame2. This Spec details the **testing surface** — the concrete API, patterns, and adapter shape that re-frame2 ships so users can write small, fast, isolated tests without ceremony or global-state pollution.

The testing surface is built entirely from foundation primitives in [002-Frames.md](002-Frames.md): `make-frame` / `destroy-frame`, `with-frame`, `dispatch-sync` with the opts map, per-frame and per-call overrides, the public registrar query API, drain semantics, and pure machine transition functions. This Spec doesn't introduce new framework primitives — it documents how to compose the existing ones into a test-friendly experience.

## Normative surface

The concrete API for testing, satisfying [Goal 11 (Deterministic, testable runtime)](000-Vision.md#goals). Every entry below is a re-export from `re-frame.core` and gathered alongside the test-flavoured helpers in the convenience namespace `re-frame.test-support` (per [§Built-in test-runner namespace](#built-in-test-runner-namespace)) — the inventory is single-source-of-truth.

| Need | API |
|---|---|
| Per-test frame fixture | `(rf/make-frame opts)` / `(rf/destroy-frame f)` |
| Scoped REPL/test block | `(rf/with-frame :frame-id body...)` *or* `(rf/with-frame [sym expr] body...)` — see [§`with-frame` call shapes](#with-frame-call-shapes) |
| Synchronous test trigger | `(rf/dispatch-sync event)` or `(rf/dispatch-sync event opts)` |
| Stub fx (per-call) | `(rf/dispatch-sync ev {:fx-overrides {:my-app/http stub-fn}})` |
| Stub fx (per-frame) | `(rf/reg-frame :test-frame {:fx-overrides {…}})` |
| Replace interceptor | `{:interceptor-overrides {:logger nil}}` per-call or per-frame |
| Add interceptor (recorder) | `(rf/reg-frame :test-frame {:interceptors [event-recorder]})` |
| Assertion: read app-db | `(rf/get-frame-db :test-frame)` |
| Assertion: read snapshot | `@(rf/sub-machine :auth/state-machine)` (or `(rf/snapshot-of [:rf/machines :auth/state-machine])` for storage-layer assertions) |
| Pure machine simulation | `(machine-transition definition snapshot event)` — no frame needed |
| Machine cleanup on destroy | `(rf/destroy-frame f)` — disposes sub-cache, stops router, clears overrides |
| Static sub-graph inspection | `(rf/sub-topology)` |
| Sub computation against an `app-db` | `(rf/compute-sub query-v db)` — query-v is `[:sub-id arg1 arg2]`, JVM-runnable |
| Test-flavoured helpers | `(ts/dispatch-sequence events)` — chained `dispatch-sync`; `(ts/assert-state path expected)` — clojure.test-aware assertion. Both ship with `re-frame.test-support`. |

### `with-frame` call shapes

`with-frame` has **two canonical shapes**, both normative and both required of every host. The canonical definition lives in [002 §`with-frame`](002-Frames.md#with-frame); this section gathers the test-surface usage notes.

#### Shape 1 — bare keyword (operate on an existing frame)

```clojure
(rf/with-frame :scratch
  (rf/dispatch-sync [:init])
  @(rf/subscribe [:status]))
```

Pins `*current-frame*` to the supplied frame id for the body's dynamic extent. The frame is **not** created or destroyed by the macro — the keyword is used as-is. Used when the frame already exists (registered via `reg-frame` or created earlier via `make-frame`), e.g. shared fixtures across multiple `deftest` blocks, REPL sessions.

#### Shape 2 — binding-vector (create, use, destroy)

```clojure
(rf/with-frame [binding-sym expr] body...)
```

Evaluates `expr` (typically `(rf/make-frame opts)`), binds the result to `binding-sym` (so the body can refer to it for `get-frame-db`, `dispatch-sync` opts, etc.), sets that frame as the implicit `*current-frame*` for the body's dynamic extent (so `dispatch-sync` and `subscribe` inside the body resolve to it without needing `{:frame ...}`), and on body exit (success or exception) calls `destroy-frame` on whatever was bound. Modelled on `with-open`. Used when the frame's lifetime is exactly the body — per-test fixtures, devcard widgets, REPL sessions wanting guaranteed teardown.

#### Discriminator

The macro inspects its first argument:

- Keyword → Shape 1.
- Vector `[sym expr]` → Shape 2.

Both shapes are part of the normative test surface; tests, fixtures, and helper macros MAY freely use either, and hosts MUST support both.

### JVM-runnable boundary (authoritative)

Every entry in the table above is JVM-runnable, with the exceptions listed below — this is the single authoritative statement of the test-surface's JVM/CLJS split, per [C2](000-Vision.md#c2-cross-platform-jvm-interop-preserved):

- ✓ `make-frame` / `destroy-frame` / `reset-frame` / `with-frame`
- ✓ `dispatch-sync` and the entire dispatch pipeline (router, drain, interceptors)
- ✓ All `reg-event-*` handler invocation
- ✓ Override application (`:fx-overrides`, `:interceptor-overrides`, `:interceptors`)
- ✓ `app-db` mutation and snapshot reading
- ✓ Cofx injection
- ✓ `machine-transition` (pure function)
- ✓ `compute-sub` (sub computation against an `app-db` value)
- ✓ Public registrar queries (`registrations`, `frame-meta`, `sub-topology`, etc.)
- ✓ **Hiccup → HTML string emission** (per [011](011-SSR.md)) — pure function over hiccup data, JVM-runnable. Snapshot tests, SSR conformance tests, and visual-regression diffs all run headlessly.
- ✗ React-actually-mounting (mount lifecycle, `:on-click` event firing into the real DOM, scroll events) — CLJS-only.
- ✗ Reactive subscription *tracking* (auto-subscribe-on-deref, dispose lifecycle) — CLJS-only. Subscription *computation* (running the body against an `app-db` value) is JVM-runnable via `compute-sub`.

In practice: every business-logic test runs on the JVM. View *content* tests (does the rendered hiccup contain the expected text? does the structure match the schema?) also run on the JVM via `render-to-string`. Only tests that exercise actual React mounting, click events firing through DOM listeners, or scroll-position-style interactive behaviour need a CLJS runtime. The split is clean and SSR-friendly.

## Test fixture lifecycle patterns

### Pattern 1 — anonymous fixture per test

The most common shape. Each test creates a frame, runs assertions, tears down.

```clojure
(rf/reg-event-db :auth/init-idle (fn [_ _] {:auth/state :idle}))

(deftest auth-flow
  (let [f (rf/make-frame {:on-create [:auth/init-idle]})]
    (try
      (rf/dispatch-sync [:auth/login-pressed] {:frame f})
      (is (= :validating (get-in (rf/get-frame-db f) [:auth :state])))
      (finally
        (rf/destroy-frame f)))))
```

### Pattern 2 — `with-frame` for tighter blocks

For tests that don't need explicit teardown logic, `with-frame` handles the lifecycle:

```clojure
(deftest auth-flow
  (rf/with-frame [f (rf/make-frame {:on-create [:auth/init-idle]})]
    (rf/dispatch-sync [:auth/login-pressed])         ;; uses :frame f via with-frame's binding
    (is (= :validating (get-in (rf/get-frame-db f) [:auth :state])))))
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
  (is (= :validating (get-in (rf/get-frame-db :test-fixture) [:auth :state]))))
```

`reset-frame` (per [002 §reset-frame](002-Frames.md#reset-frame--full-replace-opt-in)) clears `app-db` to `{}` and re-fires `:on-create`. State is fresh between tests; the registration cost is paid once.

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

`machine-transition` is a pure function — no frame, no `app-db`, no router. Test the logic in isolation; integration tests cover the wiring. See [005 §Testing](005-StateMachines.md#testing) for the full three-level test pyramid (pure `machine-transition`, unregistered handler fn from `create-machine-handler`, registered in test frame).

## Per-test stubbing patterns

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
    (is (= 2 (count (rf/compute-sub [:pending-todos] (rf/get-frame-db f)))))))
```

Composed subs (`:<-`) are computed transitively — the inputs are computed first, then the output. All without spinning up the reactive cache.

#### `compute-sub` algorithm

`compute-sub` is **pure**: same `(query-v, db)` always returns the same value. No reactive cache, no Reagent reactions, no `app-db` deref — the function takes `db` as a value argument.

Pseudocode (the contract every implementation matches):

```
compute-sub(query-v, db):
  let sub-id   = head(query-v)
  let reg      = handler-meta(:sub, sub-id)
  if reg is nil:
     emit :rf.error/no-such-sub trace; return nil       ; per 009 default :replaced-with-default

  ; Resolve inputs first (the chained-sub case).
  let inputs = match reg.signal-fn:
                 nil                     -> nil          ; root sub: body reads db directly
                 [:<- input-query-vs]    -> map (q -> compute-sub(q, db)) input-query-vs
                 fn                      -> resolve-signal-result((signal-fn db query-v), db)

  ; Run the body with resolved inputs (or with db, for root subs).
  return reg.computation-fn(inputs-or-db, query-v)
```

Notes on the contract:

- **Recursive resolution.** `compute-sub` recursively calls itself on each input `query-v`. Layered subs (`A` ← `B` ← `C`) resolve depth-first: `C` is computed first against `db`, then `B` against `[C-value]`, then `A` against `[B-value]`. Each layer's output is passed as a flat positional list to the next layer's `computation-fn`, exactly mirroring how Reagent's `make-reaction` chains compose `:<-` inputs.
- **No memoisation across calls.** `compute-sub` is a pure function over `(query-v, db)`. Implementations may memoise *within* a single call (the same input sub appearing twice in one tree is computed once and reused) but **must not** carry a cache between calls — it is not a substitute for the reactive runtime, and an `app-db` value that has changed must produce a fresh result. Per-call memoisation is an optimisation; tests must not depend on it.
- **No cycles.** A cycle in the static `:<-` topology is a registration-time error (per [001](001-Registration.md)); `compute-sub` does not need to detect cycles at call time. If a host bypasses the registration-time check, `compute-sub` may stack-overflow — surface a structured error trace if cheap; otherwise let the host's stack overflow propagate.
- **Errors.** If a sub's `computation-fn` throws, emit `:rf.error/sub-exception` per [009 §Error contract](009-Instrumentation.md#error-contract); default recovery `:replaced-with-default` returns `nil`. An unresolved input sub (`:rf.error/no-such-sub`) substitutes `nil` and the body still runs (default `:replaced-with-default`).
- **Determinism.** `compute-sub` is JVM-runnable, deterministic, and free of side effects. It is the function the conformance corpus invokes for `:sub-values` assertions per [conformance/README.md](conformance/README.md).

The function form for `:<-` matches Reagent's existing `subs/reg-sub` semantics — the resolved input *values* are passed positionally to the body, not the input `query-v`s. The outer `query-v` (the one being computed) remains the second argument to `computation-fn`, identical to in-runtime behaviour.

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
(is (= :validating (get-in (rf/get-frame-db :test-frame) [:auth :state])))
(is (= 3 (count (get-in (rf/get-frame-db :test-frame) [:items]))))
```

### Reading machine snapshots

```clojure
(is (= :authenticated (:state @(rf/sub-machine :auth/state-machine {:frame :test-frame}))))
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
  (is (= "alice@example.com" (get-in (rf/get-frame-db f) [:auth :form :email])))
  (rf/dispatch-sync [:auth/password-changed "hunter2"])
  (is (some? (get-in (rf/get-frame-db f) [:auth :form :password])))
  (rf/dispatch-sync [:auth/login-pressed])
  (is (= :validating (get-in (rf/get-frame-db f) [:auth :state]))))
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
    (try (t) (finally (rf/destroy-frame :test-fixture)))))

(deftest example-test
  (rf/dispatch-sync [:some-event] {:frame :test-fixture})
  (is (= ... (get-in (rf/get-frame-db :test-fixture) [...]))))
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

The `day8/re-frame-test` library provides `run-test-sync` and similar helpers. re-frame2 does **not** ship a `run-test-sync` shim — the macro existed in v1 to wrap test bodies in a synchronous drain, and v2's `dispatch-sync` is already settle-by-default, so the shim was pure migration tax. Existing test suites built against `re-frame-test` rewrite the `run-test-sync` body to inline `dispatch-sync` calls under the standard per-test `reset-runtime-fixture` (or `with-fresh-registrar` for ad-hoc bodies) — see [MIGRATION §M-52](MIGRATION.md#m-52-run-test-sync-removed--use-dispatch-sync-under-reset-runtime-fixture). The other two re-frame-test helpers — `dispatch-sequence` and `assert-state` — keep their v1 names and ship in `re-frame.test-support`; the move is a mechanical `re-frame.test` → `re-frame.test-support` require rewrite per [MIGRATION §M-25](MIGRATION.md#m-25-re-frametest-helpers-renamed-to-re-frametest-support).

## Forward compatibility with stories

A test fixture is a story-variant minus the rendering — the story library's `run-variant` consumes the same primitives a test does (see [007 §Portable into tests](007-Stories.md#portable-into-tests)). The testing surface guarantees these shapes for 007:

- `(make-frame {:on-create [:event-id] :fx-overrides {…} :interceptor-overrides {…} :interceptors [...]})` — exact opts shape.
- `(dispatch-sync ev {:frame f :fx-overrides {…}})` — exact opts shape.
- `(get-frame-db f)` — current `app-db` value (a plain map) for the named frame.
- `(snapshot-of path {:frame f})` — exact opts arg.
- `(destroy-frame f)` — exact teardown contract.
- Inclusion-tag schema is open (additive `set` on `reg-frame` metadata).

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

### Snapshot / fixture serialization

Some tests want to capture a frame's `app-db` and replay it later (golden-master testing, regression checks). Foundation supports this trivially (`(spit "fixture.edn" (pr-str (get-frame-db f)))`); a helper is user-space.

### Property-based testing integration

`test.check`-style generative testing fits cleanly into re-frame2 — `make-frame` is cheap, generators produce event sequences, properties check invariants. Documented as a pattern.

### Model-based testing harness over `machine-transition`

`@xstate/test`-style: treat a transition table as a graph and *generate* test cases automatically — paths, state-coverage, transition-coverage, shortest-path-to-state, guard-coverage. The pure `machine-transition` function makes this cheap; the transition contract is sufficient to build the harness externally without runtime changes.

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

re-frame2 ships a `re-frame.test-support` convenience namespace (renamed from v1's `re-frame.test` per rf2-8hcb). Users `(:require [re-frame.test-support :as ts])` once and reach the full testing surface. The canonical helper inventory is:

| Helper | Origin | Purpose |
|---|---|---|
| `with-frame`, `make-frame`, `destroy-frame`, `reset-frame`, `dispatch-sync`, `get-frame-db`, `snapshot-of`, `compute-sub`, `sub-topology`, `machine-transition` | re-export from `re-frame.core` | Same primitives the rest of the framework uses; gathered here for one require. |
| `dispatch-sequence` | test-flavoured fn | `(dispatch-sequence events)` / `(dispatch-sequence events opts)` — fires each event via `dispatch-sync` in order against the resolved frame. Returns the final `app-db` value. Optional `:after-each (fn [db ev] ...)` runs after each event's drain settles, useful for capturing intermediate state. Optional `:frame` defaults to `(current-frame)` (typically `:rf/default`). Equivalent to a `doseq` of `dispatch-sync` calls; reads better in tests. |
| `assert-state` | test-flavoured fn | `(assert-state expected-db)` for a full-db check, or `(assert-state path expected-val)` for a path check. Both shapes accept a trailing `{:frame ...}` opt. Mismatch fires a `clojure.test/is`-style failure (delivered via `do-report`). |
| `snapshot-registrar`, `restore-registrar!`, `with-fresh-registrar`, `reset-runtime-fixture` | fixture machinery | Snapshot/restore the registrar (and per-process state — frames, flows, schemas, trace listeners) around a test or fixture. The standard `:each` fixture for re-frame2 test suites. |

This is the full surface. Anything else a test needs is composed from `dispatch-sync` / `get-frame-db` / `compute-sub` / `machine-transition` directly — there is no hidden helper layer.

#### `dispatch-sequence` example

```clojure
(rf/reg-event-db :counter/inc (fn [db _] (update db :n inc)))
(rf/reg-event-db :counter/dec (fn [db _] (update db :n dec)))

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

#### `assert-state` example

```clojure
(rf/dispatch-sync [:auth/login-pressed])
(ts/assert-state [:auth :state] :validating)
;; or full-db form:
(ts/assert-state {:auth {:state :validating}})
;; with a non-default frame:
(ts/assert-state [:auth :state] :validating {:frame :test/auth-flow})
```

### `re-frame-test` library compatibility

re-frame2 does **not** ship a `run-test-sync` shim — the macro existed in v1 to wrap a test body in a synchronous drain, and v2's `dispatch-sync` is already settle-by-default, so the shim was pure migration tax (rf2-u3w8j). Existing `re-frame-test` users rewrite the body to inline `dispatch-sync` calls under the per-test `reset-runtime-fixture` (or `with-fresh-registrar` for ad-hoc bodies); see [MIGRATION §M-52](MIGRATION.md#m-52-run-test-sync-removed--use-dispatch-sync-under-reset-runtime-fixture). The other two re-frame-test helpers — `dispatch-sequence` and `assert-state` — keep their v1 names and ship in `re-frame.test-support`; the move is a mechanical `re-frame.test → re-frame.test-support` namespace rename per [MIGRATION §M-25](MIGRATION.md#m-25-re-frametest-helpers-renamed-to-re-frametest-support).

### Headless rendering for visual regression

Spec 011 (SSR & Hydration) ships a pure hiccup → HTML string emitter that is JVM-runnable (per [011 §The render-tree → HTML emitter](011-SSR.md#the-render-tree--html-emitter-cljs-reference)). Snapshot tests, visual-regression diffs, and SSR conformance tests all use this emitter — `(rf/render-to-string view-or-hiccup {:frame f})` returns a string suitable for diffing without JSDOM. Tests that need React mount/commit lifecycle (interactive event firing) still require CLJS; everything else runs JVM-side.
