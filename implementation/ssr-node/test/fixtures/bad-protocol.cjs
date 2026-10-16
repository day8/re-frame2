'use strict';
// A bundle built against a protocol this service does not speak.
module.exports = {
  protocol: 99,
  buildId: 'bad-2',
  entries: ['app/root'],
  render() { return '<p>never</p>'; },
};
