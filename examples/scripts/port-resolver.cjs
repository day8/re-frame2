/*
 * Shared port-resolution mechanism for the example/Story orchestrators.
 *
 * Two sibling orchestrators each need to pick a free TCP port and serve
 * `implementation/out/examples` on it:
 *
 *   - examples-port.cjs            — the adapter-smoke orchestrator.
 *   - story-feature-load-port.cjs  — the Story feature-load + play-script
 *                                    gates.
 *
 * Both want IDENTICAL contention handling — a loopback bind-and-release
 * probe, an explicit-env-var override that fails loud when busy, and a
 * forward scan to the next free port when no override is set. Only the
 * POLICY differs between them (default/preferred port, the env-var name,
 * and the exact user-facing wording). This module owns the shared
 * mechanism so each resolver file is just its distinguishing policy
 * expressed over these primitives, rather than a second copy of the
 * bind-probe loop.
 *
 * The `actionable` flag threads through to errors so a caller's bottom
 * .catch can print `err.message` (no stack) for a port clash. The
 * examples orchestrator relies on this; the Story orchestrators leave it
 * off and print full errors.
 */

'use strict';

const net = require('net');

const MAX_PORT_ATTEMPTS = 200;

// Build an Error optionally tagged `.actionable = true`. A caller whose
// bottom .catch special-cases tagged errors prints just `err.message`
// (no stack), so a port clash surfaces as a clean one-liner.
function portError(message, { actionable = false } = {}) {
  const err = new Error(message);
  if (actionable) err.actionable = true;
  return err;
}

// Make a strict env-var parser bound to `envVarName` (used only in the
// "out of range" message). Returns null for unset/blank, throws an
// (optionally actionable) error for a non-integer or out-of-range value.
function makeParseExplicitPort(envVarName, { actionable = false } = {}) {
  return function parseExplicitPort(raw) {
    if (raw == null || String(raw).trim() === '') return null;
    const n = Number(String(raw).trim());
    if (!Number.isInteger(n) || n < 1 || n > 65535) {
      throw portError(
        `${envVarName} must be an integer in 1..65535; got ${JSON.stringify(raw)}`,
        { actionable },
      );
    }
    return n;
  };
}

// Bind-and-release probe on 127.0.0.1 (by default). Resolves true when
// the port is free, false when it is taken (any bind error —
// EADDRINUSE on Mac/Linux, EACCES for a dual-stack listener on Windows).
function canListen(port, host = '127.0.0.1') {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', () => resolve(false));
    server.listen(port, host, () => {
      server.close(() => resolve(true));
    });
  });
}

// Scan forward from `startPort` to the first free port. `opts.exhausted`
// is a `(startPort, attempts) => Error` builder for the all-busy case so
// each resolver can supply its own actionable wording.
async function findAvailablePort(startPort, opts = {}) {
  const host = opts.host || '127.0.0.1';
  const attempts = opts.attempts || MAX_PORT_ATTEMPTS;
  for (let i = 0; i < attempts; i += 1) {
    const port = startPort + i;
    if (port > 65535) break;
    if (await canListen(port, host)) return port;
  }
  const exhausted =
    opts.exhausted ||
    ((start, n) =>
      portError(`No free port found from ${start} after ${n} attempts.`));
  throw exhausted(startPort, attempts);
}

module.exports = {
  MAX_PORT_ATTEMPTS,
  canListen,
  findAvailablePort,
  makeParseExplicitPort,
  portError,
};
