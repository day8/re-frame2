// NO `'use strict'`, DELIBERATELY — the control for the freeze.
//
// A sloppy-mode CommonJS module's write to a frozen object fails SILENTLY.
// This fixture exists so the isolation witness can state the guarantee at
// its real strength: the FREEZE is a diagnostic that only strict code
// hears, and the ISOLATION is the structured clone, which holds either
// way. It reports what it observed after writing, so the witness can show
// the write reached nothing even though nothing threw.

module.exports = {
  protocol: 1,
  buildId: 'sloppy-build-1',
  entries: { 'app/root': { stateAllowlist: [':todos'] } },

  render({ state }, emit) {
    let threw = false;
    try {
      state[':todos'] = 'MUTATED';
    } catch {
      threw = true;
    }
    emit('<i>sloppy</i>');
    return { meta: { threw, afterWrite: state[':todos'] ?? null, frozen: Object.isFrozen(state) } };
  },
};
