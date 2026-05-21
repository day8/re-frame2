# Runtime seam

This chapter is about the **Causa ↔ tool read-and-mutate seam** — the contract a tool client (an MCP server, an IDE plugin, a record-replay harness, a future AI co-pilot drop) composes against when it wants to drive a running re-frame2 app from out-of-process. The core of it is **a namespace of pure-data accessors** — `day8.re-frame2-causa.runtime/<accessor>` — that a tool client renders as EDN forms over an nREPL channel to shadow-cljs, which evaluates them in the browser tab against the runtime that Causa's preload installed. The return values come back over the bencode-framed channel.

The seam is **public-for-tools, not public-for-host-apps**. A host application reaches for the runtime accessors only when it's writing tool-shaped code (a custom debug panel, a test-harness assertion, a record-replay export). The same Tool-Pair-style discipline that governs the framework's trace bus applies — Causa emits, the tool consumes — but the runtime is also a *mutation* surface (`dispatch!`, `restore-epoch!`, `reset-frame-db!`) for tool clients that need to drive the app rather than just observe it.

A keybinding lifecycle pair (`attach!` / `detach!`) lives in a sibling namespace and is also documented here because the embed-host escape hatch shape is closer to the runtime seam than to the mount facade.

## What the contract is

`day8.re-frame2-causa.runtime` lives on the browser side of an nREPL-shaped channel. It rides Causa's `:devtools/preloads` (no separate preload entry) so a consumer app that already loads Causa automatically carries the runtime. Today's reference consumer is `tools/re-frame2-pair-mcp/`; tomorrow's might be any MCP server, IDE plugin, or record-replay harness — the contract is the same.

Three load-bearing supports underpin every accessor:

- **`session-id`** — a random UUID set once per preload load. The MCP-server-side preload probe reads this to confirm the runtime landed.
- **`*current-origin*`** — a `^:dynamic` var holding the `:tags :origin` value the runtime stamps onto every mutation it performs. Tool clients rebind it for the synchronous extent of an eval'd form to identify themselves.
- **`health`** — a one-call summary of the runtime's view of the world. Used by `discover-app` tools.

All install side-effects are gated on `re-frame.interop/debug-enabled?`. A stray production load is a no-op — no `js/globalThis` pollution, no listener install.

## Discovery sentinel

Two markers prove the runtime landed in the host browser process.

| Marker | Spelling | Lifetime |
|---|---|---|
| CLJS var | `day8.re-frame2-causa.runtime/session-id` | Random UUID set once per preload load. Survives shadow-cljs `:after-load`; wiped by full page refresh. |
| JS global mirror | `js/globalThis.__day8_re_frame2_causa_runtime` | JS object carrying `session-id` + `installed` ms-timestamp. The MCP-server-side probe reads this without a CLJS compile round-trip. |

A page-refresh-cleared sentinel surfaces as `{:reason :runtime-not-preloaded}` on the next `discover-app` tool call, carrying a setup hint that points at the missing `:preloads` entry.

## Origin tag

Every mutation the runtime performs on behalf of a tool client carries `:tags :origin <tool-name>`. The runtime exposes a `^:dynamic` var the tool rebinds:

```clojure
(def ^:dynamic *current-origin*
  "Default :causa-mcp. Tool clients re-bind for the synchronous extent
   of an eval'd form to their own origin tag."
  :causa-mcp)
```

### `*current-origin*`

- **Kind**: `^:dynamic` Var
- **Status**: v1 (dev-only)
- **Description**: Default `:causa-mcp`. The tool client wraps each eval'd form in `(binding [runtime/*current-origin* :my-tool] ...)` for the synchronous extent.

### `current-origin`

- **Signature**:
  ```clojure
  (current-origin) → keyword
  ```
- **Status**: v1 (dev-only)
- **Description**: Read accessor — answers "what's the current `:origin` tag?". Public so tests can pin the rebind contract without `#'`-piercing the dynamic var.

The async-tagging gap: a dispatched event's downstream cascade carries the origin only through the synchronous handler frame. Later cascades pick up the framework's natural origin tagging.

## Frame resolution

Every accessor that operates on a frame resolves it via the same three-step fallback ladder:

1. Caller-supplied `:frame <id>` arg.
2. The sole registered frame (when exactly one is registered).
3. `nil` → accessor returns `{:ok? false :reason :no-frame-resolved :hint "Pass :frame :foo or register at least one frame."}`.

Multi-frame apps without an explicit `:frame` pick are surfaced via `discover-app`'s `:ambiguous-frame? true` flag rather than silently picking one. The tool-arg layer in the MCP server is the right place to refuse mutations against an ambiguous resolution; reads degrade through the documented `:no-frame-resolved` fallback.

## Privacy egress

Every direct-read accessor routes returned values through `re-frame.core/elide-wire-value` before egress. The single normative emission site for the `:rf/redacted` sensitive sentinel and the `:rf.size/large-elided` size marker lives in the framework; the runtime's job is to call it with `:include-sensitive?` and `:include-large?` defaulting `false` and to honour the caller's opt-in.

Callers pass plain `:include-sensitive?` / `:include-large?` opts; the runtime translates to the framework's `:rf.size/*` namespaced opt keys.

## Inspection band

Nine read-only accessors. Every one returns a map; success is `:ok? true`; failure is `:ok? false :reason <kw> :hint <str>`.

### `get-trace-buffer`

- **Signature**:
  ```clojure
  (get-trace-buffer opts) → {:ok? true :events <vec> :count <n>}
  ```
- **Status**: v1 (dev-only)
- **Description**: Filtered slice of the framework's trace stream. Filter keys are the canonical Spec 009 vocabulary — `:operation` / `:op-type` / `:since` / `:frame` / `:severity` / `:event-id` / `:handler-id` / `:source` / `:origin` / `:dispatch-id` / `:since-ms` / `:between` / `:pred`.

### `get-epoch-history`

- **Signature**:
  ```clojure
  (get-epoch-history opts) → {:ok? true :frame <id> :epochs <vec> :count <n>}
  ```
- **Status**: v1 (dev-only)
- **Description**: The per-frame epoch ring buffer. Each epoch is a `:rf/epoch-record` (drain-completion snapshot with `:db-before` / `:db-after`). Default depth 50.

### `get-app-db`

- **Signature**:
  ```clojure
  (get-app-db opts) → {:ok? true :frame <id> :path <vec> :value <edn>}
  ```
- **Status**: v1 (dev-only)
- **Description**: The live `app-db` for a frame, optionally scoped by `:path` for sub-tree reads. Reads through `elide-wire-value` so `:sensitive?` / `:large?`-marked paths egress as elision markers.

### `get-app-db-diff`

- **Signature**:
  ```clojure
  (get-app-db-diff opts) → {:ok? true :frame <id> :epoch-id <uuid> :diff {:before … :after …}}
  ```
- **Status**: v1 (dev-only)
- **Description**: Reads `:db-before` + `:db-after` off a named epoch record. Heavy nested-diff projection lives on the MCP server side; this accessor returns the raw before / after pair.

### `get-machine-state`

- **Signature**:
  ```clojure
  (get-machine-state opts) → {:ok? true :frame <id> :machine-id <kw> :state <edn>}
  ```
- **Status**: v1 (dev-only)
- **Description**: Per-machine state read. The Stately-grade state-chart inspector reads this; tools that want machine-state pinning for a record-replay assertion compose against it directly.

### `get-machine-list`

- **Signature**:
  ```clojure
  (get-machine-list opts) → {:ok? true :machines <map> :count <n>}
  ```
- **Status**: v1 (dev-only)
- **Description**: Map of every machine registered in the active frame, keyed by machine-id. Used by the machine-inspector dropdown and by tools enumerating the machine surface.

### `get-issues`

- **Signature**:
  ```clojure
  (get-issues opts) → {:ok? true :issues <vec> :count <n>}
  ```
- **Status**: v1 (dev-only)
- **Description**: Projection over the trace buffer filtered to issue-tier op-types — `:error` / `:warning` / `:rf.schema/violation` / `:rf.hydration/mismatch`. The Issues ribbon paints this; tools that want "what's broken right now?" reach for it.

### `get-handlers`

- **Signature**:
  ```clojure
  (get-handlers opts) → {:ok? true :handlers <vec> :count <n>}
  ```
- **Status**: v1 (dev-only)
- **Description**: Registrar listing, optionally narrowed by `:kind ∈ #{:event :sub :fx :cofx :machine :flow :reg-machine :frame :view}`. Source-coord metadata travels with each row.

### `get-source-coord`

- **Signature**:
  ```clojure
  (get-source-coord opts) → {:ok? true :kind <kw> :id <any> :source-coord <map>}
  ```
- **Status**: v1 (dev-only)
- **Description**: Per-registration source-coord projection. Resolves an event-id / sub-id / handler-id back to the `{:ns :file :line :column}` of where it was registered. The "click anywhere, walk to the line" backbone.

## Mutation band

Three write accessors. Every mutation tags the runtime cascade with `:tags :origin *current-origin*` so the action surfaces in the trace stream and downstream tool consumers can distinguish tool-driven cascades from app-driven ones.

### `dispatch!`

- **Signature**:
  ```clojure
  (dispatch! event-vec opts)
    → {:ok? true :event-id <kw> :frame <id> :origin <kw> :mode :queued/:sync}
  ```
- **Status**: v1 (dev-only)
- **Description**: Fire an event tagged with the current origin. Modes: `:queued` (default — non-blocking `rf/dispatch`) or `:sync` (`rf/dispatch-sync`). Frame resolution mirrors the read-side accessors.

### `restore-epoch!`

- **Signature**:
  ```clojure
  (restore-epoch! opts) → {:ok? true/false :frame <id> :epoch-id <uuid> :origin <kw>}
  ```
- **Status**: v1 (dev-only)
- **Description**: Rewind a frame's `app-db` to the named epoch's `:db-after` via `rf/restore-epoch`. Failures (six documented modes — see [Tool-Pair §Time-travel — Restore](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md)) emit a structured `:rf.epoch/*` trace and leave `app-db` unchanged; the accessor surfaces `:reason :rf.epoch/restore-failed` + a hint pointing to the trace bus.

### `reset-frame-db!`

- **Signature**:
  ```clojure
  (reset-frame-db! opts) → {:ok? true/false :frame <id> :origin <kw>}
  ```
- **Status**: v1 (dev-only)
- **Description**: Inject `:value` into a frame's `app-db`. Schema-validates via `rf/reset-frame-db!`; the three failure rows (`:rf.error/no-such-handler` / `:rf.epoch/reset-frame-db-during-drain` / `:rf.epoch/reset-frame-db-schema-mismatch`) surface on the trace bus; the accessor projects `:reason :rf.epoch/reset-failed` + a hint.

The three together compose the Tool-Pair time-travel surface: read an epoch, restore to that epoch, or directly inject a known-good state for "try anyway" recovery.

## Streaming band

Three subscription-bookkeeping accessors. The runtime records metadata for in-flight subscriptions; per-tick drain pumps and queue-overflow bookkeeping live on the MCP server side.

### `subscribe!`

- **Signature**:
  ```clojure
  (subscribe! opts) → {:ok? true :sub-id <uuid> :topic <kw> :filter <map>}
  ```
- **Status**: v1 (dev-only)
- **Description**: Open a streaming subscription for `:topic ∈ #{:trace :epoch :fx :error}` with `:filter`. The runtime records metadata; the MCP server's tick loop drains and forwards.

### `unsubscribe!`

- **Signature**:
  ```clojure
  (unsubscribe! opts) → {:ok? true :sub-id <id> :existed? <bool>}
  ```
- **Status**: v1 (dev-only)
- **Description**: Idempotent close. `:existed? false` for an unknown id.

### `list-subscriptions`

- **Signature**:
  ```clojure
  (list-subscriptions) → {:ok? true :subs <vec> :count <n>}
  ```
- **Status**: v1 (dev-only)
- **Description**: Diagnostic enumerating active runtime-side subscription metadata. Per-tick `:queue-depth` / `:queue-bytes` / `:dropped-events` fields live on the MCP server side.

## Escape hatch

One accessor handles arbitrary CLJS forms — the MCP server's `eval-cljs` channel renders the user-supplied form inside the runtime's `binding` wrapper.

### `eval-form-result`

- **Signature**:
  ```clojure
  (eval-form-result value opts) → {:ok? true :value <elided>}
  ```
- **Status**: v1 (dev-only)
- **Description**: The runtime-side result shaper. The MCP server renders the user's form inside a `(binding [*current-origin* …] …)` wrapper, `cljs-eval`s the wrapped form directly, and the result passes through `eval-form-result` for privacy + size scrubbing. Caller's `:include-sensitive?` / `:include-large?` opts gate the egress.

## Meta band

Two introspection accessors used by the tool client's discovery + change-detect protocols.

### `health`

- **Signature**:
  ```clojure
  (health)
    → {:ok? true :session-id <uuid> :debug-enabled? <bool> :frames <vec>
       :ambiguous-frame? <bool> :coord-annotation-enabled? <bool> :origin <kw>}
  ```
- **Status**: v1 (dev-only)
- **Description**: One-call summary of the runtime's view of the world. Side-effect-free — Causa-the-panel's preload owns the trace + epoch listeners; this accessor installs no listeners of its own. Used by `discover-app` tools.

### `tail-build-probe`

- **Signature**:
  ```clojure
  (tail-build-probe) → {:ok? true :probe <int> :session-id <uuid> :build-tick <int>}
  ```
- **Status**: v1 (dev-only)
- **Description**: Returns a fresh monotonic counter every call. MCP servers poll until the value changes — proving a hot-reload landed and the runtime re-evaluated. The counter survives `:after-load` (`defonce`) and resets only on full page refresh (same lifetime as `session-id`). Change-detect lives MCP-side.

## Test support

One test-fixture isolation helper. Production code never calls this.

### `reset-for-test!`

- **Signature**:
  ```clojure
  (reset-for-test!) → nil
  ```
- **Status**: v1 (test-only)
- **Description**: Clears `subscriptions` + `probe-counter` for fixture isolation. Does NOT touch `session-id` (per-preload constant by design) or the JS-global sentinel. Test-only — never call from production code.

## Keybinding lifecycle

The keybinding lifecycle pair lives in a sibling namespace — `day8.re-frame2-causa.keybinding` — but its shape and intended audience put it closer to the runtime seam than to the mount facade. The pair is the embed-host escape hatch: hosts that mount Causa as a right-hand-side panel (Story, third-party tool surfaces) and want to take the `Ctrl+Shift+C` chord back reach for `detach!`.

### `attach!`

- **Signature**:
  ```clojure
  (attach!) → nil
  ```
- **Status**: v1 (dev-only)
- **Description**: Install the global `Ctrl+Shift+C` keydown listener once. No-op on second + subsequent calls (the `attached-state` sentinel survives reloads). Honours the `:rf.causa/keybinding-enabled?` config slot — when `false` the listener is NOT installed.

### `detach!`

- **Signature**:
  ```clojure
  (detach!) → nil
  ```
- **Status**: v1 (dev-only)
- **Description**: Remove the global keydown listener if one is currently attached. Idempotent — safe to call when nothing is attached (no-op), and safe to call twice in a row (the second call is a no-op). Symmetric with `attach!`.

The two surfaces compose: standalone Causa calls `attach!` from the preload's six side-effects; an embed host that loaded Causa first and wants to take the chord back from inside its own mount lifecycle calls `detach!` after Causa has already attached.

Calling them in sequence — `attach! → detach! → attach!` — flips between attached / not-attached cleanly without leaking listeners or stale sentinel state.

## Cross-side coupling — one-way

The MCP server depends on the accessor signatures above (the contract). The runtime is independent of any server — Causa-the-panel loads `runtime.cljs` without an MCP server running, and any future MCP consumer can attach later without the runtime needing to know.

Adding an accessor is an additive change at the Causa layer; removing or renaming one is a breaking change to the Tool-Pair contract and requires a major-version bump. This is the same Tool-Pair discipline that governs the framework's trace bus and epoch history: the framework / Causa emits; tools consume; the contract is the data shape, not the call shape.

## A complete tool client interaction

A tool client's typical flow:

```
1. discover-app                  — runtime/health, reports :session-id, :frames, :ambiguous-frame?
2. get-trace-buffer               — runtime/get-trace-buffer {:since-ms 5000}
3. dispatch                       — runtime/dispatch! [:my.app/click 42] {:frame :app/main}
4. get-app-db                     — runtime/get-app-db {:frame :app/main :path [:counter]}
5. restore-epoch                  — runtime/restore-epoch! {:frame :app/main :epoch-id <uuid>}
6. unsubscribe / cleanup          — runtime/unsubscribe! for any open streaming subs
```

Every call carries an `:origin` tag (the tool client's identifier rebound for the synchronous extent of the eval'd form); every mutation surfaces in the trace stream where Causa's panel — running in the same browser — paints it. The tool client and the panel observe the same surface; neither coordinates with the other.

## See also

- [Mount control](mount-control.md) — the host-facing facade `open!` / `close!` / `toggle!` / `popout!` the runtime seam is parallel to.
- [Configuration keys](config-keys.md) — `:rf.causa/keybinding-enabled?` and the boot-time gate the `attach!` lifecycle pair reads.
- [Framework API — Instrumentation](../../api/11-instrumentation.md) — the trace bus, the epoch buffer, and `elide-wire-value` the runtime accessors consume.
- [Framework API — Registrar](../../api/12-registrar.md) — `rf/registrations` and `rf/handler-meta`, the framework primitives `get-handlers` and `get-source-coord` project over.
- [Tool-Pair spec](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md) — the normative contract for the framework / Causa emit, tool consume discipline.
