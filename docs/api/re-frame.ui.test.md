# re-frame.ui.test

`re-frame.ui.test` is the **testing surface of the compiled-view substrate**
(`re-frame.ui`). It is dev/test only — nothing in a production bundle may
`:require` it (the bundle-isolation gate enforces this).

Six names across two hosts:

- **JVM structural host (Tier 1, headless).** `render` runs the real compiled view
  against the **ambient frame** and returns the versioned public **structural tree**
  (a plain map). Traverse it with ordinary Clojure — `(tree-seq map? :children tree)`
  and a predicate — and read nodes with the `attrs` / `text` projections. Handlers are
  event vectors as data, so "what does this button do" is an equality check — no DOM,
  no click simulation, no flake.
- **CLJS mounted host (Tier 3).** `with-root` owns one real React mount with total
  teardown and binds the connected DOM **container** (query it with native
  `.querySelector`); `flush!` / `flush-presence!` settle framework and presence work.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.ui.test :as ui.test])
```

Frame scope is the programmer's ordinary bracket, not a `ui.test` concept: mint a frame
with `rf/make-frame` (+ `:initial-events`, seeded via `[:rf/set-db {…}]`) and establish
it with `rf/with-new-frame` (eval-bind-run-destroy — the frame is destroyed on exit, so
nothing leaks into `rf/frame-ids`) or `rf/with-frame` (pin one you already hold). Drive
state with `rf/dispatch-sync` and assert on a fresh `render`. The events and subs a view
touches must be `.cljc` (the standard re-frame discipline) so the JVM render can resolve
them.

## JVM structural host (Tier 1)

### `render`

- **Kind**: macro
- **Signature**:
  ```clojure
  (render [view props])
  (render [view props] opts) → structural-tree
  ```
- **Description**: Run the real compiled view against the **ambient frame** on the JVM
  and return the versioned public **structural tree** (the top node, stamped
  `:rf.ui/tree-version`). ONE input grammar — a **literal view form**, the same
  top-region grammar `ui/mount` takes (wrappers included) surrounding exactly one
  mounted view, with props carried **in** the form. ONE option —
  `{:sub-overrides {query-v value}}`, the explicit JVM read door (a map of query vector
  → value; it affects render **reads** only, never app-db). Frame scope is
  `rf/with-new-frame` / `rf/with-frame` — there is no frame option; with no ambient
  frame, rendering proceeds frameless and any frame-scoped read raises honestly. A
  plan-bearing form (a top-region `frame-root`) is **not** a render form — establish the
  frame with `rf/with-new-frame` and render the plain view. Tier-1 renders the JVM
  structural subset — no effects, no host ops; `sub` is the one-shot headless read.
  Expanding this macro in a CLJS build is a didactic compile error (the client emitter
  targets React directly; mounted CLJS tests use the Tier-3 surface).
- **Example**:
  ```clojure
  ;; Frameless: a view that reads only its props needs no frame at all —
  ;; the shortest correct test. :sub-overrides pins any sub the view reads.
  (deftest add-button-carries-intent
    (let [tree (ui.test/render [app/add-button {:product-id 42}]
                               {:sub-overrides {[:cart/locked?] false}})]
      (is (= [:cart/add 42]
             (-> (some #(when (= :button (:tag %)) %)
                       (tree-seq map? :children tree))
                 ui.test/attrs
                 :on-click)))))

  ;; Frame tier: a real registered-sub derivation reads the ambient frame's
  ;; app-db; with-new-frame binds it and tears it down on exit.
  (rf/with-new-frame [f (rf/make-frame {:initial-events [[:rf/set-db {:cart #{}}]]})]
    (rf/dispatch-sync [:cart/add 42] {:frame f})
    (let [tree (ui.test/render [cart-badge {}])]
      (is (= "1" (ui.test/text (some #(when (= :span (:tag %)) %)
                                     (tree-seq map? :children tree)))))))
  ```

### `attrs`

- **Kind**: function
- **Signature**: `(attrs node) → map | nil`
- **Description**: The **merged attribute projection** of a structural node — the one
  attribute read (a keyword lookup on a node reads its FIELDS, never its attributes, so
  a bare `(:on-click node)` is a field miss). For an **element**, `:attrs` merged with
  `:events` (collision-free by construction — the compiler routes `:on-*` to `:events`;
  handler slots carry event vectors / option maps / opaque markers **as data**). For a
  **view boundary**, the `:props` map. For a **fragment / html** node, `{}` (total, not
  an error). `nil` → `nil` (threads through a missed traversal). Intent assertion is an
  equality check on the projected handler.
- **Example**:
  ```clojure
  (is (= [:cart/add 42]
         (:on-click (ui.test/attrs
                     (some #(when (= :button (:tag %)) %)
                           (tree-seq map? :children tree))))))
  ```

### `text`

- **Kind**: function
- **Signature**: `(text node) → string | nil`
- **Description**: The concatenation of `node`'s text descendants in document order —
  descending through elements, fragments, and view boundaries alike. Trusted-HTML
  nodes contribute nothing (their content is unparsed markup). No whitespace
  normalization beyond what the tree carries. `nil` → `nil` (nil-punning).

### Traversal — ordinary Clojure

`ui.test` ships **no** selector helper: a structural tree is deliberately plain data, so
traversal is ordinary Clojure over `(tree-seq map? :children tree)` (which yields the map
nodes and skips string content):

```clojure
;; first element by tag:            (some #(when (= :button (:tag %)) %) (tree-seq map? :children tree))
;; first node at a view boundary:   (some #(when (= :shop/product-card (:view-id %)) %) (tree-seq map? :children tree))
;; every match:                     (filterv #(= :li (:tag %)) (tree-seq map? :children tree))
;; attribute assertion:             read the matched node through (ui.test/attrs node)
```

## CLJS mounted host (Tier 3)

### `with-root`

- **Kind**: macro
- **Signature**: `(with-root [container root-form] body…) → js/Promise`
- **Description**: Mount the **literal** `root-form` into a connected, test-owned DOM
  container, await the initial commit, invoke/await `body` with the connected DOM
  **container** bound, then await teardown of the React root and container on **every**
  exit. Query the bound container with native `.querySelector` / `.querySelectorAll` and
  read ordinary DOM properties/events. Browser / jsdom only (a JVM expansion is a
  tier-mismatch error). The root form is compiled by the same analyzer/emitter as
  `ui/render!`; each invocation mints a private runtime root identity, so concurrent
  calls cannot collide or claim an application's authored roots. Cleanup never masks a
  primary mount/body failure; a secondary cleanup failure rides the primary rejection as
  `rfUiTestCleanupError`. Await the returned Promise before asserting or starting another
  mounted operation.
- **Example**:
  ```clojure
  (ui.test/with-root [container [ui/frame-root {:id :app :initial-events [[:app/init]]}
                                 [counter]]]
    (-> (ui.test/flush! #(rf/dispatch-sync [:count/inc] {:frame :app}))
        (.then (fn [] (is (= "1" (.-textContent (.querySelector container ".count"))))))))
  ```

### `flush!`

- **Kind**: function (CLJS only)
- **Signature**:
  ```clojure
  (flush!) → js/Promise
  (flush! thunk) → js/Promise
  ```
- **Description**: The **sole** public compiled-view test flush. The optional `thunk`
  runs inside awaited React 19 `act`, then framework notifications and React commits
  alternate to a fixed point — await the Promise before asserting or beginning another
  mounted operation. Drive a mounted dispatch with
  `(flush! #(rf/dispatch-sync event {:frame f}))`. Bounded by the shared convergence
  budget: a registry that never quiesces fails loud with
  `:rf.error/flush-convergence-exceeded` rather than spinning. Calling it inside an open
  event drain throws `:rf.error/flush-in-open-epoch` synchronously, before Promise
  construction. **CLJS mounted host only** — the JVM structural render has no React tree
  to settle, so a Tier-1 checkpoint is a fresh `render` after a synchronous
  `rf/dispatch-sync`.

### `flush-presence!`

- **Kind**: function (CLJS only)
- **Signature**:
  ```clojure
  (flush-presence!) → js/Promise
  (flush-presence! ms) → js/Promise
  ```
- **Description**: Advance **presence** enter/exit transitions on a fake clock — the S4
  twin of `flush!` — so retained (`:unmounting`) children reach their `:timeout-ms`
  removal **without** wall-clock sleeps. `(flush-presence!)` advances to quiescence
  (every pending exit fires); `(flush-presence! ms)` advances the logical clock by `ms`,
  firing only the exits that come due. The advance and its removal commits run inside
  awaited React `act` and the returned Promise settles at the framework/React fixed
  point — await it before asserting an enter/exit. The open-event-drain guard runs
  synchronously first. **CLJS mounted host only.**

## See also

- [`re-frame.ui`](re-frame.ui.md) — the compiled-view substrate under test (`defview`,
  `mount`, `sub`, `frame-root`).
- [`re-frame.test-support`](re-frame.test-support.md) — the runtime-state testing
  surface (fixtures, registrar snapshot, `dispatch-sync`, `poll-until`).
- [`re-frame.test-helpers`](re-frame.test-helpers.md) — the hiccup-tree assertion
  helpers for the interpreted (non-compiled) substrates.
