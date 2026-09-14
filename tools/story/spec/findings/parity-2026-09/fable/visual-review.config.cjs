'use strict';

/*
 * Playwright config for the Story visual-review experiment (rf2-ia2if).
 *
 * Its only job is to keep baselines and diff images OUT of the repository:
 * Playwright's CLI has no flag for the snapshot path, so this file exists.
 * Run from implementation/ against a watch serving the login-form testbed:
 *
 *   npx shadow-cljs watch :examples/login-form
 *   STORY_VISUAL_DIR=<scratch dir> npx playwright test \
 *     -c ../tools/story/spec/findings/parity-2026-09/fable/visual-review.config.cjs
 *
 * Add `-u` (Playwright's own --update-snapshots) to approve: that is the only
 * way a baseline is written, and it also writes each case's hash sidecar.
 */

const path = require('path');

const dir = process.env.STORY_VISUAL_DIR;
if (!dir) throw new Error('Set STORY_VISUAL_DIR to a scratch directory outside the repo.');

module.exports = {
  testDir: __dirname,
  testMatch: 'visual-review.pw.cjs',
  snapshotPathTemplate: path.join(dir, 'baselines', '{arg}{ext}'),
  outputDir: path.join(dir, 'test-results'),
  workers: 1,
  reporter: 'list',
  timeout: 300000,
  use: { browserName: 'chromium', viewport: { width: 1280, height: 800 } },
};
