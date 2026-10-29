'use strict';
// A `boot` hook that settles LATER — the shape of a bundle that reaches an
// ESM-only dependency with `await import(…)` — and the synchronous throw
// that is its control.
//
// The mode is an env flag because each isolate inherits a COPY of
// `process.env` when its thread is constructed, so a flag the test sets
// before `createService` reaches every isolate of that service:
//
//   throw   `boot` throws synchronously
//   reject  `boot` returns a promise that rejects on a later tick
//   slow    `boot` returns a promise that finishes its work 200 ms later

const MODE_FLAG = 'RF2_SSR_NODE_BOOT_MODE';
const BOOT_FAILURE = 'the config file is missing';

const mode = process.env[MODE_FLAG];
let booted = false;

module.exports = {
  protocol: 1,
  buildId: 'async-boot-build-1',
  entries: ['app/root'],

  boot() {
    if (mode === 'throw') throw new Error(BOOT_FAILURE);
    if (mode === 'reject') {
      return new Promise((_, reject) => setTimeout(() => reject(new Error(BOOT_FAILURE)), 20));
    }
    return new Promise((resolve) =>
      setTimeout(() => {
        booted = true;
        resolve();
      }, 200),
    );
  },

  render() {
    return `<p data-booted="${booted}"></p>`;
  },
};

module.exports.MODE_FLAG = MODE_FLAG;
module.exports.BOOT_FAILURE = BOOT_FAILURE;
