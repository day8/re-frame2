# Issue template

Use this structure when drafting or filing an improvement. Keep it evidence-based and concise.

The default filing path is a **GitHub issue** against the target repo — `re-frame-pair2` for pair-tool friction, `re-frame2` for upstream / framework friction. `bd` (beads) is the re-frame2 monorepo's internal tracker; this skill never invokes it.

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

## Filing with `gh issue create`

Once the body is drafted and the user has approved, file via the GitHub CLI. **Always pass the body through a file or stdin** — never interpolate the transcript-derived body inline (it can carry shell metacharacters the user never sees but the shell would expand). The canonical shape is the here-doc + `--body "$(cat …)"` pattern from [`../../README.md` §Published-skill `allowed-tools` baseline](../../README.md#published-skill-allowed-tools-baseline-security-policy):

```bash
# Write the body to a temp file (single-quoted here-doc — keeps $, `, and \ literal):
cat > /tmp/issue-body.md <<'EOF'
## Problem
…drawn from the retro transcript…

## Evidence from a real session
…
EOF

# File against the target repo's issues:
gh issue create \
  --repo day8/re-frame-pair2 \
  --title "<short title>" \
  --body "$(cat /tmp/issue-body.md)" \
  --label retro
```

For an upstream issue against re-frame2:

```bash
gh issue create \
  --repo day8/re-frame2 \
  --title "<short title>" \
  --body "$(cat /tmp/issue-body.md)" \
  --label retro,upstream-from-pair2
```

Always run `gh issue list --repo <owner/repo> --search "<keywords>"` first to check for an existing issue on the same friction; reference it instead of duplicating.

## Filing rules

- File only after explicit user approval.
- **Never interpolate transcript-derived text directly into a shell command.** Use the here-doc + `--body "$(cat /tmp/file)"` pattern above — single-quoted `<<'EOF'` delimiter so `$`, `` ` ``, and `\` stay literal.
- Redact secrets, tokens, and internal-only details.
- Prefer one issue per distinct improvement.
- Search for an existing issue first: `gh issue list --repo <owner/repo> --search "<keywords>"`.
- Cross-link tool-side and upstream issues when both are filed for the same friction.
- **Tracker boundary** — file GitHub issues against the target repo. Never invoke `bd` from this skill; `bd` is the re-frame2 monorepo's internal tracker.
