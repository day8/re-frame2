# `examples/_shared/` - shared example assets

This directory is the canonical source for the example catalogue's visual
assets. All example pages reference the shared favicon and PNG social card;
all except TodoMVC also load the shared stylesheet. TodoMVC keeps its official
stylesheets and declares that single exception in
`examples/scripts/examples-asset-manifest.cjs`.

## Ownership

- This directory owns the shared CSS, favicon, and social-card source art.
- `examples/scripts/examples-asset-manifest.cjs` owns per-page asset exceptions
  and any extra assets that a page needs.
- `examples/scripts/examples-staging.cjs` copies this tree beside each staged
  `index.html`.
- `examples/scripts/check-examples-assets.cjs` enforces page references, the
  manifest exceptions, shared-tree integrity, and the CSS accessibility and
  responsive contracts.

Pages use stable relative URLs such as `_shared/css/style.css`. Staging can
therefore place the same `_shared` tree beside every output page regardless of
that example's source or build directory.

## Visual identity

The catalogue deliberately uses one substrate-neutral identity. Substrate
differences belong in the selector and example code, not in competing palettes.

| Role | Choice |
| --- | --- |
| Typography | Inter/system UI for text; JetBrains Mono/system mono for code |
| Palette | paper `#F7F3EC`, ink `#1A1814`, amber `#C8741A` |
| Atmosphere | subtle radial highlights fixed to the viewport |

The font names are preferences only. The stylesheet loads no remote fonts and
falls back to local system families, so examples do not make third-party font
requests and remain usable offline.

## Files

- `css/style.css` defines the shared `--ex-*` tokens and visual rules. Pages
  link this file; it imports `structure.css`.
- `css/structure.css` defines reusable geometry and the responsive inline-Xray
  shell. It is not linked directly.
- `img/favicon.svg` is the shared `r2` favicon and ships as SVG.
- `img/og.png` is the 1200x630 social-preview asset referenced by every page.
- `img/og.svg` is the editable source for `og.png`, not a page asset. Re-export
  the PNG at exactly 1200x630 after changing the source art. Its colour literals
  intentionally mirror the `--ex-*` tokens in `style.css`.

## Inline Xray shell

The `counter` and `flows` examples mount Xray beside the app with the same DOM
contract:

```html
<div class="rf2-testbed-shell">
  <main id="app"></main>
  <aside data-rf-xray-host></aside>
</div>
```

`structure.css` lays this out as two columns on wider viewports, with the host
width controlled by `--rf-xray-inline-width` (560px by default). At 900px and
below it stacks the host beneath the app, removes the host's fixed minimum
width, and caps its height so the panel scrolls internally. These rules only
change layout; Xray's host selector and mount contract remain unchanged.
