# re-frame.test-helpers

Helpers for testing views without a browser. A view returns [hiccup](../core/glossary.md#hiccup), and these pure functions walk that data: they find nodes by `:data-testid` or any other attribute, read their text, and call the event handlers attached to them. That catches what state assertions miss: a view that reads the wrong path or formats a value wrongly, or a button wired to dispatch into the wrong frame.

```clojure
(:require [re-frame.test-helpers :as th])
```

```clojure
(let [tree  (counter-view {:n 5})
      label (th/find-by-testid tree "counter-label")]
  (is (= "Count: 5" (th/text-content label))))
```

Everything here, the [connected view test](#a-connected-view-test) included, runs on the JVM with no DOM, no React and no `act()`. It needs a view you can call as a function, as a Reagent view is. A UIx `defui` that calls `use-sub` or `use-frame` only runs inside React's render, so mount it instead, as in [Test a view §4](../core/testing/views.md#4-uix-hook-components-mount-it-for-real). Its companion [`re-frame.test-support`](re-frame.test-support.md) holds the fixtures that reset the runtime between tests; a test that checks both state and views requires both. To assert on rendered HTML markup rather than on structure or handlers, use `render-to-string` from [re-frame.ssr](re-frame.ssr.md). [Test a view](../core/testing/views.md) walks through a complete view test.

## Reading hiccup nodes

### `attrs`

- **Kind**: function
- **Signature**:
  ```clojure
  (attrs node) → map
  ```
- **Description**: Returns the attrs map of a hiccup node, or `nil` when it has none.
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
- **Description**: Returns everything after the tag and the optional attrs map. The result is always a vector, empty when the node has no children. Non-vector input returns `nil`.
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
- **Description**: Returns the text under `node`: every string leaf, with nested components expanded, joined into one string. Numbers become strings and `nil`s are skipped. With no text, the result is `""`.
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
- **Description**: Returns the value under `event-key` in `node`'s attrs map, or `nil`. Equivalent to `(get (attrs node) event-key)`.
- **Example**:
  ```clojure
  (let [btn (th/find-by-testid tree "counter-inc")]
    (th/extract-handler btn :on-click))   ; => the handler fn, or nil
  ```

## Finding nodes by attribute

These walk the whole tree, expanding components as they go, and work with any attribute keyword: `:data-testid`, `:id`, `:data-test`, or your own.

### `find-by-attr`

- **Kind**: function
- **Signature**:
  ```clojure
  (find-by-attr tree attr val) → node
  ```
- **Description**: Returns the first node whose attrs map has `attr` equal to `val`, or `nil` when nothing matches.
- **Example**:
  ```clojure
  (th/find-by-attr tree :data-test "submit")
  (th/find-by-attr tree :id        "login")
  ```

### `find-all-by-attr`

- **Kind**: function
- **Signature**:
  ```clojure
  (find-all-by-attr tree attr val) → vector
  ```
- **Description**: Returns every node whose attrs map has `attr` equal to `val`, in depth-first order, or an empty vector when nothing matches.
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
- **Description**: Returns every node whose `attr` value is a string starting with `prefix`, or an empty vector when nothing matches. Non-string values never match.
- **Example**:
  ```clojure
  ;; Matches "row-1", "row-2", …
  (th/find-by-attr-prefix tree :data-test "row-")
  ```

## Finding nodes by testid

The same three searches, keyed on `:data-testid`.

### `find-by-testid`

- **Kind**: function
- **Signature**:
  ```clojure
  (find-by-testid tree test-id) → node
  ```
- **Description**: Returns the first node whose `:data-testid` is `test-id`, or `nil`. Equivalent to `(find-by-attr tree :data-testid test-id)`.
- **Example**:
  ```clojure
  (th/find-by-testid tree "counter-inc")  ; => the first matching node, or nil
  ```

### `find-all-by-testid`

- **Kind**: function
- **Signature**:
  ```clojure
  (find-all-by-testid tree test-id) → vector
  ```
- **Description**: Returns every node whose `:data-testid` is `test-id`, in depth-first order. Equivalent to `(find-all-by-attr tree :data-testid test-id)`.
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
- **Description**: Returns every node whose `:data-testid` starts with `prefix`. Equivalent to `(find-by-attr-prefix tree :data-testid prefix)`.
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
- **Description**: Calls the handler under `event-key` on `node` with `args` and returns its value. Use it to click a button or change an input in a test.
    - An ordinary `dispatch` inside the handler only queues the event, so `app-db` has not changed yet when `invoke-handler` returns. Wait for the result with [`re-frame.test-support/poll-until`](re-frame.test-support.md#poll-until), in the same fixture-owned frame the click dispatched into. A handler that calls `dispatch-sync` drains in place.
    - On CLJS, do not wrap the click and the wait in `rf/with-new-frame`: `poll-until` returns a Promise at once, so the body returns and destroys the frame before the queued event drains. On the JVM, `poll-until` blocks inside the body, so the frame outlives the wait.
    - A missing handler is treated as a test bug, so it throws:
        - `:rf.error/invoke-handler-bad-node`: `node` is not a hiccup vector. A `find-by-testid` that matched nothing returns `nil`, which lands here.
        - `:rf.error/invoke-handler-missing`: there is no handler fn under `event-key`, including when the node has no attrs map.
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
- **Description**: Returns an attrs map carrying `:data-testid id`, for use in a view; find the node again with `find-by-testid`. The 2-arity merges `extra` into the map, and `:data-testid` always wins on collision.
    - In a `rf/reg-view` body, write the callback with the `dispatch` that `reg-view` provides, as below; the closure captures it. A bare `rf/dispatch` in the callback runs after the render scope has unwound, finds no frame in scope (there is no fallback to `:rf/default`), and raises `:rf.error/no-frame-context`.
- **Example**:
  ```clojure
  (rf/reg-view counter-inc-button []
    [:button (th/testid "counter-inc" {:on-click #(dispatch [:counter/inc])})
     "+"])
  ;; the button node => [:button {:data-testid "counter-inc" :on-click ...} "+"]
  ```

## Tree expansion

### `expand-tree`

- **Kind**: function
- **Signature**:
  ```clojure
  (expand-tree tree) → tree
  ```
- **Description**: Expands every component in a hiccup tree by calling it with its args, as Reagent's renderer would: function components, Form-2 components (a function returning the render function) and Form-3 class components. Afterwards, every vector starts with a keyword tag or a non-component value.
    - The `find-*` functions and `text-content` already expand as they walk. Call `expand-tree` yourself only to re-expand a sub-tree mid-walk.
    - A Form-3 class expands by calling its stashed `:reagent-render` function directly. No React component is created and no lifecycle methods run.
    - Form-3 detection looks for the tag that reagent-slim's `create-class` sets, so a class from stock Reagent's `create-class` is not recognised. On the JVM there are no classes to detect.
- **Example**:
  ```clojure
  (th/expand-tree [parent-view {:n 5}])  ; => hiccup whose vectors all start
                                         ;    with keyword tags
  ```

## A connected view test

A view that subscribes or dispatches needs a frame in scope. There is no dedicated single-frame fixture; combine three pieces:

1. `re-frame.test-support/make-reset-runtime-fixture`, given an `:adapter` (and optionally an `:init-fn` for per-test setup), seats the ambient `:rf/default` frame and rolls the registrar back between tests.
2. The functions on this page: call the root view function directly and walk the tree it returns.
3. `re-frame.test-support/poll-until`, for async work (a queued `dispatch`, an HTTP reply, a machine `:after`) whose result shows up in the re-rendered view.

```clojure
(ns my-app.counter-view-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]
            [re-frame.test-helpers :as th]))

;; The app under test. In a real suite, require your app's namespaces instead.
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))

(rf/reg-sub :counter/n
  (fn [db _] (:n db 0)))

(rf/reg-view counter-view []
  [:div
   [:span (th/testid "counter-display") @(subscribe [:counter/n])]
   [:button (th/testid "counter-inc" {:on-click #(dispatch [:counter/inc])}) "+"]])

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; Synchronous: dispatch-sync drains before the assertion, so walk the
;; re-rendered view directly.
(deftest counter-shows-the-count
  (rf/dispatch-sync [:counter/inc])
  (rf/dispatch-sync [:counter/inc])
  (is (= "2" (th/text-content
               (th/find-by-testid (counter-view) "counter-display")))))

;; Async: the invoked :on-click fires a plain dispatch, which queues, so poll
;; the re-rendered view until it settles.
(deftest inc-button-is-wired
  (th/invoke-handler (th/find-by-testid (counter-view) "counter-inc") :on-click)
  (is (ts/poll-until
        #(= "1" (th/text-content
                  (th/find-by-testid (counter-view) "counter-display")))
        {:label "counter reached 1"})))
```

That is the JVM form. On CLJS the fixture takes `:async? true` and the async test composes `poll-until`'s Promise under `(async done …)`, as the CLJS example under [`poll-until`](re-frame.test-support.md#poll-until) shows.

## See also

- [re-frame.core](re-frame.core.md): `dispatch-sync`, `with-new-frame`, `make-frame`, `app-db-value` and `compute-sub`, the production functions these tests drive.
