# Issue template

Use this structure when drafting or filing an improvement. Keep it evidence-based and concise. The default filing path is a **GitHub issue** against `day8/re-frame2` — the pair tool ships inside that monorepo (`skills/re-frame2-pair/` + `tools/re-frame2-pair-mcp/`). Both pair-tool and framework friction file there, distinguished in the title + body; labels are optional (the canonical rule is in [`../SKILL.md` §Filing improvements](../SKILL.md#filing-improvements)). Tracker boundary: never `bd`.

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

**Shell-safe titles.** `gh issue create` has no `--title-file`, so `--title` is always inline argv — the file trick that protects the body cannot protect it. Author the title yourself from a restricted **safe alphabet** (letters, digits, spaces, and `- . , / ( ) :` only) by filling the patterns above with summarised text; never paste a transcript-/evidence-derived string (a suggested title, a quoted failure message, a recap line) into `--title` — it can carry `$(…)`, backticks, `"`, `\`, or a newline the shell expands before `gh` sees argv. Full threat model: [`../../shared/issue-filing.md`](../../shared/issue-filing.md) §Shell-safety: the title is an inline argument.

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

Once the body is drafted and the user has approved, file via the GitHub CLI. The shared shell-safety core — why the body goes through `--body-file`, the safe-alphabet `--title` / `--search` rule, redaction — lives once in [`../../shared/issue-filing.md`](../../shared/issue-filing.md); the worked pair-retro recipe is below.

1. Use the `Write` tool to compose the body into a **fresh, per-filing temp file in the host OS's temp directory** — never a fixed, predictable shared path (a hard-coded file under `/tmp` breaks on hosts without a POSIX `/tmp`, and its predictable name lets concurrent or rapid filings overwrite each other's redacted body — wrong text to GitHub, or sensitive evidence left in a shared location). Pick the OS path, add a per-filing nonce, and carry that exact path into `--body-file` below:

   - **POSIX:** `${TMPDIR:-/tmp}/re-frame2-pair-retro-$$-$RANDOM.md`
   - **Windows (PowerShell):** `$env:TEMP\re-frame2-pair-retro-$([guid]::NewGuid()).md`

   The body is plain markdown — nothing expands it.

2. File it against the target repo with one `gh issue create` command. **Labels are optional, best-effort taxonomy — never a precondition** (canonical rule: [`../SKILL.md` §Filing improvements](../SKILL.md#filing-improvements)): `gh issue create` fails the whole command on a `--label` the repo does not define, so the **baseline command carries no `--label`** and always lands. Encode the tool-vs-framework routing in the title + body, not solely in a label:

   ```bash
   gh issue create \
     --repo day8/re-frame2 \
     --title "<short title>" \
     --body-file "<the per-filing temp path you wrote in step 1>"
   ```

   `<short title>` is the agent-authored safe-alphabet title (§Title patterns); `<the per-filing temp path …>` is the exact nonce-carrying path from step 1, never a re-typed fixed name.

3. **Only to add the label taxonomy, pass labels that already exist** — detect first, never pass an unverified label:

   ```bash
   gh label list --repo day8/re-frame2 --limit 200
   ```

   - **pair-tool friction** — if present, add `--label retro,pair-mcp` (drop any absent token; omit `--label` if neither exists).
   - **framework friction** — if present, add `--label retro,upstream-from-re-frame2-pair` (same degrade rule).

   If a labelled create fails with an unknown-label error, **re-run the no-label baseline command** — the issue must land; the label is optional.

Always search for an existing issue on the same friction first, and reference it instead of duplicating:

```bash
gh issue list --repo <owner/repo> --search "<keywords>"
```

**Author the `<keywords>` yourself from the safe alphabet (§Title patterns).** `--search` is inline argv with no `--search-file`, so a query pasted from the transcript / a quoted failure string / a suggested title can carry `$(…)`, backticks, `"`, `\`, or a newline the shell expands before `gh` sees it (and it leaks the raw evidence to GitHub as the query). Never interpolate evidence text into `--search`. See [`../../shared/issue-filing.md`](../../shared/issue-filing.md) §Search before filing.

## Filing rules

- File only after explicit user approval.
- **Labels optional; never let a missing label block filing** — baseline is a no-label create; add `--label` only after `gh label list` confirms it (see [`../SKILL.md` §Filing improvements](../SKILL.md#filing-improvements)).
- **Never interpolate transcript-derived text into a shell command** — body via `Write` + `--body-file` (a fresh per-filing OS-temp file); `--title`, `--search`, `--label`, `--repo` authored from the safe alphabet (§Title patterns), never evidence text.
- Redact secrets, tokens, and internal-only details; prefer one issue per distinct improvement.
- Cross-link tool-side and upstream issues when both are filed for the same friction.
- **Tracker boundary** — file GitHub issues against the target repo; never invoke `bd` (the re-frame2 monorepo's internal tracker).
