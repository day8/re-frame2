/*
 * Counter (perf-instrumented) — browser smoke.
 *
 * Spec 009 §Performance instrumentation: when a build sets
 * `re-frame.performance/enabled? true`, the runtime brackets event
 * dispatch / sub recompute / fx execution / view render in
 * `performance.mark` + `performance.measure` calls so the User-Timing
 * stream carries `rf:event:*` / `rf:sub:*` / `rf:fx:*` / `rf:render:*`
 * entries.
 *
 * This spec runs against `examples/counter-perf` — the same counter
 * source, same `:advanced` compilation, but with the goog-define flag
 * flipped via `:closure-defines`. After a real dispatch (the +1 click),
 * it reads `performance.getEntriesByType('measure')` and asserts that
 * the four expected `rf:` buckets are populated.
 *
 * The counter source itself is unchanged; the perf surface is purely a
 * compile-time toggle exercised through the same code path as the
 * default counter spec.
 */

const { expectTextEquals } = require('../../../../examples/scripts/spec-helpers.cjs');
const VERBOSE_TESTS = process.env.RF2_VERBOSE_TESTS === '1';

function userTimingDiagnostics(buckets) {
  return [
    '  perf-on counter - User Timing entries:',
    `    rf:event   count=${buckets.counts.event}   sample=${buckets.samples.event}`,
    `    rf:sub     count=${buckets.counts.sub}     sample=${buckets.samples.sub}`,
    `    rf:fx      count=${buckets.counts.fx}      sample=${buckets.samples.fx}`,
    `    rf:render  count=${buckets.counts.render}  sample=${buckets.samples.render}`,
  ].join('\n');
}

function expectTimingBucket(buckets, bucket) {
  if (buckets.counts[bucket] === 0) {
    throw new Error(
      `Expected at least one rf:${bucket}:* measure entry; got 0.\n` +
        userTimingDiagnostics(buckets)
    );
  }
}

module.exports = {
  name: 'counter-perf',
  url:  '/counter-perf/',
  run:  async (page) => {
    // Make sure the app booted and the initial render landed before we
    // sample the User-Timing stream — `dispatch-sync :counter/initialise`
    // and the first reagent render must have run.
    const span = page.locator('#app [data-testid="counter-value"]');
    await expectTextEquals(span, '5', 10000);

    // Drive a real dispatch through the +/- buttons so every bucket
    // (event handler, sub recompute, fx, render) fires at least once.
    await page.locator('#app').getByRole('button', { name: '+' }).click();
    await expectTextEquals(span, '6');

    // Read every recorded `measure` entry whose name starts `rf:` and
    // group by bucket. Returned to Node as a plain object so the
    // Playwright report carries the per-bucket totals.
    const buckets = await page.evaluate(() => {
      const entries = performance.getEntriesByType('measure');
      const out = { event: [], sub: [], fx: [], render: [], other: [] };
      for (const e of entries) {
        if (typeof e.name !== 'string' || !e.name.startsWith('rf:')) continue;
        const parts = e.name.split(':');         // ["rf", "<bucket>", "<id>"...]
        const bucket = parts[1];
        if (out[bucket] !== undefined) {
          out[bucket].push(e.name);
        } else {
          out.other.push(e.name);
        }
      }
      return {
        counts: {
          event:  out.event.length,
          sub:    out.sub.length,
          fx:     out.fx.length,
          render: out.render.length,
          other:  out.other.length,
        },
        // Sample one name from each populated bucket so the spec runner
        // log carries a concrete `rf:` entry per bucket — useful in CI.
        samples: {
          event:  out.event[0]  || null,
          sub:    out.sub[0]    || null,
          fx:     out.fx[0]     || null,
          render: out.render[0] || null,
        },
      };
    });

    if (VERBOSE_TESTS) {
      console.log(userTimingDiagnostics(buckets));
    }

    expectTimingBucket(buckets, 'event');
    expectTimingBucket(buckets, 'sub');
    expectTimingBucket(buckets, 'fx');
    expectTimingBucket(buckets, 'render');
  },
};
