# MCP transport — the skill's only transport

The re-frame2-pair ops run over the **MCP server** — a persistent
stdio JSON-RPC server that holds one nREPL socket open for the whole
session (per-op latency ~5–50ms). This is the only transport the
skill exposes; the bash shims under `scripts/` are retired from the
skill's tool surface (no shell tool is in `allowed-tools:`) and
remain on disk only for the project's own e2e test harness. The
shell-counterpart mapping lives in [`ops.md` §Bash-shim appendix](ops.md#bash-shim-appendix-not-reachable-from-this-skill)
for that harness; do not reach for it as a fallback transport.

## Install / configure (one-time)

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

The server auto-discovers the nREPL port from (in order):
1. `$SHADOW_CLJS_NREPL_PORT`
2. `target/shadow-cljs/nrepl.port`
3. `.shadow-cljs/nrepl.port`
4. `.nrepl-port`

## Stale-binary post-merge hook (rf2-6jj3r)

When you work inside the re-frame2 source repo (rather than against a
globally-installed npm release), the MCP binary lives at
`tools/re-frame2-pair-mcp/out/server.js` and is rebuilt locally —
`out/` is `.gitignore`d. After `git pull` brings down MCP source-side
changes, the binary on disk is now stale; the running MCP server is
still exec-ing the previous build. Symptoms are confusing
(stale-fix-still-not-fixed; `:nrepl-port-not-found` after a port
discovery improvement merged; …).

Install the repo's git hooks once per clone so `git pull` warns when
this happens:

```bash
scripts/install-git-hooks.sh
# or, on Windows:
powershell -ExecutionPolicy Bypass -File scripts/install-git-hooks.ps1
```

The hook is idempotent, advisory (it never blocks a pull), and prints
the exact rebuild command + bounce hint when MCP source / build config
changed in the pulled commits. Re-run to refresh after upstream hook
edits.

## MCP tool reference (args)

| MCP tool       | Args |
|----------------|------|
| `discover-app` | `{}` (optional `build`) |
| `eval-cljs`    | `{form: "..."}` |
| `dispatch`     | `{event: "...", sync: true, frame: ":foo"}` |
| `trace-window` | `{ms: 1000}` |
| `watch-epochs` | `{pred: {"event-id-prefix": ":cart"}}` (pull-mode — call repeatedly with `since-id`) |
| `tail-build`   | `{probe: "..."}` |
| `snapshot`     | `{frames: "all"\|[":rf/default"...], include: ["app-db","sub-cache","machines","epochs","traces"], path: "[:cart :items]"}` — default `:app-db` mode is **`:summary`** (tree-summary marker, not the full value); pass `path` to slice. Root `path: "[]"` opts back into the full slice. See [`ops.md` §Read](ops.md#read). |
| `get-path`     | `{path: "[:cart :items 0 :sku]", frame: ":rf/default"}` — targeted-read primitive. Returns `{ok? true :exists? true :value <subtree>}` or `{ok? false :reason :path-not-found :deepest-valid-prefix [...]}`. `:exists?` distinguishes a path that legitimately points at `nil` from a missing path. |
| `subscribe`    | `{topic: "trace"\|"epoch"\|"fx"\|"error", filter: {...}, max-events: 0, max-ms: 0}` — push-mode; emits `notifications/progress` ticks; resolves on cancel / `max-events` / `max-ms` / `unsubscribe`. See `references/streaming-subscriptions.md`. |
| `unsubscribe`  | `{sub-id: "<uuid>"}` — idempotent close. |
| `list-subscriptions` | `{}` (or `{frame: ":rf/default", include-values: true}`) — list the **live reactive sub-cache** for a frame (the answer to "what subscriptions are active?"), reading the same source as `snapshot :sub-cache`. Returns `{:ok? true :frame <id> :count n :subs [<query-v> ...]}`; reflects disposal. NOT the streaming taps — for those use `list-streams`. (rf2-qicji) |
| `list-streams` | `{}` (or `{topic: "epoch"}` / `{sub-id: "<uuid>"}`) — list active **streaming-tap** subscriptions with `:queue-depth`, `:dropped-events`, `:overflow-reason` without draining queues. Diagnostic for "is my probe still alive?". (The streaming diagnostic `list-subscriptions` formerly carried; rf2-qicji.) |
| `list-handlers` | `{kind: "event"}` — discovery: every id registered under one kind. `{:ok? true :kind :event :ids [...] :count n}`, ids sorted. Kinds: `event` / `sub` / `fx` / `cofx` / `view` / `frame` / `route` / `flow` / `head` / `error-projector` / `machine`. Prefer over a `registrar-list` eval. See [`ops.md` §Read](ops.md#read). |
| `handler-meta` | `{kind: "event", id: ":user/login"}` — drill: registration metadata for one id (source-coord + `:doc` + `:tags` + an `:rf.source/uri` clickable jump-to-editor link). `{:ok? false :reason :not-registered ...}` on a miss. Composite sub ids pass as the vector-string form. Prefer over a `registrar-describe` eval. See [`ops.md` §Read](ops.md#read). |
| `get-re-frame2-pair-instructions` | `{}` — inline agent-onboarding text (tool catalogue, EDN posture, tagged-mutation conventions, streaming semantics, wire pipeline). No nREPL round-trip. Optionally call at session start to orient before the first real op. |

The `subscribe` / `unsubscribe` pair is the **push-mode** counterpart to `watch-epochs`. Each batch of matching events arrives as a `notifications/progress` notification correlated by the call's `progressToken`; the tool's final result is a summary. Use `subscribe` whenever you want a live narration; use `watch-epochs` (pull-mode, polled in a loop) when the agent host doesn't surface progress notifications to the model.

(`inject-runtime` is gone — the runtime ships into the app via
shadow-cljs `:devtools :preloads`. See `SKILL.md` §Setup.)

## When to use `snapshot` vs the per-op reads

`snapshot` is the **coarse-grained mega-op** for investigate-X
workflows that would otherwise chain 5-10 individual reads
(`app-db/snapshot` + `subs/cache` + `machines/list` + `epoch/history`
+ `trace/buffer`, etc.). Each per-op read is its own bencode
round-trip plus Claude-think latency; `snapshot` collapses the whole
thing into one round-trip.

Use `snapshot` when:

- You're starting a post-mortem and don't yet know which slice
  carries the answer.
- You want a fixed reference point — same call, same shape, several
  hypotheses to test against it.
- You need cross-slice context (e.g. "what was app-db at the same
  moment the trace ring shows this event?").

Use the per-op reads when:

- You know exactly which slice you want and only need that slice.
- You want a path-scoped value (`runtime/app-db-at [:deep :path]`).
- You want a derived projection (`runtime/find-where`, `cascade-of`,
  `last-pair-epoch`) — `snapshot` returns raw history; projections
  still need their dedicated forms via `eval-cljs`.

`snapshot` accepts `include` to subset the slices —
`{include: ["app-db","epochs"]}` returns just those two — and
`frames` to pick a subset of frame-ids — `{frames: [":stories"]}`.

**`:app-db` slice modes.** The `:app-db` slice no longer defaults to the full value. Two modes:

- **`:summary` (default, no `path`)** — the `:app-db` slot is a `{:rf.mcp/summary {:type :map :keys [...] :count ... :bytes ...}}` marker carrying the top-level shape without committing the token budget. Map-key lists over 64 entries get truncated and flagged `:keys-truncated? true` so the marker itself can never blow the wire cap.
- **`:path-sliced` (with `path`)** — the slot is `(get-in db path)`. Out-of-range paths surface per-frame in a top-level `:path-not-found` map with `:deepest-valid-prefix` so the agent can re-aim without a binary search.
- **Root path `path: "[]"`** — explicit request for the full `:app-db`, equivalent to the legacy default. The wire cap is then the backstop.

The other slices (`:sub-cache`, `:machines`, `:epochs`) pass through unchanged. Per rf2-mscih the `:traces` slice now ships **cascade bundles by default** (`{:dispatch-id :frame :event :dispatched :handler :fx :effects :subs :renders :other :trace-events :parent-dispatch-id}` per cascade — the framework's `(rf/trace-buffer frame-id)` shape and the same wire format the `subscribe` streaming surface emits on cascade-bundle topics).

Use `get-path` (next section) when you already know the addressed subtree — it's a single-slice round-trip rather than the multi-slice composition `snapshot` does.

## Preload probe (no inject step)

Every op that needs the in-browser runtime first probes
`js/globalThis.__re_frame2_pair_runtime` — the load-time marker the
preload installs. If the marker is missing the op refuses with
`{:ok? false :reason :runtime-not-preloaded :hint "..."}`. A full
page refresh drops the runtime, but the preload re-installs it on
the next bundle load; no manual reconnect step.

### `eval-cljs`: build resolution + fail-loud preflight

`eval-cljs` resolves which shadow-cljs build to evaluate against and
preflights the runtime sentinel *before* eval'ing, so a runtime-absent
build can never return a misleading `{:ok? true :value nil}` (the old
footgun: shadow's `cljs-eval` against a non-running build yields a
blank value indistinguishable from a genuine `nil`).

- **Auto-detect:** with no `build` arg, `eval-cljs` detects the running
  shadow build (`shadow.cljs.devtools.api/active-builds`). Exactly one
  running ⇒ it's used; the resolved id is echoed back as `:build`.
- **Fail loud:** a runtime-absent build (or zero/many running builds
  with no explicit `build`) returns
  `{:ok? false :reason :no-runtime-for-build :build <id> :running-builds [...] :hint "..."}`
  — never `:ok? true :value nil`. The `:running-builds` list shows which
  build to target via `build: "<id>"`.

The same resolution + preflight logic backs the legacy `scripts/`
bash shim's `eval` op.

## When the MCP server is degraded

If shadow-cljs isn't running yet when the MCP server boots, it still
answers `tools/list` but every `tools/call` returns

```edn
{:ok? false :reason :nrepl-port-not-found :hint "..."}
```

Start shadow-cljs and retry — the server picks up the port on the
next call.

## The bash shims (out of scope for this skill)

The shims under `scripts/` predate the MCP server and remain on disk
only for the project's own e2e test harness and ad-hoc shell use
outside the skill. They consume the same `re-frame2-pair.runtime`
namespace — the runtime contract is transport-agnostic — but they are
**not** reachable from this skill (no shell tool in `allowed-tools:`).
Treat the MCP tools above as the complete operating surface.
