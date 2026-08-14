# re-frame2-ui

> The skill for **`re-frame.ui`** — re-frame2's compiled-view substrate, now **retired and awaiting removal**. Reads, reviews, debugs and maintains `defview` view code an app already has, with the generated compile-rejection roster behind it as an on-demand reference. **New view work does not start here**, and this skill is removed when the substrate is. View layer only; the Reagent and UIx adapters remain first-class.

## What it does

The `re-frame2-ui` skill teaches an agent to work on the compiled-view substrate the way its compiler expects: `defview` components as pure `(props) → template` functions, handlers as event-vector data, reactive `(sub …)` reads as plain values, view-local state and effects under the placement law, frames and roots at preflight, the timeout-bounded presence primitive, the interop boundaries (`ui/raw`, `ui/->react`, `ui/spread`, custom elements), structural tests via `re-frame.ui.test`, and the one-setting Shadow build-hook install.

Behind the teaching prose sits `references/ui-context.md` — a **generated** context sheet carrying the authoring-surface disposition (every public `ui/` var, taught or deliberately out of scope) and the full `:rf.ui.compile/*` compile-rejection roster, each entry the compiler's own didactic message. It is regenerated from the compiler itself and drift-checked in CI, so it cannot fall behind what the compiler actually enforces.

It is **authoring-only**: the skill stops at writing the code; the author runs the compiler and the tests. The closed grammar makes the compiler the first reviewer — an unprovable form is a compile error whose message names the fix.

## When to reach for it

Load this skill when reading, reviewing, debugging or maintaining **view** code in an app that **already depends on** `day8/re-frame2-ui` — anything involving `defview`, `ui/sub`, `frame-root`, `ui/mount`, compiled views / compiled hiccup, `ui.test`, or a `:rf.ui.compile/*` diagnostic id.

`re-frame.ui` is **retired**, and so is Freehand (`re-frame.freehand`), the view layer this substrate was previously described as being absorbed into. re-frame2's architecture is two first-class adapters — Reagent and UIx — plus **Hicasso** (`re-frame.hicasso`, alias `h`) as the re-frame-native view layer. Hicasso's shapes are deliberately not `ui/` shapes — no `local`, no `effect`, no `frame-root`, different root doors, ephemeral state in app-db behind `h/reg-state` — so do not carry an idiom across from this skill by analogy. Read [Hicasso's public door](https://github.com/day8/re-frame2/blob/main/implementation/hicasso/src/re_frame/hicasso.cljc) instead.

Do **not** use this skill for:

- Events, subscriptions, effects, machines — everything upstream of the view → use [re-frame2](re-frame2.md).
- View code on the Reagent/UIx/reagent-slim adapters (first-class, fully supported) → use [re-frame2](re-frame2.md).
- Rewriting existing Reagent views into Hicasso → use [reagent-migration](reagent-migration.md) (optional; staying on Reagent is fine).
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
- `day8/re-frame2-ui` has no published Maven coordinate and never will, so there is no install recipe to link here. New view work goes to an adapter or to Hicasso, whose public door is the reference linked above.
