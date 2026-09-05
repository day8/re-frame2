'use strict';
// A MODULE THAT BOOTS, AND THEN STOPS BOOTING — the fixture the REPLACEMENT
// path needs (rf2-2hmg).
//
// Every other boot-failure fixture here fails on the FIRST boot, which is
// `Pool.start()`: the service never comes up, and the operator reading the
// refusal is standing at the process they just started. The path this
// fixture exists for is the other one. A replacement isolate boots while
// the service is LIVE and serving, so its boot failure is delivered to
// whoever is waiting for capacity — a caller across a wire — and no
// fixture that fails on its first boot can ever reach that receiver.
//
// THE SWITCH IS AN ENV FLAG BECAUSE ISOLATES SHARE NO STATE. Each isolate
// is a separate V8 isolate with its own module registry, so a module-level
// "have I booted before" counter is per-thread and always reads zero. What
// a worker thread does inherit is a COPY of `process.env`, taken when the
// thread is constructed — so a flag the test sets after the pool is up is
// invisible to the isolates already running and visible to every
// replacement spawned afterwards, which is exactly the discrimination the
// witness needs.
//
// The thrown Error is built to carry all three things the refusal must not
// pass on: a message the application authored, a `code` outside the closed
// refusal family, and — by way of `worker.cjs`'s boot post and
// `isolate.cjs`'s boot receiver — the module path this deployment was
// pointed at.

const FAIL_FLAG = 'RF2_SSR_NODE_FLAKY_BOOT';

/** The sentinel is the fixture's, so the witness and the fixture cannot drift. */
const BOOT_SENTINEL = 'rf2-2hmg-boot-4d21fe';

/**
 * A refusal code the module has no business choosing. Spelled as a real
 * member of the family so the row is testing the boundary rather than a
 * typo: if this reached `statusFor` it would turn a 500 into a 503, which
 * is the status a caller's retry policy sleeps on.
 */
const BOOT_SPOOF_CODE = ':rf.ssr-node/service-saturated';

if (process.env[FAIL_FLAG] === '1') {
  const err = new Error(`the bundle refused to load: ${BOOT_SENTINEL}`);
  err.code = BOOT_SPOOF_CODE;
  throw err;
}

module.exports = {
  protocol: 1,
  buildId: 'flaky-boot-build-1',
  entries: {
    // Synchronous, for the reason `hang.cjs` gives: the deadline has to
    // reach a render no cooperative cancel could, because that is the one
    // that gets the isolate TERMINATED and therefore replaced.
    'app/hang': { stateAllowlist: [], runtimeAllowlist: [] },
    'app/root': { stateAllowlist: [], runtimeAllowlist: [] },
  },

  render({ entry }, emit) {
    if (entry === 'app/hang') {
      while (true) {
        Math.sqrt(Date.now());
      }
    }
    emit('<p>ok</p>');
  },
};

module.exports.FAIL_FLAG = FAIL_FLAG;
module.exports.BOOT_SENTINEL = BOOT_SENTINEL;
module.exports.BOOT_SPOOF_CODE = BOOT_SPOOF_CODE;
