# Issue template

Use this structure when drafting or filing an improvement. Keep it evidence-based and concise.

The default filing path is a **GitHub issue** against `day8/re-frame2` — the pair tool ships inside that monorepo (`skills/re-frame2-pair/` + `tools/re-frame2-pair-mcp/`). Pair-tool friction and upstream / framework friction both file there; a **`pair-mcp` label** distinguishes pair-tool issues from framework issues. (Tracker boundary: never `bd` — see Filing rules below.)

## Routing first

Before drafting, decide which kind of friction it is — both target `day8/re-frame2`, distinguished by label:

- **pair-tool friction** (`--label pair-mcp`) — friction in the pair tool itself: SKILL.md, scripts, recipes, attach logic, structured results, cross-platform handling.
- **framework friction** (no `pair-mcp` label) — friction caused by a gap in the framework's Tool-Pair contract. Name the specific surface from [`../../shared/tool-pair-surfaces.md`](../../shared/tool-pair-surfaces.md) (e.g. missing trace event category, under-specified `:rf.epoch/*` failure mode, missing registrar query, source-coord shape question, schema-reflection limitation) or a private-namespace reach-through that should be promoted.

When unsure, ask the user. Sometimes both: a tool-side workaround now and an upstream GitHub issue for the long-term fix; cross-link them.

## Title patterns

- `Improve <workflow> when <condition>`
- `Add <op/result/warning> for <workflow>`
- `Fix <platform> behavior in <script/op>`
- `Make <workflow> self-validating instead of manual`
- `Surface <signal> instead of requiring manual reconstruction`
- `Promote <private-ns reach-through> to a public Tool-Pair surface` (upstream)

**Shell-safe titles (same boundary as the body).** The title is passed inline as `--title "<short title>"` — `gh issue create` has no `--title-file` flag, so the file trick that protects the body cannot protect the title. **Author the title yourself from a restricted safe alphabet** — letters, digits, spaces, and `- . , / ( ) :` only — by filling the placeholders above with summarised text. **Never paste a transcript-/evidence-derived string** (a suggested title, a quoted failure message, a recap line) into `--title`: it can carry `$(…)`, backticks, `"`, `\`, or a newline that the shell expands before `gh` sees argv, bypassing the no-interpolation boundary even when the body is safe. Re-read the assembled `--title` in the pre-emission reviewer pass; if any shell metacharacter survived, rewrite it. See [`../../shared/issue-filing.md`](../../shared/issue-filing.md) §Shell-safety: the title is an inline argument.

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
- upstream `day8/re-frame2` issue (Tool-Pair contract, trace event, epoch machinery, schema reflection, source-coord annotation)

## Expected impact

Explain what effort, confusion, or risk this would remove in future sessions.

## Open questions

List any remaining uncertainty, especially if the best fix might belong upstream.
```

## Filing with `gh issue create`

Once the body is drafted and the user has approved, file via the GitHub CLI. Two shell arguments carry session-derived text — the **body** and the **title** — and both must be kept clear of the shell:

- **Body:** never interpolate the transcript-derived body inline (it can carry shell metacharacters the user never sees but the shell would expand). Instead, **write the body to a file with the `Write` tool**, then pass it with `gh`'s native `--body-file` flag — a single `gh issue create` invocation with no `cat` subshell, so it runs under the skill's `Bash(gh issue *)` permission.
- **Title:** `gh` has no `--title-file`, so `--title` is always inline argv. **Author the title yourself from the safe-alphabet patterns above** — never paste an evidence-/transcript-derived string into `--title` (see §Title patterns). In the worked commands below, `<short title>` is a placeholder for that agent-authored safe title, not a slot to paste session text into.

This is the canonical shape from [`../../README.md` §Published-skill `allowed-tools` baseline](../../README.md#published-skill-allowed-tools-baseline-security-policy):

1. Use the `Write` tool to compose `/tmp/issue-body.md` (the drafted body as plain markdown — no shell escaping needed; nothing expands it):

   ```markdown
   ## Problem
   …drawn from the retro transcript…

   ## Evidence from a real session
   …
   ```

2. File it against `day8/re-frame2` with one `gh issue create` command. For **pair-tool friction**, add the `pair-mcp` label so it is distinguishable from framework issues:

   ```bash
   gh issue create \
     --repo day8/re-frame2 \
     --title "<short title>" \
     --body-file /tmp/issue-body.md \
     --label retro,pair-mcp
   ```

For **framework friction** (a gap in the Tool-Pair contract), file against the same repo without the `pair-mcp` label:

```bash
gh issue create \
  --repo day8/re-frame2 \
  --title "<short title>" \
  --body-file /tmp/issue-body.md \
  --label retro,upstream-from-re-frame2-pair
```

`--body-file` reads the body verbatim from disk, so no shell expansion ever touches the transcript-derived text, and the only `Bash` call is a bare `gh issue create` — exactly what the skill's `allowed-tools` grants. The `--title` value is safe only because it is agent-authored from the safe alphabet (§Title patterns), not because it is read from a file.

Always run `gh issue list --repo <owner/repo> --search "<keywords>"` first to check for an existing issue on the same friction; reference it instead of duplicating.

## Filing rules

- File only after explicit user approval.
- **Never interpolate transcript-derived text directly into a shell command.** Use the `Write` tool + `--body-file /tmp/issue-body.md` pattern for the body, and **author the `--title` from the safe alphabet** (§Title patterns) — never paste evidence text into `--title`, `--label`, or `--repo`.
- Redact secrets, tokens, and internal-only details.
- Prefer one issue per distinct improvement.
- Search for an existing issue first: `gh issue list --repo <owner/repo> --search "<keywords>"`.
- Cross-link tool-side and upstream issues when both are filed for the same friction.
- **Tracker boundary** — file GitHub issues against the target repo. Never invoke `bd` from this skill; `bd` is the re-frame2 monorepo's internal tracker.
