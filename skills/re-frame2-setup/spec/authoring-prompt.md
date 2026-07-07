# re-frame2-setup — Authoring Prompt

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-setup` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

A self-contained prompt that re-authors the `re-frame2-setup` skill from this `spec/` folder alone. Drop into a fresh Claude Code session in the re-frame2 repo root.

## The prompt

> *I'm re-authoring the `re-frame2-setup` skill at `skills/re-frame2-setup/`. The skill helps an author **bootstrap a fresh re-frame2 ClojureScript project** from nothing (or close to it) — adds the artefact deps, writes a minimal `shadow-cljs.edn`, lays down a canonical entry namespace with `rf/init!` + the Reagent adapter, and walks the author through their first mounted counter. After the counter mounts, the author switches to `skills/re-frame2/` for code-writing or `skills/re-frame2-pair/` for live pair-programming.*
>
> *Read these first (in this order):*
>
> *1. `skills/re-frame2-setup/spec/design.md` — the locked design decisions (L1 through L10). Pillars 1-4 in §2 are non-negotiable. Q14 lock applies (NO verification module).*
> *2. `skills/re-frame2-setup/spec/inputs.md` — the canonical inputs the skill leans on.*
> *3. `examples/core/counter/` — the canonical first-counter shape (`core.cljs`, `index.html`; the examples share a single repo-level build, so there is no per-example `shadow-cljs.edn` — derive the greenfield build from the generator template, per `inputs.md`). `references/first-counter.md` is a trimmed version of this example.*
> *4. `implementation/core/src/re_frame/core.cljc` (for the `rf/init!` contract) + `implementation/adapters/reagent/src/re_frame/adapter/reagent.cljs` (for the adapter spec map shape).*
> *5. `skills/re-frame-migration/SKILL.md` + `skills/re-frame-migration/spec/` — the closest structural sibling with an existing `spec/` triad. Voice / shape mirror this.*
> *6. `skills/re-frame2/SKILL.md` — the parent skill the author switches to once setup is done. SKILL.md's exit hand-off points here; cross-skill routing is single-sourced in `skills/README.md`.*
>
> *Then write the skill at `skills/re-frame2-setup/` with this exact file structure:*
>
> ```
> skills/re-frame2-setup/
> ├── SKILL.md                       (router + canonical greenfield path)
> ├── README.md                      (human-facing intro)
> ├── LICENSE                        (MIT)
> ├── package.json                   (npm metadata)
> ├── .claude-plugin/plugin.json     (Claude Code plugin metadata)
> ├── references/
> │   ├── deps-versions.md           (lockstep VERSION; pay-as-you-go artefact table; deps.edn / package.json)
> │   ├── shadow-cljs.md             (minimal build shape, index.html, CSP)
> │   ├── entry-namespace.md         (rf/init! + Reagent root contract + UIx/Helix greenfield)
> │   └── first-counter.md           (end-to-end worked example)
> ├── spec/
> │   ├── design.md
> │   ├── inputs.md
> │   └── authoring-prompt.md
> ├── tests/
> │   └── setup_drift_test.clj       (structural drift guard; Babashka)
> └── evals/
>     └── evals.json                 (trigger-accuracy fixture)
> ```
>
> *Every reference leaf follows the family leaf-size discipline — ≤250 lines AND ≤16 KB (target ~150 lines / ~10 KB; see `skills/README.md` §Leaf size discipline). `entry-namespace.md` runs slightly over on bytes because it carries the CI-pinned UIx/Helix greenfield recipe (the `check_skill_setup_counter_drift.py` guard pins those snippets to that file, so it can't be split). SKILL.md is the router (under Anthropic's 500-line ceiling). All leaves are one level deep.*
>
> *SKILL.md walks the seven canonical steps:*
>
> *1. Discover the current artefact VERSION.*
> *2. Add deps to `deps.edn`.*
> *3. Add npm deps to `package.json`.*
> *4. Write `shadow-cljs.edn`.*
> *5. Write the entry namespace with `(rf/init! reagent-adapter/adapter)` before any render. Exported entry symbol is `init` (matches the generator template).*
> *6. Write the first counter (schema + event + sub + reg-view + mount; the whole-app-db schema attaches frame-locally at boot under `with-frame`, matching the generator).*
> *7. Run it and verify.*
>
> *Cardinal rules to bake in (these go in SKILL.md):*
>
> *1. **Never hardcode the re-frame2 artefact VERSION in suggestions written to disk.** Look it up first; leave it as `<VERSION>`. (Concrete Clojure/CLJS/Reagent pins matching the template are fine.) Also: the framework coordinate branches on publication state — pre-publish is `:git/sha` / `:local/root`, `:mvn/version` is the labelled post-publish destination.*
> *2. **All eleven artefacts ship at the same VERSION** (and `day8/re-frame2-xray` rides the same line). Mixing is unsupported. Lockstep is a build-time discipline, not a boot-time runtime check.*
> *3. **The day-one shape matches the generator template:** core + Reagent adapter + `-schemas` + `-xray` + explicit `reagent/reagent`. The remaining per-feature artefacts (`-machines`/`-routing`/`-flows`/`-http`/`-ssr`/`-epoch`) are pay-as-you-go.*
> *4. **The Reagent adapter is the default reference substrate.** Unless the author says UIx or Helix, scaffold Reagent; UIx/Helix greenfield is a documented two-substitution delta → the entry-namespace leaf.*
> *5. **The generator template is a USER-RUN route, not one this skill executes.** The `allowed-tools` cover the manual scaffold (`clojure -Stree`, npm, `shadow-cljs watch/compile`) but **not** `clojure -Tnew create` — hand the author the command to run.*
> *6. **Don't write tests for the author.** This skill stops at "the counter mounts".*
> *7. **nREPL is dev-only and bound to localhost.** Anywhere the skill mentions nREPL (shadow-cljs's default REPL, `re-frame2-pair` attachment), remind the author never to expose it on `0.0.0.0` / a public interface.*
>
> *Locks to preserve verbatim:*
>
> *- **L6 — NO verification module.** No `references/verify.md`; no "verify before claiming done" hard rule. Done checklist lists conditions; author confirms.*
> *- **L7 — No bead-ids in user-facing skill content.***
> *- **L8 — Findings stay local.** Don't commit `ai/` or `findings/`.*
> *- **L10 — Clean exit hand-off; no per-skill routing table.** SKILL.md ends with a hand-off paragraph to the next skill (`re-frame2` / `re-frame2-xray` / `re-frame2-pair`); cross-skill routing is single-sourced in `skills/README.md` §Skill routing, which the hand-off points at.*
>
> *Frontmatter — the `description` is "pushy" per Anthropic best practice. List the greenfield-trigger phrases: "start a re-frame2 project", "scaffold re-frame2", "hello-world re-frame2 app", "new re-frame2 app", plus a build failure on a freshly-scaffolded project tracing to missing `re-frame.core` / `re-frame.adapter.reagent` wiring. Do **not** list the ambiguous "add re-frame2 to my repo" — it also matches the non-trivial-existing-app case the skill routes away. The description explicitly carves out the exit routes (after setup → `re-frame2` for code-writing, `re-frame2-pair` for pair-programming) and points at `skills/README.md` §Skill routing for the disqualifiers.*
>
> *Voice: tight, declarative, recipe-shaped. Use tables for routing; use code blocks for canonical shapes (deps.edn, shadow-cljs.edn, core.cljs). Inline Troubleshooting at the end of SKILL.md for the common build failures (missing `.cljs` namespace vs missing npm React — distinct layers, missing `rf/init!`, missing `<div id="app">`, `:init-fn` mismatch, Xray host missing).*
>
> *Don't:*
>
> *- Don't hardcode versions in the leaves — point at `references/deps-versions.md` for lookup.*
> *- Don't teach re-frame2's API beyond what the first counter requires.*
> *- Don't turn UIx/Helix into a full multi-substrate decision tree — Reagent is the default. But **do** ship the UIx/Helix greenfield recipe in `references/entry-namespace.md` §UIx / Helix greenfield: the deps swap, the entry-ns root-API substitution, and the substrate `defui`/`defnc` views reading subs via `use-subscribe` and dispatching via `(:dispatch (rf/capture-frame))`. The drift-test locks (UIx/Helix template-pin parity + substrate views) require it, and the counter-vocabulary guard pins those snippets to that leaf.*
> *- Don't write `*.md` documentation outside `skills/re-frame2-setup/`.*
> *- Don't commit `ai/` or `findings/` content.*
> *- Don't claim AI authorship anywhere — commits and PR title/body read as Mike Thompson's work.*
> *- Don't write a verification leaf or verify-before-done hard rule.*
> *- Don't include bead-ids in user-facing leaves.*
>
> *Open the PR with title `feat(skills): re-frame2-setup — greenfield bootstrap skill`. PR body lists: the skill structure, the file LoC table, the cardinal rules, the relationship to the adjacent skills (`re-frame2` for code-writing, `re-frame2-pair` for pair-programming, `re-frame-migration` for v1→v2 migrants, `re-frame2-implementor` for porters).*

## Notes on the reauthoring contract

- The prompt above is a one-shot — feed it to a fresh session, it produces the skill.
- The prompt assumes the session has read access to the repo and access to `examples/core/counter/`.
- The prompt does **not** ask the session to verify the resulting skill — Mike reads the PR and comments.
- If the canonical `examples/core/counter/` shape has changed between authoring passes, `references/first-counter.md` needs re-derivation.

## When to re-author

- A new mandatory artefact ships in lockstep (the eleven-artefact set grows) → update `references/deps-versions.md` and the SKILL.md framing.
- The `re-frame.adapter.reagent` contract changes materially → `references/entry-namespace.md` and `references/first-counter.md` need updates.
- `rf/init!`'s signature changes → all four reference leaves need a sweep.
- Reagent v3 lands and supplants v2 → re-derive against the v3 counter example.
- Anthropic skill conventions change materially → reauthor against the new conventions.

Otherwise, edit existing leaves directly; reauthoring is for major-version updates.
