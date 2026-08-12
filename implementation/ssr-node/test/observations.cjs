'use strict';
// TEST-ONLY INSTRUMENTATION — how a fixture reports what it saw, now that
// the response contract carries body markup and nothing else.
//
// ## WHY THIS FILE EXISTS
//
// The witnesses in this suite are mostly claims about what happened INSIDE
// an isolate: was the snapshot frozen, did the write throw, did this
// render ever overlap another, which thread served it, what state did the
// module actually read. All of it is knowledge the render module has and
// the parent does not, so it has to cross the thread boundary somehow.
//
// It used to cross on `meta`, a free-form map the module returned and the
// service forwarded onto the public `complete` frame. That was the
// egress this package was reopened for: a channel the application fills
// in, on the response leg of a contract whose stated topology is *Node
// returns the body markup, and nothing else*. The fixtures were its
// clearest demonstration — `readTodos` and `readRoute` are application
// state, and they were crossing.
//
// ## THE CHANNEL IS THE MARKUP, AND THAT IS THE POINT
//
// The observations are not deleted, because they are witnesses to four of
// the five guarantees. They come home through the one door the contract
// names: the fixture RENDERS what it observed, as an attribute on markup
// it was emitting anyway, and the test reads it back out of the body.
//
// So the instrumentation is not merely *allowed* by the contract, it is a
// standing demonstration of it. If a future change re-opened a second
// channel, nothing here would need it; if a future change closed the
// markup channel too, there would be no service left.
//
// This file is under `test/`, is required by fixtures and witnesses only,
// and nothing in `src/` knows it exists.
//
// ## BASE64, DELIBERATELY
//
// The observations carry EDN text — `"AAA"`, `{:name :a}`, `[1 2 3]` —
// which is full of quotes and braces, and one of the guarantees this suite
// checks is that the body's BYTES reach the client unaltered. An
// instrumentation channel that needed HTML escaping would be an
// instrumentation channel that could corrupt the thing it is measuring.
// Base64's alphabet is attribute-safe with no escaping at all, so the
// blob is inert markup and round-trips exactly.

/** The attribute the blob rides on. One name, spelled once. */
const ATTR = 'data-rf-test-observations';

const RE = new RegExp(`${ATTR}="([A-Za-z0-9+/=]*)"`);

/**
 * Render an observation blob as an attribute, with its leading space, so
 * a fixture can splice it into a tag it was already emitting.
 */
const encode = (obj) =>
  ` ${ATTR}="${Buffer.from(JSON.stringify(obj), 'utf8').toString('base64')}"`;

/**
 * Read the blob back out of a body. Returns `null` when the markup carries
 * none — a witness that expected observations and got `null` is reading a
 * fixture that did not emit them, which is a failure worth seeing plainly
 * rather than a `{}` it would happily assert against.
 */
function observationsIn(html) {
  const m = RE.exec(html ?? '');
  if (!m) return null;
  return JSON.parse(Buffer.from(m[1], 'base64').toString('utf8'));
}

module.exports = { ATTR, encode, observationsIn };
