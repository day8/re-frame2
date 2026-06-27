#!/usr/bin/env node
/*
 * Tests for `examples/scripts/check-reagent-slim-boundary.cjs` — the STATIC
 * stock-Reagent / slim-Reagent source-boundary gate (rf2-hekqp).
 *
 * Two jobs, both with teeth:
 *
 *  1. LIVE GATE — run the real scan over the actual examples/reagent/ tree and
 *     FAIL if it reports any slim-wiring violation. This is what gives the
 *     always-run `test:script-policy` gate its teeth: a `reagent2.*` or
 *     `re-frame.adapter.reagent-slim` require leaking into the stock tree turns
 *     this gate RED in CI. (Today the real tree is clean, so the live scan
 *     passes — see the teeth-proof in the PR: add a slim require to a stock
 *     example → RED → restore → GREEN.)
 *
 *  2. UNIT TEETH — pin the pure detector against synthetic fixtures so the
 *     behaviours the gate relies on cannot silently regress to a vacuous pass:
 *       - slim requires (`reagent2.*`, `re-frame.adapter.reagent-slim`) ARE
 *         flagged;
 *       - stock wiring (`reagent.core`, `re-frame.adapter.reagent`) is NOT
 *         flagged — the boundary must distinguish stock from slim.
 *
 * Standalone node-runnable suite — no external test framework, mirroring
 * `check-examples-assets.test.cjs`. Wired into package.json via
 * `test:script-policy`.
 */

'use strict';

const path = require('path');
const assert = require('assert');

const scanner = require('../../examples/scripts/check-reagent-slim-boundary.cjs');
const {
  FORBIDDEN,
  detectForbidden,
  scanFile,
  scanAll,
  listStockReagentSources,
  STOCK_REAGENT_ROOTS,
} = scanner;

let failed = 0;
function it(label, fn) {
  try {
    fn();
    console.log(`  PASS  ${label}`);
  } catch (err) {
    failed++;
    console.error(`  FAIL  ${label}`);
    console.error(`        ${(err && err.message) || err}`);
  }
}

console.log('check-reagent-slim-boundary tests (rf2-hekqp)');

// ---------------------------------------------------------------------------
// 1) LIVE GATE — the teeth in CI. Scan the real stock-Reagent tree; any slim
//    require is RED.
// ---------------------------------------------------------------------------

const realSources = listStockReagentSources();

it('the real stock-Reagent tree exposes a non-vacuous set of sources', () => {
  assert.ok(
    realSources.length >= 20,
    `expected the full stock-Reagent source set (>=20), got ` +
      `${realSources.length} — walk/layout drift; a vacuous gate is forbidden`,
  );
});

it('LIVE: no stock-Reagent example source requires slim wiring', () => {
  const { errors } = scanAll({ files: realSources });
  assert.strictEqual(
    errors.length,
    0,
    `the live boundary scan found ${errors.length} violation(s):\n` +
      errors.map((e) => `    - ${e}`).join('\n'),
  );
});

// ---------------------------------------------------------------------------
// 2) UNIT TEETH — synthetic in-memory fixtures so the detector is pinned.
// A tiny fake io: a map of absolute path -> file contents.
// ---------------------------------------------------------------------------

function makeIo(files) {
  const norm = (p) => path.resolve(p);
  const map = new Map(Object.entries(files).map(([k, v]) => [norm(k), v]));
  return {
    readFileSync: (p) => {
      const v = map.get(norm(p));
      if (v == null) {
        const e = new Error(`ENOENT: ${p}`);
        e.code = 'ENOENT';
        throw e;
      }
      return v;
    },
  };
}

// A realistic stock-Reagent ns form — the shape every examples/reagent/ core
// ships (stock adapter + stock reagent.dom.client). MUST scan clean.
const STOCK_NS = [
  '(ns examples.reagent.counter.core',
  '  (:require [reagent.dom.client          :as rdc]',
  '            [re-frame.core               :as rf]',
  '            [re-frame.views]',
  '            [re-frame.adapter.reagent    :as reagent-adapter])',
  '  (:require-macros [re-frame.core :refer [reg-view]]))',
].join('\n');

// The slim ns form (lifted from examples/reagent-slim/.../core.cljs). MUST be
// flagged on BOTH rules.
const SLIM_NS = [
  '(ns examples.reagent-slim.counter.core',
  '  (:require [reagent2.dom.client            :as rdc]',
  '            [reagent2.dom.server            :as rds]',
  '            [re-frame.core                  :as rf]',
  '            [re-frame.adapter.reagent-slim  :as reagent-slim-adapter])',
  '  (:require-macros [re-frame.core :refer [reg-view]]))',
].join('\n');

// ---- the detector distinguishes stock from slim --------------------------

it('detectForbidden returns no hits for a stock-Reagent ns form', () => {
  assert.deepStrictEqual(detectForbidden(STOCK_NS), []);
});

it('TEETH: detectForbidden flags BOTH slim rules on a slim ns form', () => {
  const ids = detectForbidden(SLIM_NS).map((r) => r.id).sort();
  assert.deepStrictEqual(ids, ['reagent-slim-adapter', 'reagent2']);
});

// ---- reagent2.* rule: anchored so stock reagent.* never matches -----------

it('TEETH: a reagent2.* require is flagged', () => {
  assert.ok(
    detectForbidden('  (:require [reagent2.core :as r])').some((r) => r.id === 'reagent2'),
  );
});

it('TEETH: a deep reagent2 ns (reagent2.dom.server) is flagged', () => {
  assert.ok(
    detectForbidden('[reagent2.dom.server :as rds]').some((r) => r.id === 'reagent2'),
  );
});

it('stock reagent.core is NOT flagged by the reagent2 rule', () => {
  assert.ok(!detectForbidden('[reagent.core :as r]').some((r) => r.id === 'reagent2'));
});

it('stock reagent.dom.client is NOT flagged by the reagent2 rule', () => {
  assert.ok(
    !detectForbidden('[reagent.dom.client :as rdc]').some((r) => r.id === 'reagent2'),
  );
});

// ---- adapter rule: -slim flagged, stock adapter never ---------------------

it('TEETH: re-frame.adapter.reagent-slim is flagged', () => {
  assert.ok(
    detectForbidden('[re-frame.adapter.reagent-slim :as a]').some(
      (r) => r.id === 'reagent-slim-adapter',
    ),
  );
});

it('the stock re-frame.adapter.reagent is NOT flagged (no -slim suffix)', () => {
  assert.deepStrictEqual(detectForbidden('[re-frame.adapter.reagent :as reagent-adapter]'), []);
});

it('the stock adapter on a require line with no alias is NOT flagged', () => {
  assert.deepStrictEqual(detectForbidden('   [re-frame.adapter.reagent]'), []);
});

// ---- scanFile / scanAll integration --------------------------------------

it('scanFile reports a clean stock source with no errors', () => {
  const p = path.join(STOCK_REAGENT_ROOTS[0], 'counter', 'core.cljs');
  const io = makeIo({ [p]: STOCK_NS });
  assert.deepStrictEqual(scanFile(io, p).errors, []);
});

it('TEETH: scanFile reports a slim require leaking into the stock tree', () => {
  // A stock-tree path that (wrongly) carries slim wiring — exactly the
  // regression this gate exists to catch.
  const p = path.join(STOCK_REAGENT_ROOTS[0], 'counter', 'core.cljs');
  const leaked = STOCK_NS.replace(
    '[re-frame.adapter.reagent    :as reagent-adapter]',
    '[re-frame.adapter.reagent-slim :as reagent-adapter]',
  );
  const io = makeIo({ [p]: leaked });
  const { errors } = scanFile(io, p);
  assert.ok(
    errors.some(
      (e) => e.includes('re-frame.adapter.reagent-slim') && e.includes('forbidden'),
    ),
    `expected a slim-adapter violation, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: scanAll surfaces a reagent2 leak across the synthetic tree', () => {
  const clean = path.join(STOCK_REAGENT_ROOTS[0], 'counter', 'core.cljs');
  const dirty = path.join(STOCK_REAGENT_ROOTS[0], 'demo', 'core.cljs');
  const io = makeIo({
    [clean]: STOCK_NS,
    [dirty]: STOCK_NS.replace('[reagent.dom.client          :as rdc]', '[reagent2.dom.client :as rdc]'),
  });
  const { errors } = scanAll({ io, files: [clean, dirty] });
  assert.strictEqual(errors.length, 1, `expected exactly one violation, got: ${errors.join(' | ')}`);
  assert.ok(errors[0].includes('reagent2'));
});

// ---- contract constant sanity --------------------------------------------

it('FORBIDDEN names exactly the two slim-substrate surfaces', () => {
  assert.deepStrictEqual(
    FORBIDDEN.map((r) => r.id).sort(),
    ['reagent-slim-adapter', 'reagent2'],
  );
});

if (failed > 0) {
  console.error(`\ncheck-reagent-slim-boundary tests: ${failed} failed.`);
  process.exit(1);
}
console.log('\ncheck-reagent-slim-boundary tests: all passed.');
