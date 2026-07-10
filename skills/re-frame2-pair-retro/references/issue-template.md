# Issue template

Use this structure when drafting or filing an improvement. Keep it evidence-based and concise. The default filing path is a **GitHub issue** against `day8/re-frame2` — the pair tool ships inside that monorepo (`skills/re-frame2-pair/` + `tools/re-frame2-pair-mcp/`). Both pair-tool and framework friction file there, distinguished in the title + body; labels are optional (the canonical rule is in [`../SKILL.md` §Filing improvements](../SKILL.md#filing-improvements)). Tracker boundary: never `bd`.

**The filing mechanics live once — in the shared recipe, [`../../shared/issue-filing.md`](../../shared/issue-filing.md).** That leaf is the single home for the generic shell-safety core every issue-filing skill shares: search-before-file, the explicit-approval gate, composing the redacted body with the `Write` tool and passing it by file (a fresh temp file in the host OS's temp directory), the safe-alphabet title/search rule, redaction, and the `bd`-never tracker boundary. Read it for *how* to file; **do not restate it here**. This template carries only what is specific to a Pair-retro issue — the routing decision, the title patterns, the target/optional-label policy, and the issue-body skeleton.

## Routing first

Before drafting, decide which kind of friction it is — both target `day8/re-frame2`, and the distinction is carried in the title + body:

- **pair-tool friction** (optional `--label pair-mcp`) — friction in the pair tool itself: SKILL.md, scripts, recipes, attach logic, structured results, cross-platform handling.
- **framework friction** (optional `--label upstream-from-re-frame2-pair`, no `pair-mcp`) — friction caused by a gap in the framework's Tool-Pair contract. Name the specific surface from [`../../shared/tool-pair-surfaces.md`](../../shared/tool-pair-surfaces.md) (e.g. missing trace event category, under-specified `:rf.epoch/*` failure mode, missing registrar query, source-coord shape question, schema-reflection limitation) or a private-namespace reach-through that should be promoted.

When unsure, ask the user. Sometimes both: a tool-side workaround now and an upstream GitHub issue for the long-term fix; cross-link them.

## Title patterns

- `Improve <workflow> when <condition>`
- `Add <op/result/warning> for <workflow>`
- `Fix <platform> behavior in <script/op>`
- `Make <workflow> self-validating instead of manual`
- `Surface <signal> instead of requiring manual reconstruction`
- `Promote <private-ns reach-through> to a public Tool-Pair surface` (upstream)

Fill a pattern with **summarised, agent-written** text; never paste a transcript-/evidence-derived string — a suggested title, a quoted failure message, a recap line — into the title. The full title-safety rule (why the title is an inline shell argument with no file equivalent, the restricted safe alphabet, and the pre-emission reviewer pass) lives in the shared recipe: [`../../shared/issue-filing.md`](../../shared/issue-filing.md) §Shell-safety: the title is an inline argument.

## Body

```md
## Problem

Describe the workflow the user was trying to complete and the friction they hit.

## Evidence from a real session

- What happened
- What had to be retried, worked around, or manually verified
- Why the current workflow was slower or less trustworthy than it should have been

## Why re-frame2-pair was not enough

Explain the missing behavior, missing data, brittle assumption, or misleading contract. If the gap is upstream in re-frame2, name the Tool-Pair surface (or the missing surface) explicitly.

## Proposed improvement

Describe the change concretely. Name the likely layer:
- `SKILL.md`
- script/runtime op
- result/warning shape
- tests/fixture
- upstream `day8/re-frame2` issue (a Tool-Pair surface from [`../../shared/tool-pair-surfaces.md`](../../shared/tool-pair-surfaces.md))

## Expected impact

Explain what effort, confusion, or risk this would remove in future sessions.

## Open questions

List any remaining uncertainty, especially if the best fix might belong upstream.
```

## Filing with `gh issue create`

File per the shared recipe — [`../../shared/issue-filing.md`](../../shared/issue-filing.md) — which owns the whole mechanic: search for an existing issue first, get explicit user approval, compose the redacted body with the `Write` tool, pass it by file, and author the title from the safe alphabet. This section adds only the Pair-retro layer that rides on top of that recipe:

- **Target repo** — always `day8/re-frame2`. Both pair-tool and framework friction land there; the tool-vs-framework distinction is carried in the title + body, not in the repo.
- **Optional labels, never a filing precondition.** The baseline create carries **no `--label`** and always lands (canonical rule: [`../SKILL.md` §Filing improvements](../SKILL.md#filing-improvements)). Only to add the taxonomy, detect the repo's labels first — never pass an unverified one:

   ```bash
   gh label list --repo day8/re-frame2 --limit 200
   ```

   - **pair-tool friction** — if present, add `--label retro,pair-mcp` (drop any absent token; add no `--label` if neither exists).
   - **framework friction** — if present, add `--label retro,upstream-from-re-frame2-pair` (same degrade rule).

   If a labelled create fails with an unknown-label error, re-run the no-label baseline create — the issue must land; the label is optional.

## Filing rules

The generic rules — file only after explicit user approval, never interpolate transcript-derived text into a shell command, redact secrets/tokens/internal paths, and never invoke `bd` — are owned by [`../../shared/issue-filing.md`](../../shared/issue-filing.md); follow them there. The Pair-retro-specific rules:

- **Labels are optional; a missing label never blocks filing** — baseline is a no-label create; add `--label` only after `gh label list` confirms it (see §Filing with `gh issue create` above and [`../SKILL.md` §Filing improvements](../SKILL.md#filing-improvements)).
- **Cross-link the tool-side and upstream issues** when both are filed for the same friction, so a reader of either finds the other.
