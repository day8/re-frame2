'use strict';

/*
 * ONE owner for every examples EXTERNAL-ASSET EXCEPTION (rf2-phpbo8).
 *
 * The problem this closes
 * -----------------------
 * A handful of examples depart from the "index.html + _shared, nothing else"
 * staging baseline: they stage an extra static asset (TodoMVC's official CSS,
 * managed-http-counter's success fixture, the RealWorld fallback avatar) and/or
 * opt out of a required _shared asset (TodoMVC links the vendored TodoMVC CSS
 * instead of the shared stylesheet). Before this manifest each such exception
 * was declared INDEPENDENTLY in up to three places with no cross-consistency:
 *
 *   - examples/scripts/examples-staging.cjs  PER_EXAMPLE_ASSETS  (what to STAGE)
 *   - examples/scripts/check-examples-assets.cjs  ALLOWLIST       (what the
 *                                                                  SCANNER accepts)
 *   - the two implementation script test suites, each pinning its side with a
 *     LITERAL list that restated the production data.
 *
 * TodoMVC's base.css/index.css lived as BOTH staged destinations AND scanner
 * `localAssets`, redeclared verbatim — so the two could drift with nothing to
 * catch it. This module is the single side-effect-free owner. Each entry binds
 * a build id (staging's key) to a source page (the scanner's key), the assets
 * to stage, the shared-asset exemptions the page may omit, and one reason. Both
 * consumers PROJECT their view out of it (stagedAssetsByBuild / pageExemptions),
 * so the staged destinations and the scanner's accepted page-local refs are
 * derived from ONE declaration and cannot diverge.
 *
 * Loading this module touches no filesystem and runs no I/O — it is pure data
 * plus two pure projections, safe to require from both the staging helper (which
 * serves browser-gate output) and the static asset scanner.
 */

// ---------------------------------------------------------------------------
// The manifest. Each entry declares ONE example's departure from the
// index.html + _shared staging baseline.
//
//   build           — the shadow-cljs build id (colon-stripped coord, as
//                      listStandaloneExamples() returns) the STAGING consumer
//                      keys assets by.
//   page            — the source index.html the SCANNER keys shared-asset
//                      exemptions by (REPO_ROOT-relative, forward-slashed).
//                      Recorded for every entry; only entries with a
//                      scanner-relevant exception (a shared-asset exemption or
//                      an html-linked staged asset) surface in pageExemptions().
//   reason          — one human-readable justification for the exception.
//   assetExemptions — required _shared asset hrefs this page may OMIT (it
//                      references something else instead). Empty for a
//                      staging-only exception.
//   assets          — static assets staged into the output dir beyond
//                      index.html + _shared. Each: { from, src, dest, htmlLinked }.
//                        from       — 'node-modules' (under implementation/
//                                     node_modules) or 'src' (colocated under
//                                     the example's own source folder).
//                        src        — source path relative to that root
//                                     (forward-slashed; consumers path.join it).
//                        dest       — destination path under the output dir.
//                        htmlLinked — true iff the page references dest DIRECTLY
//                                     via a flat href (so the SCANNER must accept
//                                     it as a page-local ref rather than flag it
//                                     missing). An asset fetched/referenced from
//                                     app code (not the HTML) leaves it false.
// ---------------------------------------------------------------------------
const EXAMPLE_ASSET_MANIFEST = [
  {
    build: 'examples/todomvc',
    page: 'examples/core/todomvc/index.html',
    reason:
      'TodoMVC uses the official TodoMVC CSS packages (todomvc-common base.css ' +
      '+ todomvc-app-css index.css, pinned in implementation/package.json and ' +
      'staged from node_modules/ by `npm install`, NOT vendored in-repo) ' +
      'instead of the shared stylesheet — see examples/core/todomvc/README.md ' +
      '§Official assets. It is therefore exempt from _shared/css/style.css but ' +
      'STILL carries the shared favicon + OG card, and its two npm-staged CSS ' +
      'links are not repo-source files (they are staged, not flagged as missing).',
    assetExemptions: ['_shared/css/style.css'],
    assets: [
      { from: 'node-modules', src: 'todomvc-common/base.css', dest: 'base.css', htmlLinked: true },
      { from: 'node-modules', src: 'todomvc-app-css/index.css', dest: 'index.css', htmlLinked: true },
    ],
  },
  {
    build: 'examples/managed-http-counter',
    page: 'examples/core/managed_http_counter/index.html',
    reason:
      'managed-http-counter fetches api/inc.json on its success path; the ' +
      'fixture lives colocated under the example source api/ folder. Without ' +
      'it the success path 404s and the example exercises only its failure ' +
      'branch. Fetched from app code, not linked in the HTML — no scanner ' +
      'exemption, staging-only.',
    assetExemptions: [],
    assets: [
      { from: 'src', src: 'api/inc.json', dest: 'api/inc.json', htmlLinked: false },
    ],
  },
  {
    build: 'examples/realworld',
    page: 'examples/real-apps/realworld_http/index.html',
    reason:
      'RealWorld falls a nil/blank avatar back to default-avatar.svg served ' +
      'from the app asset root (realworld_shared.avatar); the asset lives ' +
      'colocated in the RealWorld source folder. Without it every fallback ' +
      'avatar is a broken image. Referenced from app code, not linked in the ' +
      'HTML — no scanner exemption, staging-only.',
    assetExemptions: [],
    assets: [
      { from: 'src', src: 'default-avatar.svg', dest: 'default-avatar.svg', htmlLinked: false },
    ],
  },
  {
    build: 'examples/realworld-resources',
    page: 'examples/real-apps/realworld_resources/index.html',
    reason:
      'The RealWorld resources variant falls a nil/blank avatar back to ' +
      'default-avatar.svg served from the app asset root ' +
      '(realworld_shared.avatar); the asset lives colocated in the source ' +
      'folder. Without it every fallback avatar is a broken image. Referenced ' +
      'from app code, not linked in the HTML — no scanner exemption, staging-only.',
    assetExemptions: [],
    assets: [
      { from: 'src', src: 'default-avatar.svg', dest: 'default-avatar.svg', htmlLinked: false },
    ],
  },
  {
    build: 'examples/realworld-resources-ui',
    page: 'examples/real-apps/realworld_resources/index.html',
    reason:
      'The RealWorld resources re-frame.ui variant (ui_core / ui_views) falls ' +
      'a nil/blank avatar back to default-avatar.svg exactly like the Reagent ' +
      'arm — ui_views calls realworld_shared.avatar/avatar-src throughout and ' +
      'the canned corpus has blank :image values. The build resolves to the ' +
      'SAME colocated source folder as the Reagent arm, so the asset is the ' +
      'same colocated default-avatar.svg. Without this entry the enumerator ' +
      'discovers the ui build but stages no avatar, so clean staging serves ' +
      'broken avatars. Referenced from app code, not linked in the HTML — no ' +
      'scanner exemption, staging-only.',
    assetExemptions: [],
    assets: [
      { from: 'src', src: 'default-avatar.svg', dest: 'default-avatar.svg', htmlLinked: false },
    ],
  },
];

// ---------------------------------------------------------------------------
// Projection for the STAGING consumer (examples-staging.cjs): build id ->
// [{ from, src, dest }]. EVERY declared asset is staged (staging copies them
// all regardless of htmlLinked; htmlLinked only concerns the scanner). Mirrors
// the old PER_EXAMPLE_ASSETS shape so the staging helper is a drop-in consumer.
// ---------------------------------------------------------------------------
function stagedAssetsByBuild(manifest = EXAMPLE_ASSET_MANIFEST) {
  const out = {};
  for (const entry of manifest) {
    if (!entry.assets || entry.assets.length === 0) continue;
    out[entry.build] = entry.assets.map(({ from, src, dest }) => ({ from, src, dest }));
  }
  return out;
}

// ---------------------------------------------------------------------------
// Projection for the SCANNER consumer (check-examples-assets.cjs): page
// relIndex -> { reason, assetExemptions, localAssets }. `localAssets` is
// DERIVED as the dest of every html-linked asset (the page-local refs the
// scanner must accept as staged-from-outside-repo-source rather than flag as
// missing) — so TodoMVC's base.css/index.css are the SAME declaration the
// staging projection reads, never a second literal list. Only entries with a
// scanner-relevant exception (a shared-asset exemption OR an html-linked asset)
// are emitted, so a staging-only entry adds no empty ALLOWLIST noise. Mirrors
// the old ALLOWLIST shape so the scanner is a drop-in consumer.
// ---------------------------------------------------------------------------
function pageExemptions(manifest = EXAMPLE_ASSET_MANIFEST) {
  const out = {};
  for (const entry of manifest) {
    if (!entry.page) continue;
    const localAssets = (entry.assets || [])
      .filter((a) => a.htmlLinked)
      .map((a) => a.dest);
    const assetExemptions = entry.assetExemptions || [];
    if (assetExemptions.length === 0 && localAssets.length === 0) continue;
    out[entry.page] = { reason: entry.reason, assetExemptions, localAssets };
  }
  return out;
}

module.exports = {
  EXAMPLE_ASSET_MANIFEST,
  stagedAssetsByBuild,
  pageExemptions,
};
