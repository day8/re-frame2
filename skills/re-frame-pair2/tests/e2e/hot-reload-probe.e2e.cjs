/*
 * hot-reload-probe.e2e.cjs — pair2's hot-reload probe contract exercised
 * against a real shadow-cljs watch build.
 *
 * Steps:
 *   1. Capture the probe value: registrar-handler-ref :event :counter/inc.
 *   2. Touch the fixture's core.cljs (append + restore a no-op comment) so
 *      shadow-cljs hot-reloads the namespace.
 *   3. Call `scripts/tail-build.sh --probe '<probe>'` and expect
 *      `:ok? true :soft? false` once the probe value flips.
 *
 * This is the probe-based reload contract from §4.5 of the spec — the
 * one explicitly called out as safety-critical in `docs/TESTING.md`.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { runShim, parseEdn, openPage } = require('./_helpers.cjs');

const PROBE = '(re-frame-pair2.runtime/registrar-handler-ref :event :counter/inc)';

async function run(ctx) {
  const { browser } = await openPage(ctx);
  try {
    // Capture before
    const before = runShim(ctx, 'eval', [PROBE]);
    if (before.exit !== 0) {
      throw new Error('eval(before) exit ' + before.exit + ': ' + before.stderr);
    }
    const bv = parseEdn(before.stdout);
    if (!bv || bv['ok?'] !== true) {
      throw new Error('probe before did not return ok?: ' + before.stdout);
    }
    const beforeHash = bv['value'];

    // Trigger reload: append + restore a comment line in core.cljs.
    const fixtureSrc = path.join(
      ctx.fixtureDir,
      'src',
      'counter',
      'core.cljs',
    );
    const original = fs.readFileSync(fixtureSrc, 'utf8');
    const marker = '\n;; e2e touch ' + Date.now() + '\n';
    fs.writeFileSync(fixtureSrc, original + marker);
    let restored = false;
    try {
      // tail-build --probe waits for the value to flip; the default
      // --wait-ms is 5000.
      const t = runShim(ctx, 'tail-build', [
        '--probe',
        PROBE,
        '--wait-ms',
        '15000',
      ]);
      if (t.exit !== 0) {
        throw new Error('tail-build exit ' + t.exit + ': ' + t.stderr);
      }
      const tv = parseEdn(t.stdout);
      if (!tv || tv['ok?'] !== true) {
        throw new Error('tail-build did not return ok?: ' + t.stdout);
      }
      if (tv['soft?'] === true) {
        throw new Error(
          'tail-build reported :soft? true — probe did not flip, ' +
          'hot-reload did not land: ' + t.stdout,
        );
      }

      // Sanity: the probe should now read a different value.
      const after = runShim(ctx, 'eval', [PROBE]);
      if (after.exit !== 0) {
        throw new Error('eval(after) exit ' + after.exit + ': ' + after.stderr);
      }
      const av = parseEdn(after.stdout);
      const afterHash = av && av['value'];
      if (afterHash === beforeHash) {
        throw new Error(
          'probe value did not change after reload: ' +
            beforeHash + ' === ' + afterHash,
        );
      }
    } finally {
      fs.writeFileSync(fixtureSrc, original);
      restored = true;
    }
    if (!restored) {
      throw new Error('failed to restore fixture src');
    }
  } finally {
    await browser.close();
  }
}

module.exports = { run };
