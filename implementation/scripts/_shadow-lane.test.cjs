#!/usr/bin/env node

'use strict';

/*
 * The build lane and the process tree of `lib/shadow.cjs`, exercised at the process
 * boundary: real child processes take, wait for, reclaim and release a lock file, and
 * a stand-in shadow-cljs runner (a scratch project's `node_modules/shadow-cljs/cli/
 * runner.js`) plays the compiler, so nothing here enters the real shadow-cljs. Every
 * process gets `RF2_SHADOW_LANE_LOCK` pointing into this suite's scratch lane, so the
 * suite never touches, or queues behind, the machine's real build lane.
 *
 * Signals are delivered with `process.emit` inside the child, which runs the same
 * handlers an OS signal would and works on Windows, where Node cannot send a catchable
 * signal to another process. Discovered by `npm run test:scripts`.
 */

const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { test, after } = require('node:test');

const { makeScratchDir, cleanupScratchDirs } = require('./lib/scratch-fixtures.cjs');
const { buildIds, lanePolicy } = require('./lib/shadow.cjs');

const SCRIPTS_DIR = __dirname;
const REPO_ROOT = path.resolve(SCRIPTS_DIR, '..', '..');
const HELPER = path.join(SCRIPTS_DIR, 'lib', 'shadow.cjs');

const lane = makeScratchDir(REPO_ROOT, 'shadow-lane');
// A failed test can leave a child waiting on a lane nobody releases; none may
// outlive the suite.
const live = new Set();
after(() => {
  for (const child of live) {
    try {
      child.kill('SIGKILL');
    } catch (_) {
      // already gone
    }
  }
  cleanupScratchDirs();
});

// The stand-in for shadow-cljs's runner. FAKE_MODE picks the behaviour: `ok` writes
// FAKE_OUT and exits 0, `no-output` exits 0 writing nothing, `fail3` exits 3, and
// `sleep` / `watch` start a grandchild, record both pids in FAKE_PIDFILE and run until
// killed (`watch` also reports its build as completed).
function fakeRunner() {
  const fs = require('node:fs');
  const cp = require('node:child_process');
  const mode = process.env.FAKE_MODE || 'ok';
  if (mode === 'sleep' || mode === 'watch') {
    const grandchild = cp.spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
      stdio: 'ignore',
    });
    fs.writeFileSync(
      process.env.FAKE_PIDFILE,
      JSON.stringify({ runner: process.pid, grandchild: grandchild.pid }),
    );
    if (mode === 'watch') {
      setTimeout(() => {
        process.stdout.write('[:app] Build completed. (1 files, 1 compiled, 0 warnings, 0.01s)\n');
      }, 200);
    }
    setInterval(() => {}, 1000);
  } else {
    if (mode === 'ok') fs.writeFileSync(process.env.FAKE_OUT, '// built\n');
    process.exit(mode === 'fail3' ? 3 : 0);
  }
}

// One driver for every child process role, picked by argv[2].
function driver() {
  const fs = require('node:fs');
  const { spawnSync } = require('node:child_process');
  const { acquireLane, runShadow } = require(process.env.SL_HELPER);
  const say = (line) => process.stdout.write(`${line}\n`);
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const [mode, ...rest] = process.argv.slice(2);
  (async () => {
    if (mode === 'hold') {
      const release = await acquireLane({ command: rest[0], pollMs: 50 });
      say(`HELD ${process.pid}`);
      process.stdin.setEncoding('utf8');
      process.stdin.on('data', (data) => {
        if (data.includes('nested')) {
          const nested = spawnSync(process.execPath, [__filename, 'take', 'nested'], {
            encoding: 'utf8',
            timeout: 10000,
          });
          say(`NESTED ${nested.status} ${JSON.stringify(nested.stderr)}`);
        }
        if (data.includes('release')) {
          release();
          say('RELEASED');
          process.exit(0);
        }
      });
    } else if (mode === 'take') {
      const release = await acquireLane({ command: rest[0], pollMs: 50 });
      say(`HELD ${process.pid}`);
      release();
      say('RELEASED');
      process.exit(0);
    } else if (mode === 'run') {
      const code = await runShadow(JSON.parse(rest[0]), JSON.parse(rest[1]));
      say(`EXIT ${code}`);
      process.exit(code);
    } else if (mode === 'signal-while-waiting') {
      setTimeout(() => process.emit(rest[0]), 600);
      await acquireLane({ command: 'waiter', pollMs: 50 });
      say('HELD-UNEXPECTEDLY');
    } else if (mode === 'signal-while-running') {
      const [signal, project, owner] = rest;
      // With a handler of the caller's own, the helper reaps and releases but leaves
      // the exit to that handler, so this process can look at the tree while alive.
      if (owner === 'caller-exits') process.on(signal, () => {});
      runShadow(['compile', 'app'], { cwd: project });
      while (!fs.existsSync(process.env.FAKE_PIDFILE)) await sleep(50);
      say('RUNNING');
      process.emit(signal);
      const { runner, grandchild } = JSON.parse(fs.readFileSync(process.env.FAKE_PIDFILE, 'utf8'));
      const alive = (pid) => {
        try {
          process.kill(pid, 0);
          return true;
        } catch (err) {
          return err.code === 'EPERM';
        }
      };
      const deadline = Date.now() + 10000;
      while ((alive(runner) || alive(grandchild)) && Date.now() < deadline) await sleep(50);
      say(`TREE ${alive(runner) || alive(grandchild) ? 'alive' : 'reaped'}`);
      say(`LANE ${fs.existsSync(process.env.RF2_SHADOW_LANE_LOCK) ? 'held' : 'free'}`);
      process.exit(0);
    } else if (mode === 'watch') {
      runShadow(['watch', ':app'], { cwd: rest[0] });
      const lock = process.env.RF2_SHADOW_LANE_LOCK;
      while (!fs.existsSync(process.env.FAKE_PIDFILE)) await sleep(50);
      const { runner } = JSON.parse(fs.readFileSync(process.env.FAKE_PIDFILE, 'utf8'));
      while (fs.existsSync(lock)) await sleep(50);
      let alive = true;
      try {
        process.kill(runner, 0);
      } catch (_) {
        alive = false;
      }
      say(`LANE-FREE runner-alive=${alive}`);
      process.emit('SIGINT');
    }
  })();
}

function writeFn(file, fn) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, `'use strict';\n(${fn.toString()})();\n`);
}

const DRIVER = path.join(lane, 'driver.cjs');
writeFn(DRIVER, driver);
const PROJECT = path.join(lane, 'project');
writeFn(path.join(PROJECT, 'node_modules', 'shadow-cljs', 'cli', 'runner.js'), fakeRunner);

let lockCounter = 0;
function freshLock() {
  lockCounter += 1;
  return path.join(lane, `lane-${lockCounter}.lock`);
}

function isAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (err) {
    return err.code === 'EPERM';
  }
}

// A lane that is never released hangs its waiter, so every test is bounded.
const TIMEOUT_MS = 60000;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function eventually(predicate, what, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await sleep(50);
  }
  assert.fail(`timed out waiting for ${what}`);
}

// A driver child with its output collected as it arrives.
function start(args, env) {
  const child = spawn(process.execPath, [DRIVER, ...args], {
    cwd: PROJECT,
    env: { ...process.env, SL_HELPER: HELPER, RF2_SHADOW_LANE_TOKEN: '', ...env },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  live.add(child);
  const proc = { child, out: '', err: '', code: undefined };
  child.stdout.on('data', (d) => { proc.out += d; });
  child.stderr.on('data', (d) => { proc.err += d; });
  proc.exited = new Promise((resolve) => {
    child.on('close', (code, signal) => {
      proc.code = code;
      proc.signal = signal;
      resolve(proc);
    });
  });
  return proc;
}

function readLock(lock) {
  return JSON.parse(fs.readFileSync(lock, 'utf8'));
}

function leftovers(lock) {
  const base = path.basename(lock);
  return fs.readdirSync(path.dirname(lock)).filter((f) => f.startsWith(`${base}.`));
}

test('the lane policy follows the command, and a watch names its builds', () => {
  assert.equal(lanePolicy('compile'), 'hold');
  assert.equal(lanePolicy('release'), 'hold');
  assert.equal(lanePolicy('watch'), 'until-built');
  assert.equal(lanePolicy('watch', true), 'hold');
  assert.equal(lanePolicy('server'), 'none');
  assert.deepEqual(
    buildIds(['watch', ':examples/a', 'b', '--config-merge', '{:x 1}', '--verbose']),
    ['examples/a', 'b'],
  );
});

test('a holder records who it is, and releasing removes the lock', { timeout: TIMEOUT_MS }, async () => {
  const lock = freshLock();
  const a = start(['hold', 'the-holder'], { RF2_SHADOW_LANE_LOCK: lock });
  await eventually(() => a.out.includes('HELD'), 'the holder to take the lane');
  const record = readLock(lock);
  assert.equal(record.pid, a.child.pid);
  assert.equal(record.command, 'the-holder');
  assert.equal(record.worktree, REPO_ROOT, 'the worktree is the nearest directory holding .git');
  assert.ok(!Number.isNaN(Date.parse(record.started)), `started is a timestamp: ${record.started}`);
  assert.match(record.token, /^[0-9a-f]{16}$/);
  a.child.stdin.write('release\n');
  await a.exited;
  assert.equal(a.code, 0);
  assert.equal(fs.existsSync(lock), false, 'the release left the lock file behind');
  assert.deepEqual(leftovers(lock), []);
});

test('a second run waits, names the holder, and proceeds once the holder releases', { timeout: TIMEOUT_MS }, async () => {
  const lock = freshLock();
  const a = start(['hold', 'first-build'], { RF2_SHADOW_LANE_LOCK: lock });
  await eventually(() => a.out.includes('HELD'), 'the first run to take the lane');
  const b = start(['take', 'second-build'], { RF2_SHADOW_LANE_LOCK: lock });
  await eventually(() => b.err.includes('waiting for build lane'), 'the waiting line');
  assert.ok(
    b.err.includes(`waiting for build lane held by ${a.child.pid} (${REPO_ROOT}, first-build)`),
    `the waiting line names the holder's pid, worktree and command:\n${b.err}`,
  );
  assert.ok(
    b.err.includes(`; lock file ${lock} (delete it if ${a.child.pid} is not a shadow-cljs build)`),
    `the waiting line names the lock file to delete:\n${b.err}`,
  );
  await sleep(300);
  assert.equal(b.out.includes('HELD'), false, 'the second run took a lane that was held');
  a.child.stdin.write('release\n');
  await Promise.all([a.exited, b.exited]);
  assert.equal(b.code, 0, b.err);
  assert.match(b.out, /HELD/);
  assert.match(b.err, /took the build lane after waiting/);
  assert.equal(fs.existsSync(lock), false);
});

test('a killed holder\'s lock is reclaimed by the next run with no manual step', { timeout: TIMEOUT_MS }, async () => {
  const lock = freshLock();
  const a = start(['hold', 'doomed'], { RF2_SHADOW_LANE_LOCK: lock });
  await eventually(() => a.out.includes('HELD'), 'the holder to take the lane');
  a.child.kill('SIGKILL');
  await a.exited;
  assert.equal(readLock(lock).pid, a.child.pid, 'the kill gave the holder no chance to release');
  const b = start(['take', 'successor'], { RF2_SHADOW_LANE_LOCK: lock });
  await b.exited;
  assert.equal(b.code, 0, b.err);
  assert.match(b.err, new RegExp(`reclaimed the build lane from dead holder ${a.child.pid} `));
  assert.doesNotMatch(b.err, /waiting for build lane/);
  assert.equal(fs.existsSync(lock), false);
  assert.deepEqual(leftovers(lock), []);
});

// A holder that dies hard frees its pids, and the OS can hand them to unrelated live
// processes. Each stand-in here began after the record named it, so it is not the holder.
test('a lock whose recorded pids now name processes that began later is reclaimed', { timeout: TIMEOUT_MS }, async () => {
  const lock = freshLock();
  const strangers = [0, 1].map(() => {
    const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' });
    live.add(child);
    return child;
  });
  const anHourAgo = new Date(Date.now() - 3600000).toISOString();
  fs.writeFileSync(lock, `${JSON.stringify({
    pid: strangers[0].pid,
    child: strangers[1].pid,
    childAt: anHourAgo,
    worktree: REPO_ROOT,
    command: 'long-dead-build',
    started: anHourAgo,
    token: '0123456789abcdef',
  })}\n`);
  const b = start(['take', 'successor'], { RF2_SHADOW_LANE_LOCK: lock });
  await eventually(() => b.code !== undefined, 'the run to reclaim a lane held by recycled pids', 20000);
  assert.equal(b.code, 0, b.err);
  assert.match(b.err, new RegExp(`reclaimed the build lane from dead holder ${strangers[0].pid} `));
  assert.ok(strangers.every((s) => isAlive(s.pid)), 'the reclaim touched the unrelated processes');
  assert.equal(fs.existsSync(lock), false);
  for (const s of strangers) s.kill('SIGKILL');
});

test('a waiter that is killed leaves the holder\'s lock as it was', { timeout: TIMEOUT_MS }, async () => {
  const lock = freshLock();
  const a = start(['hold', 'holder'], { RF2_SHADOW_LANE_LOCK: lock });
  await eventually(() => a.out.includes('HELD'), 'the holder to take the lane');
  const token = readLock(lock).token;
  const b = start(['take', 'waiter'], { RF2_SHADOW_LANE_LOCK: lock });
  await eventually(() => b.err.includes('waiting for build lane'), 'the waiting line');
  b.child.kill('SIGKILL');
  await b.exited;
  assert.equal(readLock(lock).token, token);
  a.child.stdin.write('release\n');
  await a.exited;
  assert.equal(fs.existsSync(lock), false);
  assert.deepEqual(leftovers(lock), []);
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  test(`${signal} while waiting exits without ever taking the lane`, { timeout: TIMEOUT_MS }, async () => {
    const lock = freshLock();
    const a = start(['hold', 'holder'], { RF2_SHADOW_LANE_LOCK: lock });
    await eventually(() => a.out.includes('HELD'), 'the holder to take the lane');
    const token = readLock(lock).token;
    const b = start(['signal-while-waiting', signal], { RF2_SHADOW_LANE_LOCK: lock });
    await b.exited;
    assert.equal(b.code, signal === 'SIGINT' ? 130 : 143, b.err);
    assert.doesNotMatch(b.out, /HELD-UNEXPECTEDLY/);
    assert.equal(readLock(lock).token, token, 'the waiter disturbed the holder\'s lock');
    a.child.stdin.write('release\n');
    await a.exited;
    assert.equal(fs.existsSync(lock), false);
  });

  test(`${signal} while compiling reaps the whole compiler tree and releases the lane`, { timeout: TIMEOUT_MS }, async () => {
    const lock = freshLock();
    const pidfile = path.join(lane, `pids-${signal}.json`);
    const b = start(['signal-while-running', signal, PROJECT, 'helper-exits'], {
      RF2_SHADOW_LANE_LOCK: lock,
      FAKE_MODE: 'sleep',
      FAKE_PIDFILE: pidfile,
    });
    await b.exited;
    assert.equal(b.code, signal === 'SIGINT' ? 130 : 143, b.err);
    assert.match(b.out, /RUNNING/);
    const { runner, grandchild } = JSON.parse(fs.readFileSync(pidfile, 'utf8'));
    await eventually(() => !isAlive(runner) && !isAlive(grandchild), 'the compiler tree to die');
    assert.equal(fs.existsSync(lock), false, 'the signal left the lane held');
  });

  // The tree must be dead BEFORE the launcher exits, not merely after: on Windows the
  // OS kills a node process's children when it exits, which would hide a missing reap.
  test(`${signal} with the caller's own handler: the tree is reaped while the launcher still runs`, { timeout: TIMEOUT_MS }, async () => {
    const lock = freshLock();
    const pidfile = path.join(lane, `pids-${signal}-caller.json`);
    const b = start(['signal-while-running', signal, PROJECT, 'caller-exits'], {
      RF2_SHADOW_LANE_LOCK: lock,
      FAKE_MODE: 'sleep',
      FAKE_PIDFILE: pidfile,
    });
    await b.exited;
    assert.equal(b.code, 0, 'the helper took over an exit the caller owns');
    assert.match(b.out, /TREE reaped/, `${b.out}\n${b.err}`);
    assert.match(b.out, /LANE free/, `${b.out}\n${b.err}`);
  });
}

test('a lane held by an ancestor admits its descendants instead of deadlocking', { timeout: TIMEOUT_MS }, async () => {
  const lock = freshLock();
  const a = start(['hold', 'ancestor'], { RF2_SHADOW_LANE_LOCK: lock });
  await eventually(() => a.out.includes('HELD'), 'the ancestor to take the lane');
  a.child.stdin.write('nested\n');
  await eventually(() => a.out.includes('NESTED'), 'the nested run to finish');
  const nested = a.out.split('\n').find((l) => l.startsWith('NESTED'));
  assert.match(nested, /^NESTED 0 /, nested);
  assert.doesNotMatch(nested, /waiting for build lane/);
  assert.equal(readLock(lock).pid, a.child.pid, 'the descendant released its ancestor\'s lane');
  a.child.stdin.write('release\n');
  await a.exited;
  assert.equal(fs.existsSync(lock), false);
});

test('a one-shot compile deletes its declared output first, and the verdict is the compiler\'s', { timeout: TIMEOUT_MS }, async () => {
  const out = path.join(lane, 'out', 'app.js');
  const run = async (mode, opts = {}) => {
    const lock = freshLock();
    const p = start(
      ['run', JSON.stringify(['compile', 'app']), JSON.stringify({ cwd: PROJECT, clean: [out], ...opts })],
      { RF2_SHADOW_LANE_LOCK: lock, FAKE_MODE: mode, FAKE_OUT: out },
    );
    await p.exited;
    assert.equal(fs.existsSync(lock), false, `${mode}: the lane was left held`);
    return p;
  };
  const stale = () => {
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, '// stale bundle from an earlier compile\n');
  };

  stale();
  const ok = await run('ok');
  assert.equal(ok.code, 0, ok.err);
  assert.equal(fs.readFileSync(out, 'utf8'), '// built\n');

  stale();
  const failed = await run('fail3');
  assert.equal(failed.code, 3, 'the compiler\'s own exit code passes through');
  assert.equal(fs.existsSync(out), false, 'a failed compile left the stale bundle in place');
  assert.match(failed.err, /was cleared before the attempt, not left stale/);

  stale();
  const empty = await run('no-output');
  assert.equal(empty.code, 1, 'a clean exit that leaves the declared output missing is refused');
  assert.match(empty.err, /exited 0 but .* is missing/);

  const watchClean = start(
    ['run', JSON.stringify(['watch', 'app']), JSON.stringify({ cwd: PROJECT, clean: [out] })],
    { RF2_SHADOW_LANE_LOCK: freshLock() },
  );
  await watchClean.exited;
  assert.equal(watchClean.code, 2, 'a watch has no disposable output to declare');
});

test('a runner that cannot resolve still clears the declared output, and the lane is released', { timeout: TIMEOUT_MS }, async () => {
  // An install with no shadow-cljs in it, so the runner's `.js` does not resolve.
  const bare = path.join(lane, 'bare-project');
  fs.mkdirSync(path.join(bare, 'node_modules'), { recursive: true });
  const out = path.join(lane, 'out', 'bare.js');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, '// stale bundle from an earlier compile\n');
  const lock = freshLock();
  const p = start(
    ['run', JSON.stringify(['compile', 'app']), JSON.stringify({ cwd: bare, clean: [out] })],
    { RF2_SHADOW_LANE_LOCK: lock },
  );
  await p.exited;
  assert.match(p.err, /could not resolve shadow-cljs/);
  assert.equal(p.code, 1, 'a runner that cannot resolve is a failed compile');
  assert.equal(fs.existsSync(out), false, 'the stale bundle survived a compile that never started');
  assert.equal(fs.existsSync(lock), false, 'the lane was left held');
});

test('a watch releases the lane after its first build and runs on', { timeout: TIMEOUT_MS }, async () => {
  const lock = freshLock();
  const pidfile = path.join(lane, 'pids-watch.json');
  const w = start(['watch', PROJECT], {
    RF2_SHADOW_LANE_LOCK: lock,
    FAKE_MODE: 'watch',
    FAKE_PIDFILE: pidfile,
  });
  await w.exited;
  assert.match(w.out, /Build completed/, 'the watch output passes through');
  assert.match(w.out, /LANE-FREE runner-alive=true/, `${w.out}\n${w.err}`);
  assert.match(w.err, /released the build lane, the watch runs on/);
  const { runner, grandchild } = JSON.parse(fs.readFileSync(pidfile, 'utf8'));
  await eventually(() => !isAlive(runner) && !isAlive(grandchild), 'Ctrl-C to reap the watch');
});
