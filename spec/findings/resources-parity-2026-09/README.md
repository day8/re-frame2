# Resources ↔ TanStack Query-class server-state libraries — the 2026-09-14 parity research

Three independent research passes answered one brief from Mike on 2026-09-14: where
Resources ([`spec/016-Resources.md`](../../016-Resources.md)) stands against TanStack Query
and its peers (RTK Query, SWR, Apollo Client, Riverpod, TanStack DB), how to measure that,
and how to be better rather than merely level. Each pass ran from its own brief
(`<pass>/prompt.md`), measured trunk as it stood on 2026-09-14/15 (each report pins the sha
it inspected), wrote its report before reading the others, then read the others and revised.

This folder is the tracked copy of that corpus. Its working location was the gitignored
`ai/findings/Resources/` tree on the primary checkout, which a worker in its own worktree
cannot see and which the research chain rf2-kpnxv → rf2-11w9f → rf2-f7rvf → rf2-ylmcn →
rf2-hk1mc cites. The layout is unchanged so the reports' relative links hold. It is the
Resources twin of the Story corpus at
[`tools/story/spec/findings/parity-2026-09/`](../../../tools/story/spec/findings/parity-2026-09/README.md),
tracked the same way on 2026-09-14.

| Folder | Pass | What it carries |
|---|---|---|
| [`fable/`](fable/report.md) | "fable" | `report.md` (the ranked recommendations, the refusals and the DO NOT BUILD list), `matrix.md` (20 jobs scored per layer with citations), `rubric.md` (vocabulary, scale, controls), `evidence.md` (every probe and walk), `evidence/` (`conduit-trace.md`, `landscape.md`, `scorecard-verification.md`, `tutorial-stall-log.md`, `logs/`, `screens/`), `probes/` (the JVM and Node probes that produced the logs), `sibling-review.md` |
| [`astra/`](astra/report.md) | "astra" | `report.md`, `parity-matrix.md` (16 jobs), `evaluation-plan.md`, `evidence.md` (the run ledger), `scorecard-audit.md`, `sibling-review.md` and `review-round-2.md`, `evidence/` (an executed TanStack Query fixture — source only — browser and JVM probe receipts, gate logs, the two bead drafts that became rf2-oyr9f and rf2-n2onr, `independent-draft/` and `sibling-review/round-1..6/` preserving every earlier revision), `prompt-review/` (the two rounds of brief review) |
| [`grok/`](grok/report.md) | "grok" | `report.md`, `intermediate/` (`landscape.md`, `closeness-test.md` — the executed protocol — `scoreboard.md`, `advantage.md`, `evidence-ledger.md`, `sibling-synthesis.md`, and the Conduit walk shots under `conduit-walk/`) |

## What to read, and where the standing form lives

- **For the finding**, read any report's opening. The three converge: Resources matches or
  beats the comparators on every exercised read-side and write-side job, wins outright on the
  multi-user leak boundary, declared write consequences, optimistic rollback under overlap and
  the stub-table-as-coverage testing story, and was behind only on the first hour (docs) and
  on offline persistence and cross-tab (deliberately deferred). They disagree on whether a
  weighted number belongs anywhere; none proposes a read-side API change.
- **For the test itself** — jobs, vocabulary, controls, executed protocol, invalidation —
  read [`fable/rubric.md`](fable/rubric.md) and [`fable/matrix.md`](fable/matrix.md) with
  [`fable/probes/`](fable/probes/) and [`fable/evidence/logs/`](fable/evidence/logs/);
  [`grok/intermediate/closeness-test.md`](grok/intermediate/closeness-test.md) and
  [`grok/intermediate/scoreboard.md`](grok/intermediate/scoreboard.md); and
  [`astra/parity-matrix.md`](astra/parity-matrix.md) with
  [`astra/evaluation-plan.md`](astra/evaluation-plan.md) and
  [`astra/evidence.md`](astra/evidence.md). Unlike Story, Resources has no numbered
  parity-test spec page: the re-run triggers below are the standing form, and the spec's own
  "Deferred slices" section carries the fires-when triggers for the deferred slices, so a page
  would duplicate both.

## What landed from it

Every build recommendation the three reports ranked has landed on trunk (cross-checked
2026-09-21). Read a claim in the reports about the tree as dated by the sha it pins.

| Recommendation | Where it landed |
|---|---|
| Tutorial first hour: name the http dependency, real API base, viewer scope, the missing test continuation (fable D1; grok "done on trunk") | rf2-oyr9f PR #9838, rf2-6ddy0 PR #9844 |
| Scorecard `:keep-previous?` and GC wording; fair TanStack portraits; persistence row marked deferred (fable H1 + D2; grok #1 and #4) | rf2-jqouk + rf2-n2onr PR #9842, `docs/resources/coming-from-tanstack-query.md` |
| Tutorial names the absent stale default and `:revalidate-on` (grok #2) | `docs/resources/tutorial/02-server-data.md` |
| Conduit mounts Xray in its dev build and says what it does not show (fable D3 + D3b; grok #3) | rf2-trjh7 PR #9846 |
| "Exactly these requests were issued" as a test assertion (fable L3) | `docs/resources/testing.md` section 4 |
| Xray write-reach lint over the optimistic-reconciled trace (fable L1; grok "lint") | rf2-ynkzj PR #9851 |
| Infinite-feed refetch tradeoffs explained before any API grows (astra 5) | `docs/resources/how-to/paginate-a-feed.md`, "Refetch and reset" |
| Persistence and cross-tab as explicit product decisions with triggers (astra 4; fable G3) | `spec/016-Resources.md`, "Deferred slices" |

fable H2 and L2 were withdrawn by the report itself after re-verification.

## Re-run triggers

From fable KM1 and grok "Keep measuring", the standing test is re-run by hand — never as a
CI gate — when any of these move:

- `spec/016-Resources.md` sections Mutations, Scope resolution, or Race and in-flight;
- the stale or GC default, or a deferred 016 slice landing;
- the tutorial's chapters 01–04, or the Conduit example, rewritten;
- TanStack Query shipping a React-stable major, or a TanStack DB release that changes the
  local-first job.

Method notes the siblings converged on: wait on data identity, not on "list visible"
(keep-previous masks the difference); plant a missing invalidation on a tag that member tags
will not mask; record the interval between a rejected older write and recovery, not only the
settled UI. The automatable residue already lives in the tree as the six
`spec/conformance/fixtures/resources-*.edn` fixtures and their named tests.

## Refusals and DO NOT BUILD

The consensus of the three passes, kept here so nobody re-derives it. Items the spec already
refuses are marked; the rest live only in these reports.

- No normalised entity cache, no GraphQL-in-016, no process-global cache, no `:select` —
  already refused in `spec/016-Resources.md`.
- No offline persistence, cross-tab or persister plugin now — deferred in 016 with a
  fires-when trigger (a real app in this repo that needs it, or a second migrant report).
- No parity CI gate or dashboard, and no composite parity percentage anywhere.
- No runtime warning when a caller passes a scope literal: trust the programmer. If a real
  leak is ever traced to one, it becomes an Xray lint, not a warning.
- No hook-shaped or view-local fetching API to shrink the first hour; the three lanes are the
  product, and the first hour was fixed in the tutorial.
- No serialising of writes by scope (TanStack `mutation.scope`): revision-based conflict
  handling already converges, and serialisation hides latency.
- No automatic member tags on every list resource: generalised masking makes invalidation
  reach implicit, which is the TanStack failure in reverse.
- No resource-wide "stale at once" default: a documented migration profile suffices.
- No infinite, SSR, prefetch or polling features added to Conduit; its README points at the
  capability examples instead.
- No `handler-meta`-driven static consequence enumerator: consequences are functions of
  params and cannot be enumerated without executing.
- No Resources MCP to claim the assistant job before the Xray mount had landed.
- No read-side API change motivated by parity: the reads all MATCH on exercised evidence.

## Deliberately not filed

- **astra 2**, an application-level Conduit test combining an initially absent collection
  member, an older rejected write and an identity change with replan. Library-level coverage
  exists (the conflict-rollback deftests in
  `implementation/resources/test/re_frame/resources_optimistic_settle_cljs_test.cljc`, the
  route plan-recovery and replan tests beside it) and the flagship suite covers the favourite
  populate-and-invalidate flow and the session-restore replan. Two of the three passes say
  stop after the docs items. Trigger to revisit: a real "the feed kept my guess" bug in a
  shipped app.
- **A numbered parity-test spec page** like Story's. This README carries the triggers and
  016 carries the deferred-slice triggers; a page would be ceremony.
- **astra 1**, re-measuring authoring on the repaired entry path. rf2-6ddy0 walked the
  tutorial as a fresh consumer after the repair; a further re-measure is a trigger above,
  not a build.

## Provenance, and what was left out

- Copied 2026-09-21 under rf2-nup2n from `ai/findings/Resources/` (4,075 files, 272 MB) as
  316 files, 4.2 MB, layout unchanged. Left out: `astra/evidence/shadow-cache/` (155 MB) and
  `astra/evidence/tanstack/node_modules/` (57 MB), which are build caches;
  `astra/evidence/browser/js/` (44 MB of compiled cljs-runtime and bundle) and
  `astra/evidence/tanstack/app.js` (a 1.6 MB esbuild bundle, over the 200 KB cap the Story
  corpus used); `astra/evidence/local-source/` (160 files) and the `verification/` and
  `current-source/` copies under `astra/evidence/sibling-review/round-*/`, which were
  snapshots of tracked files as they stood; `astra/evidence/primary/` (33 fetched vendor
  documents); `astra/evidence/resources-beads-all.json` (a 707 KB tracker export); and three
  `.cpcache/` directories.
- **No link gate validates this folder.** `scripts/check_doc_slugs.py` excludes every
  directory named `findings` by design (exploratory work), and `scripts/check_readme_links.py
  --ci` excludes the docs-gate roots, `spec/` among them, so this README's own links were
  checked by hand. Of the reports' relative links, 145 resolve; 84 point into the left-out
  subtrees above and do not; 104 more, all in `astra/evidence/independent-draft/` and
  `astra/evidence/sibling-review/round-5/astra-before/`, were already broken in the working
  tree — those drafts were written at `astra/` and later moved under `evidence/` without
  their `evidence/...` links being rewritten — and are kept as they were, since they are
  preserved drafts rather than the current report.
- `mkdocs.yml` lists `spec/findings/` under `exclude_docs`, so the corpus never enters the
  built site even though `mkdocs_hooks.py` stages all of `spec/` into `docs_dir`.
- Personal home paths in the evidence (receipts, process logs, `deps.edn` files with
  absolute `:local/root` entries, the scratchpad root the probes ran from) are scrubbed to
  `<HOME>` / `<user>` so the repo's portability gate (`scripts/check-no-hardcoded-paths.sh`)
  holds, as the Story corpus's were; the `ai/` originals kept the literal paths. Four
  Playwright probes (`astra/evidence/browser-probe.cjs`, `astra/evidence/scope-browser-probe.cjs`,
  `grok/intermediate/conduit-walk.cjs`, `grok/intermediate/conduit-walk-favorite.cjs`) had an
  explicit `timeout: 30000` added to their one `page.goto` call each, because the repo's
  navigation-ceiling policy test sweeps every tracked `.cjs`; the fable probes already
  carried one. Nothing else in the files was altered.
- The repo ignores gate logs (`*.log`, `*.exit`); the 117 small ones the reports link were
  added with `git add -f`, as the Story corpus's were.
