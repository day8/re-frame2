# re-frame.ui.test

`re-frame.ui.test` is the **testing surface of the compiled-view substrate**
(`re-frame.ui`). It is dev/test only — nothing in a production bundle may
`:require` it (the bundle-isolation gate enforces this).

It has two tiers that share nothing:

- **Tier 1 — headless structural render.** `render` runs the real compiled view
  against a real frame **on the JVM** and returns the versioned public **structural
  tree** (a plain map). `find` / `find-all` query it with a closed selector grammar;
  `attrs` / `text` are the read projections. Handlers are event vectors as data, so
  "what does this button do" is an equality check — no DOM, no click simulation, no
  flake.
- **Tier 3 — mounted DOM.** `with-root` owns one real React mount with total
  teardown; `query` answers a native CSS selector against that mounted root.

Handing a CSS string to `find`, a structural tree to `query`, or a DOM element to
`attrs` / `text` is a typed error (`:rf.error/ui-test-tier-mismatch`) that points at
the other tier.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.ui.test :as ui.test])
```

Test frames are minted with the canonical `rf/make-frame` + `:initial-events` (seed
via `[:rf/set-db {…}]`) — one frame-init grammar, shared with production. The
events and subs a view touches must be `.cljc` (the standard re-frame discipline) so
the JVM render can resolve them.

## Tier 1 — headless structural render

### `render`

- **Kind**: macro
- **Signature**:
  ```clojure
  (render root-or-view)
  (render root-or-view opts) → structural-tree
  ```
- **Description**: Run the real compiled view against a real frame on the JVM and
  return the versioned public **structural tree** (the top node, stamped
  `:rf.ui/tree-version`). Accepts exactly two forms: a **view reference** (the
  compile-resolved `defview` var/symbol — props ride `{:props …}`, a frame rides
  `{:frame …}`), or a **literal root form** (the same root grammar `ui/mount` takes;
  `{:props …}` is rejected because props live in the form). A **plan-bearing** root
  form (a top-region `frame-root`) OWNS its frames: its plans preflight **fresh
  isolated** test frames before the render and tear them down after, so `{:frame …}`
  alongside it is rejected. `opts` are closed: `:frame`, `:props` (view-reference form
  only), `:sub-overrides` (a map of query vector → value, the explicit JVM read door).
  Without a frame, rendering proceeds frameless and any frame-scoped read raises
  honestly. Tier-1 renders the JVM structural subset — no effects, no host ops; `sub`
  is the one-shot headless read. Expanding this macro in a CLJS build is a didactic
  compile error (the client emitter targets React directly; mounted CLJS tests use the
  Tier-3 surface).
- **Example**:
  ```clojure
  (let [frame (rf/make-frame {:initial-events [[:rf/set-db {:cart #{}}]]})
        tree  (ui.test/render [product-card {:product p}] {:frame frame})]
    (is (= [:cart/add 42]
           (-> tree (ui.test/find :button) ui.test/attrs :on-click)))
    (is (= "Add to cart"
           (-> tree (ui.test/find :button) ui.test/text))))
  ```

### `find`

- **Kind**: function
- **Signature**: `(find tree selector) → node | nil`
- **Description**: The **first** node of `tree` matching `selector`, in depth-first
  pre-order (document order) — the tree node itself is tested first, then its
  descendants. Returns the structural node (itself a valid `tree` argument, so finds
  compose), or `nil` on no match (idiomatic nil-punning threads through). The closed
  selector grammar: an **unqualified keyword** (element tag, exact), a **qualified
  keyword** or **`defview` var** (view boundary), an **attr map** (each entry present
  and `rf=` to the expected value in the attrs projection; events match by vector), or
  a **pred fn** (the escape; receives map nodes). A CSS string, a vector selector, or
  any other value is a typed error naming the alternative.
- **Example**:
  ```clojure
  ;; compose finds to scope a query:
  (-> tree (ui.test/find :form) (ui.test/find :button))
  ```

### `find-all`

- **Kind**: function
- **Signature**: `(find-all tree selector) → [node …]`
- **Description**: **All** nodes of `tree` matching `selector`, as a vector in
  document order (possibly empty — `[]` on no match). Same closed selector grammar as
  `find`.

### `attrs`

- **Kind**: function
- **Signature**: `(attrs node) → map | nil`
- **Description**: The **merged attribute projection** of a structural node — the one
  attribute read (a keyword lookup on a node reads its FIELDS, never its attributes).
  For an **element**, `:attrs` merged with `:events` (collision-free by construction —
  the compiler routes `:on-*` to `:events`; handler slots carry event vectors / option
  maps / opaque markers **as data**). For a **view boundary**, the `:props` map. For a
  **fragment / html** node, `{}` (total, not an error). `nil` → `nil` (threads through
  a missed `find`). Intent assertion is an equality check on the projected handler.
- **Example**:
  ```clojure
  (is (= [:cart/add 42]
         (:on-click (ui.test/attrs (ui.test/find tree :button)))))
  ```

### `text`

- **Kind**: function
- **Signature**: `(text node) → string | nil`
- **Description**: The concatenation of `node`'s text descendants in document order —
  descending through elements, fragments, and view boundaries alike. Trusted-HTML
  nodes contribute nothing (their content is unparsed markup). No whitespace
  normalization beyond what the tree carries. `nil` → `nil` (nil-punning).

## Tier 3 — mounted DOM

### `with-root`

- **Kind**: macro
- **Signature**: `(with-root [root root-form] body…) → js/Promise`
- **Description**: Mount the **literal** `root-form` into a connected, test-owned DOM
  container, await the initial commit, invoke/await `body` with its opaque mounted
  `root`, then await teardown of the React root and container on **every** exit.
  Browser / jsdom only (a JVM expansion is a tier-mismatch error). The root form is
  compiled by the same analyzer/emitter as `ui/render!`; each invocation mints a
  private runtime root identity, so concurrent calls cannot collide or claim an
  application's authored roots. Cleanup never masks a primary mount/body failure; a
  secondary cleanup failure rides the primary rejection as `rfUiTestCleanupError`.
  Await the returned Promise before asserting or starting another mounted operation.
- **Example**:
  ```clojure
  (ui.test/with-root [root [ui/frame-root {:id :app :initial-events [[:app/init]]}
                            [counter]]]
    (-> (ui.test/flush! #(ui.test/dispatch! :app [:count/inc]))
        (.then (fn [] (is (= "1" (.-textContent (ui.test/query root ".count"))))))))
  ```

### `query`

- **Kind**: function
- **Signature**: `(query root css-selector) → js/Element | nil`
- **Description**: The Tier-3 live-DOM counterpart of `find`: a **mounted** root (from
  `with-root`) + a **native CSS selector string**, answered by the host DOM's
  `querySelector`. It shares nothing with the Tier-1 grammar — no `rf=` matching, no
  structural projections, no view-id selectors; CSS is the whole contract. `root` must
  be the live opaque value bound by `with-root`; the selector must be a string. A miss
  returns `nil`, exactly like `querySelector`. Handing it a structural tree is a
  tier-mismatch error pointing at `find`.

## Driving state and draining work

### `dispatch!`

- **Kind**: function
- **Signature**: `(dispatch! frame-target event) → nil`
- **Description**: Real dispatch + drain into `frame-target` (a frame value or id):
  processes `event` synchronously end-to-end, then drains any synchronously-enqueued
  events to fixed point. Drive state with real events, re-render, and assert on the new
  tree — no click simulation. Under a mounted (Tier-3) root, wrap it in `flush!` so the
  React commit settles before you assert.

### `flush!`

- **Kind**: function
- **Signature**:
  ```clojure
  ;; JVM (Tier 1): synchronous
  (flush!) → nil
  ;; CLJS (Tier 3): Promise-backed
  (flush!) → js/Promise
  (flush! thunk) → js/Promise
  ```
- **Description**: Drain framework work to quiescence. On the **JVM** it synchronously
  drains the host-agnostic ViewCell registry (there is no React tree to settle) and
  returns `nil`. On **CLJS** it returns a Promise: the optional `thunk` runs inside
  awaited React 19 `act`, then framework notifications and React commits alternate to a
  fixed point — await the Promise before asserting or beginning another mounted
  operation. Both paths are bounded by the shared convergence budget: a registry that
  never quiesces fails loud with `:rf.error/flush-convergence-exceeded` rather than
  spinning. Calling it inside an open event drain throws `:rf.error/flush-in-open-epoch`
  (CLJS) / fails the shared open-drain guard.

## See also

- [`re-frame.ui`](re-frame.ui.md) — the compiled-view substrate under test (`defview`,
  `mount`, `sub`, `frame-root`).
- [`re-frame.test-support`](re-frame.test-support.md) — the runtime-state testing
  surface (fixtures, registrar snapshot, `dispatch-sync`, `poll-until`).
- [`re-frame.test-helpers`](re-frame.test-helpers.md) — the hiccup-tree assertion
  helpers for the interpreted (non-compiled) substrates.
