# re-frame.test-helpers

`re-frame.test-helpers` is the view-tree assertion axis of the testing surface. A view is a function that returns [hiccup](../core/glossary.md#hiccup). These helpers walk that returned data structure: they locate nodes by `:data-testid` (or any attribute), read their text, and pluck or invoke an attached event handler. The walk helpers are pure functions over hiccup data. The whole surface, single-frame fixture trio included, runs on the JVM with no JSDOM, no React, and no `act()`.

This namespace pairs with `render-to-string` in [re-frame.ssr.md](re-frame.ssr.md), the HTML-string view-test path. Use the hiccup walkers when asserting on *structure* or *handlers*. Use `render-to-string` when asserting on rendered *markup*. The runtime-state axis (registrar fixtures, `dispatch-sequence`, the `assert-*-equals` family) lives in [re-frame.test-support.md](re-frame.test-support.md). A test that needs both axes `:require`s both namespaces.

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
  [:button (th/testid "counter-inc" {:on-click #(rf/dispatch [:counter/inc])})
   "+"]
  ;; => [:button {:data-testid "counter-inc" :on-click ...} "+"]
  ```

## Single-frame e2e fixture

The trio below — `with-app-fixture`, `expect-text`, and `wait-until` — covers the single-frame e2e pattern: create a frame, install handlers, dispatch, assert on the rendered view, then destroy the frame. Multi-frame setups use `re-frame.core`'s frame primitives directly. The two dynamic vars carry the fixture-stashed root view. The 2-arity forms of `expect-text` and `wait-until` render against it.

### `with-app-fixture`

- **Kind**: macro
- **Signature**:
  ```clojure
  (with-app-fixture opts-map frame-id body+)
  (with-app-fixture opts-map          body+)   ; anonymous gensym'd id
  ```
- **Description**: Bracket `body` with a fresh single-frame fixture. In order, the macro:

  - creates the frame — anonymous, or under the supplied `frame-id` (a literal keyword; its presence is what selects between the two shapes);
  - binds it as the current frame;
  - stashes `:root-view` / `:root-view-args` for the body's dynamic extent;
  - calls the opts' `:install` hook inside that scope;
  - runs `body`;
  - destroys the frame on exit, whether `body` succeeded or threw.

  `opts-map` keys (all optional):

  - `:install` — a zero-arg fn run with the frame already bound. Typically it holds the `reg-event` / `reg-sub` / `reg-view` calls the test relies on.
  - `:root-view` — a hiccup-returning view fn, stashed for the 2-arity assertion forms.
  - `:root-view-args` — args vector passed to `:root-view` (default `[]`).
  - `:frame-config` — extra map merged into the frame config.

  Registrations land in the global registrar, so pair this with `re-frame.test-support`'s `make-reset-runtime-fixture` (or `with-fresh-registrar`) to roll them back between tests.
- **Example**:
  ```clojure
  (deftest counter-increments
    (th/with-app-fixture {:install  counter/install!
                          :root-view counter/main}
                         :test-app
      (rf/dispatch-sync [:counter/inc])
      (rf/dispatch-sync [:counter/inc])
      (th/expect-text :counter-display "2")))
  ```

### `expect-text`

- **Kind**: function
- **Signature**:
  ```clojure
  (expect-text testid expected)
  (expect-text tree testid expected)
  ```
- **Description**: Assert that the hiccup node carrying `:data-testid testid` has `text-content` equal to `expected`. Failures report through `clojure.test/is`. Returns `true` on pass and `false` on fail; the `clojure.test` failure is reported either way.

  - The 2-arity renders the fixture-stashed root view (`*current-root-view*`, set by `with-app-fixture`) with `*current-root-view-args*`. It raises `:rf.error/no-root-view` outside a fixture body, or when the fixture supplied no `:root-view`.
  - The 3-arity walks an explicit `tree` and needs no fixture.
  - `testid` may be a string (`"counter-display"`) or a keyword (`:counter-display`, coerced via `name`). Anything else raises `:rf.error/testid-bad-arg`.
- **Example**:
  ```clojure
  ;; 2-arity — against the fixture's :root-view:
  (th/expect-text :counter-display "2")

  ;; 3-arity — against an explicit tree:
  (th/expect-text (counter-view {:n 5}) "counter-label" "Count: 5")
  ```

### `wait-until`

- **Kind**: function
- **Signature**:
  ```clojure
  (wait-until pred)
  (wait-until pred opts)
  (wait-until testid expected)
  (wait-until testid expected opts)
  ```
- **Description**: Poll until a condition is truthy, within a bounded deadline. This is the view-test counterpart to `re-frame.test-support/poll-until`. Use it for async runs (HTTP, scheduled events, machine `:after` transitions) whose post-condition shows up in the rendered view. For sync runs, `expect-text` after `dispatch-sync` suffices.

  - The predicate form polls `(pred)` until truthy or the deadline elapses.
  - The testid form polls the fixture-stashed root view until `(text-content (find-by-testid tree testid))` equals `expected`. `testid` may be a string or a keyword (coerced via `name`); anything else raises `:rf.error/testid-bad-arg`.
  - An exception thrown by the predicate or probe counts as a falsey poll, not a propagated error. So a missing fixture root view surfaces as the timeout, not as `:rf.error/no-root-view`.
  - `opts` (all optional): `:timeout-ms` (default 2000), `:interval-ms` (default 5), `:label`.

  Per-platform shape matches `poll-until`:

  - JVM — synchronous. Returns the truthy value. On timeout it throws an `ex-info` carrying `:rf.error/id` `:rf.error/wait-until-timeout` (the canonical discriminator).
  - CLJS — returns a `js/Promise` that resolves with the truthy value or rejects on timeout. Compose it with `cljs.test/async`.
- **Example**:
  ```clojure
  ;; JVM — testid form; blocks until the display text settles.
  (th/wait-until :counter-display "2" {:label "counter reached 2"})

  ;; CLJS — predicate form returns a js/Promise; compose with cljs.test/async.
  (-> (th/wait-until #(= 3 (:n (rf/app-db-value :rf/default))))
      (.then (fn [_] (done))))
  ```

### `*current-root-view*`

- **Kind**: dynamic var
- **Description**: The root-view fn stashed by `with-app-fixture` for the body's dynamic extent. The 2-arity testid forms of `expect-text` and `wait-until` read this var to know which view-fn to call when assembling the hiccup tree. It is `nil` outside a fixture body. Callers operating on an explicit tree use the 3-arity shapes instead.

### `*current-root-view-args*`

- **Kind**: dynamic var
- **Description**: The args vector passed to `*current-root-view*` when rendering the tree. Defaults to `[]`, the common zero-arg view. `with-app-fixture` sets it from the fixture opts' `:root-view-args` key. A root view that takes arguments (e.g. a props map) is therefore supplied once, at the fixture site, rather than at every `expect-text` call.

## See also

- [re-frame.test-support.md](re-frame.test-support.md) — the runtime-state assertion axis: registrar fixtures, `dispatch-sequence`, `assert-path-equals` / `assert-db-equals`, `poll-until`.
- [re-frame.core.md](re-frame.core.md) — `dispatch-sync`, `with-new-frame`, `make-frame`, `app-db-value`, `compute-sub` — the production primitives these view tests drive.
- [re-frame.ssr.md](re-frame.ssr.md) — `render-to-string`, the HTML-string view-test path that complements hiccup-walk.
- [Test an event handler](../core/testing/event-handlers.md) and [Test a pipeline run](../core/testing/pipeline-runs.md) — the practical how-to guides for the testing surface.
