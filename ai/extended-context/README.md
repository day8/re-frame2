# Extended Context

Durable context that is not obvious from the code alone and does not belong in
beads, `/ai/map.md`, or transient findings.

## Document Shape

Files in this folder should read like small AI skills: a compact trigger, a
repeatable procedure, and concrete prompt text or checks where useful.

The first five lines are YAML front matter:

```yaml
---
name: lowercase-hyphenated-id
summary: One sentence describing the durable context.
when_to_use: The trigger condition for using this note.
---
```

After the front matter:

- Start with an H1 matching the human-readable title.
- Explain why the context exists only if it is not obvious.
- Prefer `Trigger`, `Procedure`, `Prompt`, and `Checks` sections for operational notes.
- Keep temporary scratch work in `ai/findings/`; this folder is for reusable context.
- Before adding a new file, check this README to avoid duplicates, then add it to the index below.

## Index

- [Mayor Recovery After Token Limits or Worker Crashes](mayor-recovery-after-token-limit-or-worker-crash.md): how to reconstruct reality when prior mayor/worker sessions died and left stale bead, PR, branch, or worktree state.
- [Story Browser Testbed Invariants](story-browser-testbed-invariants.md): Story browser-testbed lessons around per-worktree ports, hot-reload baselines, panel scoping, and browser-visible snapshot hashes.

The mayor-method docs (including the dispatch prompt template, the
worktree-boundary block, the standing cron loops, and the merge protocol)
live in [`/docs/the-mayor-method/`](../../docs/the-mayor-method/).
