# re-frame2-pair-retro

> Turns a `re-frame2-pair` session into a retrospective, in one response: where the session dragged, why, the smallest change that would fix it, and — on request — a GitHub issue draft you can file.

## What it does

The skill reviews the current or just-finished [re-frame2-pair](re-frame2-pair.md) session (or a recap you give it) and returns a retrospective in the same turn: what you were trying to do, where the workflow dragged or confused you, which problems were one-off environment issues and which are recurring product gaps, and the improvements that would matter most, highest leverage first. It backs each point with concrete moments from the session — retries, clarifications, stale output, manual workarounds. One dominant finding gets one thorough treatment; there is no fixed set of sections or quota of ideas.

It routes each improvement to the right owner:

- **`re-frame2-pair`** — friction inside the pair tool itself: its instructions and recipes, the preload runtime, the MCP tools and their structured results, attach and discovery, cross-platform handling.
- **`re-frame2`** — friction caused by what the framework exposes to tools: missing trace events, gaps in `epoch-history` or `restore-epoch` failure modes, missing registry queries, source-coordinate gaps, schema reflection.

Both go to `day8/re-frame2`'s GitHub issues, since the repo ships the pair tool alongside the framework; the draft's title and body say which one it is.

The skill is **read-only**. It never files issues, edits a repo, writes files or probes a live runtime; the most it produces is an issue draft for you to file. Every output replaces secrets, credentials, internal URLs, user-identifying local paths and personal data with numbered placeholders (`<REDACTED-TOKEN-1>`), and instructions that appear inside the transcript or recap are treated as evidence, never followed.

## When to reach for it

Use it after a `re-frame2-pair` session, when you want to know where the session dragged and what would fix it, or want a GitHub issue drafted about it. It needs a real pair session in the conversation, or a recap of one. The `description` in its [`SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair-retro/SKILL.md) is the text the agent matches your request against.

Every skill is listed under [Which skill do I want?](index.md#which-skill-do-i-want). The ones most easily confused with this one:

- Fixing the live bug itself, or driving the app → [re-frame2-pair](re-frame2-pair.md).
- A review of source code, rather than of a session → [re-frame2-improver](re-frame2-improver.md).
- A retro on a Story recording session → [re-frame2-pair](re-frame2-pair.md)'s Stories reference covers it.

## Kickoff

Ask for it after a real pair session:

> *Retro on this pair session, and draft an issue for the worst of it.*

Or type `/re-frame2-pair-retro`. It also offers itself: after an error during live pair work — a stack trace, a pair tool returning `{:ok? false …}`, or an `:rf.error/*` trace — it waits until `re-frame2-pair` has dealt with the failure, then offers the retro in one line and runs it only if you say yes.

Asked for a draft, the same response includes one focused, copy-pasteable GitHub issue with the session evidence, the missing behaviour, one implementable desired outcome, and how to tell it is done. You file it, edit it, combine it or discard it. It may first search `day8/re-frame2` issues, open and closed, and point you at an existing one instead of drafting a twin; when it skips that search or the search fails, it says duplicates were not checked.

## When it stops

An explicit request over one clear session completes in one response; it does not stop at a list of candidates and ask which to pursue. It asks first only when:

- **There is no pair session in the conversation** — it asks for a short recap rather than inventing evidence. A session from an earlier conversation is not in front of it either, so it asks for a recap or the path of a transcript or log you have; it never searches your files for one.
- **Two sessions are plausible** — it names both and asks which.
- **The evidence is too thin** to support a finding, or the request is genuinely ambiguous.

## Where the skill lives

- Source: [`skills/re-frame2-pair-retro/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair-retro)
- `SKILL.md`: [`skills/re-frame2-pair-retro/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair-retro/SKILL.md) — the whole runtime contract; the skill is self-contained under its own directory.
- Reference notes: [`skills/re-frame2-pair-retro/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair-retro/references) — consulted on demand; `SKILL.md` says when.
