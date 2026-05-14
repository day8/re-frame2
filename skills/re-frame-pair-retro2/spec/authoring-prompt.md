# re-frame-pair-retro2 — Authoring Prompt

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame-pair-retro2` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

A self-contained prompt that re-authors the `re-frame-pair-retro2` skill from this `spec/` folder alone. Drop into a fresh Claude Code session in the re-frame2 repo root.

## The prompt

> *I'm re-authoring the `re-frame-pair-retro2` skill at `skills/re-frame-pair-retro2/`. The skill helps a user **retrospect on a re-frame-pair2 session** — diagnose friction, classify root causes, propose 2-5 prioritised improvements (plus optional bolder ideas), and optionally draft a bead. The skill defaults to **diagnosis, not contribution**; bead filing is opt-in. The skill is one of the principal feedback loops for the `re-frame-pair2` skill family.*
>
> *Read these first (in this order):*
>
> *1. `skills/re-frame-pair-retro2/spec/design.md` — the locked design decisions (L1 through L12). Pillars 1-4 in §2 are non-negotiable. L1 (no re-frame-10x routing) and L2 (no bead filing without approval) are cardinal.*
> *2. `skills/re-frame-pair-retro2/spec/inputs.md` — the canonical inputs the skill leans on.*
> *3. `skills/re-frame-pair2/SKILL.md` + `skills/re-frame-pair2/references/` — the sibling skill the user just exercised. The improver reads the parent's friction surface (ops, recipes, errors, hot-reload-protocol).*
> *4. `skills/re-frame-pair2/spec/design.md` — the sibling's locks. The improver respects these when proposing changes.*
> *5. `skills/re-frame2/SKILL.md` + `skills/re-frame2/spec/design.md` — the application-authoring sibling (relevant for upstream-routing decisions).*
> *6. `skills/re-frame-migration/SKILL.md` — the closest structural sibling with an existing `spec/` triad. Voice / shape mirror this.*
>
> *Then write the skill at `skills/re-frame-pair-retro2/` with this exact file structure:*
>
> ```
> skills/re-frame-pair-retro2/
> ├── SKILL.md                            (~170 lines; conversation guide + 6-step workflow)
> ├── README.md                           (human-facing intro)
> ├── LICENSE                             (MIT)
> ├── package.json                        (npm metadata)
> ├── .claude-plugin/plugin.json          (Claude Code plugin metadata)
> ├── agents/
> │   └── openai.yaml                     (alt-host config for non-Claude operation)
> ├── references/
> │   ├── analysis-lenses.md              (~140 lines; ten root-cause lenses)
> │   ├── known-frictions.md              (~120 lines; recurring pain patterns)
> │   └── issue-template.md               (~90 lines; bead-body template + redaction)
> └── spec/
>     ├── design.md
>     ├── inputs.md
>     └── authoring-prompt.md
> ```
>
> *Every reference is ≤250 lines (target ~120). SKILL.md is ~170 lines (well under Anthropic's 500-line ceiling).*
>
> *SKILL.md walks the six-step analysis workflow:*
>
> *1. Reconstruct the session goal.*
> *2. Build a short timeline of where progress stalled / restarted / detoured.*
> *3. Extract friction (numbered list; present BEFORE root causes).*
> *4. Classify the root cause using the ten lenses (briefly; don't force every finding through every lens).*
> *5. Generate improvements at the right layer (skill / script / runtime / tests / docs / upstream `re-frame2`).*
> *6. Prioritise — return 2-5 grounded improvements + 0-2 bolder ideas.*
>
> *Cardinal guard-rails to bake in (SKILL.md):*
>
> *1. **Always start with session analysis.** Do not jump straight to fixes.*
> *2. **Present friction points before root causes.** Let the user choose which to dig into.*
> *3. **Default to diagnosis, not contribution.** Do not assume the user wants to file a GitHub issue.*
> *4. **Never file a GitHub issue or edit another repo without explicit user approval.***
> *5. **No re-frame-10x routing.** Time-travel + trace consumption ride on re-frame2's native Tool-Pair surfaces.*
> *6. **If the best fix is upstream in `re-frame2`, say so.** File against the right repo.*
>
> *Output format the skill produces (compact retrospective):*
>
> *- `Goal`*
> *- `Observed friction` (numbered)*
> *- `Likely root causes`*
> *- `Improvement ideas` (2-5, each carrying friction / why-not-enough / proposed change / layer / impact)*
> *- `Bolder ideas` (when warranted; clearly labelled)*
> *- `Issue candidates` (only if user asks)*
> *- `Other possibilities` (low-priority leftovers)*
>
> *Locks to preserve verbatim:*
>
> *- **L1 — No re-frame-10x routing.** Cardinal anti-pattern.*
> *- **L2 — Never file a GitHub issue without explicit user approval.** Cardinal guard-rail.*
> *- **L3 — Route the fix to the right repo.** `re-frame-pair2` for tool changes; `re-frame2` for upstream contract changes. Skills file GitHub issues against the target repo — `bd` (beads) is the re-frame2 monorepo's internal tracker and is never invoked from a published skill (rf2-hpkkx baseline; rf2-80grk decision).*
> *- **L10 — No bead-ids in user-facing skill content.***
> *- **L11 — Findings stay local.** Don't commit `ai/` or `findings/`.*
> *- **L12 — Redact secrets before filing.** GitHub-issue drafts strip secrets, tokens, internal URLs, unnecessary local paths. Bodies pass via stdin/here-doc (`gh issue create --body "$(cat /tmp/file)"`), never inline interpolation of transcript-derived text.*
>
> *Frontmatter — the `description` is "pushy" but conversational. Trigger phrases: "how could re-frame-pair2 better support my workflow", "retrospective on a debugging session", "concrete improvement ideas for re-frame-pair2", "draft a bead for re-frame-pair2". The description discriminates against the live-app `re-frame-pair2` skill and the authoring `re-frame2` skill.*
>
> *Voice: tight, diagnostic, conversational. Use evidence-grounded findings, not vibes. Use the analysis-lenses table for classification. Use the impact statement to force specificity.*
>
> *Don't:*
>
> *- Don't propose fixes that route through `re-frame-10x` — L1.*
> *- Don't file beads autonomously — L2.*
> *- Don't reduce every problem to "write more docs". Consider product behaviour, tooling, defaults, instrumentation first.*
> *- Don't confuse a transient local outage with a product gap unless the workflow made recovery harder than it should have.*
> *- Don't propose vague improvements like "better UX" without naming the concrete missing behaviour.*
> *- Don't pressure the user to file anything.*
> *- Don't write `*.md` documentation outside `skills/re-frame-pair-retro2/`.*
> *- Don't commit `ai/` or `findings/` content.*
> *- Don't claim AI authorship anywhere — commits and PR title/body read as Mike Thompson's work.*
> *- Don't include bead-ids in user-facing leaves.*
>
> *Open the PR with title `feat(skills): re-frame-pair-retro2 — pair-session retrospective skill`. PR body lists: the skill structure, the file LoC table, the six-step workflow, the ten lenses, the output format, the relationship to the sibling skills (`re-frame-pair2` — its primary feedback loop; `re-frame2` — for upstream routing).*

## Notes on the reauthoring contract

- The prompt above is a one-shot — feed it to a fresh session, it produces the skill.
- The prompt assumes the session has read access to the re-frame2 repo and the sibling `re-frame-pair2` skill.
- The prompt does **not** ask the session to verify the resulting skill against a real retrospective — Mike reads the PR and exercises the skill on a real session afterwards.
- If `re-frame-pair2`'s surface has changed materially between authoring passes, `references/known-frictions.md` may have stale entries; flag them but don't auto-remove (some entries persist because the pattern is upstream / unaddressable in the pair tool alone).

## When to re-author

- A new common friction pattern emerges (3+ retros surface it) and the existing taxonomy doesn't fit → add a new analysis lens (and update `references/analysis-lenses.md` accordingly), then refresh SKILL.md's step 4 framing.
- The bead-filing process changes (e.g. `bd` tool surface or routing) → re-derive `references/issue-template.md`.
- A new feedback channel becomes load-bearing (e.g. GitHub Issues replaces `bd`) → update L3 routing and `references/issue-template.md`.
- Anthropic skill conventions change materially → reauthor against the new conventions.

Otherwise, edit existing references directly; reauthoring is for major-version updates.
