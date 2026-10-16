'use strict';
// A render that throws, and one that returns no body at all.

module.exports = {
  protocol: 1,
  buildId: 'throws-build-1',
  entries: {
    'app/before': { stateAllowlist: [], runtimeAllowlist: [] },
    'app/silent': { stateAllowlist: [], runtimeAllowlist: [] },
  },

  render({ entry }) {
    if (entry === 'app/silent') return; // returns no body at all
    throw new Error('fell over immediately');
  },
};
