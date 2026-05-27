# Story — Tutorial: Embed-mode + variant share URLs (rf2-dafym)

> A starter recipe for embedding a Story variant in a docs site,
> README, or any third-party page as a clean iframe. Walks the
> `variant-share-url` builder, the `?embed=1` chrome-hide flag, and
> the three URL surfaces a reader generating embed code consults.
> Per rf2-ymnfx Issue B there is no separate Share button or QR
> popover — the variant URL is the browser's live address-bar URL.

## What "embed mode" buys you

Story's chrome — sidebar, RHS Xray embed, toolbar — is great for
authoring, but distracting when you want to drop a single variant
into a docs page. The `?embed=1` query string elides the chrome and
leaves the variant canvas alone, full-width, ready to iframe.

Side-by-side:

| Mode | Sidebar | RHS Xray | Toolbar | Canvas |
|---|---|---|---|---|
| Full shell (no flag) | visible | visible | visible | inset |
| `?embed=1` (embed mode) | hidden | hidden | hidden | full width |

The flag is hydrated once at shell mount (per
[`API.md`](API.md) §URL surfaces) and never persisted to
`localStorage`. A reader who clicks through to the full shell from
an embedded view drops the flag at the address bar.

## The three URL surfaces, briefly

Story carries three URL surfaces (per
[`API.md`](API.md) §URL surfaces). Knowing which one to reach for
is the first step in writing embed code:

| Surface | Source of truth | Encodes | Use case |
|---|---|---|---|
| `variant-share-url` | Args you pass in | Variant id + active modes + cell-overrides + substrate | Embed iframes; chat / bug-report links; the browser's live address bar (via `url-state` pushState) |
| `url-from-state` | Live shell state | Workspace, mode tab, viewport, background, tag filter | Browser address bar during interactive use |
| `embed-flag-from-current-url` | Current `?embed=1` query param | The `:embed?` chrome-state flag | The embed-mode toggle (this tutorial) |

The recipe below stitches `variant-share-url` together with the
`?embed=1` flag to produce iframe-ready URLs.

## Audience and scope

This recipe is for someone publishing docs, blog posts, READMEs, or
any third-party page that wants to embed a Story variant as an
iframe. The recipe covers:

- Building the variant URL via `variant-share-url`.
- Adding `?embed=1` for chrome-free presentation.
- The iframe-attribute shape Story expects.
- The hash-route shape (sharable across machines).
- Common pitfalls and gotchas.

What this recipe is **not**: a normative spec page. The normative
contract lives in [`API.md`](API.md) §URL surfaces (rf2-zex19);
this is the tutorial-shaped on-ramp.

What this recipe **no longer covers**: the per-variant Share button +
QR popover that lived on each variant's title row up to v1. The
affordance was retired in rf2-ymnfx Issue B because the share URL is
already the browser's live address bar — Cmd-L / Cmd-A / Cmd-C
copies it without a separate UI surface. Embed authors call
`story/variant-share-url` directly (or read `window.location.href`
on the live shell) and build their iframe URLs from that.

## Building a sharable variant URL

`variant-share-url` is a pure URL builder — JVM-portable, CLJS-
portable, no DOM access. Pass it a variant id and (optionally) the
modes / overrides / substrate to encode.

```clojure
(require '[re-frame.story :as story])

(story/variant-share-url :story.counter/at-five)
;; => "?variant=%3Astory.counter%2Fat-five"

(story/variant-share-url :story.counter/at-five
  "https://myproject.day8.com.au/stories/"
  {:active-modes #{:Mode.app/dark-desktop}
   :cell-overrides {:label "Custom label"}
   :substrate :reagent})
;; => "https://myproject.day8.com.au/stories/?variant=%3Astory.counter%2Fat-five&modes=%3AMode.app%2Fdark-desktop&overrides=...&substrate=reagent"
```

The encoder:

- URL-encodes every keyword (the leading `:` becomes `%3A`).
- Omits empty parts (no `modes`, no `modes=` param in the output).
- Preserves hash routes — if `base-url` ends in `#/stories/foo`,
  params land *before* the hash so they remain readable via
  `window.location.search`.

## Adding the embed flag

For an embed iframe, append `&embed=1` (or `?embed=1` if the URL
has no other query params yet) to the share URL:

```js
// In your docs site's build step or template:
function embedUrl(baseShareUrl) {
  const u = new URL(baseShareUrl);
  u.searchParams.set('embed', '1');
  return u.toString();
}

const shareUrl = "https://myproject.day8.com.au/stories/?variant=%3Astory.counter%2Fat-five";
const iframeSrc = embedUrl(shareUrl);
// => "https://myproject.day8.com.au/stories/?variant=%3Astory.counter%2Fat-five&embed=1"
```

The flag is read once by `embed-flag-from-current-url` at shell
mount and written to `[:chrome-visibility :embed?]` on the
shell-state atom. Every chrome pane's visibility resolver gates on
that slot — sidebar, toolbar, RHS Xray embed all drop out of the
render tree, leaving the canvas alone.

## The iframe

Drop the embed URL into an `<iframe>` in your docs site:

```html
<iframe
  src="https://myproject.day8.com.au/stories/?variant=%3Astory.counter%2Fat-five&embed=1"
  title="Counter at five — Story variant"
  loading="lazy"
  width="100%"
  height="320"
  style="border: 0;"></iframe>
```

Notes on the attributes:

- **`loading="lazy"`** — Story bundles aren't tiny; deferring the
  fetch until the iframe scrolls into view keeps docs-page paint
  budget intact.
- **`title`** — accessible name for the iframe. Pick something
  meaningful per embed.
- **`width="100%"` + fixed `height`** — Story's canvas doesn't
  auto-resize the iframe; you size it explicitly. For variable-
  height canvases consider a fixed-aspect-ratio wrapper or the
  [`iframe-resizer`](https://github.com/davidjbradshaw/iframe-resizer)
  npm package (third-party; Story doesn't ship a postMessage
  protocol for it).
- **`border: 0`** — the variant canvas already carries its own
  visual identity (per [`016-Design-Tokens.md`](016-Design-Tokens.md)).
  An iframe border is double chrome.

## Where the share URL lives

The variant URL is the browser's live address bar URL. The chrome's
[`re-frame.story.ui.url-state`](../src/re_frame/story/ui/url_state.cljc)
wires `pushState` / `popstate` against the
`re-frame.story.share/variant-share-url` encoder so the address bar
always reflects the focused variant + active modes + cell-overrides
+ chrome state. Cmd-L / Cmd-A / Cmd-C copies it; pasting into chat or
a docs page links straight back.

There is no separate Share button or QR popover. The v1 affordance
that lived on each variant's title row was retired in rf2-ymnfx
Issue B (2026-05-27) — the popover added no capability beyond what
the browser's address bar already supplied, and the QR code was a
phone-handoff convenience that AirDrop / "Send to phone" / and any
of a dozen browser-native flows already cover. The earlier
`qrcode-generator` (npm, MIT, ~52 KB) local SVG encoder that
replaced the third-party QR-image service (`rf2-20w5i`, security
audit, 2026-05-14) retired alongside the popover.

For programmatic embed-URL generation, call `story/variant-share-url`
directly — see the worked example below.

## How embed mode and the chrome interact

The embed flag is **one-shot at mount**. The chrome-visibility
resolver consults `[:chrome-visibility :embed?]` (boolean) on the
shell-state atom; the URL parser writes that slot once and never
re-reads. This matters for two scenarios:

| Scenario | Behaviour |
|---|---|
| Reader clicks a variant in the sidebar of an embedded shell | No-op — the sidebar isn't rendered. They follow the hash route. |
| Reader navigates to a different variant via `window.history.pushState` (e.g. from a docs-page link) | The shell re-resolves the variant. The `:embed?` flag persists for the lifetime of the page (no localStorage round-trip, but no reset either). |
| Reader reloads the iframe | `embed-flag-from-current-url` re-reads the query param. If the URL still has `&embed=1`, embed mode persists. |
| Reader copies the iframe URL and opens it in a top-level tab | Embed mode persists in the new tab too. To drop it, edit the URL to remove `embed=1`. |

The flag is **not sticky** in the sense that it doesn't persist to
`localStorage` — the URL is the source of truth. This avoids the
"every Story shell on this machine is in embed mode forever" bug
that a localStorage-backed flag would carry.

## Hash-route shape (for content-addressed embedding)

If your shell is mounted under a hash route (`#/stories/...`),
`variant-share-url` puts params *before* the hash:

```
https://myproject.day8.com.au/stories/?variant=%3Astory.counter%2Fat-five&embed=1#/stories/:story.counter/at-five
```

Both halves are read at mount:

- `window.location.search` carries the query params (variant id,
  modes, overrides, substrate, embed flag).
- `window.location.hash` carries the canonical route for the
  variant (consumed by the chrome's router).

For a base URL with no hash route, the query params are
sufficient — Story's URL parser handles both shapes.

## Production-elided builds — embedding the static export

For embeds that ship to production docs sites, use
`npm run story:build` (per
[`013-Static-Build.md`](013-Static-Build.md)) to produce a
self-contained static HTML directory. The output:

- Bundles the Story shell at `:advanced` optimisation.
- Inlines the variant registry.
- Carries the QR encoder + a11y opt-in surfaces (no
  third-party fetches at runtime).
- Drops the dev-time recorder, hot-reload, and live-validation
  surfaces.

Deploy the static directory to GitHub Pages, Netlify, Vercel, or
raw S3, then point your iframes at the deployed URL. The embed
flag and share-URL builder both ride the static build — no
authoring-only dependency for end users.

## Common pitfalls

- **Forgetting to URL-encode the variant id when assembling URLs
  by hand.** Variant ids like `:story.auth.login-form/happy-path`
  carry both `:` and `/`; `variant-share-url` handles them, but a
  hand-rolled URL string from a template will mis-parse. Use the
  builder.
- **Caching iframes on a CDN with stale variant bundles.** A docs
  site that embeds a published Story shell must cache-bust the
  iframe `src` when the underlying bundle changes — otherwise
  CDN-cached iframes serve old variants. Versioned subdirectories
  (`/stories/v1.2.3/`) are the safest pattern.
- **Embedding from a dev shell into a production docs site.**
  Don't iframe `http://localhost:8080` into your published docs.
  Use the `story:build` output or a deployed shell.
- **Mixing `?embed=1` with full-shell navigation.** Once a reader
  clicks into the sidebar (which is hidden under `?embed=1`) the
  flag has no UI to surface its state. If you want to offer a
  "see this variant in the full shell" affordance, link out to a
  separate `_blank` tab with the same URL minus the embed flag.
- **Iframe `sandbox` attribute.** Story uses `localStorage` (for
  the help-overlay seen-flag, RHS panel preferences, and a11y
  CDN opt-in). A fully-sandboxed iframe will break these
  surfaces. If you must sandbox, allow at least
  `allow-scripts allow-same-origin`.
- **CSP `frame-ancestors`.** If you publish the Story shell with
  a Content-Security-Policy header, the `frame-ancestors`
  directive must include the embedding origin or the iframe will
  refuse to render. The static-build output ships no CSP by
  default; if you add one, allow your docs-site origin.

## End-to-end worked example

A docs page embeds the counter's `:at-five` variant in dark mode:

```html
<!-- In your docs site's markdown or HTML template: -->
<figure>
  <iframe
    src="https://myproject.day8.com.au/stories/?variant=%3Astory.counter%2Fat-five&modes=%3AMode.app%2Fdark-desktop&embed=1#/stories/:story.counter/at-five"
    title="Counter at five (dark mode) — Story variant"
    loading="lazy"
    width="100%"
    height="320"
    style="border: 0;"></iframe>
  <figcaption>The counter at 5, dark mode.</figcaption>
</figure>
```

To produce the URL programmatically rather than hand-rolling it:

```clojure
(require '[re-frame.story :as story])

(defn embed-iframe-src [variant-id base-url & {:keys [modes overrides substrate]}]
  (str (story/variant-share-url variant-id base-url
         (cond-> {}
           modes      (assoc :active-modes modes)
           overrides  (assoc :cell-overrides overrides)
           substrate  (assoc :substrate substrate)))
       "&embed=1"))

(embed-iframe-src :story.counter/at-five
                  "https://myproject.day8.com.au/stories/"
                  :modes #{:Mode.app/dark-desktop})
;; => "https://myproject.day8.com.au/stories/?variant=...&modes=...&embed=1"
```

The same builder runs JVM-side, CLJS-side, or in your docs site's
build step.

## Cross-references

- [`API.md`](API.md) §URL surfaces — normative cluster table for
  the three URL surfaces.
- [`005-SOTA-Features.md`](005-SOTA-Features.md) §Share URL
  (retired QR popover) — the variant URL contract + the rf2-ymnfx
  Issue B retirement rationale.
- [`005-SOTA-Features.md`](005-SOTA-Features.md) §Third-party
  network egress — the rf2-20w5i security-audit lineage.
- [`013-Static-Build.md`](013-Static-Build.md) — `story:build` for
  production-deployable shells.
- [`014-Chrome-Features.md`](014-Chrome-Features.md) §URL state —
  hash-route hydration + the chrome-visibility model.
- [`016-Design-Tokens.md`](016-Design-Tokens.md) — chrome identity
  tokens; what the canvas carries when chrome is hidden.
- [`Tutorial-Playwright.md`](Tutorial-Playwright.md) — the
  companion recipe for e2e probing of Story variants.
