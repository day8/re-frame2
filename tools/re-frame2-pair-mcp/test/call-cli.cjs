// Drives bin/call.cjs, the one-shot shell client, against the compiled
// out/server.js. Needs no live shadow-cljs: it calls the server-local tool,
// an unknown tool, and an app-facing tool with discovery forced to fail, and
// checks each one's stdout, stderr and exit status.
//
// The app-facing case is bounded well under the SDK's 60-second request
// timeout because discovery sends the client a `roots/list` request, and a
// client that leaves it unanswered stalls there; the CLI must decline it.
//
// Run with: `npm run test:call-cli` after `npm run build`. Exits 0 on success.
'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const CLI = path.join(__dirname, '..', 'bin', 'call.cjs');
const SERVER = path.join(__dirname, '..', 'out', 'server.js');
const DISCOVERY_BOUND_MS = 30000;

if (!fs.existsSync(SERVER)) {
  console.error('FAIL: no ' + SERVER + ' — run `npm run build` first');
  process.exit(1);
}

function runCli(argv) {
  const env = { ...process.env };
  delete env.SHADOW_CLJS_NREPL_PORT;
  const started = Date.now();
  const r = spawnSync(process.execPath, [CLI, ...argv], {
    cwd: os.tmpdir(),
    env,
    encoding: 'utf8',
    timeout: 45000,
  });
  return { status: r.status, stdout: r.stdout || '', stderr: r.stderr || '', ms: Date.now() - started, error: r.error };
}

const failures = [];
function check(name, ok, detail) {
  if (ok) {
    console.log('OK   ' + name);
  } else {
    failures.push(name);
    console.error('FAIL ' + name + '\n' + detail);
  }
}
const show = (r) => JSON.stringify({ status: r.status, ms: r.ms, error: r.error && r.error.message, stdout: r.stdout.slice(0, 400), stderr: r.stderr.slice(-400) });

// 1. A server-local tool returns its result on stdout and exits 0, and the
//    flags after `--` reach the server (its boot log names the eval gate).
const instr = runCli(['get-re-frame2-pair-instructions', '{}', '--', '--no-eval']);
check('server-local tool -> exit 0 with its result on stdout',
  instr.status === 0 &&
    instr.stdout.startsWith('{:ok? true') &&
    instr.stdout.includes(':tool "get-re-frame2-pair-instructions"') &&
    instr.stdout.includes(':tool-contract "'),
  show(instr));
check('launch flags after -- reach the server',
  instr.stderr.includes('DISABLED (--no-eval)'),
  show(instr));

// 2. A tool error is printed and exits 1.
const unknown = runCli(['no-such-tool']);
check('unknown tool -> exit 1 with :unknown-tool on stdout',
  unknown.status === 1 && unknown.stdout.includes(':reason :unknown-tool'),
  show(unknown));

// 3. An app-facing call with no app runs discovery, which asks this client
//    for roots; the call still answers promptly with the degraded envelope.
const degraded = runCli(['eval-cljs', '{"form":"(+ 1 2)"}', '--', '--http-port', '1']);
check('app-facing call with no app -> exit 1, nREPL port not found, within ' + DISCOVERY_BOUND_MS + 'ms',
  degraded.status === 1 &&
    degraded.stdout.includes(':reason :rf.error/pair-mcp-nrepl-port-not-found') &&
    degraded.ms < DISCOVERY_BOUND_MS,
  show(degraded));

// 4. Arguments that are not one JSON object never reach a server.
const badArgs = runCli(['get-path', '[:not :json]']);
check('non-JSON arguments -> exit 2 before any server starts',
  badArgs.status === 2 && badArgs.stderr.includes('<json-args> is not JSON') &&
    !badArgs.stderr.includes('[re-frame2-pair-mcp]'),
  show(badArgs));

if (failures.length) {
  console.error('\n' + failures.length + ' CALL-CLI CHECK(S) FAILED');
  process.exit(1);
}
console.log('\nCALL CLI GREEN');
