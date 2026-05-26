# 018-Event-Spine

The architectural core of Xray: the **4-layer chrome** + the **7-tab detail panel** + the **single-axis spine sub** (`:rf.xray/focus`) that binds every dependent surface to one user-controlled focal point.

This spec replaces the legacy 16-panel sidebar (now dead — see [`000-Vision.md`](000-Vision.md) §The 7-tab inventory and [`007-UX-IA.md`](007-UX-IA.md) §The 4-layer chrome) with a denser, keyboard-mnemonic, 10x-shaped layout. The event list is the load-bearing layer; every panel rebinds when selection moves.

---

## §1 Goal + non-goals

### Goal

Make the five canonical questions ([`000-Vision.md`](000-Vision.md) §Why it exists) answerable in seconds via:

1. A **two-ribbon chrome** (rf2-4vp5j) — a chrome ribbon (`Event History` label + nav cluster + `+ filter` + frame view-scope + Dynamic/Static mode dropdown + settings/close) above an events ribbon (filter pills + hidden-by-filters count). The events ribbon is hidden by default and animates open only once the first filter exists (rf2-pjjwh). The focus-dimension feature (focus button / focus-chip / per-row focus gutter / out-of-focus dimming) and the `Clear Filters` button were RETIRED per rf2-pjjwh — they were not in the Figma surface; row click still SELECTS the cascade and drives every panel.
2. An **event list** that is the orienting timeline + canonical scrubber.
3. A **tab bar** of 6 surfaces (Event / App-db / Views / Trace / Machines / Issues).
4. A **detail panel** whose content is always the current tab's projection of the focused event.

Every selection event passes through a single spine sub — `:rf.xray/focus` — so every panel reading the spine rebinds atomically. No panel reads `(peek history)`; no panel carries its own `:selected-*-id` slot.

### Non-goals

- **No AI in Xray.** No co-pilot rail, no AI tab, no in-chrome LLM surface. AI access goes through `tools/re-frame2-pair-mcp/` over raw nREPL — the agent reads the same instrumentation Xray reads, not a Xray-curated facade. (Xray is the human-only surface; re-frame2-pair-mcp is the AI access path.)
- **No Xray-MCP.** A dedicated `xray-mcp` jar was envisaged but dropped per rf2-hvl1g (2026-05-19). MCP server panel dies with it. Agent access flows through `tools/re-frame2-pair-mcp/` against the framework-published Xray runtime API.
- **No `:sensitive? true` event-handler annotation.** Reversed in favour of unified path-marked classification per [spec/015-Data-Classification](../../../spec/015-Data-Classification.md). Xray CONSUMES that contract; this spec defines how the sentinels render in Xray's surfaces (§12).
- **No writes to host runtime.** Xray stays read-only forever (Lock #3 in [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md)).
- **No bottom rail.** The pass-2/round-1/round-2 "L0" scrubber rail is gone — the ribbon `[◀ ▶ ⏭]` cluster + the event list together ARE the scrubber.
- **No multi-frame merged view.** The frame picker is single-select.

---

## §2 The 4-layer chrome

```
┌─────────────────────────────────────────────────────────────────────────┐
│ LAYER 1  Two ribbons — chrome (40px) + events ribbon (rf2-4vp5j)        │  scope + spine controls
├─────────────────────────────────────────────────────────────────────────┤
│ LAYER 2  Event list (8 rows default; resizable; min 2)                  │  the spine / timeline
├─────────────────────────────────────────────────────────────────────────┤
│ LAYER 3  Tab bar (40px) — 7 tabs                                        │  projection selector
├─────────────────────────────────────────────────────────────────────────┤
│ LAYER 4  Detail panel (fills remaining canvas)                          │  per-tab content
└─────────────────────────────────────────────────────────────────────────┘
```

Wireframe at default (800px popout, "cosy" density):

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Frame: :app/main ▾   Dynamic ▾                          🔇 0  ● 1   ⚙ ✕ │   L1 chrome ribbon
│ Events: [◀ ▶ ⏭]  🎯 :order/retry  [+ :auth/* ✎] [× :mouse-move ✎] [+]   │   L1.5 events ribbon
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
│ ◉Event ○App-db ○Views 8 ○Trace 47 ○Machines 1 ○Routes ⚠Issues 1         │   L3 — 7 tabs
├─────────────────────────────────────────────────────────────────────────┤
│ — Event tab content for the focused event —                             │   L4 — fills the rest
│   event vector · source · handler return · db writes · fx · fx-handlers │
│     (cljs-devtools-shaped renderer; pure hiccup; theme-token driven)    │
└─────────────────────────────────────────────────────────────────────────┘
```

Layers are stacked top-to-bottom; only L2/L3 has a user-draggable resize handle. L1/L3 are fixed-height; L2 takes the remainder above L3; L4 takes the remainder below L3. Narrow widths (<800px) and wide widths (≥1200px) preserve the layer order — see [`007-UX-IA.md`](007-UX-IA.md) §The 4-layer chrome.

**Why 4 layers, not 5:** the round-2 design had a bottom rail (L0) carrying the scrubber + mode pill + classification totals. Mike's call: "there is already a scrubber effectively at the top, along with a list of events." The events-ribbon nav cluster IS the seek, the event list IS the timeline, and classification totals relocate to per-row + per-panel renderings. One fewer layer; same affordances. (The Dynamic/Static **mode dropdown** lives at chrome-ribbon-left — see §3; LIVE / RETRO is a separate spine state surfaced in the L2 head-row cue.)

---

## §2.5 Static surface (3-layer chrome)

Xray exposes **two modes** per Lock #14 in [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) — the **Dynamic** surface specified in §2 above (event-coupled spine + 4-layer chrome) and a peer **Static** surface (event-INDEPENDENT registry browse + 3-layer chrome). This section owns the Static architectural contract; visual-language details (mode pill widget chrome, edge stripe colour tokens, motion dampening durations) live in [`007-UX-IA.md`](007-UX-IA.md) §Static mode.

### 3-layer silhouette

Dynamic is 4 layers (L1 ribbon · L2 event list · L3 tab bar · L4 detail panel). **Static drops L2 — there is no spine in Static mode because Static is event-INDEPENDENT** — and renders 3 layers:

```
┌─────────────────────────────────────────────────────────────────────────┐
│ LAYER 1  Chrome ribbon (40px) — mode dropdown + right icons             │   scope controls
├─────────────────────────────────────────────────────────────────────────┤
│ LAYER 3  Tab bar (40px) — 5 tabs                                        │   projection selector
├─────────────────────────────────────────────────────────────────────────┤
│ LAYER 4  Detail panel (fills remaining canvas)                          │   per-tab content
└─────────────────────────────────────────────────────────────────────────┘
```

L2's absence is itself a functional signal — see §The 4 mode signals below. The L1 chrome ribbon retains the mode dropdown (ribbon-left) and the right-icons cluster (`⚙` settings · `✕` close); the Dynamic chrome's events ribbon — nav cluster, focus-chip, frame picker, filter pills — is HIDDEN because Static is event-independent and has no spine, so those clusters have no meaning here.

### The 4 mode signals (chrome silhouette + 3 reinforcing)

The user reads "Static" at a glance via **four stacked signals**; together they telegraph the mode without the user needing to look at any one widget. Lock #14 commits to all four, on the principle that mode confusion is the failure mode to defend against.

| # | Signal | Dynamic | Static |
|---|---|---|---|
| 1 | **Mode dropdown** at chrome-ribbon-left — a compact `<select>` (rf2-4vp5j; replaced the old 160px two-segment radio pill) sharing the frame picker's control weight. Lives in BOTH modes (it's the toggle, not the indicator). | `Dynamic ▾` | `Static ▾` |
| 2 | **2-px left-edge ribbon stripe.** | `:accent-violet` | `:cyan` (existing palette token — zero new tokens) |
| 3 | **Motion dampening.** | LIVE pulse + machine-active pulse + 180ms tab fade | All continuous pulses dropped; tab fade collapses to 0ms instant. Honours `prefers-reduced-motion: reduce` via `--rf-xray-motion-scale`. |
| 4 | **Chrome silhouette.** | 4-layer (L1 · L2 · L3 · L4) | 3-layer (L1 · L3 · L4 — no spine) |

The dropdown is wired to the same handler the `Cmd-Shift-M` / `Ctrl+Shift+M` global chord (per §11 Keyboard map) fires — chord and dropdown share the toggle.

### Mode-state lifecycle slots

Two app-db slots on the `:rf/xray` frame carry the mode user-state:

| Slot | Type | Default | Notes |
|---|---|---|---|
| `:rf.xray/mode` | `:dynamic` \| `:static` | `:dynamic` | Active mode. Drives the surface composer in `shell.cljs`. |
| `:rf.xray.static/selected-tab` | `:machines` \| `:routes` \| `:schemas` \| `:flows` \| `:interceptors` | `:machines` | Static-scoped tab choice. **Separate from the Dynamic `:rf.xray/active-tab` slot** so flipping modes preserves both choices. |

Three event handlers drive the lifecycle:

- **`:rf.xray/set-mode`** `(fn [{:keys [db]} [_ mode]] …)` — writes a specific mode. Used by the mode-pill segment-click path, hydration after localStorage read, and test fixtures.
- **`:rf.xray/toggle-mode`** `(fn [{:keys [db]} _] …)` — flips between modes. Used by the `Cmd-Shift-M` chord (see `keybinding.cljs`) and as the canonical mode-flip path.
- **`:rf.xray.static/select-tab`** `(fn [{:keys [db]} [_ tab-id]] …)` — flips the Static-scoped tab. Unknown values are rejected (validated against the registered Static tab inventory).

`set-mode` and `toggle-mode` attach the `:rf.xray.static/persist-mode` fx so every mutation round-trips through localStorage in one place.

### localStorage persistence — `xray.mode`

The user's mode choice survives reloads via localStorage under the canonical key **`xray.mode`** (a bare string — `"dynamic"` or `"static"`). A bare string keeps the slot cheap to read + cheap to inspect from browser devtools; modes are an enum, not a structured value. Unknown / malformed values normalise back to `:dynamic` (the conservative default — the existing chrome).

The namespace prefix is `xray.mode` (not `re-frame2.xray.mode.v1`) deliberately — it mirrors the spec-published name from the rf2-o5f5f findings doc, is short, and reads naturally in browser devtools. The filter-persistence slot uses the longer versioned form because its shape may evolve; the mode slot is a fixed enum, so versioning would be overkill.

Sub-surface slots (e.g. Static Machines' selected-id and per-machine sub-mode) ride their own localStorage keys under the `xray.static.*` prefix — see [`003-Machine-Inspector.md`](003-Machine-Inspector.md) §Static Machines surface.

### Availability

Static mode is unconditionally available. The mode dropdown mounts at chrome-ribbon-left in every host, `Cmd-Shift-M` / `Ctrl-Shift-M` dispatches `:rf.xray/toggle-mode` against `:rf/xray`, and the surface composer switches on `:rf.xray/mode`. Per rf2-8l3uk the prior `:rf.xray/static-mode?` opt-in feature gate was removed (pre-alpha posture — back-compat shims are out of scope; if Static mode is useful, expose it unconditionally).

### Mnemonic mode-scoping rule

The 5-letter Static sub-tab mnemonics (`m` Machines · `r` Routes · `c` Schemas · `f` Flows · `i` Interceptors per [`007-UX-IA.md`](007-UX-IA.md) §Static mode) are **mode-scoped**: the same letter dispatches the active mode's tab, not a globally-fixed target. `m` in Dynamic opens the Machines instance-inspector (per §5); `m` in Static opens the Machines registry browse (per [`003-Machine-Inspector.md`](003-Machine-Inspector.md) §Static Machines surface). The keybinding wiring rides the same chord-detection ns (`keybinding.cljs`) but routes through a mode-aware dispatcher.

The mode-scoping rule is what lets the mnemonic vocabulary stay small (single letters) without colliding across modes — switching modes flips both the chrome AND the meaning of the letter keys, and the user reads which is active from the 4 stacked mode signals above.

### Frame isolation

Same discipline as the Dynamic chrome (per §8 Frame-observation isolation invariants). The Static surface composer inside `shell.cljs` is wrapped in `[rf/frame-provider {:frame :rf/xray}]`; every subscribe + dispatch inside the surface resolves to `:rf/xray`. Each subscribing region is `reg-view`-registered so its rendered component carries `:contextType frame-context` (rf2-in6l2 + Spec 004 §Plain Reagent fns do not pick up the surrounding frame).

### See also

- [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) Lock #14 — the direction-setting decision behind Two modes (Dynamic + Static).
- [`007-UX-IA.md`](007-UX-IA.md) §Static mode — visual-language details (mode pill widget chrome, edge stripe colour tokens, motion dampening durations, sub-tab mnemonics, design language).
- [`003-Machine-Inspector.md`](003-Machine-Inspector.md) §Static Machines surface — concrete Static Machines surface description (4-mode sub-strip · Topology · Sim body (rf2-r4nao — landed) · Instances JUMP · Cascade dimmed).

---

## §3 Ribbon anatomy (Layer 1) — two ribbons (rf2-4vp5j)

Layer 1 is **two stacked ribbons** (rf2-4vp5j's two-ribbon redesign), not
one. They split scope SELECTORS from spine/filter CHROME so each stratum
stays compact and reads as a single concern:

### L1 chrome ribbon (`rf-xray-ribbon`, 40px)

Carries only scope selectors (left) and chrome actions (right):

| Cluster | Side | Content | Keys |
|---|---|---|---|
| **Frame** | left | `Frame ▾` dropdown — ALWAYS rendered (rf2-ad7zx.12); the selected value is surfaced INSIDE the option list (the active option carries a `✓`), not inlined on the button. Interactive whenever ≥1 frame is available (rf2-ad7zx.14): a single-frame host gets a working 1-entry dropdown listing that lone frame; only the zero-frame state disables the control. **Single-select VIEW SCOPE** (rf2-4vp5j — not a filter). Tool frames hidden unless Settings → View → "Show tool frames in picker" toggle on. | — |
| **Mode** | left | `Dynamic ▾` / `Static ▾` **dropdown** (`<select>`) — compact, understated; shares the frame picker's control weight (rf2-4vp5j). The 2-px left-edge accent stripe (violet Dynamic / cyan Static) carries the mode SIGNAL so the control itself recedes. | `Cmd/Ctrl-Shift-M` |
| **Indicators** | right | Silent-by-default `🔇 N` mute indicator + `● N` REDACTED indicator (each painted only when its count > 0). | — |
| **Right-icons** | right | `⚙` settings popup · `✕` close shell | `,` or `s` · `Esc` |

### L1.5 events ribbon (`rf-xray-events-ribbon`, distinct `bg-2`)

The filter chrome. **rf2-pjjwh — this ribbon is HIDDEN by default and
animates open (CSS `grid-template-rows: 0fr ⇄ 1fr`) only once the first
filter exists; it animates closed when the last filter is removed.** The
nav cluster + `+ filter` add affordance live UP on the chrome ribbon
(bar-1); the focus-chip and the `Clear Filters` button were retired
(rf2-pjjwh — not in the Figma surface).

| Cluster | Side | Content | Keys |
|---|---|---|---|
| **Label** | left | `↳ filters:` | — |
| **Filter pills** | left | `[+ :auth/* ✎]` IN pills (green) + `[× :mouse-move ✎]` OUT pills (red) + a `+` add-filter icon. Click any pill → edit popup; each pill's `✕` removes it. | `/` focus add-pill |
| **Hidden** | far right | `N events filtered out` (only when N > 0). | — |

### Mode is a DROPDOWN (rf2-4vp5j supersedes the "mode pill dropped" note)

Earlier drafts of this spec **dropped** the mode pill, communicating
LIVE/RETRO via the L2 spine alone. The rf2-4vp5j redesign **re-adds a
mode control as a compact `<select> dropdown`** at chrome-ribbon-left
(not the old 160px two-segment radio pill — that was too dominant for an
occasional-use control). The dropdown and the `Cmd/Ctrl-Shift-M` global
chord share the same `:rf.xray/toggle-mode` handler. (LIVE vs RETRO
within Dynamic mode is still a SPINE state, surfaced by the L2 head-row
cue; the dropdown toggles Dynamic ↔ Static, a separate axis.)

Wireframe (two ribbons; cluster boundaries shown):

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│ Event History  [◀ ▶ ⏭]  + filter      :app/main ▾   Dynamic ▾        🔇 2  ● 1   ⚙ ✕ │  L1 chrome
├─────────────────────────────────────────────────────────────────────────────────────┤
│ ↳ filters:  +  [+ :auth/* ✎] [× :mouse-move ✎]                          3 events filtered out │  L1.5 events (shown only when filters exist — rf2-pjjwh)
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Frame dropdown

The default contents are the host app's frames, single-select. Example with three frames:

```
┌────────────────────────┐
│ ✓ :rf/default          │   ← current selection (cyan checkmark)
│   :app/dialog          │
│   :app/sidebar         │
└────────────────────────┘
```

**Excludes `:rf/xray` (and any future tool frames like `:rf/re-frame2-pair`) by default.** See [§8 Frame-observation isolation invariants](#8-frame-observation-isolation-invariants).

When the Settings "Show tool frames in picker" power-user toggle is on, tool frames are appended under a `── Power user ──` divider:

```
┌────────────────────────┐
│ ✓ :rf/default          │
│   :app/dialog          │
│   :app/sidebar         │
│ ── Power user ──       │
│   :rf/xray            │
└────────────────────────┘
```

Single-frame apps still get the full `Frame ▾` dropdown — it is interactive and opens a 1-entry list naming that lone frame (rf2-ad7zx.14). The earlier "collapse to a flat label" behaviour is gone (rf2-ad7zx.12 always renders the dropdown). Only a zero-frame state disables the overlaid `<select>` (no inert popup).

### Filter pills

The filter system lives in this cluster — see §7 for full IN/OUT pill semantics and the edit-popup contract.

### LIVE / RETRO is a spine state (orthogonal to the mode dropdown)

The Dynamic/Static **mode dropdown** (above) is one axis; LIVE /
LIVE-paused / RETRO is a separate SPINE state communicated by the L2
event list itself (the head row's pulse cue indicates LIVE; a pinned row
indicates RETRO). LIVE/RETRO transitions ride the `Space` / `L` keys +
the `⏭` ribbon button + ordinary row clicks (clicking any non-head row
flips LIVE→RETRO). The spine sub carries `:mode :live | :retro` for
downstream consumers; there is no separate LIVE/RETRO pill widget.

### Right-icon behaviour

- `⚙` Settings — dispatches `:rf.xray/settings-open`; opens the Settings modal popup (see [§9 Settings popup](#9-settings-popup)).
- `✕` Close — dispatches **`:rf.xray/close-shell`** (rf2-fq491), an app-db event handled in `mount.cljs` that actually hides the shell (an earlier draft only flipped a CSS class and could leave the shell stuck open). Host can re-open via `Ctrl+Shift+C`.
- **`⛶` Popout is omitted from the ribbon** (rf2-g3ghh / rf2-yn86j — silent-by-default): the second-window UX ([`011-Launch-Modes.md`](011-Launch-Modes.md)) has not landed, so the ribbon does not show a broken-claim button. The programmatic `(xray/popout!)` API remains the supported pop-out path; the `⛶` slot is reserved for when the second-window UX ships.

---

## §4 Event list (the spine) — Layer 2

The orienting layer. Single-line rows; latest-on-bottom; virtualised; eight visible by default; user-resizable.

### Defaults

| Default | Value | Notes |
|---|---|---|
| Visible rows | **8** | Density sweet spot at 28px per row ≈ 224px footprint |
| Initial selection | Last event (head) | On Xray open, the most recent cascade is focused |
| Row height | 28px (single line) | No density tiers; one shape |
| Sort order | Latest at bottom | Auto-scrolls to bottom in LIVE mode |
| Resizable | Drag handle on L2/L3 boundary | Min 2 rows ≈ 56px; max bounded by canvas |
| Virtualisation | Viewport + 20-row overscan | Uses existing `panels/overflow_indicator.cljs` |

#### v1 ships — Compact density baseline (rf2-htik0)

The spec table's row-height baseline is `28px` ("cosy"; the named
default in the View settings table); v1 ships at `22px` ("compact"
per [`007-UX-IA.md` §Density slider](007-UX-IA.md#density-slider))
without exposing a user-facing density picker for the L2 surface
yet. Container height drops from 224px to 200px (8 × 22px + gaps +
outer padding); min-height drops 56px → 48px so the L2/L3 drag
handle can still squeeze the list to ~2 rows. The named tiers
remain in the spec (compact/cosy — `:comfy` was dropped per 015 §Density) so a future density picker
re-flips the rhythm without a re-design pass.

#### v1 ships — Nav-button semantics (rf2-htik0)

The ribbon's `[◀ ▶]` nav cluster: `◀` (prev / step backward in time)
is disabled at the **oldest** event (no older to step to); `▶` (next
/ step forward) is disabled at the **most recent** event (no newer
to step to). The earlier shell prototype shipped these inverted —
the v1 fix swapped the `at-head?` / `at-tail?` predicates and the
docstring now reads as the actual semantics. Recorded here so the
spec's nav-button semantics line up with the runtime contract for
test rigs that pin enable / disable state across the buffer
boundaries.

#### v1 ships — Full event vector inline (rf2-htik0)

The Row anatomy table below documents the `Event id` column as
`:order/submit` (the event-id alone). v1 ships the **full dispatched
event vector** inline (`[:cart/add-item {:item-id "apple" :qty 2}]`)
truncated at the 80-char inline cap (`<head>…]` suffix preserves the
closing bracket so the row still reads as a vector). The event-id
gets the accent-violet keyword colour so it pops out of the payload;
the payload renders in the row's default text colour. Empty payloads
collapse to `[:counter/inc]` (no `{}` placeholder). The 80-char cap
is a single-row legibility constraint — clicking the row opens the
L4 Event tab with the full untruncated vector. The Row anatomy table
below remains the canonical shape; this callout records what the
`Event id` column actually packs at v1.

### Row anatomy

**rf2-pjjwh — clean Figma mock layout.** ONE row shape, four columns,
matching the Figma EventList exactly. The active (selected) row is marked
by BACKGROUND ONLY (`bg-[var(--devtools-hover)]`):

```
│ Col          │ Width        │ Content                              │
├──────────────┼──────────────┼──────────────────────────────────────┤
│ Event id     │ flex mono    │ :order/submit (accent keyword colour)│
│ Source       │ 52px         │ ui / fx / timer / router (muted)     │
│ Timestamp    │ ≥76px        │ 12:30:05.123 (right-aligned)         │
│ Duration     │ ≥60px        │ 1.2 ms (right-aligned)               │
```

rf2-pjjwh RETIRED these decorations the mock did not carry: the leading
focus gutter (+ its glyph `● ◉ x ▥`), the origin-prefix glyph before the
source tag, the activity badges (`⚠ 🌐 🤖`), the trailing 2px lifecycle
status stripe, the redaction marker, and the out-of-focus dimming. The
dropped fields (full event vector + args, sequence number, frame, source
coord, handler duration) surface in the row's hover `:title` tooltip + the
L4 Event tab on click. The timestamp column shows the absolute wall-clock
`HH:MM:SS.mmm` (rf2-3f2di A8), not the relative chip described below.

#### Relative-time chip (rf2-vbbq0 / rf2-0s2at)

Each row carries a trailing right-aligned chip showing how long ago the cascade was dispatched. The chip's bucket strategy keeps old chips visually stable:

| Diff               | Display |
|---|---|
| `< 1s`             | `now`   |
| `< 60s`            | `Ns`    |
| `< 60min`          | `Nm`    |
| `< 24h`            | `Nh`    |
| `≥ 24h`            | `Nd`    |

**Anchor (rf2-0s2at):** the "now" each chip computes against is the **dispatched-time of the most recent cascade in `:rf.xray/cascades`** — flips on event arrival, not on a per-second tick. Between events the L2 list stays frozen (no re-render); when a new event lands the anchor advances and every older row's chip recomputes (a row that read `3s` may now read `8s`). This replaces the earlier (rf2-vbbq0 original) 1s `setInterval` design: relative time is meaningful between events, not between seconds, and the per-second tick caused constant L2 flicker watching live testbeds. No timer; the anchor sub composes off the existing `:rf.xray/cascades` reactive path. The chip's `:title` attribute carries the absolute walltime (`HH:MM:SS · ISO · epoch-ms`) as the power-user reveal — hover the chip for the precise time without leaving L2. Replaces the v1 absolute datetime column dropped in Round-3 R3-C.

#### Gutter glyphs / row badges / redaction marker — RETIRED (rf2-pjjwh)

The per-row gutter glyph (`● ◉ x ▥ ↺`), the three activity badges
(`⚠ 🌐 🤖`), and the inline redaction/elision marker
(`[● REDACTED N]` / `[● ELIDED N]`) were retired from the L2 row per
rf2-pjjwh — the Figma EventList carries none of them. Error / HTTP /
machine / redaction context still lives in the cascade record and surfaces
in the L4 Event + Issues + Trace panels (the panels read the cascade's
`:other` / `:errors` slots directly). The REDACTED count remains as a
silent-by-default indicator on the chrome ribbon; see §12 for the full
data-classification rendering contract.

### Row variants

ONE shape, decorated:

```
Basic                ● :input/changed
Machine-triggering   ● :form/submit-clicked       🤖
HTTP-triggering      ● :order/submit              🌐
Errored              x :checkout/finalize     ⚠
Compound             x :checkout/submit-failed  ⚠ 🌐 🤖
Sensitive (partial)  ● :auth/login                              [● REDACTED 1]
Sensitive (whole)    ▥ :auth/login                              [● REDACTED]
Selected             ◉ :order/retry                  🌐                          (cyan border)
```

### Hover tooltip — the home of dropped detail

The single-line row drops detail the round-1 two-line row used to carry. Every row carries a hover tooltip (400ms delay) that discloses:

```
┌─ Tooltip on hover ──────────────────────────────────────────────────┐
│ :order/submit                          cascade #347                 │
│ 16:42:14.701   ⏱ 12ms · tier ●                                     │
│ src/cart/events.cljs:213                                            │
│ args  {:order-id 92 :attempt 2}                                     │
│ ────                                                                │
│ click row to focus · `o` open source · ctrl+click copy id           │
└─────────────────────────────────────────────────────────────────────┘
```

The Event tab (L4 when active) is the OTHER home for the dropped detail. The tooltip + tab pair means the row stays scannable while the detail stays one hover or one click away.

### Row click + key behaviour

| Action | Result |
|---|---|
| **Click row** | `:rf.xray/focus-cascade <id>` + flip `:mode → :retro`; detail panel updates per active tab |
| **Double-click row** | Focus + pivot L3 to Event tab (= click row then press `e`) |
| **`o` while row focused** | Open source coord in editor (per [`007-UX-IA.md`](007-UX-IA.md) §Editor protocol matrix) |
| **`Ctrl+click` row** | Copy cascade-id to clipboard |
| **Right-click row** | Context menu (see [§7 Filter system — right-click context menu](#7-filter-system)) |
| **Hover badge** | Category tooltip (see Row badges table) |
| **Click badge** | Category action (see Row badges table) |

### LIVE-tracking + sticky rules

| Selection state | New event arrives | Behaviour |
|---|---|---|
| Selection = head | New event arrives | Selection auto-advances to new head; auto-scroll to bottom; mode stays LIVE |
| Selection = older row | New event arrives | Selection STAYS on older row; auto-scroll suspends; sticky `↓ N new events — press ⏭ to follow` marker pins at bottom edge; mode = RETRO |
| Mode = LIVE (paused) | New event arrives | Buffer keeps collecting; visible list stops auto-scrolling; same sticky marker |

The LIVE/sticky split is the chrome's load-bearing temporal behaviour. New arrivals must not steal focus during retro investigation.

### Row expansion

Round-3 decision: rows are **NOT click-expandable** in place. The Event tab (L4) is the sole destination for the dropped detail; hover tooltips give a peek; clicking focuses + the Event tab (already showing the focused event) displays everything. This keeps the row geometry one-line uniformly and the spine's "selection = focus" semantics atomic.

### Multi-instance Mode C lineage overlay

When the user is inspecting a machine in Mode C (4+ instances; see [`003-Machine-Inspector.md`](003-Machine-Inspector.md)), event rows that triggered transitions on the focused machine get a thin violet underline — a "this is the lineage of the machine you're focused on" overlay layered ON TOP of the row's normal rendering. The overlay is additive; standard row layout unchanged.

### Empty states

- **No events yet (cold start):** "Click around your app — every dispatch will land here."
- **Buffer empty after explicit clear:** "Buffer cleared. New events will appear here."
- **All cascades match a filter:** "All N cascades match active filters — show all, or change filter set" with two buttons.
- **No cascades in selected frame:** "No events in `:app/dialog` — pick another frame, or trigger one in your app."

---

## §5 Tab bar + detail panel (Layers 3 + 4)

### The 7 tabs

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│ ◉Event ○App-db ○Views 8 ○Trace 47 ○Machines 1 ○Routing ⚠Issues 1                         │   L3
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

| # | Tab | Mnem | What it shows for the focused event | Spec |
|---|---|---|---|---|
| 1 | **Event** | `e` | Whole event vector + arg-map + source · handler return · db writes · fx vector · fx-handlers that ran (incl. results) | this doc §5.1 + [`016-Auxiliary-Panels.md`](016-Auxiliary-Panels.md) §Event-detail panel |
| 2 | **App-db** | `a` | Diff `:db-before` vs `:db-after` — slice-first · clickable path segments (rf2-e9tb0) · path-origin chips (rf2-s8r6c) · full-tree disclosure | [`004-App-DB-Diff.md`](004-App-DB-Diff.md) + this doc §5.2 |
| 3 | **Views** | `v` | Per-view rows: mounted / re-rendered / unmounted groups; each row lists subs used + sub return values; cluster-large-grids; isolation-scoped to selected frame | [`012-Views.md`](012-Views.md) |
| 4 | **Trace** | `t` | Raw multi-axis trace stream filtered to `:dispatch-id = <focus>`; trace-type toggle row at top + IN/OUT pills + sensible defaults | this doc §5.3 + [`013-Trace-Consumer.md`](013-Trace-Consumer.md) |
| 5 | **Machines** | `m` | **Event-driven Dynamic panel** (rf2-y9xmf): BLANK when the focused event has no machine activity; one per-machine section (topology + transition highlight + guards + actions + cancellation cascade + `:after` rings) when it does. The spine-INDEPENDENT browse-all canvas relocated to the Static Machines sub-tab's Topology mode in rf2-ga16q. UC1 Sim engine landed under the Static Machines surface's Sim sub-mode (rf2-r4nao — events/subs at `:rf.xray.static.machines/sim-*`, view at `tools/xray/src/day8/re_frame2_xray/static/machines/sim.cljs`); UC2 Mode A/B/C remains a Dynamic-side concern, reached from Static via the per-row → Dynamic JUMP. | [`003-Machine-Inspector.md`](003-Machine-Inspector.md) |
| 6 | **Routing** | `r` | **FLAT focused-event lens** (rf2-lq0ef): current matched route + params/query/fragment + **Simulate-URL** input ranking every registered route via the 6-rule `:rf.route/rank` tuple with the rank explainer inline; per-focused-event glyphs `◆ HERE` / `◆ FROM` / `◆ TO`. Silent when no routes registered. | this doc §5.6 + [`016-Auxiliary-Panels.md`](016-Auxiliary-Panels.md) §Routing tab |
| 7 | **Issues** | `i` | JS exceptions + schema violations + sensitive-data warnings + hydration mismatches + perf-budget overruns + app console errors/warns | this doc §5.4 + [`016-Auxiliary-Panels.md`](016-Auxiliary-Panels.md) §Issues ribbon |

(rf2-4v67l — the Chrome A11y dogfood tab was removed. A11y
dogfooding is properly Story's domain, where it already ships as the
`chrome-a11y` panel (rf2-18t6p · `tools/story/src/re_frame/story/
ui/chrome_a11y.cljs`) — a sibling to the variant a11y scanner
`re-frame.story.ui.a11y` (rf2-qgms1). A duplicate Xray panel was
noise that flagged the Xray events-list as a problem.)

**Effects is folded into Event** — the "fx handlers that ran" block under Event tab covers it.

**Subs are folded into Views** — subs nest under each view row, not a separate tab. See [`012-Views.md`](012-Views.md).

**Performance is dropped** — cross-link to Chrome DevTools' Performance tab (the framework emits `rf:event:*`, `rf:sub:*`, `rf:fx:*`, `rf:render:*`, `rf:cascade:*` User-Timing entries that DevTools renders natively in the Timings track).

### Tab strip rendering

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│ ◉Event ○App-db ○Views 8 ○Trace 47 ○Machines 1 ○Routing ⚠Issues 1                         │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Active:** `◉` gutter + 2px violet underline + `text-primary`.
- **Inactive:** `○` gutter + `text-secondary`.
- **Count badge:** `<tab> <N>` (`Views 8` = views that rendered this cascade; `Trace 47` = filtered trace count). The number IS the badge — no extra dot.
- **Issues weight:** `⚠` gutter (red) replaces `○`; stays visible even when active.
- **Dormant tab:** `text-disabled` + `○`; clickable → empty state.
- **Count flash on LIVE update:** count flashes violet 200ms then settles. No continuous spinner.

Single-row at all widths. Below 800px labels truncate to 3 chars (`Eve App Vie Tra Mac Can Rou Iss`); counts always full. Below 560px the strip scrolls horizontally.

### Tab strip ARIA

The L3 tab strip uses the proper ARIA tab pattern (per rf2-lvf8t —
rf2-q7who Thread B):

- The wrapping element is a generic container (`<div>`) with
  `role='tablist'` and a descriptive `aria-label`. It MUST NOT be a
  `<nav>` element: tabs are not site navigation, and a `<nav>`
  landmark collides with host-app `<nav>` landmarks under role-based
  queries (`getByRole('navigation')` becomes ambiguous when Xray is
  embedded — e.g. as Story's right-hand-side panel).
- Each tab button carries `role='tab'` and `aria-selected="true"` /
  `"false"` reflecting the active tab.

The `data-testid="rf-xray-tab-bar"` selector remains the canonical
test addressing surface; ARIA is the user-facing assistive contract.

### Detail panel layout

L4 fills the remaining canvas (60% default; resizable via L2/L3 drag handle). All value displays in the detail panel use the cljs-devtools-shaped renderer (`theme/data_inspector.cljc`):

- `inspect <value>` — expandable hero
- `inspect-inline <value>` — one-line tail-elided
- `inspect-diff <before> <after>` — diff variant

The renderer does NOT depend on `binaryage/cljs-devtools` (that library targets the Chrome console; this is in-page hiccup). Pure hiccup, theme-token-driven, substrate-agnostic. See [`007-UX-IA.md`](007-UX-IA.md) §Detail panel renderer.

### §5.1 Event tab content — the 8-section Event lens

Shipped layout per rf2-zh2qc + rf2-jhhqt + rf2-lo37i (rf2-jhhqt swaps
DISPATCH SITE before EVENT per Mike's Q1 verbatim and adds the COEFFECTS
section; rf2-lo37i adds the FLOWS section as a peer surface to make the
cascade's flow step first-class). FLOWS sits RIGHT AFTER the HANDLER —
flows fire at the outermost `:after` interceptor, reshaping the pending
`:db` before it commits, so the lens reads in true pipeline order
(handler → flows → committed effects → fx-handlers). Top-of-panel: a
single-line cascade-outcome summary; below: eight stacked sections that
read top-to-bottom as the developer scans.

```
┌─ Event lens · :cart/add-item                              ✓ ok · 11ms · #347 · SSR✓ ┐
│                                                                                      │
│ ▼ DISPATCH SITE                                                                      │
│   src/cart/views.cljs:127       [code]                                             │
│   via :ui · origin :app                                                              │
│                                                                                      │
│ ▼ EVENT                                                                              │
│   [:cart/add-item {:id 42 :qty 2}]                                                   │
│                                                                                      │
│ ▼ COEFFECTS  (2)                                                                     │
│   :now            #inst "2026-05-18T19:00:00Z"                                       │
│   :local-storage  {:user/last-cart-id "cart-42"}                                     │
│                                                                                      │
│ ▼ INTERCEPTORS  (1)                                                                  │
│   :auth/require-login          src/auth/interceptors.cljs:42   [code]              │
│                                                                                      │
│ ▼ HANDLER                                                                            │
│   reg-event-fx · src/cart/events.cljs:88                       [code]              │
│                                                                                      │
│ ▼ FLOWS  (3)                                                                         │
│   ▸ :cart-total                wrote [:cart :total]   52.50                         │
│                                  read  [:cart :items]                                │
│     ↳ :tax-due       via :cart-total                                                 │
│                                wrote [:tax :due]      5.25                          │
│                                  read  [:cart :total]                                │
│     ↳ :grand-total-display     via :cart-total, :tax-due                            │
│                                wrote [:checkout :grand-total]  57.75                │
│                                  read  [:cart :total] [:tax :due]                    │
│                                                                                      │
│ ▼ EFFECTS RETURNED                                                                   │
│   :db    <… changed; see App-db tab …>                                               │
│   :fx    [[:http/post {…}] [:dispatch [:notify "added"]]]                            │
│                                                                                      │
│ ▼ EFFECTS HANDLERS RAN  (2)                                                          │
│   :http/post   ⏱ 87ms  ✓ handled                                                     │
│   ┌─ MANAGED FX [HTTP] · :http/post · 87ms ──────────────────────────────┐           │
│   │ STATUS: ✓ 200 OK · correlation: c-abc12 · phase: completed           │           │
│   │ ▼ REQUEST  ▼ WIRE TIMING  ▼ RESPONSE  ▼ HANDLER  ▼ APP-DB SLICE      │           │
│   └────────────────────────────────────────────────────────────────────────┘         │
│   :dispatch    ⏱ <1ms  ✓ handled  → queued [:notify "added"]                        │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

#### Cascade-outcome line (top-of-panel chrome)

`<event-id>   ✓/✗/⚠ <outcome> · <duration-ms> · cascade #<id> [· SSR✓]`

- Glyph + colour: classified off the universal `:op-type` severity axis
  (Spec 009), not an enumerated op list. `✗ red` when the cascade's
  `:other` bucket carries any `:op-type :error` / `:rf.error/*` trace
  (handler exception, drain-depth overflow, flow-eval failure, fx/cofx
  error, …); `⚠ amber` when it carries any `:op-type :warning` /
  `:rf.warning/*` trace; `✓ green` otherwise. Error severity wins over a
  co-resident warning.
- `SSR✓` orientation badge when the focused event is
  `:rf.ssr/hydrated` / `:rf.ssr/hydration-complete`; omitted for
  purely-client-side cascades.

#### The 8 sections (Mike's verbatim order, rf2-jhhqt + rf2-lo37i)

1. **DISPATCH SITE** — source-coord chip + `via :source · origin :origin`
   caption. Reads `:rf.trace/call-site` off the `:rf.event/dispatched`
   trace (rf2-twt7m Change 1). Renders an absent-placeholder when no
   call-site was captured. **Comes FIRST** per Mike's Q1 — the
   developer's first instinct ("who fired this?") gets the most
   prominent slot.
2. **EVENT** — the dispatched event vector via `inspector/inspect`.
   Always present.
3. **COEFFECTS** — user-injected coeffects only, **silent when zero**
   (the section is ABSENT entirely, NOT '(none)'). Reads `:coeffects`
   off the `:event/do-fx` trace's `:tags` (rf2-jhhqt — the runtime
   stamps the user-injected subset; the framework defaults `:db`
   `:event` `:frame` `:source` `:trace-id` are filtered at the
   substrate). Mirrors the INTERCEPTORS section's filter-out-framework-
   defaults posture. Each row: `<:cofx-id>  <inspected-value>`.
4. **INTERCEPTORS** — non-standard chain only, **silent when zero**
   (the section is ABSENT entirely, NOT '(none)'). Reads
   `(rf/handler-meta :event id) :interceptors` and filters out anything
   carrying `:rf/default? true` (rf2-twt7m Change 3) plus the known
   auto-wrapper ids (`:rf/db-handler` / `:rf/fx-handler` /
   `:rf/ctx-handler`) as a belt-and-braces fallback.
5. **HANDLER** — `reg-event-<kind> · src/file.cljs:N [code]`. Per
   Q2: does NOT duplicate the event-id (already shown in §2). Reads
   `(rf/handler-meta :event id)`.
6. **FLOWS** — silent-by-default when no flows fired (rf2-lo37i —
   peer section sitting RIGHT AFTER the handler, before the
   handler-driven effects). Flows fire automatically at the OUTERMOST
   `:after` interceptor, immediately after the event handler returns:
   they transform the pending `:db` effect BEFORE the single deferred
   app-db install (the atomic commit boundary), so they precede both
   the committed DB changes and any fx (Mike CONFIRMED 2026-05-24, `bd
   remember --key event-pipeline-atomicity`). Reads `:rf.flow/computed`
   traces (op-type `:flow`) from the cascade's `:other` bucket per
   [spec/013-Flows.md §Flow tracing](../../../spec/013-Flows.md#flow-tracing)
   and [spec/009-Instrumentation.md §Flow trace events](../../../spec/009-Instrumentation.md#flow-trace-events).
   One row per firing in cascade order (the framework's topo-sorted
   walk):
   - `▸ :flow-id` (or `↳ :flow-id  via :upstream-flow` when the row's
     read path overlaps a preceding row's write path — subtle indent +
     `↳` glyph linking back to the upstream flow id)
   - `wrote <write-path>` + after-value (via `inspector/inspect`)
   - `read <input-path-1> <input-path-2> …` — input paths recovered
     from the registry via `(rf/handler-meta :flow flow-id)` (the
     per-firing trace does not carry input PATHS; rf2-qlzh4 polish
     bead tracks adding `:before` to the trace payload for full self-
     containment). When the flow has been cleared mid-session the
     read line renders `input paths unavailable (flow may have been
     cleared)` instead of paths.

   `:rf.flow/skip` traces (value-equal dirty-check suppression per
   [spec/013-Flows.md §Dirty-check semantics](../../../spec/013-Flows.md#dirty-check-semantics))
   are NOT rendered as rows — a flow that didn't recompute did not
   touch app-db, so it stays out of the cascade-detail by default.
7. **EFFECTS RETURNED** — silent-by-default when neither `:db` nor `:fx`
   was returned. Reads `:fx` + `:db-present?` off the `:event/do-fx`
   trace's `:tags` (rf2-twt7m Change 2). `:db` is shown as
   `<… changed; see App-db tab …>` — the diff itself lives in the
   App-db tab (and already reflects any FLOWS recomputes folded in,
   since flows ran before the db committed). When the focused event is
   `:rf.ssr/hydrated`, an additional `:rf.ssr/hydration-outcome` row
   renders with the `{:duration-ms :subs-ran :mismatches}` payload +
   (when mismatches > 0) a `→ jump to Issues bisector` affordance.
8. **EFFECTS HANDLERS RAN** — silent-by-default when no fx ran. One
   row per `:rf.fx/handled` (or override / skipped / exception) trace:
   fx-id chip + perf-tier dot + status caption. For `:dispatch` the
   queued child event renders inline as `→ queued [:foo …]`. For
   managed-fx surfaces (`:rf.http/*`, `:rf.ws/*`, `:rf.machine/*`,
   `:rf.server/*`, `:rf.flow/*`) the wire-boundary `record-panel`
   mounts INLINE beneath the row per §8.3 of the findings doc — NOT
   in a trailing block.

#### Edge cases

- **No call-site captured** — DISPATCH SITE shows
  `"source coord unavailable"`; no open chip rendered.
- **No user coeffects** — COEFFECTS section ABSENT entirely.
- **No user interceptors** — INTERCEPTORS section ABSENT entirely.
- **No effects returned** — EFFECTS RETURNED section ABSENT.
- **No fx handlers ran** — EFFECTS HANDLERS RAN section ABSENT.
- **No flows fired** — FLOWS section ABSENT entirely (silent-by-
  default; no '(none)' placeholder).
- **Flow cleared mid-session** — FLOWS section still renders the row
  (the firing happened); the read-paths line renders the absent
  placeholder since `(rf/handler-meta :flow id)` returns nil.
- **Handler threw** — §6 + §7 + §8 are all absent (handler never
  returned, so the flow transform never ran, the db never committed,
  and the fx walk never started — every post-handler stage is omitted);
  a small footer caption renders: `Handler threw — see Issues tab ⚠
  for the exception detail.` This is the ONE inline cross-reference
  to another tab.

#### What's dropped from the Event tab

- **subs ran** → Views tab.
- **renders** → Views tab.
- **`:other` errors / warnings / machine transitions** → Issues tab
  (errors), Machines tab (transitions), Trace tab (firehose).
- **db writes diff** → App-db tab. §5 carries only the `:db` presence
  marker; the actual diff is the App-db tab's job.

All sections use the §5 renderer. Long-keyword treatment (per
[`007-UX-IA.md`](007-UX-IA.md)) applies to keyword leaves. Data-
classification sentinels render per §12.

### §5.2 App-db tab content (changed-slices-first)

```
┌─ App-db tab · :app/main · cascade #347 :order/submit ───────────────────────────────────┐
│                                                                                          │
│ Path: [:cart :orders 0]   [Show full tree ▾]   [Copy path]                              │
│   ↑     ↑       ↑                                                                       │
│   each path segment is clickable; click opens the segment-inspector popup at that       │
│   path-prefix (rf2-e9tb0). Hover shows a dotted underline + "Inspect app-db at <prefix>".│
│                                                                                          │
│ ── Changed this cascade (4 slices) ─────────────────────────────────────────────────────│
│                                                                                          │
│   [:cart :orders]                                                  [fx :db]              │
│     0 ▸ {:id 92 :qty 2 :status :idle}                                                    │
│       → {:id 92 :qty 2 :status :submitting}     (~ status changed)                       │
│     1 ▸ {:id 91 …}     (unchanged)                                                       │
│                                                                                          │
│   [:cart :total]                                                   [flow :cart/totals]   │
│     45.00  →  47.50                                                                      │
│                                                                                          │
│   [:cart :submitted-at]                                            [fx :db]              │
│     nil  →  "2026-05-17T16:42:14.701Z"   (+ added)                                       │
│                                                                                          │
│   [:auth :password]                                                [mixed]               │
│     [● REDACTED]  →  [● REDACTED]   (sentinel preserved across diff)                     │
│                                                                                          │
│ ── Full tree ▸ collapsed (click to expand) ─────────────────────────────────────────────│
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

**Default disclosure:** changed slices only. Full-tree behind `[Show full tree ▾]`.

**Clickable path segments (rf2-e9tb0):** every segment of every diff path is independently clickable; clicking opens a segment-inspector popup at that path-prefix. The popup renders the value at the inspected path via Xray's data-inspector primitive. The canonical contract lives in [`004-App-DB-Diff.md`](004-App-DB-Diff.md) §Clickable path segments. (The pinned-watches strip that earlier drafts described was dropped when clickable path segments landed — the diff already identifies changes surgically, and any prefix of any diff path can be inspected with one click on its breadcrumb segment.)

**Diff colour ladder** (`inspect-diff` per [`004-App-DB-Diff.md`](004-App-DB-Diff.md)):

| Change | Colour | Symbol |
|---|---|---|
| Added (was nil/absent, now value) | accent-green | `+ ` |
| Modified (was X, now Y) | accent-amber | `~ ` |
| Removed (was value, now nil/absent) | accent-red | `- ` |
| Unchanged (full-tree only) | text-tertiary | (no prefix) |

Container-level changes inherit the worst-of-children colour.

**Path-origin tags (rf2-s8r6c):** each slice header carries a chip
identifying the cascade-step that wrote the path — `[fx :db]` (green;
event handler's `:db` return), `[flow :flow-id]` (violet; flow output
wrote this path), or `[mixed]` (yellow; both handler + flow, or
multiple flows, touched this path in the same cascade). The canonical
contract lives in [`004-App-DB-Diff.md`](004-App-DB-Diff.md)
§Path-origin tags. The chip answers *"who wrote this?"* — critical
when handler + downstream flow touch overlapping paths.

**Per-leaf classification rendering:** see §12.

**Path navigator:** breadcrumb above the diff (visible always). Click any path segment in the body → breadcrumb updates + scrolls. `Copy path` copies Clojure form (`[:cart :orders 0]`).

**Full-tree disclosure:** `[Show full tree ▾]` expands an `inspect`-rendered full app-db tree below the changed slices. Same renderer (so classification sentinels render uniformly). Default-collapsed nested maps; expand carets per node. Slow for huge databases — the renderer handles virtualisation per node via `:app-db/inspector-collapse-threshold` (default 20 keys).

**Empty states:**
- No changes this cascade: "No app-db changes this cascade. (Handler was effects-only or read-only.)"
- First cascade after page load: "First cascade — no before-state to diff against. Showing full tree." Auto-expands full-tree.

### §5.3 Trace tab content (filtered firehose)

```
┌─ Trace tab toolbar ─────────────────────────────────────────────────────────────────────┐
│ Types:  [● event]  [○ sub]  [● fx]  [○ render]  [○ machine]  [● warning]                │
│ Pills:  [+ :auth/* ✎]  [× :mouse-move ✎]  [+]                                           │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

**Trace-type toggle row.** Six chips, each a toggle:

| Chip | Default | Covers |
|---|---|---|
| `event` | **ON** | event-dispatch, event-handler-start/end, interceptor-pipeline entries |
| `sub` | OFF | sub-create, sub-compute (cached + recomputed), sub-dispose |
| `fx` | OFF | fx-dispatch, fx-handler-start/end, fx-result |
| `render` | OFF | render-start/end, mount/unmount, ratom-deref attribution |
| `machine` | OFF | machine-transition, guard-evaluate, action-fire, spawn/destroy |
| `warning` | **ON** | warnings + errors emitted via Spec 009 trace bus |

**Default-on set: events + warnings.** Higher-level tabs (Event for fx, Views for renders+subs, Machines for transitions) cover the typical cases; defaulting all-on reproduces the original firehose. **Errors are ALWAYS ON** regardless of the warning chip (explicit error gate added to UI) — hiding errors via a filter creates the silent-failure footgun.

**IN/OUT pills row.** Same pill UX as the ribbon (§3 + §7). Pill scope here is `:trace-axis-id` by default; widened via pill-edit popup checkboxes. **Trace pills are LOCAL to the Trace tab** (don't share state with ribbon pills — ribbon pills filter the event list; trace pills filter trace entries inside one cascade).

**Severity colouring:**

| Severity | Treatment |
|---|---|
| `error` | row text red (`text-error`); 1px left border `accent-red`; never filtered out |
| `warning` | row text yellow (`text-warning`); 1px left border `accent-yellow` |
| `info` (default) | row text `text-secondary`; no border; dim relative to errors/warnings |

**Row layout** (TWO-LINE; Trace tab keeps two-line rows — this surface is forensic, intentionally different from L2's orienting single-line):

```
● 16:42:14.701.234  event:dispatch  [:order/retry {…}]                       12µs
                    src/cart/events.cljs:267 · cascade #347
⚠ 16:42:14.703.012  sub:recompute    :cart/can-retry? [92]  → true              4µs
                    src/cart/subs.cljs:42 · was-cached: false · :rf.cache/miss
x 16:42:14.713.501  fx:result        :http/post failed: 500                    87ms
                    src/cart/fx.cljs:18 · error: connection-reset
```

Virtualised list (overscan 20). See [`013-Trace-Consumer.md`](013-Trace-Consumer.md) for the underlying trace-bus contract.

### §5.4 Issues tab content

**Purpose:** the "what's wrong?" rollup. One tab to scan for problems; click a row to seek the spine to the offending cascade.

**IN the Issues tab:**

| Category | Source | Default | Row treatment |
|---|---|---|---|
| **JS exceptions** | uncaught errors; React lifecycle exceptions; promise rejections at handler scope | ON | red gutter; full stack-trace in detail expand |
| **Schema violations** | Malli registration on app-db / event-args / sub-output | ON | yellow gutter; offending path + expected vs actual via `inspect-diff` |
| **Sensitive-data warnings** | `:rf/redacted` paths that escaped via `console.error` before marking applied · per-marking-site mark-misses (an `add-marks` / `set-marks` path pointing to nothing — typo detection) | ON | magenta gutter; marker-aware so the warning itself doesn't leak the value |
| **Hydration mismatches** | SSR-only; mismatched server/client tree | ON | yellow gutter; node path + server vs client text |
| **Perf-budget overruns** | cascades exceeding configured perf budget | ON | orange gutter; actual vs budget + cascade-id |
| **App console errors/warns** | host app's `console.error` / `console.warn` calls (captured via hook) | ON | dim grey gutter (advisory); raw text |

**OUT of the Issues tab** (deliberately excluded):

| Category | Lives in |
|---|---|
| Subscription design advisories | Views tab → per-sub advisory chip on sub-row |
| Framework-internal `console.warn` | Dev console (where they originate) |
| Recoverable HTTP retries (recovered = not an issue) | Trace tab (visible when `fx` chip ON) |
| Filtered-OUT events that errored (already surfaced via row error-override) | Event list with `⚠` + `▽` filter-bypass gutter |

**Layout:**

```
┌─ Issues tab content ────────────────────────────────────────────────────────────────────┐
│ All issues this session ◉   Spine cascade only ○                                         │
│                                                                                          │
│ ⚠ EXCEPTION    16:42:15.244   cascade #349 :checkout/finalize                            │
│   exception: cart-id missing                                                             │
│   src/cart/events.cljs:340 ↗   [Seek to cascade]                                         │
│                                                                                          │
│ ▥ SENSITIVE    16:42:14.103   cascade #346 :auth/login                                   │
│   sensitive path [:auth :password] logged via console.error before mark applied          │
│   src/auth.cljs:88 ↗   [Seek to cascade]   (value redacted — no reveal)                  │
│                                                                                          │
│ ◐ PERF         16:42:13.991   cascade #347 :order/submit                                 │
│   handler took 142ms (budget 100ms)                                                      │
│   src/cart/events.cljs:213 ↗   [Seek to cascade]                                         │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

Default scope = "All issues this session." Toggle to "Spine cascade only" to filter to focused cascade's issues.

### §5.5 Machines tab — see [`003-Machine-Inspector.md`](003-Machine-Inspector.md)

Briefly (rf2-y9xmf): the Dynamic panel is **event-driven only**. It is BLANK when the focused event triggered no machine transitions; it renders one per-machine section (topology + transition highlight + guards + actions + cancellation cascade + `:after` rings) when the focused event did trigger transitions. Per-machine prev/next nav walks the spine's epoch history to the prior/next event that ALSO touched the focused machine. The UC1 Sim engine landed under the Static Machines surface's Sim sub-mode (rf2-r4nao) at `:rf.xray.static.machines/sim-*` (view at `tools/xray/src/day8/re_frame2_xray/static/machines/sim.cljs`); it does NOT render in the Dynamic tab. UC2 Mode A/B/C dynamic-instance UI remains a Dynamic-side concern, reached from Static via the per-row → Dynamic JUMP.

### §5.6 Routing tab — parallel to Machines (rf2-nrbs9)

The 7th tab — promoted from "lives in App-db + Trace" to its own lens tab per Mike's design call (2026-05-18). The full content contract lives in [`016-Auxiliary-Panels.md`](016-Auxiliary-Panels.md) §Routing tab; this section locks the L4 detail-panel switch entry + the lens model the Event-Spine asserts.

**FLAT lens model (rf2-lq0ef).** The Routing tab opens to a **flat, focused-event lens** — current matched route + params/query/fragment + a **Simulate-URL** input that ranks every registered route against the entered URL using the 6-rule `:rf.route/rank` tuple, with the **rank explainer** surfaced inline (which rule decided each rank position). The legacy URL-depth route TREE as the orientation surface is **gone** — URL-depth nesting was hard to scan, and `Simulate-URL` + the 6-rule rank explainer together answer the *"what would this URL match?"* orientation question better than a static tree ever did.

**Per-focused-event highlighting** (parallel to the Machines tab's focused-event lens):

| Marker | When | Visual |
|---|---|---|
| `◆ HERE` | The current matched route, always | Violet chip (`accent-violet`); left-border accent |
| `◆ FROM` | Cascade caused navigation — the prior route | Cyan chip; left-border accent |
| `◆ TO` | Cascade caused navigation — the new route | Green chip; left-border accent |

When `◆ TO` is set, `◆ HERE` collapses into it — TO is the new HERE. When the focused cascade has no routing impact, only `◆ HERE` surfaces (orientation only).

**Detection contract:** the panel scans the focused cascade's trace events for a `:rf.route.nav-token/allocated` emit (per [`spec/012-Routing.md`](../../../spec/012-Routing.md) — the emit fires inside both `:rf.route/navigate` and `:rf.route/transitioned`). The emit's `:tags :route-id` is the TO; the current `:rf/route` slice's `:id` (when different) is the FROM. Same-route re-navigations (different params/query, same route-id) collapse FROM — surfacing a FROM equal to TO is noise.

**Below the active route:** params + query + fragment rendered as a labelled grid so the lens always shows the same skeleton (predictable scanning); absent slots render as `—`. The Simulate-URL input + ranked candidate list lives below the params block — see [`016-Auxiliary-Panels.md`](016-Auxiliary-Panels.md) §Simulate-URL contract for the data shape and interaction rules.

**Silent state.** When the host app registers no routes the panel renders only the header + a terse `No routes registered.` one-liner. No `(none)` placeholder, no marketing copy (silent-by-default per rf2-g3ghh).

**L4 case-switch entry.** The detail panel's case-switch (`shell.cljs` §L4 detail panel) routes `:routing → [routing/Panel]`. The panel reads `:rf.xray/routing-tab-data`, a composite over `:rf.xray/registered-routes` + `:rf.xray/current-route-slice` + `:rf.xray/cascades` + `:rf.xray/focus`.

---

## §6 Spine binding — `:rf.xray/focus`

The single-axis selection that every layer reads from.

```clojure
;; The spine sub
:rf.xray/focus
;; ->
{:dispatch-id <id-or-nil>     ; cascade root the user focused on
 :epoch-id    <id-or-nil>     ; epoch the cascade settled in
 :frame       <frame-id>      ; frame the cascade ran in
 :mode        :live | :retro  ; :live tracks head; :retro pins
 :head?       <bool>          ; true when :dispatch-id is the latest cascade
 :previewing? <bool>}         ; true while user hovers without committing
```

### Spine events

| Event | When dispatched | Effect on spine |
|---|---|---|
| `:rf.xray/focus-cascade <id>` | User click row · double-click row · palette jump | Sets `:dispatch-id <id>`, computes `:epoch-id` from cascades, flips `:mode → :retro` |
| `:rf.xray/focus-cascade-prev` | `◀` button · `j` / `←` key | Steps `:dispatch-id` back one in `:rf.xray/filtered-cascades`; flips `:mode → :retro` |
| `:rf.xray/focus-cascade-next` | `▶` button · `k` / `→` key | Steps `:dispatch-id` forward one in `:rf.xray/filtered-cascades`; flips `:mode → :retro` if not already at head |
| `:rf.xray/follow-head` | `⏭` button · `L` key | Sets `:mode :live`, clears pinned id, snaps `:dispatch-id` to head |
| `:rf.xray/toggle-live-pause` | `Space` key | Pauses/resumes LIVE buffer-to-list flow; buffer continues collecting; mode stays LIVE (paused) |
| `:rf.xray/select-frame <frame-id>` → `:rf.xray/set-frame <frame-id>` | Frame picker selection | **`:rf.xray/select-frame`** is the canonical write surface (event-fx; dispatched by the frame-switcher view + the palette + `core/set-target-frame!`). It writes the dedicated `:view-scope-frame` slot (the VIEW SCOPE the L2 list scopes by — rf2-4vp5j) AND dispatches the spine primitive **`:rf.xray/set-frame`**, which writes `:focus :frame` + clears `:dispatch-id` to head of the new frame. Per the multi-frame panel-focus fix wave (rf2-fvplw / rf2-y8bik / rf2-ug1r6 / rf2-thodq) the `set-frame` write ALSO re-seeds `:rf.xray/target-frame` (the per-frame projection axis the App-db diff + Views composites read) AND `:rf.xray/epoch-history` (the cached snapshot of `(rf/epoch-history target)`) so every per-frame panel follows the picker as one atomic move — see [§Multi-frame panel-focus invariant (P) — v1 ships](#multi-frame-panel-focus-invariant-p--v1-ships) below. |
| `:rf.xray/preview-cascade <id>` | Row hover (before click commits) | Sets `:previewing? true`, `:dispatch-id <id>` transiently; reverts on hover-out without click |

### Per-layer rebind table

| Layer | Surface | Reads from spine | Notes |
|---|---|---|---|
| L1.5 events ribbon | Nav cluster (`◀` `▶` `⏭`) | `:dispatch-id`, `:mode` | Disabled state when at boundaries |
| L1 chrome ribbon | Frame picker (VIEW SCOPE) | `:rf.xray/view-scope-frame` | Writes `:view-scope-frame` (+ spine `:focus :frame`) via `:rf.xray/select-frame` |
| L1.5 events ribbon | Filter pills + hidden indicator | `:rf.xray/active-filters` · `:rf.xray/hidden-by-filters` | Filters re-derive `:rf.xray/filtered-cascades`, which the L2 list reads; `N hidden` + `Clear Filters` surface when a filter suppresses rows |
| L2 event list | Head-row mode cue | `:mode`, `:head?` | Pulse on head row in LIVE; pinned-row glyph in RETRO (LIVE/RETRO is a spine state; the Dynamic/Static mode dropdown is a separate chrome-ribbon control) |
| L2 event list | Row gutter glyph | `:dispatch-id` | `◉` on focused row; `●` elsewhere |
| L2 event list | Auto-scroll behaviour | `:mode`, `:head?` | LIVE: auto-scroll bottom; RETRO: sticky position |
| L3 tab bar | Count badges (`Views 8`) | Focused cascade's projection counts | Re-derives on `:rf.xray/focus` change |
| L4 detail panel | Tab content | `:dispatch-id`, `:epoch-id`, `:frame` | Per-tab projection consumes spine |

**Atomicity contract:** the spine sub is the ONLY axis. When a user clicks a row, EVERY dependent surface (count badges, gutter glyph, detail panel content, the spine's mode cue) rebinds in the next animation frame. No panel maintains its own selection state; no panel reads `(peek history)`; no panel reads `:selected-dispatch-id` (the two-axis legacy slots are deleted).

### Sub-graph

```
:rf.xray/cascades                 ← raw cascade list from Tool-Pair projection
        │
        ▼
:rf.xray/view-scope-frame         ← single defaulted VIEW SCOPE (rf2-4vp5j; head-frame default)
:rf.xray/active-filters           ← IN/OUT pill state (Xray app-db slot)
:rf.xray/muted-event-ids          ← muted-event-id set (Xray app-db slot)
        │
        ▼
:rf.xray/filtered-cascades        ← single switch point: list + scrubber + counters
        │                            (view-scope frame FIRST, then IN/OUT pills + mutes)
        ▼
:rf.xray/focus                    ← spine: {:dispatch-id :epoch-id :frame :mode :head? :previewing?}
        │
        ├──── L1 chrome ribbon (frame picker, mode dropdown, ⚙ ✕)
        ├──── L1.5 events ribbon (nav, focus-chip, filter pills, N-hidden + Clear Filters)
        ├──── L2 event list (focused row, auto-scroll)
        ├──── L3 tab bar (count badges)
        └──── L4 detail panel (per-tab content)
```

The scoping + filtering happens at the data layer (`:rf.xray/filtered-cascades`), not at render. Reasons:
1. Virtualisation cares about row count — render-time filtering means the virtualiser budgets unfiltered rows.
2. Scrubbing must respect the scope + filters — `[◀ ▶ ⏭]` walks `:rf.xray/filtered-cascades`, not all cascades.
3. **Frame scope applied FIRST, then filters.** Per rf2-4vp5j the picker is a VIEW SCOPE (not a filter): `:rf.xray/filtered-cascades` scopes to `:view-scope-frame` via `matcher/filter-cascades-by-view-scope` BEFORE applying the IN/OUT pills + mutes, so the L2 list, scrubber, and nav `[◀ ▶ ⏭]` all walk the frame-scoped, pill-filtered list as one. The frame scope is excluded from the hidden-by-filters count (frame ≠ filter); the pills + mutes are what that count measures. Spine's LIVE auto-tracking ALSO respects the view scope so `:head?` and the head walk are scoped per-frame.

### LIVE / RETRO transitions

| From | To | Trigger |
|---|---|---|
| LIVE | RETRO | Click any row that isn't head · `j` / `k` / `◀` / `▶` step |
| RETRO | LIVE | `L` key · `⏭` button |
| LIVE | LIVE (paused) | `Space` key |
| LIVE (paused) | LIVE | `Space` key · `L` key (snap-LIVE implies resume) |

The spine carries `:mode`; the L2 event list reads it for LIVE-tracking + sticky-on-older + the head-row pulse cue. (The dedicated Mode pill widget that earlier drafts placed in the ribbon was dropped — the spine cue in L2 is the only mode surface.)

---

## §7 Filter system

**Single-tier filtering.** The ONLY filtering surface is the
events-ribbon IN/OUT pills (+ the L2-row mute affordance) — they scope
the L2 event list, the scrubber nav, the Issues counter, and palette
verbs at the data layer (`:rf.xray/filtered-cascades`). The earlier
"Trace tab filter toolbar" was **removed** (rf2-gkczt): the Trace L4
panel is scoped to the focused epoch's `:trace-events` and carries no
filtering UI at all — the focused epoch IS its scope (see
[`021-Dynamic-Panel-Designs.md`](021-Dynamic-Panel-Designs.md) §Trace
panel).

**Frame is a view SCOPE, not a filter (rf2-4vp5j).** The frame picker is
NOT part of the filter system. It is a single, defaulted VIEW SCOPE
(default = the head epoch's frame); it is never counted as "hidden",
never listed as a filter cause, and never reset by Clear Filters. See
[§Frame picker is a view scope](#frame-picker-is-a-view-scope-rf2-4vp5j)
below.

### Ribbon pills

Two modes, multiple of each, AND'd across modes; OR'd within mode:

```
ACTIVE FILTERS = (match-any-IN) AND NOT (match-any-OUT)

  IN  pills:  [+ :auth/*]  [+ http]                ← whitelist; show ONLY matches
  OUT pills:  [× :mouse-move]  [× :anim-frame]     ← blacklist; hide matches
```

- **No IN pills present** → show everything not blacklisted.
- **One or more IN pills** → restrict to events matching ANY IN pattern, minus any OUT match.

### Pill visual contract

| Mode | Glyph | Border colour | Example |
|---|---|---|---|
| filter-IN | `+` | green | `[+ :auth/* ✎]` |
| filter-OUT | `×` | magenta | `[× :mouse-move ✎]` |
| add-new | trailing `+` | tertiary outline | `[+]` |
| overflow | `…N more ▾` | tertiary | `[…3 more ▾]` |

Pill `✎` icon (pencil) = "click to edit." Whole pill is the clickable target.

### Add-filter dialog (click-pill → edit popup)

The dialog copy is normative (rf2-ad7zx.19). The Add-filter form (the
trailing-`+` / `:add` source) reads exactly:

```
┌─ Filter events ─────────────────────────────────┐
│ Action                                          │
│   (•) Show only matching events                 │
│   ( ) Hide matching events                      │
│                                                 │
│ Match events containing                         │
│   [ :auth/*, :mouse-move, /login ]              │
│   Matches keywords, namespaces, globs, or text. │
│   Examples: :auth/*, :auth, :mouse-move, /login │
│                                                 │
│ ───────────────────────────────────────────────  │
│                       [Cancel]    [Add filter]  │
└─────────────────────────────────────────────────┘
```

- **Title** is `Filter events` on the trailing-`+` (`:add`) source.
  The `:pill` (edit existing) source titles `Edit filter` and exposes
  the `[Delete]` button; the `:context` (right-click) source titles
  `Add filter for this event`.
- **Action radios** — `Show only matching events` (IN) /
  `Hide matching events` (OUT) — toggle the mode (IN ↔ OUT) without
  delete/recreate. The wording is presentation; the underlying
  IN/OUT slot semantics are unchanged (§7 matcher algebra).
- **`Match events containing` field** accepts: exact keyword
  (`:auth/login`), glob (`:auth/*`, `:order.cart/*`), namespace
  (`:order/*` matches namespace `order`), substring (`/login`). The
  input placeholder is `:auth/*, :mouse-move, /login`; the two-line
  helper under it reads `Matches keywords, namespaces, globs, or
  text.` then `Examples: :auth/*, :auth, :mouse-move, /login`.
- **Buttons** — `[Cancel]` discards the draft; the primary button is
  `Add filter` on the `:add`/`:context` sources and `Apply` on the
  `:pill` edit source.
- **Event-id is the only scope.** The dialog produces exactly the `{:pattern <kw-or-str>}` shape keyed off the cascade's event-id (rf2-o8pjv). There is no match-scope selector — the matcher honours event-id exclusively.
- **Trailing `+`** opens the same popup with empty pattern + default mode = IN.

### Right-click event-row → context menu

```
┌──────────────────────────────────────────────────┐
│ ◯ Open source in editor                          │
│ ◯ Copy event id                                  │
│ ◯ Copy event vector                              │
│ ─                                                │
│ ◯ Pin this cascade                               │
│ ◯ Re-dispatch                                    │
│ ─                                                │
│ Filter-OUT (hide):                               │
│ ◯ × Hide events with id :order/submit            │
│ ◯ × Hide events from ns :order/*                 │
│ ─                                                │
│ Filter-IN (show only):                           │
│ ◯ + Show only events with id :order/submit       │
│ ◯ + Show only events from ns :order/*            │
│ ─                                                │
│ Contextual:                                      │
│ ◯ + Show only events from machine :form          │  ← when row has machine badge
│ ◯ + Show only HTTP events                        │  ← when row has HTTP badge
│ ◯ + Show only errored events                     │  ← when row has error gutter
└──────────────────────────────────────────────────┘
```

No confirm — both IN and OUT are reversible.

### Empty defaults + Recommended quick-add

Ship empty by default — no shipping `:mouse-move` filtered out, because there's no universally-noisy event in re-frame's universe. Surfacing missing events on first session is worse than noisy first session that prompts the user to filter.

The Settings popup (§9) and the empty-list empty state both offer a **Recommended filters** quick-add:

```
Recommended filters for high-frequency apps:
  ☐ :mouse-move        (pointer-coord events; very high volume)
  ☐ :anim-frame        (requestAnimationFrame ticks)
  ☐ :resize            (window resize coalescing)
  ☐ :pointermove       (modern pointer events)
  ☐ :scroll            (scroll events; often debounce-triggered)

[Apply selected]   [Apply all]   [Cancel]
```

User CHOSE to load them. First session is honest about what's filtered.

### Auto-filter chip strip (data-classification)

Per [spec/015-Data-Classification](../../../spec/015-Data-Classification.md), the framework emits trace events with sentinel-tagged values. When the trace bus drops sensitive content (under default `:rf.privacy/show-sensitive? false`), Xray's chrome surfaces the count via per-row redaction markers + Settings → Diagnostics — NOT as an auto-filter chip. (Earlier drafts also placed a per-session totals tooltip on the Mode pill widget; that widget was dropped, so the markers + Settings panel are the only session-totals surfaces.) The auto-filter mechanism described in earlier round designs collapses into the standard ribbon-pill UX: any user-added OUT pill for an event-id is the canonical filter. Xray does not auto-add filters on the user's behalf.

### v1 ships: right-click → edit-popup (NOT silent append)

This section's earlier wording — that the right-click context menu's "Always hide this event-type" item silently appends to the OUT bucket — is **superseded by v1's actually-landed behaviour** (rf2-ak4ms).

**v1 ships:** the right-click → "Always hide this event-type" item **opens the edit popup pre-populated** with the event-id as the pattern + mode = OUT, source = `:context`. The user sees what's about to land in the OUT bucket and can fine-tune the pattern or cancel before commit. No `[Delete]` button (the pill doesn't exist yet); `[Apply]` confirms.

Rationale: pre-alpha posture (per the masterpiece principle). Silent mutation of the filter set on a right-click is the kind of surprise the visible-confirm flow trades a click to avoid. The popup's three trigger sources — `:pill` (edit existing), `:add` (trailing `+`), `:context` (right-click) — share the same modal so the edit-popup is the single mutation site for the IN/OUT slot.

### Matcher scope: event-id only (rf2-o8pjv)

The dialog filters on **`event-id` only** — it is the implicit, only scope. The matcher (`filters/matcher.cljc`) consults the pill's `:pattern` against the cascade's event-id. Earlier drafts shipped a "Match scope" section (`event-id` / `event-args` / `source-coord` / `tags` checkboxes) as speculative scaffolding for a future widening pass; the three wider scopes were never matched, only stored. Per the pre-alpha masterpiece posture (no non-functional UI, no shims) that section and all its plumbing were removed: the pill shape reduces to `{:pattern <kw-or-str>}`. Surface-aware predicates (machine / http-correlation / fx) arrive as distinct typed-predicate **kinds** via right-click affordances on other panels (`filters/typed_predicates.cljc`), not as a scope-widening of this dialog.

### Filter persistence — write-through, reset-on-load (rf2-swclw)

Ribbon pills round-trip through localStorage per host-app under a
Xray-namespaced key. **v1 storage key:** `"re-frame2.xray.filters.v1"`
(versioned so future schema changes can ignore stale payloads).
Configurable via `(xray-config/configure! {:rf.xray/filters-storage-key "<key>"})`
per [`015-Configuration.md`](015-Configuration.md) — hosts that run
multiple Xray instances in the same browser session (Story testbeds)
override so each instance keeps its own pill state.

**Transient filters RESET on every load (rf2-swclw).** The IN/OUT pills,
the muted-event-id set, and the frame pin are **session-scoped
exploration filters** — a fresh page load starts FULLY UNFILTERED, never
silently carrying a stale filter from a past session (the trap that hid
events and made the inspector look broken — rf2-jvghz; an inspector's
prime directive is to show the truth). The first-mount hook
(`mount.cljs/::reset-transient-filters`) does NOT hydrate these slots
(so each starts at its registry default — empty pills / empty mute set /
unpinned frame) AND clears each slot's stale localStorage value so the
storage matches what the user sees and a phantom value can never
resurface. Only **durable view prefs** (Dynamic/Static mode, density,
panel layout) hydrate on load via their own hooks. The persist fx still
writes pills through to localStorage *within* a session so a mid-session
mutation survives a sub-recompute; it is the LOAD that resets, not the
write that stops.

Host-supplied seed via `(xray-config/configure! {:rf.xray/filters {:in […] :out […]}})` lands ONLY on first install when localStorage is empty — the seed never clobbers a user's hand-tuned set. Per the [`Empty defaults`](#empty-defaults--recommended-quick-add) policy above, Xray itself ships with `nil` seed (first-session honesty).

### "N events hidden by filters" indicator + Clear Filters (rf2-jvghz)

Because filters reset on load (above) but can still hide rows *within* a
session, the events ribbon carries an in-session safety net: when ANY
suppressing filter is active (an IN/OUT pill or a non-empty mute set),
the ribbon's far-right action cluster renders a **`Clear Filters`**
button; beside it, an **`N events hidden by filters`** message renders
*only when N > 0* (`N = max 0 (raw-visible − filtered-visible)`, both
counts taken over the L2 list's visible-row set, both scoped to the
selected frame). An active pill that happens to hide nothing → button
present, message absent. The frame view-scope is excluded from the count
entirely (frame ≠ filter — rf2-4vp5j), so switching frames never inflates
N and `Clear Filters` never clears the frame. The pure model lives in
`filters/hidden.cljc` (`summary`); `Clear Filters` resets the pills + the
mute set (not the frame).

### Frame picker is a view scope (rf2-4vp5j)

Post rf2-4vp5j the frame picker is **a single, defaulted VIEW SCOPE — not
a data-layer filter** (this supersedes the earlier rf2-oziyr "picker
frame composed into `filtered-cascades`" framing in §6):

- **Default = head-epoch frame.** On a fresh load the L2 list scopes to
  whichever frame produced the most-recent pickable event
  (`frame-switcher/head-frame`), rather than merging every frame.
- **Single dedicated slot.** The picker writes `:view-scope-frame` (via
  the canonical event-fx `:rf.xray/select-frame`); the L2 list scopes by
  it through `matcher/filter-cascades-by-view-scope`, which is a NO-OP
  when the scope is nil (a defaulted scope never drops the
  not-yet-frame-tagged `:ungrouped` bucket). `:rf.xray/select-frame`
  ALSO dispatches the spine's `:rf.xray/set-frame` (re-seeding
  `:focus :frame` + `:rf.xray/target-frame` + `:rf.xray/epoch-history`
  per invariant P) so the per-frame panels follow the picker as one
  atomic move.
- **NOT persisted, NOT counted as hidden.** The view scope resets to its
  head-frame default on load (rf2-swclw) and is excluded from the
  hidden-by-filters count + `Clear Filters` (above).

The Settings popup §9 exposes the pill set + Recommended quick-add + factory-reset (the §9 Filters tab in v1 is a pointer into the ribbon pill UI — full per-pill management surface lives in the ribbon per spec §3; see [`016-Auxiliary-Panels.md`](016-Auxiliary-Panels.md) §Settings popup — v1 ships).

### Error overrides

When a filtered event raises an exception, surface it anyway (`:rf.xray/filters-auto-hide-error-overrides?` config key, default `true`). The row appears with both `⚠` and `▽` (filter-bypass) gutter; user knows "this would normally be hidden, but it errored."

### Data-layer filtering

Filter applied at DATA layer (`:rf.xray/filtered-cascades` sub), not render. See §6 sub-graph.

```clojure
:rf.xray/active-filters
;; ->
{:in  [{:pattern <kw-or-glob-or-ns>}  ; event-id only (rf2-o8pjv)
       …]
 :out [{:pattern <…>}
       …]}
```

Every consumer (event list, scrubber, Issues badge counter, palette verbs) reads `:rf.xray/filtered-cascades`. Raw `:rf.xray/cascades` stays as primitive for unfiltered totals.

---

## §8 Frame-observation isolation invariants

**Principle:** Xray observes ANOTHER frame, NEVER itself. The split between Xray's internal state (lives in `:rf/xray`) and the inspected host frame (`:rf/default`, `:app/main`, etc.) is load-bearing. Without strict enforcement the inspector recursively inspects itself, the Views panel fills with Xray's own re-renders, and the user can't see their app.

### The four invariants

| # | Invariant | Enforcement |
|---|---|---|
| **I1** | **Frame picker excludes `:rf/xray`** from the inspectable-frame list by default. Internal frames (`:rf/xray`, future `:rf/re-frame2-pair`) are filtered out at the available-frames consumer in `frame_switcher.cljs` (the chrome-ribbon frame dropdown). | Settings popup (§9) carries a power-user toggle **"Show tool frames in picker"** under View → Power user (off by default; off in fresh installs; on only when a framework dev is debugging Xray itself). |
| **I2** | **No Xray UI view reads from `:rf/xray` for data purposes.** Subscribes inside a Xray view that need host-app data MUST target the selected frame (`(rf/sub :the-sub :frame (sub :rf.xray/focus.frame))` form). Subscribes targeting Xray's own state (selection, mode, filters, settings) are fine but never appear in the inspected-data panels (Event/App-db/Views/Trace/Machines/Issues). | Code review + dev-time lint: a predicate added to `tools/xray/src/.../shell.cljs` mount path walks the registered sub graph and asserts no Xray-namespaced sub feeds an Event/App-db/Views/Trace/Machines/Issues render path. Throws useful error during dev mount; no-op in production. |
| **I3** | **Views panel render-attribution is scoped to the selected frame ONLY.** The frame's per-cascade render projection must filter component-render entries to those whose owning frame matches `:rf.xray/focus.frame`. Xray's own React subtrees must not bleed in even when both frames mount under the same `react-dom` root. | Implementation: render tracker tags each component-render with `:owning-frame` at capture time; Views panel reads `(filter #(= (:owning-frame %) frame) renders)`. |
| **I4** | **Test gate — Xray-self-observation is disallowed by CI.** Browser feature test: open Xray; select `:rf/default`; trigger a Xray-internal hover (a Xray-side hover-render); assert Views panel for `:rf/default` does NOT include any Xray-namespaced component. | Lives in `tools/xray/test/day8/re_frame2_xray/isolation_test.cljs` (new). Runs in `npm run test:browser`. **Failure blocks merge.** |

### Test contract

The browser feature gate that asserts I1 + I3 + I4 together is the canonical isolation test. It mounts Xray in a host app, drives a deterministic sequence:

1. Mount host app with `:rf/default` frame.
2. Mount Xray (via `[data-rf-xray-host]`).
3. **Assert I1:** open ribbon's frame dropdown; assert option list does NOT include `:rf/xray`.
4. Select `:rf/default` in the frame picker.
5. Trigger a Xray-internal hover (e.g. hover an event row, which causes Xray's hover-render).
6. Open Views tab.
7. **Assert I3:** Views panel for `:rf/default` does NOT include any component whose namespace starts with `day8.re-frame2-xray`.
8. Open Settings popup → View → Power user → toggle "Show tool frames in picker" ON.
9. Re-open ribbon's frame dropdown.
10. **Assert I1 (inverse):** option list NOW includes `:rf/xray` under a `── Power user ──` divider.

**Gate name:** `tools/xray/test/day8/re_frame2_xray/isolation_test.cljs` (new file). Runs under `npm run test:browser`. **Failure blocks merge.**

**Lint I2:** unit test asserting the dev-time lint predicate throws on a deliberately-misconfigured Xray-namespaced sub feeding a host-data render path; passes on the actual Xray registry. Lives in `tools/xray/test/day8/re_frame2_xray/sub_graph_lint_test.cljs` (new file). Runs under `npm run test:cljs`.

### Multi-frame panel-focus invariant (P) — v1 ships

The four invariants above (I1–I4) keep Xray-internal renders OUT of
inspected-host-frame panels. The complementary invariant — panels
follow the picker INTO whichever inspected frame the user picks —
landed as the multi-frame panel-focus fix wave (rf2-fvplw + rf2-y8bik
+ rf2-ug1r6 + rf2-thodq):

| Slot | Owner | What `:rf.xray/set-frame` does |
|---|---|---|
| `:focus :frame` | spine | Set to picked frame-id; clears `:dispatch-id` to head of new frame. |
| `:rf.xray/target-frame` | per-frame projection axis | Re-seeded to picked frame-id. This is the legacy axis the App-db diff + Views composites compose against; pre-fix the picker only wrote the spine's `:focus :frame` and the composites stayed bound to whichever frame was last targeted (commonly `:rf/default`). |
| `:rf.xray/epoch-history` | cached snapshot | Re-seeded from `(rf/epoch-history <picked-frame>)` so the App-db diff's `:selected-epoch-diff` / `:sections` / `:annotated-tree` and Views' `:focused-cascade-pair` / `:views-sub-diff` composites refresh against the new frame's epoch ring in the same dispatch. |

**Invariant P (Panel follows focus):** every per-frame panel
composite (App-db diff, Views, Machines, Issues, Trace) MUST refresh
its data axis on every `:rf.xray/set-frame` write within the same
dispatch. No panel may persist a frame-binding that survives a picker
selection. The picker is the single seam that re-routes every
per-frame surface.

The composite seam `:rf.xray/observed-frame` reads
`(:frame focus)` first (the spine slot the picker writes; also
derived by `compose-focus` from the focused cascade) and falls back
to `:rf.xray/target-frame` so click-on-row picker-less navigation
also rebinds. Both writes happen in the same `:rf.xray/set-frame`
reducer so the App-db diff renders the diff body for the picked frame
on the next render-frame, and the Views panel's `:has-cascade?`
guard does not flip to `false` between picks.

Test gates: `spine_cljs_test.cljs` pins the `:target-frame` +
`:epoch-history` writes on both arities of the reducer; the
`app_db_diff_cljs_test.cljs` + `views_subs_cljs_test.cljs` regression
suites pin the panel render bodies post-reseed.

---

## §9 Settings popup

**Trigger:** `,` key OR `s` key OR click ribbon `⚙` icon.

**Shape: modal overlay** (NOT a dedicated panel). Centred floating panel at 560×640 default; backdrop dim (15% black) but Xray visible underneath. Closes on `Esc`, click outside, or click `✕` in panel header. Settings persist immediately on change (no Apply/Cancel — every toggle/field writes through to `(xray-config/configure! …)` on commit).

**Why modal not panel:**

1. Settings is **transient** — open, tweak, close — not browsed. Modals fit transient workflows; panels fit ongoing reference.
2. Modal **preserves the user's last-active tab** — they don't lose context.
3. Dedicated panel would force a tab-bar slot — tab count would creep back up (6 is hard-won; adding Settings would make 7).
4. Modal pattern matches Cmd-K palette (also transient overlay) — consistent affordance class.

### Wireframe

```
┌─ Settings ───────────────────────────────────────────────────  ✕  ┐
│                                                                    │
│ ◉ Filters                                                          │
│ ○ View                                                             │
│ ○ Keybindings                                                      │
│ ○ Buffer                                                           │
│ ○ Popout                                                           │
│ ○ Actions                                                          │
│                                                                    │
│ ─ Filters ──────────────────────────────────────────────────────  │
│                                                                    │
│ Active filters (5)                                                 │
│   [+ :auth/* ✎]  [× :mouse-move ✎]  [+ http ✎]                    │
│   [× :anim-frame ✎]  [× :pointer-move ✎]                          │
│   [Clear all] [Add pill]                                           │
│                                                                    │
│ Recommended filters                                                │
│   ☐ :mouse-move        (pointer-coord events; high volume)        │
│   ☐ :anim-frame        (requestAnimationFrame ticks)              │
│   ☐ :resize            (window resize coalescing)                  │
│   ☐ :pointermove       (modern pointer events)                     │
│   ☐ :scroll            (scroll events; often debounce-triggered)  │
│   [Apply selected]   [Apply all]                                   │
│                                                                    │
│ Auto-hide error overrides? ◉ Yes  ○ No                            │
│   When ON, OUT-matched events that ERROR still surface on the list │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

### Sections (left-rail navigation; right-pane content)

| Section | Content |
|---|---|
| **Filters** (default) | Active filter pills (mirror of ribbon pills; edit/delete) · Recommended-filters quick-add · auto-hide-error-overrides toggle · advanced pattern-editor for complex globs |
| **View** | Theme: `◉ Dark · ○ Light · ○ Dim` · Density: `◉ Cosy 28px · ○ Compact 22px` (event-list row height; the `:comfy` tier was dropped per 015 §Density) · Long-keyword treatment threshold (chars) · **`── Power user ──` divider · "Show tool frames in picker" toggle** (OFF by default; reveals `:rf/xray` etc. in ribbon picker; only useful when debugging Xray itself) |
| **Keybindings** | Table of `Action / Chord / Edit` rows; per-row chord editor (click chord cell → focus, press new chord); reset-to-defaults button per row + global; `Handle keys?` master toggle |
| **Buffer** | `:buffer/retained-epochs <int>` (default 200) · `:buffer/cascades-retained <int>` (default 50) · `:app-db/inspector-collapse-threshold <int>` (default 20) · "Clear buffer now" button |
| **Popout** | `:popout/width <px>` (default 800) · `:popout/height <px>` (default 600) · `:popout/position <:right :left :centre>` (default `:right`) · "Open in popout now" button (= `o`) |
| **Actions** | `[factory-reset!]` BIG RED BUTTON · "Reset to fresh-install state — clears filters, pins, keybindings, theme. Buffer kept." Confirmation modal on click. Plus: "Reset filters only" / "Reset keybindings only" / "Clear pinned cascades" finer-grained reset buttons. |

### v1 ships

**v1 ships five sections, NOT six** (rf2-9poxq + rf2-jh9ws +
rf2-ttnst + rf2-ou3pn): **General**, **Filters**, **Keybindings**,
**Buffer**, **Diff** — a top tab strip (not left-rail nav) drives
which body section renders. Defaults: auto-open-on-error OFF,
panel-position `:right-rail`, theme `:light` (Figma authority;
toggled by the top-ribbon sun/moon icon), text-size 13 px. Storage
key `re-frame2.xray.settings.v1` (single nested map, one round-trip
through `pr-str`). Full per-knob inventory + persistence rationale +
auto-open-watcher semantics are in
[`016-Auxiliary-Panels.md`](016-Auxiliary-Panels.md) §Settings popup
— v1 ships. (A Telemetry tab shipped briefly with the initial popup
landing but was removed per rf2-jh9ws — Xray transmits no telemetry
and the toggle was a broken affordance. The Theme tab was retired
per rf2-ou3pn — the ribbon's sun/moon icon dispatches the same
`[:rf.xray/settings-update :theme nil <kw>]` event the popup radio
used to drive; the popup copy was pure redundancy.)

The Keybindings, Buffer, Popout, and Actions sections (the bottom
four rows of the table above) are the **full §9 catalogue intent**
and remain the target for follow-on beads. v1's Filters tab is a
discoverability pointer into the ribbon pill UI — not a full
management surface — because that surface already lives in the
ribbon and re-implementing it in two places would invite drift.

### "Show tool frames in picker" toggle

- **Section:** View (NOT Filters — it's about what the picker shows, which is a view concern).
- **Sub-section:** Power user (visually separated by `── Power user ──` divider).
- **Label:** "Show tool frames in picker" (literal).
- **Sub-label:** "Reveals `:rf/xray` (and `:rf/re-frame2-pair` etc.) in the ribbon's frame dropdown. Only useful when debugging Xray itself."
- **Default:** OFF. Persists per host-app via localStorage. NOT included in factory-reset's standard reset (framework devs who turned it on will want it stable across resets) — separate "Reset power-user toggles" button.

### configure! API mapping

Every Settings popup field maps to a `(xray-config/configure! {…})` key. See [`015-Configuration.md`](015-Configuration.md) for the full enumeration. New keys this spec adds:

- `:rf.xray/filters` `{:in […] :out […]}` — IN/OUT pill seeds.
- `:rf.xray/filters-auto-hide-error-overrides?` — bool, default `true`.
- `:rf.xray/picker-show-tool-frames?` — bool, default `false`.

---

## §10 Reserved

(The Causality popover that previously occupied this section was dropped entirely per rf2-y0z5b. The `c` key is unbound.)

---

## §11 Keyboard map

Complete map for the spine + chrome:

| Region | Keys |
|---|---|
| **Ribbon nav cluster** | `j` back-one-event · `k` forward-one-event · `G` fast-forward-to-latest (= `⏭`, snap LIVE) |
| **Event list (L2)** | `j` / `k` next/prev (alias of ribbon nav) · `J` / `K` cascade-root skip · `g g` / `G` top/bottom · `Enter` activate · `Space` pause auto-scroll · `[` / `]` (10x parity = `j`/`k`) · `*` pin · `r` rewind · `R` re-dispatch · `o` open source · `/` focus filter add-pill |
| **Tab bar (L3)** | `1`–`7` jump to tab N · `Ctrl+→` / `Ctrl+←` next/prev tab · letter mnemonics: `e` Event · `a` App-db · `v` Views (incl. subs nested under each view) · `t` Trace · `m` Machines · `r` Routing · `i` Issues |
| **Detail panel (L4)** | `Tab` / `Shift+Tab` cycle focusables · `Esc` returns focus to event list |
| **Mode + scrubbing** | `Space` pause/resume LIVE · `L` snap to LIVE (jump to head) · `←` / `→` step one cascade (= `j`/`k`) · `Shift+←` / `Shift+→` step cascade root · `Home` / `End` oldest/newest. (The dedicated Mode pill widget was dropped — the L2 spine itself indicates LIVE / LIVE-paused / RETRO; only the widget is gone.) |
| **Surface toggle** | `Cmd-Shift-M` (macOS) / `Ctrl+Shift+M` (every other host) toggles between **Dynamic** and **Static** surfaces (per Lock #14 in [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) + §Static surface below). Dispatches `:rf.xray/toggle-mode` against the `:rf/xray` frame. Mode pill at ribbon-left mirrors the toggle (chord + pill share the handler). |
| **Global** | `Ctrl+Shift+C` toggle Xray visibility · `Cmd-K` / `Ctrl+K` palette · `?` cheat-sheet · `,` or `s` settings popup (= `⚙`) · `o` popout (= `⛶`) · `Esc` close modal / return to canvas |

### Retired keys (from pre-rewrite spec)

- `f` (Effects) — Effects tab folded into Event; `f` retired.
- `s` (Subscriptions) — Subs panel folded into Views; `s` repurposed to Settings popup.
- `c` (Causality tab) — Causality surface dropped entirely (rf2-y0z5b); `c` is unused.
- `p` (Performance) — Performance panel dropped; `p` unused (available for future tab if added).
- `w` (Flows) — Flows folded into Views; `w` unused.
- ~~`r` (Routes panel) — Routes folded into App-db~~. **Restored** (rf2-nrbs9): Routing got promoted back to its own L3 tab (cohesive sub-domains earn their own lens tab). `r` is now the **Routing tab** mnemonic; the event-list `r` rewind binding stays on the L2 event list scope (the L2 list's key handler wins when focus is in the list; the tab-bar's letter mnemonic wins when focus is elsewhere).
- `S` (Schemas) — schema violations live in Issues; `S` unused.
- `h` (Hydration) — hydration mismatches live in Issues; `h` unused.

`f`, `p`, `w`, `S`, `h` are released to the global namespace for future use.

---

## §12 Data-classification rendering contract

Xray CONSUMES the contract specified in [spec/015-Data-Classification](../../../spec/015-Data-Classification.md). This section defines how each sentinel renders in each Xray surface.

### The three display sentinels (from spec/015)

```clojure
;; Sensitive only — opaque; never revealable; no expand affordance
{:user/ssn :rf/redacted}

;; Large only — drillable; click-to-expand with size warning
{:docs/csv-upload {:rf.size/large-elided {:path   [:docs/csv-upload]
                                          :bytes  4523198
                                          :type   :string
                                          :reason :schema
                                          :hint   "CSV upload — fetch via :handle"
                                          :handle [:rf.elision/at [:docs/csv-upload]]}}}

;; Both — sensitive dominates content visibility; size still informative
{:internal/diff-blob :rf/redacted {:bytes 4523198}}
```

### Per-sentinel rendering

| Sentinel | Xray renders | Drillable? | Hover tooltip discloses | Click affordance |
|---|---|---|---|---|
| `:rf/redacted` (bare) | `[● REDACTED 1]` magenta | NO | Path of redaction · mark source (`add-marks` / `set-marks` / event-handler / sub / fx / cofx / machine / flow) · local count | One-way disclosure of STRUCTURE only (path + source). **NO "reveal value" button.** **NO fetch handle.** The value is GONE at the source. |
| `:rf/redacted {:bytes N}` | `[● REDACTED · N bytes]` magenta | NO | Same as above + size | Same as above; size disclosed (helps debug "is the redacted thing big enough to be the problem?") |
| `:rf.size/large-elided {:path [...] :bytes N :type <kw> :reason :schema :hint "…" :handle [:rf.elision/at <path>]}` | `[● ELIDED · N bytes]` yellow | YES | Path · mark source · byte size · `:hint` text · `:type` (`:map`/`:vector`/`:set`/`:string`/`:scalar`) | Popover with `:hint` text + **"Fetch full value" button**. Fetch routes via `get-path` per [Tool-Pair.md](../../../spec/Tool-Pair.md) (round-trips the marker's `:handle`). Size-warned via confirm modal when bytes > threshold (default 100KB). |

### Per-surface enumeration

| Layer | Surface | Sentinels rendered |
|---|---|---|
| L2 event list row | Trailing redaction marker | `[● REDACTED N]` magenta / `[● ELIDED N]` yellow as static trailing marker on the row (no inline preview slot); marker count = total sentinels in event arg-map |
| L4 Event tab | Event vector + handler `:tags` + fx-args payload | Via `inspect` renderer; sentinels render as colourful inline chips |
| L4 App-db tab | Diff slice tree before/after | Via `inspect-diff`; sentinel position in path preserved |
| L4 Views tab | Per-view sub return values | Via `inspect`; per-sub redaction propagation visible; cluster aggregates per [`012-Views.md`](012-Views.md) |
| L4 Event tab "fx handlers that ran" | Per-fx `:fx-args` payload + return | Via `inspect`; e.g. `:http/post` request body shows `{:password :rf/redacted}` |
| L4 Machines tab | `:data` slot of focused instance + per-transition `:context` | Via `inspect`; per-`reg-machine` `:sensitive` paths drive redaction |
| L4 Trace tab | Raw `:tags` per trace event | Via `inspect-inline` for compact rows; severity colouring applies |
| L4 Issues tab | Exception `:data` payload; sensitive-data warning rows | Via `inspect`; sentinels prevent error-message leakage; sensitive warnings marker-aware so the warning itself doesn't leak the value |
| Settings → Diagnostics panel | Per-session totals | `[● REDACTED N · ● ELIDED M]` aggregate (the Mode pill widget that earlier drafts surfaced this on was dropped; per-row markers + Settings carry the totals now) |

### Combination semantics

When `:rf/redacted` and `:rf.size/large-elided` co-mark the same value (`{:internal/diff-blob :rf/redacted {:bytes N}}`), **sensitive dominates content visibility**:

- Renders as `[● REDACTED · N bytes]` magenta.
- NO expand affordance (sensitive wins; you can't drill).
- Size disclosed (informative; helps debug "is the redacted thing big enough?").

The two sentinels MUST have different affordances or the model collapses:

- `:rf/redacted` = privacy-dropped, gone, magenta, no fetch.
- `:rf.size/large-elided` = size-elided, on-box, yellow, fetch-on-click.

### Size-warned drill (`:rf.size/large-elided`)

Click `[● ELIDED · N bytes]` → popover with `:hint` text + "Fetch full value" button that round-trips the marker's `:handle` through `get-path`. When `N > :large/fetch-warn-threshold-bytes` (default 100KB), the click first surfaces a confirm modal:

```
┌─ Fetch large value ──────────────────────┐
│                                          │
│  This value is 4.3 MB.                   │
│  Fetching it will:                       │
│    • round-trip the host runtime         │
│    • render into the in-page renderer    │
│    • may impact INP                      │
│                                          │
│  [Fetch]    [Cancel]                     │
└──────────────────────────────────────────┘
```

Without the modal, large drill-ins can blow out the renderer and degrade INP. The threshold is configurable via Settings → Buffer.

### What Xray does NOT do

- **No "reveal redacted value" button.** Ever. The only path to seeing sensitive payloads is the host-level opt-in `(xray-config/configure! {:rf.privacy/show-sensitive? true})` which is a deliberate code-level act gating FUTURE events. The walker drops the value before the trace bus buffers it; the value is unrecoverable at render time. Drop-and-forget is the contract.
- **No fetch button for `:rf/redacted`.** Distinguishes from `:rf.size/large-elided`. The two sentinels MUST have different affordances.
- **No schema-based marking** (rejected per [spec/015](../../../spec/015-Data-Classification.md)). Xray renders sentinels from the seven first-class marking sites only.

### The seven first-class marking sites (framework contract; Xray consumes)

Per [spec/015-Data-Classification](../../../spec/015-Data-Classification.md):

1. **Event handler** (`reg-event-db/fx/ctx`) — `{:sensitive [paths]}` on the registration map.
2. **App-db** (per frame) — `(rf/add-marks <frame-id> {path mark, ...})` (additive merge) or `(rf/set-marks <frame-id> {path mark, ...})` (replace wholesale).
3. **Subscription** — output marking via `{:sensitive [paths]}` or whole-output `{:sensitive? true/false}` override. Default = propagate from sensitive input paths.
4. **Effect** (`reg-fx`) — input marking on the fx-args.
5. **Coeffect** (`reg-cofx`) — injection marking.
6. **State machine** (`reg-machine`) — `:data` slot path marking.
7. **Flow** (`reg-flow`) — output marking + propagation override.

Xray's renderer is the same regardless of which site marked the value — it sees the sentinel and renders per the table above. The mark site is disclosed in the hover tooltip so the user can trace "where did this redaction come from?" without revealing the value itself.

---

## §13 Tests / acceptance

Coverage is enumerated in [`017-Test-Coverage-Matrix.md`](017-Test-Coverage-Matrix.md). The rows this spec adds (cross-link to that doc for the full row content):

| Surface | Test gate |
|---|---|
| **4-layer chrome rendering** | `tools/xray/test/.../chrome_layout_test.cljs` — asserts L1/L2/L3/L4 mount; asserts no L0 bottom rail; asserts ribbon cluster order |
| **Spine binding** | `tools/xray/test/.../spine_test.cljs` — asserts `:rf.xray/focus` rebinds atomically when a row is clicked; asserts L2 mode cue (head-row pulse / pinned-row glyph), L3 count badges, L4 detail content all reflect the new focus |
| **Filter IN/OUT pills round-trip** | `tools/xray/test/.../filter_pills_test.cljs` — asserts pill add/edit/delete via popup; asserts AND-across-modes / OR-within-mode semantics; asserts localStorage persistence; asserts Recommended-filters quick-add |
| **Event-driven Dynamic Machines panel (rf2-y9xmf)** | per-feature in `tools/xray/test/.../machines/runtime_test.cljs` — asserts BLANK state on no-activity; per-machine section rendered on transition; topology highlight + guards + actions + cancellation + `:after` rings; prev/next nav walks spine to other events touching the focused machine |
| **UC1 Sim engine + UC2 Mode A/B/C (rf2-r4nao — Static re-host, landed)** | NOT a Dynamic test row. The Sim engine subs/events (`:rf.xray.static.machines/sim-*`) + the `static/machines/sim.cljs` view ship under the Static Machines surface's Sim sub-mode per [`003-Machine-Inspector.md`](003-Machine-Inspector.md); Static-side tests gate those surfaces. UC2 Mode A/B/C remains Dynamic-side (reached via the per-row → Dynamic JUMP). |
| **Data classification rendering** | `tools/xray/test/.../classification_rendering_test.cljs` — asserts `:rf/redacted` opaque (no reveal button); asserts `:rf.size/large-elided` drillable with size-confirm modal; asserts combination semantics (`:rf/redacted` dominates `:rf.size/large-elided`); asserts per-surface rendering across L2/L4 |
| **Frame-isolation invariants** | `tools/xray/test/.../isolation_test.cljs` (NEW; spec §8) — asserts I1 (picker excludes `:rf/xray`) + I3 (Views scoped to selected frame) + I4 (Xray hover doesn't leak into `:rf/default` Views); runs under `npm run test:browser`; **failure blocks merge** |
| **Sub-graph isolation lint** | `tools/xray/test/.../sub_graph_lint_test.cljs` (NEW; spec §8 I2) — asserts dev-time lint predicate throws on misconfigured Xray-namespaced sub feeding host data |
| **Settings modal popup** | `tools/xray/test/.../settings_popup_test.cljs` — asserts modal open/close via `,`/`s`/`⚙`/`Esc`/outside-click; asserts section navigation; asserts every field maps to a configure! key; asserts "Show tool frames in picker" toggle flips picker option list |

The [`017-Test-Coverage-Matrix.md`](017-Test-Coverage-Matrix.md) rows for the dropped panels (AI co-pilot, MCP server, Performance, Subs) are deleted per the spec rewrite.

---

## §13.5 Vision — wider matcher scopes + recorder → Story export

### Wider matcher scopes

v1 ships the IN/OUT pill matcher with **event-id substring/glob/exact**
matching only. **Future:** the same pill machinery extends to:

- **Event-args matchers** — `:order/* {:method :pay/* …}` matches
  events with specific arg payload shape. Useful for filtering
  high-volume events by their payload (e.g. `:input/changed` is noisy
  but `:input/changed {:field :credit-card-number}` is interesting).
- **Path matchers** — pills can match by app-db path touched
  (`path:/cart/items`) — surface the cascades that modified this slice.
- **Source-coord matchers** — pills can match by source file/line
  range, useful for "show me everything dispatched from this module."
- **Origin matchers** — already wired for `:origin` axis; future
  expansion to include custom origins from third-party tools.

The matcher algebra stays AND-across-modes / OR-within-mode (per §7);
the matcher vocabulary grows.

### Recorder → `:play-script` export pipeline (Story integration)

**Bug class:** "I caught this bug in dev; I want a Story variant that
reproduces it so my colleague can see the same thing."

The Xray session has every dispatch (and its outcome) in the trace
buffer. Story has the `:play-script` machinery (per Story's spec) for
declarative variant replay. The pipeline bridges them:

```clojure
;; In Xray, right-click a focused cascade → "Record from here"
;;   → marker dropped at the focused cascade.
;; Continue clicking around the app to capture the repro path.
;; Right-click → "Export to Story" → opens a dialog with the
;;   generated `:play-script`:

{:play-script
 [{:dispatch [:cart/add-item {:id 22}]}
  {:dispatch [:cart/begin-checkout]}
  {:wait-for-machine [:checkout :review]}
  {:dispatch [:pay/decline]}
  {:assert-state [:checkout :failure]}]}
```

The pipeline:

1. **Recorder mode** — Xray marks a cascade as the "start" of a
   recording; subsequent cascades (until the user stops recording) are
   captured as the script.
2. **Sanitisation** — payloads with `:sensitive?` paths render as
   `[:rf/redacted]` (the script captures the cascade structure, not
   the secret).
3. **Export dialog** — paste into a Story variant file, or "Save to
   Story" (when Story is embedded in the same dev build) drops the
   variant straight into the story.
4. **Round-trip** — opening that Story variant runs the play-script;
   Xray, embedded in the Story chrome, lands on the same cascade.

This is the **only "export" Xray offers**, and it lands in Story's
persistent layer — Lock #4 (no session export) is preserved because
the export target is Story (which already has a persistence model),
not Xray.

---

## §14 Cross-references

- [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) — Lock #14 (Two modes — Dynamic + Static) is the direction-setting decision behind §2.5 Static surface above.
- [`000-Vision.md`](000-Vision.md) — 7-tab inventory; philosophy shift to human-only surface.
- [`003-Machine-Inspector.md`](003-Machine-Inspector.md) — event-driven Dynamic Machines panel (rf2-y9xmf) + §Static Machines surface (the shipped Static-mode Machines surface — 4-mode sub-strip with Topology / Sim body (rf2-r4nao — landed) / Instances JUMP / Cascade dimmed-with-tooltip). The UC1 Sim engine and UC2 Mode A/B/C historical prose remain below as Sim re-host reference (rf2-r4nao — landed).
- [`004-App-DB-Diff.md`](004-App-DB-Diff.md) — diff renderer + changed-paths derivation used in L4 App-db tab content.
- [`007-UX-IA.md`](007-UX-IA.md) — typography, colour tokens, density, keyboard map, editor protocol matrix.
- [`012-Views.md`](012-Views.md) — Views tab three-group layout (mounted / re-rendered / unmounted); nested subs; cluster-large-grids.
- [`013-Trace-Consumer.md`](013-Trace-Consumer.md) — trace ring buffer Trace tab filters from.
- [`014-Registry-Catalogue.md`](014-Registry-Catalogue.md) — `:rf.xray/*` registry surface (spine sub, focus events, filter slot, active-tab slot).
- [`015-Configuration.md`](015-Configuration.md) — `configure!` API surface for filters, view, keybindings, buffer, popout, factory-reset.
- [`016-Auxiliary-Panels.md`](016-Auxiliary-Panels.md) — per-tab content contracts (Event detail · Issues ribbon · etc.); Performance section dropped.
- [`017-Test-Coverage-Matrix.md`](017-Test-Coverage-Matrix.md) — test rows for chrome + spine + filters + classification rendering + isolation invariants + settings.
- [spec/015-Data-Classification](../../../spec/015-Data-Classification.md) — framework contract Xray consumes (7 marking sites + 3 display sentinels).
- [spec/009-Instrumentation](../../../spec/009-Instrumentation.md) — trace bus contract; framework emits `rf:event:*` / `rf:sub:*` / `rf:fx:*` / `rf:render:*` / `rf:cascade:*` User-Timing entries (which the dropped Performance panel's role is now served by Chrome DevTools).
