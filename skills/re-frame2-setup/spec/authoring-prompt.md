# re-frame2-setup — Authoring Prompt

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-setup` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

A self-contained prompt that re-authors the `re-frame2-setup` skill from this `spec/` folder alone. Drop into a fresh Claude Code session in the re-frame2 repo root.

**This prompt is an execution wrapper** — an ordered set of reads plus a write/run step. Everything normative is owned elsewhere and linked, never restated (a second copy is what drifts): the locked decisions, file structure, and discovery surface live in [`design.md`](design.md); the canonical inputs, mappings, and reauthor triggers live in [`inputs.md`](inputs.md); the family conventions (leaf-size discipline, single-source routing, the published-skill `allowed-tools` baseline) live in [`skills/README.md`](../../README.md); the user-facing six-step workflow the skill teaches lives in [`SKILL.md`](../SKILL.md).

## The reauthoring procedure

> *I'm re-authoring the `re-frame2-setup` skill at `skills/re-frame2-setup/` — the greenfield-bootstrap skill that walks an author from an empty directory to a mounted first counter, then hands off to `re-frame2` (code-writing) / `re-frame2-pair` (live pair-programming). Read these in order, then write the skill to match what each source owns — do not paste a copy of any of it into this prompt or the leaves:*
>
> *1. **[normative — decisions]** `skills/re-frame2-setup/spec/design.md` — the goal, the four pillars (§2, Q14 lock), the locked decisions L1–L13 (cardinal rules, day-one shape, exit hand-off, the derived scaffold), the locked file structure (§5), the discovery surface (§6), and the drift-guard map (§9a). Sole source for the layout, the rules, and the locks.*
> *2. **[normative — inputs & triggers]** `skills/re-frame2-setup/spec/inputs.md` — the canonical greenfield artefacts and mappings the leaves derive from, and the §6 update procedure (the when-to-reauthor triggers).*
> *3. **[family conventions]** `skills/README.md` — the leaf-size discipline (§Leaf size discipline: the numeric ceiling every leaf obeys), the single-source skill-routing table (§Skill routing: the exit hand-off points here, no per-skill routing table), and the published-skill `allowed-tools` baseline (§Published-skill `allowed-tools` baseline, including the nREPL-localhost reminder).*
> *4. **[user workflow]** `skills/re-frame2-setup/SKILL.md` (current) — the six-step canonical path the skill teaches, to preserve/refresh rather than re-derive.*
> *5. **[canonical sources]** `examples/core/counter/{core.cljs,index.html}`; `implementation/core/src/re_frame/core.cljc` (the `rf/init!` contract) + `implementation/adapters/reagent/src/re_frame/adapter/reagent.cljs` (the adapter spec map); the generator template under `tools/template/resources/day8/re_frame2_template/{_reagent,_shared,_uix,root}/` + `tools/template/src/day8/re_frame2_template/hooks.clj` (the twelve-file emission the default scaffold IS — rendered into the leaves by `tests/first_counter_derivation.clj`, never retyped — plus the day-one deps, the hot-reload lifecycle and the UIx three-file swap); and `skills/re-frame-migration/SKILL.md` + `spec/` for voice / shape.*
>
> *Then write the skill to the file structure and cardinal rules locked in `design.md`, the discovery surface in `design.md` §6, and the family leaf-size discipline in `skills/README.md` — honouring the L1–L13 locks in `design.md` §3 without restating them — regenerate the two derived regions with `bb tests/first_counter_derivation.clj` rather than writing their file bodies. Preserve the six-step user workflow `SKILL.md` owns. Open a PR following the repo's conventions; commits and PR title/body read as the maintainer's own work, and the commit set touches only `skills/re-frame2-setup/**`.*

## Notes on the reauthoring contract

- The prompt above is a one-shot — feed it to a fresh session, it produces the skill.
- The prompt assumes read access to the repo, including `examples/core/counter/` and the generator template.
- The prompt does **not** ask the session to verify the resulting skill — Mike reads the PR (`design.md` L6 / Q14: the skill deliverable ships no verification module; the shipped skill itself does run the app build it scaffolds).
- When to re-author (major-version updates) and the per-surface update procedure are owned by [`inputs.md`](inputs.md) §6 — consult it there rather than restating the triggers in this wrapper.
