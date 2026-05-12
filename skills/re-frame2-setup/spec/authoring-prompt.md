# re-frame2-setup — Authoring Prompt

A self-contained prompt that re-authors the `re-frame2-setup` skill from this `spec/` folder alone. Drop into a fresh Claude Code session in the re-frame2 repo root.

## The prompt

> *I'm re-authoring the `re-frame2-setup` skill at `skills/re-frame2-setup/`. The skill helps an author **bootstrap a fresh re-frame2 ClojureScript project** from nothing (or close to it) — adds the artefact deps, writes a minimal `shadow-cljs.edn`, lays down a canonical entry namespace with `rf/init!` + the Reagent adapter, and walks the author through their first mounted counter. After the counter mounts, the author switches to `skills/re-frame2/` for code-writing or `skills/re-frame-pair2/` for live pair-programming.*
>
> *Read these first (in this order):*
>
> *1. `skills/re-frame2-setup/spec/design.md` — the locked design decisions (L1 through L10). Pillars 1-4 in §2 are non-negotiable. Q14 lock applies (NO verification module).*
> *2. `skills/re-frame2-setup/spec/inputs.md` — the canonical inputs the skill leans on.*
> *3. `examples/reagent/counter/` — the canonical first-counter shape (`core.cljs`, `shadow-cljs.edn`, `index.html`). `reference/first-counter.md` is a trimmed version of this example.*
> *4. `implementation/core/src/re_frame/core.cljc` (for the `rf/init!` contract) + `implementation/reagent/src/re_frame/adapter/reagent.cljs` (for the adapter spec map shape).*
> *5. `skills/re-frame-migration/SKILL.md` + `skills/re-frame-migration/spec/` — the closest structural sibling with an existing `spec/` triad. Voice / shape mirror this.*
> *6. `skills/re-frame2/SKILL.md` — the parent skill the author switches to once setup is done. The setup skill's routing-on-exit table points here.*
>
> *Then write the skill at `skills/re-frame2-setup/` with this exact file structure:*
>
> ```
> skills/re-frame2-setup/
> ├── SKILL.md                       (~170 lines; router + canonical greenfield path)
> ├── README.md                      (human-facing intro)
> ├── LICENSE                        (MIT)
> ├── package.json                   (npm metadata)
> ├── .claude-plugin/plugin.json     (Claude Code plugin metadata)
> ├── reference/
> │   ├── deps-versions.md           (~120 lines; lockstep VERSION; pay-as-you-go artefact table)
> │   ├── shadow-cljs.md             (~100 lines; minimal build shape, index.html)
> │   ├── entry-namespace.md         (~120 lines; rf/init! + Reagent root contract)
> │   └── first-counter.md           (~110 lines; end-to-end worked example)
> └── spec/
>     ├── design.md
>     ├── inputs.md
>     └── authoring-prompt.md
> ```
>
> *Every reference leaf is ≤250 lines (target ~120). SKILL.md is ~170 lines (under Anthropic's 500-line ceiling). All leaves are one level deep.*
>
> *SKILL.md walks the seven canonical steps:*
>
> *1. Discover the current artefact VERSION.*
> *2. Add deps to `deps.edn`.*
> *3. Add npm deps to `package.json`.*
> *4. Write `shadow-cljs.edn`.*
> *5. Write the entry namespace with `(rf/init! reagent-adapter/adapter)` before any render.*
> *6. Write the first counter (event + sub + reg-view + mount).*
> *7. Run it and verify.*
>
> *Cardinal rules to bake in (these go in SKILL.md):*
>
> *1. **Never hardcode artefact versions in suggestions written to disk.** Look them up first.*
> *2. **All ten artefacts ship at the same VERSION.** Mixing is unsupported.*
> *3. **Only add the per-feature artefacts the author actually uses.** Core + adapter on day one; the rest pay-as-you-go.*
> *4. **The Reagent adapter is the default reference substrate.** Unless the author says UIx or Helix, scaffold Reagent.*
> *5. **Don't write tests for the author.** This skill stops at "the counter mounts".*
>
> *Locks to preserve verbatim:*
>
> *- **L6 — NO verification module.** No `reference/verify.md`; no "verify before claiming done" hard rule. Done checklist lists conditions; author confirms.*
> *- **L7 — No bead-ids in user-facing skill content.***
> *- **L8 — Findings stay local.** Don't commit `ai/` or `findings/`.*
> *- **L10 — Routing-on-exit table at the end of SKILL.md.** Every adjacent skill listed by name. The author leaves this skill confidently for the next one.*
>
> *Frontmatter — the `description` is "pushy" per Anthropic best practice. List every greenfield-trigger phrase: "start a re-frame2 project", "scaffold re-frame2", "how do I set up re-frame2", "add re-frame2 to my repo", "give me a hello-world re-frame2 app". The description explicitly carves out the exit routes (after setup → `re-frame2` for code-writing, `re-frame-pair2` for pair-programming).*
>
> *Voice: tight, declarative, recipe-shaped. Use tables for routing; use code blocks for canonical shapes (deps.edn, shadow-cljs.edn, core.cljs). Inline Troubleshooting at the end of SKILL.md for the four most common build failures (classpath miss, missing react, missing `rf/init!`, missing `<div id="app">`).*
>
> *Don't:*
>
> *- Don't hardcode versions in the leaves — point at `reference/deps-versions.md` for lookup.*
> *- Don't teach re-frame2's API beyond what the first counter requires.*
> *- Don't branch into UIx/Helix substrates at greenfield.*
> *- Don't write `*.md` documentation outside `skills/re-frame2-setup/`.*
> *- Don't commit `ai/` or `findings/` content.*
> *- Don't claim AI authorship anywhere — commits and PR title/body read as Mike Thompson's work.*
> *- Don't write a verification leaf or verify-before-done hard rule.*
> *- Don't include bead-ids in user-facing leaves.*
>
> *Open the PR with title `feat(skills): re-frame2-setup — greenfield bootstrap skill`. PR body lists: the skill structure, the file LoC table, the cardinal rules, the relationship to the adjacent skills (`re-frame2` for code-writing, `re-frame-pair2` for pair-programming, `re-frame-migration` for v1→v2 migrants, `re-frame2-implementor` for porters).*

## Notes on the reauthoring contract

- The prompt above is a one-shot — feed it to a fresh session, it produces the skill.
- The prompt assumes the session has read access to the repo and access to `examples/reagent/counter/`.
- The prompt does **not** ask the session to verify the resulting skill — Mike reads the PR and comments.
- If the canonical `examples/reagent/counter/` shape has changed between authoring passes, `reference/first-counter.md` needs re-derivation.

## When to re-author

- A new mandatory artefact ships in lockstep (the ten-artefact set grows) → update `reference/deps-versions.md` and the SKILL.md framing.
- The `re-frame.adapter.reagent` contract changes materially → `reference/entry-namespace.md` and `reference/first-counter.md` need updates.
- `rf/init!`'s signature changes → all four reference leaves need a sweep.
- Reagent v3 lands and supplants v2 → re-derive against the v3 counter example.
- Anthropic skill conventions change materially → reauthor against the new conventions.

Otherwise, edit existing leaves directly; reauthoring is for major-version updates.
