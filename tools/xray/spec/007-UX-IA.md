# 007-UX-IA

## The one-event-spine model (the load-bearing statement)

Every Xray surface orients around **one focused event** — the spine
sub `:rf.xray/focus`. The user picks an event in the L2 list; every
dependent surface rebinds atomically. Tabs are **lenses on that one
event**:

| Tab | Bug-class it answers |
|---|---|
| **Epoch** (`e`) | "What does this event do?" — the handling pipeline (DISPATCH → COEFFECTS → EVENT HANDLER → FLOWS → DB CHANGES → AFTER INTERCEPTORS → FX; optional sections omitted when absent) + wire-boundary diff per managed fx. Supersedes the retired Event/Handler tab per rf2-5gl5r. (Per-panel content design: [`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md) §2.) |
| **app-db** (`a`) | "What changed because of this event?" — the complete app-db, sectioned by reserved `:rf/*` area, with inline diff annotations. (Per-panel content design: [`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md) §4.) |
| **Views** (`v`) | "Why did these views re-render?" — the left → right reactive-flow graph (app-db → subs → views) + hover-to-highlight on rendered DOM. (Per-panel content design: [`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md) §3. Rendered tab label follows the Figma export `Views` (rf2-ad7zx); the spec's rf2-e33ad display label was `View`; key stays `:views`.) |
| **Trace** (`t`) | "What raw events fired in this cascade?" — readable-line timeline, op-family colour bands, relative timing. (Per-panel content design: [`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md) §5; dedicated redesign spec + Figma-handoff target: [`023-Trace-Panel.md`](./023-Trace-Panel.md).) |
| **Machine** (`m`) | "What did this event do to my machines?" — transitions, cancellation cascade, `:after` rings. **Event-driven only post-rf2-y9xmf** (no picker, no Mode A/B/C; BLANK when the focused event has no machine activity; per-machine prev/next nav walks the spine). (Per-panel content design: [`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md) §6.) |
| **Routes** (`r`) | "What did this event do to my routes?" — current route + this-epoch navigation + the registered route table. (Per-panel content design: [`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md) §7. Promoted to its own L3 tab per rf2-nrbs9.) |

(The former **Issues** tab (`i`) was removed per rf2-gbz39 — Mike RULED Option (c), 2026-05-31. Errors · warnings · advisories now surface inline in the Epoch panel + the L2 event-row pink-wash + the always-on issues ribbon signal, rather than in a dedicated 7th tab.)

(rf2-4v67l — the Chrome A11y dogfood tab was removed. A11y
dogfooding is properly Story's domain, where it already ships as
the `chrome-a11y` panel (rf2-18t6p · `tools/story/src/re_frame/
story/ui/chrome_a11y.cljs`) — a sibling to the variant a11y scanner
`re-frame.story.ui.a11y` (rf2-qgms1). A duplicate Xray-side panel
was noise that flagged Xray's own events-list as a problem.)

Plus popovers (`r` nav-token timeline · `f` wire-trace
+ `h` hydration bisector, future). Every popover is invokable from any
tab. Every popover anchors on `:rf.xray/focus`.

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

Xray fills a true-inline panel on the **right side** of the host app
by default. The host app provides `[data-rf-xray-host]` as a normal
flex/grid column and Xray renders inside it. This is not an overlay
and not a body-padding dock: the app remains visible and clickable to
the left because normal layout owns the relationship.

### The 4-layer chrome

```
┌─────────────────────────────────────────────────────────────────────────┐
│ LAYER 1  Two ribbons — chrome (~32px) + events ribbon (~36px) (rf2-4vp5j)│  scope + spine controls
├─────────────────────────────────────────────────────────────────────────┤
│ LAYER 2  Event list (4-col table; 6 rows default; resize via L2/L3 seam)│  the spine / timeline
├─────────────────────────────────────────────────────────────────────────┤
│ LAYER 3  Tab bar (40px) — 9 tabs                                        │  projection selector
├─────────────────────────────────────────────────────────────────────────┤
│ LAYER 4  Detail panel (fills remaining canvas)                          │  per-tab content
└─────────────────────────────────────────────────────────────────────────┘
```

Wireframe at default (reconciled to the Figma design — `design-reference/xray_devtools_reference.cljs`,
the five-region layout + `ChromeRibbon` / `EventsRibbon` / `EventList`):

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Event History  [◀ ▶ ⏭]  + filter   :app/main ▾   Dynamic / Static ▾   ⚙ ✕│   L1 chrome ribbon
│ ↳ filters:  +  [+ :auth/* ✎] [× :mouse-move ✎]              3 filtered out│   L1.5 events ribbon (shown only when filters exist — rf2-pjjwh)
├─────────────────────────────────────────────────────────────────────────┤
│ source │ event id        │ timestamp      │ duration                     │   L2 — 4-col table
│ fx     │ :title/flow     │ 12:30:05.123   │  1.2 ms                      │      6 rows default
│ view   │ :counter-inc    │ 12:30:06.456   │  0.4 ms        ← focused row  │      latest-on-bottom
│ timer  │ :poll/tick      │ 12:30:07.001   │  0.2 ms                      │
│ view   │ :user/profile   │ 12:30:07.892   │  0.6 ms                      │
│ machine│ :title/loaded   │ 12:30:08.234   │  0.3 ms                      │
│ view   │ :form/submit    │ 12:30:09.456   │  1.8 ms                      │
╞═════════════════════════════════════════════════════════════════════════╡   L2/L3 seam — drag ↕ to resize
├─────────────────────────────────────────────────────────────────────────┤
│ [Epoch] app-db Views Trace Machine Routes Resources Graph Modules        │              L3 — 9 tabs
├─────────────────────────────────────────────────────────────────────────┤
│ — Epoch tab content for the focused event —                             │   L4 — fills the rest
└─────────────────────────────────────────────────────────────────────────┘
```

The four layers, top to bottom:

1. **L1 — Two ribbons (rf2-4vp5j / rf2-pjjwh).** A **chrome ribbon** leads with the
   `Event History` label, then the nav cluster (`◀` `▶` `⏭`) + the `+ filter` add affordance,
   then scope selectors (the **`<frame>` ▾** view-scope dropdown whose face shows the
   currently-selected frame, rf2-pjjwh + `Dynamic / Static ▾` **mode dropdown**), and chrome
   actions (`⚙` settings + `✕` close) on the right. Below it an **events ribbon** carries the
   filter chrome: `↳ filters:` label · a `+` add-filter icon · the active filter pills (each
   removable, `×`). **rf2-pjjwh — the events ribbon is hidden by default and animates open
   only once the first filter exists; it animates closed when the last filter is removed.**
   The focus-dimension feature (focus button / `🎯 focus` chip / per-row focus gutter) and the
   `Clear Filters` button were RETIRED (rf2-pjjwh — not in the Figma surface). Row click still
   SELECTS the cascade and drives every L3/L4 panel. (`⛶` popout is omitted — silent-by-default
   until the second-window UX lands.) LIVE/RETRO surfaces in the L2 event-list spine itself;
   the chrome ribbon's mode dropdown toggles Dynamic ↔ Static (a separate axis). Anatomy in
   §The L1 ribbon below.
2. **L2 — Event list.** A **four-column table** (`source · event id · timestamp · duration`);
   **6 rows visible by default**, with **the L2/L3 seam acting as the drag handle for
   vertical resize** (§Splitter affordance); latest-on-bottom; virtualised; sticky header.
   The active/focused row takes a subtle background; functional semantic markers
   (redaction / issue / pin) ride as subtle per-row signals (§Event-list rows). The spine
   sub `:rf.xray/focus` reads from this layer.
3. **L3 — Tab bar (40px).** Nine Dynamic tabs in the order the
   `panel-registry/reg-l4-tab!` `:order` fixes (the Figma export fixed the
   first six; the cohesive-sub-domain tabs were appended after), updated post
   rf2-5gl5r + rf2-gbz39 (Issues tab removed per Option (c)) + EP-0016 /
   EP-0014 / EP-0013 (Resources / Graph / Modules added):
   **Epoch · app-db · Views · Trace · Machine · Routes · Resources · Graph ·
   Modules**. Letter mnemonics: `e` `a` `v` `t` `m` `r` `s` `g` `u`. (The
   original Figma export listed Event/Handler at
   `:order 0`; rf2-5gl5r retired that panel in favour of the Epoch panel at
   `:order -1` — same letter mnemonic `e`, same leftmost position. **Graph**
   and **Modules** are L4-only registry tabs — focusable but with no standalone
   `mount-*!` facade.) Each tab
   renders its **label only** (no `◉`/`○` glyph — Figma
   design rf2-ad7zx); the **active tab fills with the single `accent`** (GitHub blue) + white
   text, inactive tabs are plain with a hover background. (The Figma export labels
   the reactive tab `Views` and the machines tab `Machine`; the spec elsewhere uses the
   rf2-e33ad display label `View` and `Machines`. The export wins on the rendered label per
   rf2-ad7zx; the internal panel-registry keys stay `:views` / `:machines`.) Routes was promoted
   to its own L3 tab in rf2-nrbs9 — it follows the cohesive-sub-domain rule
   (sub-domains earn their own lens tab). The spine-INDEPENDENT
   browse-all canvas relocated to the Static Machines sub-tab in
   rf2-ga16q (the Runtime Machines tab is the event-driven lens per
   rf2-y9xmf).
   (rf2-4v67l — the Chrome A11y dogfood tab was removed in favour of
   Story's already-shipped chrome-a11y panel per rf2-18t6p.)
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

Below 900px viewport: Xray takes 100% of viewport width.

Below 600px viewport (phones): **Xray refuses to mount** (per lock
#5). The DOM root creates but the visible UI is a single message
explaining desktop-only.

### Inline host CSS variables

The default true-inline host (`[data-rf-xray-host]`) is sized and
themed via two host-readable CSS custom properties. Xray never reads
or writes these from CLJS — the host's stylesheet is the single
source of truth.

| Property | Default | Purpose |
|---|---|---|
| `--rf-xray-inline-width` | `560px` | `flex-basis` of the inline host. Default bumped 420 → 560 under rf2-9ovfb. |
| `--rf-xray-accent` | `#539bf5` | Xray's single **GitHub blue** accent (matches `accent` in §Colour system; rf2-ad7zx.13). Published on `:root` in the recommended host snippet so host stylesheets can colour their own dev chrome to harmonise with Xray (rf2-9ovfb). |

Override either property anywhere up the cascade; the closest
declaration wins as usual. The published spelling is also exported
as `day8.re-frame2-xray.config/default-layout-host-css-var` /
`default-layout-host-width` / `default-accent-css-var` /
`default-accent` so tooling and docs generators can refer to them
without forking the string. Full contract + drag mechanics in
[`011-Launch-Modes.md`](./011-Launch-Modes.md).

### Resize affordance

Xray's panel SHALL be horizontally resizable via a drag handle on the
panel's outer edge (left edge when docked `:right-rail`; the default per
`Settings → General → Panel position`). The handle SHALL:

- Show `cursor: col-resize` on hover
- Drag-to-update width with global pointer-capture (mouse, touch,
  and pen unified through pointer events; `touch-action: none` so a
  touch-drag does not pan the page)
- Clamp width to `[320px, 90vw]`
- Persist via `configure! :rf.xray/settings :general :panel-width-px`
  (see [`015-Configuration.md`](./015-Configuration.md))
- Reset to default width on double-click

The CSS-variable cascade (`--rf-xray-inline-width` on the host's
`flex-basis`) continues to work unchanged — the handle simply drives
the same custom property reactively, so a `:root { --rf-xray-inline-
width: 720px; }` override and a user drag write to the same surface.

No textual affordance accompanies the handle (cursor change is sufficient
signal per [`Conventions.md`](./Conventions.md) §UI text — silent by
default). The resize handle is a worked example of "non-obvious
affordance with iconographic alternative": discovery is via cursor
change on edge hover, not via prose label.

In the second-window pop-out (`:popout` shell mode — launched from the
chrome `⛶` button or `(xray/popout!)`, no longer a panel-position
value per `rf2-czcg5`) the browser's window controls govern size, so no
in-panel handle renders. In `:fullscreen` position the handle is
suppressed — the panel fills the viewport.

#### Auto-inject contract (rf2-70u8q)

The handle is **auto-injected**. Consumers SHALL NOT need to wire any
resize CSS on the layout host — dropping in
`<aside data-rf-xray-host></aside>` and the minimal host CSS (no
`resize: horizontal`, no `overflow: auto`) is sufficient. Xray's
preload mounts the shell into the host once the substrate adapter is
ready (per §The default landing view); the shell renders the handle as
an absolutely-positioned child pinned to the host's left edge.

The handle's styles are Xray-owned — the consumer's CSS surface
remains exactly the four declarations the layout-host contract
already requires (`flex`, `min-width`, `box-sizing`, `border-left`).

##### Yield-to-consumer

Xray MUST detect a consumer-asserted browser-native handle and yield:
if `getComputedStyle(host).resize` is `"horizontal"` or `"both"`, the
auto-injected handle SHALL render nil so the page does not carry two
draggable affordances. The probe runs at render time on every paint,
so a runtime CSS swap (devtools edit, theme switch) updates the yield
decision on the next frame.

The yield path is the **opt-out**. Pre-alpha posture is zero-config
for the consumer; the opt-in to Xray's handle is "do nothing" and
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

### Splitter affordance — the L2/L3 seam (rf2-t2dsh)

The horizontal boundary between the **L2 event list** and the **L3 tab
bar** is a draggable resize affordance. The seam SHALL:

- Show `cursor: row-resize` on hover anywhere along the seam
- Drag-to-update the event-list height with global pointer-capture
  (mouse, touch, and pen unified through pointer events;
  `touch-action: none` so a touch-drag does not pan the page)
- Clamp height to `[48px, viewport×0.7]` — 48px == 2 rows + chrome,
  the documented L2 minimum; 70% leaves a 30% sliver for L1 chrome +
  L3 tab bar + L4 detail panel
- Persist via `configure! :rf.xray/settings :general :events-list-height-px`
  (round-trips through localStorage with the rest of the Settings
  map; see [`015-Configuration.md`](./015-Configuration.md))
- Reset to default height (200px) on double-click

The click-area is a **full-width 8px horizontal strip** so the
operator can grab the seam from any X coordinate; the visible
affordance is a 1px accent hairline at 33% alpha along the seam
centre, intensifying to ~55% alpha on hover (light + dark themes
both route through `--rf-xray-accent` — the treatment lands
consistently in both).

#### Disposition of the prior corner-grip

The previous affordance was the browser-native `:resize "vertical"`
declaration on the L2 list — a tiny 16px corner-grip at the bottom-
right of the list. That affordance was **retired** in rf2-t2dsh:

- no persistence (height reset to 200px on every reload)
- no keyboard surface (mouse/touch only)
- tiny corner hit-target (~16×16px) — invisible to a user who hasn't
  hovered the exact corner
- inconsistent with the panel-width handle's interaction model (drag
  + reset + keyboard arrows)

The L2 list's inline style no longer carries `:resize`; the seam
handle is the sole vertical-resize surface. Per pre-alpha posture
(no back-compat shims) the swap is a hard cut.

#### Keyboard contract

The seam is keyboard-reachable (`tabindex="0"`, `role="separator"`,
`aria-orientation="horizontal"`, live `aria-valuenow` for the current
height). Bindings:

| Key | Action |
|---|---|
| `ArrowDown` | Grow list by 8px (matches drag-down semantics) |
| `ArrowUp` | Shrink list by 8px |
| `Shift+ArrowDown` | Grow by 32px (coarse step) |
| `Shift+ArrowUp` | Shrink by 32px |
| `Home` | Snap to upper clamp (registry applies the 70vh bound) |
| `End` | Snap to lower clamp (registry applies the 48px floor) |
| `Enter` / `Space` | Reset to default (matches double-click) |

Unrecognised keys bubble normally — the surrounding chrome's
`Ctrl+Shift+C` / `?` / `Esc` shortcuts remain reachable from the
handle's focus position.

#### Rendering

The seam-handle is one DOM node mounted between `event-list` and
`tab-bar` (DOM-order asserted by `events-list-seam-cljs-test`). The
testid is `rf-xray-event-list-seam`. Styles are Xray-owned — no
consumer CSS surface; the seam appears automatically as part of the
4-layer chrome.

The seam rides on the Dynamic surface composer (`surface-composer`
→ `dynamic-chrome`); Static mode renders a different surface
(`static-shell/surface`) that doesn't carry an L2 event list, so
the seam is absent there without an explicit check.

## The L1 ribbon — two ribbons (rf2-4vp5j)

Layer 1 is **two stacked ribbons**, splitting scope SELECTORS from
spine/filter CHROME. (The Dynamic/Static **mode dropdown** lives at
chrome-ribbon-left — an earlier draft dropped the mode pill entirely; the
rf2-4vp5j redesign re-added it as a compact `<select>`. LIVE/RETRO is a
separate spine state, surfaced by the L2 head-row cue.)

### L1 chrome ribbon (`rf-xray-ribbon`, ~32px — Figma design rf2-ad7zx)

Reconciled to the Figma design (`design-reference/xray_devtools_reference.cljs`, the
`chrome-ribbon` component). Left → right: the logo/wordmark, the two scope dropdowns, then
the chrome actions at the far right.

| Cluster | Side | Content | Keys |
|---|---|---|---|
| **Logo** | left | `❖ Xray` wordmark — the brand anchor, the single blue `accent`, semibold. | — |
| **Frame** | left | `Frame ▾` dropdown (multi-frame); flat `Frame: :rf/default` label when single-frame. **Single-select VIEW SCOPE** (rf2-4vp5j — not a filter; not persisted). Tool frames hidden unless Settings → View → "Show tool frames in picker" toggle on. | — |
| **Mode** | left | `Dynamic / Static ▾` dropdown — compact, understated (occasional use); the dropdown's active option + `data-active-mode` carry the mode signal (the chrome stripe is the single blue accent in both modes — rf2-ad7zx.13). | `Cmd/Ctrl-Shift-M` |
| **Right-icons** | right | `⚙` settings popup · `✕` close shell (`:rf.xray/close-shell`). `⛶` popout is omitted (reserved for the second-window UX — silent by default). | `,` or `s` · `Esc` |

The silent-by-default `🔇 N` mute + `● N` REDACTED indicators are **functional surfaces painted
only when their count > 0** (absent in the Figma mock because the counts there are 0); they
render to the right of the Mode dropdown when active. Kept per the functional-semantic carve-out
(rf2-ad7zx).

### L1.5 events ribbon (`rf-xray-events-ribbon`, ~36px — Figma design rf2-ad7zx / rf2-pjjwh)

Reconciled to the Figma design (`design-reference/xray_devtools_reference.cljs`, the
`events-ribbon` component). **rf2-pjjwh — this ribbon is hidden by default and animates open
(CSS `grid-template-rows: 0fr ⇄ 1fr`) only once the first filter exists; it animates closed
when the last filter is removed.** Left → right: `↳ filters:` label, a `+` add-filter icon,
the filter pills. The nav cluster moved UP to the chrome ribbon; the focus chip and the
`Clear Filters` button were RETIRED (rf2-pjjwh — not in the Figma surface).

| Cluster | Side | Content | Keys |
|---|---|---|---|
| **Label** | left | `↳ filters:` (muted) | — |
| **Filter pills** | left | a `+` add-filter icon + active filter pills (each a removable `<key> ×` chip). Click any pill → edit popup; each pill's `✕` removes it. | `/` focus add-pill |
| **Hidden** | far right | `N events filtered out` count, only when N > 0. | — |

Full anatomy + filter-pill edit popup in
[`018-Event-Spine.md`](./018-Event-Spine.md) §3 + §7.

### Frame slot contract (rf2-iwwou)

The **Frame** cluster is the L1 frame-switcher slot — the single
contractually-anchored surface every frame-aware feature reaches
through. The slot lives in `tools/xray/src/day8/re_frame2_xray/
frame_switcher.cljs`; the ribbon mounts the view as one delegate. The
contract:

| Surface | Id | Role |
|---|---|---|
| Sub | `:rf.xray/current-frame` | Returns the frame id the user has focused (or nil pre-selection). |
| Sub | `:rf.xray/available-frames` | First-seen-order vec of selectable frames; tool frames filtered by default per §I1 below. |
| Event-fx | `:rf.xray/select-frame <frame-id>` | Canonical write. Dispatches the spine's `:rf.xray/set-frame` (which re-seeds `:target-frame` + `:epoch-history` — see [`018-Event-Spine.md`](./018-Event-Spine.md) §6) AND fires `:rf.xray.frame-switcher/persist` for localStorage. |
| Fx | `:rf.xray.frame-switcher/persist` | localStorage write under `re-frame2.xray.frame-switcher.v1` (per-instance overridable via the direct setter `day8.re-frame2-xray.frame-switcher/set-storage-key!`; a future `configure! :rf.xray/frame-switcher-storage-key` plumb is straightforward but not wired today). **Per rf2-swclw the frame pin is a TRANSIENT view scope: it is NOT hydrated on load — `mount.cljs/::reset-transient-filters` clears the stored value so each session starts at the head-frame default.** The write still happens within a session; only the load resets. |

Every frame-aware feature — the L1 ribbon picker, the Cmd-K palette's
`:palette/select-frame` verb, future panel-by-frame surfaces — MUST
dispatch `:rf.xray/select-frame`. Reaching the spine's `:rf.xray/
set-frame` primitive directly bypasses the persistence + future
instrumentation layers attached to the canonical event.

### EP-0013 realm-awareness — the retained-internal installation substrate (shipped — rf2-3caq85 · rf2-7vqpwa · rf2-wtg9z4)

The EP-0023 PUBLIC model is **`image -> frame -> event stream`** (images
as registration-set values, frames as execution contexts) — that is what
the operator reasons in, and the Module-view tab renders it FIRST (the
FRAMES/IMAGES section, [`026`](./026-Module-View-Panel.md) §8). EP-0013
(app values and runtime realms) is **partially superseded**: the *realm*
is RETAINED as the **internal installation substrate** the public model
rides on — it owns the registrar/adapter/frame-registry, a frame belongs
to exactly one realm, and the `(realm, frame)` pair is the internal
address. It is NOT the central/beginner-facing public vocabulary (mirrors
the `re-frame.migration` disposition map: `rf/realm` → retained-internal,
`rf/app` → publicly-replaced by `rf/image`). Per EP-0023 Consequence
#4/#9, tooling MAY show this internal installation boundary but must
**LABEL it as implementation structure**, not present it as a peer public
browse dimension. The public realm address API (`rf/realm-ids` — the
installed realms; `rf/frame-realm` — a frame's realm; PR #4038) is a
tooling/migration surface. **Zero-ceremony extends to the tooling: a
single-realm process renders byte-identically to the pre-EP picker / trace
rows; the realm dimension is spelled only when more than one realm is
present, and where it does it reads as the internal installation realm.**

- **Frames panel — groups by the internal installation realm in
  multi-realm processes only** (disposition 3, rf2-3caq85). The frame is
  the EP-0023 public addressing unit; the realm is the retained-internal
  installation boundary. `frame_switcher.cljs` adds
  `:rf.xray/available-frame-realm-groups` (the pickable frames grouped by
  `rf/frame-realm`) and the pure `group-frames-by-realm` /
  `multi-realm?` helpers. When the result spans >1 realm the picker
  renders an `<optgroup>` per realm, **labelled `installation realm
  <realm>`** so the grouping reads as implementation structure
  (`data-testid rf-xray-ribbon-frame-realm-group-<realm>`); when it
  collapses to one realm the picker renders the FLAT option list,
  byte-identical to the pre-bead render. A frame whose realm is unknown
  buckets to `:rf.realm/default` (absence = default realm, the EP-0013 D1
  rule).
- **Trace rows — surface the internal installation realm where present**
  (disposition 2, rf2-7vqpwa). The public execution context an event runs
  in is the FRAME; the realm `:rf.realm/id` stamp is the internal
  installation substrate. `trace_helpers.cljc` projects the row's `:realm`
  from `[:tags :rf.realm/id]` (`realm-of`), and the feed carries
  `:multi-realm?` (`multi-realm-feed?`). `panels/trace.cljs` renders a
  compact realm chip (`data-testid rf-xray-trace-row-<id>-realm`,
  **titled `Internal installation realm (EP-0013 substrate): <realm>`**)
  at the end of the target column ONLY when the arc spans >1 realm AND the
  row carries a stamp — a single-realm (or unstamped) arc renders
  unchanged.
  The framework's trace emit does not stamp `:rf.realm/id` yet (the slot
  is reserved per disposition 2; the emit is a later core slice), so in a
  single-realm process every row's `:realm` is nil and the surface is
  inert — exactly the zero-ceremony posture.
- **Static-registry browse + handler-resolution — qualify by realm**
  (disposition-1/2, rf2-dfaey7). The static browse panels read
  registrations via `(rf/registrations :kind)` — the DEFAULT realm only —
  so a multi-realm host could not see which realm owns a registration nor
  flag a cross-realm id conflict (the same id registered in two realms).
  The shared `static/shared/realm.cljs` helper composes the EP-0013 stage-8
  map-shaped query form `(rf/registrations {:realm r :kind k})` + the
  installed-realm enumeration `rf/realm-ids` into the browse: it returns
  realm-qualified `[realm-id reg-map]` pairs (`realm-qualified-registrations`),
  attributes each row to its owning `:rf.realm/id` (`realm-of` blanks the
  default realm — absence is the default), flags ids spanning >1 realm
  (`cross-realm-ids`), and renders a per-row realm chip (`realm-badge`,
  warning-toned on a cross-realm conflict, **titled as the owning internal
  installation realm** so it reads as implementation structure per EP-0023
  Consequence #4/#9) ONLY in a multi-realm browse.
  **Handler-resolution is realm-scoped**: a chain entry resolves its
  `:interceptor` descriptor against the SAME realm the event lives in
  (`resolve-ref-fn-for` per realm). The named first consumer is the **Static
  Interceptors** sub-tab (`static/interceptors/panel.cljs`,
  `collect-interceptors-by-realm` + the `:rf.xray.static.interceptors/realm-pairs`
  sub feeding `tab-data`); the same helper extends to the schemas / flows /
  routes / machines browse panels when multi-realm demand lands. Single-realm
  hosts (the common case: `(rf/realm-ids)` ⇒ `#{:rf.realm/default}`) read the
  default-realm path — `realm-badge` renders nothing, no realm column appears,
  the browse is byte-identical to the pre-rf2-dfaey7 surface. Fail-soft: a
  core too old for the map-shaped query form / `rf/realm-ids` degrades to the
  default-realm read.
- **Module-view tab — EP-0023 public model first, retained substrate below**
  (rf2-wtg9z4 · rf2-32siq3.12). A Dynamic L4 tab (`panels/module_view.cljs`,
  label **Modules**, order 9, registered via `reg-l4-tab!` — an L4-only
  tab, no `mount-*!` facade, so it is NOT in `panel-enum`). It renders the
  EP-0023 **FRAMES/IMAGES** section FIRST — the `image -> frame` public
  model (each live image-loaded frame as an execution context carrying its
  resolved image's `[kind id]` descriptors) — then the retained EP-0013
  internal substrate below: the **REALMS** section (`rf/realm-ids` ×
  `rf/frame-realm` — the `(realm, frame)` address space) and the
  **MODULES** section (per-module ownership / capability requirements /
  descriptor provenance, read off each realm's installed app value via the
  graduated public `rf/installed-app` seam — rf2-at0oen). A process running
  entirely on the load-order / sugar path shows the honest no-module
  caption, which names the `rf/app` / `rf/module` / `rf/install!` remedy as
  the **retained-internal app-composition substrate** and points at the
  image/frame public model above. See [`026`](./026-Module-View-Panel.md)
  for the normative contract.

## The default landing view

On page load after `rf/init!`, when `[data-rf-xray-host]` exists:

- Xray auto-opens in the right inline host.
- `Ctrl+Shift+C` hides/shows the already-mounted shell with a CSS-only
  display toggle.
- **Active tab: Event**, showing the most-recent cascade's event
  detail (the spine sub `:rf.xray/focus` auto-points at head).
- **Frame picker: shows the active frame** (single-frame apps collapse
  to static label).
- **Filter pills: empty by default** — first session is honest about
  what's filtered; Recommended quick-add available via add-pill.
- **L2 spine: head row pulses** (LIVE cue; the dedicated Mode pill widget was dropped).

## Event-list rows (L2 · table — rf2-ad7zx)

Reconciled to the Figma design (`tools/xray/design-reference/xray_devtools_reference.cljs`,
the `event-list` component + the brief), the later iteration: the L2 spine is a **compact,
scannable four-column table** — one row
per event, newest at the bottom — not a single-line gutter-glyph row. The prior gutter-glyph +
right-aligned-badge row shape is **superseded**; the functional semantic markers the framework
needs (redaction / issue / pin) survive as a subtle per-row tint or trailing marker (below), not
as the primary row structure. Full click behaviour + hover tooltip in
[`018-Event-Spine.md`](./018-Event-Spine.md) §4.

**6 rows visible by default; the L2/L3 seam is a drag handle for vertical resize** (drag
down to show more history — see §Splitter affordance for the full mechanics). The header
row is sticky; on hover/selection the row takes a subtle background (`hover` / `bg-active`)
— the active/focused row is the spine's `:rf.xray/focus`.

### Columns (left → right)

| Column | Content | Notes |
|---|---|---|
| **source** | what triggered the event, as a short **text label** (not an icon) — `view` · `fx` · `timer` · `machine` · … | muted (`text-secondary`); spelled out per Visual encoding (§022) |
| **event id** | the dispatched event keyword, e.g. `:counter-inc` | mono, `text-primary`; long-keyword treatment per §Long-keyword treatment |
| **timestamp** | when it fired, e.g. `12:30:06.456` | mono, muted |
| **duration** | how long the event took to process, e.g. `0.4 ms` | mono, muted |

```
┌ Event list  (6 rows default · drag L2/L3 seam ↕ to resize) ─────────────┐
│ source │ event id          │ timestamp      │ duration                  │
│ fx     │ :title/flow       │ 12:30:05.123   │  1.2 ms                   │
│ view   │ :counter-inc      │ 12:30:06.456   │  0.4 ms   ← focused row    │
│ timer  │ :poll/tick        │ 12:30:07.001   │  0.2 ms                   │
│ machine│ :title/loaded     │ 12:30:08.234   │  0.3 ms                   │
│  …                                                                       │
╞═════════════════════════════════════════════════════════════════════════╡   ↕ L2/L3 seam (drag to resize)
└──────────────────────────────────────────────────────────────────────────┘
```

### Functional semantic markers (kept — the framework needs them)

The Figma mock did not render these, but they carry meaning the framework relies on, so they
survive as **subtle per-row signals** layered onto the table (per the ruling's carve-out for
functional semantic colours):

| Marker | Signal | Rendering |
|---|---|---|
| Issue tint / trailing `⚠` | the row's epoch carries an error/warning issue | a subtle row tint + small trailing marker (not a new column); navigates to Issues for that epoch |
| `[● REDACTED N]` | event arg-map carries `:rf/redacted` | magenta trailing marker |
| `[● ELIDED N]` | event arg-map carries `:rf.size/large-elided` | yellow trailing marker |
| pin marker `↺` | pinned cascade | small trailing modifier on the row |

These are right-anchored, subordinate to the four data columns — they reinforce, never replace,
the table.

### Hover tooltip — the home of dropped detail

Every row carries a 400ms-delayed hover tooltip that discloses what the four-column row drops:
cascade sequence number, tier, source coord, and the arg-map preview (via `inspect-inline`).
See [`018-Event-Spine.md`](./018-Event-Spine.md) §4 for the full
tooltip wireframe.

## Dynamic Machines panel shape (post-rf2-y9xmf)

The L4 content of the **Machines** tab is **event-driven only**. The
panel never carries exploratory chrome (no picker, no Mode A/B/C
selector, no sub-strip, no arc / scrubber). It renders one of three
shapes based on the focused event's machine activity:

```
┌─ Machine inspector ───────────────────────────────────────  [Prev][Next] ─┐
│                                                                            │
│ — case A: no machines registered —                                         │
│   "No machines registered." (Prev/Next hidden)                             │
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
  section's `machine-id`), skipping epochs that touched only other
  machines. The jump mutates focus through the spine's
  `focus-cascade-reducer` (stamping `:mode :retro`) so it sticks in
  LIVE mode (rf2-nugvv).

> **rf2-nugvv (2026-06-04)** — the **Share** button + the whole Xray
> share-URL surface were removed; the Machine panel was the share
> modal's sole UI entry point. Prev/Next is now the only header
> affordance.

**The Sim engine + browse-all index have no Dynamic UI.**
Sibling bead rf2-r4nao landed the Sim toggle / side-rail + the
browse-all entry point under the Static Machines surface; the engine
events / subs are now namespaced `:rf.xray.static.machines/sim-*` (re-
hosted from the historical `:rf.xray/sim-*`) and the view lives at
`tools/xray/src/day8/re_frame2_xray/static/machines/sim.cljs`.
Programmatic callers drive Sim against `:rf/xray` via that ns.

### Interactive Machines canvas (rf2-y3l8z)

Every per-machine section in the Dynamic Machines panel renders its
chart through the machines-viz `MachineChart` xyflow component
(`panels/machine_canvas.cljs` → `day8.re-frame2-machines-viz.chart`).
The chart is no longer a static SVG paint; xyflow owns pan, zoom, and
fit internally (the pre-xyflow host-side `chart/controls.cljc`
viewport reducer is gone — rf2-gpzb4).

```
┌─ :auth/login   :idle → :authing                          [:auth/submit] ─┐
│ · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · ││
│ · · ▢ idle ──→ ▣ authing · · · · · · · · · · · · · · · · · · · · · · · ││
│ · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · · ││
│ · · · · · · · ◔ :after rings track node centres under zoom + pan · · · ││
│ ┌──────┐                                                                 │
│ │ + − ⛶ │ ← xyflow <Controls> (bottom-left)                              │
│ └──────┘                                                                 │
│ Guards   ✓ :session-fresh?                                              │
│ Actions  ✓ :clear-form                                                  │
└─────────────────────────────────────────────────────────────────────────┘
```

**Controls — xyflow's built-in `<Controls>` component** (mounted with
`{:showZoom true :showFitView true :showInteractive false}`; xyflow
positions it bottom-left). It is a button cluster, not a custom Xray
toolbar:

- **Zoom-in `+`** / **zoom-out `−`** buttons — step the viewport zoom
  about the centre.
- **Fit-view `⛶`** button — fits the laid-out content into the viewport
  with padding on all sides; centred. (Driven by xyflow's `fitView`;
  Xray additionally re-frames on panel-entry by bumping the orthogonal
  `:fit-signal` nonce, rf2-6tw7t.)
- The interactivity-lock toggle is suppressed (`:showInteractive
  false`) — the chart is non-interactive (`nodesDraggable false`).

> **No `NN%` zoom-readout chip and no `Reset` button.** Earlier
> drafts of this section described a custom `[− 100% +] [Fit][Reset]`
> toolbar carried over from the pre-xyflow SVG renderer's host-side
> viewport adapter. xyflow's `<Controls>` ships zoom-±/fit buttons
> only — there is no percentage readout and no reset-to-100% button.
> Reset-to-default framing is achieved via the fit-view button (or a
> panel-entry `:fit-signal` bump).

> **rf2-48fwsi — Canvas/List view-mode toggle removed.** The canvas
> formerly carried a top-left two-button **Canvas** | **List** pill.
> After the rf2-g2axio events-as-nodes redesign no view branched on
> the persisted mode — clicking "List" only flipped a localStorage
> slot nothing read — so the toggle, its per-machine slot, and its
> localStorage round-trip were deleted. The chart is the sole render
> path. A chartless guards/actions-only "List view" would be a
> separate new feature, not a revert.

**Direct manipulation:**

- **Mouse wheel** zooms toward the cursor (xyflow's `zoomOnScroll`;
  the chart-world coord under the cursor is fixed through the zoom
  transition). Trackpad pinch arrives as a wheel event with
  `ctrlKey=true`; both are accepted.
- **Click-drag** anywhere on canvas background (not on a state
  node) pans (xyflow's `panOnDrag`; `nodesDraggable` is `false`,
  so node hits fall through to the node's own `:on-click`
  state-click event rather than dragging the node).

**Keyboard shortcuts.** The chart binds **no** zoom/pan/fit keyboard
shortcuts. Earlier drafts of this section claimed a `+`/`=`/`-`/`_`/`0`/
`f`/`F` + arrow-key map on a `tabIndex=0` canvas host — that surface
belonged to the pre-xyflow SVG renderer's host-side `chart/controls.cljc`
viewport reducer and did not survive the rf2-gpzb4 xyflow migration. The
live chart sets no `tabIndex` and registers no `onKeyDown`; xyflow 12.x
ships no native viewport zoom-by-key (`+`/`-`/`0`) or pan-by-arrow
handler (its built-in arrow-key handler *moves a selected node* and is
inert here because `nodesDraggable`/`elementsSelectable` are `false`).

Zoom / pan / fit are therefore mouse-driven plus the `<Controls>`
buttons:

| Affordance | Action |
|---|---|
| Mouse wheel / trackpad pinch | Zoom toward the cursor (`zoomOnScroll`) |
| Click-drag on canvas background | Pan (`panOnDrag`) |
| `<Controls>` `+` / `−` buttons | Zoom in / out about the centre |
| `<Controls>` fit-view button | Fit the topology to the viewport |

> **Follow-up (rf2-oc0fwy):** if a keyboard zoom/pan surface is wanted,
> it is a *new feature* (a focusable canvas host + an `onKeyDown` that
> drives the captured `ReactFlowInstance`'s `zoomIn`/`zoomOut`/`fitView`/
> `setViewport`), not a spec-described-existing one. File a feature bead
> rather than treating it as implemented.

**Bounds:** zoom is clamped to `[0.2, 4.0]` (the chart's `minZoom`
/ `maxZoom` props). Wheel + button zoom-step factors are xyflow's
internal defaults — not configured by the chart.

**`:after` rings track the canvas (rf2-obp4z · rf2-uv1on).** The
countdown-ring overlay (machines-viz `chart.overlays.after-rings/
AfterRingsOverlay`) walks the rendered xyflow node DOM
(`[data-testid=rf-mv-chart-node-…]`) to find each bearing node's
bounding box and absolute-positions a ring there. Because xyflow
owns node positions post-migration, the ring tracks the node's
on-screen box at every zoom + pan (superseding the old elk-coordinate
`{:cx :cy :r}` + SVG `translate(tx,ty) scale(s)` viewport-transform
model the SVG renderer used).

**Reduced motion.** Toolbar buttons + the canvas transform ride
Xray's `--rf-xray-motion-scale` seam — no extra wiring here;
motion shrinks to ~0 under `prefers-reduced-motion: reduce` along
with the rest of Xray's surface.

**State (app-db slots under `:rf.xray/machine-canvas`):**

| Slot | Shape | Purpose |
|---|---|---|
| `:chart-collapsed-by-id {<machine-id> boolean}` | per-machine boolean | operator's choice to hide the chart real-estate (default expanded); persisted to localStorage and rehydrated on load |

**Viewport state is NOT an Xray app-db slot (rf2-gpzb4 — 2026-05-21
xyflow migration).** xyflow owns zoom / pan / fit internally; the
former host-side viewport-reducer machinery (`:viewports`,
`:viewport-dims`, `:drag` slots + the
`:rf.xray.machine-canvas/viewport-for` /
`viewport-dims-for` subs + the `/drag-start` · `/drag-move` ·
`/drag-end` · `/measure` events) was removed when the SVG renderer
gave way to `MachineChart`. Fit re-frames ride the orthogonal
`:fit-signal` nonce (rf2-6tw7t), not a stored viewport. rf2-48fwsi
likewise removed the `:view-mode-by-id` slot + its `view-mode-for` /
`view-mode-by-id` subs along with the dead Canvas/List toggle.

Subscriptions exposed for tools / tests:
`:rf.xray.machine-canvas/chart-collapsed-for`,
`:rf.xray.machine-canvas/chart-collapsed-by-id`.

## IN/OUT filter pills

Live in the **L1.5 events ribbon** (rf2-4vp5j moved them down out of the
chrome ribbon; NOT a sidebar). Two pill types (colour + glyph encode
mode):

```
[+ :auth/* ✎]      ← filter-IN  · green border · `+` glyph    · show ONLY matches
[× :mouse-move ✎]  ← filter-OUT · magenta brd  · `×` glyph    · hide matches
[+]                ← trailing add-pill         · click → popup w/ blank pattern
```

AND across modes; OR within mode. `(match-any-IN) AND NOT
(match-any-OUT)`. localStorage persists per host app **within a session,
but RESETS on every load** (rf2-swclw — pills are a transient filter; a
fresh load starts unfiltered). When a filter is hiding rows mid-session,
the events ribbon's far-right cluster shows `N events filtered out`
(rf2-jvghz). The `Clear Filters` button was retired (rf2-pjjwh); pills are
removed individually via each pill's `✕`. The frame view-scope is NOT a
pill (rf2-4vp5j)
— it is excluded from this composition + the hidden count. Full edit-popup
contract + Recommended-filters quick-add + right-click context menu in
[`018-Event-Spine.md`](./018-Event-Spine.md) §7.

## Settings popup (modal overlay)

**Trigger:** `,` key OR `s` key OR click ribbon `⚙` icon.

**Shape: modal overlay** (NOT a dedicated panel). Centred floating
panel at 560×640; backdrop dim (15% black) but Xray visible
underneath. Closes on `Esc`, click outside, or click `✕`. Settings
persist immediately on change (no Apply/Cancel — every toggle writes
through to `(xray-config/configure! …)` on commit).

Four inner tabs (top tab strip, body below) — Mike 2026-05-19 §0ter.4
walkthrough originally locked six (rf2-ttnst); the Theme tab was
retired per rf2-ou3pn (top-ribbon sun/moon icon is now the canonical
light/dark affordance) and the Filters tab per rf2-wknb3 (full pill
management lives in the ribbon filter strip + per-pill edit popup +
mute manager modal). The Buffer tab inherited the
`:general :epoch-history` slider per rf2-pu9sb:

| # | Tab | Mnemonic | Content |
|---|---|---|---|
| 1 | **General** | `g` | Text size · Panel width · Panel position · Auto-open-on-error · Density (Cosy / Compact — no Comfy) · Long-keyword threshold · **Editor override** (rf2-dudqz — per-machine click-to-source picker; nil / `:vscode` / `:cursor` / `:windsurf` / `:zed` / `:idea` / `{:custom <tpl>}`; default nil = use host default) · **Power user:** "Show tool frames in picker" toggle (off by default) · `:use-system-colors?` HCM-override toggle (relocated from Theme per rf2-ou3pn — slot is `:general :use-system-colors?`) |
| 2 | **Keybindings** | `k` | Read-only chord table (every binding the global listener captures) · master "Handle keys?" toggle. v1 is READ-ONLY; rebind UI is the v1.1 follow-on. |
| 3 | **Buffer** | `b` | `:buffer/cascades-retained` (writes through to `(rf/configure! :trace-buffer {:cascades-retained N})` — rf2-5u03ig) · "Clear buffer now" button with confirm modal. (The epoch-history slider was briefly relocated here per rf2-pu9sb but reverted back to General 2026-05-27; the inert `:app-db/inspector-collapse-threshold` input was removed per rf2-5u03ig.) |
| 4 | **Diff** | `d` | Hiccup-diff opt-in `:highlight-fn-ref-changes?` toggle (sub-output diff layout fixed unified; the app-db diff engine itself is Editscript A* per [`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md) §9.1.5.1 with no user-tuneable knobs — the prior section-grouping engine was retired wholesale per rf2-7is22) |

**Inner-tab mnemonics** (g / k / b / d) — bare-letter
keystrokes captured at the dialog level while the modal is open.
The dialog's `on-key-down` stops propagation on every consumed key,
and the global keydown listener gates spine bindings on a
`target-inside-modal?` check (a `data-rf-xray-mode="settings"`
closest-walk), so the inner mnemonics do not also drive the outer
spine. Mnemonics are suppressed while the focused element is an
`<input>` / `<textarea>` / `<select>` / contenteditable surface so
typing into numeric knobs is not interrupted.

**Dropped from earlier drafts** (per the same 2026-05-19 walkthrough plus post-#2186 retirements):

| Dropped | Rationale |
|---|---|
| **Theme tab** (rf2-ou3pn) | Top-ribbon sun/moon icon (`ribbon-theme-toggle` in `shell.cljs`) is the canonical light/dark affordance; both surfaces dispatched the identical `[:rf.xray/settings-update :theme nil <kw>]` event, so the popup copy was pure redundancy. The `:use-system-colors?` HCM-override toggle relocated to **General → Power user** — the setting slot has always been `:general :use-system-colors?`; only its cosmetic home in the Theme section is gone with the tab. |
| **Filters tab** (rf2-wknb3) | Full pill management lives in the top-ribbon filter strip (`filters/pills.cljs`, per [`018-Event-Spine.md`](./018-Event-Spine.md) §7), the per-pill edit popup (`filters/edit_popup.cljs`), and the mute manager modal (rf2-ikuwt). The settings tab's only widget was an "Open auto-filter UI" button dispatching `:rf.xray.filters/open` — an event with no handler registered anywhere — plus a static explainer paragraph. With the management surfaces all canonical elsewhere, the discoverability pointer was redundant. |
| Actions tab + factory-reset BIG RED BUTTON | Factory-reset stays code-only (`config/reset-settings!`) — a destructive UI button has no use case the confirm modal beneath "Clear buffer" does not already cover. |
| Density Comfy tier | Two tiers cover the rhythm need; the third was a styling-pass aspiration with no observed demand. |
| Per-tab default expansion (`:bookish` / `:dense`) | Each tab owns its expansion default; no global knob. |
| Accent user-swap | The accent is a single fixed GitHub blue (per §Colour system); light/dark theme is the only user colour axis. |
| Sub-output diff layout (`:unified` / `:split` toggle) | Fixed unified. |
| Section-grouping threshold | Engine retired wholesale per rf2-7is22 (#2235); app-db diff now runs Editscript A* directly with no separate section-grouping stage. |
| Popout as its own tab | Folds into General's Panel-position sub-section. |

Full wireframe + per-field configure! mapping in
[`018-Event-Spine.md`](./018-Event-Spine.md) §9. configure! API
surface in [`015-Configuration.md`](./015-Configuration.md).

## Frame-observation isolation invariants

Xray observes ANOTHER frame, NEVER itself. Four invariants
(enumerated in [`018-Event-Spine.md`](./018-Event-Spine.md) §8):

- **I1:** Frame picker excludes `:rf/xray` by default. Settings →
  View → Power user → "Show tool frames in picker" reveals it.
- **I2:** No Xray UI view reads from `:rf/xray` for data purposes.
  Dev-time lint asserts this on Xray mount.
- **I3:** Views panel render-attribution is scoped to selected frame
  ONLY. Render tracker tags each entry with `:owning-frame`.
- **I4:** Browser feature test asserts Xray-self-observation is
  disallowed. **Failure blocks merge.**

The test gate lives at
`tools/xray/test/day8/re_frame2_xray/isolation_test.cljs`. Runs
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
  callouts + the mode dropdown stay Inter too (Fraunces is scoped to L4
  panel headings).

All three faces ship as `local()`-only `@font-face` rules from
`theme/global-styles/font-faces-css`. No third-party HTTP fetch is
initiated; OS-installed copies resolve automatically, otherwise the
per-stack fallback chain (`tokens/sans-stack` / `tokens/mono-stack`
/ `tokens/display-stack`) takes over. Consuming projects that want
web-hosted copies inject their own `url()`-bearing `@font-face`
rules — CSS layers candidates by family + weight so host-side
declarations compose with the `local()` defaults.

### Sizes — one knob, whole scale (`--rf-xray-font-size`)

Every type-scale entry resolves through the **`--rf-xray-font-size`**
CSS custom property (rf2-n8i2c). The default value is `13px`,
published on `:root` by `theme/global-styles/motion-css`. Each entry
is `calc(var(--rf-xray-font-size, 13px) * <multiplier>)` where the
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
`tools/xray/src/day8/re_frame2_xray/theme/tokens.cljc`
(`type-scale-multipliers`) as pure data so the JVM test surface can
assert the relationship without parsing CSS. Below 10px: refused
(the `:micro` token sits at the floor).

#### Host override + density coupling

Hosts override the knob via a `:root` stylesheet rule —
`:root { --rf-xray-font-size: 14px }` rescales every typographic
surface ~1.08× without a code change. The default `13px` is also
published as the host-readable knob (`API.md` §CSS variables).

The Settings → General **Density** radio is the in-shell consumer
of the same var (rf2-i40us). The mapping lives in
`settings/effects.cljs §density->font-size-px`:

| Density | `--rf-xray-font-size` value | Notes |
|---|---|---|
| **Compact** | 12px | One step tighter than baseline |
| **Cosy** (default) | 13px | Anchor; matches `tokens/font-size-default` |
| **Comfy** | 14px | Catalogued for forward compat — radio surfaces only Compact / Cosy in v1 (Mike 2026-05-19) |

`effects/apply-density-font-size!` is the canonical writer. On every
density change it writes the resolved px value into
`--rf-xray-font-size` on **both** the Xray shell root (so inline
`calc(var(--rf-xray-font-size, 13px) * N)` resolutions inherit the
value) **and** `<html>` (so popout / fullscreen mounts that may not
be inside the inline shell root still inherit). The writer is
idempotent and a no-op when neither element is present (JVM test
runner). The same writer runs on boot from `apply-all!` so a
persisted density survives reload before first paint. Unknown
density keywords (e.g. a persisted `:comfy` payload from before the
v1 radio drop) coerce to `:cosy` — mirroring the `:rf.xray/density`
sub's normalisation.

This var is **distinct** from `--rf-xray-text-size`, the Settings
→ General Text-size slider's user-knob (rf2-9poxq, predates
rf2-n8i2c). Two CSS vars, two knobs, one shell — see
[`016-Auxiliary-Panels.md`](./016-Auxiliary-Panels.md) §Settings
popup for the disambiguation. The density radio writes
`--rf-xray-font-size`; the text-size slider writes
`--rf-xray-text-size`. Hosts that want a single density knob
target `--rf-xray-font-size` and leave the slider's var alone.

## Long-keyword treatment

Smart middle-elide + namespace fade + click-to-copy:

```
BEFORE:  :some.namespace.views.something/blah-blah-blah         (38 chars; overflows 560px)
AFTER:   :some.namespace…/blah-blah-blah  ⎘                     (with hover-copy icon)
         ^^^^^^^^^^^^^^^^                ^^^^^^^^^^^^^^^^^^
         text-tertiary 400                accent 600 (mode accent)
         (keep first ns segment; elide middle; keep keyword name)
```

Algorithm: when event-id exceeds N chars (compact 28; cosy 36;
configurable via Settings → View → Long-keyword threshold), elide
the middle of the NAMESPACE only. Keep first ns segment and the
keyword name (after `/`) intact. Un-namespaced keywords fall back to
tail-elide.

Helper lives in
`tools/xray/src/day8/re_frame2_xray/theme/keyword_render.cljs`.
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
Surfaces:  bg-0 #161616  (backdrop)
           bg-1 #1c1c1c  (sidebar, top strip — Figma --devtools-chrome-bg)
           bg-2 #242424  (panels)
           bg-3 #2a2a2a  (popovers — Figma --devtools-hover)
           bg-active #2a2a2a  (hover, selected)

Borders:   subtle #2a2a2a  · default #373737 (Figma --devtools-border)

Text:      primary #e6edf3 (Figma --devtools-text)  · secondary #adbac7  · tertiary #8b949e (Figma --devtools-text-muted)

Accents:   blue    #539bf5  ACCENT — active tab, chrome stripe, selected, focus ring, changed (the identity — rf2-ad7zx.13)
           info    #79c0ff  fixed cool categorical blue; :story / :test origin; spine-paused; syntax-number
           indigo  #5570FF  :pair-origin
           green   #3fb950  success, additions, machine-active
           yellow  #d29922  warnings, schema-replaced-with-default, :rf.size/large-elided elision
           amber   #FB923C  long-task / perf-slow (functional perf-amber)
           red     #F87171  errors, schema-violations, hydration-mismatches
           magenta #E879F9  classification: :rf/redacted

Perf:      fast     #3fb950  (<16ms)
           medium   #d29922  (16-50)
           slow     #FB923C  (50-100)
           blocking #F87171  (>100ms, INP threshold)
```

**Accent identity = GitHub-style blue (rf2-ad7zx.13).** The accent is the single GitHub blue
(`#539bf5` dark / `#0969da` light) the Figma export ships (the `devtools-css` block embedded in
`design-reference/xray_devtools_reference.cljs`).
There is **one** accent — active tab, chrome stripe, active states, focus ring, the L4 header
stripe, and the logo / wordmark all read it. The **Dynamic / Static MODE stays functional** (it
gates motion) but **no longer drives accent colour**: the shell reads the same blue in either
mode. The earlier orange-identity scheme (an always-orange `brand` + per-mode orange/cyan accent
swap) is **removed**. Every accent is a single CSS-custom-property token, so the identity is a
one-line change per token. The accent decision + the cross-panel **visual-encoding rules** live
in [022-Design-Tokens](022-Design-Tokens.md); the **full palette above is authoritative**.

Light theme inverts lightness (`bg-0 #fbfbfb`, `bg-1 #f5f5f5`, `bg-2
#ffffff`); the accent darkens to `#0969da` to maintain contrast.

### CSS custom-property surface (rf2-on4cm)

Every palette token is published as a `--rf-xray-<key>` CSS custom
property on `:root` (default = dark palette), `.rf-xray-theme-dark`,
and `.rf-xray-theme-light`. The shell-root class toggle written by
`settings/effects/apply-theme!` flips which block is in scope, so a
descendant reading `var(--rf-xray-bg-1)` resolves to the active
theme's hex without any per-component branching.

The 357+ inline-style call sites that read `(:bg-1 tokens)` consume
the canonical `theme.tokens/tokens` map — post the v1.0 sweep
(rf2-on4cm) every entry is a `"var(--rf-xray-<key>)"` string rather
than a literal hex, so every paint flows through the active class
scope automatically. The dark- and light-palette maps remain the
hex source of truth that `theme.global-styles/themes-css` reads to
emit the custom-property registrations.

Two consumer paths stay on the hex maps directly:

- `mount.cljs`'s popout opener-gone overlay (built imperatively in
  the popout window's document, which does not carry the Xray
  `<style>` injection) reads `theme.tokens/dark-palette` for its
  literal hexes.
- `config.cljc`'s `default-accent` publishes a literal hex INTO the
  `--rf-xray-accent` host-readable variable as its default value
  (`API.md` §CSS variables), so it must remain a hex string rather
  than a recursive var reference.

Where an alpha tint is needed, `theme.tokens/with-alpha` builds the
canonical `color-mix(in srgb, var(--rf-xray-<key>) <pct>%,
transparent)` string — CSS-Color-4 composition that picks up the
active-theme variable rather than concatenating an `#xxxxxx55` hex
tail.

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
pseudo-element on `[data-testid="rf-xray-shell"]`. Opacity `0.035`;
`mix-blend-mode: overlay` lets the grain blend additively against
both dark and light theme backgrounds. Zero extra DOM nodes — the
pseudo-element approach keeps the grain off the React tree entirely
and out of every panel's render graph. Under dark theme it reads as
a soft film grain over the recessed canvas; under light theme it
manifests as a subtle paper grain over the white canvas. The CSS
lives in `theme/global-styles/grain-css`; injection is via a single
`<style id="rf-xray-grain">` block, idempotent + id-keyed DOM
probe.

### L4 panel accent stripe (single accent — Figma design rf2-ad7zx.13)

Every L4 panel renders a **3-px left-border** on its `<h1>` in the **single `accent`** (GitHub
blue). The prior per-panel **domain-colour** mapping (`:event` violet · `:app-db`/`:views` cyan ·
`:trace` orange · `:machines` green · `:routing` yellow · `:issues` red) is **superseded**: the
Figma export carries a **single accent identity** (App's active tab + every panel reads
`--devtools-active` → the accent), so the stripe is a consistent signal, not a per-panel domain
colour. Surfaces stay neutral so the blue accent pops.

Domain colour still does load-bearing work **inside** each panel where it is semantic — `error`
red in Issues, machine `green`, route `yellow`, the op-family colour-bands in Trace (§021 §5.2),
the per-panel header icons (§021 §17.1.5) — but the **header stripe** is the single accent.

The helper `theme/tokens/accent-stripe-style` emits the inline-style map (`:border-left "3px
solid <accent>"` + `:padding-left "10px"`); per-panel call sites merge it into the `<h1>`
`:style`. This is the same accent as the chrome's 2-px ribbon-edge stripe (§Static mode below) —
the L1 stripe is the accent at chrome, this is the accent at the L4 header.

(rf2-4v67l — `:chrome-a11y` was dropped alongside the panel itself.
A11y dogfooding is now Story's concern per rf2-18t6p + rf2-qgms1.)

### Cascade gutter (rf2-5kfxe + the diff renderer)

The App-db diff renderer (in `views/edn_inspector.cljs`, driven by
the Editscript-backed projection from `diff/engine.cljc`) and the
`inspect-diff` mode of the detail-panel renderer both ship a
**per-node gutter**: a 3-px coloured left-border + glyph that
telegraphs the operation at a glance.

| Op | Glyph | Tone | Token |
|---|---|---|---|
| Added | `+` | green | `success` |
| Removed | `-` | red | `error` |
| Modified | `~` | amber | `warning` |
| Children (recursive descent) | `◴` | accent | `accent` (GitHub blue) |
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

Xray-specific custom glyphs: `◆` cascade root · `●` filled node · `○`
hollow node · `◉` selected node · `↺` rewind · `▥` whole-event
redacted · `⚠` exception badge · `🌐` HTTP badge · `🤖` machine badge.

## Motion + animation

Animation communicates, not decorates. Three durations:

| Tier | Range | Used for |
|---|---|---|
| **Quick** | 100ms | Hover, focus rings |
| **Standard** | 200–250ms | Tab switches, scrubber drag-snap, popover open/close |
| **Slow** | 400–600ms | Diff flashes, error pulses, the 320ms Xray slide-in |

Specific motions:

- **Tab cross-fade** (`@keyframes rf-xray-fade-in`, rf2-5kfxe.3):
  180ms ease-out, opacity 0 → 1 with a 2px translateY (the new tab
  rises *into* place rather than appearing statically). Subtle
  enough to feel like a settle, not a slide; characterful enough to
  read as a beat rather than a hard cut. Triggered by `^{:key
  selected}` on the L4 case-switch wrapper so a tab swap unmounts +
  remounts → keyframes auto-play from frame 0. Animation lives in
  `theme/global-styles/motion-css`.
- **Diff flash** (`@keyframes rf-xray-diff-flash`, rf2-5kfxe.2):
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
| `Ctrl+Shift+C` | Toggle Xray visibility |
| `Ctrl+Shift+M` / `Cmd+Shift+M` | Toggle Dynamic ↔ Static mode (`keybinding/mode-toggle-key?`, rf2-o5f5f.1) |
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
| `1` | Epoch |
| `2` | App-db |
| `3` | Views |
| `4` | Trace |
| `5` | Machines |
| `6` | Routes |
| `e` | Epoch (mnemonic) |
| `a` | App-db (mnemonic) |
| `v` | Views (mnemonic — incl. subs nested under each view) |
| `t` | Trace (mnemonic) |
| `m` | Machines (mnemonic) |
| `r` | Routes (mnemonic) |
| `Ctrl+→` / `Ctrl+←` | Next / previous tab |

### Detail panel (L4)

| Key | Action |
|---|---|
| `Tab` / `Shift+Tab` | Cycle focusables |
| `Esc` | Return focus to event list |

### Machines canvas (rf2-y3l8z)

The chart binds **no keyboard shortcuts** — see §Interactive Machines
canvas → Keyboard shortcuts. Pan / zoom / fit are mouse-wheel,
click-drag, and the xyflow `<Controls>` buttons only. The earlier
`+`/`=`/`-`/`_`/`0`/`f`/`F` + arrow-key map (on a `tabIndex=0` canvas
host) was pre-xyflow SVG-renderer fiction and did not survive the
rf2-gpzb4 migration (rf2-oc0fwy audit). A keyboard zoom/pan surface
would be a new feature, not a documented-existing one.

### Retired keys (from pre-rewrite spec)

- `f` (Effects) — Effects tab folded into Event; `f` retired.
- `s` (Subscriptions) — Subs panel folded into Views; `s` repurposed
  to open Settings popup.
- `c` (Causality) — Causality surface dropped entirely (rf2-y0z5b);
  `c` unused.
- `p` (Performance) — Performance panel dropped; `p` unused.
- `w` (Flows) — Flows folded into Views; `w` unused.
- `S` (Schemas) — schema violations surface inline in the Epoch panel
  (rf2-gbz39 removed the Issues tab per Option (c)); `S` unused.
- `h` (Hydration) — hydration mismatches surface inline in the Epoch panel
  (rf2-gbz39 removed the Issues tab per Option (c)); `h` unused.

## Detail panel renderer

Every value display in every tab's L4 detail panel uses
`tools/xray/src/day8/re_frame2_xray/theme/data_inspector.cljc`:

- `inspect <value>` — the hero: expandable inspector. Maps `{ … }`,
  vecs `[ … ]`, sets `#{ … }`, lists `( … )`. **Keywords are the single
  coloured type — the single `accent`** (GitHub blue), per
  [022-Design-Tokens](022-Design-Tokens.md) §Visual encoding + the §021 §10.1
  minimal-coloring lock; other scalars (strings / numbers / booleans / nil) render
  in `text-primary` mono, unchanged values in `dim`. Expand carets per node;
  default-collapse based on size.
- `inspect-inline <value>` — one-line variant; identical palette;
  forced single line; tail-elides at 80 chars.
- `inspect-diff <before> <after>` — diff variant; side-by-side or
  unified per `:layout`; colour-coded add/remove inline.

**Does NOT depend on `binaryage/cljs-devtools`.** That library targets
the Chrome console (formatters API); its output is not in-page hiccup.
Hand-built renderer matching the aesthetic using Xray's theme tokens.

### Renderer contract (v1 ships)

The cljs-devtools-shaped surface (rf2-x9fzk):

| Knob | Default | Purpose |
|---|---|---|
| `collapse-threshold` | `5` | Collections longer than this start collapsed; the user clicks `▶` to expand. Map literals ≤ 5 keys render flat; typical app-db slices don't dump every key on initial render. |
| `string-inline-cap` | `64` | Strings longer than this tail-ellide in `inspect-inline`; the full value remains visible via the parent collection's expand affordance. |
| `large-fetch-warn-threshold-bytes` | `100000` (100 KB) | Per [`018-Event-Spine.md`](./018-Event-Spine.md) §12 — `:rf.size/large-elided` expansions above this size gate behind a confirm step so a stray click can't pour a multi-megabyte expansion into the detail panel. |

**Colour palette** (mapped onto Xray's theme tokens so the renderer
reads as native shell chrome): **keywords are the single coloured type —
the single `accent`** (GitHub blue), per the §021 §10.1
minimal-coloring lock + [022-Design-Tokens](022-Design-Tokens.md); other
scalars render in `text-primary` mono, `dim` for unchanged. Punctuation +
meta render in `text-tertiary` / `text-secondary` to recede.

**Substrate-agnostic state.** Per the pure-hiccup contract
([Conventions rf2-tijr](./Conventions.md)) the renderer never
references Reagent / UIx / Helix. Per-node expand state lives in
`:rf/xray` app-db under `[:data-inspector <node-key> …]` and is
read/written via re-frame primitives:

- `:rf.xray.data-inspector/expansion <node-key>` — sub for one node's
  state.
- `:rf.xray.data-inspector/toggle-expanded <node-key>` — flip.
- `:rf.xray.data-inspector/request-large-confirm <node-key>` /
  `:rf.xray.data-inspector/confirm-large <node-key>` — two-step
  confirmation for `:rf.size/large-elided` markers above the size threshold.

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
- `{:rf.size/large-elided {:path [...] :bytes N :type <kw> :reason :schema :hint "…" :handle [:rf.elision/at <path>]}}` — yellow chip
  (`● large · N bytes · "hint…"`); click reveals an inline expansion
  surfacing the `:hint` text and routing a fetch via the `:handle`
  through `get-path`. Sizes above
  `large-fetch-warn-threshold-bytes` gate behind an inline confirm
  prompt (textual "Expand N bytes? (>100000 threshold)" + Confirm
  button) rather than a full modal — v1 ships the inline prompt so
  the renderer doesn't drag in modal infrastructure.
- `{:rf/redacted {:bytes N}}` — combined sensitive + large; magenta
  with size shown for diagnostic; **never** expandable (sensitive
  dominates content visibility).

## Data-classification rendering

Per [spec/015-Data-Classification](../../../spec/015-Data-Classification.md):

| Sentinel | Xray renders | Drillable | Affordance |
|---|---|---|---|
| `:rf/redacted` | `[● REDACTED N]` magenta | NO | Hover tooltip discloses path + mark source; **no reveal** |
| `:rf.size/large-elided {:path [...] :bytes N :type <kw> :reason :schema :hint "…" :handle [:rf.elision/at <path>]}` | `[● ELIDED · N bytes]` yellow | YES | Click → popover with `:hint` text + "Fetch full value" button that round-trips the marker's `:handle` through `get-path` (size-warned via confirm modal when bytes > threshold) |
| `:rf/redacted {:bytes N}` | `[● REDACTED · N bytes]` magenta | NO | Sensitive dominates; size disclosed |

Per-surface enumeration in [`018-Event-Spine.md`](./018-Event-Spine.md)
§12. The magenta and yellow hues MUST NOT collide.

## Editor protocol matrix

The `o` shortcut (and every `open` chip Xray renders next to a
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

- **Default editor.** When `:rf.xray/editor` is unset or `nil`, the
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
  registered OS handler, the click is a clean no-op at the OS level
  (the JS layer cannot observe the miss). For a host that has actually
  CONFIGURED an editor this is the correct best-effort behaviour. For
  an UNCONFIGURED host (the bare-preload `:vscode` default that neither
  the host nor the operator confirmed) the panel-side
  `:rf.xray/open-in-editor` event-fx MUST surface the
  unconfigured-host DX hint instead of the silent navigation — see
  [§Unconfigured-host DX hint](#unconfigured-host-dx-hint-rf2-4s08ov)
  below and
  [`015-Configuration.md` §Unconfigured-host DX hint](./015-Configuration.md#unconfigured-host-dx-hint-rf2-4s08ov).
- **Click vector.** The chip MUST invoke navigation by setting
  `window.location.href` (or rendering an `<a href>` and letting the
  browser follow it).

The single canonical implementation in
`re-frame.source-coords.editor-uri` MUST be the only URI builder; no
panel may inline its own URI assembly.

### Configuration

- The user picks the editor via the **Settings** popup (`,`) → View.
  Stored under the `:rf.xray/editor` config key.
- The boot-time entry is `(xray-config/configure! {:rf.xray/editor …})` per
  [`015-Configuration.md`](./015-Configuration.md) §`:rf.xray/editor`.
- Default: `:vscode` — the most-installed editor in 2026.
- The preference is **session-scoped**, persisted via the same Xray
  config substrate as theme / density. No cloud-sync.
- Xray's preference is **independent** of Story's `:rf.story/editor`.

### End-user override (rf2-dudqz)

Mixed-editor teams: the host app's `:rf.xray/editor` is project-wide,
but an individual operator MAY want a different click-to-source target
on their machine (e.g. a JetBrains user on a VS-Code-default team).
The **Settings popup → General tab → "Click-to-source links open in"
picker** is the per-machine override.

- **Slot:** `[:general :editor-override]` inside the persisted
  settings map. Persists via the same localStorage round-trip every
  other operator preference uses (`re-frame2.xray.settings.v1`); no
  new storage key.
- **Default:** `nil` — no override; the host's `:rf.xray/editor`
  default wins.
- **Accepted values:** identical to `:rf.xray/editor` — `nil`,
  `:vscode`, `:cursor`, `:windsurf`, `:zed`, `:idea`, or `{:custom
  "<tpl>"}`. The picker surfaces every enumerated keyword as a radio
  plus a "(project default)" radio (writes `nil`) plus a "Custom URI
  template" radio + text input (writes the `{:custom …}` shape).
- **Resolution order:** `config/get-editor` returns the FIRST
  non-nil tier of `[end-user-override → host default → :vscode]`.
  Selection is immediate — the next click-to-source affordance uses
  the override URI without a reload.
- **Reset:** the "Reset to project default" button clears the
  override (writes `nil`); the picker shows "Project default:
  <host-editor-name>" so the operator knows what flipping back
  lands on.
- **Scope:** purely client-side. The override does NOT mutate the
  host's atom and does NOT reach other browsers / tabs / users.
  Clearing the override falls back to the host's `configure!` value.
- **Security boundary:** the `{:custom …}` template still passes
  through the rf2-vwcsq scheme-rejection **denylist** at build time and
  again at the click-time `open!` seam (rf2-ox357n removed the prior
  positive allowlist — the framework spec mandates a rejection list, not
  an allowlist). A template that resolves to a forbidden script scheme
  (`javascript:` / `data:` / `vbscript:`) silently no-ops at the chip —
  the picker is NOT a route around the denylist. Other schemes,
  including `http:` / `https:` and unknown future-editor schemes, pass
  through (the residual `http:`-navigates-a-tab footgun is the accident
  the spec accepts; only the three script schemes are XSS vectors).

### Unconfigured-host DX hint (rf2-4s08ov)

A host that wires only the bare preload never sets `:rf.xray/editor`,
so click-to-source targets the framework default `:vscode`. The URI
resolves and navigation fires, but if VS Code is not the developer's
editor the OS has no `vscode:` handler and the click is a silent
no-op the JS layer cannot detect (the No-handler-installed fallback
above). rf2-ffijtp documented the fix; rf2-4s08ov makes the chip
itself guide the developer.

- **Trigger.** BOTH open-in-editor surfaces read
  `config/editor-configured?` and route through the same decision when
  it is false (NEITHER the host explicitly set `:rf.xray/editor` NOR a
  valid operator override is present): neither may fire the silent
  `:rf.editor/open` navigation.
  - The panel-side `:rf.xray/open-in-editor` event-fx dispatches
    `:rf.xray/editor-hint-show`.
  - The in-DOM `open-chip` `<a>` routes its `:on-click` through
    `open-in-editor/chip-click!` (rf2-r4q6y3), which dispatches
    `[:rf.xray/editor-hint-show]` on the `:rf/xray` frame when that
    shell frame is present, and falls back to the best-effort `open!`
    only when there is no `:rf/xray` frame for the toast to mount in
    (the standalone / static-host contract). This closes the gap where
    a Static-mode source chip could still silently navigate to the
    implicit `vscode:` URI on an unconfigured host.
- **Configured = navigate.** `editor-configured?` is true the moment
  EITHER the host calls `set-editor!` / `configure!` (an explicit set,
  even of `:vscode`, counts; `(configure! {:rf.xray/editor nil})`
  resets it — rf2-eilutf) OR a valid operator override exists. In
  that state the click resolves + navigates exactly as before; the
  hint never fires. A malformed override degrades to unconfigured.
- **The hint.** A small, non-intrusive bottom-corner toast ("No
  editor configured") with a one-line note and an **Open Settings**
  button. The toast is NOT a modal — it does not block the chrome. It
  self-dismisses on Open-Settings and is dismissable via the ✕ button
  or **Esc**. Because a non-modal `role=status` toast MUST NOT trap
  focus, the reachable Esc path is the shell-level global keydown
  listener (`keybinding/handle-keydown`, rf2-wpvy6f), which dismisses
  the hint when open and falls through otherwise.
- **Open-Settings.** `:rf.xray/editor-hint-open-settings` dismisses
  the toast and dispatches `:rf.xray/settings-open`, which lands the
  operator on the General tab — the editor picker's home.
- **State.** One boolean app-db slot `:editor-hint-open?` on
  `:rf/xray`; the `:rf.xray/editor-hint-open?` sub gates the toast
  mount (mounted at the shell-view root, sibling to the modals).

### Cross-references

- The shared URI builder lives at
  `implementation/core/src/re_frame/source_coords/editor_uri.cljc`
  (CLJC, JVM + CLJS portable; rf2-evgf5).
- Xray's mirror chip
  (`day8.re-frame2-xray.open-in-editor/open-chip`) consumes the
  same helper — see [`API.md` §Open in editor](./API.md#open-in-editor-rf2-evgf5).
- Story's matching surface — see
  [`tools/story/spec/005-SOTA-Features.md` §"Open in editor" per variant](../../story/spec/005-SOTA-Features.md).
- rf2-evgf5 — the chip implementation bead (Story + Xray).

## Command palette

Centred 560px modal, 50% height. Opened via the `Ctrl+K` / `Cmd-K`
chord (global; also reachable from the top-strip control). Closes
on `Esc`, click-outside, or invocation of any item.

### Indexed sources

- Recent events (200-entry buffer; matches event-id + source coord)
- Registered handlers (id + `:doc`)
- Frames
- Machines with current state
- L4 tab jumps — Dynamic: Epoch / App DB / Views / Trace / Machines
  / Routes; Static: Machines / Routes /
  Schemas / Flows / Interceptors (see §Mode-aware command surface below)
- Command verbs (recents-boosted; see §Command verbs below)
- Settings entries
- Pinned cascades (pin chips live in the palette as a "Pinned
  cascades" source, since the L0 rail is gone)

Fuzzy match splits on camelCase / kebab-case / namespace boundaries.

### Mode-aware command surface (rf2-ybjkx)

Every palette item carries a **`:modes`** set declaring which Xray
modes it surfaces under — `#{:dynamic}`, `#{:static}`, or
`#{:dynamic :static}` for verbs meaningful in both. The aggregator
(`palette/sources/by-mode-pred`) filters by membership against the
active `:rf.xray/mode`. Items missing `:modes` fall through to
both modes (the legacy contract — every item used to be visible
always).

The L4 tab-jump items are mode-aware so the **same mnemonic
letter** dispatches the active mode's tab. `m` in Dynamic jumps to
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
| `:toggle-theme` | Toggle theme (dark ↔ light) | `#{:dynamic :static}` | Flips the `rf-xray-theme-{dark,light}` class on the shell root. |
| `:cycle-reduced-motion` | Cycle reduced-motion override (OS → always → never) | `#{:dynamic :static}` | Three-state cycle: `:os` (OS pref alone) → `:always` (force reduce) → `:never` (force full). User override of `prefers-reduced-motion: reduce`; rides the `--rf-xray-motion-scale` seam in `theme/global-styles/motion-css`. Persists across reloads. |
| `:snapshot-app-db` | Snapshot app-db | `#{:dynamic :static}` | Dumps the focused frame's app-db to the JS console + clipboard for sharing. Both are off-box sinks, so the payload is routed through `runtime/egress-value` first (pinned to the focused frame) — sensitive ⇒ `:rf/redacted`, large ⇒ `:rf.size/large-elided`, fail-closed; the verb has no raw opt-in (rf2-mxzgg). |
| `:jump-to-settings` | Jump to Settings | `#{:dynamic :static}` | Equivalent to the `,` / `s` bare-key shortcut; available from the palette so the user can fuzzy-find the gesture without leaving the keyboard. |
| `:toggle-mode` | Toggle mode (Dynamic ↔ Static) | `#{:dynamic :static}` | Chord parity with `Cmd-Shift-M`; flips `:rf.xray/mode` between `:dynamic` and `:static`. |
| `:clear-epoch-history` | Clear epoch history | `#{:dynamic}` | Drops Xray's epoch snapshots (Dynamic-only — no epoch concept under Static). |

Pre-rf2-ybjkx verbs (clear-trace-buffer, reset-suppressed-counters,
open-popout, …) continue to surface under their original `:modes`
sets. The full catalogue lives in
`tools/xray/src/day8/re_frame2_xray/palette/sources.cljc`
§`command-items`.

### Recents (rf2-ybjkx)

Command invocations bubble through a **top-3 ring** persisted to
localStorage under `re-frame2.xray.palette.recents.v1`. The ring
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

The recents slot lives at `:rf.xray.palette/recents` on Xray's
app-db; the persisted vector hydrates on first palette open via
`recents/load`. The reducer (`recents/record`) is pure — `update +
distinct + take 3` — so the slot remains test-friendly.

### Modal + close behaviour

- **Esc** closes the palette unconditionally (no exceptions for
  in-flight fuzzy queries; mirrors the rest of Xray's modal
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
| `:os` (default) | Respect the OS pref alone — `@media (prefers-reduced-motion: reduce)` flips `--rf-xray-motion-scale` to ~0. |
| `:always` | Force reduced motion ON regardless of OS pref. |
| `:never` | Force reduced motion OFF regardless of OS pref. |

The override writes to a Xray-owned class on the shell root that
takes precedence over the OS media query, so the user can opt OUT
of system-level reduce-motion when developing motion-heavy
surfaces (the inverse use case is more common: developers on
default-reduce machines need to preview the full motion). Persists
to localStorage alongside the other Xray settings.

## Modal layers

Three modal surfaces float over the chrome:

1. **Command palette** — 560px centred.
2. **Keyboard cheat-sheet** (`?`) — 480px modal listing every
   shortcut.
3. **Settings** (`,` or `s` or `⚙`) — 560×640px modal with 6 sections.

### Shared modal-chrome scaffold (rf2-7oxvd)

**All** of Xray's modal/popover surfaces (Settings, Filter
edit-popup, Mute manager, App-DB segment-inspector, EDN-inspector
popup, Cancellation-cascade popover, **Command palette**) render the
**same backdrop + dialog scaffold** — a full-inset click-to-dismiss
overlay wrapping a WAI-ARIA dialog box that carries `role="dialog"` +
`aria-modal="true"` + an accessible name (via `aria-label` or
`aria-labelledby`) + the focus-trap contract (focus-on-open,
Tab/Shift+Tab trap, restore-on-close — see `theme/a11y`). That
genuinely-identical scaffold is extracted to
`day8.re-frame2-xray.theme.modal-chrome`; each modal supplies its own
divergent bits (dim colour / blur / alignment / z-index, dialog size,
header / body content, Esc / mnemonic key handling, accessible name) as
**slots/props, never flags**. Each modal's `data-testid`s, ARIA
attributes, z-stacking and dismiss behaviour are unchanged.

The **command palette** was the eighth and last surface to adopt the
scaffold (Mike ruled Option A — full consolidation). Its apparent
divergences all land on existing slots without a per-palette flag:
always-`:fixed` (literal `:positioning :fixed` — it has no Story testbed
cell), Esc handled inside its `:auto-focus` text input (it passes no
chrome keydown handler, the edit-popup pattern), its `data-rf-xray-mode`
marker via `:dialog-extra`, and its hand-rolled combobox/listbox ARIA
staying in the dialog `children` while the dialog-level role/aria-modal/
accessible-name comes from the shared `a11y/dialog-attrs`. Adoption also
gives the palette the focus trap it previously lacked — the trap
intercepts only Tab/Shift+Tab and the palette's sole focusable is the
input (rows drive a virtual `aria-activedescendant` cursor, not real
focus), so Tab wraps to the input and the arrow-key navigation is
untouched.

## Discoverability

Three layers, no onboarding tour:

1. **The `?` cheat-sheet.** Modal showing every shortcut.
2. **Empty-state hints.** Each empty state shows a contextual keyboard
   hint.
3. **The command palette itself.** Typing `?` in the palette filters
   to commands and shows their shortcuts.

## Bundle splitting

Per-tab lazy loading via shadow-cljs's per-output-target slicing:

- Core (UI shell + ribbon + event list + Epoch tab + App-db tab):
  <1.5 MB minified / <500 KB gzipped.
- Machines tab (includes the ELK+SVG chart primitive that absorbed
  `tools/machines-viz/`): <400 KB extra, lazy-loaded on first open.
- Views tab: <100 KB extra, lazy-loaded on first open.

## Performance budget

**Opening Xray must not change observable INP** on a typical app.

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
Xray shows a yellow top banner: "Xray is enabled in this build.
Disable for production." Single-click dismiss, remembered for the
session.

## Mountable panel contract (rf2-crhr8)

Every Xray panel is **independently mountable**. The 4-layer shell
COMPOSES panels but does NOT own them — panels are reachable as
stand-alone mount targets so a host can drop one panel into Story
ribbons, the Scittle playground (per rf2-i8mv option-c progressive
disclosure), the docs / guide surface, or custom debugging setups
without bringing along the rest of the shell chrome.

The mount surface lives in
`day8.re-frame2-xray.panels` — one mount fn per panel, plus
a master `mount-shell!` for the full 4-layer chrome. This per-panel
surface is **internal-but-stable**, NOT a v1.0 host-facing embed
contract: the 4-layer shell + the test suite depend on it and hosts
MAY use it, but it carries no host-facing-contract guarantee (the
only opt is `:frame`) — the v1.0 host-facing embed contract is the
**full-shell** embed per
[`008-Embedding-Contract.md`](./008-Embedding-Contract.md)
§Full-shell embed contract. (rf2-jw2ny — one honest status across
`panels.cljs`, `008`, and `API.md`.)

### Mountable surface inventory

> **Single source of truth (rf2-rapnr).** The mount-fn column of the
> tables below is a PROJECTION of the authoritative panel enumeration
> in `day8.re-frame2-xray.panel-enum/panel-enum`. A drift between this
> inventory, the `mount-<panel>!` facade in
> `day8.re-frame2-xray.panels`, and the api-manifest `:cljs-only` rows
> goes RED in CI via the single-source guard
> (`tools/xray/test/day8/re_frame2_xray/panel_enum_guard_cljs_test.cljs`).
> Adding / removing / renaming a panel starts with a one-line edit to
> the enum; this table follows. See the `panel-enum` ns docstring.

The Xray panel-surface inventory totals **15 surfaces** across five
tiers — **13 are independently mountable** via a `mount-<panel>!` fn
(the panel-enum set), and **2 are internal sub-components** that render
under their owning panel and expose no standalone mount fn. The split:

- **Tier 1 — L3 tab panels (7):** one per L3 detail-panel tab.
- **Spine — embeddable event spine (1):** the L2 event list mounted
  standalone (`008-Embedding-Contract.md` §Embeddable event spine).
- **Tier 2 — overlay / popup surfaces (3):** modal-light surfaces
  the shell composes at its root.
- **Tier 3 — inline content surface (1):** the managed-fx
  wire-boundary diff template embedded in the Epoch panel's
  "EFFECTS HANDLERS RAN" section.
- **Full shell — master entry (1):** `mount-shell!`, the full 4-layer
  chrome that composes every panel above.
- **Tier 4 — internal sub-components (2):** auxiliary inspectors
  geometry-coupled to `machine-inspector/Panel` (after-rings
  overlay, sim side-rail) — NOT in the panel-enum set.

The 7 + 1 + 3 + 1 + 1 mountable surfaces sum to the **13** entries of
`panel-enum`; adding Tier 4's 2 internal sub-components reaches the
**15-surface** total. Modal overlays managed by the shell (Settings
dialog, command palette, share modal) are NOT counted here — they are
shell chrome, not panel content.

**Tier 1 — L3 tab panels (7):** one per `:rf.xray/selected-tab`
value. (Post rf2-5gl5r the Event/Handler tab was retired in favour
of the Epoch tab; post rf2-gbz39 the Issues tab was removed per Mike's
Option (c) ruling — issues surface inline in the Epoch panel + the L2
event-row pink-wash + the always-on issues ribbon signal. The Epoch
panel renders the focused epoch's full computational timeline as a
numbered vertical cascade per
[`021-Dynamic-Panel-Designs.md` §9.1](./021-Dynamic-Panel-Designs.md#91-the-epoch-panel-numbered-cascade--rf2-sc3r1).
The Resources tab — the declarative-server-state lens (Spec 016 §Xray
and AI tooling) — earns its own L3 tab after Routing per Mike's
cohesive-sub-domain ruling; server-state is a sub-domain, not an
App-db section.)

| Panel | View | Mount fn |
|---|---|---|
| Epoch tab    | `epoch-panel/Panel`      | `mount-epoch-panel!` |
| App-db tab   | `app-db-diff/Panel`      | `mount-app-db-diff!` |
| Reactive tab | `reactive-panel/Panel`   | `mount-reactive-panel!` |
| Trace tab    | `trace/Panel`            | `mount-trace!` |
| Machines tab | `machine-inspector/Panel`| `mount-machine-inspector!` |
| Routing tab  | `routing/Panel`          | `mount-routing!` |
| Resources tab | `resources/Panel`       | `mount-resources!` |

**Spine — embeddable event spine (1):** the L2 event list mounted
standalone — the SAME `shell/event-list` reg-view the full shell
composes at L2. See [`008-Embedding-Contract.md`](./008-Embedding-Contract.md)
§Embeddable event spine for the contract.

| Panel | View | Mount fn |
|---|---|---|
| Event spine | `shell/event-list` | `mount-event-spine!` |

(rf2-gbz39 — the Issues tab + its `issues-ribbon/Panel` + `mount-issues-ribbon!` were removed per Mike's Option (c) ruling; issues surface inline in the Epoch panel + the L2 event-row pink-wash + the always-on issues ribbon signal. The `:rf.xray/issues-ribbon` projection survives in `registry.cljs` as the ribbon signal's data source.)

**Tier 2 — overlay / popup surfaces (3):** modal-light surfaces the
shell composes at its root, each self-gating on a `:rf.xray/*-open?`
sub (closed-state cost is one subscribe + a `when` short-circuit).

| Panel | View | Mount fn |
|---|---|---|
| App-DB segment-inspector popup | `app-db-segment-inspector/Popup`   | `mount-segment-inspector!` |
| Cancellation-cascade side-panel | `cancellation-cascade/SidePanel`  | `mount-cancellation-cascade-side-panel!` |
| Cancellation-cascade popover    | `cancellation-cascade/Popover`    | `mount-cancellation-cascade-popover!` |

**Tier 3 — inline content surface (1):** the managed-fx
wire-boundary diff template originally embedded inline under the
retired Event/Handler panel's six-domino cascade view (rf2-5gl5r);
the mount fn survives as the standalone surface for Story ribbons
that want JUST the managed-fx list for the focused cascade.

| Panel | View | Mount fn |
|---|---|---|
| Managed-fx records list | `panels/ManagedFxList` | `mount-managed-fx!` |

**Full shell — master entry (1):** `mount-shell!` mounts the complete
4-layer chrome (ribbon + event-list + tab-bar + detail-panel),
composing every panel above. The same mount path `mount.cljs/open!`
uses for the default in-app `[data-rf-xray-host]` mount; exposed here
so hosts that own their DOM (Story, custom dev surfaces) mount the full
shell at any element.

| Panel | View | Mount fn |
|---|---|---|
| Full 4-layer shell | `shell/shell-view` | `mount-shell!` |

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
scrubber sub-components were collapsed into the Dynamic panel; the
remaining sub-component surface is the two listed above.)

### The mount-fn contract

Every `mount-<panel>!` fn:

1. Calls `(registry/register-xray-handlers!)` — idempotent install
   of every panel's subs / events / fxs. The orchestrator's
   `defonce`-guarded sentinel collapses repeat installs across
   panel mounts and shadow-cljs `:after-load` cycles.
2. Calls `(rf/reg-frame :rf/xray {})` — idempotent register of
   Xray's state-isolation frame. `reg-frame`'s surgical-update-on-
   re-register semantics (per Spec 002 §reg-frame) keep this
   idempotent.
3. Wraps the panel's view in `[rf/frame-provider {:frame :rf/xray}
   [Panel]]` so descendant `subscribe` / `dispatch` re-anchor to
   `:rf/xray` regardless of the host's React-context. The
   `:rf/xray` default may be overridden via `opts {:frame
   :my-app/frame}` per the embedding contract
   ([008-Embedding-Contract.md](./008-Embedding-Contract.md) §State
   isolation).
4. Delegates to `substrate-adapter/render` with the wrapped tree +
   `mount-point`. Xray is substrate-agnostic; the host installs
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
| **epoch-panel**    | `:rf.xray/focus` · `:rf.xray/epoch-history` (via `panels.shared.focus-resolver`) | `:rf.xray.epoch/toggle-row-expand` · `:rf.xray.epoch/set-subs-filter-mode` · `:rf.xray.epoch/set-db-diff-mode` |
| **app-db-diff**    | `:rf.xray/app-db-state` (← `:rf.xray/app-db-current+diff`; rf2-p53m2 — the `:rf.xray/app-db-diff` composite was pruned) | `:rf.xray/focus-slice-path` · `:rf.xray/open-segment-inspector` |
| **views**          | `:rf.xray/views-focused-cascade-pair` · `:rf.xray/views-sub-diff` | view-row toggles · sub-diff selection |
| **trace**          | `:rf.xray/trace-feed` (incremental projection) | `:rf.xray/select-dispatch-id` · `:rf.xray/open-in-editor` |
| **machine-inspector** | `:rf.xray/machine-chart-data` · `:rf.xray/active-timers-for-focused-machine` · `:rf.xray/machine-scrubber-position` | scrubber events · `:rf.xray/focus-cascade` |
| **routing**        | `:rf.xray/registered-routes` · `:rf.xray/current-route-slice` · `:rf.xray/routing-tab-data` | route-simulation events |
| **segment-inspector** | `:rf.xray/segment-inspector-open?` · `:rf.xray/segment-inspector-value` | `:rf.xray/close-segment-inspector` |
| **cancellation-cascade** | `:rf.xray/cancellation-cascade-for-focused-machine` · `:rf.xray/cancellation-cascade-for-focused-event` · `:rf.xray/cancellation-cascade-popover-open?` · `:rf.xray/modal-positioning` | `:rf.xray/cancellation-cascade-close` |
| **managed-fx**     | `:rf.xray/managed-fx-for-focused-event` | `:rf.xray/focus-event` |

No panel reads sibling-panel state directly. No panel assumes any
particular frame-picker / tab-bar / event-list / spine-head value
beyond what the spine sub `:rf.xray/focus` exposes — and `focus`
itself defaults to head of the trace buffer when no row is selected.
Each panel is fully driven by the trace bus + the host's
`(rf/init!)` plumbing.

### Shell composes, doesn't own

The 4-layer shell (`shell.cljs`) **composes** panels by referencing
each panel's `Panel` reg-view in the L4 detail-panel case-switch,
and mounts the Tier 2 overlay surfaces at the shell-view root for
modal layering. The shell does NOT own per-panel state — each panel
reads and writes its own slice of `:rf/xray`'s app-db via its own
`install!`-registered handlers.

This separation is what makes per-panel mountability possible: any
host that wants ONE panel mounts that panel directly via
`mount-<panel>!`; the shell is just one specific composition of all
of them.

### Hot-reload + idempotency

`register-xray-handlers!` is `defonce`-guarded so shadow-cljs
`:after-load` cycles do not re-register handlers (which would emit
`:rf.warning/handler-replaced` traces on every reload). `reg-frame`
is idempotent via surgical-update semantics. Mount fns can be called
from a host's `init!` path at any frequency without risk.

## Static mode (rf2-o5f5f)

Xray exposes TWO modes — **Dynamic** (the event-coupled spine + 4-layer
chrome described above) and **Static** (event-INDEPENDENT browse of
what's registered). Static is "Xray-in-a-quieter-key": it shares the
full Dynamic design language (Inter + JetBrains Mono, the complete
`theme/tokens.cljc` palette, the 4px spacing grid, the 56px ribbon, the
40px tab-bar). Differentiation is **temperature, not vocabulary**.

### Surface inventory (3-layer chrome)

Dynamic is 4 layers (L1 ribbon · L2 event list · L3 tab bar · L4 detail
panel). Static drops L2 — there is no spine in Static mode because the
surface is event-independent — and renders 3 layers:

    ┌───────────────────────────────────────────────────────┐
    │ L1  Chrome ribbon — frame picker · mode dropdown · ⚙ ✕ │
    ├───────────────────────────────────────────────────────┤
    │ L3  Tab bar (40px) — 5 tabs                           │
    ├───────────────────────────────────────────────────────┤
    │ L4  Detail panel (fills remaining canvas)             │
    └───────────────────────────────────────────────────────┘

L2's absence is also a functional signal — see §Mode-signal mechanism
below.

The L1 frame picker is **mode-independent**. Per [Spec 001](../../../spec/001-Registration.md)
the registrar is **process-global** — frames isolate *state*, not
*registrations* — so event / sub / route / interceptor /
machine-definition catalogues are shared across every frame and read
the same regardless of the picker. What the picker scopes is the
genuinely per-frame surface each Static panel projects:

| Tab | Frame-scoped (picker changes it) | Process-global (cross-frame) |
|---|---|---|
| **Machines** | live machine snapshots (the `:rf/machines` runtime area — `[:rf.runtime/machines :snapshots]` in the target-frame **runtime-db**, EP-0001 rf2-vzld77) | the machine-definition catalogue |
| **Routes** | the current-route slice (the `:rf/route` runtime area — `[:rf.runtime/routing :current]` in the target-frame **runtime-db**, EP-0001 rf2-vzld77) | the route-definition catalogue |
| **Schemas** | the app-db-schema side-table (`schemas-by-frame`) | event-spec + sub-spec rows |
| **Flows** | the flows registry (`{frame-id {flow-id …}}`, [Spec 013](../../../spec/013-Flows.md)) | — (fully per-frame) |
| **Interceptors** | — | interceptor chains (live on globally registered events). A chain entry is an inline value OR a by-reference entry (bare keyword / `[id arg]`) into the `:interceptor` registrar (EP-0022); the lens surfaces refs by their authored form and enriches each from the registered descriptor. |

So switching the picker changes the per-frame projections above; the
global catalogues are deliberately cross-frame. The picker stays in
Static because four of the five tabs DO carry a per-frame surface.
Both shells mount the same `frame_switcher/frame-switcher-view` (the
canonical L1 contract); the selection persists across mode toggles.
Dynamic's spine-coupled clusters (nav `[◀ ▶ ⏭]`, filter pills) remain
hidden in Static — those have no meaning without a spine.

### Sub-tab inventory (Static L3)

Five Static sub-tabs, mode-scoped mnemonics per the findings doc
`ai/findings/2026-05-19-xray-explorer-mode.md` §5.2:

| Tab | Mnemonic | Bead | Contents |
|---|---|---|---|
| **Machines**     | `m` (default) | rf2-o5f5f.2 | Registry browse + Topology + 4-mode sub-strip |
| **Routes**       | `r` | rf2-o5f5f.3 | Registered routes (promoted from Dynamic) + Simulate-URL |
| **Schemas**      | `c` | rf2-o5f5f.4 | Registered schemas + sample data + jump-to-source |
| **Flows**        | `f` | rf2-uhsqb   | Registered flows catalogue |
| **Interceptors** | `i` | rf2-o5f5f.6 | Pure-browse lens over interceptor chains — ref-aware (EP-0022): surfaces by-reference entries (keyword / `[id arg]`) alongside inline values, enriched from the `:interceptor` registrar |

rf2-b2fif removed the Views + Events sub-tabs (info already in the
source code; the tabs were not pulling their weight).

Mnemonic mode-scoping: the same letter dispatches the active mode's
tab — `m` in Dynamic opens the Machines instance-inspector, `m` in
Static opens the Machines registry browse.

### Mode-signal mechanism (4 stacked signals)

The user reads Static at a glance via four stacked signals — together
they telegraph the mode without the user needing to look at the dropdown:

1. **Mode dropdown** at chrome-ribbon-left — a compact `<select>`
   (`Dynamic ▾` / `Static ▾`), rf2-4vp5j (replaced the old 160px
   two-segment radio pill — too dominant for an occasional-use control).
   Lives in both modes (it's the toggle, not the indicator). Cmd-Shift-M
   (the global chord) fires the same `:rf.xray/toggle-mode` event so
   chord and dropdown share the handler. The mode SIGNAL is carried by
   the dropdown's active option + `data-active-mode` (the stripe is the
   single accent in both modes — rf2-ad7zx.13).
2. **2-px left-edge ribbon stripe** — the single `accent` (**GitHub blue**)
   in both modes (per §Colour system + [022-Design-Tokens](022-Design-Tokens.md);
   rf2-ad7zx.13 — the Figma export carries one accent, no per-mode colour
   swap). The stripe is a one-token chrome-edge accent.
3. **Motion dampening** — Dynamic ships the LIVE pulse + machine-active
   pulse + 180ms tab fade. Static drops the continuous pulses entirely
   and collapses the 180ms tab fade to instant (so cluster swaps land
   without motion). Honours `prefers-reduced-motion: reduce` via the
   `--rf-xray-motion-scale` seam in `theme/global-styles/motion-css`.
4. **Chrome silhouette** — Dynamic is 4-layer; Static is 3-layer (no
   L2 / no spine). The shape itself is a signal.

### Mode-state lifecycle

The mode slot lives on Xray's app-db at `[:rf.xray/mode]`
(`:dynamic | :static`); the Static-scoped tab choice lives at
`[:rf.xray.static/selected-tab]` (default `:machines`). Three event
handlers drive the lifecycle:

- `:rf.xray/set-mode` — writes a specific mode (mode-dropdown
  selection, hydration after localStorage read, test fixtures).
- `:rf.xray/toggle-mode` — flips between modes (the Cmd-Shift-M
  chord — see `keybinding.cljs`).
- `:rf.xray.static/select-tab` — flips the Static-scoped tab
  (independent of the Dynamic `:rf.xray/select-tab` slot so flipping
  modes preserves both choices).

Set + toggle attach the `:rf.xray.static/persist-mode` fx so every
mutation round-trips through localStorage under the canonical key
`xray.mode`. Unknown / malformed values normalise back to
`:dynamic` — the conservative default.

### Frame isolation

Same discipline as the Dynamic shell. The Static surface composer
inside `shell.cljs` is wrapped in `[rf/frame-provider {:frame
:rf/xray}]`; every subscribe + dispatch inside the surface resolves
to `:rf/xray`. Each subscribing region is `reg-view`-registered so
its rendered component carries `:contextType frame-context` (rf2-in6l2
+ Spec 004 §Plain Reagent fns do not pick up the surrounding frame).

### Availability

Static mode is unconditionally available. The surface composer reads
`:rf.xray/mode`, the mode dropdown mounts at chrome-ribbon-left in every
host, and the Cmd-Shift-M / Ctrl-Shift-M chord drives the toggle. Per
rf2-8l3uk the prior `:rf.xray/static-mode?` opt-in feature gate was
removed (pre-alpha posture — back-compat shims are out of scope; if
Static mode is useful, expose it unconditionally).

### Mode bifurcation rule (rf2-qgnle)

> **Dynamic and Static are parallel modes by design, not legacy
> duplication.**
>
> The two shells (`shell.cljs` and `static/shell.cljs`) deliberately
> stand alongside each other rather than collapse into one
> mode-axis-parameterised component. The split is the architecture; the
> rules below lock how mode-divergent surface area is structured so a
> future third mode (or a fourth) slots in symmetrically rather than
> compounding.

**Namespace rule.** Shared state — anything Dynamic and Static read or
write identically — lives under bare `:rf.xray/<key>`. Mode-keyed
slots — anything that diverges between the two modes — live under
`:rf.xray.<mode>/<key>`. The canonical pair today is
`:rf.xray/selected-tab` (Dynamic's tab choice; uses the shared root)
vs `:rf.xray.static/selected-tab` (Static's tab choice, default
`:machines`). A future Dynamic-keyed slot that needs to coexist with a
Static counterpart MAY migrate from `:rf.xray/*` to
`:rf.xray.dynamic/*`; until that happens, the shared root IS
Dynamic's default surface.

**Shell ns rule.** Dynamic's shell is
`tools/xray/src/day8/re_frame2_xray/shell.cljs`. Static's shell is
`tools/xray/src/day8/re_frame2_xray/static/shell.cljs`. Each shell
owns its own tab inventory, its own ribbon composition, and its own
chrome silhouette (see §Surface inventory above — Dynamic is 4-layer
with L2; Static is 3-layer without). The composer (`surface-composer`
in `shell.cljs`) `case`-dispatches between the two on `[:rf.xray/mode]`.

**Tab inventory rule.** Tab inventories are mode-keyed and not shared.
Dynamic ships 9 tabs (Epoch / App DB / Views / Trace / Machines /
Routing / Resources / Graph / Modules — see
[`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md)
for the per-panel content designs + [`018-Event-Spine.md`](./018-Event-Spine.md)
§The 9 tabs for the live registry ids; the Event/Handler tab was retired
by rf2-5gl5r when the Epoch panel reached parity; the Issues tab was
removed by rf2-gbz39 per Mike's Option (c) ruling — issues surface
inline in the Epoch panel + the L2 event-row pink-wash + the always-on
issues ribbon signal. Resources / Graph / Modules are the
cohesive-sub-domain L4 lenses added per EP-0016 / EP-0014 / EP-0013;
Graph + Modules are L4-only registry tabs with no standalone `mount-*!`
facade). Static ships 5 tabs (Machines /
Routes / Schemas / Flows / Interceptors — see §Sub-tab inventory
above). New tabs MUST declare which mode(s) they belong to; tab-id
keyword collisions across modes (`:machines`) are deliberate and
resolved by the active-mode dispatch, not by renaming. Mnemonic
collisions across modes (`m` · `r`) are likewise resolved by the
mode-scoped resolver (Cmd-Shift-M flips the active mode; the letter
then dispatches the active mode's tab — see §Keyboard).

**Shared-token rule.** Design tokens — colours, spacing, typography,
motion — live ONCE in the HCM token registry (`theme/tokens.cljc`,
`theme/global-styles/*`) and apply across both modes. Visual cohesion
between Dynamic and Static is achieved at the token layer, not by
shell-ns reuse. The mode-signal mechanism (mode dropdown, 2-px ribbon
stripe, motion-dampening, chrome silhouette — see §Mode-signal mechanism
above) reads from tokens. Post rf2-ad7zx.13 both shells paint the same
single `accent` (GitHub blue) stripe — the divergence is in motion +
chrome silhouette, NOT in stripe colour (the Figma export carries one
accent). **Zero new tokens introduced per mode** is the standing
constraint.

**Cycle-avoidance rule.** When one shell needs to reach into another
mode's chrome (the canonical case today: Static's ribbon needs the
right-icons cluster originally authored in Dynamic's `shell.cljs`),
**inline the affected cluster locally** in the borrowing shell rather
than `:require` across modes and form a cycle. The cost is a small,
acknowledged duplication (today: `static/shell.cljs`'s
`ribbon-right-icons` mirrors `shell.cljs`'s same-named cluster — see
the comment block at the inline). The gain is that each shell stays
independently buildable, has no compile-time dependency on its
sibling, and can drift cleanly when one mode's chrome evolves ahead of
the other. The duplication is a feature, not debt — it is the
mechanism that keeps the bifurcation honest.

**Consequence.** Adding a third mode (a hypothetical `:debug`,
`:simple`, etc.) follows the same shape: a new
`tools/xray/src/day8/re_frame2_xray/<mode>/shell.cljs`; a new
`:rf.xray.<mode>/*` namespace for mode-keyed slots; the composer's
`case` gains a new branch; tokens stay shared; any borrowed chrome
clusters inline locally. The pattern scales linearly per added mode;
the per-mode shell duplication is the predictable cost. **No further
mode-related work may unify the shells or share mode-divergent
state under the bare `:rf.xray/*` root** — both moves contradict
this rule.

### See also

- [`008-Embedding-Contract.md`](./008-Embedding-Contract.md) — the
  full-shell embedding contract for Story / first-party embeds (Story
  mounts the full Xray shell with `:rf.xray/keybinding-enabled?
  false`).
- [`011-Launch-Modes.md`](./011-Launch-Modes.md) — the default
  in-app shell-mount path via `[data-rf-xray-host]`.
- [`Conventions.md`](./Conventions.md) §Panel facade + leaf split —
  the canonical per-panel facade shape (`Panel` reg-view +
  `install!`) every mount-fn target adheres to.
- [`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md) —
  the per-panel content designs for the Dynamic L4 panels (Epoch
  is the §9.1 design; rf2-5gl5r retired the Event/Handler panel
  that previously occupied §2 of this doc; rf2-gbz39 removed the
  Issues tab — issues now surface inline in the Epoch panel + the
  L2 event-row pink-wash + the issues ribbon; the Resources / Graph /
  Modules lenses were added per EP-0016 / EP-0014 / EP-0013); the
  per-panel companion to the Mode bifurcation rule above.
