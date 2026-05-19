# Story — Design Tokens (chrome identity)

> The token contracts that constitute Story's chrome identity: **typography**
> (rf2-2rwdc), **colour** (rf2-i3i5j), **motion** (rf2-3lt89), **depth +
> backdrop** (rf2-ypd6h), **iconography** (rf2-p0wur), and the **toolbar
> 5-cluster** structural contract (rf2-v58dm). These are the
> identity-bearing surfaces — the public design system third-party
> Story-panel authors honour and the chrome `re-frame.story.theme.*`
> namespaces ship.
>
> **Pre-alpha status:** shipped. The implementation source lives at
> [`tools/story/src/re_frame/story/theme/`](../src/re_frame/story/theme/);
> the file's docstrings carry the per-token rationale this spec
> consolidates. The contracts named here are normative.

## Why a token contract

Story is a **workshop / playground** UI — the surface developers hover
over their work-in-progress variants in a controlled chrome. The
chrome's job is to recede in service of the rendered variant **and**
to assert a distinct identity that distinguishes Story from:

1. **Causa** (re-frame2's diagnostic surface) — when both surfaces
   land in the RHS together, the user must read "workshop" vs
   "diagnostic" without needing labels. Two surfaces, two roles.
2. **Commodity component-explorer chrome** — Storybook 8 ships flat
   solid backgrounds, no per-row glyph rhythm, no motion choreography,
   and renders against Inter / system fonts. The
   [feature-set audit](./findings/re-frame-2-story-feature-set.md)
   classified these as the rubric's "predictable layout that lacks
   context-specific character" anti-pattern.

The locks below name the identity-bearing decisions so future PRs
cannot drift back to commodity defaults. Per
[`Principles.md`](Principles.md) §EDN-first — tokens are pure data;
call sites consume tokens, never raw hex / font-family / duration
strings.

## Public API — `re-frame.story.theme.*`

The token namespaces are the public surface third-party Story-panel
authors consume. Hex / font-family / duration / shadow literals at
call sites are banned (rf2-i3i5j AC#3 + rf2-2rwdc AC#5 + the
follow-on motion sweep). Inline `:style` maps reference tokens by
key:

```clojure
(:require [re-frame.story.theme.colors      :refer [tokens]]
          [re-frame.story.theme.typography  :as typography :refer [sans-stack mono-stack]]
          [re-frame.story.theme.motion      :as motion]
          [re-frame.story.theme.depth       :as depth]
          [re-frame.story.theme.glyphs      :as glyphs])

{:background  (:bg-2 tokens)
 :color       (:text-primary tokens)
 :font-family sans-stack
 :font-size   (:body-tight typography/type-scale)
 :transition  (:row motion/transitions)
 :box-shadow  (:elev-2 depth/shadows)}
```

## §Typography (rf2-2rwdc)

> Shipped: [`theme/typography.cljc`](../src/re_frame/story/theme/typography.cljc).
> Phase 1 — rf2-s1r9a §F4.

### Lock

The canonical sans + mono pair is **IBM Plex Sans + IBM Plex Mono**.
Story does NOT use:

- `Inter` (Causa's pick, the AI-slop default),
- `system-ui` / `Roboto` / `Arial` (the cookie-cutter floor),
- `Space Grotesk` (the rubric's named convergence point).

Rationale: Plex carries IBM's editorial bias — geometric without being
sterile, with characterful italics, a confident `g`, and a mono sibling
tuned to the same proportions. The sans + mono pair share design DNA
so chrome that mixes them (e.g. a variant id rendered next to its
status text) holds together typographically. The pair distinguishes
Story from Causa's Inter + JetBrains Mono at a glance.

### Stacks (public)

| Token             | Use                                                 | First family                 |
|-------------------|-----------------------------------------------------|------------------------------|
| `sans-stack`      | chrome / labels / prose                             | `"IBM Plex Sans"`            |
| `mono-stack`      | code / EDN / variant ids                            | `"IBM Plex Mono"`            |
| `display-stack`   | hero / overlay titles (with `letter-spacing -0.01em`) | `"IBM Plex Sans"`         |

Each stack ends with a thoughtful fallback chain so a missing webfont
degrades gracefully — Plex first, soft humanist (Optima / Avenir Next)
as the visual sibling for systems missing the webfont, then
`ui-sans-serif` / `ui-monospace` for the OS-default picker.

### Webfont delivery

The chrome injects `@font-face` rules via
`(theme.typography/inject-font-faces!)` at shell mount. Per
rf2-2rwdc + the rf2-s1r9a Phase 1 browser-gate trace, the
auto-injected declarations are **`local()`-only** — no HTTP fetch is
ever attempted. An OS-installed Plex picks up automatically (Mike's
machines, IBM Carbon design system users); otherwise the fallback
chain in `sans-stack` / `mono-stack` takes over.

Consuming projects that want self-hosted or CDN webfonts inject their
own `@font-face` declarations with `url()` entries pointing at their
vendored woff2s. CSS allows a later `@font-face` with the same family
+ weight to layer additional `src:` candidates over the chrome's
`local()`-only baseline.

The injector self-elides in production via
`re-frame.story.config/enabled?` — published static builds never
touch the DOM. Idempotent — subsequent calls short-circuit on the
internal `font-faces-injected?` sentinel.

### Weights, type scale, letter-spacing

| Map                 | Slots                                                                                              |
|---------------------|----------------------------------------------------------------------------------------------------|
| `weights`           | `:regular 400` `:medium 500` `:semibold 600` `:bold 700`                                           |
| `type-scale`        | `:hero 24px` `:display 16px` `:body 13px` `:body-tight 12px` `:mono-body 12px` `:caption 11px` `:micro 10px` `:nano 9px` + `:line-height-tight 1.4` `:line-height-body 1.5` `:line-height-mono 1.4` |
| `letter-spacing`    | `:label 0.5px` `:label-wide 0.6px` `:display -0.01em` `:body normal`                               |

The type scale runs **tight (10–18 px)** because Story's chrome packs
sidebar / toolbar / controls / inspector / canvas-title surfaces into
one viewport — info density wins for the workshop UI. The shape
(`:display` / `:body` / `:body-tight` / `:mono-body` / `:caption` /
`:micro`) mirrors Causa's `type-scale` so the two surfaces compose
cleanly; the values differ (Story's `:display` runs 16, Causa's 14;
Story adds `:hero 24px` for the welcome overlay).

### Zero-raw contract

The contract: **no raw `font-family` / `:font-size` strings outside
`re-frame.story.theme.typography`**. Per rf2-2rwdc AC#5 — call sites
read from `sans-stack` / `mono-stack` / `type-scale` / `weights` /
`letter-spacing` or fail review. The sweep that established the
contract spanned 30+ chrome files; the contract is a permanent lock
against drift.

### Cross-reference

- [`findings/re-frame-2-story-feature-set.md`](./findings/re-frame-2-story-feature-set.md)
  §anti-pattern catalogue — commodity-default fonts as a Storybook
  regression risk.

## §Colour (rf2-i3i5j)

> Shipped: [`theme/colors.cljc`](../src/re_frame/story/theme/colors.cljc).
> Phase 1 — rf2-s1r9a §F5.

### Lock

Story's palette is **warm-slate substrate + amber accent**:

- Warm-slate grounds (`:bg-0` → `:bg-3` plus `:bg-canvas` / `:bg-overlay`
  / `:bg-active` / `:bg-input`) — warmer than Causa's cool-grey
  substrate.
- **Amber `#F5A524`** as the hero accent — Story's identity signal,
  the workshop / atelier metaphor: gold-amber reads as "spotlight" /
  "work in progress".
- Cool-blue `:info #6CB8FF` as the secondary accent (workspace glyphs,
  scrubber rows).

Story is **NOT**:

- VS-Code Dark+ (the pre-rf2-i3i5j chrome literally adopted
  `#1e1e1e` / `#252526` / `#0e639c` / `#9cdcfe` / … — the
  cookie-cutter floor),
- Cool-grey + violet (Causa's pick around `#7C5CFF`),
- Cool-grey + brand pink (the Storybook commodity drift the
  [feature-set audit](./findings/re-frame-2-story-feature-set.md)
  anti-pattern #1 explicitly rejects).

### Rationale — two surfaces, two roles

When Story (workshop) and Causa (diagnostic) both render in the RHS
together, the user reads the role at a glance from temperature alone:

- **Warm** ↔ workshop / construction / focus on work-in-progress.
- **Cool** ↔ diagnostic / introspection / focus on what already ran.

Amber `#F5A524` pairs with Causa's `#7C5CFF` violet on the colour
wheel as a near-complementary contrast (amber yellow ↔ violet purple)
without landing on the AI-slop "purple gradient" floor. WCAG-AA
contrast preserved across every foreground/background pairing per the
rf2-2uwv contrast baseline (amber on `#0F1115` ground scores ~8:1;
`#1A1D24` ground ~6.5:1).

### Token vocabulary (public)

The contract third-party Story-panel authors honour:

| Category          | Tokens                                                                                                    |
|-------------------|-----------------------------------------------------------------------------------------------------------|
| **Surfaces**      | `:bg-0` (deepest matte) · `:bg-1` (sidebar) · `:bg-2` (raised chrome) · `:bg-3` (row hover / chip ground) |
| **Special surfaces** | `:bg-canvas` (THE workshop region — variant render surface) · `:bg-overlay` (floating dialogs) · `:bg-active` (selected variant row — amber-tinted) · `:bg-input` |
| **Borders**       | `:border-subtle` · `:border-default` · `:border-strong`                                                   |
| **Text**          | `:text-primary` · `:text-secondary` · `:text-tertiary` · `:text-on-accent`                                |
| **Hero accent**   | `:accent-amber` · `:accent-amber-hover` · `:accent-amber-soft` (deep amber backdrop) · `:accent-amber-deep` (pressed amber / outline) |
| **Semantic**      | `:success` / `-bg` · `:warning` / `-bg` · `:danger` / `-bg` · `:info` / `-bg`                             |
| **Mono / neutral**| `:mono-1` · `:mono-2` · `:mono-3`                                                                         |
| **Tag palette**   | `:tag-{dev,docs,test,screenshot,experimental,internal,agent}-bg/-fg` (seven canonical tags from [`001-Authoring.md`](001-Authoring.md) §Inclusion tags) |
| **Breakpoint**    | `:breakpoint-bg` · `:breakpoint-active` · `:breakpoint-ring` · `:breakpoint-ctrl-bg` · `:breakpoint-ctrl-bd` (rf2-ulw5m — Play step-debugger amber highlights) |
| **Focus / rows**  | `:focus-ring` (≡ `:accent-amber`) · `:row-fail-bg` · `:scrub-row-bg`                                      |

**Story carries five elevation levels** (`:bg-0`…`:bg-3` + `:bg-canvas`)
because the shell is laid out as CANVAS (focal, lifted) over an
inspector/sidebar ground over a base — the extra `:bg-canvas` level
is the variant render surface, visibly distinct so the user's eye
snaps to the work-in-progress instead of the surrounding chrome.
Causa's diagnostic single-pane only needs four levels.

### Naming-shape mirror

The token shape (`:bg-0` / `:bg-1` / `:bg-2` / `:bg-3`,
`:border-{subtle,default,strong}`, `:text-{primary,secondary,tertiary}`,
`:accent-*` / `:success` / `:warning` / `:danger` / `:info`) mirrors
Causa's
[`tokens.cljc`](../../causa/src/day8/re_frame2_causa/theme/tokens.cljc).
**Same shape, different values** — surfaces compose cleanly via
shared keys while asserting distinct palettes via differing hex
resolutions.

### Zero-raw contract

Per rf2-i3i5j AC#3 — **no hex literals at call sites in
`tools/story/src/**`**. All colour references resolve through
`(:keyword tokens)`. The sweep that established the contract spanned
30+ files; subsequent additions to the chrome have honoured it.

### Cross-references

- [`findings/re-frame-2-story-feature-set.md`](./findings/re-frame-2-story-feature-set.md)
  §anti-pattern #1 — brand-pink-on-cold-grey as a commodity Storybook
  drift.
- [`014-Chrome-Features.md`](014-Chrome-Features.md) §Sidebar tag-as-
  badge affordance — the seven canonical tag-palette pairs.

## §Motion (rf2-3lt89)

> Shipped: [`theme/motion.cljc`](../src/re_frame/story/theme/motion.cljc).
> Phase 1 — rf2-s1r9a §F6.

### Lock

Motion is **language, not decoration**. Three principles:

1. **Choreography over micro-interaction.** Per Phase 2 audit:
   *"one well-orchestrated page load with staggered reveals
   (animation-delay) creates more delight than scattered
   micro-interactions."* The shell-mount entrance is Story's
   high-impact moment — four landmarks (toolbar / sidebar / main /
   right) reveal in a staggered 360ms sequence.
2. **Expressive durations, not Material defaults.** Six timing slots
   tuned per register (chip press → entrance choreography); five
   easing curves (standard / exit / enter / emphatic / overshoot).
3. **`prefers-reduced-motion` always honoured.** Every animation /
   transition wraps in a `@media (prefers-reduced-motion: reduce)`
   override that pins durations to `0.01ms` — users with OS-level
   reduced-motion preferences get an instant-render shell.

Pre-rf2-3lt89 the chrome shipped **zero motion**. Hover states
snapped, the help overlay opened with no fade, and the shell threw
every region onto the page in one synchronous render. The rf2-s1r9a
audit (RED 2/10) called this out as the rubric's "predictable layout
that lacks context-specific character" anti-pattern.

### Timing tokens (public)

```
:micro-tap     80ms   chip presses, button squashes
:hover        140ms   colour / background transitions on hover
:overlay-fade 180ms   help overlay, recorder dialogs
:panel-slide  220ms   sidebar / inspector rail slide-ins
:entrance     360ms   shell mount entrance steps (per region)
:focus-ring   120ms   focus-visible outline expansion
```

### Easing tokens (public)

```
:standard   cubic-bezier(0.4, 0.0, 0.2, 1)        gentle in/out for state changes
:exit       cubic-bezier(0.4, 0.0, 1.0, 1.0)      fast-out, panel dismissals
:enter      cubic-bezier(0.0, 0.0, 0.2, 1.0)      slow-in, panel reveals
:emphatic   cubic-bezier(0.32, 0.72, 0, 1)        the shell mount entrance
:overshoot  cubic-bezier(0.34, 1.56, 0.64, 1)     chip press / micro-tap rebound
```

### Pre-built transition strings

For the most common chrome surfaces (`motion/transitions`):

```
:chip     background + colour hover + overshoot tap
:row      background + colour standard hover
:focus    outline-offset + outline-color focus-ring expansion
:overlay  opacity + transform overlay-fade (help / recorder dialogs)
:panel    transform + opacity panel-slide (command palette)
```

### Shell-mount choreography (the high-impact moment)

The `stagger` map keys on chrome region; each region carries an
`animation-delay` offset so the four landmarks reveal in sequence:

| Region    | `animation-delay` |
|-----------|-------------------|
| `:toolbar`| `0ms`             |
| `:sidebar`| `60ms`            |
| `:main`   | `120ms`           |
| `:right`  | `180ms`           |

`(motion/stagger-animation :region)` returns the inline `:animation`
string for a region — composes the canonical `rf-story-mount-in`
keyframe with the region's delay and the `:entrance` timing +
`:emphatic` easing. The `both` fill-mode keeps each region invisible
during its delay window so the staggered reveal is crisp.

### Injected stylesheet — `motion-css`

The chrome injects `motion-css` once at shell mount via
`(motion/inject-motion-css!)`. The stylesheet ships:

- `rf-story-mount-in` — shell-entrance keyframe (10px lift + fade-in),
  used by every region via `stagger-animation`.
- `rf-story-overlay-in` / `rf-story-overlay-out` — help / dialog open
  / close keyframes (8px lift + scale 0.985 → 1.0 / 1.0 → 0.99).
- `rf-story-chip-press` — micro-tap chip-press rebound on `:active`
  (scale 1.0 → 0.94 → 1.0).
- The canonical `[data-rf-story-root] *:focus-visible` outline — 2px
  amber, 2px offset, `border-radius: 3px`, animated on the
  `:focus-ring` timing.
- The `@media (prefers-reduced-motion: reduce)` override scoped to
  `[data-rf-story-root]` — every animation / transition pinned to
  `0.01ms`.

### The `--motion-scale` seam

The chrome's motion vocabulary composes through pure-data tokens. A
downstream consumer can scale the entire chrome's motion in one place
by overriding the `prefers-reduced-motion` block (e.g. tests
opt-into-instant via the same selector, or a future `:reduced-motion`
preference toggle layers on top of the OS-level one). The seam is
**the scoping selector `[data-rf-story-root]`** — every motion-bearing
chrome surface lives under this root, so a single CSS rule can pin
durations across the whole chrome without per-component opt-in.

### Idempotent + production-elided

`inject-motion-css!` is idempotent — subsequent calls short-circuit
on the `motion-css-injected?` sentinel. Behind
`re-frame.story.config/enabled?` so production builds skip the DOM
touch entirely.

### Cross-reference

- [`findings/re-frame-2-story-feature-set.md`](./findings/re-frame-2-story-feature-set.md)
  — motion-as-language as a competitive differentiator (Storybook
  ships ~none).

## §Depth + backdrop (rf2-ypd6h)

> Shipped: [`theme/depth.cljc`](../src/re_frame/story/theme/depth.cljc).
> Phase 1 — rf2-s1r9a §F7.

### Lock

The shell's atmospheric layer composes **three** distinct surfaces:

1. **Gradient mesh** on the shell root — a two-layer radial mesh of
   a warm amber blob (top-left, ~4.5% opacity) and a cool teal blob
   (bottom-right, ~2.5% opacity), blended against the deepest slate
   `#0B0D11`. Reads as **studio lighting** rather than a colour
   assignment.
2. **Grain overlay** as a `::before` pseudo on the shell root — an
   SVG-feTurbulence noise sheet at `opacity: 0.04` with
   `mix-blend-mode: overlay`. The eye reads it as **screen texture**
   rather than visible noise. Cuts the eyestrain pure-solid dark
   UIs induce and reduces AMOLED / OLED "screen-burning hole" effect.
3. **Canvas-frame accent edge** on the variant render surface — a 1px
   amber inset shadow so the user's eye lands on the work-in-progress
   automatically.

Pre-rf2-ypd6h the chrome shipped **flat solid backgrounds** — toolbar
/ sidebar / canvas / right-panel all rendered on identical `#252526`
grounds with no `box-shadow` declarations on any surface. The canvas
frame (the focal workshop region) was visually indistinguishable from
every inspector panel; the eye had nowhere to land. Storybook 8 still
ships flat backdrops — per the
[feature-set audit](./findings/re-frame-2-story-feature-set.md), this
is one of Story's clearest competitive differentiators.

### Shadow tokens (public) — `depth/shadows`

| Slot              | Use                                                             |
|-------------------|-----------------------------------------------------------------|
| `:elev-1`         | subtle 1px lift — raised chrome (toolbar, sidebar widget foot)   |
| `:elev-2`         | standard 4px lift — lifted panels (controls, dispatch console)  |
| `:elev-overlay`   | dramatic 16px lift — floating dialogs (help, recorder, share)   |
| `:canvas-edge`    | Story's signature — 1px amber inset + 8px drop on canvas frame  |
| `:focus-glow`     | 3px amber glow (`rgba(245, 165, 36, 0.25)`) — focus chrome      |

All shadows use `rgba` so they sit on top of any backdrop the surface
is rendered against.

### Backdrop tokens (public) — `depth/backdrops`

Each slot is a single CSS `background` shorthand that drops straight
into an inline `:style` map:

| Slot              | Composition                                                                                      |
|-------------------|--------------------------------------------------------------------------------------------------|
| `:shell-root`     | Warm amber blob (top-left, 4.5%) + cool teal blob (bottom-right, 2.5%) + `#0B0D11` deepest slate |
| `:canvas-frame`   | Single warm amber radial centred on top — the workshop region's halo                              |
| `:overlay-glass`  | Subtle radial centre-light + edge falloff — help / dialog "lit on a stage"                       |

The warm + cool counterweight is deliberate: a single warm cast would
look like a stain; the cool teal balances it so the eye reads "studio
lighting" rather than "tint applied."

### Grain overlay (`depth/grain-css`)

A self-contained SVG-feTurbulence noise sheet inline-encoded as a
data URI (no HTTP fetch). Injected once at shell mount via
`(depth/inject-grain-css!)`. Targets the
`[data-rf-story-root]::before` pseudo so the overlay lives BEHIND
the root's content via `z-index: -1` + `position: absolute` from
the root's `position: relative`. Pointer-events disabled so the
overlay never swallows clicks. Idempotent and production-elided
same shape as `inject-motion-css!`.

### Cross-reference

- [`003-Render-Shell.md`](003-Render-Shell.md) §Right-hand pane — the
  three-pane substrate the gradient mesh sits beneath.

## §Iconography — sidebar glyph rhythm (rf2-p0wur)

> Shipped: [`theme/glyphs.cljc`](../src/re_frame/story/theme/glyphs.cljc).
> Phase 2 — rf2-p0wur §F8.

### Lock

Story ships **five inline SVG glyphs** as the chrome's icon set —
NOT Lucide, NOT Heroicons, NOT a third-party icon library. The set is
deliberately small and identity-bearing:

| Public fn          | Glyph                | Use                                                              |
|--------------------|----------------------|------------------------------------------------------------------|
| `story-glyph`      | ◆ diamond (outline)  | Story (parent container) — sidebar row glyph, **amber-coloured** |
| `variant-glyph`    | ● filled dot         | Variant (renderable unit) — sidebar row glyph, status-coloured or muted |
| `workspace-glyph`  | ▦ 2×2 grid          | Workspace (multi-variant composition) — sidebar row glyph, **info-cyan-coloured** |
| `chevron-right`    | → chevron            | Pop-out affordance on chips, links, Causa popout                 |
| `external-link`    | ↗ external-link arrow| Causa pop-out chip, open-in-editor affordances                   |

Pre-rf2-p0wur the sidebar had **no per-row glyphs** — three different
row types (story / variant / workspace) read identically. The
[feature-set audit](./findings/re-frame-2-story-feature-set.md) called
this out as a parse cost the eye paid on every scan. With the glyphs
shipped, the three row types are visually distinguishable at a glance.

### SVG-via-`currentColor` contract

Every glyph is a stroke-based SVG drawn at a 16×16 viewBox with
`stroke="currentColor"` — callers control colour via CSS. The
glyph fns return pure hiccup (no Reagent dep, JVM-portable). Common
SVG attributes:

```clojure
{:viewBox        "0 0 16 16"
 :stroke         "currentColor"
 :stroke-width   "1.5"
 :stroke-linecap "round"
 :stroke-linejoin "round"
 :aria-hidden    "true"
 :style          {:display        "inline-block"
                  :vertical-align "-2px"
                  :flex-shrink    "0"}}
```

The size arity defaults are: story / variant glyphs `14px`, workspace
/ chevron / external-link `12px`. Call sites pass an explicit size
when the row layout demands one (e.g. sidebar story rows render at
`13px`, sidebar variants at `10px` — see
`re-frame.story.ui.sidebar`).

### Sidebar amber-glyph rhythm (the structural contract)

The sidebar tree renders three row types with a deliberate **glyph +
colour rhythm** so the eye parses the tree structure without reading
text:

| Row type    | Glyph              | Colour                    | Active state                                              |
|-------------|--------------------|---------------------------|-----------------------------------------------------------|
| Story       | `story-glyph`      | `:accent-amber`           | Bold + amber-coloured row (parent header)                 |
| Variant     | `variant-glyph` OR `status-dot` | `:text-tertiary` (muted) OR semantic-status colour | `:bg-active` ground + `:accent-amber` text + 2px amber `border-left` |
| Workspace   | `workspace-glyph`  | `:info` (cool-cyan)       | `:bg-active` ground + `:accent-amber` text + 2px amber `border-left` |

The colour split is intentional:

- **Amber** carries the story chapter heading + every active selection.
- **Info-cyan** carries workspace glyphs — workspaces are the
  multi-variant composition surface; the cyan-vs-amber temperature
  split tells the eye "this row is a different category" without
  needing labels.
- **Muted text-tertiary** carries non-testable variant glyphs at low
  opacity so the row indent is uniform without competing with the
  active selection's amber edge.

Testable variants (those carrying `:test` in `:tags` or a non-empty
`:play` sequence) replace the muted glyph with a **`status-dot`**
that wears the semantic colour from the variant's last
`run-variant` outcome (`:success` green pass · `:danger` red fail ·
`:warning` yellow running · transparent ring pending).

### Cross-references

- [`014-Chrome-Features.md`](014-Chrome-Features.md) §Sidebar tag-as-
  badge affordance — the inline tag pills the glyph rhythm composes
  with.
- [`003-Render-Shell.md`](003-Render-Shell.md) — the sidebar surface
  this glyph rhythm lives in.

## §Toolbar 5-cluster structure (rf2-v58dm)

> Shipped: [`ui/toolbar.cljs`](../src/re_frame/story/ui/toolbar.cljs).
> Phase 2 — rf2-v58dm §F9. Replaces the v1 single-strip described in
> [`010-Toolbar.md`](010-Toolbar.md) §Placement-and-rendering.

### Lock

The toolbar reads as **5 distinct affordance clusters** separated by
token-driven vertical hairlines, each labelled with a small-caps
cluster name:

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│ MODES axis-groups…  │ DATA Dispatch + Play  │ VIEW Viewport + Bg  │ DEBUG Inspect  │ REC Recorder [reset] │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

Pre-rf2-v58dm the toolbar was a flat alphabetic chip row with
ungrouped affordances — viewport, backgrounds, dispatch console,
recorder, inspector, modes all jostled for left-to-right space with
no visual grouping. As the chrome accreted features (rf2-zll4h
viewport + backgrounds, rf2-h0jc0 inspect chip, rf2-5fc15 REC chip,
rf2-q9kv5 dispatch chip, rf2-8i2a9 play-status chip), the flat row
became hard to scan. The 5-cluster layout names the logical groups so
users scan groups rather than chips.

### Cluster vocabulary (public)

| Cluster   | Hosts                                                                                                  | Label    |
|-----------|--------------------------------------------------------------------------------------------------------|----------|
| **MODES** | Registry-driven `reg-mode` axis-groups + unaxed modes (per [`010-Toolbar.md`](010-Toolbar.md))         | `Modes`  |
| **DATA**  | Dispatch console chip (rf2-q9kv5) · Play-script status chip (rf2-8i2a9) — variant-scoped affordances    | `Data`   |
| **VIEW**  | Viewport switcher chip (rf2-zll4h) · Backgrounds switcher chip                                          | `View`   |
| **DEBUG** | Element inspector chip (rf2-h0jc0) — React-Devtools-style pick mode                                    | `Debug`  |
| **REC**   | Test Codegen REC chip (rf2-5fc15) · `[reset]` (resets active modes)                                    | `Rec`    |

The MODES cluster is **registry-driven** (variable width — every
registered `:mode` produces a chip per the existing
[`010-Toolbar.md`](010-Toolbar.md) §Data source contract). The other
four clusters are **fixed-affordance** — each cluster's chip set is
defined by the chrome, not by the registrar.

### Visual contract

- **Cluster labels** wear `:micro` type-scale (`10px`), `:semibold`
  weight, uppercase via `text-transform`, `:text-tertiary` colour,
  and `:label-wide` letter-spacing (`0.6px`). Each label sits at
  the left edge of its cluster with `aria-hidden="true"` (the
  cluster's chips carry their own ARIA semantics).
- **Hairline dividers** between clusters are `1px` solid
  `:border-subtle`, `align-self: stretch`, `margin: 2px 4px`. The
  divider sits centred on the strip rather than spanning it
  edge-to-edge.
- **Active chip styling** — `:bg` becomes `:accent-amber`,
  `:color` becomes `:text-on-accent`, and the border becomes
  `:accent-amber-deep` (a pressed-amber outline). This is the
  rf2-v58dm signature: the **accent-amber-deep active-chip border**
  reads as "pressed + saturated" rather than just "highlighted."
- **Cluster ordering is structural, NOT alphabetic.** Modes go first
  (variable width, left-edge anchor); the chrome-fixed clusters
  follow right-aligned via a `:spacer` slot in the order DATA →
  VIEW → DEBUG → REC.

### Cluster-internal ordering

Within a cluster, ordering rules are:

- **MODES** — the existing alphabetic-by-mode-id ordering from
  [`010-Toolbar.md`](010-Toolbar.md) §Data source, with axis-grouped
  modes leading and unaxed modes trailing.
- **DATA** — Dispatch console chip first (panel-visibility toggle),
  play-status chip second (status indicator). Both are
  variant-scoped: when no variant is selected, the entire DATA
  cluster collapses (along with its leading divider).
- **VIEW** — Viewport switcher first, then backgrounds switcher
  (Storybook addon-viewport + addon-backgrounds parity per
  rf2-zll4h).
- **DEBUG** — Element inspector chip only at v1.
- **REC** — REC chip first, then `[reset]` (the latter conditional
  on `(seq active-modes)`).

### Test surface

The toolbar's `<header role="toolbar">` carries `data-test=
"story-toolbar"`. Each cluster carries `data-test=
"story-toolbar-cluster"` + `data-cluster="<name>"` where `<name>`
is `modes` / `data` / `view` / `debug` / `rec`. Test corpora can
locate a cluster without walking the chip tree:

```
[data-test="story-toolbar"]                          ; the strip
[data-test="story-toolbar-cluster"][data-cluster="modes"]
[data-test="story-toolbar-cluster"][data-cluster="rec"]
```

### Cross-references

- [`010-Toolbar.md`](010-Toolbar.md) — the canonical toolbar surface
  spec. §Placement and §Selection semantics now read against the
  5-cluster shape; the per-cluster vocabulary lives in this section.
- [`014-Chrome-Features.md`](014-Chrome-Features.md) §Command palette
  — the orthogonal Cmd-K palette that searches the same registry
  the MODES cluster exposes.

## Composition — how the chrome consumes the tokens

The shell composes the six token domains in one pass at
`(mount-shell!)`:

```clojure
;; theme/typography.cljc
(theme.typography/inject-font-faces!)   ; @font-face → document.head
;; theme/motion.cljc
(theme.motion/inject-motion-css!)       ; @keyframes + reduced-motion → document.head
;; theme/depth.cljc
(theme.depth/inject-grain-css!)         ; ::before grain overlay → document.head
```

Three idempotent one-shot injections, each gated on
`re-frame.story.config/enabled?` so production builds DCE the chrome
entirely. The injectors run BEFORE the first render so the
`font-family` / `animation` / `box-shadow` declarations on chrome
surfaces resolve immediately.

The shell stamps its root with `data-rf-story-root` — every token-
dependent CSS rule (the `*:focus-visible` outline, the
`prefers-reduced-motion` override, the grain overlay's `::before`)
scopes to this selector so the chrome composes cleanly with
host-app stylesheets.

## Zero-raw contract (consolidated)

The six token namespaces are the **single source of truth** for the
chrome's identity-bearing values. The contract:

- **No `font-family` strings outside `theme.typography`** (rf2-2rwdc AC#5).
- **No hex literals outside `theme.colors`** (rf2-i3i5j AC#3).
- **No raw duration / easing strings outside `theme.motion`** (rf2-3lt89 follow-on).
- **No `box-shadow` strings outside `theme.depth`** (rf2-ypd6h follow-on).
- **No SVG glyph definitions outside `theme.glyphs`** (rf2-p0wur).

Call sites consume tokens via keyword lookup against the canonical
maps; failures are caught at review. The contract is enforced by
maintenance discipline rather than a lint rule — the test corpus's
chrome-feature gates exercise the rendered surfaces, and the
[`015-Test-Coverage.md`](015-Test-Coverage.md) row count would
inflate visibly if a regression sneaked in a hard-coded value.

## Production elision

Per [`005-SOTA-Features.md`](005-SOTA-Features.md) §DCE contract
the entire chrome is dead-code-eliminated under `:advanced`. The
token namespaces are pure data — they compile to small constant
maps — and the injector functions self-elide via
`re-frame.story.config/enabled?` so:

- A production build of the host app pays **zero bytes** for the
  six token namespaces.
- A static-export build (`story:build` per
  [`013-Static-Build.md`](013-Static-Build.md)) DOES emit the
  chrome — `enabled?` stays true in static mode — and the
  `<style>` injects fire the same as the live playground.

## Cross-references — design system map

| Domain        | Spec section                                                          | Source                                                              |
|---------------|-----------------------------------------------------------------------|---------------------------------------------------------------------|
| Typography    | §Typography (this doc)                                                | [`theme/typography.cljc`](../src/re_frame/story/theme/typography.cljc) |
| Colour        | §Colour (this doc)                                                    | [`theme/colors.cljc`](../src/re_frame/story/theme/colors.cljc)      |
| Motion        | §Motion (this doc)                                                    | [`theme/motion.cljc`](../src/re_frame/story/theme/motion.cljc)      |
| Depth         | §Depth + backdrop (this doc)                                          | [`theme/depth.cljc`](../src/re_frame/story/theme/depth.cljc)        |
| Iconography   | §Iconography (this doc)                                               | [`theme/glyphs.cljc`](../src/re_frame/story/theme/glyphs.cljc)      |
| Toolbar shape | §Toolbar 5-cluster (this doc) + [`010-Toolbar.md`](010-Toolbar.md)    | [`ui/toolbar.cljs`](../src/re_frame/story/ui/toolbar.cljs)          |
| Chrome surface| [`003-Render-Shell.md`](003-Render-Shell.md)                          | [`ui/shell.cljs`](../src/re_frame/story/ui/shell.cljs)              |
| Chrome features| [`014-Chrome-Features.md`](014-Chrome-Features.md)                   | various                                                             |
| SOTA features | [`005-SOTA-Features.md`](005-SOTA-Features.md)                        | various                                                             |
| Causa tokens (peer) | [`tools/causa/`](../../causa/) — `theme/tokens.cljc`            | the cool-grey + violet counterpart Story contrasts against           |

The token spec is the canonical home; the surface specs reference
back to the token section that owns each axis.
