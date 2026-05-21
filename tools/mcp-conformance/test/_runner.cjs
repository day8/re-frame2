// Shared Node-side test runner for MCP-conformance harnesses.
//
// Every `tools/mcp-conformance/test/end-to-end-*.cjs` (and the
// sibling `live-re-frame2-pair-overflow.cjs`) repeats the same ceremony:
//
//   - construct a `StdioClientTransport` with `stderr: 'pipe'`
//   - pipe the child's stderr to ours with a `[server]` prefix
//   - construct an SDK `Client` with a `mcp-conformance-*` identity
//   - install a watchdog `setTimeout` so a hung child can't wedge CI
//   - on success: tear down the transport, clear the watchdog,
//     `process.exit(0)`
//   - on error: best-effort `client.close()`, clear the watchdog,
//     print the failure, `process.exit(1)`
//   - on watchdog: print the timeout, `process.exit(2)`
//
// Source: rf2-sems4. The split was originally three near-identical
// ~40-LoC blocks of pure framing code; centralising the framing here
// shrinks each test body to the workflow steps that actually differ.
// Adding another Node-side conformance test (a third re-frame2-pair variant)
// becomes ~30 LoC instead of ~110.
//
// ## Contract
//
// `runWithWatchdog({ watchdogMs, transportSpec, clientName }, body)`
//
//   - `watchdogMs`     — ms before the hard-cap `process.exit(2)` fires.
//   - `transportSpec`  — `{ command, args, cwd, env }` forwarded to
//                        `StdioClientTransport` (with `stderr: 'pipe'`
//                        spliced in automatically).
//   - `clientName`     — the SDK `Client` `name` slot. Every conformance
//                        test uses a `mcp-conformance-<server>` identity.
//   - `body`           — `async (client, transport) => void`. Throws on
//                        any assertion failure; the runner reports the
//                        message + stack and exits 1.
//
// The SDK `Client.version` slot is pinned at `'0.1.0'` for every
// conformance harness — none of the callers override it and the
// server's `initialize` handler treats client version as informational.
// Promoting it to a parameter would be slot-soup (rf2-i3ffz F-GAP-6).
//
// `runWithWatchdog` performs the SDK connect handshake itself (every
// historical caller did it as the first line of the body) and passes
// the connected `client` + `transport` to `body`. Tests that want to
// skip (no-op exit 0) call `runWithWatchdog.skip(reason)` BEFORE
// invoking this function — that prints a uniform `SKIP <reason>`
// banner and exits 0 without spawning a child or installing a
// watchdog.
//
// The runner returns nothing; it always terminates the process via
// `process.exit`. Callers that pre-flight skip MUST exit themselves
// (via `runWithWatchdog.skip`).

const { Client } = require('@modelcontextprotocol/sdk/client/index.js');
const { StdioClientTransport } = require('@modelcontextprotocol/sdk/client/stdio.js');
const { ErrorCode, McpError } = require('@modelcontextprotocol/sdk/types.js');

const CLIENT_VERSION = '0.1.0';

async function runWithWatchdog({ watchdogMs, transportSpec, clientName }, body) {
  // Install the watchdog FIRST so even a hang inside transport
  // construction (rare but possible: bad cwd, env corruption) gets
  // killed. The `finally` below clears the timer on every exit path —
  // including the case where `client.close()` itself throws (per
  // rf2-i3ffz F-CORR-3: the historical shape did cleanup inside the
  // `try`, so a throw on the success-path teardown would invert the
  // log to a FAIL and lose the body's success state).
  const watchdog = setTimeout(() => {
    console.error('FAIL: watchdog timeout (' + watchdogMs + 'ms)');
    process.exit(2);
  }, watchdogMs);

  // `stderr: 'pipe'` is non-negotiable — every conformance test pipes
  // the server's stderr so CI logs surface the server-side debug
  // output when a step fails. Splice it in here rather than asking
  // the caller to remember it.
  const transport = new StdioClientTransport({ ...transportSpec, stderr: 'pipe' });

  const client = new Client({ name: clientName, version: CLIENT_VERSION }, { capabilities: {} });

  // Pipe the child's stderr to ours with a `[server]` prefix. Same
  // shape every historical caller used; the closure captures the
  // transport reference cleanly.
  transport.stderr?.on('data', (d) => process.stderr.write('[server] ' + d.toString()));

  // Drive `body` to completion, recording its outcome in `exitCode`.
  // Teardown lives in `finally` so a throw from `client.close()` can't
  // re-enter the catch and mask the body's success state. Three exit
  // codes: 0 = body green, 1 = body or initialize threw, 2 = watchdog
  // (process.exit happens inside the timer callback, never here).
  let exitCode;
  let bodyError;
  try {
    // Initialize handshake. SDK validates the result against
    // InitializeResultSchema; a malformed envelope throws here.
    await client.connect(transport);
    await body(client, transport);
    exitCode = 0;
  } catch (err) {
    exitCode = 1;
    bodyError = err;
  } finally {
    // Best-effort teardown. A throw here is independent of the body
    // outcome: if `client.close()` raises on a green body, we still
    // exit 0 (the conformance assertions passed). If it raises on a
    // failed body, the original `bodyError` is still the load-bearing
    // signal and the close-error is purely cosmetic.
    try {
      await client.close();
    } catch (closeErr) {
      console.error('NOTE: client.close() raised during teardown:', closeErr.message);
    }
    clearTimeout(watchdog);
    if (bodyError) {
      console.error('FAIL:', bodyError.message);
      if (bodyError.stack) console.error(bodyError.stack);
    }
    process.exit(exitCode);
  }
}

// ---------------------------------------------------------------------
// Pre-flight skip helper (rf2-i3ffz F-HYG-2).
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
// JSON-RPC error-code conformance gate (rf2-i3ffz F-GAP-3).
//
// `mcp-base/vocab.cljc` pins the five JSON-RPC 2.0 §5.1 codes the
// MCP servers route against (`-32700` ParseError, `-32600` InvalidRequest,
// `-32601` MethodNotFound, `-32602` InvalidParams, `-32603`
// InternalError). Before this gate landed no test asserted either
// server's actual on-the-wire emission — a regression that swapped
// `-32602` and `-32603` would have shipped unobserved.
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

// ---------------------------------------------------------------------
// Tool-descriptor shape gate (rf2-80y2h).
//
// Both `end-to-end-re-frame2-pair.cjs` and `end-to-end-story.cjs` ran four
// near-identical `for (const t of listed.tools)` loops over the
// catalogue, pinning cross-MCP-convention invariants the SDK's
// ListToolsResultSchema does NOT model:
//
//   1. inputSchema.type === 'object'        (NAMING.md catalogue surface)
//   2. inputSchema.properties has max-tokens (TOKEN-BUDGETS.md MUST)
//   3. an :outputSchema map is declared      (rf2-3l3be)
//   4. an :annotations map with at least one classification hint set
//      (rf2-94p8q)
//
// The two copies (~60 LoC each) differed in EXACTLY one place: the
// annotations classification set. re-frame2-pair-mcp permits
// `openWorldHint` as a classifier (its eval-cljs / dispatch tools touch
// an open world); story-mcp does not (its tools are closed-world reads /
// fixture writes). Extracting the loops here with an `allowOpenWorld`
// flag collapses that drift to a single maintained gate; a new
// invariant is added once, not twice.
//
// Throws on the first violation with a descriptive, server-agnostic
// message (the tool name + the failed invariant). Returns nothing.
// ---------------------------------------------------------------------
function assertDescriptorShape(tools, { allowOpenWorld } = {}) {
  // 1 + 2. inputSchema is a map with type=object and a max-tokens slot.
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

  // 3. outputSchema declared (rf2-3l3be).
  for (const t of tools) {
    if (!t.outputSchema || typeof t.outputSchema !== 'object') {
      throw new Error(
        'tool ' + t.name + ' MUST declare :outputSchema (rf2-3l3be); got: ' +
          JSON.stringify(t.outputSchema),
      );
    }
  }

  // 4. annotations map with at least one classification hint (rf2-94p8q).
  // openWorldHint counts as a classifier only when `allowOpenWorld` is
  // set — the single per-server difference between the two harnesses.
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

module.exports = { runWithWatchdog, assertJsonRpcErrorCodes, assertDescriptorShape };
