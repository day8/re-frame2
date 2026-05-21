# Known recurring frictions

Use this file as a pattern-check, not as a substitute for session evidence.

If the current session resembles one of these classes, mention that it may be part of a recurring product gap rather than a one-off.

## Common classes (framework-agnostic)

### Runtime discovery and attachment brittleness

Signals:
- repeated attach attempts
- discovery works on one platform or shell but not another
- the user has to bypass the documented path and connect manually
- the tool can reach the runtime indirectly, but the documented helper fails

Typical improvements:
- platform-aware discovery logic
- explicit fallback behavior
- stronger structured errors naming the next best action
- regression coverage for platform-specific connection paths

### Docs and behavior drift

Signals:
- the documented command exists but behaves differently
- the recipe is conceptually right but the exact method, flag, or expectation is wrong
- the user follows docs and still gets misleading results

Typical improvements:
- tighten contracts between docs and code
- add smoke checks for documented commands and result shapes
- promote volatile facts into generated or verified references

### Weak trust signals

Signals:
- the user cannot tell whether the result is fresh, partial, stale, or inferred
- the tool succeeds structurally but returns too little information to trust
- the user has to manually verify whether the action really landed

Typical improvements:
- add explicit freshness, completeness, or warning fields
- distinguish "worked but partial" from "fully landed"
- add better default summaries around retries, misses, and fallback paths

### Hidden prerequisite burden

Signals:
- too much expert knowledge is needed before the happy path works
- the user has to know environment quirks, shell behavior, fixture setup, or instrumentation assumptions
- a missing prerequisite is only discovered late

Typical improvements:
- earlier prerequisite checks
- clearer discovery output
- safer defaults
- stronger recipes for environment setup and recovery

### Missing first-class workflows

Signals:
- the user repeatedly falls back to raw commands or ad hoc reasoning
- the same multi-step workaround appears in more than one session
- the tool has the necessary low-level primitives but not the right composed recipe

Typical improvements:
- add a new structured op
- add a higher-level recipe to the skill
- reshape result fields so downstream reasoning is less manual

### Validation and fixture gaps

Signals:
- a regression clearly could have been caught by a smoke test, fixture, or cross-platform check
- confidence in the tool depends on manual dogfooding
- behavior is documented as supported but not actually exercised in CI

Typical improvements:
- add fixture coverage
- add targeted regression tests
- narrow the documented contract until tests exist

## re-frame2-specific classes

### Frame ambiguity

Signals:
- the user dispatches or inspects without specifying a frame and the result confuses them
- the tool defaults to "the" frame in a multi-frame app
- the timeline of events is correct but the user cannot tell which frame produced which row

Typical improvements:
- require an explicit `:frame` opt where ambiguity is possible, or surface the implicit choice prominently in the result
- add a "current operating frame" indicator in retrospective output
- list `(rf/frame-ids)` and recently-active frames at session start

### Wrong listener for the question

Signals:
- the user reaches for raw trace events to answer "what happened in this cascade" — a job for the assembled epoch stream
- the tool re-derives `:sub-runs` / `:renders` / `:effects` per epoch instead of reading the structured projections on `:rf/epoch-record`
- the user wants per-emit detail (live timing, in-flight events) but is reading post-hoc epoch records

Typical improvements:
- recipe that names the listener for each question shape
- default to `register-epoch-listener!` for cascade-shaped questions; reach for `register-listener!` only for per-emit detail
- short prose in SKILL.md naming the two streams and when each wins

### Time-travel restore failures

Signals:
- `restore-epoch` returned a `:rf.epoch/*` error and the user did not know how to recover
- the target epoch aged out silently because `:depth` was at default
- a hot-swapped handler or evolved schema invalidated an older snapshot, but the failure tag was not surfaced helpfully

Typical improvements:
- structured presentation of the six named failure modes with a next-best-action per mode
- preflight check before restore (depth, schema digest, machine version)
- surface `:history-size` and `:schema-digest-recorded`/`:current` in the tool's output

### Production-elision confusion

Signals:
- the trace stream, epoch history, or schema reflection returned empty and the user thought the tool was broken
- the build is `:advanced` and `goog.DEBUG=false`, so every Tool-Pair surface elides
- the user cannot tell whether they hit an elision wall or a tool bug

Typical improvements:
- preflight check that reads `re-frame.interop/debug-enabled?` and reports the answer
- make "I am attached to a production-elided build" a first-class result state
- recipe for switching to a dev build before continuing

### Tool-catalogue / build-capability uncertainty

Signals:
- the retrospective is unsure whether a tool the user "should have reached for" was actually exposed by the running re-frame2-pair-mcp build, or whether it was reasoning from stale docs
- the session reasons about tool availability from `re-frame2-pair/references/ops.md` alone — that doc can drift from the live `tools.cljs` catalogue (see on the drift gate)
- a "why didn't they use tool X?" thread surfaces with no way to confirm X was actually callable in that session

Typical improvements:
- when the retro is explicitly tied to an in-conversation live re-frame2-pair session whose runtime is already attached AND the user has confirmed a runtime probe is wanted, `mcp__re-frame2-pair__discover-app` MAY be invoked to capture the live build's id, health, and session sentinel — confirms the runtime the user was operating against and (combined with the server's `tools/list`) sanity-checks "tool X was actually available". This is **opt-in only** — recap-only or offline retros never probe (the skill's primary contract per `spec/inputs.md` is transcript-only; `mcp__re-frame2-pair__discover-app` is not in the default tool grant). If the user has not confirmed a probe, reason from the transcript alone — the skill's domain is the session shape, not the live runtime.
- when proposing a fix that adds or renames a tool, cross-reference the live catalogue rather than the skill's docs alone (same opt-in gate applies — only when runtime access is already part of the conversation)
- if `discover-app` was invoked and itself fails or returns `:reason :runtime-not-preloaded`, that becomes evidence the user's environment never had the re-frame2-pair surface — a finding in its own right, not a retro blocker

### Source-coordinate availability

Signals:
- "where in the source did this come from?" returned nothing
- `data--coord` annotation is off because it is opt-in
- the user expected DOM-to-source even though re-frame2 commits to the attribute, not the helpers

Typical improvements:
- preflight that reports whether source-coord annotation is enabled
- include the recipe for turning it on at startup
- recipe for parsing the attribute via the host's DOM access (CDP, querySelector, Playwright locator)

### Private-namespace reach-through

Signals:
- the tool or a recipe reaches into `re-frame.db`, `re-frame.router`, `re-frame.subs`, `re-frame.events`, or `re-frame.registrar`
- a re-frame2 minor version moves something and the recipe breaks

Typical improvements:
- audit reach-throughs and replace with public APIs from Tool-Pair
- file a GitHub issue against `day8/re-frame2` if the public surface is missing the needed capability
- add a lint or smoke test that flags private-namespace usage

### Multi-tool coexistence

Signals:
- the pair tool's listener key collides with Causa (or another tool's) listener
- listener-ordering assumptions fail (per Spec 009 listener ordering is not contract)
- multiple tools writing to the same trace consumer step on each other

Typical improvements:
- always register with a tool-specific key namespace
- never assume listener-ordering
- recipe for "I am attached alongside another tool" coexistence
