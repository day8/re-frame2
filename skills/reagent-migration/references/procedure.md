# The procedure — incremental, never big-bang

> A bad bulk migration is worse than none. Migrate a namespace / a closed
> subtree at a time; verify it renders and its tests pass; iterate. The unit
> of migration is the **whole view** (cardinal rule 2), and the unit of a
> *pass* is a **closed subtree**.

## Pre-flight — is this migration even in scope?

Confirm all three, or stop:

1. **The app is already on re-frame2.** re-frame.ui is a re-frame2 substrate. If the app is still on re-frame v1, the events/subs/db migration comes first — route to [`re-frame-migration`](../re-frame-migration) and come back only when that is done.
2. **The author specifically wants the experimental substrate.** If they are content on Reagent views (the supported default), there is nothing to do — say so. Don't migrate views because you can.
3. **The `re-frame.ui` artifact is actually available to the target project.** `re-frame.ui` ships in `day8/re-frame2-ui`, which is **in-tree / pre-publication** — the Maven coordinate is **not yet published** (publication is separately owned and must not be accelerated). So a project can only adopt it by consuming the **in-tree / git-source** artifact today, not as a released dependency. If the project has no path to that source, there is nothing to migrate *onto* yet — wait for publication. (This is migration honesty, not a reason to publish early.)

## Step 1 — Scope a closed subtree

Pick a namespace, or a leaf-to-root view subtree, that does **not** call *into* views staying on Reagent where you can avoid it. Leaf-to-root is the **recommended default**, not a hard constraint: the outward `ui/->react` bridge has shipped (MIG-22), so a converted `ui/defview` *can* be consumed by an unconverted Reagent parent — but every boundary crossing is a `ui/->react` wrapper, so converting **leaf views first, shared components last** (closing the subtree bottom-up) keeps subtrees pure `ui` and minimises boundary wrappers.

Flag any *inbound* Reagent call site you can't include in the subtree — that view is a boundary; decide with the author whether the caller converts too, the boundary is embedded inward (`ui/raw` + `r/as-element`, MIG-22), or the converted child is exported outward (`ui/->react`, MIG-22).

## Step 2 — Gate every candidate view (whole-view law)

Before touching a view, scan its whole body for **D/R hits** — then route by tier, not by a blanket hold:

- **An R hit → hold the WHOLE view on Reagent**, honestly, and record why. That is a genuine reject with no compiled equivalent (Reagent introspection/scheduler MIG-35, dynamic tag heads MIG-21) or an unshipped capability gap (the explicit-frame `sub` pin MIG-03). → [`catalog-reject.md`](catalog-reject.md).
- **A D hit → decide it with the author, then convert the WHOLE view or hold the WHOLE view** — never a partial body. The judgment calls are catalogued in [`catalog-judgment.md`](catalog-judgment.md): state/lifecycle (MIG-16/17), derived state or the ratom-store restructure (MIG-19/20), the `:on-*` handler split (MIG-18), SSR path routing (MIG-23 — *shipped*, route between the static-page and hydrate paths, not a hold), computed DOM props (MIG-28), third-party wrappers (MIG-22), and the loop-key / foreign-boundary / plain-fn-ambient calls (MIG-08/10/13/26/27/30). A couple are non-gating (MIG-27/28 convert with a named check) — read the row.

Do not rewrite the clean parts of a held view — a half-migrated body neither compiles nor runs (whole-view coherence, [`gotchas.md`](gotchas.md)).

Two things are **not** view gates here. The **mechanical** rules — including the `route-link` head-rename (MIG-32) — are applied directly in Step 3, never held. And an **effectful sub body (MIG-25)** is a *dataflow-side* finding you surface for the author (the view's own deref converts fine, MIG-02, once the sub is made pure) — it is **not** a reason to hold the view.

## Step 3 — Apply the M-tier rewrites to the clean views

For each fully-clean view, apply [`catalog-mechanical.md`](catalog-mechanical.md) **atomically per view**: the `ui/defview` header, the params→map change, and **every call site** of that view change in one edit (MIG-01). Within a view, do loop-context analysis before the handler lifts (the capture check), then the order-free rewrites (deref-drop, prop respelling, key-meta, `doall` strip, foreign heads, `ui/html`, `ui/raw-fn`) in any order. Cite the `MIG-NN` id for each change.

## Step 4 — Fix requires and mount/boot last

- **Requires (MIG-24):** add `[re-frame.ui :as ui :refer [defview sub]]`; drop `reagent.*` requires **only** when the namespace has zero remaining uses (a held view keeps them).
- **Mount (MIG-15) / adapter (MIG-33):** once per root / once per app. On a mixed page (some roots still Reagent), keep the Reagent adapter installed and swap only when every root on the page is converted — confirm the root inventory with the author.

## Step 5 — Compile and test (the skill runs the gates); the programmer renders

Run the nearest safe noninteractive gate **yourself** — discover the project's compile/test command (`npx shadow-cljs compile …`, `npm test`, `clojure -M:test`) and run it (cardinal rule 6, under the trust-the-explicit-invoker `allowed-tools` baseline). The genuinely-interactive step — booting a dev build and eyeballing the render — stays with the programmer when there is no connected browser/runtime to drive. **"Compiles" is necessary, not sufficient** — a converted subtree referenced from unconverted Reagent, or a `MIG-35` introspection call, can compile clean and fail only at render. So the done-bar for a subtree is:

1. it **compiles** (the skill runs this — the compiler catches most re-frame.ui grammar errors here, the point of a build-time substrate);
2. it **renders** — the programmer boots a dev build and eyeballs the converted views (and, cheaply, reads the live frame with `re-frame2-pair` if a subtle behaviour changed); the skill runs this itself only where a connected browser/runtime is available;
3. its **tests pass** (the skill runs these — re-baseline render counts if the substrate changed them).

Only when a subtree is green do you scope the next one. If a view surfaces a new gap mid-pass, hold it (rule 2) and keep going.

**A single file converted in isolation is PROVISIONAL.** If a view's callers live in *other* files that are still Reagent, converting just its file leaves it un-rendered until something mounts it through the compiled path. Two things can: a **compiled** caller — a converted route component or parent view — or an explicit `ui/->react` wrapper at the Reagent parent (the outward bridge has shipped, MIG-22). Absent either, the file compiles but never renders. Treat it as provisional (it compiles, but "compiles ≠ renders") until a real caller mounts it. That is exactly why the unit of a pass is a *closed subtree*, not a lone file — a closed subtree needs neither the wrapper nor a still-Reagent caller.

## Resuming an interrupted migration

Because each pass is a closed subtree left in a compiling, rendering, tested state, an interrupted migration resumes cleanly: the converted subtrees are done, the held views are recorded with their reasons, and the next closed subtree is the next unit. There is no global half-state to reconcile — that is the payoff of never big-banging.
