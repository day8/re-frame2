/*
 * Port resolver shared by the adapter-smoke orchestrator
 * (`implementation/adapters/scripts/serve-and-run-adapter-smokes.cjs`) and
 * the standalone-example development server (`serve-example.cjs`).
 *
 * Why this exists. A hard-bound port that the top-level :dev-http map in
 * implementation/shadow-cljs.edn also claims (8030, say — the
 * two_frame_isolation testbed) is taken the moment ANY `shadow-cljs watch`
 * is running. A second bind of 0.0.0.0:8030 then fails with a
 * cryptic `Error: listen EACCES 0.0.0.0:8030` on Windows (the dual-stack
 * 0.0.0.0/:: listener returns EACCES, not the clearer EADDRINUSE), which
 * gives no hint that the dev's own watch session is the cause.
 *
 * This resolver handles both halves:
 *   1. DEFAULT_PORT is 8050. It sits in the examples-orchestrator's OWNED
 *      range (805x), clear of every port the top-level :dev-http set
 *      claims. See the OWNED-RANGE PORT MAP in
 *      implementation/scripts/dev-testbed.cjs (the single source of truth
 *      for who-owns-what) for the convention, which keeps the bands
 *      non-overlapping. The pre-flight
 *      + forward scan (step 2) still apply, so even an unexpected clash on
 *      805x lands on the next free port instead of hard-failing.
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
 * Mechanism (bind probe / forward scan / actionable errors) is shared
 * with the Story feature-load resolver via port-resolver.cjs; this file
 * supplies only the examples-specific POLICY (default port + wording),
 * so the two sibling orchestrators handle port contention identically.
 */

'use strict';

const {
  MAX_PORT_ATTEMPTS,
  canListen,
  findAvailablePort: findAvailablePortShared,
  makeParseExplicitPort,
  portError,
} = require('./port-resolver.cjs');

// Default port. The examples orchestrator OWNS the 805x band — clear of
// every port the top-level :dev-http set claims. See the OWNED-RANGE PORT
// MAP in implementation/scripts/dev-testbed.cjs for the convention.
// The pre-flight + forward scan (below) still cover an unexpected clash by
// landing on the next free port; with the bands non-overlapping a
// running `shadow-cljs watch` does not pre-claim this default.
const DEFAULT_PORT = 8050;

const parseExplicitPort = makeParseExplicitPort('EXAMPLES_PORT', { actionable: true });

// Wrap the shared scanner with the examples-specific exhausted-port
// wording (actionable, like every error this module raises) so the
// public signature is unchanged.
function findAvailablePort(startPort, opts = {}) {
  return findAvailablePortShared(startPort, {
    ...opts,
    exhausted: (start, attempts) =>
      portError(
        `No free examples port found from ${start} after ${attempts} attempts. ` +
          `Set EXAMPLES_PORT to an unused port.`,
        { actionable: true },
      ),
  });
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
      // Deliberately NO port list here. A transcribed :dev-http
      // enumeration goes stale in silence as the map grows, and a test
      // pinning it would certify the drift. Point at the two authorities
      // instead; they are the ones that move. Same call, and for the same
      // reason, as the OWNED-RANGE PORT MAP's own refusal to re-list DEV_HTTP.
      throw portError(
        `EXAMPLES_PORT=${explicit} is already in use. Is a 'shadow-cljs watch' ` +
          `running? A watch claims every port in the top-level :dev-http map ` +
          `(implementation/shadow-cljs.edn) — read that map for the live list, ` +
          `and the OWNED-RANGE PORT MAP in implementation/scripts/dev-testbed.cjs ` +
          `for which band belongs to whom (the examples orchestrator owns 805x). ` +
          `Stop the watch, or set EXAMPLES_PORT to a free port.`,
        { actionable: true },
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
