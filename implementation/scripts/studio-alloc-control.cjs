#!/usr/bin/env node
/*
 * SCAFFOLDING for rf2-lnecd — deleted before the PR.
 *
 * A POSITIVE CONTROL for the CDP sampling heap profiler, because the arms it
 * was pointed at returned 5-23 KB for a 301-element React mount, which is not
 * a believable number, and "the arm allocates little" and "the sampler sees
 * little" are not distinguishable without a known quantity.
 *
 * Allocates a KNOWN number of bytes inside the same start/stop window the
 * probe uses and prints what the sampler says it saw. If the sampler is sound
 * the reported total tracks the known one; if it does not, the allocation
 * instrument is unusable on this host and the report must say so rather than
 * quote it.
 */

'use strict';

const { chromium } = require('playwright');

function totalBytes(node) {
  let sum = node.selfSize || 0;
  for (const c of node.children || []) sum += totalBytes(c);
  return sum;
}

(async () => {
  const browser = await chromium.launch({ args: ['--js-flags=--expose-gc'] });
  const page = await browser.newPage();
  await page.goto('about:blank');
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('HeapProfiler.enable');

  for (const interval of [512, 64]) {
    for (const mb of [1, 8, 32]) {
      await cdp.send('HeapProfiler.collectGarbage');
      await cdp.send('HeapProfiler.startSampling', { samplingInterval: interval });
      // Float64Array: 8 bytes per element, allocated OUTSIDE the JS heap's
      // object space in some V8 versions — so also allocate plain objects,
      // which is what a React mount actually does.
      await page.evaluate((n) => {
        const sink = [];
        for (let i = 0; i < n; i += 1) sink.push({ a: i, b: 'x', c: null, d: [i] });
        window.__sink__ = sink.length;
      }, mb * 10000);
      const { profile } = await cdp.send('HeapProfiler.stopSampling');
      const objs = mb * 10000;
      console.log(
        `interval ${String(interval).padStart(4)}  ${String(objs).padStart(7)} objects ` +
        `(~${(objs * 120 / 1048576).toFixed(1)} MB of {a,b,c,d:[i]})  ` +
        `sampler says ${(totalBytes(profile.head) / 1048576).toFixed(2)} MB`);
    }
  }
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
