# re-frame2-implementor — Authoring Prompt

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-implementor` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

A self-contained prompt that re-authors the `re-frame2-implementor` skill from this `spec/` folder alone. Drop into a fresh Claude Code session in the re-frame2 repo root.

## The prompt

> *I'm re-authoring the `re-frame2-implementor` skill at `skills/re-frame2-implementor/`. The skill guides an engineer building a NEW re-frame2 implementation in one of the eight in-scope JS-cross-compile-to-React+VDOM hosts — not application authoring, not v1→v2 migration. The skill is **workflow + guidance layered on the pinned spec and live conformance fixtures** — it routes into the contract, it never mirrors it: no leaf may carry a fixture count, capability catalogue, or operator list that the corpus can answer at the pin.*
>
> *Read these first (in this order):*
>
> *1. `skills/re-frame2-implementor/spec/design.md` — the locked decisions L1–L10 (L3 is the verification posture: the agent runs the port's discovered noninteractive gates itself; interactive/visual evidence is a handoff) and §6, the reduction rationale.*
> *2. `skills/re-frame2-implementor/spec/inputs.md` — the canonical inputs, and §4's list of repo gates the skill is wired into (two filename constraints and the literal provenance commands/safety clauses that MUST survive).*
> *3. `spec/000-Vision.md`, `spec/Implementor-Checklist.md`, `spec/conformance/README.md` — the three load-bearing spec files.*
> *4. `skills/README.md` — the leaf-size discipline (§Leaf size discipline), the allowed-tools baseline, the verification-posture table.*
>
> *Then write the skill with this exact file structure:*
>
> ```
> skills/re-frame2-implementor/
> ├── SKILL.md (router; lean — two phases, cardinal one-liners, verification, checkpoints, kickoff, done, leaf list)
> ├── README.md (human-facing intro)
> ├── LICENSE / package.json / .claude-plugin/plugin.json (distribution triad — unchanged shape)
> ├── evals/evals.json (trigger-only fixtures — keep)
> └── references/
> ├── cardinal-rules.md (the eleven rules + anti-pattern corollaries; §8 keeps the full gh-issue shell-safety recipe verbatim)
> ├── phase-1-decisions.md (the port profile: no-interview defaults, spec pin with the literal `git -C … remote get-url origin` command, the compact profile template, what is NOT in the profile)
> ├── phase-2-impl-order.md (the EP loop: step-0 harness bootstrap with the fail-loud floors, the six-step loop, the foundation + optional EP index tables, cross-cutting obligations, who runs the gates)
> └── conformance.md (derive-at-the-pin greps, the harness's four fail-loud floors, §Capability tagging with the three v1-required families, the two out-of-claim flavours, static hosts, diagnosis, reporting)
> ```
>
> *Cardinal rules to bake into SKILL.md (one-liners; full text in cardinal-rules.md): 1 spec-is-the-contract + pin-before-reading; 2 profile before code; 3 dependency order 001 → 002 → 006 → views → 009 → 015 with acceptance gate 1 running every fixture applicable to `:core/*` + `:identity/*` + `:data-classification/*`; 4 substrate-agnostic phrasing; 5 no core.async; 6 JVM-runnability of the pure test surface; 7 conformance is the acceptance test; 8 spec gaps: search, draft, ask before filing — never `bd`; 9 per-issue approval for cross-repo writes; 10 the reserved `:rf/*` scheme with the three-fx carve-out; 11 one path algebra, one canonical identity.*
>
> *Locks (from `spec/design.md`) to preserve: L1 spec-is-contract (no tour leaf — the reference source at the pin is consulted directly); L2 two phases without ceremony (no interview, no dossier, no per-EP session/commit/report mandates); L3 the verification posture above; L4 substrate-agnostic voice; L5 the corpus as both acceptance test and working loop (harness seam bootstraps at step 0; fail loud on unknown spec versions / capabilities / ops); L6 upstream-issue spec gaps; L7 no bead-ids in user-facing leaves; L8 findings stay local; L9 no AI attribution; L10 cross-link bidirectionally.*
>
> *Hard tooling constraints (spec/inputs.md §4): keep the filenames `references/cardinal-rules.md`, `references/phase-1-decisions.md`, `references/phase-2-impl-order.md`; keep the literal spec-pin commands and the gh-issue body/title/search safety clauses; every line stating the foundation order includes 015, and every line pinning the gate-1 scope names all three v1-required families; run `scripts/check_skill_implementor_order.py` and `scripts/check_skill_implementor_partition_drift.py` (both `--self-test` then `--verbose --ci`) plus `scripts/check_skill_mcp_drift.py --verbose --ci` before opening the PR.*
>
> *Voice: tight, declarative, workflow-shaped. Leaf sizes are `skills/README.md` §Leaf size discipline's to state — don't restate them here. Don't duplicate `spec/` content — reference it by URL and teach the derivation greps. Don't write a verification leaf. Don't reference rf2-XXXX bead ids in user-facing content. Don't claim AI authorship anywhere.*

## Notes on the reauthoring contract

- The prompt is a one-shot — feed it to a fresh session in the repo root; it produces the skill.
- Verification of the result is Mike's (read the files, comment on the PR); the repo gates named above are the mechanical floor.
- If `spec/` has reorganised between passes, the session sweeps the leaves' spec URLs against the new layout.

## When to re-author from scratch

- `spec/` reorganises significantly (renamed EP files, several new EPs) → rebuild the URL surface from the new layout.
- The two-phase shape or the L3 verification posture changes → update `spec/design.md` first, then the skill.
- Otherwise, edit the existing leaves directly; reauthoring from scratch is for major-version updates.
