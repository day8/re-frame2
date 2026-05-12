# re-frame-pair-improver2

> Turns a `re-frame-pair2` session into a product retrospective. Surfaces friction, classifies root causes, proposes 2–5 concrete improvements, and (only on explicit approval) drafts or files beads against the right repo.

## What it does

The `re-frame-pair-improver2` skill is a **meta-skill** for `re-frame-pair2`. It reads the current or just-finished pair-programming session and turns it into a focused retrospective: what was the user actually trying to do; where did the workflow drag, confuse, or frustrate; which problems were one-off environment issues vs recurring product gaps; and 2–5 concrete improvement ideas, prioritized by leverage, including 1–2 bolder ideas when reshaping the workflow would materially improve it.

The skill is **diagnosis first**, contribution second. It presents friction points before root causes, lets the user steer which ones to dig into, and never files a bead or edits another repo without explicit approval. Improvements are routed to the right layer:

- **`re-frame-pair2`** — friction inside the pair tool itself (SKILL.md, scripts, recipes, structured results, attach/discovery brittleness, cross-platform handling).
- **`re-frame2`** — friction caused by the framework's Tool-Pair contract (missing trace events, gaps in `epoch-history` or `restore-epoch` failure modes, missing registrar query surfaces, source-coord annotation gaps, schema-reflection shortcomings).

Working style: prefer evidence over vibes (cite concrete moments — retries, clarifications, stale outputs, manual workarounds); separate symptom from cause; notice both direct friction (the user said something was frustrating) and indirect (repeated commands, fallbacks to lower-level tools); be creatively ambitious *after* the diagnosis is clear, labelling speculative ideas plainly. Time-travel and trace-stream consumption stay on re-frame2's own surfaces — no proposals route through `re-frame-10x`.

## When to reach for it

Load this skill when:

- The user asks how `re-frame-pair2` could better support their workflow.
- The user wants a retrospective on a debugging or pairing session that just happened.
- The user wants concrete improvement ideas or bead drafts for `re-frame-pair2`.

Do **not** use this skill for:

- Inspecting / debugging a live app → that's [re-frame-pair2](re-frame-pair2.md) itself.
- Writing new re-frame2 code → use [re-frame2](re-frame2.md).
- Greenfield setup or v1 migration → use [re-frame2-setup](re-frame2-setup.md) or [re-frame-migration](re-frame-migration.md).

## Kickoff

The skill auto-triggers on retrospective-shaped questions ("how could pair2 do this better?", "what could be improved here?"). To force-load:

```
/skill re-frame-pair-improver2
```

The skill's analysis workflow runs in six steps: reconstruct the session goal, build a short timeline of stalls / restarts / detours, extract a numbered friction list (and ask the user which to dig into), classify root causes through ten lenses (skill structure / skill gap / misleading docs / missing structured op / unreliable op / default or fallback / platform bug / validation gap / upstream limitation / context-window issue), generate improvements at the right layer, and prioritize down to 2–5.

Output is a compact retrospective with sections — `Goal`, `Observed friction`, `Likely root causes`, `Improvement ideas`, optional `Bolder ideas`, optional `Bead candidates`. The skill drafts bead bodies on request, but never files without explicit approval.

## Where the skill lives

- Source: [`skills/re-frame-pair-improver2/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-pair-improver2)
- `SKILL.md`: [`skills/re-frame-pair-improver2/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-pair-improver2/SKILL.md)
- Reference leaves: [`skills/re-frame-pair-improver2/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-pair-improver2/references) — `analysis-lenses.md` (taxonomy when a session has multiple plausible causes), `known-frictions.md` (recurring classes of `re-frame-pair2` pain — useful for sanity-checking whether a friction is one-off or a pattern), `issue-template.md` (bead-body structure).
- Companion skill: [`re-frame-pair2`](re-frame-pair2.md) — the skill this one retrospects.
