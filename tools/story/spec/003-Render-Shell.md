# Story — Render Shell

> The UI: sidebar, canvas, controls, workspaces, embedded Xray
> inspector; the five workspace layouts (`:grid` / `:prose` /
> `:variants-grid` / `:tabs` / `:custom`); hot-reload decorator
> fingerprinting; the `mount-shell!` / `unmount-shell!` /
> `active-shell` lifecycle. The contract Stage 4 implements (RHS
> revised per rf2-sgdd3 — see §Right-hand pane below).

See [`007-Mode-Tabs.md`](007-Mode-Tabs.md) for the `:dev` / `:docs` /
`:test` mode-tabs primitive that sits at the top of the canvas pane
(rf2-9hc8). See [`016-Design-Tokens.md`](016-Design-Tokens.md) for the
chrome-identity token contracts (typography / colour / motion /
backdrop / glyphs / toolbar 5-cluster) the shell composes.

See [`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) for the
Story-UI product contract that composes this shell into the target
workshop, and
[`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
for the Inspector region and the single canonical statement of the
Story/Xray ownership boundary (the right-hand Xray per-panel embed
remains owned here).

## UI shell substrate

Story's own UI shell (sidebar, control panel, embedded Xray inspector,
dispatch console, etc.) renders using **Reagent** at v1, sourced from
`implementation/adapters/reagent/`. The UI shell namespaces live under
`tools.story.ui.*`.

### Rationale

- **Reagent is stable and dogfood-neutral.** The Story UI shell
  exercises the same re-frame primitives Story stories exercise; using
  a substrate the rest of the codebase already validates avoids a
  self-hosting bias.
- **reagent-slim is still landing.** The slim rewrite is in active
  implementation. Story should not block on it; once reagent-slim is
  GA Story can migrate (Stage 8 may revisit).
- **The UI shell is itself one app's worth of views; substrate
  switches are cheap if done early.** Reagent's API surface is the
  most well-trodden; reducing risk during the initial Stage 4
  render-shell push.

### Bundle implications

The `tools/story/deps.edn` declares `reagent/reagent` (Maven coord)
and `day8/re-frame2-reagent` (the adapter, via `:local/root`).
Together these load Reagent v2 (per `feedback_target_reagent_v2`) in
the dev tool's runtime. In a `:advanced` build of the host app, Story
DCEs entirely (see [`005-SOTA-Features.md`](005-SOTA-Features.md) §DCE
contract) so neither dep reaches production.

## Chrome identity — design tokens

The shell composes its identity-bearing surfaces via the
[`016-Design-Tokens.md`](016-Design-Tokens.md) contracts. At
`(mount-shell!)` the shell injects three stylesheets one-shot into
`document.head` so the chrome's `font-family` / `animation` /
`box-shadow` declarations resolve immediately on first paint:

1. **IBM Plex `@font-face` rules** —
   `(theme.typography/inject-font-faces!)` (rf2-2rwdc).
2. **Motion keyframes + `prefers-reduced-motion` override** —
   `(theme.motion/inject-motion-css!)` (rf2-3lt89).
3. **Grain overlay (`::before` pseudo on `[data-rf-story-root]`)** —
   `(theme.depth/inject-grain-css!)` (rf2-ypd6h).

Each injector is idempotent and gated on
`re-frame.story.config/enabled?` so production builds DCE the chrome
entirely. The shell stamps its root with `data-rf-story-root` so the
token-dependent CSS rules (the `*:focus-visible` outline, the
reduced-motion override, the grain overlay) scope cleanly to Story's
chrome without bleeding into the host app.

The four chrome landmarks (toolbar / sidebar / main / right) ride
**staggered entrance keyframes** via inline `:animation` styles so
shell mount reveals as a choreographed 360ms sequence rather than a
single synchronous paint. The stagger map keys per region on a
`0ms` / `60ms` / `120ms` / `180ms` delay — see
[`016-Design-Tokens.md`](016-Design-Tokens.md) §Motion §Shell-mount
choreography for the canonical values.

See [`016-Design-Tokens.md`](016-Design-Tokens.md) for the full
token vocabulary the shell consumes:

- §Typography — the `sans-stack` / `mono-stack` / `display-stack`
  contract every chrome surface uses.
- §Colour — the warm-slate + amber palette + the seven-tag colour
  vocabulary.
- §Motion — the timing / easing / transition tokens + the entrance
  choreography.
- §Depth + backdrop — the gradient mesh + grain overlay + shadow
  scale + canvas-frame accent edge.
- §Iconography — the five SVG glyphs the sidebar consumes (see
  §Sidebar glyph rhythm below).
- §Toolbar 5-cluster — the MODES / DATA / VIEW / DEBUG / REC
  cluster contract (the canonical toolbar surface spec lives in
  [`010-Toolbar.md`](010-Toolbar.md)).

## Sidebar glyph rhythm

The sidebar renders three row types — **story** (parent container),
**variant** (renderable unit), **workspace** (multi-variant
composition) — with a deliberate glyph + colour rhythm so the tree
parses visually without reading text.

| Row type    | Glyph                            | Colour                                              | Active state                                        |
|-------------|----------------------------------|-----------------------------------------------------|-----------------------------------------------------|
| Story       | `theme.glyphs/story-glyph` (◆)   | `:accent-amber` — chapter-heading register          | Bold + `:bg-active` ground + `:accent-amber` text + 2px amber `border-left` (clickable, opens the rollup docs view per rf2-8j7wg / spec/008 §Per-story rollup docs page) |
| Variant     | `theme.glyphs/variant-glyph` (●) OR `status-dot` for testable variants | `:text-tertiary` (muted) OR semantic-status colour | `:bg-active` ground + `:accent-amber` text + 2px amber `border-left` |
| Workspace   | `theme.glyphs/workspace-glyph` (▦) | `:info` (cool-cyan) — composition register         | `:bg-active` ground + `:accent-amber` text + 2px amber `border-left` |

**Amber-cyan temperature split** — the story + active-selection
glyphs wear warm amber; the workspace glyph wears cool info-cyan.
The temperature contrast tells the eye "this row is a different
category" without needing labels. Testable variants (carrying `:test`
in `:tags` or a non-empty `:play-script` sequence) replace the muted variant
glyph with a **`status-dot`** that wears the semantic colour from
the variant's last `run-variant` outcome
(`:success`/`:danger`/`:warning`/pending).

The glyphs are rendered inline-SVG via `currentColor` so CSS
controls colour at the call site — see
[`016-Design-Tokens.md`](016-Design-Tokens.md) §Iconography for the
five-glyph set and SVG contract. The sidebar component lives at
[`ui/sidebar.cljs`](../src/re_frame/story/ui/sidebar.cljs); the
style map at
[`ui/sidebar_styles.cljs`](../src/re_frame/story/ui/sidebar_styles.cljs).

## Workspace layouts

A `reg-workspace` body declares `:layout`; the render shell hosts five
canonical layouts:

| Layout | Source data | UX |
|---|---|---|
| `:grid` | explicit `:variants` list | Responsive grid; per-card width/height; drag-resize. |
| `:prose` | `:content` blocks (`{:type :prose :body "md..."}` or `{:type :variant :id ...}`) | Markdown narrative with embedded variants. |
| `:variants-grid` | implicit — enumerates variants from the registry for one parent story | devcards-style "all states at once" view. |
| `:tabs` | explicit `:variants` list | Tab strip; one variant active at a time. |
| `:custom` | `:render <view-id>` | Hosts an arbitrary registered view. |

The `:variants-grid` layout (Phase 2 SOTA addition) renders every
variant of a single parent story side-by-side; it differs from `:grid`
(which renders an explicit `:variants` list) by enumerating variants
from the registry.

### `:variants-grid :isolation` (rf2-gqid4)

The workspace body's optional `:isolation` slot tunes the mount
strategy for `:variants-grid`:

- `:isolated` (default) — every cell mounts in parallel; each wraps
  its rendered view in a per-variant scope provider (the
  namespace-preserving `frame-provider-existing` twin — the variant
  frame is already allocated). Baseline frame-isolation contract.
- `:shared` — cells mount ONE at a time with a prev/next navigator
  (◀ N/total ▶). Same serialised-mount strategy `:tabs` (rf2-ktnl8)
  uses, scoped to the implicit `:variants-grid` enumeration.

`:shared` is the affordance for views that internally hardcode a
frame-provider (e.g. `gallery_chrome.cljs` / rf2-sszlr): parallel
cells of such views share interior state because the last-seeded
cell's app-db clobbers siblings. Serialised mount restores per-
variant state without forcing the author to flip the workspace to
`:tabs` (which loses the devcards `:variants-grid` semantic of
enumerating variants from the registry).

The normative slot definition lives in
[`001-Authoring.md`](001-Authoring.md) §reg-workspace.

## Shell lifecycle

```clojure
(mount-shell! mount-point opts)        ; attach the Story chrome
(unmount-shell!)                       ; detach
(active-shell)                         ; => {:mount-point ... :workspace ...} or nil
```

Hot-reload preserves shell state: a re-mount calls into the same shell
node and reseats the workspace. Decorator fingerprinting tracks
whether the decorator stack used by a mounted variant has changed at
the registry level; stale variants re-mount.

## Multi-substrate side-by-side rendering

For a variant declaring `:substrates #{:reagent :uix :helix}` (or any
subset; default = the host frame's adapter), the render shell renders
each substrate **inline** in its own pane.

- Each per-substrate render runs in a try/catch boundary.
- A substrate-specific failure renders inline alongside the other
  substrates' healthy renders — it is **not** auto-skipped.
- The failure record is appended to the variant's `:assertions` list,
  tagged with the substrate that failed.

The rationale for inline-rendering substrate failures is documented in
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §inline-substrate-failures.

### Substrate-portable post-render hook

DOM-mutating chrome that runs against the rendered output of a variant
(axe-core a11y scans; the layout-debug overlay trio — measure, outline,
pseudo) uses the **`:adapter/after-render`** late-bind hook the
framework publishes from each substrate adapter (Reagent / UIx /
Helix — rf2-334d9). The hook is `(re-frame.interop/after-render f)`,
which dispatches through the late-bind directory to whichever
substrate the active variant runs under; UIx and Helix wire it via
`useLayoutEffect`, Reagent via its post-render queue. Each adapter
publishes the hook at startup so panel code stays substrate-agnostic.

At v1 Story's own UI shell is Reagent-only (§UI shell substrate above)
and the load-bearing post-render work happens inside the chrome's
Reagent layer, so the hook is not used by the shell itself today.
**At Stage 8** — when the Story chrome migrates to multi-substrate
per §Bundle implications and when the a11y / layout-debug panels
attach against a variant whose substrate is not Reagent — these panels
attach via `:adapter/after-render` rather than substrate-specific
escape hatches. The hook is the canonical portable surface; panel
authors writing for the multi-substrate world should target it
directly.

## Workspace persistence (both modes)

Workspace layouts persist **both** ways:

- **Default: local-storage.** Interactive rearrangements (drag-resize
  panes, reorder variants in a grid) auto-save to local storage keyed
  by `[workspace-id breakpoint]`. Persistence is per-user, per-browser.
- **Save-as registered artefact.** A "Save layout as `:Workspace.x/y`"
  button serialises the current layout to transit (full
  `story/reg-workspace` body), re-registers it under the chosen id, and
  exports the transit blob for cross-machine sharing. Other users
  `(story/reg-workspace ...)` the transit body to consume the layout
  durably.

Stage 4 (render shell) wires the local-storage save on every layout
change; Stage 4 also adds the "Save layout" affordance.
`tools.story.workspace.transit/workspace->edn` returns the serialised
form. Rationale: see [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md)
§both-workspace-persistence.

## Right-hand pane (rf2-sgdd3 · rewritten per rf2-v1ach)

The RHS stacks four regions vertically:

1. **Xray — per-panel embed** (rf2-v1ach). Story's RHS hosts ONE
   Xray panel at a time, picked by the user via a chip-row above
   the panel slot. The embed is rendered by
   [`re-frame.story.ui.xray-embed/xray-embed-panel`](../src/re_frame/story/ui/xray_embed.cljs)
   and supersedes the pre-rf2-v1ach whole-shell `[data-rf-xray-host]`
   mount: the 4-layer Xray chrome (L1 ribbon + L2 event list +
   tab-bar + detail panel) needs ~720px to render usefully and
   Story's RHS column is 320px, so the per-panel embed renders one
   Xray panel cleanly at column width and exposes the rest via the
   pop-out escape hatch. See §Mount lifecycle below for the lock on
   the contract between Story (the host) and Xray (the panel
   provider).

   The region renders four things:

   - **Chip-row picker.** A horizontal strip of chips
     `[Event] [App-db] [Views] [Trace] [Machines] [Routing] [Issues]`
     wearing the seven Xray panel ids in the canonical
     debugging-frequency order. The active chip carries the warm
     amber accent; clicking a chip swaps which panel mounts below
     and the user's click is sticky for the session (it overrides
     the variant's declared default per §Authoring `:xray-panel`
     slot). At the right edge of the chip-row sits a `[Pop out ↗]`
     chip — clicking it opens the full Xray 4-layer shell in a
     second window via `day8.re-frame2-xray.mount/popout!`, the
     escape hatch for the chrome the per-panel embed elides.
   - **Event spine band (rf2-9k43e).** A COMPACT, clickable
     recent-events spine sitting BETWEEN the chip-row and the panel
     host (`<div data-test="story-xray-spine-band">`). It hosts the
     SAME `shell/event-list` L2 component the full Xray shell
     composes — via Xray's `panels/mount-event-spine!` (see
     [`tools/xray/spec/008-Embedding-Contract.md`](../../xray/spec/008-Embedding-Contract.md)
     §Embeddable event spine) — so a variant's event SEQUENCE is
     inspectable IN-PLACE, not only the final/focused event. The
     spine + the chip-selected panel mount in the SAME `:rf/xray`
     frame; clicking a PAST event in the spine dispatches
     `:rf.xray/focus-cascade` (the row's own handler, reused
     verbatim from the full shell), which re-binds the spine sub
     `:rf.xray/focus` — and the panel below re-renders against the
     chosen epoch (app-db + epoch panels update to that epoch).
     **Mike RULED scope = A (2026-06-01):** the embed's inline spine
     is the compact in-place affordance; the `[Pop out ↗]` /
     `Ctrl+Shift+C` full-shell stays the deep-history escape hatch
     (NOT a second full shell). The band is height-capped (the
     full-shell L2 defaults to ~200px; Story's RHS column is 320px,
     so the host caps the band to keep it compact and lets the
     list's own `overflow-y:auto` scroll older events — the host
     owns the container size). While the embed is collapsed
     (rf2-ba86n.19 disclosure) the spine drops too — no spine mount,
     no L2 compute.
   - **Panel host.** A `<div data-rf-xray-panel-host=<panel-id>>`
     into which one of Xray's `panels/mount-<panel>!` fns
     ([`day8.re-frame2-xray.panels`](../../xray/src/day8/re_frame2_xray/panels.cljs))
     mounts a Reagent tree. The host is owned by a Reagent class-3
     component (see §Mount lifecycle) that drives the
     mount/unmount round-trip on every panel-id swap.
   - **Empty / no-Xray states.** When no variant is focused, the
     region renders an italic placeholder ("Select a variant to
     inspect via Xray."). When Xray is not on the host build's
     classpath the region renders a distinct empty state ("Xray
     is not loaded in this build — embed surface unavailable.")
     so the chrome stays honest about what's available rather than
     blanking silently.

   Feature-detection rides
   `re-frame.story.xray-preset/xray-available?`. The selection-
   watcher seam in [`shell.cljs`](../src/re_frame/story/ui/shell.cljs)
   calls `xray-preset/wire-cross-host!` on every variant-selection
   edge to bridge Story's configuration into Xray's slots
   (`:rf.xray/project-root`, `:rf.xray/keybinding-enabled?`, listener
   detach) — but does NOT mount Xray; the embed's panel-host owns
   the mount lifecycle. See §Mount lifecycle for the full sequencing.

   The diagnostic surface area the embed covers is the same as the
   pre-rf2-v1ach whole-shell mount: L1 ribbon + L2 event list (under
   the Event panel) replace the retired scrubber; the Trace panel
   replaces the retired trace panel; the Event-tab cascade view +
   filtered Trace replace the retired actions panel. The chip-row
   makes the panel selection explicit at the column level.

2. **Controls** — per-variant args editor (Story-unique).

3. **Dispatch Console** — free-form event dispatch into the
   variant's frame (Story-unique, opt-in per variant via
   `:dispatch-console?`).

4. **Play status / viewport / backgrounds** — Story-unique chrome
   chips for the active variant's `:play-script` status, viewport
   sizing, and background framing.

The shell's `:panel-visibility` map drives Stage-6 registered
`reg-story-panel` panels (a11y, schema-validation, layout-debug)
underneath; they keep their late-bind contract per §Panel
registration contract.

## Mount lifecycle (rf2-v1ach)

The Xray-in-RHS embed (region 1 of §Right-hand pane) is the canonical
Story-Xray mount surface. This section locks the public contract
between Story (the host) and Xray (the panel provider). Third-party
panels that want to compose alongside Xray (future statechart-viz,
custom inspectors) key against this same shape.

### The contract — `panels/mount-<panel>!`

Xray exposes seven per-panel mount fns under the
[`day8.re-frame2-xray.panels`](../../xray/src/day8/re_frame2_xray/panels.cljs)
namespace (locked in
[`tools/xray/spec/008-Embedding-Contract.md`](../../xray/spec/008-Embedding-Contract.md)
§Per-panel mount API and
[`tools/xray/spec/007-UX-IA.md`](../../xray/spec/007-UX-IA.md)
§Mountable panel contract):

| Panel id        | Mount fn                                                  | Xray Panel view                                  |
|-----------------|-----------------------------------------------------------|---------------------------------------------------|
| `:epoch`        | `(mount-epoch-panel!        mount-point opts) → unmount`  | Numbered computational timeline for the focused epoch (rf2-5gl5r supersedes `:event-detail`) |
| `:app-db`       | `(mount-app-db-diff!        mount-point opts) → unmount`  | Structural diff of app-db across the cascade      |
| `:views`        | `(mount-views!              mount-point opts) → unmount`  | Per-view sub-invalidation surface                 |
| `:trace`        | `(mount-trace!              mount-point opts) → unmount`  | Trace-buffer feed for the focused cascade         |
| `:machines`     | `(mount-machine-inspector!  mount-point opts) → unmount`  | State-machine chart + arcs/rings/cluster overlays |
| `:routing`      | `(mount-routing!            mount-point opts) → unmount`  | Registered routes + simulate-URL surface          |

(rf2-gbz39 — `:issues` + `mount-issues-ribbon!` were removed alongside
the Xray Issues tab per Mike's Option (c) ruling; issues surface inline
in the Epoch panel + the L2 event-row pink-wash + the always-on issues
ribbon signal, so there is no standalone Issues panel to embed.)

The RHS additionally mounts one **L2 spine** (rf2-9k43e), the compact
in-place event navigator — NOT a chip-row panel:

| Pseudo-id       | Mount fn                                                  | Xray view                                        |
|-----------------|-----------------------------------------------------------|---------------------------------------------------|
| `:event-spine`  | `(mount-event-spine! mount-point opts) → unmount`         | The L2 `shell/event-list` recent-events timeline (the full shell's spine, mounted standalone) |

`:event-spine` is wired through `mount-fn-for` like every chip panel,
but it does NOT appear in `panel-catalog` (it is a persistent band, not
a chip-selected lens). The same `panel-host-component` class-3 driver
mounts it — with the spine host-style + `data-test="story-xray-spine-host"`
— so it inherits the rf2-4l7t2 React-18 deferred-unmount lifecycle. Its
panel-id never changes, so the host mounts the spine once; focus changes
flow through the spine's own subs, not a remount.

Each mount fn:

1. Installs Xray's handler registry (idempotent).
2. Ensures the `:rf/xray` frame exists (idempotent
   `rf/reg-frame`).
3. Wraps the panel view in `[rf/frame-provider-existing {:frame :rf/xray}
   [<Panel>]]` so the panel's `:rf.xray/*` subscribes resolve to
   Xray's state-isolation frame regardless of host React-context.
4. Delegates to the host's `substrate-adapter/render` to mount the
   tree at `mount-point`.
5. Returns an `unmount` fn so the caller owns lifecycle.

The six panel ids are also the chip ids in §Right-hand pane's
chip-row picker. (rf2-gbz39 dropped `:issues` with the Xray Issues tab.)

### Story's panel-host — Reagent class-3 driver

Story drives the mount/unmount lifecycle from a Reagent class-3
component (`panel-host-component` in
[`xray_embed.cljs`](../src/re_frame/story/ui/xray_embed.cljs)).
Its job is to translate panel-id changes into mount/unmount round-
trips against the active panel's `mount-<panel>!`:

| React lifecycle hook       | Action                                                                                      |
|----------------------------|---------------------------------------------------------------------------------------------|
| `:component-did-mount`     | call `(mount-fn-for active-panel host-div)`; stash the returned unmount fn.                 |
| `:component-did-update`    | when the panel-id changed, call the stashed unmount fn, then mount the new panel.           |
| `:component-will-unmount`  | call the stashed unmount fn; clear the ref.                                                 |
| `:reagent-render`          | render `<div data-rf-xray-panel-host=<panel-id> ref=... />` — stable across re-renders.    |

The component is keyed on `<variant-id>::<panel-id>` in the parent
hiccup so a variant or panel change forces a fresh React mount
(belt-and-braces — the lifecycle handlers cover it, but the key
guarantees no state leaks across Xray-internal bugs).

`mount-fn-for` does a compile-time symbol → fn lookup against the
canonical six panel ids (e.g. `:epoch` → `day8.re-frame2-xray.
panels/mount-epoch-panel!` post rf2-5gl5r; rf2-gbz39 dropped `:issues`
with the Xray Issues tab) **plus the `:event-spine` pseudo-id**
(rf2-9k43e — → `panels/mount-event-spine!`, the L2 spine band) — a nil
result is a clean no-op render, so Xray absence never throws.

### Chip-click → swap sequence

A user click on a chip in the picker:

1. Mutates `:xray-panel` in
   `re-frame.story.ui.state/shell-state-atom` via
   `state/swap-state!`.
2. Re-renders `xray-embed-panel` (the atom is a Reagent ratom).
3. `effective-panel` resolves the new panel-id (user override
   wins over story/variant `:xray-panel` slot).
4. The parent hiccup's React key changes — `panel-host-component`
   remounts cleanly; the previous panel's unmount fn fires; the
   new panel's mount fn fires.

The user's chip click is sticky for the session — it overrides any
declared `:xray-panel` until the user picks a different chip or
the shell unmounts.

### `wire-cross-host!` — bridges-only, no mount

[`re-frame.story.xray-preset/wire-cross-host!`](../src/re_frame/story/xray_preset.cljc)
fires from the selection-watcher on every variant-selection edge.
It does THREE things — all configuration bridges, none of which
mount anything:

1. **`:rf.story/project-root`** — read Story's configured root via
   `story/configure!` and propagate to
   `day8.re-frame2-xray.config/configure!` so Xray's source-
   coord chips resolve against the same on-disk root Story uses
   (rf2-r1uod, symmetric to shop's rf2-6jyf6).
2. **`:rf.xray/keybinding-enabled? false`** — flip Xray's
   keybinding config slot so Story's `Cmd/Ctrl+K` reaches Story's
   own command palette without Xray's global capture-phase
   listener swallowing the key (rf2-q7who.1).
3. **`day8.re-frame2-xray.keybinding/detach!`** — runtime removal
   of the listener Xray's preload already installed under the
   default-true posture (rf2-ycrt2 — closes the gap rf2-q7who.1
   declared but did not close).

This replaces the legacy `ensure-xray-mounted!` whose name
implied a mount but in practice fired the same three bridges PLUS
called `mount/open!` on Xray's whole-shell mount path. Under the
per-panel embed the panel-host owns the mount; `wire-cross-host!`
fires the bridges only. The deprecated `ensure-xray-mounted!`
shim was REMOVED (rf2-ee38b.3 — no production caller, per the
project's no-back-compat posture). A caller that genuinely needs
the legacy whole-shell open composes
`(do (wire-cross-host!) (apply-open!))` directly; the supported
route is the popout escape hatch.

### Adding a third-party panel

A future tool (statechart-viz, profiler-overlay, etc.) that wants
to compose into the same RHS picker would:

1. Expose `(mount-<panel-id>! mount-point opts) → unmount-fn`
   from its own namespace, following Xray's
   `panels/mount-<panel>!` shape.
2. Register the panel-id in Story's `panel-catalog` (rf2-v1ach
   reserves the seven Xray ids; additions follow the same vector
   shape).
3. Map the panel-id to its mount-fn symbol in
   `panel-id->mount-fn-sym`.

The chip-row picker, the panel-host's lifecycle, and the user-
override stickiness all work identically — the contract is panel-
provider-agnostic because it keys against the public mount-fn
shape rather than Xray-specific internals.

The detailed Xray-side contract — every mount fn's input subs,
write events, and embedding `opts` — lives in
[`tools/xray/spec/008-Embedding-Contract.md`](../../xray/spec/008-Embedding-Contract.md)
and the per-panel Xray specs in
[`tools/xray/spec/007-UX-IA.md`](../../xray/spec/007-UX-IA.md).

## Trace bus consumption

Each variant's frame gets a trace callback registered at variant-mount
into a per-variant ring buffer (`re-frame.story.ui.trace-buffer`):

```clojure
(rf/register-listener! variant-id
  (fn [trace-event]
    (swap! (variant-trace-buffer variant-id) conj trace-event)))
```

The buffer is consumed by the schema-validation panel (rf2-dvue) to
project Spec 010 validation failures. Xray, embedded in the RHS,
maintains its own trace history through its own preload-time
`re-frame.trace.tooling/register-listener!` registration — the two
are independent listeners on the framework trace bus.

The six-domino cascade projection (`re-frame.trace.projection/
group-cascades`) used to power Story's retired trace panel; it now
lives in framework code and is consumed by Xray's Trace tab. Per
Phase 1 §4.3 this is the debugging UX no JS tool can match.

## Panel registration contract

The `reg-story-panel` surface is the **single hook** through which
tooling embeds itself into the Story chrome — no new registry kind,
no parallel mounting protocol. Five rules govern panel hosting:

1. **`:render` is a `:view` id.** The shell renders the panel by
   calling `(rf/view <render-id>)` and invoking the resolved fn with
   the current `variant-id`. Late-bind: the actual view can register
   from a different artefact (e.g. `day8/re-frame2-xray` for the epoch
   panel) so long as the same `:view` id is registered before the user
   opens the panel.

2. **Placement is one of five slots.** `:right` / `:left` / `:bottom`
   / `:top` host the panel inline; `:modal` opens it over the canvas.
   Each placement is an independent host; multiple panels at the same
   placement stack in id order.

3. **Visibility flows through `:panel-visibility`.** The shell state's
   `:panel-visibility` map (keyed by panel id) is the on/off switch.
   Unspecified → visible; explicit `false` → hidden. Users toggle via
   the chrome's panel-list affordance.

4. **Author calls `reg-story-panel` from anywhere on the classpath.**
   The built-in panels (`:rf.story.panel/a11y` /
   `:rf.story.panel/chrome-a11y` / `:rf.story.panel/layout-debug`)
   register from inside `install-canonical-vocabulary!`; third-party
   tooling (e.g. future statechart-viz panels) registers from its own
   boot. Per rf2-18t6p, the chrome-a11y panel is the sibling of `:a11y`
   scoped to `[data-rf-story-root]` (the chrome wrapper) rather than
   `[data-rf-story-variant-root]` — same engine, distinct scope, so
   Story dogfoods its OWN a11y rather than only the variant tree.

5. **Xray is NOT a `reg-story-panel` registration.** Pre-rf2-v1ach
   the spec described a `:rf.story.panel/epoch` panel that late-bound
   to a Xray-registered view. That model is superseded by the
   per-panel embed locked in §Right-hand pane and §Mount lifecycle —
   Story embeds Xray via a direct require of `xray-embed-panel`
   and per-panel `mount-<panel>!` calls, not via `reg-story-panel`.
   The five-rule panel contract above still governs other tooling
   embeds (a11y, schema-validation, layout-debug, future third-party
   panels that do NOT participate in the Xray chip-row); the Xray
   surface has its own dedicated contract because the chip-row
   picker + Xray's mount-fn family are richer than `reg-story-
   panel`'s single-view-per-panel shape.

See [`006-MCP-Surface.md`](006-MCP-Surface.md) for how the agent
surface re-uses this contract.

## Source-coord stamping flow (UI side)

The story tool's "Open in editor" affordance (v1.1) reads `:source`
off the variant registry record. The play-runner copies the `:source`
of each `:play-script` step into the corresponding `:assertions` record
so failure cards link back to source.

## Namespace layout

```
tools/story/
├── deps.edn
├── README.md
├── spec/                                        ; this folder
├── src/
│   ├── re_frame/
│   │   └── story.cljs                           ; public ns — reg-* macros + run-variant
│   │   └── story.clj                            ; macro impl (the dev/prod expand split)
│   └── tools/
│       └── story/
│           ├── impl/                            ; private — what the macros call
│           │   ├── reg.cljs                     ; reg-story*, reg-variant*, etc.
│           │   ├── schema.cljs                  ; :rf/variant Malli schema
│           │   └── extends.cljs                 ; :extends resolution
│           ├── registry.cljs                    ; side-table; query helpers
│           ├── runtime/
│           │   ├── frame.cljs                   ; per-variant frame allocation
│           │   ├── args.cljs                    ; effective-args resolution
│           │   ├── decorators.cljs              ; composition order
│           │   ├── loaders.cljs                 ; 4-phase lifecycle
│           │   ├── play.cljs                    ; play-runner + assertion recorder
│           │   ├── snapshot-id.cljs             ; content-hash
│           │   └── source-coord.cljs            ; source-stamp pipe
│           ├── render/
│           │   ├── shell.cljs                   ; sidebar / canvas / panels
│           │   ├── variants_grid.cljs           ; :variants-grid layout
│           │   ├── controls.cljs                ; auto-derived controls
│           │   ├── multi_substrate.cljs         ; side-by-side substrate panes
│           │   └── time_travel.cljs             ; (retired rf2-sgdd3 — Xray L1 ribbon + L2 list)
│           ├── panels/
│           │   ├── trace_buffer.cljs            ; per-variant trace ring buffer (schema-validation consumer)
│           │   ├── a11y.cljs                    ; axe-core integration — VARIANT scope
│           │   ├── chrome_a11y.cljs             ; axe-core integration — CHROME scope (rf2-18t6p)
│           │   ├── perf.cljs                    ; live ribbon (v1.1)
│           │   ├── layout_debug.cljs            ; measure / outline / pseudo
│           │   ├── design_tokens.cljs           ; v1.1, conditional
│           │   └── docs.cljs                    ; autodocs from :doc + schemas
│           ├── share/
│           │   └── transit.cljs                 ; workspace transit export
│           ├── workspace/
│           │   ├── grid.cljs
│           │   ├── prose.cljs
│           │   └── transit.cljs
│           └── ui/
│               ├── widgets.cljs                 ; controls widgets
│               ├── theme.cljs
│               └── routing.cljs                 ; story-tool URL surface
└── test/
    └── tools/
        └── story/
            └── ...
```

The macro-emitting layer is `re-frame.story` (the user-facing ns); all
internal implementation lives under `tools.story.*`. Public ns names
match the convention from `re-frame.adapter.reagent` /
`re-frame.ssr`.
