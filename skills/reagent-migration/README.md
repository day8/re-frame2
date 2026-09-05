# reagent-migration

> ↑ [`skills/`](https://github.com/day8/re-frame2/tree/main/skills) — index of all re-frame2 skills.

A `Skill` that helps `Claude Code` rewrite Reagent view code into Hicasso — `re-frame.hicasso`, aliased `h`, re-frame2's re-frame-native view layer. A Reagent hiccup view becomes an `h/defview` mounted in brackets, `@(subscribe …)` becomes `(h/sub …)`, `#(dispatch …)` handlers become event vectors the tree retains as data, and the state and lifecycle Reagent kept inside the component move to where re-frame can see them. The mechanical rewrites are applied directly; the judgment calls are reasoned; the cases Hicasso has no equivalent for are declined honestly ("keep this on Reagent").

## Read this first — you probably do not need it

This skill's first job is to check whether it has a job.

re-frame2 ships first-class, actively-supported adapters. `day8/re-frame2-reagent` is the default browser substrate and the adapter the reference suite runs against. An app moving from re-frame v1 to re-frame2 swaps the dependency, installs the adapter, and keeps its view code — that is a finished migration, and it is the [`re-frame-migration`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-migration) skill's job.

Rewriting views into Hicasso is a separate, optional second step, and it is a rewrite rather than a respelling: views change shape, handlers become data, view-held state leaves the component. 2 facts frame the choice, and the skill states both before it starts:

- Hicasso is pre-publication. There is no released Maven coordinate; a project adopts it from source. If yours has no path to that, there is nothing to migrate onto yet.
- staying on Reagent is a complete, supported configuration — not a half-migrated one. The skill never implies the author should move, because a migration guide that overstates the need costs its reader work they did not have to do.

When to reach for it (narrow): you are already on re-frame2, you know you don't have to do this, and you specifically want Hicasso for some views.

## What it covers

- A real tool, run first. [`migration/reagent-to-hicasso/codemod`](https://github.com/day8/re-frame2/tree/main/migration/reagent-to-hicasso/codemod) is a JVM source-text reporter that loads no re-frame2. Its report has 2 halves: a census of every Reagent API call site (the inventory that sizes the job) and a fixer for the `[:> …]` prop dialect at React crossings, 6 of whose rewrite families are decidable from source text. The view rewrite itself is judgment, not a codemod.
- The mental model — the shifts to internalise: a view is a declared React component you mount in brackets; subscriptions deref-drop to a value read that is ambient and edge-recording; handlers become data whose shape selects the behaviour (vector, key map, `h/event`, plain fn); and the view holds no state — there is no `local`, no `use-state`, no cell.
- The transformation catalogue, organised by what you do with each rule (`MIG-NN` ids so an author can audit any change):
  - M-tier ("do this") — `h/defview` and the one-props-map law, deref-drop, dispatch-lifting with the 2 markers, `::h/prevent`, key-meta → `:key`, the prop dialect (mostly: leave it alone), root mounting, ns requires, keystroke handlers → an IME-gated key map.
  - D-tier ("how to DECIDE") — Form-2/`with-let` state (app-db via `h/reg-state`, the forms module, or a React island), Form-3 lifecycle (callback refs, events, `h/error-boundary`), the `:on-*` handler split, foreign React and its callback contracts (`h/defhost` / `[:>]` / `h/as-element` / `h/as-component`), derived state, the ratom-as-store restructure, computed props via a plain `merge` with the owned keys last, and SSR-then-hydrate (the pipeline ships; the decision is whether to run a Node renderer).
  - R-tier ("don't migrate — stay on Reagent") — the honesty backbone, and it is short: the prev-props update protocol, a frame-pinned reactive read, Reagent introspection and schedulers.
- An incremental procedure — report, then a closed subtree at a time, leaf → root; verify it compiles, renders, and passes tests. Includes the shipped test kit (`re-frame.hicasso.test*` — `.test`, `.test.mounted`, `.test.forms`, `.test.runtime`, `.test.server`) and `hm/shadow!`, which runs the Reagent original and the Hicasso candidate side by side against isolated copies of one seeded frame and compares DOM and intents.
- The gotchas — led by the one that costs most, and it is really three: a half-converted view fails at three different times under three different ids (a leftover ambient read or dispatch refuses at render, a surviving `#(dispatch …)` closure fails at click, an `h/sub` hoisted into a callback fails at fire), plus how to read the complaint that says which. Then metadata keys never being read, the string/symbol prop-key edges, markers not nesting, and the places a guide page overstates the shipped surface.

## What it deliberately does not cover

- the re-frame v1 → v2 migration (events / subs / `app-db` / effects / boot) — that is the [`re-frame-migration`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-migration) skill, which completes the move to re-frame2 on its own
- writing new re-frame2 code — that is the [`re-frame2`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2) skill
- greenfield setup — [`re-frame2-setup`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-setup)
- live-runtime inspection — [`re-frame2-pair`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair)
- the dataflow layer — the skill rewrites the view tier only; where a view forces a `reg-sub`/event change, it names it for the author, it does not make it
- the interactive visual confirmation — booting the app and eyeballing the render — that is the programmer's. (The skill does run the project's own noninteractive compile/test gates as it goes.)

## How the skill works

The skill is knowledge Claude reads and then applies to a consumer's Reagent code with judgment. It:

- runs the reporter and reads both halves before planning anything
- teaches the mental model (the 4 view shifts)
- applies the M-tier rewrites directly (citing `MIG-NN`)
- reasons through the D-tier decisions with the author
- declines the R-tier cases honestly, holding those views on Reagent

One standing rule governs all of it: emit only what has shipped, and read the door to find out. Guide pages have described forms that do not exist — the `h/fn` spelling was swept to `h/event` in August 2026, and the key-map position restriction is still overstated today — so no page is authority for a spelling. The skill checks `re-frame.hicasso` itself before it writes a verb, and names the gap when there isn't one.

## Status

Pre-alpha, and it migrates to a pre-publication view layer with no released Maven coordinate. The skill is authored; it has not been exercised end-to-end against a real Reagent codebase. Its content is grounded against Hicasso's shipped source — the public door, the codec, the intent lowering and the slot rule — rather than against the design corpus.

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
│   ├── mental-model.md        # the Reagent→Hicasso view shift
│   ├── catalog-mechanical.md  # M-tier — "do this" (before→after per rule)
│   ├── catalog-judgment.md    # D-tier — "here's how to DECIDE"
│   ├── catalog-reject.md      # R-tier — "don't migrate — stay on Reagent"
│   ├── procedure.md           # report first, then incremental closed-subtree passes
│   ├── ssr-hydrate.md         # MIG-23's SSR-then-hydrate recipe (client-only work skips it)
│   └── gotchas.md             # the three leftover ids, metadata keys, dialect edges
├── evals/
│   └── evals.json             # trigger fixtures + behavioural fixtures across the M/D/R tiers
└── spec/
    ├── design.md              # locked design decisions
    ├── inputs.md              # canonical inputs the skill leans on
    └── authoring-prompt.md    # one-shot reauthor prompt
```

`evals/` and `spec/` are authoring-time scaffolding — the skill's own design docs and eval fixtures. They are not part of the distributable skill package (`package.json` `files` omits them); a packaged consumer runs the skill, they do not re-run its gates.

## Install

`reagent-migration` ships as part of the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. Link the skill from a full monorepo clone into `~/.claude/skills/` (the repo-root `scripts/install-skills.sh` / `scripts/install-skills.ps1` link every skill at once). Link, never copy — a `cp -r` snapshot drifts from the maintained source. The `package.json` + `.claude-plugin/plugin.json` packaging metadata is staged for eventual Agent-Skill / Claude-Code-Plugin distribution, not yet a published install path (`plugin.json` carries `"status": "pre-alpha"`).

## Source of truth

[`implementation/hicasso/src/re_frame/hicasso.cljc`](https://github.com/day8/re-frame2/blob/main/implementation/hicasso/src/re_frame/hicasso.cljc) is Hicasso's public door and the roster of what is actually exported; the `impl/` namespaces beside it carry the lowering rules the catalogues cite. The `MIG-NN` ids are this skill's own vocabulary for the Reagent constructs it recognises, cited so an author can audit any change. If the skill and the shipped source disagree, the source wins.

## Licence

MIT. See [`LICENSE`](LICENSE).
