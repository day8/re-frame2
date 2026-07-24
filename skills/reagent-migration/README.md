# reagent-migration

> ↑ [`skills/`](..) — index of all re-frame2 skills.

A `Skill` that helps `Claude Code` **migrate Reagent view code to [Freehand](https://github.com/day8/re-frame2/blob/main/spec/004-Views.md)** — `re-frame.freehand`, aliased `v`, re-frame2's re-frame-native view layer. A Reagent hiccup view becomes a `v/defview` mounted in brackets and never called, `@(subscribe …)` becomes `(v/sub …)`, `#(dispatch …)` handlers lift to event vectors, and the state and lifecycle Reagent kept inside the component move to where re-frame can see them. The mechanical rewrites are applied directly; the judgment calls are **reasoned** (this is an AI skill, not a codemod); the cases Freehand has no equivalent for are **declined honestly** ("keep this on Reagent").

## Read this first — optional, second, pre-alpha

This skill is **not on anyone's critical path**, and the README says so before anything else:

- **It is the OPTIONAL, SECOND step.** The migration journey is two moves, in order: **(1)** re-frame v1 → re-frame2 (the *required* foundation — the [`re-frame-migration`](../re-frame-migration) skill; it leaves your views on Reagent), then **(2)** — optionally — Reagent views → Freehand (*this* skill).
- **Freehand is PRE-ALPHA.** A few surfaces are declared but not landed — an author-declared `:ref`, a trusted-markup verb — so a view that leans on one holds on Reagent, and the skill says why. (The React host boundary landed in both directions, so a foreign React component is a judgment call now, not a wait.)
- **Staying on Reagent is a first-class, fully-supported choice.** A re-frame2 app running Reagent, UIx or Helix views through its adapter is a complete, supported configuration. Freehand is a **peer view layer**, not a successor, and the skill never implies you *should* move.

**When to reach for it (narrow):** you are *already on re-frame2* and *specifically want to trial Freehand* for some views. That is the whole trigger.

## What it covers

- **The mental model** — the shifts to internalise: a view is a **declaration** you mount in brackets and never call, subscriptions **deref-drop** (`@(subscribe …)` → `(v/sub …)`), dispatch **lifts to data** (`#(dispatch [:e])` → `[:e]`), and the view holds **no state and no lifecycle** — there is no `local`, no `ref`, no `effect`.
- **The transformation catalog**, organised by what you do with each rule (`MIG-NN` ids the report cites so an author can audit any change):
  - **M-tier ("do this")** — unambiguous mechanical rewrites with a before→after each: `reg-view`→`v/defview` and the one-props-map law, deref-drop, dispatch-lifting with the `::v/value` projection markers, prop respelling, key-meta→prop, plain hiccup, mount and frame preflight, ns requires, the `route-link` head-rename.
  - **D-tier ("how to DECIDE")** — the judgment cases where the skill earns its keep: Form-2/`with-let` state (app-db, a semantic controller, or a behavior), Form-3 lifecycle (a registered behavior, an event, or `v/error-boundary`), the `:on-*` handler split, derived state, the ratom-as-store restructure, SSR path routing, computed props, runtime-built markup.
  - **R-tier ("don't migrate — stay on Reagent")** — the honesty backbone: trusted markup, `:ref`, Reagent introspection and schedulers, and a frame-pinned reactive read. (Foreign React heads and Reagent wrapper libraries used to sit here; the host boundary landed, so they are a judgment call now.)
- **An incremental procedure** — migrate a closed subtree at a time, leaf → root; verify it compiles, renders, and passes tests; iterate. Never big-bang. Includes the structural test surface (`re-frame.freehand.test`), which asserts a button's intent as data without a browser.
- **The gotchas** — brackets-mount-parens-inline, the exactly-one-props-map law, the bare-symbol trap (`[:li item]` is content, not a spread), whole-view coherence, render-scoped reads, and why you migrate interpreted rather than promoting mid-flight.

## What it deliberately does NOT cover

- The re-frame **v1 → v2** migration (events / subs / `app-db` / effects / boot) — that is the [`re-frame-migration`](../re-frame-migration) skill, the required first step.
- Writing new re-frame2 code — that is the [`re-frame2`](../re-frame2) skill.
- Greenfield setup — [`re-frame2-setup`](../re-frame2-setup).
- Live-runtime inspection — [`re-frame2-pair`](../re-frame2-pair).
- The **dataflow layer** — the skill rewrites the *view tier* only; where a view forces a `reg-sub`/event change, it *names* it for the author, it does not make it.
- The interactive visual confirmation — booting the app and eyeballing the render — that is the programmer's, in their own environment. (The skill *does* run the project's own noninteractive compile/test gates as it goes.)

## How the skill works

The skill is knowledge Claude reads and then applies to a consumer's Reagent code **with judgment** — there is no rewrite tool to run. For an ambiguous view it *reasons* about the right Freehand shape rather than emitting a flag. It:

- teaches the mental model (the four view shifts);
- applies the M-tier rewrites directly (citing `MIG-NN`);
- reasons through the D-tier decisions with the author;
- declines the R-tier cases honestly, holding those views on Reagent.

One standing rule governs all of it: **emit only what has shipped.** Freehand's design corpus describes forms — `local`, `effect`, `ref`, a trusted-markup verb — that are not exported (while others, like the outward bridge `v/->react`, since have been). The skill checks the API catalogue before it writes a verb, and names the gap when there isn't one.

## Status

Pre-alpha, and it migrates **to** a pre-alpha view layer. The skill is authored; it has not yet been exercised end-to-end against a real Reagent codebase. The structure mirrors the [`re-frame-migration`](../re-frame-migration) skill; the content is grounded against the shipped `re-frame.freehand` export surface and Spec 004 (Views).

## Layout

```
skills/reagent-migration/
├── SKILL.md
├── README.md
├── LICENSE
├── package.json
├── .claude-plugin/
│   └── plugin.json
├── references/
│   ├── mental-model.md        # the Reagent→Freehand view shift
│   ├── catalog-mechanical.md  # M-tier — "do this" (before→after per rule)
│   ├── catalog-judgment.md    # D-tier — "here's how to DECIDE"
│   ├── catalog-reject.md      # R-tier — "don't migrate — stay on Reagent"
│   ├── procedure.md           # incremental, closed-subtree passes
│   └── gotchas.md             # brackets-vs-parens, bare-symbol trap, whole-view coherence
├── evals/
│   └── evals.json             # trigger fixtures + behavioural fixtures across the M/D/R tiers
└── spec/
    ├── design.md              # locked design decisions
    ├── inputs.md              # canonical inputs the skill leans on
    └── authoring-prompt.md    # one-shot reauthor prompt
```

`evals/` and `spec/` are authoring-time scaffolding — the skill's own design docs and eval fixtures. They are **not part of the distributable** skill package (`package.json` `files` omits them); a packaged consumer runs the skill, they do not re-run its gates.

## Install

`reagent-migration` ships as part of the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. Link the skill from a full monorepo clone into `~/.claude/skills/` (the repo-root `scripts/install-skills.sh` / `scripts/install-skills.ps1` link every skill at once). Link, never copy — a `cp -r` snapshot drifts from the maintained source. The `package.json` + `.claude-plugin/plugin.json` packaging metadata is staged for eventual Agent-Skill / Claude-Code-Plugin distribution, not yet a published install path (`plugin.json` carries `"status": "pre-alpha"`).

## Source of truth

[Spec 004 — Views](https://github.com/day8/re-frame2/blob/main/spec/004-Views.md) is the contract, and [`spec/API.md`](https://github.com/day8/re-frame2/blob/main/spec/API.md) is the roster of what is actually exported. The `MIG-NN` ids are this skill's own vocabulary for the rewrites it applies, cited so an author can audit any change. If the skill and the spec disagree, the spec wins.

## Licence

MIT. See [`LICENSE`](LICENSE).
