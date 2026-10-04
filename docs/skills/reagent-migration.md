# reagent-migration (views → Fresco)

Rewrites Reagent **view** code into **Fresco**, re-frame2's re-frame-native view layer. An optional second step after the v1→v2 move: it applies the mechanical rewrites, reasons through the judgment calls with you, and leaves on Reagent what Fresco has no equivalent for.

## Kickoff

Ask in your own words with the code in scope — *"migrate the views under `src/app/cart/` to Fresco"* — or type `/reagent-migration`. Its first move is to check whether it has a job at all.

## Scope

Use this skill on an app already running re-frame2 when you want Fresco for some or all views. Keeping Reagent is fully supported. A v1 app can also migrate to re-frame2 while keeping Reagent; [re-frame-migration](re-frame-migration.md) handles the frame-dependent view calls that still need updating.

Fresco ships at core's version in the same release set. The rewrite changes view parameters, handlers and state ownership; the skill discusses those choices before converting a view.

## What it does

For a cart that already registers `:cart/add` and `:cart/count`, the view changes like this. Both forms mount inside the app's frame:

```clojure
;; Before: in the Reagent view namespace, mounted as [add-button 42].
(ns app.cart.views
  (:require [re-frame.core :as rf]))

(rf/reg-view add-button [id]
  [:button {:on-click #(dispatch [:cart/add id])}
   "Add (" @(subscribe [:cart/count]) ")"])
```

```clojure
;; After: replace the namespace require and view,
;; then mount as [add-button {:id 42}].
(ns app.cart.views
  (:require [re-frame.fresco :as h]))

(h/defview add-button [{:keys [id]}]
  [:button {:on-click [:cart/add id]}
   "Add (" (h/sub [:cart/count]) ")"])
```

Positional parameters become one props map (and every call site changes with it), `@(subscribe …)` becomes `(h/sub …)`, which returns the value, and a dispatch-only closure becomes the event vector itself. Fresco views hold no local state. Product state moves to `app-db`, buffered field edits can use the forms module, and widget mechanics can stay in a foreign React component. The skill discusses that ownership before rewriting a stateful view.

There is also a timing change: the event vector drains synchronously, while the old injected `dispatch` queued the event. Most callbacks want the vector form. If code after the dispatch relies on the old state, the skill checks the ordering and can preserve queued dispatch using an explicitly captured frame; see [MIG-04/05](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/references/catalog-mechanical.md#mig-04--05--dispatch-lifting).

The skill's rules come in three tiers, each rule named by a `MIG-NN` id so you can audit any change:

- **Mechanical** — unambiguous before→after rewrites, such as the three above, key metadata, root mounting and namespace requires.
- **Judgment** — cases it reasons through with you, such as view-local and Form-2 state, Form-3 lifecycle, foreign React components and their callbacks, derived state, a ratom used as a store, SSR-then-hydrate.
- **Stay on Reagent** — a short list Fresco has no home for: the prev-props update protocol, a frame-pinned reactive read, Reagent introspection and schedulers.

It rewrites the **view tier** only. Where a view forces a dataflow change — a new `reg-sub`, a hoisted event — it names the change for you rather than editing events or subscriptions. And it writes only spellings that exist in Fresco's shipped public namespace; a guide or design page is not authority for a spelling.

## Related work

- The re-frame **v1 → v2** events/subs/db migration → [re-frame-migration](re-frame-migration.md).
- Writing new re-frame2 code → [re-frame2](re-frame2.md).
- A review of view code against re-frame2's anti-patterns, without porting it → [re-frame2-improver](re-frame2-improver.md).

## How the migration runs

The skill first runs a reporter, [`migration/reagent-to-fresco/codemod`](https://github.com/day8/re-frame2/tree/main/migration/reagent-to-fresco/codemod): a JVM tool that reads your source text, loads no re-frame2 and changes no source file. It writes an EDN report with two halves:

- a **census** of the view-layer API call sites it recognises — Reagent's (`r/atom`, `r/with-let`, `r/create-class`, `r/cursor`, `r/as-element`, `r/reactify-component`, root mounts) and those into re-frame2's own adapter namespaces — which sizes the job;
- a **fixer** for the `[:> …]` prop dialect at React crossings, six of whose rewrite families can be decided from source text alone.

No tool converts the views themselves; that is judgment. The skill runs the reporter from your project as an ordinary Clojure CLI invocation that pulls the codemod as a git dependency — no re-frame2 checkout is needed and none is created, though a project that already works from one can point the same command at it through `:local/root`. Expect one line on stderr, `Use of :paths external to the project has been deprecated`; it is not a failure. The exact command is in [`SKILL.md` §Start with the reporter](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/SKILL.md#start-with-the-reporter--it-is-a-real-tool-and-it-runs-first). `--rewrite` previews the six prop-dialect rewrites without changing source. The reporter's `--rewrite --write` mode, which applies only the six decidable prop-dialect families, is the last step of a migration, never the first.

After the report, it converts one **closed subtree** at a time — a namespace, or a view and the views beneath it, leaf views first — so each pass ends compiling, rendering and tested. It converts or holds each view whole, so there is never a half-migrated view. The skill runs the compile and test gates itself and hands you the **render** check, because "compiles" is not done: the failures that cost most all compile clean. The procedure, the traps and the shipped shadow-comparison test kit are in [`references/procedure.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/references/procedure.md).

A converted view can also sit beneath a parent that remains on Reagent via `h/as-component`; the skill does not require a whole-app rewrite.

When no Reagent view remains, it tells you the adapter choice is now open: Fresco's own adapter, `re-frame.fresco.substrate/adapter`, can replace `day8/re-frame2-reagent`. That choice stays yours. Dropping `reagent/reagent` also requires checking the rest of the repository for remaining uses, not just counting converted views.

## When it stops

- **The app is still on re-frame v1** — it sends you to [re-frame-migration](re-frame-migration.md) first.
- **A view needs something from the stay-on-Reagent list** — that whole view stays on Reagent, with the reason stated.
- **A judgment call** — it decides with you before converting, then converts or holds the whole view.
- **A Fresco function it would need is not in the shipped public namespace** — it names the gap and holds the view rather than copying a spelling from a guide page.

A half-converted view fails at a different moment depending on what was left behind: a leftover `rf/subscribe` or `rf/dispatch` in the render raises `:rf.error/ambient-frame-refused` at render; a surviving `#(dispatch …)` closure renders fine and raises `:rf.error/no-frame-context` on click; an `h/sub` moved into a callback or timer raises `:rf.error/fresco-sub-outside-render` when it fires. Causes and fixes are in [`references/gotchas.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/references/gotchas.md).

The [skill contract](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/SKILL.md) contains the full workflow and links to its reference notes.
