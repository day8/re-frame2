'use strict';
// A render that FINISHES, and a callback it scheduled that ends the thread
// once the isolate is idle again — the late timer or rejected promise an
// ordinary module leaves behind.
//
//   `app/throw-later` — throws from a timer after the render resolved.
//   `app/exit-later`  — exits the thread from a timer after the render resolved.
//   `app/root`        — renders and schedules nothing.
//
// Each render prints its thread id, so a row can tell a replacement from
// the isolate that died.

const { threadId } = require('node:worker_threads');
const { encode } = require('../observations.cjs');

module.exports = {
  protocol: 1,
  buildId: 'dies-idle-build-1',
  entries: ['app/throw-later', 'app/exit-later', 'app/root'],

  render({ entry }) {
    if (entry === 'app/throw-later') {
      setTimeout(() => {
        throw new Error('dies-idle: a callback threw after the render resolved');
      }, 20);
    }
    if (entry === 'app/exit-later') setTimeout(() => process.exit(0), 20);
    return `<p${encode({ threadId })}>ok</p>`;
  },
};
