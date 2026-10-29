#!/usr/bin/env node
'use strict';

/*
 * The one way into shadow-cljs.
 *
 * Every script that runs shadow-cljs — its npm runner (`shadow-cljs/cli/runner.js`)
 * or the JVM `shadow.cljs.devtools.cli` through `clojure` — goes through this module,
 * as a library (`runShadow`) or as a CLI (the npm scripts). `_shadow-spawn-policy.test.cjs`
 * refuses any other route. It does three things.
 *
 * 1. THE BUILD LANE. Two shadow-cljs builds running at once on this machine wedge
 *    silently: no error, no exit, no output. So a build first takes a machine-wide lock,
 *    a file in the OS temp directory (outside every worktree; `/tmp` on POSIX), created
 *    atomically by hard-linking a fully written record into place, so no reader ever sees
 *    half a holder. The record is {pid, child, childAt, worktree, command, started, token}.
 *
 *    The lane passes in arrival order. A run asking for it writes its record as a ticket
 *    into the queue directory beside the lock (`<lock>.queue/<token>.ticket`), refreshes
 *    it on every poll, and takes a freed lane only when no earlier ticket is still
 *    waiting, so a slow poller is never overtaken by a later arrival. A run that finds
 *    the lane busy prints
 *
 *        shadow-lane: waiting for build lane held by <pid> (<worktree>, <command>) since <started>; queue position <n>; lock file <path> (delete it only if neither <pid> nor its compiler <child> is a shadow-cljs build)
 *
 *    where position 1 is next, and polls until its turn. The hint names both recorded
 *    processes because either one alive keeps the lane held; before the compiler is
 *    spawned it names <pid> alone. A holder whose launcher and
 *    compiler child are both gone is dead, and so is one whose pid now names a process
 *    that began after the record named it: the operating system has handed a dead
 *    holder's pid to an unrelated process. The next run reclaims a dead holder's lock with
 *    no manual step; the reclaim happens under a short-lived guard directory, so two
 *    waiters cannot both reclaim and both proceed. The lock is released when the build
 *    ends, on Ctrl-C or a termination signal, and on process exit; a kill that gives this
 *    process no chance to run is covered by the dead-holder reclaim. Ctrl-C while waiting
 *    exits without taking it. A ticket is retired by the same two tests, and also once
 *    it goes unrefreshed for TICKET_STALE_MS, so neither a waiter that is killed nor one
 *    that stops waiting without removing its ticket holds up the queue.
 *
 *    Every worktree runs its own copy of this helper, and a copy without the queue still
 *    shares the lane: it waits on the same lock record and takes a freed lane whenever
 *    it polls first, but writes no ticket, so the queue orders only the runs that do.
 *
 *    `RF2_SHADOW_LANE_LOCK` names a different lock file, which is how the tests stay off
 *    the real lane. A holder exports its token as `RF2_SHADOW_LANE_TOKEN`, so a descendant
 *    asking for the lane while an ancestor holds it proceeds instead of deadlocking. On CI
 *    a runner builds one thing at a time, so the lock is always free and costs one file.
 *
 * 2. THE PROCESS TREE. The compiler is spawned shell-free under this node — the runner's
 *    `.js` resolved from the project directory, or, for a project with no `node_modules`
 *    of its own (the Fresco bench lane), from the implementation install beside this
 *    helper; never `npx` or a `.cmd` — and on POSIX as
 *    a process-group leader, so its whole tree (node runner, JVM) dies together. A signal
 *    to this process, or its exit, reaps that tree before the lock is released.
 *
 * 3. STALE OUTPUT. A one-shot compile deletes the outputs its caller declares (`clean`)
 *    after taking the lane and before compiling. A failed compile then leaves NO bundle
 *    rather than the previous one, so a caller that does not chain the exit code fails
 *    loudly instead of testing old code, and a clean exit that leaves a declared output
 *    missing is refused. Nothing undeclared is touched: watch outputs and the shadow-cljs
 *    cache stay.
 *
 * THE LANE BY COMMAND.
 *   - compile, release, check, run, clj-run: one-shot, so the lane is held for the run.
 *   - watch: held until every named build reports its first `Build completed` or
 *     `Build failure`, then released while the watch runs on. A watch lives for hours,
 *     and holding the lane that long would block every other worker's compile; a later
 *     recompile inside the watch runs outside the lane, and that overlap is the
 *     programmer's call. `--hold-lane` (`holdLane`) keeps the lane for the watch's whole
 *     life, for a bounded gate whose test IS a series of recompiles.
 *   - anything else (server, the REPLs, info): no lane, and stdin stays attached.
 *
 * CLI
 *   node scripts/lib/shadow.cjs [--clean <path>]... [--cwd <dir>] [--clojure <aliases>]
 *                               [--hold-lane] <shadow-cljs command> [args...]
 *     --clean <path>       a declared disposable output, relative to the current directory
 *     --cwd <dir>          the shadow-cljs project directory (default: the current one)
 *     --clojure <aliases>  run `clojure <aliases> -m shadow.cljs.devtools.cli <command> ...`
 *     --hold-lane          hold the lane for the whole run, whatever the command
 *   Exit: the compiler's own status; 1 for a signal death, a spawn failure or a missing
 *   declared output; 2 for a usage error; 128+n when signal n stops this process.
 */

const { spawnSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {
  spawnHarnessProcess,
  terminateProcessTreeSync,
} = require('./local-browser-harness.cjs');

const LOCK_ENV = 'RF2_SHADOW_LANE_LOCK';
const TOKEN_ENV = 'RF2_SHADOW_LANE_TOKEN';
const POLL_MS = 500;
// A reclaim guard or an unreadable lock older than this belongs to a process that died
// mid-write; nothing healthy holds either for more than milliseconds.
const STALE_MS = 10000;
// How long one probe of a live pid's start time is believed. The probe spawns a process,
// so a waiter polling the same holder reuses it, and re-probes this often so a holder that
// dies mid-wait and has its pid handed on is still caught.
const IDENTITY_TTL_MS = 30000;
// A start time reported this far after the record named the pid still counts as the
// holder's, which absorbs clock granularity; a reused pid begins long after.
const CLOCK_SLACK_MS = 1000;
// A waiting run refreshes its ticket on every poll, so one left unrefreshed this long
// belongs to a run that is no longer waiting. It is well above the longest a poll can be
// held up by the start-time probes.
const TICKET_STALE_MS = 30000;
const ONE_SHOT = new Set(['compile', 'release', 'check', 'run', 'clj-run']);
const SIGNALS = process.platform === 'win32'
  ? ['SIGINT', 'SIGTERM', 'SIGHUP', 'SIGBREAK']
  : ['SIGINT', 'SIGTERM', 'SIGHUP'];

function log(line) {
  process.stderr.write(`${line}\n`);
}

// ---------------------------------------------------------------------------
// The lock
// ---------------------------------------------------------------------------

function laneLockPath(env = process.env) {
  if (env[LOCK_ENV]) return path.resolve(env[LOCK_ENV]);
  const dir = process.platform === 'win32' ? os.tmpdir() : '/tmp';
  return path.join(dir, 'rf2-shadow-cljs-build-lane.lock');
}

function isAlive(pid) {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (err) {
    return err.code === 'EPERM';
  }
}

// When a live process began, in ms since the epoch, or null where the OS will not say.
function probeStartTime(pid) {
  try {
    if (process.platform === 'win32') {
      const r = spawnSync(
        'powershell.exe',
        [
          '-NoProfile',
          '-NonInteractive',
          '-Command',
          `[DateTimeOffset]::new((Get-Process -Id ${pid} -ErrorAction Stop).StartTime).ToUnixTimeMilliseconds()`,
        ],
        { encoding: 'utf8', windowsHide: true, timeout: 15000 },
      );
      const ms = Number(r.stdout.trim());
      return r.status === 0 && ms > 0 ? ms : null;
    }
    const r = spawnSync('ps', ['-o', 'lstart=', '-p', String(pid)], {
      encoding: 'utf8',
      env: { ...process.env, LC_ALL: 'C' },
      timeout: 15000,
    });
    const ms = Date.parse(r.stdout.trim());
    return r.status === 0 && Number.isFinite(ms) ? ms : null;
  } catch (_) {
    return null;
  }
}

const probed = new Map();

function startTimeOf(pid) {
  const hit = probed.get(pid);
  if (hit && Date.now() - hit.at < IDENTITY_TTL_MS) return hit.began;
  const began = probeStartTime(pid);
  probed.set(pid, { began, at: Date.now() });
  return began;
}

// Whether `pid` still names the process a record named at `namedAt`. A live pid whose
// process began after that is an unrelated process holding a dead holder's recycled pid,
// which would otherwise hold the lane for as long as it lives. Where the record carries no
// time, or the OS will not say when the process began, a live pid counts.
function isRecordedProcess(pid, namedAt) {
  if (!isAlive(pid)) return false;
  const named = Date.parse(namedAt);
  if (!Number.isFinite(named)) return true;
  const began = startTimeOf(pid);
  return began === null || began <= named + CLOCK_SLACK_MS;
}

function holderAlive(holder) {
  return isRecordedProcess(holder.pid, holder.started) || isRecordedProcess(holder.child, holder.childAt);
}

function mtimeOf(file) {
  try {
    return fs.statSync(file).mtimeMs;
  } catch (_) {
    return Date.now();
  }
}

// null when the lane is free; the holder's record; or {unreadable, mtimeMs} for a file
// that is not a record.
function readHolder(lockPath) {
  let text;
  try {
    text = fs.readFileSync(lockPath, 'utf8');
  } catch (err) {
    if (err.code === 'ENOENT') return null;
    return { unreadable: true, mtimeMs: mtimeOf(lockPath) };
  }
  try {
    const record = JSON.parse(text);
    if (record && typeof record.token === 'string') return record;
  } catch (_) {
    // fall through
  }
  return { unreadable: true, mtimeMs: mtimeOf(lockPath) };
}

function describeHolder(holder) {
  if (holder.unreadable) return 'an unreadable lock file';
  return `${holder.pid} (${holder.worktree}, ${holder.command}) since ${holder.started}`;
}

// The worktree a build belongs to: the nearest directory holding `.git`.
function worktreeOf(dir) {
  const start = path.resolve(dir);
  for (let at = start; ; at = path.dirname(at)) {
    if (fs.existsSync(path.join(at, '.git'))) return at;
    if (path.dirname(at) === at) return start;
  }
}

function tryTake(lockPath, record) {
  fs.mkdirSync(path.dirname(lockPath), { recursive: true });
  const staged = `${lockPath}.${record.token}.tmp`;
  fs.writeFileSync(staged, `${JSON.stringify(record)}\n`);
  try {
    fs.linkSync(staged, lockPath);
    return true;
  } catch (err) {
    if (err.code === 'EEXIST') return false;
    throw err;
  } finally {
    fs.rmSync(staged, { force: true });
  }
}

// Remove a dead holder's lock. Only the process holding the guard may remove a lock, and
// it re-reads under the guard, so a live holder that took the lane a moment ago is
// never removed in its predecessor's place.
function reclaim(lockPath, seen) {
  const guard = `${lockPath}.reclaim`;
  try {
    fs.mkdirSync(guard);
  } catch (err) {
    if (err.code !== 'EEXIST') throw err;
    if (Date.now() - mtimeOf(guard) > STALE_MS) fs.rmSync(guard, { recursive: true, force: true });
    return false;
  }
  try {
    const now = readHolder(lockPath);
    if (!now) return false;
    const same = seen.unreadable ? now.unreadable : now.token === seen.token;
    const dead = now.unreadable ? Date.now() - now.mtimeMs > STALE_MS : !holderAlive(now);
    if (!same || !dead) return false;
    fs.rmSync(lockPath, { force: true });
    return true;
  } finally {
    fs.rmSync(guard, { recursive: true, force: true });
  }
}

// ---------------------------------------------------------------------------
// The queue
// ---------------------------------------------------------------------------

// A ticket is the waiting run's lane record, written whole by a rename.
function writeTicket(queueDir, record) {
  fs.mkdirSync(queueDir, { recursive: true });
  const file = path.join(queueDir, `${record.token}.ticket`);
  fs.writeFileSync(`${file}.tmp`, `${JSON.stringify(record)}\n`);
  fs.renameSync(`${file}.tmp`, file);
  return file;
}

// A ticket a reader retired while this run was still waiting is written again, with its
// original arrival time, so the run keeps its place.
function refreshTicket(file, record) {
  const now = new Date();
  try {
    fs.utimesSync(file, now, now);
  } catch (err) {
    if (err.code === 'ENOENT') writeTicket(path.dirname(file), record);
  }
}

// Best effort: a ticket that cannot be removed goes stale.
function dropTicket(file) {
  try {
    fs.rmSync(file, { force: true });
  } catch (_) {
    // retired by TICKET_STALE_MS
  }
}

// Arrival order: when each run asked for the lane, ties broken by token.
function byArrival(a, b) {
  return Date.parse(a.started) - Date.parse(b.started) || (a.token < b.token ? -1 : a.token > b.token ? 1 : 0);
}

// The tickets still waiting, in arrival order. One whose pid is gone, or left unrefreshed
// for TICKET_STALE_MS, is removed on sight; so is a file a writer died before renaming.
function waitingTickets(queueDir) {
  let names;
  try {
    names = fs.readdirSync(queueDir);
  } catch (_) {
    return [];
  }
  const tickets = [];
  for (const name of names) {
    const file = path.join(queueDir, name);
    const age = Date.now() - mtimeOf(file);
    const read = name.endsWith('.ticket') ? readHolder(file) : null;
    const ticket = read && !read.unreadable ? read : null;
    if (!ticket) {
      if (age > STALE_MS) dropTicket(file);
    } else if (!isAlive(ticket.pid) || age > TICKET_STALE_MS) {
      dropTicket(file);
    } else {
      tickets.push({ ...ticket, file });
    }
  }
  return tickets.sort(byArrival);
}

// The earliest of `ahead` still waiting, by the holder's start-time rule; a ticket whose
// pid now names a process that began later is removed.
function nextInLine(ahead) {
  for (const ticket of ahead) {
    if (isRecordedProcess(ticket.pid, ticket.started)) return ticket;
    dropTicket(ticket.file);
  }
  return null;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// This process's hold on the lane; `depth` makes a nested acquire in-process a no-op.
const lane = { held: null, depth: 0, waiting: 0, tickets: new Set() };
// Compiler children this process spawned and has not yet seen exit.
const owned = new Set();

/**
 * Take the build lane, waiting for it if another run holds it. Resolves to a release
 * function, which is idempotent; exit and termination signals release it too.
 */
async function acquireLane({ command, cwd = process.cwd(), pollMs = POLL_MS } = {}) {
  if (lane.held) {
    lane.depth += 1;
    return releaseOnce();
  }
  const lockPath = laneLockPath();
  const queueDir = `${lockPath}.queue`;
  const record = {
    pid: process.pid,
    child: null,
    worktree: worktreeOf(cwd),
    command: command || 'shadow-cljs',
    started: new Date().toISOString(),
    token: crypto.randomBytes(8).toString('hex'),
  };
  const inherited = process.env[TOKEN_ENV];
  const waitStarted = Date.now();
  let announced = null;
  let ticket = null;
  lane.waiting += 1;
  armSignals();
  try {
    ticket = writeTicket(queueDir, record);
    lane.tickets.add(ticket);
    for (;;) {
      refreshTicket(ticket, record);
      const holder = readHolder(lockPath);
      if (holder && !holder.unreadable && inherited && holder.token === inherited && holderAlive(holder)) {
        // An ancestor holds the lane for this whole run; waiting on it would deadlock.
        return () => {};
      }
      if (holder) {
        const dead = holder.unreadable
          ? Date.now() - holder.mtimeMs > STALE_MS
          : !holderAlive(holder);
        if (dead) {
          if (reclaim(lockPath, holder)) {
            log(`shadow-lane: reclaimed the build lane from dead holder ${describeHolder(holder)}`);
          } else {
            await sleep(Math.min(pollMs, 100));
          }
          continue;
        }
      }
      // The holder's own ticket can outlive its take by a moment; it is not ahead of anyone.
      const ahead = waitingTickets(queueDir)
        .filter((t) => t.token !== (holder && holder.token) && byArrival(t, record) < 0);
      if (!holder) {
        if (!nextInLine(ahead)) {
          if (tryTake(lockPath, record)) {
            lane.held = { lockPath, record };
            lane.depth = 1;
            break;
          }
          continue;
        }
      } else {
        const key = `${holder.unreadable ? 'unreadable' : holder.token} ${ahead.length}`;
        if (announced !== key) {
          announced = key;
          // The lane stays held while either recorded process lives (`holderAlive`).
          const hint = holder.unreadable ? ''
            : holder.child ? ` (delete it only if neither ${holder.pid} nor its compiler ${holder.child} is a shadow-cljs build)`
            : ` (delete it if ${holder.pid} is not a shadow-cljs build)`;
          log(`shadow-lane: waiting for build lane held by ${describeHolder(holder)}; queue position ${ahead.length + 1}; lock file ${lockPath}${hint}`);
        }
      }
      await sleep(pollMs);
    }
  } finally {
    if (ticket) {
      dropTicket(ticket);
      lane.tickets.delete(ticket);
    }
    lane.waiting -= 1;
    disarmIfIdle();
  }
  process.env[TOKEN_ENV] = record.token;
  if (announced) {
    const waited = Math.round((Date.now() - waitStarted) / 1000);
    log(`shadow-lane: took the build lane after waiting ${waited}s`);
  }
  return releaseOnce();
}

function releaseOnce() {
  let done = false;
  return () => {
    if (done) return;
    done = true;
    releaseLane();
  };
}

function releaseLane(all = false) {
  if (!lane.held) return;
  lane.depth = all ? 0 : lane.depth - 1;
  if (lane.depth > 0) return;
  const { lockPath, record } = lane.held;
  lane.held = null;
  if (process.env[TOKEN_ENV] === record.token) delete process.env[TOKEN_ENV];
  const holder = readHolder(lockPath);
  if (holder && holder.token === record.token) fs.rmSync(lockPath, { force: true });
  disarmIfIdle();
}

// Name the compiler child in the record, so a lane whose launcher was killed outright
// stays held while the compiler it started is still running.
function recordChild(pid) {
  if (!lane.held || !pid) return;
  const { lockPath, record } = lane.held;
  record.child = pid;
  record.childAt = new Date().toISOString();
  const staged = `${lockPath}.${record.token}.tmp`;
  try {
    fs.writeFileSync(staged, `${JSON.stringify(record)}\n`);
    fs.renameSync(staged, lockPath);
  } catch (_) {
    // The launcher's own pid still marks the lane held.
    fs.rmSync(staged, { force: true });
  }
}

// ---------------------------------------------------------------------------
// Owning the tree: exit and signals
// ---------------------------------------------------------------------------

function reapSync() {
  for (const child of owned) {
    try {
      terminateProcessTreeSync(child, { signal: 'SIGTERM' });
    } catch (_) {
      // best effort: the lane is still released below
    }
  }
  owned.clear();
  for (const ticket of lane.tickets) dropTicket(ticket);
  lane.tickets.clear();
  releaseLane(true);
}

const signalHandlers = new Map();
let exitHooked = false;

function onSignal(signal) {
  // Counted before the reap, which disarms this handler once nothing is left to own.
  const callerHandles = process.listenerCount(signal) > 1;
  reapSync();
  // Leave the exit to any other handler the caller installed; otherwise exit as the
  // signal would have.
  if (!callerHandles) process.exit(128 + (os.constants.signals[signal] || 1));
}

function armSignals() {
  if (!exitHooked) {
    process.on('exit', reapSync);
    exitHooked = true;
  }
  if (signalHandlers.size > 0) return;
  for (const signal of SIGNALS) {
    const handler = () => onSignal(signal);
    signalHandlers.set(signal, handler);
    process.on(signal, handler);
  }
}

function disarmIfIdle() {
  if (lane.held || lane.waiting > 0 || owned.size > 0) return;
  for (const [signal, handler] of signalHandlers) process.off(signal, handler);
  signalHandlers.clear();
}

// ---------------------------------------------------------------------------
// Running shadow-cljs
// ---------------------------------------------------------------------------

function lanePolicy(command, holdLane = false) {
  if (holdLane || ONE_SHOT.has(command)) return 'hold';
  if (command === 'watch') return 'until-built';
  return 'none';
}

// The build ids a watch names: every argument after the command that is neither an
// option nor the EDN value of `--config-merge`.
function buildIds(args) {
  const ids = [];
  for (let i = 1; i < args.length; i += 1) {
    const arg = args[i];
    if (arg === '--config-merge') {
      i += 1;
    } else if (!arg.startsWith('-') && !arg.startsWith('{')) {
      ids.push(arg.replace(/^:/, ''));
    }
  }
  return ids;
}

// The implementation install, beside this helper.
const IMPL_DIR = path.resolve(__dirname, '..', '..');

// A project with no install of its own shares the implementation's; a project that
// has one must carry shadow-cljs in it, so a broken install fails loudly.
function resolveRunner(projectDir) {
  const own = fs.existsSync(path.join(projectDir, 'node_modules'));
  return require.resolve('shadow-cljs/cli/runner.js', { paths: own ? [projectDir] : [projectDir, IMPL_DIR] });
}

// Spawn the compiler as an owned child and settle with how it ended. Under the
// `until-built` policy its output is teed through this process, so the lane can be
// released once every watched build has reported.
function spawnOwned(exe, args, { cwd, policy, builds, release, env }) {
  return new Promise((resolve) => {
    const watching = policy === 'until-built';
    const out = watching ? 'pipe' : 'inherit';
    let child;
    try {
      child = spawnHarnessProcess(exe, args, {
        cwd,
        env,
        stdio: [policy === 'none' ? 'inherit' : 'ignore', out, out],
      });
    } catch (error) {
      resolve({ error });
      return;
    }
    let settled = false;
    const settle = (result) => {
      if (settled) return;
      settled = true;
      owned.delete(child);
      disarmIfIdle();
      resolve(result);
    };
    owned.add(child);
    armSignals();
    recordChild(child.pid);
    if (watching) {
      const pending = new Set(builds.map((id) => `[:${id}]`));
      let partial = '';
      const scan = (chunk) => {
        if (pending.size === 0) return;
        const lines = (partial + chunk.toString()).split(/\r?\n/);
        partial = lines.pop();
        for (const line of lines) {
          if (!/Build (completed|failure)/.test(line)) continue;
          for (const marker of pending) if (line.includes(marker)) pending.delete(marker);
        }
        if (pending.size === 0) {
          release();
          log('shadow-lane: the watch finished its first build; released the build lane, the watch runs on');
        }
      };
      child.stdout.on('data', (chunk) => {
        process.stdout.write(chunk);
        scan(chunk);
      });
      child.stderr.on('data', (chunk) => {
        process.stderr.write(chunk);
        scan(chunk);
      });
    }
    child.on('error', (error) => settle({ error }));
    // BOTH arguments: a signal death closes as (null, signal), and the signal name is
    // the only record of the cause.
    child.on('close', (status, signal) => settle({ status, signal }));
  });
}

/**
 * The compiler's verdict as one exit code. A numeric status is the compiler's own and
 * passes through. Anything else is abnormal and becomes 1 — above all a signal death,
 * which `close` reports as a null status that `process.exit()` would read as success,
 * so an OOM kill or a cancelled job would otherwise pass. A zero exit that leaves a
 * declared output missing is refused too.
 */
function verdict(result, { label, display, clean }) {
  const named = clean.map((p) => path.relative(process.cwd(), p) || p).join(', ');
  if (result.error) {
    log(`${label}: failed to start ${display}: ${result.error.message}`);
    return 1;
  }
  if (result.status !== 0) {
    const numeric = typeof result.status === 'number';
    const cause = numeric
      ? `did not complete (exit ${result.status})`
      : result.signal
        ? `was terminated by signal ${result.signal} before it could complete`
        : 'did not complete and reported no exit status';
    const cleared = clean.length > 0
      ? `; ${named} was cleared before the attempt, not left stale`
      : '';
    log(`${label}: ${display} ${cause}${cleared}.`);
    return numeric ? result.status : 1;
  }
  const missing = clean.filter((p) => !fs.existsSync(p));
  if (missing.length > 0) {
    const names = missing.map((p) => path.relative(process.cwd(), p) || p).join(', ');
    log(`${label}: ${display} exited 0 but ${names} is missing; treating as fatal.`);
    return 1;
  }
  return 0;
}

/**
 * Run shadow-cljs and resolve to its exit code (see `verdict`).
 *
 *   args      the shadow-cljs command line, command first: ['compile', 'node-test']
 *   cwd       the shadow-cljs project directory
 *   clean     declared disposable outputs (absolute, or relative to process.cwd()),
 *             deleted before a one-shot compile and required after a clean exit
 *   clojure   aliases for the JVM entry, e.g. '-M:cljs-test'; omitted, the npm runner
 *   holdLane  hold the lane for the whole run, whatever the command
 *   label     prefix for this module's own messages
 */
async function runShadow(args, opts = {}) {
  const {
    cwd = process.cwd(),
    clean = [],
    clojure = null,
    holdLane = false,
    label = 'shadow',
  } = opts;
  const command = args[0];
  const policy = lanePolicy(command, holdLane);
  const cleanPaths = clean.map((p) => path.resolve(p));
  if (cleanPaths.length > 0 && !ONE_SHOT.has(command)) {
    log(`${label}: --clean declares the output of a one-shot compile; \`${command}\` is not one.`);
    return 2;
  }
  const display = clojure
    ? `clojure ${clojure} -m shadow.cljs.devtools.cli ${args.join(' ')}`
    : `shadow-cljs ${args.join(' ')}`;

  const release = policy === 'none' ? () => {} : await acquireLane({ command: display, cwd });
  try {
    // The declared outputs go first, before anything can refuse the run: a stale
    // bundle that outlived a failed attempt is what this helper exists to prevent,
    // whether the compiler failed or never started.
    for (const p of cleanPaths) {
      fs.rmSync(p, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
    }
    let exe;
    let exeArgs;
    if (clojure) {
      exe = 'clojure';
      exeArgs = [...clojure.split(/\s+/).filter(Boolean), '-m', 'shadow.cljs.devtools.cli', ...args];
    } else {
      try {
        exeArgs = [resolveRunner(cwd), ...args];
      } catch (err) {
        log(`${label}: could not resolve shadow-cljs from ${cwd} (run \`npm install\` there): ${err.message}`);
        return 1;
      }
      exe = process.execPath;
    }
    const result = await spawnOwned(exe, exeArgs, {
      cwd,
      policy,
      builds: buildIds(args),
      release,
      env: process.env,
    });
    return verdict(result, { label, display, clean: cleanPaths });
  } finally {
    release();
  }
}

const USAGE =
  'usage: node scripts/lib/shadow.cjs [--clean <path>]... [--cwd <dir>] ' +
  '[--clojure <aliases>] [--hold-lane] <shadow-cljs command> [args...]';

async function main(argv) {
  const opts = { clean: [], cwd: process.cwd(), clojure: null, holdLane: false, label: 'shadow' };
  let i = 0;
  for (; i < argv.length; i += 1) {
    const arg = argv[i];
    const takesValue = arg === '--clean' || arg === '--cwd' || arg === '--clojure';
    if (takesValue && (i + 1 >= argv.length)) {
      log(`shadow: ${arg} needs a value\n${USAGE}`);
      return 2;
    }
    if (arg === '--clean') opts.clean.push(path.resolve(argv[(i += 1)]));
    else if (arg === '--cwd') opts.cwd = path.resolve(argv[(i += 1)]);
    else if (arg === '--clojure') opts.clojure = argv[(i += 1)];
    else if (arg === '--hold-lane') opts.holdLane = true;
    else break;
  }
  const args = argv.slice(i);
  if (args.length === 0 || args[0].startsWith('-')) {
    log(USAGE);
    return 2;
  }
  return runShadow(args, opts);
}

if (require.main === module) {
  main(process.argv.slice(2)).then(
    (code) => process.exit(code),
    (err) => {
      log(err && err.stack ? err.stack : String(err));
      process.exit(1);
    },
  );
}

module.exports = {
  SHADOW_CLI: __filename,
  LOCK_ENV,
  TOKEN_ENV,
  acquireLane,
  buildIds,
  laneLockPath,
  lanePolicy,
  main,
  readHolder,
  runShadow,
};
