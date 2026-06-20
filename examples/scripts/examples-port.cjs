/*
 * Port resolver for the adapter-smoke orchestrator
 * (serve-and-run-adapter-smokes.cjs).
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
 *   1. DEFAULT_PORT is 8050. It sits in the examples-orchestrator's OWNED
 *      range (805x), clear of the top-level :dev-http set — that map claims
 *      8765 / 8030-8034 (Xray testbeds) and 8040-8043 (the four Story
 *      showcases). See the OWNED-RANGE PORT MAP in
 *      implementation/scripts/dev-testbed.cjs (the single source of truth
 *      for who-owns-what) for the convention; rf2-ot0lv moved this default
 *      off the 804x band to keep the bands non-overlapping. The pre-flight
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
// the top-level :dev-http set (8765 / 8030-8034 = Xray testbeds; 8040-8043
// = Story showcases). See the OWNED-RANGE PORT MAP in
// implementation/scripts/dev-testbed.cjs for the convention (rf2-ot0lv).
// The pre-flight + forward scan (below) still cover an unexpected clash by
// landing on the next free port; with the bands now non-overlapping a
// running `shadow-cljs watch` no longer pre-claims this default.
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
      throw portError(
        `EXAMPLES_PORT=${explicit} is already in use. Is a 'shadow-cljs watch' ` +
          `running? The top-level :dev-http map (implementation/shadow-cljs.edn) ` +
          `claims 8765 / 8030-8034 / 8040-8043 whenever a watch is up (the ` +
          `examples orchestrator owns 805x — see the OWNED-RANGE PORT MAP in ` +
          `implementation/scripts/dev-testbed.cjs). Stop the watch, or set ` +
          `EXAMPLES_PORT to a free port.`,
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
