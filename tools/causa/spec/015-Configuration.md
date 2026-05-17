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
   :project-root          "C:/Users/me/code/my-app"
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

### `:project-root`

The on-disk root prepended to the source-coord's classpath-relative
`:file` slot before the editor URI ships (rf2-5m5n2). Source-coords
stamped at registration time are classpath-relative (form-meta `:file`
slot, e.g. `"app/cart/handlers.cljs"`); editor URI handlers
(`vscode://file/<path>...`, `cursor://...`, `idea://...`, etc.) resolve
`<path>` against the filesystem. A relative path fails with "Path does
not exist", so Causa's Open chip and the `:rf.editor/open` reg-fx need
to know the on-disk root to prepend before the URI ships.

| Value | Meaning |
|---|---|
| String | The on-disk root (typically the directory above the classpath source-paths). Joined to source-coord `:file` via `/`. Threaded into the URI by `re-frame.source-coords.editor-uri/editor-uri` via its 3-arg form. |
| `nil` | Default. Source-coord file ships verbatim — Open chip behaves as it did pre-rf2-5m5n2 (useful for hosts whose source-paths are already absolute, and for tests). |

Default: `nil`.

Blank strings MUST normalise to `nil`. Causa's `:project-root` is
**independent** of Story's (an app-source root for Causa, a stories
root for Story); two atoms, two `configure!` surfaces.

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
The host app owns the normal-flow right-side layout host; Causa renders
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

### `:settings`

Bulk-replace the Settings popup state map (rf2-9poxq). Shape mirrors
the `default-settings` block in `config.cljc`:

```clojure
{:general   {:text-size           13          ; px; slider range 10–18
             :panel-position      :right-rail ; :right-rail | :popout | :fullscreen
             :auto-open-on-error? false}
 :theme     :dark                              ; :dark | :light
 :telemetry {:opt-in?             false}}
```

| Value | Meaning |
|---|---|
| Map | Deep-merge over `default-settings` (per-section). Persists immediately to the localStorage key `re-frame2.causa.settings.v1` so the next page load reads the host-supplied posture. |
| (absent) | Leave the live settings map untouched. |

The popup's per-knob event surface (`:rf.causa/settings-update`) is
the normal write path; this key is the bulk-set escape hatch for
hosts that want to ship their own factory defaults (corporate fork
with light theme, embedded host that prefers `:fullscreen` panel
position, etc.).

Default-defining shape, per-knob rationale and the localStorage key
are normatively documented in
[`016-Auxiliary-Panels.md`](./016-Auxiliary-Panels.md) §Settings popup
— v1 ships. The `:editor` / `:project-root` / `:launch/auto-open?` /
`:trace/show-sensitive?` keys above remain process-global atoms
distinct from `:settings` (their semantics predate the popup; the
popup-managed surface is the `{:settings <map>}` shape).

### `:filters`

Host-supplied seed pill set the registry hydrates `:active-filters`
with on **first install** (when localStorage is empty). Per
[`018-Event-Spine.md`](./018-Event-Spine.md) §7 'Empty defaults',
Causa ships with no filters by default (first-session honesty beats
first-session quietness). The seed is the escape hatch for hosts
that have a reason to ship a starting posture — typically Story
testbeds that need a known starting point for reproducibility.

| Value | Meaning |
|---|---|
| `{:in [{:pattern <…>} …] :out [{:pattern <…>} …]}` | Seed the slot on first install only. The seed never clobbers a user's hand-tuned set — once localStorage carries any pill, the seed is ignored. |
| `nil` (default) | No seed; registry defaults to `{:in [] :out []}`. |

### `:filters/storage-key`

The localStorage key the filter persistence layer reads / writes.

| Value | Meaning |
|---|---|
| String | Use this key for round-trip. Hosts that run multiple Causa instances in the same browser session (e.g. Story testbeds) override so each instance keeps its own pill state. |
| `nil` | Reset to default. |

Default: `"re-frame2.causa.filters.v1"` (versioned so future schema
changes can ignore stale payloads).

When both `:filters` and `:filters/storage-key` are passed in one
call, the storage key is set BEFORE the seed so a host that overrides
both gets the seed persisted under the right key.

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

- `:density`, `:default-frame`, `:ai-provider`, `:buffer-depths`,
  `:sidebar-mode`, `:launcher-pill`, `:keybindings` — all currently
  owned by `(causa/init! opts)` and the persisted Settings shape per
  [`API.md`](./API.md). A future consolidation MAY migrate them
  through `configure!`; until then, set them via the per-instance /
  per-localStorage paths.

Note: `:theme` is **no longer reserved** — it now lives inside the
`:settings` map (see above) and is reachable via the Settings popup's
Theme tab or `(configure! {:settings {:theme :light}})`.

## Vision — full configure! key inventory (30+ keys)

v1 ships ~5 host-supplied keys (`:editor` / `:project-root` /
`:layout/host-selector` / `:launch/auto-open?` / `:trace/show-sensitive?`)
plus the `:filters` seed slot. The full destination per
[`ai/findings/2026-05-17-10x-config-options-for-causa.md`](#findings)
absorbs every re-frame-10x configuration option that translates plus
several Causa-native additions. The full list, grouped by phase
priority:

### Must-haves (matched against re-frame-10x's anchor)

- `:filters/auto-hide-events <set>` — exact event-ids to auto-hide
  (re-frame-10x's `ignored-events`). Wired via the IN/OUT pill system
  in [`018-Event-Spine.md`](./018-Event-Spine.md) §7.
- `:filters/auto-hide-event-ns <vector>` — event-id namespace
  patterns to auto-hide (e.g. `["my-app.noisy" "re-com.box"]`).
- `:filters/auto-hide-error-overrides? <bool>` — when an auto-hidden
  event raises an exception, surface it anyway (default `true`).
  Errors override filters.
- `:buffer/retained-epochs <int>` — exposed retainer-N depth control
  (re-frame-10x's `retained-epochs`). Floor 25; ceiling 5000.
- `:theme` — already wired in v1 via `:settings`. Future: `:light`,
  `:dark`, `:dim`.

### Should-adds

- `:keybinding/handle-keys? <bool>` — master toggle for Causa's
  keystroke capture; default `true`. Hosts with conflicting global
  shortcuts can surrender.
- `:keybinding/bindings <map>` — rebind any action; default carries
  the spec-mandated set (`Ctrl+Shift+C`, `c`/`r`/`f`/`a`/`v`/`t`/`m`/`i`
  + spine keys per [`018-Event-Spine.md`](./018-Event-Spine.md) §Keyboard map).
- `:render/ns-aliases <map>` — rendering substitution so deeply-nested
  namespaces (`{my-app.deeply.nested mnn}`) collapse in panel renders.
  Re-frame-10x's `ns-aliases`.
- `:render/alias-namespaces? <bool>` — master toggle for ns-aliases
  substitution (paired with above).
- `:render/auto-expand-below <int>` — auto-expand data nodes with
  fewer than N children in the cljs-devtools-shaped renderer.
- `:render/uuids-as <enum :plaintext :identicons :last-4>` — UUID
  rendering format.
- `:launch/restore-visibility? <bool>` — persist last-known visibility
  across reloads.
- `:launch/popout-geometry <map>` — remember last popout window
  position `{:w :h :x :y}`.
- `:trace/collect-when <enum :always :panel-open>` — gate trace
  collection on panel visibility (re-frame-10x's `trace-when`).

### Nice-to-haves

- `:trace/fatten? <bool>` — opt into trace fattening for
  context-at-position payloads (Phase 5 prereq per
  [`013-Trace-Bus.md`](./013-Trace-Bus.md) §Vision).
- `:settings.tab/persist? <bool>` — persist selected tab across
  reloads.
- `:logging/debug? <bool>` — Causa self-debug logs (re-frame-10x's
  `debug?`). Backlog — Causa instruments itself via the trace bus;
  redundant for most cases.

### Recovery action (not a key)

- `(causa-config/factory-reset!)` — wipes every
  `day8.re-frame2-causa.*` localStorage key + resets in-memory atoms.
  Red button in the Settings popup; CLI escape hatch for "I broke
  something and don't know what to fix."

The full destination is auditable against `tools/causa/test/.../config_cljc_test.cljc`
which enforces no slot is forgotten when the surface grows.

<a id="findings"></a>

**Findings:** `ai/findings/2026-05-17-10x-config-options-for-causa.md`
carries the per-key design rationale, cross-reference against
re-frame-10x's 26 options, and the priority ranking that drives the
phase plan above.

## Production posture

Per [`API.md`](./API.md) §Force-disable, production builds DCE the
Causa shell. The config atoms survive (`configure!` is CLJC) but are
never read; calling `configure!` in production is a no-op observable
only through the atoms. Hosts MAY guard the call behind
`goog.DEBUG` / `^boolean js/goog.DEBUG` if avoiding the no-op write
matters — typically it does not.
