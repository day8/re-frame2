'use strict';
// ONE TRANSPORT OVER THE PROTOCOL.
//
// HTTP is what a JVM caller already speaks, so it is the transport this
// package ships. It is deliberately thin and deliberately NOT the
// interesting file: everything that decides an outcome lives in
// `protocol.cjs` and `service.cjs`, and this one frames and unframes.
// A Unix socket or a length-prefixed pipe would be a sibling of this
// file and would touch nothing else.
//
// ## ONE BUFFERED RESPONSE
//
// A render is answered with one body: the `html` the service returned,
// with a byte-accurate `Content-Length`, written once — or with a refusal
// instead of it. No byte of a body leaves before the render has finished,
// so there is never a status code already sent when a refusal arrives.
//
// ## CONTENT-LENGTH IS COUNTED IN BYTES
//
// `Buffer.byteLength(body, 'utf8')`, never `body.length`, which counts
// UTF-16 code units. A `Content-Length` short by the width of one em dash
// TRUNCATES the response, and what a truncated SSR body costs is the tail
// of the markup and any hydration the client was going to do with it.

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
 * reads the 413 and sees a broken connection instead — `UND_ERR_SOCKET` in
 * place of a status code, a transport fault where the service means to
 * give a diagnosable refusal.
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

async function handleRender(service, req, res, { maxRequestBytes }) {
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
    // THE ECHO IS A HEADER, AND NOT EVERY STRING IS ONE. An ellipsis, an
    // emoji or a CR/LF in `requestId` is a valid protocol string that Node
    // refuses at `writeHead` — which runs AFTER the render, so an
    // unrepresentable token would come back as a `render-threw` 500 from a
    // renderer that had succeeded. It is checked here instead, before an
    // isolate is acquired, and refused as the caller fault it is. The
    // in-process protocol's string domain is unaffected: this is a fact
    // about HTTP, and the refusal body carries the token back.
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

  try {
    const complete = await service.renderToString(renderRequest);
    res.writeHead(200, {
      'content-type': 'text/html; charset=utf-8',
      // BYTES. See the header.
      'content-length': Buffer.byteLength(complete.html, 'utf8'),
      'x-rf-ssr-build': complete.buildId,
      'x-rf-ssr-render-ms': String(complete.renderMs),
      ...(requestId ? { 'x-rf-ssr-request': requestId } : {}),
    });
    res.end(complete.html, 'utf8');
  } catch (err) {
    sendRefusal(res, err instanceof Refusal ? err : new Refusal(CODE.RENDER_THREW, String(err), {}), requestId);
  }
}

function handleHealth(service, res) {
  // A service whose pool could not replace an isolate has closed for good,
  // so it must not read healthy to whatever is deciding whether to restart it.
  const body = JSON.stringify({
    status: service.failed ? 'failed' : 'ok',
    protocol: service.protocol,
    buildId: service.buildId,
    entries: service.entries,
    isolates: service.stats(),
  });
  res.writeHead(service.failed ? 503 : 200, {
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
    // CONTAINED HERE, NOT BY THE PROCESS. Node's HTTP parser accepts
    // request targets the WHATWG URL constructor refuses — `//`, or
    // `http://[::1` — and this listener is synchronous with no catch above
    // it, so an uncaught throw would let one caller's bad request line take
    // the whole sidecar down, and every render in flight with it. It is a
    // malformed request like any other, so it gets that refusal.
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
      return void handleRender(service, req, res, { maxRequestBytes });
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
