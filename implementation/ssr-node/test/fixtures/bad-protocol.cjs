'use strict';
// A bundle built against a protocol this service does not speak.
module.exports = {
  protocol: 99,
  buildId: 'bad-2',
  entries: { 'app/root': { stateAllowlist: [] } },
  render(_c, emit) { emit('<p>never</p>'); return {}; },
};
