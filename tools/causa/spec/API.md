# API

The consolidated user-facing surface. Implementer-readable: every
symbol a consumer of Causa might reach for.

This doc is a **reference**; the normative descriptions live in the
per-area specs (000–011). Where the two drift, the per-area spec
wins.

## Installation API

### Preload-style enablement (browser)

```clojure
;; shadow-cljs.edn — dev build only
{:builds {:app {:devtools {:preloads [day8.re-frame2-causa.preload]}}}}
```

The preload:

1. Registers Causa's trace listener via `re-frame.core/register-trace-cb!`.
2. Registers Causa's epoch listener via `re-frame.core/register-epoch-cb!`.
3. Mounts a hidden `<div id="causa-root">` into `document.body`.
4. Listens for `Ctrl+Shift+C` (toggle) and the floating-pill click.
5. Reads localStorage for theme / density / AI-provider settings.
6. Boots the React root with the closed-by-default panel state.

### Force-disable

```clojure
:closure-defines {day8.re-frame2-causa.config/enabled? false}
```

When set false (or in a production build via `goog.DEBUG=false`), the
preload's entry point is a no-op; no DOM root, no listeners, zero
bytes after elision.

## Public CLJS API

Causa exposes a small handful of programmatic entry points. Users
typically interact via the panel; these are for embedding hosts,
test harnesses, and Settings UIs.

### `day8.re-frame2-causa.core`

```clojure
(causa/init!)
;; Mount Causa manually (alternative to :preloads). Idempotent.

(causa/init! opts)
;; opts: {:default-frame :app/main
;;        :theme         :dark / :light / :high-contrast
;;        :density       :compact / :cosy / :comfy
;;        :ai-provider   {:provider :claude / :openai / :gemini / :local / :custom
;;                        :api-key  "sk-..."     ;; localStorage only; never sent to Day8
;;                        :model    "claude-3-5-sonnet"
;;                        :system-prompt "..."}
;;        :buffer-depths {:trace 200 :epoch 50}}

(causa/open!)        ;; Show the panel programmatically.
(causa/close!)       ;; Hide the panel programmatically.
(causa/toggle!)      ;; Toggle.
(causa/popout!)      ;; Open the same-browser pop-out window.

(causa/active-frame)        ;; Return the frame currently selected in the picker.
(causa/set-active-frame! :app/main)

(causa/active-panel)        ;; Return the active panel id (one of :events, :app-db, :causality, ...).
(causa/set-active-panel! :causality)

(causa/load-theme css-string)
;; Programmatically swap the theme (useful for editor-driven palette sync).
```

### `day8.re-frame2-causa.panels.*`

Each panel exports its `*-view` component for embedding (per
[`008-Embedding-Contract.md`](./008-Embedding-Contract.md)). The
embedding contract names the public symbol `Panel`; the present
impl exports under `*-view`. Promoting to a capital-`Panel` alias
is the open decision tracked under the embedding-facade bead — the
canonical symbol list as of today is:

```clojure
day8.re-frame2-causa.panels.event-detail/event-detail-view
day8.re-frame2-causa.panels.causality-graph/causality-graph-view
day8.re-frame2-causa.panels.time-travel/time-travel-view
day8.re-frame2-causa.panels.app-db-diff/app-db-diff-view
day8.re-frame2-causa.panels.subscriptions/subscriptions-view
day8.re-frame2-causa.panels.effects/effects-view
day8.re-frame2-causa.panels.trace/trace-view
day8.re-frame2-causa.panels.machine-inspector/machine-inspector-view
day8.re-frame2-causa.panels.flows/flows-view
day8.re-frame2-causa.panels.routes/routes-view
day8.re-frame2-causa.panels.performance/performance-view
day8.re-frame2-causa.panels.schema-violation-timeline/schema-violation-timeline-view
day8.re-frame2-causa.panels.issues-ribbon/issues-ribbon-view
day8.re-frame2-causa.panels.hydration-debugger/hydration-debugger-view
day8.re-frame2-causa.panels.mcp-server/mcp-server-view
day8.re-frame2-causa.panels.ai-co-pilot/ai-co-pilot-view
```

Each accepts the props map specified in
[`008-Embedding-Contract.md`](./008-Embedding-Contract.md):

```clojure
{:frame    :app/main          ;; frame to observe (required)
 :compact? false              ;; reduced chrome (optional)
 :height   nil                ;; pixels (optional)
 :scope    {...}              ;; filter (optional)
 :on-event #(...)}            ;; emit-event callback (optional)
```

## Public JS API

For React-only hosts (a JS Story consumer, a non-Reagent app):

```javascript
import {init, open, close, toggle, popout} from '@day8/re-frame2-causa';
import {EventDetailPanel, CausalityPanel, /* ... */} from '@day8/re-frame2-causa/panels';

init({defaultFrame: ':app/main', theme: 'dark', density: 'cosy'});

open();
close();
toggle();
popout();

// Embedded panel
<EventDetailPanel
  frame=":app/main"
  compact
  height={320}
  scope={{dispatchIdPrefix: 'story-variant-7c2-', includeChildren: true}}
  onEvent={(evt) => console.log(evt)}
/>
```

The JS surface is a thin adapter over the Reagent components; props
camelCase what the Reagent surface kebab-cases.

## Trace / epoch surfaces (consumed, not exposed)

Causa **consumes** the framework's surfaces. It does not expose
analogues; users go to the framework for these. Listed here for
reference:

| Surface | Spec | What Causa reads |
|---|---|---|
| `(rf/register-trace-cb! key callback)` | Spec 009 | The trace bus (every operation). |
| `(rf/register-epoch-cb! key callback)` | Tool-Pair | The per-cascade epoch records. |
| `(rf/trace-buffer)` / `(rf/trace-buffer filter)` | Spec 009 | The bounded trace buffer (default 200). |
| `(rf/epoch-history frame-id)` | Tool-Pair | The per-frame epoch ring buffer (default 50). |
| `(rf/restore-epoch frame-id epoch-id)` | Tool-Pair | Used for confirmed rewinds. |
| `(rf/reset-frame-db! frame-id value)` | Tool-Pair | Used for "try anyway" recovery. |
| `(rf/get-frame-db frame-id)` | Spec 002 | The app-db panel's live read. |
| `(rf/compute-sub query-v db)` | Spec 008 | The sub-graph panel's value display. |
| `(rf/handlers kind)` / `(rf/handler-meta kind id)` | Spec 001 | Registry-browser metadata. |
| `(rf/frame-ids)` / `(rf/frame-meta id)` | Spec 002 | The frame picker. |
| `(rf/machines frame-id)` | Spec 005 | The machine inspector dropdown. |
| `(rf/app-schemas frame-id)` | Spec 010 | The schema-violation timeline rows. |
| `(rf/sub-cache frame-id)` (CLJS only) | Tool-Pair | The subscription graph. |
| `:dispatch-id` / `:parent-dispatch-id` (in `:tags`) | Spec 009 | The causality graph edges. |
| `:origin` (in `:tags`) | Spec 009 | The colour-coding axis. |
| Source-coord metadata (`:ns` / `:line` / `:column` / `:file`) | Spec 001 / 006 | Click-to-source — see `Open in editor` below. |
| `data-rf2-source-coord` DOM attribute | Spec 006 | DOM-level source-coord (for the rare cases where DOM event → source is needed). |

## Open in editor (rf2-evgf5)

Every panel that surfaces a source-coord (the event-detail hero, the
causality graph nodes, the machine inspector's state / edge / guard /
action chips, the hydration debugger's render-tree rows, the trace
panel's per-event rows, etc.) wraps the coord in a clickable `open`
chip. Click sets `window.location.href` to a URI-scheme handler the OS
dispatches to the configured editor:

| Editor (config key) | URI scheme |
|---|---|
| `:vscode` (default) | `vscode://file/<path>:<line>:<column>` |
| `:cursor`           | `cursor://file/<path>:<line>:<column>` |
| `:windsurf`         | `windsurf://file/<path>:<line>:<column>` |
| `:zed`              | `zed://file/<path>:<line>:<column>` |
| `:idea`             | `idea://open?file=<path>&line=<line>&column=<column>` |
| `{:custom <tpl>}`   | user template with `{path}` / `{file}` / `{line}` / `{column}` placeholders |

Host applications set the preference at boot via the `configure!`
entry point (full key surface normatively enumerated in
[`015-Configuration.md`](./015-Configuration.md)):

```clojure
(require '[day8.re-frame2-causa.config :as causa-config])
(causa-config/configure! {:editor :cursor})
```

Causa's editor preference is **independent** of Story's
`:rf.story/editor` (hosts that run both tools can route each tool to a
different editor). The shared URI builder lives at
`re-frame.source-coords.editor-uri` (core artefact, CLJC); Causa's
mirror chip (`day8.re-frame2-causa.open-in-editor/open-chip`) consumes
it. Unknown editor keywords fall back to `:vscode` so a typo still
yields a clickable URI rather than a no-op; source-coords without
`:file` hide the chip entirely.

## MCP API

Per [`010-MCP-Server.md`](./010-MCP-Server.md). The MCP server lives
at `tools/causa-mcp/` and exposes 12 tools:

| Tool | Kind |
|---|---|
| `get-trace-buffer` | read |
| `get-epoch-history` | read |
| `get-app-db` | read |
| `get-app-db-diff` | read |
| `get-machine-state` | read |
| `get-machine-list` | read |
| `get-issues` | read |
| `get-handlers` | read |
| `get-source-coord` | read |
| `restore-epoch` | mutate (user-confirmed) |
| `reset-frame-db` | mutate (user-confirmed) |
| `dispatch` | mutate (user-confirmed) |

JSONSchema for each tool's args is surfaced via `tools/list` per the
MCP spec.

## Settings keys

Settings persist in `localStorage` under the key
`day8.re-frame2-causa/settings/v1`. Distinct from the boot-time
`configure!` surface enumerated in
[`015-Configuration.md`](./015-Configuration.md) (which writes
process-global atoms) and from `(causa/init! opts)` above (which
wires per-instance panel state). Shape (validated by Malli):

```clojure
{:theme         :dark / :light / :high-contrast
 :density       :compact / :cosy / :comfy
 :ai-provider   {:provider :claude / :openai / :gemini / :local / :custom
                 :api-key      "sk-..."
                 :model        "claude-3-5-sonnet"
                 :system-prompt "..."
                 :custom-url   "https://..."     ;; only when :provider = :custom
                 :custom-headers {"X-..." "..."}}
 :buffer-depths {:trace 200 :epoch 50}
 :default-frame :app/main
 :sidebar-mode  :grouped / :show-all
 :launcher-pill {:hidden? false}
 :keybindings   {:toggle ["Ctrl+Shift+C"]   ;; vector for multiple binds
                 :command-palette ["Ctrl+K"]
                 ...}}
```

Corruption (schema fails) → Causa wipes the slot and writes the
default shape, surfacing a one-time toast: "Settings were corrupted
and have been reset to defaults."

Per-frame-app-db pinned slices live under a separate key:
`day8.re-frame2-causa/pinned-slices/<frame-id>/v1`.

```clojure
{:pinned-slices [{:path [:user :auth :status]
                  :label "auth status"
                  :pinned-at 1715518800000}
                 ...]}
```

## Trace-event tags Causa emits

When Causa mutates the runtime (rewind, reset, re-dispatch), it
emits trace events tagged `:origin :causa` (or `:origin :causa-mcp`
when from the MCP server) so its actions are visible in the trace
stream.

These ride the framework's existing `:event/dispatched`,
`:rf.epoch/restored`, etc. operations — no new operation kinds
invented (per [`Principles.md`](./Principles.md) §Observation only).

## Versioning

`day8/re-frame2-causa` follows semver. Major (1.x → 2.x) changes
break the public API or the embed contract. Minor (1.0 → 1.1) adds
panels or surfaces. Patch (1.0.0 → 1.0.1) fixes bugs without
contract changes.

The framework dep is `~> 1.0` (compatible with re-frame2's first
stable release). When the framework moves to 2.0, Causa's matching
major bumps with it.

## What this doesn't expose

- **No plugin registration API.** First-party panels only at v1.0.
- **No middleware injection.** Causa does not intercept dispatches;
  it only observes them.
- **No "private" surfaces** (`/-` namespaces, internal helpers) —
  callers must not reach for `day8.re-frame2-causa.internal/*`.
- **No global state mutators** beyond `init!` / `open!` / `close!`
  / `toggle!` / `set-active-frame!` / `set-active-panel!`. The
  panel's internal state is encapsulated.
