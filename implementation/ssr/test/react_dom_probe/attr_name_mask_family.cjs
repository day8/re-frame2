#!/usr/bin/env node
//
// rf2-4ale — GENERATOR for `attr_name_mask_family.edn`, the external anchor
// for the SSR EMITTED-ATTRIBUTE-NAME direction of the react-dom conversion
// table.
//
// WHY THIS EXISTS. `re-frame.ssr.ui-tree` carries the conversion table in two
// halves that are maintained independently:
//
//   `standard-names`     author name  -> React prop name  (a mirror of
//                        react-dom's own `possibleStandardNames`)
//   `dom-attr-aliases`   React prop name -> the DOM attribute name
//                        react-dom/server actually WRITES
//
// PR #9576 moved this repo to react-dom 19.3.0 and re-derived the first half
// (`masktype -> maskType` landed there) while the second half stayed at its
// 19.2.0 mirror. React 19.3 changed BOTH: it also began emitting
// `maskType` as `mask-type`. Comparing only `possibleStandardNames` cannot
// see that second direction, so the upgrade looked complete and the emitter
// silently kept writing the 19.2 spelling. The whole CI rollup was green over
// it — browser, node, JVM SSR, template, controlled-input and HMR lanes, zero
// failures — because nothing in the tree compared an EMITTED NAME against
// react-dom. That is the hole this file closes, and the consequence is not
// cosmetic: SSR delivering `maskType` to a client that hydrates against
// `mask-type` is a hydration mismatch.
//
// WHAT IS MEASURED, AND WHY BOTH HALVES COME FROM REACT.
//
//   1. The candidate NAMES are react-dom's own `possibleStandardNames` values
//      (every canonical DOM prop name React knows), filtered to the mask
//      family. Taking candidates from OUR table would reproduce the blind
//      spot exactly — a name we never learned would be a name we never probe.
//   2. The EMITTED NAME is read back out of react-dom's own markup: the
//      attribute is rendered with a sentinel value and the name is whatever
//      react-dom wrote in front of it. Nothing here restates a re-frame rule.
//
// WHY THE MASK FAMILY AND NOT EVERY NAME. Deliberately bounded. A full sweep
// of the emitted direction would be a react-dom table clone, which is a
// different and much larger piece of work than this bead; the audit that
// reopened rf2-4ale asked in terms for a FOCUSED witness for the newly
// changed alias and said no general table-generation project was needed. The
// family is the right unit rather than the single name because `maskUnits`
// and `maskContentUnits` are the UNAFFECTED CONTROLS — they sit beside
// `maskType` in the same table, took the same code path, and must not move.
// A witness carrying only the row that changed cannot show the fix is narrow.
//
// THE CLASS THIS DOES NOT COVER, recorded rather than built for: any other
// react-dom emitted-name change between 19.2.0 and 19.3.0 (or a later bump)
// outside the mask family is still invisible to the tree. The general repair
// is a full emitted-direction probe; it is not this bead's.
//
// REGENERATE (from `implementation/`, where node_modules lives):
//
//   node ssr/test/react_dom_probe/attr_name_mask_family.cjs \
//     > ssr/test/react_dom_probe/attr_name_mask_family.edn
//
// The output is deterministic (rows sorted by prop name), so a regeneration
// that changes nothing produces no diff. Bumping react-dom is what should
// change it — and if the new version moves a row,
// `re-frame.ssr-attr-name-react-parity-test` reds and names the attribute.

'use strict';

const fs = require('fs');
const path = require('path');

const reactDomRoot = path.dirname(require.resolve('react-dom/package.json'));
const reactDomVersion = JSON.parse(
  fs.readFileSync(path.join(reactDomRoot, 'package.json'), 'utf8')
).version;

// The LEGACY node builds, matching `boolean_attr_classes.cjs`: they are the
// pair that exports `renderToStaticMarkup`, the one-shot string API this
// probe needs, and they carry the same `pushAttribute` code as the streaming
// builds, so the emitted names are react-dom's rather than a legacy dialect's.
const devServerPath = path.join(
  reactDomRoot, 'cjs', 'react-dom-server-legacy.node.development.js');
const prodServerPath = path.join(
  reactDomRoot, 'cjs', 'react-dom-server-legacy.node.production.js');

// ---------------------------------------------------------------------------
// 1. Candidate names — react-dom's own `possibleStandardNames` table.
// ---------------------------------------------------------------------------

function scrapePossibleStandardNames(source) {
  const marker = 'possibleStandardNames = {';
  const start = source.indexOf(marker);
  if (start < 0) {
    throw new Error(
      'react-dom development build carries no `possibleStandardNames = {` — ' +
      'the scrape anchor moved; fix this generator rather than the fixture.');
  }
  const open = start + marker.length - 1;
  let depth = 0;
  let end = -1;
  for (let i = open; i < source.length; i++) {
    const ch = source[i];
    if (ch === '{') depth++;
    else if (ch === '}') {
      depth--;
      if (depth === 0) { end = i + 1; break; }
    }
  }
  if (end < 0) throw new Error('unbalanced `possibleStandardNames` literal');
  const literal = source.slice(open, end);
  // eslint-disable-next-line no-eval
  const table = eval('(' + literal + ')');
  const names = new Set(Object.values(table));
  if (names.size < 100) {
    throw new Error(
      `possibleStandardNames scrape produced only ${names.size} names — ` +
      'the literal boundary is wrong.');
  }
  return [...names];
}

const devSource = fs.readFileSync(devServerPath, 'utf8');
const candidates = scrapePossibleStandardNames(devSource)
  .filter((name) => /^mask/i.test(name));

// ---------------------------------------------------------------------------
// 2. The emitted name — read back out of react-dom's own markup.
// ---------------------------------------------------------------------------

const React = require('react');
const prod = require(prodServerPath);

if (typeof prod.renderToStaticMarkup !== 'function') {
  throw new Error(
    `${prodServerPath} exports no renderToStaticMarkup — the build layout ` +
    'moved; fix this generator rather than accepting an empty fixture.');
}

// Alphanumeric so react-dom's attribute-value escaping cannot alter it, and
// distinctive enough that it cannot collide with anything else in the markup.
const SENTINEL = 'rf2SentinelValue';

// `<mask>` inside `<svg>`: the element these attributes belong on, in the
// namespace react-dom resolves them in.
function render(propName) {
  return prod.renderToStaticMarkup(
    React.createElement(
      'svg', null, React.createElement('mask', { [propName]: SENTINEL })));
}

// The emitted name is whatever react-dom wrote immediately in front of the
// sentinel. Anchored on the leading space react-dom writes before every
// attribute, so the capture cannot run backwards into the tag name.
const EMITTED = new RegExp(' ([^\\s=<>"]+)="' + SENTINEL + '"');

const rows = [];
for (const propName of candidates) {
  const markup = render(propName);
  const match = EMITTED.exec(markup);
  if (match === null) {
    // react-dom accepted the prop and wrote nothing recognisable. That is a
    // real verdict this fixture cannot express, so fail rather than record a
    // row that would assert something false.
    throw new Error(
      `react-dom ${reactDomVersion} rendered <mask ${propName}> without an ` +
      `attribute carrying the sentinel: ${JSON.stringify(markup)}`);
  }
  rows.push({ propName, emitted: match[1], markup });
}
rows.sort((a, b) => (a.propName < b.propName ? -1 : a.propName > b.propName ? 1 : 0));

// An empty or shrunken fixture is the failure mode that reads as a clean
// measurement: the parity test would iterate no rows and pass. React 19
// carries three mask-family names, and `maskType` is the one this witness
// exists for — refuse a fixture missing either.
if (rows.length < 3) {
  throw new Error(
    `only ${rows.length} mask-family rows measured against react-dom ` +
    `${reactDomVersion} — that is not a plausible fixture; investigate the ` +
    'probe rather than committing it.');
}
if (!rows.some((row) => row.propName === 'maskType')) {
  throw new Error(
    `react-dom ${reactDomVersion} carries no \`maskType\` in ` +
    'possibleStandardNames — that is the row this witness exists for, and a ' +
    'fixture without it would pass vacuously.');
}

// ---------------------------------------------------------------------------
// 3. EDN.
// ---------------------------------------------------------------------------

const ednString = (s) =>
  '"' + s.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';

const out = [];
out.push(';; GENERATED FILE — do not hand-edit.');
out.push(';;');
out.push(';; react-dom EMITTED-ATTRIBUTE-NAME evidence for the mask family');
out.push(';; (rf2-4ale). `:emitted` is the name react-dom/server wrote in front');
out.push(';; of the sentinel value; `:markup` is its whole output, kept so a');
out.push(';; future reader can see the bytes the name was read out of. The');
out.push(';; candidate names are react-dom\'s own `possibleStandardNames`');
out.push(';; values, not a re-frame roster. Measured by');
out.push(';; `react_dom_probe/attr_name_mask_family.cjs` against the installed');
out.push(';; package; compared with `re-frame.ssr.ui-tree` in');
out.push(';; `re_frame/ssr_attr_name_react_parity_test.clj`.');
out.push(';;');
out.push(';; Regenerate from `implementation/`:');
out.push(';;   node ssr/test/react_dom_probe/attr_name_mask_family.cjs \\');
out.push(';;     > ssr/test/react_dom_probe/attr_name_mask_family.edn');
out.push('{:react-dom-version ' + ednString(reactDomVersion));
out.push(' :generated-by "implementation/ssr/test/react_dom_probe/attr_name_mask_family.cjs"');
out.push(' :element "mask"');
out.push(' :sentinel ' + ednString(SENTINEL));
out.push(' :rows');
out.push(' [');
for (const row of rows) {
  out.push('  {:react-prop ' + ednString(row.propName));
  out.push('   :emitted    ' + ednString(row.emitted));
  out.push('   :markup     ' + ednString(row.markup) + '}');
}
out.push(' ]}');
process.stdout.write(out.join('\n') + '\n');
