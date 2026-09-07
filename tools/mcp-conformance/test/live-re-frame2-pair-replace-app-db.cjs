// live-re-frame2-pair-replace-app-db.cjs
//
// LIVE conformance for `replace-app-db`'s print-is-not-quotation repair
// (rf2-olqo; the sibling of rf2-j2wz, which fixed the same class for
// `dispatch` / `dispatch-dry-run`).
//
// ## What this test guards
//
// `replace-app-db` takes a `db` argument as EDN DATA and injects it into a
// live frame through the Tool-Pair `app-db-reset!` write primitive. The
// injection is a STRING of CLJS source shipped over nREPL, so how the datum
// reaches that string is the whole contract:
//
//   * PRINTED into the form (`pr-str`) -> the runtime READER re-reads it and
//     the evaluator then EVALUATES it. `(inc 41)` becomes `42`; `js/window`
//     resolves to the host object. The caller asked for data and got a
//     computation's result.
//   * QUOTED into the form (`eval-form/rt-quote`) -> `(quote <datum>)`, which
//     evaluates to the datum itself. A list stays a list, a symbol stays a
//     symbol.
//
// ## Why this file exists at all — what the unit tests cannot witness
//
// `tools/re-frame2-pair-mcp/test/re_frame2_pair_mcp/replace_app_db_test.cljs`
// pins the repair by STUBBING `nrepl/cljs-eval-value`, capturing the emitted
// string, and reading it back with `cljs.reader/read-string`. That proves the
// emitted SYNTAX is `(quote ...)`. It cannot prove what evaluation yields,
// because nothing in that suite evaluates: a test that stubs the thing under
// test cannot fail when that thing is wrong. The audit of PR #9380 reopened
// rf2-olqo for exactly this gap — the only live `replace-app-db` call in the
// hermetic suite was the writes-DISABLED refusal probe in
// `live-re-frame2-pair-turn-observation.cjs`, which never reaches the emitter.
//
// This file closes it: a real server booted WITH `--allow-writes`, a real
// nREPL, a real browser runtime, and the injected values read BACK out of the
// committed app-db through `get-path`.
//
// ## What this test drives
//
//   1. `replace-app-db {db}` — the ONE-arity (no `frame`) call path.
//   2. `get-path` read-back of each injected slot.
//   3. `replace-app-db {db frame}` — the TWO-arity (frame-targeted) call path,
//      with the frame id read live off the runtime. Both arities emit through
//      `rt-quote`; both are covered because the repair had to touch both.
//   4. Teardown: the fixture's boot db is restored so inner tests ordered
//      after this one in `scripts/live-test-inventory.cjs` see the state they
//      expect. (The hermetic orchestrator runs every inner test sequentially
//      against ONE booted fixture.)
//
// ## The non-execution control
//
// The payload is chosen so that EVALUATION and QUOTATION give visibly
// different answers, and the wrong one is not an error:
//
//   `(inc 41)`   quoted -> the three-element list `(inc 41)`
//                printed -> `42`
//   `js/window`  quoted -> the symbol `js/window`
//                printed -> the host Window object (`#object[Window ...]`)
//
// A regression to `pr-str` therefore does not throw — it succeeds and silently
// substitutes a computed value for the caller's data. That is precisely why an
// assertion on the READ-BACK value, not on the call's success, is the one that
// bites.
//
// ## Gating
//
// Rostered in `scripts/live-test-inventory.cjs`; run by
// `scripts/run-re-frame2-pair-live-hermetic-suite.cjs` (CI job `mcp-live` in
// `.github/workflows/expensive-tests.yml`). Without
// `$SHADOW_CLJS_NREPL_PORT` it SKIPs, exactly like its siblings — the server
// answers the degraded `:nrepl-port-not-found` envelope and no form is ever
// evaluated, so there is nothing to witness.

'use strict';

const path = require('node:path');
const os = require('node:os');
const { runWithWatchdog, responseText } = require('./_runner.cjs');

const SERVER = path.resolve(__dirname, '..', '..', 're-frame2-pair-mcp', 'out', 'server.js');

// The two injected slots. Namespaced under `rf2-olqo` so they cannot collide
// with the fixture's own `:count` slot or with a sibling test's injection.
const EXPR_KEY = ':rf2-olqo/expr';
const SYM_KEY = ':rf2-olqo/who';

// The fixture's boot db (skills/re-frame2-pair/tests/fixture — `{:count 5}`).
// Restored at teardown so a later inner test reading `:count` is not surprised.
const FIXTURE_BOOT_DB = '{:count 5}';

// `:count` rides along in every injection for the same reason.
const PAYLOAD_DB =
  '{:count 5 ' + EXPR_KEY + ' (inc 41) ' + SYM_KEY + ' js/window}';

async function callOrThrow(client, name, args, what) {
  const resp = await client.callTool({ name, arguments: args });
  if (resp.isError) {
    throw new Error(
      what + ' returned isError — got: ' + responseText(resp).slice(0, 400),
    );
  }
  return resp;
}

// Read one app-db slot back out of the COMMITTED state. `get-path` is the
// narrow read (it returns `{:ok? true :exists? true :path [...] :value v}`),
// so the assertion below can anchor on the `:value` slot rather than on a
// substring that could match anywhere in a whole-db dump.
async function readSlot(client, key, what) {
  const resp = await callOrThrow(
    client,
    'get-path',
    { path: '[' + key + ']' },
    what,
  );
  return responseText(resp);
}

function assertQuotedDatumSurvived(text, where) {
  // POSITIVE: the datum came back as the datum.
  if (!/:value\s+\(inc 41\)/.test(text)) {
    throw new Error(
      where + ': the injected list MUST read back as the LIST `(inc 41)`. ' +
        'A `:value 42` here is the print-is-not-quotation regression rf2-olqo ' +
        'fixed — the runtime evaluated the caller\'s DATA. Got: ' +
        text.slice(0, 400),
    );
  }
  // NEGATIVE (the control): had the db been PRINTED rather than QUOTED into
  // the emitted form, the runtime would have evaluated it and this is what we
  // would see instead. Asserted separately so a partially-matching payload
  // cannot pass on the positive alone.
  if (/:value\s+42\b/.test(text)) {
    throw new Error(
      where + ': `:value 42` means the emitted form EVALUATED `(inc 41)` — ' +
        'the db argument reached the runtime via `pr-str`, not `rt-quote`. ' +
        'Got: ' + text.slice(0, 400),
    );
  }
}

function assertQuotedSymbolSurvived(text, where) {
  if (!/:value\s+js\/window/.test(text)) {
    throw new Error(
      where + ': the injected symbol MUST read back as the SYMBOL ' +
        '`js/window`. An `#object[Window ...]` here means the symbol was ' +
        'RESOLVED rather than quoted. Got: ' + text.slice(0, 400),
    );
  }
  if (/#object\[/.test(text)) {
    throw new Error(
      where + ': the read-back carries a host `#object[...]` — the symbol ' +
        'resolved against the runtime instead of riding through as data. ' +
        'Got: ' + text.slice(0, 400),
    );
  }
}

// Pre-flight SKIP — same posture as the sibling live-* variants.
if (!process.env.SHADOW_CLJS_NREPL_PORT) {
  runWithWatchdog.skip(
    'live-re-frame2-pair-replace-app-db: $SHADOW_CLJS_NREPL_PORT not set.\n' +
      '      This variant requires a live shadow-cljs nREPL + browser\n' +
      '      runtime: without one `replace-app-db` returns the degraded\n' +
      '      :nrepl-port-not-found envelope, no form is ever EVALUATED, and\n' +
      '      the data-versus-evaluation repair (rf2-olqo) has nothing to\n' +
      '      witness. The hermetic orchestrator boots the fixture runtime\n' +
      '      and wires the env so this gate fires on CI.',
  );
}

runWithWatchdog(
  {
    watchdogMs: 30000,
    clientName: 'mcp-conformance-re-frame2-pair-live-replace-app-db',
    transportSpec: {
      command: process.execPath,
      // --allow-writes: `replace-app-db` is default-OFF gated, and the gate
      // fires BEFORE the db argument is read. Without the flag this whole
      // file would only ever re-prove the refusal the turn-observation gate
      // already pins.
      args: [SERVER, '--allow-writes'],
      cwd: os.tmpdir(),
      env: { ...process.env },
    },
  },
  async (client) => {
    console.log('OK   connect -> server attached on nREPL (--allow-writes)');

    // ---------------------------------------------------------------------
    // 1. ONE-arity call path: `replace-app-db {db}`.
    // ---------------------------------------------------------------------
    await callOrThrow(
      client,
      'replace-app-db',
      { db: PAYLOAD_DB },
      'replace-app-db {db} (one-arity, writes enabled)',
    );
    console.log('OK   replace-app-db {db} -> committed (one-arity)');

    assertQuotedDatumSurvived(
      await readSlot(client, EXPR_KEY, 'get-path ' + EXPR_KEY + ' (one-arity)'),
      'one-arity replace-app-db',
    );
    console.log(
      'OK   get-path ' + EXPR_KEY + ' -> `(inc 41)` survives as DATA ' +
        '(not 42) — one-arity',
    );

    assertQuotedSymbolSurvived(
      await readSlot(client, SYM_KEY, 'get-path ' + SYM_KEY + ' (one-arity)'),
      'one-arity replace-app-db',
    );
    console.log(
      'OK   get-path ' + SYM_KEY + ' -> `js/window` survives as a SYMBOL ' +
        '(not resolved) — one-arity',
    );

    // ---------------------------------------------------------------------
    // 2. TWO-arity call path: `replace-app-db {db frame}`.
    //
    // The frame id is read LIVE off the runtime rather than hard-coded, so
    // this arm keeps working if the fixture renames its frame. `eval-cljs` is
    // the same internal-read seam the cofx and redaction gates use.
    // ---------------------------------------------------------------------
    const frameResp = await callOrThrow(
      client,
      'eval-cljs',
      { form: '(re-frame2-pair.runtime/current-frame)' },
      'eval-cljs (re-frame2-pair.runtime/current-frame)',
    );
    const frameText = responseText(frameResp);
    const frameMatch = frameText.match(/:value\s+(:[A-Za-z0-9_.*+!?<>=$%&|/-]+)/);
    if (!frameMatch) {
      throw new Error(
        'could not read the operating frame id off the runtime — the ' +
          'frame-targeted arity cannot be exercised without it. Got: ' +
          frameText.slice(0, 300),
      );
    }
    const frameId = frameMatch[1];
    console.log('OK   current-frame -> ' + frameId);

    // A DIFFERENT expression on the second arm, so a stale read of the
    // one-arity injection cannot pass for the frame-targeted one.
    const frameDb =
      '{:count 5 ' + EXPR_KEY + ' (inc 41) ' + SYM_KEY + ' js/window}';
    await callOrThrow(
      client,
      'replace-app-db',
      { db: frameDb, frame: frameId },
      'replace-app-db {db frame} (two-arity, writes enabled)',
    );
    console.log('OK   replace-app-db {db frame} -> committed (two-arity)');

    assertQuotedDatumSurvived(
      await readSlot(client, EXPR_KEY, 'get-path ' + EXPR_KEY + ' (two-arity)'),
      'two-arity (frame-targeted) replace-app-db',
    );
    console.log(
      'OK   get-path ' + EXPR_KEY + ' -> `(inc 41)` survives as DATA ' +
        '(not 42) — two-arity',
    );

    assertQuotedSymbolSurvived(
      await readSlot(client, SYM_KEY, 'get-path ' + SYM_KEY + ' (two-arity)'),
      'two-arity (frame-targeted) replace-app-db',
    );
    console.log(
      'OK   get-path ' + SYM_KEY + ' -> `js/window` survives as a SYMBOL ' +
        '(not resolved) — two-arity',
    );

    // ---------------------------------------------------------------------
    // 3. Teardown — restore the fixture's boot db.
    // ---------------------------------------------------------------------
    await callOrThrow(
      client,
      'replace-app-db',
      { db: FIXTURE_BOOT_DB },
      'replace-app-db teardown (restore the fixture boot db)',
    );
    const restored = await readSlot(
      client,
      ':count',
      'get-path :count (teardown)',
    );
    if (!/:value\s+5\b/.test(restored)) {
      throw new Error(
        'teardown did NOT restore the fixture boot db — a later inner test ' +
          'reading :count would see this test\'s injection. Got: ' +
          restored.slice(0, 300),
      );
    }
    console.log('OK   teardown -> fixture boot db restored ({:count 5})');

    console.log('\nRE-FRAME2-PAIR-MCP LIVE REPLACE-APP-DB CONFORMANCE GREEN');
  },
);
