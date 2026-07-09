# MCP transport — the skill's only transport

re-frame2-pair ops run over the **MCP server** — a persistent stdio JSON-RPC server holding one nREPL socket open for the whole session (per-op latency ~5–50ms). This is the **only** transport the skill exposes (no shell tool in `allowed-tools:`); treat the MCP tools as the complete operating surface.

## Contents

- [Install / configure (one-time)](#install--configure-one-time)
- [Stale-binary post-merge hook](#stale-binary-post-merge-hook)
- [MCP tool reference (args)](#mcp-tool-reference-args) — the 30 tools, name → arg signature → semantics home
- [When to use `snapshot` vs the per-op reads](#when-to-use-snapshot-vs-the-per-op-reads)
- [Preload probe (no inject step)](#preload-probe-no-inject-step)
- [Build-id resolution](#build-id-resolution)
- [When the MCP server is degraded](#when-the-mcp-server-is-degraded)

## Install / configure (one-time)

The MCP server is **not yet published to npm** — build and run it from a re-frame2 clone (`cd tools/re-frame2-pair-mcp && npm install && npm run build`, then point `mcpServers` at the compiled `out/server.js`; see [`docs/LOCAL_DEV.md` §MCP server from a clone](../docs/LOCAL_DEV.md#mcp-server-from-a-clone)). Once published:

```bash
npm install -g @day8/re-frame2-pair-mcp
```

Then add to your Claude Code settings:

```json
{
  "mcpServers": {
    "re-frame2-pair": {
      "command": "re-frame2-pair-mcp",
      "env": {
        "SHADOW_CLJS_BUILD_ID": "app"
      }
    }
  }
}
```

On the first tool call the server discovers the live shadow-cljs nREPL
via a **five-step cascade** (per `tools/re-frame2-pair-mcp/spec/002-nREPL-Transport.md` §Port discovery — discovery is **lazy on first tool call**, not boot, because the `roots/list` request can only fire after the client's `initialize` handshake):

1. `--port-file <abs>` launch flag — explicit, cwd-independent override.
2. `$SHADOW_CLJS_NREPL_PORT` env var.
3. **MCP `roots/list` walk** (the zero-config primary path) — ask the agent host for its open workspace roots, walk each (bounded, skipping `node_modules` / `.git` / `target`) for `shadow-cljs.edn` paired with the adjacent `.shadow-cljs/nrepl.port`. One match → silent attach; multiple → an `elicitation/create` prompt so the user picks the project; zero → step 4.
4. **Shadow HTTP probe** — `GET http://127.0.0.1:9630/api/project-info` returns the consumer build's `:project-home`, against which the server reads `target/shadow-cljs/nrepl.port`, `.shadow-cljs/nrepl.port`, `.nrepl-port` (in that order). HTTP port overridable via `--http-port <n>` (default 9630). Fallback for hosts that don't expose `roots`.
5. CWD-relative scan of the same three port-file candidates — fallback for environments without shadow's web server.

The `--port-file` and `$SHADOW_CLJS_NREPL_PORT` escape hatches (steps 1-2) win the cascade — pass one when discovery misses (no running shadow, non-default `:http :port`, exotic setup). Shadow restarts are absorbed transparently: each subsequent tool call re-reads the cached `<project-home>/.shadow-cljs/nrepl.port` and reconnects if the port changed.

## Stale-binary post-merge hook

When working inside the re-frame2 source repo (not a globally-installed npm release), the MCP binary lives at `tools/re-frame2-pair-mcp/out/server.js` and is rebuilt locally (`out/` is `.gitignore`d). After `git pull` brings down MCP source-side changes, the on-disk binary is stale while the running server still exec's the previous build. Symptoms are confusing (stale-fix-still-not-fixed; `:nrepl-port-not-found` after a port-discovery improvement merged).

Install the repo's git hooks once per clone so `git pull` warns when this happens:

```bash
scripts/install-git-hooks.sh
# or, on Windows:
powershell -ExecutionPolicy Bypass -File scripts/install-git-hooks.ps1
```

The hook is idempotent, advisory (never blocks a pull), and prints the exact rebuild command + bounce hint when MCP source / build config changed in the pulled commits. Re-run to refresh after upstream hook edits.

## MCP tool reference (args)

The server exposes **30 tools** (catalogued in `tools/re-frame2-pair-mcp/tool-descriptors.edn`, the generated descriptor manifest), and **all 30 are reachable from this skill's `allowed-tools:`**. The two write-authority tools (`restore-epoch`, `replace-app-db`) are the canonical path for named state rewrites, gated by the server's default-OFF `--allow-writes` flag — the server's gate, not the allow-list, is the write boundary; the eval forms are the backstop for a gate-OFF server. The full gate + backstop explanation lives in [`ops.md` §Time-travel](ops.md#time-travel-epoch-restore).

This is the **transport index** — the tool name, its arg signature, and where its per-tool semantics are documented. The behaviour of each tool (return shapes, modes, gotchas) lives once in `ops.md`; this table does not restate it.

| MCP tool | Arg signature | Semantics home |
|---|---|---|
| `discover-app` | `{}` (optional `build` / `port`) — connect + health probe; carries a `:freshness` token | SKILL.md §Connect first · §Install / configure below |
| `orient` | `{}` — app-shape summary in one round-trip | [`ops.md` §Read](ops.md#read) |
| `get-re-frame2-pair-instructions` | `{}` — inline agent-onboarding text, no nREPL round-trip | SKILL.md §Connect first |
| `list-handlers` | `{kind: "event"}` — every id under one registrar kind | [`ops.md` §Read](ops.md#read) |
| `handler-meta` | `{kind, id, frame?}` — registration meta for one id (add `frame` for the per-frame arity) | [`ops.md` §Read](ops.md#read) |
| `describe-image` | `{frame, include-ns?}` — the selected registration universe a frame runs | [`ops.md` §Frames](ops.md#frames) |
| `snapshot` | `{frames, include, path}` — multi-slice read; `:app-db` defaults to `:summary` | [`ops.md` §Read](ops.md#read) · §slice modes below |
| `get-path` | `{path, frame?, paths?}` — targeted read; `{:exists?}` distinguishes nil from missing | [`ops.md` §Read](ops.md#read) |
| `read-sub` | `{sub, frame?}` — validated, elided one-shot subscription read | [`ops.md` §Read](ops.md#read) |
| `list-subscriptions` | `{frame?, include-values?}` — the live reactive sub-cache for a frame | [`ops.md` §Read](ops.md#read) |
| `eval-cljs` | `{form, frame?, await?, timeout-ms?}` — CLJS eval; frame-scopes via `with-frame` | [`ops.md` §Write](ops.md#write) |
| `read-ui` | `{view-id \| point \| selector}` (exactly one) — rendered subtree + producing entity | [`ops.md` §ui/read](ops.md#view--rendered-content--producing-entity-uiread) |
| `read-dom` | `{selector, sub-selector?, attrs?, max-text?, limit?}` — raw DOM by CSS selector | [`ops.md` §read-dom](ops.md#read-dom--raw-dom-content-by-explicit-css-selector) |
| `dispatch` | `{event, sync?, frame?, trace?, await-render?, settle?, queued?, fx-overrides?, cofx?}` | [`ops.md` §Write](ops.md#write) |
| `dispatch-dry-run` | `{event, frame?, fx-overrides?}` — simulate WITHOUT committing; not `--allow-writes`-gated | [`ops.md` §Write](ops.md#write) |
| `restore-epoch` | `{epoch-id, frame?}` — canonical time-travel undo; `--allow-writes`-gated | [`ops.md` §Time-travel](ops.md#time-travel-epoch-restore) |
| `replace-app-db` | `{db, frame?}` — canonical state injection; `--allow-writes`-gated | [`ops.md` §Write](ops.md#write) |
| `trace-window` | `{ms, limit?, cursor?}` — epoch records added in the last N ms | [`ops.md` §Trace](ops.md#trace) |
| `watch-epochs` | `{pred?, since-id?, limit?, cursor?}` — pull-mode poll | [`ops.md` §Live watch](ops.md#live-watch-push-mode) |
| `watch-until` | `{signals, pred, timeout-ms?}` — block until a signal predicate holds | [`ops.md` §Signal recording](ops.md#signal-recording--blocking-waits) |
| `subscribe` | `{topic, filter?, max-events?, max-ms?}` — push-mode; emits `notifications/progress` | [`streaming-subscriptions.md`](streaming-subscriptions.md) |
| `unsubscribe` | `{sub-id}` — idempotent close | [`streaming-subscriptions.md`](streaming-subscriptions.md) |
| `list-streams` | `{topic? \| sub-id?}` — active streaming-tap subs (runtime side) | [`streaming-subscriptions.md` §Diagnostics](streaming-subscriptions.md#diagnostics--what-streams-are-currently-registered) |
| `get-stream-controls` | `{}` — server-side stream resource-control state; in-process, ungated | [`streaming-subscriptions.md` §get-stream-controls](streaming-subscriptions.md#get-stream-controls--why-was-my-stream-denied--quiet--terminated) |
| `record` | `{signals, stop?, max-entries?}` — read-only signal recorder | [`ops.md` §Signal recording](ops.md#signal-recording--blocking-waits) |
| `read-recording` | `{recording-id, drain?, stop?}` — read back a recording's change-log | [`ops.md` §Signal recording](ops.md#signal-recording--blocking-waits) |
| `tail-build` | `{probe?, wait-ms?}` — wait for a hot-reload to land by polling the probe | [`ops.md` §Hot-reload](ops.md#hot-reload-coordination) |
| `get-operating-frame` | `{}` — read the operating-frame triple | [`ops.md` §Frames](ops.md#frames) |
| `set-operating-frame` | `{frame}` — pin the session's operating frame | [`ops.md` §Frames](ops.md#frames) |
| `reset-operating-frame` | `{}` — clear the pin; idempotent | [`ops.md` §Frames](ops.md#frames) |

(There is no `inject-runtime` tool — the runtime ships into the app via shadow-cljs `:devtools :preloads`. See `SKILL.md` §Setup.)

## When to use `snapshot` vs the per-op reads

**Orient first** (SKILL.md §Orient before you drill): `snapshot` is a drill-in for *after* `orient`, never the way you take in an unfamiliar app; a full `snapshot {path: "[]"}` is a last resort the server refuses against a reserved `:rf/*` tool frame.

`snapshot` is the **coarse-grained mega-op** for investigate-X workflows that would otherwise chain 5-10 individual reads (`app-db/snapshot` + `subs/cache` + `machines/list` + `epoch/history` + `trace/buffer`, etc.) — each its own bencode round-trip plus Claude-think latency; `snapshot` collapses them into one.

Use `snapshot` when:

- Starting a post-mortem and you don't yet know which slice carries the answer.
- You want a fixed reference point — same call, same shape, several hypotheses to test against it.
- You need cross-slice context (e.g. "what was app-db at the same moment the trace ring shows this event?").

Use the per-op reads when:

- You know exactly which slice you want and need only that slice.
- You want a path-scoped value (`runtime/app-db-at [:deep :path]`).
- You want a derived projection (`runtime/find-where`, `cascade-of`, `last-pair-epoch`) — `snapshot` returns raw history; projections still need their dedicated forms via `eval-cljs`.

`snapshot` accepts `include` to subset slices (`{include: ["app-db","epochs"]}`) and `frames` to pick frame-ids (`{frames: [":stories"]}`).

**`:app-db` slice modes.** The `:app-db` slice defaults to a summary, not the full value. Two modes:

- **`:summary` (default, no `path`)** — the `:app-db` slot is a `{:rf.mcp/summary {:type :map :keys [...] :count ... :bytes ...}}` marker carrying the top-level shape without committing the token budget. Map-key lists over 64 entries get truncated and flagged `:keys-truncated? true` so the marker itself can never blow the wire cap.
- **`:path-sliced` (with `path`)** — the slot is `(get-in db path)`. Out-of-range paths surface per-frame in a top-level `:path-not-found` map with `:deepest-valid-prefix` so the agent can re-aim without a binary search.
- **Root path `path: "[]"`** — explicit request for the full `:app-db`. A **last resort** (large on any real app frame; refused against a reserved `:rf/*` tool frame). Orient first, then slice. The wire cap is the backstop.

The other slices (`:sub-cache`, `:machines`, `:epochs`) pass through unchanged. The `:traces` slice now ships **event bundles by default** (`{:dispatch-id :frame :event :dispatched :handler :fx :effects :subs :renders :other :trace-events :parent-dispatch-id}` per run — the framework's `(re-frame.trace.tooling/trace-buffer frame-id)` shape and the same wire format the `subscribe` streaming surface emits on event-bundle topics).

Use `get-path` (next section) when you already know the addressed subtree — it's a single-slice round-trip rather than the multi-slice composition `snapshot` does.

## Preload probe (no inject step)

Every op needing the in-browser runtime first probes `js/globalThis.__re_frame2_pair_runtime` — the load-time marker the preload installs. If missing, the op refuses with `{:ok? false :reason :runtime-not-preloaded :hint "..."}`. A full page refresh drops the runtime, but the preload re-installs it on the next bundle load; no manual reconnect step.

### `eval-cljs`: build resolution + fail-loud preflight

`eval-cljs` resolves which shadow-cljs build to evaluate against and preflights the runtime sentinel *before* eval'ing, so a runtime-absent build can never return a misleading `{:ok? true :value nil}` — shadow's `cljs-eval` against a non-running build yields a blank value indistinguishable from a genuine `nil`, and the preflight guards against it.

- **Auto-detect:** with no `build` arg, `eval-cljs` detects the running shadow build (`shadow.cljs.devtools.api/active-builds`). Exactly one running ⇒ used; the resolved id is echoed as `:build`.
- **Fail loud:** a runtime-absent build (or zero/many running builds with no explicit `build`) returns `{:ok? false :reason :no-runtime-for-build :build <id> :running-builds [...] :hint "..."}` — never `:ok? true :value nil`. `:running-builds` shows which build to target via `build: "<id>"`.

## Build-id resolution

shadow build ids are namespaced keywords (`:examples/machine-epochs`).
The server resolves a `build` arg through a deterministic, **session-scoped**
cascade so you rarely repeat it:

1. **Suffix-forgiving match.** Colon-tolerant first (`"examples/standard-epochs"`
   and `":examples/standard-epochs"` resolve identically — the bare `keyword`
   footgun `::examples/...` is closed). Then **unique name-suffix**: a
   `build` naming a running build by a unique tail (`"machine-epochs"`)
   resolves to the canonical running id. The rule is exact-match-first,
   then unique-tail; **two builds sharing the tail stay ambiguous** and a
   no-match id passes through unchanged so the diagnostic ladder still
   fires — never a silent wrong-build pick. Resolution runs once per
   session and caches the suffix→canonical alias on the conn-atom.
2. **Sticky per-session selection.** The first call that names a build —
   `discover-app {build: "my-app"}` after a passing health probe, OR an
   explicit `:build` on any later tool — sets the session-sticky default
   (`:resolved-build-id` on the conn-atom). Every subsequent call may
   omit `:build`. A later explicit `:build` both routes that call and
   re-points the sticky default. The cache resets on reconnect (relaunch
   shadow against a different build ⇒ fresh resolution). Per-session,
   never process-global — each MCP client spawns its own server, so the
   sticky target can't leak across clients.
3. **`SHADOW_CLJS_BUILD_ID` env var** (defaulting to `:app`) — the final
   fallback.

**Round-trippable canonical ids.** The canonical id is the **keyword**. `discover-app` echoes it under both `:build-id` and `:build`; the read-family ops (`orient`, `read-dom`, `read-ui`, `eval-cljs`) echo the resolved `:build`. A value copied out of any of those slots works unchanged as a later `:build` arg.

**`:build-not-running` vs `:no-runtime-connected` — distinct rungs.** The diagnostic ladder distinguishes two failure shapes the operator fixes differently:

| Reason | Means | Fix |
|---|---|---|
| `:build-not-running` | shadow's `active-builds` doesn't include the targeted build (or the `:app` default isn't running while several builds are). | Pick a running build — the envelope carries `:running-builds` (keyword vector) **and** a sibling `:running-builds-arg-forms` (each rendered in the exact `":examples/machine-epochs"` string form the `:build` arg accepts back), so you can paste a build straight from the error into `:build`. |
| `:no-runtime-connected` | the build **IS** running but no CLJS runtime answered the eval (cljs-eval returned blank — no browser tab connected, or its WebSocket dropped). | Open the app in a browser tab — or reload an already-open tab so the runtime reconnects. Also carries `:running-builds`. |

The eval path surfaces the ambiguous-target case as `:no-runtime-for-build` (also enumerating `:running-builds`); the plain read path surfaces the same candidate list via `:build-not-running`. Either way the error names the valid set in a paste-back form — never a silent wrong-build pick or host failure.

## When the MCP server is degraded

If shadow-cljs isn't running when the MCP server boots, it still answers `tools/list` but every `tools/call` returns

```edn
{:ok? false :reason :nrepl-port-not-found :hint "..."}
```

Start shadow-cljs and retry — the server picks up the port on the next call.
