# The procedure — incremental, never big-bang

> A bad bulk migration is worse than none. Migrate a namespace / a closed
> subtree at a time; verify it renders and its tests pass; iterate. The unit
> of migration is the **whole view** (cardinal rule 2), and the unit of a
> *pass* is a **closed subtree**.

## Pre-flight — is this migration even in scope?

Confirm both, or stop:

1. **The app is already on re-frame2.** re-frame.ui is a re-frame2 substrate. If the app is still on re-frame v1, the events/subs/db migration comes first — route to [`re-frame-migration`](../re-frame-migration) and come back only when that is done.
2. **The author specifically wants the experimental substrate.** If they are content on Reagent views (the supported default), there is nothing to do — say so. Don't migrate views because you can.

## Step 1 — Scope a closed subtree

Pick a namespace, or a leaf-to-root view subtree, that does **not** call *into* views staying on Reagent. This matters because of the unshipped outward bridge ([`catalog-reject.md`](catalog-reject.md), `ui/->react`): a converted `ui/defview` can only be consumed by other converted views. So convert **leaf views first, shared components last**, closing the subtree from the bottom up.

Flag any *inbound* Reagent call site you can't include in the subtree — that view is a boundary; decide with the author whether the caller converts too or the boundary is embedded (`ui/raw` + `r/as-element`, MIG-22).

## Step 2 — Gate every candidate view (whole-view law)

For each view in the subtree, scan for **any** D/R hit before touching it:

- a state/lifecycle decision (MIG-16/17), a derived-state or ratom-store restructure (MIG-19/20);
- a reject or capability gap (MIG-21/23/25/30/32/35, the `sub` pin, the outward bridge);
- a loop-legality issue (MIG-08), a foreign-boundary fn prop (MIG-10), an explicit-frame op (MIG-03), ambient ops in a plain fn (MIG-26).

**If a view has any of these, leave the WHOLE view on Reagent** and record why. Do not rewrite the clean parts — a half-migrated body neither compiles nor runs. Decide the held views with the author separately (D-tier) or hold them honestly (R-tier).

## Step 3 — Apply the M-tier rewrites to the clean views

For each fully-clean view, apply [`catalog-mechanical.md`](catalog-mechanical.md) **atomically per view**: the `ui/defview` header, the params→map change, and **every call site** of that view change in one edit (MIG-01). Within a view, do loop-context analysis before the handler lifts (the capture check), then the order-free rewrites (deref-drop, prop respelling, key-meta, `doall` strip, foreign heads, `ui/html`, `ui/raw-fn`) in any order. Cite the `MIG-NN` id for each change.

## Step 4 — Fix requires and mount/boot last

- **Requires (MIG-24):** add `[re-frame.ui :as ui :refer [defview sub]]`; drop `reagent.*` requires **only** when the namespace has zero remaining uses (a held view keeps them).
- **Mount (MIG-15) / adapter (MIG-33):** once per root / once per app. On a mixed page (some roots still Reagent), keep the Reagent adapter installed and swap only when every root on the page is converted — confirm the root inventory with the author.

## Step 5 — The author compiles, renders, and tests

Print the commands; the author runs them (cardinal rule 6). **"Compiles" is necessary, not sufficient** — a converted subtree referenced from unconverted Reagent, or a `MIG-35` introspection call, can compile clean and fail only at render. So the done-bar for a subtree is:

1. it **compiles** (the compiler catches most re-frame.ui grammar errors here — that is the point of a build-time substrate);
2. it **renders** — the author boots a dev build and eyeballs the converted views (and, cheaply, reads the live frame with `re-frame2-pair` if a subtle behaviour changed);
3. its **tests pass** (re-baseline render counts if the substrate changed them).

Only when a subtree is green do you scope the next one. If a view surfaces a new gap mid-pass, hold it (rule 2) and keep going.

**A single file converted in isolation is PROVISIONAL.** If a view's callers live in *other* files that are still Reagent, converting just its file leaves it un-rendered — a compiled `defview` cannot be consumed by an unconverted Reagent parent (the outward bridge is unshipped, [`catalog-reject.md`](catalog-reject.md)). It is not *proven* until a **compiled** caller — a converted route component or parent view — mounts it through the compiled path and you render it. Treat such a file as provisional (it compiles, but "compiles ≠ renders") until its mounting caller is also compiled. That is exactly why the unit of a pass is a *closed subtree*, not a lone file.

## Resuming an interrupted migration

Because each pass is a closed subtree left in a compiling, rendering, tested state, an interrupted migration resumes cleanly: the converted subtrees are done, the held views are recorded with their reasons, and the next closed subtree is the next unit. There is no global half-state to reconcile — that is the payoff of never big-banging.
