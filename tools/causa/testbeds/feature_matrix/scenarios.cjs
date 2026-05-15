'use strict';

const {
  expectTextEquals,
  expectVisible,
  waitForValue,
} = require('../../../../examples/scripts/spec-helpers.cjs');
const {
  clearTraceBus,
  readEpochHistoryAsEdn,
  readTraceEventsAsEdn,
} = require('../../../../testbeds/spec-helpers.cjs');

const PANEL_HANDOFFS = [
  ['event-detail', 'rf-causa-event-detail'],
  ['time-travel', 'rf-causa-time-travel'],
  ['app-db', 'rf-causa-app-db-diff'],
  ['causality', 'rf-causa-causality-graph'],
  ['subs', 'rf-causa-subscriptions'],
  ['fx', 'rf-causa-fx'],
  ['trace', 'rf-causa-trace'],
  ['machines', 'rf-causa-machine-inspector'],
  ['flows', 'rf-causa-flows'],
  ['routes', 'rf-causa-routes'],
  ['performance', 'rf-causa-performance'],
  ['issues', 'rf-causa-issues-ribbon'],
  ['schemas', 'rf-causa-schema-violation-timeline'],
  ['hydration', 'rf-causa-hydration-debugger'],
  ['mcp-server', 'rf-causa-mcp-server'],
  ['copilot', 'rf-causa-copilot-panel'],
];

const STAGED_SURFACES = [
  {
    build: 'examples/counter',
    bundleDir: ['out', 'examples', 'counter'],
    html: ['examples', 'reagent', 'counter', 'index.html'],
    servedPath: 'counter',
  },
  {
    build: 'examples/cart-total',
    bundleDir: ['out', 'examples', 'cart-total'],
    html: ['tools', 'causa', 'testbeds', 'cart_total', 'index.html'],
    servedPath: 'cart-total',
  },
  {
    build: 'examples/counter-perf',
    bundleDir: ['out', 'examples', 'counter-perf'],
    html: ['tools', 'causa', 'testbeds', 'perf_counter', 'index.html'],
    servedPath: 'counter-perf',
  },
  {
    build: 'testbeds/deliberate-throw',
    bundleDir: ['out', 'testbeds', 'deliberate-throw'],
    html: ['testbeds', 'deliberate_throw', 'index.html'],
    servedPath: 'testbeds/deliberate-throw',
  },
  {
    build: 'testbeds/schema-violation',
    bundleDir: ['out', 'testbeds', 'schema-violation'],
    html: ['testbeds', 'schema_violation', 'index.html'],
    servedPath: 'testbeds/schema-violation',
  },
  {
    build: 'testbeds/http-toggle',
    bundleDir: ['out', 'testbeds', 'http-toggle'],
    html: ['testbeds', 'http_toggle', 'index.html'],
    servedPath: 'testbeds/http-toggle',
    extraFiles: [
      {
        src: ['testbeds', 'http_toggle', 'api', 'success.json'],
        dest: ['api', 'success.json'],
      },
    ],
  },
  {
    build: 'testbeds/multi-frame',
    bundleDir: ['out', 'testbeds', 'multi-frame'],
    html: ['testbeds', 'multi_frame', 'index.html'],
    servedPath: 'testbeds/multi-frame',
  },
  {
    build: 'testbeds/deep-machine',
    bundleDir: ['out', 'testbeds', 'deep-machine'],
    html: ['testbeds', 'deep_machine', 'index.html'],
    servedPath: 'testbeds/deep-machine',
  },
  {
    build: 'testbeds/long-flow-w-failure',
    bundleDir: ['out', 'testbeds', 'long-flow-w-failure'],
    html: ['testbeds', 'long_flow_w_failure', 'index.html'],
    servedPath: 'testbeds/long-flow-w-failure',
  },
  {
    build: 'testbeds/drain-depth-trigger',
    bundleDir: ['out', 'testbeds', 'drain-depth-trigger'],
    html: ['testbeds', 'drain_depth_trigger', 'index.html'],
    servedPath: 'testbeds/drain-depth-trigger',
  },
  {
    build: 'testbeds/non-trivial-app-db',
    bundleDir: ['out', 'testbeds', 'non-trivial-app-db'],
    html: ['testbeds', 'non_trivial_app_db', 'index.html'],
    servedPath: 'testbeds/non-trivial-app-db',
  },
  {
    build: 'testbeds/sensitive-dispatcher',
    bundleDir: ['out', 'testbeds', 'sensitive-dispatcher'],
    html: ['testbeds', 'sensitive_dispatcher', 'index.html'],
    servedPath: 'testbeds/sensitive-dispatcher',
  },
  {
    build: 'testbeds/large-dispatcher',
    bundleDir: ['out', 'testbeds', 'large-dispatcher'],
    html: ['testbeds', 'large_dispatcher', 'index.html'],
    servedPath: 'testbeds/large-dispatcher',
  },
  {
    build: 'testbeds/ssr-hydration-mismatch',
    bundleDir: ['out', 'examples', 'testbed-ssr-hydration-mismatch'],
    html: ['testbeds', 'ssr_hydration_mismatch', 'index.html'],
    servedPath: 'testbeds/ssr-hydration-mismatch',
  },
  {
    build: 'testbeds/ssr-multi-frame',
    bundleDir: ['out', 'examples', 'testbed-ssr-multi-frame'],
    html: ['testbeds', 'ssr_multi_frame', 'index.html'],
    servedPath: 'testbeds/ssr-multi-frame',
  },
];

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function openCausa(page) {
  if ((await page.locator('[data-testid="rf-causa-shell"]').count()) === 0) {
    await page.keyboard.press('Control+Shift+C');
  }
  await expectVisible(page.locator('[data-testid="rf-causa-shell"]'), 5000);
}

async function clickSidebar(page, id, canvasTestId) {
  await page.locator(`[data-testid="rf-causa-sidebar-item-${id}"]`).click();
  await expectVisible(page.locator(`[data-testid="${canvasTestId}"]`), 5000);
}

async function clickTestId(page, testId) {
  const clicked = await page.evaluate((id) => {
    const target = Array.from(document.querySelectorAll(`[data-testid="${id}"]`))
      .find((el) => !el.closest('#rf-causa-root'));
    if (!target || typeof target.click !== 'function') return false;
    target.click();
    return true;
  }, testId);
  if (!clicked) {
    await page.locator(`[data-testid="${testId}"]`).click();
  }
}

async function clickHostButtonByLabel(page, label) {
  const clicked = await page.evaluate((targetLabel) => {
    const buttons = Array.from(document.querySelectorAll('button'))
      .filter((el) => !el.closest('#rf-causa-root'));
    const target = buttons.find((el) => (el.textContent || '').trim() === targetLabel);
    if (!target) return false;
    target.click();
    return true;
  }, label);
  if (!clicked) {
    throw new Error(`Could not find host-app button ${JSON.stringify(label)} outside Causa chrome.`);
  }
}

async function clearTrace(page) {
  const cleared = await clearTraceBus(page);
  if (!cleared.ok) {
    throw new Error(`Could not clear Causa trace bus: ${cleared.reason}`);
  }
}

async function readTrace(page) {
  const probe = await readTraceEventsAsEdn(page);
  if (!probe.ok) {
    throw new Error(`Could not read Causa trace bus: ${probe.reason}`);
  }
  return probe.events;
}

async function waitForTraceMatch(page, pattern, label, timeoutMs = 10000) {
  const re = pattern instanceof RegExp ? pattern : new RegExp(pattern);
  return waitForValue(
    async () => readTrace(page),
    (events) => events.some((event) => re.test(event)),
    { timeoutMs, description: label },
  );
}

async function readTraceCounts(page) {
  const text = ((await page.locator('[data-testid="rf-causa-trace-counts"]').textContent()) || '').trim();
  const match = /(\d+)\s*\/\s*(\d+)\s+in view/.exec(text);
  if (!match) {
    throw new Error(`Could not parse trace counts: ${JSON.stringify(text)}`);
  }
  return { rendered: Number(match[1]), total: Number(match[2]), text };
}

async function selectOutcome(page, value) {
  await page.locator('[data-testid="outcome-select"]').selectOption(value);
}

async function expectTraceContainsAll(page, checks) {
  const events = await readTrace(page);
  for (const [label, pattern] of checks) {
    const re = pattern instanceof RegExp ? pattern : new RegExp(pattern);
    if (!events.some((event) => re.test(event))) {
      throw new Error(`Trace did not contain ${label}; patterns checked against ${events.length} events.`);
    }
  }
}

async function expectNumericTextAtLeast(locator, min, timeoutMs = 5000) {
  await waitForValue(
    async () => Number(((await locator.textContent()) || '').trim()),
    (value) => Number.isFinite(value) && value >= min,
    { timeoutMs, description: `numeric text >= ${min}` },
  );
}

async function runShellFeatureSweep(page) {
  await expectTextEquals(page.locator('span').first(), '5', 10000);
  await openCausa(page);

  for (const [id, canvas] of PANEL_HANDOFFS) {
    await clickSidebar(page, id, canvas);
  }

  await clickHostButtonByLabel(page, '+');
  await clickHostButtonByLabel(page, '+');
  await clickHostButtonByLabel(page, '-');

  await clickSidebar(page, 'event-detail', 'rf-causa-event-detail');
  await waitForValue(
    () => page.locator('[data-testid^="rf-causa-cascade-row-"]').count(),
    (count) => count > 0,
    { timeoutMs: 5000, description: 'event-detail cascade rows' },
  );

  await clickSidebar(page, 'time-travel', 'rf-causa-time-travel');
  const slider = page.locator('[data-testid="rf-causa-time-travel-slider"]');
  await expectVisible(slider, 5000);
  const sliderMax = Number(await slider.getAttribute('max'));
  if (!Number.isFinite(sliderMax) || sliderMax < 2) {
    throw new Error(`Expected time-travel slider max >= 2, got ${sliderMax}.`);
  }

  await clickSidebar(page, 'trace', 'rf-causa-trace');
  const traceCounts = await readTraceCounts(page);
  if (traceCounts.total < 1) {
    throw new Error(`Expected non-empty trace feed, got ${traceCounts.text}.`);
  }

  await clickSidebar(page, 'routes', 'rf-causa-routes');
  await expectVisible(page.locator('[data-testid="rf-causa-routes-empty"]'), 5000);
  await clickSidebar(page, 'schemas', 'rf-causa-schema-violation-timeline');
  await expectVisible(page.locator('[data-testid="rf-causa-schema-timeline-empty-no-schemas"]'), 5000);
  await clickSidebar(page, 'mcp-server', 'rf-causa-mcp-server');
  await expectVisible(page.locator('[data-testid="rf-causa-mcp-empty-no-activity"]'), 5000);
}

async function runExceptionSchemaHttp(page) {
  await openCausa(page);
  await clearTrace(page);

  await clickTestId(page, 'throw-handler');
  await clickTestId(page, 'throw-fx');
  await clickTestId(page, 'throw-flow');
  await clickTestId(page, 'throw-machine');
  await waitForTraceMatch(page, /deliberate-throw \/ machine action|:rf\.error\/machine-action-exception/, 'machine action exception trace');
  await expectTraceContainsAll(page, [
    ['handler exception', /handler-exception|deliberate-throw \/ handler/],
    ['flow exception', /flow-eval-exception|deliberate-throw \/ flow/],
    ['machine exception', /machine-action-exception|deliberate-throw \/ machine action/],
  ]);

  await clickSidebar(page, 'issues', 'rf-causa-issues-ribbon');
  await expectVisible(page.locator('[data-testid="rf-causa-issues-feed"]'), 5000);
  await clickSidebar(page, 'trace', 'rf-causa-trace');
  await expectVisible(page.locator('[data-testid="rf-causa-trace-feed"]'), 5000);
}

async function runSchemaViolation(page) {
  for (const id of ['violate-app-db', 'violate-event', 'violate-cofx', 'violate-fx-args']) {
    await clickTestId(page, id);
  }
  await expectTextEquals(page.locator('[data-testid="auth-token"]'), '42', 5000);
  await expectNumericTextAtLeast(page.locator('[data-testid="event-count"]'), 1, 5000);
  await expectNumericTextAtLeast(page.locator('[data-testid="cofx-count"]'), 1, 5000);
  await expectNumericTextAtLeast(page.locator('[data-testid="fx-count"]'), 1, 5000);
  await openCausa(page);
  await clickSidebar(page, 'schemas', 'rf-causa-schema-violation-timeline');
  await expectVisible(page.locator('[data-testid="rf-causa-schema-violation-timeline"]'), 5000);
}

async function runHttpToggle(page) {
  await openCausa(page);
  await clearTrace(page);

  await clickTestId(page, 'go');
  await expectVisible(page.locator('[data-testid="reply-kind"]'), 5000);

  const outcomes = [
    ':rf.http/http-4xx',
    ':rf.http/http-5xx',
    ':rf.http/timeout',
    ':rf.http/transport',
    ':rf.http/decode-failure',
    ':rf.http/cors',
  ];
  for (const outcome of outcomes) {
    await selectOutcome(page, outcome);
    await clickTestId(page, 'go');
    await waitForTraceMatch(page, new RegExp(outcome.replace('.', '\\.')), `${outcome} trace`);
  }

  await clickSidebar(page, 'fx', 'rf-causa-fx');
  await expectVisible(page.locator('[data-testid="rf-causa-fx-list"]'), 5000);
  await clickSidebar(page, 'issues', 'rf-causa-issues-ribbon');
  await expectVisible(page.locator('[data-testid="rf-causa-issues-feed"]'), 5000);
}

async function runMultiFrame(page) {
  await clickTestId(page, 'inc-A');
  await clickTestId(page, 'inc-B');
  await expectTextEquals(page.locator('[data-testid="n-A"]'), '1', 5000);
  await expectTextEquals(page.locator('[data-testid="n-B"]'), '1', 5000);
  await clickTestId(page, 'cross-bump');
  await expectNumericTextAtLeast(page.locator('[data-testid="n-A"]'), 2, 5000);
  await openCausa(page);
  await clickSidebar(page, 'time-travel', 'rf-causa-time-travel');
  await expectVisible(page.locator('[data-testid="rf-causa-time-travel"]'), 5000);
}

async function runDeepMachine(page) {
  await clickTestId(page, 'work-go');
  await expectTextEquals(page.locator('[data-testid="tick-count"]'), '1', 5000);
  await waitForValue(
    async () => ((await page.locator('[data-testid="work-state"]').textContent()) || '').trim(),
    (state) => state.length > 0 && state !== ':idle',
    { timeoutMs: 5000, description: 'deep machine transition off :idle' },
  );
  await openCausa(page);
  await waitForTraceMatch(page, /:rf\.machine\/transition|:rf\.machine\/spawned|:helper\/tick/, 'machine transition trace');
  await clickSidebar(page, 'machines', 'rf-causa-machine-inspector');
  await expectVisible(page.locator('[data-testid="rf-causa-machine-inspector"]'), 5000);
}

async function runLongFlow(page) {
  await openCausa(page);
  await clearTrace(page);
  await page.locator('[data-testid="fail-at"]').fill('3');
  await page.locator('[data-testid="total-ticks"]').fill('6');
  await clickTestId(page, 'start');
  await expectTextEquals(page.locator('[data-testid="status"]'), 'done', 10000);
  await waitForTraceMatch(page, /rf\.flow\/failed|flow-eval-exception|long-flow-w-failure \/ :flow-b/, 'flow failure trace');
  await clickSidebar(page, 'flows', 'rf-causa-flows');
  await expectVisible(page.locator('[data-testid="rf-causa-flows"]'), 5000);
}

async function runDrainDepth(page) {
  await openCausa(page);
  await clearTrace(page);
  await page.locator('[data-testid="drain-depth"]').fill('5');
  await clickTestId(page, 'start');
  await expectTextEquals(page.locator('[data-testid="depth-reached"]'), '0', 5000);
  const history = await waitForValue(
    () => readEpochHistoryAsEdn(page),
    (probe) => probe.ok && probe.records.some((record) => record.includes(':halted-depth')),
    { timeoutMs: 10000, description: ':halted-depth epoch record' },
  );
  await waitForTraceMatch(page, /drain-depth-exceeded|halted-depth|:rf\.error\/drain-depth-exceeded/, 'drain-depth trace');
  await clickSidebar(page, 'performance', 'rf-causa-performance');
  await expectVisible(page.locator('[data-testid="rf-causa-performance"]'), 5000);
  return { haltedEpochs: history.records.filter((record) => record.includes(':halted-depth')).length };
}

async function runAppDbPrivacyLarge(page) {
  await openCausa(page);
  await clearTrace(page);

  for (const id of [
    'toggle-theme',
    'toggle-notifications',
    'add-cart-item',
    'bump-first-item-qty',
    'register-new-sku',
    'revoke-write-and-collapse',
  ]) {
    await clickTestId(page, id);
  }
  await clickSidebar(page, 'app-db', 'rf-causa-app-db-diff');
  await expectVisible(page.locator('[data-testid="rf-causa-app-db-diff"]'), 5000);
}

async function runSensitiveDispatcher(page) {
  await openCausa(page);
  await clearTrace(page);
  await clickTestId(page, 'sign-in-plain');
  await clickTestId(page, 'sign-in-redacted');
  await expectTextEquals(page.locator('[data-testid="plain-count"]'), '1', 5000);
  await expectTextEquals(page.locator('[data-testid="redacted-count"]'), '1', 5000);
  await expectVisible(page.locator('[data-testid="rf-causa-redacted-indicator"]'), 5000);
  const bodyText = await page.locator('body').textContent();
  if ((bodyText || '').includes('shhh-this-is-secret')) {
    throw new Error('Raw sensitive password leaked into DOM.');
  }
}

async function runLargeDispatcher(page) {
  await openCausa(page);
  await clearTrace(page);
  for (const id of ['write-auto', 'write-declared', 'write-fx-declared', 'write-schema']) {
    await clickTestId(page, id);
  }
  await expectTextEquals(page.locator('[data-testid="auto-count"]'), '1', 5000);
  await expectTextEquals(page.locator('[data-testid="declared-count"]'), '1', 5000);
  await expectTextEquals(page.locator('[data-testid="fx-count"]'), '1', 5000);
  await expectTextEquals(page.locator('[data-testid="schema-count"]'), '1', 5000);
  await expectVisible(page.locator('[data-testid="elision-decls"]'), 5000);
}

async function runHydration(page) {
  await openCausa(page);
  await clickSidebar(page, 'hydration', 'rf-causa-hydration-debugger');
  await expectVisible(page.locator('[data-testid="rf-causa-hydration-mismatch-list"]'), 5000);
  await expectVisible(page.locator('[data-testid="rf-causa-hydration-mismatch-detail"]'), 5000);
}

async function runTwentyEventLoad(page, state) {
  await expectTextEquals(page.locator('span').first(), '5', 10000);
  await openCausa(page);
  await clickSidebar(page, 'trace', 'rf-causa-trace');
  await clearTrace(page);
  const before = await readTraceCounts(page).catch(() => ({ rendered: 0, total: 0, text: 'empty' }));
  const start = Date.now();
  for (let i = 0; i < 20; i += 1) {
    await clickHostButtonByLabel(page, i % 2 === 0 ? '+' : '-');
  }
  const after = await waitForValue(
    () => readTraceCounts(page),
    (counts) => counts.total > before.total,
    { timeoutMs: 10000, description: 'trace count growth after 20 dispatches' },
  );
  const elapsedMs = Date.now() - start;
  await clickSidebar(page, 'event-detail', 'rf-causa-event-detail');
  await waitForValue(
    () => page.locator('[data-testid^="rf-causa-cascade-row-"]').count(),
    (count) => count > 0,
    { timeoutMs: 5000, description: 'event-detail cascade rows after load' },
  );
  await clickSidebar(page, 'causality', 'rf-causa-causality-graph');
  await expectVisible(page.locator('[data-testid="rf-causa-causality-graph"]'), 5000);
  state.loadStats = {
    eventCountBefore: before.total,
    eventCountAfter: after.total,
    visibleRows: after.rendered,
    traceBufferDepth: after.total,
    elapsedMs,
  };
}

const SCENARIOS = [
  {
    name: 'feature matrix shell and panel handoff',
    url: '/counter/',
    panels: PANEL_HANDOFFS.map(([id]) => id),
    coveredRows: [
      'Event Detail',
      'Causality Strip and Event Log',
      'Time Travel',
      'App-DB Diff',
      'Causality Graph',
      'Trace',
      'Subscriptions',
      'Machines',
      'Routes',
      'Schemas / Schema Timeline',
      'Hydration',
      'Performance',
      'Issues Ribbon',
      'Flows',
      'Effects',
      'MCP Server',
      'AI Co-pilot',
      'Shell, Keybinding, Config, Preload, Settings, and Production Elision',
    ],
    run: runShellFeatureSweep,
  },
  {
    name: 'deterministic exceptions and issue/trace surfacing',
    url: '/testbeds/deliberate-throw/',
    panels: ['issues', 'trace'],
    coveredRows: ['Event Detail', 'Trace', 'Issues Ribbon', 'Effects', 'Flows', 'Machines'],
    run: runExceptionSchemaHttp,
  },
  {
    name: 'schema violation timeline',
    url: '/testbeds/schema-violation/',
    panels: ['schemas', 'issues'],
    coveredRows: ['Schemas / Schema Timeline', 'Issues Ribbon'],
    run: runSchemaViolation,
  },
  {
    name: 'managed http and effects rows',
    url: '/testbeds/http-toggle/',
    panels: ['fx', 'issues', 'trace'],
    coveredRows: ['Effects', 'Issues Ribbon', 'Trace', 'Performance'],
    run: runHttpToggle,
  },
  {
    name: 'multi-frame isolation substrate',
    url: '/testbeds/multi-frame/',
    panels: ['time-travel', 'trace'],
    coveredRows: ['Causality Graph', 'Causality Strip and Event Log', 'Time Travel', 'Trace'],
    run: runMultiFrame,
  },
  {
    name: 'deep machine inspector substrate',
    url: '/testbeds/deep-machine/',
    panels: ['machines'],
    coveredRows: ['Machines', 'Trace'],
    run: runDeepMachine,
  },
  {
    name: 'long flow failure substrate',
    url: '/testbeds/long-flow-w-failure/',
    panels: ['flows', 'issues'],
    coveredRows: ['Flows', 'Issues Ribbon', 'Performance'],
    run: runLongFlow,
  },
  {
    name: 'drain-depth load failure substrate',
    url: '/testbeds/drain-depth-trigger/',
    panels: ['performance', 'trace', 'time-travel'],
    coveredRows: ['Performance', 'Trace', 'Time Travel'],
    run: runDrainDepth,
  },
  {
    name: 'non-trivial app-db diff substrate',
    url: '/testbeds/non-trivial-app-db/',
    panels: ['app-db', 'time-travel'],
    coveredRows: ['App-DB Diff', 'Time Travel'],
    run: runAppDbPrivacyLarge,
  },
  {
    name: 'sensitive redaction substrate',
    url: '/testbeds/sensitive-dispatcher/',
    panels: ['trace'],
    coveredRows: ['Redaction, Sensitive, and Large Values', 'Trace'],
    run: runSensitiveDispatcher,
  },
  {
    name: 'large value elision substrate',
    url: '/testbeds/large-dispatcher/',
    panels: ['trace'],
    coveredRows: ['Redaction, Sensitive, and Large Values', 'App-DB Diff'],
    run: runLargeDispatcher,
  },
  {
    name: 'hydration mismatch debugger',
    url: '/testbeds/ssr-hydration-mismatch/',
    panels: ['hydration'],
    coveredRows: ['Hydration', 'Issues Ribbon'],
    run: runHydration,
  },
  {
    name: '20-event feature/load re-check',
    url: '/counter/',
    panels: ['trace', 'event-detail', 'causality'],
    load: true,
    coveredRows: [
      'Event Detail',
      'Causality Strip and Event Log',
      'Causality Graph',
      'Trace',
      'Time Travel',
      'Performance',
      'Shell, Keybinding, Config, Preload, Settings, and Production Elision',
    ],
    run: runTwentyEventLoad,
  },
];

module.exports = {
  PANEL_HANDOFFS,
  SCENARIOS,
  STAGED_SURFACES,
};
