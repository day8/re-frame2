# reagent-migration (views → re-frame.ui)

> Migrates Reagent **view** code to re-frame2's experimental `re-frame.ui` compiled-view substrate. The **optional, second** step after the v1→v2 move — applies the mechanical `MIG` rewrites, reasons through the judgment calls, and declines what re-frame.ui doesn't yet handle. Staying on Reagent views is a first-class, fully-supported choice.

## Read this first — optional, second, experimental

This skill is not on anyone's critical path:

- **It is the OPTIONAL, SECOND step.** The migration journey is two moves, in order: **(1)** re-frame v1 → re-frame2 (the *required* foundation — [re-frame-migration](re-frame-migration.md); it leaves your views on Reagent), then **(2)** — optionally — Reagent views → re-frame.ui (*this* skill). Do (2) only after (1), and only if you want the compiled-view substrate.
- **re-frame.ui is EXPERIMENTAL.** Parts are still staged (an explicit-frame `sub` pin). The skill names those gaps and holds the affected views on Reagent. It ships in `day8/re-frame2-ui`, which is currently **in-tree / pre-publication** (not yet on Maven) — the target project must be able to consume the in-tree / git-source artifact before there is anything to migrate onto.
- **Staying on Reagent views is a first-class, fully-supported choice.** A re-frame2 app running Reagent views through the Reagent adapter is a complete, supported configuration — never a half-migrated one.

## What it does

The skill teaches the **view shift** — under re-frame.ui, views **compile** at build time (they are no longer plain functions re-run each render), subscriptions **deref-drop** (`@(subscribe …)` → `(sub …)`), the frame is **explicit** (no ambient `subscribe`/`dispatch`), and dispatch **lifts to data** (`#(dispatch [:e])` → `[:e]`).

It then applies a transformation catalog organised by what you do with each rule (the `MIG-NN` ids match re-frame2's own Reagent→re-frame.ui rule table):

- **M-tier ("do this")** — unambiguous mechanical rewrites, before→after each: `reg-view`→`ui/defview`, deref-drop, dispatch-lifting, prop respelling, key-meta→prop, plain hiccup, mount, ns requires, `:dangerouslySetInnerHTML`→`ui/html`.
- **D-tier ("how to DECIDE")** — the judgment cases the skill reasons through: Form-2/`with-let` local state (app-db vs `local`), Form-3 lifecycle (effect vs domain event), derived state (`track`/`cursor`/`reaction`), the ratom-as-store restructure, computed DOM props + the bare-symbol trap, third-party Reagent wrappers.
- **R-tier ("don't migrate — stay on Reagent, or wait")** — the honesty backbone: genuine rejects (Reagent introspection/scheduler, dynamic tag heads) and the experimental capability gaps that remain unshipped (the explicit-frame `sub` frame-pin). (An effectful sub body is a *dataflow-side* heads-up — make the sub pure — not itself a view hold.)

This is an **AI skill that applies judgment, not a codemod** — there is no rewrite-clj tool to run. For an ambiguous view it *reasons* about the right re-frame.ui shape rather than emitting a flag. It rewrites the **view tier** only; where a view forces a dataflow change (a new `reg-sub`, a hoisted event), it *names* it for the author rather than editing the dataflow layer.

## When to reach for it

Load this skill only when **both** are true:

- The app is **already on re-frame2** (the v1→v2 move is done).
- The author **specifically wants to trial the experimental `re-frame.ui` substrate** for some views.

Do **not** use it for:

- The re-frame **v1 → v2** events/subs/db migration → use [re-frame-migration](re-frame-migration.md).
- Writing new re-frame2 code → use [re-frame2](re-frame2.md).
- Greenfield setup → use [re-frame2-setup](re-frame2-setup.md).
- Live-runtime inspection → use [re-frame2-pair](re-frame2-pair.md).

## How the migration runs (incremental)

The skill migrates a **closed subtree** at a time, leaf → root — the preferred low-wrapper default now the outward `ui/->react` bridge has shipped (a converted `ui/defview` can also be consumed by an unconverted Reagent parent through that bridge, so leaf → root is a default, not a hard rule). For each candidate view it **gates the whole view first**: a **capability gap** (or a genuine reject) holds the *entire* view on Reagent, and a **judgment call** is decided with the author, then the whole view converts or the whole view stays — never a half-migrated body. It applies the M-tier rewrites to the clean views, cleans up the requires last, then **runs the compile + test gates itself** and hands the programmer the **render** check before moving to the next subtree. "Compiles" is not the done-bar — a converted view must still be rendered and eyeballed.

## Where the skill lives

- Source: [`skills/reagent-migration/`](https://github.com/day8/re-frame2/tree/main/skills/reagent-migration)
- `SKILL.md`: [`skills/reagent-migration/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/SKILL.md)
- Tier catalogues: [`references/catalog-mechanical.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/references/catalog-mechanical.md) (M — do this), [`catalog-judgment.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/references/catalog-judgment.md) (D — how to decide), [`catalog-reject.md`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/references/catalog-reject.md) (R — stay on Reagent, or wait).
- `re-frame.ui` reference: [Spec 004 — Views](https://github.com/day8/re-frame2/blob/main/spec/004-Views.md).
- The required first step: [re-frame-migration (v1→v2)](re-frame-migration.md).
