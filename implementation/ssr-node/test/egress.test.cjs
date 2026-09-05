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
// ## THERE ARE TWO DOORS OUT OF A RENDER, AND THIS FILE ONCE WATCHED ONE
//
// A render module leaves the isolate by RETURNING or by THROWING, and for
// a commit the sections above were the whole of this file — every one of
// them driving a module that returns. The row that read "the refusal does
// not carry the payload out through the error channel" was the closest
// thing to a guard on the second door and it drove the LEAKY fixture,
// which returns; so the error channel it checked was the one the return
// door opens, and a module that threw was never on the table. A control
// shaped so that it cannot reach the case it is named for is worse than an
// absent one, because its green is spent.
//
// Section 4 is that door. It matters more than the return door rather than
// less, for a reason worth stating plainly: RETURNING a payload is a
// module doing something unusual, while THROWING an Error built from the
// value being processed is what every renderer already does — React names
// the property that was undefined, a validation error quotes the input, a
// template error interpolates the row. So the leak on this side does not
// need a module that reaches for a second channel; it needs a module that
// has an ordinary bug. And `error.code` is the second half: it is a bare
// property on an ordinary `Error`, `statusFor` maps it to an HTTP status,
// so an application render fault could present itself as a 400 the caller
// blames itself for, a 503 a retry policy sleeps on, or a 504.
//
// ## AND THE THROWING DOOR HAS TWO SIDES, WHICH IS SECTION 5
//
// Section 4 closed the throw the SERVICE IS STANDING IN FRONT OF: the
// module throws while `worker.cjs` is inside `await renderModule.render()`,
// so its own try/catch has the stack. That is not the only way a render
// exception escapes. A throw from a callback the render SCHEDULED — a
// timer, an unhandled `.then`, an event listener — happens on a later tick
// with no `try` anywhere above it, so it is an uncaught exception in the
// worker thread; Node kills the thread and raises `'error'` on the parent's
// `Worker`. That receiver is in `isolate.cjs`, it is a different piece of
// code with its own refusal to build, and section 4's rows cannot reach it
// because their fixture throws synchronously.
//
// So the same law was being stated by two receivers and only one of them
// had been made to state it. The second put `err.message` on the refusal
// and `err.stack` in its `detail` — the module's wording, and with it every
// absolute path in the deployment's filesystem — under `isolate-lost`.
// Section 5's fixture is a MATCHED PAIR for exactly this reason: the same
// Error, built the same way, reaching the service down the two different
// paths. Before the fix the awaited arm was clean and the scheduled one
// leaked, which is what a hole in one of two receivers looks like from
// outside.
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
const {
  CODE,
  COMPLETE_FIELDS,
  MODULE_RETURN_REFUSAL,
  RENDER_THREW_REFUSAL,
  ISOLATE_LOST_REFUSAL,
  REPLACEMENT_FAILED_REFUSAL,
  isRefusalCode,
} = require('../src/protocol.cjs');
const { serve, statusFor } = require('../src/http.cjs');
const LEAKY = require('./fixtures/leaky.cjs');
const NULL_RETURN = require('./fixtures/null-return.cjs');
const THROWS_DATA = require('./fixtures/throws-data.cjs');
const THROWS_ASYNC = require('./fixtures/throws-async.cjs');

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

test('every CHUNK frame carries EXACTLY the three body-frame fields', async () => {
  // The chunk half of the roster above, and the row that makes
  // `protocol.cjs`'s `chunkFrame` a live constructor rather than a second
  // written-down copy of a shape the boundary also spells by hand
  // (rf2-6r9j.74). The worker's own message carries `t` and `id` as well,
  // so a boundary that spread it — or that grew a field on one side of the
  // pair only — arrives here as a fourth key rather than as a quietly
  // wider public frame.
  //
  // Multi-chunk on purpose: `seq` is a field the constructor is given and
  // a single-chunk response would read 0 whether it was carried or not.
  await withService('chunked', { isolates: 1 }, async (service) => {
    const parts = ['<a>', '<b>', '<c/>'];
    const { chunks } = await collect(service, {
      protocol: 1,
      entry: 'app/root',
      state: { ':bytes': JSON.stringify(parts) },
    });
    assert.strictEqual(chunks.length, parts.length, 'the fixture really did emit three');
    for (const frame of chunks) {
      assert.deepStrictEqual(Object.keys(frame).sort(), ['html', 'seq', 'type']);
    }
    assert.deepStrictEqual(chunks.map((c) => c.seq), [0, 1, 2], 'and seq crossed intact');
    assert.deepStrictEqual(chunks.map((c) => c.html), parts, 'and so did the markup');
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

test('the RETURN refusal does not carry the payload out through the error channel', async () => {
  // The subtle re-run of the same leak: a diagnostic that echoed what the
  // module tried to return would be the identical egress wearing a
  // different frame type, and it would look like helpfulness.
  //
  // SCOPE, because this row's name used to overstate it. The fixture is
  // `leaky`, which RETURNS — so what is checked here is the error channel
  // as the RETURN door opens it, and a thrown Error's `code`, `message`
  // and `detail` are a different path through the same channel that no row
  // in this file reached. Section 4 is that path; this row is now half of
  // a pair rather than the whole claim it was written as.
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

// ---------------------------------------------------------------------------
// 4. The OTHER door — a render module that THROWS
//
// See the header. Everything above drives a module that returns; these
// rows drive one that throws, which is the ordinary way a renderer fails
// and was the open half of the response law.
// ---------------------------------------------------------------------------

// One sentinel per live field of the thrown Error, each arriving as a
// separate allowlisted state key so a green scan cannot be one field's
// accident. Distinctive on purpose, for the reason `SENTINELS` gives.
const THROW_STATE = {
  ':for-code': '"rf2-c38b-code-1e4a77"',
  ':for-message': '"rf2-c38b-message-8b30d2"',
  ':for-detail': '"rf2-c38b-detail-c519f0"',
};
const THROW_SENTINELS = [
  'rf2-c38b-code-1e4a77',
  'rf2-c38b-message-8b30d2',
  'rf2-c38b-detail-c519f0',
];

const throwReq = (entry) => ({ protocol: 1, entry, state: THROW_STATE });

/**
 * Everything a caller of the in-process API can read off a refusal, as one
 * scannable frame plus the stack separately.
 *
 * `toFrame` is the wire shape, so scanning it is scanning what a transport
 * would serialise; it is fed to `scanForEgress` rather than to a second
 * hand-rolled scanner, so the control at the top of section 2 covers this
 * section too. `stack` is checked alongside because it is a string a
 * caller can reach on the `Error` even though no frame carries it — and a
 * service-owned message is what keeps it clean, since `Error`'s stack
 * opens with the message.
 */
const refusalLeaks = (err) => [
  ...scanForEgress([err.toFrame('corr-throw')], THROW_SENTINELS),
  ...THROW_SENTINELS.filter((s) => (err.stack ?? '').includes(s)).map((s) => ({
    type: 'stack',
    sentinel: s,
    in: err.stack,
  })),
];

test('CONTROL — the throwing fixture really does put all three sentinels on the Error', () => {
  // In-process, with no service in the way, and for exactly the reason the
  // leaky control exists: three absence checks below are only measurements
  // if the three values were genuinely on the Error to begin with. A
  // fixture that had drifted into throwing a bare `new Error('boom')` would
  // satisfy every assertion in this section and prove nothing at all.
  let thrown = null;
  try {
    THROWS_DATA.render({ entry: 'app/plain', state: THROW_STATE }, () => {});
  } catch (err) {
    thrown = err;
  }
  assert.ok(thrown, 'the fixture must actually throw');
  assert.ok(thrown.message.includes(THROW_SENTINELS[1]), 'message carries its sentinel');
  assert.ok(String(thrown.code).includes(THROW_SENTINELS[0]), 'code carries its sentinel');
  assert.ok(thrown.detail.echoed.includes(THROW_SENTINELS[2]), 'detail carries its sentinel');
  assert.ok(
    thrown.detail.nested.deeper.includes(THROW_SENTINELS[2]),
    'and it is NESTED, so a shallow scan would miss it',
  );

  // The three really are distinct, or one leak could pass for another.
  assert.strictEqual(new Set(THROW_SENTINELS).size, 3);
});

test('CONTROL — the spoofed codes really are members of the refusal family', () => {
  // Without this row the status-spoof row below could go vacuous in the
  // quietest possible way: rename a member of `CODE`, and the fixture's
  // hard-coded strings become codes the service has never heard of, which
  // `statusFor` maps to 500 all on its own. The row would still be green
  // and would no longer be testing a spoof.
  const members = new Set(Object.values(CODE));
  for (const [entry, code] of Object.entries(THROWS_DATA.SPOOFED_CODE)) {
    assert.ok(members.has(code), `${entry} spoofs ${code}, which is no longer a real code`);
    assert.notStrictEqual(
      statusFor(code),
      statusFor(CODE.RENDER_THREW),
      `${code} must map to a DIFFERENT status than render-threw, or there is nothing to spoof`,
    );
  }
  assert.strictEqual(Object.keys(THROWS_DATA.SPOOFED_CODE).length, 3, '400, 503 and 504');
});

test('a render that THROWS is refused with service-owned wording, carrying nothing it authored', async () => {
  await withService('throws-data', { isolates: 1 }, async (service) => {
    const err = await refusalOf(() => collect(service, throwReq('app/plain')));
    assert.ok(err, 'a throw must refuse');

    assert.strictEqual(err.code, CODE.RENDER_THREW, 'the documented code, not the module’s');
    assert.strictEqual(err.message, RENDER_THREW_REFUSAL, 'the contract owns the wording');
    assert.deepStrictEqual(
      Object.keys(err.detail).sort(),
      ['afterChunks', 'entry'],
      'the detail is service-owned: the entry the caller named, and the tear count',
    );
    assert.strictEqual(err.detail.entry, 'app/plain');
    assert.strictEqual(err.detail.afterChunks, 0, 'nothing was written, so nothing is torn');

    assert.deepStrictEqual(
      refusalLeaks(err),
      [],
      'the module’s exception reached the public refusal',
    );
  });
});

test('a THROW after emitting is still a torn response, and still carries nothing', async () => {
  // The pre-emit row above is the clean refusal; this is the other half,
  // and closing the leak must not have cost the tear. A `detail` rebuilt
  // by the service is exactly where `afterChunks` could quietly go missing.
  await withService('throws-data', { isolates: 1 }, async (service) => {
    const chunks = [];
    const err = await refusalOf(async () => {
      for await (const frame of service.renderFrames(throwReq('app/torn'))) {
        if (frame.type === 'chunk') chunks.push(frame.html);
      }
    });
    assert.deepStrictEqual(chunks, ['<p>first</p>'], 'the bytes really did leave');
    assert.strictEqual(err.code, CODE.RENDER_THREW);
    assert.strictEqual(err.message, RENDER_THREW_REFUSAL);
    assert.strictEqual(err.detail.afterChunks, 1, 'the tear is still named, with its count');
    assert.deepStrictEqual(refusalLeaks(err), []);
  });
});

test('a thrown `code` cannot choose the refusal code, in-process', async () => {
  // The taxonomy is closed and it is the SERVICE's. A module that types
  // `err.code = ':rf.ssr-node/service-saturated'` is describing its own
  // failure in this service's vocabulary; believing it turns an
  // application bug into a lie about whose fault the failure was.
  await withService('throws-data', { isolates: 1 }, async (service) => {
    for (const entry of Object.keys(THROWS_DATA.SPOOFED_CODE)) {
      const err = await refusalOf(() => collect(service, throwReq(entry)));
      assert.strictEqual(err.code, CODE.RENDER_THREW, `${entry} spoofed the refusal code`);
      assert.strictEqual(err.message, RENDER_THREW_REFUSAL);
      assert.deepStrictEqual(refusalLeaks(err), [], `${entry} leaked`);
    }
    // …and the invented-code entry, which is the same fault with no
    // plausible deniability: a code that is not in the family at all.
    const invented = await refusalOf(() => collect(service, throwReq('app/plain')));
    assert.strictEqual(invented.code, CODE.RENDER_THREW);
  });
});

test('and it cannot choose the HTTP status either — every throw is a 500', async () => {
  // The corollary over the transport, and the arm with the operational
  // teeth: `statusFor` reads the refusal code, so a spoofed 400 tells a
  // JVM host "your request was bad" about a fault that was entirely ours,
  // a 503 puts a retry policy to sleep, and a 504 blames a deadline.
  await withService('throws-data', { isolates: 1 }, async (service) => {
    const http = await serve({ service, port: 0 });
    try {
      const entries = ['app/plain', ...Object.keys(THROWS_DATA.SPOOFED_CODE)];
      for (const entry of entries) {
        const res = await post(`http://127.0.0.1:${http.port}/render`, throwReq(entry));
        assert.strictEqual(res.status, 500, `${entry} chose its own HTTP status`);
        assert.strictEqual(
          res.headers.get('x-rf-ssr-refusal'),
          CODE.RENDER_THREW,
          `${entry} chose its own refusal header`,
        );

        const headers = JSON.stringify(Object.fromEntries(res.headers.entries()));
        for (const s of THROW_SENTINELS) {
          assert.ok(!res.text.includes(s), `${entry} leaked ${s} into the JSON body`);
          assert.ok(!headers.includes(s), `${entry} leaked ${s} into a header`);
        }
        assert.ok(
          JSON.parse(res.text).message === RENDER_THREW_REFUSAL,
          'the body carries the contract’s wording',
        );
      }
    } finally {
      await http.close();
    }
  });
});

test('a streaming response torn by a throw is DESTROYED, not completed', async () => {
  // The post-emit arm over the transport. Headers are already sent, so
  // there is no status left to send and `sendRefusal` destroys the socket:
  // the caller must see a broken transfer rather than a well-formed
  // shorter page it would cache and serve. `fetch` surfaces that as a
  // rejected body read, which is the observation this row makes.
  await withService('throws-data', { isolates: 1 }, async (service) => {
    const http = await serve({ service, port: 0 });
    try {
      const res = await fetch(`http://127.0.0.1:${http.port}/render?stream=1`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(throwReq('app/torn')),
      });
      // The first chunk really did go out under a 200 — that is what makes
      // the tear a tear rather than a refusal.
      assert.strictEqual(res.status, 200);
      const read = await res.text().then(
        (text) => ({ ok: true, text }),
        (err) => ({ ok: false, err }),
      );
      assert.strictEqual(read.ok, false, 'a torn stream must not read as a complete body');
      assert.ok(
        !String(read.err).includes(THROW_SENTINELS[1]),
        'not even the transport error may carry the module’s wording',
      );
    } finally {
      await http.close();
    }
  });
});

// ---------------------------------------------------------------------------
// 5. The SECOND RECEIVER — an exception that escapes the render call
//
// See the header. Everything in section 4 throws while the service is
// inside the awaited `render()`; these rows throw from a callback the
// render scheduled, which no `try` in `worker.cjs` is standing in front of.
// Node terminates the thread and the parent's `worker.on('error')` builds
// the refusal instead — a different receiver stating the same law.
// ---------------------------------------------------------------------------

// One sentinel, from an allowlisted state key, on an Error the fixture
// reaches by two different routes. Distinctive for the reason `SENTINELS`
// gives: a value that could occur in framing or in a stack trace would
// make a zero meaningless.
const ASYNC_STATE = { ':for-uncaught': '"rf2-c38b-async-9f31c4"' };
const ASYNC_SENTINEL = 'rf2-c38b-async-9f31c4';

const asyncReq = (entry) => ({ protocol: 1, entry, state: ASYNC_STATE });

/** The same reading as `refusalLeaks`, for this section's single sentinel. */
const asyncLeaks = (err) => [
  ...scanForEgress([err.toFrame('corr-async')], [ASYNC_SENTINEL]),
  ...((err.stack ?? '').includes(ASYNC_SENTINEL)
    ? [{ type: 'stack', sentinel: ASYNC_SENTINEL, in: err.stack }]
    : []),
];

test('CONTROL — the scheduled callback really does throw the sentinel-bearing Error', () => {
  // The control this section cannot do without, and it has to be built
  // differently from section 4's: the whole point of this fixture is that
  // its throw is UNCAUGHT, so calling `render` and letting the callback run
  // would take down the test process rather than hand back an Error.
  //
  // So the callback is intercepted instead of allowed to fire.
  // `setImmediate` is swapped for the duration of the call, which captures
  // the exact function the worker thread would have run, and the row then
  // throws it on purpose and reads what came out. That is a measurement of
  // the real callback rather than of a fixture's promise about it.
  const scheduled = [];
  const realSetImmediate = globalThis.setImmediate;
  globalThis.setImmediate = (fn) => {
    scheduled.push(fn);
    return { unref() {} };
  };
  try {
    THROWS_ASYNC.render({ entry: 'app/uncaught', state: ASYNC_STATE }, () => {});
  } finally {
    globalThis.setImmediate = realSetImmediate;
  }

  assert.strictEqual(scheduled.length, 1, 'the render must schedule exactly one callback');
  let thrown = null;
  try {
    scheduled[0]();
  } catch (err) {
    thrown = err;
  }
  assert.ok(thrown, 'the scheduled callback must actually throw');
  assert.ok(thrown.message.includes(ASYNC_SENTINEL), 'and its message carries the sentinel');
  assert.strictEqual(
    thrown.code,
    THROWS_ASYNC.SPOOFED_CODE,
    'it also types a `code`, as a module with its own taxonomy would',
  );
  // Without this the status row below could go vacuous exactly the way
  // section 4's could: rename a member of `CODE` and the fixture's
  // hard-coded string stops being a spoof at all.
  assert.ok(
    new Set(Object.values(CODE)).has(THROWS_ASYNC.SPOOFED_CODE),
    'the spoofed code must still be a real member of the family',
  );
});

test('CONTROL — the AWAITED arm of the same fixture is refused by the other receiver', async () => {
  // The discriminator, and the row that makes this a section rather than a
  // repeat. `app/rejected` hands the identical Error back by rejecting the
  // promise `render` returned, so `worker.cjs` catches it and section 4's
  // door closes on it. A green here beside a green below is two receivers
  // both holding the law; a green here beside a red below — which is what
  // this file measured before the fix — is precisely one of them holding it.
  await withService('throws-async', { isolates: 1 }, async (service) => {
    const err = await refusalOf(() => collect(service, asyncReq('app/rejected')));
    assert.ok(err, 'a rejected render must refuse');
    assert.strictEqual(err.code, CODE.RENDER_THREW, 'the awaited door, and its code');
    assert.strictEqual(err.message, RENDER_THREW_REFUSAL);
    assert.deepStrictEqual(asyncLeaks(err), [], 'the awaited arm leaked');
  });
});

test('an exception that ESCAPES the render call carries nothing the module authored', async () => {
  // The case. `render` returns a promise that never settles and throws from
  // a scheduled callback, so nothing awaits the exception: the thread dies
  // and `isolate.cjs`'s `worker.on('error')` is what answers the caller.
  //
  // It answered with `err.message` and `err.stack`, which is the module's
  // own wording plus every absolute path in the deployment's filesystem,
  // published on the in-process refusal and serialised over HTTP.
  await withService('throws-async', { isolates: 1 }, async (service) => {
    const err = await refusalOf(() => collect(service, asyncReq('app/uncaught')));
    assert.ok(err, 'the render must not have succeeded');

    // The isolate really is lost — the distinction is kept, not smoothed
    // into `render-threw`. A crashed worker is not a reusable one, and the
    // code is how the pool and the operator are told so.
    assert.strictEqual(err.code, CODE.ISOLATE_LOST, 'the fault is what it is');
    assert.strictEqual(err.message, ISOLATE_LOST_REFUSAL, 'the contract owns the wording');
    assert.deepStrictEqual(
      Object.keys(err.detail).sort(),
      ['isolate', 'threadId'],
      'the detail is service-owned: which isolate died, and its thread',
    );
    assert.strictEqual(typeof err.detail.threadId, 'number');

    assert.deepStrictEqual(
      asyncLeaks(err),
      [],
      'the escaped exception reached the public refusal',
    );
    // The stack carried MORE than the sentinel, and this is that second
    // half: it names the file the render came from, which is an absolute
    // path in the deployment's filesystem. The key-list assertion above
    // already says `detail.stack` is gone; this says what its going was
    // worth, and it scans the serialised frame rather than the field, so a
    // future `detail` member that reached for a path is caught too.
    assert.ok(
      !JSON.stringify(err.toFrame()).includes('throws-async.cjs'),
      'a serialised stack names the server’s own filesystem',
    );
  });
});

test('and the OPERATOR still gets the exception, in full, on the sidecar stderr', async () => {
  // The other half of closing a diagnostic, and the reason this row is not
  // optional: before the fix the escaped exception reached NOBODY except
  // through the refusal — the worker's own `reportRenderException` never
  // runs, because the worker never caught anything. Closing the refusal
  // without opening the operator's copy would have traded a leak for a
  // silence, and "fail loudly" is a requirement rather than a preference.
  //
  // Not a new channel: `bin/serve.cjs` already writes `[rf.ssr-node] …` to
  // stderr and this is that stream under that prefix. The write happens in
  // the PARENT — which, in this suite, is this process — so the row can
  // read it directly.
  const captured = [];
  const realWrite = process.stderr.write;
  process.stderr.write = function (chunk, ...rest) {
    captured.push(String(chunk));
    return realWrite.call(this, chunk, ...rest);
  };
  try {
    await withService('throws-async', { isolates: 1 }, async (service) => {
      await refusalOf(() => collect(service, asyncReq('app/uncaught')));
    });
  } finally {
    process.stderr.write = realWrite;
  }

  const log = captured.join('');
  // The prefix is on the FIRST line only — a stack is many lines and the
  // rest of them are the stack's own — so the line filter establishes that
  // the sidecar's own channel is what spoke, and the whole capture is what
  // the content is then read from. Filtering the content by prefix would
  // discard every frame and quietly turn the stack assertion below into a
  // test of the first line.
  assert.ok(
    log.split('\n').some((line) => line.includes('[rf.ssr-node]')),
    'the operator was told nothing at all',
  );
  assert.ok(log.includes(ASYNC_SENTINEL), 'the operator copy must be the REAL exception');
  assert.ok(log.includes('throws-async.cjs'), 'and it must carry the stack, which is the point');
});

test('an escaped exception cannot choose the refusal header either', async () => {
  // The transport corollary. `isolate-lost` and `render-threw` are both
  // 500, so the status is not the spoof vector on this path — the HEADER
  // is, and so is the code a JVM host branches on. The module typed a
  // 400-mapped member of the family onto its Error; neither may move.
  await withService('throws-async', { isolates: 1 }, async (service) => {
    const http = await serve({ service, port: 0 });
    try {
      const res = await post(`http://127.0.0.1:${http.port}/render`, asyncReq('app/uncaught'));
      assert.strictEqual(res.status, 500, 'the module chose its own HTTP status');
      assert.notStrictEqual(
        statusFor(THROWS_ASYNC.SPOOFED_CODE),
        500,
        'the spoofed code must map somewhere else, or there is nothing to spoof',
      );
      assert.strictEqual(
        res.headers.get('x-rf-ssr-refusal'),
        CODE.ISOLATE_LOST,
        'the module chose its own refusal header',
      );

      const headers = JSON.stringify(Object.fromEntries(res.headers.entries()));
      assert.ok(!res.text.includes(ASYNC_SENTINEL), 'the JSON body leaked the sentinel');
      assert.ok(!headers.includes(ASYNC_SENTINEL), 'a header leaked the sentinel');
      assert.strictEqual(
        JSON.parse(res.text).message,
        ISOLATE_LOST_REFUSAL,
        'the body carries the contract wording',
      );
    } finally {
      await http.close();
    }
  });
});

test('a stream torn by an ESCAPED exception is destroyed, and still says nothing', async () => {
  // The post-emit arm, over the transport, for the second receiver. Bytes
  // already left under a 200, so there is no status left to send and the
  // socket is destroyed rather than the response completed — the caller
  // must see a broken transfer, not a well-formed shorter page it would
  // cache. Same requirement section 4 makes of the first receiver.
  await withService('throws-async', { isolates: 1 }, async (service) => {
    const http = await serve({ service, port: 0 });
    try {
      const res = await fetch(`http://127.0.0.1:${http.port}/render?stream=1`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(asyncReq('app/uncaught-torn')),
      });
      assert.strictEqual(res.status, 200, 'the first chunk really did go out');
      const read = await res.text().then(
        (text) => ({ ok: true, text }),
        (err) => ({ ok: false, err }),
      );
      assert.strictEqual(read.ok, false, 'a torn stream must not read as a complete body');
      assert.ok(
        !String(read.err).includes(ASYNC_SENTINEL),
        'not even the transport error may carry the module wording',
      );
    } finally {
      await http.close();
    }
  });
});

// ---------------------------------------------------------------------------
// 6. The THIRD RECEIVER — a REPLACEMENT isolate that cannot boot (rf2-2hmg)
//
// Sections 4 and 5 are both about a render. This one is about a BOOT, and
// the reason a boot belongs in an egress file at all is that the audience
// for a boot refusal is not fixed.
//
// `isolate.cjs` builds one refusal for every boot failure and says, in a
// comment, why it may carry the module's own `message` and `stack`: boot
// fails before the service listens, so the reader is the operator standing
// at the process they just started rather than a caller across a wire.
// That is true of `Pool.start()`. It is FALSE of `Pool._startReplacement()`
// — a replacement boots while the service is live and serving, and
// `Pool.release()` hands its failure straight to everyone queued in
// `acquire()`. Same refusal, second audience, and the comment authorising
// the wording was written about the first one.
//
// So this section is the same law as sections 4 and 5, stated by a third
// receiver, and the leak it closes is the widest of the three: the module's
// boot message, the absolute module path this deployment was pointed at,
// AND a `code` the module chose — the spoof section 4 closed for a render
// exception, still open on the boot path because a different piece of code
// builds this refusal and it does not consult `isRefusalCode`.
//
// THE HALF THE PATTERN ALSO REQUIRES. Before this, a replacement failure
// with NO waiter queued reached nobody at all: the handler's only statement
// is the loop over `waiters`, so an empty queue discarded the error and the
// pool silently shrank by an isolate. Closing the refusal without opening
// the operator's copy would have made that the only outcome, which is the
// trade PR #9278 named — a leak for a silence.
// ---------------------------------------------------------------------------

const FLAKY_BOOT = require('./fixtures/flaky-boot.cjs');
const { FAIL_FLAG, BOOT_SENTINEL, BOOT_SPOOF_CODE } = FLAKY_BOOT;

const tick = () => new Promise((resolve) => setImmediate(resolve));

/**
 * Drive one replacement-boot failure and report everything it produced.
 *
 * The shape is forced by the path. A replacement is only ever spawned by
 * `release()` seeing a DEAD isolate, and the only way to kill one from
 * outside is the deadline — so the scenario is: hang the single isolate,
 * queue a second caller behind it, arm the fixture's boot failure while
 * both are in flight, and read what the QUEUED caller is handed when the
 * pool tries to restore capacity and cannot.
 *
 * `admissionTimeoutMs` is far above the deadline on purpose: the waiter
 * must still be waiting when the replacement fails, or the row measures an
 * ordinary saturation refusal instead of the one it came for.
 */
async function replacementBootFailure() {
  const captured = [];
  const realWrite = process.stderr.write;
  process.stderr.write = function (chunk, ...rest) {
    captured.push(String(chunk));
    return realWrite.call(this, chunk, ...rest);
  };
  try {
    return await withService(
      'flaky-boot',
      { isolates: 1, admissionTimeoutMs: 5000, defaultTimeoutMs: 300, maxTimeoutMs: 5000 },
      async (service) => {
        const hung = refusalOf(() => collect(service, { protocol: 1, entry: 'app/hang' }));
        await tick();
        await tick();
        const queued = refusalOf(() => collect(service, { protocol: 1, entry: 'app/root' }));
        await tick();
        await tick();
        const statsWhileQueued = service.stats();
        // Armed only now: the isolates already running took their copy of
        // `process.env` when they were constructed, so this reaches the
        // replacement and nothing else.
        process.env[FAIL_FLAG] = '1';
        const [hungRefusal, queuedRefusal] = await Promise.all([hung, queued]);
        return {
          hungRefusal,
          queuedRefusal,
          statsWhileQueued,
          statsAfter: service.stats(),
          stderr: captured.join(''),
        };
      },
    );
  } finally {
    delete process.env[FAIL_FLAG];
    process.stderr.write = realWrite;
  }
}

test('CONTROL — the flaky fixture really does refuse to boot, with all three payloads', () => {
  // The fixture measured directly rather than trusted. A fixture that had
  // quietly stopped throwing — or that threw without the `code`, which is
  // the half the boot receiver does not check — would make every row below
  // pass while hunting nothing.
  const modulePath = require.resolve('./fixtures/flaky-boot.cjs');
  process.env[FAIL_FLAG] = '1';
  delete require.cache[modulePath];
  let thrown = null;
  try {
    require('./fixtures/flaky-boot.cjs');
  } catch (err) {
    thrown = err;
  } finally {
    delete process.env[FAIL_FLAG];
    delete require.cache[modulePath];
    require('./fixtures/flaky-boot.cjs'); // leave the cache holding the good one
  }
  assert.ok(thrown, 'the fixture must actually throw when the flag is armed');
  assert.ok(thrown.message.includes(BOOT_SENTINEL), 'and carry the sentinel on its message');
  assert.strictEqual(thrown.code, BOOT_SPOOF_CODE, 'and carry the spoofed code');
  assert.ok(isRefusalCode(BOOT_SPOOF_CODE), 'which must be a real member, or nothing is spoofed');
});

test('CONTROL — a caller really is QUEUED when the replacement is attempted', async () => {
  // The discriminator. Every assertion in this section is about what a
  // WAITER receives, so a run in which nobody was waiting — or in which the
  // pool never tried to replace anything — would be green about a path it
  // never took. Both halves are read from the service's own counters.
  const run = await replacementBootFailure();
  assert.strictEqual(run.statsWhileQueued.waiting, 1, 'a caller must be queued in acquire()');
  assert.strictEqual(run.statsWhileQueued.busy, 1, 'and the only isolate must be held by the hang');
  assert.strictEqual(run.hungRefusal.code, CODE.RENDER_TIMEOUT, 'the deadline is what kills it');
  assert.strictEqual(run.statsAfter.replacements, 1, 'and the pool must have tried to replace it');
  assert.ok(run.queuedRefusal, 'the queued caller must have been refused, not served');
});

test('a WAITING CALLER is told nothing the module or the deployment authored', async () => {
  const run = await replacementBootFailure();
  const frame = run.queuedRefusal.toFrame('corr-boot');
  const text = JSON.stringify(frame);

  assert.ok(
    !text.includes(BOOT_SENTINEL),
    'the module\'s boot wording must not cross to a caller',
  );
  assert.ok(
    !/[A-Za-z]:[\\/]|\/(?:home|srv|usr|opt)\//.test(text),
    `no absolute deployment path may cross either; got ${text}`,
  );
  assert.strictEqual(
    run.queuedRefusal.message,
    REPLACEMENT_FAILED_REFUSAL,
    'the wording is this contract\'s',
  );
  assert.strictEqual(run.queuedRefusal.code, CODE.ISOLATE_LOST, 'an isolate was lost and not replaced');
  assert.ok(isRefusalCode(frame.code), 'and the code is a member of the closed family');
  assert.strictEqual(
    statusFor(frame.code),
    500,
    'so the module cannot turn its own boot failure into a 503 a retry policy sleeps on',
  );
});

test('and the OPERATOR gets the boot failure, in full, on the sidecar stderr', async () => {
  // The other half, and not optional for the reason section 5 gives. On
  // this path the operator's position is worse than it was there: with no
  // waiter queued the handler said nothing to ANYONE, so a pool could
  // shrink to nothing in silence. The write is therefore unconditional —
  // it is not guarded on there being a waiter to have leaked to.
  const run = await replacementBootFailure();
  assert.ok(
    run.stderr.split('\n').some((line) => line.includes('[rf.ssr-node]')),
    'the operator was told nothing at all',
  );
  assert.ok(run.stderr.includes(BOOT_SENTINEL), 'the operator copy must be the REAL failure');
  assert.ok(
    run.stderr.includes('flaky-boot.cjs'),
    'and must name the module that would not load, which is the first thing to go look at',
  );
});

// ---------------------------------------------------------------------------
// 7. The FOURTH RECEIVER — a rejection that is not a `Refusal` at all
//
// `service.renderFrames` wraps the render's rejection in a last-resort arm
// for anything that did not arrive as a `Refusal`. It was reported as
// unreachable — every `isolate.render()` path was believed to reject with a
// `Refusal` — and as harmless if reached, on the ground that it could only
// carry this package's own error text. Both halves are false, and the row
// below is the measurement rather than the argument.
//
// THE ROUTE IS AN ORDINARY IN-PROCESS CALL, with no internals stubbed.
// `validatePartition` returns the CALLER'S OWN partition object rather than
// a copy, so the object the validator reads and the object `postMessage`
// structured-clones are the same live object, read twice. Anything with an
// accessor on it — a getter, a Proxy, a lazily-materialised row from a
// serializer — can therefore satisfy `typeof value === 'string'` on the
// first read and hand back something unclonable on the second.
// `postMessage` then throws `DataCloneError` synchronously inside
// `render()`'s promise executor, which is not a `Refusal` and never passed
// through a receiver that could have made it one.
//
// AND THE TEXT IT CARRIES IS THE CALLER'S. A `DataCloneError` names the
// value it choked on, so the caller's own value is interpolated into the
// message and published on the public refusal frame — the same egress
// sections 4 and 5 refuse, arriving through the one door still open.
// ---------------------------------------------------------------------------

const CLONE_SENTINEL = 'rf2-2hmg-caller-7c19ab';

/**
 * A `state` partition whose one value is a string when the validator reads
 * it and an unclonable, sentinel-bearing `Symbol` when the clone does.
 *
 * A `Symbol` rather than a function on purpose: `DataCloneError` renders a
 * function as its source text, which would carry nothing the caller chose,
 * while a symbol renders its DESCRIPTION — so this is the shape that shows
 * whether caller-authored text crosses, which is the claim being tested.
 */
function twoFacedState() {
  const state = {};
  let reads = 0;
  Object.defineProperty(state, ':route', {
    enumerable: true,
    configurable: true,
    get() {
      reads += 1;
      return reads === 1 ? '{:name :ok}' : Symbol(CLONE_SENTINEL);
    },
  });
  return { state, reads: () => reads };
}

/** Drive one such request, capturing the sidecar's stderr alongside. */
async function uncontractedRejection() {
  const captured = [];
  const realWrite = process.stderr.write;
  process.stderr.write = function (chunk, ...rest) {
    captured.push(String(chunk));
    return realWrite.call(this, chunk, ...rest);
  };
  try {
    const twoFaced = twoFacedState();
    const refusal = await withService('reference', { isolates: 1 }, (service) =>
      refusalOf(() => collect(service, { protocol: 1, entry: 'app/root', state: twoFaced.state })),
    );
    return { refusal, reads: twoFaced.reads(), stderr: captured.join('') };
  } finally {
    process.stderr.write = realWrite;
  }
}

test('CONTROL — the request really does pass validation and then fail the CLONE', async () => {
  // Without this the section could be green about a request that never got
  // past `validateRequest` — a caller-fault refusal looks nothing like the
  // one being hunted, but "no sentinel in the frame" is true of it too.
  const run = await uncontractedRejection();
  assert.strictEqual(run.reads, 2, 'the partition value must be read by the validator AND the clone');
  assert.ok(run.refusal, 'the call must be refused');
  assert.notStrictEqual(
    run.refusal.code,
    CODE.BAD_REQUEST_FIELD,
    'and refused past validation, not by it — this row is about the clone',
  );
  assert.ok(
    run.stderr.includes('DataCloneError'),
    'and the fault really is the structured clone, which is what makes it uncontracted',
  );
});

test('a rejection that is not a Refusal carries nothing the CALLER authored', async () => {
  const run = await uncontractedRejection();
  const frame = run.refusal.toFrame('corr-clone');
  assert.ok(
    !JSON.stringify(frame).includes(CLONE_SENTINEL),
    'the caller\'s own value must not be reflected back on a public refusal',
  );
  assert.strictEqual(run.refusal.message, RENDER_THREW_REFUSAL, 'the wording is this contract\'s');
  assert.ok(isRefusalCode(frame.code), 'and the code is a member of the closed family');
});

test('and the OPERATOR gets the uncontracted fault, because nothing else would', async () => {
  // A fault here is a fault in the SIDECAR rather than in the application:
  // a render rejected with something the package's own contract does not
  // describe. Nothing upstream logged it — the worker never saw it and the
  // isolate never built a refusal for it — so before this the leaked
  // wording was, once again, the only copy in existence.
  const run = await uncontractedRejection();
  assert.ok(
    run.stderr.split('\n').some((line) => line.includes('[rf.ssr-node]')),
    'the operator was told nothing at all',
  );
  assert.ok(run.stderr.includes(CLONE_SENTINEL), 'the operator copy must be the REAL fault');
});
