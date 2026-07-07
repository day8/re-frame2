# 015-Configuration

Xray exposes a single top-level configuration entry point —
`day8.re-frame2-xray.config/configure!` — which the host calls once at
boot to wire up Xray's runtime knobs. This doc normatively
enumerates `configure!`'s accepted keys, their semantics and defaults,
and the per-frame Xray app-db slots those knobs drive.

The promise: an AI agent or human reader handed only this doc MUST be
able to reconstruct the full `configure!` surface — every key, every
accepted value, every default — without reading
`tools/xray/src/day8/re_frame2_xray/config.cljc`. Pair this doc with
[`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md) (the
`:rf.xray/*` registrar surface) and you have the complete contract
between Xray and its host.

The split: `configure!` is the **process-global** Xray surface (one
atom per key, shared across every host that loads Xray) — distinct
from `(xray/init! opts)` per [`API.md`](./API.md) §Public CLJS API
which wires per-instance booleans into the panel's state machine, and
distinct from the persisted Settings shape per [`API.md`](./API.md)
§Settings keys which round-trips through `localStorage`.

## Reserved-namespace convention — `:rf.<tool>/*` (rf2-xea9u)

Every Xray `configure!` key lives under the `:rf.xray/*` reserved
sub-namespace. This is the canonical convention for re-frame2 tools:
each tool reserves its own `:rf.<tool>/*` namespace under the
framework root, per
[`spec/Conventions.md` §Reserved namespaces](../../../spec/Conventions.md#reserved-namespaces-framework-owned).
Story uses `:rf.story/*`, Xray uses `:rf.xray/*`, and any future
re-frame2 tool that ships its own `configure!` MUST follow the same
pattern.

The convention solves three problems:

1. **Collision protection.** A host application that merges its own
   config map with Xray's never collides on bare names like
   `:editor` or `:auto-open?`.
2. **Greppability.** `rg ':rf.xray/'` finds every Xray knob across
   code, docs, skills, and Story testbed seed snippets.
3. **Discoverability.** IDE auto-completion against `:rf.xray/`
   reveals the catalogue without reading this doc.

**Per-tool egress profile.** The on-box privacy gate is owned
**per `(tool, frame)`** — there is no cross-tool shared atom and no
single process-global toggle (EP-0015 issue 7 /
[Spec 015 §Cross-tool visibility grain](../../../spec/015-Data-Classification.md#cross-tool-visibility-grain)).
Each tool reserves its own egress-profile knob under its own
`:rf.<tool>/*` namespace: Xray's is `:rf.xray/egress-profile`, Story's
is `:rf.story/egress-profile`. Setting one tool's profile does NOT
affect the other's — visibility is scoped to the tool that holds the
knob.

Pre-alpha posture: the rename is a hard cut. Legacy bare / dotted
spellings (`:editor`, `:auto-open?`, `:launch/auto-open?`, etc.) are
NOT accepted — unknown keys are silently ignored per the forward-
compat rule below.

## Key-naming axis — navigation map (rf2-dz35f · audit-of-audits #16)

> **`configure!` is a single entry point with ~10 keys today and ~30
> keys planned. Authors navigate the surface by the TOPICAL axis baked
> into each key's local name — every knob's prefix names the cluster
> it belongs to.**

A reader audit (`ai/findings/2026-05-20-tools-xray-api-review.md`
Finding #4 → `rf2-cthfn` audit-of-audits #16) flagged the risk that a
single `configure!` accepting a growing key set becomes a navigation
hazard: 10 keys is fine, 30 is not, and the bare name `configure!`
gives readers no map of what lives where.

**Pick (rf2-dz35f · option b · Mike-confirmed).** Keep the single
`configure!` entry point. Document the key-naming axis loudly so
authors can navigate by namespaced keywords. The split into multiple
entry points (`configure-editor!`, `configure-launch!`, …) is
mechanical if surface bloat ever forces it; until then, one entry
point + a documented axis is the lower-overhead shape (per the
ownership rule locked by `rf2-g2a5v`, splitting would invalidate
`defaults < configure! < persisted Settings` — wait for evidence of
pain).

### How keys are organised

Every `configure!` key carries a **topical prefix** in its local name
identifying the cluster it belongs to. The convention is:

```
:rf.<reservation>/<cluster>-<knob>
```

Where:

- `<reservation>` — the reserved namespace owning the key (`xray`
  for Xray's knobs). Each re-frame2 tool reserves its own
  `:rf.<tool>/*` segment; there is no cross-tool shared reservation
  for the on-box privacy gate (the egress profile is per-`(tool,
  frame)` — Xray's `:rf.xray/egress-profile`, Story's
  `:rf.story/egress-profile`).
- `<cluster>` — the topical cluster (editor, launch, keybinding,
  render, trace, …). New related keys join the cluster by sharing
  the prefix.
- `<knob>` — the specific dial within the cluster
  (`-storage-key`, `-enabled?`, `-auto-hide-events`, …).

The flat hyphenated shape (`:rf.xray/filters-storage-key`,
`:rf.xray/keybinding-enabled?`) is the canonical v1 form. Reading
`rg ':rf.xray/<cluster>'` enumerates every knob in a cluster; IDE
completion against `:rf.xray/<cluster>-` reveals the dials without
reading this doc.

> **Evolution path (not v1).** If a cluster grows past comfort
> (~5 keys), it MAY graduate to its own sub-namespace
> (`:rf.xray.<cluster>/<knob>`) — `:rf.xray.kb/enabled?`,
> `:rf.xray.kb/bindings`, etc. The graduation is mechanical
> (rename + forward-compat alias for one minor release) and is
> RESERVED for the long-tail vision; v1 ships flat.

### Cluster catalogue

The table below maps every `configure!` cluster to its anchor section
in this doc. Authors looking for a specific knob: scan the cluster
column, jump to the linked section, find the knob.

| Cluster | Reserved namespace | v1 keys | Future keys (vision) | Anchor |
|---|---|---|---|---|
| **Editor / source-coord** | `:rf.xray/` | `:rf.xray/editor`, `:rf.xray/project-root` | — | [`§:rf.xray/editor`](#rfxrayeditor) + [`§:rf.xray/project-root`](#rfxrayproject-root) |
| **Egress profile (privacy gate)** | `:rf.xray/` | `:rf.xray/egress-profile` | — | [`§:rf.xray/egress-profile`](#rfxrayegress-profile) |
| **Layout host** | `:rf.xray/` | `:rf.xray/layout-host-selector` | — | [`§:rf.xray/layout-host-selector`](#rfxraylayout-host-selector) |
| **Launch** | `:rf.xray/` | `:rf.xray/auto-open?` | `:rf.xray/launch-restore-visibility?`, `:rf.xray/launch-popout-geometry` | [`§:rf.xray/auto-open?`](#rfxrayauto-open) + [Vision §Should-adds](#vision--full-configure-key-inventory-30-keys) |
| **Keybinding** | `:rf.xray/` | `:rf.xray/keybinding-enabled?` | `:rf.xray/keybinding-handle-keys?`, `:rf.xray/keybinding-bindings` | [`§:rf.xray/keybinding-enabled?`](#rfxraykeybinding-enabled) |
| **Settings popup (bulk-set)** | `:rf.xray/` | `:rf.xray/settings` (carries `:theme`, `:density`, `:buffer`, `:diff`, …) | — | [`§:rf.xray/settings`](#rfxraysettings) |
| **Filters** | `:rf.xray/` | `:rf.xray/filters`, `:rf.xray/filters-storage-key` | `:rf.xray/filters-auto-hide-events`, `:rf.xray/filters-auto-hide-event-ns`, `:rf.xray/filters-auto-hide-error-overrides?` | [`§:rf.xray/filters`](#rfxrayfilters) + [`§:rf.xray/filters-storage-key`](#rfxrayfilters-storage-key) |
| **Buffer depths** | `:rf.xray/` | (via `:rf.xray/settings` `:buffer` slot) | `:rf.xray/buffer-retained-epochs` (process-global escape hatch) | [Vision §Must-haves](#vision--full-configure-key-inventory-30-keys) |
| **Render / inspector** | `:rf.xray/` | — | `:rf.xray/render-ns-aliases`, `:rf.xray/render-alias-namespaces?`, `:rf.xray/render-auto-expand-below`, `:rf.xray/render-uuids-as` | [Vision §Should-adds](#vision--full-configure-key-inventory-30-keys) |
| **Trace collection** | `:rf.xray/` | — | `:rf.xray/trace-collect-when`, `:rf.xray/trace-fatten?` | [Vision §Should-adds](#vision--full-configure-key-inventory-30-keys) |
| **Logging (self-debug)** | `:rf.xray/` | — | `:rf.xray/logging-debug?` | [Vision §Nice-to-haves](#vision--full-configure-key-inventory-30-keys) |

The cluster table is the canonical navigation aid; the §Configuration
keys section below carries the normative semantics. The
§[Vision](#vision--full-configure-key-inventory-30-keys) section
catalogues the future keys not yet shipped.

**For authors of new keys.** Adding a knob? Pick its cluster first
(reuse an existing prefix when the dial belongs to an established
topic; mint a new prefix only when the knob opens a new axis), then
the local name (`-<knob>` suffix within the cluster prefix). Update
this table in the same PR — the cluster catalogue is the contract
authors navigate by, not the per-knob anchor docs.

## Entry point

```clojure
(require '[day8.re-frame2-xray.config :as xray-config])

(xray-config/configure!
  {:rf.xray/editor                :cursor
   :rf.xray/project-root          "C:/Users/me/code/my-app"
   :rf.xray/layout-host-selector  "[data-rf-xray-host]"
   :rf.xray/auto-open?            true
   :rf.xray/keybinding-enabled?   true
   :rf.xray/egress-profile        :rf.egress/local-redacted})
```

`configure!` MUST accept a map and MUST return `nil`. Keys not listed
below MUST be silently ignored (forward-compat: future Xray releases
will grow keys; older hosts passing newer keys MUST not break, and
newer hosts passing older-Xray-unaware keys MUST not break). Absent
keys MUST leave the corresponding atom untouched — `configure!` is
**additive**, not replacing-the-whole-config; calling it twice with
disjoint key sets composes.

Hosts SHOULD call `configure!` exactly once at boot, before the Xray
preload mounts. Calling it after mount is legal — every key is read at
its consumer's hot path on each use, so changes take effect on the
next read — but defeats the "boot-time configuration" mental model and
is reserved for hot-reload / live-rebind scenarios (Settings panel,
dev REPL).

## Configuration keys

### `:rf.xray/editor`

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
| `{:custom "<tpl>"}` | user template | Template containing `{path}` / `{file}` / `{line}` / `{column}` placeholders. Substituted at click time. The escape hatch for editors Xray does not know natively. |
| `nil` | (resets to `:vscode`) | Explicit reset to default. |

Default: `:vscode`.

Unknown editor keywords MUST fall back to `:vscode` so a typo still
yields a clickable URI rather than a no-op. Source-coords without a
`:file` MUST hide the click chip entirely. The canonical URI builder
lives at `re-frame.source-coords.editor-uri` (core artefact, CLJC);
Xray's open-in-editor chip consumes it via
`day8.re-frame2-xray.config/editor-uri`.

The full set of URI-construction rules — default-editor behaviour
when unset, line/column defaults, no-URL-encoding posture, the
no-handler-installed clean-no-op fallback, the `{:custom …}`
substitution contract — is normatively specified in
[`007-UX-IA.md` §URI construction](./007-UX-IA.md#uri-construction-normative).
The matrix here enumerates the keywords; that section binds them
into MUSTs.

Xray's `:editor` is **independent** of Story's `:rf.story/editor`
(per [`spec/007-Stories.md`](../../../spec/007-Stories.md)). Hosts
running both tools MAY route each to a different editor — e.g.
`:vscode` for the application code Xray points at, `:idea` for the
Story test corpus.

#### End-user override (rf2-dudqz)

`:rf.xray/editor` is the **project-wide default** set by the host
app's boot. Individual operators on a mixed-editor team MAY override
it for their machine via the **Settings popup → General tab →
"Click-to-source links open in" picker** ([`007-UX-IA.md` §End-user
override](./007-UX-IA.md#end-user-override-rf2-dudqz)).

The override lives in the persisted-settings map at
`[:general :editor-override]`; it accepts the same value shape as
`:rf.xray/editor` (`nil` / enumerated keyword / `{:custom <tpl>}`).
`config/get-editor` returns the FIRST non-nil tier of
`[end-user-override → host default → :vscode]`. The override is
purely client-side — it does NOT mutate the host's atom and does NOT
reach other browsers / tabs / users.

Tests cover the resolution order, the localStorage round-trip, and
the `open-chip` / `:rf.xray/open-in-editor` consumers under
`tools/xray/test/day8/re_frame2_xray/settings/editor_override_cljs_test.cljs`.

#### Unconfigured-host DX hint (rf2-4s08ov)

A host that wires only the bare preload never sets `:rf.xray/editor`,
so the open-in-editor chip targets the framework default `:vscode`.
The URI resolves and `Location.assign` fires — but if VS Code is not
the developer's editor, the OS has no `vscode:` protocol handler and
the click is a **silent no-op** the JS layer cannot observe (the
no-handler-installed clean-no-op fallback in
[`007-UX-IA.md` §URI construction](./007-UX-IA.md#uri-construction-normative)).
rf2-ffijtp documented the fix (hosts MUST set `:rf.xray/editor`);
rf2-4s08ov surfaces it at click-time.

When `config/editor-configured?` is false — NEITHER the host
explicitly set `:rf.xray/editor` NOR a valid operator override sits in
`[:general :editor-override]` — BOTH open-in-editor surfaces MUST NOT
fire the silent `:rf.editor/open` navigation:

- The panel-side `:rf.xray/open-in-editor` event-fx dispatches
  `:rf.xray/editor-hint-show` instead.
- The in-DOM `open-chip` (`<a>`) routes its `:on-click` through
  `open-in-editor/chip-click!` (rf2-r4q6y3), which applies the SAME
  decision: when an editor is configured it navigates via `open!`;
  when unconfigured AND a live `:rf/xray` shell frame is present it
  dispatches `[:rf.xray/editor-hint-show]` on that frame; when
  unconfigured with NO `:rf/xray` frame (a standalone / static host
  with no shell where a hint toast cannot mount) it falls back to the
  best-effort `open!` — the documented standalone contract.

The hint mounts a small, non-intrusive bottom-corner toast ("No editor
configured") carrying an **Open Settings** button that lands the
operator on the General-tab editor picker
(`:rf.xray/editor-hint-open-settings` → `:rf.xray/settings-open`). The
toast is dismissable (✕ / **Esc**) and self-dismisses on Open-Settings.
Because the toast is a non-modal `role=status` surface that MUST NOT
trap focus (it would steal it from the host app), the **reachable Esc
path is the shell-level global keydown listener**
(`keybinding/handle-keydown`, rf2-wpvy6f): it dismisses the hint
whenever it is open and falls through (does not consume Esc) whenever
it is closed, so other Esc consumers and the host are undisturbed. The
toast's own in-DOM `on-key-down` is retained as defense-in-depth for
the case where focus does land inside the toast.

`editor-configured?` is true the moment EITHER the host calls
`set-editor!` / `(configure! {:rf.xray/editor …})` (an explicit set —
even of `:vscode` — counts as confirmation) OR a valid operator
override is present. In that state the chip click resolves + navigates
exactly as before; the hint never fires. A malformed override
(rejected by `valid-editor-override?`) does NOT count as configured —
it degrades to the unconfigured state like `get-editor` does.
`(configure! {:rf.xray/editor nil})` RESETS the configured state to the
framework default — the bulk surface gates on key PRESENCE so it is
equivalent to `set-editor! nil`; an ABSENT `:rf.xray/editor` key leaves
the preference untouched (rf2-eilutf).

Tests cover the predicate + `configure!` nil-reset / absent-key
equivalence (`config_test.clj`), the event-fx routing + the direct
`chip-click!` decision (`open_in_editor_cljs_test.cljs`), the toast
events / sub / render + Open-Settings wiring
(`settings/editor_hint_cljs_test.cljs`), and the shell-level Esc
dismissal (`keybinding_cljs_test.cljs`).

### `:rf.xray/project-root`

The on-disk root prepended to the source-coord's classpath-relative
`:file` slot before the editor URI ships (rf2-5m5n2). Source-coords
stamped at registration time are classpath-relative (form-meta `:file`
slot, e.g. `"app/cart/handlers.cljs"`); editor URI handlers
(`vscode://file/<path>...`, `cursor://...`, `idea://...`, etc.) resolve
`<path>` against the filesystem. A relative path fails with "Path does
not exist", so Xray's Open chip and the `:rf.editor/open` reg-fx need
to know the on-disk root to prepend before the URI ships.

| Value | Meaning |
|---|---|
| String | The on-disk root (typically the directory above the classpath source-paths). Joined to source-coord `:file` via `/`. Threaded into the URI by `re-frame.source-coords.editor-uri/editor-uri` via its 3-arg form. |
| `nil` | Default. Source-coord file ships verbatim — Open chip behaves as it did pre-rf2-5m5n2 (useful for hosts whose source-paths are already absolute, and for tests). |

Default: `nil`.

Blank strings MUST normalise to `nil`. Xray's `:project-root` is
**independent** of Story's (an app-source root for Xray, a stories
root for Story); two atoms, two `configure!` surfaces.

#### Open-in-editor launch modes (rf2-wn3bh — Option B dev-server endpoint)

Jump-to-source has TWO launch paths; the chip + the `:rf.editor/open`
reg-fx PREFER the first and FALL BACK to the second. The mechanism is
additive — B never removes the URI path. (See also
[`spec/Tool-Pair.md` §Open-in-editor launch modes](../../../spec/Tool-Pair.md).)

1. **Dev-server endpoint (preferred).** The JS-ecosystem standard
   (Vite `/__open-in-editor`, react-dev-utils, Next). A shadow-cljs
   `:dev-http` Ring `:handler` —
   `re-frame.testbed.open-in-editor-server/handler`, a **JVM-only `.clj`**
   server fn — answers
   `POST /__rf-open-in-editor?file=<…>&line=<n>&column=<c>` (with
   `OPTIONS` for the CORS preflight). The endpoint is **POST-only +
   loopback-guarded by design** — it launches the developer's editor on
   a local path, so a `GET`/`HEAD` drive-by must never trigger a launch
   (rejected 405; the historic Vite / react-dev-utils CVE class). It
   resolves the (classpath-relative) `:file` against the live
   source-paths **at runtime on the dev machine** (the same context-
   class-loader `getResource` resolution as
   `re-frame.source-coords/absolutise-file`, rf2-wvsxg, but at request
   time so it works for the JAR / in-jar / odd-classpath cases the
   compile-time bake cannot reach), then launches the editor via the
   `launch-editor` npm package (a dev-only dependency; handles every OS
   + editor, superseding the per-editor `editor://` scheme table). The
   client open-seam (`open-in-editor/open-coord!` → the shared
   `re-frame.source-coords.open-endpoint/open-coord!`) `fetch`es the
   endpoint on its own origin; the configured editor keyword rides as
   the `editor=` query param and maps to a `launch-editor` command hint.
   Zero-config jump-to-source for EVERYONE — **no `:project-root`
   needed, no absolute path baked into the bundle.**

2. **`editor://` URI (fallback).** When no dev server answers (static
   export, non-shadow host, production-mode inspection, network error,
   or a non-2xx endpoint reply), `open-coord!` falls back to the
   historic path: build an `editor://file/<abs-path>:<line>:<column>`
   URI (via `re-frame.source-coords.editor-uri`, with `:project-root`
   prepended per the table above + rf2-wvsxg absolutisation) and hand it
   to `Location.assign` through the `navigator` seam. This is the only
   path `:project-root` participates in — it remains the fallback knob
   for hosts running without a re-frame2 dev server.

**Bundle isolation.** The server handler is a `.clj` — never part of any
CLJS/browser build, so it cannot leak into a production bundle. The
client seam (`re-frame.source-coords.open-endpoint`) is referenced only
from the dev-only tool open-seams and DCEs out of release bundles like
the seams themselves. `launch-editor` is a `devDependency`.

### `:rf.xray/egress-profile`

Xray's on-box dev-UI egress PROFILE — the privacy gate for
`:sensitive? true` trace-event DISPLAY per
[Spec 009 §Privacy](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)
(resolved by `rf2-a32kd`) and
[Spec 015 §Cross-tool visibility grain](../../../spec/015-Data-Classification.md#cross-tool-visibility-grain).
EP-0015 issue 7 rules on-box visibility **per `(tool, frame)` pair**:
there is **no single process-global `show-sensitive?` user toggle**, and
no cross-tool shared atom. The predecessor process-global
`:rf.privacy/show-sensitive?` boolean (`rf2-azls9`) is **retired** and
folded onto this per-tool named-boundary model — matching the Story
migration and the per-`(tool, frame)` `local-render` seam Xray already
ships. Each tool owns its own profile knob (`:rf.xray/egress-profile`;
Story's is `:rf.story/egress-profile`).

The value is a member of the closed `:rf.egress/*` enum
(`re-frame.projection/profiles`). For Xray's on-box dev surface the
relevant pair is:

| Value | Meaning |
|---|---|
| `:rf.egress/local-redacted` | Default (fail-closed). Xray's trace collector MUST drop events whose top-level `:sensitive?` field is `true` before any buffer push, and MUST bump the suppressed-events counter (see [§App-db slots](#app-db-slots) below) so the shell's bottom rail can surface a `[● REDACTED N]` indicator. |
| `:rf.egress/local-raw` | The trusted-local operator opt-in. The collector receives every event unchanged; `:sensitive? true` events flow through to every consumer. Includes large values too (the profile's `:rf.size/include-large?`). |
| `nil` | Resets to the default (`:rf.egress/local-redacted`). |
| unknown | `configure!` rejects with `:rf.error/unknown-egress-profile` (the enum is closed). |

The "is a `:sensitive?` event suppressed?" decision derives from the
profile's `:rf.size/include-sensitive?` resolution via the framework
projection table (`projection/profile-size-opts`), the SAME table
`project-egress` consumes — one source of truth, no re-implemented
redaction policy. The profile MUST be read at the head of the collector
body on every event so changing it via `configure!` takes effect on the
next trace event without re-registering the listener (per
[`013-Trace-Consumer.md`](./013-Trace-Consumer.md) §Privacy gate).

**Reveal is an auditable operator act** (Spec 015 §Cross-tool grain):
widening to `:rf.egress/local-raw` emits a `:rf.xray/egress-reveal` trace
op so the reveal is trace-visible rather than a silent local flip.
Narrowing back to `:rf.egress/local-redacted` triggers the retroactive
scrub (the reveal is NOT a one-way trapdoor — see
[`013-Trace-Consumer.md`](./013-Trace-Consumer.md) §Retroactive-scrub),
clearing every buffered event so privacy is fully restored. Going the
other way (widening) is one-way lossy in the sense that events dropped
under the redacting default are already gone — hosts debugging a
redaction policy widen the profile and re-drive the runtime to see the
raw cascade.

### `:rf.xray/layout-host-selector`

The CSS selector Xray uses for its default true-inline shell mount.
The host app owns the normal-flow right-side layout host; Xray renders
inside it after substrate readiness.

| Value | Meaning |
|---|---|
| CSS selector string | Use this selector when finding the app-provided Xray host. |
| `nil` | Reset to the default selector. |

Default: `[data-rf-xray-host]`.

If the selector cannot be found when the default launch path opens,
Xray MUST emit the actionable missing-host diagnostic described in
[`011-Launch-Modes.md`](./011-Launch-Modes.md) §Layout host contract.

### `:rf.xray/auto-open?`

Controls only the preload's default launch attempt. It does not disable
Xray, the trace/epoch collectors, browser API exports, keybinding, or
explicit `open!` / `toggle!` calls.

| Value | Meaning |
|---|---|
| `true` | Default. After `rf/init!` installs a substrate adapter, the preload opens the Xray shell in the configured true-inline host. |
| `false` | Suppress only the automatic page-load open. Use this for tool-owned Story/static canvases that intentionally do not reserve app real estate for Xray. |
| `nil` | Reset to default (`true`). |

Hosts that set this to `false` SHOULD do so before `rf/init!`, so the
preload's adapter-ready probe sees the final launch posture before it
would otherwise diagnose a missing host. The missing-host diagnostic is
unchanged for the default path and for explicit opens.

### `:rf.xray/keybinding-enabled?`

Controls whether `keybinding/attach!` installs Xray's global,
capture-phase `keydown` listener. The listener handles Xray's
spec-published shortcuts: `Ctrl+Shift+C` (shell toggle), `Cmd/Ctrl+K`
(command palette), and the unmodified spine bindings
(`Space` / `L` / `j` / `k` / `G` / `c` / `Esc`). It calls
`stopPropagation()` for the keys it consumes so host bindings further
down the propagation path don't double-fire.

| Value | Meaning |
|---|---|
| `true` | Default. `keybinding/attach!` installs the listener; the standalone Xray shell behaves exactly as it did pre-rf2-4eyik. |
| `false` | Suppress installation entirely. `keybinding/attach!` short-circuits to a no-op; the sentinel does not flip; no listener lands on `js/document`. |
| `nil` | Reset to default (`true`). |

Hosts MUST set this BEFORE the Xray preload runs (the preload calls
`keybinding/attach!` at adapter-ready time). Setting it afterwards is
a no-op on the already-attached listener unless the host explicitly
calls `keybinding/detach!` (see below).

The slot exists for embed hosts (per
[`008-Embedding-Contract.md`](./008-Embedding-Contract.md) — Story
mounts Xray as its right-hand-side panel) whose own global
keybindings collide with Xray's. Story's command-palette
(`Cmd/Ctrl+K`) is the canonical collision: without the toggle, Xray's
capture-phase listener consumes the keypress before Story's handler
fires. Per rf2-4eyik (rf2-q7who Thread A) — the embed-contract gap
discovered via rf2-drprn.

#### `keybinding/detach!` — public escape hatch (rf2-ycrt2)

`day8.re-frame2-xray.keybinding/detach!` is the public companion to
`attach!` for embed hosts whose mount lifecycle runs AFTER Xray's
preload. The contract:

```clojure
(require '[day8.re-frame2-xray.keybinding :as xray-keybinding])

(xray-keybinding/detach!)
```

- **No arguments**; returns `nil`.
- **Idempotent**. Calling it when nothing is attached is a no-op (the
  internal sentinel does not underflow); calling it twice in a row is
  safe.
- **Symmetric with `attach!`**. The pair `(attach!) → (detach!) →
  (attach!)` flips between attached / not-attached cleanly without
  leaking listeners.
- **Safe in any host**. Guarded on `(exists? js/document)`.

When to call: embed hosts that flip `:rf.xray/keybinding-enabled?` to
`false` from a **mount-time** hook (not boot-time) must follow the
slot flip with `detach!`. The slot alone is read only at attach time;
without `detach!` the listener Xray's preload installed under the
default-true posture stays on `js/document` and continues consuming
keypresses despite the intent declaration. Per rf2-ycrt2 (rf2-q7who.1
runtime follow-on). Story's `ensure-xray-mounted!` is the canonical
example: it calls `disable-keybinding!` (slot flip) then
`detach-keybinding!` (runtime removal) on every variant-selection
edge.

Boot-time hosts (those that call
`configure! {:rf.xray/keybinding-enabled? false}` BEFORE Xray's
preload runs) do NOT need to call `detach!` —
their slot flip lands before `attach!` reads it, the short-circuit
fires, and no listener is ever installed. `detach!` exists for the
mount-time lifecycle the slot's attach-time-only read cannot cover
alone.

### `:rf.xray/settings`

Bulk-replace the Settings popup state map (rf2-9poxq; expanded by
rf2-ttnst — Mike 2026-05-19 §0ter.4 walkthrough). Shape mirrors the
`default-settings` block in `config.cljc`:

```clojure
{:general   {:text-size              13          ; px; slider range 10–18
             :panel-position         :right-rail ; :right-rail | :fullscreen (rf2-czcg5 dropped :popout — pop-out launches from the chrome ⛶ button)
             :panel-width-px         480         ; number; clamped [320, 0.9 × viewport-width-px]
             :auto-open-on-error?    false
             :density                :cosy       ; #{:cosy :compact} — no :comfy in v1
             :show-tool-frames?      false       ; reveal :rf/xray + :rf/pair2 in L1 picker
             :long-keyword-threshold 24}         ; chars; long-keyword elision threshold
 :theme     :dark                                ; :dark | :light
 :diff      {:highlight-fn-ref-changes? false}   ; opt-in fn-ref classification
 :buffer    {:events-retained 50}}                ; per-frame trace-ring event count
```

The `:general` slot carries three knobs introduced by rf2-ttnst:

- `:density` — `:cosy` (default) or `:compact`. Drives the Views
  detail rows + App-db diff rows vertical rhythm. The `:comfy` tier
  catalogued earlier in spec/007-UX-IA.md §Density slider is dropped
  in v1; persisted `:comfy` values from prior schemas are treated as
  `:cosy` by the `:rf.xray/density` convenience sub.
- `:show-tool-frames?` — boolean. When `true` the L1 frame-picker
  dropdown reveals `:rf/xray` + `:rf/pair2`. Default `false` per
  spec/007-UX-IA.md §Frame-observation isolation invariants §I1.
- `:long-keyword-threshold` — integer (chars). Fully-qualified
  keywords longer than the threshold elide in compact list cells.
  Default `24`, was previously a fixed constant; now user-tuneable
  per spec/007-UX-IA.md §Long-keyword treatment.

The `:buffer` slot carries the buffer-depth tunable surfaced in the
Buffer tab:

- `:events-retained` — count of events retained in each frame's
  trace ring (one slot per event / pipeline run). Mirrors
  `re-frame.trace.tooling/default-events-retained` (`50`). Writes
  through to the runtime ring via `(rf/configure! {:trace-buffer
  {:events-retained N}})` — `settings/effects.cljs
  §apply-events-retained!` resizes the live ring and `apply-all!`
  replays the persisted value on boot (rf2-5u03ig). Renamed from
  `:trace-buffer/keep` at rf2-43koh when Xray's separate ring was
  retired in favour of the framework's per-frame event-keyed rings
  (per the rf2-3g9nw D1=a ruling). No back-compat alias — pre-alpha
  posture.

The earlier `:buffer/retained-epochs` input was removed (rf2-pu9sb —
no runtime consumer; the per-frame epoch ring is sized by `:general
:epoch-history`), and the inert
`:buffer/app-db/inspector-collapse-threshold` input was removed
(rf2-5u03ig — no runtime consumer; the App-db inspector already
auto-collapses on depth/width).

The Buffer tab also exposes a destructive "Clear buffer now" action
that fires `trace-collector/retroactive-scrub!` after a confirmation
modal (`"Clear buffer? This deletes all retained epochs."` → Cancel
/ Clear). The action drops the framework's per-frame rings + Xray's
frameless secondary ring + the redaction counter in one wholesale
clear. The action is dispatch-only and carries no `configure!`
counterpart; hosts that need a programmatic clear call the
`trace-collector` helper directly.

The `:panel-width-px` slot (rf2-x8h9y) drives the
`:right-rail` panel's horizontal width. The Xray drag handle (per
[`007-UX-IA.md` §Resize affordance](./007-UX-IA.md#resize-affordance))
writes through to this slot on drag-end; the slot persists via the
existing `re-frame2.xray.settings.v1` localStorage key so width survives
reloads. Default `480`. Ignored in `:popout` (window owns size) and
`:fullscreen` (viewport owns size) positions.

> Note (rf2-jh9ws): a `:telemetry` slot shipped briefly with the
> initial popup landing (rf2-9poxq) but was removed — Xray
> transmits no telemetry. Legacy `:telemetry` keys in persisted
> payloads or in `(configure! {:rf.xray/settings ...})` calls are
> silently dropped by the per-section merge.

| Value | Meaning |
|---|---|
| Map | Deep-merge over `default-settings`, section by section AND recursively within each section (rf2-8j3gyt — a partial nested override, e.g. `{:general {:event-list-col-widths {:source 100}}}`, keeps its untouched sibling keys at their default rather than dropping them). Seeds the live settings map immediately. Persists to the localStorage key `re-frame2.xray.settings.v1` ONLY when that slot is still empty (a genuinely fresh install) — see the merge-order reconciliation below (rf2-rr2yw3). |
| (absent) | Leave the live settings map untouched. |

The popup's per-knob event surface (`:rf.xray/settings-update`) is
the normal write path; this key is the bulk-set escape hatch for
hosts that want to ship their own factory defaults (corporate fork
with light theme, embedded host that prefers `:fullscreen` panel
position, etc.).

> **Merge-order reconciliation (rf2-rr2yw3).** An earlier revision of
> this section said `:rf.xray/settings` "persists immediately … so the
> next page load reads the host-supplied posture" unconditionally —
> which, for a host that calls `configure!` on every boot (the
> documented pattern), silently overwrote a user's already-persisted
> Settings-popup mutations on the very next reload, contradicting
> the "`configure!` vs `init!` vs persisted Settings — ownership rule"
> section further below in this same document, whose `hardcoded
> defaults < configure! overrides < persisted Settings overrides`
> order is authoritative. The rule here is now singular: `configure!`
> ALWAYS seeds the live map (so a host's posture is visible even with
> no storage-backed load ever running — tests, harnesses), but only
> PERSISTS it when localStorage is still empty. `load-settings-from-
> storage!` (run later, per the documented boot order) deep-merges
> whatever IS in localStorage over `default-settings` seeded with the
> `configure!` map, so a returning user's persisted values always win
> for the keys they've touched, while a fresh key the host newly
> configures still lands for everyone else.

Default-defining shape, per-knob rationale and the localStorage key
are normatively documented in
[`016-Auxiliary-Panels.md`](./016-Auxiliary-Panels.md) §Settings popup
— v1 ships. The `:rf.xray/editor` / `:rf.xray/project-root` /
`:rf.xray/auto-open?` / `:rf.xray/egress-profile` keys above
remain process-global atoms distinct from `:rf.xray/settings` (their
semantics predate the popup; the popup-managed surface is the
`{:rf.xray/settings <map>}` shape).

### `:rf.xray/filters`

Host-supplied seed pill set the registry hydrates `:active-filters`
with on **first install** (when localStorage is empty). Per
[`018-Event-Spine.md`](./018-Event-Spine.md) §7 'Empty defaults',
Xray ships with no filters by default (first-session honesty beats
first-session quietness). The seed is the escape hatch for hosts
that have a reason to ship a starting posture — typically Story
testbeds that need a known starting point for reproducibility.

| Value | Meaning |
|---|---|
| `{:in [{:pattern <…>} …] :out [{:pattern <…>} …]}` | Seed the slot on first install only. The seed never clobbers a user's hand-tuned set — once localStorage carries any pill, the seed is ignored. |
| `nil` (default) | No seed; registry defaults to `{:in [] :out []}`. |

> **Transient vs durable (rf2-swclw).** The IN/OUT pills, the
> muted-event-id set, and the frame view-scope are **transient
> exploration filters**: they persist via localStorage *within* a session
> but RESET on every load — `mount.cljs/::reset-transient-filters` does
> not hydrate them and clears the stored value so a stale filter can never
> silently hide rows on reload (rf2-jvghz; an inspector must show the
> truth). Only **durable view prefs** (the persisted Settings shape below
> — mode, density, panel layout) hydrate on boot. The `:rf.xray/filters`
> seed therefore lands only on a genuinely-empty first install, before the
> reset hook has a stored value to clear.

### `:rf.xray/filters-storage-key`

The localStorage key the filter persistence layer reads / writes.

| Value | Meaning |
|---|---|
| String | Use this key for round-trip. Hosts that run multiple Xray instances in the same browser session (e.g. Story testbeds) override so each instance keeps its own pill state. |
| `nil` | Reset to default. |

Default: `"re-frame2.xray.filters.v1"` (versioned so future schema
changes can ignore stale payloads).

When both `:rf.xray/filters` and `:rf.xray/filters-storage-key`
are passed in one call, the storage key is set BEFORE the seed so a
host that overrides both gets the seed persisted under the right
key.

### Static mode availability

Static mode is unconditionally available. The mode **dropdown** mounts at
chrome-ribbon-left (`data-testid="rf-xray-mode-pill"` — the testid keeps
its historical name; the widget is now a compact `<select>` per
rf2-4vp5j), `Cmd-Shift-M` / `Ctrl-Shift-M` toggles between Dynamic and
Static surfaces via `:rf.xray/toggle-mode`, and the active mode hydrates
from `xray.mode` localStorage on boot (with `"dynamic"` fallback — mode
is a DURABLE view pref, so unlike the transient filters above it does
persist across loads).

Per rf2-8l3uk the prior `:rf.xray/static-mode?` configure key was
removed (pre-alpha posture — back-compat shims are out of scope; if
Static mode is useful, expose it unconditionally).

**Persistence.** The mode selection persists under the localStorage
key `xray.mode` as a bare string (`"dynamic"` / `"static"`). The
persistence fx is `:rf.xray.static/persist-mode` (per
[`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md) §Static
mode).

Cross-reference: [`007-UX-IA.md`](./007-UX-IA.md) §Static mode
(visual-language treatment of the mode pill, edge stripe, motion
dampening, chrome silhouette) +
[`018-Event-Spine.md`](./018-Event-Spine.md) §Static surface (the
architectural contract — 3-layer silhouette, 4-signal mode-recognition
mechanism, mode-state lifecycle).

## App-db slots

`configure!` is the host-visible surface; under the hood, Xray
mirrors privacy-gate state into its own `:rf/xray` app-db so the
reactive sub-graph drives UI updates immediately (per
[`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md) §Shared
infrastructure and bead `rf2-0vxdn`). Two slots are normatively
specified:

### `[:suppressed-counters {<frame-id> <count>}]`

A `frame-id → count` map, where each value is the number of
`:sensitive? true` trace events the collector dropped for that frame
under the current local-render egress profile
(`:rf.xray/egress-profile`). Events without a
frame scope (registration-time emits, outermost-dispatch lookup
failures) MUST count under the `:global` bucket so a count is never
lost.

The slot is updated by the `:rf.xray/note-sensitive-suppressed` event
(per [`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md)
§Shared infrastructure) dispatched from the trace collector. It is
cleared by `:rf.xray/reset-suppressed-counters` — either entirely
(no-arg) or per-bucket — fired from `trace-collector/retroactive-scrub!`
and test fixtures.

The `:rf.xray/suppressed-sensitive-count` subscription reads this
slot and returns the total across every bucket; the
`[● REDACTED N]` bottom-rail indicator binds to that sub so the count
updates IMMEDIATELY on every collector bump, with no dependency on
sibling subs recomputing (rf2-0vxdn PR #681).

The slot's source-of-truth duality is deliberate: the underlying atom
in `day8.re-frame2-xray.config/suppressed-counters` remains the
JVM-runnable data primitive (so CLJC unit tests can assert it without
spinning up a CLJS runtime and a frame), and the dispatch into
`:rf/xray` is the reactive surface for CLJS. Both stay in lockstep —
every atom bump fires a matching dispatch; every dispatch comes from
an atom bump.

### Xray-owned `:rf/xray` frame

Every other piece of Xray state — selected dispatch-id, selected
panel, pin store, target-frame, etc. — lives under the `:rf/xray`
frame's app-db per [`008-Embedding-Contract.md`](./008-Embedding-Contract.md)
§State isolation (Option-C frame-provider). Those slots are owned by
the panels that drive them; this doc enumerates only the
configuration-derived slot (`:suppressed-counters`) because it is the
visible bridge between `configure!` and the reactive surface.

**Frame is registered trace-disabled (rf2-2qaqh).** Xray registers its
own frame with the framework's `:rf.trace/frame-no-emit?` frame-config:

```clojure
(rf/reg-frame :rf/xray {:rf.trace/frame-no-emit? true})   ;; mount.cljs/ensure-xray-frame!
```

This marks `:rf/xray` a tool / inspector frame: the framework's `emit!`
/ `emit-error!` short-circuit for any trace event tagged with that frame,
so Xray's own UI reactivity (`:rf.sub/run` + `:rf.view/render` on every
panel render) emits NO trace and never floods the shared ring it
inspects. It is the frame-scoped sibling of the handler-scoped
`:rf.trace/no-emit?`; honoured on every `reg-frame` (re-)registration so
the gate survives hot-reload. This config is NOT a `configure!` key — it
is a framework `reg-frame` option Xray sets internally. See
[framework API §`:rf.trace/frame-no-emit?`](../../../spec/API.md) +
[`013-Trace-Consumer.md` §Tool-frame trace gate](./013-Trace-Consumer.md). (Xray
adds a second, ingest-side belt-and-braces drop for the residual cases
the frame gate misses — see 013.)

## `configure!` vs `init!` vs persisted Settings — ownership rule (rf2-g2a5v)

> **`configure!` is for STATIC config given at boot. `init!` is the
> LIFECYCLE hook. Persisted Settings are USER-MUTABLE overrides
> loaded at boot. The three surfaces compose in a fixed merge order.**

Xray carries three parallel host-facing config surfaces. A reader
audit (`ai/findings/2026-05-20-tools-xray-api-review.md` Finding #2)
flagged that `:theme`, `:density`, and `:target-frame` (renamed from `:default-frame`, EP-0002 rf2-bd4div) overlapped
across all three with no documented disambiguation rule. The
ownership rule below locks the contract for pre-alpha and forward.

**Per-surface role.**

| Surface | Role | Mutability | Lifetime |
|---|---|---|---|
| `(xray-config/configure! {…})` | Static boot config — defaults, feature flags, host-environment wiring (editor target, project root, layout-host selector, auto-open, keybinding enabled, filter seed, …). | Host-code-mutable at boot; immutable from the user's perspective. | Process-global atoms; one set of values per host load. |
| `(xray/init! opts)` | Lifecycle hook — called by host app code to bring Xray up (alternative to `:preloads`). The `opts` map carries per-instance panel-state wiring (Settings shape inputs such as `:theme`, `:density`, `:target-frame` (EP-0002 rf2-bd4div), `:buffer-depths`). Per rf2-2thl2 each accepted key is wired end-to-end; aspirational Settings-shape keys without backing infrastructure (`:ai-provider`, `:sidebar-mode`, `:launcher-pill`, `:keybindings`) land via the persisted Settings shape only — `init!` does not accept them on the opts map until the wiring catches up. Idempotent. | Host-code-driven; once-per-load. | Per-mount lifecycle. |
| Persisted Settings (`localStorage` slot `day8.re-frame2-xray/settings/v1`) | User-mutable overrides — the Settings popup is the canonical UI; persists `:theme`, `:density`, `:ai-provider`, `:buffer-depths`, `:target-frame` (EP-0002 rf2-bd4div), `:sidebar-mode`, `:launcher-pill`, `:keybindings` per [`API.md`](./API.md) §Settings keys. | User-mutable via the Settings popup; round-trips through localStorage. | Survives reload until corrupted or cleared. |

**Merge order (lowest precedence first):**

```
hardcoded defaults  <  configure! overrides  <  persisted Settings overrides
```

`init!` receives the **merged** config. Concretely:

1. Xray's compiled-in defaults seed every knob.
2. `configure!` writes overlay onto the process-global atoms before
   `init!` runs (or any `:preloads`-driven mount). Hosts that want a
   non-default starting value for a Settings-shape key (e.g. an
   embed that forces `:theme :high-contrast`) MAY pass it through
   `configure!`; the value lands as the new default for any user
   who has not yet mutated that key via the Settings popup.
3. The persisted Settings shape, loaded from localStorage on boot,
   then overlays whichever keys the user has previously mutated. A
   user toggling `:theme :light` once continues to see Light on
   every subsequent reload regardless of what the host wrote via
   `configure!`.
4. `init!` reads the fully-merged config when it wires the panel's
   per-instance state machine; `init! opts` is the last-mile shape
   passed to that wiring (test harnesses, Story testbeds, and
   embedding hosts that need to inject a specific shape at mount
   time without round-tripping through atoms).

**Implementation (rf2-rr2yw3).** For the `:rf.xray/settings` bulk-config
key specifically (`config.cljc`), step 2 is realised by seeding
`configured-settings-seed` (rather than unconditionally overwriting the
live settings atom AND the localStorage payload — the earlier
behaviour, which broke this exact order), and step 3 by
`load-settings-from-storage!` deep-merging the persisted payload OVER
`(merge-known-sections default-settings @configured-settings-seed)`.
See `:rf.xray/settings` above for the full reconciliation.

**Consequence.** A key like `:theme` legally appears on all three
surfaces — that is by design, not by accident. The host's
`configure!` call sets the boot-time default; the Settings popup
gives the user override authority; `init! opts` is the last-mile
injection seam for harnesses that want to pin a shape per mount.
Hosts that need pure static behaviour (no user mutation) leave the
Settings popup's relevant tab out of their build via the panel
inventory, or refuse persistence at the harness layer; the merge
order does not change.

The same rule applies symmetrically to every overlapping key today
(`:density`, `:target-frame` (EP-0002 rf2-bd4div), `:buffer-depths`; `:ai-provider` once
its backing infrastructure lands per rf2-2thl2). Future host-facing
knobs added to `configure!` MUST declare whether they participate in
the persisted Settings shape; knobs that do inherit this merge order
automatically.

## Reserved keys

The following keys are **reserved** for future `configure!` extension.
Hosts MUST NOT use them for their own purposes; future Xray releases
MAY assign them semantics.

- `:density`, `:target-frame` (EP-0002 rf2-bd4div), `:ai-provider`, `:buffer-depths`,
  `:sidebar-mode`, `:launcher-pill`, `:keybindings` — all currently
  owned by `(xray/init! opts)` and the persisted Settings shape per
  [`API.md`](./API.md), and read via the merge order above. A future
  consolidation MAY migrate them through `configure!` (so the host's
  boot-time overlay slot becomes the canonical write site); until
  then, set them via the per-instance / per-localStorage paths.

Note: `:theme` is **no longer reserved** — it now lives inside the
`:rf.xray/settings` map (see above) and is reachable via the Settings
popup's Theme tab or `(configure! {:rf.xray/settings {:theme :light}})`.

## Vision — full configure! key inventory (30+ keys)

v1 ships ~6 host-supplied keys (`:rf.xray/editor` /
`:rf.xray/project-root` / `:rf.xray/layout-host-selector` /
`:rf.xray/auto-open?` / `:rf.xray/keybinding-enabled?` /
`:rf.xray/egress-profile`) plus the `:rf.xray/filters` seed
slot. All Xray knobs follow the `:rf.xray/*` convention; each
re-frame2 tool reserves its own `:rf.<tool>/*` segment via
[`spec/Conventions.md`](../../../spec/Conventions.md) (Story's
`:rf.story/*`, etc.) — there is no cross-tool shared reservation,
including for the on-box egress profile (per-`(tool, frame)`).
The full destination per
[`ai/findings/2026-05-17-10x-config-options-for-xray.md`](#findings)
absorbs every re-frame-10x configuration option that translates plus
several Xray-native additions. The full list, grouped by phase
priority:

### Must-haves (matched against re-frame-10x's anchor)

All forthcoming keys follow the `:rf.xray/*` convention.

- `:rf.xray/filters-auto-hide-events <set>` — exact event-ids to
  auto-hide (re-frame-10x's `ignored-events`). Wired via the IN/OUT
  pill system in [`018-Event-Spine.md`](./018-Event-Spine.md) §7.
- `:rf.xray/filters-auto-hide-event-ns <vector>` — event-id namespace
  patterns to auto-hide (e.g. `["my-app.noisy" "re-com.box"]`).
- `:rf.xray/filters-auto-hide-error-overrides? <bool>` — when an
  auto-hidden event raises an exception, surface it anyway (default
  `true`). Errors override filters.
- `:rf.xray/buffer-retained-epochs <int>` — exposed retainer-N depth
  control (re-frame-10x's `retained-epochs`). Floor 25; ceiling 5000.
- Theme — already wired in v1 via `:rf.xray/settings`. Future:
  `:light`, `:dark`, `:dim`.

### Should-adds

- `:rf.xray/keybinding-handle-keys? <bool>` — master toggle for
  Xray's keystroke capture; default `true`. Hosts with conflicting
  global shortcuts can surrender.
- `:rf.xray/keybinding-bindings <map>` — rebind any action; default
  carries the spec-mandated set (`Ctrl+Shift+C`,
  `c`/`r`/`f`/`a`/`v`/`t`/`m`/`i` + spine keys per
  [`018-Event-Spine.md`](./018-Event-Spine.md) §Keyboard map).
- `:rf.xray/render-ns-aliases <map>` — rendering substitution so
  deeply-nested namespaces (`{my-app.deeply.nested mnn}`) collapse in
  panel renders. Re-frame-10x's `ns-aliases`.
- `:rf.xray/render-alias-namespaces? <bool>` — master toggle for
  ns-aliases substitution (paired with above).
- `:rf.xray/render-auto-expand-below <int>` — auto-expand data nodes
  with fewer than N children in the cljs-devtools-shaped renderer.
- `:rf.xray/render-uuids-as <enum :plaintext :identicons :last-4>` —
  UUID rendering format.
- `:rf.xray/launch-restore-visibility? <bool>` — persist last-known
  visibility across reloads.
- `:rf.xray/launch-popout-geometry <map>` — remember last popout
  window position `{:w :h :x :y}`.
- `:rf.xray/trace-collect-when <enum :always :panel-open>` — gate
  trace collection on panel visibility (re-frame-10x's `trace-when`).

### Nice-to-haves

- `:rf.xray/trace-fatten? <bool>` — opt into trace fattening for
  context-at-position payloads (Phase 5 prereq per
  [`013-Trace-Consumer.md`](./013-Trace-Consumer.md) §Vision).
- `:rf.xray/settings-tab-persist? <bool>` — persist selected tab
  across reloads.
- `:rf.xray/logging-debug? <bool>` — Xray self-debug logs
  (re-frame-10x's `debug?`). Backlog — Xray instruments itself via
  the trace bus; redundant for most cases.

### Recovery action (not a key)

- `(xray-config/factory-reset!)` — wipes every
  `day8.re-frame2-xray.*` localStorage key + resets in-memory atoms.
  Red button in the Settings popup; CLI escape hatch for "I broke
  something and don't know what to fix."

The full destination is auditable against `tools/xray/test/.../config_cljc_test.cljc`
which enforces no slot is forgotten when the surface grows.

<a id="findings"></a>

**Findings:** `ai/findings/2026-05-17-10x-config-options-for-xray.md`
carries the per-key design rationale, cross-reference against
re-frame-10x's 26 options, and the priority ranking that drives the
phase plan above.

## Production posture

Per [`API.md`](./API.md) §Force-disable, production builds DCE the
Xray shell. The config atoms survive (`configure!` is CLJC) but are
never read; calling `configure!` in production is a no-op observable
only through the atoms. Hosts MAY guard the call behind
`goog.DEBUG` / `^boolean js/goog.DEBUG` if avoiding the no-op write
matters — typically it does not.
