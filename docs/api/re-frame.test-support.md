# re-frame.test-support

`re-frame.test-support` is the **runtime-state** testing surface. It holds test-only fixture machinery and test-flavoured helpers that drive and assert against a frame's `app-db`, the registrar, the dispatch drain, and the trace stream. Its sibling `re-frame.test-helpers` owns the view-tree axis: the hiccup walkers plus the `testid` authoring helper.

This namespace does not re-export from `re-frame.core`, so a production build never picks up test-flavoured machinery by accident. A test file requires it alongside `[re-frame.core :as rf]` (and `[re-frame.test-helpers :as th]` for view assertions).

```clojure
(:require [re-frame.test-support :as ts])
```

Examples below also use `[re-frame.core :as rf]` for the production primitives that double as testing entry points (`dispatch-sync`, `app-db-value`, …). For the practical how-to, see [Test an event handler](../core/testing/event-handlers.md) and [Test a pipeline run](../core/testing/pipeline-runs.md).

## Fixture machinery

The fixture primitives follow one pattern: snapshot the registrar before the test mutates registrations, then restore it afterwards, whether the test passes or fails. Framework-shipped registrations are captured in the snapshot, so they survive. Per-test registrations are rolled back.

### `snapshot-registrar`

- **Kind**: function
- **Signature**:
  ```clojure
  (snapshot-registrar) → snapshot
  ```
- **Description**: Capture the current registrar state. Returns a snapshot value for later restore.
- **Example**:
  ```clojure
  ;; Capture before a test mutates registrations; roll back on the way out.
  (let [snap (ts/snapshot-registrar)]
    ;; ... test body registers extra handlers / subs ...
    (ts/restore-registrar! snap))
  ```

### `restore-registrar!`

- **Kind**: function
- **Signature**:
  ```clojure
  (restore-registrar! snapshot) → nil
  ```
- **Description**: Restore a previously captured registrar `snapshot`. Returns `nil`.
- **Example**:
  ```clojure
  ;; `snap` was captured earlier via `snapshot-registrar`.
  (ts/restore-registrar! snap)
  ```

### `make-reset-runtime-fixture`

- **Kind**: function
- **Signature**:
  ```clojure
  (make-reset-runtime-fixture)
  (make-reset-runtime-fixture opts) → fixture-fn | {:before … :after …}
  ```
- **Description**: Build a `clojure.test` / `cljs.test` `:each` fixture that resets the per-process runtime around each test. Pair with `use-fixtures :each`.

  Around each test the fixture:

  - reinstates the stable ns-load registrar and source-store baseline captured at fixture-build time, which makes tests independent of run order inside a shared test bundle;
  - snapshots the registrar;
  - resets the frames registry, trace listeners, and per-artefact state (flows, schemas, machines, routing, resources, http, epoch). This runs via late-bind hooks that no-op when an artefact is absent from the classpath;
  - disposes then reinstalls the adapter;
  - restores everything in a `finally`.

  `opts` (all optional):

  | Key | Meaning |
  |-----|---------|
  | `:adapter` | Substrate adapter to install; also ensures the `:rf/default` frame. When omitted, no adapter is installed. |
  | `:init-fn` | Zero-arg fn run after adapter install, before the test body, under the same ambient frame scope as the body. |
  | `:clear-kinds` | Collection of registrar kinds cleared after the snapshot capture and before the body (the snapshot restores them on the way out). |
  | `:clear-app-schemas?` | Boolean; clear the schemas artefact's per-frame side-table for the test's duration. |
  | `:ambient-frame` | Frame id bound as the body's ambient scope when an adapter is installed. Default `:rf/default`; pass `nil` to opt out (for tests that create their own top-level frames). |
  | `:async?` | Boolean, default `false`. Declares the suite **async-capable**; the return shape that delivers it is chosen per host. On CLJS you get a `cljs.test` map-form fixture `{:before … :after …}`, **required** for suites with `(async done …)` tests. On the JVM the option is inert and you always get the fn-form — `clojure.test` has no async tests, and no map-fixture support at all (it *invokes* a fixture, and a Clojure map is `IFn`, so a map fixture would silently skip every test body). |
- **Example**:
  ```clojure
  (use-fixtures :each
    (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

  ;; CLJS suite with (async done …) tests — map-form fixture:
  (use-fixtures :each
    (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter :async? true}))
  ```

  A `.cljc` suite whose CLJS rows are async writes the same plain `:async? true`
  — no reader conditional at the call site, because the factory already picks
  the map on CLJS and the fn-form on the JVM.

## Bundle co-load hygiene

CLJS node runners often load **every** test namespace into one bundle before any
test runs. Two example apps that both register the same per-app id (for example
`:rf.route/not-found`, or shared event vocabulary) leave duplicate provenance rows
in the shared source store; default-image assembly then fails loud with
`:rf.error/image-duplicate-id`. The sequester / reinstate pair removes a suite's
sibling registrations for the duration of its tests.

Call sequester at **namespace load** (right after requiring the app ns), and
reinstate from a per-test `:init-fn` (or equivalent) when the suite must see its
own registrations again.

### `sequester-app-registration!`

- **Kind**: function
- **Signature**:
  ```clojure
  (sequester-app-registration! kind id provenance-ns) → descriptor | nil
  ```
- **Description**: Remove **one** app namespace's registration row for
  `(kind, id)` from the live registrar **and** the provenance source store.
  `kind` is the **registrar kind** the id was registered under — `:route` for
  routes, `:event` for events, `:sub` for subscriptions, and so on. The `(kind,
  id)` pair must match the live registration: a mismatched kind captures nothing,
  returns `nil`, and leaves the row registered. Returns the captured source-store
  descriptor, or `nil` when absent. Reinstate with `reinstate-app-registration!`.
- **Example**:
  ```clojure
  ;; At ns load, after requiring the app under test. A per-app not-found route
  ;; registers under registrar kind :route (NOT :event) — pass the kind the id
  ;; was registered under.
  (defonce !not-found
    (ts/sequester-app-registration! :route :rf.route/not-found
                                    "my.app.routes"))

  ;; A non-nil return is the captured descriptor — proof the route row was found
  ;; and removed (a nil would mean the (kind, id) matched nothing). Reinstate it
  ;; when this suite must see its own :rf.route/not-found again, e.g. from a
  ;; per-test :init-fn:
  ;;   (ts/reinstate-app-registration! !not-found)
  ```

### `reinstate-app-registration!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reinstate-app-registration! descriptor) → nil
  ```
- **Description**: Reinstate a descriptor captured by
  `sequester-app-registration!` through `registrar/register!` (registrar and
  source store in lockstep). No-op on `nil`.
- **Example**:
  ```clojure
  (ts/reinstate-app-registration! !not-found)
  ```

### `sequester-app-namespaces!`

- **Kind**: function
- **Signature**:
  ```clojure
  (sequester-app-namespaces! ns-prefix) → captured-row-count
  ```
- **Description**: Namespace-tree form: remove **every** source-store row whose
  provenance namespace starts with `ns-prefix` (and the matching registrar ids when
  the current registrar row belongs to that prefix). Capture is **memoized** per
  prefix; scrubbing runs on every call so merge-form restores cannot reintroduce
  sibling rows. Returns the captured row count. Reinstate with
  `reinstate-app-namespaces!`.
- **Example**:
  ```clojure
  ;; At ns load — drop the co-loaded sibling app's registrations:
  (ts/sequester-app-namespaces! "realworld.uix")
  ```

### `reinstate-app-namespaces!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reinstate-app-namespaces! ns-prefix) → nil
  ```
- **Description**: Reinstate every row `sequester-app-namespaces!` captured for
  `ns-prefix` through `registrar/register!`. Call from a suite's per-test
  `init-fn` when that suite owns the prefix.
- **Example**:
  ```clojure
  (use-fixtures :each
    (ts/make-reset-runtime-fixture
     {:adapter plain-atom/adapter
      :init-fn #(ts/reinstate-app-namespaces! "realworld.reagent")}))
  ```

## Test-flavoured helpers

To fire several events in order, call `rf/dispatch-sync` per event — each drains to fixed point before the next, so observable state between calls reflects committed effects:

```clojure
(doseq [ev [[:counter/inc] [:counter/inc] [:counter/dec]]]
  (rf/dispatch-sync ev))
```

### `assert-path-equals`

- **Kind**: function
- **Signature**:
  ```clojure
  (assert-path-equals path expected-val)
  (assert-path-equals path expected-val opts)
  ```
- **Description**: Assert `(get-in db path) == expected-val` against the resolved frame's `app-db`. A mismatch fires a `clojure.test/is`-style failure via `do-report`. Returns `true` on pass and `false` otherwise; the failure has already been reported either way.

  `opts`: `:frame` targets a non-default frame; frame resolution is `:frame` opt → `(current-frame)` → `:rf/default`.

  This is the fn-side counterpart to the `:rf.assert/path-equals` story event-family: same name root, different runner channel.
- **Example**:
  ```clojure
  (rf/dispatch-sync [:counter/inc])
  (ts/assert-path-equals [:n] 1)
  ;; against a non-default frame:
  (ts/assert-path-equals [:cart :count] 2 {:frame :checkout})
  ```

For a full-db assertion, compare directly: `(is (= expected-db (rf/app-db-value frame-id)))`.

## Deterministic-wait helpers

### `poll-until`

- **Kind**: function
- **Signature**:
  ```clojure
  (poll-until pred)
  (poll-until pred opts)
  ```
- **Description**: Poll `pred` until it returns truthy, within a bounded deadline.

  - **JVM**: synchronous. Returns the truthy value, or throws `ex-info` on timeout.
  - **CLJS**: returns a `js/Promise` that resolves with the truthy value, or rejects on timeout. A `pred` that returns a `js/Promise` is awaited; its resolved value drives the truthy check.

  The timeout error carries `:rf.error/id` `:rf.error/poll-until-timeout` (the canonical discriminator), plus `:elapsed-ms` and `:label` in its data.

  `opts`: `:timeout-ms` (default 2000), `:interval-ms` (default 5), `:label`.
- **Example**:
  ```clojure
  ;; JVM — synchronous; returns the truthy value (throws on timeout).
  (ts/poll-until #(= 2 (:n (rf/app-db-value :rf/default)))
                 {:label "counter reached 2"})

  ;; CLJS — returns a js/Promise; compose with cljs.test/async.
  (-> (ts/poll-until #(= 3 (:n (rf/app-db-value :rf/default))))
      (.then (fn [_] (done))))
  ```

## Trace-recorder bracket

### `with-trace-recorder!`

- **Kind**: macro
- **Signature**:
  ```clojure
  (with-trace-recorder! [recs-sym] body+)
  (with-trace-recorder! [recs-sym opts] body+)
  ```
- **Description**: Bracket `body` with a fresh trace-tooling listener that accumulates matching trace events into an atom bound to `recs-sym`. The listener is registered before `body` runs and unregistered in a `finally` on the way out, even if `body` throws. Returns the value of `body`'s final form.

  `opts` (optional map literal; keys evaluated at macroexpansion):

  - `:pred` — a 1-arg `(fn [ev] truthy?)` filter. Default: accept every event.
  - `:shape` — `:flat` (default; the atom holds a vector of events) or `:by-op` (the atom holds a map keyed by `(:operation ev)`).
  - `:key` — listener key. Default: a freshly-gensym'd keyword unique to the expansion site, so two brackets in one test do not collide.

  Macro requires:

  - **JVM**: resolves alias-qualified through the normal `(:require [re-frame.test-support :as ts])`.
  - **CLJS**: the namespace carries no self-`:require-macros` (unlike `re-frame.core`). CLJS test files must therefore require the macro explicitly: `(:require-macros [re-frame.test-support :refer [with-trace-recorder!]])`, or `:as ts` in `:require-macros` for alias-qualified use.
- **Example**:
  ```clojure
  ;; Flat shape (default), default filter, simple read.
  (ts/with-trace-recorder! [traces]
    (rf/dispatch-sync [:my-event])
    (is (= 1 (count (filter #(= :rf.event/run-start (:operation %))
                            @traces)))))

  ;; :by-op shape with a :pred filter — atom holds a map keyed by operation.
  (ts/with-trace-recorder! [observed
                            {:pred  #(contains? #{:rf.view/render
                                                  :rf.view/rendered}
                                                (:operation %))
                             :shape :by-op}]
    (render-twice!)
    (is (= 2 (count (:rf.view/render @observed))))
    (is (= 2 (count (:rf.view/rendered @observed)))))
  ```

## See also

- [re-frame.core.md](re-frame.core.md) — the production primitives that double as testing entry points (`make-frame`, `with-frame`, `dispatch-sync`, `with-fx-overrides`, `app-db-value`, `compute-sub`, `sub-topology`) and the registrar-introspection API (`registrations`, `handler-meta`).
- [re-frame.test-helpers.md](re-frame.test-helpers.md) — the sibling view-tree assertion namespace (hiccup walkers plus the `testid` authoring helper).
- [re-frame.http.md](re-frame.http.md) — HTTP test stubs for tests that exercise managed requests.
- [Test an event handler](../core/testing/event-handlers.md) and [Test a pipeline run](../core/testing/pipeline-runs.md) — the practical how-to guides for the testing surface.
