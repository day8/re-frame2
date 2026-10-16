#!/usr/bin/env node

'use strict';

/*
 * Source policy: `lib/shadow.cjs` is the only code that starts shadow-cljs. It takes
 * the machine-wide build lane, owns the compiler's process tree and clears a one-shot
 * compile's declared output, so an entry that reaches shadow-cljs any other way runs
 * outside the lane and can wedge a concurrent build.
 *
 * Two surfaces are read:
 *   - every launcher script under implementation/scripts: none but the helper may
 *     name the npm runner (`shadow-cljs/cli/runner.js`), the package as a spawn or
 *     path argument, the `.bin` shim, or the JVM entry `shadow.cljs.devtools.cli`;
 *   - the `scripts` of implementation/package.json and every tools/<tool>/package.json:
 *     none may run `shadow-cljs` (bare, through npx or through `.bin`) or name
 *     `shadow.cljs.devtools.cli`; they call `scripts/lib/shadow.cjs` instead.
 * Comments are stripped first, so prose that names a forbidden form is not a hit.
 * Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
const { stripComments, createPolicyTestSuite } = require('./_policy-test-util.cjs');

const SCRIPTS_DIR = __dirname;
const IMPL_DIR = path.resolve(SCRIPTS_DIR, '..');
const REPO_ROOT = path.resolve(IMPL_DIR, '..');
const HELPER = path.join(SCRIPTS_DIR, 'lib', 'shadow.cjs');

const { test, run } = createPolicyTestSuite('shadow-spawn-policy');

// A direct route into shadow-cljs from JS source.
const CODE_ROUTE_RE = new RegExp(
  [
    'shadow-cljs\\/cli\\/runner\\.js',
    'shadow\\.cljs\\.devtools\\.cli',
    // the package as a spawn argument (`npx shadow-cljs`) or a path segment
    // (`node_modules/shadow-cljs/cli/runner.js` built with path.join): one string
    // literal holding exactly the package name
    '([\'"`])shadow-cljs\\1\\s*[,)\\]]',
    '\\.bin[\\/\\\\]shadow-cljs',
  ].join('|'),
);

// A direct route into shadow-cljs from an npm script body: `shadow-cljs` as a
// command word (bare, via npx, or via the `.bin` shim), or the JVM entry.
const SCRIPT_ROUTE_RE =
  /(?:^|[\s;&|(])(?:npx\s+)?(?:\S*[\/\\]\.bin[\/\\])?shadow-cljs(?:\.cmd)?(?=\s|$)|shadow\.cljs\.devtools\.cli/;

function launcherFiles(dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules') continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...launcherFiles(full));
    else if (/\.(c|m)?js$/.test(entry.name) && !entry.name.endsWith('.test.cjs')) out.push(full);
  }
  return out;
}

function packageJsonFiles() {
  const files = [path.join(IMPL_DIR, 'package.json')];
  const toolsDir = path.join(REPO_ROOT, 'tools');
  for (const tool of fs.readdirSync(toolsDir).sort()) {
    const pkg = path.join(toolsDir, tool, 'package.json');
    if (fs.existsSync(pkg)) files.push(pkg);
  }
  return files;
}

const rel = (file) => path.relative(REPO_ROOT, file).split(path.sep).join('/');

const LAUNCHERS = launcherFiles(SCRIPTS_DIR);
const PACKAGES = packageJsonFiles();

test('the scanned surfaces are non-trivial, and the helper is among them', () => {
  assert.ok(LAUNCHERS.length >= 30, `expected at least 30 launcher scripts, found ${LAUNCHERS.length}`);
  assert.ok(LAUNCHERS.includes(HELPER), 'lib/shadow.cjs was not found by the walk');
  assert.ok(PACKAGES.length >= 3, `expected implementation/ plus tool package.json files, found ${PACKAGES.length}`);
});

test('the helper itself carries the direct routes, so the pattern can see them', () => {
  const code = stripComments(fs.readFileSync(HELPER, 'utf8'));
  assert.match(code, /shadow-cljs\/cli\/runner\.js/);
  assert.match(code, /shadow\.cljs\.devtools\.cli/);
});

for (const file of LAUNCHERS) {
  if (file === HELPER) continue;
  test(`${rel(file)}: reaches shadow-cljs only through lib/shadow.cjs`, () => {
    const code = stripComments(fs.readFileSync(file, 'utf8'));
    const hit = code.match(CODE_ROUTE_RE);
    assert.equal(
      hit,
      null,
      `${rel(file)} starts shadow-cljs directly (${hit && hit[0]}). Run it through ` +
        "lib/shadow.cjs — `runShadow(args, opts)`, or spawn `SHADOW_CLI` under process.execPath — " +
        'so the build takes the machine-wide lane.',
    );
  });
}

for (const file of PACKAGES) {
  const scripts = JSON.parse(fs.readFileSync(file, 'utf8')).scripts || {};
  for (const [name, body] of Object.entries(scripts)) {
    test(`${rel(file)} "${name}": reaches shadow-cljs only through lib/shadow.cjs`, () => {
      const hit = body.match(SCRIPT_ROUTE_RE);
      assert.equal(
        hit,
        null,
        `${rel(file)} script "${name}" runs shadow-cljs directly (${hit && hit[0].trim()}). ` +
          'Call `node <path>/implementation/scripts/lib/shadow.cjs <command> ...` instead ' +
          '(`--clojure <aliases>` for the JVM entry).',
      );
    });
  }
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
    "spawnSync('clojure', ['-M:test', '-m', 'shadow.cljs.devtools.cli', 'compile', 'x']);",
    "spawn(path.join(ROOT, 'node_modules', '.bin/shadow-cljs'), ['compile', 'x']);",
  ];
  for (const src of planted) assert.match(stripComments(src), CODE_ROUTE_RE, src);
  const allowed = [
    "spawnSync(process.execPath, [SHADOW_CLI, 'compile', BUILD_ID]);",
    "// spawns shadow-cljs/cli/runner.js under node\nconst x = 1;",
    'console.error(\'Did you run "shadow-cljs release examples/counter"?\');',
    'console.error(\'  Rebuild with "shadow-cljs\');',
  ];
  for (const src of allowed) assert.doesNotMatch(stripComments(src), CODE_ROUTE_RE, src);

  const plantedScripts = [
    'shadow-cljs compile cljs-test && node out/cljs-test.js',
    'npx shadow-cljs release app',
    'cd ../tools/x && clojure -M:cljs-test -m shadow.cljs.devtools.cli compile t && node out/t.js',
    'node_modules/.bin/shadow-cljs watch app',
    'npm run a && shadow-cljs.cmd compile b',
  ];
  for (const body of plantedScripts) assert.match(body, SCRIPT_ROUTE_RE, body);
  const allowedScripts = [
    'node scripts/lib/shadow.cjs compile browser-test && node scripts/serve-and-run-browser-tests.cjs',
    'cd ../tools/x && node ../../implementation/scripts/lib/shadow.cjs --clojure -M:cljs-test compile t',
    'node scripts/compile-node-test.cjs node-test out/node-test.js && node out/node-test.js',
    'node scripts/check-elision.cjs --root out/shadow-cljs-probe',
  ];
  for (const body of allowedScripts) assert.doesNotMatch(body, SCRIPT_ROUTE_RE, body);
});

run();
