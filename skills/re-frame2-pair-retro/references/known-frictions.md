# Known recurring frictions

Use this file as a pattern-check, not a substitute for session evidence. If the current session resembles one of these classes, flag it as a possible recurring product gap rather than a one-off.

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
- structured presentation of the seven named failure modes (the six `:rf.epoch/restore-*` modes — `restore-unknown-epoch`, `restore-schema-mismatch`, `restore-missing-handler`, `restore-version-mismatch`, `restore-during-drain`, `restore-non-ok-record` — plus unknown-frame via `:rf.error/no-such-handler`) with a next-best-action per mode
- preflight check before restore (depth, schema digest, machine version)
- surface `:history-size` and `:schema-digest-recorded`/`:current` in the tool's output

### Production-elision confusion

Signals:
- the trace stream, epoch history, or schema reflection returned empty and the user thought the tool was broken
- a production-elided build (`:advanced` + `goog.DEBUG=false`) darkened the dev-gated surfaces and the user read the partial result as everything-gone
- the user cannot tell whether they hit an elision wall or a tool bug

Background: production elision is NOT a total wall. Only the **dev-gated** surfaces elide under `re-frame.interop/debug-enabled?` false — the trace stream, schema validation, the epoch machinery (epoch-history / `restore-epoch` / the epoch streaming topic), and source-coordinate annotation. `orient` still returns registry/frame shape (built from `health` + frame ids + app-db top keys + registry counts, none dev-gated), the direct-read primitives (`snapshot` / `get-path` / `read-sub`) carry their own egress/privacy posture rather than the debug gate, and the always-on event/error-emit substrate (the `:events` / `:errors` streams of `register-listener!`, per Spec 009 §What IS available in production) keeps answering. Misclassifying a mixed production result as everything-is-gone abandons usable probes.

Typical improvements:
- preflight check that reads `re-frame.interop/debug-enabled?` and reports the answer
- make "I am attached to a production-elided build" a first-class result state, distinguishing the dev-only surfaces that go dark (trace / epoch / schema / source-coord) from the surfaces that still answer (orientation, registry/frame shape, the direct-read primitives, and the always-on event/error-emit listeners — observability, not an app-steerable recovery policy)
- recipe for switching to a dev build before continuing

### Error observability and recovery-model confusion

Applies when the session chased an error — why it fired, where it surfaced, or why the app didn't "recover" the way the user expected.

Signals:
- the agent or user expected an app-level error policy to swallow or substitute a result and was confused the framework applied its typed default — there is no app-steering recovery policy and no per-frame `:on-error` slot
- the session looked for a per-frame `:on-error` slot — there is none
- the agent assumed the error-emit listener elides in a CLJS production build and dismissed a production error report

Background: recovery is framework-owned (the typed per-category default); observability is the **always-on** error-emit listener — `(rf/register-listener! :errors id listener-fn)`, the `:errors` stream of the stream-parameterized listener verb. It is NOT gated by `re-frame.interop/debug-enabled?` and survives `:advanced` + `goog.DEBUG=false` for every catalogued production-reachable `:rf.error/*` (per `spec/009-Instrumentation.md` §Production elision) — registered listeners DO fire in prod. Only dev-side enrichments elide: the `:rf.trace/dispatch-id` / `:rf.trace/trigger-handler` source-coord correlation and the retain-N ring buffer ride the dev trace surface. Genuine recovery for *expected* failures is local-at-source (managed-HTTP `:retry`, optional-read fallback), not an app-steerable policy.

Typical improvements:
- turn a silent runtime fallback into a louder warning the agent can route to the user
- prefer a recipe over a doc paragraph when the friction is "I didn't know how to pull recent errors at the REPL"
- route the fix: an unrecognised error category or unclear inspection recipe → improve the pair tool's error catalogue (`re-frame2-pair/references/errors.md`); a gap in the runtime's behaviour itself (a category the always-on substrate doesn't cover, missing structured `:tags`) → file upstream against `re-frame2`, cross-linking `spec/009-Instrumentation.md §Error observability`

### Tool-catalogue / build-capability uncertainty

Signals:
- the retrospective is unsure whether a tool the user "should have reached for" was actually exposed by the running re-frame2-pair-mcp build, or whether it was reasoning from stale docs
- the session reasons about tool availability from `re-frame2-pair/references/ops.md` alone — that doc can drift from the live catalogue the running server exposes (the authoritative live catalogue is whatever the attached server returns from `tools/list`; the pair-MCP conformance corpus pins each tool's wire shape but does not catch a recipe citing a tool the build never exposed)
- a "why didn't they use tool X?" thread surfaces with no way to confirm X was actually callable in that session

Typical improvements:
- when proposing a fix that adds or renames a tool, cross-reference the live catalogue rather than the skill's docs alone (opt-in probe only — see SKILL.md §Guard rails)

If `discover-app` was run (opt-in and use-gated — see SKILL.md §Guard rails): it captures the live build's id, health, and session sentinel, and with the server's `tools/list` sanity-checks "tool X was actually available". If the probe itself fails, branch on the **diagnostic ladder** rather than collapsing every failure to "no surface here" — each reason points at a different next step, so collapsing them yields generic "add the preload" advice when the real fix differs. The ladder reasons (per `probe.cljs`, pinned by the conformance corpus): `:nrepl-unreachable` (shadow-cljs nREPL down — fix connectivity), `:build-not-running` (start the build), `:no-runtime-connected` (build runs but no browser tab attached — open/reload the tab, or pick the correct build), `:runtime-loaded-but-preload-missing` (runtime live but the re-frame2-pair preload absent — add the preload). `:runtime-not-preloaded` is the **degradation fallback** the ladder emits when it cannot otherwise classify — last-resort, not the normal-case verdict.

### Source-coordinate availability

Signals:
- "where in the source did this come from?" returned nothing
- `data-rf2-source-coord` is absent because the build is production-elided (`interop/debug-enabled?` false), the element was not produced by a registered view (anonymous/unregistered fn — only registered-view ROOT elements carry the attribute; for a descendant node the tool walks up to the nearest annotated ancestor), or the view root is a Fragment / non-DOM exemption — the annotation is mandatory in dev builds per Spec 006 §Source-coord annotation, not opt-in
- the user expected DOM-to-source even though re-frame2 commits to the attribute, not the helpers

Typical improvements:
- preflight that reports build mode (dev vs production-elided) and registered-view coverage — `discover-app`'s `:coord-annotation-enabled?` field already reports this (heuristic: any element on the page carries `data-rf2-source-coord` or `data-rc-src`)
- recipe for switching to a dev build and/or registering the view via `reg-view` (with re-com's `data-rc-src` / `:src (at)` as the fallback source-coord source)
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
- the pair tool's listener key collides with Xray (or another tool's) listener
- listener-ordering assumptions fail (per Spec 009 listener ordering is not contract)
- multiple tools writing to the same trace consumer step on each other

Typical improvements:
- always register with a tool-specific key namespace
- never assume listener-ordering
- recipe for "I am attached alongside another tool" coexistence
