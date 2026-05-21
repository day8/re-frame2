# Dispatch Prompt Template

Canonical worker-prompt shapes for safe delegation. Placeholders:

- `<MAYOR_CHECKOUT>` — absolute path of the mayor's primary checkout
- `<WORKTREE_ROOT>` — root holding worker worktrees (sibling of the mayor checkout)
- `<ASSIGNED_WORKTREE>` — the worktree this worker should edit (subdir of `<WORKTREE_ROOT>`)
- `<BEAD_ID>` — the bead identifier

## Worktree boundary — mandatory in every editing dispatch

**Why this block exists.** Shell commands use `workdir`, but `apply_patch` and
some edit tools have no workdir, so relative patch paths can resolve against
the mayor checkout instead of the assigned worktree. "Use your worktree" is
not a strong enough prompt. Workers must verify before each edit and check
both checkouts after the first one.

**The path-resolution leak failure mode.** Observed repeatedly in audit
and PowerShell-tool dispatches: the worker
correctly ran the worktree guard and got the right `WORKTREE_ROOT`, then a
file `Write` (especially of a new `ai/findings/<name>.md` file) landed in the
mayor checkout because the edit tool resolved a repo-relative path against
the agent's session root instead of the worker's git root. Symptoms:

- `git status` inside the worker worktree shows no new findings file.
- `git status` inside the mayor checkout shows a new untracked file under
  `ai/findings/` that the worker thinks it wrote to its worktree.
- The findings file is gitignored so neither side commits it — the boundary
  fails *silently*.

**Defence:** any `Write` of a brand-new file (especially under `ai/findings/`)
must be IMMEDIATELY followed by `Test-Path <ASSIGNED_WORKTREE>\<rel>` AND
`Test-Path <MAYOR_CHECKOUT>\<rel>`. If the mayor-side Test-Path returns True,
the worker has leaked and must stop without further edits.

**Paste verbatim into every editing-worker prompt:**

```text
WORKTREE BOUNDARY - MANDATORY

Your assigned worktree is:
<ASSIGNED_WORKTREE>

The mayor checkout is:
<MAYOR_CHECKOUT>

Never edit the mayor checkout.

Shell workdir is not enough protection. apply_patch and Write tools have no
workdir, so relative patch / write paths can land in the mayor checkout.
This is the path-resolution leak failure mode — the
worktree guard at session start does NOT catch it, because the leak happens
mid-session in a single tool call.

Before every file edit, run:

Get-Location; git rev-parse --show-toplevel; git status --short --branch

Only edit if git rev-parse --show-toplevel prints exactly:
<ASSIGNED_WORKTREE>

When using apply_patch, Write, or any edit tool, use ABSOLUTE file paths
under <ASSIGNED_WORKTREE> wherever the tool accepts them. If the tool only
accepts relative paths, use paths relative to the session root that
explicitly target the worker worktree —
<WORKTREE_ROOT>/<WORKTREE_NAME>/<repo-relative-path>. Never use bare
repo-relative paths unless `git rev-parse --show-toplevel` for the agent
session root is itself the assigned worktree.

After the first edit, immediately run:

git -C <ASSIGNED_WORKTREE> status --short --branch
git -C <MAYOR_CHECKOUT> status --short --branch

Continue only if the worker worktree is dirty and the mayor checkout did
not receive code edits.

NEW-FILE EXTRA CHECK (path-resolution leak).  When you Write
a brand-new file (most-common offender: ai/findings/<name>.md from an
audit), the path-resolution leak silently routes the write into the mayor
checkout because the file did not previously exist in either tree. The
worker-worktree `git status` then shows nothing new (the leak landed
elsewhere) and the gitignored `/ai/` tree masks the symptom on both
sides.  After every new-file Write, run BOTH:

  Test-Path <ASSIGNED_WORKTREE>\<repo-relative-path>
  Test-Path <MAYOR_CHECKOUT>\<repo-relative-path>

Only the first should be True. If the mayor-side Test-Path returns True,
STOP — you've leaked.  Do not retry the write, do not delete the leaked
file, do not commit. Report the leak with both Test-Path results and let
the mayor decide.

If any edit lands outside <ASSIGNED_WORKTREE>, stop immediately and report
it. Do not repair, restore, commit, or push until the mayor tells you what
to do.
```

**Mayor checks after dispatch.** `git status --short --branch` in the mayor
checkout, AND `Get-ChildItem <MAYOR_CHECKOUT>\ai\findings\ -ErrorAction
SilentlyContinue` to spot leaked gitignored findings the worker meant to put
in its own worktree. If the mayor checkout gains unexpected files (committed
or gitignored), interrupt the worker, preserve the edits into the worker
worktree, then restore the mayor checkout — only specific known files; never
broad destructive resets.

**TODO (leak-mitigation escalation).** If this strengthened reminder proves
insufficient and the leak continues to happen, escalate to one of:

- **Canary protocol (option A):** `scripts/assert-worker-worktree.ps1`
  writes a sentinel under `<worktree>/.worker-canary.txt` with timestamp +
  expected path; mayor-side post-dispatch reads it back and asserts the
  canary landed in the worker worktree, not the mayor checkout.
- **Mayor-side leak detector (option B):** new
  `scripts/assert-mayor-clean.ps1` that runs `git status --short` plus an
  `ai/findings/` untracked-file scan against the mayor checkout and warns
  if new files appeared during the dispatch window.

Both are larger investments than the documentation patch above; ship them
only if the reminder + new-file Test-Path check don't close the gap.

## Common preamble (every dispatch)

```
You are implementing bead **<BEAD_ID>** in <project description>.

<project stance, obtained from operator — pre-alpha, production-stable, etc.>

NEVER link to ai/findings/* from committed files (spec/*, docs/*, tools/*/spec/*,
skills/*, migration/*, README.md). The /ai/ tree is gitignored; mkdocs strict's
link-validator catches it at CI time and blocks unrelated PRs in cascade.
If you need to cite a finding, inline a 1-sentence summary in the committed
prose. The pre-PR lint `python scripts/check_doc_slugs.py` flags violations
under defect category AI_FINDINGS_LINK.
```

## Worktree path convention

Worker worktrees live under `<WORKTREE_ROOT>`. Not inside the mayor checkout
(mixes worker edits into the mayor's working tree). Not `.claude/worktrees/`
(forbidden — tool-path-resolution quirks leak edits to the mayor checkout).

---

## Shape 1 — Solo bead implementation

One bead, one PR. Standard shape. Sections: bead ID + verbatim title; 2–4
paragraphs of context with `file:line` citations; numbered concrete steps;
worktree at `<WORKTREE_ROOT>/<descriptive>-<BEAD_ID>` with branch
`worker/<descriptive>-<BEAD_ID>`; include worktree-boundary block; `bd
update <BEAD_ID> --claim` + `--status=in_progress`; quality gates with exact
commands; push + `gh pr create` titled `<scope>(<artefact>): <summary>
(<BEAD_ID>)`; return PR URL + per-step summary + test deltas, under <N> words.

## Shape 2 — Cluster (multiple beads, single PR, sequenced commits)

3–12 beads on a shared surface. Sections: cluster name + N beads + source
findings; numbered bead list ordered **smallest cleanup → biggest correctness
fix** (so a failing P1 fix doesn't strand the small cleanups); commit format
`<scope>(<artefact>): <summary> (<BEAD_ID>)`; worktree at
`<WORKTREE_ROOT>/<cluster-name>-<HEAD_BEAD_ID>`; pre-claim each bead BEFORE
its commit (so bd state mirrors history one-to-one and a stalled cluster
leaves a clean partial trail); quality gates after EACH commit + full
regression after ALL; PR titled `<scope>(<artefact>): <cluster name> (N beads
incl. <P1 highlights>)`; return PR URL + per-bead one-liner + cross-bead
unifications spotted. Disjoint-surface "small-misc" clusters are valid at
the tail of a drain — the binding rule is hot-zone parallelism, not strict
same-surface.

## Shape 3 — Audit (read-only research)

One bead asks for a finding, not a fix. Sections: goal (read `<surface>`
end-to-end; identify correctness drifts, perf hotspots, API hygiene, testing
gaps, cross-artefact coupling); reference (surface paths, relevant spec
docs, recent landings that changed the surface, prior audit findings to
avoid re-discovering); worktree + boundary block + `--status=in_progress`;
**WRITE THE FINDINGS DOC FIRST** to `ai/findings/<surface>-audit-YYYY-MM-DD.md`
(gitignored — never commit findings, and never link to them from committed
files — see Common preamble policy + `check_doc_slugs.py` AI_FINDINGS_LINK);
file follow-on beads ONE AT A TIME
after the doc lands, appending each bead ID to the audit-bead's notes so
partial progress is durable across a watchdog timeout; close audit-bead
with verdict + cross-refs; no PR by default (trivial one-line obvious
fixes can ride along in a small PR); return under 400 words with per-finding
`file:line` citations + follow-on bead IDs + severity counts
(HIGH/MED/LOW/DEFER) + verdict.

## Shape 4 — Cluster reviewer (research + recommendation, no dispatch)

Used between dispatch waves to shape the next round. Read-only — no
worktree boundary block needed. Sections: cluster policy verbatim;
in-flight workers + their surfaces (do NOT recommend changes that touch
these); enumerate beads filed in the last ~30 min via
`git log -p --since='35 minutes ago' -- .beads/issues.jsonl`; per-bead
decide (A) add to in-flight cluster / (B) form new cluster (3+ beads on
shared non-in-flight surface) / (C) solo (P0/P1 correctness, structural
>250 LoC, decision-resolved, cross-cutting) / (D) defer; structured
output template; net recommendation in 2–3 sentences with specific
timing + dispatch shape. **Do not change bd state.**

## Shape 5 — Fix CI failure on a specific PR

One PR has a failing check that isn't obviously irrelevant. Sections:
the failing check name + log lines verbatim; 2–3 root-cause hypotheses;
worktree at `<WORKTREE_ROOT>/<branch-name>-fix` checking out the existing
branch (not a new one); boundary block; investigation steps; pick the
fix (A) surgical / (B) medium / (C) skip + file follow-on bead
(appropriate when stance allows a safe-out and the fix proves deeper
than the bead's scope); verify locally; **push to the existing PR branch,
not main**; return under 300 words with root cause + fix chosen +
verification. Diagnosis often surfaces deeper insight than the failure
log shows — test the hypothesis before applying the fix.

---

## Common failure modes these patterns close

- Agents adding back-compat shims by default → stance must be explicit in every preamble.
- Same-file races between concurrent workers → enumerate in-flight workers + surfaces.
- Workers leaking edits into the mayor checkout → worktree-boundary block.
- Path-resolution leak on new-file Write (especially `ai/findings/*` from
  an audit) routes the file into the mayor checkout silently → new-file
  Test-Path double-check in the worktree-boundary block.
- Stalled agents losing analysis → findings-first protocol; one-bead-at-a-time bd creates.
- Clusters splitting when they should be one PR → cluster reviewer pre-validates shape.
- Hot-zone merge conflicts → explicit hot-zone list in every prompt.
- Re-discovering known issues → name recent landings + prior findings docs.
- Generic prompts producing generic work → require `file:line` citations + concrete fix sketches.
- Findings docs leaking into PRs → `ai/findings/` is gitignored; never commit one.
- `--delete-branch` failing on Windows → covered by Mayor Merge Protocol in `bootstrap.md`.

## Canonical examples

Record once per project — three or four good examples teach a new mayor
more than thirty mediocre ones:

- **Solo done well**: `<bead-id>` — `<one-line on why exemplary>`
- **Cluster done well**: `<name>` — `<bead-count>` beads + `<a surprise the cluster surfaced>`
- **Audit done well**: `<bead-id>` — `<follow-on count>` + `<analytical move that made it valuable>`
- **CI fix done well**: `<bead-id>` — `<diagnosis-vs-surface-log distinction>`
