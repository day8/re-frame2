# reagent-migration — Authoring Prompt

> **Skill-internal meta-doc.** A self-contained prompt that re-authors the
> `reagent-migration` skill from this `spec/` folder alone. Not part of the
> skill contract. For the skill contract, see [`SKILL.md`](../SKILL.md).

## The prompt

> *I'm re-authoring the `reagent-migration` skill at `skills/reagent-migration/`. The skill migrates **Reagent view code to Freehand** — `re-frame.freehand`, aliased `v`, re-frame2's re-frame-native view layer. It is an **AI skill that applies judgment, NOT a codemod** (the rewrite tool was shelved — Mike ruled skill-only; if it seems to need a companion tool, STOP and ask Mike).*
>
> *Read these first, in order:*
>
> *1. `skills/reagent-migration/spec/design.md` — the locked decisions (L1–L11). The load-bearing ones: L1 (AI skill not codemod), L2 (OPTIONAL, SECOND, PRE-ALPHA; Reagent/UIx/Helix stay first-class peers), L4 (whole view is the unit — never half-migrate), L6 (closed subtrees are a hard constraint, because there is no outward React bridge), L8 (migrate interpreted, never promote mid-flight), L9 (emit only what has shipped), L10 (generic to any Reagent app).*
> *2. `skills/reagent-migration/spec/inputs.md` — the inputs. The primary one is the **exported roster** (`spec/API.md` §Freehand views, `docs/api/re-frame.freehand.md`), because it decides whether a rewrite target exists at all. Spec 004 / 004B / 004C / 004D / 008 / 011 are the contract. The design corpus (EP-0036, the decision records, the draft guide) is a HAZARD, not an input: it describes forms that are not exported.*
> *3. `skills/re-frame-migration/` — the structural sibling. Match its SHAPE: `SKILL.md` router + `README.md` + the distribution triad (`LICENSE` / `package.json` / `.claude-plugin/plugin.json`) + `references/` leaves + `spec/` meta-docs + `evals/evals.json`. Match its front-matter format, voice, and cardinal-rules density.*
>
> *Then write the skill with the file structure locked in `design.md` §4: `SKILL.md` (router) + six reference leaves (`mental-model`, `catalog-mechanical` (M-tier, before→after), `catalog-judgment` (D-tier, "how to DECIDE"), `catalog-reject` (R-tier, the honesty backbone), `procedure` (incremental closed-subtree, plus the structural test surface), `gotchas`) + three `spec/` meta-docs + `evals/evals.json`. Every leaf one level deep from `SKILL.md`; each leaf ≤250 lines / ≤16 KB.*
>
> *Cardinal rules to bake into `SKILL.md`: (1) AI judgment, not a codemod; (2) the whole view is the unit — never half-migrate; (3) incremental, never big-bang; (4) cite `MIG-NN` ids; (5) views only — name dataflow changes; (6) emit only what has shipped — check `spec/API.md`; (7) the skill runs the noninteractive compile/test gates itself, the programmer owns the interactive visual confirmation.*
>
> *The four view shifts the mental model teaches: brackets mount and parens inline (a declaration, not a function — and calling one raises `:rf.error/view-called-directly`); deref-drop (`@(subscribe …)` → `(v/sub …)`, which returns the value); dispatch lifts to data (event vectors, with `::v/value` / `::v/checked` / `::v/key` as the closed projection roster); and the view holds no state and no lifecycle (no `local`, no `ref`, no `effect` — app-db, a semantic controller, or a registered behavior).*
>
> *Front-matter `description` is "pushy" and self-limiting: trigger on Reagent-view→Freehand phrasing (v/defview, reg-view→defview, deref-drop, moving off Reagent hiccup, the Reagent view surfaces `r/atom`/`r/with-let`/`r/create-class`/`adapt-react-class`), AND state the negatives: the v1→v2 migration is `re-frame-migration`, authoring is `re-frame2`, and staying on Reagent is fine.*
>
> *Voice: tight, declarative, recipe-shaped; full sentences over dash-chained fragments. Tables for rule lookups; code blocks for before→after shapes. Cite `MIG-NN` in every catalogue.*
>
> *Don't: ship or invoke a codemod; oversell Freehand (it is pre-alpha — say so); emit `local` / `effect` / `ref` / `v/->react` / `v/html` / `v/check` / `v/client-only` or any other verb absent from `spec/API.md`; add `{:compiled true}` during a migration pass; write `*.md` outside `skills/reagent-migration/` (except the index registration in `skills/README.md` + the docs mirror/nav); commit anything under `ai/`; use this repo's testbeds or paths as examples (stay generic); claim AI authorship in commits/PR.*
>
> *Open the PR titled `feat(skills): reagent-migration — AI skill for Reagent→Freehand view migration`. Body: the sibling shape matched, the M/D/R catalogue distilled (which rules), the optional/second/pre-alpha framing, the incremental procedure, the per-tier evals. Surface OQ1/OQ2 from `design.md` for Mike.*

## Notes on the reauthoring contract

- The prompt is a one-shot: feed it to a fresh session, it produces the skill.
- It assumes repo read access. Verifying every emitted verb against
  `spec/API.md` is part of the job, not an optional polish pass.
- It does not ask the session to verify the resulting skill's judgment calls —
  that verification is Mike's (read the files, comment on the PR).

## When to re-author

- **A major Freehand surface lands** — the qualified host leaf for foreign React
  components, or the outward React bridge. Either one collapses a large part of
  `catalog-reject.md` and relaxes the closed-subtree constraint in
  `procedure.md`; that is a rebuild, not an edit.
- The positioning changes (Freehand graduates from pre-alpha, or publishes a
  Maven coordinate) → design L2 changes; update this `spec/` folder first, then
  the skill.

Otherwise, edit the leaves directly; reauthoring from scratch is for major
surface changes.
