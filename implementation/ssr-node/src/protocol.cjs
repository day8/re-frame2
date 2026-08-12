'use strict';
// THE JVM<->NODE RENDER PROTOCOL (rf2-hic-056).
//
// This file is the contract and its validator, and it knows nothing about
// HTTP, worker threads or how any of it is framed on a wire. That is the
// separability requirement discharged at its root: the protocol is a set
// of MESSAGE SHAPES, and a transport is an adapter over them. `http.cjs`
// is one such adapter; a socket, a length-prefixed pipe or an in-process
// call would be others, and none of them would touch this file.
//
// ## A RESPONSE IS A SEQUENCE OF FRAMES, NOT A STRING
//
// The bead's design constraint is that no layer bake in "one complete
// string", so that a streaming caller later does not need a second
// semantics. Every response here is therefore N `chunk` frames followed
// by one terminal frame, where N is 1 for a `renderToString` module and
// many for a streaming one. Joining is a decision the TRANSPORT makes,
// once, at the edge — never something a middle layer does on the way
// past, because a middle layer that joins is a middle layer that has to
// be rewritten.
//
// A refusal arrives INSTEAD OF chunks and never after them. A caller that
// has begun writing bytes to its own client cannot un-write them, so
// everything refusable is refused before the first chunk leaves the
// isolate. That ordering is a property of `validateRequest` running to
// completion before dispatch, and it is asserted in `protocol.test.cjs`.
//
// ## NODE RETURNS BODY MARKUP AND NOTHING ELSE
//
// This is the ruled topology rather than a simplification, and it is what
// keeps the field lists below as short as they are.
// `docs/design/hicasso/production-server-arm.md` §5 sets the compliant
// shape out arrow by arrow: `ssr-ring` drains the boot events and holds
// the request frame on the JVM; Node resolves an entry identifier against
// the table its own bundle publishes, seeds a per-request frame from the
// state projection, and renders; **Node returns the body markup, and
// nothing else**; the JVM assembles the page and writes the payload
// script from ITS OWN app-db. §11's tripwire names the alternative in as
// many words — *"Anything beyond the body markup crossing the contract …
// is the host fork the adversarial review rejected, arriving by
// increments."*
//
// So the hydration payload is not on this wire in either direction, and
// neither is the head model, the response accumulator, cookies or
// redirects. That is why `payloadPolicy` and `clientFrameId` are absent
// from the request: with the payload built on the JVM, a render that
// asked for either would be a render building a payload.
//
// ## FAIL-CLOSED MEANS THE FIELD LIST IS THE CONTRACT
//
// A request field this file does not name is a refusal. Not ignored, not
// passed through, not logged — refused. Two things follow that look like
// strictness for its own sake and are not:
//
//   - a typo'd field name cannot silently become a default, which is the
//     failure mode the framework's own `payload-policy` exists to
//     prevent on the neighbouring wire;
//   - the field list ENFORCES THE TOPOLOGY above, at the door, rather
//     than in a paragraph nobody reads at 3am. The three fields a
//     well-meaning implementer would actually reach for are named in
//     `REFUSED_FIELDS` with the reason attached to the refusal, so the
//     message teaches instead of merely declining.
//
// ## AND THE RESPONSE LEG IS ALLOWLISTED THE SAME WAY
//
// A fail-closed request door with an open response door is not a
// fail-closed crossing; it is a fail-closed crossing in one direction and
// a `meta` map in the other. `COMPLETE_FIELDS` is therefore the response
// leg's field list, and it is the same kind of object as `REQUEST_FIELDS`
// rather than a comment: every member is a fact the SERVICE owns about
// the crossing it just performed, and there is no member — and no
// mechanism — by which the application's render module contributes one.
// The module's only output channel is `emit`, and `MODULE_RETURN_REFUSAL`
// is the reason a module that reaches for a second one is told no.

const PROTOCOL_VERSION = 1;

/**
 * Refusal codes. Namespaced under `:rf.ssr-node/*` — a reserved-namespace
 * tenant per Conventions' `:rf/*` single-root scheme. Cataloguing the
 * family in `spec/Conventions.md` is a sequenced follow-up; that file is
 * hot-zone and this bead is fenced from it.
 */
const CODE = Object.freeze({
  MALFORMED_REQUEST: ':rf.ssr-node/malformed-request',
  PROTOCOL_VERSION: ':rf.ssr-node/protocol-version',
  UNKNOWN_REQUEST_FIELD: ':rf.ssr-node/unknown-request-field',
  BAD_REQUEST_FIELD: ':rf.ssr-node/bad-request-field',
  REQUEST_TOO_LARGE: ':rf.ssr-node/request-too-large',
  UNKNOWN_ENTRY: ':rf.ssr-node/unknown-entry',
  STATE_KEY_NOT_ALLOWED: ':rf.ssr-node/state-key-not-allowed',
  BUILD_IDENTITY_MISMATCH: ':rf.ssr-node/build-identity-mismatch',
  RENDER_TIMEOUT: ':rf.ssr-node/render-timeout',
  RENDER_THREW: ':rf.ssr-node/render-threw',
  ISOLATE_LOST: ':rf.ssr-node/isolate-lost',
  SERVICE_SATURATED: ':rf.ssr-node/service-saturated',
  SERVICE_CLOSED: ':rf.ssr-node/service-closed',
  MALFORMED_MODULE: ':rf.ssr-node/malformed-render-module',
});

/**
 * EVERY field a request may carry. The list IS the allowlist — see the
 * header. Adding a field here is a protocol change and should read like
 * one.
 */
const REQUEST_FIELDS = Object.freeze([
  'protocol',
  'entry',
  'state',
  'args',
  'buildId',
  'timeoutMs',
  'requestId',
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
    'them itself forks the event drain, which is the host fork the adversarial review ' +
    'rejected on the record. Send the drained state as `state` instead.',
  payloadPolicy:
    'The hydration payload is built on the JVM from its own app-db. Node returns body ' +
    'markup and nothing else, so there is no payload here for a policy to govern.',
  head:
    'The head model, the response accumulator, cookies and redirects are `ssr-ring`\'s and ' +
    'stay on the JVM. Anything beyond body markup crossing this contract is the host fork ' +
    'arriving by increments.',
});

/**
 * EVERY field a `complete` frame carries. The response leg's half of the
 * contract, and the same kind of list as `REQUEST_FIELDS` — see the header.
 *
 * Read the members as a single claim: each one is a fact about the
 * CROSSING, produced by this service, and none of them originates in the
 * application's render module.
 *
 *   `type`      — the frame discriminator.
 *   `chunks`    — how many body chunks preceded this frame. Counted here.
 *   `renderMs`  — how long the module's render took. Timed here.
 *   `buildId`   — which bundle answered. Read from the module's published
 *                 table at boot, not from the render.
 *   `requestId` — the CALLER's own correlation token, echoed back. It is
 *                 the one field that did not originate on this side, and
 *                 it originated with the caller rather than with the
 *                 application bundle, so echoing it egresses nothing the
 *                 caller does not already hold.
 *
 * Adding a member is a protocol change and should read like one. Adding a
 * member the render module fills in is the topology change §11 of the
 * server-arm pricing calls "the host fork arriving by increments", and
 * `test/egress.test.cjs` is the row that will say so.
 */
const COMPLETE_FIELDS = Object.freeze(['type', 'chunks', 'renderMs', 'buildId', 'requestId']);

/**
 * Why a render module that returns a value is refused rather than having
 * its value quietly dropped.
 *
 * The wording lives here, with the contract, because it IS the contract:
 * the module has one output channel and dropping a second one silently is
 * how a second one survives long enough to be depended on. This service
 * shipped for one commit with the returned value forwarded to the caller
 * as `meta`, HTTP happened not to serialise it, and "the current transport
 * drops it" was doing the work a guarantee was supposed to do.
 */
const MODULE_RETURN_REFUSAL =
  'the render module returned a value. `emit` is its only output channel: this contract ' +
  'carries body markup and nothing else, so a second way out of the isolate is application ' +
  'data crossing a boundary that has no policy for it — the host fork arriving by increments. ' +
  'Render what the module has to say, and return nothing.';

/**
 * A top-level app-db key, as its EDN text. Keywords in re-frame2 are what
 * top-level app-db keys are (Spec 011 §`:rf/app-db` projection), so the
 * wire spelling is a leading colon and no whitespace. Deliberately not a
 * full keyword grammar: this is a boundary check, and a boundary that
 * reimplements the reader is a boundary with a reader's bugs.
 */
const KEY_TEXT = /^:[^\s:][^\s]*$/;

class Refusal extends Error {
  constructor(code, message, detail = {}) {
    super(message);
    this.name = 'Refusal';
    this.code = code;
    this.detail = detail;
  }
  /** The wire frame. Terminal, and never preceded by a chunk. */
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

const isPlainObject = (x) => typeof x === 'object' && x !== null && !Array.isArray(x);

/**
 * Validate the render module a service was pointed at.
 *
 * Fail-closed here too, and for the sharpest reason in the file: an entry
 * with no `stateAllowlist` is not an entry that may read everything, it
 * is an entry that cannot be rendered. A permissive default on THIS list
 * is the whole-app-db-by-accident failure the framework's payload policy
 * was written to prevent, one wire over — and the pricing dossier is
 * explicit that the render projection's failure mode is worse than the
 * payload's, because a payload allowlist that is too narrow costs the
 * client a recompute while a render projection that is too narrow is a
 * silently wrong page.
 */
function validateModule(mod, where = '<render module>') {
  if (!isPlainObject(mod)) {
    refuse(CODE.MALFORMED_MODULE, `${where} did not export an object`);
  }
  if (mod.protocol !== PROTOCOL_VERSION) {
    refuse(
      CODE.MALFORMED_MODULE,
      `${where} declares protocol ${JSON.stringify(mod.protocol)}; this service speaks ${PROTOCOL_VERSION}`,
      { declared: mod.protocol, expected: PROTOCOL_VERSION },
    );
  }
  if (typeof mod.buildId !== 'string' || mod.buildId.length === 0) {
    refuse(
      CODE.MALFORMED_MODULE,
      `${where} publishes no buildId. Build identity is how a JVM host detects that the ` +
        `sidecar is serving a different application than the one it deployed against.`,
    );
  }
  if (typeof mod.render !== 'function') {
    refuse(CODE.MALFORMED_MODULE, `${where} exports no render function`);
  }
  if (!isPlainObject(mod.entries) || Object.keys(mod.entries).length === 0) {
    refuse(CODE.MALFORMED_MODULE, `${where} publishes an empty entry table`);
  }
  for (const [id, entry] of Object.entries(mod.entries)) {
    if (!isPlainObject(entry)) {
      refuse(CODE.MALFORMED_MODULE, `${where} entry ${JSON.stringify(id)} is not an object`);
    }
    const list = entry.stateAllowlist;
    if (!Array.isArray(list)) {
      refuse(
        CODE.MALFORMED_MODULE,
        `${where} entry ${JSON.stringify(id)} declares no stateAllowlist. An entry with no ` +
          `declared render-visibility list cannot be rendered — absence is a refusal, never ` +
          `a licence to read everything.`,
        { entry: id },
      );
    }
    for (const k of list) {
      if (typeof k !== 'string' || !KEY_TEXT.test(k)) {
        refuse(
          CODE.MALFORMED_MODULE,
          `${where} entry ${JSON.stringify(id)} allowlists ${JSON.stringify(k)}, which is not ` +
            `a top-level app-db key in EDN spelling`,
          { entry: id, key: k },
        );
      }
    }
  }
  return mod;
}

/**
 * Validate one request against the contract and against the tables the
 * loaded bundle published (`tables` is `{ buildId, entries }`). Returns
 * the request normalized — defaults applied, nothing added the caller did
 * not send. Throws `Refusal` on anything else.
 *
 * Order matters and is deliberate: shape, then identity, then content. A
 * caller reading its first refusal wants the field it got wrong, not the
 * consequence three checks downstream.
 */
function validateRequest(req, tables, limits = {}) {
  const { defaultTimeoutMs = 1000, maxTimeoutMs = 5000, maxRequestBytes = 1 << 20 } = limits;

  if (!isPlainObject(req)) {
    refuse(CODE.MALFORMED_REQUEST, 'a render request must be an object');
  }

  // ---- the field allowlist, first and without exception ------------------
  for (const field of Object.keys(req)) {
    if (!REQUEST_FIELDS.includes(field)) {
      const why = REFUSED_FIELDS[field];
      refuse(
        CODE.UNKNOWN_REQUEST_FIELD,
        `unknown request field ${JSON.stringify(field)}. The contract carries: ` +
          `${REQUEST_FIELDS.join(', ')}.` +
          (why ? ` ${why}` : ''),
        { field, allowed: REQUEST_FIELDS, ...(why ? { refusedOnPurpose: true } : {}) },
      );
    }
  }

  if (req.protocol !== PROTOCOL_VERSION) {
    refuse(
      CODE.PROTOCOL_VERSION,
      `request declares protocol ${JSON.stringify(req.protocol)}; this service speaks ${PROTOCOL_VERSION}`,
      { declared: req.protocol, expected: PROTOCOL_VERSION },
    );
  }

  if (typeof req.entry !== 'string' || req.entry.length === 0) {
    refuse(CODE.BAD_REQUEST_FIELD, '`entry` must be a non-empty string', { field: 'entry' });
  }
  if (req.requestId !== undefined && typeof req.requestId !== 'string') {
    refuse(CODE.BAD_REQUEST_FIELD, '`requestId` must be a string', { field: 'requestId' });
  }
  if (req.args !== undefined && typeof req.args !== 'string') {
    refuse(
      CODE.BAD_REQUEST_FIELD,
      '`args` is the root arguments as EDN TEXT, not a decoded value — the service does not ' +
        'read application data',
      { field: 'args' },
    );
  }
  if (req.buildId !== undefined && typeof req.buildId !== 'string') {
    refuse(CODE.BAD_REQUEST_FIELD, '`buildId` must be a string', { field: 'buildId' });
  }

  // ---- build identity ----------------------------------------------------
  // The per-request half of the skew detector. A JVM host that records the
  // id it deployed against turns "the two artefacts are from different
  // builds" — which otherwise has nothing to compare, and fails as two
  // different applications answering one request — into a refusal.
  if (req.buildId !== undefined && req.buildId !== tables.buildId) {
    refuse(
      CODE.BUILD_IDENTITY_MISMATCH,
      `request expects build ${JSON.stringify(req.buildId)} but this sidecar is serving ` +
        `${JSON.stringify(tables.buildId)}`,
      { expected: req.buildId, serving: tables.buildId },
    );
  }

  // ---- the entry table ---------------------------------------------------
  const entry = Object.prototype.hasOwnProperty.call(tables.entries, req.entry)
    ? tables.entries[req.entry]
    : undefined;
  if (entry === undefined) {
    refuse(CODE.UNKNOWN_ENTRY, `no entry named ${JSON.stringify(req.entry)} in this bundle`, {
      entry: req.entry,
      known: Object.keys(tables.entries),
    });
  }

  // ---- the state, and the render-visibility allowlist --------------------
  let state = {};
  if (req.state !== undefined) {
    if (!isPlainObject(req.state)) {
      refuse(
        CODE.BAD_REQUEST_FIELD,
        '`state` must be an object mapping a top-level app-db key (EDN text) to that ' +
          "key's value (EDN text)",
        { field: 'state' },
      );
    }
    let bytes = 0;
    for (const [k, v] of Object.entries(req.state)) {
      if (!KEY_TEXT.test(k)) {
        refuse(
          CODE.BAD_REQUEST_FIELD,
          `state key ${JSON.stringify(k)} is not a top-level app-db key in EDN spelling`,
          { field: 'state', key: k },
        );
      }
      if (typeof v !== 'string') {
        refuse(
          CODE.BAD_REQUEST_FIELD,
          `state value for ${k} must be EDN text; the service does not decode application data`,
          { field: 'state', key: k },
        );
      }
      // The allowlist belongs to the ENTRY, never to the request, so a
      // caller cannot widen its own allowance.
      if (!entry.stateAllowlist.includes(k)) {
        refuse(CODE.STATE_KEY_NOT_ALLOWED, `entry ${JSON.stringify(req.entry)} may not read ${k}`, {
          entry: req.entry,
          key: k,
          allowed: entry.stateAllowlist,
        });
      }
      bytes += Buffer.byteLength(k, 'utf8') + Buffer.byteLength(v, 'utf8');
    }
    if (bytes > maxRequestBytes) {
      refuse(
        CODE.REQUEST_TOO_LARGE,
        `state is ${bytes} bytes, over the ${maxRequestBytes}-byte ceiling`,
        { bytes, ceiling: maxRequestBytes },
      );
    }
    state = req.state;
  }

  // ---- the deadline ------------------------------------------------------
  let timeoutMs = defaultTimeoutMs;
  if (req.timeoutMs !== undefined) {
    if (typeof req.timeoutMs !== 'number' || !Number.isFinite(req.timeoutMs) || req.timeoutMs <= 0) {
      refuse(CODE.BAD_REQUEST_FIELD, '`timeoutMs` must be a positive finite number', {
        field: 'timeoutMs',
      });
    }
    // Clamped rather than refused: a caller asking for longer than the
    // service will ever wait has made a configuration mistake, and the
    // service's own ceiling is the one that binds either way.
    timeoutMs = Math.min(req.timeoutMs, maxTimeoutMs);
  }

  return {
    protocol: PROTOCOL_VERSION,
    entry: req.entry,
    state,
    args: req.args,
    requestId: req.requestId,
    timeoutMs,
  };
}

/** A body chunk. `seq` is monotonic per response and starts at 0. */
const chunkFrame = (seq, html) => ({ type: 'chunk', seq, html });

/**
 * The terminal success frame. Everything that is not body markup — and
 * `COMPLETE_FIELDS` is the whole of it. There is deliberately no rest
 * parameter and no spread of a caller-supplied object here: a constructor
 * that copies whatever it is handed is a field list that grows without
 * anyone editing the field list.
 */
const completeFrame = ({ chunks, renderMs, buildId, requestId }) => ({
  type: 'complete',
  chunks,
  renderMs,
  buildId,
  ...(requestId === undefined ? {} : { requestId }),
});

module.exports = {
  PROTOCOL_VERSION,
  CODE,
  REQUEST_FIELDS,
  REFUSED_FIELDS,
  COMPLETE_FIELDS,
  MODULE_RETURN_REFUSAL,
  KEY_TEXT,
  Refusal,
  refuse,
  validateModule,
  validateRequest,
  chunkFrame,
  completeFrame,
};
