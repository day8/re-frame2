#!/usr/bin/env node
/*
 * re-frame.ui focused-build dependency isolation.
 *
 * `out/node-test-ui.js` is Shadow's loader for the focused `:node-test-ui`
 * build. Its SHADOW_IMPORT rows are the compiler-computed transitive
 * dependency closure, making it a stronger and more truthful boundary than a
 * runtime assertion inside a `*-cljs-test` namespace: that same namespace is
 * also loaded by the consolidated `:node-test` build, where the retiring
 * adapters are intentionally present because their own tests still run.
 */

'use strict';

const fs = require('node:fs');
const path = require('node:path');

const loaderPath = path.resolve(__dirname, '..', 'out', 'node-test-ui.js');
const requiredImport = 're_frame.ui.substrate.js';
const forbiddenPrefixes = [
  're_frame.adapter.reagent',
  're_frame.adapter.reagent_slim',
  're_frame.adapter.uix',
  're_frame.adapter.helix',
];

function importsFrom(loader) {
  return [...loader.matchAll(/SHADOW_IMPORT\("([^"]+)"\);/g)].map((match) => match[1]);
}

function forbiddenImports(imports) {
  return imports.filter((name) =>
    forbiddenPrefixes.some((prefix) => name.startsWith(prefix))
  );
}

function selfTest() {
  const clean = [requiredImport, 're_frame.core.js'];
  const contaminated = [...clean, 're_frame.adapter.uix.js'];
  const cleanResult = forbiddenImports(clean);
  const redResult = forbiddenImports(contaminated);

  if (cleanResult.length !== 0) {
    console.error('[ui-adapter-isolation] self-test: clean closure was rejected');
    return 1;
  }
  if (redResult.length !== 1 || redResult[0] !== 're_frame.adapter.uix.js') {
    console.error('[ui-adapter-isolation] self-test: forbidden adapter import was not detected');
    return 1;
  }

  console.log('[ui-adapter-isolation] self-test PASS (synthetic contamination is rejected)');
  return 0;
}

function main(argv) {
  if (argv.includes('--self-test')) return selfTest();

  if (!fs.existsSync(loaderPath)) {
    console.error(`[ui-adapter-isolation] missing focused loader: ${loaderPath}`);
    return 2;
  }

  const imports = importsFrom(fs.readFileSync(loaderPath, 'utf8'));
  if (imports.length === 0) {
    console.error('[ui-adapter-isolation] focused loader contains no SHADOW_IMPORT rows');
    return 2;
  }
  if (!imports.includes(requiredImport)) {
    console.error(`[ui-adapter-isolation] positive control missing: ${requiredImport}`);
    return 2;
  }

  const forbidden = forbiddenImports(imports);
  if (forbidden.length > 0) {
    console.error('[ui-adapter-isolation] retiring adapter code entered the focused UI dependency closure:');
    for (const name of forbidden) console.error(`  ${name}`);
    return 1;
  }

  console.log(`[ui-adapter-isolation] PASS (${imports.length} compiler-selected imports)`);
  return 0;
}

process.exitCode = main(process.argv.slice(2));
