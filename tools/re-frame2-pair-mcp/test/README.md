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
            ├── concerns the stdio handshake / tool catalogue? → stdio-roundtrip.js
            └── concerns the live nREPL socket?                 → live-nrepl.js
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
