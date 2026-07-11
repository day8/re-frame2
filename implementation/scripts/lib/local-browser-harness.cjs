'use strict';

const { spawn, spawnSync } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const http = require('http');
const https = require('https');
const net = require('net');
const path = require('path');

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// A usable explicit port is an integer in 1..65535: a port a caller
// can pin, advertise in a BASE_URL, and probe. Port 0 is excluded: it
// binds (the OS assigns an ephemeral port) but the assigned port is only
// knowable by reading it back off the bound socket, so advertising :0 is
// never what a caller wants. Out-of-range (<1, >65535) and non-integers
// are excluded because net.listen throws ERR_SOCKET_BAD_PORT on them.
function isValidExplicitPort(port) {
  return Number.isInteger(port) && port >= 1 && port <= 65535;
}

// True if the given TCP port is currently free on 127.0.0.1. We bind a
// temporary listener and immediately release it; EADDRINUSE (or any
// other bind error) is treated as "not free" so callers fall back to an
// OS-chosen port. A port outside 1..65535 (including 0) is reported "not
// free" WITHOUT touching net.listen — port 0 would otherwise bind an
// ephemeral port (a false "free" for an unusable advertised port), and
// negative / overflow values would throw ERR_SOCKET_BAD_PORT instead of
// the controlled fall-through.
function isPortFree(port) {
  if (!isValidExplicitPort(port)) return Promise.resolve(false);
  return new Promise((resolve) => {
    const srv = net.createServer();
    srv.once('error', () => resolve(false));
    srv.once('listening', () => {
      srv.close(() => resolve(true));
    });
    srv.listen(port, '127.0.0.1');
  });
}

// Ask the OS for a free port (listen on 0, capture, release).
function findFreePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.once('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address();
      srv.close(() => resolve(port));
    });
  });
}

// Resolve a port to serve on: prefer `preferredPort` when it is a usable
// explicit port (integer 1..65535) AND currently free, otherwise fall
// back to an OS-chosen free port. `onFallback(preferred, fallback)` is
// invoked (if provided) on EITHER fallback trigger — a busy preferred
// port OR an invalid one (0, negative, overflow, NaN, non-integer) — so
// callers log the substitution rather than advertising an unusable :0 or
// crashing net.listen with ERR_SOCKET_BAD_PORT.
async function resolveServePort(preferredPort, opts = {}) {
  if (isValidExplicitPort(preferredPort) && (await isPortFree(preferredPort))) {
    return preferredPort;
  }
  const fallback = await findFreePort();
  if (typeof opts.onFallback === 'function') {
    opts.onFallback(preferredPort, fallback);
  }
  return fallback;
}

// Fetch the body of a path (default `/.rf-harness-token`) from a local
// server, trimmed. Resolves null on non-200, error, or timeout — the
// caller treats "no token yet" as "keep polling" and a present-but-wrong
// token as a foreign server.
const TOKEN_FILE_BASENAME = '.rf-harness-token';

// Publish a per-run server-ownership token under `root`. Writes a fresh
// 16-byte hex nonce to
// `${root}/.rf-harness-token` so http-server publishes it the moment it
// starts serving `root`, and the readiness handshake (waitForOwnedHttpReady
// + fetchToken) can positively distinguish THIS run's server from a
// port-squatter or a stale server from an aborted run.
//
// This owns the write side of the handshake; fetchToken and
// waitForOwnedHttpReady own the read side.
//
// Returns `null` when `root` does not exist yet — the caller decides how to
// report the missing asset root (each launcher prints its own diagnostic and
// exits). Otherwise returns `{ token, remove }`:
//   - `token`  — the nonce to pass as waitForOwnedHttpReady's expectedToken.
//   - `remove` — idempotent, best-effort teardown. It unlinks the sentinel
//                ONLY when the file still holds THIS run's token. An
//                overlapping run against the SAME root may have re-published
//                its own token over ours between our write and our teardown;
//                unlinking unconditionally there would destroy the NEWER
//                run's sentinel (a cross-run teardown race that would let the
//                newer run's owned-readiness handshake spuriously fail).
//                Reading the file and matching our nonce first makes
//                concurrent teardown safe. It never throws — teardown
//                bookkeeping must not fail the run — and no-ops on a second
//                call.
function publishOwnershipToken(root) {
  if (!fs.existsSync(root)) return null;
  const tokenPath = path.join(root, TOKEN_FILE_BASENAME);
  const token = crypto.randomBytes(16).toString('hex');
  fs.writeFileSync(tokenPath, token, 'utf8');

  let removed = false;
  function remove() {
    if (removed) return;
    removed = true;
    try {
      // Only OUR sentinel is ours to unlink. If a newer overlapping run has
      // already replaced the content with its own nonce, leave it be.
      if (fs.readFileSync(tokenPath, 'utf8').trim() === token) {
        fs.unlinkSync(tokenPath);
      }
    } catch (_) {
      // best-effort: the file is already gone, unreadable, or was
      // concurrently replaced — nothing of ours to clean up.
    }
  }

  return { token, remove };
}

function fetchToken(port, opts = {}) {
  const host = opts.host || '127.0.0.1';
  const tokenPath = opts.path || `/${TOKEN_FILE_BASENAME}`;
  const timeout = opts.timeout || 1000;
  return new Promise((resolve) => {
    const req = http.get({ host, port, path: tokenPath, timeout }, (res) => {
      if (res.statusCode !== 200) {
        res.resume();
        resolve(null);
        return;
      }
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => { body += chunk; });
      res.on('end', () => resolve(body.trim()));
      res.on('error', () => resolve(null));
    });
    req.on('error', () => resolve(null));
    req.on('timeout', () => { req.destroy(); resolve(null); });
  });
}

// Readiness wait with ownership-token verification. Resolves
// `{ ok: true }` only once the server on `port` answers AND serves a
// `/.rf-harness-token` body equal to `expectedToken`. Otherwise resolves
// `{ ok: false, reason }`:
//   - 'child-exited'      — opts.isAborted() went true (server died)
//   - 'token-mismatch'    — a foreign server is bound to this port (its
//                           token is present but != ours); refuse to run
//   - 'token-never-served'— reachable but never published our token
//   - 'timeout'           — never became reachable
// This positively distinguishes "our server" from a port-squatter / a
// stale http-server from an aborted run, defeating false-RED and (worse)
// false-GREEN runs against the wrong asset tree.
async function waitForOwnedHttpReady(port, expectedToken, deadline, opts = {}) {
  const pollMs = opts.pollMs || 200;
  const isAborted = opts.isAborted || (() => false);
  let sawReachable = false;
  while (Date.now() < deadline) {
    if (isAborted()) return { ok: false, reason: 'child-exited' };
    if (await probeHttp(port, opts)) {
      sawReachable = true;
      if (isAborted()) return { ok: false, reason: 'child-exited' };
      const got = await fetchToken(port, opts);
      if (got && got === expectedToken) return { ok: true };
      if (got && got !== expectedToken) {
        return { ok: false, reason: 'token-mismatch', got };
      }
      // Token absent yet — http-server may be up but not yet serving the
      // sentinel file. Keep polling.
    }
    if (isAborted()) return { ok: false, reason: 'child-exited' };
    await sleep(pollMs);
  }
  return { ok: false, reason: sawReachable ? 'token-never-served' : 'timeout' };
}

function probeHttp(port, opts = {}) {
  const host = opts.host || '127.0.0.1';
  const path = opts.path || '/';
  const timeout = opts.timeout || 1000;
  // Honour the caller's scheme. The default loopback
  // readiness probes (and the ownership-token handshake) only ever speak
  // http, so http stays the default; the XRAY_FEATURE_GATE_BASE_URL
  // escape hatch can point at an https external server, in which case the
  // probe must use TLS or it would report a valid server unreachable.
  // `rejectUnauthorized: false` keeps a self-signed external dev server
  // probeable — this is a liveness probe, not a trust boundary (the real
  // navigation is Playwright's, which enforces its own TLS policy).
  const secure = opts.protocol === 'https:' || opts.secure === true;
  const agent = secure ? https : http;
  const requestOpts = { host, port, path, timeout };
  if (secure) requestOpts.rejectUnauthorized = false;

  return new Promise((resolve) => {
    const req = agent.get(requestOpts, (res) => {
      res.resume();
      resolve(res.statusCode != null);
    });
    req.on('error', () => resolve(false));
    req.on('timeout', () => {
      req.destroy();
      resolve(false);
    });
  });
}

// Derive the complete readiness target from a base URL. Host, scheme, port,
// and base path must all match the URL the browser will open; probing only a
// coincidentally equal local port could otherwise produce a false green. Invalid
// and non-HTTP(S) URLs fail instead of falling back to loopback. The result can
// be spread directly into probeHttp/waitForHttpReady options.
function probeTargetFromBaseUrl(baseUrl, opts = {}) {
  const label = opts.envName || 'base URL';
  let parsed;
  try {
    parsed = new URL(baseUrl);
  } catch (_) {
    throw new Error(
      `${label} is not a valid URL: ${JSON.stringify(baseUrl)}. ` +
        'Expected something like "http://host:port/optional/base/path".',
    );
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error(
      `${label} must use http: or https: (got "${parsed.protocol}" ` +
        `in ${JSON.stringify(baseUrl)}).`,
    );
  }
  const port = parsed.port
    ? Number(parsed.port)
    : parsed.protocol === 'https:' ? 443 : 80;
  // Probe the URL's base path (e.g. "/app/") so a meaningful base path is
  // honoured; URL normalises a hostname-only URL's pathname to "/".
  const path = parsed.pathname || '/';
  return { host: parsed.hostname, port, path, protocol: parsed.protocol };
}

// The address a server BINDS to is not always an address a client can
// CONNECT to, so a readiness prove must derive its probe host from the bind
// host rather than defaulting to loopback. A readiness check MUST address the
// same interface the server actually listens on: an IPv6-loopback-only bind
// (`::1`) is unreachable via IPv4 `127.0.0.1` (and vice versa), so probing the
// wrong loopback times out against a perfectly healthy server. Every CONCRETE
// bind host — a loopback literal, `localhost`, or a hostname — is therefore
// threaded through to the probe unchanged.
//
// The exception is a WILDCARD bind. `0.0.0.0` / `::` mean "listen on every
// interface"; they are bind targets, never portable client destinations, and
// connecting to an unspecified address is not reliable. Each maps to its
// matching loopback — a concrete, reachable address a wildcard-bound server
// necessarily answers on: `0.0.0.0` -> `127.0.0.1`, `::` -> `::1`. This never
// broadens a server's bind surface; it only picks a client-usable address for
// the liveness/ownership handshake.
function deriveProbeHost(bindHost) {
  switch (bindHost) {
    case '0.0.0.0':
      return '127.0.0.1';
    case '::':
    case '[::]':
      return '::1';
    default:
      return bindHost;
  }
}

async function waitForHttpReady(port, deadline, opts = {}) {
  const pollMs = opts.pollMs || 200;
  const isAborted = opts.isAborted || (() => false);
  while (Date.now() < deadline) {
    if (isAborted()) return false;
    if (await probeHttp(port, opts)) return true;
    if (isAborted()) return false;
    await sleep(pollMs);
  }
  return false;
}

function spawnHarnessProcess(command, args, opts = {}) {
  const spawnOpts = { ...opts };
  if (spawnOpts.detached == null && process.platform !== 'win32') {
    // Make the child a process-group leader so teardown can kill the full
    // server/browser subtree instead of only the direct Node child.
    spawnOpts.detached = true;
  }
  return spawn(command, args, spawnOpts);
}

function hasExited(child) {
  return !child ||
    !child.pid ||
    child.exitCode != null ||
    child.signalCode != null;
}

function waitForExit(child, timeoutMs) {
  if (hasExited(child)) return Promise.resolve(true);
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      child.off('exit', onExit);
      resolve(false);
    }, timeoutMs);
    const onExit = () => {
      clearTimeout(timer);
      resolve(true);
    };
    child.once('exit', onExit);
  });
}

function terminateProcessTreeSync(child, opts = {}) {
  if (hasExited(child)) return;
  const signal = opts.signal || 'SIGTERM';
  if (process.platform === 'win32') {
    spawnSync('taskkill.exe', ['/pid', String(child.pid), '/T', '/F'], {
      stdio: 'ignore',
      windowsHide: true,
    });
    return;
  }

  try {
    process.kill(-child.pid, signal);
  } catch (_) {
    try {
      child.kill(signal);
    } catch (__) {}
  }
}

async function terminateProcessTree(child, opts = {}) {
  if (hasExited(child)) return;

  terminateProcessTreeSync(child, { signal: opts.signal || 'SIGTERM' });
  if (await waitForExit(child, opts.timeoutMs || 2000)) return;

  if (process.platform !== 'win32') {
    terminateProcessTreeSync(child, { signal: 'SIGKILL' });
  } else {
    try {
      child.kill('SIGKILL');
    } catch (_) {}
  }
  await waitForExit(child, 500);
}

function createHarnessCleanup(opts = {}) {
  const processes = [];
  const cleanupFns = [];
  const onError = opts.onError || ((err) => {
    if (err) console.error(err && err.stack ? err.stack : err);
  });
  const exit = opts.exit || ((code) => process.exit(code));
  // Injectable terminators make cleanup races testable without real taskkill
  // latency.
  const terminateSync = opts.terminateSync || terminateProcessTreeSync;
  const terminateAsync = opts.terminateAsync || terminateProcessTree;
  // Child termination and cleanup callbacks need independent idempotence
  // guards. A process exit racing async cleanup must still synchronously sweep
  // children that the async path has not reached, without running callbacks
  // twice.
  //
  //   - `killed` guards the child-process kill-sweep. cleanupSync() will
  //     ALWAYS run the synchronous sweep unless a previous sweep already
  //     ran (the sweep is itself idempotent — terminateProcessTreeSync
  //     no-ops on already-dead children via hasExited()). This is what
  //     stops orphaned children when process.exit() fires the 'exit'
  //     handler while an async cleanup() is mid-flight (awaiting
  //     terminateProcessTree on child N of M).
  //   - `cleanupFnsRan` guards the addCleanup callbacks so they run at
  //     most once across both paths.
  let killed = false;
  let cleanupFnsRan = false;
  let cleaning = null;

  function trackProcess(child) {
    if (child) processes.push(child);
    return child;
  }

  function addCleanup(fn) {
    cleanupFns.push(fn);
    return fn;
  }

  // Synchronously terminate every tracked child subtree. Idempotent:
  // the per-child terminateProcessTreeSync no-ops on already-exited
  // children, and the `killed` flag short-circuits a redundant sweep.
  function killChildrenSync() {
    if (killed) return;
    killed = true;
    for (const child of [...processes].reverse()) {
      try {
        terminateSync(child);
      } catch (err) {
        onError(err);
      }
    }
  }

  function runCleanupFnsSync() {
    if (cleanupFnsRan) return;
    cleanupFnsRan = true;
    for (const fn of [...cleanupFns].reverse()) {
      try {
        fn();
      } catch (err) {
        onError(err);
      }
    }
  }

  // The process 'exit' handler. Node cannot await here, so a synchronous exit
  // racing an in-flight async cleanup() MUST still synchronously kill
  // any children that cleanup() has not yet reached — otherwise they are
  // orphaned. killChildrenSync gates on its own `killed`
  // flag, never on the async path's state, so this always sweeps unless
  // a sync sweep already completed.
  function cleanupSync() {
    killChildrenSync();
    runCleanupFnsSync();
  }

  async function cleanup() {
    if (cleaning) return cleaning;
    cleaning = (async () => {
      for (const child of [...processes].reverse()) {
        // hasExited() makes this a no-op if cleanupSync() already swept
        // this child (or it exited on its own).
        try {
          await terminateAsync(child);
        } catch (err) {
          onError(err);
        }
      }
      // Mark the synchronous kill-sweep satisfied so a subsequent
      // 'exit' -> cleanupSync() doesn't redundantly re-sweep children
      // this async path already awaited to completion.
      killed = true;
      if (cleanupFnsRan) return;
      cleanupFnsRan = true;
      for (const fn of [...cleanupFns].reverse()) {
        try {
          await fn();
        } catch (err) {
          onError(err);
        }
      }
    })();
    return cleaning;
  }

  function installSignalHandlers() {
    process.once('SIGINT', () => {
      cleanup().then(() => exit(130), (err) => {
        onError(err);
        exit(1);
      });
    });
    process.once('SIGTERM', () => {
      cleanup().then(() => exit(143), (err) => {
        onError(err);
        exit(1);
      });
    });
    process.once('exit', cleanupSync);
  }

  return {
    addCleanup,
    cleanup,
    cleanupSync,
    installSignalHandlers,
    trackProcess,
  };
}

// fs.statSync that swallows every error (missing path, ENOTDIR, a non-string
// argument) into `null`, so a caller can branch on existence/type without a
// try/catch at the call site.
function statOrNull(target) {
  try {
    return fs.statSync(target);
  } catch (_) {
    return null;
  }
}

// Human-readable diagnostic for each structured owned-readiness failure, so a
// caller's log surfaces WHY the gate refused (a foreign tree, a tokenless
// responder, an early child exit, or a plain timeout), not merely that it did.
// The 'timeout' wording is preserved verbatim — it is the only outcome the
// unowned path could ever produce, and callers/tests key on it.
function ownedReadinessFailureDiagnostic(result, port, timeoutMs) {
  switch (result.reason) {
    case 'token-mismatch':
      return (
        `Refusing to run on :${port}: a foreign server answered but its ` +
        `/${TOKEN_FILE_BASENAME} does not match this run's ownership token ` +
        `(got ${JSON.stringify(result.got)}). The assets being served are NOT ` +
        `the tree this run staged — a stale/sibling listener won the port.`
      );
    case 'token-never-served':
      return (
        `http-server became reachable on :${port} but never served this run's ` +
        `/${TOKEN_FILE_BASENAME} within ${timeoutMs}ms; the served asset tree ` +
        `may be inconsistent.`
      );
    case 'child-exited':
      return (
        `http-server exited before it became ready on :${port} ` +
        `(likely a lost port bind or a failed start).`
      );
    case 'timeout':
    default:
      return `http-server did not become reachable on :${port} within ${timeoutMs}ms.`;
  }
}

// Compose the canonical local-http-server lifecycle: shell-free spawn, tracked
// teardown, early-exit detection, optional bounded output capture, OWNED
// readiness (ownership-token verification — reachability alone is never proof),
// and failure diagnostics. Callers retain only command-specific work.
//
// The argv is fixed policy: `-a <host>` (loopback by default, NOT
// http-server's 0.0.0.0), `-p <port>`, `-s` (silent), `-c-1` (no cache) —
// so a recompiled bundle is never served stale and the run's assets aren't
// exposed on non-loopback interfaces. The host stays a parameter only so a
// caller could serve elsewhere; every current caller uses the 127.0.0.1
// default.
//
// Options:
//   - cleanup    (required) — a createHarnessCleanup() handle; the spawned
//                 server is registered via cleanup.trackProcess for
//                 process-tree teardown.
//   - httpServerBin (required) — the resolved http-server bin path. Callers
//                 resolve it themselves (usually lazily) so this module never
//                 hard-depends on the http-server package being installed.
//   - root       (required) — the directory to serve. MUST exist and be a
//                 directory: a per-run ownership token is published under it
//                 before spawn, and a missing/non-directory root fails loudly
//                 (throws) before any browser work — a gate against a missing
//                 asset tree is never valid.
//   - port       (required) — the resolved, already-free port to bind.
//   - host       — bind address (default '127.0.0.1').
//   - probeHost  — the address the owned-readiness handshake CONNECTS to.
//                 Defaults to a client-usable derivation of `host`
//                 (deriveProbeHost): a concrete bind (`::1`, `localhost`, a
//                 hostname) probes itself; a wildcard bind (`0.0.0.0`/`::`)
//                 probes the matching loopback. Both the liveness probe and
//                 the ownership-token fetch use this SAME host, so they can
//                 never diverge onto different interfaces (a server bound to
//                 `::1` must be proven ready over `::1`, not IPv4 loopback).
//   - cwd        — spawn cwd (where node_modules / http-server resolves).
//   - execPath   — node binary to run http-server under (default
//                 process.execPath, shell-free).
//   - readyTimeoutMs — readiness budget (default 30000).
//   - captureOutput — when true, pipe stdout/stderr and retain a bounded
//                 line buffer surfaced as the failure tail (the Story
//                 posture). When false (default), inherit the parent stdio
//                 (the adapter-smoke / serve-example posture); `output` stays
//                 empty and no tail prints.
//   - verbose    — with captureOutput, also stream the server's output live.
//   - isAborted  — an EXTRA abort predicate, OR'd with the internal
//                 server-exited flag, so a caller whose OTHER child (e.g.
//                 serve-example's shadow-cljs watch) can invalidate the run
//                 aborts the readiness wait too.
//   - onExit     — (code, signal) callback invoked on the server's exit, for
//                 a caller that records the outcome for its own verdict (e.g.
//                 serve-example's decideRunnerExit).
//   - suppressExitDiagnostic — predicate; when it returns true the
//                 "exited unexpectedly" line is suppressed (e.g. once the
//                 caller is already tearing down / was interrupted, so the
//                 forced-shutdown exit isn't reported as a crash).
//   - log        — sink for the readiness/exit diagnostics (default
//                 console.error, matching every caller's current output).
//   - failureTailLines — how many trailing captured lines to print on a
//                 readiness failure (default 40).
//
// Returns { server, ready, output, isDown }:
//   - server  — the tracked ChildProcess (for the caller's own waits).
//   - ready   — true ONLY once the responder on `port` served this run's
//               ownership token; false for EVERY non-owned outcome — a foreign
//               responder (token-mismatch), a tokenless one (token-never-served),
//               an early child exit (child-exited), or a timeout. The caller
//               returns its non-zero verdict; the canonical reason-specific
//               diagnostics have already been printed.
//   - output  — the captured line buffer (empty unless captureOutput).
//   - isDown  — () => boolean; the live server-exited flag.
async function startLocalHttpServer(opts = {}) {
  const {
    cleanup,
    httpServerBin,
    root,
    port,
    host = '127.0.0.1',
    probeHost = deriveProbeHost(host),
    cwd,
    execPath = process.execPath,
    readyTimeoutMs = 30000,
    captureOutput = false,
    verbose = false,
    isAborted,
    onExit,
    suppressExitDiagnostic = () => false,
    log = (msg) => console.error(msg),
    failureTailLines = 40,
  } = opts;

  // Owned readiness is the DEFAULT lifecycle (rf2-3fc89f.14): a local browser
  // gate must proceed only after proving the responder on `port` serves THIS
  // run's staged root — never a foreign/stale asset tree that squatted the port
  // during the non-atomic resolveServePort()->spawn handoff. Fail loudly BEFORE
  // any spawn if the staging root is missing or not a directory; a gate against
  // a missing asset tree is never valid, and silently proceeding is how a stale
  // build passes a browser check.
  const rootStat = statOrNull(root);
  if (!rootStat || !rootStat.isDirectory()) {
    throw new Error(
      `startLocalHttpServer: staging root does not exist or is not a ` +
        `directory: ${JSON.stringify(root)}. Refusing to start a browser gate ` +
        `against a missing asset tree.`,
    );
  }

  // Publish the per-run ownership token under `root` BEFORE spawn, so
  // http-server serves it the moment it starts serving the directory. Register
  // its concurrency-safe `remove` for teardown — it only unlinks OUR token, so
  // an overlapping run against the same root is never clobbered.
  const published = publishOwnershipToken(root);
  if (!published) {
    // root passed the stat above but vanished before the token write (a
    // teardown race). Still refuse loudly rather than fall back to unowned
    // readiness.
    throw new Error(
      `startLocalHttpServer: staging root vanished before the ownership token ` +
        `could be published: ${JSON.stringify(root)}.`,
    );
  }
  const { token, remove } = published;
  cleanup.addCleanup(remove);

  const args = [
    httpServerBin,
    root,
    '-a',
    host,
    '-p',
    String(port),
    '-s',
    '-c-1',
  ];
  const stdio = captureOutput
    ? ['ignore', 'pipe', 'pipe']
    : ['ignore', 'inherit', 'inherit'];
  const server = cleanup.trackProcess(
    spawnHarnessProcess(execPath, args, { cwd, stdio }),
  );

  const output = [];
  if (captureOutput) {
    const capture = (chunk, stream) => {
      const text = chunk.toString();
      for (const line of text.split(/\r?\n/)) {
        if (line) output.push(`[http-server:${stream}] ${line}`);
      }
      if (verbose) process[stream].write(text);
    };
    server.stdout.on('data', (chunk) => capture(chunk, 'stdout'));
    server.stderr.on('data', (chunk) => capture(chunk, 'stderr'));
  }

  let down = false;
  server.on('exit', (code, signal) => {
    down = true;
    if (typeof onExit === 'function') onExit(code, signal);
    // The server is force-terminated during normal teardown, which fires
    // this exit with a non-zero/null code AFTER the run has finished. A
    // caller that is already exiting suppresses the noise via
    // suppressExitDiagnostic; a genuine mid-run crash is surfaced loudly.
    if (code !== 0 && code !== null && !suppressExitDiagnostic()) {
      log(`http-server exited unexpectedly (code=${code}, signal=${signal}).`);
    }
  });

  const isDown = () => down;
  const aborted = () =>
    down || (typeof isAborted === 'function' && isAborted());

  // Readiness WITH ownership-token verification (rf2-84gzw / rf2-gkf9 shared
  // primitive): reachability alone is NOT proof — a foreign responder that won
  // the port race would answer the liveness probe and yield a false green.
  // Accept ready:true ONLY when the responder serves this run's token; return
  // ready:false for every other structured outcome, with a reason-specific
  // diagnostic.
  // Prove owned readiness against the SAME interface the server bound to.
  // `probeHost` is threaded through the single options object both probeHttp
  // and fetchToken read, so the liveness probe and the ownership-token fetch
  // address one host — never loopback-by-default while the server listens on
  // a non-default bind (e.g. `::1`), which would time out a healthy server.
  const result = await waitForOwnedHttpReady(
    port,
    token,
    Date.now() + readyTimeoutMs,
    { isAborted: aborted, host: probeHost },
  );
  if (!result.ok) {
    log(ownedReadinessFailureDiagnostic(result, port, readyTimeoutMs));
    for (const line of output.slice(-failureTailLines)) log(line);
    return { server, ready: false, output, isDown };
  }
  return { server, ready: true, output, isDown };
}

module.exports = {
  TOKEN_FILE_BASENAME,
  createHarnessCleanup,
  deriveProbeHost,
  fetchToken,
  findFreePort,
  isPortFree,
  isValidExplicitPort,
  probeHttp,
  probeTargetFromBaseUrl,
  publishOwnershipToken,
  resolveServePort,
  sleep,
  spawnHarnessProcess,
  startLocalHttpServer,
  terminateProcessTree,
  terminateProcessTreeSync,
  waitForHttpReady,
  waitForOwnedHttpReady,
};
