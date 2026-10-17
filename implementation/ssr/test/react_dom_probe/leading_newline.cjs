#!/usr/bin/env node
//
// GENERATOR for `leading_newline.edn`, the external anchor for the
// leading-LF compensation in the SSR hiccup emitter.
//
// WHY THIS EXISTS. The HTML parser drops the first LF after a `<pre>`,
// `<listing>` or `<textarea>` start tag, so react-dom/server writes one
// compensating LF ahead of a body that begins with one. Which bodies it
// compensates is a fact of react-dom's source, per element and per channel:
// a string child under all three, a `value` / `defaultValue` under
// `<textarea>`, and a string `dangerouslySetInnerHTML.__html` under `<pre>` /
// `<listing>`. A table of those cases written by the same hand as the emitter
// would share its mistakes, so
// `re_frame/ssr_leading_newline_react_parity_test.clj` checks the emitter
// against React's own bytes, recorded here.
//
// WHAT IS MEASURED. Each element and channel below renders three bodies
// through the installed react-dom's `renderToStaticMarkup`: one beginning
// with LF, one beginning with CR LF (React tests the first character for LF
// alone), and one with no leading newline. The bodies carry markup, so the
// record also shows which channel React escapes and which it writes raw.
// React refuses `dangerouslySetInnerHTML` on a `<textarea>`; such a row is
// recorded under `:refused` with React's message instead of markup.
//
// REGENERATE (from `implementation/`, where node_modules lives):
//
//   node ssr/test/react_dom_probe/leading_newline.cjs \
//     > ssr/test/react_dom_probe/leading_newline.edn
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
// one-shot string API this probe needs (as in `javascript_url.cjs`).
const serverPath = path.join(
  reactDomRoot, 'cjs', 'react-dom-server-legacy.node.production.js');

const React = require('react');
const server = require(serverPath);

if (typeof server.renderToStaticMarkup !== 'function') {
  throw new Error(
    `${serverPath} exports no renderToStaticMarkup — the build layout moved; ` +
    'fix this generator rather than accepting an empty fixture.');
}

// ---------------------------------------------------------------------------
// 1. The corpus.
// ---------------------------------------------------------------------------

// [label, body]. No apostrophe: react-dom and the emitter spell its entity
// differently (`&#x27;` / `&#39;`), which is not what this probe measures.
const BODIES = [
  ['leading-lf', '\n<b>code</b> & more'],
  ['leading-cr-lf', '\r\n<b>code</b>'],
  ['no-leading-lf', '<b>code</b>'],
];

// [channel, the props React is handed the body through, the elements].
const CHANNELS = [
  ['child', (body) => ({ children: body }), ['pre', 'listing', 'textarea']],
  ['inner-html', (body) => ({ dangerouslySetInnerHTML: { __html: body } }),
   ['pre', 'listing', 'textarea']],
  ['value', (body) => ({ value: body }), ['textarea']],
  ['default-value', (body) => ({ defaultValue: body }), ['textarea']],
];

const rows = [];
const refused = [];
for (const [channel, propsOf, elements] of CHANNELS) {
  for (const element of elements) {
    for (const [label, body] of BODIES) {
      let markup;
      try {
        markup = server.renderToStaticMarkup(
          React.createElement(element, propsOf(body)));
      } catch (e) {
        refused.push({ element, channel, label, message: e.message });
        continue;
      }
      rows.push({ element, channel, label, body, markup });
    }
  }
}

// ---------------------------------------------------------------------------
// 2. EDN.
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
    else if (code < 0x20) {
      out += '\\u' + code.toString(16).toUpperCase().padStart(4, '0');
    } else out += ch;
  }
  return out + '"';
}

const out = [];
out.push(';; GENERATED FILE — do not hand-edit.');
out.push(';;');
out.push(';; react-dom leading-LF evidence for the newline-eating elements.');
out.push(';; `:markup` is what react-dom/server wrote for `:body` handed to');
out.push(';; `:element` through `:channel`; `:refused` holds the rows React');
out.push(';; throws on, with its message. Measured by');
out.push(';; `react_dom_probe/leading_newline.cjs` against the installed package;');
out.push(';; compared with `re-frame.ssr/render-to-string` in');
out.push(';; `re_frame/ssr_leading_newline_react_parity_test.clj`.');
out.push(';;');
out.push(';; Regenerate from `implementation/`:');
out.push(';;   node ssr/test/react_dom_probe/leading_newline.cjs \\');
out.push(';;     > ssr/test/react_dom_probe/leading_newline.edn');
out.push('{:react-dom-version ' + ednString(reactDomVersion));
out.push(' :generated-by "implementation/ssr/test/react_dom_probe/leading_newline.cjs"');
out.push(' :rows');
out.push(' [');
for (const row of rows) {
  out.push('  {:element ' + ednString(row.element) +
           ' :channel ' + ednString(row.channel) +
           ' :label ' + ednString(row.label));
  out.push('   :body   ' + ednString(row.body));
  out.push('   :markup ' + ednString(row.markup) + '}');
}
out.push(' ]');
out.push(' :refused');
out.push(' [');
for (const row of refused) {
  out.push('  {:element ' + ednString(row.element) +
           ' :channel ' + ednString(row.channel) +
           ' :label ' + ednString(row.label));
  out.push('   :message ' + ednString(row.message) + '}');
}
out.push(' ]}');
process.stdout.write(out.join('\n') + '\n');
