/*
 * Shared helpers for pair2 e2e specs (rf2-cxik).
 *
 * - `runShim(ctx, subcmd, args, env?)` — execute
 *   `bb scripts/ops.clj <subcmd> [args]` against the live fixture's
 *   nREPL, buffering stdout/stderr into ctx.diagnostics when present.
 *   The older positional call shape is still accepted for focused
 *   helper tests. Returns { exit, stdout, stderr }. CWD is the fixture
 *   dir so ops.clj's port-file lookup finds the live shadow-cljs port.
 *
 * - `parseEdn(s)` — naive but adequate parser for the structured
 *   shapes we assert on (maps, vectors, keywords, strings, ints,
 *   booleans, nil). Specs assert on shape, not bytewise equality, so
 *   reading the full Clojure reader isn't necessary.
 *
 * - `openPage(ctx)` — boots a headless Chromium, navigates to the
 *   fixture URL, returns { browser, page }.
 */
'use strict';

const { spawnSync } = require('child_process');
const path = require('path');

function isVerboseTests(env = process.env) {
  return env.RF2_VERBOSE_TESTS === '1';
}

function createDiagnostics({ verbose = isVerboseTests() } = {}) {
  const entries = [];

  const writeLive = (line, stream) => {
    if (!verbose) return;
    const write = stream === 'stderr' ? console.error : console.log;
    write(line);
  };

  return {
    verbose,
    add(line, stream = 'stdout') {
      if (line == null) return;
      const normalized = String(line).replace(/\r\n/g, '\n');
      for (const part of normalized.split('\n')) {
        entries.push({ stream, text: part });
        writeLive(part, stream);
      }
    },
    isEmpty() {
      return entries.length === 0;
    },
    flush({ stdout = console.log, stderr = console.error } = {}) {
      for (const entry of entries) {
        if (entry.stream === 'stderr') stderr(entry.text);
        else stdout(entry.text);
      }
    },
  };
}

function flushDiagnostics(diagnostics) {
  if (!diagnostics || diagnostics.verbose || diagnostics.isEmpty()) return;
  console.error('--- pair2 e2e diagnostics ---');
  diagnostics.flush({
    stdout: (line) => console.error(line),
    stderr: (line) => console.error(line),
  });
  console.error('-----------------------------');
}

function addChunk(diagnostics, prefix, chunk, stream = 'stdout') {
  if (!diagnostics || !chunk) return;
  const normalized = String(chunk).replace(/\r\n/g, '\n');
  for (const line of normalized.split('\n')) {
    if (line.length === 0) continue;
    diagnostics.add(`${prefix}${line}`, stream);
  }
}

function runShim(ctxOrSkillRoot, fixtureDirOrSubcmd, subcmdOrArgs, argsOrEnv = [], envOrEmpty = {}) {
  let ctx = null;
  let skillRoot;
  let fixtureDir;
  let subcmd;
  let args;
  let env;

  if (ctxOrSkillRoot && typeof ctxOrSkillRoot === 'object') {
    ctx = ctxOrSkillRoot;
    skillRoot = ctx.skillRoot;
    fixtureDir = ctx.fixtureDir;
    subcmd = fixtureDirOrSubcmd;
    args = Array.isArray(subcmdOrArgs) ? subcmdOrArgs : [];
    env = Array.isArray(subcmdOrArgs) ? argsOrEnv : (subcmdOrArgs || {});
  } else {
    skillRoot = ctxOrSkillRoot;
    fixtureDir = fixtureDirOrSubcmd;
    subcmd = subcmdOrArgs;
    args = argsOrEnv;
    env = envOrEmpty;
  }

  args = Array.isArray(args) ? args : [];
  env = env || {};

  const opsClj = path.join(skillRoot, 'scripts', 'ops.clj');
  const diagnostics = ctx && ctx.diagnostics;
  const label = ctx && ctx.spec ? ctx.spec : subcmd;
  if (diagnostics) {
    diagnostics.add(
      `[pair2-e2e:${label}] process: bb ${opsClj} ${subcmd} ${args.join(' ')}`.trim(),
    );
  }
  const res = spawnSync('bb', [opsClj, subcmd, ...args], {
    cwd: fixtureDir,
    encoding: 'utf8',
    maxBuffer: 16 * 1024 * 1024,
    env: { ...process.env, ...env },
  });
  if (diagnostics) {
    diagnostics.add(`[pair2-e2e:${label}] bb exit ${res.status}`);
    if (res.error) diagnostics.add(`[pair2-e2e:${label}] bb error ${res.error.message}`, 'stderr');
    addChunk(diagnostics, `[pair2-e2e:${label}:stdout] `, res.stdout);
    addChunk(diagnostics, `[pair2-e2e:${label}:stderr] `, res.stderr, 'stderr');
  }
  return {
    exit: res.status,
    stdout: res.stdout || '',
    stderr: res.stderr || '',
  };
}

/*
 * Minimal Clojure edn parser. Handles:
 *
 *   - nil, true, false
 *   - integers (and bigints) — no rationals
 *   - keywords (incl. namespaced — :ns/k)
 *   - strings (no escapes beyond \" and \\)
 *   - vectors [...], maps {...}, lists (...)
 *
 * Returns the parsed JS value or throws on malformed input. Specs that
 * need full reader fidelity should call out to bb instead.
 */
function parseEdn(input) {
  const src = String(input || '').trim();
  if (src === '') return null;

  let i = 0;
  function skip() {
    while (i < src.length) {
      const c = src[i];
      if (c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === ',') {
        i++;
      } else if (c === ';') {
        while (i < src.length && src[i] !== '\n') i++;
      } else {
        break;
      }
    }
  }
  function readToken() {
    let j = i;
    while (j < src.length && !/[\s,\(\)\[\]\{\}]/.test(src[j])) j++;
    const tok = src.slice(i, j);
    i = j;
    return tok;
  }
  function readString() {
    if (src[i] !== '"') throw new Error('expected "');
    i++;
    let out = '';
    while (i < src.length && src[i] !== '"') {
      if (src[i] === '\\' && i + 1 < src.length) {
        out += src[i + 1] === 'n' ? '\n' : src[i + 1];
        i += 2;
      } else {
        out += src[i++];
      }
    }
    if (src[i] !== '"') throw new Error('unterminated string');
    i++;
    return out;
  }
  function readForm() {
    skip();
    if (i >= src.length) throw new Error('unexpected EOF');
    const c = src[i];
    if (c === '"') return readString();
    if (c === '[') {
      i++;
      const out = [];
      while (true) {
        skip();
        if (src[i] === ']') { i++; return out; }
        out.push(readForm());
      }
    }
    if (c === '(') {
      i++;
      const out = [];
      while (true) {
        skip();
        if (src[i] === ')') { i++; return out; }
        out.push(readForm());
      }
    }
    if (c === '{') {
      i++;
      const out = new Map();
      while (true) {
        skip();
        if (src[i] === '}') { i++;
          // Convert pure-keyword-keyed maps to plain JS objects for
          // ergonomic assertion access. Mixed-key maps stay as Map.
          let allKw = true;
          for (const k of out.keys()) {
            if (typeof k !== 'string' || !k.startsWith(':')) { allKw = false; break; }
          }
          if (!allKw) return out;
          const obj = {};
          for (const [k, v] of out.entries()) obj[k.slice(1)] = v;
          return obj;
        }
        const k = readForm();
        const v = readForm();
        out.set(k, v);
      }
    }
    if (c === ':') {
      const tok = readToken();
      return tok; // keep the leading ':' so callers can disambiguate
    }
    const tok = readToken();
    if (tok === 'nil') return null;
    if (tok === 'true') return true;
    if (tok === 'false') return false;
    if (/^-?\d+$/.test(tok)) return parseInt(tok, 10);
    if (/^-?\d+\.\d+$/.test(tok)) return parseFloat(tok);
    // symbols pass through as strings
    return tok;
  }
  return readForm();
}

/*
 * Open a Playwright browser. Lazy-required so the runner doesn't fail
 * when playwright isn't installed — specs that need a browser call
 * this, others (e.g. discover-only) don't.
 */
async function openPage(ctx) {
  const playwright = require('playwright');
  const browser = await playwright.chromium.launch({ headless: true });
  const context = await browser.newContext();
  const page = await context.newPage();
  const diagnostics = ctx && ctx.diagnostics;
  const label = ctx && ctx.spec ? ctx.spec : 'open-page';
  if (diagnostics) {
    diagnostics.add(`[pair2-e2e:${label}] URL: ${ctx.fixtureUrl}`);
  }
  page.on('console', (msg) => {
    if (diagnostics) diagnostics.add(`[pair2-e2e:${label}:browser:${msg.type()}] ${msg.text()}`);
  });
  page.on('pageerror', (err) => {
    if (!diagnostics) return;
    diagnostics.add(`[pair2-e2e:${label}:browser:pageerror] ${err.message}`, 'stderr');
    if (err.stack) diagnostics.add(err.stack, 'stderr');
  });
  page.on('framenavigated', (frame) => {
    if (diagnostics && frame === page.mainFrame()) {
      diagnostics.add(`[pair2-e2e:${label}:browser:navigation] ${frame.url()}`);
    }
  });
  await page.goto(ctx.fixtureUrl, { waitUntil: 'load' });
  return { browser, page };
}

module.exports = {
  createDiagnostics,
  flushDiagnostics,
  isVerboseTests,
  runShim,
  parseEdn,
  openPage,
};
