#!/usr/bin/env node
// THE CONVERGED P0 CLOCK TABLE — driver (rf2-a4x1o).
//
//   node implementation/freehand/test/re_frame/bench/hicasso/p0_converge_run.cjs
//
// There is no new driver here and there is no new build id. rf2-2rtt6.2
// built `run.cjs` so that a sibling arm rides the `:hicasso-bench` lane by
// naming its own `:init-fn`, and HD-017 makes a build-id touch a hot-zone
// edit. This file is the three environment variables that name this arm's
// entry, and then rf2-2rtt6.2's driver unchanged — which also makes the
// reproduction command ABOVE runnable on Windows, where an inline
// `VAR=value node ...` prefix is not a thing the shell understands and a
// published repro that only runs on a POSIX shell is a repro that half the
// maintainers cannot take.
//
// Exit codes are `run.cjs`'s and are not this file's to change:
//
//   0  measured, guard clean, control passed
//   1  the run failed (build, page error, a fatal the page recorded, a
//      positive control that did not see what it predicted)
//   2  THE ARM-ORDER GUARD REFUSED. Repair the ARM, never the tolerance.

'use strict';

process.env.HICASSO_INIT_FN =
  process.env.HICASSO_INIT_FN || 're-frame.bench.hicasso.p0-converge-app/-main';
process.env.HICASSO_OUT_DIR = process.env.HICASSO_OUT_DIR || 'out/hicasso-converge';
process.env.HICASSO_PORT = process.env.HICASSO_PORT || '8134';

require('./run.cjs');
