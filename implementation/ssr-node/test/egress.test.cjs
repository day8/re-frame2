'use strict';
// THE EGRESS CONTROL — application data cannot cross outside body markup.
//
//     node implementation/ssr-node/test/egress.test.cjs
//
// The package's stated topology is *Node returns the body markup, and
// nothing else*, and until this file existed that was a paragraph rather
// than a property. `worker.cjs` accepted an arbitrary `out.meta` from the
// application's render module, `isolate.cjs` carried it, and
// `service.renderFrames()` published it on the public `complete` frame —
// an unallowlisted, application-controlled channel at precisely the point
// the contract says there is none. The fixtures in this directory were its
// demonstration: `readTodos` and `readRoute` are application state, and
// they were crossing.
//
// HTTP happened not to serialise it, which is where the finding gets its
// teeth. "The transport drops it" is a fact about `http.cjs` and not a
// guarantee about this package: the protocol is documented as
// transport-independent, `renderFrames()`/`renderToString()` are the
// in-process API a JVM host embedding Node would use, and a socket or
// pipe adapter written tomorrow would carry the field in full. So the
// property has to be checked where it is actually true — on the FRAMES —
// and this file checks the transport afterwards as a corollary rather
// than as the evidence.
//
// ## WHAT IS BEING CLAIMED, EXACTLY
//
// Not "no data crosses" — chunks are data and that is the whole job.
// The claim is that on the response leg:
//
//   1. the `complete` frame's fields are EXACTLY `COMPLETE_FIELDS`, every
//      one of which is a fact this service produced about the crossing;
//   2. nothing the render module authored appears anywhere in the frame
//      sequence outside `chunk.html`; and
//   3. a module that reaches for a second channel is REFUSED, not quietly
//      dropped — and the refusal does not carry the payload either, which
//      is the failure a diagnostic-shaped leak would take.
//
// Claim 3 is about the ACCEPTED SET as much as about the refusal, and the
// set is exactly `{ undefined }`. The door shipped reading
// `out !== undefined && out !== null`, so `return null` — a value someone
// typed, and the likeliest deliberate return a render module has — went
// through as a clean success. A guarantee one value short is fail-closed
// except, so the null rows below are ordinary members of this section
// rather than an appendix to it.
//
// ## EVERY ROW HAS A CONTROL, AND THE CONTROLS ARE ORDINARY ROWS
//
// The suite-wide discipline (see the package README) applies here with
// particular force, because an absence check is the easiest kind of test
// to pass by accident: a scanner with a typo'd field name, a sentinel that
// was never actually planted, or a fixture that quietly stopped producing
// the thing being hunted would all read green. So `scanForEgress` is
// exercised against a doctored frame set that DOES leak and is required to
// find it, the leaky fixture is driven in-process and required to really
// return its payload, and the roster check is shown rejecting an added
// field before it is trusted accepting the real one.

const test = require('node:test');
const assert = require('node:assert');

const { withService, collect, refusalOf, post } = require('./_support.cjs');
const { CODE, COMPLETE_FIELDS, MODULE_RETURN_REFUSAL } = require('../src/protocol.cjs');
const { serve } = require('../src/http.cjs');
const LEAKY = require('./fixtures/leaky.cjs');
const NULL_RETURN = require('./fixtures/null-return.cjs');

// ---------------------------------------------------------------------------
// The two checkers. Both are ordinary functions so a control can feed them
// a fault by hand — a checker reachable only through a live service is a
// checker nobody can prove works.
// ---------------------------------------------------------------------------

/**
 * The fields on a `complete` frame that are not in the roster, and the
 * roster fields that are missing. Both directions: a frame that quietly
 * LOST `buildId` is a different bug, but it is still one this row should
 * see rather than a green tick.
 */
function rosterDrift(frame) {
  const got = Object.keys(frame);
  return {
    extra: got.filter((k) => !COMPLETE_FIELDS.includes(k)),
    // `requestId` is optional by contract — present only when the caller
    // sent one — so its absence is not drift.
    missing: COMPLETE_FIELDS.filter((k) => k !== 'requestId' && !got.includes(k)),
  };
}

/**
 * Every place a sentinel appears in a frame sequence OUTSIDE body markup.
 *
 * Chunks are stripped of `html` rather than skipped whole, because a chunk
 * frame is a plausible place for a future field to sprout and skipping the
 * frame would make this scanner blind to exactly that.
 */
function scanForEgress(frames, sentinels) {
  const findings = [];
  for (const frame of frames) {
    const rest = { ...frame };
    delete rest.html;
    const text = JSON.stringify(rest);
    for (const s of sentinels) {
      if (text.includes(s)) findings.push({ type: frame.type, sentinel: s, in: text });
    }
  }
  return findings;
}

// The state one render is given. Distinctive on purpose: a sentinel that
// could plausibly occur in framing or in a stack trace would make a zero
// meaningless.
const STATE = {
  ':todos': '"rf2-hic-056-todos-4b19ae"',
  ':route': '{:name :rf2-hic-056-route-7c02fd}',
};
const ARGS = '{:page "rf2-hic-056-args-51d8b0"}';
const SENTINELS = [
  STATE[':todos'].replace(/"/g, ''),
  'rf2-hic-056-route-7c02fd',
  'rf2-hic-056-args-51d8b0',
];

const req = (extra = {}) => ({
  protocol: 1,
  entry: 'app/root',
  state: STATE,
  args: ARGS,
  ...extra,
});

// ---------------------------------------------------------------------------
// 1. The roster
// ---------------------------------------------------------------------------

test('CONTROL — the roster check rejects an added field and a missing one', () => {
  // Before any green roster row is believed. A checker that returned an
  // empty list unconditionally would satisfy every row below it.
  const honest = { type: 'complete', chunks: 1, renderMs: 0.4, buildId: 'b' };
  assert.deepStrictEqual(rosterDrift(honest), { extra: [], missing: [] });

  const leaked = { ...honest, meta: { readTodos: 'x' } };
  assert.deepStrictEqual(rosterDrift(leaked).extra, ['meta'], 'an added field must be seen');

  const truncated = { type: 'complete', chunks: 1, renderMs: 0.4 };
  assert.deepStrictEqual(rosterDrift(truncated).missing, ['buildId']);
});

test('the complete frame carries EXACTLY the service-owned roster', async () => {
  await withService('reference', { isolates: 1 }, async (service) => {
    const withoutId = await collect(service, req());
    assert.deepStrictEqual(rosterDrift(withoutId.complete), { extra: [], missing: [] });
    assert.deepStrictEqual(Object.keys(withoutId.complete).sort(), [
      'buildId',
      'chunks',
      'renderMs',
      'type',
    ]);

    const withId = await collect(service, req({ requestId: 'corr-1' }));
    assert.deepStrictEqual(rosterDrift(withId.complete), { extra: [], missing: [] });
    assert.strictEqual(withId.complete.requestId, 'corr-1', 'the caller’s token is echoed');
  });
});

test('renderToString returns the body and the roster, and nothing besides', async () => {
  // The other public surface, and the one an embedding JVM host would
  // reach for first. It spreads the complete frame, so a field that got
  // onto the frame would arrive here too.
  await withService('reference', { isolates: 1 }, async (service) => {
    const out = await service.renderToString(req());
    assert.deepStrictEqual(Object.keys(out).sort(), [
      'buildId',
      'chunks',
      'html',
      'renderMs',
      'type',
    ]);
    assert.ok(out.html.includes('<div data-entry="app/root"'), 'the body really did render');
  });
});

// ---------------------------------------------------------------------------
// 2. The scan
// ---------------------------------------------------------------------------

test('CONTROL — the scan finds a planted leak, in each of its two shapes', () => {
  // Same function, same sentinels, a frame set doctored to carry them.
  // Without this row a zero below would be evidence of nothing.
  const onComplete = [
    { type: 'chunk', seq: 0, html: `<p>${SENTINELS[0]}</p>` },
    { type: 'complete', chunks: 1, renderMs: 1, buildId: 'b', meta: { readTodos: SENTINELS[0] } },
  ];
  assert.strictEqual(scanForEgress(onComplete, SENTINELS).length, 1, 'a leak on the terminal frame');

  const onChunk = [
    { type: 'chunk', seq: 0, html: '<p>ok</p>', note: SENTINELS[2] },
    { type: 'complete', chunks: 1, renderMs: 1, buildId: 'b' },
  ];
  assert.strictEqual(scanForEgress(onChunk, SENTINELS).length, 1, 'a leak beside body markup');

  // …and the scan is not simply always positive: the same frames with the
  // sentinel only inside `html` are clean.
  const clean = [
    { type: 'chunk', seq: 0, html: `<p>${SENTINELS[0]}${SENTINELS[1]}${SENTINELS[2]}</p>` },
    { type: 'complete', chunks: 1, renderMs: 1, buildId: 'b' },
  ];
  assert.deepStrictEqual(scanForEgress(clean, SENTINELS), []);
});

test('no value the render module handled crosses outside body markup', async () => {
  await withService('reference', { isolates: 1 }, async (service) => {
    const frames = [];
    for await (const frame of service.renderFrames(req({ requestId: 'corr-2' }))) {
      frames.push(frame);
    }

    // The sentinels must be REACHABLE, or the scan is looking for
    // something the render never had. The reference module renders its
    // todos into the body, so the first one is in the markup by
    // construction; the other two it merely observed.
    const body = frames.filter((f) => f.type === 'chunk').map((f) => f.html).join('');
    assert.ok(body.includes(SENTINELS[0]), 'the module did read and render the state');

    assert.deepStrictEqual(
      scanForEgress(frames, SENTINELS),
      [],
      'application data reached a public frame outside body markup',
    );
  });
});

test('the HTTP transport carries none of it either — corollary, not evidence', async () => {
  // Deliberately last of the three, and deliberately framed as a
  // corollary: this transport dropping a field is what let the leak ship
  // unnoticed, so a green row HERE has never been the property. It is
  // still worth having, because headers are their own egress surface.
  await withService('reference', { isolates: 1 }, async (service) => {
    const http = await serve({ service, port: 0 });
    try {
      const res = await post(`http://127.0.0.1:${http.port}/render`, req({ requestId: 'corr-3' }));
      assert.strictEqual(res.status, 200);
      assert.ok(res.text.includes(SENTINELS[0]), 'the body is the channel and it is carrying');

      const headers = JSON.stringify(Object.fromEntries(res.headers.entries()));
      for (const s of SENTINELS) {
        assert.ok(!headers.includes(s), `header carried ${s}`);
      }
    } finally {
      await http.close();
    }
  });
});

// ---------------------------------------------------------------------------
// 3. The refusal
// ---------------------------------------------------------------------------

test('CONTROL — the leaky fixture really does return a payload', () => {
  // In-process, with no service in the way. This is what makes the
  // refusal below a measurement: a fixture that had quietly stopped
  // returning anything would satisfy every assertion in the next row.
  const emitted = [];
  const out = LEAKY.render({ entry: 'app/root', state: { ':todos': '[1]' } }, (h) => emitted.push(h));
  assert.strictEqual(emitted.length, 1, 'it emits body markup like any other module');
  assert.strictEqual(out.meta.secret, LEAKY.SECRET);
  assert.strictEqual(out.meta.readTodos, '[1]', 'and it reads application state into the payload');
});

test('a render module that returns a value is REFUSED, not silently dropped', async () => {
  await withService('leaky', { isolates: 1 }, async (service) => {
    const err = await refusalOf(() => collect(service, { protocol: 1, entry: 'app/root', state: { ':todos': '[1]' } }));
    assert.ok(err, 'the render must not have succeeded');
    assert.strictEqual(err.code, CODE.RENDER_THREW);
    assert.strictEqual(err.message, MODULE_RETURN_REFUSAL, 'the contract owns the wording');

    // The tear is named. The module emitted before it returned, so bytes
    // really did leave and the transport must not present them as a page.
    assert.strictEqual(err.detail.afterChunks, 1);
    assert.strictEqual(err.detail.returned, '[object Object]', 'the SHAPE, for a diagnosis');
  });
});

test('the refusal does not carry the payload out through the error channel', async () => {
  // The subtle re-run of the same leak: a diagnostic that echoed what the
  // module tried to return would be the identical egress wearing a
  // different frame type, and it would look like helpfulness.
  await withService('leaky', { isolates: 1 }, async (service) => {
    const err = await refusalOf(() =>
      collect(service, { protocol: 1, entry: 'app/root', state: { ':todos': '"secret-state-3ab1"' } }),
    );
    const text = `${err.message}${JSON.stringify(err.detail)}${err.stack ?? ''}`;
    assert.ok(!text.includes(LEAKY.SECRET), 'the refusal echoed the module’s own value');
    assert.ok(!text.includes('secret-state-3ab1'), 'the refusal echoed the request state');
  });
});

test('CONTROL — the null fixture really does return null, and not nothing', () => {
  // In-process, with no service in the way, and for the same reason the
  // leaky control exists: the row below is only a measurement if the two
  // values it has to tell apart are genuinely both on the table. `null`
  // and `undefined` are `==`, so a fixture that had drifted into falling
  // off its end would look identical from the outside — and would turn
  // the refusal row into a test of nothing.
  const emitted = [];
  const out = NULL_RETURN.render({ entry: 'app/root', state: { ':todos': '[1]' } }, (h) =>
    emitted.push(h),
  );
  assert.strictEqual(emitted.length, 1, 'it emits body markup like any other module');
  assert.strictEqual(out, null, 'the return really is null…');
  assert.notStrictEqual(out, undefined, '…and null is not undefined, which is the whole distinction');
});

test('a module that returns null is REFUSED too — `undefined` is the whole accepted set', async () => {
  // The door read `out !== undefined && out !== null` for one commit, so
  // this exact module emitted, returned, and was reported as a clean
  // success. `undefined` is what falling off the end produces and is
  // therefore what ABSENCE looks like; `null` is a value someone typed,
  // and `return null` is the spelling a render module reaches for to mean
  // "nothing to say". A guarantee that admits one deliberate value is not
  // fail-closed, and that one value sat on the likeliest path of all.
  //
  // Same code, same wording: reaching for a second channel is one offence
  // and it keeps one refusal.
  await withService('null-return', { isolates: 1 }, async (service) => {
    const err = await refusalOf(() =>
      collect(service, { protocol: 1, entry: 'app/root', state: { ':todos': '[1]' } }),
    );
    assert.ok(err, 'returning null must not pass as a clean success');
    assert.strictEqual(err.code, CODE.RENDER_THREW, 'the existing refusal, not a new member');
    assert.strictEqual(err.message, MODULE_RETURN_REFUSAL, 'the contract owns the wording');
    assert.strictEqual(err.detail.afterChunks, 1, 'it emitted first, so the response is torn');
    assert.strictEqual(err.detail.returned, '[object Null]', 'the SHAPE, for a diagnosis');
  });
});

test('a well-behaved module returns nothing, and the service is fine with that', async () => {
  // The positive half of the door, and — since the row above closed the
  // null gap — the ONLY half: `undefined` is the contract's answer, it is
  // now the entire accepted set, and it must not be coerced into an empty
  // object that then trips the very check above.
  await withService('reference', { isolates: 1 }, async (service) => {
    const { chunks, complete } = await collect(service, req());
    assert.strictEqual(chunks.length, 1);
    assert.strictEqual(complete.type, 'complete');
  });
});
