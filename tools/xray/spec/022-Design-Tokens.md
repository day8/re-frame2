# Xray — Design Tokens

> The visual-design foundation for Xray: colour identity, type scale, and the
> visual-encoding rules every panel obeys. The full five-region layout + the seven
> per-panel designs are ported from the Figma-derived brief under **rf2-ad7zx.1**
> (into [007-UX-IA](007-UX-IA.md) + [021-Dynamic-Panel-Designs](021-Dynamic-Panel-Designs.md));
> this doc locks the **tokens** those designs reference.
>
> **Authority:** the **Figma design wins on look + brand** (Mike: keep the Figma design, don't
> go off script). This doc — the GitHub-style blue/neutral identity, type scale,
> visual-encoding — is downstream of the **Figma export** (the `devtools-css` block embedded in
> `tools/xray/design-reference/xray_devtools_reference.cljs`) and takes precedence on anything *visible*.
> [007-UX-IA §Colour system](007-UX-IA.md#colour-system) keeps the **functional semantic accents**
> the framework needs but the mock didn't render (redacted, pair-origin, perf tiers) + the
> CSS-custom-property surface. Keep in sync; on the visible chrome the **Figma design wins**.

## Colour identity — GitHub-style blue/neutral (rf2-ad7zx.13)

Xray's accent colour is a **single GitHub-style blue** — the palette the Figma export ships.
Every accent is a **single token** (a CSS custom property at runtime; a theme key in
`tools/xray/.../theme/tokens.cljc`) so the identity is a **one-line change per token** — a
hard requirement, not an accident.

**Single accent — no per-mode colour swap:**
- There is **one** `accent` (GitHub blue): active tab, the chrome stripe, active/selected
  states, focus ring, the L4 panel header stripe, and `changed`/recompute highlights all read it.
- The **Dynamic / Static MODE stays a functional mode** (it gates motion — Static drops the
  continuous pulses + collapses the tab fade). It **no longer drives accent colour**: the shell
  reads the same blue accent in either mode. The earlier orange-identity scheme (an always-orange
  `brand` plus per-mode `accent-dynamic` orange / `accent-static` cyan, swapped under a
  `.mode-dynamic` / `.mode-static` root class) is **removed**.
- The logo / wordmark (`❖ Xray`) reads the same single `accent` blue.

### Surfaces & text (neutral; both themes)

| token | role | dark | light |
|---|---|---|---|
| `bg-0` | deepest recess | `#161616` | `#fbfbfb` |
| `bg-1` | chrome surface (sidebar, top strip) | `#1c1c1c` | `#f5f5f5` |
| `bg-2` | panel surface | `#242424` | `#ffffff` |
| `bg-3` | raised / strip / popovers | `#2a2a2a` | `#e8e8e8` |
| `hover` | hover background | `#2a2a2a` | `#e8e8e8` |
| `border-subtle` | hairlines | `#2a2a2a` | `#e8e8e8` |
| `border-default` | controls | `#373737` | `#d1d1d1` |
| `text-primary` | body text | `#e6edf3` | `#24292f` |
| `text-secondary` | labels / hints | `#adbac7` | `#656d76` |
| `text-tertiary` | muted / inactive | `#8b949e` | `#8c959f` |
| `dim` | dimmed / inert / **unchanged** | `#6e7681` | `#8c959f` |

`bg-1` = the Figma `--devtools-chrome-bg`; `border-default` = `--devtools-border`;
`text-primary` = `--devtools-text`; `text-tertiary` = `--devtools-text-muted`. Surfaces are
**neutral GitHub greys** so the blue accent pops. Xray carries three text levels (the Figma
export ships two — `--devtools-text` + `--devtools-text-muted`); `text-secondary` is a brighter
mid (`#adbac7`, GitHub `fg.muted`) so all three clear WCAG AA on the dark surfaces — AAA for
primary/secondary.

### Accent (single blue)

| token | role | dark | light |
|---|---|---|---|
| **`accent`** | the SINGLE accent — active tab · chrome stripe · selected states · focus ring · L4 header stripe · logo · changed/recompute | `#539bf5` | `#0969da` |

`accent` = the Figma `--devtools-active` / `--devtools-changed`. Swapping the whole identity is
one edit per token.

### Semantic & change

| token | role | dark | light |
|---|---|---|---|
| `error` | errors | `#f85149` | `#cf222e` |
| `warning` | warnings (also drives the filter "N hidden" chrome) | `#d29922` | `#9a6700` |
| `advisory` | advisories — the lowest Issues severity (calm, cool blue) | `#79c0ff` | `#0550ae` |
| `success` | success / diff-added | `#3fb950` | `#1a7f37` |
| `info` | fixed cool categorical blue — distinct from `accent` (spine-paused, sub-run, route-from, syntax-number, the machine TO-highlight) | `#79c0ff` | `#0550ae` |
| `changed` | a value/sub **changed/recomputed** (alias of `accent`, used sparingly) | *= accent* | *= accent* |
| `unchanged` | unchanged / short-circuited (alias of `dim`) | *= dim* | *= dim* |

`error`/`warning`/`success` = the Figma `--devtools-error` / `--devtools-warning` /
`--devtools-success`.

- **Issues severities:** `error` (red) · `warning` (amber) · `advisory` (cool blue) — three
  distinct tones so the Issues panel reads at a glance.
- **`info` vs `accent`:** both are blue, but `accent` is the **primary** chrome signal (active /
  selected / changed) and `info` is a **fixed categorical** cool blue used where a surface needs
  to read as a distinct peer of the primary accent (the in-flight head rides `accent`; the
  paused head rides `info`; the dispatch op-family rides `accent`, the db / sub families ride
  `info`). `info` shares `advisory`'s hue — both are the GitHub syntax-number blue.
- **app-db diff:** added → `success`, removed → `error`, changed → `warning` (reuse the
  semantic tokens; no new colours).
- **Low-opacity row washes:** a few surfaces tint a whole ROW rather than colour text — the
  schema-violation sub-block wash (`bg-violation`, a rose surface), the app-db-diff per-op
  washes (`diff-added-wash` / `diff-modified-wash` / `diff-removed-wash`, 8-digit-hex
  `#RRGGBBAA` at ~10-12%), and the **L2 issue-epoch row wash** (`bg-issue-row`, rf2-b8guz — a
  light-pink rose wash, same hue family as `bg-violation`, painted behind any L2 event row whose
  epoch carries an issue per the canonical Issues predicate; see
  [`018-Event-Spine.md`](018-Event-Spine.md) §Issue-epoch row wash). Washes are deliberately low
  alpha so the row text + other row signals stay legible and the wash COMPOSES over the
  selected/focused-row background. Full hex values live in `theme/tokens.cljc` (the source of
  truth for every token).
- **Views / recompute:** changed/recomputed highlight → `changed` (= `accent`); unchanged /
  short-circuited → `unchanged` (= `dim`). Use the accent sparingly (the changed node, not whole
  rows) so the UI doesn't over-saturate.

### Functional categorical hues (carve-out)

These do REAL semantic work — perf tiers, machine state, route side-channel, redaction,
op-family legends — and are **not** collapsed into the accent.

| token | role | dark | light |
|---|---|---|---|
| `green` | success / additions / machine-active | `#3fb950` | `#1a7f37` |
| `yellow` | warnings / schema-replaced / `:rf.size/large-elided` elision | `#d29922` | `#9a6700` |
| `orange` | functional amber — long-task / perf-slow tier | `#FB923C` | `#C2570F` |
| `red` | errors / schema-violations / hydration-mismatches | `#F87171` | `#C84444` |
| `magenta` | classification: `:rf/redacted` chip · Epoch COEFFECT badge · palette frame indicator · filter OUT pill · diff colour family · static-routes/schemas letter chips · machine-inspector | `#a855f7` | `#9333ea` |
| `magenta-pink` | Epoch SUBSCRIPTIONS badge (rf2-cgm4f split from `magenta`) | `#ec4899` | `#db2777` |

`orange` here is the **functional perf-amber** (`#FB923C` / `#C2570F`) — it is NOT a brand
colour and is distinct from the removed orange brand identity.

`magenta` + `magenta-pink` are two distinct hues in the pink/violet family (rf2-cgm4f,
Mike-ruled 2026-05-26). The original Epoch-panel mock split COEFFECT (violet `#a855f7`)
and SUBSCRIPTIONS (pink `#ec4899`); pre-rf2-cgm4f both collapsed onto the lighter fuchsia
`#E879F9` and the two pipeline pills read near-identically. Splitting hue (not just
lightness) restores the operator's ability to distinguish the two cascade steps at a
glance. Source of truth: `tools/xray/src/day8/re_frame2_xray/theme/tokens.cljc`
(`:magenta` + `:magenta-pink` keys in both the dark and light maps).

### Syntax highlighting (Figma `devtools-css` block)

EDN / Clojure source rendering reads:

| role | dark | light |
|---|---|---|
| keyword | `accent` (`#539bf5`) | `accent` (`#0969da`) |
| string | `green` (`#3fb950`) | `green` (`#1a7f37`) |
| number | `info` (`#79c0ff`) | `info` (`#0550ae`) |
| comment | `text-tertiary` | `text-tertiary` |

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
  `changed` (the accent) / filled; unchanged = `unchanged` (`dim`) / outline. A short text
  tag only if colour alone is ambiguous.
- **A relationship / short-circuit** → **edge style** (a cut/short-circuited dependency is a
  dashed + greyed edge), not a glyph beside it.
- **A count / sharing fact** → topology (multiple edges) + a small **`×N`** number.
- **Naming something** → **text, spelled out** (e.g. the event list's Source column reads
  `view` / `fx` / `timer`, not icons).
- **Accessibility:** never rely on a lone glyph *or* a lone colour — pair colour with
  shape / weight / text so meaning survives colour-blindness and unfamiliar symbols.

## Caveats & implementation

- **Contrast.** `accent` (blue) is for **fills, active states, the chrome stripe, and large
  text**. Body copy uses `text-primary` / `text-secondary`; the dark text ramp is tuned so all
  three text levels clear WCAG 2.1 AA on `bg-1`/`bg-2` (AAA for primary/secondary).
- **Where they live.** `tools/xray/.../theme/tokens.cljc` (+ the runtime CSS-custom-property
  emission in `theme/global-styles`). The Dynamic/Static mode root class no longer flips the
  accent. Consumers read `(:accent tokens)` etc. so a re-skin is a token-table edit. The
  machines-viz chart (`tools/machines-viz/.../theme/tokens.cljc`) mirrors this palette at the
  values level (drift-gate `xray-and-machines-viz-*-palettes-match-values`, rf2-z7ms8) so the
  chart paints the same colours whether embedded by Xray or standalone.
