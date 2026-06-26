# `examples/_shared/` — shared examples design system

This directory holds the assets every example shares: one stylesheet,
one favicon, one social card. Every example `index.html` links the same
three, on every substrate (Reagent, Reagent Slim, UIx, Helix).

Keeping them here means there's only one copy. Change the palette in this
directory and the whole catalogue re-skins — no per-example copy drifts
out of sync.

Examples build into many different output folders, yet share one asset
directory. The trick: every page references its assets at the *same
relative path* — `_shared/css/style.css`, never an absolute URL. So
whatever stages an example just drops a copy of this tree next to the
staged page.

## Visual identity

Every page wears the same skin: one typography stack, one palette, one
stylesheet. This is deliberate. The obvious alternative — give each
substrate its own colours, so you can tell a Reagent page from a UIx page
at a glance — is a trap. We want you to notice the *code*, not the
styling.

Substrate gets announced where it belongs: in the substrate selector, and
in the substrate-specific examples themselves. The look stays neutral. So
when two examples look identical, that's the point — their UIs really are
the same, give or take the rendering layer.

| Mood              | Editorial Warm — established, refined, magazine-leaning  |
| ----------------- | -------------------------------------------------------- |
| Typography pair   | Inter (UI + body) + JetBrains Mono (code, mono labels)   |
| Palette           | warm paper bg #F7F3EC / deep ink #1A1814 / amber #C8741A |
| Atmosphere        | paper-grain radial gradients fixed to the viewport       |

The fonts echo the rest of the project: Xray uses Inter + JBM, Story uses
IBM Plex on a similarly light surface. So an example, a dev tool, and the
docs open side by side read as one family, not three unrelated apps.

**No remote fonts.** The stylesheet *names* Inter and JetBrains Mono as
its first-preference families, but it loads **no** web fonts to back them
up — no `@import` of Google Fonts or any other host. The font stacks fall
through to their declared system fallbacks: `system-ui` / `Segoe UI` /
`-apple-system` for the UI, `ui-monospace` / `SF Mono` / `Menlo` for the
mono.

So a staged example makes **zero** third-party network requests just to
style itself. That matters beyond tidiness. Offline runs, firewalled CI,
and privacy-sensitive local demos all render the same. And a screenshot
taken today matches one taken next year, because nothing is fetched from
a server that might have changed.

## Files

Five files. Most are plain; only the social card needs explaining.

- `css/style.css` — the shared design system itself, linked by every
  `index.html`. Imports `structure.css`.
- `css/structure.css` — the substrate-agnostic structural baseline:
  form geometry, grid layout, the max-widths. This is about *shape*, not
  brand — that's why it's split out from `style.css`.
- `img/favicon.svg` — the shared favicon (warm-slate with an amber
  accent). Browsers render SVG favicons fine, so it ships as-is, with no
  raster step.
- `img/og.png` — the shared Open Graph preview card, a 1200×630 raster.
  Every `index.html` references this one. It has to be a raster because
  link-preview scrapers (Facebook / X / LinkedIn / Slack / Discord) won't
  render an SVG `og:image` — so the social card must be a PNG/JPG to show
  up at all.
- `img/og.svg` — the editable SOURCE ART behind that card; no page
  references it. When the design changes, re-export `og.png` from this at
  *exactly* 1200×630. Its colour literals mirror the `--ex-*` tokens in
  `style.css`, so the source and the stylesheet stay in sync — the social
  card inherits the same palette, and the same accessibility decisions,
  as the rest of the catalogue.

## Responsive Xray-host shell

Two examples (`counter`, `flows`) run Xray *inline*, side by side with the
app. That raises a layout problem, and it's worth solving once in the
shared CSS instead of separately in each example. Both wrap their app +
Xray panel in a `.rf2-testbed-shell`:

```html
<div class="rf2-testbed-shell">
  <main id="app"></main>
  <aside data-rf-xray-host></aside>
</div>
```

On a desktop this is a two-column flex — app on the left, the inline
Xray host on the right at `--rf-xray-inline-width` (560px by default).
That breaks on a phone. The host is `flex-shrink: 0` with a 320px
`min-width`, so it won't give up space; together with a usable app column,
the layout needs roughly 624px before any app content can appear. Below
that, the pages declare a responsive viewport but the layout still runs
off the side of the screen.

So `structure.css` **stacks** the shell below a 900px breakpoint. The two
columns become two rows — app on top, Xray host underneath. The host
drops its fixed width and min-width and goes full-bleed, and its height is
capped (`max-height: 60vh` with internal scroll) so the panel can't grow
tall enough to push the app off-screen.

None of this touches the host/app DOM contract. The shape Xray looks for
(`.rf2-testbed-shell > #app` plus `[data-rf-xray-host]`) is **unchanged**,
so auto-mounting and both examples keep working at every width — only the
CSS rearranges.
