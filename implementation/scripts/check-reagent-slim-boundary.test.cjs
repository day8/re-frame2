#!/usr/bin/env node
/*
 * Tests for `examples/scripts/check-reagent-slim-boundary.cjs`: the live scan of
 * the stock-Reagent examples (any slim `reagent2.*` / reagent-slim adapter require
 * there is red), plus synthetic fixtures that keep its detector and its fail-closed
 * enumeration from going vacuous. Discovered by `npm run test:scripts`.
 */

'use strict';

const path = require('path');
const assert = require('assert');

const scanner = require('../../examples/scripts/check-reagent-slim-boundary.cjs');
const {
  detectForbidden,
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

console.log('check-reagent-slim-boundary tests');

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

// An unreadable declared root must throw, naming it, rather than silently shrink
// the set while the aggregate floor still passes.
function failingReaddirIo(badDir) {
  const realFs = require('fs');
  const bad = path.resolve(badDir);
  return {
    readdirSync: (dir, opts) => {
      if (path.resolve(dir) === bad) {
        const e = new Error(`EACCES: permission denied, scandir '${dir}'`);
        e.code = 'EACCES';
        throw e;
      }
      return realFs.readdirSync(dir, opts);
    },
  };
}

it('TEETH: a missing/unreadable declared root FAILS CLOSED, naming that root (rf2-3fc89f.31)', () => {
  const badRoot = STOCK_REAGENT_ROOTS[0];
  assert.throws(
    () => listStockReagentSources(STOCK_REAGENT_ROOTS, { io: failingReaddirIo(badRoot) }),
    (err) => err.message.includes(badRoot),
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

// A stock-Reagent ns form; the scanAll fixture derives its leak from it.
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

it('TEETH: detectForbidden flags BOTH slim rules on a slim ns form', () => {
  const ids = detectForbidden(SLIM_NS).map((r) => r.id).sort();
  assert.deepStrictEqual(ids, ['reagent-slim-adapter', 'reagent2']);
});

it('TEETH: scanAll surfaces a reagent2 leak across the synthetic tree', () => {
  const clean = path.join(STOCK_REAGENT_ROOTS[0], 'counter', 'core.cljs');
  const dirty = path.join(STOCK_REAGENT_ROOTS[0], 'demo', 'core.cljs');
  const io = makeIo({
    [clean]: STOCK_NS,
    [dirty]: STOCK_NS.replace('[reagent.dom.client          :as rdc]', '[reagent2.dom.client :as rdc]'),
  });
  const { errors } = scanAll({ io, files: [clean, dirty] });
  assert.ok(
    errors.length === 1 && errors[0].includes('reagent2'),
    `expected exactly one reagent2 violation, got: ${errors.join(' | ')}`,
  );
});

if (failed > 0) {
  console.error(`\ncheck-reagent-slim-boundary tests: ${failed} failed.`);
  process.exit(1);
}
console.log('\ncheck-reagent-slim-boundary tests: all passed.');
