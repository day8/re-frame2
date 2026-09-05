'use strict';
// GUARANTEE 2 — THE ALLOWLISTED REQUEST, FAIL-CLOSED.
//
//     node implementation/ssr-node/test/protocol.test.cjs
//
// Most of this runs against `validateRequest` directly rather than through
// a booted service, and that is a choice worth defending: a fail-closed
// contract is a claim about EVERY input, and the cheapest honest way to
// make that claim is to enumerate inputs. The service-level rows at the
// bottom then prove the two things a pure function cannot — that the
// validator really is the door (a refusal reaches a caller with no chunk
// emitted) and that it runs before an isolate is ever acquired.
//
// Each guard has a CONTROL beside it: the same request minus the fault
// must pass. A refusal that would have fired for the wrong reason is a
// guard that has not been shown to work.

const test = require('node:test');
const assert = require('node:assert');
const { withService, collect, observed, refusalOf } = require('./_support.cjs');
const {
  CODE,
  REQUEST_FIELDS,
  PARTITIONS,
  REFUSED_FIELDS,
  Refusal,
  validateRequest,
  validateModule,
} = require('../src/protocol.cjs');

const TABLES = {
  buildId: 'reference-build-1',
  entries: {
    'app/root': {
      stateAllowlist: [':todos', ':route'],
      runtimeAllowlist: [':rf.runtime/routing', ':rf.runtime/machines'],
    },
    'app/other': { stateAllowlist: [':route'], runtimeAllowlist: [] },
  },
};

const OK = () => ({
  protocol: 1,
  entry: 'app/root',
  state: { ':todos': '[]' },
  runtime: { ':rf.runtime/routing': '{:current {:route-id :home}}' },
});

/** The code a request refused with, or null if it validated. */
function codeOf(req, limits) {
  try {
    validateRequest(req, TABLES, limits);
    return null;
  } catch (err) {
    assert.ok(err instanceof Refusal, `expected a Refusal, got ${err}`);
    return err.code;
  }
}

// ---------------------------------------------------------------------------
// The control. Everything below is this request plus one fault.
// ---------------------------------------------------------------------------

test('the control request validates', () => {
  const out = validateRequest(OK(), TABLES);
  assert.strictEqual(out.entry, 'app/root');
  assert.deepStrictEqual(out.state, { ':todos': '[]' });
  assert.deepStrictEqual(out.runtime, { ':rf.runtime/routing': '{:current {:route-id :home}}' });
  assert.strictEqual(out.timeoutMs, 1000, 'the service default applies when none is asked for');
});

test('the partition table names both partitions, and nothing else', () => {
  assert.deepStrictEqual(
    PARTITIONS.map((p) => [p.field, p.allowlist]),
    [
      ['state', 'stateAllowlist'],
      ['runtime', 'runtimeAllowlist'],
    ],
  );
});

// ---------------------------------------------------------------------------
// The field allowlist IS the contract
// ---------------------------------------------------------------------------

test('a field the contract does not name is refused, not ignored', () => {
  assert.strictEqual(codeOf({ ...OK(), nonsense: 1 }), CODE.UNKNOWN_REQUEST_FIELD);
});

test('every field the contract DOES name is accepted — the list is not vacuous', () => {
  const full = {
    protocol: 1,
    entry: 'app/root',
    state: { ':route': '{:name :home}' },
    runtime: { ':rf.runtime/machines': '{:snapshots {}}' },
    args: '{:page 3}',
    buildId: 'reference-build-1',
    timeoutMs: 250,
    requestId: 'r-1',
  };
  assert.deepStrictEqual(
    Object.keys(full).sort(),
    [...REQUEST_FIELDS].sort(),
    'this row must exercise every field, or the allowlist has an untested member',
  );
  assert.strictEqual(codeOf(full), null);
});

test('`initialEvents` is refused, and the refusal says why', () => {
  const err = refuseOf({ ...OK(), initialEvents: [[':boot']] });
  assert.strictEqual(err.code, CODE.UNKNOWN_REQUEST_FIELD);
  assert.match(err.message, /host fork/, 'the message must teach, not merely decline');
  assert.strictEqual(err.detail.refusedOnPurpose, true);
});

test('`payloadPolicy` is refused — the payload is built on the JVM', () => {
  const err = refuseOf({ ...OK(), payloadPolicy: [':todos'] });
  assert.strictEqual(err.code, CODE.UNKNOWN_REQUEST_FIELD);
  assert.match(err.message, /body markup and nothing else/);
});

test('every deliberately-refused field carries its reason', () => {
  for (const field of Object.keys(REFUSED_FIELDS)) {
    const err = refuseOf({ ...OK(), [field]: 'x' });
    assert.strictEqual(err.code, CODE.UNKNOWN_REQUEST_FIELD, field);
    assert.strictEqual(err.detail.refusedOnPurpose, true, field);
    assert.ok(err.message.includes(REFUSED_FIELDS[field]), `${field} lost its explanation`);
  }
});

function refuseOf(req) {
  try {
    validateRequest(req, TABLES);
    assert.fail('expected a refusal');
  } catch (err) {
    assert.ok(err instanceof Refusal);
    return err;
  }
}

// ---------------------------------------------------------------------------
// Shape
// ---------------------------------------------------------------------------

test('a non-object request is refused', () => {
  assert.strictEqual(codeOf('a string'), CODE.MALFORMED_REQUEST);
  assert.strictEqual(codeOf(null), CODE.MALFORMED_REQUEST);
  assert.strictEqual(codeOf([1, 2]), CODE.MALFORMED_REQUEST);
});

test('the protocol version is checked, in both directions', () => {
  assert.strictEqual(codeOf({ ...OK(), protocol: 2 }), CODE.PROTOCOL_VERSION);
  const noVersion = OK();
  delete noVersion.protocol;
  assert.strictEqual(codeOf(noVersion), CODE.PROTOCOL_VERSION);
});

test('the typed fields are typed', () => {
  assert.strictEqual(codeOf({ ...OK(), entry: '' }), CODE.BAD_REQUEST_FIELD);
  assert.strictEqual(codeOf({ ...OK(), entry: 42 }), CODE.BAD_REQUEST_FIELD);
  assert.strictEqual(codeOf({ ...OK(), requestId: 7 }), CODE.BAD_REQUEST_FIELD);
  assert.strictEqual(codeOf({ ...OK(), buildId: 7 }), CODE.BAD_REQUEST_FIELD);
  assert.strictEqual(codeOf({ ...OK(), timeoutMs: 0 }), CODE.BAD_REQUEST_FIELD);
  assert.strictEqual(codeOf({ ...OK(), timeoutMs: -1 }), CODE.BAD_REQUEST_FIELD);
  assert.strictEqual(codeOf({ ...OK(), timeoutMs: Infinity }), CODE.BAD_REQUEST_FIELD);
});

test('`args` must be EDN TEXT — the service never decodes application data', () => {
  assert.strictEqual(codeOf({ ...OK(), args: { page: 3 } }), CODE.BAD_REQUEST_FIELD);
  assert.strictEqual(codeOf({ ...OK(), args: '{:page 3}' }), null);
});

// ---------------------------------------------------------------------------
// The entry table — the per-request half of the skew detector
// ---------------------------------------------------------------------------

test('an entry the bundle does not carry is refused per request', () => {
  const err = refuseOf({ ...OK(), entry: 'app/ghost' });
  assert.strictEqual(err.code, CODE.UNKNOWN_ENTRY);
  assert.deepStrictEqual(err.detail.known, ['app/root', 'app/other']);
});

test('build identity: a caller expecting another build is refused', () => {
  assert.strictEqual(codeOf({ ...OK(), buildId: 'some-other-build' }), CODE.BUILD_IDENTITY_MISMATCH);
  assert.strictEqual(codeOf({ ...OK(), buildId: 'reference-build-1' }), null);
});

// ---------------------------------------------------------------------------
// The render-visibility allowlist
// ---------------------------------------------------------------------------

test('a state key the entry does not declare is refused', () => {
  const err = refuseOf({ ...OK(), state: { ':todos': '[]', ':secrets': '{:token "abc"}' } });
  assert.strictEqual(err.code, CODE.STATE_KEY_NOT_ALLOWED);
  assert.strictEqual(err.detail.key, ':secrets');
});

test('the allowlist belongs to the ENTRY, so it is narrower for a narrower entry', () => {
  // `:todos` is fine for app/root and refused for app/other. Same request
  // shape, same key, different entry — which is what "the caller cannot
  // widen its own allowance" means in practice.
  assert.strictEqual(codeOf({ protocol: 1, entry: 'app/root', state: { ':todos': '[]' } }), null);
  assert.strictEqual(
    codeOf({ protocol: 1, entry: 'app/other', state: { ':todos': '[]' } }),
    CODE.STATE_KEY_NOT_ALLOWED,
  );
});

test('state keys must be top-level app-db keys, and values must be EDN text', () => {
  assert.strictEqual(codeOf({ ...OK(), state: { todos: '[]' } }), CODE.BAD_REQUEST_FIELD);
  assert.strictEqual(codeOf({ ...OK(), state: { ':a b': '[]' } }), CODE.BAD_REQUEST_FIELD);
  assert.strictEqual(codeOf({ ...OK(), state: { ':todos': ['a'] } }), CODE.BAD_REQUEST_FIELD);
  assert.strictEqual(codeOf({ ...OK(), state: [] }), CODE.BAD_REQUEST_FIELD);
});

// ---------------------------------------------------------------------------
// The runtime partition — the same door, the same posture
// ---------------------------------------------------------------------------

test('a runtime key the entry does not declare is refused, and the refusal names the partition', () => {
  const err = refuseOf({ ...OK(), runtime: { ':rf.runtime/resources': '{}' } });
  assert.strictEqual(err.code, CODE.STATE_KEY_NOT_ALLOWED);
  assert.strictEqual(err.detail.field, 'runtime');
  assert.strictEqual(err.detail.key, ':rf.runtime/resources');
  assert.deepStrictEqual(err.detail.allowed, [':rf.runtime/routing', ':rf.runtime/machines']);
  // ...and the app-db refusal names ITS partition, so the two cannot be confused.
  assert.strictEqual(refuseOf({ ...OK(), state: { ':secrets': '{}' } }).detail.field, 'state');
});

test('the runtime allowlist belongs to the ENTRY too - an empty list reads nothing', () => {
  const req = { protocol: 1, entry: 'app/other', state: { ':route': '{}' } };
  assert.strictEqual(codeOf(req), null);
  assert.strictEqual(
    codeOf({ ...req, runtime: { ':rf.runtime/routing': '{}' } }),
    CODE.STATE_KEY_NOT_ALLOWED,
    'app/other declared [] - a decision, and the caller cannot widen it',
  );
});

test('runtime keys must be top-level runtime-db keys, values EDN text, and runtime an object', () => {
  assert.strictEqual(codeOf({ ...OK(), runtime: { routing: '{}' } }), CODE.BAD_REQUEST_FIELD);
  assert.strictEqual(codeOf({ ...OK(), runtime: { ':rf.runtime/routing': {} } }), CODE.BAD_REQUEST_FIELD);
  assert.strictEqual(codeOf({ ...OK(), runtime: '{}' }), CODE.BAD_REQUEST_FIELD);
  assert.strictEqual(refuseOf({ ...OK(), runtime: [] }).detail.field, 'runtime');
});

test('an absent runtime partition validates as an empty one - the field is optional, like state', () => {
  const req = OK();
  delete req.runtime;
  assert.deepStrictEqual(validateRequest(req, TABLES).runtime, {});
  delete req.state;
  assert.deepStrictEqual(validateRequest(req, TABLES).state, {});
});

test('the byte ceiling is ONE ceiling over both partitions', () => {
  const state = { ':todos': '"' + 'a'.repeat(40) + '"' };
  const runtime = { ':rf.runtime/routing': '"' + 'b'.repeat(40) + '"' };
  const bytesOf = (o) =>
    Object.entries(o).reduce((n, [k, v]) => n + Buffer.byteLength(k) + Buffer.byteLength(v), 0);
  const each = Math.max(bytesOf(state), bytesOf(runtime));
  const ceiling = each + 10; // room for either alone, not for both
  assert.ok(bytesOf(state) + bytesOf(runtime) > ceiling, 'the fixture must exceed the ceiling only together');
  const base = { protocol: 1, entry: 'app/root' };
  assert.strictEqual(codeOf({ ...base, state }, { maxRequestBytes: ceiling }), null);
  assert.strictEqual(codeOf({ ...base, runtime }, { maxRequestBytes: ceiling }), null);
  assert.strictEqual(codeOf({ ...base, state, runtime }, { maxRequestBytes: ceiling }), CODE.REQUEST_TOO_LARGE);
});

test('state is bounded, and the ceiling is measured in BYTES', () => {
  // One em dash is one code unit and three bytes. A ceiling that counted
  // code units would admit this; the byte accounting refuses it. Written
  // as an escape so an encoding-normalising editor cannot quietly ASCII-fy
  // the input and leave this row green over something that proves nothing.
  const value = `"${'\u005cu2014'.repeat(40)}"`;
  const bytes = Buffer.byteLength(':todos', 'utf8') + Buffer.byteLength(value, 'utf8');
  assert.ok(bytes > value.length, 'the fixture must be non-ASCII or it proves nothing');
  // No runtime partition on this request: the ceiling is measured to the
  // byte, and the shared ceiling would otherwise count runtime's bytes too.
  const stateOnly = { protocol: 1, entry: 'app/root', state: { ':todos': value } };
  assert.strictEqual(codeOf(stateOnly, { maxRequestBytes: bytes - 1 }), CODE.REQUEST_TOO_LARGE);
  assert.strictEqual(codeOf(stateOnly, { maxRequestBytes: bytes }), null);
});

test('a deadline over the service ceiling is clamped rather than refused', () => {
  const out = validateRequest({ ...OK(), timeoutMs: 60000 }, TABLES, { maxTimeoutMs: 750 });
  assert.strictEqual(out.timeoutMs, 750);
});

// ---------------------------------------------------------------------------
// THE NORMALIZED REQUEST IS A SNAPSHOT (rf2-ey07)
//
// Everything above asks whether a bad request is refused. This asks the
// question one step later, and it is a different question: of the request
// that PASSED, is what comes out the thing that was checked?
//
// It has to be, because the returned object is what `isolate.render` hands
// to `postMessage`, and a structured clone is a SECOND READ of every value
// in it. A field backed by an accessor — a getter, a Proxy, a lazily
// materialised row out of a serializer — can be a well-formed string when
// the validator reads it and something else entirely when the clone does.
// A validator that returns the caller's own object has therefore checked a
// value the wire will never see, and the fail-closed guarantee above is
// about that unchecked second read rather than about the request.
//
// THE READ COUNT IS THE DISCRIMINATOR, and it is deliberately a fact about
// the tree rather than about the fix: a witness that merely passes a bad
// value proves nothing about a time-of-check/time-of-use gap, because a
// value that is bad on BOTH reads is refused by the ordinary type checks
// thirty lines up. Only a value that CHANGES between reads separates the
// two, and only a read count can see it change.
// ---------------------------------------------------------------------------

/**
 * Install a property on `host` that is `honestValue` for its first
 * `honestReads` reads and an unclonable `Symbol` on every read after that.
 * Returns the read counter.
 *
 * A `Symbol` rather than a function or a big object because it is the
 * shape that cannot be smuggled past anything: no coercion turns one into
 * a string by accident, `typeof` names it, and a structured clone refuses
 * it outright.
 */
function twoFaced(host, key, honestValue, honestReads = 1) {
  let reads = 0;
  Object.defineProperty(host, key, {
    enumerable: true,
    configurable: true,
    get() {
      reads += 1;
      return reads <= honestReads ? honestValue : Symbol('rf2-ey07-second-read');
    },
  });
  return () => reads;
}

test('a partition value is CAPTURED, so the request carries what was validated', () => {
  const state = {};
  const reads = twoFaced(state, ':route', '{:name :ok}');
  const out = validateRequest({ protocol: 1, entry: 'app/root', state }, TABLES);

  assert.notStrictEqual(
    out.state,
    state,
    "the normalized request must not alias the caller's own partition object",
  );
  assert.strictEqual(
    out.state[':route'],
    '{:name :ok}',
    'it must carry the value the validator checked, not a fresh read of the accessor behind it',
  );
  assert.strictEqual(
    reads(),
    1,
    "and reading the normalized request must not reach back into the caller's object",
  );
});

test('`args` is captured too — the partition is the shape, not the whole of it', () => {
  // Same defect, a field away. `args` was read by its `!== undefined` test,
  // read again by its `typeof` test, and read a THIRD time to build the
  // returned request — so a caller could satisfy both checks and still put
  // something else on the wire. A fix that closed the partition and left
  // this open would have moved the defect rather than closed it.
  const req = { protocol: 1, entry: 'app/root', state: { ':todos': '[]' } };
  const reads = twoFaced(req, 'args', '[1 2 3]', 2);
  const out = validateRequest(req, TABLES);
  assert.strictEqual(
    out.args,
    '[1 2 3]',
    'the request must carry the EDN text that was validated',
  );
  assert.strictEqual(reads(), 1, 'and one read is all a validated field ever needs');
});

test('every field the normalized request carries is read exactly ONCE', () => {
  // The invariant the two rows above are consequences of, stated directly
  // and over the whole field list rather than over the two fields that
  // happened to be reachable. This one uses an honest accessor: it changes
  // no value and forces no failure, it only counts. A field read twice is
  // a field whose second read nobody validated, whatever it returns today.
  const req = {};
  const counters = {
    protocol: twoFaced(req, 'protocol', 1, Infinity),
    entry: twoFaced(req, 'entry', 'app/root', Infinity),
    buildId: twoFaced(req, 'buildId', TABLES.buildId, Infinity),
    args: twoFaced(req, 'args', '[1 2 3]', Infinity),
    requestId: twoFaced(req, 'requestId', 'corr-1', Infinity),
    timeoutMs: twoFaced(req, 'timeoutMs', 250, Infinity),
    state: twoFaced(req, 'state', { ':todos': '[]' }, Infinity),
    runtime: twoFaced(req, 'runtime', {}, Infinity),
  };

  const out = validateRequest(req, TABLES);
  assert.strictEqual(out.entry, 'app/root', 'the control: this request must actually validate');

  const readTwice = Object.entries(counters)
    .filter(([, reads]) => reads() !== 1)
    .map(([field, reads]) => `${field} (${reads()})`);
  assert.deepStrictEqual(readTwice, [], 'these fields were read more than once');
});

// ---------------------------------------------------------------------------
// The module's own tables are fail-closed too
// ---------------------------------------------------------------------------

test('a render module is validated, and an entry with no allowlist is unrenderable', () => {
  const bad = { protocol: 1, buildId: 'b', entries: { 'app/root': {} }, render() {} };
  assert.throws(() => validateModule(bad), (e) => e.code === CODE.MALFORMED_MODULE);
  const good = {
    protocol: 1,
    buildId: 'b',
    entries: { 'app/root': { stateAllowlist: [':a'], runtimeAllowlist: [] } },
    render() {},
  };
  assert.strictEqual(validateModule(good), good);
});

test('an entry with a stateAllowlist but no runtimeAllowlist is unrenderable too', () => {
  const half = {
    protocol: 1,
    buildId: 'b',
    entries: { 'app/root': { stateAllowlist: [':a'] } },
    render() {},
  };
  assert.throws(
    () => validateModule(half),
    (e) =>
      e.code === CODE.MALFORMED_MODULE &&
      /runtimeAllowlist/.test(e.message) &&
      e.detail.allowlist === 'runtimeAllowlist',
  );
  const badKey = {
    ...half,
    entries: { 'app/root': { stateAllowlist: [':a'], runtimeAllowlist: ['routing'] } },
  };
  assert.throws(
    () => validateModule(badKey),
    (e) => e.code === CODE.MALFORMED_MODULE && /runtime-db key/.test(e.message),
  );
});

test('a module with no build identity is refused — there would be nothing to compare', () => {
  assert.throws(
    () => validateModule({ protocol: 1, entries: { a: { stateAllowlist: [] } }, render() {} }),
    (e) => e.code === CODE.MALFORMED_MODULE && /buildId/.test(e.message),
  );
});

// ---------------------------------------------------------------------------
// Through a live service: the validator is the DOOR
// ---------------------------------------------------------------------------

test('a refused request yields no chunks, and never touches an isolate', async () => {
  await withService('reference', { isolates: 1 }, async (service) => {
    const before = service.stats();
    const err = await refusalOf(() =>
      collect(service, { protocol: 1, entry: 'app/root', state: { ':nope': '1' } }),
    );
    assert.strictEqual(err.code, CODE.STATE_KEY_NOT_ALLOWED);

    const after = service.stats();
    assert.strictEqual(after.ready, before.ready, 'a refusal must not have borrowed an isolate');
    assert.strictEqual(after.busy, 0);

    // …and the same service still renders, so the refusal was about the
    // request rather than about the service being broken.
    const ok = await collect(service, { protocol: 1, entry: 'app/root', state: { ':todos': '[1]' } });
    assert.strictEqual(ok.chunks.length, 1);
  });
});

test('the runtime partition reaches the module frozen, and a refused runtime key yields no chunks', async () => {
  await withService('reference', { isolates: 1 }, async (service) => {
    assert.deepStrictEqual(
      service.entries['app/root'].runtimeAllowlist,
      [':rf.runtime/routing'],
      'the LIVE table carries the runtime allowlist the bundle published',
    );
    const out = await collect(service, {
      protocol: 1,
      entry: 'app/root',
      state: { ':todos': '[1]' },
      runtime: { ':rf.runtime/routing': '{:current {:route-id :home}}' },
    });
    const seen = observed(out);
    assert.strictEqual(seen.readRuntimeRoute, '{:current {:route-id :home}}', 'the module READ the runtime partition');
    assert.strictEqual(seen.runtimeFrozen, true, 'and it arrived frozen, like state');

    const before = service.stats();
    const err = await refusalOf(() =>
      collect(service, {
        protocol: 1,
        entry: 'app/root',
        runtime: { ':rf.runtime/machines': '{}' },
      }),
    );
    assert.strictEqual(err.code, CODE.STATE_KEY_NOT_ALLOWED);
    assert.strictEqual(err.detail.field, 'runtime');
    assert.strictEqual(service.stats().ready, before.ready, 'a refusal must not have borrowed an isolate');
  });
});

test('an entry the bundle lacks is refused against the LIVE table, not a copy', async () => {
  await withService('reference', { isolates: 1 }, async (service) => {
    assert.deepStrictEqual(Object.keys(service.entries).sort(), ['app/other', 'app/root']);
    const err = await refusalOf(() => collect(service, { protocol: 1, entry: 'app/ghost' }));
    assert.strictEqual(err.code, CODE.UNKNOWN_ENTRY);
  });
});

test('a malformed bundle refuses at BOOT, not at first request', async () => {
  for (const [fixtureName, why] of [
    ['bad-no-allowlist', /stateAllowlist/],
    ['bad-no-runtime-allowlist', /runtimeAllowlist/],
    ['bad-no-build-id', /buildId/],
    ['bad-protocol', /protocol/],
  ]) {
    const err = await refusalOf(() => withService(fixtureName, {}, async () => {}));
    assert.ok(err, `${fixtureName} should not have booted`);
    assert.strictEqual(err.code, CODE.MALFORMED_MODULE, fixtureName);
    assert.match(err.message, why, fixtureName);
  }
});
