#!/usr/bin/env node
/*
 * Tests for `dev-testbed.cjs` arg resolution + URL printing.
 *
 * Standalone node-runnable suite — no external test framework, mirroring
 * `_path-policy.test.cjs`. Each test logs PASS / FAIL; the process exits
 * 0 only when every test passes. Wired into `package.json` via
 * `test:script-helpers`.
 *
 * Requiring `dev-testbed.cjs` does NOT spawn shadow-cljs: the CLI body is
 * guarded by `require.main === module`, so importing it here only loads
 * the pure `resolveArgs` / `urlsForBuild` helpers + the DEV_HTTP map.
 *
 * Group aliases (xray / stories / epochs / all) were removed (rf2-trlj7):
 * `npm run dev` takes EXPLICIT build-ids only. `resolveArgs` now just
 * passes tokens through, de-duplicating build-ids in first-seen order.
 */

'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { DEV_HTTP, resolveArgs, urlsForBuild } = require('./dev-testbed.cjs');
const { IMPL_ROOT } = require('./_path-policy.cjs');

// ---------------------------------------------------------------------------
// Drift guard helpers (rf2-d3fb7.1).
//
// shadow-cljs.edn is the source of truth for which dev-testbed builds are
// served on a `:dev-http` port; DEV_HTTP in dev-testbed.cjs mirrors it
// (READ-only — shadow-cljs.edn is hot-zone). The managed-http omission
// (port 8035 present in :dev-http, absent from DEV_HTTP + the README) shipped
// green because nothing tied the two surfaces together. This guard parses the
// `:dev-http` map straight out of shadow-cljs.edn and asserts every served
// build appears in DEV_HTTP at the matching port, so a future testbed
// addition that forgets the launcher map fails THIS gate instead of silently
// printing no URL.
//
// The `:dev-http` map keys port -> [roots]; the build-id is recovered by
// matching the build's `:output-dir` root (`out/...`) against the build defs
// in the same file. We deliberately hand-roll a focused parser rather than
// pull in an EDN dependency — the shapes we read (a flat port->vector map and
// per-build `:output-dir` strings) are simple and stable.
// ---------------------------------------------------------------------------

/** Read shadow-cljs.edn from the implementation root. */
function readShadowEdn() {
  return fs.readFileSync(path.join(IMPL_ROOT, 'shadow-cljs.edn'), 'utf8');
}

/**
 * Strip line comments (`;` to end-of-line) so they can't be mistaken for
 * map content. Naive but sufficient: shadow-cljs.edn carries no `;` inside
 * the string literals we parse (output-dir / dev-http roots).
 */
function stripEdnComments(edn) {
  return edn
    .split('\n')
    .map((line) => {
      const i = line.indexOf(';');
      return i === -1 ? line : line.slice(0, i);
    })
    .join('\n');
}

/**
 * Parse the `:dev-http` map into { port -> [roots...] }. The map binds an
 * integer port to a vector of string roots.
 */
function parseDevHttp(edn) {
  const src = stripEdnComments(edn);
  const start = src.indexOf(':dev-http');
  assert.ok(start !== -1, 'shadow-cljs.edn must contain a :dev-http map');
  const open = src.indexOf('{', start);
  assert.ok(open !== -1, ':dev-http must be followed by a map');
  // Walk to the matching close brace.
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
  // Each entry is `<port> [ "root" "root" ... ]`.
  const entries = {};
  const re = /(\d{2,5})\s*\[([^\]]*)\]/g;
  let m;
  while ((m = re.exec(body)) !== null) {
    const port = Number(m[1]);
    const roots = (m[2].match(/"([^"]*)"/g) || []).map((s) => s.slice(1, -1));
    entries[port] = roots;
  }
  return entries;
}

/**
 * Parse every build def's `:output-dir` into { outputDir -> buildId }.
 * Build defs are top-level `:<group>/<name> { ... :output-dir "out/..." ...}`.
 */
function parseBuildOutputDirs(edn) {
  const src = stripEdnComments(edn);
  const map = {};
  // build-id immediately followed by an opening brace, then somewhere a
  // :output-dir string before the next build-id. We scan build-id positions
  // and read the FIRST :output-dir that follows each.
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

/**
 * Recover { buildId -> port } for every `:dev-http`-served dev-testbed build,
 * by matching each port's `out/...` root against the build defs.
 */
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

console.log('dev-testbed arg-resolution tests (rf2-trlj7)');

it('a single explicit build-id passes through unchanged', () => {
  assert.deepStrictEqual(resolveArgs([':examples/standard-epochs']), [
    ':examples/standard-epochs',
  ]);
});

it('several explicit build-ids pass through in order', () => {
  assert.deepStrictEqual(
    resolveArgs([':examples/standard-epochs', ':examples/routes-epochs']),
    [':examples/standard-epochs', ':examples/routes-epochs'],
  );
});

it('extra shadow-cljs flags pass straight through', () => {
  assert.deepStrictEqual(resolveArgs([':testbeds/panel-gallery', '--verbose']), [
    ':testbeds/panel-gallery',
    '--verbose',
  ]);
});

it('a removed group name is NOT expanded — it passes through as a literal', () => {
  // Post-removal: `xray` is just a token. It reaches shadow-cljs as an
  // unknown build-id (which shadow-cljs reports), never a 6-build expansion.
  assert.deepStrictEqual(resolveArgs(['xray']), ['xray']);
  assert.deepStrictEqual(resolveArgs(['stories']), ['stories']);
  assert.deepStrictEqual(resolveArgs(['all']), ['all']);
  assert.deepStrictEqual(resolveArgs(['epochs']), ['epochs']);
});

it('duplicate explicit build-ids are deduped, order preserved', () => {
  assert.deepStrictEqual(
    resolveArgs([':examples/standard-epochs', ':examples/standard-epochs']),
    [':examples/standard-epochs'],
  );
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
  assert.deepStrictEqual(urlsForBuild(':testbeds/panel-gallery'), [
    'http://localhost:8765/',
  ]);
});

it('urlsForBuild prints the live + /#/stories URLs for a Story build', () => {
  assert.deepStrictEqual(urlsForBuild(':examples/login-form'), [
    'http://localhost:8043/',
    'http://localhost:8043/#/stories',
  ]);
  assert.deepStrictEqual(urlsForBuild(':examples/nine-states-with-stories'), [
    'http://localhost:8040/',
    'http://localhost:8040/#/stories',
  ]);
});

it('urlsForBuild returns [] for a build with no dev-http port', () => {
  assert.deepStrictEqual(urlsForBuild(':examples/counter'), []);
  assert.deepStrictEqual(urlsForBuild('--verbose'), []);
});

it('every DEV_HTTP build-id is a colon-prefixed build coord', () => {
  for (const build of Object.keys(DEV_HTTP)) {
    assert.ok(build.startsWith(':'), `${build} should be a :build coord`);
  }
});

it('urlsForBuild prints the live URL for the managed-http testbed (rf2-d3fb7.1)', () => {
  assert.deepStrictEqual(urlsForBuild(':examples/managed-http'), [
    'http://localhost:8035/',
  ]);
});

// --- Drift guard (rf2-d3fb7.1) --------------------------------------------
// Tie DEV_HTTP to shadow-cljs.edn's :dev-http map so the next testbed
// addition can't ship with a served port that the launcher knows nothing
// about (which is exactly how managed-http/8035 shipped green). Parses
// shadow-cljs.edn directly (READ-only — it's a hot-zone file) and asserts
// every :dev-http-served build appears in DEV_HTTP at the matching port.
it('DEV_HTTP covers every :dev-http-served build in shadow-cljs.edn (drift guard)', () => {
  const edn = readShadowEdn();
  const served = servedBuildPorts(edn);

  // Sanity-check the parser actually found the known builds — a silently
  // empty parse must NOT pass this guard vacuously.
  assert.ok(
    Object.keys(served).length >= 6,
    `parser recovered only ${Object.keys(served).length} served builds from ` +
      `shadow-cljs.edn :dev-http — expected the full dev-testbed set`,
  );
  assert.strictEqual(
    served[':examples/managed-http'],
    8035,
    'parser must recover managed-http -> 8035 from shadow-cljs.edn',
  );

  const missing = [];
  const mismatched = [];
  for (const [buildId, port] of Object.entries(served)) {
    const info = DEV_HTTP[buildId];
    if (!info) {
      missing.push(`${buildId} (served on ${port})`);
    } else if (info.port !== port) {
      mismatched.push(`${buildId}: DEV_HTTP=${info.port} but :dev-http=${port}`);
    }
  }
  assert.strictEqual(
    missing.length,
    0,
    `DEV_HTTP (dev-testbed.cjs) is missing builds served on a :dev-http ` +
      `port in shadow-cljs.edn: ${missing.join(', ')}. Add each to DEV_HTTP ` +
      `(and the README build->port table) so the launcher prints its URL.`,
  );
  assert.strictEqual(
    mismatched.length,
    0,
    `DEV_HTTP port(s) disagree with shadow-cljs.edn :dev-http: ${mismatched.join(
      '; ',
    )}.`,
  );
});

if (failed > 0) {
  console.error(`\n${failed} test(s) failed.`);
  process.exit(1);
} else {
  console.log('\nAll dev-testbed tests passed.');
}
