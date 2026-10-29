'use strict';
// A module whose buildId is a valid protocol string that no HTTP header can
// carry — the trailing newline of an id read from a file. The service boots
// on it; the HTTP transport must refuse it before it listens.

module.exports = {
  protocol: 1,
  buildId: 'bad-header-build-1\n',
  entries: ['app/root'],
  render: () => '<p>never served</p>',
};
