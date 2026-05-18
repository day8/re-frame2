# tools/mcp-conformance

End-to-end **MCP-client** conformance harness for the re-frame2 MCP
servers — `re-frame2-pair-mcp`, `story-mcp`, and (when its implementation lands)
`causa-mcp`. Source: rf2-cum40.

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
   when an agent attaches all three servers in one session, and
   the deliberate divergences between server implementations.
   Source: rf2-ll0yq.

## What this is

Every existing test surface for these servers is **server-side**:

- per-tool unit tests (under each server artefact's `test/`)
- `stdio-roundtrip.js` — a hand-rolled JSON-RPC wire-format probe
- `live-{nrepl,server}.js` — workflow tests against a running runtime

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

## Files

- `package.json` — depends on `@modelcontextprotocol/sdk` only
- `test/end-to-end-re-frame2-pair.cjs` — re-frame2-pair-mcp conformance (degraded mode,
  no nREPL needed — same shape as re-frame2-pair-mcp's stdio-roundtrip)
- `test/live-re-frame2-pair-overflow.cjs` — re-frame2-pair-mcp **live**-runtime variant
  (rf2-ynaoc) that exercises the wire-cap `:rf.mcp/overflow` marker
  under a real over-budget eval. **Gated on `$SHADOW_CLJS_NREPL_PORT`**:
  unset = clean SKIP (degraded mode can't trip the cap naturally),
  set = full live-runtime cap-trigger conformance against the
  canonical `OverflowBody` schema pinned by `wire-vocab/`.
- `test/live-re-frame2-pair-subscribe.cjs` — re-frame2-pair-mcp **live**-runtime variant
  (rf2-zb5z6) that exercises the `notifications/progress` streaming
  wire surface. Subscribes to `:trace`, dispatches a known event,
  collects every progress frame the server emits, and validates each
  against the canonical `ReFrame2PairProgressNotificationParams` schema
  pinned by `wire-vocab/`. Gated on `$SHADOW_CLJS_NREPL_PORT` (same
  posture as the overflow variant).
- `scripts/run-live-re-frame2-pair-overflow-hermetic.cjs` — hermetic
  orchestrator (rf2-uw6d6) that boots shadow-cljs against the re-frame2-pair
  fixture (`skills/re-frame2-pair/tests/fixture/`), launches headless
  Chromium so the runtime preload lands, then runs both
  `live-re-frame2-pair-overflow.cjs` and `live-re-frame2-pair-subscribe.cjs` against
  the spawned `SHADOW_CLJS_NREPL_PORT`. Closes the CI-coverage gap
  the SKIP path leaves on each.
- `test/end-to-end-story.cjs` — story-mcp conformance (full write-loop
  with `--allow-writes` enabled)
- `test/end-to-end-causa.cjs` — placeholder; exits 0 with a `SKIP`
  marker until causa-mcp's server implementation lands

## How to run

From this directory:

```bash
npm install

# re-frame2-pair-mcp: requires the server bundle on disk at
# ../re-frame2-pair-mcp/out/server.js. Build it first with:
#   cd ../re-frame2-pair-mcp && npx shadow-cljs compile server
npm run test:re-frame2-pair

# story-mcp: requires `clojure` on PATH (override via
# $STORY_MCP_CMD if non-default). No pre-build step — the server is
# launched via `clojure -M -m re-frame.story-mcp.server`.
npm run test:story

# causa-mcp: currently a SKIPPED no-op (see comment in the file).
npm run test:causa

# All three (re-frame2-pair must be pre-built):
npm test
```

## What each test covers

### `end-to-end-re-frame2-pair.cjs`

1. Connect — full SDK handshake against the freshly spawned bundle
2. `tools/list` — confirm the twelve advertised tools match the pinned
   catalogue
3. Spot-check every descriptor carries an `inputSchema`
4. Walk degraded-mode `dispatch` / `watch-epochs` / `snapshot` /
   `subscribe` — each call routes through the SDK's
   `CallToolResultSchema` parse step
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
5. Marker body validates against canonical `OverflowBody` schema
   pinned by `wire-vocab/`: `:limit :reached`, integer `:cap-tokens`
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
   SIGTERM / SIGHUP).

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

1. Connect — `clojure -M -m re-frame.story-mcp.server --allow-writes`
2. `tools/list` — confirm the 19 advertised tools
3. Spot-check every descriptor carries an `inputSchema`
4. `register-variant` → `run-variant` (vacuous pass) →
   `read-failures` (total=0) → `unregister-variant` + verify
5. Clean `Client.close()`

Watchdog: 90s (cold JVM boot is ~10–30s on a CI runner).

### `end-to-end-causa.cjs`

Placeholder — exits 0 with a `SKIP` marker. Will be filled in when
the causa-mcp server implementation lands; the file's body comment
documents the expected shape.

## CI

`.github/workflows/test.yml` runs each of the three scripts in its own
`mcp-conformance-{re-frame2-pair,story,causa}` job, parallel to the existing
`node-test-tools-{re-frame2-pair,story}-mcp` jobs. Same Node 24 + JDK 21 setup
as those jobs.

The `mcp-conformance-re-frame2-pair` job runs three steps in sequence:

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
   under CI's clean ephemeral runtime — not just on Mike's machine.

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
   ([`tools/re-frame2-pair-mcp/spec/`](../re-frame2-pair-mcp/spec/),
   [`tools/story-mcp/spec/`](../story-mcp/spec/), and — when its
   implementation lands —
   [`tools/causa-mcp/spec/`](../causa-mcp/spec/)). Duplicating that
   here would create a second source of truth on a wire that already
   has one canonical home per server.

A `spec/` folder containing only pointers to those three places would
add navigation tax for no informational gain. The exemption is the
straight read.

If `mcp-conformance` ever gains contract surface that does not belong
to a specific server or to one of the three cross-MCP docs — e.g. a
new conformance-only protocol the harness defines on its own behalf —
the exemption is revisited at that point.
