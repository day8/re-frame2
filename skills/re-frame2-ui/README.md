# re-frame2-ui

> ↑ [`skills/`](..) — index of all re-frame2 skills.

`re-frame2-ui` is the canonical Claude Code **authoring skill** for
[`re-frame.ui`](https://github.com/day8/re-frame2/tree/main/implementation/ui) —
re-frame2's **experimental** compiled-view substrate (artefact
`day8/re-frame2-ui`). It teaches an agent to write, review, and debug view
code on that substrate: `defview` components in the closed template grammar,
handlers-as-data, reactive `(sub …)` reads, view-local state and effects,
frames and roots, presence, interop boundaries, structural tests via
`re-frame.ui.test`, and the one-setting Shadow build-hook install.

The substrate is **opt-in and additional** — the Reagent, UIx, and
reagent-slim adapters remain first-class, and staying on them is a fully
supported choice. Everything upstream of the view (events, subs, effects,
machines) is ordinary re-frame2, authored with the
[`re-frame2`](../re-frame2) skill.

This skill absorbed the former `re-frame2-ui-context` sheet: the generated
API-disposition + compile-rejection reference now lives behind the teaching
prose as `references/ui-context.md`, so there is **one** trigger surface for
compiled-view work.

## Repo contents

- `SKILL.md` — the authored teaching layer: the React → re-frame.ui mental
  map, cardinal rules, install, `defview`/templates/handlers/state/frames/
  presence/interop/testing, and routing to the reference.
- `references/ui-context.md` — **GENERATED, do not hand-edit**: the compact
  context sheet — the authoring-surface disposition (every public `ui/` var,
  taught or deliberately out of scope) and the full `:rf.ui.compile/*`
  compile-rejection roster, extracted from the compiler's own didactic
  messages. Regenerate from `implementation/scripts/api-manifest/` with
  `clojure -M -m re-frame.api-manifest.ui-context`; a CI drift check
  (`ui-context --check`) reds until it is regenerated.
- `.claude-plugin/plugin.json` — Claude Code Plugin packaging metadata.
- `package.json` — npm packaging metadata (skill is also distributable as an
  Agent Skill).

## Relationship to other skills

Routing is single-sourced at
[`skills/README.md` §Skill routing](../README.md#skill-routing--single-source).
In brief:

- [`re-frame2`](../re-frame2) — authors everything upstream of the view:
  events, subscriptions, effects, frames, machines, schemas, patterns. It is
  also the home for view work on the Reagent/UIx/reagent-slim adapters.
- [`reagent-migration`](../reagent-migration) — the optional, second step
  after a v1→v2 migration: port existing Reagent **view** code onto this
  substrate. `re-frame2-ui` writes *new* compiled views; `reagent-migration`
  converts existing ones.
- [`re-frame2-pair`](../re-frame2-pair) — drives a *running* app. This skill
  is authoring-only; the author runs the compiler and the tests.

## Status

Pre-alpha, like the substrate it teaches. The generated reference is
drift-checked against the compiler and the public API manifest in CI, so the
call shapes and diagnostic roster track the implementation by construction.
