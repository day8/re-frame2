'use strict';
// An entry that declares a `stateAllowlist` but no `runtimeAllowlist`.
// Fail-closed for the runtime partition exactly as for the app-db one:
// unrenderable. The sibling of `bad-no-allowlist.cjs`.
module.exports = {
  protocol: 1,
  buildId: 'bad-1',
  entries: { 'app/root': { stateAllowlist: [':todos'] } },
  render(_c, emit) { emit('<p>never</p>'); },
};
