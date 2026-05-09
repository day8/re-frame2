/*
 * Counter (perf-instrumented) — browser smoke for rf2-du3i.
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

const { expectTextEquals } = require('../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'counter-perf',
  url:  '/counter-perf/',
  run:  async (page) => {
    // Make sure the app booted and the initial render landed before we
    // sample the User-Timing stream — `dispatch-sync :counter/initialise`
    // and the first reagent render must have run.
    const span = page.locator('span').first();
    await expectTextEquals(span, '5', 10000);

    // Drive a real dispatch through the +/- buttons so every bucket
    // (event handler, sub recompute, fx, render) fires at least once.
    await page.getByRole('button', { name: '+' }).click();
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
        // log carries a concrete `rf:` entry per bucket — useful in CI
        // and as the entries listed in the rf2-du3i PR body.
        samples: {
          event:  out.event[0]  || null,
          sub:    out.sub[0]    || null,
          fx:     out.fx[0]     || null,
          render: out.render[0] || null,
        },
      };
    });

    console.log('  rf2-du3i perf-on counter — User Timing entries:');
    console.log(`    rf:event   count=${buckets.counts.event}   sample=${buckets.samples.event}`);
    console.log(`    rf:sub     count=${buckets.counts.sub}     sample=${buckets.samples.sub}`);
    console.log(`    rf:fx      count=${buckets.counts.fx}      sample=${buckets.samples.fx}`);
    console.log(`    rf:render  count=${buckets.counts.render}  sample=${buckets.samples.render}`);

    if (buckets.counts.event === 0) {
      throw new Error('Expected at least one rf:event:* measure entry; got 0.');
    }
    if (buckets.counts.sub === 0) {
      throw new Error('Expected at least one rf:sub:* measure entry; got 0.');
    }
    if (buckets.counts.fx === 0) {
      throw new Error('Expected at least one rf:fx:* measure entry; got 0.');
    }
    if (buckets.counts.render === 0) {
      throw new Error('Expected at least one rf:render:* measure entry; got 0.');
    }
  },
};
