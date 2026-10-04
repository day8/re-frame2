'use strict';
// A render that throws, and one that emits nothing at all. The torn
// response — a throw AFTER emitting — is `throws-data.cjs`'s `app/torn`.

module.exports = {
  protocol: 1,
  buildId: 'throws-build-1',
  entries: {
    'app/before': { stateAllowlist: [], runtimeAllowlist: [] },
    'app/silent': { stateAllowlist: [], runtimeAllowlist: [] },
  },

  render({ entry }, emit) {
    if (entry === 'app/silent') return; // emits nothing at all
    throw new Error('fell over immediately');
  },
};
