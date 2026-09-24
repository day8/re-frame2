#!/usr/bin/env node
//
// GENERATOR for `javascript_url.edn`, the external anchor for the
// `javascript:` URL rule in the SSR hiccup emitters.
//
// WHY THIS EXISTS. The markup `re-frame.ssr/render-to-string` and the
// streaming shell walk paint is hydrated by a Reagent-tier client, which
// paints through react-dom. react-dom's `setProp` swaps a `javascript:` URL in
// `href`, `src`, `action`, `formAction` and `xlinkHref` (and in `data` on an
// `<object>`) for a URL that throws, before it ever reaches `setAttribute`.
// React does not patch an attribute at hydration, so an emitter that wrote the
// value unchanged would leave the unblocked URL live on the hydrated page while
// the client's own render would have blocked it. The emitters apply the same
// rule, and `re_frame/ssr_javascript_url_react_parity_test.clj` checks it
// against this file.
//
// WHAT IS MEASURED, AND WHY THE SERVER BUILD STANDS IN FOR THE CLIENT. This
// generator runs in Node with no DOM, so it cannot mount the client. Instead
// it renders each row through react-dom/server and records the attribute
// value React writes. That value is the one the client paints, because both
// builds apply ONE rule: the same `isJavaScriptProtocol` regex, the same
// substituted URL, the same prop cases (`href` / `src` / `action` /
// `formAction` / `xlinkHref`, `data` only on an `<object>`, none on a custom
// element). The generator checks the first two by reading both builds' source
// text, and it ABORTS if they differ, so a react-dom bump that parts the two
// builds cannot produce a plausible fixture. The prop cases were read from
// both builds' `pushAttribute` / `setProp` by hand.
//
// REGENERATE (from `implementation/`, where node_modules lives):
//
//   node ssr/test/react_dom_probe/javascript_url.cjs \
//     > ssr/test/react_dom_probe/javascript_url.edn
//
// The output is deterministic (the corpus below is a fixed list), so a
// regeneration that changes nothing produces no diff.

'use strict';

const fs = require('fs');
const path = require('path');

const reactDomRoot = path.dirname(require.resolve('react-dom/package.json'));
const reactDomVersion = JSON.parse(
  fs.readFileSync(path.join(reactDomRoot, 'package.json'), 'utf8')
).version;

// The LEGACY node production build exports `renderToStaticMarkup`, the
// one-shot string API this probe needs (as in `boolean_attr_classes.cjs`).
const serverPath = path.join(
  reactDomRoot, 'cjs', 'react-dom-server-legacy.node.production.js');
const clientPath = path.join(
  reactDomRoot, 'cjs', 'react-dom-client.production.js');

// ---------------------------------------------------------------------------
// 1. One rule in both builds.
// ---------------------------------------------------------------------------

const BLOCKED_URL =
  "javascript:throw new Error('React has blocked a javascript: URL as a security precaution.')";

function ruleOf(buildPath) {
  const source = fs.readFileSync(buildPath, 'utf8');
  const match = /isJavaScriptProtocol\s*=\s*(\/[^\n]*?\/i)/.exec(source);
  if (match === null) {
    throw new Error(
      `${buildPath} carries no \`isJavaScriptProtocol = /…/i\` — the scrape ` +
      'anchor moved; fix this generator rather than the fixture.');
  }
  if (!source.includes(JSON.stringify(BLOCKED_URL))) {
    throw new Error(
      `${buildPath} does not substitute ${JSON.stringify(BLOCKED_URL)} — ` +
      'react-dom changed the blocked URL; re-measure before regenerating.');
  }
  return match[1];
}

const serverRule = ruleOf(serverPath);
const clientRule = ruleOf(clientPath);
if (serverRule !== clientRule) {
  throw new Error(
    `react-dom ${reactDomVersion}'s server and client builds test javascript: ` +
    `URLs differently (${serverRule} vs ${clientRule}), so server bytes no ` +
    'longer stand in for what the client paints. Re-measure.');
}

// ---------------------------------------------------------------------------
// 2. The corpus.
// ---------------------------------------------------------------------------

// Every spelling `isJavaScriptProtocol` matches: it ignores case, skips
// leading C0 controls and spaces, and allows a tab, LF or CR between the
// letters. Then three values it leaves alone, the controls: an ordinary URL,
// and two near-misses a looser copy of the rule would wrongly block.
const VALUES = [
  ['plain', 'javascript:alert(1)'],
  ['mixed-case', 'JaVaScRiPt:alert(1)'],
  ['leading-spaces', '  javascript:alert(1)'],
  ['leading-c0-controls', '\u0001\u001Fjavascript:alert(1)'],
  ['tab-lf-cr-inside', 'java\tscr\nipt\r:alert(1)'],
  ['trailing-space', 'javascript:alert(1) '],
  ['https', 'https://example.com/?q=javascript:x'],
  ['space-before-colon', 'javascript :alert(1)'],
  ['leading-no-break-space', ' javascript:alert(1)'],
];

// [element, React prop, the attribute name React writes, parent or null].
// `font-face` is one of the eight hyphenated tags react-dom does NOT treat as
// custom elements, so it blocks there too. The last two rows are where
// react-dom does NOT block: `data` is a URL only on an `<object>`, and a
// custom element's props are written verbatim.
const TARGETS = [
  ['a', 'href', 'href', null],
  ['img', 'src', 'src', null],
  ['form', 'action', 'action', null],
  ['button', 'formAction', 'formAction', null],
  ['use', 'xlinkHref', 'xlink:href', 'svg'],
  ['object', 'data', 'data', null],
  ['font-face', 'href', 'href', null],
  ['div', 'data', 'data', null],
  ['my-widget', 'href', 'href', null],
];
const BLOCKING_TARGETS = 7;

const React = require('react');
const server = require(serverPath);

if (typeof server.renderToStaticMarkup !== 'function') {
  throw new Error(
    `${serverPath} exports no renderToStaticMarkup — the build layout moved; ` +
    'fix this generator rather than accepting an empty fixture.');
}

function render(element, prop, parent, value) {
  const node = React.createElement(element, { [prop]: value });
  return server.renderToStaticMarkup(
    parent === null ? node : React.createElement(parent, null, node));
}

// react-dom escapes all five of `& < > " '` in an attribute value; decoding
// them yields the value the parsed attribute holds.
function decode(s) {
  return s.replace(/&quot;/g, '"').replace(/&#x27;/g, "'")
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
}

const rows = [];
for (const [element, prop, attribute, parent] of TARGETS) {
  for (const [label, value] of VALUES) {
    const markup = render(element, prop, parent, value);
    const match = new RegExp(`<${element}[^>]*? ${attribute}="([^"]*)"`).exec(markup);
    if (match === null) {
      throw new Error(
        `react-dom ${reactDomVersion} wrote no ${attribute} on <${element}> ` +
        `for ${label}: ${JSON.stringify(markup)}`);
    }
    const painted = decode(match[1]);
    rows.push({ element, prop, attribute, parent, label, value, markup, painted,
                blocked: painted === BLOCKED_URL });
  }
}

// A fixture that blocks nothing, or everything, would pass the parity test
// against an emitter that got the rule backwards. Refuse both.
const blockedCount = rows.filter((row) => row.blocked).length;
if (blockedCount !== 6 * BLOCKING_TARGETS) {
  throw new Error(
    `react-dom ${reactDomVersion} blocked ${blockedCount} of ${rows.length} ` +
    'rows; the corpus expects the six blocking spellings blocked in each of ' +
    `the ${BLOCKING_TARGETS} blocking targets and nothing else. Investigate ` +
    'before committing.');
}

// ---------------------------------------------------------------------------
// 3. EDN.
// ---------------------------------------------------------------------------

// Control characters are written as escapes, so the fixture holds no raw CR
// or LF inside a string for line-ending normalisation to rewrite.
function ednString(s) {
  let out = '"';
  for (const ch of s) {
    const code = ch.codePointAt(0);
    if (ch === '\\') out += '\\\\';
    else if (ch === '"') out += '\\"';
    else if (ch === '\n') out += '\\n';
    else if (ch === '\r') out += '\\r';
    else if (ch === '\t') out += '\\t';
    else if (code < 0x20 || code === 0xA0) {
      out += '\\u' + code.toString(16).toUpperCase().padStart(4, '0');
    } else out += ch;
  }
  return out + '"';
}

const out = [];
out.push(';; GENERATED FILE — do not hand-edit.');
out.push(';;');
out.push(';; react-dom `javascript:` URL evidence for rf2-w1hd8. `:painted` is');
out.push(';; the attribute value react-dom wrote, entity-decoded, which is the');
out.push(';; value the parsed attribute holds; `:markup` is the whole output it');
out.push(';; was read out of. The server and client builds apply one rule');
out.push(';; (`:rule` below, checked equal in both by the generator), so this is');
out.push(';; also what a hydrating react-dom client paints. Measured by');
out.push(';; `react_dom_probe/javascript_url.cjs` against the installed package;');
out.push(';; compared with the SSR hiccup emitters in');
out.push(';; `re_frame/ssr_javascript_url_react_parity_test.clj`.');
out.push(';;');
out.push(';; Regenerate from `implementation/`:');
out.push(';;   node ssr/test/react_dom_probe/javascript_url.cjs \\');
out.push(';;     > ssr/test/react_dom_probe/javascript_url.edn');
out.push('{:react-dom-version ' + ednString(reactDomVersion));
out.push(' :generated-by "implementation/ssr/test/react_dom_probe/javascript_url.cjs"');
out.push(' :rule ' + ednString(serverRule));
out.push(' :blocked-url ' + ednString(BLOCKED_URL));
out.push(' :rows');
out.push(' [');
for (const row of rows) {
  out.push('  {:element ' + ednString(row.element) +
           ' :prop ' + ednString(row.prop) +
           ' :attribute ' + ednString(row.attribute) +
           ' :parent ' + (row.parent === null ? 'nil' : ednString(row.parent)) +
           ' :label ' + ednString(row.label));
  out.push('   :value   ' + ednString(row.value));
  out.push('   :blocked? ' + row.blocked);
  out.push('   :painted ' + ednString(row.painted));
  out.push('   :markup  ' + ednString(row.markup) + '}');
}
out.push(' ]}');
process.stdout.write(out.join('\n') + '\n');
