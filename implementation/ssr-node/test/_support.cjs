'use strict';
// Shared scaffolding for the suite. Nothing here decides an outcome; it
// only saves each witness from re-spelling a path and a teardown.

const path = require('node:path');
const { createService } = require('../src/service.cjs');

const fixture = (name) => path.join(__dirname, 'fixtures', `${name}.cjs`);

/** Boot a service against a fixture module. Absolute path, always. */
const boot = (name, opts = {}) => createService({ modulePath: fixture(name), ...opts });

/**
 * Run `fn(service)` and close the service whichever way it went. A leaked
 * worker thread keeps the process alive and turns a red assertion into a
 * hang, which is a much worse thing to debug than a failure.
 */
async function withService(name, opts, fn) {
  const service = await boot(name, opts);
  try {
    return await fn(service);
  } finally {
    await service.close();
  }
}

/** Collect a whole frame sequence. Returns `{ chunks, complete }`. */
async function collect(service, request) {
  const chunks = [];
  let complete = null;
  for await (const frame of service.renderFrames(request)) {
    if (frame.type === 'chunk') chunks.push(frame);
    else complete = frame;
  }
  return { chunks, complete };
}

/**
 * The refusal a call produced, or `null` if it produced none. Written as a
 * helper because "assert it refused" and "assert it refused with THIS
 * code" are different claims and the second is the one worth making.
 */
async function refusalOf(fn) {
  try {
    await fn();
    return null;
  } catch (err) {
    return err;
  }
}

/** Minimal JSON-over-HTTP client, so the transport tests need no dependency. */
async function post(url, body, headers = {}) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...headers },
    body: JSON.stringify(body),
  });
  const buf = Buffer.from(await res.arrayBuffer());
  return { status: res.status, headers: res.headers, buf, text: buf.toString('utf8') };
}

module.exports = { fixture, boot, withService, collect, refusalOf, post };
