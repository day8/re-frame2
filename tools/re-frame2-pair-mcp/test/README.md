# re-frame2-pair-mcp tests — JS / CLJS layering

The test surface is split across CLJS unit/conformance suites and Node
integration scripts.

## TL;DR — when to add a test on each side

| Question | Side |
|---|---|
| Does a per-tool function build the right eval form / wire envelope? | **CLJS** — `re_frame2_pair_mcp/<tool>_test.cljs` |
| Does a cross-cutting concern (cache, cap, dedup, elision, sensitive) reshape an envelope correctly? | **CLJS** — `re_frame2_pair_mcp/<concern>_test.cljs` |
| Does `tools/invoke` preserve build resolution → precheck → dispatch → cache → cap order? | **CLJS** — `re_frame2_pair_mcp/invoke_test.cljs` |
| Does the full tool catalogue (the ordered `registry/tools` list) still produce the documented EDN wire shape per (tool × args × stub-conn)? | **CLJS** — `re_frame2_pair_mcp/conformance_test.cljs` |
| Does the compiled `out/server.js` complete an MCP handshake and surface the documented tool descriptors? | **JS** — `stdio-roundtrip.js` |
| Does the persistent nREPL socket survive multiple ops on one server process without leaking / hanging? | **JS** — `live-nrepl.js` |
| Do connect / dispatch / trace / hot-reload work end-to-end against a live browser-hosted fixture, through the real MCP boundary? | **JS** — `live-e2e-fixture.cjs` |
| Do the three Hicasso evidence-door tools return a schema-matched, non-empty, value-free projection when actually run against a live Hicasso application — and, against a build that has never loaded the door, reach `:evidence-tier-unavailable` rather than an analyzer compile error? | **JS** — `live-hicasso-wire.cjs` |
| Does closing stdin (EOF) retire the session — close the persistent nREPL socket and exit 0 — with no out-of-band kill? | **JS** — `stdin-eof-shutdown.cjs` |

If a regression would only be visible **after** the CLJS compiles to
JS, write a JS test. If it would be visible in the CLJS source, write a
CLJS test. The two layers are complementary: `npm test` runs the CLJS
suite, while the Node integration scripts are explicit package commands.

## The two layers

### CLJS — `re_frame2_pair_mcp/*_test.cljs`

The default `npm test` gate. shadow-cljs compiles the `:server-test`
build (`shadow-cljs.edn`) and Node runs `out/server-test.js`. Every
`*_test.cljs` namespace under `test/re_frame2_pair_mcp/` is picked up
by the `:ns-regexp "-test$"` rule.

What this layer covers:

- **Per-tool body** — `<tool>_test.cljs` (one per registered tool):
  pins the function shape (args coercion, eval-form composition,
  wire envelope, error surfaces) without touching the network.
- **Cross-cutting concerns** — `cache_test.cljs`, `wire_cap_test.cljs`,
  `dedup_test.cljs`, `dedup_benchmark_test.cljs`,
  `sensitive_filter_test.cljs`, `path_slicing_test.cljs`,
  `elision_test.cljs`, `lazy_summary_test.cljs`,
  `cursor_pagination_test.cljs`, `args_test.cljs`,
  `diff_encode_epochs_test.cljs`. Each is a unit suite over its
  concern's public surface.
- **Pipeline glue** — `invoke_test.cljs` covers build resolution →
  precheck → dispatch → cache → cap.
- **Conformance corpus** — `conformance_test.cljs` (rf2-xkxbv): one
  inline-fixture corpus driving every tool through `tools/invoke`
  against a stub conn, asserting recorded wire-shape EDN. Sibling
  to `re-frame.ssr-conformance-test` / `re-frame.machines-conformance-test`
  / `re-frame.schemas-conformance-test` / `re-frame.flows-conformance-test`
  on the framework side.
- **Snapshot pipeline / wire shape** — `snapshot_test.cljs`,
  `subscribe_test.cljs`, `list_subscriptions_test.cljs`,
  `wire_cap_test.cljs`, `typical_tokens_test.cljs`. These exercise
  the SHAPE the server emits without ever opening a socket; nREPL is
  stubbed at `nrepl/cljs-eval-value`.

What this layer DOESN'T cover:

- The stdio JSON-RPC framing. The CLJS suite never reaches the
  `out/server.js` entry-point; it talks directly to the per-tool fn.
  A broken stdio handler would pass every CLJS test and break in
  production.
- The persistent-socket nREPL round-trip. `nrepl/cljs-eval-value` is
  stubbed in the unit suite; bencode parsing has its own dedicated
  unit suite (`nrepl_test.cljs`) but the live-socket integration is
  out of scope.
- The compiled JS itself. shadow's `:simple` optimisation pass can
  introduce name-mangling / dead-code-elimination issues that only
  surface after compilation. The CLJS suite runs against
  `:server-test` (no simple opts); production runs against
  `:server` (simple opts). The two builds COULD diverge.

### JS — `live-nrepl.js` + `stdio-roundtrip.js`

The integration layer. Both scripts spawn `out/server.js` as a
subprocess and drive it through stdin/stdout JSON-RPC frames.

#### `stdio-roundtrip.js` — handshake + tool catalogue

No external dependencies. Runs in CI by default. Boots the server
with the nREPL port intentionally unresolvable so the degraded path
runs deterministically. Exercises:

- `initialize` handshake — server announces protocol version + name.
- `tools/list` — pins the exact tool name set and per-tool
  `inputSchema.properties` keys. A renamed property fails this test
  (the rename is part of the wire contract; users' MCP-host configs
  depend on it).
- Selected app-facing calls (`eval-cljs`, `snapshot`, `get-path`,
  `subscribe`, and `unsubscribe`) return
  `:reason :nrepl-port-not-found` with no app endpoint.
- An unknown tool returns an `isError` envelope with
  `:reason :unknown-tool` before endpoint discovery.

Run with: `node test/stdio-roundtrip.js` (after `npm run build`).

#### `live-nrepl.js` — persistent-socket round-trip

Requires a running nREPL on the port read from `$NREPL_TEST_PORT`
(default 17778). Not part of the default `npm test` gate — opt-in,
documented as a smoke harness. Exercises:

- The persistent socket survives multiple ops on one server instance
  (the original pilot bug — bencode@2's `decode.position` cursor —
  would resurface here, NOT in the CLJS unit suite).
- bencode multi-frame parsing on a real wire (status frame separate
  from value frame in nREPL's normal output stream).
- `eval-cljs` degrades cleanly when the runtime preload is absent —
  surfaces a structured error rather than hanging on the socket.

Run with: `NREPL_TEST_PORT=17778 node test/live-nrepl.js`
(after starting an nREPL on that port).

#### `live-e2e-fixture.cjs` — connect / dispatch / trace / hot-reload

The end-to-end live gate. Spawns `out/server.js` and drives it with real
MCP frames against the browser-hosted pair fixture
(`skills/re-frame2-pair/tests/fixture/`), exercising the three flows the
retired `skills/re-frame2-pair/tests/e2e/` suite used to drive through the
`scripts/ops.clj` bash transport — now through the one implementation, the
MCP server:

- **connect** — `discover-app` finds the preloaded runtime and returns a
  healthy snapshot (`:ok?`, `:debug-enabled?`, `:frames [:rf/default]`).
- **dispatch + trace** — `dispatch {event "[:counter/inc]" sync true}`
  commits, the on-screen `#value` reads `6`, and `trace-window` carries a
  matching `:counter/inc` epoch.
- **hot-reload** — capture a `registrar-handler-ref` probe, touch-edit the
  fixture's `core.cljs` to trigger a shadow-cljs reload, and confirm
  `tail-build {probe … wait-ms …}` reports `:soft? false` once the probe
  flips.

Requires an already-running fixture (`npx shadow-cljs watch app` in the
fixture dir) plus a resolvable Playwright (for the browser that hosts the
runtime). SOFT-SKIPS (exits 0 with a `SKIP` banner) when the server bundle,
the fixture HTTP endpoint, the nREPL port file, or Playwright is absent, so
it is safe to invoke anywhere without a false red. It is the pair-mcp-owned
counterpart to the mcp-conformance hermetic live suite, which boots the
fixture itself; this one attaches to a fixture you booted.

Run with: `npm run test:live-e2e-fixture` (after `npm run build` and booting
the fixture; `RE_FRAME2_PAIR_FIXTURE_URL` / `SHADOW_CLJS_NREPL_PORT`
override discovery).

#### `live-hicasso-wire.cjs` — the Hicasso evidence door, actually run (rf2-hic-059)

The one witness in this tree that executes a Pair tool against a real
Hicasso provider. `hicasso_tool_test.cljs` stubs the eval with canned
envelopes and `hicasso_wire_test.cljs` compares emitted strings with the
provider's source; both are static seam checks, and neither had ever sent
the emitted form, compiled it, evaluated it in a runtime, or carried a
result back through the schema gate.

This one does. It boots the `rf2-hic-025` slice application through its
own `-main` over `eval-cljs`, then calls `read-mounted-boundaries`,
`read-read-attribution` and `explain-render` as MCP tools, asserting per
read that the envelope is `:ok? true` stamped with the schema this build
consumes, that it names the slice's own frame and its `::subs/draft`
read, and — the point of the file — that it does **not** carry the
secret seeded into that draft. Non-vacuity comes first: `read-sub`, the
same server on the same socket, returns the secret, so the absence rows
are about the door rather than about an empty runtime.

**It also witnesses the door-ABSENT rung, and that row runs first**
(rf2-t2ec). Before anything pulls `re-frame.hicasso.tool` in, the same
three tools must answer `:reason :evidence-tier-unavailable` with the
load-the-door hint — the population being a Hicasso build that has never
compiled the door, which is what a Reagent or UIx app is permanently.
This is the row that found the original defect: the tools answered a raw
`:rf.error/eval-cljs-compile-error` instead, because a form referencing a
var in an unloaded namespace is rejected by shadow's analyzer before any
branch of it runs. No stubbed suite can see that, because no stub
compiles anything.

The row's population is self-restoring: the script's own
`(require 're-frame.hicasso.tool)` reaches the RUNTIME rather than the
build's module graph, and each run opens a fresh page, so consecutive
runs against one long-lived watch all see the door absent again. What
the row cannot survive is a host page that already carries the door —
one hosting Xray, say — and `RF2_HICASSO_WIRE_URL` accepts any host, so
the script probes first and fails rather than skipping: a skip and a
pass are indistinguishable, which is the very failure shape the row
exists to catch.

Requires a browser build carrying the `re-frame2-pair.runtime` preload
whose classpath can reach `re-frame.hicasso.tool` and the slice — the
implementation tree is one:

```
cd implementation
npx shadow-cljs watch hicasso/hmr-testbed \
  --config-merge '{:devtools {:preloads [re-frame2-pair.runtime]}}'
```

SOFT-SKIPS (exit 0, `SKIP` banner) when the server bundle, the page, the
nREPL port file or Playwright is absent. Run with:
`npm run test:live-hicasso-wire` (`RF2_HICASSO_WIRE_URL` /
`SHADOW_CLJS_NREPL_PORT` override discovery).

#### `stdin-eof-shutdown.cjs` — EOF lifecycle contract (rf2-j538f7.32)

The self-contained lifecycle grader. Spawns `out/server.js` and drives it
over real stdio against a **fake bencode nREPL** the script itself boots —
no live shadow-cljs, so it runs anywhere (Ubuntu + Windows CI). It pins the
documented `stdin EOF -> nREPL close -> process exit` contract
(`spec/001-Wire-Protocol.md`, `server.cljs` lifecycle step 6): the MCP host
owns process lifecycle, so closing stdin must retire the session. Three
cases:

- **with nREPL** — a completed `eval-cljs` call opens an idle nREPL socket;
  `child.stdin.end()` must close that socket (the fake peer observes its
  connection close) AND exit the process `0` within a short bound. The
  success path never calls `child.kill()` — force-kill lives only in the
  cleanup `finally`, so a leaked/hung process surfaces as a red instead of
  being masked. Before the fix the child stayed alive indefinitely.
- **no nREPL** — a closed-world session (discovery never resolves a port)
  still exits `0` promptly on EOF.
- **early EOF** — EOF before the first tool call (no socket ever opened) is
  harmless, and a duplicate terminal event does not change the exit status
  (idempotency).

Every case also asserts stdout purity — teardown emits nothing but MCP
frames on stdout. The `shutdown!` teardown logic has hermetic CLJS unit
counterparts in `re_frame2_pair_mcp/shutdown_test.cljs`.

Run with: `npm run test:stdin-eof-shutdown` (after `npm run build`).

#### `post-merge-hook-test.cjs` — stale-binary post-merge hook (rf2-6jj3r)

Unit + smoke tests for the repo's `post-merge` git hook (source lives
under `scripts/git-hooks/`). The hook is pure POSIX sh (no CLJS source
of truth), so its tests sit here as a `.cjs` sibling.

Two layers:

- **Unit** — dot-sources `scripts/git-hooks/lib/check-stale-mcp-binary.sh`
  and pipes synthetic lists of changed file paths through
  `check_stale_mcp_binary`. Asserts warning text appears only for
  paths that fall inside an MCP source surface (the `src/` tree, the
  build-config files `shadow-cljs.edn` / `deps.edn` / `package.json`)
  and is silent otherwise.
- **Smoke** — spins up a tiny temp git repo, stages the hook's
  detection library at the expected relative path, sets `ORIG_HEAD`
  to a prior commit, and runs `scripts/git-hooks/post-merge`
  end-to-end. Asserts the warning fires for a real cross-MCP diff and
  is silent for an unrelated diff.

Run with: `npm run test:post-merge-hook` (or
`node test/post-merge-hook-test.cjs`).

## Adding a new test — decision tree

```
Is the regression visible in CLJS source?
  ├── yes → CLJS unit test
  │         ├── concerns a single tool body?         → <tool>_test.cljs
  │         ├── concerns a cross-cutting concern?    → <concern>_test.cljs
  │         ├── concerns the boundary pipeline?      → invoke_test.cljs
  │         └── concerns the public wire envelope?   → conformance_test.cljs
  │
  └── no → JS integration test
            ├── concerns the stdio handshake / tool catalogue?   → stdio-roundtrip.js
            ├── concerns the stdin-EOF shutdown lifecycle?        → stdin-eof-shutdown.cjs
            ├── concerns the live nREPL socket?                   → live-nrepl.js
            ├── concerns an end-to-end flow against a live app?   → live-e2e-fixture.cjs
            └── concerns the Hicasso door against a live app?     → live-hicasso-wire.cjs
```

## Why this layout is unusual

Sibling artefacts under `implementation/<feature>/` are pure CLJS /
JVM; their test layers are all `clojure -M:test`, all in one host
language. re-frame2-pair-mcp is the exception — it compiles to Node and runs
under `node out/server.js`. The compiled JS is the production
artefact, but the source of truth is `.cljs`. So the test layer
straddles both worlds: CLJS for everything verifiable from source,
JS for everything that only exists after compilation.

That's the boundary this README pins. Two layers, two scopes, one
artefact.
