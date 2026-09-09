# Beads primer — re-frame2

**Provenance: this file is advice from a tool.** `bd prime` prints it at SessionStart and
PreCompact; it is not the repo's instruction sheet. **`CLAUDE.md` is the committed instruction
and wins wherever the two differ.** Never cite this file as "CLAUDE.md says" — grep `CLAUDE.md`
before attributing anything to it. That confusion has already happened here: a claim sourced
from this hook's output reached a bead as `CLAUDE.md`'s instruction and was refuted at source.
(`bd recall provenance-trap-at-session-start-the-bd-prime`)

## Session close

Work is not complete until `git push` succeeds. File issues for follow-up, run the gates your
diff needs, update issue status, then:

```
sh scripts/beads-checkpoint.sh                   # re-export from Dolt, verify, commit
git checkout HEAD -- .beads                      # only AFTER the checkpoint
git fetch origin main && git rebase origin/main  # explicit ref, never `git pull`
bd dolt push
git push
```

**The order is fixed; `CLAUDE.md` § Beads durability carries the reasons.** Clearing `.beads`
*after* a `bd close` reverts that close, and the next checkpoint writes the revert back. A
`git pull` takes its rebase target from `FETCH_HEAD`, which a concurrent git process rewrites.
Never `git stash` — stashes are repo-global and contaminate every worktree in flight.

## Core rules

- `bd` for ALL task tracking. No TodoWrite, no TaskCreate, no markdown TODO lists.
- Bead before code; `bd update <id> --claim` when you start.
- **`bd update --notes` REPLACES the field; `--append-notes` adds to it.** The loss is silent
  and has cost tens of thousands of characters here. `CLAUDE.md` § Beads durability has the
  recovery and the backtick trap in the same act.
- Worker branches carry code, spec, docs and tests — never `.beads/issues.jsonl` or
  `.beads/metadata.json`. A pre-commit hook and a CI job both enforce it.
- `bd edit` opens `$EDITOR` and blocks an agent. Never use it.

## Finding work — and finding what we already know

```
bd ready                bd list --status=open       bd show <id>
bd memories <topic>     bd recall <key>             bd history <id>
```

`bd show` prints the DESCRIPTION first, and that is the OLDEST text on a bead — corrections and
audit reopenings accrete below it. Order by `bd history <id>`, which is newest-first; neither
the bottom of the output nor a date in the prose is a currency signal.

## ~950 memories exist, and none of them is injected

`bd remember` has accumulated roughly 950 memories. **They are deliberately NOT loaded into
your context** — that is precisely what this file replaces. They remain searchable and you are
expected to search them: run `bd memories <topic>` before any non-trivial census, sweep, merge
or worktree operation. Most are measured accounts of an instrument handing back a confident
wrong answer.

## Four hazards `CLAUDE.md` does not carry

- **A `bd` write from outside the repo prints success and persists nothing.** Write from the
  repo root and read it back. `bd recall bd-write-from-wrong-cwd-silently-discarded`
- **`bd export` silently drops every memory without `--include-memories`.**
  `bd recall bd-export-drops-memories-without-flag`
- **Routine bead decay is OFF, permanently** — it deletes closed beads, whose close reasons
  are normative records here, and reclaims no space anyway. The ONLY sanctioned invocation:

  ```
  bd gc --skip-decay --force      # NEVER pass --older-than; routine decay is OFF (rf2-nj0c)
  ```

  `bd recall bd-gc-decay-silently-deletes-closed-beads`
- **A 502 from `gh pr merge` can mean the merge SUCCEEDED and only its response was lost** —
  wait a tick, don't retry. `bd recall 502-on-merge-means-it-may-have-happened`

This file overrides `bd prime` entirely, so upstream improvements to the default do not arrive
on their own — re-sync by hand against `bd prime --export` after a `bd` upgrade.
