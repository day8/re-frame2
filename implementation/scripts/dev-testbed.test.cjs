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
const { readEdn, isMap, mapGetKeyword, shadowBuildEntries } = require('./lib/edn.cjs');

// shadow-cljs.edn is the source of truth for which builds are served on a
// :dev-http port, and nothing else ties DEV_HTTP to it. It is read as
// STRUCTURE with the shared EDN reader, which throws on anything it cannot read
// rather than hand back a short roster.

function readShadowEdn() {
  return fs.readFileSync(path.join(IMPL_ROOT, 'shadow-cljs.edn'), 'utf8');
}

// { port -> [roots] }. A port's value is a root string, a roots vector, or a
// map whose :roots (or :root) key holds one of those.
function parseDevHttp(config) {
  const devHttp = mapGetKeyword(config, 'dev-http');
  assert.ok(isMap(devHttp), 'shadow-cljs.edn must contain a :dev-http map');
  const entries = {};
  for (const [port, value] of devHttp.entries) {
    assert.ok(port && port.edn === 'number', 'a :dev-http key is not a port number');
    const spec = isMap(value)
      ? mapGetKeyword(value, 'roots') ?? mapGetKeyword(value, 'root')
      : value;
    const roots = spec && spec.edn === 'vector' ? spec.items : [spec];
    assert.ok(
      roots.every((r) => r && r.edn === 'string'),
      `:dev-http port ${port.raw} has no readable roots`,
    );
    entries[port.raw] = roots.map((r) => r.value);
  }
  return entries;
}

// { outputDir -> buildId } over every build in :builds. A build with no
// :output-dir (a :node-test build writes :output-to) serves nothing.
function parseBuildOutputDirs(config) {
  const map = {};
  for (const [id, build] of shadowBuildEntries(config)) {
    const od = mapGetKeyword(build, 'output-dir');
    if (od === undefined) continue;
    assert.ok(od && od.edn === 'string', `build :${id} has a :output-dir that is not a string`);
    map[od.value] = `:${id}`;
  }
  return map;
}

// { buildId -> port }, matching each port's `out/...` root to a build def.
function servedBuildPorts(edn) {
  const config = readEdn(edn);
  const devHttp = parseDevHttp(config);
  const byOutputDir = parseBuildOutputDirs(config);
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

it('duplicate explicit build-ids are deduped, order preserved; flags pass verbatim', () => {
  assert.deepStrictEqual(
    resolveArgs([':examples/login-form', '--verbose', ':examples/standard-epochs', ':examples/login-form', '--verbose']),
    [':examples/login-form', '--verbose', ':examples/standard-epochs', '--verbose'],
  );
});

it('urlsForBuild: the live URL, plus /#/stories for a Story build, and [] with no dev-http port', () => {
  assert.deepStrictEqual(
    [':examples/standard-epochs', ':examples/login-form', ':examples/counter'].map(urlsForBuild),
    [
      ['http://localhost:8031/'],
      ['http://localhost:8043/', 'http://localhost:8043/#/stories'],
      [],
    ],
  );
});

// Exact in both directions: a served build missing from DEV_HTTP prints no URL,
// and an orphaned DEV_HTTP entry prints a live-looking URL for a build
// shadow-cljs will reject.
it('DEV_HTTP mirrors every :dev-http-served build in shadow-cljs.edn, port for port (drift guard)', () => {
  const devHttpPorts = Object.fromEntries(Object.entries(DEV_HTTP).map(([id, info]) => [id, info.port]));
  assert.deepStrictEqual(devHttpPorts, servedBuildPorts(readShadowEdn()));
});

// A build id need not carry a namespace, so a port serving `:solo` maps to it
// like any other.
it('servedBuildPorts reads a served build whose id has no namespace', () => {
  const edn =
    '{:dev-http {8099 {:roots ["src/solo" "out/solo"]}}\n' +
    ' :builds {:solo {:target :browser :output-dir "out/solo"}}}';
  assert.deepStrictEqual(servedBuildPorts(edn), { ':solo': 8099 });
});

if (failed > 0) {
  console.error(`\n${failed} test(s) failed.`);
  process.exit(1);
} else {
  console.log('\nAll dev-testbed tests passed.');
}
