#!/usr/bin/env node
/*
 * Tests for `dev-testbed.cjs` arg resolution and URL printing, plus the guard
 * that its DEV_HTTP map mirrors shadow-cljs.edn's :dev-http map. Requiring the
 * module does not spawn shadow-cljs (the CLI body is behind
 * `require.main === module`). Discovered by `npm run test:scripts`.
 */

'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { DEV_HTTP, resolveArgs, urlsForBuild } = require('./dev-testbed.cjs');
const { IMPL_ROOT } = require('./_path-policy.cjs');

// shadow-cljs.edn is the source of truth for which builds are served on a
// :dev-http port, and nothing else ties DEV_HTTP to it. A focused hand-rolled
// parser reads the two simple shapes needed: the port -> roots map and each
// build's :output-dir.

function readShadowEdn() {
  return fs.readFileSync(path.join(IMPL_ROOT, 'shadow-cljs.edn'), 'utf8');
}

// shadow-cljs.edn carries no `;` inside the strings parsed here.
function stripEdnComments(edn) {
  return edn
    .split('\n')
    .map((line) => {
      const i = line.indexOf(';');
      return i === -1 ? line : line.slice(0, i);
    })
    .join('\n');
}

// { port -> [roots] }. A port's value is a bare roots vector or a map whose
// leading :roots key holds one, so the first `[...]` after each port is its roots.
function parseDevHttp(edn) {
  const src = stripEdnComments(edn);
  const start = src.indexOf(':dev-http');
  assert.ok(start !== -1, 'shadow-cljs.edn must contain a :dev-http map');
  const open = src.indexOf('{', start);
  assert.ok(open !== -1, ':dev-http must be followed by a map');
  let depth = 0;
  let end = -1;
  for (let i = open; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) {
        end = i;
        break;
      }
    }
  }
  assert.ok(end !== -1, ':dev-http map must be balanced');
  const body = src.slice(open + 1, end);
  // A port is a 2-5 digit integer directly followed by its value's `[` or `{`.
  const entries = {};
  const portRe = /(\d{2,5})\s*[[{]/g;
  const ports = [];
  let pm;
  while ((pm = portRe.exec(body)) !== null) {
    ports.push({ port: Number(pm[1]), at: pm.index });
  }
  for (let i = 0; i < ports.length; i++) {
    const from = ports[i].at;
    const to = i + 1 < ports.length ? ports[i + 1].at : body.length;
    const slice = body.slice(from, to);
    const vec = slice.match(/\[([^\]]*)\]/);
    if (!vec) continue;
    const roots = (vec[1].match(/"([^"]*)"/g) || []).map((s) => s.slice(1, -1));
    entries[ports[i].port] = roots;
  }
  return entries;
}

// { outputDir -> buildId } over the top-level `:<group>/<name> { ... }` build defs.
function parseBuildOutputDirs(edn) {
  const src = stripEdnComments(edn);
  const map = {};
  const idRe = /(:[a-zA-Z][\w.-]*\/[\w.-]+)\s*\{/g;
  const ids = [];
  let m;
  while ((m = idRe.exec(src)) !== null) {
    ids.push({ id: m[1], at: m.index });
  }
  for (let i = 0; i < ids.length; i++) {
    const from = ids[i].at;
    const to = i + 1 < ids.length ? ids[i + 1].at : src.length;
    const slice = src.slice(from, to);
    const od = slice.match(/:output-dir\s+"([^"]*)"/);
    if (od) map[od[1]] = ids[i].id;
  }
  return map;
}

// { buildId -> port }, matching each port's `out/...` root to a build def.
function servedBuildPorts(edn) {
  const devHttp = parseDevHttp(edn);
  const byOutputDir = parseBuildOutputDirs(edn);
  const result = {};
  for (const [port, roots] of Object.entries(devHttp)) {
    const outRoot = roots.find((r) => r.startsWith('out/'));
    if (!outRoot) continue; // no compiled build behind this port — skip.
    const buildId = byOutputDir[outRoot];
    assert.ok(
      buildId,
      `:dev-http port ${port} serves '${outRoot}' but no build def has that ` +
        `:output-dir — parser or shadow-cljs.edn drift`,
    );
    result[buildId] = Number(port);
  }
  return result;
}

let failed = 0;

function it(label, f) {
  try {
    f();
    console.log(`  PASS  ${label}`);
  } catch (err) {
    failed++;
    console.error(`  FAIL  ${label}`);
    console.error(`        ${err.message || err}`);
  }
}

console.log('dev-testbed arg-resolution tests');

it('duplicate explicit build-ids are deduped, order preserved', () => {
  assert.deepStrictEqual(
    resolveArgs([
      ':examples/login-form',
      ':examples/standard-epochs',
      ':examples/login-form',
    ]),
    [':examples/login-form', ':examples/standard-epochs'],
  );
});

it('non-build flags are NOT deduped (passed verbatim)', () => {
  assert.deepStrictEqual(
    resolveArgs([':testbeds/panel-gallery', '--verbose', '--verbose']),
    [':testbeds/panel-gallery', '--verbose', '--verbose'],
  );
});

it('urlsForBuild prints the live URL for a plain build', () => {
  assert.deepStrictEqual(urlsForBuild(':examples/standard-epochs'), [
    'http://localhost:8031/',
  ]);
});

it('urlsForBuild prints the live + /#/stories URLs for a Story build', () => {
  assert.deepStrictEqual(urlsForBuild(':examples/login-form'), [
    'http://localhost:8043/',
    'http://localhost:8043/#/stories',
  ]);
});

it('urlsForBuild returns [] for a build with no dev-http port', () => {
  assert.deepStrictEqual(urlsForBuild(':examples/counter'), []);
});

// Exact in both directions: a served build missing from DEV_HTTP prints no URL,
// and an orphaned DEV_HTTP entry prints a live-looking URL for a build
// shadow-cljs will reject.
it('DEV_HTTP mirrors every :dev-http-served build in shadow-cljs.edn, port for port (drift guard)', () => {
  const devHttpPorts = Object.fromEntries(Object.entries(DEV_HTTP).map(([id, info]) => [id, info.port]));
  assert.deepStrictEqual(devHttpPorts, servedBuildPorts(readShadowEdn()));
});

if (failed > 0) {
  console.error(`\n${failed} test(s) failed.`);
  process.exit(1);
} else {
  console.log('\nAll dev-testbed tests passed.');
}
