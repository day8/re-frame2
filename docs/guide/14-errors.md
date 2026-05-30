# 14 - Errors

You want failures that explain themselves before they ruin your afternoon. This chapter teaches how re-frame2 treats errors: handler exceptions, schema violations, skipped effects, HTTP failures, and SSR projection are all structured events in the same observable runtime, not random console confetti.

The first rule is simple: pre-commit failure does not partially update `app-db`. If a handler, coeffect, interceptor, or schema boundary fails before commit, the old state remains the state.

## Atomic commit boundary

Event handling has a commit point. Before that point, the runtime is building coeffects, running interceptors, calling the handler, validating, and preparing effects. After that point, `app-db` has changed and effects may run.

That boundary is why tests can be crisp and tools can tell the truth. A failed pre-commit event is not a half-applied mutation. It is a failed event.

## Effects can fail after commit

Effects are different. If a handler commits `:db` and then an effect throws, the db is not rolled back. The external world may already have been touched. This is not a weakness; it is reality being accurately represented.

Design effects to report replies as events. Model failure states explicitly. Do not pretend a network request is part of an atomic transaction with the DOM and your backend unless you enjoy mythology.

## Schema failures are first-class

A schema violation is not "some warning somewhere." It is traceable. Good tests fail on schema violations because a final state can look clean after rollback while the event still carried invalid data.

## Public error projection

On the server, users should not see stack traces or sensitive ex-data. re-frame2 lets frames name public error projectors so SSR can turn internal failures into safe public values while preserving detailed diagnostics for dev tooling.

## Pitfall: hiding data in exception messages

Never put secrets in exception strings. If a handler reads a sensitive value and throws `(ex-info (str token) ...)`, no framework can infer your regret. Use per-app helpers that scrub ex-data and messages before throwing across public or off-box boundaries.
