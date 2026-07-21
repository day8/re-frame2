# reagent-migration

> ↑ [`skills/`](..) — index of all re-frame2 skills.

A `Skill` that helps `Claude Code` **migrate Reagent view code to re-frame2's experimental [`re-frame.ui`](https://github.com/day8/re-frame2/blob/main/spec/004-Views.md) compiled-view substrate** — a Reagent hiccup view becomes a compiled `ui/defview`, `@(subscribe …)` becomes `(sub …)`, `#(dispatch …)` handlers lift to data, and the frame becomes explicit. The mechanical rewrites are applied directly; the judgment calls are **reasoned** (this is an AI skill, not a codemod); the cases re-frame.ui doesn't yet handle are **declined honestly** ("keep this on Reagent, or wait").

## Read this first — optional, second, experimental

This skill is **not on anyone's critical path**, and the README says so before anything else:

- **It is the OPTIONAL, SECOND step.** The migration journey is two moves, in order: **(1)** re-frame v1 → re-frame2 (the *required* foundation — the [`re-frame-migration`](../re-frame-migration) skill; it leaves your views on Reagent), then **(2)** — optionally — Reagent views → re-frame.ui (*this* skill). Do (2) only after (1), and only if you want the compiled-view substrate.
- **re-frame.ui is EXPERIMENTAL.** Parts are still staged (an explicit-frame `sub` pin). The skill names those gaps and holds the affected views on Reagent.
- **Staying on Reagent views is a first-class, fully-supported choice.** A re-frame2 app running Reagent views through the Reagent adapter is a complete, supported configuration. The skill never implies you *should* move to re-frame.ui.

**When to reach for it (narrow):** you are *already on re-frame2* and *specifically want to trial the experimental `re-frame.ui` substrate* for some views. That is the whole trigger.

## What it covers

- **The mental model** — the one shift to internalise: views **compile** now (build-time analysis, not a fn re-run per render), subscriptions **deref-drop** (`@(subscribe …)` → `(sub …)`), the frame is **explicit** (no ambient `subscribe`/`dispatch`), and dispatch **lifts to data** (`#(dispatch [:e])` → `[:e]`).
- **The transformation catalog**, organised by what you do with each rule (`MIG-NN` ids matching the framework's own rule table):
  - **M-tier ("do this")** — unambiguous mechanical rewrites with a before→after each: `reg-view`→`defview`, deref-drop, dispatch-lifting, prop respelling, key-meta→prop, plain hiccup, mount, ns requires, `dangerouslySetInnerHTML`→`ui/html`, and more.
  - **D-tier ("how to DECIDE")** — the judgment cases where the skill earns its keep: Form-2/`with-let` local state (app-db vs `local`), Form-3 lifecycle (effect vs domain event), derived state (`track`/`cursor`/`reaction`), the ratom-as-store restructure, computed DOM props + the bare-symbol trap, third-party Reagent wrappers.
  - **R-tier ("don't migrate — stay on Reagent, or wait")** — the honesty backbone: genuine rejects (Reagent introspection/scheduler, dynamic tag heads) and the experimental capability gaps that remain unshipped (the explicit-frame `sub` frame-pin). (An effectful sub body is a *dataflow-side* heads-up — make the sub pure — not itself a view hold.)
- **An incremental procedure** — migrate a closed subtree at a time, leaf → root; verify it compiles, renders, and passes tests; iterate. Never big-bang.
- **The gotchas** — the bare-symbol trap (`[:li item]` is content, not a spread), whole-view coherence, keyed-child extraction, dynamic tag heads, and the staged-gap trap.

## What it deliberately does NOT cover

- The re-frame **v1 → v2** migration (events / subs / `app-db` / effects / boot) — that is the [`re-frame-migration`](../re-frame-migration) skill, the required first step.
- Writing new re-frame2 code — that is the [`re-frame2`](../re-frame2) skill.
- Greenfield setup — [`re-frame2-setup`](../re-frame2-setup).
- Live-runtime inspection — [`re-frame2-pair`](../re-frame2-pair).
- The **dataflow layer** — the skill rewrites the *view tier* only; where a view forces a `reg-sub`/event change, it *names* it for the author, it does not make it.
- The interactive visual confirmation — booting the app and eyeballing the render — that is the programmer's, in their own environment. (The skill *does* run the project's own noninteractive compile/test gates as it goes.)

## How the skill works

The skill is knowledge Claude reads and then applies to a consumer's Reagent code **with judgment** — there is no rewrite-clj tool to run (that codemod was shelved). For an ambiguous view it *reasons* about the right re-frame.ui shape rather than emitting a flag. It:

- teaches the mental model (the four view shifts);
- applies the M-tier rewrites directly (citing `MIG-NN`);
- reasons through the D-tier decisions with the author;
- declines the R-tier / capability-gap cases honestly, holding those views on Reagent.

## Status

Pre-alpha, and it migrates **to** an experimental substrate. The skill is authored; it has not yet been exercised end-to-end against a real Reagent codebase. The structure mirrors the [`re-frame-migration`](../re-frame-migration) skill; the content is grounded against the framework's `MIG-01…35` rule table, the shelved migrator's golden fixtures (read as worked examples, not revived), and Spec 004 (Views).

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
│   ├── mental-model.md        # the re-frame v1→re-frame.ui view shift
│   ├── catalog-mechanical.md  # M-tier — "do this" (before→after per rule)
│   ├── catalog-judgment.md    # D-tier — "here's how to DECIDE"
│   ├── catalog-reject.md      # R-tier — "don't migrate — stay on Reagent, or wait"
│   ├── procedure.md           # incremental, closed-subtree passes
│   └── gotchas.md             # bare-symbol trap, whole-view coherence, keyed-child, staged-gap
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

The framework's `MIG-01…35` Reagent→re-frame.ui rule table, itself grounded in [Spec 004 — Views](https://github.com/day8/re-frame2/blob/main/spec/004-Views.md) and the shipped `re-frame.ui` export surface. Every rewrite the skill applies cites a `MIG-NN` id. If the skill and the framework's rule table disagree, the framework wins.

## Licence

MIT. See [`LICENSE`](LICENSE).
