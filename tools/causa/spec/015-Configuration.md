# 015-Configuration

Causa exposes a single top-level configuration entry point —
`day8.re-frame2-causa.config/configure!` — which the host calls once at
boot to wire up Causa's runtime knobs. This doc normatively
enumerates `configure!`'s accepted keys, their semantics and defaults,
and the per-frame Causa app-db slots those knobs drive.

The promise: an AI agent or human reader handed only this doc MUST be
able to reconstruct the full `configure!` surface — every key, every
accepted value, every default — without reading
`tools/causa/src/day8/re_frame2_causa/config.cljc`. Pair this doc with
[`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md) (the
`:rf.causa/*` registrar surface) and you have the complete contract
between Causa and its host.

The split: `configure!` is the **process-global** Causa surface (one
atom per key, shared across every host that loads Causa) — distinct
from `(causa/init! opts)` per [`API.md`](./API.md) §Public CLJS API
which wires per-instance booleans into the panel's state machine, and
distinct from the persisted Settings shape per [`API.md`](./API.md)
§Settings keys which round-trips through `localStorage`.

## Entry point

```clojure
(require '[day8.re-frame2-causa.config :as causa-config])

(causa-config/configure!
  {:editor                :cursor
   :layout/host-selector  "[data-rf-causa-host]"
   :launch/auto-open?     true
   :trace/show-sensitive? false})
```

`configure!` MUST accept a map and MUST return `nil`. Keys not listed
below MUST be silently ignored (forward-compat: future Causa releases
will grow keys; older hosts passing newer keys MUST not break, and
newer hosts passing older-Causa-unaware keys MUST not break). Absent
keys MUST leave the corresponding atom untouched — `configure!` is
**additive**, not replacing-the-whole-config; calling it twice with
disjoint key sets composes.

Hosts SHOULD call `configure!` exactly once at boot, before the Causa
preload mounts. Calling it after mount is legal — every key is read at
its consumer's hot path on each use, so changes take effect on the
next read — but defeats the "boot-time configuration" mental model and
is reserved for hot-reload / live-rebind scenarios (Settings panel,
dev REPL).

## Configuration keys

### `:editor`

The 'Open in editor' click-to-source target. Drives every panel that
surfaces a source-coord (event-detail hero, causality nodes, machine
inspector chips, hydration debugger rows, trace panel rows — per
[`API.md`](./API.md) §Open in editor).

| Value | URI scheme | Notes |
|---|---|---|
| `:vscode` | `vscode://file/<path>:<line>:<column>` | Default when unset or `nil`. |
| `:cursor` | `cursor://file/<path>:<line>:<column>` | Cursor (the VS Code fork) — its own URI handler. |
| `:windsurf` | `windsurf://file/<path>:<line>:<column>` | Windsurf (a VS Code fork; registers its own scheme distinct from VS Code's; rf2-mqm2d / rf2-queq0). |
| `:zed` | `zed://file/<path>:<line>:<column>` | Zed (rf2-mqm2d / rf2-queq0). |
| `:idea` | `idea://open?file=<path>&line=<line>&column=<column>` | IntelliJ family — IDEA, WebStorm, PyCharm. The single `idea://` handler dispatches across every JetBrains IDE. |
| `{:custom "<tpl>"}` | user template | Template containing `{path}` / `{file}` / `{line}` / `{column}` placeholders. Substituted at click time. The escape hatch for editors Causa does not know natively. |
| `nil` | (resets to `:vscode`) | Explicit reset to default. |

Default: `:vscode`.

Unknown editor keywords MUST fall back to `:vscode` so a typo still
yields a clickable URI rather than a no-op. Source-coords without a
`:file` MUST hide the click chip entirely. The canonical URI builder
lives at `re-frame.source-coords.editor-uri` (core artefact, CLJC);
Causa's open-in-editor chip consumes it via
`day8.re-frame2-causa.config/editor-uri`.

The full set of URI-construction rules — default-editor behaviour
when unset, line/column defaults, no-URL-encoding posture, the
no-handler-installed clean-no-op fallback, the `{:custom …}`
substitution contract — is normatively specified in
[`007-UX-IA.md` §URI construction](./007-UX-IA.md#uri-construction-normative).
The matrix here enumerates the keywords; that section binds them
into MUSTs.

Causa's `:editor` is **independent** of Story's `:rf.story/editor`
(per [`spec/007-Stories.md`](../../../spec/007-Stories.md)). Hosts
running both tools MAY route each to a different editor — e.g.
`:vscode` for the application code Causa points at, `:idea` for the
Story test corpus.

### `:trace/show-sensitive?`

The privacy gate for `:sensitive? true` trace events per
[Spec 009 §Privacy](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)
(resolved by `rf2-a32kd`) and bead `rf2-azls9`. Framework-published
trace-consuming integrations MUST default-suppress `:sensitive? true`
events; Causa is a framework-published consumer.

| Value | Meaning |
|---|---|
| `false` | Default. Causa's trace collector MUST drop events whose top-level `:sensitive?` field is `true` before any buffer push, and MUST bump the suppressed-events counter (see [§App-db slots](#app-db-slots) below) so the shell's bottom rail can surface a `[● REDACTED N]` indicator. |
| `true` | The collector receives every event unchanged; `:sensitive? true` events flow through the bus to every consumer. |
| `nil` | Resets to default (`false`). |

The flag MUST be read at the head of the collector body on every
event so toggling it via `configure!` takes effect on the next trace
event without re-registering the listener (per
[`013-Trace-Bus.md`](./013-Trace-Bus.md) §Privacy gate).

`:trace/show-sensitive?` is **one-way lossy** — flipping from `false`
to `true` only affects *future* events. Sensitive events already
dropped under the default are gone from the buffer; only the
suppressed-counter survives. Hosts debugging a redaction policy
typically flip the flag and re-drive the runtime to see the raw
cascade.

### `:layout/host-selector`

The CSS selector Causa uses for its default true-inline shell mount.
The host app owns the normal-flow left-side layout host; Causa renders
inside it after substrate readiness.

| Value | Meaning |
|---|---|
| CSS selector string | Use this selector when finding the app-provided Causa host. |
| `nil` | Reset to the default selector. |

Default: `[data-rf-causa-host]`.

If the selector cannot be found when the default launch path opens,
Causa MUST emit the actionable missing-host diagnostic described in
[`011-Launch-Modes.md`](./011-Launch-Modes.md) §Layout host contract.

### `:launch/auto-open?`

Controls only the preload's default launch attempt. It does not disable
Causa, the trace/epoch collectors, browser API exports, keybinding, or
explicit `open!` / `toggle!` calls.

| Value | Meaning |
|---|---|
| `true` | Default. After `rf/init!` installs a substrate adapter, the preload opens the Causa shell in the configured true-inline host. |
| `false` | Suppress only the automatic page-load open. Use this for tool-owned Story/static canvases that intentionally do not reserve app real estate for Causa. |
| `nil` | Reset to default (`true`). |

Hosts that set this to `false` SHOULD do so before `rf/init!`, so the
preload's adapter-ready probe sees the final launch posture before it
would otherwise diagnose a missing host. The missing-host diagnostic is
unchanged for the default path and for explicit opens.

## App-db slots

`configure!` is the host-visible surface; under the hood, Causa
mirrors privacy-gate state into its own `:rf/causa` app-db so the
reactive sub-graph drives UI updates immediately (per
[`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md) §Shared
infrastructure and bead `rf2-0vxdn`). Two slots are normatively
specified:

### `[:suppressed-counters {<frame-id> <count>}]`

A `frame-id → count` map, where each value is the number of
`:sensitive? true` trace events the collector dropped for that frame
under the current `:trace/show-sensitive?` setting. Events without a
frame scope (registration-time emits, outermost-dispatch lookup
failures) MUST count under the `:global` bucket so a count is never
lost.

The slot is updated by the `:rf.causa/note-sensitive-suppressed` event
(per [`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md)
§Shared infrastructure) dispatched from the trace collector. It is
cleared by `:rf.causa/reset-suppressed-counters` — either entirely
(no-arg) or per-bucket — fired from `trace-bus/clear-buffer!` and
test fixtures.

The `:rf.causa/suppressed-sensitive-count` subscription reads this
slot and returns the total across every bucket; the
`[● REDACTED N]` bottom-rail indicator binds to that sub so the count
updates IMMEDIATELY on every collector bump, with no dependency on
sibling subs recomputing (rf2-0vxdn PR #681).

The slot's source-of-truth duality is deliberate: the underlying atom
in `day8.re-frame2-causa.config/suppressed-counters` remains the
JVM-runnable data primitive (so CLJC unit tests can assert it without
spinning up a CLJS runtime and a frame), and the dispatch into
`:rf/causa` is the reactive surface for CLJS. Both stay in lockstep —
every atom bump fires a matching dispatch; every dispatch comes from
an atom bump.

### Causa-owned `:rf/causa` frame

Every other piece of Causa state — selected dispatch-id, selected
panel, pin store, target-frame, etc. — lives under the `:rf/causa`
frame's app-db per [`008-Embedding-Contract.md`](./008-Embedding-Contract.md)
§State isolation (Option-C frame-provider). Those slots are owned by
the panels that drive them; this doc enumerates only the
configuration-derived slot (`:suppressed-counters`) because it is the
visible bridge between `configure!` and the reactive surface.

## Reserved keys

The following keys are **reserved** for future `configure!` extension.
Hosts MUST NOT use them for their own purposes; future Causa releases
MAY assign them semantics.

- `:theme`, `:density`, `:default-frame`, `:ai-provider`,
  `:buffer-depths`, `:sidebar-mode`, `:launcher-pill`, `:keybindings`
  — all currently owned by `(causa/init! opts)` and the persisted
  Settings shape per [`API.md`](./API.md). A future consolidation MAY
  migrate them through `configure!`; until then, set them via the
  per-instance / per-localStorage paths.

## Production posture

Per [`API.md`](./API.md) §Force-disable, production builds DCE the
Causa shell. The config atoms survive (`configure!` is CLJC) but are
never read; calling `configure!` in production is a no-op observable
only through the atoms. Hosts MAY guard the call behind
`goog.DEBUG` / `^boolean js/goog.DEBUG` if avoiding the no-op write
matters — typically it does not.
