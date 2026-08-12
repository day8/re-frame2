'use strict';
// A RENDER THAT NEVER RETURNS — the fixture guarantee 4 exists for.
//
// The loop is SYNCHRONOUS on purpose. `react-dom/server`'s
// `renderToString` is synchronous, so the realistic runaway render is one
// no timer, promise or abort signal can reach: the interrupt has to come
// from outside the thread. An `await`-based hang would be a much weaker
// test, because a cooperative cancel would have been enough to pass it.

module.exports = {
  protocol: 1,
  buildId: 'hang-build-1',
  entries: { 'app/root': { stateAllowlist: [':todos'] }, 'app/quick': { stateAllowlist: [] } },

  render({ entry }, emit) {
    if (entry === 'app/quick') {
      emit('<p>quick</p>');
      return {};
    }
    // eslint-disable-next-line no-constant-condition
    while (true) {
      // Touch something so no engine can optimise the loop away.
      Math.sqrt(Date.now());
    }
  },
};
