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
// ## TWO PARTITIONS CROSS, AND EACH HAS ITS OWN ALLOWLIST
//
// A settled frame is two partitions — the application's app-db and the
// framework's runtime-db (the route slice, the machine snapshots) — and a
// render reads both. So the request carries both, in the same per-key
// EDN-text shape: `state` for the app-db partition and `runtime` for the
// runtime-db partition, each gated by an ENTRY-owned list (`stateAllowlist`
// / `runtimeAllowlist`). The JVM projects them with
// `re-frame.ssr.render-state/project` under its own `:render-state` policy
// (rf2-8arzr shared contract S3/S4) and the bundle's entry seeds a fresh
// frame from them with `render-state/restore!` — the framework decided that
// install door, which is why the gap an earlier revision of the README
// carried openly is closed. The invariants did not move: the service still
// never decodes application data, an entry with no list for a partition
// cannot be rendered, and a caller cannot widen its own allowance.
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
  'runtime',
  'args',
  'buildId',
  'timeoutMs',
  'requestId',
]);

/**
 * The two frame-state partitions a request carries, as [request field,
 * entry allowlist field, what the keys are]. One table, so the request
 * validator and the module validator cannot disagree about which
 * partitions exist.
 */
const PARTITIONS = Object.freeze([
  Object.freeze({ field: 'state', allowlist: 'stateAllowlist', keys: 'app-db' }),
  Object.freeze({ field: 'runtime', allowlist: 'runtimeAllowlist', keys: 'runtime-db' }),
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
 *
 * THE ACCEPTED SET IS `{ undefined }` and nothing else. The door's first
 * cut spared `null` as well, on the reading that `null` is a kind of
 * nothing; it is not. Falling off the end of a function produces
 * `undefined`, so `undefined` is what absence looks like here, while
 * `return null` is a sentence someone wrote — and it is the likeliest
 * deliberate return this contract will ever be handed, because it reads
 * as "nothing to say". A guarantee that admits one value is fail-closed
 * except, with the exception on the most probable path.
 */
const MODULE_RETURN_REFUSAL =
  'the render module returned a value. `emit` is its only output channel: this contract ' +
  'carries body markup and nothing else, so a second way out of the isolate is application ' +
  'data crossing a boundary that has no policy for it — the host fork arriving by increments. ' +
  'Falling off the end — `undefined` — is the only accepted return; `null` is a value, not ' +
  'an absence. Render what the module has to say, and return nothing.';

/**
 * What a caller is told when the render module threw.
 *
 * ONE WORDING FOR EVERY RENDER EXCEPTION, and the wording is this
 * contract's rather than the module's — which is the whole of the fix, so
 * it is worth saying why a fixed string beats the obvious alternative.
 *
 * A thrown `Error` is the OTHER way out of a render, and it was carrying
 * what the returned value is refused for. `message` is built from the
 * value being processed in every renderer worth the name — React names the
 * property that was undefined, a validator quotes the input, a template
 * interpolates the row — so this is not a leak that needs a module doing
 * something strange; it is a leak that needs a module having a bug.
 * `detail` is worse: nothing bounds what an application hangs on it. Both
 * crossed into the public `Refusal` and into the HTTP JSON body.
 *
 * `code` is the same untrusted property and a distinct failure. It is a
 * bare field on an ordinary `Error`, and `http.cjs`'s `statusFor` maps it
 * to a status — so a module could describe its own bug in this service's
 * closed vocabulary and present a render fault as a 400 the caller blames
 * itself for, a 503 a retry policy sleeps on, or a 504. The refusal
 * taxonomy is a fact about THIS SERVICE'S crossing; the application is not
 * one of its authors.
 *
 * WHY NOT A REDACTED OR TRUNCATED MESSAGE. Because there is no rule that
 * separates the safe part of an application's exception from the rest, and
 * a rule that only usually holds is the shape this file already refuses on
 * the request leg. The diagnosis is not lost — the worker writes the real
 * exception, stack and all, to the sidecar's stderr, which is where an
 * operator already reads this package (`bin/serve.cjs` writes there under
 * the same prefix). Two audiences, two channels: the caller across the
 * wire gets an honest, closed refusal; the operator inside the process
 * gets everything.
 */
const RENDER_THREW_REFUSAL =
  'the render module threw. Its exception belongs to the application, not to this contract: ' +
  'the message, the `detail` and any `code` it carried are the module\'s own and do not cross ' +
  'this boundary — a diagnostic that echoed them would be application data leaving through ' +
  'the error channel, which is the same egress the response leg refuses wearing a different ' +
  'frame type, and a module-chosen `code` would let a render fault present itself as a ' +
  'caller fault, a saturation or a deadline. The full exception, with its stack, is on the ' +
  'sidecar\'s stderr for the operator.';

/**
 * What a caller is told when the isolate died under their render.
 *
 * THE SAME LAW AS `RENDER_THREW_REFUSAL`, STATED BY THE OTHER RECEIVER —
 * and that there were two receivers is the whole of why this constant
 * exists. `RENDER_THREW_REFUSAL` closes the throw `worker.cjs` is standing
 * in front of: the module throws while the service is inside
 * `await renderModule.render()`, so a `try` has it. A throw from a callback
 * the render SCHEDULED has no `try` above it anywhere — a timer, an
 * unhandled `.then`, an event listener — so it is an uncaught exception in
 * the worker thread. Node terminates the thread and raises `'error'` on the
 * parent's `Worker`, and `isolate.cjs` builds the refusal from an `Error`
 * that came from the application.
 *
 * It carried `err.message` and `err.stack`. The message is the module's own
 * wording, leaking for the reason `RENDER_THREW_REFUSAL` sets out at
 * length; the stack is worse than the message, because on top of that
 * wording it names every absolute path in the deployment's filesystem.
 * Both were on the public `Refusal` and in the HTTP JSON body.
 *
 * THE CODE STAYS `ISOLATE_LOST` AND IS NOT SMOOTHED INTO `RENDER_THREW`.
 * The two are different facts and the caller acts on the difference: a
 * render that threw leaves a healthy isolate the pool may reuse, while this
 * one is a dead thread the pool must replace. Closing the wording is not a
 * licence to describe a crashed worker as a reusable one.
 *
 * The diagnosis is not lost. `isolate.cjs` writes the real exception, stack
 * and all, to the sidecar's stderr under the prefix `bin/serve.cjs` already
 * uses — and on this path that is not merely the better channel, it is the
 * only one there has ever been: the worker never caught anything, so its
 * own `reportRenderException` never ran. Before this, an operator's sole
 * copy of the fault was the one the caller was wrongly being handed.
 */
const ISOLATE_LOST_REFUSAL =
  'the isolate died while rendering. The exception belongs to the application, not to this ' +
  'contract: its message and its stack are the module\'s own — and the stack names the ' +
  'server\'s filesystem besides — so neither crosses this boundary, for the same reason a ' +
  'render that threw inside the call does not carry its wording out. The render did not ' +
  'finish and this isolate is not reusable; the pool replaces it. The full exception, with ' +
  'its stack, is on the sidecar\'s stderr for the operator.';

/**
 * What a caller waiting for capacity is told when the pool could not
 * replace a terminated isolate.
 *
 * THE SAME LAW AGAIN, STATED BY A THIRD RECEIVER — and this one exists
 * because a refusal's AUDIENCE changed under it rather than because a new
 * refusal was needed. `isolate.cjs` builds one refusal for every boot
 * failure, and it carries the module's own `message` and `stack` on
 * purpose: boot fails before the service listens, so its reader is the
 * operator standing at the process they just started. That reasoning is
 * sound for `Pool.start()` and false for `Pool._startReplacement()`. A
 * replacement boots while the service is LIVE, and `Pool.release()` hands
 * its failure to everyone queued in `acquire()` — a caller across a wire,
 * reading a refusal written for somebody standing at a terminal.
 *
 * IT WAS THE WIDEST OF THE THREE LEAKS. The module's boot wording, the
 * absolute module path this deployment was pointed at, and — because the
 * boot receiver builds its `Refusal` straight from the posted `code`
 * without consulting `isRefusalCode` — a code the MODULE chose, which is
 * the spoof `RENDER_THREW_REFUSAL` closes on the render path arriving on
 * the boot path instead. A module that types `:rf.ssr-node/service-
 * saturated` onto its boot error turns its own broken bundle into the 503
 * a caller's retry policy sleeps on.
 *
 * THE CODE STAYS `ISOLATE_LOST`, for the reason its own constant gives:
 * an isolate really was lost and really was not replaced, and a caller
 * acts on that rather than on why the boot failed.
 *
 * The diagnosis moves to the operator, and on this path that is a strict
 * improvement rather than a trade. The handler's only statement used to be
 * the loop over waiters, so a replacement that failed with NOBODY queued
 * told no one at all: the pool shrank by an isolate in silence. The stderr
 * write is therefore unconditional.
 */
const REPLACEMENT_FAILED_REFUSAL =
  'the service could not replace a terminated isolate, so the capacity you were waiting for ' +
  'will not arrive. Why the replacement would not boot belongs to the application and to this ' +
  'deployment, not to this contract: the module\'s own message, the `code` it carried and the ' +
  'path this service was pointed at do not cross this boundary — a module-chosen `code` would ' +
  'let a broken bundle present itself as a caller fault, a saturation or a deadline, and the ' +
  'path names the server\'s filesystem. The full failure, with its stack, is on the sidecar\'s ' +
  'stderr for the operator.';

/**
 * Is this one of the refusal codes this service publishes?
 *
 * The family is closed — `CODE` is the whole of it — and this is the test
 * that says so at the boundary that BUILDS a public `Refusal`, so that a
 * code arriving from anywhere else cannot reach `statusFor` and be given a
 * status of its own choosing.
 */
const REFUSAL_CODES = Object.freeze(new Set(Object.values(CODE)));
const isRefusalCode = (code) => REFUSAL_CODES.has(code);

/**
 * A top-level partition key, as its EDN text. Keywords in re-frame2 are
 * what top-level app-db and runtime-db keys are (Spec 011 §`:rf/app-db`
 * projection; Conventions §Reserved runtime-db keys), so the wire spelling
 * is a leading colon and no whitespace. Deliberately not a full keyword
 * grammar: this is a boundary check, and a boundary that reimplements the
 * reader is a boundary with a reader's bugs.
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

const isPlainObject = (value) =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

/**
 * One entry's allowlist for one partition: present, an array, every
 * member a top-level key in EDN spelling. Absence is a refusal — see
 * `validateModule`.
 */
function validateAllowlist(
  moduleLabel,
  entryId,
  entryConfig,
  { allowlist: allowlistField, keys: partitionLabel },
) {
  const allowedKeys = entryConfig[allowlistField];
  if (!Array.isArray(allowedKeys)) {
    refuse(
      CODE.MALFORMED_MODULE,
      `${moduleLabel} entry ${JSON.stringify(entryId)} declares no ${allowlistField}. An entry with no ` +
        `declared render-visibility list for the ${partitionLabel} partition cannot be rendered — ` +
        `absence is a refusal, never a licence to read everything.`,
      { entry: entryId, allowlist: allowlistField },
    );
  }
  for (const allowedKey of allowedKeys) {
    if (typeof allowedKey !== 'string' || !KEY_TEXT.test(allowedKey)) {
      refuse(
        CODE.MALFORMED_MODULE,
        `${moduleLabel} entry ${JSON.stringify(entryId)} ${allowlistField} carries ${JSON.stringify(allowedKey)}, ` +
          `which is not a top-level ${partitionLabel} key in EDN spelling`,
        { entry: entryId, allowlist: allowlistField, key: allowedKey },
      );
    }
  }
}

/**
 * Validate the render module a service was pointed at.
 *
 * Fail-closed here too, and for the sharpest reason in the file: an entry
 * with no `stateAllowlist` — or no `runtimeAllowlist` — is not an entry
 * that may read everything, it is an entry that cannot be rendered. A
 * permissive default on EITHER list is the whole-app-db-by-accident
 * failure the framework's payload policy was written to prevent, one wire
 * over — and the pricing dossier is explicit that the render projection's
 * failure mode is worse than the payload's, because a payload allowlist
 * that is too narrow costs the client a recompute while a render
 * projection that is too narrow is a silently wrong page. An entry that
 * reads nothing from a partition declares the EMPTY list for it; that is
 * a decision, and absence is not.
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
  if (!isPlainObject(renderModule.entries) || Object.keys(renderModule.entries).length === 0) {
    refuse(CODE.MALFORMED_MODULE, `${moduleLabel} publishes an empty entry table`);
  }
  for (const [entryId, entryConfig] of Object.entries(renderModule.entries)) {
    if (!isPlainObject(entryConfig)) {
      refuse(
        CODE.MALFORMED_MODULE,
        `${moduleLabel} entry ${JSON.stringify(entryId)} is not an object`,
      );
    }
    for (const partition of PARTITIONS) {
      validateAllowlist(moduleLabel, entryId, entryConfig, partition);
    }
  }
  return renderModule;
}

/**
 * Validate one partition of a request — `state` or `runtime` — against
 * the entry's allowlist for it. Returns `{ partition, bytes }`: the
 * partition as sent (an empty object when the field was absent) and the
 * UTF-8 byte count of every key and value in it, so the caller can hold
 * both partitions to one ceiling. Throws `Refusal`.
 */
function validatePartition(
  request,
  entryConfig,
  entryId,
  { field: partitionField, allowlist: allowlistField, keys: partitionLabel },
) {
  const partition = request[partitionField];
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
    // The allowlist belongs to the ENTRY, never to the request, so a
    // caller cannot widen its own allowance — for either partition.
    if (!entryConfig[allowlistField].includes(partitionKey)) {
      refuse(
        CODE.STATE_KEY_NOT_ALLOWED,
        `entry ${JSON.stringify(entryId)} may not read ${partitionKey}`,
        {
          entry: entryId,
          field: partitionField,
          key: partitionKey,
          allowed: entryConfig[allowlistField],
        },
      );
    }
    partitionBytes +=
      Buffer.byteLength(partitionKey, 'utf8') + Buffer.byteLength(ednText, 'utf8');
  }
  return { partition, bytes: partitionBytes };
}

/**
 * Validate one request against the contract and against the tables the
 * loaded bundle published (`moduleTables` is `{ buildId, entries }`). Returns
 * the request normalized — defaults applied, nothing added the caller did
 * not send. Throws `Refusal` on anything else.
 *
 * Order matters and is deliberate: shape, then identity, then content. A
 * caller reading its first refusal wants the field it got wrong, not the
 * consequence three checks downstream.
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

  if (request.protocol !== PROTOCOL_VERSION) {
    refuse(
      CODE.PROTOCOL_VERSION,
      `request declares protocol ${JSON.stringify(request.protocol)}; this service speaks ${PROTOCOL_VERSION}`,
      { declared: request.protocol, expected: PROTOCOL_VERSION },
    );
  }

  if (typeof request.entry !== 'string' || request.entry.length === 0) {
    refuse(CODE.BAD_REQUEST_FIELD, '`entry` must be a non-empty string', { field: 'entry' });
  }
  if (request.requestId !== undefined && typeof request.requestId !== 'string') {
    refuse(CODE.BAD_REQUEST_FIELD, '`requestId` must be a string', { field: 'requestId' });
  }
  if (request.args !== undefined && typeof request.args !== 'string') {
    refuse(
      CODE.BAD_REQUEST_FIELD,
      '`args` is the root arguments as EDN TEXT, not a decoded value — the service does not ' +
        'read application data',
      { field: 'args' },
    );
  }
  if (request.buildId !== undefined && typeof request.buildId !== 'string') {
    refuse(CODE.BAD_REQUEST_FIELD, '`buildId` must be a string', { field: 'buildId' });
  }

  // ---- build identity ----------------------------------------------------
  // The per-request half of the skew detector. A JVM host that records the
  // id it deployed against turns "the two artefacts are from different
  // builds" — which otherwise has nothing to compare, and fails as two
  // different applications answering one request — into a refusal.
  if (request.buildId !== undefined && request.buildId !== moduleTables.buildId) {
    refuse(
      CODE.BUILD_IDENTITY_MISMATCH,
      `request expects build ${JSON.stringify(request.buildId)} but this sidecar is serving ` +
        `${JSON.stringify(moduleTables.buildId)}`,
      { expected: request.buildId, serving: moduleTables.buildId },
    );
  }

  // ---- the entry table ---------------------------------------------------
  const entryConfig = Object.prototype.hasOwnProperty.call(moduleTables.entries, request.entry)
    ? moduleTables.entries[request.entry]
    : undefined;
  if (entryConfig === undefined) {
    refuse(CODE.UNKNOWN_ENTRY, `no entry named ${JSON.stringify(request.entry)} in this bundle`, {
      entry: request.entry,
      known: Object.keys(moduleTables.entries),
    });
  }

  // ---- the two partitions, and their render-visibility allowlists --------
  const stateValidation = validatePartition(request, entryConfig, request.entry, PARTITIONS[0]);
  const runtimeValidation = validatePartition(request, entryConfig, request.entry, PARTITIONS[1]);
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
  if (request.timeoutMs !== undefined) {
    if (
      typeof request.timeoutMs !== 'number' ||
      !Number.isFinite(request.timeoutMs) ||
      request.timeoutMs <= 0
    ) {
      refuse(CODE.BAD_REQUEST_FIELD, '`timeoutMs` must be a positive finite number', {
        field: 'timeoutMs',
      });
    }
    // Clamped rather than refused: a caller asking for longer than the
    // service will ever wait has made a configuration mistake, and the
    // service's own ceiling is the one that binds either way.
    timeoutMs = Math.min(request.timeoutMs, maxTimeoutMs);
  }

  return {
    protocol: PROTOCOL_VERSION,
    entry: request.entry,
    state: stateValidation.partition,
    runtime: runtimeValidation.partition,
    args: request.args,
    requestId: request.requestId,
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
  PARTITIONS,
  REFUSED_FIELDS,
  COMPLETE_FIELDS,
  MODULE_RETURN_REFUSAL,
  RENDER_THREW_REFUSAL,
  ISOLATE_LOST_REFUSAL,
  REPLACEMENT_FAILED_REFUSAL,
  isRefusalCode,
  Refusal,
  validateModule,
  validateRequest,
  chunkFrame,
  completeFrame,
};
