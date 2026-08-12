'use strict';
// THE BYTE CORPUS — markup whose UTF-8 length differs from its UTF-16
// length, plus the two escapes an SSR body most often gets wrong.
//
// Every non-ASCII character is written as a `\u` escape rather than a
// literal, for the reason `bake_bytes.test.cjs` gives: a literal lets an
// encoding-normalising editor quietly ASCII-fy this file and leave every
// assertion downstream passing over inputs that no longer discriminate.
//
//   U+2014 EM DASH                 1 code unit,  3 bytes
//   U+2026 HORIZONTAL ELLIPSIS     1 code unit,  3 bytes
//   U+1D11E MUSICAL SYMBOL G CLEF  2 code units, 4 bytes  <- separates all
//                                                            three accountings
//
// The `</script` fragment is here because a hydration payload lives in a
// script element on the page this markup becomes, and a transport that
// re-encoded or re-escaped on the way past would show up as a changed
// digest here rather than as a broken page three layers later.

const EM_DASH = '\u2014';
const ELLIPSIS = '\u2026';
const CLEF = '\u{1D11E}';

const BODY =
  `<h1>Hicasso SSR ${EM_DASH} bytes</h1>` +
  `<p>loading${ELLIPSIS}</p>` +
  `<p>${CLEF}</p>` +
  `<p>&lt;/script&gt; &amp; &quot;quoted&quot;</p>` +
  `<p>\u00e9\u4e2d\u6587</p>`;

module.exports = {
  protocol: 1,
  buildId: 'bytes-build-1',
  entries: { 'app/root': { stateAllowlist: [] } },
  // Exported so the witness compares against the module's own bytes
  // rather than against a second copy of the same literal.
  BODY,
  render(_call, emit) {
    emit(BODY);
  },
};
