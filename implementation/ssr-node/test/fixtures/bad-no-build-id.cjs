'use strict';
// No build identity — the skew detector would have nothing to compare.
module.exports = {
  protocol: 1,
  entries: { 'app/root': { stateAllowlist: [], runtimeAllowlist: [] } },
  render() { return '<p>never</p>'; },
};
