# re-frame.test-support

`re-frame.test-support` is re-frame2's **runtime-state** testing surface — the test-only fixture machinery and test-flavoured helpers that drive and assert against a frame's `app-db`, the registrar, the dispatch drain, and the trace stream. It is the runtime-state axis (registrar, frames, `app-db`, drain) of the testing API; its sibling `re-frame.test-helpers` owns the view-tree axis (hiccup walkers plus the `testid` authoring helper). This namespace deliberately does **not** re-export from `re-frame.core`, so a production build never picks up test-flavoured machinery by accident — a test file requires this namespace alongside `[re-frame.core :as rf]` (and `[re-frame.test-helpers :as th]` for view assertions).

```clojure
(:require [re-frame.test-support :as ts])
```

Examples below also use `[re-frame.core :as rf]` for the production primitives that double as testing entry points (`dispatch-sync`, `app-db-value`, …).

## Fixture machinery

The fixture primitives follow one pattern: "snapshot the registrar before the test mutates registrations; restore after, regardless of pass / fail." Framework-shipped registrations (captured in the snapshot) survive; per-test registrations are rolled back.

### `snapshot-registrar`

- **Kind**: function
- **Signature**:
  ```clojure
  (snapshot-registrar) → snapshot
  ```
- **Description**: Capture the current registrar state.
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
- **Description**: Restore a previously captured registrar state.
- **Example**:
  ```clojure
  ;; `snap` was captured earlier via `snapshot-registrar`.
  (ts/restore-registrar! snap)
  ```

### `merge-registrar-snapshots`

- **Kind**: function
- **Signature**:
  ```clojure
  (merge-registrar-snapshots base overlay) → snapshot
  ```
- **Description**: Two-level merge of two registrar snapshots (`kind → id → metadata`). Entries from `overlay` win on a per-`[kind id]` collision; ids present only in `base` survive; ids present only in `overlay` are added. Used by `make-reset-runtime-fixture` to fold a stable ns-load baseline back over whatever the registrar currently holds, so a test namespace's own ns-load registrations are present regardless of run order.
- **Example**:
  ```clojure
  ;; `base` and `overlay` are each a value from `snapshot-registrar`.
  ;; Overlay wins per [kind id]; ids unique to either side survive.
  (ts/merge-registrar-snapshots base overlay)
  ```

### `with-fresh-registrar`

- **Kind**: function
- **Signature**:
  ```clojure
  (with-fresh-registrar body-fn) → any
  ```
- **Description**: The composed helper — snapshot + body + restore. Most tests reach for this rather than the lower-level primitives.

### `make-reset-runtime-fixture`

- **Kind**: function
- **Signature**:
  ```clojure
  (make-reset-runtime-fixture)
  (make-reset-runtime-fixture opts) → fixture-fn
  ```
- **Description**: Build a `clojure.test` fixture that resets the runtime between tests. Pair with `use-fixtures :each`.
- **Example**:
  ```clojure
  (use-fixtures :each
    (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))
  ```

## Test-flavoured helpers

### `dispatch-sequence`

- **Kind**: function
- **Signature**:
  ```clojure
  (dispatch-sequence events)
  (dispatch-sequence events opts)
  ```
- **Description**: "Run this list of events end-to-end against the current frame." `opts`: `:after-each (fn [db ev] ...)` for between-event assertions, `:frame` for non-default targets. Returns the final `app-db`.
- **Example**:
  ```clojure
  ;; Run a list of events end-to-end; returns the final app-db.
  (ts/dispatch-sequence [[:counter/inc] [:counter/inc] [:counter/dec]])

  ;; :after-each observes committed state between events.
  (let [seen (atom [])]
    (ts/dispatch-sequence [[:counter/inc] [:counter/inc]]
                          {:after-each (fn [db ev] (swap! seen conj [(:n db) ev]))}))
  ```

### `assert-path-equals`

- **Kind**: function
- **Signature**:
  ```clojure
  (assert-path-equals path expected-val)
  (assert-path-equals path expected-val opts)
  ```
- **Description**: "Assert `(get-in db path) == expected-val`." Mismatch fires a `clojure.test/is`-style failure via `do-report`. The fn-side counterpart to the `:rf.assert/path-equals` story event-family — same name root, different runner channel.

### `assert-db-equals`

- **Kind**: function
- **Signature**:
  ```clojure
  (assert-db-equals expected-db)
  (assert-db-equals expected-db opts)
  ```
- **Description**: Full-db sync assertion. Mismatch fires a `clojure.test/is`-style failure. Companion to `assert-path-equals`; reach for it when the whole-db identity matters.
- **Example**:
  ```clojure
  (rf/dispatch-sync [:counter/init])
  (ts/assert-db-equals {:n 0})
  ;; against a non-default frame:
  (ts/assert-db-equals {:n 4} {:frame :checkout})
  ```

## Deterministic-wait helpers

### `poll-until`

- **Kind**: function
- **Signature**:
  ```clojure
  (poll-until pred)
  (poll-until pred opts)
  ```
- **Description**: Bounded-deadline poll. JVM: synchronous — returns the truthy value, throws `ex-info` carrying `:rf.error/id` `:rf.error/poll-until-timeout` (the canonical discriminator) on timeout. CLJS: returns a `js/Promise` resolving with the truthy value or rejecting on timeout. Opts: `:timeout-ms` (default 2000), `:interval-ms` (default 5), `:label`.
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
- **Description**: Bracket `body` with a fresh trace-tooling listener that accumulates matching trace events into an atom bound to `recs-sym`. The listener is registered before `body` runs and unregistered in a `finally` on the way out — even if `body` throws. `opts` (optional map literal; keys evaluated at macroexpansion): `:pred` — a 1-arg `(fn [ev] truthy?)` filter, default accept every event; `:shape` — `:flat` (default — the atom holds a vector of events) or `:by-op` (the atom holds a map keyed by `(:operation ev)`); `:key` — listener key, default a freshly-gensym'd keyword unique to the expansion site so two brackets in one test do not collide. Returns the value of `body`'s final form. Reach it through the namespace alias — `ts/with-trace-recorder!` — like `re-frame.core`'s call-site macros (`rf/with-frame`).
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
- [Test an event handler](../core/testing/event-handlers.md) and [Test a full cascade](../core/testing/cascades.md) — the practical how-to guides for the testing surface.
