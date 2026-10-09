'use strict';
// GUARANTEE 2 — THE ALLOWLISTED REQUEST, FAIL-CLOSED.
//
// Mostly `validateRequest` directly, because a fail-closed contract is a
// claim about every input and enumerating inputs is the cheapest honest way
// to make it. The service-level rows at the bottom show the validator is the
// door: a refusal reaches the caller with no chunk emitted and no isolate
// borrowed. Each refusal row is the control request below plus one fault.

const test = require('node:test');
const assert = require('node:assert');
const { withService, collect, observed, refusalOf } = require('./_support.cjs');
const {
  CODE,
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

function refuseOf(req) {
  try {
    validateRequest(req, TABLES);
    assert.fail('expected a refusal');
  } catch (err) {
    assert.ok(err instanceof Refusal);
    return err;
  }
}

test('the control request validates', () => {
  assert.deepStrictEqual(validateRequest(OK(), TABLES), {
    protocol: 1,
    entry: 'app/root',
    state: { ':todos': '[]' },
    runtime: { ':rf.runtime/routing': '{:current {:route-id :home}}' },
    args: undefined,
    requestId: undefined,
    timeoutMs: 1000,
  });
});

// ---------------------------------------------------------------------------
// The field allowlist IS the contract
// ---------------------------------------------------------------------------

test('a field the contract does not name is refused, not ignored', () => {
  assert.strictEqual(codeOf({ ...OK(), nonsense: 1 }), CODE.UNKNOWN_REQUEST_FIELD);
});

test('every deliberately-refused field carries its reason', () => {
  // The two an implementer reaches for first are pinned to the phrase that
  // teaches; one dropped from `REFUSED_FIELDS` loses `refusedOnPurpose`.
  const TEACHES = { initialEvents: /host fork/, payloadPolicy: /body markup and nothing else/ };
  for (const field of new Set([...Object.keys(TEACHES), ...Object.keys(REFUSED_FIELDS)])) {
    const err = refuseOf({ ...OK(), [field]: 'x' });
    assert.strictEqual(err.code, CODE.UNKNOWN_REQUEST_FIELD, field);
    assert.strictEqual(err.detail.refusedOnPurpose, true, field);
    assert.ok(err.message.includes(REFUSED_FIELDS[field]), `${field} lost its explanation`);
    if (TEACHES[field]) {
      assert.match(err.message, TEACHES[field], `${field}: the message must teach, not merely decline`);
    }
  }
});

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
  // `args` is EDN TEXT: the service never decodes application data.
  assert.strictEqual(codeOf({ ...OK(), args: { page: 3 } }), CODE.BAD_REQUEST_FIELD);
  assert.strictEqual(codeOf({ ...OK(), timeoutMs: 0 }), CODE.BAD_REQUEST_FIELD);
  assert.strictEqual(codeOf({ ...OK(), timeoutMs: Infinity }), CODE.BAD_REQUEST_FIELD);
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
// The render-visibility allowlists, one per partition, owned by the ENTRY
// ---------------------------------------------------------------------------

test('state keys must be top-level app-db keys, and values must be EDN text', () => {
  assert.strictEqual(codeOf({ ...OK(), state: { todos: '[]' } }), CODE.BAD_REQUEST_FIELD);
  assert.strictEqual(codeOf({ ...OK(), state: { ':a b': '[]' } }), CODE.BAD_REQUEST_FIELD);
  assert.strictEqual(codeOf({ ...OK(), state: { ':todos': ['a'] } }), CODE.BAD_REQUEST_FIELD);
  assert.strictEqual(codeOf({ ...OK(), state: [] }), CODE.BAD_REQUEST_FIELD);
});

test('a runtime key the entry does not declare is refused, and the refusal names the partition', () => {
  const err = refuseOf({ ...OK(), runtime: { ':rf.runtime/resources': '{}' } });
  assert.strictEqual(err.code, CODE.STATE_KEY_NOT_ALLOWED);
  assert.deepStrictEqual(err.detail, {
    entry: 'app/root',
    field: 'runtime',
    key: ':rf.runtime/resources',
    allowed: [':rf.runtime/routing', ':rf.runtime/machines'],
  });
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

test('the byte ceiling is ONE ceiling over both partitions', () => {
  const state = { ':todos': '"' + 'a'.repeat(40) + '"' };
  const runtime = { ':rf.runtime/routing': '"' + 'b'.repeat(40) + '"' };
  const bytesOf = (o) =>
    Object.entries(o).reduce((n, [k, v]) => n + Buffer.byteLength(k) + Buffer.byteLength(v), 0);
  const ceiling = Math.max(bytesOf(state), bytesOf(runtime)) + 10; // room for either alone
  assert.ok(bytesOf(state) + bytesOf(runtime) > ceiling, 'the fixture must exceed the ceiling only together');
  const base = { protocol: 1, entry: 'app/root' };
  assert.strictEqual(codeOf({ ...base, state }, { maxRequestBytes: ceiling }), null);
  assert.strictEqual(codeOf({ ...base, runtime }, { maxRequestBytes: ceiling }), null);
  assert.strictEqual(codeOf({ ...base, state, runtime }, { maxRequestBytes: ceiling }), CODE.REQUEST_TOO_LARGE);
});

test('state is bounded, and the ceiling is measured in BYTES', () => {
  // An em dash is one code unit and three bytes, written as an escape so an
  // encoding-normalising editor cannot quietly ASCII-fy it.
  const value = `"${'—'.repeat(40)}"`;
  const bytes = Buffer.byteLength(':todos', 'utf8') + Buffer.byteLength(value, 'utf8');
  assert.ok(Buffer.byteLength(value, 'utf8') > value.length, 'the fixture must be non-ASCII or it proves nothing');
  const stateOnly = { protocol: 1, entry: 'app/root', state: { ':todos': value } };
  assert.strictEqual(codeOf(stateOnly, { maxRequestBytes: bytes - 1 }), CODE.REQUEST_TOO_LARGE);
  assert.strictEqual(codeOf(stateOnly, { maxRequestBytes: bytes }), null);
});

test('a deadline over the service ceiling is clamped rather than refused', () => {
  const out = validateRequest({ ...OK(), timeoutMs: 60000 }, TABLES, { maxTimeoutMs: 750 });
  assert.strictEqual(out.timeoutMs, 750);
});

// ---------------------------------------------------------------------------
// The normalized request is a SNAPSHOT
//
// It is what `postMessage` structured-clones, and a clone is a second read
// of every value. A field read twice is a field whose second read nobody
// validated, so the read count is the discriminator.
// ---------------------------------------------------------------------------

/** Install an accessor on `host` returning `honestValue` for `honestReads` reads, then a Symbol; returns the read counter. */
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

test('every field the normalized request carries is read exactly ONCE', () => {
  // Every field the contract names, so this is also the row showing each is accepted.
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
  assert.strictEqual(out.args, '[1 2 3]', 'the request must carry the EDN text that was validated');

  const readTwice = Object.entries(counters)
    .filter(([, reads]) => reads() !== 1)
    .map(([field, reads]) => `${field} (${reads()})`);
  assert.deepStrictEqual(readTwice, [], 'these fields were read more than once');
});

// ---------------------------------------------------------------------------
// The module's own tables are fail-closed too
// ---------------------------------------------------------------------------

test('an allowlist member that is not a top-level key in EDN spelling makes the module unrenderable', () => {
  const badKey = {
    protocol: 1,
    buildId: 'b',
    entries: { 'app/root': { stateAllowlist: [':a'], runtimeAllowlist: ['routing'] } },
    render() {},
  };
  assert.throws(
    () => validateModule(badKey),
    (e) => e.code === CODE.MALFORMED_MODULE && /runtime-db key/.test(e.message),
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
    assert.strictEqual(service.stats().ready, before.ready, 'a refusal must not have borrowed an isolate');
    // ...and the same service still renders: the refusal was about the request.
    const ok = await collect(service, { protocol: 1, entry: 'app/root', state: { ':todos': '[1]' } });
    assert.strictEqual(ok.chunks.length, 1);
  });
});

test('the runtime partition reaches the module, frozen like state', async () => {
  await withService('reference', { isolates: 1 }, async (service) => {
    const out = await collect(service, {
      protocol: 1,
      entry: 'app/root',
      state: { ':todos': '[1]' },
      runtime: { ':rf.runtime/routing': '{:current {:route-id :home}}' },
    });
    const { readRuntimeRoute, runtimeFrozen } = observed(out);
    assert.deepStrictEqual(
      { readRuntimeRoute, runtimeFrozen },
      { readRuntimeRoute: '{:current {:route-id :home}}', runtimeFrozen: true },
    );
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
