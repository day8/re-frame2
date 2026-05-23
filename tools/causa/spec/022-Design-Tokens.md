# Causa — Design Tokens

> The visual-design foundation for Causa: colour identity, type scale, and the
> visual-encoding rules every panel obeys. The full five-region layout + the seven
> per-panel designs are ported from the Figma-derived brief under **rf2-ad7zx.1**
> (into [007-UX-IA](007-UX-IA.md) + [021-Dynamic-Panel-Designs](021-Dynamic-Panel-Designs.md));
> this doc locks the **tokens** those designs reference.
>
> **Authority:** the **Figma design wins on look + brand** (Mike: keep the Figma design, don't
> go off script). This doc — orange identity, brand vs. mode-accent split, type scale,
> visual-encoding — is downstream of the **Figma export** (`ai/figma-make-export`) and takes
> precedence on anything *visible*. [007-UX-IA §Colour system](007-UX-IA.md#colour-system)
> keeps the **functional semantic accents** the framework needs but the mock didn't render
> (redacted, pair-origin, perf tiers) + the CSS-custom-property surface. Keep in sync; on the
> visible chrome the **Figma design wins**.

## Colour identity — orange-forward

Causa's brand colour is **orange**. Every accent is a **single token** (a CSS custom
property at runtime; a theme key in `implementation/.../theme/tokens.cljc`) so the identity
is a **one-line change per token** — a hard requirement, not an accident.

**Brand vs. mode accent — keep them separate:**
- **`brand`** is the logo / wordmark colour and is **always orange**, in either mode.
- The **mode accent** (active tab, the mode stripe, active/selected states, focus ring)
  **follows the current mode**: orange in **Dynamic**, cyan in **Static**. At runtime a root
  class (`.mode-dynamic` / `.mode-static`) points `accent` at `accent-dynamic` or
  `accent-static`. So in Dynamic the whole UI reads orange (brand + accent agree); in Static
  the logo stays orange while active states turn cyan.

### Surfaces & text (neutral; both themes)

| token | role | dark | light |
|---|---|---|---|
| `bg-1` | deepest surface | `#15171B` | `#F1F3F6` |
| `bg-2` | panel surface | `#1B1E24` | `#FFFFFF` |
| `bg-3` | raised / strip | `#232730` | `#E6E9EE` |
| `hover` | hover background | `#232730` | `#E6E9EE` |
| `border-subtle` | hairlines | `#232730` | `#E6E9EE` |
| `border-default` | controls | `#2F3441` | `#CFD4DC` |
| `text-primary` | body text | `#E8EAF0` | `#15171B` |
| `text-secondary` | labels / hints | `#A8AEC0` | `#4B5160` |
| `dim` | dimmed / inert / **unchanged** | `#6E7681` | `#8C959F` |

Surfaces are kept **neutral** (orange pops on neutral, and it avoids the a11y / muddy-brown
risk of tinted darks). Warming them a hair is an optional later polish, not required.

### Brand & mode accents

| token | role | dark | light |
|---|---|---|---|
| **`brand`** | logo / wordmark — **always orange** | `#F97316` | `#EA580C` |
| `accent-dynamic` | Dynamic-mode accent (orange) | `#F97316` | `#EA580C` |
| `accent-static` | Static-mode accent (cyan) | `#43C3D0` | `#2A8B96` |
| `accent` | **runtime alias** → the active mode's accent (active tab · mode stripe · selected states · focus ring) | *= accent-dynamic in Dynamic* | *= accent-static in Static* |

Decision (Mike): keep cyan for Static, but it's a single token — swapping the whole identity
is one edit per token.

### Semantic & change

| token | role | dark | light |
|---|---|---|---|
| `error` | errors | `#F85149` | `#CF222E` |
| `warning` | warnings (was `yellow`; also drives the filter "N hidden" chrome) | `#FBBF24` | `#B07A05` |
| `advisory` | advisories — the lowest Issues severity (calm, cool, ≠ warning) | `#79A6D2` | `#3B6EA5` |
| `success` | success / diff-added | `#3FB950` | `#1A7F37` |
| `changed` | a value/sub **changed/recomputed** (alias of `accent`, used sparingly) | *= accent* | *= accent* |
| `unchanged` | unchanged / short-circuited (alias of `dim`) | *= dim* | *= dim* |

- **Issues severities:** `error` (red) · `warning` (amber) · `advisory` (cool blue) — three
  distinct tones so the Issues panel reads at a glance. Keep `warning` clearly distinct from
  `brand`/`accent` (orange); if they ever blur, cool the warning toward gold `#EAB308`.
- **app-db diff:** added → `success`, removed → `error`, changed → `warning` (reuse the
  semantic tokens; no new colours).
- **Views / recompute:** changed/recomputed highlight → `changed` (= mode `accent`); unchanged
  / short-circuited → `unchanged` (= `dim`). Use the accent sparingly (the changed node, not
  whole rows) so the UI doesn't over-saturate.

## Type scale (anchored at 13px)

| token | px | use |
|---|---|---|
| `body` | 13 | default UI text (anchor) |
| `body-tight` | 12 | header chrome, ribbons |
| `caption` | 11 | hints, secondary labels |
| `micro` | 10 | badges, tabs |

Sans-serif for UI; **monospace for code / EDN values**.

## Visual encoding (applies to every panel)

Carry meaning through the **strongest channel first**; treat pictographic icons as at most
*secondary* reinforcement, never the primary signal:

- **Node state** (recomputed vs unchanged) → **colour + emphasis on the node**: changed =
  `changed` (mode accent) / filled; unchanged = `unchanged` (`dim`) / outline. A short text
  tag only if colour alone is ambiguous.
- **A relationship / short-circuit** → **edge style** (a cut/short-circuited dependency is a
  dashed + greyed edge), not a glyph beside it.
- **A count / sharing fact** → topology (multiple edges) + a small **`×N`** number.
- **Naming something** → **text, spelled out** (e.g. the event list's Source column reads
  `view` / `fx` / `timer`, not icons).
- **Accessibility:** never rely on a lone glyph *or* a lone colour — pair colour with
  shape / weight / text so meaning survives colour-blindness and unfamiliar symbols.

## Caveats & implementation

- **Contrast.** `brand` / `accent` (orange) are for **fills, active states, the mode stripe,
  and large text — not small body text** (light-theme `#EA580C` on white is borderline for
  WCAG AA at body size). Body copy uses `text-primary` / `text-secondary`; verify any orange
  *text* meets AA.
- **Migration cost.** The implementation currently reads `(:accent-violet tokens)` /
  `(:cyan tokens)` across ~357 call sites. Renaming to `:brand` / `:accent` /
  `:accent-static` (+ `dim`, `warning`, `advisory`, `changed`/`unchanged`) is a **call-site
  sweep** (or temporary aliases) — not free; scoped under rf2-ad7zx.
- **Where they live.** `implementation/.../theme/tokens.cljc` (+ the runtime
  CSS-custom-property emission, with the `.mode-dynamic` / `.mode-static` root class flipping
  `accent`). Consumers read `(:accent tokens)` etc. so a re-skin is a token-table edit.
