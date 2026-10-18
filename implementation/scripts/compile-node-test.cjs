#!/usr/bin/env node
'use strict';
// A compile of a `:node-test`-family build that ABORTS (nonzero
// exit; e.g. `aborted par-compile, ... still waiting for ...` from
// `shadow.build.compiler/par-compile-one`'s 60s `:par-timeout`, seen under
// box load AND forceable deterministically by overriding that timeout)
// throws before shadow-cljs ever reaches the link/write step for its
// `:output-to` bundle. Left alone, the PREVIOUS successful bundle just sits
// there, byte-identical to a fresh one from the outside. A caller that does
// not chain the compile's exit code — a two-step invocation, a background
// job whose failure is swallowed, anything that isn't `compile && run` —
// silently executes stale compiled code and reports a green that means
// nothing.
//
// A `node-test` compile with `:build-options {:par-timeout 1}` reliably
// aborts (exit 1) inside ~90s, and `out/node-test.js` from the prior
// successful compile is left completely unchanged on disk — same size, same
// mtime. A subsequent full compile with the default timeout self-heals
// (shadow-cljs's per-namespace cache is keyed by source hash, not by the
// aborted run), so the on-disk CACHE is not the reliably-reproducible
// carrier; the STALE, still-present BUNDLE is. This script closes that gap
// unconditionally, for any cause of abort.
//
// So it deletes `:output-to` BEFORE compiling, always. A failed compile then
// leaves NO bundle rather than a stale one: `node out/<build>.js` fails LOUD
// ("Cannot find module") instead of silently succeeding against old code,
// regardless of whether the calling script chained the exit code correctly.
// This makes the dangerous consequence (silent stale-bundle reuse)
// IMPOSSIBLE by construction rather than relying on every present and future
// caller to remember `&&`. The bundle is the compile's declared disposable
// output, so the delete, the compile and the verdict on it are `runShadow`'s
// (`lib/shadow.cjs`), which every shadow-cljs entry shares.
//
// A focused RUN needs no compile of its own: `node out/node-test.js
// --test=<ns>[,<ns>...]` selects namespaces at RUNTIME — see the comment on
// shadow-cljs.edn's `:node-test` build.
//
// WARNINGS ARE FATAL IN THE BUILD CONFIG, NOT HERE. shadow-cljs.edn's
// `:target-defaults` sets `:warnings-as-errors true` for every `:node-test`
// build, so a warning fails the compile and arrives here as a nonzero exit,
// exactly as it does for a bare `npx shadow-cljs compile <id>`. A test build
// needs that more than most: a bare `"` inside a deftest docstring closes the
// string early, the words after it become undeclared vars that compile to
// `undefined`, and the suite still reports the clean tree's test and assertion
// counts.
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
