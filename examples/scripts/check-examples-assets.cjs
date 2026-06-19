#!/usr/bin/env node
/*
 * `check-examples-assets` — STATIC asset-contract gate over every example
 * index.html (rf2-8r0mj.2 + rf2-8r0mj.3 + rf2-emvyd).
 *
 * The gap this closes
 * -------------------
 * examples/_shared/ ships a shared design system (stylesheet + favicon +
 * Open Graph card) that examples/_shared/README.md declares is "consumed by
 * every example index.html". The only automated gate that touches _shared
 * is the adapter-smoke harness (npm run test:examples), and that harness
 * compiles + serves ONLY the three adapter testbeds at
 * implementation/adapters/<name>/testbed/. Those testbed pages link NONE of
 * _shared, so a broken shared stylesheet, a missing/renamed _shared asset, a
 * bad @import target, or a stageShared regression all pass test:examples —
 * the staged copy is never loaded by any page the runner navigates to. The
 * shared design system had ZERO automated coverage.
 *
 * On top of that, the "every example page references the shared assets"
 * contract was review-only: nothing failed when a non-exempt page omitted an
 * asset, and the one real exception (TodoMVC uses the official TodoMVC CSS
 * packages instead of the shared stylesheet) was not encoded as an explicit
 * allowlisted exception anywhere.
 *
 * What this gate does (STATIC — no browser, no Playwright)
 * -------------------------------------------------------
 * It walks every tracked example index.html and, for each:
 *
 *   1. RESOLVES every local asset reference (link href, script src,
 *      og:image meta, and transitively the @import targets inside any
 *      referenced local CSS) to a real file in the repo source tree. A
 *      reference to a missing/renamed/typo'd local asset fails the gate.
 *      The build output main.js (produced by shadow-cljs, not source) is
 *      skipped.
 *
 *      DIRECT-HTML NETWORK REFS are NOT skipped (rf2-bf4vdy). An
 *      asset-bearing external reference in the page — a `<script src>`, an
 *      asset `<link href>` (stylesheet / preload / modulepreload / icon /
 *      manifest / prefetch), an `<img>/<source>/<video>/<audio> src`, or a
 *      LOCAL-staged-but-external og:image — pulls a third-party CDN
 *      script / hosted stylesheet / hosted font / external media into every
 *      staged example at load time. That is the same reproducibility /
 *      offline-dev / hidden-dependency regression the external-CSS-@import
 *      policy already guards (rf2-vou5mm / rf2-byf7y), so the gate REJECTS
 *      any asset-bearing direct-HTML network ref unless its exact URL is
 *      allowlisted (with a reason) in EXTERNAL_HTML_REF_ALLOWLIST below.
 *      Pure NAVIGATION / metadata refs (anchors, in-page #fragments,
 *      mailto:/tel: links, and inlined data: URIs) are NOT network asset
 *      fetches and stay exempt. The allowlist starts EMPTY: no shipped
 *      example page loads a remote asset, so the contract is fail-closed.
 *
 *      EXTERNAL CSS @import is NOT skipped (rf2-vou5mm). An external
 *      `@import url(https://…)` (or a protocol-relative `@import url(//…)`)
 *      inside any scanned CSS pulls a third-party network dependency into
 *      every staged example at load time — exactly the Google-Fonts
 *      regression rf2-byf7y removed. So the gate REJECTS any external CSS
 *      @import unless its URL is explicitly allowlisted (with a reason) in
 *      EXTERNAL_IMPORT_ALLOWLIST below. The allowlist starts EMPTY: the
 *      shared design system declares zero remote fonts / hosts (see
 *      examples/_shared/README.md §Visual identity), so the contract is
 *      fail-closed — a re-introduced external @import turns the gate RED.
 *
 *      REMOTE CSS url() FETCHES are NOT skipped either (rf2-o18ava). A
 *      stylesheet can pull a third-party font / image without an @import — a
 *      `@font-face { src: url(https://…) }`, a `background-image: url(//cdn…)`,
 *      or a mask / cursor / border-image remote `url(...)` fires the same
 *      load-time third-party request the @import policy guards. So the gate
 *      ALSO REJECTS any unallowlisted network `url(...)` (http(s) / `//host`)
 *      in scanned CSS, reusing EXTERNAL_IMPORT_ALLOWLIST. `data:` URIs (inlined,
 *      no request) and same-document `url(#fragment)` paint refs stay exempt.
 *
 *      Staging-aware resolution: a page references _shared assets at the
 *      relative path `_shared/...`, but in the SOURCE tree _shared lives
 *      ONCE at examples/_shared/ (not next to each page) — the orchestrator
 *      copies it into every page's output dir at stage time. So a
 *      `_shared/...` reference is resolved against the canonical
 *      examples/_shared/ source (exactly what gets staged), while a
 *      non-_shared sibling reference (e.g. TodoMVC's base.css) is resolved
 *      relative to the page's own directory.
 *
 *   2. Asserts the REQUIRED _shared asset contract: every example must
 *      reference _shared/img/favicon.svg, _shared/img/og.png, and
 *      _shared/css/style.css — UNLESS the page is allowlisted out of a
 *      specific asset (see ALLOWLIST). The TodoMVC stylesheet opt-out is
 *      encoded there: it is exempt from style.css (it links the vendored
 *      TodoMVC base.css + index.css) but STILL required to carry the shared
 *      favicon + OG card.
 *
 *   3. Asserts the social-preview (og:image) target is a RASTER, never an SVG:
 *      link-preview scrapers (Facebook / X / LinkedIn / Slack / Discord) do
 *      not render an SVG og:image, so an SVG card silently produces no large
 *      preview while a pure "the file exists" check stays green. The .svg is
 *      kept only as editable source art. (rf2-lr4am3)
 *
 *   4. Asserts the _shared source tree itself is intact: the required
 *      _shared files exist on disk (incl. the og.png raster + its og.svg
 *      source art) and structure.css remains reachable from style.css's
 *      @import.
 *
 * This is NOT a per-example *.spec.cjs — examples/ stays test-free
 * (rf2-8cevm). It is a pure static scanner, wired into the always-run
 * `test:script-policy` gate (see implementation/scripts/
 * check-examples-assets.test.cjs) so a missing/broken _shared asset turns
 * that gate RED in CI without a new .github workflow job.
 *
 * CLI
 * ---
 *   node examples/scripts/check-examples-assets.cjs           # scan, exit 0/1
 *   node examples/scripts/check-examples-assets.cjs --list    # print findings, exit 0
 *
 * The pure scan logic is exported for check-examples-assets.test.cjs, which
 * pins the contract (required-asset detection, @import resolution, the
 * TodoMVC allowlist) AND gives the gate teeth by running the real scan over
 * the repo.
 */

'use strict';

const fs = require('fs');
const path = require('path');

// __dirname is <repo>/examples/scripts. REPO_ROOT is <repo>.
const REPO_ROOT = path.resolve(__dirname, '..', '..');
const EXAMPLES_ROOT = path.join(REPO_ROOT, 'examples');
const SHARED_ROOT = path.join(EXAMPLES_ROOT, '_shared');

// The shared design-system assets every example page is expected to carry,
// keyed by the canonical relative href every index.html uses. `required`
// marks the universal contract; a page can be allowlisted out of an
// individual asset via ALLOWLIST below.
const REQUIRED_SHARED_ASSETS = [
  '_shared/img/favicon.svg',
  // The social-preview (og:image) target MUST be a raster: link-preview
  // scrapers (Facebook / X / LinkedIn / Slack / Discord) do not render an SVG
  // og:image, so an SVG card silently produces no large preview while this
  // gate stays green. The .svg is kept as editable SOURCE ART only.
  '_shared/img/og.png',
  '_shared/css/style.css',
];

// The set of href extensions the social-preview (og:image) asset is allowed to
// use. SVG is deliberately excluded — see REQUIRED_SHARED_ASSETS above.
const SOCIAL_PREVIEW_RASTER_EXTS = new Set(['.png', '.jpg', '.jpeg', '.webp', '.gif']);
const SOCIAL_PREVIEW_REQUIRED = '_shared/img/og.png';

// ---------------------------------------------------------------------------
// Allowlisted exceptions — the ONE encoded place that names a page's opt-out
// from a required shared asset, with the reason. A reviewer (human or the
// gate) reads this to know an omission is intentional, not a regression.
//
// `assetExemptions` lists the required-asset hrefs a page is allowed to
// OMIT. `localAssets` lists the page's NON-_shared local references that are
// staged from somewhere other than the repo source tree (so the resolver
// must not flag them as missing files on disk).
// ---------------------------------------------------------------------------
const ALLOWLIST = {
  // TodoMVC links the official TodoMVC CSS packages (todomvc-common's
  // base.css + todomvc-app-css's index.css, pinned in
  // implementation/package.json and staged from node_modules/) instead of
  // the shared stylesheet — see examples/reagent/todomvc/README.md
  // §Official assets. It is therefore exempt from _shared/css/style.css but
  // STILL required to carry the shared favicon + OG card, and its two
  // vendored CSS links are not repo-source files (they're npm-staged), so
  // they're declared here rather than flagged as missing.
  'examples/reagent/todomvc/index.html': {
    reason:
      'TodoMVC uses the official TodoMVC CSS packages (base.css + ' +
      'index.css, staged from node_modules/) instead of the shared ' +
      'stylesheet — see examples/reagent/todomvc/README.md §Official assets.',
    assetExemptions: ['_shared/css/style.css'],
    localAssets: ['base.css', 'index.css'],
  },
};

// ---------------------------------------------------------------------------
// External CSS network allowlist (rf2-vou5mm + rf2-o18ava) — the ONE encoded
// place that names an external CSS network reference the scanner is permitted to
// see inside scanned CSS, each with a reason. It covers BOTH an external
// `@import url(https://…|http://…|//…)` AND a remote `url(...)` fetch in a
// declaration (`@font-face src`, `background-image`, mask, cursor, …). Any
// external CSS network ref NOT listed here fails the gate.
//
// Why this exists: an external CSS @import (e.g. Google Fonts) OR a remote
// `url()` font/image makes every staged example fire a third-party network
// request at load time — the exact regression rf2-byf7y removed. The shared
// design system deliberately loads NO remote fonts/hosts (examples/_shared/
// README.md §Visual identity), so this allowlist starts EMPTY and the contract
// is fail-closed: re-introducing an external @import / url() without an entry
// here turns the gate RED.
//
// Shape mirrors ALLOWLIST: key = the external URL with any ?query/#hash
// STRIPPED (the scanner normalises @import targets that way before the lookup);
// value = { reason } explaining why the remote dependency is intentional.
// ---------------------------------------------------------------------------
const EXTERNAL_IMPORT_ALLOWLIST = {
  // (empty) — no example CSS may pull a remote stylesheet/font. Add an entry
  // here ONLY with a deliberate decision and a reason. NB: the key is the
  // query-stripped URL — e.g. `@import url(https://x/css2?family=Inter)` is
  // keyed as 'https://x/css2':
  //   'https://fonts.googleapis.com/css2': {
  //     reason: 'why this remote font is intentionally loaded',
  //   },
};

// ---------------------------------------------------------------------------
// Direct-HTML external network-ref allowlist (rf2-bf4vdy) — the ONE encoded
// place that names an asset-bearing external reference (a `<script src>`, an
// asset `<link href>`, an `<img>/<source>/<video>/<audio> src`, or an external
// og:image) the scanner is permitted to see directly in an example page, each
// with a reason. Any asset-bearing direct-HTML network ref NOT listed here
// fails the gate.
//
// Why this exists: a direct external asset ref (a CDN <script>, a hosted
// stylesheet/font <link>, an external image/media src) makes every staged
// example fire a third-party network request at load time — the same
// reproducibility / offline-dev / hidden-dependency regression the external-CSS
// @import policy already guards (rf2-vou5mm / rf2-byf7y), just on the HTML side
// rather than inside a stylesheet. The shared design system ships ZERO remote
// assets, so this allowlist starts EMPTY and the contract is fail-closed.
//
// Shape mirrors EXTERNAL_IMPORT_ALLOWLIST: key = the external URL with any
// ?query/#hash STRIPPED (the scanner normalises HTML refs that way before the
// lookup); value = { reason } explaining why the remote dependency is
// intentional. NB: only http(s) and the protocol-relative `//host/...` form
// are gated — `data:` URIs are inlined (not a network request) and pure
// navigation refs (anchors, #fragments, mailto:/tel:) are never asset fetches.
// ---------------------------------------------------------------------------
const EXTERNAL_HTML_REF_ALLOWLIST = {
  // (empty) — no example page may load a remote script/stylesheet/font/image.
  // Add an entry here ONLY with a deliberate decision and a reason. NB: the key
  // is the query-stripped URL — e.g. `<script src=https://x/sdk.js?v=2>` is
  // keyed as 'https://x/sdk.js':
  //   'https://unpkg.com/some-lib/dist/lib.js': {
  //     reason: 'why this remote script is intentionally loaded',
  //   },
};

// Build output produced by shadow-cljs at stage time — never a repo-source
// file, so the resolver skips it (every index.html ships <script src=main.js>).
const BUILD_OUTPUTS = new Set(['main.js']);

// ---------------------------------------------------------------------------
// Example host-page enumeration. Walk the examples/ tree for the HTML host
// pages every staged example serves, skipping the _shared tree itself (it
// holds assets, not example pages) and node_modules / build-output dirs.
//
// A "host page" is the page's own `index.html` OR an auxiliary showcase host
// page named `<something>.index.html` — e.g. the Story showcase trios
// `login/stories.index.html` and `nine_states/stories.index.html`, which mount
// the example inside the Story shell + Xray and are held to the SAME shared-
// asset contract as their sibling index.html (favicon + OG card + style.css).
// Enumerating the `*.index.html` shape (rf2-x48bp4) gives the gate teeth over
// those auxiliary pages, so a future edit cannot silently drop a required
// shared asset from a showcase host page and stay green. (Before rf2-x48bp4
// only the bare `index.html` name was enumerated, so the showcase pages carried
// the assets but were never enforced.)
// ---------------------------------------------------------------------------

// True for the HTML host-page filenames the asset contract enumerates: the
// bare `index.html` and any `<prefix>.index.html` auxiliary showcase host page
// (e.g. `stories.index.html`).
function isExampleHostPage(name) {
  return name === 'index.html' || name.endsWith('.index.html');
}

function listExampleIndexHtml(root = EXAMPLES_ROOT) {
  const out = [];
  const stack = [root];
  while (stack.length > 0) {
    const dir = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (entry.name === 'node_modules' || entry.name === '_shared') continue;
        stack.push(full);
      } else if (entry.isFile() && isExampleHostPage(entry.name)) {
        out.push(full);
      }
    }
  }
  return out.sort();
}

// ---------------------------------------------------------------------------
// Reference extraction. We hand-roll focused regexes rather than pull in an
// HTML/CSS parser — the only shapes we read are attribute-quoted hrefs/srcs,
// the og:image meta content, and CSS @import url(...) targets.
// ---------------------------------------------------------------------------

// True for references the resolver does not check on disk: absolute URLs
// (http://, https://, protocol-relative //), data: URIs, and in-page
// fragments / mailto etc.
function isExternalRef(ref) {
  return (
    /^[a-z][a-z0-9+.-]*:/i.test(ref) || // any scheme: http:, https:, data:, mailto:
    ref.startsWith('//') ||             // protocol-relative
    ref.startsWith('#')                 // in-page fragment
  );
}

// True only for refs that trigger a third-party NETWORK fetch at load time:
// http(s):// and the protocol-relative `//host/...` form. A `data:` URI is
// inlined (no request), and `#fragment` / `mailto:` / `tel:` are pure
// navigation — none of those is a network asset dependency. Used to gate the
// external CSS @import policy (rf2-vou5mm) AND the direct-HTML asset-ref policy
// (rf2-bf4vdy) with one shared notion of "is this a remote fetch".
function isNetworkRef(ref) {
  return /^https?:/i.test(ref) || ref.startsWith('//');
}

// The asset `<link rel="...">` tokens whose href triggers a load-time fetch
// (a remote one would be a third-party network dependency). `rel` values that
// are pure navigation/metadata (canonical, alternate, author, license, …) are
// NOT asset-bearing and are not gated. Multi-token rels (e.g. `rel="icon
// shortcut"`) match if ANY token is asset-bearing.
const ASSET_LINK_RELS = new Set([
  'stylesheet',
  'preload',
  'modulepreload',
  'prefetch',
  'preconnect',
  'dns-prefetch',
  'icon',
  'shortcut', // legacy `rel="shortcut icon"`
  'apple-touch-icon',
  'mask-icon',
  'manifest',
]);

// Extract every ASSET-BEARING reference from an index.html's source, TAGGED
// with the element/attribute it came from, so the direct-HTML network policy
// (rf2-bf4vdy) can distinguish a remote asset fetch (rejected) from harmless
// navigation/metadata (exempt). Returns [{ ref, source }] — `ref` is the
// query/hash-stripped target, `source` a human-readable origin (e.g.
// `<script src>`, `<link rel=stylesheet href>`, `<img src>`, `og:image`).
// Order-preserving; de-duplicated on (ref, source).
//
// What counts as asset-bearing:
//   - <script src=...>                          (always a fetch)
//   - <link rel="<asset rel>" href=...>         (stylesheet/preload/icon/…)
//   - <img|source|video|audio|track src=...>    (media fetch)
//   - <meta property="og:image" content=...>    (social-preview fetch)
// An <a href> / a <link rel="canonical"> / any non-asset element href is pure
// navigation and is intentionally NOT returned.
function extractAssetRefs(html) {
  const out = [];
  const seen = new Set();
  const push = (raw, source) => {
    if (!raw) return;
    const clean = raw.split(/[?#]/)[0].trim();
    if (!clean) return;
    const key = `${source} ${clean}`;
    if (seen.has(key)) return;
    seen.add(key);
    out.push({ ref: clean, source });
  };
  const attr = (tag, name) => {
    const m = tag.match(
      new RegExp(`\\b${name}\\s*=\\s*("([^"]*)"|'([^']*)')`, 'i'),
    );
    return m ? (m[2] != null ? m[2] : m[3]) : null;
  };

  // <script src=...> — always an asset fetch.
  for (const m of html.matchAll(/<script\b[^>]*>/gi)) {
    const src = attr(m[0], 'src');
    if (src) push(src, '<script src>');
  }

  // <link rel="..." href=...> — asset-bearing only for the rels above.
  for (const m of html.matchAll(/<link\b[^>]*>/gi)) {
    const tag = m[0];
    const href = attr(tag, 'href');
    if (!href) continue;
    const rel = (attr(tag, 'rel') || '').toLowerCase().trim();
    const tokens = rel.split(/\s+/).filter(Boolean);
    const isAsset = tokens.some((t) => ASSET_LINK_RELS.has(t));
    if (isAsset) push(href, `<link rel="${rel || '(none)'}" href>`);
  }

  // <img|source|video|audio|track src=...> — media fetch.
  for (const m of html.matchAll(/<(img|source|video|audio|track)\b[^>]*>/gi)) {
    const src = attr(m[0], 'src');
    if (src) push(src, `<${m[1].toLowerCase()} src>`);
  }

  // <meta property="og:image" content=...> — social-preview fetch.
  for (const og of extractOgImageRefs(html)) {
    push(og, 'og:image');
  }

  return out;
}

// Extract every asset reference from an index.html's source: <link href>,
// <script src>, and the og:image <meta content>. Order-preserving,
// de-duplicated. Query/hash suffixes are stripped for on-disk resolution.
function extractHtmlRefs(html) {
  const refs = [];
  const push = (raw) => {
    if (!raw) return;
    const clean = raw.split(/[?#]/)[0].trim();
    if (clean && !refs.includes(clean)) refs.push(clean);
  };
  // href="..." / href='...' on <link> (and any element — anchors are
  // in-page or external and get filtered by isExternalRef downstream).
  for (const m of html.matchAll(/\b(?:href|src)\s*=\s*("([^"]*)"|'([^']*)')/gi)) {
    push(m[2] != null ? m[2] : m[3]);
  }
  // <meta property="og:image" content="...">
  for (const m of html.matchAll(
    /<meta\b[^>]*\bproperty\s*=\s*["']og:image["'][^>]*\bcontent\s*=\s*("([^"]*)"|'([^']*)')[^>]*>/gi,
  )) {
    push(m[2] != null ? m[2] : m[3]);
  }
  return refs;
}

// Extract the og:image content value(s) specifically — the social-preview
// target a link-preview scraper would fetch. Used to enforce the raster
// contract (an SVG og:image renders no preview card). Query/hash stripped.
function extractOgImageRefs(html) {
  const out = [];
  for (const m of html.matchAll(
    /<meta\b[^>]*\bproperty\s*=\s*["']og:image["'][^>]*\bcontent\s*=\s*("([^"]*)"|'([^']*)')[^>]*>/gi,
  )) {
    const raw = m[2] != null ? m[2] : m[3];
    if (!raw) continue;
    const clean = raw.split(/[?#]/)[0].trim();
    if (clean && !out.includes(clean)) out.push(clean);
  }
  return out;
}

// Extract local @import targets from a CSS source. Handles both
// `@import url('x')` / `@import url("x")` / `@import url(x)` and the bare
// `@import 'x'` / `@import "x"` forms. External targets are returned too so
// the caller can filter them uniformly via isExternalRef.
function extractCssImports(css) {
  const out = [];
  const push = (raw) => {
    if (!raw) return;
    const clean = raw.split(/[?#]/)[0].trim();
    if (clean && !out.includes(clean)) out.push(clean);
  };
  for (const m of css.matchAll(
    /@import\s+(?:url\(\s*("([^"]*)"|'([^']*)'|([^)'"]*))\s*\)|("([^"]*)"|'([^']*)'))/gi,
  )) {
    push(m[2] || m[3] || m[4] || m[6] || m[7]);
  }
  return out;
}

// Extract every `url(...)` target a CSS source can fetch at load time —
// `@font-face { src: url(...) }`, `background`/`background-image`,
// `mask`/`mask-image`, `cursor`, `border-image`, `list-style-image`, etc. The
// `@import url(...)` form is intentionally SKIPPED (it is the @import policy's
// job, enforced via extractCssImports above) so a remote @import is reported
// once, not twice. Handles `url("x")` / `url('x')` / bare `url(x)` (with
// surrounding whitespace). Returns the RAW (un-stripped) targets so the caller
// can tell a `url(#fragment)` local paint-ref and a `data:` URI apart from a
// network fetch — query/hash stripping happens at the network-classification
// step, not here, or `url(#grain)` would normalise to the empty string.
// Order-preserving, de-duplicated.
function extractCssUrls(css) {
  const out = [];
  const seen = new Set();
  // Blank out `@import url(...)` occurrences first so they are not re-collected
  // here (extractCssImports already owns the @import contract).
  const body = css.replace(
    /@import\s+url\(\s*(?:"[^"]*"|'[^']*'|[^)'"]*)\s*\)/gi,
    '',
  );
  for (const m of body.matchAll(
    /\burl\(\s*("([^"]*)"|'([^']*)'|([^)'"]+))\s*\)/gi,
  )) {
    const raw = (m[2] != null ? m[2] : m[3] != null ? m[3] : m[4] || '').trim();
    if (!raw) continue;
    if (seen.has(raw)) continue;
    seen.add(raw);
    out.push(raw);
  }
  return out;
}

// ---------------------------------------------------------------------------
// The pure scan. Returns { errors: string[], pages: [...] }; the CLI and the
// test both consume this. Filesystem reads are injected so the test can pin
// behaviour without touching disk; the CLI passes the real fs.
// ---------------------------------------------------------------------------

function readFileSafe(io, p) {
  try {
    return io.readFileSync(p, 'utf8');
  } catch {
    return null;
  }
}

// Read a file as RAW BYTES (a Buffer), never decoded as text — used to inspect
// the og.png signature/dimensions. The CLI passes the real fs (no encoding =>
// Buffer); the synthetic test io returns whatever string was stored, which
// validatePng coerces to a Buffer so a fixture can supply real PNG bytes.
function readBytesSafe(io, p) {
  try {
    return io.readFileSync(p); // no encoding => Buffer for the real fs
  } catch {
    return null;
  }
}

// The 8-byte PNG file signature (89 50 4E 47 0D 0A 1A 0A).
const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

// The canonical social-preview raster dimensions (examples/_shared/README.md
// declares img/og.png is the 1200x630 card).
const OG_PNG_WIDTH = 1200;
const OG_PNG_HEIGHT = 630;

// Validate that `data` is a decodable PNG whose IHDR declares the expected
// dimensions. Returns { ok, width, height, reason }. A spec-conformant PNG
// always opens with the 8-byte signature immediately followed by the IHDR
// chunk (length=13, type='IHDR', then width:uint32be, height:uint32be), so
// reading the dimensions needs no full decoder — just the first 24 bytes.
// Accepts a Buffer or coerces a string fixture to one (latin1 preserves bytes).
function validatePng(data, expectedWidth = OG_PNG_WIDTH, expectedHeight = OG_PNG_HEIGHT) {
  const buf = Buffer.isBuffer(data) ? data : Buffer.from(String(data), 'latin1');
  if (buf.length < 24) {
    return { ok: false, reason: `file is only ${buf.length} byte(s); too short to be a PNG` };
  }
  if (!buf.subarray(0, 8).equals(PNG_SIGNATURE)) {
    return { ok: false, reason: 'missing the 8-byte PNG signature (not a PNG file)' };
  }
  // Bytes 8..16 are the IHDR chunk length (must be 13) + type ('IHDR').
  if (buf.subarray(12, 16).toString('latin1') !== 'IHDR') {
    return { ok: false, reason: "first chunk is not IHDR (malformed PNG header)" };
  }
  const width = buf.readUInt32BE(16);
  const height = buf.readUInt32BE(20);
  if (width !== expectedWidth || height !== expectedHeight) {
    return {
      ok: false,
      width,
      height,
      reason: `dimensions are ${width}x${height}, expected ${expectedWidth}x${expectedHeight}`,
    };
  }
  return { ok: true, width, height };
}

// ---------------------------------------------------------------------------
// WCAG contrast — the shared palette must clear AA for normal text and the
// focus-indicator must clear the 3:1 non-text bar (rf2-febmqu + rf2-mon7tz).
// A small static check over the :root tokens declared in style.css, so a
// regression that re-introduces a sub-AA foreground (or restores the old
// low-alpha amber focus ring) turns the gate RED — examples are teaching
// surfaces and must not model an accessibility-regressed default.
// ---------------------------------------------------------------------------

const WCAG_AA_NORMAL_TEXT = 4.5; // 1.4.3 — normal-size text
const WCAG_NON_TEXT = 3.0; // 1.4.11 — UI component / focus indicator

// Retired shared-palette colour literals that must not reappear in source art
// (rf2-y82dk9). og.svg is the editable master the shipped og.png is exported
// from; its palette literals intentionally mirror the --ex-* CSS tokens. When a
// token is darkened for AA (e.g. --ex-ink-faint #8A8270 → #6E6654, 3.45:1 →
// 5.14:1 on paper, rf2-febmqu) the og.png raster can silently lag behind because
// the existing gate treats it as opaque bytes beyond dimensions/signature. Each
// row names a retired value, its AA-safe replacement, and the reason — a static
// scan over og.svg so re-introducing the stale literal turns the gate RED.
const RETIRED_OG_SOURCE_COLORS = [
  {
    retired: '#8A8270',
    replacement: '#6E6654',
    token: '--ex-ink-faint',
    reason:
      'sub-AA on the shared paper background (3.45:1 < 4.5:1); darkened to ' +
      '#6E6654 (5.14:1) in style.css, rf2-febmqu',
  },
];

function srgbToLinear(channel) {
  const c = channel / 255;
  return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

// Relative luminance of a #rrggbb / #rgb hex colour (WCAG 2.x definition).
function relativeLuminance(hex) {
  let h = hex.replace('#', '').trim();
  if (h.length === 3) h = h.split('').map((c) => c + c).join('');
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  return 0.2126 * srgbToLinear(r) + 0.7152 * srgbToLinear(g) + 0.0722 * srgbToLinear(b);
}

// WCAG contrast ratio between two hex colours (1..21).
function contrastRatio(fgHex, bgHex) {
  const l1 = relativeLuminance(fgHex);
  const l2 = relativeLuminance(bgHex);
  const hi = Math.max(l1, l2);
  const lo = Math.min(l1, l2);
  return (hi + 0.05) / (lo + 0.05);
}

// Parse the `--ex-*: #hex;` custom-property declarations out of a style.css
// source into a { tokenName: '#hex' } map. Only solid hex values are read
// (the contrast checks operate on opaque token pairs).
function parseExTokens(css) {
  const tokens = {};
  for (const m of css.matchAll(/(--ex-[a-z0-9-]+)\s*:\s*(#[0-9a-fA-F]{3,8})\b/g)) {
    tokens[m[1]] = m[2];
  }
  return tokens;
}

// The shared token contrast contract. Each row is a foreground token paired
// with the background token(s) it is actually rendered against in the shipped
// CSS, plus the WCAG floor it must clear. Computed from the parsed --ex-*
// tokens so a palette edit that drops a pair below threshold fails the gate.
// `min` = the contrast floor; `worst` background is whichever the row lists.
function sharedContrastContract(tokens) {
  const surfaces = ['--ex-bg', '--ex-bg-raised', '--ex-bg-sunken'];
  return [
    // Normal text / link / muted / skeleton foregrounds on every paper surface.
    { fg: '--ex-accent-deep', bgs: surfaces, min: WCAG_AA_NORMAL_TEXT, role: 'link / value text' },
    // Warning TEXT foreground. --ex-warn (#C49419) is decorative-fill only
    // (≤2.76:1 as text); the AA-safe text/border token is --ex-warn-deep, and
    // the process_monitor_helix example consumes it (via --hx-amber-deep) for
    // warn tile-value / log-level / active-chip text. The decorative --ex-warn
    // is intentionally NOT listed (it ships only as borders / meter / dot fill).
    { fg: '--ex-warn-deep', bgs: surfaces, min: WCAG_AA_NORMAL_TEXT, role: 'warning text' },
    // --ex-warn-deep also carries the active warn-chip UI-component border on a
    // light pane-head surface — it must clear the 3:1 non-text bar there too.
    { fg: '--ex-warn-deep', bgs: surfaces, min: WCAG_NON_TEXT, role: 'warning border' },
    { fg: '--ex-ink-faint', bgs: surfaces, min: WCAG_AA_NORMAL_TEXT, role: 'muted / skeleton text' },
    { fg: '--ex-ink-muted', bgs: surfaces, min: WCAG_AA_NORMAL_TEXT, role: 'secondary text' },
    { fg: '--ex-ink', bgs: surfaces, min: WCAG_AA_NORMAL_TEXT, role: 'body text' },
    // White-text filled action backgrounds (buttons / login submit / pills):
    // white-on-fill must clear AA, so the FILL is the "fg" against #FFFFFF here.
    { fg: '--ex-accent-deep', bgs: ['#FFFFFF'], min: WCAG_AA_NORMAL_TEXT, role: 'white label on filled action', invert: true },
    { fg: '--ex-success', bgs: ['#FFFFFF'], min: WCAG_AA_NORMAL_TEXT, role: 'white label on connected pill', invert: true },
    { fg: '--ex-error', bgs: ['#FFFFFF'], min: WCAG_AA_NORMAL_TEXT, role: 'white label on error pill', invert: true },
    { fg: '--ex-ink-muted', bgs: ['#FFFFFF'], min: WCAG_AA_NORMAL_TEXT, role: 'white label on disconnected pill', invert: true },
    // The keyboard focus indicator ring uses --ex-accent-deep; it must clear
    // the 3:1 non-text bar against every surface it can sit on.
    { fg: '--ex-accent-deep', bgs: surfaces, min: WCAG_NON_TEXT, role: 'focus-indicator ring' },
  ];
}

// Resolve a local reference from `pageDir` to its real SOURCE location,
// modelling the orchestrator's staging. A `_shared/...` reference is staged
// from the single canonical examples/_shared/ tree (copied into every page's
// output dir at the same relative path), so it resolves against
// `sharedParent` (examples/), NOT relative to the page. Every other relative
// reference is a true page-local sibling and resolves relative to `pageDir`.
function resolveRef(ref, pageDir, sharedParent = EXAMPLES_ROOT) {
  if (ref === '_shared' || ref.startsWith('_shared/')) {
    return path.resolve(sharedParent, ref);
  }
  return path.resolve(pageDir, ref);
}

// Reject any unallowlisted network `url(...)` reference inside a CSS source
// (rf2-o18ava). An `@font-face { src: url(https://…) }`, a
// `background-image: url(//cdn…)`, a masked/cursor/border-image remote `url()`,
// etc. fires a third-party network request at load time — the SAME
// reproducibility / offline-dev / hidden-dependency regression the external CSS
// `@import` policy (rf2-vou5mm / rf2-byf7y) already guards, just via a `url()`
// declaration rather than an `@import`. Only http(s) and the protocol-relative
// `//host/...` form are gated: `data:` URIs are inlined (no request) and a
// `url(#fragment)` is a same-document paint reference (SVG filter/gradient), not
// a fetch. Pushes one error per offending raw URL into `errors`.
function checkCssNetworkUrls(css, displayRef, errors, externalAllowlist = EXTERNAL_IMPORT_ALLOWLIST) {
  for (const raw of extractCssUrls(css)) {
    if (!isNetworkRef(raw)) continue; // data:/#fragment/relative local — not a network fetch
    const key = raw.split(/[?#]/)[0].trim(); // allowlist keys are query/hash-stripped
    if (Object.prototype.hasOwnProperty.call(externalAllowlist, key)) continue;
    errors.push(
      `${displayRef}: CSS url('${raw}') pulls a third-party network ` +
        `dependency (remote font / image / mask / cursor) into staged ` +
        `examples at load time. Remote CSS url() fetches are forbidden unless ` +
        `explicitly allowlisted with a reason in EXTERNAL_IMPORT_ALLOWLIST in ` +
        `check-examples-assets.cjs (rf2-o18ava).`,
    );
  }
}

// Resolve + check a single local CSS file's @import targets, recursively.
// Records an error for any local import that does not resolve to a real file,
// for any EXTERNAL @import (http/https/protocol-relative) not present in the
// external-import allowlist (rf2-vou5mm), and for any remote `url(...)` fetch
// in the CSS body (rf2-o18ava).
function checkCssImports(io, cssAbsPath, displayRef, errors, seen, externalAllowlist = EXTERNAL_IMPORT_ALLOWLIST) {
  if (seen.has(cssAbsPath)) return;
  seen.add(cssAbsPath);
  const css = readFileSafe(io, cssAbsPath);
  if (css == null) return; // a missing CSS file is reported by its referrer
  // Remote url() fetches (font-face/background/mask/cursor/…) — rf2-o18ava.
  checkCssNetworkUrls(css, displayRef, errors, externalAllowlist);
  for (const imp of extractCssImports(css)) {
    if (isExternalRef(imp)) {
      // An external CSS @import pulls a third-party network dependency into
      // every staged example at load time. Reject it unless its exact URL is
      // allowlisted with a reason (fail-closed — see EXTERNAL_IMPORT_ALLOWLIST).
      // data:/#fragment schemes are not network deps; only http/https and the
      // protocol-relative `//host/...` form are gated.
      if (isNetworkRef(imp) && !Object.prototype.hasOwnProperty.call(externalAllowlist, imp)) {
        errors.push(
          `${displayRef}: external @import '${imp}' pulls a third-party ` +
            `network dependency into staged examples. External CSS @imports ` +
            `are forbidden unless explicitly allowlisted with a reason in ` +
            `EXTERNAL_IMPORT_ALLOWLIST in check-examples-assets.cjs (rf2-vou5mm).`,
        );
      }
      continue;
    }
    const target = path.resolve(path.dirname(cssAbsPath), imp);
    if (!io.existsSync(target)) {
      errors.push(
        `${displayRef}: @import '${imp}' does not resolve to a file ` +
          `(looked for ${path.relative(REPO_ROOT, target).split(path.sep).join('/')})`,
      );
      continue;
    }
    if (target.toLowerCase().endsWith('.css')) {
      checkCssImports(io, target, `${displayRef} -> ${imp}`, errors, seen, externalAllowlist);
    }
  }
}

function scanPage(io, indexAbsPath, opts = {}) {
  const allowlist = opts.allowlist || ALLOWLIST;
  const externalImportAllowlist = opts.externalImportAllowlist || EXTERNAL_IMPORT_ALLOWLIST;
  const externalHtmlRefAllowlist =
    opts.externalHtmlRefAllowlist || EXTERNAL_HTML_REF_ALLOWLIST;
  const required = opts.required || REQUIRED_SHARED_ASSETS;
  const errors = [];
  const relIndex = path.relative(REPO_ROOT, indexAbsPath).split(path.sep).join('/');
  const exempt = allowlist[relIndex] || {};
  const exemptAssets = new Set(exempt.assetExemptions || []);
  const allowedLocal = new Set(exempt.localAssets || []);

  const html = readFileSafe(io, indexAbsPath);
  if (html == null) {
    errors.push(`${relIndex}: could not read index.html`);
    return { relIndex, errors, refs: [] };
  }

  const refs = extractHtmlRefs(html);
  const pageDir = path.dirname(indexAbsPath);
  const seenCss = new Set();

  // 0) Direct-HTML network policy (rf2-bf4vdy): an asset-bearing external
  //    reference in the page — a <script src>, an asset <link href>, an
  //    <img>/<source>/<video>/<audio> src, or an external og:image — pulls a
  //    third-party CDN script / hosted stylesheet/font / external media into
  //    every staged example at load time. Reject it unless its exact URL is
  //    allowlisted with a reason (fail-closed — EXTERNAL_HTML_REF_ALLOWLIST).
  //    Only http(s)/protocol-relative refs are network deps; data: URIs are
  //    inlined and pure navigation (#fragment / mailto: / a-href) is exempt.
  for (const { ref, source } of extractAssetRefs(html)) {
    if (!isNetworkRef(ref)) continue;
    if (Object.prototype.hasOwnProperty.call(externalHtmlRefAllowlist, ref)) continue;
    errors.push(
      `${relIndex}: ${source} '${ref}' pulls a third-party network ` +
        `dependency into staged examples. Direct-HTML external asset refs ` +
        `(remote scripts / stylesheets / fonts / images) are forbidden ` +
        `unless explicitly allowlisted with a reason in ` +
        `EXTERNAL_HTML_REF_ALLOWLIST in check-examples-assets.cjs (rf2-bf4vdy).`,
    );
  }

  // 1) Resolve every local reference to a real file in the source tree.
  for (const ref of refs) {
    if (isExternalRef(ref)) continue;
    if (BUILD_OUTPUTS.has(ref)) continue; // main.js — build output, not source
    // Allowlisted non-_shared local assets (e.g. TodoMVC's npm-vendored CSS)
    // are staged from outside the repo source tree; do not flag as missing,
    // but still note them so they're visible in --list output.
    if (allowedLocal.has(ref)) continue;
    const target = resolveRef(ref, pageDir, opts.sharedParent || EXAMPLES_ROOT);
    if (!io.existsSync(target)) {
      errors.push(
        `${relIndex}: asset '${ref}' does not resolve to a file ` +
          `(looked for ${path.relative(REPO_ROOT, target).split(path.sep).join('/')})`,
      );
      continue;
    }
    // Transitively check @import targets inside any referenced local CSS.
    if (target.toLowerCase().endsWith('.css')) {
      checkCssImports(io, target, `${relIndex} (${ref})`, errors, seenCss, externalImportAllowlist);
    }
  }

  // 2) Required shared-asset contract: every page references each required
  //    asset unless explicitly exempt (allowlist). An exemption that is not
  //    actually exercised (the page DOES reference the "exempt" asset) is a
  //    stale allowlist entry — flag it so the allowlist can't rot.
  const refSet = new Set(refs);
  for (const need of required) {
    const isExempt = exemptAssets.has(need);
    const present = refSet.has(need);
    if (!present && !isExempt) {
      errors.push(
        `${relIndex}: missing required shared asset reference '${need}'. ` +
          `Either add it to the page's <head>, or (if intentional) encode ` +
          `the exception in ALLOWLIST in check-examples-assets.cjs with a reason.`,
      );
    }
    if (present && isExempt) {
      errors.push(
        `${relIndex}: allowlisted as exempt from '${need}' but the page ` +
          `DOES reference it — remove the stale exemption from ALLOWLIST ` +
          `in check-examples-assets.cjs.`,
      );
    }
  }

  // 3) Social-preview RASTER contract: every og:image the page declares must
  //    point at a raster (PNG/JPG/WebP/GIF), never an SVG. Link-preview
  //    scrapers (Facebook / X / LinkedIn / Slack / Discord) ignore an SVG
  //    og:image and render no large preview card — a failure mode invisible to
  //    a pure "the referenced file exists" check. (rf2-lr4am3)
  for (const og of extractOgImageRefs(html)) {
    if (isExternalRef(og)) continue; // a hosted absolute URL is the page's call
    const ext = path.extname(og).toLowerCase();
    if (!SOCIAL_PREVIEW_RASTER_EXTS.has(ext)) {
      errors.push(
        `${relIndex}: og:image '${og}' is not a raster social-preview asset ` +
          `(extension '${ext || '(none)'}'). Link-preview scrapers do not ` +
          `render an SVG og:image — point it at the rasterised ` +
          `'${SOCIAL_PREVIEW_REQUIRED}' (allowed: ` +
          `${[...SOCIAL_PREVIEW_RASTER_EXTS].join(', ')}).`,
      );
    }
  }

  return { relIndex, errors, refs };
}

// Verify the _shared SOURCE tree is intact: the required files exist and
// structure.css remains reachable from style.css. Independent of any page so
// a delete/rename of a shared asset is caught even if every page were
// (wrongly) allowlisted out of it.
function checkSharedTree(io, opts = {}) {
  const sharedRoot = opts.sharedRoot || SHARED_ROOT;
  const errors = [];
  const mustExist = [
    path.join(sharedRoot, 'css', 'style.css'),
    path.join(sharedRoot, 'css', 'structure.css'),
    path.join(sharedRoot, 'img', 'favicon.svg'),
    // og.png is the SHIPPED social-preview target every page references; og.svg
    // is its editable SOURCE ART. Require both: a deleted raster breaks every
    // page's preview, a deleted source removes the ability to re-export it.
    path.join(sharedRoot, 'img', 'og.png'),
    path.join(sharedRoot, 'img', 'og.svg'),
  ];
  for (const f of mustExist) {
    if (!io.existsSync(f)) {
      errors.push(
        `examples/_shared: required shared asset missing: ` +
          `${path.relative(REPO_ROOT, f).split(path.sep).join('/')}`,
      );
    }
  }

  // The shipped og.png is the social-preview RASTER every page references. A
  // bare existence check (above) would stay green if the bytes were replaced
  // by non-PNG content (a renamed SVG/text file) or a wrong-size export — both
  // break link-preview scrapers silently. Validate the actual bytes: decodable
  // PNG signature + IHDR + the documented 1200x630 dimensions (rf2-mon7tz).
  const ogPngPath = path.join(sharedRoot, 'img', 'og.png');
  if (io.existsSync(ogPngPath)) {
    const bytes = readBytesSafe(io, ogPngPath);
    if (bytes == null) {
      errors.push(`examples/_shared/img/og.png: could not read the raster bytes.`);
    } else {
      const v = validatePng(bytes);
      if (!v.ok) {
        errors.push(
          `examples/_shared/img/og.png: not a valid ${OG_PNG_WIDTH}x${OG_PNG_HEIGHT} ` +
            `social-preview PNG — ${v.reason}. The canonical card is the ` +
            `${OG_PNG_WIDTH}x${OG_PNG_HEIGHT} raster (examples/_shared/README.md); ` +
            `re-export img/og.svg to a real PNG. Link-preview scrapers receive a ` +
            `broken/wrong-size image otherwise (rf2-mon7tz).`,
        );
      }
    }
  }
  // OG SOURCE-ART palette conformance (rf2-y82dk9). The shipped og.png is
  // re-exported from og.svg, whose colour literals intentionally mirror the
  // --ex-* CSS tokens. The og.png byte-check above is opaque to colour, so a
  // shared-palette darkening (e.g. the AA fix --ex-ink-faint #8A8270 → #6E6654,
  // rf2-febmqu) can leave og.svg/og.png stale and still pass. Reject any retired
  // /sub-AA literal re-appearing in the source art so it can't silently drift
  // back below the palette's own accessibility decisions.
  const ogSvgPath = path.join(sharedRoot, 'img', 'og.svg');
  const ogSvg = readFileSafe(io, ogSvgPath);
  if (ogSvg != null) {
    // Strip XML/SVG comments first: the source-art header documents the
    // retired→AA-safe migration BY NAMING the retired value, which is prose,
    // not a live colour. We only flag the literal where it is an actual paint
    // attribute (fill / stroke / stop-color), so the doc note can cite it.
    const ogSvgNoComments = ogSvg.replace(/<!--[\s\S]*?-->/g, '');
    for (const c of RETIRED_OG_SOURCE_COLORS) {
      // A LIVE colour is the retired hex used as a paint value:
      //   fill="#8A8270"  stroke='#8A8270'  stop-color="#8A8270"
      // (quoted attribute or bare presentation-attribute value).
      const re = new RegExp(
        `\\b(?:fill|stroke|stop-color|color)\\s*[:=]\\s*["']?\\s*${c.retired}\\b`,
        'i',
      );
      if (re.test(ogSvgNoComments)) {
        errors.push(
          `examples/_shared/img/og.svg: source art uses the retired ` +
            `${c.retired} colour (${c.reason}). Replace it with the AA-safe ` +
            `${c.replacement} (${c.token}) and re-export og.png, so the social ` +
            `card keeps the shared palette's accessibility decisions (rf2-y82dk9).`,
        );
      }
    }
  }

  const externalImportAllowlist =
    opts.externalImportAllowlist || EXTERNAL_IMPORT_ALLOWLIST;

  // No EXTERNAL @import in the shared CSS (rf2-vou5mm). An external
  // `@import url(https://…|//…)` here makes every staged example fire a
  // third-party network request at load time — the Google-Fonts regression
  // rf2-byf7y removed. Fail-closed: reject any external @import in style.css /
  // structure.css whose exact URL is not allowlisted. Checked HERE (not only
  // via the page reference graph) so the contract holds even if no scanned page
  // happens to link the file.
  for (const cssName of ['style.css', 'structure.css']) {
    const cssPath = path.join(sharedRoot, 'css', cssName);
    const css = readFileSafe(io, cssPath);
    if (css == null) continue; // missing-file is reported by the mustExist loop
    for (const imp of extractCssImports(css)) {
      const isNetwork = /^https?:/i.test(imp) || imp.startsWith('//');
      if (
        isNetwork &&
        !Object.prototype.hasOwnProperty.call(externalImportAllowlist, imp)
      ) {
        errors.push(
          `examples/_shared/css/${cssName}: external @import '${imp}' pulls a ` +
            `third-party network dependency into every staged example. The ` +
            `shared design system loads NO remote fonts/hosts (see ` +
            `examples/_shared/README.md §Visual identity). External CSS ` +
            `@imports are forbidden unless explicitly allowlisted with a ` +
            `reason in EXTERNAL_IMPORT_ALLOWLIST in ` +
            `check-examples-assets.cjs (rf2-vou5mm).`,
        );
      }
    }
    // No remote `url(...)` fetch in the shared CSS (rf2-o18ava). A
    // `@font-face { src: url(https://…) }` / `background-image: url(//cdn…)` /
    // mask / cursor remote url() here makes every staged example fire a
    // third-party request at load time — the SAME no-remote-styling contract
    // (examples/_shared/README.md §Visual identity) the @import policy guards.
    // Checked HERE, independent of the page reference graph, so the contract
    // holds even if no scanned page happens to link the file.
    checkCssNetworkUrls(
      css,
      `examples/_shared/css/${cssName}`,
      errors,
      externalImportAllowlist,
    );
  }

  // structure.css reachable from style.css's @import.
  const stylePath = path.join(sharedRoot, 'css', 'style.css');
  const style = readFileSafe(io, stylePath);
  if (style != null) {
    const imports = extractCssImports(style).filter((i) => !isExternalRef(i));
    const reachesStructure = imports.some(
      (i) => path.basename(i).toLowerCase() === 'structure.css',
    );
    if (!reachesStructure) {
      errors.push(
        `examples/_shared/css/style.css: no @import resolving to ` +
          `structure.css — the structural baseline is unreachable.`,
      );
    }
  }

  // CSS-cascade contract (rf2-gv5xd): the WebSocket send-form text-input
  // baseline (padding/flex/min-width:240px) MUST stay scoped to the
  // `.send-form` so it cannot leak into other examples. Component-specific
  // sizing lives behind a component selector, never as a global element rule.
  // The 7GUIs Cells example renders each inline editor as a text input inside
  // `.cells-grid`; a bare global `input[type="text"] { min-width: 240px }`
  // would override the intended compact `.cells-grid input { width: 56px }`
  // and blow the spreadsheet grid out to >=240px columns. A static cascade
  // check, since the asset gate cannot observe layout.
  const structurePath = path.join(sharedRoot, 'css', 'structure.css');
  const structure = readFileSafe(io, structurePath);
  if (structure != null) {
    // A bare `input[type="text"]` selector (no class/id/attribute qualifier
    // to its LEFT) is global; the send-form baseline must be qualified.
    if (/(^|[},;])\s*input\[type=(["'])text\2\]\s*\{/m.test(structure)) {
      errors.push(
        `examples/_shared/css/structure.css: the WebSocket text-input ` +
          `baseline is a GLOBAL 'input[type="text"]' rule. It sets ` +
          `min-width:240px on EVERY text input in EVERY example and blows ` +
          `out the 7GUIs Cells grid (whose inline editors are text inputs ` +
          `inside .cells-grid intended at width:56px). Scope it to the send ` +
          `form (e.g. '.send-form input[type="text"]') — see rf2-gv5xd.`,
      );
    }
    // The Cells grid editor must keep its compact width.
    if (!/\.cells-grid\s+input\s*\{[^}]*width:\s*56px/m.test(structure)) {
      errors.push(
        `examples/_shared/css/structure.css: the '.cells-grid input' rule ` +
          `must pin the compact 'width: 56px' cell-editor size (rf2-gv5xd).`,
      );
    }

    // RESPONSIVE Xray-host shell contract (rf2-y82dk9). The .rf2-testbed-shell
    // is a side-by-side flex with the inline Xray host fixed at
    // --rf-xray-inline-width (flex-shrink:0) + a 320px min-width, so it needs
    // ~624px before any app content shows and overflows on narrow viewports.
    // Examples are teaching surfaces, so the shared shell must encode a
    // deliberate narrow-viewport behaviour (stack/collapse) rather than
    // silently regress to an unbounded horizontal layout. Require a max-width
    // media query that flips the shell to a stacked (column) flow.
    const stacksUnderBreakpoint =
      /@media[^{]*max-width[\s\S]*?\.rf2-testbed-shell\s*\{[^}]*flex-direction:\s*column/m.test(
        structure,
      );
    if (!stacksUnderBreakpoint) {
      errors.push(
        `examples/_shared/css/structure.css: the inline-Xray ` +
          `'.rf2-testbed-shell' is a fixed two-column flex with no responsive ` +
          `fallback. It overflows horizontally on narrow viewports (the host ` +
          `is flex-shrink:0 at ~560px + 320px min-width, ~624px before any app ` +
          `content). Add a '@media (max-width: …)' rule that stacks the shell ` +
          `('.rf2-testbed-shell { flex-direction: column }') so the Xray host ` +
          `drops below the app instead of overflowing (rf2-y82dk9). See ` +
          `examples/_shared/README.md §Responsive Xray-host shell.`,
      );
    }
  }

  // Accessibility contracts on style.css (rf2-febmqu + rf2-mon7tz).
  if (style != null) {
    // (a) Shared palette CONTRAST contract (rf2-febmqu): every shipped
    //     foreground/background token pair must clear its WCAG floor (AA 4.5:1
    //     for normal text, 3:1 for the focus-indicator ring). Computed from the
    //     parsed --ex-* tokens so a palette edit that drops a pair below the
    //     floor turns the gate RED.
    const tokens = parseExTokens(style);
    const resolve = (name) =>
      name.startsWith('--ex-') ? tokens[name] : name; // literal hex passthrough
    for (const row of sharedContrastContract(tokens)) {
      const fgHex = resolve(row.fg);
      if (!fgHex) continue; // token not declared (renamed) — not this check's job
      for (const bg of row.bgs) {
        const bgHex = resolve(bg);
        if (!bgHex) continue;
        const ratio = contrastRatio(fgHex, bgHex);
        if (ratio < row.min) {
          const pair = row.invert
            ? `white text on ${row.fg} (${fgHex})`
            : `${row.fg} (${fgHex}) on ${bg} (${bgHex})`;
          errors.push(
            `examples/_shared/css/style.css: ${row.role} fails WCAG ` +
              `${row.min === WCAG_AA_NORMAL_TEXT ? 'AA' : 'non-text'} — ` +
              `${pair} is ${ratio.toFixed(2)}:1, below ${row.min}:1. Use an ` +
              `AA-safe token (prefer --ex-accent-deep / --ex-ink-muted) ` +
              `(rf2-febmqu).`,
          );
        }
      }
    }

    // (b) FOCUS-INDICATOR contract (rf2-mon7tz): the form-control focus rule
    //     must not strip the native outline without an accessible replacement.
    //     The old rule paired `outline: none` with a ≈1.5:1 low-alpha amber
    //     ring (rgba(200,116,26,0.18)) — keyboard users lost the indicator.
    //     Require a :focus-visible treatment whose ring uses the AA-safe accent
    //     token, and forbid the low-alpha amber ring from coming back.
    const hasFocusVisible = /input:focus-visible[^{]*\{[^}]*box-shadow:[^}]*--ex-accent-deep/m.test(
      style,
    );
    if (!hasFocusVisible) {
      errors.push(
        `examples/_shared/css/style.css: form controls must carry a visible ` +
          `':focus-visible' indicator whose ring uses the AA-safe ` +
          `'--ex-accent-deep' token (≥3:1 on every surface). A bare ` +
          `'outline: none' without an accessible replacement is forbidden ` +
          `(rf2-mon7tz).`,
      );
    }
    // The low-alpha amber ring (the pre-fix ≈1.5:1 indicator) must not return.
    if (/box-shadow:[^;}]*rgba\(\s*200\s*,\s*116\s*,\s*26\s*,\s*0?\.\d+\s*\)/m.test(style)) {
      errors.push(
        `examples/_shared/css/style.css: the low-alpha amber focus ring ` +
          `'rgba(200,116,26,0.18)' is below the 3:1 focus-indicator bar and ` +
          `must not be used as the focus indicator. Use a solid ` +
          `'--ex-accent-deep' ring (rf2-mon7tz).`,
      );
    }
  }
  return errors;
}

// Run the full scan over every example index.html plus the _shared tree.
function scanAll(opts = {}) {
  const io = opts.io || fs;
  const indexes = opts.indexes || listExampleIndexHtml();
  const pages = indexes.map((p) => scanPage(io, p, opts));
  const sharedErrors = checkSharedTree(io, opts);
  const errors = [...sharedErrors, ...pages.flatMap((p) => p.errors)];
  return { pages, sharedErrors, errors };
}

module.exports = {
  REPO_ROOT,
  EXAMPLES_ROOT,
  SHARED_ROOT,
  REQUIRED_SHARED_ASSETS,
  SOCIAL_PREVIEW_RASTER_EXTS,
  SOCIAL_PREVIEW_REQUIRED,
  ALLOWLIST,
  EXTERNAL_IMPORT_ALLOWLIST,
  EXTERNAL_HTML_REF_ALLOWLIST,
  ASSET_LINK_RELS,
  BUILD_OUTPUTS,
  listExampleIndexHtml,
  isExampleHostPage,
  isExternalRef,
  isNetworkRef,
  extractHtmlRefs,
  extractAssetRefs,
  extractOgImageRefs,
  extractCssImports,
  extractCssUrls,
  resolveRef,
  checkCssImports,
  checkCssNetworkUrls,
  scanPage,
  checkSharedTree,
  scanAll,
  // og.png raster byte-validation (rf2-mon7tz)
  PNG_SIGNATURE,
  OG_PNG_WIDTH,
  OG_PNG_HEIGHT,
  validatePng,
  // shared palette contrast + focus-indicator contract (rf2-febmqu + rf2-mon7tz)
  WCAG_AA_NORMAL_TEXT,
  WCAG_NON_TEXT,
  contrastRatio,
  relativeLuminance,
  parseExTokens,
  sharedContrastContract,
  // OG source-art palette + responsive Xray-host shell contracts (rf2-y82dk9)
  RETIRED_OG_SOURCE_COLORS,
};

// ---------------------------------------------------------------------------
// CLI entry-point (skipped when require()'d by the test suite).
// ---------------------------------------------------------------------------
if (require.main === module) {
  const listOnly = process.argv.slice(2).includes('--list');
  const indexes = listExampleIndexHtml();

  // Non-vacuous guard: a walk that silently recovers nothing must NOT let the
  // gate pass green having scanned zero pages. The repo ships well over a
  // dozen example host pages (index.html + the *.index.html showcase pages);
  // require a sane floor.
  if (indexes.length < 10) {
    console.error(
      `check-examples-assets: only ${indexes.length} example host page(s) ` +
        `(index.html / *.index.html) found under examples/ — expected the ` +
        `full set. Walk or layout drift; refusing to pass a vacuous gate.`,
    );
    process.exit(1);
  }

  const { pages, errors } = scanAll({ indexes });

  console.log(
    `check-examples-assets: scanning ${pages.length} example host page(s) ` +
      `(index.html + *.index.html showcase pages) + the _shared tree.`,
  );

  if (listOnly) {
    for (const p of pages) {
      console.log(`  ${p.relIndex}`);
      for (const r of p.refs) console.log(`      ${r}`);
    }
    process.exit(0);
  }

  if (errors.length > 0) {
    console.error(
      `\ncheck-examples-assets: ${errors.length} asset-contract violation(s):`,
    );
    for (const e of errors) console.error(`  - ${e}`);
    console.error(
      `\nA missing/renamed _shared asset, a broken @import, an unallowlisted ` +
        `EXTERNAL CSS @import, an unallowlisted direct-HTML remote asset ref ` +
        `(remote <script>/<link>/<img>), or a page that drops a required ` +
        `shared asset without an encoded ALLOWLIST exception fails this gate. ` +
        `Fix the reference, restore the asset, drop the remote dependency, or ` +
        `encode the exception (with a reason) in ALLOWLIST / ` +
        `EXTERNAL_IMPORT_ALLOWLIST / EXTERNAL_HTML_REF_ALLOWLIST in ` +
        `examples/scripts/check-examples-assets.cjs.`,
    );
    process.exit(1);
  }

  console.log(
    `check-examples-assets: all ${pages.length} pages resolve their assets ` +
      `and carry the required _shared contract (allowlisted exceptions ` +
      `honoured). _shared tree intact.`,
  );
}
