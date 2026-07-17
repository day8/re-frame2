# re-frame.test-helpers

`re-frame.test-helpers` is the view-tree assertion axis of the testing surface. A view is a function that returns [hiccup](../core/glossary.md#hiccup). These helpers walk that returned data structure: they locate nodes by `:data-testid` (or any attribute), read their text, and pluck or invoke an attached event handler. The walk helpers are pure functions over hiccup data. The whole surface, single-frame fixture trio included, runs on the JVM with no JSDOM, no React, and no `act()`.

This namespace pairs with `render-to-string` in [re-frame.ssr.md](re-frame.ssr.md), the HTML-string view-test path. Use the hiccup walkers when asserting on *structure* or *handlers*. Use `render-to-string` when asserting on rendered *markup*. The runtime-state axis (registrar fixtures, `assert-path-equals`, `poll-until`) lives in [re-frame.test-support.md](re-frame.test-support.md). A test that needs both axes `:require`s both namespaces.

```clojure
(:require [re-frame.test-helpers :as th])
```

## Tree expansion

### `expand-tree`

- **Kind**: function
- **Signature**:
  ```clojure
  (expand-tree tree) → tree
  ```
- **Description**: Recursively expand the components inside a hiccup tree, invoking each with its args just as Reagent's renderer would. This covers function components, Form-2 fn-returning-fn components, and Form-3 class components. After expansion, every vector's first element is a keyword tag or a non-component value.

  - Form-3 classes expand by calling the stashed `:reagent-render` fn directly. No React is instantiated and no lifecycle methods run. (On the JVM, class detection is a no-op.)
  - The `find-*` and `text-content` walkers already expand internally. Call `expand-tree` directly only to re-expand a sub-tree mid-walk.
- **Example**:
  ```clojure
  (th/expand-tree [parent-view {:n 5}])  ; => hiccup whose vectors all start
                                         ;    with keyword tags
  ```

## Reading hiccup nodes

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
- **Description**: Return everything after the tag and the optional attrs map. The result is always a vector, and it is empty when the node has no children. Non-vector input returns `nil`.
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
- **Description**: Recursively collect the string leaves under `node`, expanding nested components along the way, and join them into one string. Numbers coerce to strings and nils are skipped. When nothing matches, the result is `""`.
- **Example**:
  ```clojure
  (th/text-content [:div "Count: " [:b 5]])  ; => "Count: 5"
  ```

### `extract-handler`

- **Kind**: function
- **Signature**:
  ```clojure
  (extract-handler node event-key) → fn
  ```
- **Description**: Return the value under `event-key` in `node`'s attrs map, or `nil`. Equivalent to `(get (attrs node) event-key)`.
- **Example**:
  ```clojure
  (let [btn (th/find-by-testid tree "counter-inc")]
    (th/extract-handler btn :on-click))   ; => the handler fn, or nil
  ```

## Finding nodes by attribute

### `find-by-attr`

- **Kind**: function
- **Signature**:
  ```clojure
  (find-by-attr tree attr val) → node
  ```
- **Description**: Return the first hiccup node whose attrs map carries `attr == val`, or `nil` when nothing matches. It is generic over the attribute keyword: `:data-testid`, `:id`, `:data-test`, or anything custom.
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
- **Description**: Return every matching node, in depth-first order.
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
- **Description**: Return every node whose `attr` value is a string starting with `prefix`. Non-string attr values never match.
- **Example**:
  ```clojure
  ;; Matches "row-1", "row-2", … — non-string attr values never match.
  (th/find-by-attr-prefix tree :data-test "row-")
  ```

## Finding nodes by testid

### `find-by-testid`

- **Kind**: function
- **Signature**:
  ```clojure
  (find-by-testid tree test-id) → node
  ```
- **Description**: `find-by-attr` keyed on `:data-testid`.
- **Example**:
  ```clojure
  (let [tree  (counter-view {:n 5})
        label (th/find-by-testid tree "counter-label")]
    (is (= "Count: 5" (th/text-content label))))
  ```

### `find-all-by-testid`

- **Kind**: function
- **Signature**:
  ```clojure
  (find-all-by-testid tree test-id) → vector
  ```
- **Description**: `find-all-by-attr` keyed on `:data-testid`.
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
- **Description**: `find-by-attr-prefix` keyed on `:data-testid`.
- **Example**:
  ```clojure
  ;; Matches "item-1", "item-2", …
  (th/find-by-testid-prefix tree "item-")
  ```

## Driving handlers

### `invoke-handler`

- **Kind**: function
- **Signature**:
  ```clojure
  (invoke-handler node event-key & args) → any
  ```
- **Description**: Find the handler under `event-key` on `node`, call it with `args`, and return its value. A missing handler is treated as a test bug, so this throws:

  - `:rf.error/invoke-handler-bad-node` — `node` is not a hiccup vector.
  - `:rf.error/invoke-handler-missing` — no handler fn exists under `event-key` (including when the node has no attrs map at all).
- **Example**:
  ```clojure
  (let [btn (th/find-by-testid tree "counter-inc")]
    (th/invoke-handler btn :on-click))   ; calls the attached :on-click
  ```

## Authoring testids

### `testid`

- **Kind**: function
- **Signature**:
  ```clojure
  (testid id) → map
  (testid id extra) → map
  ```
- **Description**: Build an attrs map carrying `:data-testid id`. The 2-arity merges `extra` into that map, and `:data-testid` always wins on collision. Use it at the view call site. Pair it with `find-by-testid` at the assertion site.
- **Example**:
  ```clojure
  [:button (th/testid "counter-inc" {:on-click #(dispatch [:counter/inc])})
   "+"]
  ;; => [:button {:data-testid "counter-inc" :on-click ...} "+"]
  ```

## Single-frame view test — composition recipe

A connected view (one that subscribes / dispatches) needs a frame in scope. There is **no bespoke single-frame fixture macro** — the single-frame view test composes from primitives that already exist and are adopted at scale:

1. **`re-frame.test-support/make-reset-runtime-fixture`** — an `:adapter` (and an optional `:init-fn` that holds the `reg-event` / `reg-sub` / `reg-view` calls the test relies on) seats the ambient `:rf/default` frame and rolls the registrar back between tests.
2. **The hiccup walkers above** — call the root view fn directly and walk the returned tree.
3. **`re-frame.test-support/poll-until`** — for the async case (a plain `dispatch` that queues, an HTTP reply, a machine `:after`) whose settled outcome is observable in the re-rendered view.

```clojure
(ns my-app.views-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]
            [re-frame.test-helpers :as th]
            [my-app.counter :as counter]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter  counter/test-adapter   ;; your substrate adapter
                                  :init-fn  counter/install!}))     ;; reg-event / reg-sub

;; Synchronous — dispatch-sync drains before the assertion, so walk the
;; re-rendered view directly.
(deftest counter-increments
  (rf/dispatch-sync [:counter/inc])
  (rf/dispatch-sync [:counter/inc])
  (is (= "2" (th/text-content
               (th/find-by-testid (counter/main) "counter-display")))))

;; Async — an invoked :on-click fires a plain dispatch that queues, so poll
;; the re-rendered view until it settles (JVM shown; CLJS returns a Promise —
;; compose with cljs.test/async, exactly as poll-until does).
(deftest inc-button-is-wired
  (let [btn (th/find-by-testid (th/expand-tree (counter/main)) "counter-inc")]
    (th/invoke-handler btn :on-click))
  (is (ts/poll-until
        #(= "1" (th/text-content
                  (th/find-by-testid (counter/main) "counter-display")))
        {:label "counter reached 1"})))
```

See [`docs/core/testing/views.md`](../core/testing/views.md) for the worked walkthrough.

## See also

- [re-frame.test-support.md](re-frame.test-support.md) — the runtime-state assertion axis: registrar fixtures, `assert-path-equals`, `poll-until`.
- [re-frame.core.md](re-frame.core.md) — `dispatch-sync`, `with-new-frame`, `make-frame`, `app-db-value`, `compute-sub` — the production primitives these view tests drive.
- [re-frame.ssr.md](re-frame.ssr.md) — `render-to-string`, the HTML-string view-test path that complements hiccup-walk.
- [Test an event handler](../core/testing/event-handlers.md) and [Test a pipeline run](../core/testing/pipeline-runs.md) — the practical how-to guides for the testing surface.
