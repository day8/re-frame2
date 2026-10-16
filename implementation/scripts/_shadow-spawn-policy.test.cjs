#!/usr/bin/env node

'use strict';

/*
 * Source policy: `lib/shadow.cjs` is the only code that starts shadow-cljs. It takes
 * the machine-wide build lane, owns the compiler's process tree and clears a one-shot
 * compile's declared output, so an entry that reaches shadow-cljs any other way runs
 * outside the lane and can wedge a concurrent build.
 *
 * Three surfaces are read, across the whole tracked tree, so a launcher in a new
 * directory is covered the day it lands:
 *   - every tracked `.cjs` / `.js` / `.mjs` file: none but the helper may name the npm
 *     runner (`shadow-cljs/cli/runner.js`), the package as a spawn argument or as the
 *     directory under `node_modules`, the `.bin` shim, or the JVM entry
 *     `shadow.cljs.devtools.cli`;
 *   - the `scripts` of every tracked package.json: none may run `shadow-cljs` (bare,
 *     through npx or through `.bin`) or name `shadow.cljs.devtools.cli`; they call
 *     `implementation/scripts/lib/shadow.cjs` instead;
 *   - the `run:` steps of every workflow, read the same way as an npm script.
 * Comments are stripped first, so prose that names a forbidden form is not a hit.
 *
 * NOT READ: test files (`*.test.cjs` and kin), whose runner paths are fakes and
 * fixtures; and the paths in EXEMPT below, each with its reason.
 * Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const { stripComments, createPolicyTestSuite } = require('./_policy-test-util.cjs');

const SCRIPTS_DIR = __dirname;
const IMPL_DIR = path.resolve(SCRIPTS_DIR, '..');
const REPO_ROOT = path.resolve(IMPL_DIR, '..');
const HELPER = path.join(SCRIPTS_DIR, 'lib', 'shadow.cjs');

const { test, run } = createPolicyTestSuite('shadow-spawn-policy');

// Paths that start shadow-cljs on purpose and stay outside the helper.
const EXEMPT = [
  // Separate projects with their own install, which a consumer or a fixture test
  // runs as-is: the scaffold the template emits, and the skills' fixture apps.
  { prefix: 'tools/template/resources/', why: 'the emitted project is user-facing template content' },
  { prefix: 'skills/', within: '/tests/fixture/', why: 'a skill fixture is its own shadow-cljs project' },
  // Miswired bench drivers kept as TEXT for lane_cache_wiring.test.cjs; never executed.
  { prefix: 'implementation/core/test/re_frame/bench/lane_cache_fixtures/', why: 'gate fixtures, read as text' },
  // KNOWN DIRECT: an entry that still starts shadow-cljs itself and so runs OUTSIDE
  // the lane (AGENTS.md lists it for the coordinator's grant). `direct` makes this
  // suite red once it is routed, so the entry cannot outlive the route.
  {
    prefix: 'implementation/adapters/scripts/serve-and-run-adapter-smokes.cjs',
    why: 'the adapter-smoke launcher still spawns the runner itself',
    direct: true,
  },
];

const rel = (file) => path.relative(REPO_ROOT, file).split(path.sep).join('/');
const exempt = (relPath) =>
  EXEMPT.some((e) => relPath.startsWith(e.prefix) && (!e.within || relPath.includes(e.within)));

// A direct route into shadow-cljs from JS source.
const CODE_ROUTE_RE = new RegExp(
  [
    'shadow-cljs\\/cli\\/runner\\.js',
    'shadow\\.cljs\\.devtools\\.cli',
    // The package as a spawn argument — the first element of an argv array or a
    // call (`['shadow-cljs', 'watch', 'app']`, `spawn('shadow-cljs', …)`) — or as the
    // directory under node_modules (`path.join(IMPL, 'node_modules', 'shadow-cljs',
    // 'cli', 'runner.js')`). A shadow-cljs OUTPUT directory, such as
    // `path.join(dir, 'target', 'shadow-cljs', 'nrepl.port')`, is neither.
    '(?:[[(]\\s*|([\'"`])node_modules\\1\\s*,\\s*)([\'"`])shadow-cljs\\2\\s*[,)\\]]',
    '\\.bin[\\/\\\\]shadow-cljs',
  ].join('|'),
);

// A direct route into shadow-cljs from an npm script body or a workflow step:
// `shadow-cljs` as a command word (bare, via npx, or via the `.bin` shim), or the
// JVM entry.
const SCRIPT_ROUTE_RE =
  /(?:^|[\s;&|(])(?:npx\s+)?(?:\S*[\/\\]\.bin[\/\\])?shadow-cljs(?:\.cmd)?(?=\s|$)|shadow\.cljs\.devtools\.cli/;

// git's own environment (a hook's GIT_DIR, say) would point ls-files at another tree.
function gitEnv() {
  const env = { ...process.env };
  for (const key of Object.keys(env)) if (key.startsWith('GIT_')) delete env[key];
  return env;
}

function tracked(...pathspecs) {
  return execFileSync('git', ['ls-files', '-z', '--', ...pathspecs], {
    cwd: REPO_ROOT,
    env: gitEnv(),
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  })
    .split('\0')
    .filter(Boolean)
    .filter((p) => !p.split('/').includes('node_modules'));
}

const isTestFile = (p) => /\.test\.(c|m)?js$/.test(p);

// The `run:` bodies of a workflow: an inline value, or a block scalar's lines.
function workflowRuns(file) {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  const runs = [];
  for (let i = 0; i < lines.length; i += 1) {
    const m = /^(\s*)(-\s+)?run:\s*(.*)$/.exec(lines[i]);
    if (!m) continue;
    const at = i + 1;
    const value = m[3].trim();
    if (value && !/^[|>][-+]?$/.test(value)) {
      runs.push({ line: at, body: value });
      continue;
    }
    const keyColumn = m[1].length + (m[2] ? m[2].length : 0);
    const body = [];
    while (i + 1 < lines.length) {
      const next = lines[i + 1];
      if (next.trim() !== '' && next.match(/^\s*/)[0].length <= keyColumn) break;
      body.push(next);
      i += 1;
    }
    runs.push({ line: at, body: body.join('\n') });
  }
  return runs;
}

// One shell line as a command: comment lines dropped and quoted text blanked, so a
// message that names shadow-cljs is not read as running it.
const shellCode = (body) =>
  body
    .split('\n')
    .filter((line) => !/^\s*#/.test(line))
    .map((line) => line.replace(/"[^"]*"|'[^']*'/g, '""'))
    .join('\n');

const LAUNCHERS = tracked('*.cjs', '*.js', '*.mjs')
  .filter((p) => !isTestFile(p) && !exempt(p))
  .map((p) => path.join(REPO_ROOT, p));
const PACKAGES = tracked('package.json', '*/package.json')
  .filter((p) => !exempt(p))
  .map((p) => path.join(REPO_ROOT, p));
const WORKFLOWS = tracked('.github/workflows/*.yml').map((p) => path.join(REPO_ROOT, p));

test('the scanned surfaces are non-trivial, and the helper is among them', () => {
  assert.ok(LAUNCHERS.length >= 200, `expected at least 200 tracked scripts, found ${LAUNCHERS.length}`);
  assert.ok(LAUNCHERS.includes(HELPER), 'lib/shadow.cjs was not found by the walk');
  for (const dir of ['examples/scripts/', 'bench/fresco/', 'tools/re-frame2-pair-mcp/scripts/']) {
    assert.ok(LAUNCHERS.some((f) => rel(f).startsWith(dir)), `no launcher under ${dir} was scanned`);
  }
  assert.ok(PACKAGES.length >= 10, `expected the repo's package.json files, found ${PACKAGES.length}`);
  assert.ok(
    PACKAGES.some((f) => rel(f) === 'docs/tools/playground/sci/package.json'),
    'the playground engine package.json was not scanned',
  );
  assert.ok(WORKFLOWS.length >= 5, `expected the workflows, found ${WORKFLOWS.length}`);
});

test('the helper itself carries the direct routes, so the pattern can see them', () => {
  const code = stripComments(fs.readFileSync(HELPER, 'utf8'));
  assert.match(code, /shadow-cljs\/cli\/runner\.js/);
  assert.match(code, /shadow\.cljs\.devtools\.cli/);
});

test('every exemption still covers a tracked file, and a known-direct one is still direct', () => {
  const all = tracked('*.cjs', '*.js', '*.mjs', 'package.json', '*/package.json');
  for (const e of EXEMPT) {
    const covered = all.filter((p) => exempt(p) && p.startsWith(e.prefix));
    assert.ok(covered.length > 0, `exemption ${e.prefix} (${e.why}) covers no tracked file; delete it`);
    if (!e.direct) continue;
    for (const p of covered) {
      const code = stripComments(fs.readFileSync(path.join(REPO_ROOT, p), 'utf8'));
      assert.match(code, CODE_ROUTE_RE, `${p} no longer starts shadow-cljs directly; delete its EXEMPT entry`);
    }
  }
});

for (const file of LAUNCHERS) {
  if (file === HELPER) continue;
  test(`${rel(file)}: reaches shadow-cljs only through lib/shadow.cjs`, () => {
    const code = stripComments(fs.readFileSync(file, 'utf8'));
    const hit = code.match(CODE_ROUTE_RE);
    assert.equal(
      hit && hit[0],
      null,
      `${rel(file)} starts shadow-cljs directly (${hit && hit[0]}). Run it through ` +
        "implementation/scripts/lib/shadow.cjs — `runShadow(args, opts)`, or spawn " +
        '`SHADOW_CLI` under process.execPath — so the build takes the machine-wide lane.',
    );
  });
}

for (const file of PACKAGES) {
  const scripts = JSON.parse(fs.readFileSync(file, 'utf8')).scripts || {};
  for (const [name, body] of Object.entries(scripts)) {
    test(`${rel(file)} "${name}": reaches shadow-cljs only through lib/shadow.cjs`, () => {
      const hit = body.match(SCRIPT_ROUTE_RE);
      assert.equal(
        hit && hit[0],
        null,
        `${rel(file)} script "${name}" runs shadow-cljs directly (${hit && hit[0].trim()}). ` +
          'Call `node <path>/implementation/scripts/lib/shadow.cjs <command> ...` instead ' +
          '(`--clojure <aliases>` for the JVM entry).',
      );
    });
  }
}

for (const file of WORKFLOWS) {
  test(`${rel(file)}: every run step reaches shadow-cljs only through lib/shadow.cjs`, () => {
    const hits = workflowRuns(file)
      .filter(({ body }) => SCRIPT_ROUTE_RE.test(shellCode(body)))
      .map(({ line }) => `${rel(file)}:${line}`);
    assert.deepEqual(
      hits,
      [],
      'these steps run shadow-cljs directly. Call ' +
        '`node <path>/implementation/scripts/lib/shadow.cjs <command> ...` (or an npm ' +
        'script that does) instead.',
    );
  });
}

test('the npm scripts that build do so through the helper', () => {
  const pkg = JSON.parse(fs.readFileSync(path.join(IMPL_DIR, 'package.json'), 'utf8'));
  const routed = Object.values(pkg.scripts).filter((body) => body.includes('scripts/lib/shadow.cjs'));
  assert.ok(routed.length >= 10, `expected at least 10 routed npm scripts, found ${routed.length}`);
});

// The gate's teeth: each pattern catches the routes it exists to refuse, and passes
// the helper's own forms.
test('the patterns catch planted direct routes and pass the helper forms', () => {
  const planted = [
    "const r = require.resolve('shadow-cljs/cli/runner.js', { paths: [ROOT] });",
    "spawn(process.execPath, [path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js'), 'compile', 'x']);",
    "crossSpawn(npxBin, ['shadow-cljs', 'watch', 'app']);",
    "const args = ['shadow-cljs', 'release', ...targets];",
    "spawnSync('shadow-cljs', ['compile', 'x']);",
    "spawnSync('clojure', ['-M:test', '-m', 'shadow.cljs.devtools.cli', 'compile', 'x']);",
    "spawn(path.join(ROOT, 'node_modules', '.bin/shadow-cljs'), ['compile', 'x']);",
  ];
  for (const src of planted) assert.match(stripComments(src), CODE_ROUTE_RE, src);
  const allowed = [
    "spawnSync(process.execPath, [SHADOW_CLI, 'compile', BUILD_ID]);",
    "// spawns shadow-cljs/cli/runner.js under node\nconst x = 1;",
    'console.error(\'Did you run "shadow-cljs release examples/counter"?\');',
    'console.error(\'  Rebuild with "shadow-cljs\');',
    "const port = path.join(FIXTURE_DIR, 'target', 'shadow-cljs', 'nrepl.port');",
    "const realSide = path.join(outside, 'shadow-cljs');",
  ];
  for (const src of allowed) assert.doesNotMatch(stripComments(src), CODE_ROUTE_RE, src);

  const plantedScripts = [
    'shadow-cljs compile cljs-test && node out/cljs-test.js',
    'npx shadow-cljs release app',
    'cd ../tools/x && clojure -M:cljs-test -m shadow.cljs.devtools.cli compile t && node out/t.js',
    'node_modules/.bin/shadow-cljs watch app',
    'npm run a && shadow-cljs.cmd compile b',
  ];
  for (const body of plantedScripts) assert.match(shellCode(body), SCRIPT_ROUTE_RE, body);
  const allowedScripts = [
    'node scripts/lib/shadow.cjs compile browser-test && node scripts/serve-and-run-browser-tests.cjs',
    'cd ../tools/x && node ../../implementation/scripts/lib/shadow.cjs --clojure -M:cljs-test compile t',
    'node scripts/compile-node-test.cjs node-test out/node-test.js && node out/node-test.js',
    'node scripts/check-elision.cjs --root out/shadow-cljs-probe',
    '# npx shadow-cljs compile server, as the comment says',
    'echo "::error::$f does not look like a shadow-cljs :advanced bundle"',
    'rm -rf implementation/.shadow-cljs',
  ];
  for (const body of allowedScripts) assert.doesNotMatch(shellCode(body), SCRIPT_ROUTE_RE, body);
});

test('the workflow reader finds inline and block run steps', () => {
  const tmp = path.join(require('os').tmpdir(), `rf2-spawn-policy-${process.pid}.yml`);
  fs.writeFileSync(
    tmp,
    [
      'jobs:',
      '  a:',
      '    steps:',
      '      - name: inline',
      '        run: npx shadow-cljs compile server',
      '      - name: block',
      '        run: |',
      '          npm ci',
      '          npx shadow-cljs release app',
      '      - name: after',
      '        run: echo done',
      '',
    ].join('\n'),
  );
  try {
    const runs = workflowRuns(tmp);
    assert.deepEqual(runs.map((r) => r.line), [5, 7, 11]);
    assert.deepEqual(
      runs.map((r) => SCRIPT_ROUTE_RE.test(shellCode(r.body))),
      [true, true, false],
    );
  } finally {
    fs.rmSync(tmp, { force: true });
  }
});

run();
