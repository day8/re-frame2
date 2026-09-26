# reagent-migration (views → Fresco)

> Rewrites Reagent **view** code into **Fresco**, re-frame2's re-frame-native view layer. A genuinely **optional, second** step after the v1→v2 move — it applies the mechanical `MIG` rewrites, reasons through the judgment calls, and declines what Fresco has no equivalent for. Staying on Reagent views is a first-class, fully-supported choice, and the skill checks whether it has a job before it starts.

## Read this first — you probably do not need it

re-frame2 ships **first-class, actively-supported adapters**. `day8/re-frame2-reagent` is the default browser substrate and the adapter the reference suite runs against. An app moving from re-frame v1 to re-frame2 swaps its dependency, installs the adapter, and **keeps its view code** — that is a *finished* migration, and it is [re-frame-migration](re-frame-migration.md)'s job.

So rewriting views into Fresco is a **separate, optional second step, and it is a rewrite** rather than a respelling: parameters become one props map, handlers become data, view-held state leaves the component. Two facts frame the choice, and the skill states both before it does anything:

- **Fresco ships in the same release set as the Reagent adapter**, at the same version, so a project resolves it however it already resolves re-frame2 — from source until a release. There is no separate publication gap to wait on.
- **Staying on Reagent is a complete, supported configuration** — never a half-migrated one. The skill never implies the author *should* move, because a migration guide that overstates the need costs its reader work they did not have to do.

## What it does

**One half of the migration is automated, and it runs first.** [`migration/reagent-to-fresco/codemod`](https://github.com/day8/re-frame2/tree/main/migration/reagent-to-fresco/codemod) is a JVM source-text reporter that loads no re-frame2. Its report has two halves: a **census** of every Reagent API call site — `r/atom`, `r/with-let`, `r/create-class`, `r/cursor`, `r/as-element`, `r/reactify-component`, root mounts — which is the inventory that sizes the job; and a **fixer** for the `[:> …]` prop dialect at React crossings, six of whose rewrite families are decidable from source text alone. The view rewrite itself is judgment, not a codemod.

The skill runs the reporter itself, from your project, as an ordinary Clojure CLI invocation that pulls the codemod as a git dependency — no re-frame2 checkout is needed and none is created. Expect one line on stderr, `Use of :paths external to the project has been deprecated`: it is deliberate and not a failure. The exact command is in [`SKILL.md` §Start with the reporter](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/SKILL.md#start-with-the-reporter--it-is-a-real-tool-and-it-runs-first); the reporter's `--rewrite --write` mode, which applies only the six decidable prop-dialect families, is the last step of a migration, never the first.

The skill teaches the **view shift**: an `h/defview` mints a real React function component that is a legal hiccup head, and a plain function in head position is a loud error (`[card {…}]` is a boundary, `(helper …)` is inline); subscriptions **deref-drop** (`@(subscribe …)` → `(h/sub …)`, which returns the value and is the *ambient* collector — legal inside a `when`, a `for` or an inlined helper, recording an edge only where the read happens); handlers become **data whose shape selects the behaviour** — an intent vector, a key map, `h/event`, or a plain function — and the view **holds no state**: there is no `local`, no `use-state`, no cell of any kind.

It then applies a transformation catalog organised by what you do with each rule (the `MIG-NN` ids are the skill's own vocabulary, naming the *Reagent construct* found, cited so an author can audit any change):

- **M-tier ("do this")** — unambiguous mechanical rewrites, before→after each: `h/defview` and the one-props-map law, deref-drop, dispatch-lifting with the two markers `::h/value` / `::h/checked`, `::h/prevent`, key-meta→`:key` prop, the prop dialect (mostly: leave it alone — kebab and camel are both accepted), root mounting, ns requires, keystroke handlers → an IME-gated key map.
- **D-tier ("how to DECIDE")** — the judgment cases the skill reasons through: Form-2/`with-let` state (app-db via `h/reg-state`, `re-frame.fresco.forms/buffered-field`, or a native component), Form-3 lifecycle (a callback ref, an ordinary event, or `h/error-boundary`), the `:on-*` handler split, foreign React and its callback contracts (`h/defhost` / `[:>]` / `h/as-element` / `h/as-component`), derived state, the ratom-as-store restructure, computed props via a plain `merge` with the owned keys last, and SSR-then-hydrate (the pipeline ships — `server/render`, `ssr/hydrate!`, `h/render!` with `{:hydrate? true}` — so the decision is whether to run a Node renderer).
- **R-tier ("don't migrate — stay on Reagent")** — the honesty backbone, deliberately short: the prev-props update protocol, a frame-pinned reactive read, Reagent introspection and schedulers.

It rewrites the **view tier** only; where a view forces a dataflow change (a new `reg-sub`, a hoisted event), it *names* it for the author rather than editing the dataflow layer. And it emits only what has shipped, **read from Fresco's own door** — guide pages teach several forms that do not exist, so a design page is not authority for a spelling.

## When to reach for it

Load this skill only when **both** are true:

- The app is **already on re-frame2** (the v1→v2 move is done, and it completed).
- The author **specifically wants Fresco** for some views, knowing they do not have to.

Do **not** use it for:

- The re-frame **v1 → v2** events/subs/db migration → use [re-frame-migration](re-frame-migration.md).
- Writing new re-frame2 code → use [re-frame2](re-frame2.md).
- Greenfield setup → use [re-frame2-setup](re-frame2-setup.md).
- Live-runtime inspection → use [re-frame2-pair](re-frame2-pair.md).

## How the migration runs (incremental)

Report first, then a **closed subtree** at a time, gating each candidate view whole — a hold keeps the *entire* view on Reagent, and a judgment call is decided with the author before anything converts, so there is never a half-migrated body. The skill runs the compile and test gates itself and hands the programmer the **render** check, because "compiles" is emphatically not the done-bar: the failures that cost most all compile clean. The procedure, the traps and the shipped shadow-comparison test kit are in [`skills/reagent-migration/references/procedure.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/references/procedure.md).

## Kickoff

Ask in your own words with the code in scope — *"migrate the views under `src/app/cart/` to Fresco"* — or type `/reagent-migration`. Its first move is the check at the top of this page: whether it has a job at all.

## When it stops

- **The app is still on re-frame v1** — it sends you to [re-frame-migration](re-frame-migration.md) first.
- **A view needs something Fresco has no home for** (the R-tier: `component-did-update`'s prev-props protocol, a frame-pinned reactive read, Reagent introspection or schedulers) — that whole view stays on Reagent, with the reason stated.
- **A judgment call** (the D-tier: view-local state, lifecycle, foreign React callbacks and the rest) — it decides with you before converting, then converts or holds the whole view.
- **A Fresco verb it would need is not in the shipped public namespace** — it names the gap and holds the view rather than writing a spelling from a guide page.

A half-converted view fails at a different moment depending on what was left behind, and each moment has its own error id: a leftover `rf/subscribe` or `rf/dispatch` in the render refuses at render with `:rf.error/ambient-frame-refused`; a surviving `#(dispatch …)` closure renders fine and fails at click with `:rf.error/no-frame-context`; an `h/sub` moved into a callback or timer fails when it fires with `:rf.error/fresco-sub-outside-render`. The table and fixes are in [`references/gotchas.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/references/gotchas.md).

## Where the skill lives

- Source: [`skills/reagent-migration/`](https://github.com/day8/re-frame2/tree/main/skills/reagent-migration)
- `SKILL.md`: [`skills/reagent-migration/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/SKILL.md)
- Reference leaves: [`skills/reagent-migration/references/`](https://github.com/day8/re-frame2/tree/main/skills/reagent-migration/references) — the skill [`README.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/README.md) §Layout lists every leaf, the three tier catalogues among them.
- The migration reporter: [`migration/reagent-to-fresco/codemod/`](https://github.com/day8/re-frame2/tree/main/migration/reagent-to-fresco/codemod).
- Fresco reference: its public door, [`implementation/fresco/src/re_frame/fresco.cljc`](https://github.com/day8/re-frame2/blob/main/implementation/fresco/src/re_frame/fresco.cljc).
- The required first step: [re-frame-migration (v1→v2)](re-frame-migration.md).
