// Unit test for the per-tool annotation CLASSIFICATION ratchet.
//
// `assertClassificationRatchet` is itself a conformance gate — it is the
// teeth that turn a per-tool annotation regression (a flipped read-only /
// destructive hint, an emptied budget-hint, or — the gap this file pins —
// a live-reaching tool that drops `openWorldHint:true`) RED. A gate with
// no test is a gate that can silently lose its teeth, so this pins its
// contract directly, in-process, with no child / no MCP server (it is a
// pure data assertion). Uses Node's built-in `node:test` — same
// zero-dependency posture as the sibling runner tests
// (call-coverage-ratchet.test.cjs).
//
// The end-to-end harnesses (`end-to-end-story.cjs` /
// `end-to-end-re-frame2-pair.cjs`) exercise the GREEN path live (every
// advertised tool's real wire descriptor). This test pins the RED paths
// the green runs never hit — in particular the open-world side of the
// partition: a read-only or destructive tool that reaches the live
// browser / nREPL must carry `openWorldHint:true`. Dropping (or falsing)
// that hint mislabels a live-reaching tool as contained, and the ratchet
// must turn that RED.

const test = require('node:test');
const assert = require('node:assert/strict');

const { assertClassificationRatchet } = require('./_runner.cjs');

// A minimal, server-agnostic fixture mirroring the real fixture shape:
// `classifications` (one row per tool) + `closed-world` (the exhaustive
// open-world partition — listed ⇒ openWorldHint MUST be false; not
// listed ⇒ openWorldHint MUST be true).
const FIXTURE = {
  classifications: {
    'read-live': 'read-only', // open-world (reaches the runtime)
    'write-live': 'destructive', // open-world (reaches the runtime)
    'pin-live': 'neither', // open-world (reaches the runtime)
    'read-inline': 'read-only', // closed-world (inline / no reach)
  },
  'closed-world': ['read-inline'],
};

// A non-empty `max-tokens` budget-hint description is required on every
// tool by the same ratchet (assertion 3); supply it so the openWorld /
// posture assertions are what actually decides each case.
function mt() {
  return {
    inputSchema: {
      type: 'object',
      properties: {
        'max-tokens': { description: 'per-call cap override' },
      },
    },
  };
}

function tool(name, annotations) {
  return { name, annotations, ...mt() };
}

// A fully-legitimate live tool-set: open-world tools carry
// openWorldHint:true, the closed-world tool carries openWorldHint:false,
// and every read-only/destructive/neither posture matches.
function greenTools() {
  return [
    tool('read-live', { readOnlyHint: true, openWorldHint: true }),
    tool('write-live', { destructiveHint: true, openWorldHint: true }),
    tool('pin-live', { openWorldHint: true }),
    tool('read-inline', { readOnlyHint: true, openWorldHint: false }),
  ];
}

test('GREEN: the legitimate open/closed partition ⇒ no throw', () => {
  assert.doesNotThrow(() =>
    assertClassificationRatchet(greenTools(), FIXTURE),
  );
});

// --- the open-world complement: live-reaching tools MUST flag openWorldHint ---

test('RED: a read-only live tool that DROPS openWorldHint ⇒ throws + names it', () => {
  const tools = greenTools();
  // read-live reaches the runtime but the descriptor forgot openWorldHint.
  tools[0].annotations = { readOnlyHint: true };
  assert.throws(
    () => assertClassificationRatchet(tools, FIXTURE),
    /read-live is open-world[\s\S]*MUST be true[\s\S]*rf2-ppk6hy/,
  );
});

test('RED: a read-only live tool with openWorldHint:false ⇒ throws (mislabelled contained)', () => {
  const tools = greenTools();
  tools[0].annotations = { readOnlyHint: true, openWorldHint: false };
  assert.throws(
    () => assertClassificationRatchet(tools, FIXTURE),
    /read-live is open-world[\s\S]*MUST be true/,
  );
});

test('RED: a DESTRUCTIVE live tool that drops openWorldHint ⇒ throws (the trust-boundary case)', () => {
  const tools = greenTools();
  // write-live is destructive AND reaches the runtime; dropping the
  // open-world hint on a live-reaching destructive tool must trip the gate.
  tools[1].annotations = { destructiveHint: true };
  assert.throws(
    () => assertClassificationRatchet(tools, FIXTURE),
    /write-live is open-world[\s\S]*MUST be true/,
  );
});

test('RED: a `neither` live tool that drops openWorldHint ⇒ throws', () => {
  const tools = greenTools();
  tools[2].annotations = {}; // pin-live: no hints at all
  assert.throws(
    () => assertClassificationRatchet(tools, FIXTURE),
    /pin-live is open-world[\s\S]*MUST be true/,
  );
});

// --- the closed-world (false) side holds: inline tools MUST NOT claim reach ---

test('RED: a closed-world tool that flips openWorldHint:true ⇒ throws', () => {
  const tools = greenTools();
  // read-inline ships inline content; it must NOT claim open-world reach.
  tools[3].annotations = { readOnlyHint: true, openWorldHint: true };
  assert.throws(
    () => assertClassificationRatchet(tools, FIXTURE),
    /read-inline is pinned closed-world[\s\S]*MUST be false/,
  );
});

test('RED: a closed-world tool that drops openWorldHint ⇒ throws', () => {
  const tools = greenTools();
  tools[3].annotations = { readOnlyHint: true }; // missing openWorldHint
  assert.throws(
    () => assertClassificationRatchet(tools, FIXTURE),
    /read-inline is pinned closed-world[\s\S]*MUST be false/,
  );
});

// --- the read-only / destructive posture axis ---

test('RED: a destructive tool re-labelled read-only ⇒ throws', () => {
  const tools = greenTools();
  // write-live keeps openWorldHint:true (so it clears the open-world side)
  // but is mislabelled read-only — the posture axis must still trip.
  tools[1].annotations = { readOnlyHint: true, openWorldHint: true };
  assert.throws(
    () => assertClassificationRatchet(tools, FIXTURE),
    /write-live classification regressed[\s\S]*pins `destructive`/,
  );
});
