// Shared SDK transport, watchdog, catalogue-ratchet, and envelope helpers.
// `runWithWatchdog` owns a single primary server and exits 0/1/2 for pass,
// assertion failure, or timeout. Tests that need secondary servers use
// `connectServer` and register each client so the same watchdog can reap it.

const { Client } = require('@modelcontextprotocol/sdk/client/index.js');
const { StdioClientTransport } = require('@modelcontextprotocol/sdk/client/stdio.js');
const { ErrorCode, McpError } = require('@modelcontextprotocol/sdk/types.js');
const { decodeDedupEnvelope } = require('../lib/dedup-envelope.cjs');

const CLIENT_VERSION = '0.1.0';

// `onClient` runs before connect awaits, making a hung initialize handshake
// reachable by the caller's watchdog. Only the client is returned: the SDK
// `Protocol` takes ownership of the transport at `connect()`, so `client.close()`
// is the single teardown handle and a second one would invite a double close.
async function connectServer({ transportSpec, clientName, stderrPrefix = '[server]', onClient }) {
  const transport = new StdioClientTransport({ ...transportSpec, stderr: 'pipe' });
  const client = new Client({ name: clientName, version: CLIENT_VERSION }, { capabilities: {} });
  // Publish the teardown handle before the initialize await.
  if (onClient) onClient(client);
  // Prefix stderr so concurrent secondary servers remain distinguishable.
  transport.stderr?.on('data', (d) => process.stderr.write(stderrPrefix + ' ' + d.toString()));
  // SDK validation happens during connect; close a half-open child on throw.
  try {
    await client.connect(transport);
  } catch (connectErr) {
    await closeQuietly(client);
    throw connectErr;
  }
  return { client };
}

// Teardown must not mask the conformance outcome already recorded.
async function closeQuietly(client) {
  try {
    await client.close();
  } catch (closeErr) {
    console.error('NOTE: client.close() raised during teardown:', closeErr.message);
  }
}

// Secondary servers must remain reachable by the process-level watchdog.
const AUX_CLIENTS = new Set();

// Register from `onClient`, before connect awaits.
function registerAuxClient(client) {
  if (client) AUX_CLIENTS.add(client);
  return client;
}

// Idempotent removal after secondary teardown.
function unregisterAuxClient(client) {
  if (client) AUX_CLIENTS.delete(client);
}

async function runWithWatchdog({ watchdogMs, transportSpec, clientName }, body) {
  // The timeout owns every spawned client from construction through close.
  let activeClient;
  // Install before connect; clear only after normal teardown.
  const watchdog = setTimeout(() => {
    console.error('FAIL: watchdog timeout (' + watchdogMs + 'ms)');
    // The fallback bounds a close that never settles.
    const hardExit = setTimeout(() => process.exit(2), 2000);
    hardExit.unref();
    // Close primary and secondary clients before the timeout exit.
    const toClose = [];
    if (activeClient) toClose.push(activeClient);
    for (const aux of AUX_CLIENTS) toClose.push(aux);
    if (toClose.length > 0) {
      Promise.all(toClose.map((c) => closeQuietly(c))).finally(() =>
        process.exit(2),
      );
    } else {
      process.exit(2);
    }
  }, watchdogMs);

  // Transport and SDK initialization failures are conformance failures.
  let client;
  let exitCode;
  let bodyError;
  try {
    // Capture before connect; retain the resolved binding for finally.
    ({ client } = await connectServer({
      transportSpec,
      clientName,
      onClient: (c) => { activeClient = c; },
    }));
    activeClient = client;
    // `body` receives the connected client and nothing else — the client owns
    // its transport, so there is no second handle for a caller to tear down.
    await body(client);
    exitCode = 0;
  } catch (err) {
    exitCode = 1;
    bodyError = err;
  } finally {
    // Preserve the body outcome if close itself reports an error.
    if (client) await closeQuietly(client);
    clearTimeout(watchdog);
    if (bodyError) {
      console.error('FAIL:', bodyError.message);
      if (bodyError.stack) console.error(bodyError.stack);
    }
    process.exit(exitCode);
  }
}

// ---------------------------------------------------------------------
// Pre-flight skip helper.
//
// Conformance tests gate on environment / server-implementation status
// (`live-re-frame2-pair-overflow.cjs` requires `$SHADOW_CLJS_NREPL_PORT`).
// Skipping cleanly means exiting 0 BEFORE spawning a child or
// installing the watchdog — same pattern across every caller.
// Centralising the SKIP banner + exit here makes the caller a
// one-liner and pins the marker shape (`SKIP <reason>`) so a CI step
// that greps for it doesn't drift per-test.
// ---------------------------------------------------------------------
runWithWatchdog.skip = function skip(reason) {
  console.log('SKIP ' + reason);
  process.exit(0);
};

// ---------------------------------------------------------------------
// JSON-RPC error-code conformance gate.
//
// `mcp-base/vocab.cljc` pins the five JSON-RPC 2.0 §5.1 codes the
// MCP servers route against (`-32700` ParseError, `-32600` InvalidRequest,
// `-32601` MethodNotFound, `-32602` InvalidParams, `-32603`
// InternalError). This gate asserts each server's actual on-the-wire
// emission, so a regression that swapped `-32602` and `-32603` turns
// RED rather than shipping unobserved.
//
// Coverage today: two of the five codes are reliably reachable from
// the SDK Client.
//
//   - `-32601 MethodNotFound` — exercised via a deliberately-unknown
//     JSON-RPC method. Both servers MUST emit this canonically; a
//     rename to `-32602` would surface here.
//   - `-32603 InternalError` — exercised via `tools/call` with a
//     missing `:name` param. The SDK's server-side `tools/call` schema
//     parse fails on the missing slot and the framework wraps the zod
//     error as `-32603`. (`-32602 InvalidParams` would be the JSON-RPC
//     §5.1 reading but the SDK pins `-32603`; this gate accepts
//     either, so a future SDK tightening doesn't break the contract.)
//
// The other three (`-32700`, `-32600`, `-32602`) require wire-level
// access to the transport (sending malformed JSON, an invalid-shape
// envelope, etc.) that the SDK Client doesn't expose. Those paths are
// covered indirectly: any code path that DOES route a JSON-RPC error
// must use the `ErrorCode.*` constants imported from the same SDK
// module the canonical pin (`mcp-base/vocab.cljc`) cross-references.
//
// Returns nothing; throws on a code-mismatch with a descriptive error.
// Used by every `end-to-end-*.cjs` after the catalogue check so the
// gate runs once per server per CI invocation.
// ---------------------------------------------------------------------
async function assertJsonRpcErrorCodes(client) {
  // 1. Unknown JSON-RPC method MUST surface as `-32601 MethodNotFound`.
  // The SDK's `client.request` throws an `McpError` on a JSON-RPC error
  // response; we assert the carried `.code` matches the canonical value
  // pinned by `mcp-base/vocab.cljc/code-method-not-found`.
  try {
    await client.request(
      { method: 'conformance/unknown-method-probe', params: {} },
      // The schema slot would normally validate the result; we pass a
      // permissive parser because we EXPECT to throw, not parse.
      { parse: () => null },
    );
    throw new Error(
      'unknown JSON-RPC method MUST return -32601 MethodNotFound; ' +
        'got a successful response instead',
    );
  } catch (err) {
    if (!(err instanceof McpError)) {
      throw new Error(
        'unknown JSON-RPC method MUST throw McpError; got ' +
          (err && err.constructor ? err.constructor.name : typeof err) +
          ': ' + (err && err.message),
      );
    }
    if (err.code !== ErrorCode.MethodNotFound) {
      throw new Error(
        'unknown JSON-RPC method MUST yield code -32601 (MethodNotFound, ' +
          'per mcp-base/vocab.cljc/code-method-not-found); got ' +
          err.code + ': ' + err.message,
      );
    }
  }

  // 2. `tools/call` with a missing `:name` slot MUST surface a
  // server-side validation error — either `-32602 InvalidParams` (the
  // JSON-RPC §5.1 reading) or `-32603 InternalError` (the SDK's
  // observed shape; the framework wraps the zod parse failure as
  // InternalError). Both are pinned by `mcp-base/vocab.cljc`; either
  // is conformant — accepting the union prevents an SDK tightening
  // from breaking the contract.
  try {
    await client.request(
      { method: 'tools/call', params: { arguments: {} } },
      { parse: () => null },
    );
    throw new Error(
      'tools/call with missing :name MUST return a JSON-RPC error; ' +
        'got a successful response instead',
    );
  } catch (err) {
    if (!(err instanceof McpError)) {
      throw new Error(
        'malformed tools/call MUST throw McpError; got ' +
          (err && err.constructor ? err.constructor.name : typeof err) +
          ': ' + (err && err.message),
      );
    }
    const ok =
      err.code === ErrorCode.InvalidParams ||
      err.code === ErrorCode.InternalError;
    if (!ok) {
      throw new Error(
        'tools/call with missing :name MUST yield code -32602 ' +
          '(InvalidParams) or -32603 (InternalError) per ' +
          'mcp-base/vocab.cljc; got ' + err.code + ': ' + err.message,
      );
    }
  }
}

// Descriptor floor not covered by the SDK schema: object input with the
// shared budget arg, output schema, and at least one annotation hint. Exact
// per-tool annotation values belong to assertClassificationRatchet.
function assertDescriptorShape(tools, { allowOpenWorld } = {}) {
  // Object input and the universal budget argument.
  for (const t of tools) {
    if (!t.inputSchema || typeof t.inputSchema !== 'object') {
      throw new Error('tool ' + t.name + ' has no inputSchema');
    }
    if (t.inputSchema.type !== 'object') {
      throw new Error(
        'tool ' + t.name + " inputSchema.type MUST be 'object' (cross-MCP " +
          'convention; NAMING.md catalogue surface); got: ' +
          JSON.stringify(t.inputSchema.type),
      );
    }
    const props = t.inputSchema.properties || {};
    if (!('max-tokens' in props)) {
      throw new Error(
        'tool ' + t.name + " inputSchema.properties MUST expose 'max-tokens' " +
          '(TOKEN-BUDGETS.md §"Per-call override slot": every tool surfaces ' +
          'the cap-override slot so clients discover it automatically).',
      );
    }
  }

  // Structured result schema.
  for (const t of tools) {
    if (!t.outputSchema || typeof t.outputSchema !== 'object') {
      throw new Error(
        'tool ' + t.name + ' MUST declare :outputSchema (rf2-3l3be); got: ' +
          JSON.stringify(t.outputSchema),
      );
    }
  }

  // At least one host-visible classification hint.
  for (const t of tools) {
    if (!t.annotations || typeof t.annotations !== 'object') {
      throw new Error(
        'tool ' + t.name + ' MUST declare :annotations (rf2-94p8q); got: ' +
          JSON.stringify(t.annotations),
      );
    }
    const a = t.annotations;
    const classified =
      a.readOnlyHint === true ||
      a.destructiveHint === true ||
      (allowOpenWorld === true && a.openWorldHint === true);
    if (!classified) {
      const allowed = allowOpenWorld
        ? 'readOnlyHint / destructiveHint / openWorldHint'
        : 'readOnlyHint / destructiveHint';
      throw new Error(
        'tool ' + t.name + ' annotations MUST set at least one of ' +
          allowed + '; got: ' + JSON.stringify(a),
      );
    }
  }
}

// Exact descriptor-content ratchet. `classifications` maps every advertised
// tool to read-only, destructive, or neither. `closed-world` is the false
// side of an exhaustive openWorldHint partition; all other tools must set
// openWorldHint true. Every max-tokens property also needs useful prose.
function assertClassificationRatchet(tools, expected) {
  const table = (expected && expected.classifications) || {};
  const closedWorld = new Set((expected && expected['closed-world']) || []);
  const liveNames = tools.map((t) => t.name).sort();
  const pinnedNames = Object.keys(table).sort();

  // Classification rows and the live catalogue must have identical keys.
  if (JSON.stringify(liveNames) !== JSON.stringify(pinnedNames)) {
    const missing = liveNames.filter((n) => !table[n]);
    const stale = pinnedNames.filter((n) => !tools.some((t) => t.name === n));
    throw new Error(
      'classification ratchet (rf2-yi451): the fixture must pin every live ' +
        'tool exactly.\n  unclassified live tools (add a row): ' +
        JSON.stringify(missing) +
        '\n  stale fixture rows (no such tool): ' +
        JSON.stringify(stale),
    );
  }

  // The partition cannot name a tool outside that exact catalogue.
  const staleClosedWorld = Array.from(closedWorld).filter(
    (n) => !Object.prototype.hasOwnProperty.call(table, n),
  );
  if (staleClosedWorld.length > 0) {
    throw new Error(
      'classification ratchet (rf2-yi451): the fixture `closed-world` list ' +
        'names a tool absent from `classifications` (dangling row — the ' +
        'tool was renamed or removed; update or delete the row): ' +
        JSON.stringify(staleClosedWorld),
    );
  }

  for (const t of tools) {
    const a = t.annotations || {};
    const posture = table[t.name];
    const ro = a.readOnlyHint === true;
    const de = a.destructiveHint === true;

    // Exact readOnly/destructive posture.
    if (posture === 'read-only') {
      if (!ro || de) {
        throw new Error(
          'tool ' + t.name + ' classification regressed: fixture pins ' +
            '`read-only` (readOnlyHint:true, destructiveHint NOT true) but the ' +
            'live descriptor has readOnlyHint=' + JSON.stringify(a.readOnlyHint) +
            ', destructiveHint=' + JSON.stringify(a.destructiveHint) +
            '. annotations are the trust signal an agent host uses to ' +
            'auto-approve a call — a misclassification is a safety regression ' +
            '(rf2-yi451). Got annotations: ' + JSON.stringify(a),
        );
      }
    } else if (posture === 'destructive') {
      if (!de || ro) {
        throw new Error(
          'tool ' + t.name + ' classification regressed: fixture pins ' +
            '`destructive` (destructiveHint:true, readOnlyHint NOT true) but the ' +
            'live descriptor has readOnlyHint=' + JSON.stringify(a.readOnlyHint) +
            ', destructiveHint=' + JSON.stringify(a.destructiveHint) +
            '. A destructive write tool silently re-labelled read-only would ' +
            'be auto-approved by an agent host — the exact false-green this ' +
            'ratchet exists to turn RED (rf2-yi451). Got annotations: ' +
            JSON.stringify(a),
        );
      }
    } else if (posture === 'neither') {
      if (ro || de) {
        throw new Error(
          'tool ' + t.name + ' classification regressed: fixture pins ' +
            '`neither` (a session-pin tool — NEITHER readOnlyHint ' +
            'NOR destructiveHint true) but the live descriptor has ' +
            'readOnlyHint=' + JSON.stringify(a.readOnlyHint) +
            ', destructiveHint=' + JSON.stringify(a.destructiveHint) +
            '. A side-effecting tool that gained readOnlyHint would be ' +
            'wrongly auto-approved (rf2-yi451). Got annotations: ' +
            JSON.stringify(a),
        );
      }
    } else {
      throw new Error(
        'tool ' + t.name + ' fixture posture ' + JSON.stringify(posture) +
          ' is not one of read-only / destructive / neither (rf2-yi451 fixture bug)',
      );
    }

    // Both sides of the exhaustive open-world partition.
    if (closedWorld.has(t.name)) {
      // Inline or server-local content.
      if (a.openWorldHint !== false) {
        throw new Error(
          'tool ' + t.name + ' is pinned closed-world (inline / no runtime ' +
            'reach) but the live descriptor has openWorldHint=' +
            JSON.stringify(a.openWorldHint) + ' (MUST be false). rf2-yi451.',
        );
      }
    } else {
      // Every non-closed tool reaches an external runtime.
      if (a.openWorldHint !== true) {
        throw new Error(
          'tool ' + t.name + ' is open-world (reaches the live browser / ' +
            'nREPL — not in the fixture `closed-world` list) but the live ' +
            'descriptor has openWorldHint=' + JSON.stringify(a.openWorldHint) +
            ' (MUST be true). A live-reaching tool mislabelled as contained ' +
            'weakens the MCP client trust/confirmation boundary — the ' +
            'false-green this side exists to turn RED (rf2-ppk6hy). Got ' +
            'annotations: ' + JSON.stringify(a),
        );
      }
    }

    // The universal budget slot must explain its use.
    const props = (t.inputSchema && t.inputSchema.properties) || {};
    const mt = props['max-tokens'];
    if (!mt || typeof mt.description !== 'string' || mt.description.trim().length === 0) {
      throw new Error(
        'tool ' + t.name + " 'max-tokens' property MUST carry a non-empty " +
          'budget-hint description (TOKEN-BUDGETS.md §"Per-call override slot"; ' +
          'a dropped hint ships green under the prop-presence-only check). ' +
          'rf2-yi451. Got: ' + JSON.stringify(mt),
      );
    }
  }
}

// Every advertised tool must be SDK-called or carry a non-empty rationale
// naming its alternative coverage. Stale and contradictory exclusions fail.
function assertCallCoverageRatchet({ advertised, called, exclusions = {} }) {
  const advertisedSet = new Set(advertised);
  const calledSet = called instanceof Set ? called : new Set(called);
  const excludedNames = Object.keys(exclusions);

  // Exclusions must be current and genuinely uncalled.
  const staleNotAdvertised = excludedNames.filter((n) => !advertisedSet.has(n));
  const staleAlsoCalled = excludedNames.filter((n) => calledSet.has(n));
  if (staleNotAdvertised.length > 0 || staleAlsoCalled.length > 0) {
    throw new Error(
      'callTool coverage ratchet (rf2-ke5n56): the exclusion table has ' +
        'stale rows.\n  excluded but no longer advertised (remove the row): ' +
        JSON.stringify(staleNotAdvertised) +
        '\n  excluded yet ALSO SDK-called (the row contradicts the ' +
        'workflow — drop it, the tool IS covered): ' +
        JSON.stringify(staleAlsoCalled),
    );
  }

  // Alternative coverage must be reviewable.
  const blankRationale = excludedNames.filter(
    (n) => typeof exclusions[n] !== 'string' || exclusions[n].trim().length === 0,
  );
  if (blankRationale.length > 0) {
    throw new Error(
      'callTool coverage ratchet (rf2-ke5n56): these exclusion rows MUST ' +
        'carry a non-empty rationale naming WHERE the tool is otherwise ' +
        'covered (a live-only gate, a hermetic harness, a unit/fixture ' +
        'pin); a blank exclusion is an unreviewed hole: ' +
        JSON.stringify(blankRationale),
    );
  }

  // Descriptors alone do not satisfy call-envelope coverage.
  const excludedSet = new Set(excludedNames);
  const uncovered = advertised.filter(
    (n) => !calledSet.has(n) && !excludedSet.has(n),
  );
  if (uncovered.length > 0) {
    throw new Error(
      'callTool coverage ratchet (rf2-ke5n56): these advertised tools are ' +
        'NEITHER invoked through Client.callTool() NOR listed in the ' +
        'reviewed exclusion table.\n  uncovered: ' +
        JSON.stringify(uncovered.sort()) +
        '\n\n  An advertised tool that is only LISTED (descriptor metadata ' +
        'checked) but never CALLED can regress its callTool envelope / ' +
        'outputSchema / structuredContent shape and still ship green — the ' +
        'exact false-green class this ratchet closes. To green this:\n' +
        '    (a) add a cheap SDK-call probe (prefer a non-mutating read or ' +
        'a default-off refusal probe), OR\n' +
        '    (b) add a row to the exclusion table with a rationale naming ' +
        'where the tool IS covered (a live-only / hermetic / unit-layer ' +
        'gate).',
    );
  }
}

// Concatenate text blocks; non-text or absent content contributes nothing.
function responseText(resp) {
  if (!resp || !Array.isArray(resp.content)) return '';
  return resp.content
    .map((c) => (c && typeof c.text === 'string' ? c.text : ''))
    .join('\n');
}

// Transparent client proxy that records each callTool name.
function track(rawClient) {
  const called = new Set();
  const client = new Proxy(rawClient, {
    get(target, prop, receiver) {
      if (prop === 'callTool') {
        return (req, ...rest) => {
          if (req && typeof req.name === 'string') called.add(req.name);
          return target.callTool(req, ...rest);
        };
      }
      const v = Reflect.get(target, prop, receiver);
      return typeof v === 'function' ? v.bind(target) : v;
    },
  });
  return { client, called };
}

// Decode optional structural dedup before reading semantic slots.
function structured(resp) {
  return decodeDedupEnvelope((resp && resp.structuredContent) || {});
}

// Universal SDK-boundary cross-check after dedup decoding. Results without
// an :ok? slot are outside this relation.
function assertIsErrorMatchesOk(label, resp) {
  const sc = structured(resp);
  if (sc === undefined || sc === null || typeof sc !== 'object') return;
  if (!Object.prototype.hasOwnProperty.call(sc, 'ok?')) return;
  const ok = sc['ok?'];
  if (ok === false && resp.isError !== true) {
    throw new Error(
      label + ' carries :ok? false but isError is not true (' +
        JSON.stringify(resp.isError) + ') — violates the universal ' +
        'spec/003 §381 contract (every :ok? false is isError:true). ' +
        'A failure that is not flagged isError is cache-eligible and ' +
        'can mask a later success (rf2-87h71e / rf2-q7cavs).',
    );
  }
  if (ok === true && resp.isError === true) {
    throw new Error(
      label + ' carries :ok? true but isError is true — a success ' +
        'envelope must not be flagged a fault (rf2-87h71e converse).',
    );
  }
}

module.exports = {
  runWithWatchdog,
  connectServer,
  closeQuietly,
  registerAuxClient,
  unregisterAuxClient,
  assertJsonRpcErrorCodes,
  assertDescriptorShape,
  assertClassificationRatchet,
  assertCallCoverageRatchet,
  responseText,
  track,
  structured,
  assertIsErrorMatchesOk,
};
