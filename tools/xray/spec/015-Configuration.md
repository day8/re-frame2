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
from `(xray/init! opts)` per [`API.md`](./API.md) §Public CLJS API,
the manual installation hook with optional target and Settings writes, and
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

The flat hyphenated shape (`:rf.xray/filters-auto-hide-error-overrides?`,
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
| **Settings popup (bulk-set)** | `:rf.xray/` | `:rf.xray/settings` (carries `:general`, `:theme`, `:buffer`, `:diff`) | — | [`§:rf.xray/settings`](#rfxraysettings) |
| **Filters** | `:rf.xray/` | `:rf.xray/filters`, `:rf.xray/filters-auto-hide-error-overrides?` | `:rf.xray/filters-auto-hide-events`, `:rf.xray/filters-auto-hide-event-ns` | [`§:rf.xray/filters`](#rfxrayfilters) + [`§Error overrides`](#rfxrayfilters-auto-hide-error-overrides) |
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
dev REPL). The one exception is `:rf.xray/filters`: that seed is a
**pre-mount boot baseline** consumed once per frame by
`mount.cljs`'s `::seed-configured-filters` first-mount hook, not
re-read per use — so a post-mount `configure!` does not take effect
until the next load. Change filters live via the filter pill events /
Story path instead.

## Configuration keys

### `:rf.xray/editor`

The 'Open in editor' click-to-source target. Drives every surface that
renders a source-coord — the Epoch panel's event-detail hero and
interceptor rows, the reactive panel's `[code]` chip, the Trace
panel's per-event rows, and Static mode's machine / schema / route
catalogue rows (per [`API.md`](./API.md) §Open in editor).

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
fire the silent `:rf.xray.fx/open-in-editor` navigation:

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
not exist", so Xray's Open chip and the `:rf.xray.fx/open-in-editor` reg-fx need
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

Jump-to-source has TWO launch paths; the chip + the
`:rf.xray.fx/open-in-editor` reg-fx PREFER the first and FALL BACK to
the second. The mechanism is
additive — B never removes the URI path. (See also
[`spec/Tool-Pair.md` §Open-in-editor launch modes](../../../spec/Tool-Pair.md).)

1. **Dev-server endpoint (preferred).** The JS-ecosystem standard
   (Vite `/__open-in-editor`, react-dev-utils, Next). A shadow-cljs
   `:dev-http` Ring `:handler` —
   `re-frame.testbed.open-in-editor-server/handler`, a **JVM-only `.clj`**
   server fn — answers
   `POST /__rf-open-in-editor?file=<…>&line=<n>&column=<c>`. The
   endpoint is **POST-only +
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
| `:rf.egress/local-redacted` | Default (fail-closed). Xray's trace collector MUST drop events whose top-level `:sensitive?` field is `true` before Xray's own frameless secondary-ring push and before the coalesced mirror-sync request, and MUST bump the suppressed-events counter (see [§App-db slots](#app-db-slots) below) so the shell's **L1 chrome ribbon** can surface a `[● REDACTED N]` indicator. This ingest gate reaches neither the framework's per-frame rings, which retain every emitted event, nor the framework's epoch records — the read-side gates covering those are owned by [`013-Trace-Consumer.md` §One policy](./013-Trace-Consumer.md#one-policy-three-ingress-paths-rf2-y8doi13). |
| `:rf.egress/local-raw` | The trusted-local operator opt-in. The collector receives every event unchanged; `:sensitive? true` events flow through to every consumer. Includes large values too (the profile's `:rf.egress/include-large?`). |
| `nil` | Resets to the default (`:rf.egress/local-redacted`). |
| unknown | `configure!` rejects with `:rf.error/unknown-egress-profile` (the enum is closed). |

The "is a `:sensitive?` event suppressed?" decision derives from the
profile's `:rf.egress/include-sensitive?` resolution via the framework
projection table (`projection/profile-size-opts`), the SAME table
`project-egress` consumes — one source of truth, no re-implemented
redaction policy. The profile MUST be read at the head of the collector
body on every event so changing it via `configure!` takes effect on the
next trace event without re-registering the listener (per
[`013-Trace-Consumer.md`](./013-Trace-Consumer.md) §Privacy gate).

**Reveal is an auditable operator act** (Spec 015 §Cross-tool grain):
widening to `:rf.egress/local-raw` emits a `:rf.xray/egress-reveal` trace
event (`config.cljc/set-egress-profile!`, fail-soft and CLJS-only) so the
reveal is recorded rather than being a silent local flip. It is emitted
FRAMELESS and under an `:op-type` of `:rf.xray`, which is outside
[Spec 009 §`:op-type` vocabulary](../../../spec/009-Instrumentation.md#op-type-vocabulary)'s
closed set — so it reaches Xray's frameless secondary ring (and the L2
`:show-ungrouped?` bucket that surfaces it) but is NOT shown by the
epoch-scoped Trace panel today, and no test pins the emit.
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
(command palette), `Cmd/Ctrl+Shift+M` (Dynamic ↔ Static mode), the
unmodified spine bindings (`Space` / `l` / `Shift+G` / `j` / `k` /
`,` / `s` — `keybinding.cljs`'s `spine-key-id` is the roster, and it
deliberately states no count), and a contextual `Esc` that fires only
while the open-in-editor hint toast is open. It calls
`stopPropagation()` for the keys it consumes so host bindings further
down the propagation path don't double-fire.

| Value | Meaning |
|---|---|
| `true` | Default. The listener is installed (by `keybinding/attach!` at load time, or by the watch below when the slot flips back); the standalone Xray shell behaves exactly as it did pre-rf2-4eyik. |
| `false` | No global listener on `js/document`. `keybinding/attach!` short-circuits to a no-op when the slot is already `false`; a flip that arrives after the listener is attached removes it. |
| `nil` | Reset to default (`true`). |

**The flip is self-acting at any point in the boot sequence, so host
boot ordering does not change what the host has to call.**
`keybinding.cljs` watches this slot (rf2-y8doi.17) and attaches /
detaches the global listener on every genuine change, both directions
idempotent through the `attached-state` CAS. That watch is what makes
the slot mean what it says on the `:devtools/preloads` path, which is
the documented install route: shadow-cljs loads preloads before the
app's `:init-fn`, so the preload has already called
`keybinding/attach!` by the time the host's `configure!` runs, and a
slot read only at attach time was a silent no-op for every host that
took that route.

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
`attach!` — the hatch for removing the listener WITHOUT declaring the
slot. The contract:

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

When to call: a host that flips `:rf.xray/keybinding-enabled?` needs
nothing further — the watch described above removes the listener for
it, whenever the flip lands. `detach!` is for the host that wants the
listener gone WITHOUT declaring the slot, or that must remove it from
a mount-time hook it does not own. Per rf2-ycrt2 (rf2-q7who.1 runtime
follow-on), corrected by rf2-y8doi.17. Calling it alongside a slot
flip is harmless — merely redundant, since both sides are idempotent —
which is what Story's `re-frame.story.xray-preset/wire-cross-host!`
does today: `disable-keybinding!` (slot flip) then
`detach-keybinding!` (explicit removal), belt-and-braces.

`detach!` removes what `attach!` installed on the OPENER document and
deliberately reaches no further: a pop-out window's listener belongs
to the pop-out's own lifecycle and re-reads this slot per keystroke.
So clearing the slot is the route that quiets both surfaces.

### `:rf.xray/settings`

Bulk-replace the Settings popup state map (rf2-9poxq; expanded by
rf2-ttnst — Mike 2026-05-19 §0ter.4 walkthrough). Shape mirrors the
`default-settings` block in `config.cljc`:

```clojure
{:general   {:text-size               13          ; px; host-set — the popup slider was removed 2026-05-27
             :panel-position          :right-rail ; :right-rail | :fullscreen; pop-out has its own launch button
             :panel-width-px          560         ; number; clamped [320, 0.9 × viewport-width-px]
             :events-list-height-px   200         ; L2/L3 resize seam
             :auto-open-on-error?     false
             :density                 :cosy       ; :cosy | :compact
             :show-unchanged-subs?    false
             :show-ungrouped?         false
             :epoch-history           50          ; per-frame epoch ring depth
             :long-keyword-threshold  24          ; characters
             :reduced-motion-override :os         ; :os | :always | :never
             :use-system-colors?      false
             :event-list-col-widths   {:source 52 :timestamp 76 :duration 60}
             :editor-override         nil}        ; nil uses host editor; otherwise same values as :rf.xray/editor
 :theme     :light                               ; :light | :dark
 :diff      {:highlight-fn-ref-changes? false}   ; opt-in fn-ref classification
 :buffer    {:events-retained 50}}                ; per-frame trace-ring event count
```

The `:general` slot carries two knobs introduced by rf2-ttnst:

- `:density` — `:cosy` (default) or `:compact`. Drives the Views
  detail rows + App-db diff rows vertical rhythm. The `:comfy` tier
  catalogued earlier in spec/007-UX-IA.md §Density slider is dropped
  in v1; persisted `:comfy` values from prior schemas are treated as
  `:cosy` by the `:rf.xray/density` convenience sub.
- `:long-keyword-threshold` — integer (chars). Fully-qualified
  keywords longer than the threshold elide in compact list cells.
  Default `24`, was previously a fixed constant; now user-tuneable
  per spec/007-UX-IA.md §Long-keyword treatment.

A third, `:show-tool-frames?`, was **removed under rf2-y8doi.27**. Its
Settings UI had gone earlier and nothing replaced it, so no surface
could write the slot and its two readers hardcoded `false` — a slot no
surface can write is not an override waiting to be re-enabled. The
frame-observation isolation invariant it appeared to relax is enforced
where it always was, by `frame-switcher/internal-frames`,
unconditionally.

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
clear. It clears *data* only: the `:events-retained` retention this
same Buffer tab configures survives the clear, as does any frame's
explicit override (rf2-kuky.54). The action is dispatch-only and
carries no `configure!` counterpart; hosts that need a programmatic
clear call the `trace-collector` helper directly.

The `:panel-width-px` slot (rf2-x8h9y) drives the
`:right-rail` panel's horizontal width. The Xray drag handle (per
[`007-UX-IA.md` §Resize affordance](./007-UX-IA.md#resize-affordance))
writes through to this slot on drag-end; the slot persists via the
existing `re-frame2.xray.settings.v2` localStorage key so width survives
reloads. Default `560`. Ignored in `:popout` (window owns size) and
`:fullscreen` (viewport owns size) positions.

> Note (rf2-jh9ws): a `:telemetry` slot shipped briefly with the
> initial popup landing (rf2-9poxq) but was removed — Xray
> transmits no telemetry. Legacy `:telemetry` keys in persisted
> payloads or in `(configure! {:rf.xray/settings ...})` calls are
> silently dropped by the per-section merge.

| Value | Meaning |
|---|---|
| Map | Deep-merge over `default-settings`, section by section AND recursively within each section (rf2-8j3gyt — a partial nested override, e.g. `{:general {:event-list-col-widths {:source 100}}}`, keeps its untouched sibling keys at their default rather than dropping them). Seeds the live settings map immediately. Never persists: `configure!` writes nothing to localStorage, and the seed is re-applied on every boot, so it lands for every key the user holds no explicit override for — see the merge-order reconciliation below (rf2-rr2yw3, rf2-3x7nj.27.1). |
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
> no storage-backed load ever running — tests, harnesses), and NEVER
> persists it. `load-settings-from-storage!` deep-merges whatever IS in
> localStorage over `default-settings` seeded with the `configure!`
> map, so a returning user's persisted values always win for the keys
> they've touched, while a fresh key the host newly configures still
> lands for everyone else.
>
> **Storage holds explicit overrides only (rf2-3x7nj.27.1).** That last
> promise needs the payload to name only the keys the user touched, and
> for a while it did not: every write stored the WHOLE resolved map —
> compiled-in defaults and the host seed included — and `configure!`
> also wrote it on a fresh install, "so the posture survives a reload".
> Read back as the top layer, that made every key behave as user-set:
> after one panel drag, or no user action at all, no later host
> `configure!` value and no later Xray default reached that browser.
> The payload is now a sparse map of the exact paths a user gesture or
> an `init!` opt wrote; `configure!` writes nothing, since the
> documented host re-seeds on every boot; the boot width clamp never
> turns an inherited width into an override; and a column drag records
> only the column it moved. The storage key moved to `.v2` so every
> whole-map `.v1` payload is ignored rather than read as an overlay.
>
> **Order-independent since rf2-y8doi.17.** Seeding alone was not
> enough: the live atom was still reset to defaults-plus-seed with the
> persisted layer dropped, which is harmless only if
> `load-settings-from-storage!` runs AFTERWARDS — and on the
> `:devtools/preloads` path, the route every shipped host takes, it
> does not. shadow-cljs loads preloads before the app's `:init-fn`, so
> the preload's load has already run by the time the host calls
> `configure!`, and the reset landed ON TOP of the user's values: the
> documented order delivered inverted. `configure!` now recomputes the
> live map by re-reading the persisted payload and re-merging it above
> the seed, so the result is the same whichever of the two runs first.

Default-defining shape, per-knob rationale and the localStorage key
are normatively documented in
[`016-Auxiliary-Panels.md`](./016-Auxiliary-Panels.md) §Settings popup
— v1 ships. The `:rf.xray/editor` / `:rf.xray/project-root` /
`:rf.xray/auto-open?` / `:rf.xray/egress-profile` keys above
remain process-global atoms distinct from `:rf.xray/settings` (their
semantics predate the popup; the popup-managed surface is the
`{:rf.xray/settings <map>}` shape).

### `:rf.xray/filters`

Host-supplied seed pill set applied to `:active-filters` as the explicit
**boot baseline**. The host opts in; `mount.cljs/::seed-configured-filters`
(a first-mount hook that runs AFTER the transient-filter reset) lands a
non-empty seed on **every** load. Per
[`018-Event-Spine.md`](./018-Event-Spine.md) §7 'Empty defaults',
Xray ships with no filters by default (first-session honesty beats
first-session quietness). The seed is the escape hatch for hosts
that have a reason to ship a starting posture — typically Story
testbeds that need a known starting point for reproducibility.

| Value | Meaning |
|---|---|
| `{:in [{:pattern <…>} …] :out [{:pattern <…>} …]}` | The host's explicit boot baseline, applied to `:active-filters` on every load after the transient reset. This is NOT durable user-filter persistence — a user's own session pills always reset — and it never depends on localStorage. |
| `nil` (default) | No seed; the slot stays at its unfiltered registry default `{:in [] :out []}` — a fully-unfiltered first paint. |

> **Transient user filters vs the explicit host seed (rf2-swclw,
> rf2-fhtes).** The IN/OUT pills, the muted-event-id set, and the frame
> view-scope are **transient exploration filters**: a fresh load starts
> fully unfiltered, so a stale filter can never silently hide rows on
> reload (rf2-jvghz; an inspector must show the truth).
> `mount.cljs/::reset-transient-filters` hydrates none of them, and for
> the two that still carry a localStorage slot — the mute set and the
> frame pin — it additionally CLEARS the stored value so storage matches
> what the user sees. The IN/OUT pills need no clear at all: rf2-y8doi.27
> deleted their persistence layer outright, so reset-on-load now holds by
> construction rather than by cleanup. Durable view prefs
> (the persisted Settings shape below — mode, density, panel layout)
> hydrate on boot via their own hooks. The `:rf.xray/filters` seed is a
> THIRD, distinct category: an EXPLICIT host boot baseline the programmer
> opted into. It is re-applied every load by `::seed-configured-filters`,
> which runs immediately AFTER the transient reset — so the host's baseline
> always wins over (and is never clobbered by) a user's stale
> localStorage/session filters, while a `nil` seed leaves the paint fully
> unfiltered. It is neither durable user-filter persistence nor an
> unreachable first-install-only value.

> **Removed — `:rf.xray/filters-storage-key` (rf2-y8doi.27).** The key
> named the localStorage slot (`re-frame2.xray.filters.v1`) that the
> IN/OUT-pill persistence layer read and wrote. That layer is gone: the
> pills are transient by policy (above), so the store had a writer and no
> reader and every load cleared it. The `:rf.xray/filters` seed was always
> a separate axis — an in-memory boot baseline whose hook never touched
> localStorage (rf2-fhtes) — and is unaffected. Hosts running several Xray
> instances in one browser session no longer need the key to isolate their
> pills; there is nothing left to collide. `set-filters-storage-key!` went
> with it.

### `:rf.xray/filters-auto-hide-error-overrides?`

Controls the shipped error bypass in the frame-scoped filter chain.
Default `true`: an errored event hidden by an IN/OUT pill or mute is
surfaced anyway and tagged `:rf.xray/filter-bypassed?` for the UI cue.
The bypass never crosses the selected frame's view scope. `false`
lets those filters hide errored events too; `nil` resets to `true`.
The per-key setter is `set-filters-auto-hide-error-overrides!`.

This is a boot-time setting read by the filter subscriptions, not a
persisted Settings-map slot. Its consumer and composition contract are
in [018 §7 Error overrides](018-Event-Spine.md#7-filter-system), with
end-to-end wiring covered by `filters/error_override_wiring_cljs_test.cljs`.

### Static mode availability

Static mode is unconditionally available. The mode **dropdown** mounts at
chrome-ribbon-right (`data-testid="rf-xray-mode-pill"` — the testid keeps
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
(visual-language treatment of the mode pill, motion dampening,
chrome silhouette) +
[`018-Event-Spine.md`](./018-Event-Spine.md) §Static surface (the
architectural contract — 3-layer silhouette, mode-recognition
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
§Shared infrastructure), which the trace collector's counter
dispatches at most once per task, carrying that task's per-frame
counts to add. It is
cleared by `:rf.xray/reset-suppressed-counters` — either entirely
(no-arg) or per-bucket — fired from `trace-collector/retroactive-scrub!`
and test fixtures.

The `:rf.xray/suppressed-sensitive-count` subscription reads this
slot and returns the total across every bucket; the
`[● REDACTED N]` L1 chrome-ribbon indicator binds to that sub so the count
updates on the standard reactive write path within one task of the
collector's bumps, with no dependency on sibling subs recomputing
(rf2-0vxdn PR #681).

The slot's source-of-truth duality is deliberate: the underlying atom
in `day8.re-frame2-xray.config/suppressed-counters` remains the
JVM-runnable data primitive (so CLJC unit tests can assert it without
spinning up a CLJS runtime and a frame), and the dispatch into
`:rf/xray` is the reactive surface for CLJS. The frame slot converges
to the atom within one task: the atom takes every bump as it happens,
and the bumps of one task reach the slot together as a single
dispatch scheduled for the next task. It is never one dispatch per
bump, so a host burst of sensitive traces cannot carry `:rf/xray`'s
queue past the router's drain depth (rf2-p03xh).

### Xray-owned `:rf/xray` frame

Every other piece of Xray state — selected dispatch-id, selected
panel, pin store, target-frame, etc. — lives under the `:rf/xray`
frame's app-db per [`008-Embedding-Contract.md`](./008-Embedding-Contract.md)
§State isolation (Option-C frame-provider). Those slots are owned by
the panels that drive them; this doc enumerates only the
configuration-derived slot (`:suppressed-counters`) because it is the
visible bridge between `configure!` and the reactive surface.

**Frame is registered trace-disabled (rf2-2qaqh).** Xray SEATS its own
frame as an EP-0023 image-loaded frame and asserts the framework's
`:rf.trace/frame-no-emit?` gate alongside it, in one place —
`seat-xray-frame!` in
`tools/xray/src/day8/re_frame2_xray/panels/image_view_reads.cljs`,
driven by `mount.cljs`'s `ensure-xray-frame!`:

```clojure
(rf.live-frame/make-frame {:id :rf/xray :images [(xray-image)]})
(rf.trace/set-frame-no-emit! :rf/xray true)
```

This marks `:rf/xray` a tool / inspector frame: the framework's `emit!`
/ `emit-error!` short-circuit for any trace event tagged with that frame,
so Xray's own UI reactivity (`:rf.sub/run` + `:rf.view/render` on every
panel render) emits NO trace and never floods the shared ring it
inspects. It is the frame-scoped sibling of the handler-scoped
`:rf.trace/no-emit?`. The flag is frame-keyed trace state owned by
`re-frame.trace`, independent of the frame's image generation — the
EP-0023 constructor honours only frame-creation opts (`:id` /
`:images` / `:initial-events` / …) and would reject it as a
record-config key — so it is set through `set-frame-no-emit!` on
EVERY seat and re-seat, and the gate survives hot-reload. This is NOT
a `configure!` key; it is framework-owned frame state Xray sets
internally. See
[framework API §`:rf.trace/frame-no-emit?`](../../../spec/API.md) +
[`013-Trace-Consumer.md` §Framework-side: emission suppressed at source](./013-Trace-Consumer.md#framework-side-emission-suppressed-at-source). (Xray
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
| `(xray/init! opts)` | Manual installation hook, alternative to `:preloads`; opening/mounting is a separate verb. Loads the user's persisted Settings and applies them FIRST (rf2-y8doi.17), in the same position the preload's boot block does, then installs. Accepted options are `:target-frame`, `:theme`, `:density`, and `:buffer-depths {:epoch N}`. The Settings options address `:theme`, `[:general :density]`, and `[:general :epoch-history]`; target selection addresses Xray frame state. Aspirational AI/sidebar/launcher/keybinding-map slots are not accepted by either this hook or the shipped Settings map. Idempotent installation. | Host-code-driven; once-per-load. | Installation plus explicit option writes, not a new independent Settings instance. |
| Persisted Settings (`localStorage` slot `re-frame2.xray.settings.v2`) | Explicit overrides only, in the four slots `:general`, `:theme`, `:diff`, `:buffer`, as enumerated in [§`:rf.xray/settings`](#rfxraysettings): a sparse map of the exact paths written by user gestures (the popup, the ribbon/resize controls) and by `init!` opts — never the resolved settings map (rf2-3x7nj.27.1). Target selection and the separate `xray.mode` preference do not live in it. | User-mutable; round-trips through localStorage. | Survives reload until cleared; unreadable payloads fall back in memory. |

**Merge order (lowest precedence first):**

```
hardcoded defaults  <  configure! overrides  <  persisted Settings overrides
```

`init!` receives the **merged** config. Concretely:

1. Xray's compiled-in defaults seed every knob.
2. `configure!` writes overlay onto the process-global atoms. Hosts
   that want a non-default starting value for a Settings-shape key
   (e.g. an embed that defaults to `:theme :dark`) MAY pass it
   through `configure!`; the value lands as the new default for any
   user who has not yet mutated that key via the Settings popup.
   **This step's position in wall-clock boot order does not matter**
   — on the `:devtools/preloads` path the host's `configure!` call
   necessarily runs AFTER the preload's load-time block, step 3
   included, and the merge order still holds (see the Implementation
   note below).
3. The persisted Settings shape, loaded from localStorage on boot,
   then overlays whichever keys the user has previously mutated. The
   payload holds exactly those keys — a sparse overlay of the paths
   written (rf2-3x7nj.27.1) — so every other key keeps following steps
   1 and 2 as they change. A user toggling `:theme :light` once
   continues to see Light on every subsequent reload regardless of
   what the host wrote via `configure!`. **BOTH install routes perform this load** — the
   preload's boot block, and `init!` itself (rf2-y8doi.17; before
   that fix `init!` never loaded them, so a manually-installed host
   saw compiled-in defaults however much the user had saved).
4. `init!` reads the fully-merged config when it wires the panel's
   per-instance state machine; `init! opts` is the last-mile shape
   passed to that wiring (test harnesses, Story testbeds, and
   embedding hosts that need to inject a specific shape at mount
   time without round-tripping through atoms). The opts are written
   LAST, so for the keys they name they win over the persisted
   values — which is exactly what a per-mount pin is for.

**Implementation (rf2-rr2yw3).** For the `:rf.xray/settings` bulk-config
key specifically (`config.cljc`), step 2 is realised by seeding
`configured-settings-seed` (rather than unconditionally overwriting the
live settings atom AND the localStorage payload — the earlier
behaviour, which broke this exact order), and step 3 by
`load-settings-from-storage!` deep-merging the persisted payload OVER
`(merge-known-sections default-settings @configured-settings-seed)`.
rf2-y8doi.17 completed it: `configure!` recomputes the live map through
the same resolution, so whichever of the two runs first, the result is
the order above. rf2-3x7nj.27.1 fixed the write side: `update-setting!`
adds the one path it was given to the payload already in storage and
stores that, never the live map; `configure!` writes nothing. See
`:rf.xray/settings` above for the full reconciliation.

**Consequence.** A key like `:theme` legally appears on all three
surfaces — that is by design, not by accident. The host's
`configure!` call sets the boot-time default; the Settings popup
gives the user override authority; `init! opts` is the last-mile
injection seam for harnesses that want to pin a shape per mount.
Hosts that need pure static behaviour (no user mutation) leave the
Settings popup's relevant tab out of their build via the panel
inventory, or refuse persistence at the harness layer; the merge
order does not change.

The same rule applies to overlapping Settings values today: theme,
density and epoch depth, using the nested paths listed above. It does
not make `:target-frame` a persisted Settings field. Future host-facing
knobs added to `configure!` MUST declare whether they participate in
the persisted Settings shape; knobs that do inherit this merge order
automatically.

## Reserved keys

The following keys are **reserved** for future `configure!` extension.
Hosts MUST NOT use them for their own purposes; future Xray releases
MAY assign them semantics.

- Bare top-level `:density`, `:target-frame`, and `:buffer-depths` are
  not `configure!` keys. `init!` accepts these spellings as described
  above; boot defaults for density and epoch depth instead go through
  `:rf.xray/settings` at their nested `:general` paths.
- `:ai-provider`, `:sidebar-mode`, `:launcher-pill`, and `:keybindings`
  name earlier aspirations, not shipped inputs. Do not put them in the
  persisted Settings map expecting behaviour; future work must establish
  a real consumer before adding any such slot. In particular, Xray's
  current [no-AI boundary](Principles.md#no-ai-in-the-panel-surface) is
  unchanged.

Note: `:theme` is **no longer reserved** — it now lives inside the
`:rf.xray/settings` map (see above) and is reachable via the chrome
ribbon's sun/moon toggle or
`(configure! {:rf.xray/settings {:theme :light}})`. There is no
Settings-popup Theme tab: rf2-ou3pn dropped it as pure redundancy
(both surfaces dispatched the identical
`[:rf.xray/settings-update :theme nil <kw>]`), leaving the ribbon
toggle canonical — per [`007-UX-IA.md`](./007-UX-IA.md) §Settings
popup, "Dropped from earlier drafts".

## Vision — full configure! key inventory (30+ keys)

v1 ships NINE host-supplied keys — `:rf.xray/editor`,
`:rf.xray/project-root`, `:rf.xray/layout-host-selector`,
`:rf.xray/auto-open?`, `:rf.xray/keybinding-enabled?`,
`:rf.xray/egress-profile`, `:rf.xray/settings`, `:rf.xray/filters`
and `:rf.xray/filters-auto-hide-error-overrides?`. That is the same
set the [§Cluster catalogue](#cluster-catalogue) table enumerates and
the same set `configure!`'s own argument destructuring accepts; the
three lists move together. (It was ten until rf2-y8doi.27 deleted
`:rf.xray/filters-storage-key` — see §`:rf.xray/filters`.)
All Xray knobs follow the `:rf.xray/*` convention; each
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
  `true`). Errors override filters. **Implemented (rf2-jqqsh9)** —
  wired through `configure!` + the `:rf.xray/filtered-event-bundles`
  data-layer chain (`filters.error-override`); see
  [`018-Event-Spine.md`](./018-Event-Spine.md) §7 Error overrides.
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

- `(xray-config/factory-reset!)` — wipe every Xray localStorage key +
  reset the in-memory atoms. NOT SHIPPED: what exists today is
  `config/reset-settings!`, which resets the settings atom, clears the
  `configure!` seed and removes the ONE `re-frame2.xray.settings.v2`
  slot. The red-button UI is dropped — factory-reset stays code-only
  per [`007-UX-IA.md`](./007-UX-IA.md) §Settings popup, "Dropped from
  earlier drafts" — so this entry is the wider CLI escape hatch for
  "I broke something and don't know what to fix", not a missing
  button.

  Whatever ships has to enumerate **two key families**, because Xray's
  localStorage keys carry no single prefix: the versioned
  `re-frame2.xray.<name>.vN` slots (Settings, the frame pin, palette
  recents, event-list column widths) and the bare, unversioned
  `xray.<name>` slots (mode, the muted-event-id set, the Static-mode
  machine slots, the machine-canvas collapse map). The
  `day8.re-frame2-xray.*` prefix an earlier revision of this section
  promised matches NONE of them — it is the Clojure namespace root,
  never a storage prefix.

**No test enforces that destination, and the JVM file this section
used to cite could not.**
`tools/xray/test/day8/re_frame2_xray/config_test.clj` covers the
`configure!` / per-key setter surface: the editor preference and
`editor-configured?`, auto-open, project-root and editor-URI
construction, the filter seed, panel-width and event-list-column
clamping, and the `defaults < configure!` settings merge. It
enumerates NEITHER key family above and asserts nothing about slot
removal — it calls `reset-settings!` as per-test setup and teardown,
and the one property it pins about that call is that it clears the
`configure!` seed. Nor could a `.clj` test do more: the storage half
of `reset-settings!` is a `#?(:cljs …)` branch (`config.cljc:1615`,
`storage-remove!` at `:1628`), and `config_test.clj` records at its
own `:554-556` that the CLJS storage reader resolves to nil under
Clojure.

Coverage worth the name would need a CLJS test that seeds a slot in
BOTH families, runs the reset and reads storage back empty — and
something that fails when a new slot joins the surface without
joining that list. Both arrive with `factory-reset!`, not before it.

<a id="findings"></a>

**Findings:** `ai/findings/2026-05-17-10x-config-options-for-xray.md`
carries the per-key design rationale, cross-reference against
re-frame-10x's 26 options, and the priority ranking that drives the
phase plan above.

## Production posture

Per [`API.md`](./API.md) §Force-disable and
[`Principles.md`](./Principles.md) §Production posture is build
placement, a release build carries no Xray shell when the host doesn't
load Xray — build placement, not a gate inside these namespaces. On a
build that *does* load Xray with `goog.DEBUG=false`, the preload's boot
block folds away, so the shell never mounts and never reads the config
atoms; `configure!` is then a write nobody observes but the atoms
themselves. Hosts MAY guard the call behind `goog.DEBUG` /
`^boolean js/goog.DEBUG` if avoiding that write matters — typically it
does not, and it is not what keeps Xray out of the bundle.
