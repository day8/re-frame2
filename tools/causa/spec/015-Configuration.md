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

## Reserved-namespace convention — `:rf.<tool>/*` (rf2-xea9u)

Every Causa `configure!` key lives under the `:rf.causa/*` reserved
sub-namespace. This is the canonical convention for re-frame2 tools:
each tool reserves its own `:rf.<tool>/*` namespace under the
framework root, per
[`spec/Conventions.md` §Reserved namespaces](../../../spec/Conventions.md#reserved-namespaces-framework-owned).
Story uses `:rf.story/*`, Causa uses `:rf.causa/*`, and any future
re-frame2 tool that ships its own `configure!` MUST follow the same
pattern.

The convention solves three problems:

1. **Collision protection.** A host application that merges its own
   config map with Causa's never collides on bare names like
   `:editor` or `:auto-open?`.
2. **Greppability.** `rg ':rf.causa/'` finds every Causa knob across
   code, docs, skills, and Story testbed seed snippets.
3. **Discoverability.** IDE auto-completion against `:rf.causa/`
   reveals the catalogue without reading this doc.

**Cross-tool keys** — knobs that more than one tool reads from the
same atom — live under their own reserved namespace. The canonical
case is the privacy gate `:rf.privacy/show-sensitive?`, which Causa
AND Story both consult; setting it once via either tool's
`configure!` is enough.

Pre-alpha posture: the rename is a hard cut. Legacy bare / dotted
spellings (`:editor`, `:auto-open?`, `:launch/auto-open?`, etc.) are
NOT accepted — unknown keys are silently ignored per the forward-
compat rule below.

## Entry point

```clojure
(require '[day8.re-frame2-causa.config :as causa-config])

(causa-config/configure!
  {:rf.causa/editor                :cursor
   :rf.causa/project-root          "C:/Users/me/code/my-app"
   :rf.causa/layout-host-selector  "[data-rf-causa-host]"
   :rf.causa/auto-open?            true
   :rf.causa/keybinding-enabled?   true
   :rf.privacy/show-sensitive?     false})
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

### `:rf.causa/editor`

The 'Open in editor' click-to-source target. Drives every panel that
surfaces a source-coord (event-detail hero, machine inspector chips,
hydration debugger rows, trace panel rows — per
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

### `:rf.causa/project-root`

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

### `:rf.privacy/show-sensitive?`

The cross-tool privacy gate for `:sensitive? true` trace events per
[Spec 009 §Privacy](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)
(resolved by `rf2-a32kd`) and bead `rf2-azls9`. Framework-published
trace-consuming integrations MUST default-suppress `:sensitive? true`
events; Causa is a framework-published consumer. The key lives under
the cross-tool `:rf.privacy/*` reserved sub-namespace (per
[`spec/Conventions.md`](../../../spec/Conventions.md) and
[`spec/Privacy.md`](../../../spec/Privacy.md)) — Story and every
other re-frame2 tool that consumes the trace bus reads the same atom,
so one host config knob covers every tool.

| Value | Meaning |
|---|---|
| `false` | Default. Causa's trace collector MUST drop events whose top-level `:sensitive?` field is `true` before any buffer push, and MUST bump the suppressed-events counter (see [§App-db slots](#app-db-slots) below) so the shell's bottom rail can surface a `[● REDACTED N]` indicator. |
| `true` | The collector receives every event unchanged; `:sensitive? true` events flow through the bus to every consumer. |
| `nil` | Resets to default (`false`). |

The flag MUST be read at the head of the collector body on every
event so toggling it via `configure!` takes effect on the next trace
event without re-registering the listener (per
[`013-Trace-Bus.md`](./013-Trace-Bus.md) §Privacy gate).

`:rf.privacy/show-sensitive?` is **one-way lossy** — flipping from
`false` to `true` only affects *future* events. Sensitive events
already dropped under the default are gone from the buffer; only the
suppressed-counter survives. Hosts debugging a redaction policy
typically flip the flag and re-drive the runtime to see the raw
cascade.

### `:rf.causa/layout-host-selector`

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

### `:rf.causa/auto-open?`

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

### `:rf.causa/keybinding-enabled?`

Controls whether `keybinding/attach!` installs Causa's global,
capture-phase `keydown` listener. The listener handles Causa's
spec-published shortcuts: `Ctrl+Shift+C` (shell toggle), `Cmd/Ctrl+K`
(command palette), and the unmodified spine bindings
(`Space` / `L` / `j` / `k` / `G` / `c` / `Esc`). It calls
`stopPropagation()` for the keys it consumes so host bindings further
down the propagation path don't double-fire.

| Value | Meaning |
|---|---|
| `true` | Default. `keybinding/attach!` installs the listener; the standalone Causa shell behaves exactly as it did pre-rf2-4eyik. |
| `false` | Suppress installation entirely. `keybinding/attach!` short-circuits to a no-op; the sentinel does not flip; no listener lands on `js/document`. |
| `nil` | Reset to default (`true`). |

Hosts MUST set this BEFORE the Causa preload runs (the preload calls
`keybinding/attach!` at adapter-ready time). Setting it afterwards is
a no-op on the already-attached listener unless the host explicitly
calls `keybinding/detach!` (see below).

The slot exists for embed hosts (per
[`008-Embedding-Contract.md`](./008-Embedding-Contract.md) — Story
mounts Causa as its right-hand-side panel) whose own global
keybindings collide with Causa's. Story's command-palette
(`Cmd/Ctrl+K`) is the canonical collision: without the toggle, Causa's
capture-phase listener consumes the keypress before Story's handler
fires. Per rf2-4eyik (rf2-q7who Thread A) — the embed-contract gap
discovered via rf2-drprn.

#### `keybinding/detach!` — public escape hatch (rf2-ycrt2)

`day8.re-frame2-causa.keybinding/detach!` is the public companion to
`attach!` for embed hosts whose mount lifecycle runs AFTER Causa's
preload. The contract:

```clojure
(require '[day8.re-frame2-causa.keybinding :as causa-keybinding])

(causa-keybinding/detach!)
```

- **No arguments**; returns `nil`.
- **Idempotent**. Calling it when nothing is attached is a no-op (the
  internal sentinel does not underflow); calling it twice in a row is
  safe.
- **Symmetric with `attach!`**. The pair `(attach!) → (detach!) →
  (attach!)` flips between attached / not-attached cleanly without
  leaking listeners.
- **Safe in any host**. Guarded on `(exists? js/document)`.

When to call: embed hosts that flip `:rf.causa/keybinding-enabled?` to
`false` from a **mount-time** hook (not boot-time) must follow the
slot flip with `detach!`. The slot alone is read only at attach time;
without `detach!` the listener Causa's preload installed under the
default-true posture stays on `js/document` and continues consuming
keypresses despite the intent declaration. Per rf2-ycrt2 (rf2-q7who.1
runtime follow-on). Story's `ensure-causa-mounted!` is the canonical
example: it calls `disable-keybinding!` (slot flip) then
`detach-keybinding!` (runtime removal) on every variant-selection
edge.

Boot-time hosts (those that call
`configure! {:rf.causa/keybinding-enabled? false}` BEFORE Causa's
preload runs) do NOT need to call `detach!` —
their slot flip lands before `attach!` reads it, the short-circuit
fires, and no listener is ever installed. `detach!` exists for the
mount-time lifecycle the slot's attach-time-only read cannot cover
alone.

### `:rf.causa/settings`

Bulk-replace the Settings popup state map (rf2-9poxq; expanded by
rf2-ttnst — Mike 2026-05-19 §0ter.4 walkthrough). Shape mirrors the
`default-settings` block in `config.cljc`:

```clojure
{:general   {:text-size              13          ; px; slider range 10–18
             :panel-position         :right-rail ; :right-rail | :popout | :fullscreen
             :panel-width-px         480         ; number; clamped [320, 0.9 × viewport-width-px]
             :auto-open-on-error?    false
             :density                :cosy       ; #{:cosy :compact} — no :comfy in v1
             :show-tool-frames?      false       ; reveal :rf/causa + :rf/pair2 in L1 picker
             :long-keyword-threshold 24}         ; chars; long-keyword elision threshold
 :theme     :dark                                ; :dark | :light
 :diff      {:highlight-fn-ref-changes? false}   ; opt-in fn-ref classification
 :buffer    {:retained-epochs                    200    ; epoch buffer depth
             :trace-buffer/keep                  1000   ; raw trace-event ring depth
             :app-db/inspector-collapse-threshold 50}}  ; inspector auto-collapse branching
```

The `:general` slot carries three knobs introduced by rf2-ttnst:

- `:density` — `:cosy` (default) or `:compact`. Drives the Views
  detail rows + App-db diff rows vertical rhythm. The `:comfy` tier
  catalogued earlier in spec/007-UX-IA.md §Density slider is dropped
  in v1; persisted `:comfy` values from prior schemas are treated as
  `:cosy` by the `:rf.causa/density` convenience sub.
- `:show-tool-frames?` — boolean. When `true` the L1 frame-picker
  dropdown reveals `:rf/causa` + `:rf/pair2`. Default `false` per
  spec/007-UX-IA.md §Frame-observation isolation invariants §I1.
- `:long-keyword-threshold` — integer (chars). Fully-qualified
  keywords longer than the threshold elide in compact list cells.
  Default `24`, was previously a fixed constant; now user-tuneable
  per spec/007-UX-IA.md §Long-keyword treatment.

The `:buffer` slot carries the buffer-depth tunables surfaced in the
Buffer tab:

- `:retained-epochs` — count of epochs Causa retains in its causal
  ring. Default `200`.
- `:trace-buffer/keep` — count of raw trace events kept. Mirrors
  `trace-bus/default-buffer-depth` (`1000`).
- `:app-db/inspector-collapse-threshold` — branch factor above which
  the App-db inspector auto-collapses. Default `50`.

The Buffer tab also exposes a destructive "Clear buffer now" action
that fires `trace-bus/clear-buffer!` after a confirmation modal
(`"Clear buffer? This deletes all retained epochs."` → Cancel /
Clear). The action is dispatch-only and carries no `configure!`
counterpart; hosts that need a programmatic clear call the trace-bus
helper directly.

The `:panel-width-px` slot (rf2-x8h9y) drives the
`:right-rail` panel's horizontal width. The Causa drag handle (per
[`007-UX-IA.md` §Resize affordance](./007-UX-IA.md#resize-affordance))
writes through to this slot on drag-end; the slot persists via the
existing `re-frame2.causa.settings.v1` localStorage key so width survives
reloads. Default `480`. Ignored in `:popout` (window owns size) and
`:fullscreen` (viewport owns size) positions.

> Note (rf2-jh9ws): a `:telemetry` slot shipped briefly with the
> initial popup landing (rf2-9poxq) but was removed — Causa
> transmits no telemetry. Legacy `:telemetry` keys in persisted
> payloads or in `(configure! {:rf.causa/settings ...})` calls are
> silently dropped by the per-section merge.

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
— v1 ships. The `:rf.causa/editor` / `:rf.causa/project-root` /
`:rf.causa/auto-open?` / `:rf.privacy/show-sensitive?` keys above
remain process-global atoms distinct from `:rf.causa/settings` (their
semantics predate the popup; the popup-managed surface is the
`{:rf.causa/settings <map>}` shape).

### `:rf.causa/filters`

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

### `:rf.causa/filters-storage-key`

The localStorage key the filter persistence layer reads / writes.

| Value | Meaning |
|---|---|
| String | Use this key for round-trip. Hosts that run multiple Causa instances in the same browser session (e.g. Story testbeds) override so each instance keeps its own pill state. |
| `nil` | Reset to default. |

Default: `"re-frame2.causa.filters.v1"` (versioned so future schema
changes can ignore stale payloads).

When both `:rf.causa/filters` and `:rf.causa/filters-storage-key`
are passed in one call, the storage key is set BEFORE the seed so a
host that overrides both gets the seed persisted under the right
key.

### `:rf.causa/static-mode?`

The Static-mode feature flag (rf2-o5f5f.1). Gates whether Causa's
surface composer mounts the dual-mode chrome (Runtime + Static, with
the mode pill at ribbon-left and the Cmd-Shift-M chord wired to
`:rf.causa/toggle-mode`) or the pre-Static Runtime-only chrome.

| Value | Meaning |
|---|---|
| `false` | Default. The surface composer renders Runtime byte-identical to the pre-Static chrome — the mode pill is absent, `Cmd-Shift-M` / `Ctrl-Shift-M` falls through to host / browser shortcuts. Persisted mode in `causa.mode` localStorage is not consulted. |
| `true` | The mode pill mounts at ribbon-left (`data-testid="rf-causa-mode-pill"`), `Cmd-Shift-M` / `Ctrl-Shift-M` toggles between Runtime and Static surfaces via `:rf.causa/toggle-mode`, and the active mode hydrates from `causa.mode` localStorage on boot (with `"runtime"` fallback). |
| `nil` | Resets to default (`false`). |

Default: `false`.

The flag default flips to `true` once the placeholder Static sub-tabs
(rf2-o5f5f.4 / .5 / .6) ship — separate decision, tracked under the
`:rf.causa/static-mode?` follow-on. Hosts that want Static mode
today (e.g. Story testbeds for design review of the 3-layer chrome)
opt in via `(configure! {:rf.causa/static-mode? true})` and live
with the placeholder cards on the still-pending sub-tabs.

**Persistence.** The mode SELECTION (not the flag) persists under
the localStorage key `causa.mode` as a bare string (`"runtime"` /
`"static"`). The persistence fx is
`:rf.causa.static/persist-mode` (per
[`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md) §Static
mode). The flag itself is a per-load atom — hosts must call
`configure!` on every boot if they want Static mode active; flipping
the flag while the panel is mounted is legal but is reserved for
hot-reload / live-rebind scenarios.

Cross-reference: [`007-UX-IA.md`](./007-UX-IA.md) §Static mode
(visual-language treatment of the mode pill, edge stripe, motion
dampening, chrome silhouette) +
[`018-Event-Spine.md`](./018-Event-Spine.md) §Static surface (the
architectural contract — 3-layer silhouette, 4-signal mode-recognition
mechanism, mode-state lifecycle).

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
under the current `:rf.privacy/show-sensitive?` setting. Events without a
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
`:rf.causa/settings` map (see above) and is reachable via the Settings
popup's Theme tab or `(configure! {:rf.causa/settings {:theme :light}})`.

## Vision — full configure! key inventory (30+ keys)

v1 ships ~6 host-supplied keys (`:rf.causa/editor` /
`:rf.causa/project-root` / `:rf.causa/layout-host-selector` /
`:rf.causa/auto-open?` / `:rf.causa/keybinding-enabled?` /
`:rf.privacy/show-sensitive?`) plus the `:rf.causa/filters` seed
slot. Future tool-owned keys follow the same `:rf.causa/*` convention;
cross-tool keys live under their own `:rf.<area>/*` reservation
(`:rf.privacy/*` today; future cross-tool surfaces would book their
own segments via [`spec/Conventions.md`](../../../spec/Conventions.md)).
The full destination per
[`ai/findings/2026-05-17-10x-config-options-for-causa.md`](#findings)
absorbs every re-frame-10x configuration option that translates plus
several Causa-native additions. The full list, grouped by phase
priority:

### Must-haves (matched against re-frame-10x's anchor)

All forthcoming keys follow the `:rf.causa/*` convention.

- `:rf.causa/filters-auto-hide-events <set>` — exact event-ids to
  auto-hide (re-frame-10x's `ignored-events`). Wired via the IN/OUT
  pill system in [`018-Event-Spine.md`](./018-Event-Spine.md) §7.
- `:rf.causa/filters-auto-hide-event-ns <vector>` — event-id namespace
  patterns to auto-hide (e.g. `["my-app.noisy" "re-com.box"]`).
- `:rf.causa/filters-auto-hide-error-overrides? <bool>` — when an
  auto-hidden event raises an exception, surface it anyway (default
  `true`). Errors override filters.
- `:rf.causa/buffer-retained-epochs <int>` — exposed retainer-N depth
  control (re-frame-10x's `retained-epochs`). Floor 25; ceiling 5000.
- Theme — already wired in v1 via `:rf.causa/settings`. Future:
  `:light`, `:dark`, `:dim`.

### Should-adds

- `:rf.causa/keybinding-handle-keys? <bool>` — master toggle for
  Causa's keystroke capture; default `true`. Hosts with conflicting
  global shortcuts can surrender.
- `:rf.causa/keybinding-bindings <map>` — rebind any action; default
  carries the spec-mandated set (`Ctrl+Shift+C`,
  `c`/`r`/`f`/`a`/`v`/`t`/`m`/`i` + spine keys per
  [`018-Event-Spine.md`](./018-Event-Spine.md) §Keyboard map).
- `:rf.causa/render-ns-aliases <map>` — rendering substitution so
  deeply-nested namespaces (`{my-app.deeply.nested mnn}`) collapse in
  panel renders. Re-frame-10x's `ns-aliases`.
- `:rf.causa/render-alias-namespaces? <bool>` — master toggle for
  ns-aliases substitution (paired with above).
- `:rf.causa/render-auto-expand-below <int>` — auto-expand data nodes
  with fewer than N children in the cljs-devtools-shaped renderer.
- `:rf.causa/render-uuids-as <enum :plaintext :identicons :last-4>` —
  UUID rendering format.
- `:rf.causa/launch-restore-visibility? <bool>` — persist last-known
  visibility across reloads.
- `:rf.causa/launch-popout-geometry <map>` — remember last popout
  window position `{:w :h :x :y}`.
- `:rf.causa/trace-collect-when <enum :always :panel-open>` — gate
  trace collection on panel visibility (re-frame-10x's `trace-when`).

### Nice-to-haves

- `:rf.causa/trace-fatten? <bool>` — opt into trace fattening for
  context-at-position payloads (Phase 5 prereq per
  [`013-Trace-Bus.md`](./013-Trace-Bus.md) §Vision).
- `:rf.causa/settings-tab-persist? <bool>` — persist selected tab
  across reloads.
- `:rf.causa/logging-debug? <bool>` — Causa self-debug logs
  (re-frame-10x's `debug?`). Backlog — Causa instruments itself via
  the trace bus; redundant for most cases.

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
