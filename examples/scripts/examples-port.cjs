/*
 * Port resolver for the adapter-smoke orchestrator
 * (serve-and-run-examples-tests.cjs).
 *
 * Why this exists (rf2-0u6ce). The orchestrator used to hard-bind
 * 0.0.0.0:8030. But 8030 is ALSO claimed by the top-level :dev-http map
 * in implementation/shadow-cljs.edn (8030 = the two_frame_isolation
 * testbed) — so the moment ANY `shadow-cljs watch` is running, 8030 is
 * already taken. The orchestrator's second bind then failed with a
 * cryptic `Error: listen EACCES 0.0.0.0:8030` on Windows (the dual-stack
 * 0.0.0.0/:: listener returns EACCES, not the clearer EADDRINUSE), which
 * gave no hint that the dev's own watch session was the cause.
 *
 * This resolver fixes both halves:
 *   1. DEFAULT_PORT is 8040 — outside the top-level :dev-http set
 *      (8765 / 8031 / 8030), so the common "watch + test:examples"
 *      dev state no longer collides at all.
 *   2. It PRE-FLIGHTS the port (binds-and-releases on 127.0.0.1). If the
 *      preferred port is busy and no explicit EXAMPLES_PORT was set, it
 *      scans forward to the next free port. If an explicit EXAMPLES_PORT
 *      IS busy, it throws an actionable message rather than letting the
 *      raw EACCES stack escape.
 *
 * The orchestrator binds http-server on 127.0.0.1 (not 0.0.0.0) — the
 * Playwright specs only ever hit localhost, and the loopback-only bind
 * also sidesteps the Windows dual-stack EACCES surprise.
 *
 * Shape mirrors examples/scripts/story-feature-load-port.cjs (the Story
 * feature-load gate's resolver) deliberately, so the two sibling
 * orchestrators handle port contention identically.
 */

'use strict';

const net = require('net');

// Default outside the top-level :dev-http set (8765 / 8031 / 8030) so a
// running `shadow-cljs watch` never pre-claims the orchestrator's port.
const DEFAULT_PORT = 8040;
const MAX_PORT_ATTEMPTS = 200;

// Build an Error tagged `.actionable = true`. The orchestrator's bottom
// .catch prints just `err.message` (no stack) for tagged errors, so a
// port clash surfaces as a clean one-liner instead of a raw EACCES dump.
function actionableError(message) {
  const err = new Error(message);
  err.actionable = true;
  return err;
}

function parseExplicitPort(raw) {
  if (raw == null || String(raw).trim() === '') return null;
  const n = Number(String(raw).trim());
  if (!Number.isInteger(n) || n < 1 || n > 65535) {
    throw actionableError(
      `EXAMPLES_PORT must be an integer in 1..65535; got ${JSON.stringify(raw)}`,
    );
  }
  return n;
}

// Bind-and-release probe on 127.0.0.1. Resolves true when the port is
// free, false when it is taken (any bind error — EADDRINUSE on
// Mac/Linux, EACCES for a dual-stack listener on Windows).
function canListen(port, host = '127.0.0.1') {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', () => resolve(false));
    server.listen(port, host, () => {
      server.close(() => resolve(true));
    });
  });
}

async function findAvailablePort(startPort, opts = {}) {
  const host = opts.host || '127.0.0.1';
  const attempts = opts.attempts || MAX_PORT_ATTEMPTS;
  for (let i = 0; i < attempts; i += 1) {
    const port = startPort + i;
    if (port > 65535) break;
    if (await canListen(port, host)) return port;
  }
  throw actionableError(
    `No free examples port found from ${startPort} after ${attempts} attempts. ` +
      `Set EXAMPLES_PORT to an unused port.`,
  );
}

/*
 * Resolve the port the orchestrator should serve on.
 *
 *   - EXAMPLES_PORT set + free  → that port.
 *   - EXAMPLES_PORT set + busy  → throw an actionable message (the
 *     caller prints `err.message` and exits non-zero — no raw stack).
 *   - EXAMPLES_PORT unset       → DEFAULT_PORT if free, else the next
 *     free port scanning forward.
 */
async function resolveExamplesPort({ env = process.env } = {}) {
  const explicit = parseExplicitPort(env.EXAMPLES_PORT);
  if (explicit != null) {
    if (!(await canListen(explicit))) {
      throw actionableError(
        `EXAMPLES_PORT=${explicit} is already in use. Is a 'shadow-cljs watch' ` +
          `running? The top-level :dev-http map (implementation/shadow-cljs.edn) ` +
          `claims 8765 / 8031 / 8030 whenever a watch is up. Stop the watch, or ` +
          `set EXAMPLES_PORT to a free port.`,
      );
    }
    return explicit;
  }
  return findAvailablePort(DEFAULT_PORT);
}

module.exports = {
  DEFAULT_PORT,
  MAX_PORT_ATTEMPTS,
  canListen,
  findAvailablePort,
  parseExplicitPort,
  resolveExamplesPort,
};
