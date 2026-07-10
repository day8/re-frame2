# re-frame2 — Authoring Prompt

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

A self-contained prompt that re-authors the `re-frame2` skill from this `spec/` folder alone. Drop into a fresh Claude Code session in the re-frame2 repo root.

**Everything normative lives in [`design.md`](design.md) and [`inputs.md`](inputs.md).** The locked decisions (L1–L11), the file structure, the cardinal rules, the line budgets, and the discovery surface are owned by `design.md`; the canonical inputs and the per-surface update procedure are owned by `inputs.md`. This prompt orchestrates the reauthoring pass — it does **not** restate the tree, the rules, or the locks (a second copy is exactly what drifts).

## The prompt

> *I'm re-authoring the `re-frame2` skill at `skills/re-frame2/`. The skill teaches an AI to write working re-frame2 ClojureScript application code while spending as little context as possible. It is **authoring-only** — writing new code on the CLJS reference. Adjacent skills handle setup (`re-frame2-setup`), v1→v2 migration (`re-frame-migration`), live-app inspection (`re-frame2-pair`), and porting re-frame2 itself (`re-frame2-implementor`).*
>
> *Read these first, in order. The first two are **normative** — the design and inputs I must reproduce, not re-derive — and I must not paste a divergent copy of anything they own into this prompt or the skill:*
>
> *1. **[normative]** `skills/re-frame2/spec/design.md` — the locked design decisions (L1–L11), the four pillars (§2, non-negotiable; Q14 lock applies), the locked file structure and line budgets (§3 Pillar 3 / §5), and the discovery surface (§6). Sole source for the layout, the cardinal rules, and the locks.*
> *2. **[normative]** `skills/re-frame2/spec/inputs.md` — the canonical inputs the leaves lean on (`implementation/**`, `examples/**`, `spec/**` for rationale) and the §6 per-surface update procedure.*
> *3. `skills/README.md` — the family conventions the skill obeys: the single-source skill-routing table (§Skill routing) that SKILL.md's "When NOT to use" points at, the leaf-size discipline (§Leaf size discipline), and the published-skill `allowed-tools` baseline.*
> *4. `implementation/core/src/re_frame/core.cljc` + `frame.cljc` + `fx.cljc` + `events.cljc` + `subs.cljc` + `test_support.cljc` — the surfaces the skill teaches. Every code snippet in a leaf is verified against these (L1 — implementation is ground truth).*
> *5. `examples/core/{counter,login,managed_http_counter}/` + `examples/patterns/{boot,nine_states}/` — the worked examples the pattern leaves match (L2).*
> *6. `skills/re-frame-migration/SKILL.md` + `skills/re-frame2-implementor/SKILL.md` — the voice / density / load-bearing-rules style to mirror.*
> *7. `spec/000-Vision.md` + `spec/Conventions.md` — the AI-first design principles and naming conventions.*
>
> *Then write the skill at `skills/re-frame2/` to the locked file structure in `design.md` §5, baking the cardinal rules into `SKILL.md` and honouring the L1–L11 locks verbatim (`design.md` §3) — do not restate the tree, the rules, or the locks here. Match the line budgets and the one-level-deep routing locked in `design.md` (§3 Pillar 3 / §5; no `SKILL → A → B` chains) and the "pushy" discovery `description` specified in `design.md` §6.*
>
> *Voice: tight, declarative, recipe-shaped — mirror the sibling skills in read 6. Tables for routing; code blocks for canonical shapes; cite `implementation/<file>:<line>` where a surface claim might surprise an AI working from training memory. Quote spec text for rationale only, never for API surface (L1).*
>
> *Constraints (`design.md` §3 owns the full lock text — honour it, don't restate a divergent copy): implementation is ground truth over spec (L1); no issue-tracker ids in the user-facing leaves (L10); findings stay local — the skill's commits contain only `skills/re-frame2/**`, never `ai/` or `findings/` (L11); no verification leaf and no verify-before-done hard rule (L3 / Q14 — the author runs the tests). Repo git convention: commits and PR title/body read as Mike Thompson's work — no AI-authorship attribution.*
>
> *Open the PR with title `feat(skills): re-frame2 — authoring-only skill for writing re-frame2 CLJS code`. PR body lists: the skill structure, the file LoC table, the locks applied, and the relationship to the adjacent skills (setup / migration / re-frame2-pair / implementor).*

## Notes on the reauthoring contract

- The prompt above is a one-shot — feed it to a fresh session, it produces the skill.
- The prompt assumes the session has read access to the repo. It does not assume any out-of-repo context.
- The prompt does **not** ask the session to verify the resulting skill — Mike reads the PR and comments (`design.md` L3 / Q14).
- If `implementation/**` has changed significantly between passes, the leaves' code snippets need re-verification. A reauthoring session walks the implementation afresh; `inputs.md` §6 owns the per-surface update procedure for incremental changes.

## When to re-author

- A new registry kind ships in re-frame2 (e.g. a new `reg-X` surface) → the existing leaves' organisation may be wrong; rebuild the routing.
- A new canonical pattern is named in `spec/Pattern-*.md` → add to `patterns/` and `decision-trees/pick-a-pattern.md`; add the row to SKILL.md's pattern table and to `examples-map.md` if a worked example exists.
- The Q14 lock or any of L1–L11 changes → the design itself changes; update `design.md` first, then the skill.
- Anthropic skill conventions change materially → reauthor against the new conventions.

Otherwise, edit the existing leaves directly (`inputs.md` §6 is the per-surface procedure); reauthoring from scratch is for major-version updates.
