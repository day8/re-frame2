# re-frame2-improver — Authoring Prompt

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-improver` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

A self-contained prompt that re-authors the `re-frame2-improver` skill from this `spec/` folder alone. Drop into a fresh Claude Code session in the re-frame2 repo root.

## The prompt

> *I'm re-authoring the `re-frame2-improver` skill at `skills/re-frame2-improver/`. The skill is a **critique-mode** for **existing** re-frame2 ClojureScript code: read a body of source in scope, detect anti-patterns from a small catalogue, surface each finding with concrete file/line evidence cross-linked to the canonical idiom, and — under a two-tier Edit gate — propose or apply an inline fix. It is **explicit-pull only** and never fabricates findings.*
>
> *Read these first (in this order):*
>
> *1. `skills/re-frame2-improver/spec/design.md` — the locked design decisions (L1–L10). Pillar 1 in §2 (implementation is ground truth — no fabricated APIs) is cardinal.*
> *2. `skills/re-frame2-improver/spec/inputs.md` — the canonical inputs, including the verified spec-ownership table. Re-verify every API claim against the current `spec/` + `implementation/` before writing.*
> *3. `skills/shared/retro-protocol.md` — the shared protocol (seven-step workflow, untrusted-evidence boundary, redaction, layer-routing, the normative Edit-gate split at step 6). The skill consumes this; it does not copy it.*
> *4. `skills/re-frame2/SKILL.md` + `skills/re-frame2/patterns/` + `skills/re-frame2/references/` — the canonical-idiom source every cross-link routes to.*
> *5. `skills/re-frame2-pair-retro/` — the structural sibling that shares the protocol and the `spec/` triad shape. Voice / structure mirror this.*
> *6. `skills/README.md` §Skill routing — the disambiguation matrix the trigger semantics defer to.*
>
> *Then write the skill at `skills/re-frame2-improver/` with this exact file structure:*
>
> ```
> skills/re-frame2-improver/
> ├── SKILL.md
> ├── README.md
> ├── LICENSE (MIT)
> ├── package.json
> ├── .claude-plugin/plugin.json
> ├── evals/evals.json (8 should-trigger + 8 should-not-trigger)
> ├── references/
> │   ├── README.md (catalogue index + locked five-section leaf format + growth procedure)
> │   └── <six anti-pattern leaves>.md
> └── spec/
>     ├── design.md
>     ├── inputs.md
>     └── authoring-prompt.md
> ```
>
> *SKILL.md walks the workflow: (1) establish scope; (2) load the catalogue; (3) apply each detection rule with concrete evidence; (4) cross-link to the canonical idiom; (5) propose fixes under the two-tier Edit gate (full statement here, exactly once); (6) surface findings in the output shape. Frontmatter `allowed-tools` = `Read` / `Edit` / `Grep` / `Glob` — no `gh`.*
>
> *The six launch leaves (each in the locked five-section format — detection rules / why / canonical fix / worked example / edge cases):*
>
> *1. `manual-retry-loops.md` → Managed HTTP (Spec 014). The closed failure-category set is exactly `:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx :rf.http/decode-failure :rf.http/accept-failure :rf.http/aborted` — verify against `spec/014-HTTPRequests.md §Failure categories`; do NOT invent `:rf.http/payload`.*
> *2. `boolean-discriminator-subs.md` → Tags query layer (Spec 005).*
> *3. `manual-loading-flags.md` → Nine States (`spec/Pattern-NineStates.md`).*
> *4. `schemaless-events.md` → Schemas at boundaries (Spec 010). May carry an additive "Regression example" section.*
> *5. `imperative-effects.md` → Data-only fx via `reg-fx` (`spec/Conventions.md`). `:platforms` gating is owned by Spec 011 (SSR), NOT a non-existent Spec 003.*
> *6. `view-side-hook-state.md` → `app-db` + `reg-sub` (Spec 004 / `spec/Principles.md`). The testing surface is `compute-sub` for subs and `dispatch-sync` + `frame-db` for events — there is no `compute-event`.*
>
> *Locks to preserve verbatim (from design.md §3):*
>
> *- **L1 — Explicit-pull only.** Three filters; decline + ask for a snippet rather than fabricate.*
> *- **L2 — Static, never live.** Route live work to `re-frame2-pair`, retros to `re-frame2-pair-retro`.*
> *- **L3 — Two-tier Edit gate.** Canonical-idiom-shaped unrestricted; evidence-shaped approval-first; when in doubt, gate. Normative source in the shared leaf; full statement once in SKILL.md §Workflow step 5; a one-line pointer in §Anti-patterns.*
> *- **L4 — Filing is delegated.** No `gh` in `allowed-tools`; framework-shape friction routes to the retro skill.*
> *- **L8 — No fabricated findings, no "read the spec" reduction.***
> *- **L9 — Findings stay local.** No shipped doc points at the gitignored `ai/` tree; the design rationale lives in this `spec/` folder.*
> *- **L10 — No AI attribution.***
>
> *Voice: confident, opinionated, evidence-grounded; name the idiom and the layer; no hedging.*
>
> *Don't:*
>
> *- Don't cite an API that doesn't exist in `spec/` + `implementation/` — verify first.*
> *- Don't land an evidence-shaped `Edit` without explicit approval.*
> *- Don't reduce a finding to "read the spec".*
> *- Don't point any shipped doc at `ai/findings/...` (gitignored, local-only).*
> *- Don't write `*.md` outside `skills/re-frame2-improver/` (the one shared-leaf cross-link line in `skills/shared/retro-protocol.md` is the documented exception).*
> *- Don't claim AI authorship; commits and PR read as Mike Thompson's work.*
>
> *Open the PR with title `feat(skills): re-frame2-improver — re-frame2 code-critique skill`. PR body lists: the skill structure, the six leaves + their canonical idioms, the trigger semantics, the Edit-gate split, and the relationship to the sibling skills (`re-frame2` — canonical-idiom source; `re-frame2-pair-retro` — shares the protocol leaf).*

## Notes on the reauthoring contract

- The prompt is a one-shot — feed it to a fresh session in the repo root, it produces the skill.
- The prompt assumes read access to the re-frame2 repo and the `skills/re-frame2/` canonical-idiom source.
- The prompt does **not** ask the session to verify the resulting skill against a real review — Mike reads the PR and exercises the skill on real code afterwards.
- Every API claim MUST be re-verified against the current `spec/` + `implementation/` at authoring time; a fabricated idiom is the cardinal failure for a critique skill.

## When to re-author

- A canonical idiom changes materially in `skills/re-frame2/patterns/` or the owning `spec/` document → re-derive the affected leaf's "After" snippet and footer.
- A spec ownership / API surface moves (e.g. failure-category set, testing surface, `:platforms` ownership) → re-verify `inputs.md §3` and every leaf.
- A new anti-pattern earns a leaf (3+ real reviews) or a deferred candidate is promoted → add the leaf + catalogue row.
- The shared protocol changes → re-check the SKILL.md step-5 pointer.
- Anthropic skill conventions change materially → reauthor against the new conventions.

Otherwise, edit existing leaves directly; reauthoring is for major-version updates.
