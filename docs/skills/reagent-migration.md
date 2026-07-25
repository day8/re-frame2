# reagent-migration (views → Freehand)

> Migrates Reagent **view** code to **Freehand**, re-frame2's re-frame-native view layer. The **optional, second** step after the v1→v2 move — applies the mechanical `MIG` rewrites, reasons through the judgment calls, and declines what Freehand doesn't yet handle. Staying on Reagent views is a first-class, fully-supported choice.

## Read this first — optional, second, pre-alpha

This skill is not on anyone's critical path:

- **It is the OPTIONAL, SECOND step.** The migration journey is two moves, in order: **(1)** re-frame v1 → re-frame2 (the *required* foundation — [re-frame-migration](re-frame-migration.md); it leaves your views on Reagent), then **(2)** — optionally — Reagent views → Freehand (*this* skill).
- **Freehand is PRE-ALPHA.** A few surfaces are declared but not landed — an author-declared `:ref` is the standing one — so a view that leans on one holds on Reagent, and the skill says why. (The React host boundary landed in both directions and the trusted-markup verb `v/html` has shipped, so a foreign React component is a judgment call and pre-rendered HTML is a mechanical rewrite — neither is a wait.) Freehand ships in `day8/re-frame2-freehand`, which is **pre-publication** (not yet on Maven) — the target project must be able to consume the in-tree / git-source artefact before there is anything to migrate onto.
- **Staying on Reagent views is a first-class, fully-supported choice.** A re-frame2 app running Reagent, UIx or Helix views through its adapter is a complete, supported configuration. Freehand is a **peer view layer**, not a successor.

## What it does

The skill teaches the **view shift**: a Freehand view is a **declaration** you mount in brackets and never call (`[card {…}]` is a boundary, `(helper …)` is inline); subscriptions **deref-drop** (`@(subscribe …)` → `(v/sub …)`, which returns the value); dispatch **lifts to data** (`#(dispatch [:e])` → `[:e]`, with `::v/value` / `::v/checked` / `::v/key` / `::v/scroll-top` / `::v/new-state` as the closed projection roster); and the view **holds no state and no lifecycle** — there is no `local`, no `ref`, no `effect`.

It then applies a transformation catalog organised by what you do with each rule (the `MIG-NN` ids are the skill's own vocabulary, cited so an author can audit any change):

- **M-tier ("do this")** — unambiguous mechanical rewrites, before→after each: `reg-view`→`v/defview` and the one-props-map law, deref-drop, dispatch-lifting with projection markers, prop respelling, key-meta→prop, plain hiccup, mount and frame preflight, ns requires, the `route-link` head-rename.
- **D-tier ("how to DECIDE")** — the judgment cases the skill reasons through: Form-2/`with-let` state (app-db, a semantic controller, or a registered behavior), Form-3 lifecycle (a behavior with a chosen `:timing`, an ordinary event, or `v/error-boundary`), the `:on-*` handler split, derived state, the ratom-as-store restructure, SSR path routing, computed props.
- **R-tier ("don't migrate — stay on Reagent")** — the honesty backbone: `:ref`, Reagent introspection and schedulers, and a frame-pinned reactive read. (Foreign React heads, Reagent wrapper libraries and trusted markup used to sit here; the host boundary and the trusted-markup verb landed, so they are a rewrite or a judgment call now.)

This is an **AI skill that applies judgment, not a codemod** — there is no rewrite tool to run. For an ambiguous view it *reasons* about the right Freehand shape rather than emitting a flag. It rewrites the **view tier** only; where a view forces a dataflow change (a new `reg-sub`, a hoisted event), it *names* it for the author rather than editing the dataflow layer. And it emits only verbs that appear in the shipped API catalogue — Freehand's design corpus describes several that are not exported.

## When to reach for it

Load this skill only when **both** are true:

- The app is **already on re-frame2** (the v1→v2 move is done).
- The author **specifically wants to trial Freehand** for some views.

Do **not** use it for:

- The re-frame **v1 → v2** events/subs/db migration → use [re-frame-migration](re-frame-migration.md).
- Writing new re-frame2 code → use [re-frame2](re-frame2.md).
- Greenfield setup → use [re-frame2-setup](re-frame2-setup.md).
- Live-runtime inspection → use [re-frame2-pair](re-frame2-pair.md).

## How the migration runs (incremental)

The skill migrates a **closed subtree** at a time, leaf → root. That is the recommended default, not a hard wall: the outward bridge `v/->react` can mount a converted view under a parent staying on Reagent, so a stranded leaf is never un-renderable — but leaf-first keeps each pass renderable and tested without a bridge at every seam. For each candidate view it **gates the whole view first**: a hold (a construct with no Freehand equivalent) keeps the *entire* view on Reagent, and a judgment call is decided with the author, then the whole view converts or the whole view stays — never a half-migrated body. It applies the M-tier rewrites to the clean views, cleans up the requires and the root last, then **runs the compile + test gates itself** and hands the programmer the **render** check before moving to the next subtree. "Compiles" is not the done-bar: interpreted Freehand moves most view errors to run time by design.

Everything it emits stays **interpreted**. `{:compiled true}` is a separate, later promotion on a hot leaf, taken with measurements — opting in mid-migration turns legal bodies into build failures for no benefit.

## Where the skill lives

- Source: [`skills/reagent-migration/`](https://github.com/day8/re-frame2/tree/main/skills/reagent-migration)
- `SKILL.md`: [`skills/reagent-migration/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/SKILL.md)
- Tier catalogues: [`references/catalog-mechanical.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/references/catalog-mechanical.md) (M — do this), [`catalog-judgment.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/references/catalog-judgment.md) (D — how to decide), [`catalog-reject.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/references/catalog-reject.md) (R — stay on Reagent).
- Freehand reference: [Spec 004 — Views](https://github.com/day8/re-frame2/blob/main/spec/004-Views.md), and the exported roster in [`spec/API.md`](https://github.com/day8/re-frame2/blob/main/spec/API.md).
- The required first step: [re-frame-migration (v1→v2)](re-frame-migration.md).
