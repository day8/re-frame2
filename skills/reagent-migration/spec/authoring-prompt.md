# reagent-migration — Authoring Prompt

> **Skill-internal meta-doc.** A self-contained prompt that re-authors the
> `reagent-migration` skill from this `spec/` folder alone. Not part of the
> skill contract. For the skill contract, see [`SKILL.md`](../SKILL.md).

## The prompt

> *I'm re-authoring the `reagent-migration` skill at `skills/reagent-migration/`. The skill migrates **Reagent view code to re-frame2's experimental `re-frame.ui` compiled-view substrate**. It is an **AI skill that applies judgment, NOT a codemod** (the rewrite-clj tool was shelled — Mike ruled skill-only; if it seems to need a companion tool, STOP and ask Mike).*
>
> *Read these first, in order:*
>
> *1. `skills/reagent-migration/spec/design.md` — the locked decisions (L1–L9). The load-bearing ones: L1 (AI skill not codemod), L2 (OPTIONAL, SECOND, EXPERIMENTAL; staying on Reagent is first-class), L4 (whole view is the unit — never half-migrate), L5 (views only; name dataflow changes, never make them), L8 (generic to any Reagent app).*
> *2. `skills/reagent-migration/spec/inputs.md` — the inputs: the `MIG-01…35` rule table (the shared vocabulary — distil, don't re-host), the shelved tool's 47 golden fixtures (worked examples for the before→after blocks), and the normative specs (`spec/004-Views.md`, `004B`, `004C`, `implementation/ui/src/re_frame/ui.cljc` for the shipped surface).*
> *3. `skills/re-frame-migration/` — the structural sibling. Match its SHAPE: `SKILL.md` router + `README.md` + the distribution triad (`LICENSE` / `package.json` / `.claude-plugin/plugin.json`) + `references/` leaves + `spec/` meta-docs + `evals/evals.json`. Match its front-matter format, voice, and cardinal-rules density.*
>
> *Then write the skill with the file structure locked in `design.md` §4: `SKILL.md` (router) + six reference leaves (`mental-model`, `catalog-mechanical` (M-tier, before→after), `catalog-judgment` (D-tier, "how to DECIDE"), `catalog-reject` (R-tier, the experimental-honesty backbone), `procedure` (incremental closed-subtree), `gotchas`) + three `spec/` meta-docs + `evals/evals.json`. Every leaf one level deep from `SKILL.md`; each leaf ≤250 lines / ≤16 KB.*
>
> *Cardinal rules to bake into `SKILL.md`: (1) AI judgment, not a codemod; (2) the whole view is the unit — never half-migrate; (3) incremental, never big-bang; (4) cite `MIG-NN` ids; (5) views only — name dataflow changes; (6) the skill runs the noninteractive compile/test gates itself (verify-as-you-go, under the trust-the-explicit-invoker baseline); the programmer owns the interactive visual confirmation.*
>
> *Front-matter `description` is "pushy" and self-limiting: trigger on Reagent-view→re-frame.ui phrasing (compiled views, reg-view→defview, deref-drop, adopt the experimental substrate, the Reagent view surfaces `r/atom`/`r/with-let`/`r/create-class`/`adapt-react-class`/`:dangerouslySetInnerHTML`), AND state the negatives: the v1→v2 migration is `re-frame-migration`, authoring is `re-frame2`, and staying on Reagent is fine.*
>
> *Voice: tight, declarative, recipe-shaped; match `skills/re-frame-migration/SKILL.md`. Tables for rule lookups; code blocks for before→after shapes. Cite `MIG-NN` in every catalogue.*
>
> *Don't: ship or invoke a codemod; oversell re-frame.ui (it is experimental — say so); emit a form for a staged capability that hasn't shipped (the `sub` frame-pin — name the gap, hold the view; re-verify every "not shipped" claim against `ui.cljc`'s exports, since SSR `render-static`/`hydrate-root`, compiled `route-link`, and the outward `ui/->react` bridge have since shipped and are transforms now); write `*.md` outside `skills/reagent-migration/` (except the index registration in `skills/README.md` + the docs mirror/nav); commit anything under `ai/` or revive `tools/ui-migrator`; use this repo's testbeds/paths as examples (stay generic); claim AI authorship in commits/PR.*
>
> *Open the PR titled `feat(skills): reagent-migration — AI skill for Reagent→re-frame.ui view migration`. Body: the sibling shape matched, the M/D/R catalogue distilled (which rules), the optional/second/experimental framing, the incremental procedure, the per-tier evals. Surface OQ1/OQ2 from `design.md` for Mike.*

## Notes on the reauthoring contract

- The prompt is a one-shot: feed it to a fresh session, it produces the skill.
- It assumes repo read access and read-only access to the salvage sources (the `ai/findings` prep table and the shelved-tool fixtures) — it copies neither.
- It does not ask the session to verify the resulting skill — verification is Mike's (read the files, comment on the PR).

## When to re-author

- The framework's `MIG` rule table grows by several rules, or re-tiers many → rebuild the catalogues fresh.
- A staged `re-frame.ui` capability ships (the remaining one is the `sub` frame-pin; SSR `render-static`/`hydrate-root`, compiled `route-link`, and the outward `ui/->react` bridge already shipped and were folded into the mechanical/judgment catalogues) → the reject/capability-gap list shrinks; re-author `catalog-reject.md` and the mechanical/judgment target that now exists.
- The positioning changes (e.g. re-frame.ui graduates from experimental) → design L2 changes; update this `spec/` folder first, then the skill.

Otherwise, edit the leaves directly; reauthoring from scratch is for major-version updates.
