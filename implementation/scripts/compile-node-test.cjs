#!/usr/bin/env node
'use strict';
// Compile a `:node-test`-family build with its `:output-to` bundle declared as
// disposable output, so a compile that aborts leaves NO bundle rather than the
// previous one: `node out/<build>.js` then fails loudly instead of testing stale
// code, whether or not the caller chained the exit code. The delete, the compile
// and the verdict are `runShadow`'s (STALE OUTPUT in `lib/shadow.cjs`); warnings
// are fatal in shadow-cljs.edn's `:target-defaults`, not here.
//
// A focused RUN needs no compile of its own: `node out/node-test.js
// --test=<ns>[,<ns>...]` selects namespaces at runtime (see the comment on
// shadow-cljs.edn's `:node-test` build).
//
// Usage: node scripts/compile-node-test.cjs <build-id> <output-to> [extra shadow-cljs args...]
const path = require('node:path');

const { runShadow } = require('./lib/shadow.cjs');

const IMPL_DIR = path.resolve(__dirname, '..');

async function main(argv) {
  const [buildId, outputTo, ...extraArgs] = argv;
  if (!buildId || !outputTo) {
    console.error(
      'usage: compile-node-test.cjs <build-id> <output-to> [extra shadow-cljs args...]'
    );
    return 2;
  }

  // Never leave a stale bundle sitting where a fresh one belongs.
  return runShadow(['compile', buildId, ...extraArgs], {
    cwd: IMPL_DIR,
    clean: [path.resolve(IMPL_DIR, outputTo)],
    label: 'compile-node-test',
  });
}

if (require.main === module) {
  main(process.argv.slice(2)).then((code) => process.exit(code));
}

module.exports = { main };
