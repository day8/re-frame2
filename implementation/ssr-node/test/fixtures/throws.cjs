'use strict';
// A render that throws, and one that throws AFTER emitting — the torn
// response the transport must not present as a shorter page.

module.exports = {
  protocol: 1,
  buildId: 'throws-build-1',
  entries: {
    'app/before': { stateAllowlist: [] },
    'app/after': { stateAllowlist: [] },
    'app/silent': { stateAllowlist: [] },
  },

  render({ entry }, emit) {
    if (entry === 'app/silent') return; // emits nothing at all
    if (entry === 'app/after') {
      emit('<p>first</p>');
      throw new Error('fell over halfway');
    }
    throw new Error('fell over immediately');
  },
};
