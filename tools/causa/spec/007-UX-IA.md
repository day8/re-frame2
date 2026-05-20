# 007-UX-IA

## The one-event-spine model (the load-bearing statement)

Every Causa surface orients around **one focused event** — the spine
sub `:rf.causa/focus`. The user picks an event in the L2 list; every
dependent surface rebinds atomically. Tabs are **lenses on that one
event**:

| Tab | Bug-class it answers |
|---|---|
| **Event** (`e`) | "What does this event do?" — six dominoes + wire-boundary diff per managed fx. |
| **App-db** (`a`) | "What changed because of this event?" — slice diff. |
| **Views** (`v`) | "Why did these views re-render?" — sub invalidation chain. |
| **Trace** (`t`) | "What raw events fired in this cascade?" — wall-clock axis grows future. |
| **Machines** (`m`) | "What did this event do to my machines?" — transitions, cancellation cascade, `:after` rings. **Event-driven only post-rf2-y9xmf** (no picker, no Mode A/B/C; BLANK when the focused event has no machine activity; per-machine prev/next nav walks the spine). |
| **Machines Canvas** (`c`) | "What does this machine LOOK like?" — spine-INDEPENDENT canvas browser. Picker on the left, interactive Chart adapter on the right (zoom / pan / fit + keyboard shortcuts). No focused-event lens — the canvas always shows the picked machine's full topology. Earned its own tab per the cohesive-sub-domain rule (rf2-mkpnb). |
| **Issues** (`i`) | "What's wrong here?" — errors · warnings · schema violations · hydration mismatches · advisories. |
| **Chrome A11y** (`y`) | "Is Causa's OWN chrome accessible?" — spine-INDEPENDENT dogfood (rf2-5r2yj). Runs axe-core scoped to `#rf-causa-root` — the Causa mount node — so a11y regressions in the L1 ribbon, L2 event list, L3 tab bar, L4 detail panels, modals, and resize handle surface during dev. Mirrors Story's `chrome-a11y` panel (PR #1695). axe-core loads opt-in via CDN with an SRI hash pinned; consent click lives inline in the panel. |

Plus popovers (`r` nav-token timeline · `f` wire-trace
+ `h` hydration bisector, future). Every popover is invokable from any
tab. Every popover anchors on `:rf.causa/focus`.

**This is the single most important model in the spec.** Every
information-architecture decision in this doc derives from "one event
in, full insight out via tab + popover lenses." Cross-cutting concerns
(SSR · Machines · Routes · Managed-Fx) extend tabs; they never
fragment the chrome. See
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) for the
5-idioms × 4-areas matrix.

---

The user experience, the information architecture, the visual
language. This doc is what an implementer reads to ship pixels that
feel right — typography sizes, colour tokens, animation timings,
keyboard maps, density gradients.

For the *why* behind these picks, see [`Principles.md`](./Principles.md)
and [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md). For the
architectural contract of the 4-layer chrome + spine binding + tab
content, see [`018-Event-Spine.md`](./018-Event-Spine.md). For the
cross-cutting-concerns rendering vocabulary, see
[`019-Cross-Cutting-Insight.md`](./019-Cross-Cutting-Insight.md).

## Layout

Causa fills a true-inline panel on the **right side** of the host app
by default. The host app provides `[data-rf-causa-host]` as a normal
flex/grid column and Causa renders inside it. This is not an overlay
and not a body-padding dock: the app remains visible and clickable to
the left because normal layout owns the relationship.

### The 4-layer chrome

```
┌─────────────────────────────────────────────────────────────────────────┐
│ LAYER 1  Top ribbon (56px)                                              │  scope controls
├─────────────────────────────────────────────────────────────────────────┤
│ LAYER 2  Event list (8 rows default; resizable; min 2)                  │  the spine / timeline
├─────────────────────────────────────────────────────────────────────────┤
│ LAYER 3  Tab bar (40px) — 9 tabs                                        │  projection selector
├─────────────────────────────────────────────────────────────────────────┤
│ LAYER 4  Detail panel (fills remaining canvas)                          │  per-tab content
└─────────────────────────────────────────────────────────────────────────┘
```

Wireframe at default (800px popout, "cosy" density):

```
┌─────────────────────────────────────────────────────────────────────────┐
│ [◀ ▶ ⏭] │ Frame: :app/main ▾ │ [+ :auth/* ✎] [× :mouse-move ✎] [+] │ ● LIVE │ ⚙ ⛶ ✕ │   L1
├─────────────────────────────────────────────────────────────────────────┤
│ ● :auth/login                                          [● REDACTED 1]   │   L2 — 8 rows default
│ ● :app/route-changed                                                    │      single-line
│ ● :input/changed                                                        │      latest-on-bottom
│ ● :form/submit-clicked                              🤖                  │
│ ● :order/submit                                     🌐                  │
│ x :checkout/finalize                          ⚠                         │
│ ● :cart/recalculate                                                     │
│ ◉ :order/retry                                      🌐  ← head/sel      │
├═════════════════════════════════════════════════════════════════════════┤   drag handle (L2/L3)
│ ◉Event ○App-db ○Views 8 ○Trace 47 ○Machines 1 ○Canvas ○Routing ⚠Issues 1 ○Chrome-A11y │   L3 — 9 tabs
├─────────────────────────────────────────────────────────────────────────┤
│ — Event tab content for the focused event —                             │   L4 — fills the rest
└─────────────────────────────────────────────────────────────────────────┘
```

The four layers, top to bottom:

1. **L1 — Top ribbon (56px).** Four clusters: nav (`◀` `▶` `⏭`) ·
   frame picker · filter pills (IN/OUT) · right-icons (settings `⚙`
   + popout `⛶` + close `✕`). The Mode pill widget that earlier
   drafts placed between filter pills and right-icons was dropped —
   LIVE/RETRO surfaces in the L2 event-list spine itself. Anatomy in
   §The L1 ribbon below.
2. **L2 — Event list.** 8 single-line rows default; vertically
   resizable (min 2); latest-on-bottom; virtualised. Single row shape
   decorated by gutter glyph (`● ◉ x ▥ ↺`) + right-aligned badges (`⚠`
   `🌐` `🤖`) + trailing redaction marker (`[● REDACTED N]`). The
   spine sub `:rf.causa/focus` reads from this layer.
3. **L3 — Tab bar (40px).** Nine tabs: Event / App-db / Views / Trace /
   Machines / Machines Canvas / Routing / Issues / Chrome A11y. Letter
   mnemonics: `e` `a` `v` `t` `m` `c` `r` `i` `y`. Count badges
   (`Views 8`) update with focused cascade. Routing was promoted to its
   own L3 tab in rf2-nrbs9; Machines Canvas was promoted in rf2-mkpnb —
   both follow the cohesive-sub-domain rule (sub-domains earn their own
   lens tab). Chrome A11y was added in rf2-5r2yj as the diagnostics-
   group dogfood — mirror of Story's `chrome-a11y` panel (#1695).
4. **L4 — Detail panel.** Fills remaining canvas (60% default;
   resizable via L2/L3 drag handle). Per-tab content; all values
   rendered via the cljs-devtools-shaped renderer (see §Detail panel
   renderer).

**No bottom rail.** The pass-2/round-1 "L0" rail (with scrubber +
mode pill + classification totals) is gone — the ribbon's `[◀ ▶ ⏭]`
cluster IS the seek, the event list IS the timeline (and the L2 spine
itself indicates LIVE / RETRO via the head-row pulse / pinned-row
glyph; the dedicated Mode pill widget was dropped). Classification
totals live in per-row + per-panel renderings.

Below 1200px viewport: pop-out detaches if user opens it; chrome stays
within the inline host.

Below 900px viewport: Causa takes 100% of viewport width.

Below 600px viewport (phones): **Causa refuses to mount** (per lock
#5). The DOM root creates but the visible UI is a single message
explaining desktop-only.

### Inline host CSS variables

The default true-inline host (`[data-rf-causa-host]`) is sized and
themed via two host-readable CSS custom properties. Causa never reads
or writes these from CLJS — the host's stylesheet is the single
source of truth.

| Property | Default | Purpose |
|---|---|---|
| `--rf-causa-inline-width` | `560px` | `flex-basis` of the inline host. Default bumped 420 → 560 under rf2-9ovfb. |
| `--rf-causa-accent` | `#7C5CFF` | Causa's brand violet (matches `:accent-violet` below). Published on `:root` in the recommended host snippet so host stylesheets can colour their own dev chrome to harmonise with Causa (rf2-9ovfb). |

Override either property anywhere up the cascade; the closest
declaration wins as usual. The published spelling is also exported
as `day8.re-frame2-causa.config/default-layout-host-css-var` /
`default-layout-host-width` / `default-accent-css-var` /
`default-accent` so tooling and docs generators can refer to them
without forking the string. Full contract + drag mechanics in
[`011-Launch-Modes.md`](./011-Launch-Modes.md).

### Resize affordance

Causa's panel SHALL be horizontally resizable via a drag handle on the
panel's outer edge (left edge when docked `:right-rail`; the default per
`Settings → General → Panel position`). The handle SHALL:

- Show `cursor: col-resize` on hover
- Drag-to-update width with global pointer-capture (mouse, touch,
  and pen unified through pointer events; `touch-action: none` so a
  touch-drag does not pan the page)
- Clamp width to `[320px, 90vw]`
- Persist via `configure! :rf.causa/settings :general :panel-width-px`
  (see [`015-Configuration.md`](./015-Configuration.md))
- Reset to default width on double-click

The CSS-variable cascade (`--rf-causa-inline-width` on the host's
`flex-basis`) continues to work unchanged — the handle simply drives
the same custom property reactively, so a `:root { --rf-causa-inline-
width: 720px; }` override and a user drag write to the same surface.

No textual affordance accompanies the handle (cursor change is sufficient
signal per [`Conventions.md`](./Conventions.md) §UI text — silent by
default). The resize handle is a worked example of "non-obvious
affordance with iconographic alternative": discovery is via cursor
change on edge hover, not via prose label.

In `:popout` panel-position the browser's window controls govern size
(no in-panel handle renders). In `:fullscreen` position the handle is
suppressed — the panel fills the viewport.

#### Auto-inject contract (rf2-70u8q)

The handle is **auto-injected**. Consumers SHALL NOT need to wire any
resize CSS on the layout host — dropping in
`<aside data-rf-causa-host></aside>` and the minimal host CSS (no
`resize: horizontal`, no `overflow: auto`) is sufficient. Causa's
preload mounts the shell into the host once the substrate adapter is
ready (per §The default landing view); the shell renders the handle as
an absolutely-positioned child pinned to the host's left edge.

The handle's styles are Causa-owned — the consumer's CSS surface
remains exactly the four declarations the layout-host contract
already requires (`flex`, `min-width`, `box-sizing`, `border-left`).

##### Yield-to-consumer

Causa MUST detect a consumer-asserted browser-native handle and yield:
if `getComputedStyle(host).resize` is `"horizontal"` or `"both"`, the
auto-injected handle SHALL render nil so the page does not carry two
draggable affordances. The probe runs at render time on every paint,
so a runtime CSS swap (devtools edit, theme switch) updates the yield
decision on the next frame.

The yield path is the **opt-out**. Pre-alpha posture is zero-config
for the consumer; the opt-in to Causa's handle is "do nothing" and
the opt-out to keep the browser-native handle is "set `resize:
horizontal` on the host". No `configure!` knob, no preload flag.

##### Keyboard contract

The handle is keyboard-reachable (`tabindex="0"`, `role="separator"`,
`aria-orientation="vertical"`, live `aria-valuenow` for the current
width). Bindings:

| Key | Action |
|---|---|
| `ArrowLeft` | Widen by 8px (matches drag-left semantics) |
| `ArrowRight` | Narrow by 8px |
| `Shift+ArrowLeft` | Widen by 32px (coarse step) |
| `Shift+ArrowRight` | Narrow by 32px |
| `Home` | Snap to upper clamp (registry applies the 90vw bound) |
| `End` | Snap to lower clamp (registry applies the 320px floor) |
| `Enter` / `Space` | Reset to default (matches double-click) |

Unrecognised keys bubble normally so the surrounding chrome's
`Ctrl+Shift+C` / `?` / `Esc` shortcuts remain reachable from the
handle's focus position.

## The L1 ribbon

Four clusters, fixed order left to right. (The Mode pill widget that
earlier drafts placed between filter pills and right-icons was
dropped; LIVE/RETRO surfaces in the L2 event-list spine itself.)

| Cluster | Width | Content | Keys |
|---|---|---|---|
| **Nav** | 84px | `◀` back-one-event · `▶` forward-one-event · `⏭` fast-forward-to-latest (snap head + resume LIVE) | `j` / `k` / `G` |
| **Frame** | flex 0 1 200px | `Frame: :app/main ▾` dropdown (multi-frame); flat `Frame: :rf/default` label when single-frame. **Single-select only.** Tool frames hidden unless Settings → View → "Show tool frames in picker" toggle on. | — |
| **Filter pills** | flex 1 1 auto | IN pills (green `+`) + OUT pills (magenta `×`) + trailing `[+]` add-pill. Click any pill → edit popup. | `/` focus add-pill |
| **Right-icons** | 96px | `⚙` settings popup · `⛶` popout (`window.open` whole shell) · `✕` close shell | `,` or `s` · `o` · `Esc` |

Full anatomy + filter-pill edit popup in
[`018-Event-Spine.md`](./018-Event-Spine.md) §3 + §7.

### Frame slot contract (rf2-iwwou)

The **Frame** cluster is the L1 frame-switcher slot — the single
contractually-anchored surface every frame-aware feature reaches
through. The slot lives in `tools/causa/src/day8/re_frame2_causa/
frame_switcher.cljs`; the ribbon mounts the view as one delegate. The
contract:

| Surface | Id | Role |
|---|---|---|
| Sub | `:rf.causa/current-frame` | Returns the frame id the user has focused (or nil pre-selection). |
| Sub | `:rf.causa/available-frames` | First-seen-order vec of selectable frames; tool frames filtered by default per §I1 below. |
| Event-fx | `:rf.causa/select-frame <frame-id>` | Canonical write. Dispatches the spine's `:rf.causa/set-frame` (which re-seeds `:target-frame` + `:epoch-history` — see [`018-Event-Spine.md`](./018-Event-Spine.md) §6) AND fires `:rf.causa.frame-switcher/persist` for localStorage. |
| Fx | `:rf.causa.frame-switcher/persist` | localStorage write under `re-frame2.causa.frame-switcher.v1` (per-instance overridable via the direct setter `day8.re-frame2-causa.frame-switcher/set-storage-key!`; a future `configure! :rf.causa/frame-switcher-storage-key` plumb is straightforward but not wired today). |

Every frame-aware feature — the L1 ribbon picker, the Cmd-K palette's
`:palette/select-frame` verb, future panel-by-frame surfaces — MUST
dispatch `:rf.causa/select-frame`. Reaching the spine's `:rf.causa/
set-frame` primitive directly bypasses the persistence + future
instrumentation layers attached to the canonical event.

## The default landing view

On page load after `rf/init!`, when `[data-rf-causa-host]` exists:

- Causa auto-opens in the right inline host.
- `Ctrl+Shift+C` hides/shows the already-mounted shell with a CSS-only
  display toggle.
- **Active tab: Event**, showing the most-recent cascade's event
  detail (the spine sub `:rf.causa/focus` auto-points at head).
- **Frame picker: shows the active frame** (single-frame apps collapse
  to static label).
- **Filter pills: empty by default** — first session is honest about
  what's filtered; Recommended quick-add available via add-pill.
- **L2 spine: head row pulses** (LIVE cue; the dedicated Mode pill widget was dropped).

## Single-line event-list rows (L2)

ONE row shape, decorated by gutter glyph + right-aligned icon badges
+ trailing redaction marker. Full anatomy + click behaviour + hover
tooltip in [`018-Event-Spine.md`](./018-Event-Spine.md) §4.

### Row anatomy

| Col | Width | Content | Notes |
|---|---|---|---|
| Gutter glyph | 16px | `● ◉ x ▥ ↺` | Status; cyan border for selected adds 1px not 16px |
| Event id | flex mono 13px violet-accent | `:order/submit` | Long-keyword treatment per §Long-keyword treatment |
| Badge cluster | up to 3 × 16px slots right-aligned | `⚠ 🌐 🤖` (any subset) | Right-anchored; click any badge → action per badge spec |
| Redaction marker | inline-trailing 80px | `[● REDACTED N]` magenta / `[● ELIDED N]` yellow | Only when event arg-map carries `:rf/redacted` or `:rf/large` |

### Gutter glyphs

| Glyph | Meaning |
|---|---|
| `●` | Normal event (default); secondary colour |
| `◉` | Currently focused (the spine's `:dispatch-id`); cyan border |
| `x` | Errored event (replaces `●` when row also carries `⚠` badge) |
| `▥` | Whole-event redacted; magenta |
| `↺` | Pinned cascade (modifier; rendered as `●↺` / `◉↺` overlay) |

### Row badges

| Badge | Meaning | Click action | Hover tooltip |
|---|---|---|---|
| `⚠` | Exception during handler exec | Pivots L3 → Issues tab + selects this row's issue | Error message (60 chars + `…`) |
| `🌐` | Managed-HTTP related | Pivots L3 → Event tab + scrolls to "fx handlers that ran" | `<METHOD> <url> → <status>` or `pending` |
| `🤖` | State-machine related | Pivots L3 → Machines tab + filters to this event's transitions | First `from→to` + count of all |

Badge order is fixed: `⚠` first (highest signal), `🌐` second, `🤖`
third.

### Hover tooltip — the home of dropped detail

Every row carries a 400ms-delayed hover tooltip that discloses what
the single-line row drops: timestamp, cascade sequence number,
duration, tier, source coord, arg-map preview (via `inspect-inline`).
See [`018-Event-Spine.md`](./018-Event-Spine.md) §4 for the full
tooltip wireframe.

## Runtime Machines panel shape (post-rf2-y9xmf)

The L4 content of the **Machines** tab is **event-driven only**. The
panel never carries exploratory chrome (no picker, no Mode A/B/C
selector, no sub-strip, no arc / scrubber). It renders one of three
shapes based on the focused event's machine activity:

```
┌─ Machine inspector ──────────────────────────────  [Prev][Next] [⤴ Share] ─┐
│                                                                            │
│ — case A: no machines registered —                                         │
│   "No machines registered." (Prev/Next + Share hidden)                     │
│                                                                            │
│ — case B: machines registered, focused event has NO transitions —          │
│   "No machine activity in the focused event."  (Prev/Next hidden)          │
│                                                                            │
│ — case C: focused event triggered ≥1 transitions —                         │
│ ┌─────────────────────────────────────────────────────────────────────┐    │
│ │ :auth/login   :idle → :authing                       [:auth/submit] │    │
│ │ ┌──────────────────────────────────────────────────────────────────┐│    │
│ │ │   [chart canvas] FROM-dashed → TO-bold;   :after rings overlay  ││    │
│ │ └──────────────────────────────────────────────────────────────────┘│    │
│ │ Guards    ✓ :session-fresh?                                          │    │
│ │ Actions   ✓ :clear-form                                              │    │
│ │ Cancellation cascade (when present, inline)                          │    │
│ └─────────────────────────────────────────────────────────────────────┘    │
│ … one section per transitioned machine, document order …                   │
└────────────────────────────────────────────────────────────────────────────┘
```

**Header affordances** (right-aligned, hidden in cases A/B):

- **Prev / Next**: walk the spine's epoch history to the prior/next
  epoch whose cascade ALSO touched the **focused machine** (the head
  section's `machine-id`).
- **Share**: opens the share modal; encodes the focus + selected-tab +
  scrubber-position into a URL. The legacy `:mode` slot (forced Mode
  A/B/C) is no longer emitted; legacy URLs carrying it are silently
  dropped on restore.

**The Sim engine + browse-all index have no Runtime UI.**
Sibling bead rf2-r4nao landed the Sim toggle / side-rail + the
browse-all entry point under the Static Machines surface; the engine
events / subs are now namespaced `:rf.causa.static.machines/sim-*` (re-
hosted from the historical `:rf.causa/sim-*`) and the view lives at
`tools/causa/src/day8/re_frame2_causa/static/machines/sim.cljs`.
Programmatic callers drive Sim against `:rf/causa` via that ns.

### Interactive Machines canvas (rf2-y3l8z)

Every per-machine section in the Runtime Machines panel wraps its
chart in an interactive viewport adapter (`panels/machine_canvas.cljs`
→ `chart/controls.cljc`). The chart is no longer a static SVG paint;
it pans, zooms, and fits to viewport.

```
┌─ :auth/login   :idle → :authing                          [:auth/submit] ─┐
│ ┌─[Canvas|List]──────────────────────────────────[− 100% +] [Fit][Reset]┐│
│ │ · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · ││
│ │ · · ▢ idle ──→ ▣ authing · · · · · · · · · · · · · · · · · · · · · · ││
│ │ · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · ││
│ │ · · · · · · · ◔ :after rings track node centres under zoom + pan · · ││
│ └─────────────────────────────────────────────────────────────────────┘│
│ Guards   ✓ :session-fresh?                                              │
│ Actions  ✓ :clear-form                                                  │
└─────────────────────────────────────────────────────────────────────────┘
```

**Toolbar (top-right of every canvas):**

- `−` / `+` — Zoom out / Zoom in about the viewport centre.
- `NN%` chip — current zoom level, integer percentage.
- `Fit` — fits the laid-out content into the viewport with padding
  on all sides; centred. (Toolbar emits `:fit-request` which the
  Causa-side handler expands to a full `:fit` using the measured
  viewport dims + the content dims from `chart/layout`.)
- `Reset` — zoom 100%, pan (0, 0).

**View-mode toggle (top-left of every canvas):**

A two-button pill — **Canvas** | **List**. Per-machine slot,
persisted to localStorage under
`causa.machine-canvas.view-mode-by-id`. Canvas is the default; List
opt-out renders the section without the chart (guards + actions
only) for users who want a leaner per-event read or who prefer the
old text-first surface.

**Direct manipulation:**

- **Mouse wheel** zooms toward the cursor (wheel-toward-cursor
  invariant — the chart-world coord under the cursor is fixed
  through the zoom transition). Trackpad pinch arrives as a wheel
  event with `ctrlKey=true`; both are accepted.
- **Click-drag** anywhere on canvas background (not on a state
  node) pans. The drag handler walks up to 5 DOM ancestors looking
  for `data-testid` starting `rf-mv-chart-node-` /
  `rf-mv-chart-edge-`; clicks that hit a node fall through to
  the node's own `:on-click` (state-click event).

**Keyboard shortcuts** (chart canvas must hold focus — `tabIndex=0`
on the canvas host):

| Key | Action |
|---|---|
| `+` / `=`  | Zoom in (about viewport centre) |
| `-` / `_`  | Zoom out (about viewport centre) |
| `0`        | Reset (100%, pan 0,0) |
| `f` / `F`  | Fit to viewport |
| `←` / `→`  | Pan horizontally (20px / press) |
| `↑` / `↓`  | Pan vertically (20px / press) |

**Bounds:** zoom is clamped to `[0.2, 4.0]`. Wheel notches step
×1.1; toolbar +/- and `+`/`-` keys step ×1.2.

**`:after` rings track the canvas (rf2-obp4z).** The countdown-ring
overlay receives the same viewport-transform the chart applies and
wraps its rings group in `translate(tx,ty) scale(s)`. Rings stay
anchored to their bearing-state node centres at every zoom + pan.

**Reduced motion.** Toolbar buttons + the canvas transform ride
Causa's `--rf-causa-motion-scale` seam — no extra wiring here;
motion shrinks to ~0 under `prefers-reduced-motion: reduce` along
with the rest of Causa's surface.

**State (app-db slots under `:rf.causa/machine-canvas`):**

| Slot | Shape | Purpose |
|---|---|---|
| `:viewports {<machine-id> {:scale :tx :ty}}` | per-machine `{:scale s :tx tx :ty ty}` | current viewport |
| `:view-mode-by-id {<machine-id> :canvas|:list}` | per-machine | view-mode toggle; localStorage-persisted |
| `:viewport-dims {<machine-id> {:width :height}}` | per-machine | last-measured viewport box (drives `:fit`, keyboard) |
| `:drag {:machine-id … :dragging? :origin-x :origin-y :origin-viewport}` | transient | mouse-down pan accumulator; cleared on mouseup |

Subscriptions exposed for tools / tests:
`:rf.causa.machine-canvas/viewport-for`,
`:rf.causa.machine-canvas/view-mode-for`,
`:rf.causa.machine-canvas/view-mode-by-id`,
`:rf.causa.machine-canvas/viewport-dims-for`.

## IN/OUT filter pills

Live in the L1 ribbon (NOT a separate L1.5 strip; NOT a sidebar).
Two pill types (colour + glyph encode mode):

```
[+ :auth/* ✎]      ← filter-IN  · green border · `+` glyph    · show ONLY matches
[× :mouse-move ✎]  ← filter-OUT · magenta brd  · `×` glyph    · hide matches
[+]                ← trailing add-pill         · click → popup w/ blank pattern
```

AND across modes; OR within mode. `(match-any-IN) AND NOT
(match-any-OUT)`. localStorage persists per host app. Full edit-popup
contract + Recommended-filters quick-add + right-click context menu in
[`018-Event-Spine.md`](./018-Event-Spine.md) §7.

## Settings popup (modal overlay)

**Trigger:** `,` key OR `s` key OR click ribbon `⚙` icon.

**Shape: modal overlay** (NOT a dedicated panel). Centred floating
panel at 560×640; backdrop dim (15% black) but Causa visible
underneath. Closes on `Esc`, click outside, or click `✕`. Settings
persist immediately on change (no Apply/Cancel — every toggle writes
through to `(causa-config/configure! …)` on commit).

Six inner tabs (top tab strip, body below) — Mike 2026-05-19 §0ter.4
walkthrough locks the list (rf2-ttnst):

| # | Tab | Mnemonic | Content |
|---|---|---|---|
| 1 | **General** | `g` | Text size · Panel width · Panel position · Auto-open-on-error · Density (Cosy / Compact — no Comfy) · Long-keyword threshold · **Power user:** "Show tool frames in picker" toggle (off by default) |
| 2 | **Theme** | `t` | Dark / Light (accent stays fixed violet; per-tab default-expansion knob dropped) |
| 3 | **Filters** | `f` | Active filter pills mirror · auto-filter-UI quick-open |
| 4 | **Keybindings** | `k` | Read-only chord table (every binding the global listener captures) · master "Handle keys?" toggle. v1 is READ-ONLY; rebind UI is the v1.1 follow-on. |
| 5 | **Buffer** | `b` | `:buffer/retained-epochs` · `:trace-buffer/keep` · `:app-db/inspector-collapse-threshold` · "Clear buffer now" button with confirm modal |
| 6 | **Diff** | `d` | Hiccup-diff opt-in `:highlight-fn-ref-changes?` toggle (sub-output diff layout fixed unified; section-grouping threshold fixed defaults — both dropped from the user surface) |

**Inner-tab mnemonics** (g / t / f / k / b / d) — bare-letter
keystrokes captured at the dialog level while the modal is open.
The dialog's `on-key-down` stops propagation on every consumed key,
and the global keydown listener gates spine bindings on a
`target-inside-modal?` check (a `data-rf-causa-mode="settings"`
closest-walk), so the inner mnemonics do not also drive the outer
spine. Mnemonics are suppressed while the focused element is an
`<input>` / `<textarea>` / `<select>` / contenteditable surface so
typing into numeric knobs is not interrupted.

**Dropped from earlier drafts** (per the same 2026-05-19 walkthrough):

| Dropped | Rationale |
|---|---|
| Actions tab + factory-reset BIG RED BUTTON | Factory-reset stays code-only (`config/reset-settings!`) — a destructive UI button has no use case the confirm modal beneath "Clear buffer" does not already cover. |
| Density Comfy tier | Two tiers cover the rhythm need; the third was a styling-pass aspiration with no observed demand. |
| Per-tab default expansion (`:bookish` / `:dense`) | Each tab owns its expansion default; no global knob. |
| Accent-violet user swap | Brand accent stays fixed; light/dark theme is the only colour axis. |
| Sub-output diff layout (`:unified` / `:split` toggle) | Fixed unified. |
| Section-grouping threshold | Fixed defaults; not user-tuneable. |
| Popout as its own tab | Folds into General's Panel-position sub-section. |

Full wireframe + per-field configure! mapping in
[`018-Event-Spine.md`](./018-Event-Spine.md) §9. configure! API
surface in [`015-Configuration.md`](./015-Configuration.md).

## Frame-observation isolation invariants

Causa observes ANOTHER frame, NEVER itself. Four invariants
(enumerated in [`018-Event-Spine.md`](./018-Event-Spine.md) §8):

- **I1:** Frame picker excludes `:rf/causa` by default. Settings →
  View → Power user → "Show tool frames in picker" reveals it.
- **I2:** No Causa UI view reads from `:rf/causa` for data purposes.
  Dev-time lint asserts this on Causa mount.
- **I3:** Views panel render-attribution is scoped to selected frame
  ONLY. Render tracker tags each entry with `:owning-frame`.
- **I4:** Browser feature test asserts Causa-self-observation is
  disallowed. **Failure blocks merge.**

The test gate lives at
`tools/causa/test/day8/re_frame2_causa/isolation_test.cljs`. Runs
under `npm run test:browser`.

## Density slider

Two settings: **compact** / **cosy**. Default **cosy**. Density is a
vertical-rhythm knob, not a redesign — applies to L2 row height + L4
row padding + base type token. The third `:comfy` tier was dropped
per [`015-Configuration.md`](015-Configuration.md) §Density (two tiers
cover the rhythm need; the third had no observed demand).

| Setting | L2 row height | L4 vertical rhythm | Body type |
|---|---|---|---|
| **Compact** | 22px | tighter | -1px |
| **Cosy** (default) | 28px | (baseline) | (baseline) |

What does *not* change between densities: icon weights, border radii,
animation durations, accent colours. Configurable in Settings → View.

## Typography

Three typefaces — two body workhorses + one display face:

- **UI sans:** `Inter` (variable, wght 400–700), fallback
  `system-ui` / `-apple-system` / `Segoe UI`. ~80KB WOFF2.
- **Data mono:** `JetBrains Mono` (variable, wght 400–700), fallback
  `ui-monospace` / `SF Mono` / `Menlo`. ~100KB WOFF2.
- **Display serif:** `Fraunces` (variable, wght 500–900 with
  optical-size axis 9–144; rf2-5kfxe.9), fallback `ui-serif` /
  `Georgia` / `Cambria` / `Times`. ~30KB WOFF2. Used on **L4 panel
  `<h1>`** only — panel titles reach for a characterful serif so the
  L4/L3 hierarchy reads at a glance. Deliberately *not* another
  grotesque sans — the frontend-design rubric flags "Inter at every
  size" as a generic AI-aesthetic; one serif accent breaks the
  monotone. Body chrome stays Inter; L1 ribbon labels + chord
  callouts + the mode pill stay Inter too (Fraunces is scoped to L4
  panel headings).

All three faces ship as `local()`-only `@font-face` rules from
`theme/global-styles/font-faces-css`. No third-party HTTP fetch is
initiated; OS-installed copies resolve automatically, otherwise the
per-stack fallback chain (`tokens/sans-stack` / `tokens/mono-stack`
/ `tokens/display-stack`) takes over. Consuming projects that want
web-hosted copies inject their own `url()`-bearing `@font-face`
rules — CSS layers candidates by family + weight so host-side
declarations compose with the `local()` defaults.

### Sizes — one knob, whole scale (`--rf-causa-font-size`)

Every type-scale entry resolves through the **`--rf-causa-font-size`**
CSS custom property (rf2-n8i2c). The default value is `13px`,
published on `:root` by `theme/global-styles/motion-css`. Each entry
is `calc(var(--rf-causa-font-size, 13px) * <multiplier>)` where the
multiplier expresses the entry's RELATIVE size — `:body` is the 1.0
anchor; other entries scale around it. Modelled on TanStack Query
Devtools' `--tsqd-font-size` knob: one variable rescales the entire
shell on the next style flush without a re-render.

| Token | Multiplier | Resolves at default | Used for |
|---|---|---|---|
| Display | 1.077× | ~14px | Tab titles, modal headers, panel `<h1>` |
| Body | 1.000× | 13px | Default UI text (the anchor) |
| Body-tight | 0.923× | ~12px | Sidebar entries, header chrome |
| Mono body | 0.923× | ~12px | Code, EDN, event-list rows |
| Caption | 0.846× | ~11px | Hints, secondary labels, hover tooltips |
| Micro | 0.769× | ~10px | Badges, tabs |

Multipliers are catalogued in
`tools/causa/src/day8/re_frame2_causa/theme/tokens.cljc`
(`type-scale-multipliers`) as pure data so the JVM test surface can
assert the relationship without parsing CSS. Below 10px: refused
(the `:micro` token sits at the floor).

#### Host override + density coupling

Hosts override the knob via a `:root` stylesheet rule —
`:root { --rf-causa-font-size: 14px }` rescales every typographic
surface ~1.08× without a code change. The default `13px` is also
published as the host-readable knob (`API.md` §CSS variables).

The Settings → General **Density** radio is the in-shell consumer
of the same var (rf2-i40us). The mapping lives in
`settings/effects.cljs §density->font-size-px`:

| Density | `--rf-causa-font-size` value | Notes |
|---|---|---|
| **Compact** | 12px | One step tighter than baseline |
| **Cosy** (default) | 13px | Anchor; matches `tokens/font-size-default` |
| **Comfy** | 14px | Catalogued for forward compat — radio surfaces only Compact / Cosy in v1 (Mike 2026-05-19) |

`effects/apply-density-font-size!` is the canonical writer. On every
density change it writes the resolved px value into
`--rf-causa-font-size` on **both** the Causa shell root (so inline
`calc(var(--rf-causa-font-size, 13px) * N)` resolutions inherit the
value) **and** `<html>` (so popout / fullscreen mounts that may not
be inside the inline shell root still inherit). The writer is
idempotent and a no-op when neither element is present (JVM test
runner). The same writer runs on boot from `apply-all!` so a
persisted density survives reload before first paint. Unknown
density keywords (e.g. a persisted `:comfy` payload from before the
v1 radio drop) coerce to `:cosy` — mirroring the `:rf.causa/density`
sub's normalisation.

This var is **distinct** from `--rf-causa-text-size`, the Settings
→ General Text-size slider's user-knob (rf2-9poxq, predates
rf2-n8i2c). Two CSS vars, two knobs, one shell — see
[`016-Auxiliary-Panels.md`](./016-Auxiliary-Panels.md) §Settings
popup for the disambiguation. The density radio writes
`--rf-causa-font-size`; the text-size slider writes
`--rf-causa-text-size`. Hosts that want a single density knob
target `--rf-causa-font-size` and leave the slider's var alone.

## Long-keyword treatment

Smart middle-elide + namespace fade + click-to-copy:

```
BEFORE:  :some.namespace.views.something/blah-blah-blah         (38 chars; overflows 560px)
AFTER:   :some.namespace…/blah-blah-blah  ⎘                     (with hover-copy icon)
         ^^^^^^^^^^^^^^^^                ^^^^^^^^^^^^^^^^^^
         text-tertiary 400                accent-violet 600
         (keep first ns segment; elide middle; keep keyword name)
```

Algorithm: when event-id exceeds N chars (compact 28; cosy 36;
configurable via Settings → View → Long-keyword threshold), elide
the middle of the NAMESPACE only. Keep first ns segment and the
keyword name (after `/`) intact. Un-namespaced keywords fall back to
tail-elide.

Helper lives in
`tools/causa/src/day8/re_frame2_causa/theme/keyword_render.cljs`.
Every long-keyword consumer reads from it: event-list rows, tab strip
empty-state placeholder, ribbon filter pills, Cmd-K palette recents,
classification rendering.

Hover behaviour: 200ms-delayed `title` tooltip discloses full keyword.
Click `⎘` icon copies full keyword to clipboard.

## Colour system

Dark theme default; light theme ships at v1.0; high-contrast variant
at v1.1. All WCAG AA on text-against-background; AAA on
high-contrast.

### Dark theme tokens

```
Surfaces:  bg-0 #0E0F12  (backdrop)
           bg-1 #15171B  (sidebar, top strip)
           bg-2 #1B1E24  (panels)
           bg-3 #232730  (popovers)
           bg-active #2A2F3D  (hover, selected)

Borders:   subtle #232730  · default #2F3441  · strong #444B5B

Text:      primary #E8EAF0  · secondary #A8AEC0  · tertiary #6B7080  · disabled #494E5A

Accents:   violet  #7C5CFF  brand, current epoch
           indigo  #5570FF  :pair-origin
           cyan    #43C3D0  :story / :test origin, info
           green   #4ADE80  success, additions, machine-active
           yellow  #FBBF24  warnings, schema-replaced-with-default, :rf/large elision
           orange  #FB923C  long-task
           red     #F87171  errors, schema-violations, hydration-mismatches
           magenta #E879F9  classification: :rf/redacted

Perf:      fast     #4ADE80  (<16ms)
           medium   #FBBF24  (16-50)
           slow     #FB923C  (50-100)
           blocking #F87171  (>100ms, INP threshold)
```

Light theme inverts lightness (`bg-0 #FAFBFC`, `bg-1 #F1F3F6`, `bg-2
#FFFFFF`); accents darken slightly to maintain contrast.

### Colour is never alone

Every coloured marker pairs with a shape or icon:

- Errors → red dot + `!` icon + "Error" label.
- Schema violations → yellow triangle + path.
- Pair-origin → indigo + `🔗`.
- Active machine → green + filled glyph; idle → hollow.
- Redaction → magenta + `[● REDACTED N]` literal.
- Elision → yellow + `[● ELIDED N]` literal.

### Surface texture (grain) (rf2-5kfxe.7)

The shell root paints a soft atmospheric grain under the L1–L4
chrome. The grain is a `data:image/svg+xml`-encoded `feTurbulence`
filter (200×200 tile; `baseFrequency 0.85`, `numOctaves 2`,
`stitchTiles=stitch` for seamless repeat) rendered as a `::before`
pseudo-element on `[data-testid="rf-causa-shell"]`. Opacity `0.035`;
`mix-blend-mode: overlay` lets the grain blend additively against
both dark and light theme backgrounds. Zero extra DOM nodes — the
pseudo-element approach keeps the grain off the React tree entirely
and out of every panel's render graph. Under dark theme it reads as
a soft film grain over the recessed canvas; under light theme it
manifests as a subtle paper grain over the white canvas. The CSS
lives in `theme/global-styles/grain-css`; injection is via a single
`<style id="rf-causa-grain">` block, idempotent + id-keyed DOM
probe.

### Per-L4 panel accent stripe (rf2-5kfxe.8)

Every L4 panel renders a **3-px left-border** on its `<h1>` in its
domain colour so panels are distinguishable at a glance without
restructuring the header chrome. The mapping
(`theme/tokens/panel-domain->token`) is fixed:

| L4 tab | Domain colour | Token |
|---|---|---|
| `:event` | violet | `:accent-violet` (causal-chain accent everywhere in the Event lens) |
| `:app-db` | cyan | `:cyan` (App-db diff already highlights state in cyan) |
| `:views` | cyan | `:cyan` (Views is a peer of App-db; both read state) |
| `:trace` | orange | `:orange` (events in flight; the firing/heat tone) |
| `:machines` | green | `:green` (machine state lands green for "final") |
| `:routing` | yellow | `:yellow` (side-channel attention tone) |
| `:issues` | red | `:red` (errors; semantic red) |
| `:chrome-a11y` | red | `:red` (diagnostics group sibling to `:issues`; both surface "what's wrong here?" content — rf2-5r2yj) |

The helper `theme/tokens/accent-stripe-style` emits the inline-style
map (`:border-left "3px solid <hex>"` + `:padding-left "10px"`); per-
panel call sites merge it into the `<h1>` `:style`. Unknown tab
keywords fall back to `:accent-violet` so the stripe always renders.
This is independent of — and complementary to — the Static-mode 2-px
ribbon-edge stripe documented in §Static mode below (that one is a
mode signal at L1; this one is a panel-domain signal at L4).

### Cascade gutter (rf2-5kfxe + the diff renderer)

The App-db diff renderer (`diff/render.cljs`) and the `inspect-diff`
mode of the detail-panel renderer both ship a **per-node gutter**: a
3-px coloured left-border + glyph that telegraphs the operation at a
glance.

| Op | Glyph | Tone | Token |
|---|---|---|---|
| Added | `+` | green | `:green` |
| Removed | `-` | red | `:red` |
| Modified | `~` | yellow | `:yellow` |
| Children (recursive descent) | `◴` | violet | `:accent-violet` |
| Same (rendered for context) | (space) | tertiary | `:text-tertiary` |

The gutter is a single shared idiom across the App-db diff, the
sub-output diff, and any nested `inspect-diff` consumer. The
glyph + colour combination satisfies the "colour is never alone"
discipline above — the gutter glyph alone is enough to read the op
without any colour.

## Spacing scale

4px grid. Everything is a multiple.

| Token | Pixels | Used for |
|---|---|---|
| `space-0` | 0 | Collapsed |
| `space-1` | 4 | Badge-to-text gap |
| `space-2` | 8 | Default inline gap, button padding |
| `space-3` | 12 | Section spacing |
| `space-4` | 16 | Panel padding |
| `space-5` | 24 | Between sections |
| `space-6` | 32 | Panel-level separators |
| `space-8` | 48 | Rare; modal margins |

Border-radius: `radius-sm` 4px (buttons, chips); `radius-md` 8px
(panels, popovers); `radius-lg` 12px (modals).

## Iconography

Single icon set: **Lucide** (open-source, ~1000 icons). 1.5px stroke.
Sizes 14 / 16 / 20px (inline / tab / modal-header). 100ms hover fade
to context accent; no size change on hover.

Causa-specific custom glyphs: `◆` cascade root · `●` filled node · `○`
hollow node · `◉` selected node · `↺` rewind · `▥` whole-event
redacted · `⚠` exception badge · `🌐` HTTP badge · `🤖` machine badge.

## Motion + animation

Animation communicates, not decorates. Three durations:

| Tier | Range | Used for |
|---|---|---|
| **Quick** | 100ms | Hover, focus rings |
| **Standard** | 200–250ms | Tab switches, scrubber drag-snap, popover open/close |
| **Slow** | 400–600ms | Diff flashes, error pulses, the 320ms Causa slide-in |

Specific motions:

- **Tab cross-fade** (`@keyframes rf-causa-fade-in`, rf2-5kfxe.3):
  180ms ease-out, opacity 0 → 1 with a 2px translateY (the new tab
  rises *into* place rather than appearing statically). Subtle
  enough to feel like a settle, not a slide; characterful enough to
  read as a beat rather than a hard cut. Triggered by `^{:key
  selected}` on the L4 case-switch wrapper so a tab swap unmounts +
  remounts → keyframes auto-play from frame 0. Animation lives in
  `theme/global-styles/motion-css`.
- **Diff flash** (`@keyframes rf-causa-diff-flash`, rf2-5kfxe.2):
  400ms ease-out wash on each touched App-db slice when a new epoch
  lands. Yellow tint at ~20% alpha (`rgba(251, 191, 36, 0.20)` —
  `:yellow` token at hex32 20%) holds for the first 12% of the run
  so the eye locks on, then eases to transparent. `animation-fill-
  mode: forwards` on the section element pins the end state. The
  hold-then-fade shape is sharp enough to catch the eye on quick
  cascades but muted enough that a long burst of consecutive
  cascades doesn't strobe.
- Error pulse: single 600ms expand-fade red ring (no looping).
- Machine-active state: 1.2s gentle scale 1.0 → 1.05 → 1.0 (only
  continuous animation in chrome, only on the machine chart).
- L2 head-row LIVE pulse: 2s gentle 600ms expand-fade on the head
  row's `●` gutter glyph (continuous while LIVE; stops in RETRO).
  Replaces the dropped Mode pill widget as the LIVE/RETRO cue.
- Tab count badge flash: 200ms violet → settle on LIVE update.

### `prefers-reduced-motion`

All durations clamp to 0 except a 1-frame opacity tween where layout
needs to settle. The error pulse becomes a static red ring for 1.5s;
the machine pulse stops entirely; the L2 head-row LIVE pulse stops
(the `●` gutter glyph stays statically rendered). The Mode pill
widget that earlier drafts carried the LIVE pulse on was dropped;
the rule now applies to the spine's head-row cue.

## Keyboard

Every layer is keyboard-reachable. Chrome tab order: ribbon (L1) →
event list (L2) → tab bar (L3) → detail panel (L4 — focus enters the
active panel). `Esc` always returns focus to the event list.

### Global shortcuts

| Key | Action |
|---|---|
| `Ctrl+Shift+C` | Toggle Causa visibility |
| `?` | Keyboard cheat-sheet |
| `,` or `s` | Settings popup |
| `Esc` | Close modal / collapse popover / focus event list |
| `Ctrl+K` / `Cmd-K` | Command palette |
| `Ctrl+F` | Find within active tab |
| `o` | Popout (`window.open` whole shell) |

### Ribbon nav cluster

| Key | Action |
|---|---|
| `j` | Back one event (= `◀`) |
| `k` | Forward one event (= `▶`) |
| `G` | Fast-forward to latest (= `⏭`, snap LIVE) |
| `Space` | Pause/resume LIVE feed |
| `L` | Snap to LIVE (jump to head) |

### Event list (L2)

| Key | Action |
|---|---|
| `j` / `k` | Next / previous (alias of ribbon nav) |
| `J` / `K` | Cascade-root skip |
| `g g` / `G` | Top / bottom |
| `Enter` | Activate (= click row) |
| `[` / `]` | Previous / next (10x parity = `j`/`k`) |
| `*` | Pin a cascade (session-scoped) |
| `r` | Rewind to before this event (calls `restore-epoch`) |
| `R` | Re-dispatch this event |
| `o` | Open source in editor |
| `/` | Focus filter add-pill |
| `Ctrl+click` | Copy cascade-id |

### Tab bar (L3)

| Key | Tab |
|---|---|
| `1` | Event |
| `2` | App-db |
| `3` | Views |
| `4` | Trace |
| `5` | Machines |
| `6` | Issues |
| `e` | Event (mnemonic) |
| `a` | App-db (mnemonic) |
| `v` | Views (mnemonic — incl. subs nested under each view) |
| `t` | Trace (mnemonic) |
| `m` | Machines (mnemonic) |
| `i` | Issues (mnemonic) |
| `Ctrl+→` / `Ctrl+←` | Next / previous tab |

### Detail panel (L4)

| Key | Action |
|---|---|
| `Tab` / `Shift+Tab` | Cycle focusables |
| `Esc` | Return focus to event list |

### Machines canvas (when chart canvas holds focus — rf2-y3l8z)

| Key | Action |
|---|---|
| `+` / `=` | Zoom in (about viewport centre) |
| `-` / `_` | Zoom out (about viewport centre) |
| `0` | Reset to 100%, pan 0,0 |
| `f` / `F` | Fit to viewport |
| `←` `→` `↑` `↓` | Pan 20px / press |

The canvas shortcuts only fire while the canvas host (`tabIndex=0`)
is the active focus target — they are scoped to a clicked-into chart
and do not collide with the global Causa keymap. Wheel-zoom +
click-drag pan have no keyboard equivalent.

### Retired keys (from pre-rewrite spec)

- `f` (Effects) — Effects tab folded into Event; `f` retired.
- `s` (Subscriptions) — Subs panel folded into Views; `s` repurposed
  to open Settings popup.
- `c` (Causality) — Causality surface dropped entirely (rf2-y0z5b);
  `c` unused.
- `p` (Performance) — Performance panel dropped; `p` unused.
- `w` (Flows) — Flows folded into Views; `w` unused.
- `S` (Schemas) — schema violations live in Issues; `S` unused.
- `h` (Hydration) — hydration mismatches live in Issues; `h` unused.

## Detail panel renderer

Every value display in every tab's L4 detail panel uses
`tools/causa/src/day8/re_frame2_causa/theme/data_inspector.cljc`:

- `inspect <value>` — the hero: expandable inspector. Maps `{ … }`
  colourful, vecs `[ … ]`, sets `#{ … }`, lists `( … )`. Keys violet,
  strings orange, numbers green, keywords cyan, booleans yellow, nil
  tertiary italic. Expand carets per node; default-collapse based on
  size.
- `inspect-inline <value>` — one-line variant; identical palette;
  forced single line; tail-elides at 80 chars.
- `inspect-diff <before> <after>` — diff variant; side-by-side or
  unified per `:layout`; colour-coded add/remove inline.

**Does NOT depend on `binaryage/cljs-devtools`.** That library targets
the Chrome console (formatters API); its output is not in-page hiccup.
Hand-built renderer matching the aesthetic using Causa's theme tokens.

### Renderer contract (v1 ships)

The cljs-devtools-shaped surface (rf2-x9fzk):

| Knob | Default | Purpose |
|---|---|---|
| `collapse-threshold` | `5` | Collections longer than this start collapsed; the user clicks `▶` to expand. Map literals ≤ 5 keys render flat; typical app-db slices don't dump every key on initial render. |
| `string-inline-cap` | `64` | Strings longer than this tail-ellide in `inspect-inline`; the full value remains visible via the parent collection's expand affordance. |
| `large-fetch-warn-threshold-bytes` | `100000` (100 KB) | Per [`018-Event-Spine.md`](./018-Event-Spine.md) §12 — `:rf/large` expansions above this size gate behind a confirm step so a stray click can't pour a multi-megabyte expansion into the detail panel. |

**Colour palette** (mapped onto Causa's theme tokens so the renderer
reads as native shell chrome): keywords violet (`:accent-violet`),
strings green, numbers cyan, nil tertiary, booleans orange, symbols
magenta, default `text-primary`. Punctuation + meta render in
`text-tertiary` / `text-secondary` to recede.

**Substrate-agnostic state.** Per the pure-hiccup contract
([Conventions rf2-tijr](./Conventions.md)) the renderer never
references Reagent / UIx / Helix. Per-node expand state lives in
`:rf/causa` app-db under `[:data-inspector <node-key> …]` and is
read/written via re-frame primitives:

- `:rf.causa.data-inspector/expansion <node-key>` — sub for one node's
  state.
- `:rf.causa.data-inspector/toggle-expanded <node-key>` — flip.
- `:rf.causa.data-inspector/request-large-confirm <node-key>` /
  `:rf.causa.data-inspector/confirm-large <node-key>` — two-step
  confirmation for `:rf/large` sentinels above the size threshold.

Each L4 panel mount supplies a unique `node-key` prefix so two panels
rendered side-by-side don't share expand state. See
[`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md) for the
catalogued ids.

### Sentinel chips

The renderer recognises three `spec/015-Data-Classification` sentinel
shapes and emits bespoke chrome (per [`018-Event-Spine.md`](./018-Event-Spine.md)
§12):

- `:rf/redacted` (bare keyword) — magenta opaque chip
  (`● redacted`); italic small-caps; **never** expandable, no reveal
  affordance ever.
- `{:rf/large {:bytes N :head "…"}}` — yellow chip
  (`● large · N bytes · "head…"`); click reveals an inline expansion
  showing the full `:head` preview. Sizes above
  `large-fetch-warn-threshold-bytes` gate behind an inline confirm
  prompt (textual "Expand N bytes? (>100000 threshold)" + Confirm
  button) rather than a full modal — v1 ships the inline prompt so
  the renderer doesn't drag in modal infrastructure.
- `{:rf/redacted {:bytes N}}` — combined sensitive + large; magenta
  with size shown for diagnostic; **never** expandable (sensitive
  dominates content visibility).

## Data-classification rendering

Per [spec/015-Data-Classification](../../../spec/015-Data-Classification.md):

| Sentinel | Causa renders | Drillable | Affordance |
|---|---|---|---|
| `:rf/redacted` | `[● REDACTED N]` magenta | NO | Hover tooltip discloses path + mark source; **no reveal** |
| `:rf/large {:bytes N :head "…"}` | `[● ELIDED · N bytes]` yellow | YES | Click → popover with `:head` preview + "Fetch full value" button (size-warned via confirm modal when bytes > threshold) |
| `:rf/redacted {:bytes N}` | `[● REDACTED · N bytes]` magenta | NO | Sensitive dominates; size disclosed |

Per-surface enumeration in [`018-Event-Spine.md`](./018-Event-Spine.md)
§12. The magenta and yellow hues MUST NOT collide.

## Editor protocol matrix

The `o` shortcut (and every `open` chip Causa renders next to a
source-coord — event-detail rows, machine inspector chips,
Views per-component rows, Trace rows) sets
`window.location.href` to a URI-scheme handler the OS dispatches to
the user's editor.

### Supported editors

| Editor | Config key | URI template |
|---|---|---|
| VS Code (and forks: code-server, VSCodium) | `:vscode` (default) | `vscode://file/<path>:<line>:<column>` |
| Cursor (distinct scheme — VS Code fork) | `:cursor` | `cursor://file/<path>:<line>:<column>` |
| Windsurf (distinct scheme — VS Code fork) | `:windsurf` | `windsurf://file/<path>:<line>:<column>` |
| Zed | `:zed` | `zed://file/<path>:<line>:<column>` |
| JetBrains family (IDEA, WebStorm, Cursive, PyCharm) | `:idea` | `idea://open?file=<path>&line=<line>&column=<column>` |
| Anything else (Sublime, Emacs server-mode, Vim with a URL handler, Helix) | `{:custom <template>}` | user template with `{path}` / `{file}` / `{line}` / `{column}` placeholders |

### URI construction (normative)

- **Default editor.** When `:rf.causa/editor` is unset or `nil`, the
  builder MUST treat the editor as `:vscode`.
- **`:file` is mandatory.** When the source-coord's `:file` slot is
  absent, blank, or non-string, the builder MUST return `nil` and the
  consumer MUST hide the chip entirely.
- **`:line` and `:column` defaults.** When `:line` is absent the
  builder MUST default to `1`; when `:column` is absent it MUST
  default to `1`.
- **Unknown keyword posture.** Any editor keyword not in
  `#{:vscode :cursor :windsurf :zed :idea}` (and not a `{:custom …}`
  map) MUST fall through to the `:vscode` URI shape.
- **Custom-template substitution.** The `{:custom "<template>"}` form
  MUST substitute `{path}`, `{file}` (alias for `{path}`), `{line}`,
  and `{column}` placeholders verbatim from the source-coord.
- **No URL-encoding of the path.** The path MUST be passed verbatim
  into the URI — slashes stay slashes, colons stay colons.
- **No handler-installed fallback.** When the URI's scheme has no
  registered OS handler, the click MUST be a clean no-op.
- **Click vector.** The chip MUST invoke navigation by setting
  `window.location.href` (or rendering an `<a href>` and letting the
  browser follow it).

The single canonical implementation in
`re-frame.source-coords.editor-uri` MUST be the only URI builder; no
panel may inline its own URI assembly.

### Configuration

- The user picks the editor via the **Settings** popup (`,`) → View.
  Stored under the `:rf.causa/editor` config key.
- The boot-time entry is `(causa-config/configure! {:rf.causa/editor …})` per
  [`015-Configuration.md`](./015-Configuration.md) §`:rf.causa/editor`.
- Default: `:vscode` — the most-installed editor in 2026.
- The preference is **session-scoped**, persisted via the same Causa
  config substrate as theme / density. No cloud-sync.
- Causa's preference is **independent** of Story's `:rf.story/editor`.

### Cross-references

- The shared URI builder lives at
  `implementation/core/src/re_frame/source_coords/editor_uri.cljc`
  (CLJC, JVM + CLJS portable; rf2-evgf5).
- Causa's mirror chip
  (`day8.re-frame2-causa.open-in-editor/open-chip`) consumes the
  same helper — see [`API.md` §Open in editor](./API.md#open-in-editor-rf2-evgf5).
- Story's matching surface — see
  [`tools/story/spec/005-SOTA-Features.md` §"Open in editor" per variant](../../story/spec/005-SOTA-Features.md).
- rf2-evgf5 — the chip implementation bead (Story + Causa).

## Command palette

Centred 560px modal, 50% height. Opened via the `Ctrl+K` / `Cmd-K`
chord (global; also reachable from the top-strip control). Closes
on `Esc`, click-outside, or invocation of any item.

### Indexed sources

- Recent events (200-entry buffer; matches event-id + source coord)
- Registered handlers (id + `:doc`)
- Frames
- Machines with current state
- L4 tab jumps — Runtime: Event / App-db / Views / Trace / Machines
  / Routing / Issues; Static: Machines / Routes / Schemas / Views /
  Events (see §Mode-aware command surface below)
- Command verbs (recents-boosted; see §Command verbs below)
- Settings entries
- Pinned cascades (pin chips live in the palette as a "Pinned
  cascades" source, since the L0 rail is gone)

Fuzzy match splits on camelCase / kebab-case / namespace boundaries.

### Mode-aware command surface (rf2-ybjkx)

Every palette item carries a **`:modes`** set declaring which Causa
modes it surfaces under — `#{:runtime}`, `#{:static}`, or
`#{:runtime :static}` for verbs meaningful in both. The aggregator
(`palette/sources/by-mode-pred`) filters by membership against the
active `:rf.causa/mode`. Items missing `:modes` fall through to
both modes (the legacy contract — every item used to be visible
always).

The L4 tab-jump items are mode-aware so the **same mnemonic
letter** dispatches the active mode's tab. `m` in Runtime jumps to
the Machines instance-inspector; `m` in Static jumps to the
Machines registry browse. The mnemonic chord — `e` (Events) · `m`
(Machines) · `r` (Routes/Routing) · `c` (Schemas — Static only) ·
`v` (Views) — works inside the palette and bare on the spine
because both consult the active mode (see §Static mode for the
mnemonics inventory).

### Command verbs (rf2-ybjkx)

The palette catalogues these verbs as `:command` source items. Six
of them ship post-rf2-ybjkx:

| Command id | Label | Modes | Action |
|---|---|---|---|
| `:toggle-theme` | Toggle theme (dark ↔ light) | `#{:runtime :static}` | Flips the `rf-causa-theme-{dark,light}` class on the shell root. |
| `:cycle-reduced-motion` | Cycle reduced-motion override (OS → always → never) | `#{:runtime :static}` | Three-state cycle: `:os` (OS pref alone) → `:always` (force reduce) → `:never` (force full). User override of `prefers-reduced-motion: reduce`; rides the `--rf-causa-motion-scale` seam in `theme/global-styles/motion-css`. Persists across reloads. |
| `:snapshot-app-db` | Snapshot app-db | `#{:runtime :static}` | Dumps the focused frame's app-db to the JS console + clipboard for sharing. |
| `:jump-to-settings` | Jump to Settings | `#{:runtime :static}` | Equivalent to the `,` / `s` bare-key shortcut; available from the palette so the user can fuzzy-find the gesture without leaving the keyboard. |
| `:toggle-mode` | Toggle mode (Runtime ↔ Static) | `#{:runtime :static}` | Chord parity with `Cmd-Shift-M`; flips `:rf.causa/mode` between `:runtime` and `:static`. |
| `:clear-epoch-history` | Clear epoch history | `#{:runtime}` | Drops Causa's epoch snapshots (Runtime-only — no epoch concept under Static). |

Pre-rf2-ybjkx verbs (clear-trace-buffer, reset-suppressed-counters,
open-popout, …) continue to surface under their original `:modes`
sets. The full catalogue lives in
`tools/causa/src/day8/re_frame2_causa/palette/sources.cljc`
§`command-items`.

### Recents (rf2-ybjkx)

Command invocations bubble through a **top-3 ring** persisted to
localStorage under `re-frame2.causa.palette.recents.v1`. The ring
holds command-ids only (verbs, tab-jumps) — never event-ids,
handler-ids, or any host-app data. Persistence is best-effort:
`palette/recents/save!` swallows quota / availability failures.

Sort behaviour is **position-decayed boost**: the most-recent
command receives `recents-boost-max` (currently sized so the top
recent ranks +50% over a fresh fuzzy peer at parity), the second
receives `recents-boost-max - recents-boost-step`, the third
receives less again. Items beyond the recents tail receive zero
boost. The decay shape keeps the most-recent verb above a fresh
fuzzy peer while letting strong query matches still rise.

The recents slot lives at `:rf.causa.palette/recents` on Causa's
app-db; the persisted vector hydrates on first palette open via
`recents/load`. The reducer (`recents/record`) is pure — `update +
distinct + take 3` — so the slot remains test-friendly.

### Modal + close behaviour

- **Esc** closes the palette unconditionally (no exceptions for
  in-flight fuzzy queries; mirrors the rest of Causa's modal
  surfaces — every modal closes on `Esc`).
- Click outside the 560px modal closes the palette.
- Invoking any item closes the palette as part of the action
  dispatch (the action handler emits `[:palette/close]` after the
  effect).
- The palette is itself catalogued as a closeable verb
  (`:close-palette`) so a keyboard-only user can fuzzy-find "close"
  if Esc is unavailable.

### Reduced-motion override seam (rf2-ybjkx)

The `:cycle-reduced-motion` verb is the user-side override of the
OS `prefers-reduced-motion: reduce` media query. Three states cycle
in order:

| State | Behaviour |
|---|---|
| `:os` (default) | Respect the OS pref alone — `@media (prefers-reduced-motion: reduce)` flips `--rf-causa-motion-scale` to ~0. |
| `:always` | Force reduced motion ON regardless of OS pref. |
| `:never` | Force reduced motion OFF regardless of OS pref. |

The override writes to a Causa-owned class on the shell root that
takes precedence over the OS media query, so the user can opt OUT
of system-level reduce-motion when developing motion-heavy
surfaces (the inverse use case is more common: developers on
default-reduce machines need to preview the full motion). Persists
to localStorage alongside the other Causa settings.

## Modal layers

Three modal surfaces float over the chrome:

1. **Command palette** — 560px centred.
2. **Keyboard cheat-sheet** (`?`) — 480px modal listing every
   shortcut.
3. **Settings** (`,` or `s` or `⚙`) — 560×640px modal with 6 sections.

## Discoverability

Three layers, no onboarding tour:

1. **The `?` cheat-sheet.** Modal showing every shortcut.
2. **Empty-state hints.** Each empty state shows a contextual keyboard
   hint.
3. **The command palette itself.** Typing `?` in the palette filters
   to commands and shows their shortcuts.

## Bundle splitting

Per-tab lazy loading via shadow-cljs's per-output-target slicing:

- Core (UI shell + ribbon + event list + Event tab + App-db tab):
  <1.5 MB minified / <500 KB gzipped.
- Machines tab (includes the ELK+SVG chart primitive that absorbed
  `tools/machines-viz/`): <400 KB extra, lazy-loaded on first open.
- Views tab: <100 KB extra, lazy-loaded on first open.

## Performance budget

**Opening Causa must not change observable INP** on a typical app.

- Trace bus emission overhead: <2µs per emit (per Spec 009).
- Event list live updates: debounced to 16ms (one rAF).
- App-db diff: O(changed paths) via PersistentHashMap pointer-eq.
- Rendering: every panel virtualises long lists; nothing renders >200
  rows at once; the event list virtualises with 20-row overscan.

## Production posture

The launch pill doesn't render in production builds (per Spec 009 §
Production builds — `goog.DEBUG=false` elides the entire surface).
`Ctrl+Shift+C` does nothing. CI verifies via `npm run test:elision`.

In a non-elided dev build running in production-like conditions,
Causa shows a yellow top banner: "Causa is enabled in this build.
Disable for production." Single-click dismiss, remembered for the
session.

## Mountable panel contract (rf2-crhr8)

Every Causa panel is **independently mountable**. The 4-layer shell
COMPOSES panels but does NOT own them — panels are reachable as
stand-alone mount targets so a host can drop one panel into Story
ribbons, the Scittle playground (per rf2-i8mv option-c progressive
disclosure), the docs / guide surface, or custom debugging setups
without bringing along the rest of the shell chrome.

The public mount surface lives in
`day8.re-frame2-causa.panels` — one mount fn per public panel, plus
a master `mount-shell!` for the full 4-layer chrome.

### Mountable surface inventory

The Causa panel-surface inventory totals **13 panels** across four
tiers — **11 are independently mountable** as standalone user-facing
mount targets, and **2 are internal sub-components** that render
under their owning panel. The 4-tier split is:

- **Tier 1 — L3 tab panels (7):** one per L3 detail-panel tab.
- **Tier 2 — overlay / popup surfaces (3):** modal-light surfaces
  the shell composes at its root.
- **Tier 3 — inline content surface (1):** the managed-fx
  wire-boundary diff template embedded in the Event tab.
- **Tier 4 — internal sub-components (2):** auxiliary inspectors
  geometry-coupled to `machine-inspector/Panel` (after-rings
  overlay, sim side-rail).

Tiers 1 + 2 + 3 sum to the **11 independently mountable** surfaces;
adding Tier 4's 2 internal sub-components reaches the **13-panel**
total. Modal overlays managed by the shell (Settings dialog,
command palette, share modal) are NOT counted here — they are shell
chrome, not panel content.

**Tier 1 — L3 tab panels (7):** one per `:rf.causa/selected-tab`
value.

| Panel | View | Mount fn |
|---|---|---|
| Event tab    | `event-detail/Panel`     | `mount-event-detail!` |
| App-db tab   | `app-db-diff/Panel`      | `mount-app-db-diff!` |
| Views tab    | `views/Panel`            | `mount-views!` |
| Trace tab    | `trace/Panel`            | `mount-trace!` |
| Machines tab | `machine-inspector/Panel`| `mount-machine-inspector!` |
| Routing tab  | `routing/Panel`          | `mount-routing!` |
| Issues tab   | `issues-ribbon/Panel`    | `mount-issues-ribbon!` |

**Tier 2 — overlay / popup surfaces (3):** modal-light surfaces the
shell composes at its root, each self-gating on a `:rf.causa/*-open?`
sub (closed-state cost is one subscribe + a `when` short-circuit).

| Panel | View | Mount fn |
|---|---|---|
| App-DB segment-inspector popup | `app-db-segment-inspector/Popup`   | `mount-segment-inspector!` |
| Cancellation-cascade side-panel | `cancellation-cascade/SidePanel`  | `mount-cancellation-cascade-side-panel!` |
| Cancellation-cascade popover    | `cancellation-cascade/Popover`    | `mount-cancellation-cascade-popover!` |

**Tier 3 — inline content surface (1):** the managed-fx
wire-boundary diff template that the Event tab embeds inline under
its six-domino cascade view. Exposed standalone for Story ribbons
that want JUST the managed-fx list for the focused cascade.

| Panel | View | Mount fn |
|---|---|---|
| Managed-fx records list | `panels/ManagedFxList` | `mount-managed-fx!` |

**Tier 4 — internal sub-components:** auxiliary inspectors that
depend on `machine-inspector/Panel`'s positioned graph for their
geometry — overlays anchor on chart node centres, side-rails run
along the chart edge.

| Sub-component | View |
|---|---|
| After-rings overlay | `machine-after-rings/AfterRingsOverlay` |
| Sim side-rail       | `static.machines.sim/SimRail` |

These render under `machine-inspector/Panel` and are NOT exposed as
standalone mount fns. Mounting a ring overlay without a chart
underneath is geometrically meaningless; they remain reachable via
`mount-machine-inspector!`. (Per rf2-y9xmf the prior arc / cluster /
scrubber sub-components were collapsed into the Runtime panel; the
remaining sub-component surface is the two listed above.)

### The mount-fn contract

Every `mount-<panel>!` fn:

1. Calls `(registry/register-causa-handlers!)` — idempotent install
   of every panel's subs / events / fxs. The orchestrator's
   `defonce`-guarded sentinel collapses repeat installs across
   panel mounts and shadow-cljs `:after-load` cycles.
2. Calls `(rf/reg-frame :rf/causa {})` — idempotent register of
   Causa's state-isolation frame. `reg-frame`'s surgical-update-on-
   re-register semantics (per Spec 002 §reg-frame) keep this
   idempotent.
3. Wraps the panel's view in `[rf/frame-provider {:frame :rf/causa}
   [Panel]]` so descendant `subscribe` / `dispatch` re-anchor to
   `:rf/causa` regardless of the host's React-context. The
   `:rf/causa` default may be overridden via `opts {:frame
   :my-app/frame}` per the embedding contract
   ([008-Embedding-Contract.md](./008-Embedding-Contract.md) §State
   isolation).
4. Delegates to `substrate-adapter/render` with the wrapped tree +
   `mount-point`. Causa is substrate-agnostic; the host installs
   the adapter via `rf/init!` and the panels mount via that
   adapter's render slot.
5. Returns the substrate adapter's unmount fn so the host owns the
   panel's teardown lifecycle.

### Per-panel input axes (the coupling-map audit)

Every panel reads its data via subscribes — no sibling-render
assumptions, no shell-owned local state. The subs (registered by the
panel's own `install!`) compose against the trace bus + epoch
history + spine focus:

| Panel | Reads (subs) | Writes (dispatches) |
|---|---|---|
| **event-detail**   | `:rf.causa/focus` · `:rf.causa/cascades` · `:rf.causa/target-frame-db` | `:rf.causa/focus-cascade` · `:rf.causa/focus-event` |
| **app-db-diff**    | `:rf.causa/app-db-diff` (composite) | `:rf.causa/focus-slice-path` · `:rf.causa/open-segment-inspector` |
| **views**          | `:rf.causa/views-focused-cascade-pair` · `:rf.causa/views-sub-diff` | view-row toggles · sub-diff selection |
| **trace**          | `:rf.causa/trace-feed` (incremental projection) | `:rf.causa/select-dispatch-id` · `:rf.causa/open-in-editor` |
| **machine-inspector** | `:rf.causa/machine-chart-data` · `:rf.causa/active-timers-for-focused-machine` · `:rf.causa/machine-scrubber-position` | scrubber events · `:rf.causa/focus-cascade` |
| **routing**        | `:rf.causa/registered-routes` · `:rf.causa/current-route-slice` · `:rf.causa/routing-tab-data` | route-simulation events |
| **issues-ribbon**  | `:rf.causa/issues-ribbon` (composite) · `:rf.causa.issues/ungrouped` | `:rf.causa.issues/toggle-severity` · `:rf.causa.issues/toggle-prefix` · `:rf.causa/select-dispatch-id` |
| **segment-inspector** | `:rf.causa/segment-inspector-open?` · `:rf.causa/segment-inspector-value` | `:rf.causa/close-segment-inspector` |
| **cancellation-cascade** | `:rf.causa/cancellation-cascade-for-focused-machine` · `:rf.causa/cancellation-cascade-for-focused-event` · `:rf.causa/cancellation-cascade-popover-open?` · `:rf.causa/modal-positioning` | `:rf.causa/cancellation-cascade-close` |
| **managed-fx**     | `:rf.causa/managed-fx-for-focused-event` | `:rf.causa/focus-event` |

No panel reads sibling-panel state directly. No panel assumes any
particular frame-picker / tab-bar / event-list / spine-head value
beyond what the spine sub `:rf.causa/focus` exposes — and `focus`
itself defaults to head of the trace buffer when no row is selected.
Each panel is fully driven by the trace bus + the host's
`(rf/init!)` plumbing.

### Shell composes, doesn't own

The 4-layer shell (`shell.cljs`) **composes** panels by referencing
each panel's `Panel` reg-view in the L4 detail-panel case-switch,
and mounts the Tier 2 overlay surfaces at the shell-view root for
modal layering. The shell does NOT own per-panel state — each panel
reads and writes its own slice of `:rf/causa`'s app-db via its own
`install!`-registered handlers.

This separation is what makes per-panel mountability possible: any
host that wants ONE panel mounts that panel directly via
`mount-<panel>!`; the shell is just one specific composition of all
of them.

### Hot-reload + idempotency

`register-causa-handlers!` is `defonce`-guarded so shadow-cljs
`:after-load` cycles do not re-register handlers (which would emit
`:rf.warning/handler-replaced` traces on every reload). `reg-frame`
is idempotent via surgical-update semantics. Mount fns can be called
from a host's `init!` path at any frequency without risk.

## Static mode (rf2-o5f5f)

Causa exposes TWO modes — **Runtime** (the event-coupled spine + 4-layer
chrome described above) and **Static** (event-INDEPENDENT browse of
what's registered). Static is "Causa-in-a-quieter-key": it shares the
full Runtime design language (Inter + JetBrains Mono, the complete
`theme/tokens.cljc` palette, the 4px spacing grid, the 56px ribbon, the
40px tab-bar). Differentiation is **temperature, not vocabulary**.

### Surface inventory (3-layer chrome)

Runtime is 4 layers (L1 ribbon · L2 event list · L3 tab bar · L4 detail
panel). Static drops L2 — there is no spine in Static mode because the
surface is event-independent — and renders 3 layers:

    ┌───────────────────────────────────────────────────────┐
    │ L1  Top ribbon (56px) — mode pill + right icons       │
    ├───────────────────────────────────────────────────────┤
    │ L3  Tab bar (40px) — 5 tabs                           │
    ├───────────────────────────────────────────────────────┤
    │ L4  Detail panel (fills remaining canvas)             │
    └───────────────────────────────────────────────────────┘

L2's absence is also a functional signal — see §Mode-signal mechanism
below.

### Sub-tab inventory (Static L3)

Five Static sub-tabs, mode-scoped mnemonics per the findings doc
`ai/findings/2026-05-19-causa-explorer-mode.md` §5.2:

| Tab | Mnemonic | Bead | Contents |
|---|---|---|---|
| **Machines** | `m` (default) | rf2-o5f5f.2 | Registry browse + Topology + 4-mode sub-strip |
| **Routes**   | `r` | rf2-o5f5f.3 | Registered routes (promoted from Runtime) + Simulate-URL |
| **Schemas**  | `c` | rf2-o5f5f.4 | Registered schemas + sample data + jump-to-source |
| **Views**    | `v` | rf2-o5f5f.5 | Registered views catalogue (Fiber-walker consumer) |
| **Events**   | `e` | rf2-o5f5f.6 | Registered handlers + interceptor stack |

Mnemonic mode-scoping: the same letter dispatches the active mode's
tab — `m` in Runtime opens the Machines instance-inspector, `m` in
Static opens the Machines registry browse.

### Mode-signal mechanism (4 stacked signals)

The user reads Static at a glance via four stacked signals — together
they telegraph the mode without the user needing to look at the pill:

1. **Mode pill** at ribbon-left — two-segment radio
   `[● Runtime] [○ Static]`, 160px total, accent-violet active
   segment with a 200ms cross-fade. Lives in both modes (it's the
   toggle, not the indicator). Cmd-Shift-M (the global chord) fires
   the same `:rf.causa/toggle-mode` event so chord and pill share
   the handler.
2. **2-px left-edge ribbon stripe** — `:accent-violet` in Runtime,
   `:cyan` (already in the palette) in Static. Zero new tokens
   introduced.
3. **Motion dampening** — Runtime ships the LIVE pulse + machine-active
   pulse + 180ms tab fade. Static drops the continuous pulses entirely
   and collapses the 180ms tab fade to instant (so cluster swaps land
   without motion). Honours `prefers-reduced-motion: reduce` via the
   `--rf-causa-motion-scale` seam in `theme/global-styles/motion-css`.
4. **Chrome silhouette** — Runtime is 4-layer; Static is 3-layer (no
   L2 / no spine). The shape itself is a signal.

### Mode-state lifecycle

The mode slot lives on Causa's app-db at `[:rf.causa/mode]`
(`:runtime | :static`); the Static-scoped tab choice lives at
`[:rf.causa.static/selected-tab]` (default `:machines`). Three event
handlers drive the lifecycle:

- `:rf.causa/set-mode` — writes a specific mode (mode-pill segment
  clicks, hydration after localStorage read, test fixtures).
- `:rf.causa/toggle-mode` — flips between modes (the Cmd-Shift-M
  chord — see `keybinding.cljs`).
- `:rf.causa.static/select-tab` — flips the Static-scoped tab
  (independent of the Runtime `:rf.causa/select-tab` slot so flipping
  modes preserves both choices).

Set + toggle attach the `:rf.causa.static/persist-mode` fx so every
mutation round-trips through localStorage under the canonical key
`causa.mode`. Unknown / malformed values normalise back to
`:runtime` — the conservative default.

### Frame isolation

Same discipline as the Runtime shell. The Static surface composer
inside `shell.cljs` is wrapped in `[rf/frame-provider {:frame
:rf/causa}]`; every subscribe + dispatch inside the surface resolves
to `:rf/causa`. Each subscribing region is `reg-view`-registered so
its rendered component carries `:contextType frame-context` (rf2-in6l2
+ Spec 004 §Plain Reagent fns do not pick up the surrounding frame).

### Feature flag

Static is gated behind the `:rf.causa/static-mode?` config flag,
default `false`. Hosts opt in via:

    (causa-config/configure! {:rf.causa/static-mode? true})

before the Causa preload runs. With the flag OFF the surface composer
ALWAYS renders Runtime — byte-identical to the pre-Static chrome,
the mode pill is absent, and the Cmd-Shift-M chord falls through to
the host / browser. With the flag ON the mode pill mounts at
ribbon-left and the chord drives the toggle.

The flag flips to default-on once sibling beads rf2-o5f5f.2 … .6 fill
the placeholder Static sub-tabs (a separate decision).

### See also

- [`008-Embedding-Contract.md`](./008-Embedding-Contract.md) — the
  embedding contract for Story / first-party embeds (the `:compact?`
  / `:scope` / `:on-event` props the panel views consume).
- [`011-Launch-Modes.md`](./011-Launch-Modes.md) — the default
  in-app shell-mount path via `[data-rf-causa-host]`.
- [`Conventions.md`](./Conventions.md) §Panel facade + leaf split —
  the canonical per-panel facade shape (`Panel` reg-view +
  `install!`) every mount-fn target adheres to.
