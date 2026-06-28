# Issue template

Use this structure when drafting or filing an improvement. Keep it evidence-based and concise.

The default filing path is a **GitHub issue** against `day8/re-frame2` — the pair tool ships inside that monorepo (`skills/re-frame2-pair/` + `tools/re-frame2-pair-mcp/`). Both pair-tool and framework friction file there, distinguished primarily in the title + body, with an optional reinforcing `pair-mcp` label when the repo defines it. **Labels are optional taxonomy, never a filing precondition** (see §Filing with `gh issue create`). Tracker boundary: never `bd` — see §Filing rules.

## Routing first

Before drafting, decide which kind of friction it is — both target `day8/re-frame2`. The distinction is carried in the title + body; the label is an optional reinforcement applied only when the repo defines it (§Filing with `gh issue create`):

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

**Shell-safe titles (same boundary as the body).** `--title` is always inline argv — `gh issue create` has no `--title-file`, so the file trick that protects the body cannot protect the title. **Author the title yourself from a restricted safe alphabet** — letters, digits, spaces, and `- . , / ( ) :` only — by filling the placeholders above with summarised text. **Never paste a transcript-/evidence-derived string** (a suggested title, a quoted failure message, a recap line) into `--title`: it can carry `$(…)`, backticks, `"`, `\`, or a newline the shell expands before `gh` sees argv, bypassing the no-interpolation boundary even when the body is safe. Re-read the assembled `--title` before emitting; rewrite if any shell metacharacter survived. See [`../../shared/issue-filing.md`](../../shared/issue-filing.md) §Shell-safety: the title is an inline argument.

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

Once the body is drafted and the user has approved, file via the GitHub CLI. Three shell arguments carry session-derived text and must be kept clear of the shell — the **body** (via `--body-file`, steps 1-2), the **title**, and the **search query** — both `--title` and `--search` are inline argv with no `-file` equivalent, so author them yourself from the safe alphabet (§Title patterns); in the worked commands below `<short title>` is a placeholder for that agent-authored title, never a slot to paste session text into. This is the canonical shape from [`../../README.md` §Published-skill `allowed-tools` baseline](../../README.md#published-skill-allowed-tools-baseline-security-policy):

1. Use the `Write` tool to compose the body into a **fresh, per-filing temp file in the host OS's temp directory** — never a fixed, predictable name. A hard-coded `/tmp/issue-body.md` fails on hosts without a POSIX `/tmp` (Windows consumer installs), and its predictable name lets concurrent or rapid filings overwrite each other's redacted body (wrong text to GitHub, or sensitive evidence left in a shared location). Pick the OS path, add a per-filing nonce, and **carry that exact path into `--body-file` below**:

   - **POSIX:** `${TMPDIR:-/tmp}/re-frame2-pair-retro-$$-$RANDOM.md`
   - **Windows (PowerShell):** `$env:TEMP\re-frame2-pair-retro-$([guid]::NewGuid()).md`

   The body is plain markdown — no shell escaping needed; nothing expands it:

   ```markdown
   ## Problem
   …drawn from the retro transcript…

   ## Evidence from a real session
   …
   ```

2. File it against the target repo (here `day8/re-frame2`) with one `gh issue create` command. **The labels below are optional, best-effort taxonomy — never a precondition for filing.** `gh issue create` **fails the whole command** if you pass a `--label` the repo does not define, and a consumer's repo (or even this one, today) may not carry `retro` / `pair-mcp` / `upstream-from-re-frame2-pair`. So the **baseline filing command carries no `--label` at all** — it always succeeds:

   ```bash
   gh issue create \
     --repo day8/re-frame2 \
     --title "<short title>" \
     --body-file "<the per-filing temp path you wrote in step 1>"
   ```

   `<the per-filing temp path …>` is the exact nonce-carrying path from step 1 — never re-type a fixed name. Encode the tool-vs-framework routing in the **title and body** (also stated in §Proposed improvement → layer), not solely in a label; the label is a nicety on top.

3. **Only if you want the label taxonomy, add labels that already exist** — never pass an unverified label. Detect first, then pass only the present ones:

   ```bash
   # List the repo's labels once; keep only the ones you intend to apply.
   gh label list --repo day8/re-frame2 --limit 200
   ```

   - **pair-tool friction** — if the repo defines them, add `--label retro,pair-mcp` (drop either token that is absent; omit `--label` entirely if neither exists).
   - **framework friction** — if the repo defines them, add `--label retro,upstream-from-re-frame2-pair` (same degrade rule).

   Worked example for pair-tool friction *when both labels exist*:

   ```bash
   gh issue create \
     --repo day8/re-frame2 \
     --title "<short title>" \
     --body-file "<the per-filing temp path you wrote in step 1>" \
     --label retro,pair-mcp
   ```

   If a `gh issue create --label …` call fails with an unknown-label error, **re-run the no-label baseline command above** rather than treating the retro as failed — the issue must land; the label is optional. (Maintainers who want this taxonomy can create the labels with `gh label create retro …` once; that is a repo-maintenance choice, not a filing prerequisite.)

`--body-file` reads the body verbatim from disk, so no shell expansion touches the transcript-derived text; the only `Bash` call is a bare `gh issue create` / `gh label list` — exactly what the skill's `allowed-tools` grants. `--title`, `--search`, and `--label` are safe only because they are agent-authored from the safe alphabet (§Title patterns) — never paste an evidence-derived string into any of them.

Always run `gh issue list --repo <owner/repo> --search "<keywords>"` first to check for an existing issue on the same friction; reference it instead of duplicating. **Author the `<keywords>` yourself from the safe alphabet (§Title patterns) — `--search` is inline argv with no `--search-file`, so a query pasted from the transcript / a quoted failure string / a suggested title can carry `$(…)`, backticks, `"`, `\`, or a newline the shell expands before `gh` sees it (and leaks the raw evidence to GitHub as the query). Never interpolate evidence text into `--search`.** See [`../../shared/issue-filing.md`](../../shared/issue-filing.md) §Search before filing.

## Filing rules

- File only after explicit user approval.
- **Labels are optional; never let a missing label block filing.** File the no-label baseline by default; add `--label` only after `gh label list` confirms it exists, passing only present tokens; on an unknown-label failure re-run the no-label command. The repo may not define `retro` / `pair-mcp` / `upstream-from-re-frame2-pair`.
- **Never interpolate transcript-derived text into a shell command.** Body via the `Write`-tool + `--body-file` pattern (fresh per-filing OS-temp file, never a fixed `/tmp/issue-body.md`); `--title`, `--search`, `--label`, `--repo` authored from the safe alphabet (§Title patterns), never evidence text.
- Redact secrets, tokens, and internal-only details.
- Prefer one issue per distinct improvement.
- Search for an existing issue first: `gh issue list --repo <owner/repo> --search "<keywords>"` (author `<keywords>` from the safe alphabet — same inline-argv boundary as `--title`).
- Cross-link tool-side and upstream issues when both are filed for the same friction.
- **Tracker boundary** — file GitHub issues against the target repo; never invoke `bd` (the re-frame2 monorepo's internal tracker).
