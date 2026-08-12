'use strict';
// GUARANTEE 1 — PER-REQUEST STATE ISOLATION, VIA IMMUTABLE SNAPSHOTS.
//
//     node implementation/ssr-node/test/isolation.test.cjs
//
// The guarantee has two mechanisms of different strength and this file is
// careful to keep them apart, because conflating them would overstate the
// weaker one:
//
//   THE CLONE is the guarantee. `state` crosses the thread boundary by
//   structured clone, so the isolate's copy shares no memory with the
//   caller's and none with any other request's. It does not depend on the
//   render module behaving.
//
//   THE FREEZE is a diagnostic. A strict-mode module writing to its
//   snapshot takes a TypeError; a sloppy-mode CommonJS module's write
//   fails SILENTLY. `sloppy.cjs` is here to show that second case
//   honestly — and to show that the isolation still holds under it, which
//   is the claim that actually matters.
//
// A guarantee about "another request" needs another request, so the
// interference rows use two requests with DIFFERENT state and check each
// read its own. A single-request test could not tell isolation from
// there being nothing to isolate from.

const test = require('node:test');
const assert = require('node:assert');
const { withService, collect, observed } = require('./_support.cjs');

const req = (state, extra = {}) => ({ protocol: 1, entry: 'app/root', state, ...extra });

test('the snapshot the module is handed is FROZEN, and a write to it throws', async () => {
  await withService('reference', { isolates: 1 }, async (service) => {
    const obs = observed(await collect(service, req({ ':todos': '[1 2 3]' })));
    assert.strictEqual(obs.frozen, true, 'the snapshot must be frozen');
    assert.strictEqual(obs.mutationThrew, true, 'a strict-mode write must throw');
    // …and the value it read is the one it was sent, so the freeze did not
    // simply hand it an empty object.
    assert.strictEqual(obs.readTodos, '[1 2 3]');
  });
});

test("a sloppy module's write fails silently — and still reaches nothing", async () => {
  // The control for the row above. If the suite only ever tested strict
  // modules it would be claiming a TypeError the contract cannot promise.
  await withService('sloppy', { isolates: 1 }, async (service) => {
    const obs = observed(await collect(service, req({ ':todos': 'original' })));
    assert.strictEqual(obs.frozen, true);
    assert.strictEqual(obs.threw, false, 'sloppy mode swallows the write');
    assert.strictEqual(
      obs.afterWrite,
      'original',
      'the write must have reached nothing even though nothing threw',
    );
  });
});

test('each request gets its OWN snapshot object — never one seen before', async () => {
  await withService('reference', { isolates: 1 }, async (service) => {
    const first = await collect(service, req({ ':todos': '[1]' }));
    const second = await collect(service, req({ ':todos': '[2]' }));
    assert.strictEqual(observed(first).seenBefore, false);
    assert.strictEqual(
      observed(second).seenBefore,
      false,
      'a second request handed the same object would mean the snapshot is shared',
    );
    // One isolate served both, so `seenBefore` is a reading of something:
    // the WeakSet that answers it survived between the two renders.
    assert.strictEqual(observed(first).threadId, observed(second).threadId);
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
  // Two isolates, two states, overlapping in time — the shape the
  // guarantee is actually about. Each render sleeps, so both are in
  // flight at once rather than merely adjacent.
  await withService('reference', { isolates: 2 }, async (service) => {
    const [a, b] = await Promise.all([
      collect(service, req({ ':todos': '"AAA"', ':route': '{:name :a}', ':delay': '30' })),
      collect(service, req({ ':todos': '"BBB"', ':route': '{:name :b}', ':delay': '30' })),
    ]);
    const [obsA, obsB] = [observed(a), observed(b)];
    assert.strictEqual(obsA.readTodos, '"AAA"');
    assert.strictEqual(obsA.readRoute, '{:name :a}');
    assert.strictEqual(obsB.readTodos, '"BBB"');
    assert.strictEqual(obsB.readRoute, '{:name :b}');
    assert.strictEqual(a.chunks[0].html.includes('AAA'), true);
    assert.strictEqual(b.chunks[0].html.includes('BBB'), true);
    assert.notStrictEqual(
      obsA.threadId,
      obsB.threadId,
      'the two renders must have run in different isolates for this row to mean anything',
    );
  });
});

test('a key omitted from a request is absent, not inherited from the last one', async () => {
  // The silent failure the pricing dossier names: a view reading a key the
  // projection did not carry renders as though it were nil. That is the
  // application's problem to test — but the SERVICE must at least not
  // manufacture a value by leaving the previous request's behind.
  await withService('reference', { isolates: 1 }, async (service) => {
    const first = await collect(service, req({ ':todos': '"kept"', ':route': '{:name :x}' }));
    assert.strictEqual(observed(first).readRoute, '{:name :x}');
    const second = await collect(service, req({ ':todos': '"kept"' }));
    assert.strictEqual(observed(second).readRoute, null, 'the previous route must not persist');
  });
});

test('the module boots once per isolate, not once per request', async () => {
  // Spec 006 allows exactly one reactive substrate per process, so booting
  // is a per-isolate decision. If it ran per request the isolation would
  // be real and the cost would be absurd.
  await withService('reference', { isolates: 1 }, async (service) => {
    const a = await collect(service, req({ ':todos': '[]' }));
    const b = await collect(service, req({ ':todos': '[]' }));
    assert.strictEqual(observed(a).threadId, observed(b).threadId);
    // `overlapMax` is module-level state; its survival across the two
    // renders is what shows the module was not re-required.
    assert.strictEqual(observed(a).overlapMax, 1);
    assert.strictEqual(observed(b).overlapMax, 1);
  });
});
