# Analysis lenses

Use this file when the session has enough detail that a loose summary is not enough.

## Friction signals

Look for:

- repeated retries or "try again" loops
- repeated explanation of the same context
- fallback from a high-level workflow to low-level commands
- missing or empty outputs where the user expected an answer
- stale outputs that required manual verification
- unclear contracts between docs and actual behavior
- hidden prerequisites or environment assumptions
- long waits with weak progress signals
- workarounds that the tool should have encoded
- user uncertainty about whether to trust the result

Also notice:

- hidden capabilities that should have been discoverable
- expert-only knowledge that should have been embodied in the tool
- places where the workflow nearly worked but failed late
- places where the user had to choose between speed and safety

## re-frame2-specific friction signals

These signals are unique to or amplified by re-frame2's Tool-Pair surfaces. Watch for them in addition to the generic list above.

- ambiguity about which **frame** an inspection or dispatch targeted (multi-frame is first-class in re-frame2)
- confusion between the **raw trace stream** (`register-listener!`) and the **assembled epoch stream** (`register-epoch-listener!`) — wrong listener choice for the question being asked
- manual reconstruction of `:sub-runs`, `:renders`, or `:effects` from raw traces when the structured projections on `:rf/epoch-record` already carry them
- `restore-epoch` calls that fail with one of the named `:rf.epoch/restore-*` modes (`restore-unknown-epoch`, `restore-schema-mismatch`, `restore-missing-handler`, `restore-version-mismatch`, `restore-during-drain`, `restore-non-ok-record`) — plus unknown-frame riding `:rf.error/no-such-handler` (kind `:frame`) — without a clear next-best-action
- silent loss of history because `:epoch-history :depth` is too low for the user's workflow, with no warning when the target epoch ages out
- stale or empty result because the build is `:advanced` (production-elided): the trace surface, schema validation, and epoch machinery are gated by `re-frame.interop/debug-enabled?` and elide entirely
- source-coordinate workflows that assume `data-rf2-source-coord` is present when the runtime opt is off
- private-namespace reach-through (`re-frame.db`, `re-frame.router`, `re-frame.subs`, `re-frame.events`, `re-frame.registrar`) — these are off-contract per Tool-Pair §REPL-eval and may move
- hot-swap that fired but the user could not tell because `:rf.registry/handler-replaced` was not surfaced
- dispatch correlation gaps: cascade walks where `:rf.trace/dispatch-id` / `:rf.trace/parent-dispatch-id` were available but the tool did not stitch them
- machine-snapshot version skew (`:rf/snapshot-version`) silently breaking restore after a hot reload
- effect overrides (`:fx-overrides`) that lingered or leaked across experiments

## Error-observability lens

Use when the session chased an error — why it fired, where it surfaced, or why the app didn't "recover" the way the user expected. There is **no app-steering recovery policy** and no per-frame `:on-error` slot: recovery is framework-owned (the typed per-category default), and observability is the always-on error-emit listener surface — `(rf/register-listener! :errors id listener-fn)`, the `:errors` stream of the stream-parameterized listener verb.

Friction signals specific to errors:

- the agent or user expected an app-level error policy to swallow or substitute a result and was confused the framework applied its typed default. Surface the model: recovery is not app-steerable; genuine recovery for *expected* failures is local-at-source (managed-HTTP `:retry`, optional-read fallback); observability is the `:errors` stream of `register-listener!`.
- the session looked for a per-frame `:on-error` slot — there is none; point the user at the always-on error listener.
- the agent assumed the error-emit listener elides in a CLJS production build and dismissed a production error report. The real dev/prod split:
    - **The error-emit listener is always-on.** It rides the always-on error-emit substrate, is NOT gated by `re-frame.interop/debug-enabled?`, and survives `:advanced` + `goog.DEBUG=false` for every catalogued production-reachable `:rf.error/*` (per `spec/009-Instrumentation.md` §Production elision and the posture matrix). Registered listeners DO fire in prod.
    - **Only dev-side enrichments elide.** The `:rf.trace/dispatch-id` / `:rf.trace/trigger-handler` source-coord correlation and the retain-N ring buffer ride the dev trace surface.

Routing the fix:

- **re-frame2-pair skill** — the friction is that the agent didn't recognise an error category, or the inspection recipe was unclear → surface the trace categories more loudly in the pair tool's error catalogue (`re-frame2-pair/references/errors.md`)
- **upstream re-frame2** — the friction is the runtime's behaviour itself (a category the always-on error-emit substrate does not yet cover, missing structured `:tags` on a trace) → file against `re-frame2`, cross-link to `spec/009-Instrumentation.md §Error observability`

When proposing improvements, prefer turning a silent runtime fallback into a louder warning the agent can route to the user, and prefer a recipe over a doc paragraph when the friction is "I didn't know how to pull recent errors at the REPL".

## Root-cause categories

Map each finding to one primary cause:

- `docs/discoverability` — the feature or prerequisite existed, but the user could not find or trust it
- `workflow-gap` — the instructions or recipes did not guide the user through a common task
- `missing-op` — the workflow needed a first-class operation that does not exist
- `unreliable-op` — an existing operation was too brittle or ambiguous
- `default/fallback` — the default behavior was wrong, silent, or unsafe
- `platform-bug` — the workflow behaved differently on a specific OS, shell, or browser setup
- `validation-gap` — the bug shipped because the repo lacks the right smoke test, fixture, or warning
- `upstream-gap` — the best fix belongs in `re-frame2` itself (Tool-Pair contract, instrumentation surface, schema reflection, epoch machinery, or source-coord annotation)
- `out-of-scope` — the user wanted something `re-frame2-pair` should probably not own

## Improvement patterns

Prefer proposals that remove repeated effort:

- tighten `SKILL.md` wording or add a recipe
- add a stronger warning or a more explicit failure mode
- add a structured result field instead of forcing manual interpretation
- add a runtime/script op for a repeated manual step
- make a platform-specific fallback automatic
- add a fixture or regression test for the observed failure mode
- file a GitHub issue against `day8/re-frame2` when the pair tool is working around a missing surface in the Tool-Pair contract

Also consider higher-upside redesigns:

- collapse a multi-step troubleshooting loop into one guided operation
- make the tool detect and explain the problem before the user asks
- remove an expert-only decision by choosing or validating the safe default automatically
- turn a late failure into an early warning or preflight check
- add instrumentation that makes the next debugging step obvious instead of manual
- rethink the interaction shape if the current command flow is fighting the user's mental model
- prefer the assembled epoch stream by default for "what happened in this cascade" routing; reach for the raw trace stream only when the question demands per-emit detail

## Routing the fix

Decide which kind of friction it is before drafting. Both file against **`day8/re-frame2`** (the pair tool ships inside that monorepo); the distinction is carried **primarily in the title + body**, with an optional reinforcing label **only when the repo defines it** (labels are never a filing precondition — see [`issue-template.md` §Filing with `gh issue create`](issue-template.md)):

- **pair-tool friction** (optional `--label pair-mcp`) — the friction is in the tool's SKILL.md, scripts, attach logic, recipe selection, structured-result shape, cross-platform handling, or any concern that is not part of the framework's commitment.
- **framework friction** (optional `--label upstream-from-re-frame2-pair`, no `pair-mcp`) — the friction is caused by a gap or ambiguity in the Tool-Pair contract itself. Name the specific surface from [`../../shared/tool-pair-surfaces.md`](../../shared/tool-pair-surfaces.md) (e.g. a missing trace event category, an under-specified `:rf.epoch/*` failure mode, a missing registrar query, a `data-rf2-source-coord` shape question, a schema-reflection limitation) or a private-namespace reach-through that should be promoted to public.
- **Both** — sometimes the fastest path is a tool-side workaround now (optionally labelled `pair-mcp`) plus an upstream issue for the long-term fix. File both, and reference one from the other.

## Prioritization

Prioritize improvements that score well on most of these:

- common: likely to help many sessions
- leverage: removes a whole class of manual effort
- specific: easy to describe and implement
- trustworthy: improves confidence in results
- local-first: can be fixed in `re-frame2-pair` without waiting on upstream

If many ideas surface, return the top 2-5 and demote the rest to "other possibilities".
Include 0-2 bolder ideas when they are concrete, high-leverage, and clearly labeled as redesigns or speculative bets.
