#!/usr/bin/env node
'use strict';
// THE BAKE MANIFEST COUNTS BYTES, NOT CODE UNITS — rf2-2rtt6.114.
//
//     node freehand/test/re_frame/bench/hicasso/ssr/bake_bytes.test.cjs
//
// THE DEFECT THIS PINS. `driver.cjs bake` wrote a manifest whose
// `documentBytes`, `bodyBytes` and `payloadBytes` were `String.prototype
// .length` — UTF-16 CODE UNITS — while the field names, the console
// column's ` B` suffix and the live server's response log all said bytes.
// The two agree only for ASCII. They did not agree here: the corpus gives
// every row an em dash in its title and the `defhost` fallback an
// ellipsis, so `dogfood-snapshot` claimed 3101 for a document of which
// 3119 bytes were written, and `defhost-ssr-policy` claimed 485 against
// 491. rf2-2rtt6.86's own merged-PR audit prescribed the repair and it was
// never filed; the rf2-2rtt6.88 dossier then had to publish NO size figure
// from that manifest, and said so on the page.
//
// WHY THIS FILE, AND NOT A BAKE ASSERTION ALONE. `bake` now checks each
// manifest column against `fs.statSync` of the file it just wrote, which
// is the strongest possible witness — but it costs a shadow-cljs compile
// and cannot run in a unit gate. So the arithmetic is pinned here, over
// inputs chosen to DISCRIMINATE:
//
//   - an ASCII control, where the old and new answers agree, so the file
//     cannot pass by being vacuous;
//   - the two characters this corpus actually contains, U+2014 and U+2026,
//     each 1 code unit and 3 bytes;
//   - an ASTRAL-PLANE character, U+1D11E, which is 1 codepoint, TWO code
//     units and FOUR bytes — the only input that separates all three
//     candidate accountings from each other.
//
// Every non-ASCII character is written as a `\u` escape rather than a
// literal. A literal would let an encoding-normalising editor quietly
// ASCII-fy this file and leave every assertion below passing over inputs
// that no longer discriminate — a green gate measuring nothing, which is
// the failure mode of the defect it exists to pin.
//
// The claimed size is compared against BYTES ACTUALLY WRITTEN TO DISK, not
// against a second call to the same function. `Buffer.byteLength` agreeing
// with itself is not evidence about a file.
//
// Wired into implementation/package.json via `test:script-helpers`.

const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

// Requiring the driver must NOT drive it — the `require.main === module`
// guard is part of what is under test.
const DRIVER = path.join(__dirname, 'driver.cjs');
const { utf8Bytes, sha256 } = require('./driver.cjs');
const SRC = fs.readFileSync(DRIVER, 'utf8');

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

// --- the inputs, and what each one is here to separate ---------------------

const EM_DASH = '\u2014';   // U+2014 EM DASH — every corpus row's title carries one
const ELLIPSIS = '\u2026';  // U+2026 HORIZONTAL ELLIPSIS — the `defhost` fallback's "loading…"
const CLEF = '\u{1D11E}'; // U+1D11E MUSICAL SYMBOL G CLEF — astral plane

const CASES = [
  { what: 'ASCII only', s: '<p>plain</p>', units: 12, bytes: 12 },
  { what: 'an em dash', s: `x${EM_DASH}y`, units: 3, bytes: 5 },
  { what: 'an ellipsis', s: `loading${ELLIPSIS}`, units: 8, bytes: 10 },
  { what: 'an astral-plane character', s: `a${CLEF}b`, units: 4, bytes: 6 },
  {
    what: 'the corpus mix — a title, a fallback and a clef',
    s: `<title>Hicasso SSR ${EM_DASH} defhost</title><span>loading${ELLIPSIS}</span>${CLEF}`,
    units: 59,
    bytes: 65,
  },
];

// --- the inputs really do discriminate -------------------------------------

test('the fixtures are not accidentally ASCII — the escapes survived the file', () => {
  assert.strictEqual(EM_DASH.codePointAt(0), 0x2014);
  assert.strictEqual(ELLIPSIS.codePointAt(0), 0x2026);
  assert.strictEqual(CLEF.codePointAt(0), 0x1d11e);
  // The clef is the one input that tells all three accountings apart.
  assert.strictEqual(CLEF.length, 2, 'two UTF-16 code units');
  assert.strictEqual([...CLEF].length, 1, 'one codepoint');
  assert.strictEqual(Buffer.byteLength(CLEF, 'utf8'), 4, 'four UTF-8 bytes');
});

test('every non-ASCII case would give a DIFFERENT answer under the old code', () => {
  const discriminating = CASES.filter((c) => c.units !== c.bytes);
  assert.strictEqual(
    discriminating.length,
    CASES.length - 1,
    'exactly one case — the ASCII control — may be indistinguishable',
  );
  for (const c of discriminating) {
    assert.notStrictEqual(c.s.length, utf8Bytes(c.s), `${c.what}: cannot distinguish the defect`);
  }
});

// --- the arithmetic --------------------------------------------------------

for (const { what, s, units, bytes } of CASES) {
  test(`${what}: .length is ${units} code units, utf8Bytes is ${bytes} bytes`, () => {
    assert.strictEqual(s.length, units, 'the fixture is not the string this row describes');
    assert.strictEqual(utf8Bytes(s), bytes);
  });
}

// --- and the bytes are the ones a file system reports ----------------------

test('utf8Bytes equals the bytes ACTUALLY WRITTEN, for every case', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-114-'));
  try {
    for (const { what, s, units } of CASES) {
      const file = path.join(dir, 'fixture.html');
      // Written exactly as `bake` writes a fixture.
      fs.writeFileSync(file, s, 'utf8');
      const onDisk = fs.statSync(file).size;
      assert.strictEqual(utf8Bytes(s), onDisk, `${what}: the claim disagrees with the file`);
      if (units !== onDisk) {
        assert.notStrictEqual(s.length, onDisk, `${what}: the OLD claim must not match the file`);
      }
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// --- the wiring: `utf8Bytes` is load-bearing, not decorative ---------------

test('the driver exposes the accounting and does not bake itself on require', () => {
  assert.strictEqual(typeof utf8Bytes, 'function');
  assert.match(SRC, /module\.exports = \{ sha256, utf8Bytes, /);
  assert.match(SRC, /require\.main === module/);
});

test('utf8Bytes is Buffer.byteLength with an EXPLICIT utf8 — not a default', () => {
  // `Buffer.byteLength(s)` defaults to utf8 today. Naming the encoding is
  // what makes the claim readable beside `sha256`'s, which names it too.
  assert.match(SRC, /const utf8Bytes = \(s\) => Buffer\.byteLength\(s, 'utf8'\);/);
});

test('NO manifest column and NO " B" claim reads `.length` any more', () => {
  const bake = SRC.slice(SRC.indexOf('function bake('), SRC.indexOf('// serve — the live demo'));
  const serve = SRC.slice(SRC.indexOf('function serve('));
  for (const [where, block] of [['bake', bake], ['serve', serve]]) {
    assert.ok(block.length > 0, `${where} block not found — this pin has gone blind`);
    assert.ok(
      !/\.(document|html|payloadEdn)\.length/.test(block),
      `${where}: a size figure is back on String.prototype.length`,
    );
  }
  // And the three columns are spelled once each, from the measured value.
  // `\s+`, not `\n\s+`: this file is checked out CRLF on Windows and LF on CI.
  assert.match(bake, /documentBytes,\s+bodyBytes,\s+payloadBytes,/);
  assert.match(bake, /\['documentBytes', `\$\{id\}\.html`, r\.first\.document\]/);
  assert.match(bake, /\['bodyBytes', `\$\{id\}\.body\.html`, r\.first\.html\]/);
  assert.match(bake, /\['payloadBytes', `\$\{id\}\.payload\.edn`, r\.first\.payloadEdn\]/);
  assert.match(bake, /return \[field, file, utf8Bytes\(text\)\];/);
  assert.match(bake, /String\(documentBytes\)\.padStart\(7\)\} B/);
  assert.match(serve, /\$\{utf8Bytes\(out\.document\)\} B/);
});

test('the bake CHECKS each column against the file it just wrote, and refuses', () => {
  const bake = SRC.slice(SRC.indexOf('function bake('), SRC.indexOf('// serve — the live demo'));
  assert.match(bake, /const onDisk = fs\.statSync\(file\)\.size;/);
  assert.match(bake, /if \(claimed !== onDisk\)/);
  // A printed refusal that does not refuse is this repo's recurring defect.
  const refusal = bake.slice(bake.indexOf('if (claimed !== onDisk)'));
  assert.match(refusal, /process\.exit\(1\);/);
  assert.match(refusal, /bytes on disk/);
});

// --- the fence: the digest rows were correct and must stay correct ---------

test('sha256 still hashes with an explicit utf8 encoding — untouched by this repair', () => {
  assert.match(SRC, /crypto\.createHash\('sha256'\)\.update\(s, 'utf8'\)\.digest\('hex'\)/);
  // The digest is a function of BYTES, so it always was right; the pin is
  // here so a future "make it consistent" pass cannot take it with them.
  assert.strictEqual(
    sha256(CLEF),
    require('node:crypto').createHash('sha256').update(Buffer.from(CLEF, 'utf8')).digest('hex'),
  );
});

let failed = 0;
for (const [name, fn] of tests) {
  try {
    fn();
  } catch (err) {
    failed += 1;
    console.error(`FAIL  ${name}\n      ${err.message}`);
  }
}

if (failed > 0) {
  console.error(`\nbake_bytes.test.cjs: ${failed}/${tests.length} failed`);
  process.exit(1);
}
console.log(`bake_bytes.test.cjs: ${tests.length} passed`);
