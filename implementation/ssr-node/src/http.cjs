'use strict';
// ONE TRANSPORT OVER THE PROTOCOL (rf2-hic-056).
//
// HTTP is what a JVM caller already speaks, so it is the transport this
// package ships. It is deliberately thin and deliberately NOT the
// interesting file: everything that decides an outcome lives in
// `protocol.cjs` and `service.cjs`, and this one frames and unframes.
// A Unix socket or a length-prefixed pipe would be a sibling of this
// file and would touch nothing else.
//
// ## THE TWO MODES ARE ONE PROTOCOL READ TWICE
//
// BUFFERED (the default) collects the chunks, computes a byte-accurate
// `Content-Length`, and writes once. STREAMING writes each chunk as it
// arrives under chunked transfer-encoding. They differ in exactly the
// place the bead's separability constraint says the difference belongs —
// the edge — and the service beneath them cannot tell which is running.
//
// ## CONTENT-LENGTH IS COUNTED IN BYTES
//
// `Buffer.byteLength(body, 'utf8')`, never `body.length`. This repo has
// already paid for that distinction once: the SSR bake manifest claimed
// UTF-16 code units under byte-named columns, and every corpus row's
// title carries an em dash. Here the consequence would be worse than a
// wrong number in a report — a `Content-Length` short by the width of
// one em dash TRUNCATES the response, and what a truncated SSR body
// costs is the tail of the markup and any hydration the client was going
// to do with it.
//
// ## A TORN RESPONSE IS NOT A SHORT ONE
//
// If a render fails after chunks have already been written to the socket
// in streaming mode, there is no status code left to send. The socket is
// DESTROYED rather than ended, so the caller sees a broken transfer and
// treats it as a failure, instead of a well-formed short page it would
// have cached and served.

const http = require('node:http');
const { CODE, Refusal } = require('./protocol.cjs');

/** Caller fault vs ours. A JVM host retries one of these and not the other. */
const STATUS = {
  [CODE.MALFORMED_REQUEST]: 400,
  [CODE.PROTOCOL_VERSION]: 400,
  [CODE.UNKNOWN_REQUEST_FIELD]: 400,
  [CODE.BAD_REQUEST_FIELD]: 400,
  [CODE.REQUEST_TOO_LARGE]: 413,
  [CODE.UNKNOWN_ENTRY]: 400,
  [CODE.STATE_KEY_NOT_ALLOWED]: 400,
  [CODE.BUILD_IDENTITY_MISMATCH]: 409,
  [CODE.RENDER_TIMEOUT]: 504,
  [CODE.RENDER_THREW]: 500,
  [CODE.ISOLATE_LOST]: 500,
  [CODE.SERVICE_SATURATED]: 503,
  [CODE.SERVICE_CLOSED]: 503,
  [CODE.MALFORMED_MODULE]: 500,
};

const statusFor = (code) => STATUS[code] ?? 500;

function sendRefusal(res, refusal, requestId) {
  if (res.destroyed || res.writableEnded) return;
  if (res.headersSent) {
    // Nothing left to say in-band — see the header's torn-response note.
    res.destroy();
    return;
  }
  const body = JSON.stringify(refusal.toFrame(requestId));
  res.writeHead(statusFor(refusal.code), {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body, 'utf8'),
    'x-rf-ssr-refusal': refusal.code,
  });
  res.end(body);
}

/**
 * Read the request body under a ceiling.
 *
 * OVER THE CEILING, THE BYTES ARE DISCARDED BUT THE REQUEST IS STILL
 * DRAINED. Destroying the socket the moment the ceiling is crossed is the
 * obvious move and it is wrong: the caller is still writing, so it never
 * reads the 413 and sees a broken connection instead — a transport fault
 * where the service meant to give a diagnosable refusal. Measured, not
 * theorised; the first version of this function did exactly that and the
 * witness came back with `UND_ERR_SOCKET` in place of a status code.
 *
 * Draining is bounded rather than unbounded: memory is freed at the
 * ceiling, and a body still going at sixteen times it is past any
 * plausible caller mistake, so that one really does get the socket shut.
 */
function readBody(req, maxBytes) {
  return new Promise((resolve, reject) => {
    const parts = [];
    const hardCap = maxBytes * 16;
    let bytes = 0;
    let over = false;
    const tooLarge = () =>
      new Refusal(CODE.REQUEST_TOO_LARGE, `request body exceeds ${maxBytes} bytes`, {
        ceiling: maxBytes,
        bytes,
      });

    req.on('data', (c) => {
      bytes += c.length;
      if (bytes > maxBytes) {
        if (!over) {
          over = true;
          parts.length = 0;
        }
        if (bytes > hardCap) {
          req.destroy();
          reject(tooLarge());
        }
        return;
      }
      parts.push(c);
    });
    req.on('end', () => {
      if (over) reject(tooLarge());
      else resolve(Buffer.concat(parts).toString('utf8'));
    });
    req.on('error', (e) => reject(new Refusal(CODE.MALFORMED_REQUEST, e.message, {})));
  });
}

async function handleRender(service, req, res, { maxRequestBytes, streamingDefault }) {
  let parsed;
  let requestId;
  try {
    const text = await readBody(req, maxRequestBytes);
    try {
      parsed = JSON.parse(text);
    } catch (e) {
      throw new Refusal(CODE.MALFORMED_REQUEST, `request body is not JSON: ${e.message}`, {});
    }
    requestId = typeof parsed?.requestId === 'string' ? parsed.requestId : undefined;
  } catch (err) {
    sendRefusal(res, err instanceof Refusal ? err : new Refusal(CODE.MALFORMED_REQUEST, String(err), {}));
    return;
  }

  const url = new URL(req.url, 'http://localhost');
  const streaming =
    url.searchParams.get('stream') === '1' ||
    req.headers['x-rf-ssr-stream'] === '1' ||
    (streamingDefault && url.searchParams.get('stream') !== '0');

  const buffered = [];
  try {
    for await (const frame of service.renderFrames(parsed)) {
      if (frame.type === 'chunk') {
        if (streaming) {
          if (!res.headersSent) {
            // No `content-length`, so node frames the response as chunked
            // on its own. Declaring the encoding by hand as well is one
            // more place for the two to disagree.
            res.writeHead(200, {
              'content-type': 'text/html; charset=utf-8',
              'x-rf-ssr-build': service.buildId,
              ...(requestId ? { 'x-rf-ssr-request': requestId } : {}),
            });
          }
          res.write(frame.html, 'utf8');
        } else {
          buffered.push(frame.html);
        }
        continue;
      }
      // terminal
      if (streaming) {
        if (!res.headersSent) {
          // A module that emitted nothing cannot happen — the worker
          // refuses that — so this arm is only reachable if a future
          // module legitimately produces an empty body.
          res.writeHead(200, {
            'content-type': 'text/html; charset=utf-8',
            'x-rf-ssr-build': service.buildId,
          });
        }
        res.end();
      } else {
        const body = buffered.join('');
        res.writeHead(200, {
          'content-type': 'text/html; charset=utf-8',
          // BYTES. See the header.
          'content-length': Buffer.byteLength(body, 'utf8'),
          'x-rf-ssr-build': frame.buildId,
          'x-rf-ssr-chunks': String(frame.chunks),
          'x-rf-ssr-render-ms': String(frame.renderMs),
          ...(requestId ? { 'x-rf-ssr-request': requestId } : {}),
        });
        res.end(body, 'utf8');
      }
    }
  } catch (err) {
    sendRefusal(res, err instanceof Refusal ? err : new Refusal(CODE.RENDER_THREW, String(err), {}), requestId);
  }
}

function handleHealth(service, res) {
  const body = JSON.stringify({
    status: 'ok',
    protocol: service.protocol,
    buildId: service.buildId,
    entries: Object.keys(service.entries),
    isolates: service.stats(),
  });
  res.writeHead(200, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body, 'utf8'),
  });
  res.end(body);
}

/**
 * Start the HTTP transport. Resolves once the socket is listening, with
 * the node `Server` plus the port actually bound — which matters because
 * the tests bind port 0 and a fixed test port is a fixed test collision.
 */
function serve({ service, port = 8148, host = '127.0.0.1', maxRequestBytes = 1 << 20, streamingDefault = false }) {
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    if (req.method === 'GET' && url.pathname === '/health') return handleHealth(service, res);
    if (req.method === 'POST' && url.pathname === '/render') {
      return void handleRender(service, req, res, { maxRequestBytes, streamingDefault });
    }
    const body = JSON.stringify({
      type: 'refusal',
      code: CODE.MALFORMED_REQUEST,
      message: 'this service answers POST /render and GET /health',
    });
    res.writeHead(404, {
      'content-type': 'application/json; charset=utf-8',
      'content-length': Buffer.byteLength(body, 'utf8'),
    });
    res.end(body);
  });

  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => {
      resolve({ server, port: server.address().port, host, close: () => closeServer(server) });
    });
  });
}

const closeServer = (server) =>
  new Promise((resolve) => {
    server.closeAllConnections?.();
    server.close(() => resolve());
  });

module.exports = { serve, statusFor, STATUS };
