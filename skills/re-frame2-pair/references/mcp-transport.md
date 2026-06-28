# MCP transport — the skill's only transport

re-frame2-pair ops run over the **MCP server** — a persistent stdio JSON-RPC server holding one nREPL socket open for the whole session (per-op latency ~5–50ms). This is the **only** transport the skill exposes (no shell tool in `allowed-tools:`); treat the MCP tools as the complete operating surface. The `scripts/` shell shims exist only for the project's e2e harness and are out of scope here.

## Contents

- [Install / configure (one-time)](#install--configure-one-time)
- [Stale-binary post-merge hook](#stale-binary-post-merge-hook)
- [MCP tool reference (args)](#mcp-tool-reference-args) — the 30 tools, grouped by plane
- [When to use `snapshot` vs the per-op reads](#when-to-use-snapshot-vs-the-per-op-reads)
- [Preload probe (no inject step)](#preload-probe-no-inject-step)
- [Build-id resolution](#build-id-resolution)
- [When the MCP server is degraded](#when-the-mcp-server-is-degraded)

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

On the first tool call the server discovers the live shadow-cljs nREPL
via a **five-step cascade** (per `tools/re-frame2-pair-mcp/spec/002-nREPL-Transport.md` §Port discovery — discovery is **lazy on first tool call**, not boot, because the `roots/list` request can only fire after the client's `initialize` handshake):

1. `--port-file <abs>` launch flag — explicit, cwd-independent override.
2. `$SHADOW_CLJS_NREPL_PORT` env var.
3. **MCP `roots/list` walk** (the zero-config primary path) — ask the agent host for its open workspace roots, walk each (bounded, skipping `node_modules` / `.git` / `target`) for `shadow-cljs.edn` paired with the adjacent `.shadow-cljs/nrepl.port`. One match → silent attach; multiple → an `elicitation/create` prompt so the user picks the project; zero → step 4.
4. **Shadow HTTP probe** — `GET http://127.0.0.1:9630/api/project-info` returns the consumer build's `:project-home`, against which the server reads `target/shadow-cljs/nrepl.port`, `.shadow-cljs/nrepl.port`, `.nrepl-port` (in that order). HTTP port overridable via `--http-port <n>` (default 9630). Fallback for hosts that don't expose `roots`.
5. CWD-relative scan of the same three port-file candidates — legacy fallback for environments without shadow's web server.

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

### Orientation + discovery

| MCP tool       | Args |
|----------------|------|
| `discover-app` | `{}` (optional `build` / `port`) — connect + health probe. Carries a `:freshness` token (`:liveness :fresh\|:stale-build\|:no-runtime\|:unknown`) — check it before trusting reads. See §Connect-first in SKILL.md. |
| `orient` | `{}` — **app-shape orientation summary in ONE round-trip**. Composes discover-app + snapshot-top-keys + list-handlers + list-subscriptions + machines for first contact on an unfamiliar app. Returns `:liveness` / `:frames {:all :app :operating}` / `:app-db-top-keys` / `:registry {:counts :events :subs :fx}` / `:machines`. Compact by design; reserved `:rf/*` tool frames excluded from `:app-db-top-keys`. Drill via the reads below. |
| `get-re-frame2-pair-instructions` | `{}` — inline agent-onboarding text (tool catalogue, EDN posture, tagged-mutation conventions, streaming semantics, wire pipeline). No nREPL round-trip. Optionally call at session start. |
| `list-handlers` | `{kind: "event"}` — discovery: every id registered under one kind. `{:ok? true :kind :event :ids [...] :count n}`, ids sorted. Kinds: `event` / `sub` / `fx` / `cofx` / `interceptor` / `view` / `frame` / `route` / `flow` / `head` / `error-projector` / `resource` / `mutation` / `resource-scope` / `machine`. The `interceptor` kind (EP-0022) lists the ids registered with `reg-interceptor` — empty on an app that registers no named interceptors. The `resource` / `mutation` / `resource-scope` kinds are the resources-artefact server-state registrars — they appear only on an app that loaded the resources artefact (`day8/re-frame2-resources`); on a non-resources app those kinds list empty. See [`ops.md` §Read](ops.md#read) for what each surfaces. |
| `handler-meta` | `{kind: "event", id: ":user/login"}` — drill: registration metadata for one id (source-coord + `:doc` + `:tags` + an `:rf.source/uri` clickable jump-to-editor link). `{:ok? false :reason :not-registered ...}` on a miss. Composite sub ids pass as the vector-string form. Add `frame: ":app/main"` for the **frame-targeted** arity (API §Public registrar query API) — resolves the id through that frame's sealed image generation with per-frame provenance, the per-`(kind, id)` drill for `describe-image`. For `resource` / `mutation` / `resource-scope` metadata (scope policy, declared `:inputs`, the `:whole-db?` flag), see [`ops.md` §Read](ops.md#read). Prefer over a `registrar-describe` eval. |
| `describe-image` | `{frame: ":app/main", include-ns: true}` — **what behaviour does THIS frame run, and where did each piece come from?** The EP-0023 forward read over `(rf/frame-generation frame)`: the *selected* registration universe the frame actually runs (not the process-wide registrar union `list-handlers` reports). `{:ok? true :frame <id> :images [...] :kinds [...] :counts {<kind> N…}}`; `include-ns: true` adds `:registrations {[kind id] {:source … :ns …}…}` (the provenance that won each resolution). Omit `frame` for the operating frame; multi-frame with no pin → `:ambiguous-frame`. Drill one id with the frame-targeted `handler-meta` above. See [`ops.md` §Frames](ops.md#frames). |

### Read (data plane)

| MCP tool       | Args |
|----------------|------|
| `snapshot`     | `{frames: "all"\|[":rf/default"...], include: ["app-db","sub-cache","machines","epochs","traces"], path: "[:cart :items]"}` — default `:app-db` mode is **`:summary`** (tree-summary marker, not the full value); pass `path` to slice. Root `path: "[]"` opts back into the full slice. See [`ops.md` §Read](ops.md#read). |
| `get-path`     | `{path: "[:cart :items 0 :sku]", frame: ":rf/default"}` — targeted-read primitive. Returns `{ok? true :exists? true :value <subtree>}` or `{ok? false :reason :path-not-found :deepest-valid-prefix [...]}`. `:exists?` distinguishes a path that legitimately points at `nil` from a missing path. Batch with `paths: "[[...] [...]]"`. |
| `read-sub`     | `{sub: "[:cart/total]", frame: ":rf/default"}` — **validated one-shot subscription read**, the #1 read on any app. PREFER over a raw `eval-cljs` `@(rf/subscribe ...)`: the `sub` arg is parsed as EDN once + MUST be a vector, the sub-id is validated against the live `:sub` registrar (unknown → `:reason :unknown-id` + `:nearest`, never a silent nil), the value is elided server-side. `{:ok? true :query-v [...] :frame <id> :value <v> :elision true}` on a hit. |
| `list-subscriptions` | `{}` (or `{frame: ":rf/default", include-values: true}`) — list the **live reactive sub-cache** for a frame (the answer to "what subscriptions are active?"), reading the same source as `snapshot :sub-cache`. Returns `{:ok? true :frame <id> :count n :subs [<query-v> ...]}`; reflects disposal. NOT the streaming taps — for those use `list-streams`. |
| `eval-cljs`    | `{form: "...", frame: ":foo", await: true, timeout-ms: 5000}` — escape hatch; evaluates a CLJS form against the runtime. Frame-scopes via `with-frame`; `await` resolves Promises server-side. Enabled by default; operator opts out via `--no-eval`. |

### View plane (the rendered DOM)

| MCP tool       | Args |
|----------------|------|
| `read-ui`      | `{view-id: ":my.app/counter"}` / `{point: {x: N, y: N}}` / `{selector: "#save"}` — **typed `ui/read`**: rendered subtree as elided data PLUS the producing re-frame2 entity (view-id, source-coord, render-key, the frame's live `subs-read`), in one round-trip. Rides the `data-rf-view` map (Spec 006 §View tagging) — works on any app with ZERO testids. Pass EXACTLY ONE entry point (precedence `view-id` > `point` > `selector`). Read-only. See [`ops.md` §ui/read](ops.md#view--rendered-content--producing-entity-uiread). |
| `read-dom`     | `{selector: "#app .counter", sub-selector: ".title", attrs: ["value"], max-text: 2000, limit: 50}` — **view-plane read by explicit CSS selector**: matched count + per-node `{:tag :text :attrs}`. The "did the UI actually update / what does the rendered node say?" read. Capped at the source (per-node `:max-text`, matched-node `:limit`); over-cap text → `:rf.size/large-elided`. `:attrs` omitted ⇒ curated default set + a `data-*` / `aria-*` sweep. Read-only. Pairs with `dispatch :await-render`. |

### Write (drive the runtime)

| MCP tool       | Args |
|----------------|------|
| `dispatch`     | `{event: "[:foo ...]", sync: true, frame: ":foo", trace: true, await-render: true, settle: true, queued: true, fx-overrides: {...}, cofx: "{:rf/time-ms ...}"}` — fire an event tagged `:origin :pair`. DEFAULT returns the **consequence** (`:epoch-id :db-changed? :changed-paths :effects-fired :no-op? :cascade-summary`) — `dispatch → verify` in one call. Event validated against the `:event` registrar (unknown → `:reason :unknown-id` + `:nearest`, NOT dispatched). `settle` flushes renders synchronously + returns the full epoch incl. `:render-events`. **`cofx`** (EDN map string — EP-0010 recording / EP-0017 authoring) pins the scripted recordable coeffects threaded to the flat `:rf.cofx` map so durable writes (`:created-at`, resource `:loaded-at`, machine snapshot times, plus any owner-qualified app fact like `:counter/delta`) are **reproducible** run-to-run — essential when verifying a durable write; `:rf/time-ms` must be an integer (a malformed map returns `:reason :invalid-cofx`). Composes with every mode + `frame` / `fx-overrides`. See [`ops.md` §Write](ops.md#write). |
| `dispatch-dry-run` | `{event: "[:cart/checkout]", frame: ":foo", fx-overrides: {...}}` — **simulate a cascade WITHOUT committing**. Full reducer + interceptor + schema + machine + sub/render run; NO fx execute (each redirected to a recording stub) and the framework rolls back via restore-epoch. Returns the same `:cascade-summary` shape as `dispatch` plus `:rolled-back? true` and `:would-fire-effects [{:fx-id :args}...]`. "Experiment without consequences" — NOT gated by `--allow-writes` (its contract IS no observable effect). |
| `restore-epoch` | `{epoch-id: "<id>", frame: ":foo"}` — **the canonical time-travel undo**. Rewind a frame's whole frame-state (both partitions: app-db AND runtime-db) to a recorded epoch, appending a synthetic record so the rewind itself is undoable. Returns `{:ok? true :restored? true :cascade-summary {…} :unreplayable-effects [...]}` or `{:ok? false :reason :restore-rejected}`. **`--allow-writes`-gated** (default OFF): against a gate-OFF server returns `{:ok? false :reason :rf.error/writes-disabled}` without touching the runtime. Prefer over a raw `eval-cljs` `(rf/restore-epoch! …)` (which is un-gated + un-audited). See [`ops.md` §Time-travel](ops.md#time-travel-epoch-restore). |
| `replace-app-db` | `{db: "{...}", frame: ":foo"}` — **the canonical state injection**. Replace a frame's app-db with an arbitrary EDN value the runtime never recorded (the JSON-loaded-bug-repro case), recording a synthetic `:rf.epoch/db-replaced` epoch + logging via `tap>`. `db` is parsed as EDN data (not host source). Returns `{:ok? true :cascade-summary {…}}` or `{:ok? false :reason :reset-rejected}`. **`--allow-writes`-gated** (default OFF): returns `{:ok? false :reason :rf.error/writes-disabled}` against a gate-OFF server. Prefer over a raw `eval-cljs` `app-db-reset!`. See [`ops.md` §Write](ops.md#write). |

### Trace + epoch (read-only)

| MCP tool       | Args |
|----------------|------|
| `trace-window` | `{ms: 1000, limit: 50, cursor: "<b64>"}` — the `:rf/epoch-records` added in the last N ms for the operating frame. Diff-encoded + deduped + cursor-paginated. |
| `watch-epochs` | `{pred: {"event-id-prefix": ":cart"}, since-id: "...", limit: 50, cursor: "..."}` — pull-mode poll: epochs matching `pred` that landed after `since-id`. Call repeatedly to live-watch. See [`ops.md` §Live watch](ops.md#live-watch-push-mode). |
| `watch-until`  | `{signals: "[{:app-db [:upload :status]}]", pred: {:signal 0 :equals :done}, timeout-ms: 30000}` — **block until a predicate over a signal holds**, the blocking counterpart to `record`. Server polls a cheap runtime read (~100ms cadence) until the condition trips or `timeout-ms` elapses. `{:ok? true :held? true :elapsed-ms :sample :t}` or `{:ok? false :reason :watch-timeout :last-sample {...}}`. SIGNAL / PRED vocab shared with `record`. Read-only. |
| `subscribe`    | `{topic: "trace"\|"epoch"\|"fx"\|"error"\|"frameless", filter: {...}, max-events: 0, max-ms: 0}` — push-mode; emits `notifications/progress` ticks; resolves on cancel / `max-events` / `max-ms` / `unsubscribe`. See `references/streaming-subscriptions.md`. |
| `unsubscribe`  | `{sub-id: "<uuid>"}` — idempotent close. |
| `list-streams` | `{}` (or `{topic: "epoch"}` / `{sub-id: "<uuid>"}`) — list active **streaming-tap** subscriptions with `:queue-depth`, `:dropped-events`, `:overflow-reason` without draining queues. Diagnostic for "is my probe still alive?". (The streaming diagnostic `list-subscriptions` formerly carried.) |
| `get-stream-controls` | `{}` — the **server-side** streaming resource-control state (effective caps, active slots vs limit, token-bucket pressure, abuse-window count vs threshold). The "why was my stream **denied / quiet / terminated**?" diagnostic, the complement to `list-streams` (which reads the *runtime* tap registry). Reads the server's control atoms **in-process — no nREPL round-trip**, so it answers **even when the runtime is down**. Control state only (no payloads) → **ungated** by `--allow-sensitive-reads`. `{:ok? true :config {…} :concurrent-streams {:active :limit :at-capacity?} :rate-limit {:throttling? …} :abuse-window {:tripped? …} :cross-check …}`. See [`streaming-subscriptions.md` §get-stream-controls](streaming-subscriptions.md#get-stream-controls--why-was-my-stream-denied--quiet--terminated). |
| `record`       | `{signals: "[{:focus true} {:dom \"#count\"}]", stop: {:ms 15000}, max-entries: N}` — **first-class signal recorder**: install a READ-ONLY observer over one-or-more SIGNALS with a STOP condition, let the human interact, then read the change-log with `read-recording`. The canonical move for intermittent / human-in-the-loop bugs (render-timing races under real mouse input). Returns IMMEDIATELY with a `:recording-id`; samples once per animation frame, records each CHANGE, dedups, tears itself down at the stop condition. SIGNAL shapes: `{:app-db [path]}` / `{:sub [query-v]}` / `{:dom "sel" :attr "name"}` / `{:focus true}`. STOP: `{:ms N}` / `{:changes N}` / `{:pred {:signal i :equals v}}`. Read-only. |
| `read-recording` | `{recording-id: "rec-abc", drain: true, stop: true}` — read back a recording's change-log: `{:ok? true :recording-id :status :stopped-reason :frames-sampled :count :entries [{:i :signal :value :t :frame}...]}`. Each entry is one CHANGE. `drain true` consumes buffered entries + keeps recording (live-watch idiom); `stop true` reads-and-closes. |

### Hot-reload + frames

| MCP tool       | Args |
|----------------|------|
| `tail-build`   | `{probe: "...", wait-ms: 5000}` — wait for a hot-reload to land by polling the probe until its value changes. Falls back to a 300ms soft timer (`:soft? true`) when `probe` is omitted. See [`ops.md` §Hot-reload](ops.md#hot-reload-coordination). |
| `get-operating-frame` | `{}` — read the session's operating-frame triple `{:frames :selected :operating}`. `:operating nil` ⇒ ambiguous (two-plus app frames, no pin). |
| `set-operating-frame` | `{frame: ":foo"}` — pin the session's operating frame; the escape from the tier-4 `:ambiguous-frame` refusal. Validates the frame is registered. |
| `reset-operating-frame` | `{}` — clear the pin; ops fall back to tier 3 / 4. Idempotent. |

The three operating-frame tools surface tier 2 of the frame-resolution cascade directly (no eval round-trip) — see SKILL.md §Multi-frame model.

The `subscribe` / `unsubscribe` pair is the **push-mode** counterpart to `watch-epochs`. Each batch of matching events arrives as a `notifications/progress` notification correlated by the call's `progressToken`; the tool's final result is a summary. Use `subscribe` whenever you want a live narration; use `watch-epochs` (pull-mode, polled in a loop) when the agent host doesn't surface progress notifications to the model.

(`inject-runtime` is gone — the runtime ships into the app via
shadow-cljs `:devtools :preloads`. See `SKILL.md` §Setup.)

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

**`:app-db` slice modes.** The `:app-db` slice no longer defaults to the full value. Two modes:

- **`:summary` (default, no `path`)** — the `:app-db` slot is a `{:rf.mcp/summary {:type :map :keys [...] :count ... :bytes ...}}` marker carrying the top-level shape without committing the token budget. Map-key lists over 64 entries get truncated and flagged `:keys-truncated? true` so the marker itself can never blow the wire cap.
- **`:path-sliced` (with `path`)** — the slot is `(get-in db path)`. Out-of-range paths surface per-frame in a top-level `:path-not-found` map with `:deepest-valid-prefix` so the agent can re-aim without a binary search.
- **Root path `path: "[]"`** — explicit request for the full `:app-db`, equivalent to the legacy default. A **last resort** (large on any real app frame; refused against a reserved `:rf/*` tool frame). Orient first, then slice. The wire cap is the backstop.

The other slices (`:sub-cache`, `:machines`, `:epochs`) pass through unchanged. The `:traces` slice now ships **cascade bundles by default** (`{:dispatch-id :frame :event :dispatched :handler :fx :effects :subs :renders :other :trace-events :parent-dispatch-id}` per cascade — the framework's `(re-frame.trace.tooling/trace-buffer frame-id)` shape and the same wire format the `subscribe` streaming surface emits on cascade-bundle topics).

Use `get-path` (next section) when you already know the addressed subtree — it's a single-slice round-trip rather than the multi-slice composition `snapshot` does.

## Preload probe (no inject step)

Every op needing the in-browser runtime first probes `js/globalThis.__re_frame2_pair_runtime` — the load-time marker the preload installs. If missing, the op refuses with `{:ok? false :reason :runtime-not-preloaded :hint "..."}`. A full page refresh drops the runtime, but the preload re-installs it on the next bundle load; no manual reconnect step.

### `eval-cljs`: build resolution + fail-loud preflight

`eval-cljs` resolves which shadow-cljs build to evaluate against and preflights the runtime sentinel *before* eval'ing, so a runtime-absent build can never return a misleading `{:ok? true :value nil}` (the old footgun: shadow's `cljs-eval` against a non-running build yields a blank value indistinguishable from a genuine `nil`).

- **Auto-detect:** with no `build` arg, `eval-cljs` detects the running shadow build (`shadow.cljs.devtools.api/active-builds`). Exactly one running ⇒ used; the resolved id is echoed as `:build`.
- **Fail loud:** a runtime-absent build (or zero/many running builds with no explicit `build`) returns `{:ok? false :reason :no-runtime-for-build :build <id> :running-builds [...] :hint "..."}` — never `:ok? true :value nil`. `:running-builds` shows which build to target via `build: "<id>"`.

The same resolution + preflight logic backs the legacy `scripts/` bash shim's `eval` op.

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
