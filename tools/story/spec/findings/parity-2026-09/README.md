# Story ↔ Storybook-class workshops — the 2026-09-14 parity research

Three independent research passes answered one question from Mike on 2026-09-14:

> We want Story to have feature parity with StoryBook and similar JS (and other)
> alternatives. Except, of course, we want Story to leverage re-frame2's strengths,
> idioms and ethos. How do we test how close we are to this objective? How do we
> imagine being better (more powerful, more ergonomic, etc.) than these alternatives?

Each pass was run from its own brief, measured Story at trunk `98e8ffe9cb` against
Storybook 10.6 and its peers, wrote its report before reading the others, then read the
others and revised. This folder is the tracked copy of that corpus; its working location
was the gitignored `ai/findings/Story/` tree on the primary checkout, which a worker in
its own worktree cannot see. The layout is unchanged so the reports' relative links hold.

| Folder | Pass | What it carries |
|---|---|---|
| [`fable/`](fable/report.md) | "fable" | `report.md` (three revisions; the last leads with the current tree), `matrix.md` (per-job scores and citations), `rubric.md` (vocabulary, scale, weights, controls), `evidence.md` (every probe, walk and drift check verbatim), `probes/` (the JVM and MCP probe scripts), `prompt.md` (the brief) |
| [`astra/`](astra/report.md) | "astra" | `report.md`, `parity-matrix.md`, `evaluation-plan.md`, `critique-for-fable.md`, `evidence/` (an executed Storybook 10.6 fixture — source only, no `node_modules` — browser receipts, a11y scan, the promotion and fidelity probes, the second-sibling review with a 64-test JVM run) |
| [`grok/`](grok/report.md) | "grok" | `report.md`, `intermediate/` (landscape, closeness-test protocol, scoreboard, advantage thesis, evidence ledger, synthesis, Playwright shots of the login-form workshop) |

## What to read, and where the standing form lives

- For the finding, read any report's **Answers in brief**; the three agree on every
  defect, on the ranking and on what not to build, and disagree only on whether a
  weighted number belongs anywhere (it is kept as a method appendix in the fable report).
- For the **test itself** — jobs, vocabulary, controls, executed protocol, invalidation —
  the standing home is the numbered spec page `tools/story/spec/023-Parity-Test.md`
  (bead rf2-hc3ur writes it from this corpus). This folder is the evidence behind that
  page, not a second copy of it.
- For the work, the tracker epic is **rf2-0ae7o** ("epic(story): Storybook parity and
  surpass"); its children cite paths in this folder.

## Provenance

- Research pin: `98e8ffe9cb`. Trunk at the fable report's third revision: `8b876b5466`.
  The twelve beads filed from the first pass merged as PRs #9795–#9810 the same day; the
  reports record which of those were re-executed afterwards and which are source-verified
  only.
- Excluded from the copy: `node_modules`, build caches and static build output under
  astra's Storybook fixture (222 MB); everything else, including the small `.exit` files
  the reports link, is present.
- The `ai/` originals may drift ahead of this copy; a report's own header carries its
  revision stamps.
- `scripts/check_doc_slugs.py` excludes every directory named `findings` by design
  (exploratory work), so the links inside this folder are **not** gate-checked; the
  reports' relative links were preserved by keeping the layout, not by a gate.
- Personal home paths in the evidence (receipts, process logs, the scratchpad root the
  probes were run from) are scrubbed to `<HOME>` / `<user>` so the repo's portability
  gate (`scripts/check-no-hardcoded-paths.sh`) holds; the `ai/` originals keep the
  literal paths. Nothing else in the files was altered.
- The repo ignores gate logs (`*-re-frame2-N.log` / `.exit`); the small ones the
  reports link as evidence were added deliberately with `git add -f` so those links
  resolve. Anything over 200 KB was left out and is named in the commit message.
