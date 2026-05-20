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
| `re-frame.core` | Production primitives, also the testing entry points | `make-frame`, `destroy-frame!`, `reset-frame!`, `with-frame`, `dispatch-sync`, `with-fx-overrides`, `get-frame-db`, `snapshot-of`, `sub-topology`, `compute-sub`, `machine-transition` |
| `re-frame.test-support` | Test-only fixture machinery + test-flavoured helpers | `snapshot-registrar`, `restore-registrar!`, `with-fresh-registrar`, `reset-runtime-fixture`, `dispatch-sequence`, `assert-state`, `poll-until` |
| `re-frame.test-helpers` | View-assertion helpers (hiccup-walk + `testid` authoring) + single-frame e2e fixture (rf2-wy1ac) | `expand-tree`, `find-by-attr` / `find-all-by-attr` / `find-by-attr-prefix`, `find-by-testid` / `find-all-by-testid` / `find-by-testid-prefix`, `attrs`, `children`, `text-content`, `extract-handler`, `invoke-handler`, `testid`, `with-app-fixture`, `expect-text`, `wait-until` |

`re-frame.test-support` does **not** re-export from `re-frame.core` — a test file requires both namespaces (`[re-frame.core :as rf]` for primitives, `[re-frame.test-support :as ts]` for fixture machinery and helpers). View-assertion test files additionally `:require [re-frame.test-helpers :as th]`. The split is deliberate: `re-frame.core` carries surfaces that compose into production code paths as well as tests; `re-frame.test-support` is a require-gated test-only convenience surface; `re-frame.test-helpers` is the view-assertion surface used only by tests (per [§View-assertion helpers](#view-assertion-helpers-re-frametest-helpers)).

| Need | API |
|---|---|
| Per-test frame fixture | `(rf/make-frame opts)` / `(rf/destroy-frame! f)` |
| Scoped REPL/test block | `(rf/with-frame :frame-id body...)` *or* `(rf/with-frame [sym expr] body...)` — see [§`with-frame` call shapes](#with-frame-call-shapes) |
| Synchronous test trigger | `(rf/dispatch-sync event)` or `(rf/dispatch-sync event opts)` |
| Stub fx (per-call) | `(rf/dispatch-sync ev {:fx-overrides {:my-app/http stub-fn}})` |
| Stub fx (per-frame) | `(rf/reg-frame :test-frame {:fx-overrides {…}})` |
| Replace interceptor | `{:interceptor-overrides {:logger nil}}` per-call or per-frame |
| Add interceptor (recorder) | `(rf/reg-frame :test-frame {:interceptors [event-recorder]})` |
| Assertion: read app-db | `(rf/get-frame-db :test-frame)` |
| Assertion: read snapshot | `@(rf/sub-machine :auth/state-machine)` (or `(rf/snapshot-of [:rf/machines :auth/state-machine])` for storage-layer assertions) |
| Pure machine simulation | `(machine-transition definition snapshot event)` — no frame needed |
| Machine cleanup on destroy | `(rf/destroy-frame! f)` — disposes sub-cache, stops router, clears overrides |
| Static sub-graph inspection | `(rf/sub-topology)` |
| Sub computation against an `app-db` | `(rf/compute-sub query-v db)` — query-v is `[:sub-id arg1 arg2]`, JVM-runnable |
| Test-flavoured helpers | `(ts/dispatch-sequence events)` — chained `dispatch-sync`; `(ts/assert-state path expected)` — clojure.test-aware assertion (distinct from the `:rf.assert/*` event-vector family used inside Story `:play` blocks — see [007 §Play functions](007-Stories.md#play-functions)). Both ship with `re-frame.test-support`. |
| Single-frame e2e fixture | `(th/with-app-fixture {:install f :root-view v} :frame-id body...)` — create + bind frame, run `:install`, stash `:root-view`, destroy on exit. Pair with `(th/expect-text testid expected)` and `(th/wait-until pred-or-testid expected)` for the two-line single-frame test pattern (rf2-wy1ac). |

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

Evaluates `expr` (typically `(rf/make-frame opts)`), binds the result to `binding-sym` (so the body can refer to it for `get-frame-db`, `dispatch-sync` opts, etc.), sets that frame as the implicit `*current-frame*` for the body's dynamic extent (so `dispatch-sync` and `subscribe` inside the body resolve to it without needing `{:frame ...}`), and on body exit (success or exception) calls `destroy-frame!` on whatever was bound. Modelled on `with-open`. Used when the frame's lifetime is exactly the body — per-test fixtures, devcard widgets, REPL sessions wanting guaranteed teardown.

#### Discriminator

The macro inspects its first argument:

- Keyword → Shape 1.
- Vector `[sym expr]` → Shape 2.

Both shapes are part of the normative test surface; tests, fixtures, and helper macros MAY freely use either, and hosts MUST support both.

### JVM-runnable boundary (authoritative)

Every entry in the table above is JVM-runnable, with the exceptions listed below — this is the single authoritative statement of the test-surface's JVM/CLJS split, per [C2](000-Vision.md#c2-cross-platform-jvm-interop-preserved):

- ✓ `make-frame` / `destroy-frame!` / `reset-frame!` / `with-frame`
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
(rf/reg-event-db :auth/init-idle (fn [_ _] {:auth/state :idle}))

(deftest auth-flow
  (let [f (rf/make-frame {:on-create [:auth/init-idle]})]
    (try
      (rf/dispatch-sync [:auth/login-pressed] {:frame f})
      (is (= :validating (get-in (rf/get-frame-db f) [:auth :state])))
      (finally
        (rf/destroy-frame! f)))))
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
        (rf/reset-frame! :test-fixture)))))                          ;; reset between tests

(deftest one-thing
  (rf/dispatch-sync [:auth/login-pressed] {:frame :test-fixture})
  (is (= :validating (get-in (rf/get-frame-db :test-fixture) [:auth :state]))))
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

`machine-transition` is a pure function — no frame, no `app-db`, no router. Test the logic in isolation; integration tests cover the wiring. See [005 §Testing](005-StateMachines.md#testing) for the full three-level test pyramid (pure `machine-transition`, unregistered handler fn from `create-machine-handler`, registered in test frame).

### Pattern 5 — single-frame e2e fixture (rf2-wy1ac)

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
3. Calls the `:install` fn (zero-arg) inside the frame's scope. Typical body: `reg-event-db` / `reg-sub` / `reg-view` calls that the test relies on. Registrations land in the global registrar; pair with `re-frame.test-support/reset-runtime-fixture` (or `with-fresh-registrar`) to roll them back between tests.
4. Stashes the `:root-view` fn in `*current-root-view*` so `expect-text` / `wait-until`'s testid form can find it without an explicit tree argument. `:root-view-args` (default `[]`) rides into `*current-root-view-args*` for views that take a props map.
5. Runs `body`.
6. In a `finally`, destroys the frame regardless of whether `body` returned normally or threw — no leaked frames across tests.

`opts-map` keys (all optional): `:install`, `:root-view`, `:root-view-args`, `:frame-config` (passed through to `make-frame` / `reg-frame` — `:on-create`, `:fx-overrides`, `:interceptor-overrides`, `:interceptors` and the rest of the frame-shape contract per [Spec 002 §`reg-frame`](002-Frames.md#reg-frame--atomic-create-and-register-and-the-canonical-metadata-grammar)).

The companion helpers:

- **`(th/expect-text testid expected)`** — 2-arity: render the fixture-stashed root view, walk for `:data-testid testid`, assert `(text-content node) = expected`. Reports `:pass` / `:fail` via `clojure.test/is`. The 3-arity `(expect-text tree testid expected)` takes an explicit hiccup tree — useful for view-only tests that don't need a full fixture. `testid` may be a keyword (`:counter-display`, coerced via `name`) or a string.
- **`(th/wait-until pred-or-testid)`** / **`(th/wait-until testid expected)`** / **`(th/wait-until pred opts)`** / **`(th/wait-until testid expected opts)`** — bounded-deadline poll for async-stable assertions. The view-test counterpart to `re-frame.test-support/poll-until`: same per-platform shape (JVM-synchronous returning the truthy value; CLJS-async returning a `js/Promise` that resolves on success and rejects on timeout). The testid form polls the fixture-stashed root view until the text matches; the predicate form polls an arbitrary fn. `opts`: `:timeout-ms` (default 2000), `:interval-ms` (default 5), `:label`. Use for async event flows (HTTP, scheduled events, machine `:after` transitions) that drain past `dispatch-sync`. Timer-semantics sleeps (grace-period elapse, throttle/debounce window) keep their explicit sleep and annotate the intent locally — `wait-until` is for *settles*, not *windows*.

When NOT to use Pattern 5:

- **Multi-frame setups** (Causa, Story, cross-frame tests) — Pattern 1 / 2 with explicit `rf/with-frame` calls each frame is clearer; the fixture stash is single-slot by design.
- **Tests that don't render** — the install + frame lifecycle of Pattern 5 is overkill for pure-event tests. Reach for `(rf/with-frame [f (rf/make-frame opts)] ...)` and skip the view-stash entirely.

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
| `with-app-fixture` | Macro | `(with-app-fixture opts-map frame-id body+)` / `(with-app-fixture opts-map body+)` | Single-frame e2e fixture (rf2-wy1ac). Creates the frame, binds `*current-frame*` for the body's dynamic extent, calls `:install` (zero-arg) inside the scope, stashes `:root-view` / `:root-view-args` for `expect-text` / `wait-until`, and destroys the frame on exit (success or exception). Frame-id is positional and optional; omitting it gensym's an anonymous `:rf.frame/*` id. Opts keys: `:install`, `:root-view`, `:root-view-args`, `:frame-config` (passed through to `make-frame` / `reg-frame`). See [§Pattern 5 — single-frame e2e fixture](#pattern-5--single-frame-e2e-fixture-rf2-wy1ac). |
| `expect-text` | Fn | `(expect-text testid expected)` / `(expect-text tree testid expected) → bool?` | Locate `:data-testid testid` in the (fixture-stashed) root view's rendered hiccup and assert `(text-content node) = expected` via `clojure.test/is` (`do-report`). `testid` accepts a keyword (coerced via `name`) or a string. The 2-arity reads the fixture-stashed root view from `*current-root-view*`; the 3-arity walks an explicit tree. Throws (with a clear `ex-info` message) if neither a fixture nor an explicit tree is present. |
| `wait-until` | Fn | `(wait-until pred)` / `(wait-until pred opts)` / `(wait-until testid expected)` / `(wait-until testid expected opts)` | Bounded-deadline poll for async-stable assertions. JVM: synchronous — returns the truthy value, throws `ex-info` with `:rf.test-helpers/wait-timeout true` on timeout. CLJS: returns a `js/Promise` that resolves with the truthy value or rejects on timeout. The testid form polls the fixture-stashed root view until `(text-content (find-by-testid tree testid)) = expected`; the predicate form polls an arbitrary fn. `opts`: `:timeout-ms` (2000), `:interval-ms` (5), `:label`. Sister of `re-frame.test-support/poll-until` — same shape, tuned for the hiccup-walk pattern. |

### Function-component expansion

Reagent hiccup admits a function in the first slot of a vector — `[my-component {...}]` — and lazily invokes it during render. The walkers expand nested function components by calling them with their args (just like Reagent's renderer would) before walking, so a test that calls a parent view-fn sees the leaf hiccup the user sees. Expansion is recursive but terminating: a non-vector / non-fn leaf is a fixed point.

Form-3 components built via `r/create-class` are detected (the reagent-slim class tag + the stashed `:reagent-render` slot) and expanded by invoking the render fn directly with the hiccup args. **The walker does NOT instantiate React or run lifecycle methods** — if a Form-3 view's hiccup output depends on lifecycle state (`componentDidMount`-style behaviour), the test sees the initial render only. JVM runs identically: class-3 detection is a no-op because the JVM has no JS class instances.

### Selector convention — `:data-testid` vs `:data-test` vs custom

React conventionally uses `:data-testid`; some codebases (notably Story) standardised on `:data-test` before the rename; framework tools may use their own prefix (Causa uses `:data-rf-causa-*`). The namespace ships two layers:

- `find-by-attr` / `find-all-by-attr` / `find-by-attr-prefix` — the underlying. Match against any attr key the caller supplies. Use directly when the codebase keys on `:data-test` or a custom attribute.
- `find-by-testid` / `find-all-by-testid` / `find-by-testid-prefix` — thin wrappers that pre-bind the attr to `:data-testid`. Use for the common React-convention case.

The Conventions doc does not pin one form as canonical — the framework's view-test seam is the generic `find-by-attr` family, and `find-by-testid` is the recommended convenience for the React-conventional case.

### Examples

Drive a click and assert state changed downstream:

```clojure
(deftest counter-inc
  (rf/with-frame [_ (rf/make-frame {:on-create [:counter/init]})]
    (let [tree (counter-view {})
          btn  (th/find-by-testid tree "counter-inc")]
      (th/invoke-handler btn :on-click)
      (is (= 1 (:n (rf/get-frame-db (rf/current-frame))))))))
```

Assert rendered text after dispatching:

```clojure
(deftest counter-label
  (rf/with-frame [f (rf/make-frame {:on-create [:counter/init]})]
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

Every helper in `re-frame.test-helpers` is JVM-runnable. The hiccup-walk core (everything pre-rf2-wy1ac) is classpath-clean against `clojure.string` alone; the fixture trio (`with-app-fixture` / `expect-text` / `wait-until`) additionally reaches `re-frame.frame` (for `reg-frame` / `make-frame` / `destroy-frame!` / `*current-frame*`) and `clojure.test` / `cljs.test` (for `expect-text`'s `do-report` failure path). The fixture deps are framework-internal — they do not pull React, Reagent, or any substrate adapter into the classpath. The reagent-slim Form-3 detection uses a reader-conditional (`#?(:cljs ...)`) that's a no-op on the JVM (the JVM has no `.-cljsReagentClass` property access on plain fns), so Form-3 expansion is a CLJS-only optimisation and JVM tests see the same hiccup tree. `wait-until`'s per-platform shape is reader-conditional (JVM synchronous; CLJS `js/Promise`) per [§Pattern 5](#pattern-5--single-frame-e2e-fixture-rf2-wy1ac).

This complements the JVM-runnable list in [§Normative surface §JVM-runnable boundary](#jvm-runnable-boundary-authoritative): hiccup-walk joins `render-to-string` as a JVM-runnable view-test path.

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
      (rf/destroy-frame! f))))
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
    (try (t) (finally (rf/destroy-frame! :test-fixture)))))

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

The `day8/re-frame-test` library provides `run-test-sync` and similar helpers. re-frame2 does **not** ship a `run-test-sync` shim — the macro existed in v1 to wrap test bodies in a synchronous drain, and v2's `dispatch-sync` is already settle-by-default, so the shim was pure migration tax. Existing test suites built against `re-frame-test` rewrite the `run-test-sync` body to inline `dispatch-sync` calls under the standard per-test `reset-runtime-fixture` (or `with-fresh-registrar` for ad-hoc bodies) — see [MIGRATION §M-52](../migration/from-re-frame-v1/README.md#m-52-run-test-sync-removed--use-dispatch-sync-under-reset-runtime-fixture). The other two re-frame-test helpers — `dispatch-sequence` and `assert-state` — keep their v1 names and ship in `re-frame.test-support`; the move is a mechanical `re-frame.test` → `re-frame.test-support` require rewrite per [MIGRATION §M-25](../migration/from-re-frame-v1/README.md#m-25-re-frametest-helpers-renamed-to-re-frametest-support).

## Forward compatibility with stories

A test fixture is a story-variant minus the rendering — the story library's `run-variant` consumes the same primitives a test does (see [007 §Portable into tests](007-Stories.md#portable-into-tests)). The testing surface guarantees these shapes for 007:

- `(make-frame {:on-create [:event-id] :fx-overrides {…} :interceptor-overrides {…} :interceptors [...]})` — exact opts shape.
- `(dispatch-sync ev {:frame f :fx-overrides {…}})` — exact opts shape.
- `(get-frame-db f)` — current `app-db` value (a plain map) for the named frame.
- `(snapshot-of path {:frame f})` — exact opts arg.
- `(destroy-frame! f)` — exact teardown contract.
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

> **SA-4 classification (rf2-p6xyh).** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md): "Snapshot / fixture serialization" classifies as **`:post-v1 tracked`** at rf2-wqsoy (foundation exists; packaged helper is user-space, post-v1); "Property-based testing integration" classifies as **`:post-v1 tracked`** at rf2-rs0ux (pattern doc, no framework change); "Model-based testing harness over `machine-transition`" classifies as **`:post-v1 tracked`** at rf2-vishf (library territory, not framework — the pure `machine-transition` contract is sufficient).

### Snapshot / fixture serialization (post-v1, rf2-wqsoy)

Some tests want to capture a frame's `app-db` and replay it later (golden-master testing, regression checks). Foundation supports this trivially (`(spit "fixture.edn" (pr-str (get-frame-db f)))`); a helper is user-space. Deferred to rf2-wqsoy.

#### Post-v1 Tracking — rf2-wqsoy

- **Foundation in v1.** `get-frame-db` returns a plain value; `pr-str` / EDN reader round-trips it. No framework change is needed for the raw capture/replay path.
- **Scope deferred.** A packaged helper (`golden-master`, `regression-check`) with the ergonomic API (file-naming convention, diff rendering, `clojure.test`-style failure report) is user-space library work.
- **Reconsideration trigger.** A repeated pattern emerging across `examples/` or downstream tests that all hand-roll the same snapshot/diff scaffolding.
- **Out of scope for the bead.** Cross-process replay (record-on-prod, replay-on-dev) — that wants the trace-buffer surface, not a snapshot helper.

### Property-based testing integration (post-v1, rf2-rs0ux)

`test.check`-style generative testing fits cleanly into re-frame2 — `make-frame` is cheap, generators produce event sequences, properties check invariants. Documented as a pattern post-v1. Deferred to rf2-rs0ux.

#### Post-v1 Tracking — rf2-rs0ux

- **Foundation in v1.** `make-frame` is cheap and isolated; `dispatch-sync` settles synchronously per [Resolved decisions](#resolved-decisions); the schema-validator hook (Spec 010) gives invariants a place to live.
- **Scope deferred.** A guide-tier pattern document: generators for event sequences, invariants expressed as schemas, shrinking strategies for `dispatch-sequence` failures. No framework primitive missing.
- **Reconsideration trigger.** If schema-driven generation (per [010 §Schema-driven generative tests](010-Schemas.md#schema-driven-generative-tests-post-v1-rf2-rs0ux)) lands first, the pattern doc folds in directly.
- **Out of scope for the bead.** A bundled `test.check` dependency — re-frame2 stays library-agnostic.

### Model-based testing harness over `machine-transition` (post-v1, rf2-vishf)

`@xstate/test`-style: treat a transition table as a graph and *generate* test cases automatically — paths, state-coverage, transition-coverage, shortest-path-to-state, guard-coverage. The pure `machine-transition` function makes this cheap; the transition contract is sufficient to build the harness externally without runtime changes. Deferred to rf2-vishf.

#### Post-v1 Tracking — rf2-vishf

- **Foundation in v1.** `machine-transition` is pure and JVM-runnable; `:guards` and `:actions` are machine-scoped fns the harness can call directly; the corpus shape per [005 §Future — Model-based testing harness](005-StateMachines.md#model-based-testing-harness--re-framemachinestest) is locked.
- **Scope deferred.** The packaged library (`rf/test/machine-paths`, `rf/test/shortest-path-to`, coverage strategy selectors, EDN fixture emitter) ships as `re-frame.machines.test` post-v1.
- **Reconsideration trigger.** Either an AI-implementor needs the coverage corpus for cross-language conformance, or app-side machines start exhibiting edge-case bugs that hand-written tests miss.
- **Out of scope for the bead.** Time-travel / step-debugger over the generated paths — separate concern, lives in the tool layer (causa/re-frame2-pair).
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

re-frame2 ships a `re-frame.test-support` convenience namespace (renamed from v1's `re-frame.test` per rf2-8hcb). Users `(:require [re-frame.test-support :as ts])` to reach the fixture machinery and the test-flavoured helpers, paired with `(:require [re-frame.core :as rf])` for the dispatch / frame / sub primitives. `re-frame.test-support` does NOT re-export from `re-frame.core` — keeping the two namespaces separate preserves the rule that `re-frame.core` is the production primitive surface (used by application code) and `re-frame.test-support` is the test-only convenience surface (required only by test files). View-assertion test files additionally `:require [re-frame.test-helpers :as th]` per [§View-assertion helpers](#view-assertion-helpers-re-frametest-helpers).

The canonical helper inventory is the union of three namespaces:

| Helper | Namespace | Purpose |
|---|---|---|
| `with-frame`, `make-frame`, `destroy-frame!`, `reset-frame!`, `dispatch-sync`, `with-fx-overrides`, `get-frame-db`, `snapshot-of`, `compute-sub`, `sub-topology`, `machine-transition` | `re-frame.core` | Production primitives, also the testing entry points. Same defs the rest of the framework uses; tests reach them through `re-frame.core` (no re-export shim). |
| `dispatch-sequence` | `re-frame.test-support` | `(dispatch-sequence events)` / `(dispatch-sequence events opts)` — fires each event via `dispatch-sync` in order against the resolved frame. Returns the final `app-db` value. Optional `:after-each (fn [db ev] ...)` runs after each event's drain settles, useful for capturing intermediate state. Optional `:frame` defaults to `(current-frame)` (typically `:rf/default`). Equivalent to a `doseq` of `dispatch-sync` calls; reads better in tests. |
| `assert-state` | `re-frame.test-support` | `(assert-state expected-db)` for a full-db check, or `(assert-state path expected-val)` for a path check. Both shapes accept a trailing `{:frame ...}` opt. Mismatch fires a `clojure.test/is`-style failure (delivered via `do-report`). **Distinct from the `:rf.assert/*` event-vector family** (`:rf.assert/path-equals`, `:rf.assert/sub-equals`, …) used inside a Story `:play` block — that surface lives in Spec 007 §Play functions (`:rf.assert/*` is registered, enumerable, and reserved under `:rf.assert/*` per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)). `assert-state` is the in-process `clojure.test` sync fn (reports via `do-report`); `:rf.assert/*` events are dispatches handled by the story library's test runner (rendered as a checked-step list in dev/docs, fail loudly in test mode, simulation breakpoints in agent mode). Same intent (db-shape assertion), different test surface — see [007 §Play functions](007-Stories.md#play-functions). |
| `poll-until` | `re-frame.test-support` (rf2-ka3n6 / rf2-fun38) | `(poll-until pred)` / `(poll-until pred opts)` — bounded-deadline poll for `(pred)` to be truthy. JVM returns the truthy value synchronously (throws `ex-info` with `:rf.test/poll-timeout true` on timeout); CLJS returns a `js/Promise` that resolves with the truthy value or rejects on timeout. Opts: `:timeout-ms` (default 2000), `:interval-ms` (default 5), `:label` (string/keyword for the timeout message). Replaces incidental fixed `Thread/sleep N` / `js/setTimeout` whose intent is "wait for an observable state change" — NOT for timer-semantics tests (grace-period elapse, throttle/debounce window, "prove a thing did NOT happen within window N"); those should keep their sleep and annotate that intent locally. |
| `snapshot-registrar`, `restore-registrar!`, `with-fresh-registrar`, `reset-runtime-fixture` | `re-frame.test-support` | Snapshot/restore the registrar (and per-process state — frames, flows, schemas, trace listeners) around a test or fixture. The standard `:each` fixture for re-frame2 test suites. |
| `expand-tree`, `find-by-attr` / `find-all-by-attr` / `find-by-attr-prefix`, `find-by-testid` / `find-all-by-testid` / `find-by-testid-prefix`, `attrs`, `children`, `text-content`, `extract-handler`, `invoke-handler`, `testid` | `re-frame.test-helpers` | Hiccup-walk view-assertion surface — call the view-fn directly, walk the returned hiccup, assert on content or invoke a handler. JVM-runnable; no JSDOM, no React, no `act()`. Full inventory and contract: [§View-assertion helpers](#view-assertion-helpers-re-frametest-helpers). |
| `with-app-fixture`, `expect-text`, `wait-until` | `re-frame.test-helpers` (rf2-wy1ac) | Single-frame e2e fixture trio. `with-app-fixture` brackets a body with a fresh frame + `:install` hook + `:root-view` stash; `expect-text` walks the stashed view for a testid'd node and asserts text content; `wait-until` polls a condition or a testid's text until a deadline elapses (JVM-sync / CLJS-Promise). Compresses the 5-line single-frame e2e pattern to 2 lines. See [§Pattern 5 — single-frame e2e fixture](#pattern-5--single-frame-e2e-fixture-rf2-wy1ac). |

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

> **Two assertion surfaces — pick by test context.** `(ts/assert-state path expected)` is the sync `clojure.test`-aware fn for in-process tests (reports via `do-report`). The sibling surface is the `:rf.assert/*` event-vector family (`:rf.assert/path-equals`, `:rf.assert/sub-equals`, `:rf.assert/state-is`, `:rf.assert/dispatched?`, `:rf.assert/no-warnings`, `:rf.assert/effect-emitted`, `:rf.assert/path-matches`) used inside a Story `:play` block — see [007 §Play functions](007-Stories.md#play-functions) for the canonical vocabulary and its dual-mode behaviour (checked-step list in dev/docs, loud failures in test mode, simulation breakpoints in agent mode). Choose by test surface: `assert-state` from a `deftest` body; `:rf.assert/*` from a story variant's `:play` vector. Argument shapes are isomorphic (both target a db path or a full-db expectation) but the dispatch mechanism, runner, and reporting channel differ — they are not interchangeable.

#### `poll-until` example

Use for async settles whose post-condition is observable in state. Replaces incidental `Thread/sleep N` / `js/setTimeout` whose intent is "give the cascade time to drain". NOT a substitute for timer-semantics sleeps that prove behaviour within / past a specific window (grace, throttle, debounce, "no event fires within N ms").

JVM (synchronous — returns the truthy value, throws on timeout):

```clojure
(rf/dispatch [:cross-frame/fan-out])
(ts/poll-until #(= 3 (:count (rf/get-frame-db :other-frame)))
               {:timeout-ms 5000 :label "fan-out reached :other-frame"})
(is (= 3 (:count (rf/get-frame-db :other-frame))))
```

CLJS (returns a `js/Promise` — compose with `(.then ...)` under `cljs.test/async`):

```clojure
(deftest cross-frame-drain
  (async done
    (-> (ts/poll-until #(= 3 (:count (rf/get-frame-db :other-frame)))
                       {:timeout-ms 5000 :label "fan-out drained"})
        (.then (fn [_]
                 (is (= 3 (:count (rf/get-frame-db :other-frame))))
                 (done)))
        (.catch (fn [e]
                  (is false (str "poll-until timed out: " (.-message e)))
                  (done))))))
```

Timer-semantics sleeps that must stay (grace-period elapse, throttle/debounce, "prove no event fires within window N", host-clock advancement) keep their `Thread/sleep` / `js/setTimeout` but annotate the intent inline with a `;; Timer-semantics sleep (rf2-fun38): ...` comment so audits don't re-flag them.

### `re-frame-test` library compatibility

re-frame2 does **not** ship a `run-test-sync` shim — the macro existed in v1 to wrap a test body in a synchronous drain, and v2's `dispatch-sync` is already settle-by-default, so the shim was pure migration tax (rf2-u3w8j). Existing `re-frame-test` users rewrite the body to inline `dispatch-sync` calls under the per-test `reset-runtime-fixture` (or `with-fresh-registrar` for ad-hoc bodies); see [MIGRATION §M-52](../migration/from-re-frame-v1/README.md#m-52-run-test-sync-removed--use-dispatch-sync-under-reset-runtime-fixture). The other two re-frame-test helpers — `dispatch-sequence` and `assert-state` — keep their v1 names and ship in `re-frame.test-support`; the move is a mechanical `re-frame.test → re-frame.test-support` namespace rename per [MIGRATION §M-25](../migration/from-re-frame-v1/README.md#m-25-re-frametest-helpers-renamed-to-re-frametest-support).

### Headless rendering for visual regression

Spec 011 (SSR & Hydration) ships a pure hiccup → HTML string emitter that is JVM-runnable (per [011 §The render-tree → HTML emitter](011-SSR.md#the-render-tree--html-emitter-cljs-reference)). Snapshot tests, visual-regression diffs, and SSR conformance tests all use this emitter — `(rf/render-to-string view-or-hiccup {:frame f})` returns a string suitable for diffing without JSDOM. Tests that need React mount/commit lifecycle (interactive event firing) still require CLJS; everything else runs JVM-side.
