# tools/mcp-conformance

End-to-end **MCP-client** conformance harness for the re-frame2 MCP
servers — `re-frame2-pair-mcp` and `story-mcp`. Source: rf2-cum40.

(Historical: a third server `xray-mcp` was envisaged; it was dropped
per rf2-hvl1g — AI agent access to Xray state flows via
`re-frame2-pair-mcp` against the framework-published Xray runtime API.)

This artefact has four surfaces:

1. **`test/end-to-end-*.cjs`** — Node-side end-to-end conformance.
   Drives each server through the official `@modelcontextprotocol/sdk`
   client to validate JSON-RPC handshake + tool catalogue + one
   canonical workflow per server. Source: rf2-cum40.
2. **`wire-vocab/`** — JVM-side cross-server wire-vocabulary
   conformance. Pins the canonical Malli schema for every
   reserved `:rf.mcp/*` / `:rf.size/large-elided` / `:rf.elision/at`
   marker and asserts that fixtures + source text from every
   emitting server conform. Source: rf2-j2z7o. See
   [`wire-vocab/README.md`](wire-vocab/README.md).
3. **[`NAMING.md`](NAMING.md)** — the cross-MCP tool-naming
   convention: which verbs the catalogues pick from
   (`get-` / `list-` / `read-` / `discover-` / `restore-` / `reset-`
   / `register-` / `unregister-` / `run-` / `preview-` / `record-as-`
   / `tail-` / `dispatch` / `eval-cljs` / `subscribe` / `unsubscribe`
   / mega-op bare verbs), with one-line semantics and the per-server
   audit table. Source: rf2-mzf1r.
4. **[`TOKEN-BUDGETS.md`](TOKEN-BUDGETS.md)** — the cross-MCP
   token-budget posture: the 5,000-token default cap, the
   `max-tokens` per-call override, the `:rf.mcp/overflow` retry
   marker, per-server mechanism inventory, chained-budget rules
   when an agent attaches both servers in one session, and the
   deliberate divergences between server implementations.
   Source: rf2-ll0yq.

## What this is

Every existing test surface for these servers is **server-side**:

- per-tool unit tests (under each server artefact's `test/`)
- `stdio-roundtrip.js` — a hand-rolled JSON-RPC wire-format probe
- `live-nrepl.js` — workflow tests against a running runtime

None of these drive a server from the **client** side of MCP — they all
either hand-roll JSON-RPC framing or skip the protocol layer entirely.
That leaves a gap: when a real MCP-aware consumer (Claude Code,
Continue, etc.) talks to one of these servers, it goes through the
official `@modelcontextprotocol/sdk` `Client`, which validates **every**
response against the spec's Zod schemas (`InitializeResultSchema`,
`ListToolsResultSchema`, `CallToolResultSchema`, …). A server response
that's "close enough" to the spec to fool a hand-rolled probe can still
fail the SDK's parse step, and that bug ships unobserved until a real
consumer attaches.

This harness closes that gap. It spawns each server through the SDK's
`StdioClientTransport`, completes the full `Client.connect()`
handshake, walks one canonical workflow per server using `listTools()`
and `callTool()`, and tears the transport down cleanly. Any spec drift
on the server side surfaces as an SDK parse-error.

### callTool() coverage ratchet (rf2-ke5n56)

`listTools()` + `assertDescriptorShape` + `assertClassificationRatchet`
pin every advertised tool's *descriptor metadata*. But descriptor
metadata is not the wire envelope: a tool that is only LISTED (never
driven through `callTool()`) can regress its result envelope,
`outputSchema` / `structuredContent` shape, or argument handling and
still ship green. A senior review found several advertised Story + Pair
tools were exactly this — descriptor-only in conformance.

`assertCallCoverageRatchet` (in [`test/_runner.cjs`](test/_runner.cjs))
closes it. Each end-to-end harness records every tool it drives through
`callTool()` and asserts at the end that EVERY advertised tool was
either SDK-called or listed in a reviewed exclusion table with a
non-empty rationale naming where its coverage lives (a live-only gate, a
hermetic harness, a unit/fixture pin). A new advertised tool the
workflow forgets to probe trips RED; a stale, blank, or contradictory
exclusion row also trips RED, so the table cannot rot into a rubber
stamp. The ratchet's own teeth are unit-tested in
[`test/call-coverage-ratchet.test.cjs`](test/call-coverage-ratchet.test.cjs).
Today both servers' exclusion tables are EMPTY — every advertised tool
is SDK-called (Story reads + write-loop; Pair degraded `:nrepl-port-not-
found` envelopes for the read/session/streaming-shaped surface +
`:rf.error/writes-disabled` pre-connection refusals for the two gated
writes).

### Coverage boundary — keyword-intern bounds (rf2-fphr3)

The charter's threat model asks whether the corpus has teeth on the
**keyword-intern bounds** — the DoS surface where a server keywordizes
an unbounded stream of agent-supplied strings (event ids, sub ids,
frame ids, mode/slice args) into the JVM's never-shrinking global
keyword table. **This is NOT a cross-server WIRE contract** (the
conformance gate's natural remit — egress redaction, overflow markers,
flag gating, descriptor shape); it is a per-server **input-sanitisation**
property, and it is **covered, by construction, at the `mcp-base` unit
layer** — the shared coercion ns every server routes agent-supplied ids
through (the conformance gate covers the wire; intern bounding is
covered by `mcp-base`'s `args` tests):

- **`re-frame.mcp-base.args/safe-keyword`** — the bounded-allowlist
  resolver. Membership-checks BEFORE constructing the keyword (via JVM
  `find-keyword`, which never interns), so a caller-supplied string
  outside the finite set never mints a fresh keyword. Pinned by
  `tools/mcp-base/test/re_frame/mcp_base/args_test.clj`
  (`safe-keyword-disallowed-string-returns-nil-and-does-not-intern`
  for the bare-name arm, plus
  `safe-keyword-disallowed-NAMESPACED-string-returns-nil-and-does-not-intern`
  — rf2-ynjts.17 — for the namespaced arm the registry-backed frame-id
  / `:rf.assert/*` coercions actually hit). Both assert via a
  `find-keyword` probe that the rejected novel name is absent from the
  table both before and after the call: a literal no-intern-on-rejection
  proof.
- **`re-frame.mcp-base.args/parse-mode`** — routes every finite-set arm
  through `safe-keyword` so the membership check precedes any intern
  (rf2-ih7g4 flipped the historical intern-then-check order). Pinned by
  `parse-mode-unknown-string-does-not-intern`.
- **`re-frame.mcp-base.args/fresh-keyword`** — the deliberately-interning
  primitive, reserved (per its docstring policy) for **bounded-cost**
  sites only: operator-gated write paths (story-mcp's `--allow-writes`
  + the `:story.<path>/<name>` grammar bound), runtime-registry-bounded
  read paths (re-frame2-pair-mcp's frame-id coercion, where the live
  registry size caps allocation), and grammar-bounded registrars.
  Pinned by `fresh-keyword-interns-on-fresh-input` (rf2-xxtrz).

On the wire-validated paths the conformance corpus DOES drive
(`dispatch` event ids, `read-sub` sub ids), the server additionally
VALIDATES the id against the live registrar and refuses unknowns with
`:reason :unknown-id` + `:nearest` matches (rf2-3bu3d.3) — bounding the
accepted set to the registered universe before any intern. So the
intern surface is doubly bounded: the `mcp-base` allowlist/registry gate
upstream, and the registrar validation at the tool body.

This note records the seam so a reviewer does not read the conformance
gate's silence on intern bounds as "uncovered". If a future open-world
intern path emerges that is bounded by NEITHER a `mcp-base` allowlist nor
a runtime registry (e.g. an arbitrary-topic-string surface that
keywordizes without a finite gate), that is a server-side bug to file
against `mcp-base` / the owning server — not a hole in this cross-server
wire gate.

### Coverage boundary — EP-0017 cofx + EP-0018 event metadata (rf2-jyxmtq / rf2-z0owya)

The EP-0017 recordable-coeffects (`cofx`) surface and the EP-0018 unified
event-registration metadata are exercised across THREE layers; a future
MCP change to either should land in the layer matching what it touches:

- **SDK conformance harness (this artefact)** — the cross-MCP WIRE
  contract. EP-0017: a `cofx` arg is accepted into the `dispatch` request
  envelope (degraded `end-to-end-re-frame2-pair.cjs`); the supplied
  `:rf/time-ms` reaches the resulting state, the malformed-`cofx`
  structured refusals, and the `cofx`-kind `list-handlers`/`handler-meta`
  visibility + authored `:rf.cofx/requires` (live
  `live-re-frame2-pair-cofx.cjs`). EP-0018: the live event-metadata wire
  reflects the unified shape and carries none of the retired markers (live
  `live-re-frame2-pair-event-meta.cjs`). The degraded handler short-circuits
  every live-runtime tool to `:nrepl-port-not-found` BEFORE the tool body —
  so the cofx shape-check and the live event metadata are reachable ONLY
  through the hermetic live gates, NOT the degraded harness (which proves
  descriptor / arg-shape / CallToolResult wiring). The two live gates use
  the fixture event `:counter/stamp` (EP-0017 `:rf.cofx/requires`) and
  `:counter/inc` (EP-0018 plain `reg-event`) at
  `skills/re-frame2-pair/tests/fixture/src/counter/core.cljs`.
- **pair-MCP unit tests** — the server-side `cofx` parsing / threading
  under `:rf.cofx`, owner-qualified facts, omitted-key behaviour, and the
  malformed-input reasons in isolation
  (`tools/re-frame2-pair-mcp/test/.../dispatch_test.cljs`); event
  `handler-meta` projection shape
  (`.../handler_meta_test.cljs`). These pin the projection / parse logic
  without a live runtime or the SDK boundary.
- **core tests** — the EP-0017 / EP-0018 semantics themselves: the router
  preserving a supplied `:rf.cofx` verbatim, `:rf.cofx/requires` parse /
  satisfaction, the one `:rf/event-handler` wrapper, the removed
  `:event/kind`, and the retired-name hard errors
  (`implementation/core/.../events.cljc` + its tests). These own the
  behaviour the wire gates merely OBSERVE crossing the MCP boundary.

Keep this surface free of the legacy `world-inputs` / `:rf.world/inputs`
vocabulary as a LIVE assertion — EP-0017 replaced it with `cofx` /
`:rf.cofx` / `:rf/time-ms`; any historical mention must name the
replacement.

## Files

- `package.json` — depends on `@modelcontextprotocol/sdk` only
- `test/end-to-end-re-frame2-pair.cjs` — re-frame2-pair-mcp conformance (degraded mode,
  no nREPL needed — same shape as re-frame2-pair-mcp's stdio-roundtrip)
- `test/live-re-frame2-pair-overflow.cjs` — re-frame2-pair-mcp **live**-runtime variant
  (rf2-ynaoc) that exercises the wire-cap `:rf.mcp/overflow` marker
  under a real over-budget eval. **Gated on `$SHADOW_CLJS_NREPL_PORT`**:
  unset = clean SKIP (degraded mode can't trip the cap naturally),
  set = full live-runtime cap-trigger conformance against the
  canonical `ReFrame2PairOverflowBody` schema pinned by `wire-vocab/`.
- `test/live-re-frame2-pair-subscribe.cjs` — re-frame2-pair-mcp **live**-runtime variant
  (rf2-zb5z6) that exercises the `notifications/progress` streaming
  wire surface. Subscribes to `:trace`, dispatches a known event,
  collects every progress frame the server emits, and validates each
  against the canonical `ReFrame2PairProgressNotificationParams` schema
  pinned by `wire-vocab/`. Gated on `$SHADOW_CLJS_NREPL_PORT` (same
  posture as the overflow variant).
- `test/live-re-frame2-pair-redaction.cjs` — re-frame2-pair-mcp **live**-runtime variant
  (rf2-q4o83) that exercises egress redaction of a FRAME-OWNED
  `:sensitive {:app-db …}` slot through the pull-mode epoch tools
  (`trace-window` + `watch-epochs`). Declares the sensitive slot app-side
  through the PUBLIC EP-0015 frame-owned route (`reg-frame` with a
  `{:sensitive {:app-db [[…]]}}` classification — rf2-2h7153; NOT a
  hand-seeded elision registry and NOT schema-attached / importer-driven
  classification, both of which EP-0015 removed), dispatches a recognisable
  sentinel into it, then asserts the sentinel is ABSENT in the egress
  payload with the `--allow-sensitive-reads` gate OFF (default) — the whole
  sensitive epoch is DROPPED (`:dropped-sensitive` >= 1) — and SHIPS with
  the gate ON + `:include-sensitive true`. A third arm declares the path
  through the same frame-owned route AFTER the epoch is recorded, so the
  inner `projected-record` value-redaction is the sole protection (sentinel
  ABSENT + `:rf/redacted` PRESENT + `:dropped-sensitive 0`). Pins BOTH gate
  directions so the gate can't silently invert, and proves the frame-owned
  route reaches the epoch rollup AND the projected-record egress. This is
  the regression net for rf2-6wvh5 (the leak the gate let ship green) and
  rf2-2h7153 (the frame-owned route bypass). Gated on
  `$SHADOW_CLJS_NREPL_PORT` (same posture as the other live variants). Has
  a standalone `npm run test:re-frame2-pair-live-redaction` script
  (rf2-ybiz0) for local runnability + parity with the overflow / subscribe
  variants — without a live port it SKIPs, with one (e.g. an
  externally-attached shadow-cljs) it runs the full gate.
- `test/live-re-frame2-pair-cofx.cjs` — re-frame2-pair-mcp **live**-runtime variant
  (rf2-jyxmtq) pinning the EP-0017 recordable-coeffects (`cofx`) paths on
  the SDK boundary. Dispatches the fixture's `[:counter/stamp]` (a
  `reg-event` declaring `:rf.cofx/requires [:rf/time-ms]` that folds the
  recorded wall-clock fact into app-db under `:stamped-at`) with a scripted
  `cofx "{:rf/time-ms <N>}"` — TWICE with two distinct pinned-past times —
  and asserts each lands verbatim in `:stamped-at` (reproducible-dispatch
  determinism; a server stamping a live clock could never match two pinned
  pasts). Then proves the malformed-`cofx` refusals the degraded handler
  hides (non-map / unreadable / non-integer `:rf/time-ms` ⇒
  `:invalid-cofx` / `:invalid-cofx-time-ms`, no state mutation), and the
  EP-0017 tooling visibility: `list-handlers {kind "cofx"}` discovers
  `:rf/time-ms`, `handler-meta {kind "cofx" id ":rf/time-ms"}` surfaces the
  recordable grade (`:recordable? true :provided? true`), and the event
  fixture's `handler-meta` exposes the authored `:rf.cofx/requires`. Gated
  on `$SHADOW_CLJS_NREPL_PORT` (same posture as the other live variants).
- `test/live-re-frame2-pair-event-meta.cjs` — re-frame2-pair-mcp **live**-runtime
  variant (rf2-z0owya) pinning the EP-0018 unified event-registration
  metadata on the SDK boundary. `list-handlers {kind "event"}` discovers
  the fixture `rf/reg-event` id `:counter/inc`; `handler-meta {kind "event"
  id ":counter/inc"}` asserts `:ok? true` / `:kind :event` / `:id` AND
  rejects EP-0018 drift on the same wire response — NO `:event/kind`
  sub-tag, NO `:rf/db-handler` / `:rf/fx-handler` / `:rf/ctx-handler`
  retired wrapper id, and the unified `:rf/event-handler` wrapper visible in
  the effective interceptor chain. Closes the gap the degraded-only
  `handler-meta` / `list-handlers` coverage left (degraded short-circuits to
  the same `:nrepl-port-not-found` envelope for every live-runtime tool, so
  the live event-metadata wire was never inspected). Gated on
  `$SHADOW_CLJS_NREPL_PORT`.
- `scripts/run-live-re-frame2-pair-overflow-hermetic.cjs` — hermetic
  orchestrator (rf2-uw6d6) that boots shadow-cljs against the re-frame2-pair
  fixture (`skills/re-frame2-pair/tests/fixture/`), launches headless
  Chromium so the runtime preload lands, then runs every live inner test
  (`live-re-frame2-pair-overflow.cjs`, `live-re-frame2-pair-subscribe.cjs`,
  `live-re-frame2-pair-redaction.cjs`, `live-re-frame2-pair-iserror.cjs`,
  `live-re-frame2-pair-cofx.cjs`, `live-re-frame2-pair-event-meta.cjs`)
  against the spawned
  `SHADOW_CLJS_NREPL_PORT`. Closes the CI-coverage gap the SKIP path
  leaves on each. **Observable-SKIP guard (rf2-ybiz0):** after each inner
  test exits 0 the orchestrator asserts it printed its `... CONFORMANCE
  GREEN` sentinel AND did NOT print a `SKIP ` banner — so a setup-path
  regression that left a load-bearing live gate SKIPping (exit 0, never
  exercised) turns the hermetic job RED (exit 2, orchestration failure)
  instead of riding green on the other inner tests.
- `test/end-to-end-story.cjs` — story-mcp conformance (full write-loop
  with `--allow-writes` enabled) + closed-world read-path success
  envelopes
- `lib/dedup-envelope.cjs` — Node-side mirror of `de-dupe.core/expand`
  for the `:rf.mcp/dedup-table` wire envelope (rf2-90eft). story-mcp +
  pair-mcp wrap selected tools' `:structuredContent` in
  `{:rf.mcp/dedup-table <cache>}`; a real MCP client decodes by
  calling `de-dupe.core/expand` before user code sees the payload.
  `decodeDedupEnvelope` is the Node-side mirror: idempotent on
  unwrapped payloads, so harnesses can route every
  `:structuredContent` slot read through it and talk to the SEMANTIC
  contract (e.g. `:lifecycle` reachable) rather than the wire shape.
- `test/end-to-end-flag-gates.cjs` — cross-MCP operator-opt-in CLI
  flag-vocabulary conformance (rf2-ee38b.20). Boots story-mcp without /
  with / with-a-legacy `--allow-writes` spelling and asserts the
  default-OFF write gate (`structuredContent.gated` + `.tool`) + the
  hard-rename-rejection rule (an unrecognised flag must NOT open the
  gate) over the MCP wire — the NAMING.md §"Operator-opt-in CLI flag
  vocabulary" contract. rf2-ke5n56 extended the default-OFF coverage from
  `register-variant`-only to ALL the gated write tools: `register-variant`
  + `unregister-variant` (gate-first refusal, `:tool` slot pinned) +
  `record-as-variant` write-back (both gate states — see the VERIFIED
  gate-ordering note below), plus the intentionally UNGATED
  snippet-only `record-as-variant` path. Needs `clojure` (the JVM
  story-mcp server). The
  pair-mcp eval-cljs gate flipped to default-ON in rf2-a0z0h; its
  disabled-envelope wire counterpart now rides the live
  `live-re-frame2-pair-subscribe.cjs` path (the one boot config where
  the gate is observable — non-degraded, WITH `--no-eval`).

### VERIFIED: `record-as-variant` write-back gate ORDERING asymmetry (rf2-ke5n56, flagged)

While wiring the rf2-ke5n56 default-OFF gated-refusal coverage for
`record-as-variant` write-back, the harness author verified a server-side
gate-ordering asymmetry worth flagging to the story-mcp owner:

- `register-variant` / `unregister-variant`
  (`tools/story-mcp/.../tools/write.cljc`) check `assert-writes-allowed`
  as their FIRST expression — BEFORE any variant resolution — so a
  gate-OFF boot refuses with `structuredContent.gated === true`
  regardless of whether the target variant exists.
- `record-as-variant`
  (`tools/story-mcp/.../tools/recorder.cljc` `tool-record-as-variant`)
  wraps the whole body in `(targs/with-variant arguments (fn [vk body] …))`,
  which resolves `:variant-id` against the live registered-variant set
  FIRST; the `(when write-back? (write/assert-writes-allowed …))` gate is
  NESTED inside that callback.

The canonical-vocabulary boot registers NO variants, and a gate-OFF boot
cannot register one through the MCP surface, so a gate-OFF
`record-as-variant` write-back:true call short-circuits to `Variant not
found` BEFORE the gate is consulted — the `gated:true` envelope is
**unreachable default-off**. The literal default-OFF `gated:true` probe the
acceptance criterion describes is therefore impossible via the MCP surface
without either (a) reordering the gate ahead of variant resolution
(consistent with the two siblings), or (b) a registered fixture under a
closed gate (no single boot provides both).

`end-to-end-flag-gates.cjs` `assertRecordAsVariantWriteBackGate` pins what
IS reachable on both gate states — gate-OFF write-back:true ⇒ `Variant not
found` (regression-locking the current ordering), gate-ON write-back:true
⇒ success + `:written-back? true` (the positive control) — and its
gate-OFF assertion is written so that if the server is later reordered
gate-first, it flips RED with a message telling the maintainer to update
it to expect `gated:true` + `tool:"record-as-variant"`. The
intentionally-ungated snippet-only path (`record-as-variant` without
`:write-back`) is pinned by `assertSnippetOnlyRecordIsUngated`. Whether to
reorder the gate is a server-owner decision (filed as a follow-up); the
conformance harness does NOT modify server source.

## How to run

From this directory:

```bash
# REQUIRED in a fresh worktree — these gates drive the servers through
# the official @modelcontextprotocol/sdk `Client`, which is NOT vendored.
# Without this, `npm run test:story` / `test:re-frame2-pair` fail at
# `require('@modelcontextprotocol/sdk/client/index.js')`. (Worktrees that
# junction node_modules from the mayor checkout already have it; a clean
# `git worktree add` does not.)
npm install

# re-frame2-pair-mcp: requires the server bundle on disk at
# ../re-frame2-pair-mcp/out/server.js. Build it first with:
#   cd ../re-frame2-pair-mcp && npx shadow-cljs compile server
npm run test:re-frame2-pair

# story-mcp: requires `clojure` on PATH (override via
# $STORY_MCP_CMD if non-default). No pre-build step — the server is
# launched via `clojure -M -m re-frame.story-mcp.server`.
npm run test:story

# Both (re-frame2-pair must be pre-built):
npm test
```

## What each test covers

### `end-to-end-re-frame2-pair.cjs`

1. Connect — full SDK handshake against the freshly spawned bundle
2. `tools/list` — confirm the advertised tools match the pinned
   catalogue (sourced from re-frame2-pair-mcp's `tool-names.json`
   fixture — the single source of truth)
3. Spot-check every descriptor carries an `inputSchema`
4. Walk degraded-mode `callTool()` for EVERY advertised tool (rf2-ke5n56)
   — the read / session / streaming-shaped surface returns the shared
   `:nrepl-port-not-found` envelope; the two gated writes
   (`restore-epoch` / `replace-app-db`) return the
   `:rf.error/writes-disabled` PRE-connection refusal (default-OFF write
   gate). Each call routes through the SDK's `CallToolResultSchema` parse
   step. The `callTool` coverage ratchet then asserts no advertised tool
   was left descriptor-only.
5. Clean `Client.close()`

Runs without an nREPL on `$SHADOW_CLJS_NREPL_PORT`, so it's
self-contained and reproducible.

### `live-re-frame2-pair-overflow.cjs`  (rf2-ynaoc)

Live-runtime variant — fills the gap left by the degraded-mode
sibling above. Gated on `$SHADOW_CLJS_NREPL_PORT`; unset = clean
SKIP. When attached:

1. Connect — full SDK handshake.
2. `tools/call eval-cljs` with `(apply str (repeat 25000 "x"))` —
   25,000-char return ⇒ ~6,250 token-estimate ⇒ over the 5,000-token
   default cap.
3. SDK's `CallToolResultSchema` accepts the envelope; `isError` is
   `false` (overflow is a signal, not an error).
4. Response text carries `:rf.mcp/overflow`.
5. Marker body validates against canonical `ReFrame2PairOverflowBody`
   schema pinned by `wire-vocab/`: `:limit :reached`, integer `:cap-tokens`
   and `:token-count` with `:token-count > :cap-tokens`, string
   `:tool` and `:hint`.
6. Pin per-tool facts: `:cap-tokens = 5000` (default), `:tool =
   "eval-cljs"`, `:hint` contains "Slice" (per-tool entry from
   re-frame2-pair-mcp's `overflow-hints` table).
7. Recursion-safety: the marker itself fits under the cap.
8. Clean `Client.close()`.

Catches: cap-trigger threshold drift; marker shape regressions that
only fire on real payloads; client-side parse failures on cap-marker
shapes the SDK's strict `CallToolResultSchema` doesn't yet
recognise; keyword renames (`:cap-tokens` → `:cap_tokens`,
`:rf.mcp/overflow` → `:rf.mcp/overflows`) at the live emission site.

### `scripts/run-live-re-frame2-pair-overflow-hermetic.cjs`  (rf2-uw6d6)

Hermetic orchestrator that makes the live path above run on CI
without any external nREPL.

1. Wipes any stale `target/shadow-cljs/nrepl.port` under the re-frame2-pair
   fixture (`skills/re-frame2-pair/tests/fixture/`).
2. `npm install` in the fixture (idempotent — skipped if
   `node_modules/` already exists).
3. Spawns `shadow-cljs watch app` against the fixture (a minimal
   counter with `re-frame2-pair.runtime` already wired as a
   `:devtools :preloads` entry).
4. Polls for the nREPL port file, then the nREPL TCP listener, then
   the dev-http on `:8030`.
5. Launches headless Chromium (Playwright, resolved from
   `tools/mcp-conformance` or `implementation/`), navigates to the
   fixture URL, waits for `window.__re_frame2_pair_runtime` to land
   so re-frame2-pair-mcp's `ensure-runtime!` will pass.
6. Runs `test/live-re-frame2-pair-overflow.cjs` with
   `SHADOW_CLJS_NREPL_PORT` set to the spawned port.
7. Tears down browser + shadow-cljs in `finally` (and on SIGINT /
   SIGTERM / SIGHUP). The teardown is an idempotent **async** operation
   (rf2-7ckmwx): it `await`s Playwright's promise-returning
   `browser.close()` (bounded so a wedged close can't hang), then
   SIGTERMs shadow-cljs and `await`s its `exit` (or a short grace),
   escalating to SIGKILL and awaiting the final exit. The normal
   `finally` path awaits it before reporting success / exiting; the
   signal and watchdog paths race it against a hard cap so an
   interrupted run still exits promptly but does not fire-and-forget
   exit before the children have had a real chance to be reaped. The
   `makeCleanup` factory is exported and unit-tested by
   `test/runner-cleanup.test.cjs`.

Exit codes: `0` = green; `1` = conformance failure (forwarded from
the inner test); `2` = orchestration failure (shadow-cljs didn't
boot, runtime didn't preload, watchdog elapsed).

Watchdog: 360s for the whole hermetic run. The re-frame2-pair-mcp server
bundle must already be compiled — the script bails with a structured
error if `tools/re-frame2-pair-mcp/out/server.js` is missing.

#### Fixture dependency install — supported pattern (rf2-o0tpo)

The hermetic orchestrator's step 2 (`npm install` inside the re-frame2-pair
fixture at `skills/re-frame2-pair/tests/fixture/`) is the **supported
pattern** for this artefact, confirmed by rf2-o0tpo (pragmatic stance,
2026-05-14). Rationale:

- The fixture is a self-contained Node project with its own
  `package.json`; nested `npm install` is how Node projects compose.
- The dev runs the orchestrator deliberately; this isn't a hidden side
  effect of a generic test invocation.
- The install is idempotent (skips when `node_modules/` already exists)
  so the second run is hot.
- Moving the install to an explicit bootstrap script would add a
  separate setup step every dev / CI runner has to remember and gate.
  The current shape — invoke the orchestrator, it ensures its own
  dependencies — is simpler and the failure mode is loud (the install
  fails or shadow-cljs fails to boot; nothing silent).

If you adopt this orchestrator's pattern for a new conformance fixture,
follow the same shape: nest the fixture's `package.json`, gate the
install behind an existence check on `node_modules/`, and document the
fixture's location and entry script in the orchestrator's preamble.

### `end-to-end-story.cjs`

The single SDK-driven agent-loop harness for story-mcp's write surface.
It absorbed the four smokes that `tools/story-mcp/test/live-server.js`
used to add on top of a hand-rolled copy of this same loop (rf2-2mx0q),
so CI runs one JVM boot here instead of two near-identical ones.

1. Connect — `clojure -M -m re-frame.story-mcp.server --allow-writes`
2. `tools/list` — confirm the advertised catalogue per story-mcp's
   `tool-names.json` fixture (count-free per NAMING.md §"Single source
   of truth for tool counts")
3. `assertDescriptorShape` — every descriptor: `inputSchema`
   (type=object + `max-tokens`) + `outputSchema` + an `annotations`
   classification hint
4. Closed-world reads (`get-story-instructions` / `list-substrates` /
   `list-modes` / `list-tags` / `list-stories` / `list-assertions` /
   `list-decorators`) → success envelopes; `get-story` /
   `get-docs-markdown` default-off refusal probes (rf2-ke5n56) →
   `Story not found`
5. `register-variant` → `get-variant` (body `:doc` round-trips through
   EDN text) → `variant->edn` (the rf2-vyacl outputSchema-defect twin) +
   `read-a11y-violations` (JVM-standalone empty read) → `preview-variant`
   (`:lifecycle` surfaces) → `run-variant` (vacuous pass) →
   `read-failures` (total=0) → `snapshot-identity` (stable content-hash
   twice) → `record-as-variant` (recorder bridge wired, rf2-luhdu) →
   `unregister-variant` + not-found verify
6. `assertJsonRpcErrorCodes` — MethodNotFound + (InvalidParams|InternalError)
7. `callTool` coverage ratchet (rf2-ke5n56) — every advertised tool
   SDK-called or reviewed-excluded
8. Clean `Client.close()`

Watchdog: 90s (cold JVM boot is ~10–30s on a CI runner).

## CI

`.github/workflows/test.yml` runs each of the conformance scripts in
its own `mcp-conformance-{re-frame2-pair,story}` job, parallel to the
existing `node-test-tools-{re-frame2-pair,story}-mcp` jobs. Same
Node 24 + JDK 21 setup as those jobs.

The `mcp-conformance-re-frame2-pair` job runs four steps in sequence:

0. **`test:unit`** (rf2-md05gp) — the FULL pure-Node unit-regression
   cluster: every `node --test` row from the authoritative inventory in
   `scripts/test-all.cjs` (exec-safety, runner-watchdog, runner-cleanup,
   hermetic-setup-timeout, port-file-escape, dedup-envelope,
   call-coverage-ratchet, classification-ratchet). These are the harness's
   own regression nets — watchdog teardown of hung clients, awaited
   hermetic cleanup, bounded setup commands, symlink-safe port-file reads,
   dedup-envelope decoding, and the call/classification ratchets. It runs
   FIRST so a cheap unit regression fails before the heavier server boots,
   and is the natural home for the unit set (Node-only, no `clojure`).
   `test:unit` DERIVES its set from `test-all.cjs` rather than re-listing,
   so a new `node --test` row is picked up automatically. Pre-rf2-md05gp
   only `exec-safety` ran in CI; the other seven gates were in the local
   inventory but dead in CI, so a regression they guard could ship green.
1. **`test:re-frame2-pair`** — degraded-mode conformance against the SDK's
   strict schemas.
2. **`test:re-frame2-pair-live-overflow`** — the gated live variant. Runs
   without `$SHADOW_CLJS_NREPL_PORT` so the SKIP path is exercised
   on every CI run (a regression that broke SKIP would surface here).
3. **`test:re-frame2-pair-live-overflow-hermetic`** (rf2-uw6d6) — boots
   shadow-cljs against the re-frame2-pair fixture and runs the live overflow
   path with a real over-budget eval. This is the path that catches
   cap-trigger threshold drift, marker shape regressions on real
   payloads, and SDK strict-schema rejection of cap-marker shapes
   under CI's clean ephemeral runtime — not just on Mike's machine. The
   hermetic suite also runs `live-re-frame2-pair-subscribe.cjs`, which
   (booting non-degraded WITH `--no-eval`) pins pair-mcp's eval-cljs
   opt-out wire envelope (`:rf.error/eval-cljs-disabled`) post-rf2-a0z0h;
   `live-re-frame2-pair-iserror.cjs` (the read-family genuine-error
   isError contract); `live-re-frame2-pair-cofx.cjs` (rf2-jyxmtq — EP-0017
   reproducible-dispatch + cofx tooling visibility); and
   `live-re-frame2-pair-event-meta.cjs` (rf2-z0owya — EP-0018 unified
   event-metadata wire shape + retired-marker rejection). Each inner test
   carries a success sentinel the orchestrator asserts (rf2-ybiz0), so a
   silent in-hermetic SKIP turns the job RED.

The `mcp-conformance-story` job runs two steps: **`test:story`** (the
write-loop + read-path conformance + the rf2-ke5n56 callTool coverage
ratchet) and **`test:flag-gates`** (the cross-MCP CLI flag-vocabulary
conformance — story-mcp `--allow-writes` default-OFF for all three gated
write tools + the ungated snippet-only path + opt-in + hard-rename
rejection). Both need `clojure`. The `callTool` coverage ratchet's own
unit tests (`test:call-coverage-ratchet`) run in PR CI as part of the
`test:unit` cluster in the `mcp-conformance-re-frame2-pair` job (above) —
the Node unit set is server-agnostic, so it runs once there rather than
being duplicated in this `clojure`-only job. Locally the full inventory
(unit + e2e + live + story) runs via `npm test` (`scripts/test-all.cjs`).

## Why a separate artefact?

The harness only depends on `@modelcontextprotocol/sdk` and Node's
stdlib. Putting it under each server's `test/` would duplicate the
dependency and tie the client-side fixture to each server's
build/dependency graph. A standalone artefact keeps the conformance
contract in one place and makes "add an MCP server, add a conformance
script" a one-file change.

The artefact is bundle-isolated from production builds by construction
(it's pure Node-side test fixtures; no CLJS sources).

## Spec posture (rf2-uzouv)

`tools/mcp-conformance/` deliberately has **no local `spec/` folder**.
This is a documented exemption from the per-tool spec convention
(`tools/README.md` §Per-tool `spec/` folder convention), not a gap.

Rationale: the per-tool `spec/` convention exists so each artefact's
contract — what it does, why, the locks behind major calls — survives
across sessions in committed form. For `mcp-conformance` that contract
already lives, by construction, in three other places:

1. **The test corpus itself.** Each `test/end-to-end-<server>.cjs`
   pins exactly one server's wire surface (advertised tool catalogue,
   tool descriptor presence, the canonical workflow), and the
   `wire-vocab/` JVM test corpus pins the canonical Malli schema for
   every reserved `:rf.mcp/*` / `:rf.size/large-elided` /
   `:rf.elision/at` marker. The tests are the normative contract a
   server must satisfy; they're machine-checked.

2. **The three cross-MCP docs at this artefact's root** —
   [`NAMING.md`](NAMING.md) (cross-MCP tool-naming convention,
   rf2-mzf1r), [`TOKEN-BUDGETS.md`](TOKEN-BUDGETS.md) (cross-MCP
   token-budget posture, rf2-ll0yq), and
   [`wire-vocab/README.md`](wire-vocab/README.md) (cross-MCP
   wire-vocabulary pinning, rf2-j2z7o). These are the
   non-test-corpus normative content, sitting where their reach is
   widest (all three MCP servers consume them).

3. **The servers being verified.** Per-tool input / output / error
   contracts are owned by each server's own `spec/`
   ([`tools/re-frame2-pair-mcp/spec/`](../re-frame2-pair-mcp/spec/) and
   [`tools/story-mcp/spec/`](../story-mcp/spec/)). Duplicating that
   here would create a second source of truth on a wire that already
   has one canonical home per server.

A `spec/` folder containing only pointers to those three places would
add navigation tax for no informational gain. The exemption is the
straight read.

If `mcp-conformance` ever gains contract surface that does not belong
to a specific server or to one of the three cross-MCP docs — e.g. a
new conformance-only protocol the harness defines on its own behalf —
the exemption is revisited at that point.
