# re-frame-migration — Authoring Prompt

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame-migration` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

A self-contained prompt that re-authors the `re-frame-migration` skill from this `spec/` folder alone. Drop into a fresh Claude Code session in the re-frame2 repo root.

## The prompt

> *I'm re-authoring the `re-frame-migration` skill at `skills/re-frame-migration/`. The skill helps a programmer migrate a re-frame v1.x codebase to re-frame2. The skill is **guidance + workflow on top of `migration/from-re-frame-v1/README.md`** — it does not duplicate the M-rule and O-rule content from MIGRATION.md, it routes / sequences / operationalises them.*
>
> *Read these first (in this order):*
>
> *1. `skills/re-frame-migration/spec/design.md` — the locked design decisions (L1 through L10). Q14 lock applies (NO verification module). Source of truth is `migration/from-re-frame-v1/README.md`.*
> *2. `skills/re-frame-migration/spec/inputs.md` — the canonical inputs the skill leans on.*
> *3. `migration/from-re-frame-v1/README.md` — the breaking-change spec the skill routes around. Part 1 has the rule corpus; Part 2 is the AI-agent execution procedure.*
> *4. `skills/re-frame2/SKILL.md` + `skills/re-frame2/reference/**` — the voice / density / load-bearing-rules style to mirror.*
> *5. `skills/re-frame2-setup/SKILL.md` + `skills/re-frame2-setup/{LICENSE,package.json,.claude-plugin/plugin.json}` — the closest structural analogue (per-build-tool detail, distribution metadata triad).*
>
> *Then write the skill at `skills/re-frame-migration/` with this exact file structure:*
>
> ```
> skills/re-frame-migration/
> ├── SKILL.md                       (~190 lines; the router)
> ├── README.md                      (~70 lines; human-facing intro)
> ├── LICENSE                        (mirror skills/re-frame2-setup/LICENSE)
> ├── package.json                   (npm metadata; mirror re-frame2-setup pattern)
> ├── .claude-plugin/plugin.json     (Claude Code plugin metadata)
> └── reference/
>     ├── kickoff-prompt.md           (~60 lines; paste-ready user prompt)
>     ├── setup.md                    (~130 lines; M-0 operational detail)
>     ├── breaking-changes.md         (~180 lines; rule index keyed to v1 triggers)
>     ├── sequencing.md               (~100 lines; recommended rule order)
>     ├── auto-call-site-rewrites.md  (~180 lines; Type A — ns / effect-map / dispatch shapes)
>     ├── auto-cross-cutting.md       (~175 lines; Type A — keyword renames / interceptors / views / init / artefacts)
>     ├── guided-handlers-state.md    (~145 lines; Type B — handler / view / db-seeding / error-handler)
>     ├── guided-interceptors-subs.md (~150 lines; Type B — interceptor / sub / payload / observer)
>     └── output-format.md            (~110 lines; migration-summary shape)
> ```
>
> *The Type A catalogue is split in two so neither leaf exceeds the 250-line soft ceiling: per-call-site mechanical rewrites (M-0/1/4/8/9/16/23/25/38) in `auto-call-site-rewrites.md`; cross-cutting renames + view / init / artefact infrastructure (M-20/21-mechanical/22/24/26-mechanical/27-33/35/40) in `auto-cross-cutting.md`. Type B is split the same way: handler-state-shaped rules (M-3/5/10/11/12/13/14/15) in `guided-handlers-state.md`; interceptor- / sub- / payload- / observer-shaped rules (M-17/18/19/21/23/26) in `guided-interceptors-subs.md`. All four leaves stay one level deep from SKILL.md.*
>
> *Each leaf has a single job; the leaves don't duplicate each other; SKILL.md routes between them. Every leaf is one level deep from SKILL.md (no SKILL → A → B chains).*
>
> *Cardinal rules to bake in (these go in SKILL.md):*
>
> *1. `migration/from-re-frame-v1/README.md` is the source of truth. The skill cites rule ids; it doesn't quote rule text.*
> *2. Type A is automatic, Type B is asked-first. Never silently apply a Type B rewrite.*
> *3. Smallest correct diff. No stylistic refactoring; no opt-in modernisations unless explicitly requested.*
> *4. Apply rules in order (M-0 first, then walk).*
> *5. JVM interop is in scope; don't silently CLJS-only the project.*
> *6. Single-import contract for user code: `(:require [re-frame.core :as rf])`.*
> *7. If a rule is ambiguous, file a GitHub issue against `day8/re-frame2` — don't edit `migration/from-re-frame-v1/README.md` inline. (`bd` is monorepo-internal and never invoked from a published skill — `skills/README.md` baseline.)*
> *8. Don't invent migration rules.*
>
> *Locks to preserve verbatim:*
>
> *- **L3 — NO verification module.** No `reference/verify.md`; no "verify before claiming done" hard rule. The agent applies rules; the author runs tests.*
> *- **L5 — `migration/from-re-frame-v1/README.md` Part 2 IS the migration prompt.** The kickoff prompt in `reference/kickoff-prompt.md` wraps it; it doesn't replace it.*
> *- **L7 — Reagent v2 is the default substrate target.** Substrate migration (O-13 / O-14) is never part of a v1→v2 migration.*
> *- **L9 — Smallest correct diff.** Opt-in modernisations (O-rules) are never auto-applied as part of a routine migration.*
> *- **L10 — Findings stay local.** Don't commit `ai/` or `findings/` content.*
>
> *Frontmatter — the description is "pushy" per Anthropic best practice. List every v1 surface that should trigger discovery: `re-frame.db`, `dispatch-with`, `reg-global-interceptor`, `reg-sub-raw`, `^:flush-dom`, `re-frame.alpha`, `re-frame-test`, old effect-map shape. Plus natural-language phrases: "migrate to re-frame2", "upgrade re-frame", "v1 to v2", "what breaks under re-frame2".*
>
> *Voice: tight, declarative, recipe-shaped. Match `skills/re-frame2/SKILL.md` density. Use tables for rule lookups; use code blocks for search/rewrite shapes. Cite M-N / O-N rule ids in every leaf — the agent that uses the skill will cite the same ids in the migration report.*
>
> *Don't:*
>
> *- Don't write `*.md` documentation outside `skills/re-frame-migration/`.*
> *- Don't commit `ai/` or `findings/` content.*
> *- Don't claim AI authorship anywhere — commits and PR title/body read as Mike Thompson's work.*
> *- Don't write any verification leaf or verify-before-done hard rule.*
> *- Don't duplicate `migration/from-re-frame-v1/README.md` content — reference it by rule id and link.*
>
> *Open the PR with title `feat(skills): re-frame-migration — guided v1→v2 migration skill`. PR body lists: the skill structure, the file LoC table, the locks applied, the existing repo material folded in (`migration/from-re-frame-v1/README.md`, `docs/guide/08-from-re-frame-v1.md`, `docs/the-mayor-method.md`'s prompt pattern). Surface open questions OQ1/OQ2/OQ3 from `spec/design.md` in the PR body for Mike to action.*

## Notes on the reauthoring contract

- The prompt above is a one-shot — feed it to a fresh session, it produces the skill.
- The prompt assumes the session has read access to the repo. It does not assume any out-of-repo context.
- The prompt does **not** ask the session to verify the resulting skill — the verification is Mike's responsibility (read the files, comment on the PR).
- If `migration/from-re-frame-v1/README.md` has changed significantly between authoring passes, the rule indices in `breaking-changes.md` may need an updated sweep. The reauthoring session walks MIGRATION.md afresh and rebuilds the index.

## When to re-author

- MIGRATION.md grows by >5 rules → the existing leaves' indices are stale; rebuild from scratch is easier than patching.
- The Q14 lock changes → the design itself changes; this `spec/` folder needs updating first, then the skill.
- Anthropic skill conventions change materially (e.g. SKILL.md size ceiling changes, frontmatter shape changes) → reauthor against the new conventions.

Otherwise, edit the existing leaves directly; reauthoring from scratch is for major-version updates.
