'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const helpers = require('../../examples/scripts/spec-helpers.cjs');

// A locator waits for its element using its own timeout. Model that wait with
// a virtual clock so a missing element never stalls this regression suite.
function clock(t) {
  let elapsed = 0;
  t.mock.method(Date, 'now', () => elapsed);
  t.mock.method(global, 'setTimeout', (callback, ms) => {
    elapsed += ms;
    queueMicrotask(callback);
  });
  return {
    elapsed: () => elapsed,
    advance: (ms) => { elapsed += ms; },
  };
}

const cases = [
  { name: 'expectTextEquals', method: 'textContent', args: ['ready'], value: ' ready ' },
  { name: 'expectTextContains', method: 'textContent', args: ['ready'], value: 'already ready' },
  { name: 'expectInputValue', method: 'inputValue', args: ['ready'], value: 'ready' },
  { name: 'expectAttribute', method: 'getAttribute', args: ['data-state', 'ready'], value: 'ready' },
];

for (const entry of cases) {
  for (const disappears of [false, true]) {
    test(`${entry.name} bounds a ${disappears ? 'disappearing' : 'missing'} element wait`, async (t) => {
      const time = clock(t);
      let calls = 0;
      const locator = {
        async [entry.method](...args) {
          calls += 1;
          if (disappears && calls === 1) {
            time.advance(40);
            return 'pending';
          }
          const options = args[entry.method === 'getAttribute' ? 1 : 0];
          // A page default larger than the assertion's budget reproduces the
          // original defect; an unbounded default is worse still.
          time.advance(options?.timeout || 30000);
          throw new Error('element missing: locator timed out');
        },
      };
      await assert.rejects(
        helpers[entry.name](locator, ...entry.args, 100),
        /element missing/,
      );
      assert.equal(time.elapsed(), 100, 'the complete assertion uses one 100ms budget');
      assert.equal(calls, disappears ? 2 : 1);
    });
  }

  test(`${entry.name} still polls until the requested value appears`, async (t) => {
    const time = clock(t);
    let calls = 0;
    const locator = {
      async [entry.method]() {
        calls += 1;
        return calls === 1 ? 'pending' : entry.value;
      },
    };
    await helpers[entry.name](locator, ...entry.args, 100);
    assert.equal(calls, 2);
    assert.equal(time.elapsed(), 50);
  });
}
