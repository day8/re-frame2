# 007-UX-IA

The user experience, the information architecture, the visual
language. This doc is what an implementer reads to ship pixels that
feel right — typography sizes, colour tokens, animation timings,
keyboard maps, density gradients.

For the *why* behind these picks, see [`Principles.md`](./Principles.md)
and [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## Layout

Causa fills a true-inline panel on the **right side** of the host app by
default. The host app provides `[data-rf-causa-host]` as a normal
flex/grid column and Causa renders inside it. This is not an overlay
and not a body-padding dock: the app remains visible and clickable to
the left because normal layout owns the relationship.

### The five regions

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                                                                                  │
│  [APP CONTENT — DIMMED 12%, INTERACTIONS PASS THROUGH]                          │
│                                                                                  │
├──────────────────────────────────────────────────────────────────────────────────┤
│ ╭─ CAUSA ───────────────────────────────────────────────────── :app/main ▾ ──╮  │
│ │ ◆──○──○──○──○──○──○──○──○──○──●        ⚠ 0   ⏵◀ epoch 11/11    ?   ✕ │  │  ← top strip (56px)
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
4. **Bottom rail** (40px) — time-travel scrubber + frame info + issues
   badge.

Below 1200px viewport: sidebar collapses to icons (56px).

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
| `--rf-causa-inline-width` | `560px` | `flex-basis` of the inline host. Default bumped 420 → 560 under rf2-9ovfb (Pitch8 field feedback: event vectors with map payloads wrap awkwardly at 420; 560 reads much better for the Event Detail panel). |
| `--rf-causa-accent` | `#7C5CFF` | Causa's brand violet (matches `:accent-violet` below). Published on `:root` in the recommended host snippet so host stylesheets can colour their own dev chrome (resize handles, dock separators, story chips) to harmonise with Causa without forking the hex (rf2-9ovfb). |

Override either property anywhere up the cascade; the closest
declaration wins as usual. The published spelling is also exported
as `day8.re-frame2-causa.config/default-layout-host-css-var` /
`default-layout-host-width` / `default-accent-css-var` /
`default-accent` so tooling and docs generators can refer to them
without forking the string. Full contract + drag mechanics in
[`011-Launch-Modes.md`](./011-Launch-Modes.md) §Layout host contract
and §Brand-accent CSS variable.

## The default landing view

On page load after `rf/init!`, when `[data-rf-causa-host]` exists:

- Causa auto-opens in the right inline host.
- `Ctrl+Shift+C` hides/shows the already-mounted shell with a CSS-only
  display toggle.
- **Active panel: Events**, showing the most-recent epoch's
  event-detail.
- **Issues feed: collapsed**, with the count badge in the top strip.
- **Frame picker: shows the active frame** (single-frame apps collapse
  to static label).

## Redaction indicator

When Causa's trace collector drops a `:sensitive? true` event under
the default privacy posture (per
[`013-Trace-Bus.md`](./013-Trace-Bus.md) §Privacy gate and
[`015-Configuration.md`](./015-Configuration.md) §`:trace/show-sensitive?`),
the user must SEE that a drop happened. Sensitive cascades that
vanish silently are a footgun: the user reads "no events" and
debugs the wrong layer. The redaction indicator is the visible
admission that the privacy gate is hiding data.

The mechanism layers across two surfaces — a **chrome-level total**
on the bottom rail, and **inline per-value markers** in every panel
that surfaces wire-elided values. Both share the visual grammar
defined here.

### Visual format

`[● REDACTED N]` — square brackets, U+25CF BLACK CIRCLE bullet, single
ASCII space, the literal word `REDACTED` in upper case, single ASCII
space, the integer count `N`. Rendered in magenta (`#E879F9` per
§Colour system — the "hidden surface" hue across the chrome), weight
600, in the Micro type token (11px) on the bottom rail and Caption
token (12px) inline within panels.

The count `N` MUST be rendered explicitly even when `N = 1`. The form
`[● REDACTED 1]` is the canonical singular shape; a bare
`[● REDACTED]` is REFUSED. Rationale: a constant grammar is easier
for the eye to scan across a panel that mixes singular and plural
redactions, and reading "1" tells the user the count is live (not a
typo for a label).

### Bottom-rail chrome indicator

The bottom rail (region 5 of §The five regions) renders the
indicator in its centre group whenever the total
suppressed-sensitive count across every frame bucket is positive.
The total is read from the
`:rf.causa/suppressed-sensitive-count` subscription per
[`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md) §Shared
infrastructure, which sums every value of the
`[:suppressed-counters]` slot per
[`015-Configuration.md`](./015-Configuration.md) §App-db slots.

The indicator MUST disappear (not just dim) when the total returns
to zero — clearing the trace buffer via
`trace-bus/clear-buffer!` resets every bucket per
[`013-Trace-Bus.md`](./013-Trace-Bus.md) §Lifecycle operations, and
the chrome MUST follow.

### Inline panel markers

Any panel that surfaces wire payloads can encounter a redacted slot:
the privacy walker drops `:sensitive? true` values BEFORE the
trace bus ever pushes them (per
[Spec 009 §Privacy](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)),
and the framework's tool-pair surfaces (per
[Tool-Pair.md](../../../spec/Tool-Pair.md)) drop or redact at the
wire boundary. The panel renders a payload that ALREADY has the
sensitive descendants removed. Each removal slot MUST surface a
`[● REDACTED N]` marker so the user can SEE the gap.

Panels that MUST render inline markers when they surface
wire-elided values:

- **App-db** (`004-App-DB-Diff.md`) — both the slice tree and the
  diff before/after columns.
- **Epoch detail / Events** — the dispatch vector, the
  `:rf/epoch-record` `:db-before` / `:db-after` slots, the
  per-handler trace stream surfaced in the event detail.
- **Subscriptions** (`012-Subscriptions.md`) — the sub-cache
  output column.
- **Effects** — the `:fx-args` payload column and every `reg-fx`
  return.
- **Trace** (`013-Trace-Bus.md` §Filter vocabulary) — any
  buffer-row value column.
- **Machines** (`003-Machine-Inspector.md`) — the current-state
  payload and transition-history side panels.

Inline markers replace the value at the redacted path. The marker's
`N` counts the redactions **at the marker's scope** — a single
dropped value renders `[● REDACTED 1]` in that slot; a redacted
subtree carrying three sensitive descendants renders
`[● REDACTED 3]` at the subtree root and no descendant markers (the
parent count subsumes the children). The scope-local count contrasts
deliberately with the bottom-rail's total: the inline count answers
"how much is hidden HERE", the rail answers "how much is hidden
across the session".

### Hover and click affordance

Both surfaces (chrome + inline) MUST expose the same metadata on
hover (via `title` tooltip) and on click (via a popover).

The hover tooltip MUST disclose:

1. The Spec 009 privacy default (which gate dropped the value).
2. The opt-in path: `(causa-config/configure! {:trace/show-sensitive? true})`.
3. The local count `N`.

The click popover MUST additionally disclose, where applicable:

- The **structural key path** the redaction occupied (the path the
  privacy walker dropped at; for chrome-level redactions where no
  single path applies, the popover lists the per-frame buckets
  from the `[:suppressed-counters]` slot).
- The **reason source** — the `:sensitive?` flag on the relevant
  trace event (per
  [Spec 009 §The `:sensitive?` registration metadata key](../../../spec/009-Instrumentation.md#the-sensitive-registration-metadata-key)).

The click affordance MUST be **one-way disclosure of structure**, not
fetch. The redacted value itself is **gone** — the privacy walker
drops it before the wire boundary, and the trace bus never buffers
it. The popover MUST NOT offer a "fetch redacted value" button, a
"reveal once" link, or any other affordance that suggests the value
is recoverable. The only path to seeing sensitive payloads is the
host-level opt-in (`configure!`), which is a deliberate code-level
act gating future events — drop-and-forget is the contract.

### Contrast with `[● ELIDED N]` for `:large?`

The `:large?` size-elision mechanism per
[Spec 009 §Wire marker — `:rf.size/large-elided`](../../../spec/009-Instrumentation.md#wire-marker--rfsizelarge-elided)
shares the visual grammar but DIFFERS in affordance and copy:

| Axis | Redacted (`:sensitive?`) | Elided (`:large?`) |
|---|---|---|
| Marker | `[● REDACTED N]` | `[● ELIDED N]` |
| Hue | Magenta `#E879F9` | Yellow `#FBBF24` (warning hue per §Colour system) |
| Source | Privacy walker (drop) | Size walker (substitute with marker carrying `:digest` + `:bytes` + fetch handle) |
| Click | Discloses structure; **no fetch** | Discloses structure; **offers fetch** (the marker's handle round-trips via `get-path` per [Tool-Pair.md](../../../spec/Tool-Pair.md)) |
| Recoverable? | No — value is gone at the source | Yes — value is on-box, addressable by handle |
| Bottom rail | Per-session total (rolling) | Not summarised in chrome (per-marker count only) |

The two markers MUST NOT share the same hue: magenta for
"privacy-dropped, gone" and yellow for "size-elided, fetch-on-click"
keeps the eye distinct and the user's mental model intact (privacy
is a one-way door; size is a lazy door).

### `prefers-reduced-motion`

The indicator does not animate on first appearance — it renders in
place at next paint. The hover tooltip and click popover share the
panel-switch fade (180ms) which clamps to 0 under
`prefers-reduced-motion` per §Motion + animation.

### Cross-references

- Counter contract:
  [`013-Trace-Bus.md`](./013-Trace-Bus.md) §Suppressed-sensitive
  counter.
- Configuration flag:
  [`015-Configuration.md`](./015-Configuration.md)
  §`:trace/show-sensitive?` and §App-db slots.
- Subscription + event registry:
  [`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md)
  §Shared infrastructure
  (`:rf.causa/suppressed-sensitive-count`,
  `:rf.causa/note-sensitive-suppressed`,
  `:rf.causa/reset-suppressed-counters`).
- Privacy spec:
  [Spec 009 §Privacy + sensitive data in traces](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces).
- Size-elision marker:
  [Spec 009 §Wire marker — `:rf.size/large-elided`](../../../spec/009-Instrumentation.md#wire-marker--rfsizelarge-elided).
- rf2-azls9 — the bottom-rail implementation bead.
- rf2-0vxdn — reactive sub-graph plumbing (immediate counter
  updates).

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
           magenta #E879F9  redaction highlight ("hidden surface" hue)

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
`○` hollow node · `◉` selected node · `↺` rewind.

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
### `prefers-reduced-motion`

All durations clamp to 0 except a 1-frame opacity tween where layout
needs to settle. The error pulse becomes a static red ring for 1.5s;
the machine pulse stops entirely.

## Keyboard

Every panel is keyboard-reachable. The chrome has a strict tab order:
top-strip → sidebar → canvas (focus enters the active panel) → bottom
rail. `Esc` always returns focus to the canvas.

### Global shortcuts

| Key | Action |
|---|---|
| `Ctrl+Shift+C` | Toggle Causa |
| `?` | Keyboard cheat-sheet |
| `,` | Settings |
| `Esc` | Close modal / collapse popover / focus canvas |
| `Ctrl+F` | Find within active panel |

Pre-alpha: only `Ctrl+Shift+C` is wired in `keybinding.cljs`. Pop-out
lives at `(causa/popout!)`; the command palette is reachable through
the top-strip control once that surface lands.

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

## Editor protocol matrix

The `o` shortcut (and every `open` chip Causa renders next to a
source-coord — event detail, causality node, machine inspector, the
hydration debugger's render-tree rows) sets `window.location.href` to
a URI-scheme handler the OS dispatches to the user's editor. Lock 11
(handler-coord fallback) decides *which* coord the chip carries; this
section is the normative list of URI schemes Causa knows how to
build from that coord.

### Supported editors

| Editor | Config key | URI template |
|---|---|---|
| VS Code (and forks that share its scheme: code-server, VSCodium) | `:vscode` (default) | `vscode://file/<path>:<line>:<column>` |
| Cursor (distinct scheme — VS Code fork) | `:cursor` | `cursor://file/<path>:<line>:<column>` |
| Windsurf (distinct scheme — VS Code fork, registers its own handler) | `:windsurf` | `windsurf://file/<path>:<line>:<column>` |
| Zed | `:zed` | `zed://file/<path>:<line>:<column>` |
| JetBrains family (IDEA, WebStorm, Cursive, PyCharm) | `:idea` | `idea://open?file=<path>&line=<line>&column=<column>` |
| Anything else (Sublime, Emacs server-mode, Vim with a URL handler, Helix) | `{:custom <template>}` | user template with `{path}` / `{file}` / `{line}` / `{column}` placeholders |

The `:windsurf` and `:zed` rows mirror the VS Code colon-suffix shape — Windsurf as a VS Code fork inherits the file/line/column grammar; Zed's `zed://` handler (registered via the editor's "Register Zed Scheme" action) accepts the same URI shape per its open-listener pipeline. Editors that ship without a stable URI handler still route through `{:custom <template>}` until they standardise.

### URI construction (normative)

The chip's URI is built from the source-coord's `:file` / `:line` /
`:column` slots against the configured editor keyword. The rules below
are the contract every Causa surface that renders a click-to-source
chip MUST satisfy — equivalently, the contract the shared builder
`re-frame.source-coords.editor-uri/editor-uri` enforces on every
consumer's behalf.

- **Default editor.** When `:rf.causa/editor` is unset or `nil`, the
  builder MUST treat the editor as `:vscode`. Hosts that boot Causa
  without calling `(causa-config/configure! {:editor …})` get a
  clickable VS Code URI on first render — no opt-in required.

- **`:file` is mandatory.** When the source-coord's `:file` slot is
  absent, blank, or non-string, the builder MUST return `nil` and the
  consumer MUST hide the chip entirely. A chip that points nowhere is
  worse than no chip — the user can't tell the difference between
  "missing source data" and "your editor is broken" otherwise.

- **`:line` and `:column` defaults.** When `:line` is absent the
  builder MUST default to `1`; when `:column` is absent it MUST
  default to `1`. Editors at line 1 / column 1 land on the file's
  first byte — strictly worse than the captured location, strictly
  better than failing to navigate at all.

- **Unknown keyword posture.** Any editor keyword not in
  `#{:vscode :cursor :windsurf :zed :idea}` (and not a `{:custom …}`
  map) MUST fall through to the `:vscode` URI shape. The user typed
  `:vsocde` once — they shouldn't lose click-to-source forever for it.

- **Custom-template substitution.** The `{:custom "<template>"}` form
  MUST substitute `{path}`, `{file}` (alias for `{path}`), `{line}`,
  and `{column}` placeholders verbatim from the source-coord. Missing
  placeholders MUST pass through unchanged so a template that omits
  `{column}` simply doesn't include the column. The `:custom` value
  MUST be a string; any other shape (a keyword, a number) reverts to
  the default editor.

- **No URL-encoding of the path.** The path MUST be passed verbatim
  into the URI — slashes stay slashes, colons stay colons. Every
  editor handler tested in 2026 (`vscode://`, `cursor://`,
  `windsurf://`, `zed://`, `idea://`) fails to resolve a path with
  `/` encoded as `%2F`. Path components containing spaces are a known
  edge case; they remain unencoded and rely on the OS URI parser's
  whitespace handling. (Hosts with spaces in workspace roots SHOULD
  use the `{:custom …}` form with their own encoding.)

- **No handler-installed fallback.** When the URI's scheme has no
  registered OS handler (the user has `:cursor` configured but
  hasn't installed Cursor), the click MUST be a clean no-op — the
  browser's URI dispatcher returns without navigation, the page does
  not change. Causa MUST NOT detect installation status (the
  browser deliberately conceals it), MUST NOT fall back to a
  different editor scheme, and MUST NOT show an error toast. The
  one signal the chip emits is the URI it built; the OS handler
  chain is the only thing that knows whether an editor is reachable.

- **Click vector.** The chip MUST invoke navigation by setting
  `window.location.href` (or rendering an `<a href>` and letting the
  browser follow it). Causa MUST NOT use `window.open`, fetch the
  URI, or spawn a worker — the editor URI is a one-shot OS event,
  not a page load.

These rules apply identically to every Causa surface that surfaces a
source-coord chip — the event-detail hero, causality nodes, machine
inspector chips, hydration debugger rows, trace panel rows. The
single canonical implementation in
`re-frame.source-coords.editor-uri` MUST be the only URI builder; no
panel may inline its own URI assembly.

### Configuration

- The user picks the editor via the **Settings** modal (`,`) → Editor.
  Stored under the `:rf.causa/editor` config key.
- The boot-time entry is `(causa-config/configure! {:editor …})` per
  [`015-Configuration.md`](./015-Configuration.md) §`:editor`.
- Default: `:vscode` — the most-installed editor in 2026.
- The preference is **session-scoped**, persisted via the same Causa
  config substrate as theme / density. No cloud-sync (Lock 4 privacy
  posture: Causa state stays on the user's machine).
- Causa's preference is **independent** of Story's `:rf.story/editor`
  — a host running both tools can route each to a different editor.

### Cross-references

- Lock 11 (handler-coord fallback, in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md))
  decides which coord the chip carries; this matrix is unchanged by
  Lock 11 — the URI builder runs on whatever coord arrives.
- The shared URI builder lives at
  `implementation/core/src/re_frame/source_coords/editor_uri.cljc`
  (CLJC, JVM + CLJS portable; introduced via rf2-evgf5).
- Causa's mirror chip
  (`day8.re-frame2-causa.open-in-editor/open-chip`) consumes the
  same helper — see [`API.md` §Open in editor](./API.md#open-in-editor-rf2-evgf5).
- Story's matching surface — see
  [`tools/story/spec/005-SOTA-Features.md` §"Open in editor" per variant](../../story/spec/005-SOTA-Features.md).
- rf2-evgf5 — the chip implementation bead (Story + Causa).

## Command palette

The spine of expert workflows. Centred 560px modal, 50% height.
Pre-alpha: no keybinding is wired — opening goes through the
top-strip control (the `⌘K`-shaped slot in the chrome diagram is the
intended affordance once the palette panel itself lands).

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

1. **Command palette** — 560px centred (no keybinding wired
   pre-alpha; opens from the top-strip control).
2. **Keyboard cheat-sheet** (`?`) — 480px modal listing every
   shortcut.
3. **Settings** (`,`) — 640×480px modal: Theme · Density ·
   Keybindings · Editor (per §Editor protocol matrix above) ·
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
   close…"). On first event selection, a 4-second auto-dismiss popover
   suggests "Press `c` for the causality graph."

3. **The command palette itself.** Typing `?` in the palette filters
   to commands and shows their shortcuts. The palette is the
   documentation.

We do **not** ship a tour. Causa is a tool, not an experience.

## Empty-state hints

Each panel's empty state — when no data has landed yet, or when the
relevant runtime feature isn't wired up — surfaces a canonical
keyboard hint. The empty-state copy lives in each panel's own spec —
see the "Empty state" section in
[`001-Causality-Graph.md`](./001-Causality-Graph.md),
[`003-Machine-Inspector.md`](./003-Machine-Inspector.md),
[`004-App-DB-Diff.md`](./004-App-DB-Diff.md),
[`005-Schema-Timeline.md`](./005-Schema-Timeline.md), and
[`006-Hydration-Debugger.md`](./006-Hydration-Debugger.md).

### `prefers-reduced-motion`

No animation on these hints; they render in place at panel mount.

## Bundle splitting

Per-panel lazy loading via shadow-cljs's per-output-target slicing:

- Core (UI shell + Events + App-db + Causality strip): <1.5 MB
  minified / <500 KB gzipped.
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
