'use strict';
// ONE TRANSPORT OVER THE PROTOCOL — HTTP, because a JVM caller already
// speaks it. It frames and unframes; every outcome is decided in
// `protocol.cjs` and `service.cjs`.
//
// A render is answered with one body, written once after the render has
// finished, so no status is ever sent before a refusal arrives. Its
// `Content-Length` is `Buffer.byteLength`, never `body.length`: UTF-16 code
// units undercount any non-ASCII byte and truncate the markup.

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

/** Slack a graceful close allows past the service's own answer bound, for writing the answer. */
const CLOSE_MARGIN_MS = 100;

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
 * Read the request body under a ceiling. Past the ceiling the bytes are
 * discarded but the body is still drained, so the caller reads its 413
 * rather than a broken socket; at sixteen times the ceiling the socket is
 * shut.
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
    // The echo is a header, and Node refuses an unrepresentable one at
    // `writeHead`, after the render. Refused here, before an isolate is
    // acquired, as the caller fault it is.
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
 * the node `Server`, the port actually bound (the tests bind port 0), and
 * `close`, which stops accepting, waits for the requests in flight to be
 * answered — up to the service's own answer bound — and cuts whatever is
 * left.
 *
 * Refuses a service whose `buildId` no HTTP header can carry: every 200
 * names it in `x-rf-ssr-build`, and Node refuses the header at `writeHead`,
 * after the render, so each render would be thrown away.
 */
function serve({ service, port = 8148, host = '127.0.0.1', maxRequestBytes = 1 << 20 }) {
  try {
    http.validateHeaderValue('x-rf-ssr-build', service.buildId);
  } catch {
    return Promise.reject(
      new Refusal(
        CODE.MALFORMED_MODULE,
        `the render module's buildId ${JSON.stringify(service.buildId)} cannot be sent as an ` +
          'HTTP header value; publish one with no control characters and nothing above U+00FF',
        { buildId: service.buildId },
      ),
    );
  }

  let closing = false;
  const server = http.createServer((req, res) => {
    // While closing, a connection that has just answered is closed rather
    // than kept alive for a request that would be refused.
    res.on('finish', () => {
      if (closing) server.closeIdleConnections();
    });
    // Node's parser accepts request targets the URL constructor refuses
    // (`//`, `http://[::1`), and this listener has no catch above it, so an
    // uncaught throw would take the whole sidecar down.
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

  const close = () => {
    closing = true;
    return closeServer(server, service.answerWithinMs + CLOSE_MARGIN_MS);
  };

  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => {
      resolve({ server, port: server.address().port, host, close });
    });
  });
}

const closeServer = (server, drainMs) =>
  new Promise((resolve) => {
    const cut = setTimeout(() => server.closeAllConnections(), drainMs);
    server.close(() => {
      clearTimeout(cut);
      resolve();
    });
    server.closeIdleConnections();
  });

module.exports = { serve, statusFor, STATUS };
