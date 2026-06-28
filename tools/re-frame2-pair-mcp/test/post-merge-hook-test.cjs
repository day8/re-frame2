#!/usr/bin/env node
/**
 * Unit + smoke tests for the post-merge stale-MCP-binary hook.
 *
 * Two layers, both POSIX-portable so they run under Git Bash on Windows
 * and native sh on macOS / Linux:
 *
 *   1.  **Unit** — invokes `scripts/git-hooks/lib/check-stale-mcp-binary.sh`
 *       with synthetic stdin streams (lists of changed paths). Asserts the
 *       warning text appears only when the inputs intersect the MCP source
 *       surface and is silent otherwise. No git state is touched.
 *
 *   2.  **Smoke** — simulates `git pull` having just landed by setting
 *       `ORIG_HEAD` to an arbitrary prior commit and running the hook
 *       script directly. Asserts the warning fires for a real diff that
 *       crosses an MCP source path, and is silent for a diff that doesn't.
 *
 * These tests are `.cjs` rather than CLJS: the hook is a sh/Node-side
 * artefact with no CLJS source of truth, so its tests live as `.cjs`
 * siblings rather than under `re_frame2_pair_mcp/*_test.cljs`.
 *
 * Run with: node test/post-merge-hook-test.cjs
 * Exit 0 = all-pass, 1 = any failure.
 */

'use strict';

const fs       = require('fs');
const os       = require('os');
const path     = require('path');
const child    = require('child_process');

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..');
const LIB_PATH  = path.join(REPO_ROOT, 'scripts', 'git-hooks', 'lib', 'check-stale-mcp-binary.sh');
const HOOK_PATH = path.join(REPO_ROOT, 'scripts', 'git-hooks', 'post-merge');

let passed = 0;
let failed = 0;
const failures = [];

function assert(cond, label) {
  if (cond) {
    passed += 1;
  } else {
    failed += 1;
    failures.push(label);
  }
}

// Run a small POSIX-sh program with stdin. Returns {stdout, stderr, code}.
function runSh(script, stdin) {
  const res = child.spawnSync('sh', ['-c', script], {
    input: stdin || '',
    encoding: 'utf8',
  });
  return { stdout: res.stdout || '', stderr: res.stderr || '', code: res.status };
}

function runShFile(file, args, stdin, env) {
  const res = child.spawnSync('sh', [file].concat(args || []), {
    input: stdin || '',
    encoding: 'utf8',
    env: Object.assign({}, process.env, env || {}),
  });
  return { stdout: res.stdout || '', stderr: res.stderr || '', code: res.status };
}

// --- LAYER 1: unit tests for check-stale-mcp-binary.sh ---

function runDetector(changedPaths) {
  // dot-source the lib then call the function with stdin piped in.
  const script = '. ' + JSON.stringify(LIB_PATH) + ' && check_stale_mcp_binary';
  return runSh(script, changedPaths);
}

// Case A: MCP source changed → warning fires.
{
  const r = runDetector(
    'tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/server.cljs\n' +
    'docs/core/24-config-and-safety.md\n'
  );
  assert(r.code === 0,                                                  'A: exit 0');
  assert(r.stdout === '',                                               'A: no stdout');
  assert(/re-frame2-pair-mcp: source changed/.test(r.stderr),           'A: warning header');
  assert(/server\.cljs/.test(r.stderr),                                 'A: lists the changed file');
  assert(/npm --prefix tools\/re-frame2-pair-mcp run build/.test(r.stderr),
                                                                        'A: prints rebuild command');
  assert(/Restart Claude Code/.test(r.stderr),                          'A: prints bounce hint');
}

// Case B: only non-MCP paths → silent.
{
  const r = runDetector(
    'docs/core/24-config-and-safety.md\n' +
    'tools/xray/src/foo.cljs\n' +
    'implementation/core/src/re_frame/views.cljs\n'
  );
  assert(r.code === 0,        'B: exit 0');
  assert(r.stdout === '',     'B: no stdout');
  assert(r.stderr === '',     'B: no warning');
}

// Case C: empty stdin → silent.
{
  const r = runDetector('');
  assert(r.code === 0,        'C: exit 0');
  assert(r.stderr === '',     'C: no warning on empty input');
}

// Case D: shadow-cljs.edn touched → warns.
{
  const r = runDetector('tools/re-frame2-pair-mcp/shadow-cljs.edn\n');
  assert(r.code === 0,                                                  'D: exit 0');
  assert(/source changed/.test(r.stderr),                               'D: warning fires for shadow-cljs.edn');
  assert(/shadow-cljs\.edn/.test(r.stderr),                             'D: lists the changed file');
}

// Case E: deps.edn touched → warns.
{
  const r = runDetector('tools/re-frame2-pair-mcp/deps.edn\n');
  assert(r.code === 0,                            'E: exit 0');
  assert(/source changed/.test(r.stderr),         'E: warning fires for deps.edn');
}

// Case F: package.json touched → warns.
{
  const r = runDetector('tools/re-frame2-pair-mcp/package.json\n');
  assert(r.code === 0,                            'F: exit 0');
  assert(/source changed/.test(r.stderr),         'F: warning fires for package.json');
}

// Case G: README under MCP dir → silent (out-of-scope path).
{
  const r = runDetector('tools/re-frame2-pair-mcp/README.md\n');
  assert(r.code === 0,        'G: exit 0');
  assert(r.stderr === '',     'G: README change must not trigger');
}

// Case H: test/ under MCP dir → silent (test-only edits don't restage the binary).
{
  const r = runDetector('tools/re-frame2-pair-mcp/test/stdio-roundtrip.js\n');
  assert(r.code === 0,        'H: exit 0');
  assert(r.stderr === '',     'H: test-only edit must not trigger');
}

// Case I: multiple MCP source files → one warning block, lists all.
{
  const r = runDetector(
    'tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/server.cljs\n' +
    'tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools.cljs\n'
  );
  assert(r.code === 0,                                              'I: exit 0');
  const headerCount = (r.stderr.match(/source changed/g) || []).length;
  assert(headerCount === 1,                                         'I: single header for multiple source files');
  assert(/server\.cljs/.test(r.stderr) && /tools\.cljs/.test(r.stderr),
                                                                    'I: both files listed');
}

// Case J: prefix collision guard — `tools/re-frame2-pair-mcp-fake/src/foo.cljs`
//        must NOT trigger (the prefix gate ends with a `/`).
{
  const r = runDetector('tools/re-frame2-pair-mcp-fake/src/foo.cljs\n');
  assert(r.code === 0,        'J: exit 0');
  assert(r.stderr === '',     'J: lookalike prefix must not trigger');
}

// --- LAYER 2: smoke test of the hook against a synthetic ORIG_HEAD ---

// Build a tiny disposable git repo and run the hook against simulated
// pre/post pull states. This proves the hook's wiring (rev-parse ORIG_HEAD,
// diff --name-only, lib resolution) works end-to-end without touching the
// surrounding repo's HEAD.

function mkTempRepo() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-post-merge-hook-test-'));
  // We need git operations and a worktree that LOOKS like the re-frame2
  // repo to the hook (it `rev-parse --show-toplevel`s then reads
  // scripts/git-hooks/lib/check-stale-mcp-binary.sh relative to that).
  // Strategy: init a temp repo, then SYMLINK / copy the hook's lib into
  // it at the expected relative path.
  child.spawnSync('git', ['init', '-q', dir], { stdio: 'inherit' });
  child.spawnSync('git', ['-C', dir, 'config', 'user.email', 't@t'], { stdio: 'inherit' });
  child.spawnSync('git', ['-C', dir, 'config', 'user.name',  't'  ], { stdio: 'inherit' });
  child.spawnSync('git', ['-C', dir, 'config', 'commit.gpgsign', 'false'], { stdio: 'inherit' });

  // Bring the lib in at the same relative path the hook reads.
  const libDest = path.join(dir, 'scripts', 'git-hooks', 'lib', 'check-stale-mcp-binary.sh');
  fs.mkdirSync(path.dirname(libDest), { recursive: true });
  fs.copyFileSync(LIB_PATH, libDest);
  return dir;
}

function gitIn(dir, args, input) {
  return child.spawnSync('git', ['-C', dir].concat(args), {
    encoding: 'utf8', input: input || '',
  });
}

function writeFile(dir, rel, content) {
  const fp = path.join(dir, rel);
  fs.mkdirSync(path.dirname(fp), { recursive: true });
  fs.writeFileSync(fp, content);
}

function cleanup(dir) {
  try { fs.rmSync(dir, { recursive: true, force: true }); } catch (_) { /* ignore */ }
}

// Smoke A: real diff that crosses MCP source → hook warns.
{
  const dir = mkTempRepo();
  try {
    // Pre-pull commit: a non-MCP file.
    writeFile(dir, 'README.md', 'r0\n');
    gitIn(dir, ['add', '.']);
    gitIn(dir, ['commit', '-qm', 'r0']);

    // Capture pre-pull head into ORIG_HEAD via plumbing — git writes
    // ORIG_HEAD on real merges; we set it directly here.
    const origHead = gitIn(dir, ['rev-parse', 'HEAD']).stdout.trim();
    fs.writeFileSync(path.join(dir, '.git', 'ORIG_HEAD'), origHead + '\n');

    // Post-pull commit: MCP source changed.
    writeFile(dir, 'tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/server.cljs',
              '(ns re-frame2-pair-mcp.server)\n');
    gitIn(dir, ['add', '.']);
    gitIn(dir, ['commit', '-qm', 'mcp-fix']);

    const r = runShFile(HOOK_PATH, [], '', { GIT_WORK_TREE: dir, GIT_DIR: path.join(dir, '.git') });
    // Hook prints to stderr from the dir-relative repo root; we ran it
    // with GIT_DIR/GIT_WORK_TREE so rev-parse --show-toplevel resolves
    // inside the temp repo and finds the lib we staged there.
    assert(r.code === 0,                                              'Smoke A: hook exit 0');
    assert(/re-frame2-pair-mcp: source changed/.test(r.stderr),       'Smoke A: warning fires on real diff');
    assert(/server\.cljs/.test(r.stderr),                             'Smoke A: warning names the file');
  } finally {
    cleanup(dir);
  }
}

// Smoke B: real diff that does NOT cross MCP source → hook silent.
{
  const dir = mkTempRepo();
  try {
    writeFile(dir, 'README.md', 'r0\n');
    gitIn(dir, ['add', '.']);
    gitIn(dir, ['commit', '-qm', 'r0']);

    const origHead = gitIn(dir, ['rev-parse', 'HEAD']).stdout.trim();
    fs.writeFileSync(path.join(dir, '.git', 'ORIG_HEAD'), origHead + '\n');

    writeFile(dir, 'docs/core/24-config-and-safety.md', 'docs change\n');
    gitIn(dir, ['add', '.']);
    gitIn(dir, ['commit', '-qm', 'docs-only']);

    const r = runShFile(HOOK_PATH, [], '', { GIT_WORK_TREE: dir, GIT_DIR: path.join(dir, '.git') });
    assert(r.code === 0,                                              'Smoke B: hook exit 0');
    assert(r.stderr === '',                                           'Smoke B: hook silent on docs-only diff');
  } finally {
    cleanup(dir);
  }
}

// Smoke C: no ORIG_HEAD set → hook silent (no false positives on
// unusual merge / rebase flows where ORIG_HEAD isn't written).
{
  const dir = mkTempRepo();
  try {
    writeFile(dir, 'README.md', 'r0\n');
    gitIn(dir, ['add', '.']);
    gitIn(dir, ['commit', '-qm', 'r0']);

    // Deliberately do NOT write .git/ORIG_HEAD.
    const r = runShFile(HOOK_PATH, [], '', { GIT_WORK_TREE: dir, GIT_DIR: path.join(dir, '.git') });
    assert(r.code === 0,        'Smoke C: hook exit 0');
    assert(r.stderr === '',     'Smoke C: hook silent without ORIG_HEAD');
  } finally {
    cleanup(dir);
  }
}

// --- summary ---

const total = passed + failed;
process.stdout.write(`post-merge-hook-test: ${passed}/${total} passed\n`);
if (failed > 0) {
  process.stderr.write('\nFailures:\n');
  for (const f of failures) {
    process.stderr.write('  - ' + f + '\n');
  }
  process.exit(1);
}
process.exit(0);
