/*
 * Shared port-resolution mechanism for the example/Story orchestrators.
 *
 * Two resolver policies serve the runners that host
 * `implementation/out/examples` on a free TCP port:
 *
 *   - examples-port.cjs            — the adapter-smoke orchestrator and the
 *                                    standalone-example development server.
 *   - story-feature-load-port.cjs  — the Story feature-load and play-script
 *                                    gates.
 *
 * Both want IDENTICAL contention handling — a loopback bind-and-release
 * probe, an explicit-env-var override that fails loud when busy, and a
 * forward scan to the next free port when no override is set. Only the
 * POLICY differs between them (default/preferred port, the env-var name,
 * and the exact user-facing wording). This module owns the shared
 * mechanism so each resolver file is just its distinguishing policy
 * expressed over these primitives. The probe and the port-range rule
 * themselves are the browser harness's (`isPortFree` /
 * `isValidExplicitPort`, implementation/scripts/lib/local-browser-harness.cjs),
 * so every runner judges a port the same way.
 *
 * The `actionable` flag threads through to errors so a caller's bottom
 * .catch can print `err.message` (no stack) for a port clash. The
 * examples orchestrator relies on this; the Story orchestrators leave it
 * off and print full errors.
 */

'use strict';

const {
  isPortFree,
  isValidExplicitPort,
} = require('../../implementation/scripts/lib/local-browser-harness.cjs');

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
    if (!isValidExplicitPort(n)) {
      throw portError(
        `${envVarName} must be an integer in 1..65535; got ${JSON.stringify(raw)}`,
        { actionable },
      );
    }
    return n;
  };
}

// Scan forward from `startPort` to the first free port. `opts.exhausted`
// is a `(startPort, attempts) => Error` builder for the all-busy case so
// each resolver can supply its own actionable wording.
async function findAvailablePort(startPort, opts = {}) {
  const attempts = opts.attempts || MAX_PORT_ATTEMPTS;
  for (let i = 0; i < attempts; i += 1) {
    const port = startPort + i;
    if (port > 65535) break;
    if (await isPortFree(port)) return port;
  }
  const exhausted =
    opts.exhausted ||
    ((start, n) =>
      portError(`No free port found from ${start} after ${n} attempts.`));
  throw exhausted(startPort, attempts);
}

module.exports = {
  MAX_PORT_ATTEMPTS,
  findAvailablePort,
  isPortFree,
  makeParseExplicitPort,
  portError,
};
