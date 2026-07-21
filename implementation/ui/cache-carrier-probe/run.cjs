#!/usr/bin/env node
'use strict';

// S0 carrier proof driver (rf2-u53yy.1.1). Two independent one-shot
// `shadow-cljs compile` runs (each a fresh JVM — no persistent server) share ONLY
// the on-disk cache. Pass 1 is cold (the macro expands, stamping both carriers).
// Pass 2 leaves every source byte untouched, so a genuine disk-cache HIT means the
// consumer namespace is NOT recompiled — proven independently by the compile-time
// WITNESS file staying flat AND by Shadow reporting 0 namespaces compiled. We then
// read the compile-finish summary (computed in Clojure with exact EDN equality) and
// report, per carrier variant, whether the stamped metadata survived the round-trip.
//
//   node run.cjs      -> runs against the shadow-cljs resolvable from RF2_SHADOW_DIR
//                        (a dir with node_modules/shadow-cljs), defaulting to the
//                        implementation/ install two levels up.
//
// To sweep the supported Shadow range, install a version into a scratch dir and
// point RF2_SHADOW_DIR at it (see README.md).

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const PROBE = __dirname;
const SHADOW_DIR = process.env.RF2_SHADOW_DIR
  || path.resolve(PROBE, '..', '..'); // implementation/
const RUNNER = require.resolve('shadow-cljs/cli/runner.js', { paths: [SHADOW_DIR] });
const SHADOW_PKG = require(require.resolve('shadow-cljs/package.json', { paths: [SHADOW_DIR] }));
const WITNESS = path.join(PROBE, 'target', 's0-witness', 'expansions.txt');
const SUMMARY = path.join(PROBE, 'target', 's0-witness', 'summary.edn');
const SERVER_PID = path.join(PROBE, '.shadow-cljs', 'server.pid');

function rmrf(p) { fs.rmSync(p, { recursive: true, force: true }); }
function witnessLines() {
  if (!fs.existsSync(WITNESS)) return [];
  return fs.readFileSync(WITNESS, 'utf8').trim().split(/\r?\n/).filter(Boolean);
}
function readSummary() {
  const edn = fs.existsSync(SUMMARY) ? fs.readFileSync(SUMMARY, 'utf8') : '';
  return { variantA: /:variant-a true/.test(edn), variantB: /:variant-b true/.test(edn), edn };
}

function compile(label) {
  console.log(`\n=== ${label}: shadow-cljs compile probe (fresh JVM) ===`);
  const t0 = Date.now();
  const r = spawnSync(process.execPath, [RUNNER, 'compile', 'probe'], {
    cwd: PROBE, env: process.env, encoding: 'utf8',
  });
  const ms = Date.now() - t0;
  const out = (r.stdout || '') + (r.stderr || '');
  const m = out.match(/Build completed\. \(\d+ files, (\d+) compiled/);
  const compiled = m ? Number(m[1]) : null;
  console.log(out.split(/\r?\n/).filter((l) => /Compiling|Build completed|Build failure/.test(l)).join('\n'));
  console.log(`  (exit ${r.status}, ${ms} ms, ${compiled} namespaces compiled)`);
  if (r.status !== 0) throw new Error(`${label} compile failed (exit ${r.status})\n${out}`);
  return { compiled, ms, serverPidPresent: fs.existsSync(SERVER_PID) };
}

function stopServerIfAny(label) {
  if (!fs.existsSync(SERVER_PID)) return;
  console.log(`  ${label}: server.pid present — stopping to force a cold pass 2`);
  spawnSync(process.execPath, [RUNNER, 'stop'], { cwd: PROBE, env: process.env, encoding: 'utf8' });
  rmrf(SERVER_PID);
}

function main() {
  console.log('S0 carrier proof — Shadow disk-cache round-trip of def :meta + ns analyzer descriptors');
  console.log(`  shadow-cljs version : ${SHADOW_PKG.version}`);
  console.log(`  shadow dir          : ${SHADOW_DIR}`);

  stopServerIfAny('pre-clean');
  for (const p of ['.shadow-cljs', 'out', 'target']) rmrf(path.join(PROBE, p));

  // --- PASS 1: COLD (macro expands, stamps both carriers) ---
  const p1 = compile('PASS 1 (cold)');
  const w1 = witnessLines().length;
  const s1 = readSummary();
  console.log(`  PASS 1: ${w1} macro expansions; variant-a=${s1.variantA} variant-b=${s1.variantB}`);
  if (w1 !== 2) throw new Error(`expected 2 cold expansions (alpha,beta), got ${w1}`);
  if (!s1.variantA || !s1.variantB) {
    throw new Error(`COLD baseline failed exact-equality — probe bug, not a real NO.\n${s1.edn}`);
  }

  stopServerIfAny('between-passes');

  // --- PASS 2: WARM (no source touched — must be a genuine disk-cache hit) ---
  const p2 = compile('PASS 2 (warm — sources untouched)');
  const w2 = witnessLines().length;
  const s2 = readSummary();
  console.log(`  PASS 2: ${w2} macro expansions; variant-a=${s2.variantA} variant-b=${s2.variantB}`);

  const genuineCacheHit = (w2 === w1) && (p2.compiled === 0);
  console.log('\n================ S0 VERDICT ================');
  console.log(`  shadow-cljs ${SHADOW_PKG.version}`);
  console.log(`  server.pid seen (p1/p2): ${p1.serverPidPresent}/${p2.serverPidPresent} (both must be false)`);
  console.log(`  pass 2 namespaces compiled: ${p2.compiled} (must be 0); witness ${w1}->${w2} (must not grow)`);
  console.log(`  GENUINE DISK-CACHE HIT (macro did NOT re-expand on pass 2): ${genuineCacheHit}`);
  if (!genuineCacheHit) {
    throw new Error('cache MISS on pass 2 — cannot prove disk round-trip; the proof is INVALID');
  }
  console.log('\n  On the PROVEN cache-hit pass, [:compiler-env :cljs.analyzer/namespaces probe.consumer]:');
  console.log(`    VARIANT A (def :meta  :probe.rf2/descriptor)    survived disk round-trip: ${s2.variantA ? 'YES' : 'NO'}`);
  console.log(`    VARIANT B (ns-level   :probe.rf2/ns-descriptor) survived disk round-trip: ${s2.variantB ? 'YES' : 'NO'}`);
  console.log('===========================================');
  console.log(`\nRESULT ${SHADOW_PKG.version}: variant-a=${s2.variantA ? 'YES' : 'NO'} variant-b=${s2.variantB ? 'YES' : 'NO'} cache-hit=proven\n`);
}

main();
