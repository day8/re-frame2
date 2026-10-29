'use strict';
// THE JVM<->NODE RENDER PROTOCOL — the message shapes and their validator.
// It knows nothing about HTTP, worker threads or framing; `http.cjs` is one
// transport over it (README §The protocol).
//
// A response is one buffered body string in a `complete` message, or one
// `refusal` in its place. Node returns body markup and nothing else — the
// JVM drains the boot events, builds the hydration payload and writes the
// page (README §Node returns body markup and nothing else) — so the request
// carries no payload policy and the response no metadata.
//
// The request carries both partitions of the settled frame, `state`
// (app-db) and `runtime` (runtime-db), each a map of top-level key text to
// EDN text. The JVM chooses the keys under its `:render-state` policy; this
// file checks shape and the byte ceiling and never decodes application data.
//
// Both legs are closed field lists. A request field this file does not name
// is refused, so a typo cannot become a default, and the three fields an
// implementer would reach for first are refused with their reason
// (`REFUSED_FIELDS`). `COMPLETE_FIELDS` is the whole response leg; the
// module's only contribution to it is `html`. Every caller-fault refusal is
// delivered before an isolate is borrowed.

const PROTOCOL_VERSION = 1;

/**
 * Refusal codes. Namespaced under `:rf.ssr-node/*` — a reserved-namespace
 * tenant per Conventions' `:rf/*` single-root scheme, catalogued in
 * `spec/Conventions.md`.
 */
const CODE = Object.freeze({
  MALFORMED_REQUEST: ':rf.ssr-node/malformed-request',
  PROTOCOL_VERSION: ':rf.ssr-node/protocol-version',
  UNKNOWN_REQUEST_FIELD: ':rf.ssr-node/unknown-request-field',
  BAD_REQUEST_FIELD: ':rf.ssr-node/bad-request-field',
  REQUEST_TOO_LARGE: ':rf.ssr-node/request-too-large',
  UNKNOWN_ENTRY: ':rf.ssr-node/unknown-entry',
  BUILD_IDENTITY_MISMATCH: ':rf.ssr-node/build-identity-mismatch',
  RENDER_TIMEOUT: ':rf.ssr-node/render-timeout',
  RENDER_THREW: ':rf.ssr-node/render-threw',
  ISOLATE_LOST: ':rf.ssr-node/isolate-lost',
  SERVICE_SATURATED: ':rf.ssr-node/service-saturated',
  SERVICE_CLOSED: ':rf.ssr-node/service-closed',
  MALFORMED_MODULE: ':rf.ssr-node/malformed-render-module',
});

/** EVERY field a request may carry. Adding one is a protocol change. */
const REQUEST_FIELDS = Object.freeze([
  'protocol',
  'entry',
  'state',
  'runtime',
  'args',
  'buildId',
  'timeoutMs',
  'requestId',
]);

/**
 * The two frame-state partitions a request carries, as [request field,
 * what the keys are]. One table, so the read-once bindings and the
 * partition validator cannot disagree about which partitions exist.
 */
const PARTITIONS = Object.freeze([
  Object.freeze({ field: 'state', keys: 'app-db' }),
  Object.freeze({ field: 'runtime', keys: 'runtime-db' }),
]);

/**
 * Fields refused ON PURPOSE, each with the reason a reader needs. Every
 * other unknown field takes the generic refusal; these three are the ones
 * an implementer building this crossing would reach for first, and a
 * refusal that only says "unknown" would send them to widen the list.
 */
const REFUSED_FIELDS = Object.freeze({
  initialEvents:
    'The JVM drains the boot events and holds the request frame. A Node side that drained ' +
    'them itself forks the event drain, which is the host fork this contract refuses. ' +
    'Send the drained state as `state` instead.',
  payloadPolicy:
    'The hydration payload is built on the JVM from its own app-db. Node returns body ' +
    'markup and nothing else, so there is no payload here for a policy to govern.',
  head:
    'The head model, the response accumulator, cookies and redirects are `ssr-ring`\'s and ' +
    'stay on the JVM. Anything beyond body markup crossing this contract is the host fork ' +
    'arriving by increments.',
});

/**
 * EVERY field a `complete` response carries. `html` is the body markup the
 * module returned; every other member is a fact this service owns about the
 * crossing (`requestId` is the caller's own token, echoed). Adding a member
 * is a protocol change, and `test/egress.test.cjs` pins the roster.
 */
const COMPLETE_FIELDS = Object.freeze(['type', 'html', 'renderMs', 'buildId', 'requestId']);

/**
 * What a caller is told for each way a render fails inside the module.
 * Fixed wordings, because an exception's message, detail and stack are
 * application data, and a module-set `code` would choose its own HTTP
 * status; the operator reads the real fault on the sidecar's stderr
 * (README §The response leg).
 */
const RENDER_THREW_REFUSAL =
  "the render module threw; its exception, with its stack, is on the sidecar's stderr";

/** The isolate died under the render — an escaped exception or an exit — and is replaced. */
const ISOLATE_LOST_REFUSAL =
  "the isolate died during the render and is being replaced; the cause is on the sidecar's stderr";

/** A dead isolate could not be replaced, so the service is stopping for good. */
const REPLACEMENT_FAILED_REFUSAL =
  'the service could not replace a dead isolate and is stopping for its supervisor to restart; ' +
  "why is on the sidecar's stderr";

/**
 * Is this one of the refusal codes this service publishes? `CODE` is the
 * whole family; the boundary that builds a public `Refusal` asks this, so a
 * code from anywhere else cannot reach `statusFor` and choose a status.
 */
const REFUSAL_CODES = Object.freeze(new Set(Object.values(CODE)));
const isRefusalCode = (code) => REFUSAL_CODES.has(code);

/**
 * A top-level partition key, as its EDN text: a keyword, so a leading colon
 * and no whitespace. Deliberately not the full keyword grammar — a boundary
 * that reimplements the reader inherits the reader's bugs.
 */
const KEY_TEXT = /^:[^\s:][^\s]*$/;

class Refusal extends Error {
  constructor(code, message, detail = {}) {
    super(message);
    this.name = 'Refusal';
    this.code = code;
    this.detail = detail;
  }
  /** The wire frame, sent instead of a `complete` response. */
  toFrame(requestId) {
    return {
      type: 'refusal',
      code: this.code,
      message: this.message,
      detail: this.detail,
      ...(requestId === undefined ? {} : { requestId }),
    };
  }
}

const refuse = (code, message, detail) => {
  throw new Refusal(code, message, detail);
};

const isPlainObject = (value) =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

/**
 * Validate the render module a service was pointed at.
 *
 * Fail-closed here too: a module that does not speak this protocol version,
 * publish a build identity, export a render function and publish a
 * non-empty entry table is refused at boot rather than at its first
 * request. The entry table is a list of entry ids — the names a JVM host
 * may ask for, and the bundle can resolve back into a root.
 */
function validateModule(renderModule, moduleLabel = '<render module>') {
  if (!isPlainObject(renderModule)) {
    refuse(CODE.MALFORMED_MODULE, `${moduleLabel} did not export an object`);
  }
  if (renderModule.protocol !== PROTOCOL_VERSION) {
    refuse(
      CODE.MALFORMED_MODULE,
      `${moduleLabel} declares protocol ${JSON.stringify(renderModule.protocol)}; this service speaks ${PROTOCOL_VERSION}`,
      { declared: renderModule.protocol, expected: PROTOCOL_VERSION },
    );
  }
  if (typeof renderModule.buildId !== 'string' || renderModule.buildId.length === 0) {
    refuse(
      CODE.MALFORMED_MODULE,
      `${moduleLabel} publishes no buildId. Build identity is how a JVM host detects that the ` +
        `sidecar is serving a different application than the one it deployed against.`,
    );
  }
  if (typeof renderModule.render !== 'function') {
    refuse(CODE.MALFORMED_MODULE, `${moduleLabel} exports no render function`);
  }
  if (!Array.isArray(renderModule.entries) || renderModule.entries.length === 0) {
    refuse(
      CODE.MALFORMED_MODULE,
      `${moduleLabel} publishes no entry table: \`entries\` must be a non-empty array of entry ids`,
    );
  }
  for (const entryId of renderModule.entries) {
    if (typeof entryId !== 'string' || entryId.length === 0) {
      refuse(
        CODE.MALFORMED_MODULE,
        `${moduleLabel} entry table carries ${JSON.stringify(entryId)}, which is not a non-empty string`,
      );
    }
  }
  return renderModule;
}

/**
 * Validate the shape of one partition of a request — `state` or `runtime`:
 * top-level key text mapped to EDN text. Returns `{ partition, bytes }`: a
 * fresh copy of the values it checked (an empty object when the field was
 * absent) and their UTF-8 byte count, so the caller can hold both
 * partitions to one ceiling. Throws `Refusal`.
 *
 * A copy, because what this returns is what `postMessage` clones, and the
 * clone is a second read: a getter or a Proxy on the caller's object could
 * answer differently the second time.
 */
function validatePartition(partition, { field: partitionField, keys: partitionLabel }) {
  if (partition === undefined) return { partition: {}, bytes: 0 };
  if (!isPlainObject(partition)) {
    refuse(
      CODE.BAD_REQUEST_FIELD,
      `\`${partitionField}\` must be an object mapping a top-level ${partitionLabel} key (EDN text) to that ` +
        "key's value (EDN text)",
      { field: partitionField },
    );
  }
  let partitionBytes = 0;
  const validated = {};
  for (const [partitionKey, ednText] of Object.entries(partition)) {
    if (!KEY_TEXT.test(partitionKey)) {
      refuse(
        CODE.BAD_REQUEST_FIELD,
        `${partitionField} key ${JSON.stringify(partitionKey)} is not a top-level ${partitionLabel} key in EDN spelling`,
        { field: partitionField, key: partitionKey },
      );
    }
    if (typeof ednText !== 'string') {
      refuse(
        CODE.BAD_REQUEST_FIELD,
        `${partitionField} value for ${partitionKey} must be EDN text; the service does not decode application data`,
        { field: partitionField, key: partitionKey },
      );
    }
    validated[partitionKey] = ednText;
    partitionBytes +=
      Buffer.byteLength(partitionKey, 'utf8') + Buffer.byteLength(ednText, 'utf8');
  }
  return { partition: validated, bytes: partitionBytes };
}

/**
 * Validate one request against the contract and against the tables the
 * loaded bundle published (`moduleTables` is `{ buildId, entries }`). Returns
 * the request normalized — defaults applied, nothing added the caller did
 * not send. Throws `Refusal` on anything else.
 *
 * Shape, then identity, then content, so the first refusal names the field
 * the caller got wrong. Every field is read exactly once and the result is
 * built from the values checked, for the reason `validatePartition` copies.
 */
function validateRequest(request, moduleTables, limits = {}) {
  const { defaultTimeoutMs = 1000, maxTimeoutMs = 5000, maxRequestBytes = 1 << 20 } = limits;

  if (!isPlainObject(request)) {
    refuse(CODE.MALFORMED_REQUEST, 'a render request must be an object');
  }

  // ---- the field allowlist, first and without exception ------------------
  for (const field of Object.keys(request)) {
    if (!REQUEST_FIELDS.includes(field)) {
      const intentionalRefusalReason = REFUSED_FIELDS[field];
      refuse(
        CODE.UNKNOWN_REQUEST_FIELD,
        `unknown request field ${JSON.stringify(field)}. The contract carries: ` +
          `${REQUEST_FIELDS.join(', ')}.` +
          (intentionalRefusalReason ? ` ${intentionalRefusalReason}` : ''),
        {
          field,
          allowed: REQUEST_FIELDS,
          ...(intentionalRefusalReason ? { refusedOnPurpose: true } : {}),
        },
      );
    }
  }

  // ---- read once; everything below reads these, never `request` ----------
  const protocol = request.protocol;
  const entry = request.entry;
  const requestId = request.requestId;
  const args = request.args;
  const buildId = request.buildId;
  const timeout = request.timeoutMs;
  const state = request[PARTITIONS[0].field];
  const runtime = request[PARTITIONS[1].field];

  if (protocol !== PROTOCOL_VERSION) {
    refuse(
      CODE.PROTOCOL_VERSION,
      `request declares protocol ${JSON.stringify(protocol)}; this service speaks ${PROTOCOL_VERSION}`,
      { declared: protocol, expected: PROTOCOL_VERSION },
    );
  }

  if (typeof entry !== 'string' || entry.length === 0) {
    refuse(CODE.BAD_REQUEST_FIELD, '`entry` must be a non-empty string', { field: 'entry' });
  }
  if (requestId !== undefined && typeof requestId !== 'string') {
    refuse(CODE.BAD_REQUEST_FIELD, '`requestId` must be a string', { field: 'requestId' });
  }
  if (args !== undefined && typeof args !== 'string') {
    refuse(
      CODE.BAD_REQUEST_FIELD,
      '`args` is the root arguments as EDN TEXT, not a decoded value — the service does not ' +
        'read application data',
      { field: 'args' },
    );
  }
  if (buildId !== undefined && typeof buildId !== 'string') {
    refuse(CODE.BAD_REQUEST_FIELD, '`buildId` must be a string', { field: 'buildId' });
  }

  // ---- build identity: the per-request half of the skew detector ---------
  if (buildId !== undefined && buildId !== moduleTables.buildId) {
    refuse(
      CODE.BUILD_IDENTITY_MISMATCH,
      `request expects build ${JSON.stringify(buildId)} but this sidecar is serving ` +
        `${JSON.stringify(moduleTables.buildId)}`,
      { expected: buildId, serving: moduleTables.buildId },
    );
  }

  // ---- the entry table ---------------------------------------------------
  if (!moduleTables.entries.includes(entry)) {
    refuse(CODE.UNKNOWN_ENTRY, `no entry named ${JSON.stringify(entry)} in this bundle`, {
      entry,
      known: [...moduleTables.entries],
    });
  }

  // ---- the two partitions ------------------------------------------------
  const stateValidation = validatePartition(state, PARTITIONS[0]);
  const runtimeValidation = validatePartition(runtime, PARTITIONS[1]);
  // ONE ceiling over both partitions: the budget is the request's EDN
  // text, and a partition is a way of naming it, not a second allowance.
  const requestBytes = stateValidation.bytes + runtimeValidation.bytes;
  if (requestBytes > maxRequestBytes) {
    refuse(
      CODE.REQUEST_TOO_LARGE,
      `state and runtime together are ${requestBytes} bytes, over the ${maxRequestBytes}-byte ceiling`,
      { bytes: requestBytes, ceiling: maxRequestBytes },
    );
  }

  // ---- the deadline ------------------------------------------------------
  let timeoutMs = defaultTimeoutMs;
  if (timeout !== undefined) {
    if (typeof timeout !== 'number' || !Number.isFinite(timeout) || timeout <= 0) {
      refuse(CODE.BAD_REQUEST_FIELD, '`timeoutMs` must be a positive finite number', {
        field: 'timeoutMs',
      });
    }
    // Clamped rather than refused: a caller asking for longer than the
    // service will ever wait has made a configuration mistake, and the
    // service's own ceiling is the one that binds either way.
    timeoutMs = Math.min(timeout, maxTimeoutMs);
  }

  return {
    protocol: PROTOCOL_VERSION,
    entry,
    state: stateValidation.partition,
    runtime: runtimeValidation.partition,
    args,
    requestId,
    timeoutMs,
  };
}

/**
 * The success response, `COMPLETE_FIELDS` and nothing else — named fields
 * and no spread, so the roster cannot grow without an edit here.
 */
const completeFrame = ({ html, renderMs, buildId, requestId }) => ({
  type: 'complete',
  html,
  renderMs,
  buildId,
  ...(requestId === undefined ? {} : { requestId }),
});

module.exports = {
  PROTOCOL_VERSION,
  CODE,
  REQUEST_FIELDS,
  PARTITIONS,
  REFUSED_FIELDS,
  COMPLETE_FIELDS,
  RENDER_THREW_REFUSAL,
  ISOLATE_LOST_REFUSAL,
  REPLACEMENT_FAILED_REFUSAL,
  isRefusalCode,
  Refusal,
  validateModule,
  validateRequest,
  completeFrame,
};
