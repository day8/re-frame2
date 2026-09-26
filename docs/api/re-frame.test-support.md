# re-frame.test-support

Fixtures and helpers for tests that exercise runtime state. The fixture resets the runtime around each test so every test starts from the same registrations; the helpers assert on `app-db`, wait for async work, and record traces and errors. Its companion [`re-frame.test-helpers`](re-frame.test-helpers.md) walks the hiccup a view returns; a test that checks both state and views requires both.

Use this namespace when a test drives the runtime: it dispatches events and reads the state they commit. To test a handler's logic alone you need none of it; call the handler as a function, as [Test an event handler](../core/testing/event-handlers.md) shows.

```clojure
(:require [re-frame.test-support :as ts])
```

```clojure
(ns my-app.counter-test
  (:require [clojure.test :refer [deftest use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; In a real suite, require the app namespace that registers this instead.
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest counter-increments
  (rf/dispatch-sync [:counter/inc])
  (ts/assert-path-equals [:n] 1))
```

`plain-atom/adapter` is the headless adapter: app-db lives in a plain atom and nothing renders, so the suite runs on the JVM or Node with no React. The fixture installs it and gives each test a fresh `:rf/default` frame as its ambient frame, which is why the bare `dispatch-sync` and `assert-path-equals` need no `:frame`.

Nothing here is re-exported from `re-frame.core`, so a production build never loads test machinery by accident. Tests drive the runtime with the ordinary facade functions (`rf/dispatch-sync`, `rf/app-db-value`, …). [Test a pipeline run](../core/testing/pipeline-runs.md) shows these fixtures in use.

## Fixture machinery

Each test's registrations are rolled back afterwards, whether it passes or fails, while the registrations made when namespaces loaded (the framework's and your app's) survive. The fixtures do this by snapshotting the registrar before the test and restoring it after.

### `make-reset-runtime-fixture`

- **Kind**: function
- **Signature**:
  ```clojure
  (make-reset-runtime-fixture)
  (make-reset-runtime-fixture opts) → fixture-fn | {:before … :after …}
  ```
- **Description**: Builds a `clojure.test` / `cljs.test` `:each` fixture that resets the per-process runtime around each test. Pass it to `use-fixtures :each`. Around each test the fixture:
    - reinstates the registrations captured when the fixture was built (at the test namespace's load), so tests do not depend on run order inside a shared test bundle;
    - snapshots the registrar;
    - drops every frame, clears the trace listeners, and resets each loaded artefact's state (flows, schemas, machines, routing, resources, http, epoch); an artefact that is not on the classpath is skipped;
    - returns epoch-history configuration to its defaults, so a suite that needs another `:depth` sets it in `:init-fn`;
    - disposes the adapter, then installs `:adapter` if given;
    - restores everything in a `finally`.

    The baseline is captured when the fixture is built, at the `use-fixtures` form. Register, or require, everything the suite needs above that form: a registration made below it is outside the baseline, so frames the test makes with `rf/make-frame` do not see it, and another namespace's fixture can roll it back. Setup that must run later belongs in `:init-fn`.
- **Options** (all optional):

    | Key | Meaning |
    |-----|---------|
    | `:adapter` | Substrate adapter to install; also ensures the `:rf/default` frame. When omitted, no adapter is installed and a test that creates a frame raises `:rf.error/adapter-disposed` (`:rf.error/no-adapter-installed` if no adapter was installed earlier in the process); calling handlers as functions and reading the registrar still work. |
    | `:app-ns` | Namespace-prefix string naming this suite's own app, for test bundles that load more than one app. See below. |
    | `:init-fn` | Zero-arg fn run after the adapter is installed and before the test body, in the same ambient frame scope as the body. |
    | `:clear-kinds` | Collection of registrar kinds, as listed under [`rf/clear`](re-frame.core.md#clear), such as `[:event :sub]`, cleared after the snapshot and before the body. The snapshot restores them afterwards. An unknown kind clears nothing and is not an error. |
    | `:clear-app-schemas?` | Boolean; clears the schemas artefact's per-frame schemas for the test's duration. |
    | `:ambient-frame` | Frame id bound as the body's ambient scope when an adapter is installed. Default `:rf/default`; pass `nil` to opt out, for tests that create their own top-level frames. With `nil`, a dispatch or subscription that does not name its frame raises `:rf.error/no-frame-context` instead of landing in `:rf/default`. The fixture creates only `:rf/default`; another id is bound as given, so that frame must exist before the body dispatches. |
    | `:async?` | Boolean, default `false`. Marks the suite as having async tests. |

    `:async? true` returns a `cljs.test` map fixture `{:before … :after …}` on CLJS, which suites with `(async done …)` tests require. On the JVM the option is ignored and you always get a function fixture: `clojure.test` calls its fixtures, and a map is callable, so a map fixture would silently skip every test. A `.cljc` suite therefore writes a plain `:async? true`, with no reader conditional.

    `:app-ns` is for test runs that load every test namespace before any test runs: a CLJS node test bundle, or a JVM runner that requires all its test namespaces first. When two loaded apps register the same id (`:rf.route/not-found`, or shared event names), building the default image fails with `:rf.error/image-duplicate-id` for any suite whose baseline was captured after the second app loaded.

    Give `:app-ns` your own app's root namespace prefix, covering its whole tree (`"my-app."`, not `"my-app.core"`), never a sibling's. Every suite that uses the app declares it, not only the one that loads it first; then no suite needs its siblings' names or depends on load order. A bundle with one app never needs this key.

    When the fixture is built, before it captures a baseline, registrations whose `:rf.provenance/ns` starts with the prefix are removed. They are registered again before each test (after the reset, before `:init-fn`) and removed again afterwards, even when the test throws.
- **Example**:
    ```clojure
    (use-fixtures :each
      (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

    ;; A CLJS suite with (async done …) tests gets the map-form fixture.
    (use-fixtures :each
      (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter :async? true}))

    ;; A suite whose tests make their own frames: no ambient :rf/default.
    (use-fixtures :each
      (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter :ambient-frame nil}))

    (deftest checkout-counts
      (rf/with-new-frame [_ (rf/make-frame {:id :checkout})]   ;; destroyed when the body exits
        (rf/dispatch-sync [:counter/inc])                      ;; targets :checkout
        (ts/assert-path-equals [:n] 1 {:frame :checkout})))

    ;; A bundle that also loads other apps.
    (use-fixtures :each
      (ts/make-reset-runtime-fixture
        {:adapter reagent-adapter/adapter
         :app-ns  "my-app."          ; rows under this prefix belong to this suite
         :init-fn init!}))
    ```

### `snapshot-registrar`

- **Kind**: function
- **Signature**:
  ```clojure
  (snapshot-registrar) → snapshot
  ```
- **Description**: Captures the current registrar state and returns it as a snapshot for `restore-registrar!`. `make-reset-runtime-fixture` does this for you; call it directly only for a custom fixture. The snapshot covers the registrar only, not the source store that `rf/make-frame` assembles a frame's default image from: registrations a test makes stay visible to frames created later, and two test namespaces registering the same id can make `make-frame` fail with `:rf.error/image-duplicate-id`. `make-reset-runtime-fixture` isolates both.
- **Example**:
  ```clojure
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
- **Description**: Restores the registrar to a `snapshot` taken by `snapshot-registrar`. Returns `nil`.
- **Example**:
  ```clojure
  (ts/restore-registrar! snap)
  ```

## Assertions

### `assert-path-equals`

- **Kind**: function
- **Signature**:
  ```clojure
  (assert-path-equals path expected-val)
  (assert-path-equals path expected-val opts)
  ```
- **Description**: Asserts that `(get-in app-db path)` equals `expected-val` in the resolved frame, reporting the result through `clojure.test`'s `do-report` as `is` does. Returns `true` on pass and `false` otherwise; a failure has already been reported either way.
    - `opts`: `:frame` names another frame, as either a frame id or a frame value (what `rf/make-frame` returns). Without it, the frame is the current frame scope: a `with-frame` or `with-new-frame` binding, or else the fixture's ambient frame (`:rf/default` unless `:ambient-frame` names another). Outside any scope there is no fallback to `:rf/default`. An unknown or destroyed frame, and a call outside any scope, read as a `nil` app-db rather than raising, so the assertion fails unless `expected-val` is `nil`.
    - It mirrors the `:rf.assert/path-equals` event Story uses, under the same name.
    - To fire several events before asserting, call `rf/dispatch-sync` once per event. Each call drains fully before the next, so the state between calls reflects every committed effect.
    - For a whole-db assertion, compare directly: `(is (= expected-db (rf/app-db-value frame-id)))`.
- **Example**:
  ```clojure
  (doseq [ev [[:counter/inc] [:counter/inc] [:counter/dec]]]
    (rf/dispatch-sync ev))
  (ts/assert-path-equals [:n] 1)

  ;; Against another frame:
  (ts/assert-path-equals [:cart :count] 2 {:frame :checkout})
  ```

## Waiting for async work

### `poll-until`

- **Kind**: function
- **Signature**:
  ```clojure
  (poll-until pred)
  (poll-until pred opts)
  ```
- **Description**: Calls `pred` repeatedly until it returns a truthy value or a deadline passes. Use it in place of a fixed sleep when a test waits on a queued dispatch, an HTTP reply or a timer.
    - JVM: synchronous. Returns the truthy value, or throws `ex-info` on timeout.
    - CLJS: returns a `js/Promise` that resolves with the truthy value or rejects on timeout. A `pred` that returns a `js/Promise` is awaited, and its resolved value is tested. Call it from an `(async done …)` test, whose suite fixture needs `:async? true`.
    - A `pred` that throws counts as falsy, and polling continues, so it can read state that is still mid-change.
    - The timeout error carries `:rf.error/id` `:rf.error/poll-until-timeout`, plus `:elapsed-ms` and `:label` in its data.
    - `opts`: `:timeout-ms` (default 2000), `:interval-ms` (default 5), `:label` (a string or keyword, shown in the timeout message).
    - On CLJS, handle the rejection before the step that calls `done`, and call `done` once, last. A `.catch` placed after `done` reports a failure from a later namespace against this test and calls `done` a second time.
- **Example**:
  ```clojure
  ;; JVM: synchronous; returns the truthy value (throws on timeout).
  (rf/dispatch [:counter/inc])                    ;; queued, not yet drained
  (rf/dispatch [:counter/inc])
  (is (ts/poll-until #(= 2 (:n (rf/app-db-value :rf/default)))
                     {:label "counter reached 2"}))

  ;; CLJS: returns a js/Promise, composed under cljs.test/async.
  (deftest counter-reaches-2
    (async done
      (rf/dispatch [:counter/inc])
      (rf/dispatch [:counter/inc])
      (-> (ts/poll-until #(= 2 (:n (rf/app-db-value :rf/default)))
                         {:label "counter reached 2"})
          (.catch (fn [e] (is false (.-message e)) nil))   ;; report a timeout
          (.then  (fn [_] (done))))))                       ;; finish once, last
  ```

## Recording traces and errors

Both macros bracket a body with a fresh listener: it is registered before the body runs and unregistered in a `finally`, even if the body throws. The records land in an atom bound to the symbol you name, and the macro returns the value of the body's last form.

On the JVM the macros resolve through the ordinary `(:require [re-frame.test-support :as ts])`. On CLJS the namespace does not self-require its macros (unlike `re-frame.core`), so a CLJS test file also needs `(:require-macros [re-frame.test-support :refer [with-trace-recorder! with-emit-recorder!]])`, or `:as ts` in `:require-macros` for alias-qualified use.

### `with-trace-recorder!`

- **Kind**: macro
- **Signature**:
  ```clojure
  (with-trace-recorder! [recs-sym] body+)
  (with-trace-recorder! [recs-sym opts] body+)
  ```
- **Description**: Records the trace events emitted while `body` runs into an atom bound to `recs-sym`.
    - `opts` is an optional map literal, read at macroexpansion. Pass the map itself: a symbol naming a map is not read, and every default applies. A binding vector of any other length, or a `:shape` other than `:flat` or `:by-op`, throws at macroexpansion. The keys:
        - `:pred`: a 1-arg `(fn [ev] truthy?)` filter. Default: accept every event.
        - `:shape`: `:flat` (default; the atom holds a vector of events) or `:by-op` (a map keyed by `(:operation ev)`).
        - `:key`: the listener key. Default: a keyword generated per expansion site, so two brackets in one test do not collide.
- **Example**:
  ```clojure
  ;; Flat shape (default), every event.
  (ts/with-trace-recorder! [traces]
    (rf/dispatch-sync [:counter/inc])
    (is (= 1 (count (filter #(= :rf.event/run-start (:operation %))
                            @traces)))))

  ;; :by-op shape with a :pred filter.
  (ts/with-trace-recorder! [observed
                            {:pred  #(contains? #{:rf.event/run-start
                                                  :rf.event/run-end}
                                                (:operation %))
                             :shape :by-op}]
    (rf/dispatch-sync [:counter/inc])
    (rf/dispatch-sync [:counter/inc])
    (is (= 2 (count (:rf.event/run-start @observed))))
    (is (= 2 (count (:rf.event/run-end @observed)))))
  ```

### `with-emit-recorder!`

- **Kind**: macro
- **Signature**:
  ```clojure
  (with-emit-recorder! [recs-sym] body+)
  (with-emit-recorder! [recs-sym opts] body+)
  ```
- **Description**: Records what the always-on error or event stream emits while `body` runs into an atom bound to `recs-sym`. It is the always-on counterpart of [`with-trace-recorder!`](#with-trace-recorder): these streams also run in production builds.
    - `opts` is an optional map literal, read at macroexpansion. Pass the map itself: a symbol naming a map is not read, and every default applies. A binding vector of any other length throws at macroexpansion. The keys:
        - `:stream`: `:errors` (default; one record per `:rf.error/*` reported) or `:events` (one record per processed event). Any other value throws at macroexpansion.
        - `:pred`: a 1-arg `(fn [record] truthy?)` filter. Default: accept every record.
        - `:key`: the listener key. Default: a keyword generated per expansion site, so two brackets in one test do not collide.
    - An `:errors` record is `{:error … :event … :event-id … :frame … :time … :exception … :elapsed-ms … :source-coord …}`, `:error` being the `:rf.error/*` id. Only errors the runtime catches and reports produce one, such as `:rf.error/handler-exception` or `:rf.error/no-such-handler`; an error thrown to the caller, such as `:rf.error/poll-until-timeout`, does not, so catch that one instead.
    - An `:events` record is `{:event … :event-id … :frame … :time … :outcome … :elapsed-ms …}`. `:outcome` is `:ok`, `:error` (the handler or an interceptor threw), `:rolled-back` (a schema rejected the new `app-db`), `:flow-error` (a flow's output threw) or `:rejected` (a `:boundary? true` handler's `:schema` refused the payload). An event whose handler sets `:rf.trace/no-emit? true` produces no record.
    - In both, `:event` already has the event's sensitivity declarations applied: a declared-sensitive argument reads `:rf/redacted`.
    - The records are unprojected, which is what a test wants. These streams have no public listener function, and `rf/register-listener!` has no `:events` or `:errors` stream: an application reads production records, projected, through a frame's `:observability` sink or the `(rf/configure! {:observability …})` process default.
- **Example**:
  ```clojure
  ;; Default stream (:errors): capture what one dispatch fails with.
  (ts/with-emit-recorder! [errs]
    (rf/dispatch-sync [:boom])
    (is (= [:rf.error/handler-exception] (mapv :error @errs))))

  ;; The event stream, filtered to one frame.
  (ts/with-emit-recorder! [seen {:stream :events
                                 :pred   #(= :app/main (:frame %))}]
    (rf/dispatch-sync [:tick])
    (is (= 1 (count @seen))))
  ```

## See also

- [re-frame.core](re-frame.core.md): the production functions tests drive (`make-frame`, `with-frame`, `dispatch-sync`, `with-fx-overrides`, `app-db-value`, `compute-sub`) and registrar introspection (`registrations`, `handler-meta`).
- [re-frame.http](re-frame.http.md): HTTP stubs for tests that exercise managed requests.
- [Test an event handler](../core/testing/event-handlers.md): testing a handler as a pure function.
