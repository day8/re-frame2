# output-format

The migration summary format. The shape from [`MIGRATION.md`](../../../spec/MIGRATION.md) Part 2 §"Output format for your report" is the source of truth; this leaf restates it with one filled-in example so the summary stays consistent across migrations.

## The format

```markdown
## Migration summary

- re-frame version: <old> → <new>
- Files modified: <count>
- Required rules applied: <list of M-N rule IDs, or "none">
- Opt-in changes applied: <list of O-N rule IDs, or "none, not requested">
- Verification: <compile/test/run results>

## Items flagged for human review

<list of call sites you found suspicious but did not change, with file:line and a brief explanation>

## Anything unexpected

<observations that don't fit elsewhere>
```

Keep it **under 300 words** unless the migration was unusually complex.

## Discipline

- **One summary per migration.** Even if the migration spanned multiple sessions, the summary is the single artefact the author reads at the end. Don't fragment.
- **Cite rule ids.** Every change cites the M-N or O-N rule that authorised it. The author can grep [`MIGRATION.md`](../../../spec/MIGRATION.md) for each id if they want the rationale.
- **No new findings buried in narrative.** If you discover something during the migration that warrants a `bd` bead (spec drift, missing rule, surprising behaviour), file the bead and reference it in the summary — don't bury the finding inside prose.
- **Items flagged for human review are explicit.** A Type B rule the author **declined** to apply is just as important as one they applied. Both go in the summary.

## Worked example A — only Type A rules tripped

A medium-shape migration: a Reagent app with 30 events, 12 subs, no machines, no SSR, three `:dispatch` effect-map entries, one `reg-global-interceptor`, two `dispatch-sync` calls inside handlers, one `^:flush-dom`, no `reg-sub-raw`, no `re-frame.alpha`, has `re-frame-test`.

```markdown
## Migration summary

- re-frame version: `re-frame/re-frame 1.4.5` → `day8/re-frame2 <VERSION>` + `day8/re-frame2-reagent <VERSION>`
- Files modified: 14
- Required rules applied:
  - M-0 (1 site): deps.edn coord swap.
  - M-1 (3 sites): private `re-frame.db` requires removed; `@re-frame.db/app-db` → `(rf/get-frame-db :rf/default)` in 3 sites.
  - M-8 (3 sites): top-level `:dispatch` → `:fx [[:dispatch ...]]`.
  - M-9 (2 sites): `dispatch-sync` inside handlers → `:fx [[:dispatch ...]]`.
  - M-16 (1 site): `^:flush-dom` metadata → `:dispatch-later {:ms 0}`.
  - M-17 (1 site, Type A — single-frame app): `reg-global-interceptor` → default-frame `:interceptors`.
  - M-20 (4 sites): `:re-frame/default` → `:rf/default`.
  - M-25 (1 site): `re-frame.test` → `re-frame.test-support`; `day8/re-frame-test` coord dropped.
  - M-40 (1 site): `(rf/init!)` → `(rf/init! reagent-adapter/adapter)`.
- Opt-in changes applied: none, not requested.
- Verification:
  - Compile: clean.
  - Tests: 47 pass, 0 fail (pre-migration baseline: 47/0).
  - Smoke-test: boot OK, dispatch + sub + hot-reload all green.

## Items flagged for human review

None — the codebase tripped only Type A rules.

## Anything unexpected

- `:re-frame/default` appeared 4 times across the codebase; one was a comment string ("uses the :re-frame/default frame"). Not rewritten — comment text only. Flagging in case the author wants comments updated for consistency.
```

## Worked example B — Type B rules and a flagged test

Same shape but the codebase also had two `reg-sub-raw` calls, a multi-frame structure with a `reg-global-interceptor`, and a render-count assertion in a test.

```markdown
## Migration summary

- re-frame version: `re-frame/re-frame 1.4.5` → `day8/re-frame2 <VERSION>` + `day8/re-frame2-reagent <VERSION>`
- Files modified: 18 (+ 2 awaiting author decision)
- Required rules applied: M-0, M-1 (5 sites), M-8 (7 sites), M-9 (3 sites), M-20 (9 sites), M-25 (1 site), M-40 (1 site).
- Type B rules applied (with explicit author approval):
  - M-3 (2 sites): `:dispatch` effects with intermediate-render dependency were both real animation pacing — converted to `:dispatch-later`.
  - M-17 (1 site, multi-frame): `reg-global-interceptor` was an audit logger — converted to `register-trace-cb!`.
- Opt-in changes applied: none, not requested.
- Verification:
  - Compile: clean.
  - Tests: 89 pass, 1 fail. Render-count assertion in `views_test.cljs:42` failed (M-12); see flagged items.
  - Smoke-test: boot OK, dispatch + sub + hot-reload all green.

## Items flagged for human review

- `src/app/subs.cljs:78` — `(reg-sub-raw :live-ticker ...)` body subscribes to a WebSocket stream (M-18 category 2). Proposed rewrite: register a `:ws/connect` fx that dispatches `:ws/tick-received` events; `:live-ticker` becomes a plain `reg-sub` reading `app-db`. **Awaiting author decision**: should the ws-source stay as-is or migrate?
- `src/app/admin/subs.cljs:104` — `(reg-sub-raw :session-clock ...)` body manages a reaction lifecycle with `r/track!` (M-18 category 3). Proposed rewrite: state machine. **Awaiting author decision**.
- `test/views_test.cljs:42` — `(is (= 3 @render-count))` (M-12). Re-baselining needed; the new count is 2.

## Anything unexpected

- `day8/re-frame-test` was pinned at `0.1.5` (a pre-release). Replaced cleanly by `re-frame.test-support`; no compatibility issues observed.
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
- **The author can audit.** Every change has a rule id; the rule id lets them go back to [`MIGRATION.md`](../../../spec/MIGRATION.md) and verify.
- **The author knows what's still on them.** "Items flagged for human review" is the contract — these are the things the agent declined to action, and they're owned by the author from this point.
- **The summary goes into the project history.** Drop it as a commit message, a PR description, or a `MIGRATION-NOTES.md` in the project root — whatever the project's convention is. The migration agent doesn't decide where; the author does.
