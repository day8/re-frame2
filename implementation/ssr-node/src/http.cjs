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
    const bodyChunks = [];
    const hardByteCap = maxBytes * 16;
    let receivedBytes = 0;
    let overLimit = false;
    const requestTooLarge = () =>
      new Refusal(CODE.REQUEST_TOO_LARGE, `request body exceeds ${maxBytes} bytes`, {
        ceiling: maxBytes,
        bytes: receivedBytes,
      });

    req.on('data', (chunk) => {
      receivedBytes += chunk.length;
      if (receivedBytes > maxBytes) {
        if (!overLimit) {
          overLimit = true;
          bodyChunks.length = 0;
        }
        if (receivedBytes > hardByteCap) {
          req.destroy();
          reject(requestTooLarge());
        }
        return;
      }
      bodyChunks.push(chunk);
    });
    req.on('end', () => {
      if (overLimit) reject(requestTooLarge());
      else resolve(Buffer.concat(bodyChunks).toString('utf8'));
    });
    req.on('error', (error) =>
      reject(new Refusal(CODE.MALFORMED_REQUEST, error.message, {})),
    );
  });
}

/**
 * Split a trailing HIGH surrogate off a streamed chunk, to be written with
 * the next one (rf2-gwye.24).
 *
 * `emit` takes JavaScript strings and puts no code-point boundary on them,
 * so a module splitting its markup at a code-unit offset can end one chunk
 * on the first half of an astral character and start the next on the
 * second. Encoding each chunk to UTF-8 on its own turns each half into
 * U+FFFD — a 200 whose bytes differ from the buffered mode's, which joins
 * before it encodes. Holding back at most ONE code unit is the whole
 * repair: the pair is encoded together once its mate arrives, and every
 * other chunk goes out as promptly as before. One still held at the end is
 * written alone, which is exactly what the buffered join does with an
 * unmatched surrogate.
 */
function holdTrailingHighSurrogate(text) {
  const last = text.charCodeAt(text.length - 1);
  return last >= 0xd800 && last <= 0xdbff ? [text.slice(0, -1), text.slice(-1)] : [text, ''];
}

async function handleRender(service, req, res, requestUrl, { maxRequestBytes }) {
  let renderRequest;
  let requestId;
  try {
    const requestText = await readBody(req, maxRequestBytes);
    try {
      renderRequest = JSON.parse(requestText);
    } catch (error) {
      throw new Refusal(
        CODE.MALFORMED_REQUEST,
        `request body is not JSON: ${error.message}`,
        {},
      );
    }
    requestId =
      typeof renderRequest?.requestId === 'string' ? renderRequest.requestId : undefined;
    // THE ECHO IS A HEADER, AND NOT EVERY STRING IS ONE (rf2-gwye.25). An
    // ellipsis, an emoji or a CR/LF in `requestId` is a valid protocol
    // string that Node refuses at `writeHead` — which runs AFTER the render,
    // so a caller's unrepresentable token came back as a `render-threw` 500
    // from a renderer that had succeeded. Checked here instead, before an
    // isolate is acquired, and refused as the caller fault it is. The
    // in-process protocol's string domain is untouched: this is a fact
    // about HTTP, and the refusal body still carries the token back.
    if (requestId !== undefined) {
      try {
        http.validateHeaderValue('x-rf-ssr-request', requestId);
      } catch {
        throw new Refusal(
          CODE.BAD_REQUEST_FIELD,
          '`requestId` must be representable as an HTTP header value to be echoed over HTTP',
          { field: 'requestId' },
        );
      }
    }
  } catch (err) {
    sendRefusal(
      res,
      err instanceof Refusal ? err : new Refusal(CODE.MALFORMED_REQUEST, String(err), {}),
      requestId,
    );
    return;
  }

  // ONE SELECTOR. Buffered is the default and `?stream=1` is the opt-in,
  // and there is no second way to say it: the entry point also honoured an
  // `x-rf-ssr-stream: 1` request header and a `streamingDefault` serve
  // option (with a `?stream=0` escape that only ever mattered when that
  // option was on). Neither had a consumer anywhere — no CLI flag, no
  // README contract, no test, no JVM adapter — so the response framing a
  // caller got could be changed by a magic header or an unpinned
  // in-process option that the operational docs did not teach. Retired
  // under rf2-6r9j.72; `test/bytes.test.cjs` pins that the header is inert.
  // Read off the target the listener already parsed, inside its guard —
  // one parse, not a second unguarded one (rf2-gwye.22).
  const streaming = requestUrl.searchParams.get('stream') === '1';

  const bufferedHtmlChunks = [];
  let heldHighSurrogate = '';
  try {
    for await (const frame of service.renderFrames(renderRequest)) {
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
          const [text, held] = holdTrailingHighSurrogate(heldHighSurrogate + frame.html);
          heldHighSurrogate = held;
          if (text) res.write(text, 'utf8');
        } else {
          bufferedHtmlChunks.push(frame.html);
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
        if (heldHighSurrogate) res.write(heldHighSurrogate, 'utf8');
        res.end();
      } else {
        const body = bufferedHtmlChunks.join('');
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
function serve({ service, port = 8148, host = '127.0.0.1', maxRequestBytes = 1 << 20 }) {
  const server = http.createServer((req, res) => {
    // CONTAINED HERE, NOT BY THE PROCESS (rf2-gwye.22). Node's HTTP parser
    // accepts request targets the WHATWG URL constructor refuses — `//`, or
    // `http://[::1` — and this listener is synchronous with no catch above
    // it, so the throw was an uncaught exception: one caller's bad request
    // line took the whole sidecar down, and every render in flight with it.
    // It is a malformed request like any other, so it gets that refusal.
    let requestUrl;
    try {
      requestUrl = new URL(req.url, 'http://localhost');
    } catch {
      return sendRefusal(
        res,
        new Refusal(CODE.MALFORMED_REQUEST, 'the request target is not a valid URL', {}),
      );
    }
    if (req.method === 'GET' && requestUrl.pathname === '/health') {
      return handleHealth(service, res);
    }
    if (req.method === 'POST' && requestUrl.pathname === '/render') {
      return void handleRender(service, req, res, requestUrl, { maxRequestBytes });
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
    server.closeAllConnections();
    server.close(() => resolve());
  });

module.exports = { serve, statusFor, STATUS };
