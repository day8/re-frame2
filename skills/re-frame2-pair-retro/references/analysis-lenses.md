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

Most recurring re-frame2 friction has a catalogued class — with its own signals and typical improvements — in [`known-frictions.md`](known-frictions.md): frame ambiguity; wrong listener for the question (the raw `:trace` stream vs the assembled `:epoch` stream, both on `register-listener!`); time-travel restore failures (the six `:rf.epoch/restore-*` modes plus unknown-frame via `:rf.error/no-such-handler`); production-elision confusion; tool-catalogue / build-capability uncertainty; source-coordinate availability; private-namespace reach-through; error observability (recovery is framework-owned, observability is the always-on `:errors` listener); and multi-tool coexistence. Match the session against those classes by name rather than re-scanning a parallel list here.

A few re-frame2-specific signals are not yet their own class — watch for them too:

- hot-swap that fired but the user could not tell because `:rf.registry/handler-replaced` was not surfaced
- dispatch correlation gaps: cascade walks where `:rf.trace/dispatch-id` / `:rf.trace/parent-dispatch-id` were available but the tool did not stitch them
- machine-snapshot version skew (`:rf/snapshot-version`) silently breaking restore after a hot reload
- effect overrides (`:fx-overrides`) that lingered or leaked across experiments

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

The pair-tool-vs-framework routing decision — both file against `day8/re-frame2`, the distinction rides the title + body, labels optional — is the pre-drafting step owned by [`issue-template.md` §Routing first](issue-template.md). Classification-wise, the `upstream-gap` root-cause category above already flags when the fix belongs in `re-frame2` itself; name the specific surface from [`../../shared/tool-pair-surfaces.md`](../../shared/tool-pair-surfaces.md).

## Prioritization

Prioritize improvements that score well on most of these:

- common: likely to help many sessions
- leverage: removes a whole class of manual effort
- specific: easy to describe and implement
- trustworthy: improves confidence in results
- local-first: can be fixed in `re-frame2-pair` without waiting on upstream

If many ideas surface, return the top 2-5 and demote the rest to "other possibilities".
Include 0-2 bolder ideas when they are concrete, high-leverage, and clearly labeled as redesigns or speculative bets.
