# `examples/_shared/` — shared examples design system

One stylesheet, one favicon, one social card — and every example
`index.html`, on every substrate (Reagent, Reagent Slim, UIx, Helix),
links the same three. This directory is where they live. The payoff of
keeping them in one place is the usual one: change the palette here and
the whole catalogue re-skins; there's no per-example copy drifting out
of sync.

The trick that makes a single shared directory work for examples that
build into many different output folders is that every page references
its assets at the *same relative path* — `_shared/css/style.css`, never
an absolute URL. So whatever stages an example just has to drop a copy
of this tree next to the staged page.

## Visual identity

Every page wears the same skin: one typography stack, one palette, one
stylesheet. That's a deliberate choice, and worth pausing on, because
the obvious alternative — give each substrate its own colours so you can
tell a Reagent page from a UIx page at a glance — is a trap. The thing
we actually want a reader to notice is the *code*, not the chrome.
Substrate variety gets announced where it belongs (the substrate
selector, and the substrate-specific examples themselves); the visual
identity stays out of that conversation entirely, so two examples that
look identical are telling you their UIs really are the same modulo the
rendering layer.

| Mood              | Editorial Warm — established, refined, magazine-leaning  |
| ----------------- | -------------------------------------------------------- |
| Typography pair   | Inter (UI + body) + JetBrains Mono (code, mono labels)   |
| Palette           | warm paper bg #F7F3EC / deep ink #1A1814 / amber #C8741A |
| Atmosphere        | paper-grain radial gradients fixed to the viewport       |

The pairing is a quiet nod to the rest of the project — Xray uses Inter
+ JBM, Story uses IBM Plex on a similarly light surface — so when you
have an example, a dev tool, and the docs open side by side, they read
as one family rather than three strangers who happened to share an
elevator.

**No remote fonts.** Here's a detail that's easy to get wrong and worth
calling out: the stylesheet *names* Inter / JetBrains Mono as its
first-preference families, but it loads **no** web fonts to back them up
— there is no `@import` of Google Fonts, or any other host. The font
stacks fall through to their declared system fallbacks (`system-ui` /
`Segoe UI` / `-apple-system` for the UI, `ui-monospace` / `SF Mono` /
`Menlo` for the mono), which means a staged example makes **zero**
third-party network requests just to style itself. That buys more than
tidiness: offline runs, firewalled CI, and privacy-sensitive local demos
all render identically, and a screenshot taken today matches one taken
next year because nothing is being fetched from a server that might have
moved on.

## Files

Five files, and the only one with anything subtle going on is the social
card — the rest are what they say on the tin:

- `css/style.css` — the shared design system itself, linked by every
  `index.html`. Imports `structure.css`.
- `css/structure.css` — the substrate-agnostic structural baseline:
  form geometry, grid layout, the max-widths. Everything here is about
  *shape*, nothing about brand, which is why it's split out from
  `style.css`.
- `img/favicon.svg` — the shared favicon (warm-slate with an amber
  accent). Browsers render SVG favicons happily, so this one ships
  as-is, no raster step needed.
- `img/og.png` — the shared Open Graph preview card, a 1200×630 raster.
  This is the asset every `index.html` references, and it's a raster for
  a slightly annoying reason: link-preview scrapers (Facebook / X /
  LinkedIn / Slack / Discord) flat refuse to render an SVG `og:image`. So
  however much we'd prefer to ship vector art, the social card has to be
  a PNG/JPG to actually show up in a preview.
- `img/og.svg` — the editable SOURCE ART behind that card; no page
  references it. When the design changes, re-export `og.png` from this at
  *exactly* 1200×630. Its colour literals deliberately mirror the
  `--ex-*` tokens in `style.css`, so the source and the stylesheet can't
  drift apart and the social card inherits the same palette — including
  the same accessibility decisions — as the rest of the catalogue.

## Responsive Xray-host shell

A couple of examples (`counter`, `flows`) run Xray *inline*, side by side
with the app, and that raises a layout problem worth solving once in the
shared CSS rather than badly in each example. The two of them wrap their
app + Xray panel in a `.rf2-testbed-shell`:

```html
<div class="rf2-testbed-shell">
  <main id="app"></main>
  <aside data-rf-xray-host></aside>
</div>
```

On a desktop this is a two-column flex — app on the left, the inline
Xray host on the right at `--rf-xray-inline-width` (560px by default).
That's fine until you do the arithmetic for a phone. The host is
`flex-shrink: 0` with a 320px `min-width`, so it refuses to give ground;
between it and a usable app column the layout wants roughly 624px before
any app content can appear at all. Below that, even though the pages
declare a responsive viewport, the whole thing simply runs off the side
of the screen.

So `structure.css` **stacks** the shell below a 900px breakpoint. The
two columns become two rows — app on top, Xray host underneath — the
host lets go of its fixed width and min-width and goes full-bleed, and
its height is capped (`max-height: 60vh` with internal scroll) so the
panel can never grow tall enough to shove the app off-screen. Crucially,
none of this touches the host/app DOM contract: the shape Xray hunts for
(`.rf2-testbed-shell > #app` plus `[data-rf-xray-host]`) is **unchanged**,
so auto-mounting and both examples keep working at every width — only the
CSS rearranges.
