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
const { withService, collect, refusalOf } = require('./_support.cjs');
const {
  CODE,
  REQUEST_FIELDS,
  REFUSED_FIELDS,
  Refusal,
  validateRequest,
  validateModule,
} = require('../src/protocol.cjs');

const TABLES = {
  buildId: 'reference-build-1',
  entries: {
    'app/root': { stateAllowlist: [':todos', ':route'] },
    'app/other': { stateAllowlist: [':route'] },
  },
};

const OK = () => ({ protocol: 1, entry: 'app/root', state: { ':todos': '[]' } });

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
  assert.strictEqual(out.timeoutMs, 1000, 'the service default applies when none is asked for');
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
  const { protocol, ...noVersion } = OK();
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

test('state is bounded, and the ceiling is measured in BYTES', () => {
  // One em dash is one code unit and three bytes. A ceiling that counted
  // code units would admit this; the byte accounting refuses it. Written
  // as an escape so an encoding-normalising editor cannot quietly ASCII-fy
  // the input and leave this row green over something that proves nothing.
  const value = `"${'\u005cu2014'.repeat(40)}"`;
  const bytes = Buffer.byteLength(':todos', 'utf8') + Buffer.byteLength(value, 'utf8');
  assert.ok(bytes > value.length, 'the fixture must be non-ASCII or it proves nothing');
  assert.strictEqual(
    codeOf({ ...OK(), state: { ':todos': value } }, { maxRequestBytes: bytes - 1 }),
    CODE.REQUEST_TOO_LARGE,
  );
  assert.strictEqual(codeOf({ ...OK(), state: { ':todos': value } }, { maxRequestBytes: bytes }), null);
});

test('a deadline over the service ceiling is clamped rather than refused', () => {
  const out = validateRequest({ ...OK(), timeoutMs: 60000 }, TABLES, { maxTimeoutMs: 750 });
  assert.strictEqual(out.timeoutMs, 750);
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
    entries: { 'app/root': { stateAllowlist: [':a'] } },
    render() {},
  };
  assert.strictEqual(validateModule(good), good);
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
    ['bad-no-build-id', /buildId/],
    ['bad-protocol', /protocol/],
  ]) {
    const err = await refusalOf(() => withService(fixtureName, {}, async () => {}));
    assert.ok(err, `${fixtureName} should not have booted`);
    assert.strictEqual(err.code, CODE.MALFORMED_MODULE, fixtureName);
    assert.match(err.message, why, fixtureName);
  }
});
