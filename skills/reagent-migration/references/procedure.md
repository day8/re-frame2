# The procedure — incremental, never big-bang

> A bad bulk migration is worse than none. Migrate a namespace / a closed
> subtree at a time; verify it renders and its tests pass; iterate. The unit
> of migration is the **whole view** (cardinal rule 2), and the unit of a
> *pass* is a **closed subtree**.

## Pre-flight — is this migration even in scope?

Confirm all three, or stop:

1. **The app is already on re-frame2.** Hicasso is a re-frame2 view layer. If
   the app is still on re-frame v1, the events/subs/db migration comes first —
   route to [`re-frame-migration`](../../re-frame-migration), and note that it
   *finishes*: it leaves the views on the first-class Reagent adapter and the
   app is fully migrated at that point.
2. **The author specifically wants Hicasso, knowing they do not have to.** They
   are on a supported configuration already. Put the trade in front of them —
   [`../SKILL.md`](../SKILL.md) §Read this first carries it as a table — and
   take an explicit yes. Don't migrate views because you can.
3. **Hicasso is actually reachable from the target project's build.** It is
   **pre-publication**: `day8/re-frame2-hicasso` is not published and there is
   no date at which it will be, so there is no released Maven coordinate to
   depend on — the installation page says so itself and resolves the artefact
   by `:local/root` from a checkout.
   A project can adopt it only by consuming the in-tree / git-source artefact.
   If it has no path to that, there is nothing to migrate *onto* yet — say so
   and wait. This is migration honesty, not a reason to publish early.

## Step 0 — Run the reporter, and read both halves

```bash
cd re-frame2/migration/reagent-to-hicasso/codemod
clojure -M:run path/to/consumer/src/ --report out.edn
```

This is the inventory the whole plan is built on, and it is cheap: a bare JVM,
no re-frame2 loaded, no files touched.

- **The census (`:census`)** tells you how big the job is — every `r/atom`,
  `r/with-let`, `r/create-class`, `r/cursor`, `r/as-element`,
  `r/reactify-component`, `render-to-string` and root mount, plus re-frame2's
  own substrate adapters under `re-frame.adapter.`, classified
  `:human-decision` or `:runtime-blocker`. Two rosters, because a re-frame2
  application on the Reagent adapter calls no Reagent API of its own and a
  Reagent-only census scored it at zero. Most classes map onto a MIG rule, and
  that mapping is the D/R gating for the whole codebase before you open a file.
  Three do not: `:static-markup` lands on MIG-23's SSR leaf, and
  `:reactive-graph-control` and `:cell-disposal` have no catalogue rule at all —
  the census carries a recovery note for every class it emits, so read the note
  for the class and decide from that.
- **The fixer (`:entries`)** covers only `[:> …]`-family crossings into React.
  A codebase that crosses into React nowhere gets **zero** entries — which is
  not a clean bill of health, it is a different population. Never read one half
  as a denominator for the other.

Two report facts worth carrying: it is written even on a clean run and carries
a count of untouched sites, so *"not in the report"* is unambiguous; and the
`h/defhost` sketches list a site's callback positions, and the usual case needs
no `:callbacks` at all — the contract is inferred from the spelling. The block's
header carries the one counter-example to check against the library's
documentation: an `on*`-named render prop, which needs a `:render` override
(MIG-09/10).

Also read what it *cannot* see, and say so: a Form-2 component is a `defn`
returning a `fn` and nothing else marks it, so the census counts the `r/atom`
such a component closes over and reports nothing about the shape. A confident
wrong number would be worse.

## Step 1 — Scope a closed subtree

Pick a namespace, or a leaf-to-root view subtree, and convert **leaf views
first, shared components last**, closing the subtree bottom-up until a root or
an already-converted parent mounts it. Each pass then ends renderable and
tested, which is what lets an interrupted migration resume cleanly.

Leaf-first is the clean default, not a hard wall. If a converted view must sit
under a parent staying on Reagent, the outward bridge mounts it there —
`(def card* (h/as-component card))`, declared once at top level beside the view,
and the Reagent parent mounts `card*` as an ordinary React component. Reach for
it deliberately, not as a way to skip the bottom-up discipline: it is a foreign
boundary with its own prop crossing. Flag any inbound Reagent call site you are
not bridging — that view is a boundary, and the subtree above it waits.

## Step 2 — Gate every candidate view (whole-view law)

Before touching a view, scan its whole body for **D/R hits**, then route by
tier:

- **An R hit → hold the WHOLE view on Reagent**, honestly, and record why. The
  list is short: the prev-props update protocol (MIG-36), a frame-pinned
  reactive read (MIG-03), Reagent introspection and schedulers (MIG-35). →
  [`catalog-reject.md`](catalog-reject.md).
- **A D hit → decide it with the author, then convert the WHOLE view or hold the
  WHOLE view** — never a partial body. → [`catalog-judgment.md`](catalog-judgment.md):
  state (MIG-16), lifecycle (MIG-17), the `:on-*` handler split (MIG-18),
  derived state and the ratom-store restructure (MIG-19/20), foreign React and
  its callback contracts (MIG-09/10/22), ambient reads in plain fns (MIG-26),
  fn props on internal views (MIG-27), computed props (MIG-28), and the loop /
  render-prop shaping calls (MIG-08/13). SSR-then-hydrate (MIG-23) is decided
  there too, but its recipe is its own leaf —
  [`ssr-hydrate.md`](ssr-hydrate.md), which a client-only migration never opens.

Do not rewrite the clean parts of a held view — whole-view coherence,
[`gotchas.md`](gotchas.md).

Two things are **not** view gates here. The **mechanical** rules are applied
directly in Step 3, never held. And an **effectful sub body (MIG-25)** is a
dataflow-side finding you surface for the author — the view's own deref converts
fine once the sub is pure.

## Step 3 — Apply the M-tier rewrites to the clean views

For each fully-clean view, apply [`catalog-mechanical.md`](catalog-mechanical.md)
**atomically per view**: the `h/defview` header, the params→map change, and
**every call site** of that view change in one edit (MIG-01). Within a view the
remaining rewrites are order-free — deref-drop, dispatch lifts, the key-meta
move, the `doall` strip, keystroke handlers. Cite the `MIG-NN` id for each
change.

Two things to do *before* you call a view converted:

- **Grep the body for surviving closures.** A `#(dispatch …)` that crosses to
  React by identity fails at click time with `:rf.error/no-frame-context` and
  nothing catches it earlier ([`gotchas.md`](gotchas.md)).
- **Grep for `^{:key` in the view's lists.** Metadata is never read, so a
  survivor is an absent key, not a tidy-up.

## Step 4 — Fix requires and the root last

- **Requires (MIG-24):** add `[re-frame.hicasso :as h]`; drop `reagent.*`
  requires **only** when the namespace has zero remaining uses (a held view
  keeps them). The `h` alias is load-bearing — `::h/value`, `::h/checked` and
  `::h/prevent` auto-resolve through it, so a different alias silently writes
  different keywords. Add an optional module (`.forms`, `.motion`, `.overlay`,
  `.native`) only where one is actually used; they are absent when unused and
  that is the point of them.
- **Root (MIG-15):** once per root, and in this order — `rf/init!` (nothing
  installs an adapter for you, and the app's existing one keeps working under
  Hicasso, so it stays), then `h/mount!` carrying
  `{:frame … :initial-events …}`, which ensures and seeds the frame itself. Add
  the `defonce` root atom and the `^:dev/after-load` `h/render!` reload hook.

## Step 5 — Compile and test (the skill runs the gates); the programmer renders

Run the nearest safe noninteractive gate **yourself** — discover the project's
compile/test command (`npx shadow-cljs compile …`, `npm test`,
`clojure -M:test`) and run it (cardinal rule 7). The genuinely interactive step —
booting a dev build and eyeballing the render — stays with the programmer when
there is no connected runtime to drive.

**"Compiles" is necessary, not sufficient.** Hicasso moves most view errors to
run time by design, and the three that bite hardest all compile clean: a
surviving `#(dispatch …)`, a surviving `^{:key …}`, and a Reagent introspection
call (MIG-35). So the done-bar for a subtree is:

1. it **compiles** (the skill runs this);
2. it **renders** — the programmer boots a dev build and eyeballs the converted
   views, and reads the live frame with `re-frame2-pair` if a subtle behaviour
   changed;
3. its **tests pass** (the skill runs these).

### Structural tests are the cheap half of (2)

Handler slots hold event vectors **as data**, so "what does this button do" is
an equality check with no browser. The test kit ships in two layers, and both
are real:

- **`re-frame.hicasso.test`** (conventionally `ht`) — the value-level tiers.
- **`re-frame.hicasso.test.mounted`** (conventionally `hm`) — the mounted tier:
  `mount!`, `rerender!`, `settle!`, `dispatch-and-settle!`, `unmount!`,
  `assert-clean!` and the residue checks. Note `rerender!`, not `render!`: the
  hot-reload door on the `h` namespace is `h/render!`, but the test kit's
  re-render verb is spelled `rerender!`, and cardinal rule 6 applies to the kit
  exactly as it does to the door.

Note the packaging: the kit lives on a **separate source root** that is
deliberately not on the library's `:paths`, so a consumer who never writes a
test never carries it. Wire it in through the project's test alias / build.

**`hm/shadow!` is the migration's own instrument**, and it is the one worth
reaching for on a screen that must not change behaviour: it mounts the Reagent
original and the Hicasso candidate against isolated copies of the same seeded
frame, drives one interaction script through both, and compares canonical DOM
and the intent stream at each checkpoint.

```clojure
(hm/shadow!
 {:reference      [old/article-row {:article-id 7}]
  :candidate      [new/article-row {:article-id 7}]
  :initial-events [[:demo/install-fixture]]
  :script         [{:click "button.edit"}
                   {:type  ["input.title" "Better title"]}
                   {:click "button.save"}]})
```

Three disciplines make it worth its cost. **Add a sabotage control first** —
change a candidate prop deliberately and confirm the run turns red at the
expected checkpoint — because a comparator nobody has seen fail proves nothing.
**Script the real screen behaviour**, not a single happy click: green means the
implementations matched *for the flows in the script*. And know its reach: it
covers canonical DOM and intents, not focus, caret, IME, layout or paint. Those
need a browser test.

Omit `:script` for interactive development — both mounts stay live and each
committed render becomes a checkpoint as you use the screen by hand.

When a screen is green and its browser tests pass, **remove the Reagent
original.** Keeping both copies invites divergence.

For a small application this is over-engineering: run the reporter, port by
hand, review the handful of screens. Spend identity-proof effort on the screens
that must not change.

## Step 6 — Apply the mechanical codemod, and re-prove

```bash
clojure -M:run --rewrite src/          # dry run — what would change
clojure -M:run --rewrite --write src/  # apply
```

Last, not first. It touches only the six decidable `[:> …]` families (W1–W6),
preserves formatting, comments and line endings, and every output is outside its
own rewrite's input language so a second run is a no-op. Re-run the shadow
comparison on the screens the diff touched.

A completed run exits `0` even when the report carries human decisions — it is a
migration assistant, not a permanent build lint.

## A single converted file is PROVISIONAL

If a view's callers live in *other* files that are still Reagent, converting
just its file leaves it un-rendered unless you bridge it with `h/as-component`.
It compiles, and "compiles ≠ renders". Treat such a file as provisional until a
converted parent, a root, or a deliberate bridge mounts it. That is why the unit
of a pass is a *closed subtree*, not a lone file.

## Resuming an interrupted migration

Because each pass leaves a closed subtree compiling, rendering and tested, an
interrupted migration resumes cleanly: the converted subtrees are done, the held
views are recorded with their reasons, and the next closed subtree is the next
unit. There is no global half-state to reconcile — that is the payoff of never
big-banging.
