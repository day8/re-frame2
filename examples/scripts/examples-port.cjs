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
 *   1. DEFAULT_PORT is 8040. NOTE (rf2-xz4zn): 8040 now OVERLAPS the
 *      top-level :dev-http set — that map currently claims
 *      8765 / 8030-8033 (Xray testbeds) AND 8040-8043 (the four Story
 *      showcases, on the 804x band). So when a `shadow-cljs watch` is up,
 *      8040 may already be taken. This is harmless here: the resolver
 *      PRE-FLIGHTS (step 2) and scans forward, so test:examples just lands
 *      on the next free port (8044+) instead of hard-failing. The 8040
 *      default predates the 804x Story band; whether to bump it off the
 *      band is a deliberate config call (see rf2-uuxjd), kept out of this
 *      comment-only reconcile.
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

// Default port. NOTE (rf2-xz4zn): 8040 now sits INSIDE the top-level
// :dev-http band (8040-8043 are the Story showcases; 8765 / 8030-8033 are
// the Xray testbeds), so a running `shadow-cljs watch` CAN pre-claim it.
// The pre-flight + forward scan (below) keep this harmless — we just land
// on 8044+. A bump off the 804x band is a deliberate config call (rf2-uuxjd).
const DEFAULT_PORT = 8040;

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
          `claims 8765 / 8030-8033 / 8040-8043 whenever a watch is up. Stop the ` +
          `watch, or set EXAMPLES_PORT to a free port.`,
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
