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
| **Machines** (`m`) | "What did this event do to my machines?" — transitions, cancellation cascade, `:after` rings. |
| **Issues** (`i`) | "What's wrong here?" — errors · warnings · schema violations · hydration mismatches · advisories. |

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
│ LAYER 3  Tab bar (40px) — 6 tabs                                        │  projection selector
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
│ ◉Event  ○App-db  ○Views 8  ○Trace 47  ○Machines 1  ⚠Issues 1            │   L3 — 6 tabs
├─────────────────────────────────────────────────────────────────────────┤
│ — Event tab content for the focused event —                             │   L4 — fills the rest
└─────────────────────────────────────────────────────────────────────────┘
```

The four layers, top to bottom:

1. **L1 — Top ribbon (56px).** Five clusters: nav (`◀` `▶` `⏭`) ·
   frame picker · filter pills (IN/OUT) · mode pill (`● LIVE` / `◐
   RETRO`) · right-icons (settings `⚙` + popout `⛶` + close `✕`).
   Anatomy in §The L1 ribbon below.
2. **L2 — Event list.** 8 single-line rows default; vertically
   resizable (min 2); latest-on-bottom; virtualised. Single row shape
   decorated by gutter glyph (`● ◉ x ▥ ↺`) + right-aligned badges (`⚠`
   `🌐` `🤖`) + trailing redaction marker (`[● REDACTED N]`). The
   spine sub `:rf.causa/focus` reads from this layer.
3. **L3 — Tab bar (40px).** Six tabs: Event / App-db / Views / Trace /
   Machines / Issues. Letter mnemonics: `e` `a` `v` `t` `m` `i`. Count
   badges (`Views 8`) update with focused cascade.
4. **L4 — Detail panel.** Fills remaining canvas (60% default;
   resizable via L2/L3 drag handle). Per-tab content; all values
   rendered via the cljs-devtools-shaped renderer (see §Detail panel
   renderer).

**No bottom rail.** The pass-2/round-1 "L0" rail (with scrubber +
mode pill + classification totals) is gone — the ribbon's `[◀ ▶ ⏭]`
cluster IS the seek, the event list IS the timeline, the mode pill
lives in the ribbon right-cluster, classification totals live in the
mode-pill tooltip + per-row markers.

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
- Drag-to-update width with global mouse-capture
- Clamp width to `[320px, 90vw]`
- Persist via `configure! :settings :general :panel-width-px`
  (see [`015-Configuration.md`](./015-Configuration.md))
- Reset to default width on double-click

No textual affordance accompanies the handle (cursor change is sufficient
signal per [`Conventions.md`](./Conventions.md) §UI text — silent by
default). The resize handle is a worked example of "non-obvious
affordance with iconographic alternative": discovery is via cursor
change on edge hover, not via prose label.

In `:popout` panel-position the browser's window controls govern size
(no in-panel handle renders). In `:fullscreen` position the handle is
suppressed — the panel fills the viewport.

## The L1 ribbon

Five clusters, fixed order left to right:

| Cluster | Width | Content | Keys |
|---|---|---|---|
| **Nav** | 84px | `◀` back-one-event · `▶` forward-one-event · `⏭` fast-forward-to-latest (snap head + resume LIVE) | `j` / `k` / `G` |
| **Frame** | flex 0 1 200px | `Frame: :app/main ▾` dropdown (multi-frame); flat `Frame: :rf/default` label when single-frame. **Single-select only.** Tool frames hidden unless Settings → View → "Show tool frames in picker" toggle on. | — |
| **Filter pills** | flex 1 1 auto | IN pills (green `+`) + OUT pills (magenta `×`) + trailing `[+]` add-pill. Click any pill → edit popup. | `/` focus add-pill |
| **Mode pill** | 80px | `● LIVE` (green, 2s pulse) / `◐ RETRO @ #N` (cyan, static). Tooltip on hover shows `[● REDACTED N · ● ELIDED M]` classification totals. | `L` snap-LIVE, `Space` pause/resume |
| **Right-icons** | 96px | `⚙` settings popup · `⛶` popout (`window.open` whole shell) · `✕` close shell | `,` or `s` · `o` · `Esc` |

Full anatomy + filter-pill edit popup in
[`018-Event-Spine.md`](./018-Event-Spine.md) §3 + §7.

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
- **Mode pill: `● LIVE`** with 2s pulse.

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

Three settings: **compact** / **cosy** / **comfy**. Default **cosy**.
Density is a vertical-rhythm knob, not a redesign — applies to L2 row
height + L4 row padding + base type token.

| Setting | L2 row height | L4 vertical rhythm | Body type |
|---|---|---|---|
| **Compact** | 22px | tighter | -1px |
| **Cosy** (default) | 28px | (baseline) | (baseline) |
| **Comfy** | 36px | looser | +1px |

What does *not* change between densities: icon weights, border radii,
animation durations, accent colours. Configurable in Settings → View.

## Typography

Two typefaces only:

- **UI sans:** `Inter` (variable, wght 400–700), fallback
  `system-ui` / `-apple-system` / `Segoe UI`. ~80KB WOFF2.
- **Data mono:** `JetBrains Mono` (variable, wght 400–700), fallback
  `ui-monospace` / `SF Mono` / `Menlo`. ~100KB WOFF2.

### Sizes (cosy density)

| Token | Size / line-height / weight | Used for |
|---|---|---|
| Display | 16 / 1.4 / 600 | Tab titles, modal headers |
| Body | 14 / 1.5 / 400 | Default UI text |
| Mono body | 13 / 1.45 / 400 | Code, EDN, event-list rows (mono is 1px down to visually match sans) |
| Caption | 12 / 1.4 / 400 | Hints, secondary labels, hover tooltips |
| Micro | 11 / 1.2 / 600 | Badges, tabs |

Below 10px: refused.

## Long-keyword treatment

Smart middle-elide + namespace fade + click-to-copy:

```
BEFORE:  :some.namespace.views.something/blah-blah-blah         (38 chars; overflows 560px)
AFTER:   :some.namespace…/blah-blah-blah  ⎘                     (with hover-copy icon)
         ^^^^^^^^^^^^^^^^                ^^^^^^^^^^^^^^^^^^
         text-tertiary 400                accent-violet 600
         (keep first ns segment; elide middle; keep keyword name)
```

Algorithm: when event-id exceeds N chars (compact 28; cosy 36; comfy
44; configurable via Settings → View → Long-keyword threshold), elide
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

- Tab switch: 180ms cross-fade.
- Detail diff flash: 400ms yellow → transparent on each touched slice.
- Error pulse: single 600ms expand-fade red ring (no looping).
- Machine-active state: 1.2s gentle scale 1.0 → 1.05 → 1.0 (only
  continuous animation in chrome, only on the machine chart).
- Mode pill LIVE pulse: 2s gentle 600ms expand-fade on the `●` glyph
  (continuous while LIVE; stops in RETRO).
- Tab count badge flash: 200ms violet → settle on LIVE update.

### `prefers-reduced-motion`

All durations clamp to 0 except a 1-frame opacity tween where layout
needs to settle. The error pulse becomes a static red ring for 1.5s;
the machine pulse stops entirely; the mode-pill LIVE pulse stops (`●`
stays statically rendered).

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
- The boot-time entry is `(causa-config/configure! {:editor …})` per
  [`015-Configuration.md`](./015-Configuration.md) §`:editor`.
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

Centred 560px modal, 50% height. Pre-alpha: no keybinding wired by
default — opening goes through the top-strip control (or `Ctrl+K` once
wired).

### Indexed sources

- Recent events (200-entry buffer; matches event-id + source coord)
- Registered handlers (id + `:doc`)
- Frames
- Machines with current state
- Tab jumps (Event / App-db / Views / Trace / Machines / Issues)
- Command verbs (Rewind / Re-dispatch / Snapshot / Filter / Pin /
  Pop-out / Switch frame / …)
- Settings entries
- Pinned cascades (pin chips live in the palette as a "Pinned
  cascades" source, since the L0 rail is gone)

Fuzzy match splits on camelCase / kebab-case / namespace boundaries.

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
