#!/usr/bin/env node
//
// rf2-r9kf — GENERATOR for `boolean_attr_classes.edn`, the INDEPENDENT
// external anchor for the SSR boolean attribute-value rosters.
//
// WHY THIS EXISTS. `re-frame.ssr.html-helpers` carries three rosters and
// `boolean-attr-class` reads them; BOTH SSR serialisers (the hiccup emitter
// and the structural-tree serialiser `re-frame.ssr.ui-tree`) consult that one
// classifier. Consolidating them was right — one roster, no drift — but it
// collapsed two independent checks into one: a parity test BETWEEN two
// consumers of a shared table cannot validate the table. Both sides agree on
// the same wrong answer, which is exactly how the rf2-r9kf audit found four
// presence names and four stringifying names missing while every in-repo test
// stayed green.
//
// So the roster is pinned against something OUTSIDE it: react-dom itself,
// the installed copy, measured rather than restated.
//
// WHAT IS MEASURED, AND WHY THE CANDIDATE LIST IS REACT'S TOO. A candidate
// list taken from our own roster would reproduce the blind spot verbatim — a
// name missing from the roster would be missing from the probe. Both halves
// therefore come from react-dom:
//
//   1. The candidate NAMES are the values of react-dom's own
//      `possibleStandardNames` table, scraped out of the development build
//      (every canonical DOM prop name React knows).
//   2. A candidate is a BOOLEAN-CLASS attribute iff React's development build
//      renders `{name: true}` WITHOUT its "Received `true` for a non-boolean
//      attribute" warning. That is React's own statement of which attributes
//      take booleans, and it is what bounds this probe to a roster correction
//      rather than a react-dom clone: the numeric attributes (`cols`, `rows`,
//      `size`, `span`, `rowSpan`, `start`) coerce a boolean into markup
//      through arithmetic rather than through a boolean class, they warn, and
//      they are excluded here on React's own verdict, not on ours.
//   3. The recorded MARKUP is the production build's, for `true` and for
//      `false`, on the first probe element where the attribute reaches markup
//      at all (`checked` needs an `<input>`, `selected` an `<option>`,
//      `multiple` a `<select>`, `muted` a `<video>`; everything else lands on
//      a `<div>`). Where two elements disagree about the class, the generator
//      ABORTS rather than picking one.
//
// The EDN carries React's bytes and nothing else — no class label, no
// restatement of our own rules. `re_frame/ssr_boolean_attr_react_parity_test`
// derives the class from those bytes in Clojure and compares it with
// `html/boolean-attr-class`. The derivation is visible in the test; the
// evidence is React's.
//
// REGENERATE (from `implementation/`, where node_modules lives):
//
//   node ssr/test/react_dom_probe/boolean_attr_classes.cjs \
//     > ssr/test/react_dom_probe/boolean_attr_classes.edn
//
// The output is deterministic (rows sorted by prop name), so a regeneration
// that changes nothing produces no diff. Bumping react-dom is what should
// change it — and if the new version moves a row, the parity test reds and
// names the attribute.

'use strict';

const fs = require('fs');
const path = require('path');

const reactDomRoot = path.dirname(require.resolve('react-dom/package.json'));
const reactDomVersion = JSON.parse(
  fs.readFileSync(path.join(reactDomRoot, 'package.json'), 'utf8')
).version;

// The LEGACY node builds, because they are the pair that exports
// `renderToStaticMarkup` — the one-shot string API this probe needs. Both
// carry the same `pushAttribute`/`validateProperty` code as the streaming
// builds, so the attribute verdicts are react-dom's, not a legacy dialect's.
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
  // The literal is a flat object of string keys to string values.
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
const candidates = scrapePossibleStandardNames(devSource);

// `aria-*` / `data-*` are a PREFIX rule in react-dom's default branch rather
// than named entries, so they cannot come out of the table above. Two
// representatives are probed so the fixture pins the prefix rule too.
const prefixProbes = ['aria-expanded', 'aria-hidden', 'data-open'];

// ---------------------------------------------------------------------------
// 2. Boolean-class filter — React's own dev warning is the verdict.
// ---------------------------------------------------------------------------

const React = require('react');

const dev = require(devServerPath);

// A missing `renderToStaticMarkup` would make every `acceptsBoolean` probe
// throw and be read as "not a boolean attribute" — an EMPTY fixture that
// looks like an honest measurement. Fail on the entry point instead.
if (typeof dev.renderToStaticMarkup !== 'function') {
  throw new Error(
    `${devServerPath} exports no renderToStaticMarkup — the build layout ` +
    'moved; fix this generator rather than accepting an empty fixture.');
}

const NON_BOOLEAN_WARNING = 'for a non-boolean attribute';

function acceptsBoolean(name) {
  const originalError = console.error;
  const originalWarn = console.warn;
  let warned = false;
  const watch = (...args) => {
    const first = typeof args[0] === 'string' ? args[0] : '';
    if (first.includes(NON_BOOLEAN_WARNING)) warned = true;
  };
  console.error = watch;
  console.warn = watch;
  try {
    dev.renderToStaticMarkup(React.createElement('div', { [name]: true }));
  } catch (_e) {
    // A prop React refuses outright (`dangerouslySetInnerHTML`) is not a
    // boolean-class attribute.
    return false;
  } finally {
    console.error = originalError;
    console.warn = originalWarn;
  }
  return !warned;
}

// ---------------------------------------------------------------------------
// 3. Markup — the production build, on the first element that shows the attr.
// ---------------------------------------------------------------------------

const prod = require(prodServerPath);

if (typeof prod.renderToStaticMarkup !== 'function') {
  throw new Error(`${prodServerPath} exports no renderToStaticMarkup.`);
}

const probeElements = ['div', 'input', 'option', 'select', 'video'];

function markup(element, name, value) {
  try {
    return prod.renderToStaticMarkup(
      React.createElement(element, { [name]: value }));
  } catch (_e) {
    return null;
  }
}

// Matching is CASE-INSENSITIVE and anchored on the leading space react-dom
// writes before every attribute. Case matters because `pushBooleanAttribute`
// lowercases three names on the way out (`autoFocus` → `autofocus=""`), and a
// case-sensitive probe reads that as the attribute being absent — a wrong
// class that still looks like a measurement. The leading space keeps a short
// name from matching inside a tag name or another attribute's value.
function classify(name, trueMarkup, falseMarkup) {
  const has = (html, suffix) =>
    html !== null &&
    html.toLowerCase().includes(' ' + name.toLowerCase() + suffix);
  const mentions = (html) => has(html, '="');
  if (has(trueMarkup, '="true"') && has(falseMarkup, '="false"')) {
    return 'stringify';
  }
  if (has(trueMarkup, '=""') && !mentions(falseMarkup)) return 'presence';
  if (!mentions(trueMarkup) && !mentions(falseMarkup)) return 'absent';
  return 'unknown';
}

function probe(name) {
  let chosen = null;
  for (const element of probeElements) {
    const trueMarkup = markup(element, name, true);
    const falseMarkup = markup(element, name, false);
    if (trueMarkup === null || falseMarkup === null) continue;
    const klass = classify(name, trueMarkup, falseMarkup);
    if (klass === 'absent') continue;
    if (klass === 'unknown') {
      throw new Error(
        `react-dom ${reactDomVersion} rendered <${element} ${name}> in a shape ` +
        `this probe cannot classify: true=${JSON.stringify(trueMarkup)} ` +
        `false=${JSON.stringify(falseMarkup)}`);
    }
    if (chosen === null) {
      chosen = { element, name, trueMarkup, falseMarkup, klass };
    } else if (chosen.klass !== klass) {
      throw new Error(
        `react-dom ${reactDomVersion} classifies ${name} as ${chosen.klass} on ` +
        `<${chosen.element}> and ${klass} on <${element}> — this probe assumes ` +
        'one class per attribute name.');
    }
  }
  if (chosen === null) {
    // Boolean-class by React's warning verdict, yet no element shows it in
    // markup: record it with the `<div>` bytes so the fixture still carries
    // React's answer (both serialisers must drop it too).
    return {
      element: 'div',
      name,
      trueMarkup: markup('div', name, true),
      falseMarkup: markup('div', name, false),
    };
  }
  return chosen;
}

const rows = [];
for (const name of [...candidates, ...prefixProbes]) {
  if (!acceptsBoolean(name)) continue;
  const { element, trueMarkup, falseMarkup } = probe(name);
  if (trueMarkup === null || falseMarkup === null) continue;
  rows.push({ name, element, trueMarkup, falseMarkup });
}
rows.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));

// An empty or badly shrunken fixture is the failure mode that reads as a
// clean measurement: the parity test would iterate no rows and pass. React 19
// carries three dozen boolean-class names; refuse anything near zero.
if (rows.length < 30) {
  throw new Error(
    `only ${rows.length} boolean-class rows measured against react-dom ` +
    `${reactDomVersion} — that is not a plausible fixture; investigate the ` +
    'probe rather than committing it.');
}

// ---------------------------------------------------------------------------
// 4. EDN.
// ---------------------------------------------------------------------------

const ednString = (s) =>
  '"' + s.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';

const out = [];
out.push(';; GENERATED FILE — do not hand-edit.');
out.push(';;');
out.push(';; react-dom boolean attribute-value evidence for rf2-r9kf. Every string');
out.push(';; below is react-dom\'s own output, measured by');
out.push(';; `react_dom_probe/boolean_attr_classes.cjs` against the installed');
out.push(';; package; nothing here restates a re-frame rule. The class is DERIVED');
out.push(';; from these bytes in `re_frame/ssr_boolean_attr_react_parity_test.clj`.');
out.push(';;');
out.push(';; Regenerate from `implementation/`:');
out.push(';;   node ssr/test/react_dom_probe/boolean_attr_classes.cjs \\');
out.push(';;     > ssr/test/react_dom_probe/boolean_attr_classes.edn');
out.push('{:react-dom-version ' + ednString(reactDomVersion));
out.push(' :generated-by "implementation/ssr/test/react_dom_probe/boolean_attr_classes.cjs"');
out.push(' :probe-elements [' + probeElements.map(ednString).join(' ') + ']');
out.push(' :rows');
out.push(' [');
for (const row of rows) {
  out.push('  {:attribute ' + ednString(row.name) +
           ' :element ' + ednString(row.element));
  out.push('   :true-markup  ' + ednString(row.trueMarkup));
  out.push('   :false-markup ' + ednString(row.falseMarkup) + '}');
}
out.push(' ]}');
process.stdout.write(out.join('\n') + '\n');
