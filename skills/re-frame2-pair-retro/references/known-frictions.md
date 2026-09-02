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
- check first — this already ships: the four-tier cascade refuses with `:ambiguous-frame` rather than guessing, `set-operating-frame` / `get-operating-frame` / `reset-operating-frame` resolve it, and `discover-app` returns `:frames`. Unreached is tool-shaped (pair-skill wording); reached and still short names the framework surface
- add a "current operating frame" indicator in retrospective output

### Wrong listener for the question

Signals:
- the user reaches for raw trace events to answer "what happened in this cascade" — a job for the assembled epoch stream
- the tool re-derives `:sub-runs` / `:renders` / `:effects` per epoch instead of reading the structured projections on `:rf/epoch-record`
- the user wants per-emit detail (live timing, in-flight events) but is reading post-hoc epoch records

Typical improvements:
- recipe that names the listener for each question shape
- default to the `:epoch` stream for cascade-shaped questions; reach for the `:trace` stream only for per-emit detail
- check first — this already ships: the pair skill states the default (assembled epoch stream first; raw trace stream only for detail the projection drops), so unreached is discoverability, i.e. tool-shaped
- map those streams onto the routes a session actually calls: the `dispatch` envelope (settled, carrying the assembled epoch), `trace-window`, `watch-epochs`, `watch-until`, `record` / `read-recording`. Every observation arrives as a completed tool result and live-watch is poll-only — no subscription tool exists, so "why didn't they just subscribe?" is not available reasoning

### Time-travel restore failures

Signals:
- `restore-epoch` returned a `:rf.epoch/*` error and the user did not know how to recover
- the target epoch aged out silently because `:depth` was at default
- a hot-swapped handler or evolved schema invalidated an older snapshot, but the failure tag was not surfaced helpfully

Typical improvements:
- check first — this already ships: `restore-epoch` returns a structured refusal (`:restore-rejected`), and the seven failure modes with their `:tags` (`:history-size`, `:schema-digest-recorded`/`:current`, `:missing`, `:failing-paths`) are pinned in re-frame2's Tool-Pair contract — route there rather than re-listing them. Tags that never reached the user are tool-shaped; tags that did and still left the next step unclear are a presentation gap.
- a next-best-action per failure mode, which the contract pins but does not prescribe

### Dry-run versus a live write

Signals:
- a throwaway probe went through a live `dispatch` and took the frame's app-db with it — a handler returning `{:db <bare-map>}` REPLACES app-db wholesale
- "what would this event do?" was answered by committing it, then undoing it
- a write refused with `:restore-rejected` / `:reset-rejected` and a closed gate was read as a broken tool

Background: `dispatch-dry-run` runs the whole cascade and rolls back without firing fx — the safe primitive for "try this dispatch". The named write tools (`restore-epoch`, `replace-app-db`) sit behind the server's `--allow-writes` flag, **OFF by default**; that gate is the write boundary.

Typical improvements:
- check first whether `dispatch-dry-run` was reached for at all — available and unused is tool-shaped (pair-skill wording), not a missing surface
- a refusal read as a fault means it did not carry its own remedy — name the reason and what lifts it

### Wire-boundary privacy elision and the raw-eval carve-out

Signals:
- a structured read returned `:rf/redacted` or `:rf.size/large-elided` and it was read as missing or broken data
- raw `eval-cljs` was used to get around an elision, shipping verbatim app-db / trace / epoch state
- the retro cannot tell an absent value from a withheld one

Background: this is the **wire** boundary, not the build one (§Production-elision confusion below). Structured reads elide server-side under `--allow-sensitive-reads` (**OFF by default**); `eval-cljs` is default-ON and **not** governed by that gate, returning values un-elided — the raw-eval carve-out. A placeholder is a deliberate withholding, not a gap.

Typical improvements:
- separate "withheld at the wire boundary" from "absent in app-db" — conflating them invents a bug
- raw eval used to defeat an elision is a finding in its own right; the fix is usually the structured tool that fits

### Wire-size caps and pagination

Signals:
- a truncated, capped, or deduped page was read as a complete answer
- the same read was re-run instead of passing the previous response's cursor
- a `:rf.mcp/dedup-table` payload was reasoned about undecoded

Typical improvements:
- a capped page is unknown/incomplete, never a negative result — §Session evidence already rules this; the friction is the cap going unnoticed, so make it legible
- check whether the size-conscious args (`max-tokens`, `limit`/`cursor`, `path`, `mode`, `dedup`, `elision`) went unused (tool-shaped) or were used and still fell short (framework-shaped)

### Hot-reload baseline misuse

Signals:
- `tail-build` refused with `:missing-baseline` / `:baseline-without-probe` and that was treated as a tool bug
- a source edit was followed straight by a dispatch or trace read, so the session hit pre-reload code
- a reload that landed before the first sample was read as a spurious timeout

Typical improvements:
- the probe's baseline is captured **before** the edit; capturing it after is tool-shaped
- a correct baseline that still misled names the framework surface it fell short of

### Production-elision confusion

Signals:
- the trace stream, epoch history, or schema reflection returned empty and the user thought the tool was broken
- a production-elided build (`:advanced` + `goog.DEBUG=false`) darkened the dev-gated surfaces and the user read the partial result as everything-gone
- the user cannot tell whether they hit an elision wall or a tool bug

Background: production elision is a **mixed** result, not a total wall — the dev-gated families go dark while the always-answering families keep responding (orientation / registry-frame shape is the canonical surface that still answers), so a partial result is not a broken tool. The authoritative dark-vs-answering split is owned by re-frame2's Tool-Pair surface index (availability legend + production note) and `spec/009-Instrumentation.md` §What IS available in production; route to them rather than re-enumerating the surfaces here. Misclassifying a mixed production result as everything-is-gone abandons usable probes.

Typical improvements:
- check first — this already ships: `discover-app` reports `:debug-enabled?` and hints when the build is elided. Unreached is tool-shaped; reached and still misread means the result does not separate the dark surfaces from the answering ones (point at the routing index, don't re-list surfaces)
- recipe for switching to a dev build before continuing

### Error observability and recovery-model confusion

Applies when the session chased an error — why it fired, where it surfaced, or why the app didn't "recover" the way the user expected.

Signals:
- the agent or user expected an app-level error policy to swallow or substitute a result and was confused the framework applied its typed default — there is no app-steering recovery policy and no per-frame `:on-error` slot
- the session looked for a per-frame `:on-error` slot — there is none
- the agent assumed the error-emit listener elides in a CLJS production build and dismissed a production error report

Background: recovery and observability are distinct. Recovery is **framework-owned** — a typed per-category default, with no app-steerable per-frame `:on-error` policy; observability is the **always-on** error-emit listener, which survives production elision (only dev-side trace enrichments elide). The listener's shape, its production-survival guarantee, and the dev-only enrichments that DO elide are owned by `spec/009-Instrumentation.md` §Error observability — route there rather than re-stating them. Genuine recovery for *expected* failures is local-at-source (managed-HTTP `:retry`, optional-read fallback), not an app-steerable policy.

Typical improvements:
- turn a silent runtime fallback into a louder warning the agent can route to the user
- check first — this already ships: the pair skill's `errors.md` carries the trace-buffer recipe for recent `:rf.error/*` ops, so "I didn't know how to pull recent errors" is discoverability, i.e. tool-shaped
- route the fix: an unrecognised error category or unclear inspection recipe → improve the pair tool's error catalogue (`re-frame2-pair/references/errors.md`); a gap in the runtime's behaviour itself (a category the always-on substrate doesn't cover, missing structured `:tags`) → file upstream against `re-frame2`, cross-linking `spec/009-Instrumentation.md §Error observability`

### Tool-catalogue / build-capability uncertainty

Signals:
- the retrospective is unsure whether a tool the user "should have reached for" was actually exposed by the running re-frame2-pair-mcp build, or whether it was reasoning from stale docs
- the session reasons about tool availability from `re-frame2-pair/references/ops.md` alone — that doc can drift from the live catalogue the running server exposes (the authoritative live catalogue is whatever the attached server returns from `tools/list`; the pair-MCP conformance corpus pins each tool's wire shape but does not catch a recipe citing a tool the build never exposed)
- a "why didn't they use tool X?" thread surfaces with no way to confirm X was actually callable in that session

Typical improvements:
- when proposing a fix that adds or renames a tool, cross-reference the live catalogue rather than the skill's docs alone — a live-catalogue check is `re-frame2-pair`'s job (the retro itself never probes the runtime; a result the session never produced stays unknown/incomplete)

If the session itself ran `discover-app`, its result captures the live build's id, health, and session sentinel, and with the server's `tools/list` sanity-checks "tool X was actually available". When the session's probe failed, branch on the **diagnostic ladder** rather than collapsing every failure to "no surface here" — each reason points at a different next step, so collapsing them yields generic "add the preload" advice when the real fix differs. The ladder reasons (per `probe.cljs`, pinned by the conformance corpus): `:nrepl-unreachable` (shadow-cljs nREPL down — fix connectivity), `:build-not-running` (start the build), `:no-runtime-connected` (build runs but no browser tab attached — open/reload the tab, or pick the correct build), `:runtime-loaded-but-preload-missing` (runtime live but the re-frame2-pair preload absent — add the preload). `:runtime-not-preloaded` is the **degradation fallback** the ladder emits when it cannot otherwise classify — last-resort, not the normal-case verdict.

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
