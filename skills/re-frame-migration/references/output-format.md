# output-format

The migration summary format. The shape from [`MIGRATION.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md) Part 2 §"Output format for your report" is the source of truth; this leaf adds the lines this skill's pins, decisions and done-bar gates need, with two filled-in examples, so the summary stays consistent across migrations.

## The format

```markdown
## Migration summary

- re-frame version: <old> → <new>
- Pinned MIGRATION.md corpus: `day8/re-frame2@<sha-or-tag>` (path: `<path-to-re-frame2>`)
- re-frame2 route and pin: <the final coordinates for a `:git/sha` or `:mvn/version` route; for a relative `:local/root` route, the source pin, how the runner fetches it and the directory layout — setup.md §The consumability done-gate>
- Files modified: <count>
- Required rules applied: <list of M-N rule IDs, or "none">
- Type B rules applied (with explicit author approval): <each rule, its sites and the author's decision, or "none">
- Dependencies changed (non-re-frame): <each non-re-frame dep bumped/added/removed, with the gate that justified it — Phase-0b floor gate (React/Reagent/component-lib), Check-4 toolchain (shadow-cljs/CLJS), or the Xray peer-deps; "none" if the coord swap was the whole diff>
- Opt-in changes applied: <list of O-N rule IDs, or "none, not requested">
- Verification: <one result per done-bar gate — compile, test suite (its totals line), boot smoke-test, optimized / release compile, CI on a clean checkout, and each supported host's acceptance item — each with the exact command and its outcome; a gate that has not run is PENDING, naming the input it lacks; for a 10x app, the Xray swap line from xray-replaces-10x.md §Reporting>

## Items flagged for human review

<list of call sites you found suspicious but did not change, with file:line and a brief explanation>

## Anything unexpected

<observations that don't fit elsewhere>
```

Keep it **under 300 words** unless the migration was unusually complex.

## Discipline

- **One summary per migration.** Even if the migration spanned multiple sessions, the summary is the single artefact the author reads at the end. Don't fragment.
- **Cite rule ids.** Every change cites the M-N or O-N rule that authorised it. The author can grep [`MIGRATION.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md) for each id if they want the rationale.
- **No new findings buried in narrative.** If you discover something during the migration that warrants reporting upstream (spec drift, missing M-rule, surprising behaviour), file a GitHub issue against `day8/re-frame2` — announce the cross-repo filing first, then follow the skill's filing recipe: **compose the body into a file with the `Write` tool and pass it with `gh issue create --body-file`** (`--body` / stdin / here-doc are banned for transcript-derived bodies — they reintroduce shell interpolation/injection risk). See [`issue-filing.md`](issue-filing.md) for the full title-and-body recipe + redaction pass. Reference the issue number in the summary; don't bury the finding inside prose. (`bd` is monorepo-internal and never invoked from a published skill.)
- **Items flagged for human review are explicit.** A Type B rule the author **declined** to apply is just as important as one they applied. Both go in the summary.
- **Phase-0a dispositions are not review items.** A rule recorded as *reviewed not applicable*, *already migrated* or *intentional negative test preserved* ([`inventory-and-plan.md` §Step 5](inventory-and-plan.md#step-5--the-per-rule-completeness-gate)) stays in the plan and out of *Items flagged for human review*, which carries only what is still the author's: unresolved Type B decisions, a coverage finding you could not close, and questions that need information you lack.

## Worked example A — nothing left flagged

A medium-shape migration: a Reagent app with 30 events, 12 subs, no machines, no SSR, three `:dispatch` effect-map entries, one `reg-global-interceptor`, two `dispatch-sync` calls inside handlers, one `^:flush-dom`, no `reg-sub-raw`, no `re-frame.alpha`, has `re-frame-test`.

```markdown
## Migration summary

- re-frame version: `re-frame/re-frame 1.4.5` → `day8/re-frame2 <VERSION>` + `day8/re-frame2-reagent <VERSION>`
- Pinned MIGRATION.md corpus: `day8/re-frame2@a1b2c3d` (path: `~/src/re-frame2`)
- re-frame2 route and pin: one pushed `:git/sha` on `day8/re-frame2` and `day8/re-frame2-reagent`, each with its own `:deps/root`.
- Files modified: 16
- Dependencies changed (non-re-frame): `react`/`react-dom` 18 → ^19 and `shadow-cljs` 2.20 → 3.4.10 (Phase-0b floor gate + Check-4 toolchain skew); Reagent rode in via the `-reagent` adapter (not pinned directly). No other deps touched.
- Required rules applied: M-0 (deps.edn coord swap), M-1 (3 sites, `@re-frame.db/app-db` → `(rf/app-db-value :rf/default)`), M-8 (3), M-9 (2), M-16a (1), M-17 (1, single-frame → default-frame `:interceptors`), M-20 (4), M-25 (1; the 0.1.5 coord, which ships no runner, dropped), M-40 (`init!` and the `:rf/default` app frame added; one adapter, so Type A), M-42 (root mount → `client-root`), M-73 (30 sites, by the codemod, none flagged).
- Type B rules applied (with explicit author approval):
  - M-11 (9 sites): bare `rf/dispatch` in `:on-click` handlers → ops captured at render with `(rf/capture-frame)`.
- Opt-in changes applied: none, not requested.
- Verification:
  - Compile: `npx shadow-cljs compile app` — 0 errors, 0 warnings.
  - Tests: `npx shadow-cljs compile test && node out/test.js` — 47 tests, 0 failures, 0 errors (pre-migration baseline: 47/0/0).
  - Boot smoke-test: driven through the `re-frame2-pair` MCP — clean on every page.
  - Release compile: `npx shadow-cljs release app` — clean.
  - CI on a clean checkout: green.

## Items flagged for human review

None — the one judgment call this codebase tripped (M-11) was settled with the author during the migration; everything else was Type A.

## Anything unexpected

- `:re-frame/default` appeared 4 times across the codebase; one was a comment string ("uses the :re-frame/default frame"). Not rewritten — comment text only. Flagging in case the author wants comments updated for consistency.
```

## Worked example B — Type B rules and a flagged test

Same shape but the codebase also had two `reg-sub-raw` calls, a multi-frame structure with a `reg-global-interceptor`, a render-count assertion in a test, and re-frame-10x.

```markdown
## Migration summary

- re-frame version: `re-frame/re-frame 1.4.5` → `day8/re-frame2 <VERSION>` + `day8/re-frame2-reagent <VERSION>`
- Pinned MIGRATION.md corpus: `day8/re-frame2@a1b2c3d` (path: `~/src/re-frame2`)
- Files modified: 18 (+ 2 awaiting author decision)
- Dependencies changed (non-re-frame): already on React 19 + a current toolchain (floor gate was a fast pass); `@xyflow/react` + `elkjs` added for the Xray Machine-inspector chart (10x→Xray swap). No other deps touched.
- Required rules applied: M-0, M-1 (5 sites), M-8 (7 sites), M-9 (3 sites), M-20 (9 sites), M-25 (1 site), M-40 (1 site — `init!` and the app frame added), M-42 (1 site — root mount), M-73 (41 sites, codemod).
- Type B rules applied (with explicit author approval):
  - M-3 (2 sites): `:dispatch` effects with intermediate-render dependency were both real animation pacing — converted to `:dispatch-later`.
  - M-17 (1 site, multi-frame): `reg-global-interceptor` was an audit logger — converted to an `:observability :handled-events` sink (`register-observability-sink!`).
- Opt-in changes applied: none, not requested.
- Verification:
  - Compile: `npx shadow-cljs compile app` — 0 errors, 2 warnings (the held `reg-sub-raw` sites).
  - Tests: `node out/test.js` — 89 tests, 1 failure (M-12, flagged), 0 errors.
  - Boot smoke-test: PENDING on the M-18 decisions — both subs namespaces throw at load.
  - Release compile: `npx shadow-cljs release app` — builds, same 2 warnings.
  - CI on a clean checkout: PENDING on the M-18 decisions and the M-12 re-baseline.
  - Devtools: 10x dropped at M-0; Xray mounted after M-40 — the app is on Xray.

## Items flagged for human review

- `src/app/subs.cljs:78` — `(reg-sub-raw :live-ticker ...)` body subscribes to a WebSocket stream (M-18 category 2). Proposed rewrite: register a `:ws/connect` fx that dispatches `:ws/tick-received` events; `:live-ticker` becomes a plain `reg-sub` reading `app-db`. **Awaiting author decision**: should the ws-source stay as-is or migrate?
- `src/app/admin/subs.cljs:104` — `(reg-sub-raw :session-clock ...)` body manages a reaction lifecycle with `r/track!` (M-18 category 3). Proposed rewrite: state machine. **Awaiting author decision**.
- `test/views_test.cljs:42` — `(is (= 3 @render-count))` (M-12). Re-baselining needed; the new count is 2.

## Anything unexpected

- `day8.re-frame/test` was pinned at `0.1.5`, which ships no runner namespace, so the coord retired with its last require. Replaced cleanly by `re-frame.test-support`; no compatibility issues observed.
```

## When the output exceeds 300 words

Reasons it might:

- Multi-frame architecture (multiple `reg-global-interceptor` sites with different rewrite paths each).
- Heavy `reg-sub-raw` use (each call site is a per-decision Type B).
- Lots of `enrich` / `after` interceptors (each is Type B, each has 3 rewrite paths).

When the output would run long, group similar items: *"15 `:dispatch` rewrites under M-8 — file-level summary at `src/app/events.cljs`"* rather than listing each site individually. The author wants the **shape** of what happened, not a transcription of every line.

If there are >10 items in *"Items flagged for human review"*, that's a sign the migration is fundamentally Type-B-heavy — surface this to the author at the start of the summary rather than burying them in the list.

## What the summary is for

- **The author reads it once and knows what the migration changed.** No need to read the diff to understand the shape.
- **The author can audit.** Every change has a rule id; the rule id lets them go back to [`MIGRATION.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md) and verify.
- **The author knows what's still on them.** "Items flagged for human review" is the contract — these are the things the agent declined to action, and they're owned by the author from this point.
- **The summary goes into the project history.** Drop it as a commit message, a PR description, or a `MIGRATION-NOTES.md` in the project root — whatever the project's convention is. The migration agent doesn't decide where; the author does.
