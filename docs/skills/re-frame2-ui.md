# re-frame2-ui

> The skill for **`re-frame.ui`** — re-frame2's compiled-view substrate, now the **donor** being absorbed into Freehand. Reads, reviews, debugs and maintains `defview` view code in the closed template grammar, with the generated compile-rejection roster behind it as an on-demand reference. **New view work does not start here.** View layer only; the Reagent/UIx/reagent-slim adapters remain first-class.

## What it does

The `re-frame2-ui` skill teaches an agent to work on the compiled-view substrate the way its compiler expects: `defview` components as pure `(props) → template` functions, handlers as event-vector data, reactive `(sub …)` reads as plain values, view-local state and effects under the placement law, frames and roots at preflight, the timeout-bounded presence primitive, the interop boundaries (`ui/raw`, `ui/->react`, `ui/spread`, custom elements), structural tests via `re-frame.ui.test`, and the one-setting Shadow build-hook install.

Behind the teaching prose sits `references/ui-context.md` — a **generated** context sheet carrying the authoring-surface disposition (every public `ui/` var, taught or deliberately out of scope) and the full `:rf.ui.compile/*` compile-rejection roster, each entry the compiler's own didactic message. It is regenerated from the compiler itself and drift-checked in CI, so it cannot fall behind what the compiler actually enforces.

It is **authoring-only**: the skill stops at writing the code; the author runs the compiler and the tests. The closed grammar makes the compiler the first reviewer — an unprovable form is a compile error whose message names the fix.

## When to reach for it

Load this skill when reading, reviewing, debugging or maintaining **view** code in an app that **already depends on** `day8/re-frame2-ui` — anything involving `defview`, `ui/sub`, `frame-root`, `ui/mount`, compiled views / compiled hiccup, `ui.test`, or a `:rf.ui.compile/*` diagnostic id.

**Freehand** (`re-frame.freehand`, alias `v`) is re-frame2's re-frame-native view layer and it absorbs this substrate; the standalone `day8/re-frame2-ui` artefact is scheduled for deletion once absorption completes. Freehand's shapes are deliberately not `ui/` shapes — it has no `local`, no `ref`, no `effect` and no `frame-root` — so do not carry an idiom across from this skill by analogy. Its contract is [Spec 004 — Views](https://github.com/day8/re-frame2/blob/main/spec/004-Views.md) and its exported roster is [`spec/API.md`](https://github.com/day8/re-frame2/blob/main/spec/API.md) §Freehand views.

Do **not** use this skill for:

- Events, subscriptions, effects, machines — everything upstream of the view → use [re-frame2](re-frame2.md).
- View code on the Reagent/UIx/reagent-slim adapters (first-class, fully supported) → use [re-frame2](re-frame2.md).
- Migrating existing Reagent views onto Freehand → use [reagent-migration](reagent-migration.md) (optional; staying on Reagent is fine).
- Operating on a live runtime → use [re-frame2-pair](re-frame2-pair.md).

## Kickoff

The skill triggers on its surfaces automatically. To force-load:

```
/skill re-frame2-ui
```

## Where the skill lives

- Source: [`skills/re-frame2-ui/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-ui)
- `SKILL.md`: [`skills/re-frame2-ui/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-ui/SKILL.md)
- Generated reference: [`skills/re-frame2-ui/references/ui-context.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-ui/references/ui-context.md)
- The donor guide chapters and the donor install recipe have been replaced by the [Freehand guide](../core/freehand/index.md) and its [install page](../core/freehand/install.md), which is the view layer new work is written against. `day8/re-frame2-ui` has no published Maven coordinate and never will, so there is no install recipe to link here.
