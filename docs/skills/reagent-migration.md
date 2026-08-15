# reagent-migration (views → Hicasso)

> Rewrites Reagent **view** code into **Hicasso**, re-frame2's re-frame-native view layer. A genuinely **optional, second** step after the v1→v2 move — it applies the mechanical `MIG` rewrites, reasons through the judgment calls, and declines what Hicasso has no equivalent for. Staying on Reagent views is a first-class, fully-supported choice, and the skill checks whether it has a job before it starts.

## Read this first — you probably do not need it

re-frame2 ships **first-class, actively-supported adapters**. `day8/re-frame2-reagent` is the default browser substrate and the adapter the reference suite runs against. An app moving from re-frame v1 to re-frame2 swaps its dependency, installs the adapter, and **keeps its view code** — that is a *finished* migration, and it is [re-frame-migration](re-frame-migration.md)'s job.

So rewriting views into Hicasso is a **separate, optional second step, and it is a rewrite** rather than a respelling: parameters become one props map, handlers become data, view-held state leaves the component. Two facts frame the choice, and the skill states both before it does anything:

- **Hicasso is PRE-PUBLICATION.** There is no released Maven coordinate; a project can adopt it only from source. If yours has no path to that, there is nothing to migrate onto yet.
- **Staying on Reagent is a complete, supported configuration** — never a half-migrated one. The skill never implies the author *should* move, because a migration guide that overstates the need costs its reader work they did not have to do.

## What it does

**One half of the migration is automated, and it runs first.** [`migration/reagent-to-hicasso/codemod`](https://github.com/day8/re-frame2/tree/main/migration/reagent-to-hicasso/codemod) is a JVM source-text reporter that loads no re-frame2. Its report has two halves: a **census** of every Reagent API call site — `r/atom`, `r/with-let`, `r/create-class`, `r/cursor`, `r/as-element`, `r/reactify-component`, root mounts — which is the inventory that sizes the job; and a **fixer** for the `[:> …]` prop dialect at React crossings, six of whose rewrite families are decidable from source text alone. The view rewrite itself is judgment, not a codemod.

The skill teaches the **view shift**: an `h/defview` mints a real React function component that is a legal hiccup head, and a plain function in head position is a loud error (`[card {…}]` is a boundary, `(helper …)` is inline); subscriptions **deref-drop** (`@(subscribe …)` → `(h/sub …)`, which returns the value and is the *ambient* collector — legal inside a `when`, a `for` or an inlined helper, recording an edge only where the read happens); handlers become **data whose shape selects the behaviour** — an intent vector, a key map, `h/event`, or a plain function — and the view **holds no state**: there is no `local`, no `use-state`, no cell of any kind.

It then applies a transformation catalog organised by what you do with each rule (the `MIG-NN` ids are the skill's own vocabulary, naming the *Reagent construct* found, cited so an author can audit any change):

- **M-tier ("do this")** — unambiguous mechanical rewrites, before→after each: `h/defview` and the one-props-map law, deref-drop, dispatch-lifting with the two markers `::h/value` / `::h/checked`, `::h/prevent`, key-meta→`:key` prop, the prop dialect (mostly: leave it alone — kebab and camel are both accepted), root mounting, ns requires, keystroke handlers → an IME-gated key map.
- **D-tier ("how to DECIDE")** — the judgment cases the skill reasons through: Form-2/`with-let` state (app-db via `h/reg-state`, `re-frame.hicasso.forms/buffered-field`, or a native component), Form-3 lifecycle (a callback ref, an ordinary event, or `h/error-boundary`), the `:on-*` handler split, foreign React and its callback contracts (`h/defhost` / `[:>]` / `h/as-element` / `h/as-component`), derived state, the ratom-as-store restructure, computed props through the reserved `:&` merge, and SSR-then-hydrate (the pipeline ships — `server/render`, `ssr/hydrate!`, `h/hydrate!` — so the decision is whether to run a Node renderer).
- **R-tier ("don't migrate — stay on Reagent")** — the honesty backbone, deliberately short: the prev-props update protocol, a frame-pinned reactive read, Reagent introspection and schedulers.

It rewrites the **view tier** only; where a view forces a dataflow change (a new `reg-sub`, a hoisted event), it *names* it for the author rather than editing the dataflow layer. And it emits only what has shipped, **read from Hicasso's own door** — the draft guide teaches several forms that do not exist, so a design page is not authority for a spelling.

## When to reach for it

Load this skill only when **both** are true:

- The app is **already on re-frame2** (the v1→v2 move is done, and it completed).
- The author **specifically wants Hicasso** for some views, knowing they do not have to.

Do **not** use it for:

- The re-frame **v1 → v2** events/subs/db migration → use [re-frame-migration](re-frame-migration.md).
- Writing new re-frame2 code → use [re-frame2](re-frame2.md).
- Greenfield setup → use [re-frame2-setup](re-frame2-setup.md).
- Live-runtime inspection → use [re-frame2-pair](re-frame2-pair.md).

## How the migration runs (incremental)

Report first, then a **closed subtree** at a time, leaf → root. That is the recommended default, not a hard wall: `h/as-component` mounts a converted view under a parent staying on Reagent, so a stranded leaf is never un-renderable. For each candidate view the skill **gates the whole view first**: a hold keeps the *entire* view on Reagent, and a judgment call is decided with the author, then the whole view converts or the whole view stays — never a half-migrated body. It applies the M-tier rewrites to the clean views, cleans up the requires and the root last, then **runs the compile + test gates itself** and hands the programmer the **render** check before moving on.

"Compiles" is emphatically not the done-bar. The three failures that cost most all compile clean, and the first is the migration's signature trap: **a leftover `#(dispatch …)` closure is passed to React by identity**, renders fine, and fails only at *click* time with `:rf.error/no-frame-context`. A surviving `^{:key …}` is the second — Hicasso reads no metadata at all, so the key is simply absent and React reconciles by position. A Reagent introspection call is the third.

The shipped test kit is the cheap half of proving a screen. `re-frame.hicasso.test.mounted/shadow!` mounts the Reagent original and the Hicasso candidate against isolated copies of the same seeded frame, drives one interaction script through both, and compares canonical DOM and the intent stream at each checkpoint — with a sabotage control first, because a comparator nobody has seen fail proves nothing.

## Where the skill lives

- Source: [`skills/reagent-migration/`](https://github.com/day8/re-frame2/tree/main/skills/reagent-migration)
- `SKILL.md`: [`skills/reagent-migration/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/SKILL.md)
- Tier catalogues: [`references/catalog-mechanical.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/references/catalog-mechanical.md) (M — do this), [`catalog-judgment.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/references/catalog-judgment.md) (D — how to decide), [`catalog-reject.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/references/catalog-reject.md) (R — stay on Reagent).
- The migration reporter: [`migration/reagent-to-hicasso/codemod/`](https://github.com/day8/re-frame2/tree/main/migration/reagent-to-hicasso/codemod).
- Hicasso reference: its public door, [`implementation/hicasso/src/re_frame/hicasso.cljc`](https://github.com/day8/re-frame2/blob/main/implementation/hicasso/src/re_frame/hicasso.cljc).
- The required first step: [re-frame-migration (v1→v2)](re-frame-migration.md).
