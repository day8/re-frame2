#!/usr/bin/env node
// Runs a Python script under the first interpreter on this host that actually
// runs Python 3.10+, so an npm script can launch a checker on every host
// without naming an interpreter that may not be there.
//
//   node scripts/lib/python.cjs <script.py> [args...]
//
// Each candidate is RUN rather than looked up, because a name that resolves is
// not a Python: on Windows the Microsoft Store's app-execution aliases put a
// `python` and a `python3` on PATH that print an install hint and fail — exit
// 9009 to a spawning process, which a shell truncates to 49. Candidates, in
// order: `python`, `python3`, then the Windows launcher's `py -3`. `python`
// goes first so that wherever it works — CI's runners — the script runs under
// exactly the interpreter a bare `python` would pick. The fast-PR spine
// resolves its own interpreter the same way (`scripts/test-fast-pr.sh`,
// PYTHON LAUNCHER) for the steps it runs directly.
//
// Exits with the script's own status, so a checker's red stays red. When no
// candidate runs, exits 2 naming each candidate and what it did.

'use strict';

const { spawnSync } = require('child_process');

const MIN_VERSION = [3, 10];

// [command, leading args] — `py -3` is a command and its argument.
const CANDIDATES = [
  ['python', []],
  ['python3', []],
  ['py', ['-3']],
];

// Answers `<major>.<minor>`; Python 2 parses it too, so an old interpreter is
// named by its version rather than by a syntax error.
const PROBE = 'import sys; sys.stdout.write("%d.%d" % sys.version_info[:2])';

const PROBE_TIMEOUT_MS = 60_000;

function label([command, lead]) {
  return [command, ...lead].join(' ');
}

// Why a probe's result does not count as a working interpreter, or null when it does.
function refusal(r) {
  if (r.error) {
    if (r.error.code === 'ENOENT') return 'not found';
    if (r.error.code === 'ETIMEDOUT') return `no answer within ${PROBE_TIMEOUT_MS / 1000}s`;
    return r.error.message;
  }
  if (r.signal) return `killed by ${r.signal}`;
  if (r.status !== 0) {
    return (r.status & 0xff) === 49
      ? `exit ${r.status} — the Microsoft Store alias, which resolves on PATH but is not Python`
      : `exit ${r.status}`;
  }
  const m = /^(\d+)\.(\d+)$/.exec(String(r.stdout).trim());
  if (!m) return `answered ${JSON.stringify(String(r.stdout).trim())}, not a Python version`;
  const [major, minor] = [Number(m[1]), Number(m[2])];
  if (major < MIN_VERSION[0] || (major === MIN_VERSION[0] && minor < MIN_VERSION[1])) {
    return `Python ${major}.${minor}, older than ${MIN_VERSION.join('.')}`;
  }
  return null;
}

/**
 * The first candidate that runs Python 3.10+, as `{ found: [command, lead], tried }`;
 * `found` is null when none does. `tried` is one `{ label, refusal }` per candidate
 * probed, in order.
 */
function resolvePython({ candidates = CANDIDATES, env = process.env } = {}) {
  const tried = [];
  for (const candidate of candidates) {
    const [command, lead] = candidate;
    const r = spawnSync(command, [...lead, '-c', PROBE], {
      encoding: 'utf8',
      env,
      stdio: ['ignore', 'pipe', 'pipe'],
      timeout: PROBE_TIMEOUT_MS,
      windowsHide: true,
    });
    const why = refusal(r);
    tried.push({ label: label(candidate), refusal: why });
    if (why === null) return { found: candidate, tried };
  }
  return { found: null, tried };
}

function noInterpreterMessage(tried) {
  const width = Math.max(...tried.map((t) => t.label.length));
  return [
    `python.cjs: no Python ${MIN_VERSION.join('.')}+ interpreter runs on this host. Tried, in order:`,
    ...tried.map((t) => `  ${t.label.padEnd(width)}  ${t.refusal}`),
    `Install Python ${MIN_VERSION.join('.')}+ so that one of these runs it.`,
  ].join('\n');
}

/** Runs `argv` (a script and its arguments) under the resolved interpreter; returns the exit status. */
function main(argv, { candidates = CANDIDATES, env = process.env, log = console.error } = {}) {
  if (argv.length === 0 || argv[0].startsWith('-')) {
    log('usage: node scripts/lib/python.cjs <script.py> [args...]');
    return 2;
  }
  const { found, tried } = resolvePython({ candidates, env });
  if (!found) {
    log(noInterpreterMessage(tried));
    return 2;
  }
  const [command, lead] = found;
  const r = spawnSync(command, [...lead, ...argv], { env, stdio: 'inherit', windowsHide: true });
  if (r.error) {
    log(`python.cjs: could not run ${label(found)}: ${r.error.message}`);
    return 1;
  }
  if (r.signal) {
    log(`python.cjs: ${label(found)} ${argv[0]} was killed by ${r.signal}`);
    return 1;
  }
  return r.status;
}

if (require.main === module) {
  process.exit(main(process.argv.slice(2)));
}

module.exports = {
  CANDIDATES,
  MIN_VERSION,
  main,
  noInterpreterMessage,
  resolvePython,
};
