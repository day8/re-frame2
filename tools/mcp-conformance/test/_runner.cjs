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
//   - on watchdog: print the timeout, TEAR THE SPAWNED CHILD DOWN
//     (`client.close()` closes its transport, killing the stdio child),
//     then `process.exit(2)` — a hard-exit fallback fires after a short
//     grace window so a hung close can't keep the process alive. Tearing
//     the child down on the timeout keeps it from being orphaned (the
//     hermetic orchestrator guards the same class).
//
// Centralising the framing here shrinks each test body to the workflow
// steps that actually differ — the framing is otherwise a near-identical
// ~40-LoC block of pure ceremony per test. Adding another Node-side
// conformance test (a third re-frame2-pair variant) becomes ~30 LoC
// instead of ~110.
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
// Promoting it to a parameter would be slot-soup.
//
// `runWithWatchdog` performs the SDK connect handshake itself (so each
// body starts already connected) and passes the connected `client` +
// `transport` to `body`. Tests that want to
// skip (no-op exit 0) call `runWithWatchdog.skip(reason)` BEFORE
// invoking this function — that prints a uniform `SKIP <reason>`
// banner and exits 0 without spawning a child or installing a
// watchdog.
//
// The runner returns nothing; it always terminates the process via
// `process.exit`. Callers that pre-flight skip MUST exit themselves
// (via `runWithWatchdog.skip`).
//
// ## Bare spawn (no watchdog / no exit) — `connectServer` + `closeQuietly`
//
// `runWithWatchdog` is the single-boot-per-process form. Two gates need
// a SECOND in-process server boot it can't serve (it `process.exit`s on
// every path): `end-to-end-flag-gates.cjs` (three fresh JVMs, one per
// gate-posture probe) and `live-re-frame2-pair-redaction.cjs` (a second
// server with `--allow-sensitive-reads` for the gate-ON arm). Both call
// the shared `connectServer({ transportSpec, clientName, stderrPrefix })`
// primitive — the exact spawn+stderr-pipe+Client+connect ceremony this
// header describes — and tear down with `closeQuietly(client)`.
// `runWithWatchdog` is itself just `connectServer` wrapped in the
// watchdog + exit-code framing, so the framing has one home.

const { Client } = require('@modelcontextprotocol/sdk/client/index.js');
const { StdioClientTransport } = require('@modelcontextprotocol/sdk/client/stdio.js');
const { ErrorCode, McpError } = require('@modelcontextprotocol/sdk/types.js');
const { decodeDedupEnvelope } = require('../lib/dedup-envelope.cjs');

const CLIENT_VERSION = '0.1.0';

// ---------------------------------------------------------------------
// Shared SDK-spawn primitive.
//
// Spawn one MCP server over a `StdioClientTransport`, pipe its stderr
// to ours, construct an SDK `Client` with the pinned conformance
// identity, and run the `initialize` handshake. Returns the connected
// `{ client, transport }` for the caller to drive + tear down.
//
// `runWithWatchdog` below is the watchdog-wrapped, single-boot-per-
// process form (it `process.exit`s on every path). But two gates need
// a SECOND, in-process server boot the watchdog form can't provide:
//
//   - `end-to-end-flag-gates.cjs` boots three fresh JVMs in one process
//     (the gate is a boot-time atom — flipping it needs a new server).
//   - `live-re-frame2-pair-redaction.cjs` boots a second server with
//     `--allow-sensitive-reads` to pin the gate-ON direction.
//
// Centralising the transport+client+stderr-pipe+connect ceremony here
// means the framing the runner's header documents has ONE home; a caller
// that needs a bare boot calls `connectServer` + `closeQuietly`, and
// `runWithWatchdog` itself is just `connectServer` wrapped in the
// watchdog + exit-code framing.
//
//   - `transportSpec`  — `{ command, args, cwd, env }` forwarded to
//                        `StdioClientTransport` (`stderr: 'pipe'` is
//                        spliced in here — non-negotiable so CI logs
//                        surface server stderr on a failing step).
//   - `clientName`     — the SDK `Client` `name` slot.
//   - `stderrPrefix`   — bracketed tag prepended to each piped stderr
//                        line. Defaults to `[server]`; the redaction
//                        gate-ON arm passes `[server:gate-on]` to keep
//                        the two concurrent servers' logs distinguishable.
//   - `onClient`       — OPTIONAL `(client) => void` callback, invoked
//                        with the constructed client BEFORE the
//                        `await client.connect()` handshake. The
//                        watchdog form (`runWithWatchdog`) uses it to
//                        capture a teardown handle the moment the child
//                        is spawned — so a server that spawns and then
//                        HANGS during the initialize handshake (connect
//                        never resolving, never throwing) is still torn
//                        down by the timeout path instead of orphaning
//                        the child. The connect try/catch below only
//                        covers a connect that THROWS — a connect that
//                        hangs never reaches it.
// ---------------------------------------------------------------------
async function connectServer({ transportSpec, clientName, stderrPrefix = '[server]', onClient }) {
  const transport = new StdioClientTransport({ ...transportSpec, stderr: 'pipe' });
  const client = new Client({ name: clientName, version: CLIENT_VERSION }, { capabilities: {} });
  // Hand the caller a teardown handle the moment the client exists —
  // BEFORE the connect handshake. The child has already been spawned by
  // the transport construction above, so a connect that hangs (server
  // boots but never finishes `initialize`) would otherwise leave the
  // watchdog with no client to close. `onClient` closes that window: the
  // watchdog captures `client` here, not after `connect` resolves.
  if (onClient) onClient(client);
  // Pipe the child's stderr to ours with the (prefixed) tag. The closure
  // captures the transport reference cleanly.
  transport.stderr?.on('data', (d) => process.stderr.write(stderrPrefix + ' ' + d.toString()));
  // Initialize handshake. SDK validates the result against
  // InitializeResultSchema; a malformed envelope throws here. On a
  // connect-throw, tear the half-open transport down before re-throwing
  // so the spawned child can't leak — `client.close()` closes its
  // transport, so callers need no transport handle to clean up a failed
  // boot.
  try {
    await client.connect(transport);
  } catch (connectErr) {
    await closeQuietly(client);
    throw connectErr;
  }
  return { client, transport };
}

// Best-effort `client.close()` that never throws. A close-error is
// purely cosmetic — it's independent of whatever outcome the caller
// already recorded — so we log it and swallow it. Shared by every
// teardown path (the runner's `finally`, the flag-gate per-boot
// `finally`, the redaction gate-ON arm's `finally`).
async function closeQuietly(client) {
  try {
    await client.close();
  } catch (closeErr) {
    console.error('NOTE: client.close() raised during teardown:', closeErr.message);
  }
}

// Module-scope set of SECONDARY MCP clients the watchdog must ALSO tear
// down on a timeout. `runWithWatchdog` owns at most ONE primary client
// (`activeClient`); but two gates boot ADDITIONAL in-process servers via
// bare `connectServer` INSIDE the body
// (`live-re-frame2-pair-redaction.cjs`'s inner-projection + gate-ON arms),
// which the outer watchdog cannot reach through `activeClient` alone — a
// hang in a secondary boot or a later secondary tool call would orphan
// that child despite the harness reporting a watchdog timeout. A body
// registers each secondary client here (via `registerAuxClient`, fired
// the instant the client is constructed) and deregisters it on teardown;
// the watchdog drains every surviving member so EVERY spawned child the
// conformance process owns is reachable by the timeout path from the
// moment it can exist until teardown.
const AUX_CLIENTS = new Set();

// Register a secondary client so the active watchdog reaps it on a timeout.
// Call it from a bare `connectServer`'s `onClient` (BEFORE connect awaits)
// so a hung secondary boot is reachable from the moment its child exists.
// Returns the client unchanged for fluent use.
function registerAuxClient(client) {
  if (client) AUX_CLIENTS.add(client);
  return client;
}

// Drop a secondary client from the watchdog set once its arm has torn it
// down (or is about to). Idempotent; safe to call in a `finally`.
function unregisterAuxClient(client) {
  if (client) AUX_CLIENTS.delete(client);
}

async function runWithWatchdog({ watchdogMs, transportSpec, clientName }, body) {
  // The watchdog must OWN the active client so a timeout tears the
  // spawned child down instead of orphaning it. `activeClient` is the
  // closure handle the watchdog reads; it is assigned via
  // `connectServer`'s `onClient` callback the moment the client is
  // constructed — BEFORE the `await client.connect()` handshake — so a
  // hang ANYWHERE after spawn (during `initialize`, inside `body`,
  // inside a slow tool call) is cleaned up. Assigning at construction
  // time (not after connect resolves) keeps a server that spawns and
  // then hangs mid-handshake (connect never resolving, never throwing)
  // reachable by the timeout path — the exact orphan class this watchdog
  // exists to prevent.
  //
  // On a timeout the watchdog tears the spawned MCP server (Node bundle
  // or JVM) down rather than letting it keep the CI step's log pipes /
  // ports held past the node exit (the same orphan class the hermetic
  // orchestrator guards). `client.close()` closes its transport, which
  // kills the stdio child (the same teardown `closeQuietly` performs on
  // the normal paths). We hold the exit a short grace window so the
  // close lands, then exit with code 2 regardless (a wedged child must
  // not keep us alive).
  let activeClient;
  // Install the watchdog FIRST so even a hang inside transport
  // construction (rare but possible: bad cwd, env corruption) gets
  // killed. The `finally` below clears the timer on every exit path —
  // including the case where `client.close()` itself throws. Keeping the
  // teardown out of the `try` means a throw on the success-path teardown
  // does not invert the log to a FAIL and lose the body's success state.
  const watchdog = setTimeout(() => {
    console.error('FAIL: watchdog timeout (' + watchdogMs + 'ms)');
    // Tear the spawned child down before exiting. `closeQuietly` never
    // throws; the `.then`/`.catch` both arm the hard-exit fallback so a
    // hung close can't keep the process alive.
    const hardExit = setTimeout(() => process.exit(2), 2000);
    hardExit.unref();
    // Close the primary AND every registered secondary client so a hang
    // in ANY in-process MCP child the body spawned is reaped, not just
    // the runner-managed primary. `closeQuietly` never throws; wait for
    // all closes to settle before the exit.
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

  // Spawn + connect via the shared primitive. A throw here (bad cwd, a
  // malformed initialize envelope) is caught below and surfaces as
  // exit 1.
  let client;
  let exitCode;
  let bodyError;
  try {
    let transport;
    // Capture the teardown handle via `onClient` — fired the instant the
    // client is constructed, BEFORE connect awaits — so a hang during the
    // initialize handshake is still reachable by the watchdog. The
    // post-resolve `activeClient = client` below is redundant on the
    // happy path but kept as a belt-and-braces guarantee (and to leave
    // `client` bound for the `finally` teardown).
    ({ client, transport } = await connectServer({
      transportSpec,
      clientName,
      onClient: (c) => { activeClient = c; },
    }));
    activeClient = client;
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
    // signal and the close-error is purely cosmetic. `client` may be
    // undefined if `connectServer` threw before constructing it.
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

// ---------------------------------------------------------------------
// Tool-descriptor shape gate.
//
// Both `end-to-end-re-frame2-pair.cjs` and `end-to-end-story.cjs` walk the
// catalogue with this shared gate, pinning cross-MCP-convention
// invariants the SDK's ListToolsResultSchema does NOT model:
//
//   1. inputSchema.type === 'object'        (NAMING.md catalogue surface)
//   2. inputSchema.properties has max-tokens (TOKEN-BUDGETS.md MUST)
//   3. an :outputSchema map is declared
//   4. an :annotations map with at least one classification hint set
//
// The two servers differ in EXACTLY one place: the annotations
// classification set — whether `openWorldHint` counts as a classifier.
// Both pass `allowOpenWorld: true`: re-frame2-pair-mcp's eval-cljs /
// dispatch tools touch an open world, and story-mcp's `run-variant` /
// `preview-variant` run the variant author's lifecycle events/fx which
// can reach external systems unless stubbed (so story-mcp is not wholly
// closed-world). The PER-TOOL open-world VALUES are pinned exactly by
// `assertClassificationRatchet`'s `closed-world` list, so `allowOpenWorld`
// only sets the "at-least-one-classifier" floor; it does not let a
// closed-world tool flip open undetected. Sharing the loop here keeps the
// gate single-maintained; a new invariant is added once, not twice.
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

  // 3. outputSchema declared.
  for (const t of tools) {
    if (!t.outputSchema || typeof t.outputSchema !== 'object') {
      throw new Error(
        'tool ' + t.name + ' MUST declare :outputSchema (rf2-3l3be); got: ' +
          JSON.stringify(t.outputSchema),
      );
    }
  }

  // 4. annotations map with at least one classification hint.
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

// ---------------------------------------------------------------------
// Per-descriptor CONTENT ratchet.
//
// `assertDescriptorShape` (above) pins that EVERY descriptor carries
// SOME classification hint and a `max-tokens` PROP — but never WHICH
// classifier, nor that the budget hint carries prose. The `tools/list`
// name ratchet (`tool-names.json`, compared in each end-to-end-*.cjs)
// pins WHICH tools exist. NEITHER pins per-tool CONTENT, so for an
// UNCHANGED tool-set:
//
//   - a destructive write tool (restore-epoch, replace-app-db,
//     register-variant, …) silently re-classified `readOnlyHint:true`
//     / `destructiveHint` dropped still passes — `readOnlyHint` alone
//     satisfies the "≥1 set" check. annotations are the TRUST SIGNAL an
//     agent host uses to auto-approve a call, so a destructive tool
//     re-labelled read-only is a real (if narrow) safety regression;
//   - a tool that dropped its `max-tokens` budget-hint PROSE ships green
//     — only the PROP presence is pinned, not the description text.
//
// This ratchet closes both gaps. `expected` is a per-server fixture
// (`test/fixtures/<server>-classifications.json`):
//
//   - `classifications` — tool-name -> posture, one of:
//       `read-only`   readOnlyHint===true  AND destructiveHint!==true
//       `destructive` destructiveHint===true AND readOnlyHint!==true
//       `neither`     readOnlyHint!==true  AND destructiveHint!==true
//                     (session-pin / streaming tools — classified via
//                      openWorldHint; pinning `neither` catches a
//                      side-effecting tool that wrongly gained
//                      readOnlyHint, masking its effect)
//   - `closed-world`  — the EXHAUSTIVE open-world partition:
//                       tools listed here MUST carry `openWorldHint:false`
//                       (inline / no-runtime-reach tools, e.g.
//                       get-re-frame2-pair-instructions); EVERY tool NOT
//                       listed MUST carry `openWorldHint:true` (it reaches
//                       the live browser / nREPL — an external process).
//                       Both sides are pinned. Asserting only the `false`
//                       side left a hole: a read-only/destructive tool that
//                       reaches the runtime could drop `openWorldHint:true`
//                       (or set it false) and still ship green, mislabelling
//                       a live-reaching tool as contained and weakening the
//                       MCP client trust/confirmation boundary. Pinning the
//                       complement turns that regression RED.
//
// Also pins, per descriptor, that the `max-tokens` property carries a
// NON-EMPTY `description` (the budget-hint prose — TOKEN-BUDGETS.md
// §"Per-call override slot").
//
// The fixture's `classifications` key-set MUST match the live tool-set
// EXACTLY (every tool pinned, no stale rows) — a tool present on the
// wire but absent from the fixture (or vice-versa) trips, so a NEW tool
// can't slip past unclassified and a REMOVED tool can't leave a dangling
// row. Throws on the first violation with a server-agnostic message.
// ---------------------------------------------------------------------
function assertClassificationRatchet(tools, expected) {
  const table = (expected && expected.classifications) || {};
  const closedWorld = new Set((expected && expected['closed-world']) || []);
  const liveNames = tools.map((t) => t.name).sort();
  const pinnedNames = Object.keys(table).sort();

  // 0. The fixture must pin EXACTLY the live tool-set — no unclassified
  // new tool, no stale removed row. (The name ratchet already pins the
  // live set against tool-names.json; this keeps the classification
  // fixture in lockstep so a new tool can't ship unclassified.)
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

  // 0b. `closed-world` entries must be a subset of `classifications` (now
  // known exact-equal to the live tool-set per check 0 above). Unlike a
  // stale `classifications` row, a dangling `closed-world` entry naming a
  // renamed/removed tool is NEVER visited by the per-tool loop below (it
  // only iterates live `tools`), so without this check it survives a
  // rename undetected — silently tolerated instead of tripping RED.
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

    // 1. The load-bearing classification: the EXACT readOnly/destructive
    // posture. A flipped or dropped hint trips here.
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
            '`neither` (a session-pin / streaming tool — NEITHER readOnlyHint ' +
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

    // 2. openWorld posture — BOTH sides of the partition. The fixture's
    // `closed-world` list IS the exhaustive partition: a tool is
    // closed-world (no runtime reach) OR open-world (reaches the live
    // browser/nREPL — an external process). Pinning only the `false` side
    // would leave a hole — a read-only/destructive tool that reaches the
    // runtime could DROP `openWorldHint:true` (or set it false) and still
    // ship GREEN, mislabelling a live-reaching tool as contained and
    // weakening the MCP client trust/confirmation boundary. Pinning both
    // sides means the complement can't regress.
    if (closedWorld.has(t.name)) {
      // 2a. Closed-world (inline / no runtime reach) — these ship static /
      // inline / server-local content; openWorldHint MUST be false so a
      // host doesn't treat them as reaching an external world.
      if (a.openWorldHint !== false) {
        throw new Error(
          'tool ' + t.name + ' is pinned closed-world (inline / no runtime ' +
            'reach) but the live descriptor has openWorldHint=' +
            JSON.stringify(a.openWorldHint) + ' (MUST be false). rf2-yi451.',
        );
      }
    } else {
      // 2b. Open-world (every NON-closed tool reaches the live browser /
      // nREPL — an external process) — openWorldHint MUST be true so a
      // host applies the appropriate confirmation ceremony instead of
      // treating the call as contained on-box. A missing or false hint on
      // a live-reaching tool is the exact false-green this side closes.
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

    // 3. Budget-hint PROSE — the `max-tokens` property must carry a
    // non-empty description (TOKEN-BUDGETS.md §"Per-call override slot").
    // `assertDescriptorShape` pins the PROP presence; this pins that the
    // hint text wasn't dropped/emptied.
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

// ---------------------------------------------------------------------
// SDK callTool() coverage ratchet.
//
// The name ratchet (`tool-names.json`) pins WHICH tools the catalogue
// advertises; `assertDescriptorShape` / `assertClassificationRatchet`
// pin the descriptor METADATA of every advertised tool. NEITHER pins
// that a tool was ever actually INVOKED through `Client.callTool()` —
// so a regression in a tool's callTool envelope, outputSchema /
// structuredContent shape, or argument handling ships GREEN for any
// advertised tool the conformance workflow only LISTS but never CALLS.
// That is the false-green class this ratchet exists to catch: an
// advertised Story or Pair tool that is descriptor-only in conformance.
//
// The harness records every tool name it
// drove through `callTool()` (in a `Set`), then calls this gate with:
//
//   - `advertised`  — the live `tools/list` names (sourced from the
//                     server's own `tool-names.json` fixture, the same
//                     single-source the name ratchet compares against).
//   - `called`      — the Set of names the workflow SDK-invoked.
//   - `exclusions`  — a REVIEWED table: `{ "<tool>": "<rationale>" }`.
//                     Each row names a tool that is intentionally NOT
//                     SDK-call-covered HERE and states WHERE its
//                     alternative coverage lives (a live-only gate, a
//                     hermetic harness, a lower-layer unit/fixture pin).
//                     A bare-string rationale is mandatory and must be
//                     non-empty — an empty exclusion is a silent hole.
//
// The gate fails when a tool is NEITHER called NOR excluded-with-
// rationale, AND when the exclusion table carries a stale row (a name
// that is excluded but was also called, or that the catalogue no longer
// advertises) — so the table cannot rot into a rubber stamp. A NEW
// advertised tool that the author forgets to probe trips RED with a
// message naming it and pointing at the two ways to green it (add a
// probe, or add a reviewed exclusion row).
//
// Throws on the first violation with a descriptive, server-agnostic
// message. Returns nothing.
// ---------------------------------------------------------------------
function assertCallCoverageRatchet({ advertised, called, exclusions = {} }) {
  const advertisedSet = new Set(advertised);
  const calledSet = called instanceof Set ? called : new Set(called);
  const excludedNames = Object.keys(exclusions);

  // 0. The exclusion table must not carry stale rows. A row whose tool
  // is not advertised is a dangling rationale (the tool was renamed /
  // removed); a row whose tool WAS called is a contradiction (it claims
  // "not covered here" but the workflow drove it) — both mean the table
  // drifted from reality and must be corrected, not silently tolerated.
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

  // 1. Every excluded row MUST carry a non-empty string rationale. An
  // empty / non-string rationale is a silent hole dressed as a decision.
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

  // 2. The load-bearing check: every advertised tool is either driven
  // through callTool() or carries a reviewed exclusion. An advertised
  // tool that is neither trips RED — the descriptor-only false-green
  // this ratchet exists to catch.
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

// ---------------------------------------------------------------------
// Envelope readers / coverage tracker.
//
// Three SDK-envelope-shape helpers shared by every conformance body.
// None pins a per-test contract — they are pure mechanics feeding the
// shared ratchets above, so they live here once.
// ---------------------------------------------------------------------

// Concatenate the `.text` of every content block from an SDK callTool
// response into one newline-joined string. Tolerates a missing / non-
// array `content` (returns '') so a malformed envelope reads as empty
// rather than throwing — callers grep the whole string for sentinels /
// EDN, so a no-text block must contribute nothing, not crash the search.
function responseText(resp) {
  if (!resp || !Array.isArray(resp.content)) return '';
  return resp.content
    .map((c) => (c && typeof c.text === 'string' ? c.text : ''))
    .join('\n');
}

// SDK callTool() coverage tracker. Wrap the raw SDK client
// in a Proxy that records the tool name on each `callTool({name,…})` into
// a fresh `called` Set and transparently delegates every other client
// member (listTools, getServerVersion, request, close, …) to the real
// client — so existing call sites read unchanged. Returns
// `{ client, called }`: drive the workflow through `client`, then feed
// `called` to `assertCallCoverageRatchet`. A callTool that bypasses the
// proxy can't quietly erode coverage — the tool simply goes unrecorded
// and the ratchet catches it as uncovered.
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

// Read a callTool response's `structuredContent` through
// `decodeDedupEnvelope`. A dedup-eligible tool ships its structured
// payload wrapped in a `{:rf.mcp/dedup-table …}` envelope that a real MCP
// client expands via `de-dupe.core/expand` before reading semantic slots;
// this mirrors that. Idempotent on payloads without the marker, so it is
// safe to route EVERY structuredContent read through it — a tool gaining
// or losing dedup-eligibility doesn't break the suite (the slot is
// reachable either way).
function structured(resp) {
  return decodeDedupEnvelope((resp && resp.structuredContent) || {});
}

// Universal isError <-> :ok? cross-check. The spec contract
// (spec/003-Tool-Catalogue.md §381) is universal: EVERY `:ok? false`
// structuredContent MUST carry `isError === true`, and every `:ok? true`
// MUST carry a falsy isError. This cross-check pins the contract for an
// ARBITRARY structured envelope at the SDK boundary — a single gate
// covering every tool, rather than tool-specific + outcome-specific
// checks — catching any tool that decouples its isError flag from its
// `:ok?` slot (the class the pair-mcp read-dom/read-ui envelope
// belonged to, rf2-87h71e / rf2-q7cavs).
//
// Reads `:ok?` through `structured()` — the shared dedup-decoder —
// rather than the raw wire `resp.structuredContent`. A dedup-eligible
// tool ships its structured payload wrapped in
// `{:rf.mcp/dedup-table {...}}`; reading the raw field finds no
// top-level `:ok?` slot at all, so this cross-check would silently no-op
// on exactly the envelopes a real MCP client (which always expands the
// dedup table before reading semantic slots) would grade (rf2-6i2yi4
// finding 1). Tolerates an envelope with no `:ok?` slot (a non-`:ok?`-
// shaped result is out of scope — there is nothing to cross-check).
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
