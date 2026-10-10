// Unit tests for `assertClassificationRatchet`, the gate that turns a
// per-tool annotation regression RED. The end-to-end harnesses run its green
// path against every advertised tool's real descriptor; these pin the RED
// paths, the open-world side of the partition among them: a tool that
// reaches the live browser or nREPL must carry `openWorldHint: true`.

const test = require('node:test');
const assert = require('node:assert/strict');

const { assertClassificationRatchet } = require('./_runner.cjs');

// `closed-world` is the exhaustive open-world partition: listed => the
// tool's openWorldHint MUST be false; not listed => it MUST be true.
const FIXTURE = {
  classifications: {
    'read-live': 'read-only',
    'write-live': 'destructive',
    'pin-live': 'neither',
    'read-inline': 'read-only',
  },
  'closed-world': ['read-inline'],
};

// Every tool carries the budget-hint description the ratchet also requires,
// so the annotations alone decide each case.
function tool(name, annotations) {
  return {
    name,
    annotations,
    inputSchema: {
      type: 'object',
      properties: { 'max-tokens': { description: 'per-call cap override' } },
    },
  };
}

function greenTools() {
  return [
    tool('read-live', { readOnlyHint: true, openWorldHint: true }),
    tool('write-live', { destructiveHint: true, openWorldHint: true }),
    tool('pin-live', { openWorldHint: true }),
    tool('read-inline', { readOnlyHint: true, openWorldHint: false }),
  ];
}

test('RED: each single-tool annotation regression throws and names the tool', () => {
  for (const [label, index, annotations, pattern] of [
    ['a live-reaching tool drops openWorldHint', 0, { readOnlyHint: true },
      /read-live is open-world[\s\S]*MUST be true/],
    ['a closed-world tool drops openWorldHint', 3, { readOnlyHint: true },
      /read-inline is pinned closed-world[\s\S]*MUST be false/],
    // write-live keeps openWorldHint: true, so only the posture axis trips.
    ['a destructive tool re-labelled read-only', 1, { readOnlyHint: true, openWorldHint: true },
      /write-live classification regressed[\s\S]*pins `destructive`/],
  ]) {
    const tools = greenTools();
    tools[index].annotations = annotations;
    assert.throws(() => assertClassificationRatchet(tools, FIXTURE), pattern, label);
  }
});

// The per-tool loop only visits live tools, so a `closed-world` row naming a
// removed tool needs its own check.
test('RED: rf2-6i2yi4 finding 4 — a `closed-world` row naming a REMOVED tool is caught, not silently tolerated', () => {
  const staleFixture = {
    classifications: {
      'read-live': 'read-only',
      'write-live': 'destructive',
      'pin-live': 'neither',
    },
    'closed-world': ['read-inline'],
  };
  assert.throws(
    () => assertClassificationRatchet(greenTools().slice(0, 3), staleFixture),
    /closed-world[\s\S]*dangling[\s\S]*"read-inline"/,
  );
});
