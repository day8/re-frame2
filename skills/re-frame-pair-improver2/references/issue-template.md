# Bead / issue template

Use this structure when drafting or filing an improvement. Keep it evidence-based and concise.

The default filing path for the re-frame2 ecosystem is `bd` (beads) — both for `re-frame-pair2` (the pair tool) and for `re-frame2` (the framework). The body shape below works equally well as a GitHub issue if a repo prefers that path.

## Routing first

Before drafting, decide the target repo:

- **`re-frame-pair2`** — friction in the pair tool itself: SKILL.md, scripts, recipes, attach logic, structured results, cross-platform handling.
- **`re-frame2`** — friction caused by a gap in the framework's Tool-Pair contract: missing trace event category, under-specified `:rf.epoch/*` failure mode, missing registrar query, source-coord shape question, schema-reflection limitation, private-namespace reach-through that should be promoted.

When unsure, ask the user. Sometimes both: a tool-side workaround now and an upstream bead for the long-term fix; cross-link them.

## Title patterns

- `Improve <workflow> when <condition>`
- `Add <op/result/warning> for <workflow>`
- `Fix <platform> behavior in <script/op>`
- `Make <workflow> self-validating instead of manual`
- `Surface <signal> instead of requiring manual reconstruction`
- `Promote <private-ns reach-through> to a public Tool-Pair surface` (upstream)

## Body

```md
## Problem

Describe the workflow the user was trying to complete and the friction they hit.

## Evidence from a real session

- What happened
- What had to be retried, worked around, or manually verified
- Why the current workflow was slower or less trustworthy than it should have been

## Why re-frame-pair2 was not enough

Explain the missing behavior, missing data, brittle assumption, or misleading contract. If the gap is upstream in re-frame2, name the Tool-Pair surface (or the missing surface) explicitly.

## Proposed improvement

Describe the change concretely. Name the likely layer:
- `SKILL.md`
- script/runtime op
- result/warning shape
- tests/fixture
- upstream `re-frame2` bead (Tool-Pair contract, trace event, epoch machinery, schema reflection, source-coord annotation)

## Expected impact

Explain what effort, confusion, or risk this would remove in future sessions.

## Open questions

List any remaining uncertainty, especially if the best fix might belong upstream.
```

## Filing with bd

Once the body is drafted and the user has approved:

```bash
# In the target repo (re-frame-pair2 or re-frame2)
bd create \
  --title "<title>" \
  --type task \
  --priority <P0|P1|P2|P3> \
  --body "<body>"
```

For an upstream bead against re-frame2:

```bash
cd /c/Users/miket/code/re-frame2
bd create --title "..." --type task --priority P2 --body "..."
```

For a tool-side bead:

```bash
cd /c/Users/miket/code/re-frame-pair2
bd create --title "..." --type task --priority P2 --body "..."
```

Always run `bd list` or `bd ready` first to check for an existing bead on the same friction; reference it instead of duplicating.

## Filing rules

- File only after explicit user approval.
- Redact secrets, tokens, and internal-only details.
- Prefer one bead per distinct improvement.
- Search for an existing bead first (`bd list`, `bd ready`, `bd show <id>`).
- Cross-link tool-side and upstream beads when both are filed for the same friction.
- If the target repo does not have `bd` configured, paste the same body as a GitHub issue and tell the user it is a draft.
