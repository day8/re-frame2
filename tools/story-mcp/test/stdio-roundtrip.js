// Stdio integration test for tools/story-mcp.
//
// Spawns the JVM story-mcp server via `clojure -M -m re-frame.story-mcp.server`
// and walks the MCP handshake against it:
//
//   - initialize                  (negotiate protocolVersion 2025-06-18)
//   - notifications/initialized   (notification, no response)
//   - tools/list                  (expect all 17 tools per spec/002)
//   - tools/call list-tags        (read-side smoke; canonical seven present)
//   - tools/call list-substrates  (returns a vector, possibly empty on JVM)
//   - tools/call get-story-instructions (text content)
//   - tools/call preview-variant on an unregistered variant
//                                 (expect tool-execution error — proves the
//                                  tool is wired into the dispatch table)
//   - tools/call get-variant on an unregistered variant
//                                 (same shape — wires to dispatch table)
//   - tools/call register-variant without --allow-writes
//                                 (expect gated tool-execution error — proves
//                                  the gating contract per IMPL-SPEC §7.3)
//   - tools/call no-such-tool     (expect method-not-found protocol error)
//   - ping                        (empty result, liveness probe)
//
// A separate live-server.js drives a representative workflow against a
// running server with --allow-writes enabled.
//
// Run with: `node test/stdio-roundtrip.js` from tools/story-mcp/. Exits 0 on
// success, 1 with a FAIL marker on the failing assertion.

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const CWD = path.join(__dirname, '..');

// Canonical tool-name list (rf2-36upq TE7) — single source of truth shared
// with the JVM test corpus (tools_test.clj `tool-names-fixture`). Both
// consumers parse this JSON; a drift in the registry surfaces in one
// place rather than two.
const TOOL_NAMES = JSON.parse(
  fs.readFileSync(path.join(__dirname, 'fixtures', 'tool-names.json'), 'utf8'),
).names;

// The agent-host canonical launch (per tools/story-mcp/README.md). On
// Linux CI the `clojure` binary lands via DeLaGuardo/setup-clojure; on
// Windows it's an .exe on PATH (the GitHub-hosted runner provides it).
// We spawn the binary directly — no shell wrap — so Node 24+'s
// shell-with-args deprecation (DEP0190) stays quiet.
const CLOJURE = process.env.STORY_MCP_CMD || 'clojure';
const ARGS = ['-M', '-m', 're-frame.story-mcp.server'];

function run() {
  return new Promise((resolve, reject) => {
    const env = { ...process.env };
    const child = spawn(CLOJURE, ARGS, {
      stdio: ['pipe', 'pipe', 'pipe'],
      cwd: CWD,
      env,
    });
    child.stderr.on('data', (d) => process.stderr.write('[server] ' + d.toString()));

    let next = 1;
    const pending = new Map();
    let buf = '';
    child.stdout.on('data', (chunk) => {
      buf += chunk.toString('utf8');
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        if (!line) continue;
        try {
          const f = JSON.parse(line);
          if (f.id != null && pending.has(f.id)) {
            pending.get(f.id)(f);
            pending.delete(f.id);
          }
        } catch (e) {
          console.error('FAIL: malformed JSON on stdout:', line);
          child.kill();
          reject(e);
        }
      }
    });
    const call = (m, p) => {
      const id = next++;
      return new Promise((r) => {
        pending.set(id, r);
        child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method: m, params: p }) + '\n');
      });
    };
    const notify = (m, p) =>
      child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: m, params: p }) + '\n');

    // Watchdog: kill the child if assertions take longer than 60s. Clojure
    // boot on a cold CI runner can take 10-20s; 60s is comfortably above
    // that without leaving a hung process.
    const watchdog = setTimeout(() => {
      console.error('FAIL: watchdog timeout (60s) — server did not respond');
      child.kill();
      reject(new Error('watchdog timeout'));
    }, 60000);

    (async () => {
      // 1. initialize handshake. The JVM boot needs a moment; the first
      // call's response will land whenever the server hits its read loop.
      const init = await call('initialize', {
        protocolVersion: '2025-06-18',
        capabilities: {},
        clientInfo: { name: 'roundtrip', version: '0' },
      });
      if (!init.result?.protocolVersion) {
        throw new Error('initialize failed: ' + JSON.stringify(init));
      }
      console.log('OK   initialize ->', init.result.serverInfo);

      notify('notifications/initialized', {});

      // 2. tools/list — expect the full registry per spec/002-Tool-Registry.md
      // (rf2-luhdu record-as-variant, rf2-mqp1u list-decorators,
      //  rf2-i0kyy get-docs-markdown). The canonical name list is shared
      // with the JVM test corpus via test/fixtures/tool-names.json
      // (rf2-36upq TE7) — a registry change updates one file, not two.
      const list = await call('tools/list', {});
      const names = (list.result?.tools || []).map((t) => t.name).sort();
      if (JSON.stringify(names) !== JSON.stringify(TOOL_NAMES)) {
        throw new Error(
          'tools/list mismatch:\n  expected ' +
            JSON.stringify(TOOL_NAMES) +
            '\n  got      ' +
            JSON.stringify(names),
        );
      }
      console.log('OK   tools/list -> ' + TOOL_NAMES.length + ' tools:', names.join(', '));

      // 2b. Verify the preview-variant descriptor: required `variant-id`,
      // optional `substrate`/`active-modes`/`cell-overrides`/`base-url`.
      const previewDesc = (list.result?.tools || []).find((t) => t.name === 'preview-variant');
      if (!previewDesc) throw new Error('preview-variant descriptor missing from tools/list');
      const previewProps = previewDesc.inputSchema?.properties || {};
      for (const k of ['variant-id', 'substrate', 'active-modes', 'cell-overrides', 'base-url']) {
        if (!(k in previewProps)) {
          throw new Error('preview-variant inputSchema missing property: ' + k);
        }
      }
      if (!previewDesc.inputSchema?.required?.includes('variant-id')) {
        throw new Error('preview-variant.inputSchema missing required: variant-id');
      }
      console.log('OK   preview-variant descriptor -> variant-id (required) + substrate/active-modes/cell-overrides/base-url');

      // 2c. Verify the register-variant descriptor: required
      // `variant-id` + `body`. This is the write-gated tool per IMPL-SPEC
      // §7.3 — descriptor advertised whether the gate is open or not.
      const regDesc = (list.result?.tools || []).find((t) => t.name === 'register-variant');
      if (!regDesc) throw new Error('register-variant descriptor missing from tools/list');
      const regReq = regDesc.inputSchema?.required || [];
      for (const k of ['variant-id', 'body']) {
        if (!regReq.includes(k)) {
          throw new Error('register-variant.inputSchema missing required: ' + k);
        }
      }
      console.log('OK   register-variant descriptor -> variant-id + body required');

      // 2d. Verify the record-as-variant descriptor (rf2-luhdu landing):
      // required `variant-id`; optional duration-ms / new-variant-id / doc
      // / extends / alias / write-back. (Wire-key `write-back` — no `?` per
      // Anthropic's input-schema property-name regex; rf2-pmwgn.)
      const recDesc = (list.result?.tools || []).find((t) => t.name === 'record-as-variant');
      if (!recDesc) throw new Error('record-as-variant descriptor missing from tools/list');
      const recProps = recDesc.inputSchema?.properties || {};
      for (const k of ['variant-id', 'duration-ms', 'new-variant-id', 'doc', 'extends', 'alias', 'write-back']) {
        if (!(k in recProps)) {
          throw new Error('record-as-variant inputSchema missing property: ' + k);
        }
      }
      if (!recDesc.inputSchema?.required?.includes('variant-id')) {
        throw new Error('record-as-variant.inputSchema missing required: variant-id');
      }
      console.log('OK   record-as-variant descriptor -> variant-id (required) + duration-ms/new-variant-id/doc/extends/alias/write-back');

      // 3. tools/call list-tags — read-side smoke; the canonical seven
      // tags must be present (loaded by `install-canonical-vocabulary!` in
      // server `boot!`).
      const tagsResp = await call('tools/call', { name: 'list-tags', arguments: {} });
      if (tagsResp.result?.isError) {
        throw new Error('list-tags returned isError: ' + JSON.stringify(tagsResp));
      }
      const tagsStructured = tagsResp.result?.structuredContent;
      const canonical = (tagsStructured?.canonical || []).map(String);
      // Cheshire's `generate-string` writes keywords as bare names without
      // the leading colon — `:dev` arrives on the wire as `"dev"`.
      for (const tag of ['dev', 'docs', 'test', 'screenshot', 'experimental', 'internal', 'agent']) {
        if (!canonical.includes(tag)) {
          throw new Error('list-tags missing canonical tag ' + tag + '; got ' + JSON.stringify(canonical));
        }
      }
      console.log('OK   tools/call list-tags -> canonical seven present:', canonical.join(', '));

      // 3b. tools/call list-substrates — JVM-standalone deploy returns an
      // empty substrate set; the contract is the result-shape (a vector,
      // possibly empty).
      const subsResp = await call('tools/call', { name: 'list-substrates', arguments: {} });
      if (subsResp.result?.isError) {
        throw new Error('list-substrates returned isError: ' + JSON.stringify(subsResp));
      }
      const subsArr = subsResp.result?.structuredContent?.substrates;
      if (!Array.isArray(subsArr)) {
        throw new Error('list-substrates :substrates not an array: ' + JSON.stringify(subsResp));
      }
      console.log('OK   tools/call list-substrates -> vector (count=' + subsArr.length + ')');

      // 3c. tools/call get-story-instructions — returns the agent-onboarding
      // text. Smoke-check key authoring vocab is present.
      const instrResp = await call('tools/call', { name: 'get-story-instructions', arguments: {} });
      const instrText = instrResp.result?.content?.[0]?.text || '';
      if (instrResp.result?.isError || !instrText.includes('reg-story')) {
        throw new Error('get-story-instructions did not contain `reg-story`: ' + JSON.stringify(instrResp).slice(0, 300));
      }
      console.log('OK   tools/call get-story-instructions -> text contains reg-story/reg-variant');

      // 4. tools/call preview-variant on an unknown variant — expect
      // tool-execution error (`isError: true`) with a "not found" message.
      // This proves the tool is wired into the dispatch table; in degraded
      // states the response would carry a different shape.
      const prevResp = await call('tools/call', {
        name: 'preview-variant',
        arguments: { 'variant-id': 'story.no-such-fixture/missing' },
      });
      const prevText = prevResp.result?.content?.[0]?.text || '';
      if (!prevResp.result?.isError || !/not found/i.test(prevText)) {
        throw new Error('preview-variant on missing variant did not yield isError + not-found; got: ' + JSON.stringify(prevResp));
      }
      console.log('OK   tools/call preview-variant (missing) -> isError + not-found');

      // 4b. tools/call get-variant on an unknown variant — same shape.
      const getVResp = await call('tools/call', {
        name: 'get-variant',
        arguments: { 'variant-id': 'story.no-such-fixture/missing' },
      });
      const getVText = getVResp.result?.content?.[0]?.text || '';
      if (!getVResp.result?.isError || !/not found/i.test(getVText)) {
        throw new Error('get-variant on missing variant did not yield isError + not-found; got: ' + JSON.stringify(getVResp));
      }
      console.log('OK   tools/call get-variant (missing) -> isError + not-found');

      // 5. tools/call register-variant without --allow-writes — the
      // server boots with the gate closed by default; the write tool must
      // surface a gated tool-execution error.
      const regResp = await call('tools/call', {
        name: 'register-variant',
        arguments: {
          'variant-id': 'story.gated/probe',
          body: { doc: 'probe' },
        },
      });
      const regText = regResp.result?.content?.[0]?.text || '';
      if (!regResp.result?.isError || !/Write surface disabled/.test(regText)) {
        throw new Error('register-variant gated path expected; got: ' + JSON.stringify(regResp));
      }
      const regStruct = regResp.result?.structuredContent;
      if (!regStruct || regStruct.gated !== true) {
        throw new Error('register-variant gated result missing structuredContent.gated=true: ' + JSON.stringify(regResp));
      }
      console.log('OK   tools/call register-variant (gated) -> isError + structuredContent.gated=true');

      // 6. tools/call no-such-tool — unknown tool name; the dispatcher
      // surfaces a protocol-level method-not-found (-32601) per
      // server.cljc's `handle-tools-call`.
      const unkResp = await call('tools/call', {
        name: 'no-such-tool',
        arguments: {},
      });
      if (unkResp.error?.code !== -32601) {
        throw new Error('no-such-tool should yield method-not-found (-32601); got: ' + JSON.stringify(unkResp));
      }
      console.log('OK   tools/call no-such-tool -> -32601 method-not-found');

      // 7. ping — empty result, MCP §Utilities/ping liveness probe.
      const pingResp = await call('ping', {});
      if (pingResp.result === undefined || Object.keys(pingResp.result || {}).length !== 0) {
        throw new Error('ping should return empty {}; got: ' + JSON.stringify(pingResp));
      }
      console.log('OK   ping -> empty result {}');

      clearTimeout(watchdog);
      child.kill();
      console.log('\nSTORY-MCP STDIO ROUND-TRIP GREEN');
      resolve();
    })().catch((e) => {
      clearTimeout(watchdog);
      child.kill();
      reject(e);
    });
  });
}

run().then(() => process.exit(0)).catch((e) => {
  console.error('FAIL:', e.message);
  process.exit(1);
});
