# 10 — Testing

The testing surface is structured around one premise: **the framework's discipline at the call site pays for the tests at the boundary.** Pure handlers, an immutable `app-db`, effects-as-data, the registrar as a queryable data structure — every one of those choices makes the test path simpler. You can drive the cascade synchronously with `dispatch-sync`. You can swap fx behaviour with `with-fx-overrides`. You can assert on `app-db` via paths instead of mocking subs. You can walk a view's hiccup output without a DOM.

The surface lives across **three namespaces** because the three concerns separate cleanly:

- `re-frame.core` — the production primitives that double as testing entry points (`make-frame`, `with-frame`, `dispatch-sync`, `with-fx-overrides`, `get-frame-db`, `snapshot-of`, `compute-sub`, `machine-transition`, `sub-topology`).
- `re-frame.test-support` — the test-only fixture machinery and test-flavoured helpers. **Runtime-state axis**: registrar, frames, `app-db`, drain.
- `re-frame.test-helpers` — the view-assertion helpers (hiccup-walk + the `testid` authoring helper). **View-tree axis**: hiccup data, testids, attached handlers.

`re-frame.test-support` does **not** re-export from `re-frame.core` — a test file requires both `[re-frame.core :as rf]` and `[re-frame.test-support :as ts]`, and additionally `[re-frame.test-helpers :as th]` for view-assertion tests. The seam between the three namespaces is deliberate: production code never picks up test-flavoured assertion machinery by accident.

For the wider testing philosophy (fixtures, framework adapters, `re-frame-test` compatibility), see [008-Testing.md](../../spec/008-Testing.md).

## Runtime-state assertions (`re-frame.test-support`)

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `dispatch-sequence` | Fn | `(dispatch-sequence events)` <br> `(dispatch-sequence events opts)` | v1 | "Run this list of events end-to-end against the current frame." `opts`: `:after-each (fn [db ev] ...)` for between-event assertions, `:frame` for non-default targets. Returns the final `app-db`. |
| `assert-path-equals` | Fn | `(assert-path-equals path expected-val)` <br> `(assert-path-equals path expected-val opts)` | v1 | "Assert `(get-in db path) == expected-val`." Mismatch fires a `clojure.test/is`-style failure via `do-report`. The fn-side counterpart to the `:rf.assert/path-equals` story event-family — same name root, different runner channel. |
| `assert-db-equals` | Fn | `(assert-db-equals expected-db)` <br> `(assert-db-equals expected-db opts)` | v1 | Full-db sync assertion. Mismatch fires a `clojure.test/is`-style failure. Companion to `assert-path-equals`; reach for it when the whole-db identity matters. |
| `poll-until` | Fn | `(poll-until pred)` <br> `(poll-until pred opts)` | v1 | Bounded-deadline poll. JVM: synchronous — returns the truthy value, throws `ex-info` with `:rf.test/poll-timeout true` on timeout. CLJS: returns a `js/Promise` resolving with the truthy value or rejecting on timeout. Opts: `:timeout-ms` (default 2000), `:interval-ms` (default 5), `:label`. |
| `with-fx-overrides` | M | `(with-fx-overrides {fx-id -> override, …} body+)` | v1 | (Rowed in [03 — Effects and interceptors](03-effects.md).) Lexical-scope fx override; the most common test surface for "stub THIS fx within THIS block." Lives in `re-frame.core` but is rowed here for discoverability. |
| `compute-sub` | Fn | `(compute-sub query-v db)` | v1 | Pure sub computation against an `app-db` *value*. No cache, no reactivity — just walk the sub graph and return the value. JVM-runnable. Use in tests where you want "what would this sub return given this db?" without setting up frames. |

### Snapshot the registrar; restore after

These are the fixture primitives. The pattern is "snapshot the registrar before the test mutates registrations; restore after, regardless of pass / fail."

| API | Signature | Status | Intuition |
|---|---|---|---|
| `snapshot-registrar` | per docstring | v1 | Capture the current registrar state. |
| `restore-registrar!` | per docstring | v1 | Restore a previously captured registrar state. |
| `with-fresh-registrar` | per docstring | v1 | The composed macro — snapshot + body + restore. Most tests reach for this rather than the lower-level primitives. |
| `make-reset-runtime-fixture` | per docstring | v1 | Build a `clojure.test` fixture that resets the runtime between tests. Pair with `use-fixtures :each`. |

### A typical test

```clojure
(deftest cart-add
  (with-fresh-registrar
    (rf/reg-event-db ::add (fn [db [_ item]] (update db :cart conj item)))
    (rf/dispatch-sync [::add {:id 1 :name "widget"}])
    (assert-path-equals [:cart] [{:id 1 :name "widget"}])))
```

The pattern: fresh registrar, register the handler, dispatch synchronously, assert against the path. No mocks; no JSDOM; no React; just data.

## View assertions (`re-frame.test-helpers`)

The view-assertion surface treats a view as what it is — a function that returns hiccup — and walks the returned hiccup data structure. **JVM-runnable. No JSDOM. No React. No `act()`.** Pairs with `render-to-string` (the HTML-string view-test path per [Spec 011](../../spec/011-SSR.md)): hiccup-walk for structure / handler assertions, `render-to-string` for HTML-markup assertions.

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `expand-tree` | Fn | `(expand-tree tree)` → tree | v1 | Recursively expand fn-components and Form-3 class components inside a hiccup tree. After expansion every vector's first element is a keyword tag or a non-component value. Run this first when your view tree contains other registered views you want to assert through. |
| `attrs` | Fn | `(attrs node)` → map | v1 | Return the attrs map of a hiccup node, or `nil`. |
| `children` | Fn | `(children node)` → vector | v1 | Return everything after the tag (and optional attrs map). |
| `text-content` | Fn | `(text-content node)` → string | v1 | Recursively collect string leaves under `node` and join. Numbers coerce to strings; nils are skipped. "What's the visible text?" |
| `extract-handler` | Fn | `(extract-handler node event-key)` → fn | v1 | "Get the handler attached at this attribute on this node." Returns the value or `nil`. |
| `find-by-attr` | Fn | `(find-by-attr tree attr val)` → node | v1 | First hiccup node whose attrs map carries `attr == val`, or `nil`. Generic over the attribute keyword — `:data-testid`, `:id`, `:data-test`, custom. |
| `find-all-by-attr` | Fn | `(find-all-by-attr tree attr val)` → vector | v1 | Every matching node, in depth-first order. |
| `find-by-attr-prefix` | Fn | `(find-by-attr-prefix tree attr prefix)` → vector | v1 | Every node whose `attr` value (a string) STARTS with `prefix`. Non-string attr values do not match. |
| `find-by-testid` | Fn | `(find-by-testid tree test-id)` → node | v1 | Convenience over `find-by-attr` keyed on `:data-testid`. The common case. |
| `find-all-by-testid` | Fn | `(find-all-by-testid tree test-id)` → vector | v1 | Convenience over `find-all-by-attr` keyed on `:data-testid`. |
| `find-by-testid-prefix` | Fn | `(find-by-testid-prefix tree prefix)` → vector | v1 | Convenience over `find-by-attr-prefix` keyed on `:data-testid`. |
| `invoke-handler` | Fn | `(invoke-handler node event-key & args)` → any | v1 | Find the handler under `event-key` on `node` and call it with `args`. Returns the handler's return value. **Throws** when the node has no attrs map or no handler is registered — the throwing failure mode is deliberate (a missing handler is almost always a test bug). |
| `testid` | Fn | `(testid id)` / `(testid id extra)` → map | v1 | Build an attrs map carrying `:data-testid id`. The 2-arity merges `extra` into the map; `:data-testid` always wins on collision. Authoring helper at the view call site — pair it with `find-by-testid` at the assertion site. |

### A view-assertion test

```clojure
(rf/reg-view cart-row
  [item]
  [:tr (th/testid (str "cart-row-" (:id item)))
    [:td (:name item)]
    [:td.qty (:qty item)]
    [:button {:on-click #(rf/dispatch [::remove (:id item)])} "remove"]])

(deftest cart-row-renders-and-dispatches
  (let [tree (cart-row {:id 1 :name "widget" :qty 3})
        node (th/find-by-testid (th/expand-tree tree) "cart-row-1")]
    (is (= "widget" (th/text-content (th/find-by-attr node :td.name nil))))
    (is (fn? (th/extract-handler (th/find-by-attr node :button nil) :on-click)))))
```

No JSDOM; no `act()`; no JSON serialisation; no DOM walk. The hiccup is data; the assertions walk data.

## Multi-frame and machine testing

Tests targeting multiple frames or machines reach for the same surfaces with explicit frame opts. `dispatch-sync` accepts a frame in its envelope; `subscribe-once` accepts a frame in its second arity; `compute-sub` works against any `app-db` value (so you can drive a machine through `machine-transition` and assert on the resulting snapshot directly).

```clojure
(let [definition (rf/machine-meta :session)
      snapshot   {:state :anonymous :data {}}
      [next-snap effects] (rf/machine-transition definition snapshot [:login {:user "alice"}])]
  (is (= :authenticating (:state next-snap)))
  (is (= "alice" (get-in next-snap [:data :credentials :user])))
  (is (= [[:rf.http/managed ...]] (:fx effects))))
```

## See also

- [01 — Core](01-core.md) — `dispatch-sync`, `subscribe-once`, `make-frame`, `with-frame` rowed in dispatch / registration.
- [03 — Effects and interceptors](03-effects.md) — `with-fx-overrides` and the precedence rules.
- [07 — HTTP](07-http.md) — HTTP test stubs (`with-managed-request-stubs`, canned-reply fx).
- [12 — Registrar](12-registrar.md) — `registrations`, `handler-meta`, `sub-topology` for tests that introspect what's registered.
- [Spec 008 — Testing](../../spec/008-Testing.md) — the normative source.
