# 10 — Testing

The testing surface is structured around one premise: **the framework's discipline at the call site pays for the tests at the boundary.** Pure handlers, an immutable `app-db`, effects-as-data, the registrar as a queryable data structure — every one of those choices makes the test path simpler. You can drive the cascade synchronously with `dispatch-sync`. You can swap fx behaviour with `with-fx-overrides`. You can assert on `app-db` via paths instead of mocking subs. You can walk a view's hiccup output without a DOM.

The surface lives across **three namespaces** because the three concerns separate cleanly:

- `re-frame.core` — the production primitives that double as testing entry points (`make-frame`, `with-frame`, `dispatch-sync`, `with-fx-overrides`, `app-db-value`, `snapshot-of`, `compute-sub`, `sub-topology`).
- `re-frame.test-support` — the test-only fixture machinery and test-flavoured helpers. **Runtime-state axis**: registrar, frames, `app-db`, drain.
- `re-frame.test-helpers` — the view-assertion helpers (hiccup-walk + the `testid` authoring helper). **View-tree axis**: hiccup data, testids, attached handlers.

`re-frame.test-support` does **not** re-export from `re-frame.core` — a test file requires both `[re-frame.core :as rf]` and `[re-frame.test-support :as ts]`, and additionally `[re-frame.test-helpers :as th]` for view-assertion tests. The seam between the three namespaces is deliberate: production code never picks up test-flavoured assertion machinery by accident.

```clojure
(:require [re-frame.core         :as rf]
          [re-frame.test-support :as ts]
          [re-frame.test-helpers :as th])
```

For the wider testing philosophy (fixtures, framework adapters, `re-frame-test` compatibility), see the how-to guides [Test an event handler](../how-to/test-an-event-handler.md) and [Test a full cascade](../how-to/test-a-cascade.md).

## Runtime-state assertions (`re-frame.test-support`)

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

### `with-fx-overrides`

Defined in [03 — Effects and interceptors](03-effects.md#with-fx-overrides) — the fx-override mechanism. Tests use it to swap fx behaviour per the override surface.

### `compute-sub`

- **Kind**: function
- **Signature**:
  ```clojure
  (compute-sub query-v db)
  ```
- **Description**: Pure sub computation against an `app-db` *value*. No cache, no reactivity — just walk the sub graph and return the value. JVM-runnable. Use in tests where you want "what would this sub return given this db?" without setting up frames.
- **Example**:
  ```clojure
  ;; What would this sub compute for this db value? No frame, no cache.
  (rf/compute-sub [:login/state] db)
  ```

### Snapshot the registrar; restore after

These are the fixture primitives. The pattern is "snapshot the registrar before the test mutates registrations; restore after, regardless of pass / fail."

#### `snapshot-registrar`

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

#### `restore-registrar!`

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

#### `with-fresh-registrar`

- **Signature**:
  ```clojure
  (with-fresh-registrar body-fn) → any
  ```
- **Description**: The composed macro — snapshot + body + restore. Most tests reach for this rather than the lower-level primitives.

#### `make-reset-runtime-fixture`

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

### A typical test

```clojure
(deftest cart-add
  (with-fresh-registrar
    (rf/reg-event ::add (fn [{:keys [db]} [_ item]] {:db (update db :cart conj item)}))
    (rf/dispatch-sync [::add {:id 1 :name "widget"}])
    (assert-path-equals [:cart] [{:id 1 :name "widget"}])))
```

The pattern: fresh registrar, register the handler, dispatch synchronously, assert against the path. No mocks; no JSDOM; no React; just data.

## View assertions (`re-frame.test-helpers`)

The view-assertion surface treats a view as what it is — a function that returns hiccup — and walks the returned hiccup data structure. **JVM-runnable. No JSDOM. No React. No `act()`.** Pairs with `render-to-string` (the HTML-string view-test path; see [`render-to-string`](../../ssr/api.md#render-to-string)): hiccup-walk for structure / handler assertions, `render-to-string` for HTML-markup assertions.

### `expand-tree`

- **Kind**: function
- **Signature**:
  ```clojure
  (expand-tree tree) → tree
  ```
- **Description**: Recursively expand fn-components and Form-3 class components inside a hiccup tree. After expansion every vector's first element is a keyword tag or a non-component value. Run this first when your view tree contains other registered views you want to assert through.

### `attrs`

- **Kind**: function
- **Signature**:
  ```clojure
  (attrs node) → map
  ```
- **Description**: Return the attrs map of a hiccup node, or `nil`.
- **Example**:
  ```clojure
  (th/attrs [:div {:k 1} "child"])   ; => {:k 1}
  (th/attrs [:div "child"])          ; => nil
  ```

### `children`

- **Kind**: function
- **Signature**:
  ```clojure
  (children node) → vector
  ```
- **Description**: Return everything after the tag (and optional attrs map).
- **Example**:
  ```clojure
  (th/children [:div {:k 1} "a" "b"])  ; => ["a" "b"]
  (th/children [:div "a" "b"])         ; => ["a" "b"]
  ```

### `text-content`

- **Kind**: function
- **Signature**:
  ```clojure
  (text-content node) → string
  ```
- **Description**: Recursively collect string leaves under `node` and join. Numbers coerce to strings; nils are skipped. "What's the visible text?"

### `extract-handler`

- **Kind**: function
- **Signature**:
  ```clojure
  (extract-handler node event-key) → fn
  ```
- **Description**: "Get the handler attached at this attribute on this node." Returns the value or `nil`.

### `find-by-attr`

- **Kind**: function
- **Signature**:
  ```clojure
  (find-by-attr tree attr val) → node
  ```
- **Description**: First hiccup node whose attrs map carries `attr == val`, or `nil`. Generic over the attribute keyword — `:data-testid`, `:id`, `:data-test`, custom.
- **Example**:
  ```clojure
  ;; Generic over the attribute keyword.
  (th/find-by-attr tree :data-test "submit")
  (th/find-by-attr tree :id        "login")
  ```

### `find-all-by-attr`

- **Kind**: function
- **Signature**:
  ```clojure
  (find-all-by-attr tree attr val) → vector
  ```
- **Description**: Every matching node, in depth-first order.
- **Example**:
  ```clojure
  (th/find-all-by-attr tree :data-test "row")  ; => every matching node
  ```

### `find-by-attr-prefix`

- **Kind**: function
- **Signature**:
  ```clojure
  (find-by-attr-prefix tree attr prefix) → vector
  ```
- **Description**: Every node whose `attr` value (a string) STARTS with `prefix`. Non-string attr values do not match.
- **Example**:
  ```clojure
  ;; Matches "row-1", "row-2", … — non-string attr values never match.
  (th/find-by-attr-prefix tree :data-test "row-")
  ```

### `find-by-testid`

- **Kind**: function
- **Signature**:
  ```clojure
  (find-by-testid tree test-id) → node
  ```
- **Description**: Convenience over `find-by-attr` keyed on `:data-testid`. The common case.

### `find-all-by-testid`

- **Kind**: function
- **Signature**:
  ```clojure
  (find-all-by-testid tree test-id) → vector
  ```
- **Description**: Convenience over `find-all-by-attr` keyed on `:data-testid`.
- **Example**:
  ```clojure
  (th/find-all-by-testid tree "cart-row")  ; => vector of every match
  ```

### `find-by-testid-prefix`

- **Kind**: function
- **Signature**:
  ```clojure
  (find-by-testid-prefix tree prefix) → vector
  ```
- **Description**: Convenience over `find-by-attr-prefix` keyed on `:data-testid`.
- **Example**:
  ```clojure
  ;; Matches "item-1", "item-2", …
  (th/find-by-testid-prefix tree "item-")
  ```

### `invoke-handler`

- **Kind**: function
- **Signature**:
  ```clojure
  (invoke-handler node event-key & args) → any
  ```
- **Description**: Find the handler under `event-key` on `node` and call it with `args`. Returns the handler's return value. **Throws** when the node has no attrs map or no handler is registered — the throwing failure mode is deliberate (a missing handler is almost always a test bug).
- **Example**:
  ```clojure
  (let [btn (th/find-by-testid tree "counter-inc")]
    (th/invoke-handler btn :on-click))   ; calls the attached :on-click
  ```

### `testid`

- **Kind**: function
- **Signature**:
  ```clojure
  (testid id) → map
  (testid id extra) → map
  ```
- **Description**: Build an attrs map carrying `:data-testid id`. The 2-arity merges `extra` into the map; `:data-testid` always wins on collision. Authoring helper at the view call site — pair it with `find-by-testid` at the assertion site.

### A view-assertion test

```clojure
(rf/reg-view cart-row
  [item]
  [:tr (th/testid (str "cart-row-" (:id item)))
    [:td (th/testid "cart-row-name") (:name item)]
    [:td.qty (:qty item)]
    [:button (th/testid "cart-row-remove" {:on-click #(rf/dispatch [::remove (:id item)])})
     "remove"]])

(deftest cart-row-renders-and-dispatches
  (let [tree (th/expand-tree (cart-row {:id 1 :name "widget" :qty 3}))
        name-cell  (th/find-by-testid tree "cart-row-name")
        remove-btn (th/find-by-testid tree "cart-row-remove")]
    (is (= "widget" (th/text-content name-cell)))
    (is (fn? (th/extract-handler remove-btn :on-click)))))
```

No JSDOM; no `act()`; no JSON serialisation; no DOM walk. The hiccup is data; the assertions walk data.

## Multi-frame testing

Tests targeting multiple frames reach for the same surfaces with explicit frame opts: `dispatch-sync` accepts a frame in its envelope, `subscribe-once` accepts a frame in its second arity, and `compute-sub` works against any `app-db` value. To scope a block of test code to a particular frame, use the frame-scoping macros `with-frame` (pin an existing frame-id) and `with-new-frame` (create a throwaway frame, run the body, destroy it on exit) — both rowed in [01 — Core §Frames](01-core.md#frames-the-scoping-primitive). For driving a machine through `machine-transition` and asserting on the resulting snapshot, see [04 — Machines](../../machines/api.md) (the `re-frame.machines/machine-transition` worked example).

## See also

- [01 — Core](01-core.md) — `dispatch-sync`, `subscribe-once`, `make-frame` rowed in dispatch / registration; `with-frame` / `with-new-frame` in the Frames section.
- [03 — Effects and interceptors](03-effects.md) — `with-fx-overrides` and the precedence rules.
- [07 — HTTP](../../resources/http-api.md) — HTTP test stubs (`with-managed-request-stubs`, canned-reply fx).
- [12 — Registrar](12-registrar.md) — `registrations`, `handler-meta`, `sub-topology` for tests that introspect what's registered.
- [Test an event handler](../how-to/test-an-event-handler.md) and [Test a full cascade](../how-to/test-a-cascade.md) — the practical how-to guides for the testing surface.
