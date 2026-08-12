'use strict';
// THE HYDRATION BYTES, AND THE SEPARABLE PROTOCOL.
//
//     node implementation/ssr-node/test/bytes.test.cjs
//
// ## What a byte test at THIS layer can and cannot claim
//
// The client-side hydration contract is `rf2-hic-046`'s: that a Hicasso
// server render's bytes are adopted by a hydrating client, per surface,
// with React asked whether it found a mismatch rather than the final DOM
// merely asserted. That work is landed, it is mandatory, and it does not
// wait on this service.
//
// What THIS package owes the client is narrower and is entirely its own:
// **the bytes the renderer wrote are the bytes the client receives.** A
// service that re-encoded, re-escaped, truncated or reordered on the way
// past would break a hydration contract that was correct when it was
// measured, one layer away from where anyone would look. So every row
// here compares a SHA-256 over UTF-8 bytes at the two ends of the
// crossing, over a corpus chosen to discriminate.
//
// The corpus is `fixtures/bytes.cjs` and it is not ASCII: an em dash and
// an ellipsis (1 code unit, 3 bytes each), an astral-plane clef (2 code
// units, 4 bytes — the only input that separates all three candidate
// accountings), the escapes a payload script most often gets wrong, and
// some Latin-1 and CJK. An ASCII corpus would pass under every wrong
// encoding.
//
// The negative control is the row that makes the rest mean anything: the
// same body re-encoded latin1 must produce a DIFFERENT digest. Without it
// a comparison of two identically-broken values would read green.
//
// ## Why Content-Length is a hydration concern and not a nicety
//
// This repo has already paid once for `String.prototype.length` standing
// in for bytes — the SSR bake manifest claimed UTF-16 code units under
// byte-named columns, and every corpus row's title carries an em dash. At
// the HTTP layer the same mistake does not produce a wrong number in a
// report; it TRUNCATES the response by the width of the error, and what a
// truncated SSR body costs is the tail of the markup and whatever
// hydration the client was going to do with it.

const test = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const { withService, collect, post } = require('./_support.cjs');
const { serve } = require('../src/http.cjs');

const CORPUS = require('./fixtures/bytes.cjs');
const sha256 = (s) => crypto.createHash('sha256').update(s, 'utf8').digest('hex');
const utf8 = (s) => Buffer.byteLength(s, 'utf8');

const req = () => ({ protocol: 1, entry: 'app/root', state: {} });

test('the corpus discriminates — it is not accidentally ASCII', () => {
  assert.notStrictEqual(
    utf8(CORPUS.BODY),
    CORPUS.BODY.length,
    'a corpus whose byte length equals its code-unit length proves nothing about encoding',
  );
  assert.ok(CORPUS.BODY.includes('—'), 'em dash');
  assert.ok(CORPUS.BODY.includes('…'), 'ellipsis');
  assert.ok(CORPUS.BODY.includes('\u{1D11E}'), 'astral-plane clef');
});

test('NEGATIVE CONTROL — a re-encoded body has a different digest', () => {
  // If this row ever agreed, every comparison below would be vacuous.
  const mangled = Buffer.from(CORPUS.BODY, 'utf8').toString('latin1');
  assert.notStrictEqual(sha256(mangled), sha256(CORPUS.BODY));
});

test('the bytes out of the SERVICE are the bytes the module wrote', async () => {
  await withService('bytes', { isolates: 1 }, async (service) => {
    const { chunks } = await collect(service, req());
    const body = chunks.map((c) => c.html).join('');
    assert.strictEqual(sha256(body), sha256(CORPUS.BODY));
    assert.strictEqual(utf8(body), utf8(CORPUS.BODY));
  });
});

test('the bytes out of HTTP are the same bytes, and Content-Length counts them', async () => {
  await withService('bytes', { isolates: 1 }, async (service) => {
    const http = await serve({ service, port: 0 });
    try {
      const res = await post(`http://127.0.0.1:${http.port}/render`, req());
      assert.strictEqual(res.status, 200);
      assert.strictEqual(sha256(res.text), sha256(CORPUS.BODY), 'the transport altered the bytes');

      const declared = Number(res.headers.get('content-length'));
      assert.strictEqual(declared, utf8(CORPUS.BODY), 'Content-Length must be UTF-8 BYTES');
      assert.strictEqual(res.buf.length, declared, 'the socket delivered what the header promised');
      assert.notStrictEqual(
        declared,
        CORPUS.BODY.length,
        'a corpus where bytes and code units agree could not have caught this',
      );
      assert.strictEqual(res.headers.get('x-rf-ssr-build'), 'bytes-build-1');
    } finally {
      await http.close();
    }
  });
});

test('the STREAMING mode delivers byte-identical output to the buffered one', async () => {
  // Two readings of one protocol. If they disagreed on a single byte, the
  // separability claim would be a claim about two different semantics.
  await withService('chunked', { isolates: 1 }, async (service) => {
    const http = await serve({ service, port: 0 });
    const body = { protocol: 1, entry: 'app/root', state: { ':bytes': '["<a>","—","<c/>"]' } };
    try {
      const buffered = await post(`http://127.0.0.1:${http.port}/render`, body);
      const streamed = await post(`http://127.0.0.1:${http.port}/render?stream=1`, body);
      assert.strictEqual(buffered.status, 200);
      assert.strictEqual(streamed.status, 200);
      assert.strictEqual(sha256(streamed.text), sha256(buffered.text));
      assert.strictEqual(buffered.headers.get('content-length'), String(utf8(buffered.text)));
      assert.strictEqual(
        streamed.headers.get('content-length'),
        null,
        'a streamed response cannot know its length in advance — that is the point',
      );
    } finally {
      await http.close();
    }
  });
});

// ---------------------------------------------------------------------------
// Separability: N chunks stay N chunks
// ---------------------------------------------------------------------------

test('a multi-chunk render arrives as multiple frames — nothing joins on the way', async () => {
  await withService('chunked', { isolates: 1 }, async (service) => {
    const parts = ['<a>', '—', '<c/>', '\u{1D11E}'];
    const { chunks, complete } = await collect(service, {
      protocol: 1,
      entry: 'app/root',
      state: { ':bytes': JSON.stringify(parts) },
    });
    assert.strictEqual(
      chunks.length,
      parts.length,
      'a middle layer that joined would report a single chunk here',
    );
    assert.deepStrictEqual(chunks.map((c) => c.seq), [0, 1, 2, 3], 'seq is monotonic from 0');
    assert.deepStrictEqual(chunks.map((c) => c.html), parts, 'in order, unaltered');
    assert.strictEqual(complete.chunks, parts.length);
    assert.strictEqual(sha256(chunks.map((c) => c.html).join('')), sha256(parts.join('')));
  });
});

test('a single-chunk render is the SAME protocol, not a special case', async () => {
  await withService('bytes', { isolates: 1 }, async (service) => {
    const { chunks, complete } = await collect(service, req());
    assert.strictEqual(chunks.length, 1);
    assert.strictEqual(chunks[0].seq, 0);
    assert.strictEqual(complete.chunks, 1);
    assert.strictEqual(complete.type, 'complete');
  });
});

test('renderToString is a WRAPPER — it joins what the frames already carried', async () => {
  await withService('chunked', { isolates: 1 }, async (service) => {
    const parts = ['<p>one</p>', '<p>two</p>'];
    const out = await service.renderToString({
      protocol: 1,
      entry: 'app/root',
      state: { ':bytes': JSON.stringify(parts) },
    });
    assert.strictEqual(out.html, parts.join(''));
    assert.strictEqual(out.chunks, 2, 'the join happened at the edge; the count survived it');
  });
});

// ---------------------------------------------------------------------------
// Determinism, on the model of the spike witness's X1(a)
// ---------------------------------------------------------------------------

test('two renders of one request are byte-identical; a changed input moves the digest', async () => {
  await withService('reference', { isolates: 2 }, async (service) => {
    const request = (todos) => ({ protocol: 1, entry: 'app/root', state: { ':todos': todos } });
    const a = await service.renderToString(request('"eight"'));
    const b = await service.renderToString(request('"eight"'));
    assert.strictEqual(sha256(a.html), sha256(b.html));
    // Byte identity is a claim two renders of nothing also satisfy, so
    // move one input and require the digest to move with it.
    const c = await service.renderToString(request('"nine"'));
    assert.notStrictEqual(sha256(c.html), sha256(a.html));
  });
});

// ---------------------------------------------------------------------------
// The transport's own edges
// ---------------------------------------------------------------------------

test('a refusal comes back as JSON with its code, and with no markup', async () => {
  await withService('reference', { isolates: 1 }, async (service) => {
    const http = await serve({ service, port: 0 });
    try {
      const res = await post(`http://127.0.0.1:${http.port}/render`, {
        protocol: 1,
        entry: 'app/root',
        state: { ':secrets': '1' },
      });
      assert.strictEqual(res.status, 400);
      assert.strictEqual(res.headers.get('x-rf-ssr-refusal'), ':rf.ssr-node/state-key-not-allowed');
      const frame = JSON.parse(res.text);
      assert.strictEqual(frame.type, 'refusal');
      assert.strictEqual(frame.detail.key, ':secrets');
    } finally {
      await http.close();
    }
  });
});

test('the refusal status separates a caller fault from ours', async () => {
  await withService('hang', { isolates: 1, admissionTimeoutMs: 10000 }, async (service) => {
    const http = await serve({ service, port: 0 });
    try {
      const bad = await post(`http://127.0.0.1:${http.port}/render`, { protocol: 9 });
      assert.strictEqual(bad.status, 400, 'a protocol mismatch is the caller to fix');
      const slow = await post(`http://127.0.0.1:${http.port}/render`, {
        protocol: 1,
        entry: 'app/root',
        state: {},
        timeoutMs: 150,
      });
      assert.strictEqual(slow.status, 504, 'a deadline is a gateway timeout, not a bad request');
    } finally {
      await http.close();
    }
  });
});

test('/health publishes the build identity and the entry table', async () => {
  await withService('reference', { isolates: 2 }, async (service) => {
    const http = await serve({ service, port: 0 });
    try {
      const res = await fetch(`http://127.0.0.1:${http.port}/health`);
      const body = await res.json();
      assert.strictEqual(res.status, 200);
      assert.strictEqual(body.buildId, 'reference-build-1');
      assert.strictEqual(body.protocol, 1);
      assert.deepStrictEqual(body.entries.sort(), ['app/other', 'app/root']);
      assert.strictEqual(body.isolates.total, 2);
    } finally {
      await http.close();
    }
  });
});

test('a body that is not JSON is refused before anything else happens', async () => {
  await withService('reference', { isolates: 1 }, async (service) => {
    const http = await serve({ service, port: 0 });
    try {
      const res = await fetch(`http://127.0.0.1:${http.port}/render`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: 'not json at all',
      });
      assert.strictEqual(res.status, 400);
      assert.strictEqual(
        res.headers.get('x-rf-ssr-refusal'),
        ':rf.ssr-node/malformed-request',
      );
    } finally {
      await http.close();
    }
  });
});

test('an oversized body is refused with a STATUS, not with a broken socket', async () => {
  // Over the ceiling but inside the hard cap (16x), so the transport
  // discards the bytes, drains the request, and answers 413. A caller that
  // is still writing when the socket dies never reads its refusal, which
  // is the failure this row exists to keep fixed — the first version of
  // `readBody` destroyed the socket here and the witness came back with
  // `UND_ERR_SOCKET` in place of a status code.
  await withService('reference', { isolates: 1 }, async (service) => {
    const http = await serve({ service, port: 0, maxRequestBytes: 1024 });
    try {
      const res = await post(`http://127.0.0.1:${http.port}/render`, {
        protocol: 1,
        entry: 'app/root',
        state: { ':todos': `"${'x'.repeat(4096)}"` },
      });
      assert.strictEqual(res.status, 413);
      assert.strictEqual(res.headers.get('x-rf-ssr-refusal'), ':rf.ssr-node/request-too-large');
    } finally {
      await http.close();
    }
  });
});

test('the state ceiling also binds INSIDE the protocol, not only at the socket', async () => {
  // Two independent ceilings on purpose: the transport's, which is about
  // how many bytes it will read, and the protocol's, which is about how
  // much state an entry may be handed. A body small enough for the socket
  // can still be too much state.
  await withService('reference', { isolates: 1, maxRequestBytes: 32 }, async (service) => {
    const http = await serve({ service, port: 0, maxRequestBytes: 1 << 20 });
    try {
      const res = await post(`http://127.0.0.1:${http.port}/render`, {
        protocol: 1,
        entry: 'app/root',
        state: { ':todos': `"${'x'.repeat(200)}"` },
      });
      assert.strictEqual(res.status, 413);
    } finally {
      await http.close();
    }
  });
});
