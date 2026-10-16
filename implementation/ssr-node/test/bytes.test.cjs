'use strict';
// The bytes the renderer wrote are the bytes the client receives.
//
// Every byte row compares UTF-8 at the two ends of the crossing over
// `fixtures/bytes.cjs`, a non-ASCII corpus (3- and 4-byte characters) on
// which every wrong encoding or code-unit count differs.

const test = require('node:test');
const assert = require('node:assert');
const net = require('node:net');
const { withService, post } = require('./_support.cjs');
const { serve } = require('../src/http.cjs');
const { CODE } = require('../src/protocol.cjs');

const CORPUS = require('./fixtures/bytes.cjs');
const utf8 = (s) => Buffer.byteLength(s, 'utf8');

const BYTES_REQUEST = { protocol: 1, entry: 'app/root', state: {} };

test('the bytes out of HTTP are the same bytes, and Content-Length counts them', async () => {
  await withService('bytes', { isolates: 1 }, async (service) => {
    const http = await serve({ service, port: 0 });
    try {
      const res = await post(`http://127.0.0.1:${http.port}/render`, BYTES_REQUEST);
      assert.deepStrictEqual(
        [res.status, res.buf, Number(res.headers.get('content-length'))],
        [200, Buffer.from(CORPUS.BODY, 'utf8'), utf8(CORPUS.BODY)],
      );
    } finally {
      await http.close();
    }
  });
});

test('in process, html IS the module\'s string — nothing re-escapes or re-encodes it', async () => {
  await withService('bytes', { isolates: 1 }, async (service) => {
    const { html } = await service.renderToString(BYTES_REQUEST);
    assert.strictEqual(html, CORPUS.BODY);
  });
});

// ---------------------------------------------------------------------------
// The transport's own edges
// ---------------------------------------------------------------------------

test('a body that is not JSON is refused before anything else happens', async () => {
  await withService('reference', { isolates: 1 }, async (service) => {
    const http = await serve({ service, port: 0 });
    try {
      const res = await fetch(`http://127.0.0.1:${http.port}/render`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: 'not json at all',
      });
      assert.deepStrictEqual(
        [res.status, res.headers.get('x-rf-ssr-refusal')],
        [400, ':rf.ssr-node/malformed-request'],
      );
    } finally {
      await http.close();
    }
  });
});

test('an oversized body is refused with a STATUS, not with a broken socket', async () => {
  // Over the ceiling but inside the 16x hard cap: the transport drains the
  // request and answers 413, because a caller still writing when the socket
  // dies sees `UND_ERR_SOCKET` instead of a refusal.
  await withService('reference', { isolates: 1 }, async (service) => {
    const http = await serve({ service, port: 0, maxRequestBytes: 1024 });
    try {
      const res = await post(`http://127.0.0.1:${http.port}/render`, {
        protocol: 1,
        entry: 'app/root',
        state: { ':todos': `"${'x'.repeat(4096)}"` },
      });
      assert.deepStrictEqual(
        [res.status, res.headers.get('x-rf-ssr-refusal')],
        [413, ':rf.ssr-node/request-too-large'],
      );
    } finally {
      await http.close();
    }
  });
});

/** One raw HTTP/1.1 GET, for targets `fetch` refuses to send at all. */
function rawGet(port, target) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1', () => {
      socket.end(`GET ${target} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n`);
    });
    let text = '';
    socket.setEncoding('latin1');
    socket.on('data', (chunk) => {
      text += chunk;
    });
    socket.on('end', () => resolve(text));
    socket.on('error', reject);
    socket.setTimeout(5000, () => socket.destroy(new Error(`no answer to GET ${target}`)));
  });
}

test('a target the URL parser refuses is a 400, and the SAME service keeps serving', async () => {
  // Unguarded, the listener's throw would take the whole sidecar down: the
  // process, not this row, would fail.
  await withService('bytes', { isolates: 1 }, async (service) => {
    const http = await serve({ service, port: 0 });
    try {
      const raw = await rawGet(http.port, '//');
      assert.strictEqual(raw.split('\r\n')[0], 'HTTP/1.1 400 Bad Request', raw);
      // The header distinguishes the listener's refusal from Node's bare 400.
      assert.match(raw, /\r\nx-rf-ssr-refusal: :rf\.ssr-node\/malformed-request\r\n/i);
      const health = await fetch(`http://127.0.0.1:${http.port}/health`);
      assert.strictEqual(health.status, 200, '/health after the bad targets');
      const render = await post(`http://127.0.0.1:${http.port}/render`, BYTES_REQUEST);
      assert.deepStrictEqual([render.status, render.text], [200, CORPUS.BODY]);
      assert.strictEqual((await rawGet(http.port, '/missing')).split('\r\n')[0], 'HTTP/1.1 404 Not Found');
    } finally {
      await http.close();
    }
  });
});

test('a requestId no HTTP header can carry is refused as a caller fault, before any render', async () => {
  // `requestId` is echoed in a header, and Node refuses an unrepresentable
  // value at `writeHead` — after the render — so unchecked it would surface
  // as a `render-threw` 500. One id above U+00FF and one carrying CR/LF.
  const ids = ['correlation-…', 'correlation\r\nx-injected: 1'];
  const body = (requestId) => ({ ...BYTES_REQUEST, requestId });
  await withService('bytes', { isolates: 1 }, async (service) => {
    for (const id of ids) {
      assert.strictEqual((await service.renderToString(body(id))).requestId, id, 'in-process, any string correlates');
    }
    const rendered = [];
    const renderToString = service.renderToString.bind(service);
    service.renderToString = (request) => {
      rendered.push(request.requestId);
      return renderToString(request);
    };
    const http = await serve({ service, port: 0 });
    try {
      for (const id of ids) {
        const res = await post(`http://127.0.0.1:${http.port}/render`, body(id));
        const frame = JSON.parse(res.text);
        assert.deepStrictEqual(
          [res.status, res.headers.get('x-rf-ssr-refusal'), frame.detail.field, frame.requestId],
          [400, CODE.BAD_REQUEST_FIELD, 'requestId', id],
          JSON.stringify(id),
        );
      }
      assert.deepStrictEqual(rendered, [], 'no unrepresentable id may reach the renderer');

      // CONTROL — an ASCII id renders and is echoed, and the counter really
      // does count.
      const res = await post(`http://127.0.0.1:${http.port}/render`, body('corr-ascii-1'));
      assert.deepStrictEqual(
        [res.status, res.headers.get('x-rf-ssr-request'), res.text],
        [200, 'corr-ascii-1', CORPUS.BODY],
      );
      assert.deepStrictEqual(rendered, ['corr-ascii-1']);
    } finally {
      await http.close();
    }
  });
});
