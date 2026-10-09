'use strict';
// GUARANTEE 1 — PER-REQUEST STATE ISOLATION, VIA IMMUTABLE SNAPSHOTS.
//
// THE CLONE is the guarantee: `state` crosses the thread boundary by
// structured clone, whatever the module does. THE FREEZE is a diagnostic:
// a strict-mode write throws, a sloppy-mode write fails silently — and
// `sloppy.cjs` shows the isolation holds even then. A claim about "another
// request" needs another request, so the interference rows send two with
// different state.

const test = require('node:test');
const assert = require('node:assert');
const { withService, collect, observed } = require('./_support.cjs');

const req = (state) => ({ protocol: 1, entry: 'app/root', state });

test('the snapshot the module is handed is FROZEN, and a write to it throws', async () => {
  await withService('reference', { isolates: 1 }, async (service) => {
    const { frozen, mutationThrew, readTodos } = observed(await collect(service, req({ ':todos': '[1 2 3]' })));
    // The value read is the one sent, so the freeze did not hand it an empty object.
    assert.deepStrictEqual({ frozen, mutationThrew, readTodos }, { frozen: true, mutationThrew: true, readTodos: '[1 2 3]' });
  });
});

test("a sloppy module's write fails silently — and still reaches nothing", async () => {
  await withService('sloppy', { isolates: 1 }, async (service) => {
    const { frozen, threw, afterWrite } = observed(await collect(service, req({ ':todos': 'original' })));
    assert.deepStrictEqual({ frozen, threw, afterWrite }, { frozen: true, threw: false, afterWrite: 'original' });
  });
});

test('the caller’s own object is untouched by a render', async () => {
  await withService('reference', { isolates: 1 }, async (service) => {
    const state = { ':todos': '[1]' };
    const before = JSON.stringify(state);
    await collect(service, req(state));
    assert.strictEqual(JSON.stringify(state), before);
    assert.strictEqual(Object.isFrozen(state), false, 'the SERVICE must not freeze the caller’s object');
  });
});

test('two concurrent requests do not leak into one another, in either direction', async () => {
  // Two isolates, and each render sleeps, so both are in flight at once.
  await withService('reference', { isolates: 2 }, async (service) => {
    const [a, b] = (
      await Promise.all([
        collect(service, req({ ':todos': '"AAA"', ':route': '{:name :a}', ':delay': '30' })),
        collect(service, req({ ':todos': '"BBB"', ':route': '{:name :b}', ':delay': '30' })),
      ])
    ).map(observed);
    assert.notStrictEqual(a.threadId, b.threadId, 'the two renders must have run in different isolates');
    assert.deepStrictEqual(
      [a, b].map(({ readTodos, readRoute }) => [readTodos, readRoute]),
      [
        ['"AAA"', '{:name :a}'],
        ['"BBB"', '{:name :b}'],
      ],
    );
  });
});

test('a key omitted from a request is absent, not inherited from the last one', async () => {
  await withService('reference', { isolates: 1 }, async (service) => {
    const first = await collect(service, req({ ':todos': '"kept"', ':route': '{:name :x}' }));
    assert.strictEqual(observed(first).readRoute, '{:name :x}');
    const second = await collect(service, req({ ':todos': '"kept"' }));
    assert.strictEqual(observed(second).readRoute, null, 'the previous route must not persist');
  });
});

test('the module boots once per isolate, not once per request', async () => {
  // The fixture counts boots on the isolate's own global, which a
  // per-request re-require would not reset.
  await withService('reference', { isolates: 1 }, async (service) => {
    const a = observed(await collect(service, req({ ':todos': '[]' })));
    const b = observed(await collect(service, req({ ':todos': '[]' })));
    assert.strictEqual(a.threadId, b.threadId, 'one isolate served both');
    assert.strictEqual(a.boots, 1, 'the isolate must have booted the module exactly once');
    assert.strictEqual(b.boots, 1, 'a second boot by the time of the second render is a per-request boot');
  });
});
