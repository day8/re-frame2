# re-frame2-implementor — Authoring Prompt

A self-contained prompt that re-authors the `re-frame2-implementor` skill from this `spec/` folder alone. Drop into a fresh Claude Code session in the re-frame2 repo root.

## The prompt

> *I'm re-authoring the `re-frame2-implementor` skill at `skills/re-frame2-implementor/`. The skill guides an engineer building a NEW re-frame2 implementation in a different host language or substrate — not application authoring on the CLJS reference, not v1→v2 migration. The skill is **guidance + two-phase workflow** layered on top of `spec/` — it does not duplicate the spec corpus, it routes / sequences / operationalises consumption of it for the porting task.*
>
> *Read these first (in this order):*
>
> *1. `skills/re-frame2-implementor/spec/design.md` — the locked design decisions (L1 through L10) plus the resolutions to the three open questions from the bead. Q14 lock applies (NO verification module). Source of truth is `spec/`. CLJS reference impl is one worked example.*
> *2. `skills/re-frame2-implementor/spec/inputs.md` — the canonical inputs the skill leans on.*
> *3. `spec/000-Vision.md`, `spec/Implementor-Checklist.md`, `spec/conformance/README.md` — the three load-bearing spec files.*
> *4. `skills/re-frame-migration/SKILL.md` + `skills/re-frame-migration/reference/**` — closest structural analogue (workflow skill, has spec/ folder, kickoff-prompt pattern). Mirror the voice / density / load-bearing-rules style.*
> *5. `skills/re-frame2/SKILL.md` — the canonical authoring pattern. Voice and structure.*
> *6. `skills/re-frame2-setup/{LICENSE,package.json,.claude-plugin/plugin.json}` — the distribution-metadata triad.*
>
> *Then write the skill at `skills/re-frame2-implementor/` with this exact file structure:*
>
> ```
> skills/re-frame2-implementor/
> ├── SKILL.md                       (router; ~250 lines)
> ├── README.md                      (~80 lines)
> ├── LICENSE                        (mirror skills/re-frame-migration/LICENSE)
> ├── package.json                   (npm metadata; mirror re-frame-migration shape)
> ├── .claude-plugin/plugin.json     (Claude Code plugin metadata)
> └── reference/
>     ├── kickoff-prompt.md          (~80 lines; paste-ready prompt)
>     ├── phase-1-decisions.md       (~200 lines; D1-D7 walkthrough)
>     ├── decision-record.md         (~120 lines; fill-in template)
>     ├── phase-2-impl-order.md      (~250 lines; EP-by-EP)
>     ├── reference-impl-tour.md     (~150 lines; descriptive tour of implementation/)
>     ├── conformance.md             (~140 lines; harness + diagnosis)
>     └── output-format.md           (~120 lines; three report shapes)
> ```
>
> *Each leaf has a single job; the leaves don't duplicate each other; SKILL.md routes between them. Every leaf is one level deep from SKILL.md (no SKILL → A → B chains).*
>
> *Cardinal rules to bake into SKILL.md:*
>
> *1. The spec is the contract. `implementation/` is one worked example, not normative.*
> *2. Phase 1 before Phase 2. Decisions locked in writing before code is written.*
> *3. Implement in dependency order: 001 → 002 → 006 → 004 → 009 → optional.*
> *4. Substrate-agnostic phrasing. Don't leak CLJS+Reagent framings as universal.*
> *5. No core.async. Async fx via host primitives; cross-frame work serialised per frame.*
> *6. JVM-runnability for the test surface (pure functions stay callable from non-substrate harness).*
> *7. Conformance corpus is the acceptance test. `passed / claimed-applicable`.*
> *8. Spec gaps file `bd create` beads; never silent extrapolation from the reference.*
>
> *Locks to preserve verbatim (from `spec/design.md`):*
>
> *- **L1 — `spec/` is the contract.** Never treat `implementation/` as normative. The tour leaf is the only place CLJS framings live, and even there they're tagged as descriptive.*
> *- **L2 — Two-phase workflow.** Sequential; not collapsible.*
> *- **L3 — Q14: NO verification module.** No `reference/verify.md`; no "verify before claiming done" hard rule. The engineer runs their builds; the skill walks the workflow.*
> *- **L4 — Substrate-agnostic phrasing throughout.** Identity primitive, render-tree, reactive container — generic.*
> *- **L5 — Conformance corpus is the acceptance test.** The objective measure of "is this re-frame2?"*
> *- **L6 — Spec gaps file beads.** No silent extrapolation; no patching the spec inline.*
> *- **L7 — No bead-ids in user-facing content.** SKILL.md / README / reference/ leaves carry no `rf2-XXXX` references.*
> *- **L8 — Findings stay local.** Don't commit `ai/` or `findings/`.*
> *- **L9 — No AI attribution.** Commits and PR title/body read as Mike's own work.*
> *- **L10 — Cross-link bidirectionally.** Update `skills/re-frame2/SKILL.md`, `skills/re-frame-migration/SKILL.md`, `docs/skills/re-frame2-implementor.md` (new), and `mkdocs.yml`.*
>
> *Frontmatter — the `description` is "pushy" per Anthropic best practice. List every triggering phrase: "porting re-frame2", "implementing re-frame2 in <language>", "writing a re-frame2 runtime", "second re-frame2 implementation", "TypeScript port of re-frame2", "implementor checklist", "conformance corpus". Plus "any phrasing about building re-frame2 itself rather than using it" framing.*
>
> *Voice: tight, declarative, workflow-shaped. Match `skills/re-frame-migration/SKILL.md` density. Use tables for decision-block lookups; use code blocks for harness shapes and decision-record templates. Substrate-agnostic throughout; CLJS bindings only in `reference-impl-tour.md`, and that leaf is explicit-descriptive.*
>
> *Don't:*
>
> *- Don't write `*.md` documentation outside `skills/re-frame2-implementor/` plus the four cross-link touchpoints (`skills/re-frame2/SKILL.md`, `skills/re-frame-migration/SKILL.md`, `docs/skills/re-frame2-implementor.md`, `mkdocs.yml`).*
> *- Don't commit `ai/` or `findings/` content.*
> *- Don't claim AI authorship anywhere — commits and PR title/body read as Mike Thompson's work.*
> *- Don't write any verification leaf or verify-before-done hard rule.*
> *- Don't duplicate `spec/` content — reference it by URL.*
> *- Don't conflate CLJS reference behaviour with pattern requirements outside the tour leaf.*
> *- Don't reference rf2-XXXX bead ids in user-facing content.*
>
> *Open the PR with title `feat(skills): re-frame2-implementor — guided two-phase skill for building a new re-frame2 impl (rf2-5xje)`. PR body lists: skill structure, the file LoC table, the locks applied, the existing repo material folded in (`spec/Implementor-Checklist.md`, `spec/conformance/`, `spec/000-Vision.md`'s host matrix), cross-link updates made, the three open-questions-resolutions copied from `spec/design.md` §7.*

## Notes on the reauthoring contract

- The prompt above is a one-shot — feed it to a fresh session, it produces the skill.
- The prompt assumes the session has read access to the re-frame2 repo. It does not assume any out-of-repo context.
- The prompt does **not** ask the session to verify the resulting skill — the verification is Mike's responsibility (read the files, comment on the PR).
- If `spec/` has reorganised significantly between authoring passes (renamed EP files, new optional EPs), the URL references throughout the leaves need an updated sweep. The reauthoring session walks `spec/` afresh and rebuilds the references.

## When to re-author from scratch

- `spec/` grows by >2 new EPs → the existing leaves' Phase 1 / Phase 2 walkthrough surfaces are stale; rebuild from scratch.
- A second reference implementation lands → `reference-impl-tour.md` needs restructuring; rebuilding the leaf is easier than patching.
- The Q14 lock changes → the design itself changes; this `spec/` folder needs updating first, then the skill.
- Anthropic skill conventions change materially → reauthor SKILL.md against the new conventions.

Otherwise, edit the existing leaves directly; reauthoring from scratch is for major-version updates.
