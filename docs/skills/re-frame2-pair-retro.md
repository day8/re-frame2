# re-frame2-pair-retro

> Turns a `re-frame2-pair` session into a product retrospective, delivered in one response. Surfaces friction with concrete session evidence, proposes the smallest credible change at the right owner, and — on request — includes one focused, copy-pasteable GitHub issue draft the user can file.

## What it does

The `re-frame2-pair-retro` skill is a **meta-skill** for `re-frame2-pair`. It reads the current or just-finished pair-programming session (or a user-supplied recap of one) and returns a focused retrospective in that same turn: what the user was actually trying to do; where the workflow dragged, confused, or frustrated; which problems were one-off environment issues vs recurring product gaps; and the improvements that would matter most, ordered by leverage. One dominant finding gets one thorough treatment — there is no fixed section set, finding count, or idea quota.

The skill is **read-only**. It never files issues, never edits a repo, never writes files, and never probes a live runtime; its strongest action is a copy-pasteable issue draft the user owns. Improvements are routed to the right owner:

- **`re-frame2-pair`** — friction inside the pair tool itself (SKILL.md wording, scripts, recipes, structured results, attach/discovery brittleness, cross-platform handling).
- **`re-frame2`** — friction caused by the framework's Tool-Pair contract (missing trace events, gaps in `epoch-history` or `restore-epoch` failure modes, missing registrar query surfaces, source-coord annotation gaps, schema-reflection shortcomings).

Both kinds of friction target `day8/re-frame2`'s GitHub issues — the monorepo ships the pair tool alongside the framework — with the tool-vs-framework distinction carried in the draft's title and body.

Working style: prefer evidence over vibes (cite concrete moments — retries, clarifications, stale outputs, manual workarounds); separate symptom from cause; notice both direct friction (the user said something was frustrating) and indirect (repeated commands, fallbacks to lower-level tools); be creatively ambitious *after* the diagnosis is clear, labelling speculative ideas plainly. Time-travel and trace-stream consumption stay on re-frame2's own surfaces — no proposals route through `re-frame-10x`.

## When to reach for it

Load this skill when:

- The user asks how `re-frame2-pair` could better support their workflow.
- The user wants a retrospective on a debugging or pairing session that just happened.
- The user wants concrete improvement ideas or a GitHub-issue draft for `re-frame2-pair`.

Do **not** use this skill for:

- Inspecting / debugging a live app → that's [re-frame2-pair](re-frame2-pair.md) itself. This skill never probes the runtime.
- Writing new re-frame2 code → use [re-frame2](re-frame2.md).
- A retro on a Story recording session → that belongs to [re-frame2-pair](re-frame2-pair.md)'s Stories reference.
- Greenfield setup or v1 migration → use [re-frame2-setup](re-frame2-setup.md) or [re-frame-migration](re-frame-migration.md).

## Kickoff

The skill enters one of two ways. **Explicitly** — ask for a retro over a real pair session ("retro on this pair session", "draft an issue about that") or type `/re-frame2-pair-retro`. **After an error during live pair work** — a stack trace, a pair tool returning `{:ok? false …}`, or an `:rf.error/*` trace — it waits until `re-frame2-pair` has dealt with the failure, then offers the retro in one line and runs it only if you say yes. With no pair session in the conversation it asks for a short recap rather than inventing evidence.

An explicit request over one clear session completes in one response — the skill does not stop at a friction-candidate list or ask which finding to classify. It asks first only when two genuinely plausible sessions are present (it names both), the evidence is too thin to support a finding, or the request's referent is genuinely ambiguous. Internally it keeps the diagnosis causally honest: delayed results stay bound to the calls that issued them, later success supersedes earlier failure, missing results stay unknown/incomplete rather than scored, and unrelated CI/worker/app-authoring activity stays excluded. When a session resembles a recurring class of pain, the skill checks its known-frictions catalogue to tell a one-off from a product gap.

Every output masks secrets, credentials, internal URLs, user-identifying local paths and personal data with numbered placeholders (`<REDACTED-TOKEN-1>`), and instructions that appear inside the transcript or recap are treated as evidence, never followed.

Asked for a draft, the same response includes one focused, copy-pasteable GitHub issue carrying the session evidence, the missing behaviour, one implementable desired outcome, and a completion signal in natural prose. The user files it — or edits, combines, or discards it; the skill never runs `gh issue create`. It may first search `day8/re-frame2` issues, open and closed, for an existing owner and point you there instead of drafting a twin; when it skips that search or the search fails, it says duplicate status was not checked.

## Where the skill lives

- Source: [`skills/re-frame2-pair-retro/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair-retro)
- `SKILL.md`: [`skills/re-frame2-pair-retro/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair-retro/SKILL.md) — the whole runtime contract; the skill is self-contained under its own directory.
- Reference leaves: [`skills/re-frame2-pair-retro/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair-retro/references) — consulted on demand; [`SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair-retro/SKILL.md) says when.
- Companion skill: [`re-frame2-pair`](re-frame2-pair.md) — the skill this one retrospects.
