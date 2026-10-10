#!/usr/bin/env node
/**
 * Unit + smoke tests for the post-merge stale-MCP-binary hook.
 *
 *   1.  **Unit** — dot-sources `scripts/git-hooks/lib/check-stale-mcp-binary.sh`
 *       and pipes synthetic changed-path lists through it.
 *   2.  **Smoke** — runs `scripts/git-hooks/post-merge` in a throwaway repo
 *       over a real `ORIG_HEAD..HEAD` diff.
 *
 * `test-pre-commit.sh` runs it as its post-merge layer, so the always-on PR
 * guards job grades every change to the hook or its library.
 *
 * Run with: node scripts/git-hooks/post-merge-hook-test.cjs
 * Exit 0 = all-pass, 1 = any failure.
 */

'use strict';

const fs       = require('fs');
const os       = require('os');
const path     = require('path');
const child    = require('child_process');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const LIB_PATH  = path.join(REPO_ROOT, 'scripts', 'git-hooks', 'lib', 'check-stale-mcp-binary.sh');
const HOOK_PATH = path.join(REPO_ROOT, 'scripts', 'git-hooks', 'post-merge');
const MCP       = 'tools/re-frame2-pair-mcp/';

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

function runSh(args, stdin, env) {
  const res = child.spawnSync('sh', args, {
    input: stdin || '',
    encoding: 'utf8',
    env: Object.assign({}, process.env, env || {}),
  });
  return { stdout: res.stdout || '', stderr: res.stderr || '', code: res.status };
}

// --- LAYER 1: unit tests for check-stale-mcp-binary.sh ---

function runDetector(changedPaths) {
  return runSh(['-c', '. ' + JSON.stringify(LIB_PATH) + ' && check_stale_mcp_binary'],
               changedPaths.join('\n') + '\n');
}

// Source changes warn once, on stderr only, listing every changed file and the repair.
{
  const r = runDetector([MCP + 'src/re_frame2_pair_mcp/server.cljs',
                         MCP + 'src/re_frame2_pair_mcp/tools.cljs']);
  assert(r.code === 0 && r.stdout === '',                               'A: exit 0, no stdout');
  assert((r.stderr.match(/re-frame2-pair-mcp: source changed/g) || []).length === 1,
                                                                        'A: one warning header for several files');
  assert(/server\.cljs/.test(r.stderr) && /tools\.cljs/.test(r.stderr), 'A: lists every changed file');
  assert(/npm --prefix tools\/re-frame2-pair-mcp run build/.test(r.stderr)
         && /Restart Claude Code/.test(r.stderr),                       'A: prints the rebuild and bounce steps');
}

// Each build-config file of the surface warns too.
for (const f of ['shadow-cljs.edn', 'deps.edn', 'package.json']) {
  const r = runDetector([MCP + f]);
  assert(/source changed/.test(r.stderr) && r.stderr.includes(f),      'D: ' + f + ' warns');
}

// Everything else is silent: other trees, the MCP dir's README and tests,
// and a lookalike prefix (the src gate ends with a `/`).
{
  const r = runDetector(['docs/core/24-config-and-safety.md',
                         'tools/xray/src/foo.cljs',
                         'implementation/core/src/re_frame/views.cljs',
                         MCP + 'README.md',
                         MCP + 'test/stdio-roundtrip.js',
                         'tools/re-frame2-pair-mcp-fake/src/foo.cljs']);
  assert(r.code === 0 && r.stdout === '' && r.stderr === '',            'B: silent outside the MCP surface');
}

// --- LAYER 2: smoke test of the hook against a real ORIG_HEAD ---

function gitIn(dir, args) {
  return child.spawnSync('git', ['-C', dir].concat(args), { encoding: 'utf8' });
}

// Commit one file, and optionally mark the pre-commit HEAD as ORIG_HEAD the
// way a pull does.
function commitFile(dir, rel, content, markOrigHead) {
  if (markOrigHead) {
    fs.writeFileSync(path.join(dir, '.git', 'ORIG_HEAD'),
                     gitIn(dir, ['rev-parse', 'HEAD']).stdout.trim() + '\n');
  }
  const fp = path.join(dir, rel);
  fs.mkdirSync(path.dirname(fp), { recursive: true });
  fs.writeFileSync(fp, content);
  gitIn(dir, ['add', '.']);
  gitIn(dir, ['commit', '-qm', rel]);
}

{
  // The hook resolves the lib against `rev-parse --show-toplevel`, so the
  // repo carries it at the same relative path.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-post-merge-hook-test-'));
  try {
    gitIn(dir, ['init', '-q']);
    gitIn(dir, ['config', 'user.email', 't@t']);
    gitIn(dir, ['config', 'user.name', 't']);
    gitIn(dir, ['config', 'commit.gpgsign', 'false']);
    const libDest = path.join(dir, 'scripts', 'git-hooks', 'lib', 'check-stale-mcp-binary.sh');
    fs.mkdirSync(path.dirname(libDest), { recursive: true });
    fs.copyFileSync(LIB_PATH, libDest);
    commitFile(dir, 'README.md', 'r0\n', false);
    const runHook = () => runSh([HOOK_PATH], '', { GIT_WORK_TREE: dir, GIT_DIR: path.join(dir, '.git') });

    commitFile(dir, MCP + 'src/re_frame2_pair_mcp/server.cljs', '(ns re-frame2-pair-mcp.server)\n', true);
    let r = runHook();
    assert(r.code === 0 && /re-frame2-pair-mcp: source changed/.test(r.stderr)
           && /server\.cljs/.test(r.stderr),                             'Smoke A: hook warns on a real MCP diff, naming the file');

    commitFile(dir, 'docs/core/24-config-and-safety.md', 'docs change\n', true);
    r = runHook();
    assert(r.code === 0 && r.stderr === '',                              'Smoke B: hook silent on a docs-only diff');
  } finally {
    try { fs.rmSync(dir, { recursive: true, force: true }); } catch (_) { /* ignore */ }
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
