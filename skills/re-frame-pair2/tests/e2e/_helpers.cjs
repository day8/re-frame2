/*
 * Shared helpers for pair2 e2e specs (rf2-cxik).
 *
 * - `runShim(skillRoot, fixtureDir, subcmd, args, env?)` — execute
 *   `bb scripts/ops.clj <subcmd> [args]` against the live fixture's
 *   nREPL. Returns { exit, stdout, stderr }. CWD is the fixture dir
 *   so ops.clj's port-file lookup finds the live shadow-cljs port.
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

function runShim(skillRoot, fixtureDir, subcmd, args = [], env = {}) {
  const opsClj = path.join(skillRoot, 'scripts', 'ops.clj');
  const res = spawnSync('bb', [opsClj, subcmd, ...args], {
    cwd: fixtureDir,
    encoding: 'utf8',
    env: { ...process.env, ...env },
  });
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
  await page.goto(ctx.fixtureUrl);
  return { browser, page };
}

module.exports = { runShim, parseEdn, openPage };
