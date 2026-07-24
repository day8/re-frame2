# The procedure — incremental, never big-bang

> A bad bulk migration is worse than none. Migrate a namespace / a closed
> subtree at a time; verify it renders and its tests pass; iterate. The unit
> of migration is the **whole view** (cardinal rule 2), and the unit of a
> *pass* is a **closed subtree**.

## Pre-flight — is this migration even in scope?

Confirm all three, or stop:

1. **The app is already on re-frame2.** Freehand is a re-frame2 view layer. If
   the app is still on re-frame v1, the events/subs/db migration comes first —
   route to [`re-frame-migration`](../re-frame-migration) and come back only when
   that is done.
2. **The author specifically wants Freehand.** If they are content on Reagent
   (the supported default), there is nothing to do — say so. Don't migrate views
   because you can.
3. **The Freehand artefact is actually available to the target project.**
   Freehand ships in `day8/re-frame2-freehand`, which is **pre-publication** —
   there is no released Maven coordinate to depend on. A project can adopt it
   only by consuming the in-tree / git-source artefact. If it has no path to
   that source, there is nothing to migrate *onto* yet — say so and wait. This is
   migration honesty, not a reason to publish early.

## Step 1 — Scope a closed subtree

Pick a namespace, or a leaf-to-root view subtree, that is **not called from views
staying on Reagent**. This is stricter than it sounds and it is not a style
preference: there is no outward bridge today, so a converted view can only be
mounted by another Freehand view or by a root. A converted view whose only caller
is still Reagent will never render.

So convert **leaf views first, shared components last**, closing the subtree
bottom-up until a root or an already-converted parent mounts it. Flag any inbound
Reagent call site you cannot include in the subtree — that view is a boundary,
and the whole subtree above it waits.

## Step 2 — Gate every candidate view (whole-view law)

Before touching a view, scan its whole body for **D/R hits**, then route by tier:

- **An R hit → hold the WHOLE view on Reagent**, honestly, and record why.
  Foreign React heads and Reagent wrapper libraries (MIG-09/10/22), trusted
  markup (MIG-34), `:ref` (MIG-29), Reagent introspection and schedulers
  (MIG-35), a frame-pinned reactive read (MIG-03). →
  [`catalog-reject.md`](catalog-reject.md).
- **A D hit → decide it with the author, then convert the WHOLE view or hold the
  WHOLE view** — never a partial body. The judgment calls are catalogued in
  [`catalog-judgment.md`](catalog-judgment.md): state (MIG-16), lifecycle
  (MIG-17), the `:on-*` handler split (MIG-18), derived state and the ratom-store
  restructure (MIG-19/20), SSR path routing (MIG-23), ambient reads in plain fns
  (MIG-26), fn props on internal views (MIG-27), computed props (MIG-28),
  runtime-built markup (MIG-30), and the loop / render-prop shaping calls
  (MIG-08/13).

Do not rewrite the clean parts of a held view — whole-view coherence,
[`gotchas.md`](gotchas.md).

Two things are **not** view gates here. The **mechanical** rules are applied
directly in Step 3, never held. And an **effectful sub body (MIG-25)** is a
dataflow-side finding you surface for the author — the view's own deref converts
fine once the sub is pure.

## Step 3 — Apply the M-tier rewrites to the clean views

For each fully-clean view, apply [`catalog-mechanical.md`](catalog-mechanical.md)
**atomically per view**: the `v/defview` header, the params→map change, and
**every call site** of that view change in one edit (MIG-01). Within a view the
remaining rewrites are order-free — deref-drop, dispatch lifts, prop respelling,
key-meta, `doall` strip, the `route-link` head. Cite the `MIG-NN` id for each
change.

Everything you emit stays **interpreted**. Do not add `{:compiled true}` to
anything during a migration pass.

## Step 4 — Fix requires and the root last

- **Requires (MIG-24):** add `[re-frame.freehand :as v]`; drop `reagent.*`
  requires **only** when the namespace has zero remaining uses (a held view keeps
  them). The `v` alias is load-bearing — the projection markers `::v/value` /
  `::v/checked` / `::v/key` / `::v/scroll-top` / `::v/new-state` resolve through it.
- **Root (MIG-15):** once per root. `v/mount` carries the frame preflight in its
  `:frame` opt, and Freehand needs no adapter install of its own. On a mixed page
  keep `(rf/init! reagent-adapter/adapter)` for the roots still on Reagent, and
  delete it only when the last one converts — confirm the root inventory with the
  author.

## Step 5 — Compile and test (the skill runs the gates); the programmer renders

Run the nearest safe noninteractive gate **yourself** — discover the project's
compile/test command (`npx shadow-cljs compile …`, `npm test`, `clojure -M:test`)
and run it (cardinal rule 7). The genuinely interactive step — booting a dev
build and eyeballing the render — stays with the programmer when there is no
connected runtime to drive.

**"Compiles" is necessary, not sufficient.** Interpreted Freehand moves most view
errors to run time by design, and the two that bite hardest — a `v/sub` in a
callback, a Reagent introspection call (MIG-35) — both compile clean. So the
done-bar for a subtree is:

1. it **compiles** (the skill runs this);
2. it **renders** — the programmer boots a dev build and eyeballs the converted
   views, and reads the live frame with `re-frame2-pair` if a subtle behaviour
   changed;
3. its **tests pass** (the skill runs these).

### Structural tests are the cheap half of (2)

Handler slots hold event vectors **as data**, so "what does this button do" is an
equality check with no browser. `re-frame.freehand.test` (conventionally aliased
`t`) is five names over the structural tree — `render`, `find`, `find-all`,
`attrs`, `text`:

```clojure
(:require [re-frame.core :as rf]
          [re-frame.freehand.test :as t])

(deftest add-button-carries-intent
  (rf/with-new-frame [_ (rf/make-frame {:initial-events [[:rf/set-db {:cart #{}}]]})]
    (let [tree   (t/render [product-card {:product {:id 42 :name "Hat"}}])
          button (t/find tree #(= :button (:tag %)))]
      (is (= "Add to cart" (t/text button)))
      (is (= [:cart/add 42] (:on-click (t/attrs button)))))))
```

Frame scope is the ordinary bracket (`rf/with-new-frame` / `rf/with-frame`), state
is driven with `rf/dispatch-sync`, and the checkpoint is a **fresh** `render` —
there is no frame option and no fixture. `(:on-click node)` reads a *field* and
misses; the projection `(t/attrs node)` is the attribute read. Host-bearing
behaviour — real listeners, focus, presence timing — belongs to a mounted browser
test, not this tier.

A migrated view that had no test is a good place to add one: it costs three lines
and it pins the intent the migration just rewrote.

## A single converted file is PROVISIONAL

If a view's callers live in *other* files that are still Reagent, converting just
its file leaves it un-rendered — there is no wrapper that lets a Reagent parent
mount it. It compiles, and "compiles ≠ renders". Treat such a file as provisional
until a converted parent or a root mounts it. That is exactly why the unit of a
pass is a *closed subtree*, not a lone file.

## Resuming an interrupted migration

Because each pass leaves a closed subtree compiling, rendering and tested, an
interrupted migration resumes cleanly: the converted subtrees are done, the held
views are recorded with their reasons, and the next closed subtree is the next
unit. There is no global half-state to reconcile — that is the payoff of never
big-banging.
