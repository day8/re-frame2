# re-frame2-improver — Authoring Prompt

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-improver` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

A self-contained **orchestration** prompt that re-authors the `re-frame2-improver` skill from the declared canonical sources in this `spec/` folder (plus the `re-frame2` skill). It does **not** restate the skill's design — the locks, file layout, leaf catalogue, runtime API facts, and update procedure each have exactly one owner, linked below; this prompt only sequences the read, the deliverables, and the verification. Drop it into a fresh Claude Code session at the re-frame2 repo root.

## The prompt

> *I'm re-authoring the `re-frame2-improver` skill at `skills/re-frame2-improver/` — a critique-mode skill for **existing** re-frame2 ClojureScript code (its goal and success criterion are owned by [`spec/design.md` §1](design.md)). This is a full re-author from the declared sources, not an incremental edit.*
>
> ### Read the canonical sources, in this order
>
> *Read each owner before writing anything; do not paraphrase them into the skill — link to them where the skill needs to point back.*
>
> *1. `skills/re-frame2-improver/spec/design.md` — the **normative design**: pillars (§2), the locked decisions L1–L10 (§3), the six launch leaves + their canonical idioms (§4), the deferred candidates, and the locked file structure (§5). Pillar 1 in §2 (implementation is ground truth — no fabricated APIs) is cardinal.*
> *2. `skills/re-frame2-improver/spec/inputs.md` — the **canonical inputs**: the primary/secondary/tertiary source map, the verified spec-ownership table (§3) every leaf footer and API claim must re-verify against, and the incremental **update procedure** (§6).*
> *3. `skills/re-frame2-improver/references/README.md` — the **catalogue contract**: the anti-pattern index and the locked five-section leaf format. (The load-only-what-matches routing table and the co-occurring-finding consolidation rule live in SKILL.md — routing is one level deep.)*
> *4. `skills/re-frame2/SKILL.md` + `skills/re-frame2/patterns/` + `skills/re-frame2/references/` — the canonical-idiom source of truth every cross-link routes to.*
> *5. `skills/re-frame2-pair-retro/` — the structural sibling sharing the `spec/` triad shape; mirror its voice / structure.*
> *6. `skills/README.md` §Skill routing — the monorepo's trigger matrix. SKILL.md states the sibling boundary in full locally (the packaged install routes without the monorepo); keep the local boundary aligned with this matrix rather than deferring to it.*
>
> ### Deliverables
>
> *Produce the skill at `skills/re-frame2-improver/` such that:*
>
> *- The **file structure** matches `design.md` §5 exactly (SKILL.md, README.md, LICENSE, package.json, `.claude-plugin/plugin.json`, `evals/`, `references/`, `spec/`).*
> *- **SKILL.md** walks the one-pass workflow and carries the trigger semantics + self-anti-patterns; its `allowed-tools` follow L4 (`design.md` §3) — filing is delegated, so no `gh` / issue surface. It carries its own untrusted-evidence boundary and programmer-intent correction contract inline (`design.md` L3/L7) — the packaged normal path is self-contained, with no `../shared/` runtime dependency.*
> *- The **six launch leaves** are exactly those in `design.md` §4, each in the locked five-section format (`design.md` §L5 / `references/README.md`), each cross-linking the canonical idiom `design.md` §4 assigns it and footering to the spec owner `inputs.md` §3 verifies.*
> *- **`SKILL.md`** carries the routing table (signals → leaf, co-occurrence) and the consolidation rule; **`references/README.md`** carries the catalogue index and the leaf format. No SKILL → README → leaf chain.*
> *- **`evals/`** carries `evals.json` (the sole fixture inventory) + its harness README, per the `evals/README.md` schema and the sibling `skills/re-frame2/evals` convention.*
> *- Every **locked decision** L1–L10 (`design.md` §3) holds; do not re-litigate or re-word them.*
>
> ### Verify before you ship
>
> *- **Re-verify every API claim** the leaves cite against the current `spec/` + `implementation/` before writing them — the ownership table is `inputs.md` §3. A fabricated idiom is the cardinal failure for a critique skill (`design.md` §2 pillar 1). Do not cite an API that does not exist in both `spec/` and `implementation/`.*
> *- Confirm SKILL.md, README.md, `references/README.md`, and the evals all describe the **same one-pass programmer-intent contract** (`design.md` L3), and that every normal-operation instruction resolves inside the packaged file set (`package.json` `files[]`).*
> *- Confirm no shipped doc points at the gitignored `ai/` tree (L9), and that commits + PR carry no AI attribution (L10).*
>
> *Open the PR titled `feat(skills): re-frame2-improver — re-frame2 code-critique skill`. In the body, enumerate — by reference to the owners above, not by restating them — the skill structure (`design.md` §5), the six leaves + canonical idioms (`design.md` §4), the trigger semantics (`design.md` §6), the correction contract (`design.md` §L3), and the relationship to the sibling skills (`re-frame2` — canonical-idiom source; `re-frame2-pair-retro` — the structural sibling).*

## Notes on the reauthoring contract

- The prompt is a **one-shot** — feed it to a fresh session at the repo root and it produces the skill from the linked owners.
- It assumes read access to the re-frame2 repo and the `skills/re-frame2/` canonical-idiom source.
- It deliberately **links** the design/inputs/protocol/catalogue owners rather than copying them: the file layout, the locks, the leaf catalogue, the runtime API facts, and the update procedure each live in exactly one place, so a re-author reads the current owner and cannot be fed a stale copy.
- It does **not** ask the session to verify the resulting skill against a real review — Mike reads the PR and exercises the skill on real code afterwards.
- Every API claim MUST be re-verified against the current `spec/` + `implementation/` at authoring time; a fabricated idiom is the cardinal failure for a critique skill.

## Incremental updates vs. full re-author

This prompt is for a **major-version re-author**. For incremental maintenance — a canonical idiom moving, a spec-ownership shift, a new leaf clearing the 3+-review bar — follow the **update procedure owned by [`inputs.md` §6](inputs.md)** and edit the affected leaves directly rather than re-running this prompt.
