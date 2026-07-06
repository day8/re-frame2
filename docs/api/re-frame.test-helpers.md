# re-frame.test-helpers

`re-frame.test-helpers` is the **view-tree assertion axis** of re-frame2's testing surface. It treats a view as what it is — a function that returns [hiccup](../core/glossary.md#hiccup) — and walks the returned data structure: locate nodes by `:data-testid` (or any attribute), read out their text, and pluck or invoke an attached event handler. The walk helpers are pure functions over hiccup data, and the whole surface — single-frame fixture trio included — is **JVM-runnable with no JSDOM, no React, and no `act()`**. It pairs with `render-to-string` (the HTML-string view-test path, in [re-frame.ssr.md](re-frame.ssr.md)): reach for hiccup-walk when the assertion is about *structure* or *handlers*, and for `render-to-string` when it is about rendered *markup*. The sibling [re-frame.test-support.md](re-frame.test-support.md) covers the complementary runtime-state axis (registrar fixtures, `dispatch-sequence`, the `assert-*-equals` family); a test that needs both `:require`s both.

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
- **Description**: Recursively expand function components (including Form-2 fn-returning-fn components) and Form-3 class components inside a hiccup tree, invoking each with its args as Reagent's renderer would. After expansion every vector's first element is a keyword tag or a non-component value. Form-3 classes are expanded by calling the stashed `:reagent-render` fn directly — no React instantiation, no lifecycle methods (on the JVM, class detection is a no-op). The `find-*` / `text-content` walkers expand internally; call this directly to re-expand a sub-tree mid-walk.
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
- **Description**: Return everything after the tag (and optional attrs map). Always a vector (empty when the node has no children); `nil` for non-vector input.
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
- **Description**: Recursively collect string leaves under `node` (expanding nested components) and join. Numbers coerce to strings; nils are skipped; empty result is `""`. "What's the visible text?"
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
- **Description**: "Get the handler attached at this attribute on this node." Returns the value or `nil`. Equivalent to `(get (attrs node) event-key)`.
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

## Finding nodes by testid

### `find-by-testid`

- **Kind**: function
- **Signature**:
  ```clojure
  (find-by-testid tree test-id) → node
  ```
- **Description**: Convenience over `find-by-attr` keyed on `:data-testid`. The common case.
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

## Driving handlers

### `invoke-handler`

- **Kind**: function
- **Signature**:
  ```clojure
  (invoke-handler node event-key & args) → any
  ```
- **Description**: Find the handler under `event-key` on `node` and call it with `args`. Returns the handler's return value. **Throws** — `:rf.error/invoke-handler-bad-node` when `node` is not a hiccup vector, `:rf.error/invoke-handler-missing` when no handler fn sits under `event-key` (including the no-attrs-map case). The throwing failure mode is deliberate (a missing handler is almost always a test bug).
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
- **Description**: Build an attrs map carrying `:data-testid id`. The 2-arity merges `extra` into the map; `:data-testid` always wins on collision. Authoring helper at the view call site — pair it with `find-by-testid` at the assertion site.
- **Example**:
  ```clojure
  [:button (th/testid "counter-inc" {:on-click #(rf/dispatch [:counter/inc])})
   "+"]
  ;; => [:button {:data-testid "counter-inc" :on-click ...} "+"]
  ```

## Single-frame e2e fixture

The trio below — `with-app-fixture`, `expect-text`, `wait-until` — compresses the dominant single-frame e2e test pattern (create a frame, install handlers, dispatch, assert on the rendered view, destroy the frame) from five lines of boilerplate to two. Multi-frame setups keep using `re-frame.core`'s frame primitives directly; this fixture targets the common app-developer case. The two dynamic vars carry the fixture-stashed root view that the 2-arity forms of `expect-text` / `wait-until` render against.

### `with-app-fixture`

- **Kind**: macro
- **Signature**:
  ```clojure
  (with-app-fixture opts-map frame-id body+)
  (with-app-fixture opts-map          body+)   ; anonymous gensym'd id
  ```
- **Description**: Bracket `body` with a fresh single-frame fixture. Creates the frame (anonymous, or the supplied `frame-id` — a literal keyword, the discriminator between the two shapes), binds it as the current frame and stashes `:root-view` / `:root-view-args` for the body's dynamic extent, calls the opts' `:install` hook inside that scope, runs `body`, then destroys the frame on exit — success or exception. `opts-map` keys (all optional): `:install` (zero-arg fn run with the frame already bound — typically `reg-event` / `reg-sub` / `reg-view` calls the test relies on), `:root-view` (a hiccup-returning view fn, stashed for the 2-arity assertion forms), `:root-view-args` (args vector passed to `:root-view`, default `[]`), `:frame-config` (extra map merged into the frame config). Registrations land in the global registrar, so pair this with `re-frame.test-support`'s `make-reset-runtime-fixture` (or `with-fresh-registrar`) to roll them back between tests.
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
- **Description**: Assert that the hiccup node carrying `:data-testid testid` has `text-content` equal to `expected`, reporting via `clojure.test/is`. The 2-arity renders the fixture-stashed root view (`*current-root-view*`, set by `with-app-fixture`) with `*current-root-view-args*`; the 3-arity walks an explicit `tree` and needs no fixture. `testid` may be a string (`"counter-display"`) or a keyword (`:counter-display`) — keywords are coerced via `name`; anything else raises `:rf.error/testid-bad-arg`. The 2-arity raises `:rf.error/no-root-view` outside a fixture body (or when the fixture supplied no `:root-view`). Returns `true` on pass, `false` on fail (the `clojure.test` failure is already reported either way, so callers rarely read the boolean).
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
- **Description**: Bounded-deadline poll until a condition is truthy — the view-test counterpart to `re-frame.test-support/poll-until`. The predicate form polls `(pred)` until truthy or the deadline elapses; the testid form polls the fixture-stashed root view until `(text-content (find-by-testid tree testid))` equals `expected` — `testid` may be a string or keyword (coerced via `name`; anything else raises `:rf.error/testid-bad-arg`). An exception thrown by the predicate/probe counts as a falsey poll, not a propagated error — a missing fixture root view therefore surfaces as the timeout, not as `:rf.error/no-root-view`. `opts` (all optional): `:timeout-ms` (default 2000), `:interval-ms` (default 5), `:label`. Per-platform shape matches `poll-until` — JVM: synchronous, returns the truthy value, throws `ex-info` carrying `:rf.error/id` `:rf.error/wait-until-timeout` (the canonical discriminator) on timeout; CLJS: returns a `js/Promise` resolving with the truthy value or rejecting on timeout (compose with `cljs.test/async`). Use for async runs (HTTP, scheduled events, machine `:after` transitions) whose post-condition is observable in the rendered view; for sync runs, `expect-text` after `dispatch-sync` is enough.
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
- **Description**: Root-view fn stashed by `with-app-fixture` for the body's dynamic extent. The 2-arity testid forms of `expect-text` and `wait-until` read this var to know which view-fn to call when assembling the hiccup tree. `nil` outside a fixture body; callers operating on an explicit tree use the 3-arity shapes instead.

### `*current-root-view-args*`

- **Kind**: dynamic var
- **Description**: Args vector passed to `*current-root-view*` when rendering the tree. Defaults to `[]` (the common zero-arg view). `with-app-fixture` sets it from the fixture opts' `:root-view-args` key, so a root view that takes arguments (e.g. a props map) is supplied once at the fixture site rather than at every `expect-text` call.

## See also

- [re-frame.test-support.md](re-frame.test-support.md) — the runtime-state assertion axis: registrar fixtures, `dispatch-sequence`, `assert-path-equals` / `assert-db-equals`, `poll-until`.
- [re-frame.core.md](re-frame.core.md) — `dispatch-sync`, `with-new-frame`, `make-frame`, `app-db-value`, `compute-sub` — the production primitives these view tests drive.
- [re-frame.ssr.md](re-frame.ssr.md) — `render-to-string`, the HTML-string view-test path that complements hiccup-walk.
- [Test an event handler](../core/testing/event-handlers.md) and [Test a pipeline run](../core/testing/pipeline-runs.md) — the practical how-to guides for the testing surface.
