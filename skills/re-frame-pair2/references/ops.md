# Operations catalogue

The op vocabulary the skill operates through. Each op is reachable via the **MCP transport** (preferred — ~14× faster, single persistent nREPL connection) or the **bash-shim transport** (legacy fallback). The MCP form is shown in the Invocation column; the bash-shim equivalents are catalogued in the [Bash-shim back-compat appendix](#bash-shim-back-compat-appendix) at the end of this file.

Most ops wrap a call into `re-frame-pair2.runtime`; for those, the MCP form is `eval-cljs {form: "<runtime call>"}`. Dedicated MCP tools (`dispatch`, `snapshot`, `get-path`, `trace-window`, `watch-epochs`, `tail-build`, `subscribe`, `unsubscribe`) cover the broader concerns. Prefer the **structured ops** over `repl/eval` whenever a structured op fits. See [`mcp-transport.md`](mcp-transport.md) for transport details.

## Contents

- [Read](#read)
- [Write](#write)
- [Trace](#trace) — trace stream + epoch history
- [DOM source bridge](#dom-source-bridge)
- [Live watch (push-mode)](#live-watch-push-mode)
- [Hot-reload coordination](#hot-reload-coordination)
- [Time-travel (epoch restore)](#time-travel-epoch-restore)
- [Bash-shim back-compat appendix](#bash-shim-back-compat-appendix)
- [Dropped from v1 (re-frame-pair) — surfaces with no v2 equivalent](#dropped-from-v1-re-frame-pair--surfaces-with-no-v2-equivalent)

## Read

| Op | Invocation | Returns |
|---|---|---|
| `app-db/snapshot` | `mcp__re-frame-pair2__snapshot {}` | Current app-db value for the operating frame (via `rf/get-frame-db`). The MCP `snapshot` tool defaults to **`:summary` mode** (top-level shape only) + supports `:path`-slicing at the wire boundary (rf2-tygdv) — see [`mcp-transport.md` §:app-db slice modes](mcp-transport.md#when-to-use-snapshot-vs-the-per-op-reads). Pass `path: "[]"` for the full unsliced value. The underlying runtime form is `(re-frame-pair2.runtime/snapshot)`. |
| `app-db/get` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/app-db-at [:path :to :value])"}` | Path-scoped value (via `rf/snapshot-of`). For targeted reads, prefer the `get-path` tool below — single round-trip, structured `{:exists?}` answer, shared `:path` vocabulary with `snapshot`. |
| `app-db/get-path` | `mcp__re-frame-pair2__get-path {path: "[:cart :items 0 :sku]"}` | Targeted read at `path` (rf2-tygdv). `{:ok? true :exists? true :value <subtree>}` on hit; `{:ok? false :reason :path-not-found :deepest-valid-prefix [...]}` on miss. `:exists?` distinguishes a path that points at `nil` from a missing path. |
| `app-db/schemas` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/schemas)"}` | Map of `path → schema` from `rf/app-schemas` |
| `registrar/list` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/registrar-list :event)"}` | Ids registered under `:event` / `:sub` / `:fx` / `:cofx` (via `rf/handlers`) |
| `registrar/describe` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/registrar-describe :event :cart/apply-coupon)"}` | Full handler metadata: kind, interceptor ids, `:ns` / `:line` / `:file`, `:rf/machine?`, retained source form when present |
| `subs/cache` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/sub-cache)"}` | `rf/sub-cache` — `{query-v {:value v :ref-count n}}` for every materialised subscription (CLJS-only) |
| `subs/sample` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/subs-sample [:cart/total])"}` | One-shot value via `rf/compute-sub` (no cache mutation) or `@(rf/subscribe ...)` |
| `machines/list` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/machines-list)"}` | Machine ids (`rf/machines`) |
| `machines/describe` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/machine-describe :auth)"}` | The registered spec map (`rf/machine-meta`) |
| `machines/state` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/machine-state :auth)"}` | Current snapshot from `(rf/snapshot-of [:rf/machines :auth])` |

## Write

| Op | Invocation | Notes |
|---|---|---|
| `dispatch` | `mcp__re-frame-pair2__dispatch {event: "[:cart/apply-coupon \"SPRING25\"]"}` | Queued by default; pass `sync: true` to force `dispatch-sync`. Skill-issued dispatches carry `:origin :pair` (Spec 002 §Dispatch origin tagging) so `:event/dispatched` traces can be filtered by who fired them. |
| `dispatch --frame` | `mcp__re-frame-pair2__dispatch {event: "[:foo]", frame: ":stories"}` | Targets a specific frame via the `:frame` opt on `rf/dispatch`. |
| `reg-event` / `reg-sub` / `reg-fx` | `mcp__re-frame-pair2__eval-cljs {form: "<full reg-* form>"}` | Re-registration replaces; emits `:rf.registry/handler-replaced` trace (Spec 001 §Hot-reload semantics). Ephemeral. |
| `app-db/reset` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/app-db-reset! ...)"}` | Delegates to `rf/reset-frame-db!` (Tool-Pair §Pair-tool writes, rf2-zq55) — replaces app-db, records a synthetic `:rf.epoch/db-replaced` epoch, validates against schema, refuses during a drain. Logged explicitly via `tap>` so the user sees what the agent changed. Use sparingly. |
| `repl/eval` | `mcp__re-frame-pair2__eval-cljs {form: "<arbitrary form>"}` | Escape hatch. Prefer structured ops first. |
| `fx-overrides/with` | `mcp__re-frame-pair2__dispatch {event: "[:cart/checkout]", fx-overrides: {":http": ":stub-http"}}` | Per-call `:fx-overrides` (Spec 002 §Per-frame and per-call overrides) — redirect a registered fx to a stub for one experiment, restore on completion. |

## Trace

Read-only from the trace stream + epoch history.

| Op | Invocation | Returns |
|---|---|---|
| `trace/buffer` | `mcp__re-frame-pair2__eval-cljs {form: "(rf/trace-buffer)"}` | Recent N trace events from the retain-N ring (Spec 009 §Retain-N trace ring buffer). Optional `{:operation _ :op-type _ :since _ :frame _}` filter. |
| `trace/last-epoch` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/last-epoch)"}` | Most recent `:rf/epoch-record` for the operating frame |
| `trace/last-pair-epoch` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/last-pair-epoch)"}` | Most recent epoch whose `:trigger-event`'s top-level dispatch carried `:origin :pair` (i.e. *this skill* fired it) |
| `trace/epoch` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/epoch-by-id <id>)"}` | The named epoch from the frame's history |
| `trace/dispatch-and-collect` | `mcp__re-frame-pair2__dispatch {event: "[:foo ...]", trace: true}` | Fire + wait for drain-settle + return the resulting `:rf/epoch-record` |
| `trace/recent` | `mcp__re-frame-pair2__trace-window {ms: <ms>}` | Epochs whose `:committed-at` falls inside the last N ms |
| `trace/find-where` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/find-where <pred>)"}` | Most recent epoch matching a predicate — primary forensic op for "when did X happen?" post-mortems |
| `trace/find-all-where` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/find-all-where <pred>)"}` | Every matching epoch, newest first — for trajectories rather than single transitions |
| `trace/cascade` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/cascade-of <dispatch-id>)"}` | Walk `:dispatch-id` / `:parent-dispatch-id` (Spec 009 §Dispatch correlation) to reconstruct the full cascade tree from a root dispatch |
| `trace/configure-privacy` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/configure-privacy! {:include-sensitive? true})"}` | Set the privacy posture for the streaming subscription surface. Default: `{:include-sensitive? false}` — drops `:sensitive? true` trace events before they reach the LLM-facing queue, per [Spec 009 §Privacy](../../../spec/009-Instrumentation.md). Resets on page reload. See [references/vocabulary.md §Privacy posture](vocabulary.md#privacy-posture--sensitive-and-the-streaming-surface). |

## DOM source bridge

**Why this family matters — read first.** When the runtime is configured to annotate rendered DOM (`(rf/configure :source-coords {:annotate-dom? true})` per Tool-Pair §Source-mapping), every rendered DOM node carries a `data-rf2-source-coord` attribute pointing back to the registration that produced it. The attribute's value resolves via `re-frame-pair2.runtime/parse-rf2-coord` to a structured `{:ns ... :line ... :file ...}` map keyed off the registration's source coords (auto-captured by `reg-*` macros, per Spec 001 §Source-coordinate capture). This gives you a direct, two-way bridge between a live DOM element and the exact line of source code that rendered it.

**Two attribute formats are recognised:**

- `data-rf2-source-coord` — re-frame2's own annotation when `:annotate-dom?` is on. Stable, preferred.
- `data-rc-src` — re-com's debug-instrumentation attribute. The runtime parses both; if both are present on a node, `data-rf2-source-coord` wins.

**Prerequisites — at least one of:**

- re-frame2 source-coord annotation enabled (`(rf/configure :source-coords {:annotate-dom? true})` at startup), *or*
- re-com debug instrumentation enabled and the call site passed `:src (at)`.

**Degradation is per-element.** When neither is present on a given element, the bridge returns `{:src nil :reason :no-coord-at-this-element}`. When neither annotation is enabled app-wide, every element returns `{:src nil :reason :source-coord-annotation-disabled}`. Tell the user which case they're hitting.

| Op | Invocation | Returns |
|---|---|---|
| `dom/source-at` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/dom-source-at \"#save-button\")"}` (or `(... :last-clicked)`) | `{:ns :line :file}` for a CSS selector, or for the most recently clicked element |
| `dom/find-by-src` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/dom-find-by-src \"view.cljs\" 84)"}` | Live DOM elements rendered by that source line |
| `dom/fire-click-at-src` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/dom-fire-click \"view.cljs\" 84)"}` | Synthesise a click on the element rendered by that line |
| `dom/describe` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/dom-describe \"#save-button\")"}` | Tag, classes, both source-coord attributes, and any registration metadata they resolve to |

## Live watch (push-mode)

Two transports, same underlying assembled-epoch / trace stream.

**MCP streaming subscriptions (preferred for push-mode).** True server-pushed events delivered via `notifications/progress`, correlated by the call's `progressToken`. See [streaming-subscriptions.md](streaming-subscriptions.md) for topics, filters, termination, and the recipes that prefer this path.

| Op | MCP tool | Behaviour |
|---|---|---|
| `trace/subscribe` | `mcp__re-frame-pair2__subscribe` | Open a streaming subscription on the `:trace`, `:epoch`, `:fx`, or `:error` bus. Returns a `sub-id`; each batch arrives as a `notifications/progress` tick until termination. |
| `trace/unsubscribe` | `mcp__re-frame-pair2__unsubscribe` | Close a subscription by `sub-id`. Idempotent — unknown ids return `:existed? false`. |

**Pull-mode poll (legacy / fallback).** The `watch-epochs` MCP tool is the pull-mode wrapper: call repeatedly with `since-id` to drain new matches. Use this when the agent host doesn't surface `notifications/progress` to the model, or when you want a finite window summary rather than a live stream.

| Op | Invocation | Behaviour |
|---|---|---|
| `watch/window` | `mcp__re-frame-pair2__watch-epochs {window-ms: 30000, pred: {"event-id-prefix": ":checkout/"}}` | Runs for N ms, reports every matching epoch, summarises at end |
| `watch/count` | `mcp__re-frame-pair2__watch-epochs {count: 5}` | Runs until N epochs match |
| `watch/stream` | `mcp__re-frame-pair2__watch-epochs {stream: true, pred: {"event-id-prefix": ":cart/"}}` | Streams until disconnect, idle-timeout, or `watch/stop` |
| `watch/stop` | `mcp__re-frame-pair2__watch-epochs {stop: true}` | Terminates any active watch for this session |

Predicate keys (any combination, inside `pred`): `event-id`, `event-id-prefix`, `effects`, `timing-ms` (e.g. `">100"`), `touches-path`, `sub-ran`, `render`, `origin` (`:pair|:app|:ui|:timer|:http`), `frame`.

Mode rules:

- `window-ms` and `count` are independent. `window-ms` alone runs for N ms with no count limit; `count` alone runs until N matches with no window timeout. If both are set, the first condition to fire wins. With neither (and no `stream: true`), the default is a 30 s window.

The watch transport polls the assembled-epoch stream by tracking the last seen `:epoch-id` in the operating frame's history and asking for everything since. See `docs/initial-spec.md` §4.4.

## Hot-reload coordination

Editing source is legitimate and often correct. The protocol is strict — after any source edit, before the next `dispatch` / `trace/*`:

1. Make the edit with `Edit` / `Write`.
2. Call `mcp__re-frame-pair2__tail-build` with a `probe` that verifies the browser has the new code (legacy fallback: `scripts/tail-build.sh --probe '...'`).
3. Only after the probe succeeds do you proceed to `dispatch`, `trace/*`, etc.
4. If the probe times out, treat that as a compile error in the user's code — read the tail output, report it to the user, do *not* retry dispatching.

```
mcp__re-frame-pair2__tail-build {wait-ms: 5000, probe: "(some/probe-form)"}
```

`probe` is a CLJS form chosen to change when the edited code reloads. Good probes for re-frame2:

- After editing a `reg-*` handler: `(re-frame-pair2.runtime/registrar-handler-ref :event <id>)` — compares a hash over `handler-meta`. The underlying `(rf/handler-meta :event :foo)` `:line` / `:column` / `:handler-fn` change after re-registration; capture the meta map's hash before the edit, compare after.
- After editing a `reg-machine`: same shape against `:event` (machines register under `:event` per Spec 005); `(rf/machine-meta :auth)` is the equivalent direct read.
- After editing a view or helper: pick a CLJS form that derefs the view's namespace var (e.g. `(some-ns/my-view)` or `(meta #'some-ns/my-view)`).
- If you don't know a good probe, omit `probe` and the tool falls back to a 300ms timer; the result includes `:soft? true` so you know it's timer-based.

A successful probe-flip also coincides with a `:rf.registry/handler-replaced` trace event arriving in the buffer, so an alternative confirmation is `(filter #(= :rf.registry/handler-replaced (:operation %)) (rf/trace-buffer {:since <pre-edit-id>}))`. Use whichever fits — they're not exclusive.

## Time-travel (epoch restore)

re-frame2 ships first-class time-travel as part of the Tool-Pair contract — no adapter, no internal poking. These ops are **fully implemented** and use only public surfaces.

| Op | Invocation | Purpose |
|---|---|---|
| `epoch/history` | `mcp__re-frame-pair2__eval-cljs {form: "(rf/epoch-history :rf/default)"}` | The full ring of `:rf/epoch-record` values for the frame, oldest-first |
| `epoch/restore` | `mcp__re-frame-pair2__eval-cljs {form: "(rf/restore-epoch :rf/default <epoch-id>)"}` | Rewind the frame's `app-db` to the named epoch's `:db-after`. Returns `true` on success, `false` on any documented failure mode (see below). |
| `epoch/configure` | `mcp__re-frame-pair2__eval-cljs {form: "(rf/configure :epoch-history {:depth 200})"}` | Bump the ring depth (default 50). |
| `undo/step-back` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/undo-step-back)"}` | Sugar: restore the previous epoch in the operating frame |
| `undo/to-epoch` | `mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/undo-to-epoch <id>)"}` | Sugar over `restore-epoch` for the operating frame |

**Documented failure modes** (Tool-Pair §Time-travel — restore is a no-op on failure):

| Failure | Trace operation | When |
|---|---|---|
| Unknown frame | `:rf.error/no-such-handler` (kind `:frame`) | `frame-id` not registered |
| Unknown epoch | `:rf.epoch/restore-unknown-epoch` | `epoch-id` not in current history (aged out or never recorded) |
| Schema mismatch | `:rf.epoch/restore-schema-mismatch` | `:db-after` no longer validates against currently-registered schemas (a schema was tightened since the snapshot) |
| Missing handler | `:rf.epoch/restore-missing-handler` | DB references a registration id no longer in the registrar (e.g. a machine snapshot whose machine was unregistered) |
| Version mismatch | `:rf.epoch/restore-version-mismatch` | Recorded `:rf/snapshot-version` of an active machine is incompatible with the currently-loaded definition (hot-reload bumped it) |
| Concurrent drain | `:rf.epoch/restore-during-drain` | Called while the frame's run-to-completion drain is in flight |

When `restore-epoch` returns `false`, read the matching trace event from `(rf/trace-buffer {:op-type :error})` to get the structured `:tags`, then report to the user.

**Caveat (always tell the user before restoring):** restore rewinds `app-db` only. Side effects that already fired (HTTP requests sent, navigation pushed, localStorage written, `:dispatch-later` already landed) are *not* undone.

## Bash-shim back-compat appendix

The bash shims under `scripts/` are the legacy transport — each spawns bash → babashka → a fresh nREPL connect per call (~700ms per op). The MCP server (above) is the canonical path; keep the shims for ad-hoc shell scripting, CI scripts, or when the MCP server isn't configured in the agent host. Op semantics are identical between transports.

Map MCP tools to bash shims:

| MCP tool | Bash-shim equivalent |
|---|---|
| `eval-cljs {form: "..."}` | `scripts/eval-cljs.sh '<form>'` |
| `dispatch {event: "[:foo]"}` | `scripts/dispatch.sh '[:foo]'` |
| `dispatch {event: "...", sync: true}` | `scripts/dispatch.sh '...' --sync` |
| `dispatch {event: "...", frame: ":foo"}` | `scripts/dispatch.sh '...' --frame :foo` |
| `dispatch {event: "...", trace: true}` | `scripts/dispatch.sh '...' --trace` |
| `dispatch {event: "...", fx-overrides: {...}}` | `scripts/dispatch.sh '...' --fx-override :http=:stub-http` |
| `trace-window {ms: N}` | `scripts/trace-window.sh N` |
| `watch-epochs {window-ms: ..., pred: {...}}` | `scripts/watch-epochs.sh --window-ms ... --event-id-prefix ...` |
| `watch-epochs {count: N}` | `scripts/watch-epochs.sh --count N` |
| `watch-epochs {stream: true, ...}` | `scripts/watch-epochs.sh --stream ...` |
| `watch-epochs {stop: true}` | `scripts/watch-epochs.sh --stop` |
| `tail-build {wait-ms: ..., probe: "..."}` | `scripts/tail-build.sh --wait-ms ... --probe '...'` |
| `discover-app {}` | `scripts/discover-app.sh` |
| `snapshot {...}` | _MCP-only_ (no bash-shim equivalent; chain individual `eval-cljs` calls for `snapshot`-style mega-reads via the legacy transport) |
| `get-path {path: "..."}` | _MCP-only_ (use `eval-cljs '(re-frame-pair2.runtime/app-db-at [...])'` for a coarse equivalent) |
| `subscribe` / `unsubscribe` | _MCP-only_ (push-mode requires `notifications/progress`; under the bash shim use `scripts/watch-epochs.sh --stream` for the pull-mode approximation) |

For full transport mechanics and the `:app-db` slice modes that only the MCP `snapshot` tool exposes, see [`mcp-transport.md`](mcp-transport.md).

## Dropped from v1 (re-frame-pair) — surfaces with no v2 equivalent

The v1 `re-frame-pair` skill carried a few surfaces that have no direct re-frame2 equivalent today. They have been **dropped** rather than ported:

- **`subs/live` (10x's "currently subscribed query vectors" view)** — replaced by `subs/cache` (`rf/sub-cache`), which is the public Tool-Pair-pinned shape `{query-v {:value v :ref-count n}}`. Same need, different surface.
- **10x's internal epoch-buffer accessor + ring-rollover detection** — gone; replaced by `(rf/epoch-history frame-id)` which is bounded and self-describing (size = `(count history)`, depth = `(:depth (epoch/current-config))`).
- **10x's internal undo / step-back navigation** — gone; replaced by first-class `(rf/restore-epoch frame-id epoch-id)` with six documented failure modes (see [Time-travel](#time-travel-epoch-restore)).
- **`re-com-debug-disabled` heuristic** — kept (re-com is still a valid source-coord source), but the source-coord story now leads with re-frame2's own `:annotate-dom?` annotation; re-com's `data-rc-src` is a fallback rather than the only path.
- **`trace-enabled?` discovery check** — replaced by `interop/debug-enabled?` (the `goog.DEBUG` mirror per Spec 009 §Production builds). Same gate, framework-canonical name.
- **Version-floor enforcement against re-frame-10x / re-com / re-frame** — gone (no re-frame-10x dependency; re-com is optional; re-frame2's version is implicit in the loaded ns).

If during real-world use a surface re-frame2 currently lacks would unblock a recipe (e.g. successful-fx attribution in `:effects` projection, or a stable `:render-key` shape), file a `bd` bead against the spec rather than working around in this skill.
