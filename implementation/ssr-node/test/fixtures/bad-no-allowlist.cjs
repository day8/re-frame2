'use strict';
// An entry that declares no `stateAllowlist`. Fail-closed: unrenderable.
module.exports = {
  protocol: 1,
  buildId: 'bad-1',
  entries: { 'app/root': {} },
  render(_c, emit) { emit('<p>never</p>'); return {}; },
};
