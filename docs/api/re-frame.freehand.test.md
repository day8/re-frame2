# re-frame.freehand.test

!!! warning "Pre-alpha — `day8/re-frame2-freehand` is not published"

    Freehand ships inside the re-frame2 monorepo and is **not published to
    Clojars**, and there is no date at which it will be. You resolve it with
    `:local/root` from a checkout — see
    [Install](../core/freehand/install.md). The public surface is deliberately
    still open: verbs can change while we learn from real apps.

`re-frame.freehand.test` is the **structural test surface** of the Freehand view
substrate (EP-0036; artefact `day8/re-frame2-freehand`, conventionally aliased `t`).
Five names query semantic **values** over the versioned structural tree and one
**bracket** opens the discardable render a state-reading view needs; none simulates
behaviour.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.freehand :as v]
          [re-frame.freehand.test :as t])

(deftest save-button-carries-intent
  (let [tree   (t/render [save-button {:article-id 42}])
        button (t/find tree #(= :button (:tag %)))]
    (is (= "Save" (t/text button)))
    (is (= [:article/save 42] (:on-click (t/attrs button))))))
```

Handler sites are **event vectors as data**, so "what does this button do" is an
equality check — no DOM, no click simulation, no flake.

**Both hosts, both modes, one tree.** `t/render` runs the real declaration: an
interpreted body is walked, a compiled body's structural realisation is run, and the
call spelling and the resulting tree are identical either way. The same declaration
answers the same tree on the JVM and in the ClojureScript runtime, so a `.cljc`
structural test is a cross-host claim rather than a JVM claim wearing a `.cljc`
extension.

**A view that reads state renders inside `with-render`.** `v/sub` is legal only
during an active declared render, and `render` is a walk rather than a host, so it
opens none of its own — wrap the render and the view under test runs **as written**,
with nothing published:

```clojure
(rf/dispatch-sync [:basket/add 42])

(let [tree (t/with-render (t/render [basket-total {}]))]
  (is (= "1 item" (t/text tree))))
```

**Frame scope is your ordinary bracket**, not a `t` concept: mint and establish a
frame with `rf/with-new-frame` (eval-bind-run-destroy) or pin one you already hold
with `rf/with-frame`, drive state with `rf/dispatch-sync`, and assert on a **fresh**
`render`. There is no frame option and no fixture. The structural walk subscribes to
nothing and dispatches nothing, so the events and subs a view under test touches must
be `.cljc` — the standard re-frame discipline. Host-bearing behaviour (real listeners
firing into the DOM, focus, presence timing, error recovery) belongs to the mounted
browser tier, not this one.

**Dev/test scope, public surface.** Nothing in a production bundle may `:require`
this namespace. That is a bundling rule, not a privacy one: the six names below are
a published surface rowed in the API manifest at the `testing` tier, so a rename, a
signature change or an accidental export turns the public-API gate red on both hosts.

## Rendering

### `render`

- **Kind**: function
- **Signature**:
  ```clojure
  (render form) → structural-tree
  ```
- **Description**: run `form` and answer its versioned structural tree — the value a
  structural test asserts against. `form` is a declared-view call
  (`[view props & children]`, in either execution mode) or arbitrary markup; the
  return is always the ROOT node, a map carrying `:rf.ui/tree-version`, and a form
  that denotes text, several nodes, or nothing roots in a fragment. Plain,
  serialisable data: `(tree-seq map? :children tree)` is the whole traversal API, and
  the tree prints and reads back losslessly. See
  [spec/008-Testing.md](../../spec/008-Testing.md#freehand-structural-and-mounted-testing).
- **Example**:
  ```clojure
  (t/render [save-button {:article-id 42}])
  ;; => {:view-id :app.article/save-button
  ;;     :children [{:tag      :button
  ;;                 :events   {:on-click [:article/save 42]}
  ;;                 :children ["Save"]}]
  ;;     :rf.ui/tree-version 1}
  ```

### `with-render`

- **Kind**: macro
- **Signature**:
  ```clojure
  (with-render body…) → the body's value
  ```
- **Description**: run `body` inside a **discardable render**, and answer its value —
  the bracket a view that reads state is rendered in. `v/sub` is legal only during an
  active declared render
  ([spec/006-ReactiveSubstrate.md](../../spec/006-ReactiveSubstrate.md#the-subscription-law)),
  and `render` is a walk rather than a host, so a view whose body reads state is
  refused outside this bracket with `:rf.error/view-read-outside-render`. Inside it,
  the view renders **as written** — rewriting it to take a one-shot read would be
  testing something other than the view.

  **It publishes nothing.** The render it opens is never committed, which is the
  substrate's own abandoned-render path: the reads inside resolve and probe but
  acquire nothing, so no ref-count, no watch, no cache node and no disposal obligation
  survives the bracket. Render as often as you like; each `render` is a fresh reading
  of current state.

  **It takes no frame.** Frame scope is your ordinary bracket — `rf/with-new-frame`
  for a fresh owned frame, `rf/with-frame` to pin one you hold — exactly as it is for
  a `render` that reads nothing.
- **Example**:
  ```clojure
  (deftest the-badge-shows-the-basket-count
    (rf/dispatch-sync [:basket/add 42])
    (let [tree (t/with-render (t/render [basket-badge {}]))]
      (is (= "1" (t/text tree)))))
  ```

## Finding nodes

`find` / `find-all` are conveniences over the whole traversal API, which is ordinary
Clojure — `(tree-seq map? :children tree)` and a predicate over `(:tag %)` (element
tag) or `(:view-id %)` (view boundary). There is deliberately no selector language.

Both hand `pred` **node maps only**. Text content is a host string rather than a node,
and a raw walk yields those strings as leaves, so a membership test like
`#(contains? % :rf.ui/presence)` reads the same on the JVM and in ClojureScript. Read
text with `t/text` on the owning node; walk with `tree-seq` directly if you want the
raw leaves.

### `find`

- **Kind**: function
- **Signature**:
  ```clojure
  (find tree pred) → node | nil
  ```
- **Description**: the FIRST node under `tree` — the root included, in document
  order — for which `pred` is truthy, or `nil`. `nil` threads through a missed match,
  so `(t/attrs (t/find tree p))` nil-puns rather than throwing when nothing matched.
  The name shadows `clojure.core/find`, so the namespace excludes it; require this
  one under an alias (`t`) rather than `:refer`.
- **Example**:
  ```clojure
  (t/find tree #(= :button (:tag %)))
  (t/find tree #(= :shop/product-card (:view-id %)))
  ```

### `find-all`

- **Kind**: function
- **Signature**:
  ```clojure
  (find-all tree pred) → vector
  ```
- **Description**: every node under `tree` (the root included) for which `pred` is
  truthy, in document order; an empty vector when nothing matches. `pred` reads node
  **fields**, over node **maps only**, the same way `find`'s does.
- **Example**:
  ```clojure
  (count (t/find-all tree #(= :li (:tag %))))
  ```

## Reading a node

### `attrs`

- **Kind**: function
- **Signature**:
  ```clojure
  (attrs node) → map | nil
  ```
- **Description**: the MERGED attribute projection of a structural node — the ONE
  attribute read. For an **element**, `:attrs` merged with `:events` (collision-free
  by construction: the compiler routes `:on-*` to `:events`, and handler slots carry
  event vectors, options maps or opaque markers **as data**). For a **view
  boundary**, the `:props` map. For a **declared host** — the node a `v/defhost`
  crossing emits — the `:props` map too: the authored ordinary props, with each
  filled callback position recorded as its opaque role marker. For a **fragment**
  or **trusted-HTML** node, `{}` — total, not an error. `nil` → `nil`, so a missed
  traversal threads through.

  A keyword lookup on a node reads its **fields**, never its attributes:
  `(:on-click node)` is a field miss. Intent assertion is therefore an equality check
  on the projection. A projection over a malformed value fails loud with
  `:rf.error/ui-tree-malformed` rather than reading a plausible answer off a broken
  tree. Projections are owned by
  [spec/004B-UI-Tree-and-Conversion.md](../../spec/004B-UI-Tree-and-Conversion.md#projections--how-nodes-are-read).
- **Example**:
  ```clojure
  (is (= [:cart/add 42]
         (:on-click (t/attrs (t/find tree #(= :button (:tag %)))))))
  ```

### `text`

- **Kind**: function
- **Signature**:
  ```clojure
  (text node) → string | nil
  ```
- **Description**: the concatenation of `node`'s text descendants in document order,
  descending through elements, fragments and view boundaries alike. Trusted-HTML
  nodes contribute nothing — their content is unparsed markup, not text data. No
  whitespace normalization beyond what the tree carries. `nil` → `nil`.

## Related

- [spec/008-Testing.md](../../spec/008-Testing.md#freehand-structural-and-mounted-testing) — the contract
- [spec/004B-UI-Tree-and-Conversion.md](../../spec/004B-UI-Tree-and-Conversion.md#the-node-schema--version-1) — the node schema the tree carries
- [`re-frame.freehand`](re-frame.freehand.md) — the substrate under test
- [`re-frame.ui.test`](re-frame.ui.test.md) — the compiled-view substrate's donor test surface
