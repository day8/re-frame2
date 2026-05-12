# 007-UX-IA

The user experience, the information architecture, the visual
language. This doc is what an implementer reads to ship pixels that
feel right — typography sizes, colour tokens, animation timings,
keyboard maps, density gradients.

For the *why* behind these picks, see [`Principles.md`](./Principles.md)
and [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## Layout

Causa fills a panel along the **right edge** of the viewport by
default, taking 40% of the window width (resizable). Right-edge gives
more vertical real estate for the graph and the event detail than a
bottom panel.

### The five regions

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                                                                                  │
│  [APP CONTENT — DIMMED 12%, INTERACTIONS PASS THROUGH]                          │
│                                                                                  │
├──────────────────────────────────────────────────────────────────────────────────┤
│ ╭─ CAUSA ───────────────────────────────────────────────────── :app/main ▾ ──╮  │
│ │ ◆──○──○──○──○──○──○──○──○──○──●        ⚠ 0   ⏵◀ epoch 11/11   ⌘K   ?   ✕ │  │  ← top strip (56px)
│ ├──────────────────────────────┬────────────────────────────────────────────┤  │
│ │ ◉ Events                     │ ╭─ event 11 ─────────────────────────────╮ │  │
│ │ ○ App-db                     │ │ :checkout/submit                       │ │  │
│ │ ○ Causality                  │ │ source • src/cart/events.cljs:213      │ │  │
│ │ ○ Subscriptions              │ │ ...                                    │ │  │
│ │ ○ Machines             ●●●   │ │                                        │ │  │
│ │ ○ Issues               ●3    │ ╰────────────────────────────────────────╯ │  │  ← canvas
│ │ ▥ ▥▥ ▥▥▥                    │                                            │  │
│ │  ↑ density toggle            │                                            │  │
│ │                              │                                            │  │
│ │  ↑ sidebar (192px)           │                                            │  │
│ ╰──────────────────────────────┴────────────────────────────────────────────╯  │
│ ◀◀ ─────────────────●──── ▶▶   :app/main   11/11 epochs   8.2KB                 │  ← bottom rail (40px)
╰──────────────────────────────────────────────────────────────────────────────────╯
```

The five regions:

1. **Top strip** (56px) — causality strip + frame picker + global
   actions (Issues badge, epoch counter, command palette, help, close).
2. **Sidebar** (192px) — panel navigation + density toggle.
3. **Canvas** — the active panel's content.
4. **Right rail** (when co-pilot open; 320px) — AI co-pilot panel.
5. **Bottom rail** (40px) — time-travel scrubber + frame info + issues
   badge.

Below 1200px viewport: sidebar collapses to icons (56px); co-pilot
rail goes to 0 (overlays canvas when opened).

Below 900px viewport: Causa takes 100% of viewport width.

Below 600px viewport (phones): **Causa refuses to mount** (per lock
#5). The DOM root creates but the visible UI is a single message
explaining desktop-only.

## The default landing view

On `Ctrl+Shift+C`:

- Slide-in animation: 320ms from the right edge.
- First-paint target: under 80ms (Causa is preloaded; the show/hide
  toggle is a CSS class swap).
- **Active panel: Events**, showing the most-recent epoch's
  event-detail.
- **AI co-pilot: collapsed** (per lock #8). A subtle activation cue
  marks the rail entry — see below.
- **Issues feed: collapsed**, with the count badge in the top strip.
- **Frame picker: shows the active frame** (single-frame apps collapse
  to static label).

## The AI co-pilot collapsed cue

The co-pilot rail is **closed by default** (lock #8 — the marquee-pull
of "open by default" was reversed; the principle of an unobtrusive
debugger won out).

The cue: in the top-strip's right group, a small `◇` glyph in
co-pilot magenta pulses gently every 8 seconds (single 600ms expand
on a fade-cycle). The glyph's tooltip on hover: "Ask Causa
(`Ctrl+Shift+/`)."

The pulse is once-every-8-seconds, not constant — animation
communicates, not decorates (per §Motion below). The pulse stops
entirely after the user has used the co-pilot once (Causa remembers
across the session).

When opened, the rail slides in from the right of the canvas at
250ms ease-out; the rest of Causa's columns reflow.

## Sidebar groups

Three groups, divider-separated:

```
┌──────────────────────────────┐
│ ◉ Events                     │  ← active
│ ○ App-db                     │
│ ○ Causality                  │  ← always-active group
│ ○ Subscriptions              │
│ ○ Effects                    │
│ ○ Trace                      │
│ ─                            │  ← divider
│ ○ Machines           ●●●     │  ← conditional-with-activity
│ ○ Flows                      │
│ ○ Performance                │
│ ○ Issues             ●3      │
│ ○ Routes                     │
│ ─                            │
│ ○ Schemas            ◌       │  ← dormant
│ ○ Hydration          ◌       │
│ ─                            │
│ ○ Co-pilot           ◇       │  ← cue glyph
│ ○ Settings                   │
│ ▥ ▥▥ ▥▥▥                    │  ← density toggle
└──────────────────────────────┘
```

### Activity badges

Per item, right-aligned:

| Badge | Meaning |
|---|---|
| (no badge) | Always-active panel; no signal needed. |
| `●` | Activity now or in last 5 seconds. |
| `●N` | Numeric unread count (3 issues, 5 schema violations). |
| `●●●` | Multiplicity (3 machines currently running). |
| `◌` | Dormant — no activity this session. |
| `◇` | Cue glyph (co-pilot collapsed). |

Badges fade in on first activity (200ms) and **never fade out** — the
dot persists for the session as a "this panel has data" signal.

### The `+5 more` collapse

When most conditional panels have no activity, the sidebar collapses
them into a single `+5 more` row above the dormant divider. Click
expands inline. Solves "the sidebar feels too long" without burying
anything.

### Right-click → Show all panels

Power-users get the full unsorted list. Persisted per-machine.

## The density slider

Three settings: **compact** / **cosy** / **comfy**. Default **cosy**.

| Setting | Sidebar | Pill | Tick gap | Body type |
|---|---|---|---|---|
| **Compact** | 160px | 20px | 4px | -1px |
| **Cosy** (default) | 192px | 24px | 4px | (baseline) |
| **Comfy** | 224px | 28px | 6px | +1px |

What does *not* change between densities: icon weights, border radii,
animation durations, accent colours. Density is a vertical-rhythm
knob, not a redesign.

## Typography

Two typefaces only:

- **UI sans:** `Inter` (variable, wght 400–700), fallback
  `system-ui` / `-apple-system` / `Segoe UI`. ~80KB WOFF2. UI chrome,
  labels, headings.
- **Data mono:** `JetBrains Mono` (variable, wght 400–700), fallback
  `ui-monospace` / `SF Mono` / `Menlo`. ~100KB WOFF2. App-db trees,
  event vectors, source coords, code blocks, EDN.

### Sizes (cosy density)

| Token | Size / line-height / weight | Used for |
|---|---|---|
| Display | 16 / 1.4 / 600 | Panel titles |
| Body | 14 / 1.5 / 400 | Default UI text |
| Mono body | 13 / 1.45 / 400 | Code, EDN (mono is 1px down to visually match sans) |
| Caption | 12 / 1.4 / 400 | Hints, secondary labels |
| Micro | 11 / 1.2 / 600 | Badges, tabs |

Below 10px: refused.

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
           yellow  #FBBF24  warnings, schema-replaced-with-default
           orange  #FB923C  long-task
           red     #F87171  errors, schema-violations, hydration-mismatches
           magenta #E879F9  AI co-pilot highlight

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

The colour-blind path is reachable without ever relying on hue.

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
Sizes 14 / 16 / 20px (inline / sidebar / modal-header). 100ms hover
fade to context accent; no size change on hover.

Causa-specific custom glyphs: `◆` cascade root · `●` filled node ·
`○` hollow node · `◉` selected node · `↺` rewind · `◇` co-pilot cue.

## Motion + animation

Animation communicates, not decorates. Three durations:

| Tier | Range | Used for |
|---|---|---|
| **Quick** | 100ms | Hover, focus rings |
| **Standard** | 200–250ms | Panel switches, scrubber drag-snap, sidebar collapse |
| **Slow** | 400–600ms | Diff flashes, error pulses, the 320ms Causa slide-in |

Easings: default `cubic-bezier(0.4, 0, 0.2, 1)`; entering
`cubic-bezier(0, 0, 0.2, 1)`; exiting `cubic-bezier(0.4, 0, 1, 1)`.

Specific motions:

- Panel switch: 180ms cross-fade.
- Detail diff flash: 400ms yellow → transparent on each touched slice.
- Error pulse: single 600ms expand-fade red ring (no looping).
- Machine-active state: 1.2s gentle scale 1.0 → 1.05 → 1.0 (only
  continuous animation in chrome, only on the machine chart).
- Co-pilot streaming: typewriter ~25 chars/sec.
- Co-pilot cue glyph: single 600ms expand-fade every 8 seconds until
  first use, then stops.

### `prefers-reduced-motion`

All durations clamp to 0 except a 1-frame opacity tween where layout
needs to settle. The error pulse becomes a static red ring for 1.5s;
the machine pulse stops entirely; the co-pilot cue glyph stops
pulsing (the glyph stays visible, statically).

## Keyboard

Every panel is keyboard-reachable. The chrome has a strict tab order:
top-strip → sidebar → canvas (focus enters the active panel) → bottom
rail → co-pilot (when open). `Esc` always returns focus to the canvas.

### Global shortcuts

| Key | Action |
|---|---|
| `Ctrl+Shift+C` | Toggle Causa |
| `Ctrl+Shift+P` | Pop out to separate window |
| `Ctrl+K` | Command palette |
| `?` | Keyboard cheat-sheet |
| `,` | Settings |
| `Esc` | Close modal / collapse popover / focus canvas |
| `Ctrl+Shift+/` | Toggle co-pilot rail |
| `Ctrl+F` | Find within active panel |

### Navigation

| Key | Action |
|---|---|
| `j` / `k` | Next / previous in any list |
| `g g` / `G` | Top / bottom |
| `[` / `]` | Previous / next epoch (passive — does not rewind) |
| `Shift+[` / `Shift+]` | Previous / next cascade root |
| `Tab` / `Shift+Tab` | Move between regions |

### Panel jumps (mnemonics)

| Key | Panel |
|---|---|
| `e` | Events |
| `a` | App-db |
| `c` | Causality graph |
| `s` | Subscriptions |
| `f` | Effects (fx) |
| `t` | Trace |
| `m` | Machines |
| `w` | floWs |
| `p` | Performance |
| `i` | Issues |
| `r` | Routes |
| `S` | Schemas |
| `h` | Hydration |
| `/` | Co-pilot (input focused) |
| `,` | Settings |

### Event actions (when an event is focused)

| Key | Action |
|---|---|
| `Enter` | Open detail |
| `o` | Open source in editor |
| `R` | Re-dispatch this event |
| `r` | Rewind to before this event (calls `restore-epoch`) |
| `Shift+r` | Hard rewind with failure modes surfaced |
| `f` | Filter the causality graph to this dispatch's cascade |
| `Ctrl+C` | Copy event vector |
| `Ctrl+Shift+C` | Copy source coord |

### Scrubber

| Key | Action |
|---|---|
| `Space` | Toggle play (passive auto-step at 500ms intervals) |
| `Shift+Space` | Slow play (1000ms intervals) |
| `0` | Jump view to oldest |
| `$` | Jump view to newest |
| `*` | Pin a snapshot at current epoch (session-scoped; see [`002-Time-Travel.md`](./002-Time-Travel.md) §Pinned snapshots) |

### Co-pilot

| Key | Action |
|---|---|
| `/` (from chrome) | Focus co-pilot input |
| `Enter` (input focused) | Submit |
| `Shift+Enter` | New line |
| `Ctrl+L` | Clear conversation |
| `Ctrl+P` | Previous question |
| `Ctrl+N` | Next question |

## Command palette (`Ctrl+K`)

The spine of expert workflows. Centred 560px modal, 50% height.

### Indexed sources

Matched together, recency-weighted, command-boosted:

- Recent events (200-entry buffer; matches event-id + source coord)
- Registered handlers (id + `:doc`)
- Frames
- Machines with current state
- Flows
- Subs
- Panel names
- Command verbs (Rewind / Re-dispatch / Snapshot / Filter / Pin /
  Pop-out / Switch frame / …)
- Settings entries
- Recent co-pilot questions

Fuzzy match splits on camelCase / kebab-case / namespace boundaries.

### Empty palette is context-aware

With no input, the top of the list shows:

- Actions for the active panel
- "Open source" / "Rewind here" when an event is selected
- "Investigate `:rf.error/handler-exception`" when a fresh error
  landed

### Row shape

40px tall (compact 32, comfy 48): 16px type icon, label,
right-aligned hint (`epoch 11`, `events.cljs:213`, shortcut). Arrows
to navigate; Enter invokes; `Ctrl+Enter` invokes in a pop-out.

## Modal layers

Three modal surfaces float over the chrome:

1. **Command palette** (`Ctrl+K`) — 560px centred.
2. **Keyboard cheat-sheet** (`?`) — 480px modal listing every
   shortcut.
3. **Settings** (`,`) — 640×480px modal: Theme · Density ·
   Keybindings · AI provider (includes co-pilot redaction toggles per
   [`009-AI-CoPilot.md`](./009-AI-CoPilot.md) §Redaction defaults) ·
   Buffer depths · Frame defaults · Telemetry (always off; statement
   of why we don't ship any).

## Discoverability

Three layers, no onboarding tour:

1. **The `?` cheat-sheet.** Modal showing every shortcut.
   Discoverable from the top strip's `?` icon. On first open, Causa
   flashes a 800ms violet ring around the `?` icon for one cycle
   (then never again).

2. **Empty-state hints.** Each empty state shows a contextual
   keyboard hint ("No events yet. Press `Ctrl+Shift+C` again to
   close..."). On first event selection, a 4-second auto-dismiss
   popover suggests "Press `c` for the causality graph."

3. **The command palette itself.** Typing `?` in the palette filters
   to commands and shows their shortcuts. The palette is the
   documentation.

We do **not** ship a tour. Causa is a tool, not an experience.

## Bundle splitting

Per-panel lazy loading via shadow-cljs's per-output-target slicing:

- Core (UI shell + Events + App-db + Causality strip): <1.5 MB
  minified / <500 KB gzipped.
- AI co-pilot panel: <400 KB extra, lazy-loaded on first open.
- Machine inspector: <300 KB extra (includes `tools/machines-viz/`),
  lazy-loaded on first open.
- Hydration debugger: <100 KB extra, lazy-loaded on first
  `:rf.ssr/hydration-mismatch`.

The sidebar loads metadata only at boot; opening a panel kicks the
relevant module.

## Performance budget

The hard rule: **opening Causa must not change observable INP** on a
typical app.

- Trace bus emission overhead: <2µs per emit (per Spec 009).
- Causality graph live updates: debounced to 16ms (one rAF). A burst
  of 1000 events updates the graph once per frame.
- App-db diff: O(changed paths) via PersistentHashMap pointer-eq.
- Rendering: every panel virtualises long lists; nothing renders >200
  rows at once; the causality graph caps at the last 200 dispatches.

If a feature pushes INP, the feature is cut.

## Production posture

The launch pill doesn't render in production builds (per Spec 009 §
Production builds — `goog.DEBUG=false` elides the entire surface).
`Ctrl+Shift+C` does nothing. CI verifies via `npm run test:elision`.

In a non-elided dev build running in production-like conditions,
Causa shows a yellow top banner: "Causa is enabled in this build.
Disable for production." Single-click dismiss, remembered for the
session.
