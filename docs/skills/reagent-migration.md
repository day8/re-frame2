# reagent-migration (views → Fresco)

> Rewrites Reagent **view** code into **Fresco**, re-frame2's re-frame-native view layer. An optional second step after the v1→v2 move: it applies the mechanical rewrites, reasons through the judgment calls with you, and leaves on Reagent what Fresco has no equivalent for.

## You probably do not need it

An app moving from re-frame v1 to re-frame2 swaps its dependency, installs the Reagent adapter, and **keeps its view code**. That is a finished migration, and it is [re-frame-migration](re-frame-migration.md)'s job. `day8/re-frame2-reagent` is the default browser substrate and the adapter the reference suite runs against, and staying on it is a complete, supported configuration — never a half-migrated one.

Moving views to Fresco is a separate choice, and it is a rewrite rather than a respelling. Fresco ships in the same release set as the Reagent adapter, at the same version, so a project resolves it however it already resolves re-frame2 — from source until a release. The skill states both facts before it does anything, and never implies you *should* move.

## What it does

A typical rewrite, for a Reagent view mounted as `[add-button 42]`:

```clojure
;; before — Reagent
(defn add-button [id]
  [:button {:on-click #(dispatch [:cart/add id])}
   "Add (" @(subscribe [:cart/count]) ")"])

;; after — Fresco (h is re-frame.fresco), mounted as [add-button {:id 42}]
(h/defview add-button [{:keys [id]}]
  [:button {:on-click [:cart/add id]}
   "Add (" (h/sub [:cart/count]) ")"])
```

Positional parameters become one props map (and every call site changes with it), `@(subscribe …)` becomes `(h/sub …)`, which returns the value, and a dispatch-only closure becomes the event vector itself. Fresco views hold no state of their own — no `local`, no `use-state`, no cell of any kind — so view-held state moves out of the component.

The skill starts with a reporter. [`migration/reagent-to-fresco/codemod`](https://github.com/day8/re-frame2/tree/main/migration/reagent-to-fresco/codemod) is a JVM source-text tool that loads no re-frame2. Its report has two halves: a **census** of every Reagent API call site (`r/atom`, `r/with-let`, `r/create-class`, `r/cursor`, `r/as-element`, `r/reactify-component`, root mounts), which sizes the job, and a **fixer** for the `[:> …]` prop dialect at React crossings, six of whose rewrite families can be decided from source text alone. The view rewrite itself is judgment, not a codemod.

Its rules come in three tiers, each rule named by a `MIG-NN` id so you can audit any change:

- **Mechanical** — unambiguous before→after rewrites, such as the three above, key metadata, root mounting and namespace requires.
- **Judgment** — cases it reasons through with you, such as view-local and Form-2 state, Form-3 lifecycle, foreign React components and their callbacks, derived state, a ratom used as a store, SSR-then-hydrate.
- **Stay on Reagent** — a short list Fresco has no home for: the prev-props update protocol, a frame-pinned reactive read, Reagent introspection and schedulers.

It rewrites the **view tier** only. Where a view forces a dataflow change — a new `reg-sub`, a hoisted event — it names the change for you rather than editing the dataflow layer. And it writes only spellings that exist in Fresco's shipped public namespace; a guide or design page is not authority for a spelling.

## When to reach for it

Use it only when **both** are true:

- The app is **already on re-frame2** — the v1→v2 move is done.
- You **specifically want Fresco** for some views, knowing you do not have to.

Use a different skill for:

- The re-frame **v1 → v2** events/subs/db migration → [re-frame-migration](re-frame-migration.md).
- Writing new re-frame2 code → [re-frame2](re-frame2.md).
- Greenfield setup → [re-frame2-setup](re-frame2-setup.md).
- Live-runtime inspection → [re-frame2-pair](re-frame2-pair.md).

## Kickoff

Ask in your own words with the code in scope — *"migrate the views under `src/app/cart/` to Fresco"* — or type `/reagent-migration`. Its first move is to check whether it has a job at all.

Then it runs the reporter itself, from your project, as an ordinary Clojure CLI invocation that pulls the codemod as a git dependency — no re-frame2 checkout is needed and none is created. Expect one line on stderr, `Use of :paths external to the project has been deprecated`; it is expected, not a failure. The exact command is in [`SKILL.md` §Start with the reporter](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/SKILL.md#start-with-the-reporter--it-is-a-real-tool-and-it-runs-first). The reporter's `--rewrite --write` mode, which applies only the six decidable prop-dialect families, is the last step of a migration, never the first.

## How the migration runs

Report first, then one **closed subtree** at a time. Each candidate view is gated whole: a hold keeps the *entire* view on Reagent, and a judgment call is decided with you before anything converts, so there is never a half-migrated view. The skill runs the compile and test gates itself and hands you the **render** check, because "compiles" is not done: the failures that cost most all compile clean. The procedure, the traps and the shipped shadow-comparison test kit are in [`references/procedure.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/references/procedure.md).

## When it stops

- **The app is still on re-frame v1** — it sends you to [re-frame-migration](re-frame-migration.md) first.
- **A view needs something Fresco has no home for** (`component-did-update`'s prev-props protocol, a frame-pinned reactive read, Reagent introspection or schedulers) — that whole view stays on Reagent, with the reason stated.
- **A judgment call** (view-local state, lifecycle, foreign React callbacks and the rest) — it decides with you before converting, then converts or holds the whole view.
- **A Fresco function it would need is not in the shipped public namespace** — it names the gap and holds the view rather than copying a spelling from a guide page.

A half-converted view fails at a different moment depending on what was left behind: a leftover `rf/subscribe` or `rf/dispatch` in the render raises `:rf.error/ambient-frame-refused` at render; a surviving `#(dispatch …)` closure renders fine and raises `:rf.error/no-frame-context` on click; an `h/sub` moved into a callback or timer raises `:rf.error/fresco-sub-outside-render` when it fires. Causes and fixes are in [`references/gotchas.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/references/gotchas.md).

## Where the skill lives

- Source: [`skills/reagent-migration/`](https://github.com/day8/re-frame2/tree/main/skills/reagent-migration)
- `SKILL.md`: [`skills/reagent-migration/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/SKILL.md)
- Reference notes: [`skills/reagent-migration/references/`](https://github.com/day8/re-frame2/tree/main/skills/reagent-migration/references) — the skill's [`README.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/README.md) §Layout lists them, the three tier catalogues among them.
- The migration reporter: [`migration/reagent-to-fresco/codemod/`](https://github.com/day8/re-frame2/tree/main/migration/reagent-to-fresco/codemod).
- Fresco's public namespace: [`implementation/fresco/src/re_frame/fresco.cljc`](https://github.com/day8/re-frame2/blob/main/implementation/fresco/src/re_frame/fresco.cljc).
