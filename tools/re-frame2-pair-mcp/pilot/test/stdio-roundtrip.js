// Pilot stdio round-trip test.
//
// Spawns the compiled pilot.js as a subprocess and drives it through
// the standard MCP handshake:
//   1. initialize
//   2. notifications/initialized
//   3. tools/list  (expect: ping + nrepl-ping)
//   4. tools/call name=ping  (expect: pong)
//
// Exit code 0 on success, non-zero on any deviation. Designed to run
// under `node test/stdio-roundtrip.js` from the pilot dir.
//
// nREPL handshake is NOT covered here (it needs a live shadow-cljs
// nREPL). That's exercised by the separate nrepl-handshake test if
// you have a port ready; this harness focuses on the stdio surface
// the SDK provides.

const { spawn } = require('node:child_process');
const path = require('node:path');

const PILOT = path.join(__dirname, '..', 'out', 'pilot.js');

function sendFrame(child, msg) {
  child.stdin.write(JSON.stringify(msg) + '\n');
}

function frameReader(child, onFrame) {
  let buf = '';
  child.stdout.on('data', (chunk) => {
    buf += chunk.toString('utf8');
    let idx;
    while ((idx = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, idx).trim();
      buf = buf.slice(idx + 1);
      if (!line) continue;
      try {
        onFrame(JSON.parse(line));
      } catch (e) {
        console.error('FAIL: malformed JSON frame on stdout:', line);
        process.exit(1);
      }
    }
  });
}

async function run() {
  const child = spawn(process.execPath, [PILOT], { stdio: ['pipe', 'pipe', 'pipe'] });
  child.stderr.on('data', (d) => process.stderr.write('[server] ' + d.toString()));

  let next = 1;
  const pending = new Map();
  frameReader(child, (frame) => {
    if (frame.id && pending.has(frame.id)) {
      pending.get(frame.id)(frame);
      pending.delete(frame.id);
    }
  });

  function call(method, params) {
    const id = next++;
    return new Promise((resolve) => {
      pending.set(id, resolve);
      sendFrame(child, { jsonrpc: '2.0', id, method, params });
    });
  }

  function notify(method, params) {
    sendFrame(child, { jsonrpc: '2.0', method, params });
  }

  // give the server a beat to install its handlers
  await new Promise((r) => setTimeout(r, 250));

  // 1. initialize
  const initResp = await call('initialize', {
    protocolVersion: '2025-06-18',
    capabilities: {},
    clientInfo: { name: 'pilot-test', version: '0' },
  });
  if (!initResp.result || !initResp.result.protocolVersion) {
    console.error('FAIL: initialize:', JSON.stringify(initResp));
    process.exit(1);
  }
  console.log('OK   initialize ->', initResp.result.serverInfo);

  // 2. initialized notification
  notify('notifications/initialized', {});

  // 3. tools/list
  const listResp = await call('tools/list', {});
  const toolNames = (listResp.result?.tools || []).map((t) => t.name).sort();
  const expected = ['nrepl-ping', 'ping'];
  if (JSON.stringify(toolNames) !== JSON.stringify(expected)) {
    console.error('FAIL: tools/list — expected', expected, 'got', toolNames);
    process.exit(1);
  }
  console.log('OK   tools/list ->', toolNames.join(', '));

  // 4. tools/call ping
  const pingResp = await call('tools/call', { name: 'ping', arguments: {} });
  const text = pingResp.result?.content?.[0]?.text;
  if (text !== 'pong') {
    console.error('FAIL: tools/call ping — expected "pong", got:', JSON.stringify(pingResp));
    process.exit(1);
  }
  console.log('OK   tools/call ping -> pong');

  // 5. tools/call nrepl-ping with no port (should fail gracefully, not crash)
  const nreplResp = await call('tools/call', { name: 'nrepl-ping', arguments: { port: 1 } });
  if (!nreplResp.result?.isError) {
    console.error('FAIL: nrepl-ping with port=1 should error, got:', JSON.stringify(nreplResp));
    process.exit(1);
  }
  console.log('OK   tools/call nrepl-ping (port=1) -> graceful isError');

  child.kill();
  console.log('\nPILOT GREEN');
  process.exit(0);
}

run().catch((e) => {
  console.error('FAIL:', e);
  process.exit(1);
});
