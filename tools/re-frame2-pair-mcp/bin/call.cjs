#!/usr/bin/env node
/*
 * `re-frame2-pair-call <tool> ['<json-args>'] [-- <server launch flags>]`
 *
 * One Pair tool call from a shell, for an agent that cannot register an MCP
 * server with its host: start `out/server.js` as a child process, run the MCP
 * stdio handshake, make one `tools/call`, print the result and close stdin,
 * which ends the server. The server, its tools, gates and wire caps are the
 * ones a registered host reaches; only the client differs. Wire framing and
 * lifecycle: spec/001-Wire-Protocol.md.
 *
 * stdout carries the tool's text result; stderr carries the server's log.
 * Exit status: 0 a result, 1 a tool error (`isError`), 2 no result at all.
 */
'use strict';

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { StringDecoder } = require('node:string_decoder');

const PKG_DIR = path.join(__dirname, '..');
const SERVER = path.join(PKG_DIR, 'out', 'server.js');
const PROTOCOL_VERSION = '2025-06-18';
// How long the server may take to exit once stdin closes before it is
// killed. The EOF exit is prompt; this only bounds a wedged teardown.
const EXIT_GRACE_MS = 5000;

const USAGE = `usage: re-frame2-pair-call <tool> ['<json-args>'] [-- <server launch flags>]

  <tool>         a Pair MCP tool name, e.g. discover-app, read-sub
  <json-args>    the tool's arguments as one JSON object (default {})
  -- <flags>     passed to out/server.js, e.g. -- --port-file /abs/.shadow-cljs/nrepl.port

Prints the tool's text result on stdout and the server's log on stderr.
Exit 0 on a result, 1 on a tool error (isError), 2 when no result came back.`;

function usageError(msg) {
  process.stderr.write(`re-frame2-pair-call: ${msg}\n`);
  process.exit(2);
}

function parseArgv(argv) {
  const sep = argv.indexOf('--');
  const own = sep < 0 ? argv : argv.slice(0, sep);
  const serverFlags = sep < 0 ? [] : argv.slice(sep + 1);
  if (own.length === 0 || own[0] === '-h' || own[0] === '--help') {
    process.stdout.write(USAGE + '\n');
    process.exit(own.length === 0 ? 2 : 0);
  }
  if (own.length > 2) {
    usageError(`expected <tool> and at most one JSON argument before "--", got ${own.length} words\n${USAGE}`);
  }
  let args = {};
  if (own.length === 2) {
    try {
      args = JSON.parse(own[1]);
    } catch (e) {
      usageError(`<json-args> is not JSON (${e.message}): ${own[1]}`);
    }
    if (args === null || typeof args !== 'object' || Array.isArray(args)) {
      usageError(`<json-args> must be a JSON object, got: ${own[1]}`);
    }
  }
  return { tool: own[0], args, serverFlags };
}

function main() {
  const { tool, args, serverFlags } = parseArgv(process.argv.slice(2));
  if (!fs.existsSync(SERVER)) {
    usageError(`no server at ${SERVER}; build it first with npm run build in ${PKG_DIR}`);
  }

  const child = spawn(process.execPath, [SERVER, ...serverFlags], {
    stdio: ['pipe', 'pipe', 'inherit'],
  });
  // `null` until the call has an outcome; then the process's exit status.
  let outcome = null;

  const finish = (code, err) => {
    if (outcome !== null) return;
    outcome = code;
    if (err) process.stderr.write(`re-frame2-pair-call: ${err}\n`);
    child.stdin.end();
    setTimeout(() => child.kill(), EXIT_GRACE_MS).unref();
  };

  child.on('error', (e) => finish(2, `could not start the server: ${e.message}`));
  // A server that dies mid-handshake closes the pipe under a pending write;
  // its exit, below, is what reports that.
  child.stdin.on('error', () => {});
  child.on('exit', (code, signal) => {
    if (outcome === null) finish(2, `server exited (${signal || code}) before answering ${tool}`);
    // Let stdout drain rather than calling process.exit, which can cut a
    // large result short on a pipe.
    process.exitCode = outcome;
  });

  const send = (msg) => child.stdin.write(JSON.stringify(msg) + '\n');

  // A pipe chunk can end inside a multibyte character, so one decoder spans
  // the chunks and holds a partial character until the rest arrives.
  const decoder = new StringDecoder('utf8');
  let buf = '';
  child.stdout.on('data', (chunk) => {
    buf += decoder.write(chunk);
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i).trim();
      buf = buf.slice(i + 1);
      if (!line || outcome !== null) continue;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch {
        finish(2, `non-JSON line on server stdout: ${line}`);
        return;
      }
      // A request from the server (it carries both `method` and `id`), such
      // as the `roots/list` port discovery sends. This client has no
      // workspace roots to offer, and an unanswered request stalls the call
      // for the SDK's request timeout, so decline it at once.
      if (msg.method && msg.id != null) {
        send({ jsonrpc: '2.0', id: msg.id, error: { code: -32601, message: 'Method not found' } });
        continue;
      }
      if (msg.method) continue;
      if (msg.id === 1 && msg.error) {
        finish(2, `initialize failed: ${JSON.stringify(msg.error)}`);
        return;
      }
      if (msg.id !== 2) continue;
      if (msg.error) {
        finish(2, `tools/call failed: ${JSON.stringify(msg.error)}`);
        return;
      }
      const result = msg.result || {};
      const text = (result.content || [])
        .filter((c) => c.type === 'text')
        .map((c) => c.text)
        .join('\n');
      process.stdout.write(text + '\n');
      finish(result.isError ? 1 : 0);
      return;
    }
  });

  send({
    jsonrpc: '2.0',
    id: 1,
    method: 'initialize',
    params: {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: 're-frame2-pair-call', version: '0' },
    },
  });
  send({ jsonrpc: '2.0', method: 'notifications/initialized' });
  send({ jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: tool, arguments: args } });
}

main();
