'use strict';
// Escaped non-ASCII markup distinguishes byte counts from code-unit counts
// without editor encoding drift. HTML escapes also expose unwanted re-escaping.

const EM_DASH = '\u2014';
const ELLIPSIS = '\u2026';
const CLEF = '\u{1D11E}';

const BODY =
  `<h1>Fresco SSR ${EM_DASH} bytes</h1>` +
  `<p>loading${ELLIPSIS}</p>` +
  `<p>${CLEF}</p>` +
  `<p>&lt;/script&gt; &amp; &quot;quoted&quot;</p>` +
  `<p>\u00e9\u4e2d\u6587</p>`;

module.exports = {
  protocol: 1,
  buildId: 'bytes-build-1',
  entries: ['app/root'],
  // Exported so the witness compares against the module's own bytes
  // rather than against a second copy of the same literal.
  BODY,
  render() {
    return BODY;
  },
};
