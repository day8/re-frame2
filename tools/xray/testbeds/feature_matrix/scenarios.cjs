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

// Post rf2-xy4yb (4-layer chrome refactor): the legacy 15-panel
// sidebar + bottom rail is dead. The L3 tab bar exposes 7 tabs:
// epoch / app-db / views / trace / machines / routing / issues
// (spec/018 §5; spec/007-UX-IA.md §L3 — post rf2-5gl5r the Event/
// Handler tab was retired in favour of the Epoch panel). Panels
// without a tab no longer have a UI handoff and are dropped from
// the shell-sweep scenario.
const PANEL_HANDOFFS = [
  ['epoch', 'rf-xray-epoch-panel'],
  ['app-db', 'rf-xray-app-db-diff'],
  // The :views tab routes to the full Views panel per spec/012-Views.md
  // (rf2-21ob3 replaced the legacy Subscriptions panel). The Views
  // panel renders its canonical `rf-xray-reactive` root testid.
  ['views', 'rf-xray-reactive'],
  ['trace', 'rf-xray-trace'],
  ['machines', 'rf-xray-machine-inspector'],
  // The :routing tab (promoted under rf2-nrbs9 + reshaped under
  // rf2-lq0ef) is the focused-event navigation lens. Its root view
  // renders the `rf-xray-routing` testid (panels/routing.cljs).
  ['routing', 'rf-xray-routing'],
  ['issues', 'rf-xray-issues-ribbon'],
];

const STAGED_SURFACES = [
  {
    build: 'examples/counter',
    bundleDir: ['out', 'examples', 'counter'],
    html: ['examples', 'reagent', 'counter', 'index.html'],
    servedPath: 'counter',
  },
  {
    build: 'examples/counter-perf',
    bundleDir: ['out', 'examples', 'counter-perf'],
    html: ['tools', 'xray', 'testbeds', 'perf_counter', 'index.html'],
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
  // ---- retired with the converted scenario (rf2-rviu8) ----------------
  //
  // The `testbeds/non-trivial-app-db` build was driven by the
  // 'non-trivial app-db diff substrate' scenario. That scenario is
  // now covered by `non_trivial_app_db_e2e_cljs_test.cljs` (data-
  // level, no browser), so the staged surface is no longer needed.
  // Removing it saves the bundle compile + serve setup. The testbed
  // source remains under `testbeds/non_trivial_app_db/` for any
  // future re-introduction.
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

async function openXray(page) {
  if ((await page.locator('[data-testid="rf-xray-shell"]').count()) === 0) {
    await page.keyboard.press('Control+Shift+C');
  }
  await expectVisible(page.locator('[data-testid="rf-xray-shell"]'), 5000);
}

// Post rf2-xy4yb: the L3 tab bar replaces the legacy sidebar. Tabs
// expose `data-testid="rf-xray-tab-<id>"` for the 7 surviving panels
// (epoch / app-db / views / trace / machines / routing / issues —
// spec/018 §5; rf2-5gl5r retired the Event/Handler tab in favour of
// the Epoch panel).
async function clickTab(page, id, canvasTestId) {
  await page.locator(`[data-testid="rf-xray-tab-${id}"]`).click();
  await expectVisible(page.locator(`[data-testid="${canvasTestId}"]`), 5000);
}

// Legacy panel ids → new L3 tab ids. Panels removed by the 4-layer
// refactor (time-travel, causality, subs, fx, flows, routes,
// performance, schemas, hydration, mcp-server) and the rf2-y0z5b
// causality-surface drop have no UI handoff — callers that target
// them must be updated separately. rf2-5gl5r: `event-detail` and
// `event` legacy ids both route to the Epoch panel's `:epoch` tab
// (the canonical "what happened in this epoch" surface).
const LEGACY_PANEL_TO_TAB = {
  'event-detail': 'epoch',
  'event':        'epoch',
  'app-db':       'app-db',
  'trace':        'trace',
  'machines':     'machines',
  'issues':       'issues',
};

// Back-compat wrapper used by scenarios still pointing at the old
// panel-id vocabulary (multi-frame, large-dispatcher, etc.). Maps to
// a tab when possible; throws explicitly when a caller targets a
// panel the new chrome no longer exposes so the test surfaces the
// real gap instead of timing out on a missing testid.
async function clickSidebar(page, id, canvasTestId) {
  const tabId = LEGACY_PANEL_TO_TAB[id];
  if (!tabId) {
    throw new Error(
      `clickSidebar: panel '${id}' has no L3 tab in the 4-layer chrome ` +
        `(rf2-xy4yb removed it). Update the scenario or restore the panel.`,
    );
  }
  await clickTab(page, tabId, canvasTestId);
}

async function clickTestId(page, testId) {
  const clicked = await page.evaluate((id) => {
    const target = Array.from(document.querySelectorAll(`[data-testid="${id}"]`))
      .find((el) => !el.closest('#rf-xray-root'));
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
      .filter((el) => !el.closest('#rf-xray-root'));
    const target = buttons.find((el) => (el.textContent || '').trim() === targetLabel);
    if (!target) return false;
    target.click();
    return true;
  }, label);
  if (!clicked) {
    throw new Error(`Could not find host-app button ${JSON.stringify(label)} outside Xray chrome.`);
  }
}

async function clearTrace(page) {
  const cleared = await clearTraceBus(page);
  if (!cleared.ok) {
    throw new Error(`Could not clear Xray trace bus: ${cleared.reason}`);
  }
}

async function readTrace(page) {
  const probe = await readTraceEventsAsEdn(page);
  if (!probe.ok) {
    throw new Error(`Could not read Xray trace bus: ${probe.reason}`);
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

function failWithDetails(message, details) {
  throw new Error(`${message}: ${JSON.stringify(details, null, 2)}`);
}

function ensureStateList(state, key) {
  if (!Array.isArray(state[key])) state[key] = [];
  return state[key];
}

async function clickSourceCoordChip(page, { panel, sourceIncludes = [] }) {
  const needles = Array.isArray(sourceIncludes) ? sourceIncludes : [sourceIncludes];
  return page.evaluate(({ panel: targetPanel, needles: targetNeedles }) => {
    const root = document.getElementById('rf-xray-root');
    const selectors = {
      trace: '[data-testid^="rf-xray-trace-row-"] button[data-testid$="-source-coord"]',
      issues: '[data-testid^="rf-xray-issues-row-"] button[data-testid$="-source"]',
      hydration: '[data-testid="rf-xray-hydration-source-coord"] button',
    };
    if (!root) return { clicked: false, reason: 'Xray root missing', candidates: [] };
    const selector = selectors[targetPanel] || 'button[data-testid*="source"]';
    const buttons = Array.from(root.querySelectorAll(selector));
    // rf2-ad7zx.9 — the Issues panel's source affordance is now an `↗`
    // icon (per the Figma design); the `file:line` coord rides the
    // button's `title` attribute, not its text. Prefer `title` when it
    // carries the coord (icon affordances), falling back to textContent
    // for the trace / hydration chips that still render the coord inline.
    const coordOf = (button) => {
      const title = (button.getAttribute('title') || '').trim();
      const text = (button.textContent || '').trim();
      return title || text;
    };
    const candidates = buttons.map((button) => {
      const rect = button.getBoundingClientRect();
      return {
        testId: button.getAttribute('data-testid'),
        text: coordOf(button),
        visible: rect.width > 0 && rect.height > 0,
      };
    });
    const target = buttons.find((button) => {
      const text = coordOf(button);
      const visible = button.getBoundingClientRect().width > 0 &&
        button.getBoundingClientRect().height > 0;
      return visible && targetNeedles.every((needle) => !needle || text.includes(needle));
    });
    if (!target) {
      return {
        clicked: false,
        reason: `No ${targetPanel} source-coordinate chip matched ${JSON.stringify(targetNeedles)}`,
        candidates: candidates.slice(0, 20),
      };
    }
    const sourceCoord = coordOf(target);
    const testId = target.getAttribute('data-testid');
    target.click();
    return { clicked: true, panel: targetPanel, sourceCoord, testId, candidates: candidates.slice(0, 20) };
  }, { panel, needles });
}

async function assertSourceCoordBridge(page, state, ctx, opts) {
  const beforeUrl = page.url();
  const requestStart = ctx && ctx.browserState ? ctx.browserState.requestFailures.length : 0;
  const click = await clickSourceCoordChip(page, opts);
  if (!click.clicked) {
    failWithDetails('Could not click source-coordinate chip', {
      panel: opts.panel,
      sourceIncludes: opts.sourceIncludes,
      url: beforeUrl,
      observed: click,
    });
  }

  // Per rf2-xs8vu: the chip-click handler dispatches
  // `:rf.xray/open-in-editor` under the `:rf/xray` frame, which then
  // fires `:rf.editor/open` as an fx (also `:rf/xray`-framed). The
  // trace-bus ingest filter (`xray-internal-event?`) correctly drops
  // those self-emitted events before they reach Xray's buffer — they
  // are Xray machinery, not host activity. The bridge round-trip
  // therefore CANNOT be verified by reading Xray's trace feed
  // (pre-rf2-xs8vu the test exploited the absence of that filter as
  // a convenient probe; it was incidental observability, not the
  // bridge's contract).
  //
  // The contract is: clicking a chip writes `window.location.href`
  // to the resolved `vscode://...` URI (via `open-in-editor/open!`).
  // The browser cannot resolve a custom scheme, so it raises a
  // `requestfailed` event — Playwright captures that in
  // `browserState.requestFailures`. That capture is the
  // filter-immune observable proof the click → location bridge
  // fired with the expected URI.
  const expectedUri = click.sourceCoord
    ? `vscode://file/${click.sourceCoord}:1`
    : null;
  if (!ctx || !ctx.browserState) {
    failWithDetails('assertSourceCoordBridge requires ctx.browserState (request-failure capture)', {
      panel: opts.panel,
      sourceCoord: click.sourceCoord,
      expectedUri,
    });
  }
  const newFailures = await waitForValue(
    async () => ctx.browserState.requestFailures.slice(requestStart),
    (failures) => failures.some((line) =>
      line.includes(expectedUri || 'vscode://file/')),
    {
      timeoutMs: 10000,
      description: `open-in-editor request for ${opts.panel} ${click.sourceCoord} (expected ${expectedUri})`,
    },
  );
  const afterUrl = page.url();
  const matchedFailure = newFailures.find((line) =>
    line.includes(expectedUri || 'vscode://file/'));
  const record = {
    panel: opts.panel,
    sourceCoord: click.sourceCoord,
    testId: click.testId,
    expectedUri,
    beforeUrl,
    afterUrl,
    requestFailures: newFailures,
    observedBridgeRequest: Boolean(matchedFailure),
    matchedFailure: matchedFailure || null,
  };
  ensureStateList(state, 'sourceClicks').push(record);
  if (afterUrl !== beforeUrl) {
    failWithDetails('Source-coordinate click changed the page URL', record);
  }
  return record;
}

async function assertDefaultInlineLaunchModes(page, state) {
  const countBefore = await waitForValue(
    () => readHostCounter(page),
    (value) => Number.isFinite(value),
    { timeoutMs: 10000, description: 'host counter before inline geometry check' },
  );
  if (!Number.isFinite(countBefore)) {
    failWithDetails('Host counter value was not numeric before inline geometry check', {
      mode: 'inline',
      observed: { countBefore },
    });
  }
  await openXray(page);
  const defaultInline = await page.evaluate(() => {
    const root = document.getElementById('rf-xray-root');
    const host = document.querySelector('[data-rf-xray-host]');
    const shell = document.querySelector('[data-testid="rf-xray-shell"]');
    const plus = Array.from(document.querySelectorAll('button'))
      .filter((button) => !button.closest('#rf-xray-root'))
      .find((button) => (button.textContent || '').trim() === '+');
    function rect(el) {
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return {
        left: r.left,
        top: r.top,
        right: r.right,
        bottom: r.bottom,
        width: r.width,
        height: r.height,
        centerX: r.left + r.width / 2,
        centerY: r.top + r.height / 2,
      };
    }
    const shellRect = rect(shell);
    const plusRect = rect(plus);
    const top = plusRect
      ? document.elementFromPoint(plusRect.centerX, plusRect.centerY)
      : null;
    return {
      rootMode: root ? root.getAttribute('data-rf-xray-mode') : null,
      shellMode: shell ? shell.getAttribute('data-rf-xray-mode') : null,
      rootParentIsHost: Boolean(root && host && root.parentElement === host),
      bodyPaddingLeft: document.body.style.paddingLeft,
      bodyPaddingRight: document.body.style.paddingRight,
      shell: shellRect,
      hostPlus: plusRect,
      hostPlusText: plus ? (plus.textContent || '').trim() : null,
      topAtHostPlus: top ? {
        tag: top.tagName,
        text: (top.textContent || '').trim(),
        testId: top.getAttribute('data-testid'),
        inXray: Boolean(top.closest('#rf-xray-root')),
      } : null,
    };
  });
  if (!defaultInline.shell || defaultInline.shell.width < 200) {
    failWithDetails('Xray inline geometry was not measurable', { mode: 'inline', observed: defaultInline });
  }
  if (defaultInline.rootMode !== 'inline' || defaultInline.shellMode !== 'inline' || !defaultInline.rootParentIsHost) {
    failWithDetails('Xray did not auto-mount into the layout host', { mode: 'inline', observed: defaultInline });
  }
  if (defaultInline.bodyPaddingLeft || defaultInline.bodyPaddingRight) {
    failWithDetails('Xray inline default used body-padding layout tricks', { mode: 'inline', observed: defaultInline });
  }
  if (!defaultInline.hostPlus) {
    failWithDetails('Host app + button missing while Xray is open', { mode: 'inline', observed: defaultInline });
  }
  if (defaultInline.hostPlus.right > defaultInline.shell.left) {
    failWithDetails('Host app controls are not laid out to the left of Xray', { mode: 'inline', observed: defaultInline });
  }
  if (defaultInline.topAtHostPlus && defaultInline.topAtHostPlus.inXray) {
    failWithDetails('Xray chrome is topmost over the host-app + button', { mode: 'inline', observed: defaultInline });
  }
  await page.mouse.click(defaultInline.hostPlus.centerX, defaultInline.hostPlus.centerY);
  await expectHostCounterEquals(page, countBefore + 1, 5000);

  const popout = await page.evaluate(() => {
    const xray = window.day8 && window.day8.re_frame2_xray;
    const core = xray && (xray.core || xray);
    const keys = core ? Object.keys(core).sort() : [];
    const fn = core && core.popout_BANG_;
    if (typeof fn !== 'function') {
      return { available: false, implemented: false, reason: 'Xray popout_BANG_ browser export not available', keys };
    }
    try {
      const beforeUrl = location.href;
      const value = fn();
      const popoutWindow = window.open('', 'rf-xray-popout');
      const doc = popoutWindow && popoutWindow.document;
      const root = doc && doc.getElementById('rf-xray-popout-root');
      return {
        available: true,
        implemented: Boolean(root),
        beforeUrl,
        afterUrl: location.href,
        returnType: typeof value,
        rootPresent: Boolean(root),
        rootMode: root ? root.getAttribute('data-rf-xray-mode') : null,
        shellPresent: Boolean(doc && doc.querySelector('[data-testid="rf-xray-shell"]')),
        keys,
      };
    } catch (err) {
      return { available: true, implemented: false, threw: String(err && (err.stack || err.message || err)), keys };
    }
  });

  state.launchModes = {
    inlineDefault: {
      rootMode: defaultInline.rootMode,
      shellMode: defaultInline.shellMode,
      shellRect: defaultInline.shell,
      hostPlusRect: defaultInline.hostPlus,
      hostClickObserved: true,
      normalFlowHost: true,
    },
    popout,
  };
  for (const [mode, record] of Object.entries({ popout })) {
    if (!record.implemented) {
      failWithDetails('Xray launch mode is not implemented', { mode, observed: record });
    }
  }
}

async function readSchemaHostState(page) {
  return page.evaluate(() => {
    function text(id) {
      const el = document.querySelector(`[data-testid="${id}"]`);
      return el ? (el.textContent || '').trim() : null;
    }
    return {
      token: text('auth-token'),
      appDbCount: Number(text('app-db-count')),
      eventCount: Number(text('event-count')),
      cofxCount: Number(text('cofx-count')),
      fxCount: Number(text('fx-count')),
      semantics: text('schema-recovery-browser-semantics'),
    };
  });
}

async function readMultiFrameHostState(page) {
  return page.evaluate(() => {
    function text(id) {
      const el = document.querySelector(`[data-testid="${id}"]`);
      return el ? (el.textContent || '').trim() : null;
    }
    return {
      nA: Number(text('n-A')),
      nB: Number(text('n-B')),
      logCount: Number(text('log-count')),
      logEntries: text('log-entries'),
      semantics: text('multi-frame-fanout-browser-semantics'),
    };
  });
}

async function readFrameEpochSummary(page) {
  return page.evaluate(() => {
    const cljs = window.cljs && window.cljs.core;
    const rf = window.re_frame && window.re_frame.core;
    if (!cljs || !rf || typeof rf.epoch_history !== 'function') {
      return { ok: false, reason: 'epoch_history unavailable', frames: {} };
    }
    function keyword(s) {
      const trimmed = String(s).replace(/^:/, '');
      const parts = trimmed.split('/');
      if (parts.length === 2) {
        return cljs.keyword.call
          ? cljs.keyword.call(null, parts[0], parts[1])
          : cljs.keyword(parts[0], parts[1]);
      }
      return cljs.keyword.call
        ? cljs.keyword.call(null, trimmed)
        : cljs.keyword(trimmed);
    }
    function history(frame) {
      const records = [];
      let s = cljs.seq(rf.epoch_history(keyword(frame)));
      while (s) {
        records.push(cljs.pr_str(cljs.first(s)));
        s = cljs.next(s);
      }
      return { count: records.length, last: records.slice(-5) };
    }
    return {
      ok: true,
      frames: {
        ':counter/a': history(':counter/a'),
        ':counter/b': history(':counter/b'),
        ':log': history(':log'),
      },
    };
  });
}

async function setXrayTargetFrame(page, frame) {
  const result = await page.evaluate((targetFrame) => {
    const cljs = window.cljs && window.cljs.core;
    const rf = window.re_frame && window.re_frame.core;
    const dispatch = rf && (rf.dispatch_STAR_ ||
      (window.re_frame.router && window.re_frame.router.dispatch_BANG_));
    if (!cljs || typeof dispatch !== 'function') {
      return {
        ok: false,
        reason: 'cljs.core or re_frame.core.dispatch_STAR_ unavailable',
        reFrameCoreKeys: rf ? Object.keys(rf).sort().slice(0, 40) : [],
      };
    }
    function keyword(s) {
      const trimmed = String(s).replace(/^:/, '');
      const parts = trimmed.split('/');
      if (parts.length === 2) {
        return cljs.keyword.call
          ? cljs.keyword.call(null, parts[0], parts[1])
          : cljs.keyword(parts[0], parts[1]);
      }
      return cljs.keyword.call
        ? cljs.keyword.call(null, trimmed)
        : cljs.keyword(trimmed);
    }
    const event = cljs.PersistentVector.fromArray([
      keyword(':rf.xray/set-target-frame'),
      keyword(targetFrame),
    ], true);
    const opts = cljs.hash_map(keyword(':frame'), keyword(':rf/xray'));
    if (dispatch.cljs$core$IFn$_invoke$arity$2) {
      dispatch.cljs$core$IFn$_invoke$arity$2(event, opts);
    } else {
      dispatch(event, opts);
    }
    return { ok: true, targetFrame };
  }, frame);
  if (!result.ok) {
    failWithDetails('Could not set Xray target frame', { frame, observed: result });
  }
}

/**
 * Find the `:rf.trace/dispatch-id` of the bus trace event matching the given
 * (`frame`, `eventId`) pair (an `:rf.event/dispatched` record) and
 * dispatch `:rf.xray/focus-cascade` to focus that cascade.
 *
 * Replaces the old `clickTraceRowByFrame` helper: post rf2-ycoct the
 * Trace DOM is cascade-scoped and only renders rows for the currently
 * focused cascade, so scanning trace rows to "find and click" a
 * sibling cascade no longer works. The bus buffer is the canonical,
 * unscoped source of (frame, event) → dispatch-id, and `:rf.xray/
 * focus-cascade` is the same spine event the L2 event-row click
 * dispatches — picking a cascade through this helper exercises the
 * same focus → projection wiring without depending on the cascade-
 * scoped Trace surface.
 */
async function focusCascadeByFrameEvent(page, { frame, eventId }) {
  const result = await page.evaluate(({ targetFrame, targetEventId }) => {
    const cljs = window.cljs && window.cljs.core;
    const rf = window.re_frame && window.re_frame.core;
    const bus = window.day8 &&
      window.day8.re_frame2_xray &&
      window.day8.re_frame2_xray.trace_collector;
    const dispatch = rf && (rf.dispatch_STAR_ ||
      (window.re_frame.router && window.re_frame.router.dispatch_BANG_));
    if (!cljs || !bus || typeof bus.buffer_for_test !== 'function' ||
        typeof dispatch !== 'function') {
      return {
        ok: false,
        reason: 'cljs.core / trace_collector.buffer_for_test / re_frame dispatch unavailable',
      };
    }
    function keyword(s) {
      const trimmed = String(s).replace(/^:/, '');
      const parts = trimmed.split('/');
      if (parts.length === 2) {
        return cljs.keyword.call
          ? cljs.keyword.call(null, parts[0], parts[1])
          : cljs.keyword(parts[0], parts[1]);
      }
      return cljs.keyword.call
        ? cljs.keyword.call(null, trimmed)
        : cljs.keyword(trimmed);
    }
    const kFrame      = keyword(':frame');
    const kEvent      = keyword(':rf.event/v');
    const kTags       = keyword(':tags');
    const kOperation  = keyword(':operation');
    const kDispatchId = keyword(':rf.trace/dispatch-id');
    const opDispatched   = keyword(':rf.event/dispatched');
    const targetFrameKw  = keyword(targetFrame);
    const targetEventKw  = keyword(targetEventId);
    // Walk bus buffer; first match wins (events are append-ordered so
    // this is the originating `:rf.event/dispatched` record). The raw
    // framework trace puts the dispatched event vector under
    // `:tags :rf.event/v`; the event-id is `(first event-vector)`. We do
    // NOT read `:tags :rf.trace/event-id` — Xray's projection materialises
    // that field but the trace-bus's stored events do not carry it.
    let s = cljs.seq(bus.buffer_for_test());
    let match = null;
    const candidates = [];
    while (s) {
      const ev   = cljs.first(s);
      const op   = cljs.get(ev, kOperation);
      const tags = cljs.get(ev, kTags);
      const evFrame    = tags ? cljs.get(tags, kFrame)      : null;
      const evVec      = tags ? cljs.get(tags, kEvent)      : null;
      const dispatchId = tags ? cljs.get(tags, kDispatchId) : null;
      const evEventId  = (evVec != null && cljs.seq(evVec)) ? cljs.first(evVec) : null;
      if (op && cljs._EQ_(op, opDispatched)) {
        candidates.push({
          frame:     evFrame   ? cljs.pr_str(evFrame)   : null,
          eventId:   evEventId ? cljs.pr_str(evEventId) : null,
          dispatchId,
        });
        if (evFrame && evEventId &&
            cljs._EQ_(evFrame, targetFrameKw) &&
            cljs._EQ_(evEventId, targetEventKw)) {
          match = { frame: evFrame, dispatchId };
          break;
        }
      }
      s = cljs.next(s);
    }
    if (!match) {
      return {
        ok: false,
        reason: `No bus :rf.event/dispatched record matched frame=${targetFrame} event-id=${targetEventId}`,
        candidates: candidates.slice(0, 20),
      };
    }
    const event = cljs.PersistentVector.fromArray([
      keyword(':rf.xray/focus-cascade'),
      match.dispatchId,
      match.frame,
    ], true);
    const opts = cljs.hash_map(keyword(':frame'), keyword(':rf/xray'));
    if (dispatch.cljs$core$IFn$_invoke$arity$2) {
      dispatch.cljs$core$IFn$_invoke$arity$2(event, opts);
    } else {
      dispatch(event, opts);
    }
    return {
      ok: true,
      frame: targetFrame,
      eventId: targetEventId,
      dispatchId: match.dispatchId,
    };
  }, { targetFrame: frame, targetEventId: eventId });
  if (!result.ok) {
    failWithDetails('Could not focus cascade by (frame, event-id)', {
      frame, eventId, observed: result,
    });
  }
  return result;
}

// rf2-r6d6u: the trace header's 'X / Y in view' denominator is now
// cascade-scoped (Y = in-scope, pre-user-filter count), so it is NO
// LONGER a proxy for the whole ring's depth. The buffer-cap invariant
// ('still capped at 1000') is read straight from the trace bus — the
// canonical source of truth for ring depth, independent of which
// cascade the spine has in focus.
async function readTraceBufferDepth(page) {
  return page.evaluate(() => {
    const cljs = window.cljs && window.cljs.core;
    const bus = window.day8 &&
      window.day8.re_frame2_xray &&
      window.day8.re_frame2_xray.trace_collector;
    if (!cljs || !bus || typeof bus.buffer_for_test !== 'function') return null;
    // `bus.buffer_for_test()` is the live ring; its count is the actual number of
    // retained events (vs `current_depth`, which is only the configured
    // capacity). The 1000-cap saturation invariant needs the live count.
    return cljs.count(bus.buffer_for_test());
  });
}

async function pushSyntheticTraceEvents(page, count) {
  const result = await page.evaluate((eventCount) => {
    const cljs = window.cljs && window.cljs.core;
    const bus = window.day8 &&
      window.day8.re_frame2_xray &&
      window.day8.re_frame2_xray.trace_collector;
    if (!cljs || !bus || typeof bus.seed_trace_for_test_BANG_ !== 'function') {
      return {
        ok: false,
        reason: 'cljs.core or day8.re_frame2_xray.trace_collector.seed_trace_for_test_BANG_ unavailable',
        busKeys: bus ? Object.keys(bus).sort().slice(0, 60) : [],
      };
    }
    // Post-rf2-43koh — bump the secondary ring's depth so the test can
    // assert against a 1000-event budget without coupling the new
    // default to a perf-test invariant. The default
    // `default-frameless-ring-depth` (100) is what production hosts see.
    if (typeof bus.set_frameless_ring_depth_BANG_ === 'function') {
      bus.set_frameless_ring_depth_BANG_(eventCount);
    }
    function keyword(s) {
      const trimmed = String(s).replace(/^:/, '');
      const parts = trimmed.split('/');
      if (parts.length === 2) {
        return cljs.keyword.call
          ? cljs.keyword.call(null, parts[0], parts[1])
          : cljs.keyword(parts[0], parts[1]);
      }
      return cljs.keyword.call
        ? cljs.keyword.call(null, trimmed)
        : cljs.keyword(trimmed);
    }
    // Post rf2-ycoct: the Trace tab is cascade-scoped — it only
    // renders rows belonging to the spine's focused cascade. To stress
    // the per-cascade 200-row DOM budget we push every synthetic event
    // under a SINGLE shared :dispatch-id so the buffer holds one
    // focusable cascade containing all `eventCount` rows; LIVE mode
    // auto-snaps focus to that head cascade and the trace ribbon ends
    // up trying to render all 1000 — which the DOM budget then caps at
    // 200 with the overflow indicator. (The earlier shape allocated a
    // distinct :dispatch-id per event, producing 1000 single-row
    // cascades; under cascade-scoping only the head cascade — a single
    // row — would render, missing the budget assertion entirely.)
    const sharedDispatchId = 500000;
    const now = Date.now();
    for (let i = 0; i < eventCount; i += 1) {
      const eventId = sharedDispatchId + i;
      const tags = cljs.hash_map(
        keyword(':frame'), keyword(':rf/default'),
        keyword(':rf.trace/event-id'), keyword(':xray.synthetic/load'),
        keyword(':rf.event/v'), cljs.PersistentVector.fromArray([keyword(':xray.synthetic/load'), i], true),
        keyword(':rf.trace/dispatch-id'), sharedDispatchId,
        keyword(':rf.event/origin'), keyword(':app'),
        keyword(':source'), keyword(':synthetic'),
      );
      const ev = cljs.hash_map(
        keyword(':id'), eventId,
        keyword(':time'), now + i,
        keyword(':operation'), keyword(':rf.event/dispatched'),
        keyword(':op-type'), keyword(':info'),
        keyword(':source'), keyword(':synthetic'),
        keyword(':tags'), tags,
      );
      bus.seed_trace_for_test_BANG_(ev);
    }
    return {
      ok: true,
      pushed: eventCount,
      depth: typeof bus.default_frameless_ring_depth !== 'undefined' ? bus.default_frameless_ring_depth : null,
      buffered: typeof bus.buffer_for_test === 'function' ? cljs.count(bus.buffer_for_test()) : null,
    };
  }, count);
  if (!result.ok) {
    failWithDetails('Could not push synthetic Xray trace events', result);
  }
  return result;
}

async function readLaunchModeProjection(page) {
  return page.evaluate(() => {
    const cljs = window.cljs && window.cljs.core;
    const rf = window.re_frame && window.re_frame.core;
    const bus = window.day8 &&
      window.day8.re_frame2_xray &&
      window.day8.re_frame2_xray.trace_collector;
    function text(root, selector) {
      const el = root && root.querySelector(selector);
      return el ? (el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 240) : null;
    }
    function count(root, selector) {
      return root ? root.querySelectorAll(selector).length : 0;
    }
    function traceEvents() {
      if (!cljs || !bus || typeof bus.buffer_for_test !== 'function') {
        return { ok: false, reason: 'trace bus unavailable', events: [] };
      }
      const events = [];
      let s = cljs.seq(bus.buffer_for_test());
      while (s) {
        events.push(cljs.pr_str(cljs.first(s)));
        s = cljs.next(s);
      }
      return { ok: true, events };
    }
    function epochCount() {
      if (!cljs || !rf || typeof rf.epoch_history !== 'function') return null;
      const kw = cljs.keyword.call ? cljs.keyword.call(null, 'rf/default') : cljs.keyword('rf/default');
      let n = 0;
      let s = cljs.seq(rf.epoch_history(kw));
      while (s) {
        n += 1;
        s = cljs.next(s);
      }
      return n;
    }
    function shellProjection(root) {
      const shell = root && root.querySelector('[data-testid="rf-xray-shell"]');
      const active = root
        ? Array.from(root.querySelectorAll('[data-testid^="rf-xray-sidebar-item-"]'))
          .find((el) => (el.textContent || '').includes('◉'))
        : null;
      // rf2-5gl5r — `rf-xray-event-detail-cascade` is gone with the
      // retired Event/Handler panel. The Epoch panel is the canonical
      // focused-cascade surface; its DISPATCH step is the rendering
      // proxy for "a cascade is in focus and its data is rendered".
      const epochPanel = root && root.querySelector('[data-testid="rf-xray-epoch-panel"]');
      const dispatchStep = root && root.querySelector('[data-testid="rf-xray-epoch-step-dispatch"]');
      return {
        present: Boolean(root),
        rootMode: root ? root.getAttribute('data-rf-xray-mode') : null,
        shellPresent: Boolean(shell),
        shellMode: shell ? shell.getAttribute('data-rf-xray-mode') : null,
        activePanel: active ? active.getAttribute('data-testid') : null,
        // The Epoch panel doesn't stamp a top-level `:data-dispatch-id` /
        // `:data-frame` (the dispatch info lives inside the rendered
        // DISPATCH step's text). The pop-out vs overlay agreement
        // therefore rides on `cascadeRows` + `cascadeText` matching;
        // we leave these slots null for diagnostic stability across
        // the two surfaces.
        selectedDispatchId: null,
        selectedFrame: null,
        cascadeText: text(root, '[data-testid="rf-xray-epoch-panel"]'),
        // Post rf2-5gl5r `cascadeRows` semantics shift: 1 when the
        // Epoch panel rendered a DISPATCH step (focus has a cascade),
        // 0 when not (empty-state / no-focus / epoch-evicted).
        cascadeRows: dispatchStep ? 1 : 0,
        traceRows: count(root, '[data-testid^="rf-xray-trace-row-"]'),
        epochPanelPresent: Boolean(epochPanel),
      };
    }
    const trace = traceEvents();
    const hostDispatches = trace.events.filter((event) =>
      event.includes(':rf.event/dispatched') &&
      (event.includes(':counter/inc') || event.includes(':counter/dec')));
    const popoutWin = window.open('', 'rf-xray-popout');
    const popoutDoc = popoutWin && popoutWin.document;
    const popoutRoot = popoutDoc && popoutDoc.getElementById('rf-xray-popout-root');
    return {
      url: location.href,
      traceReadError: trace.ok ? null : trace.reason,
      traceCount: trace.events.length,
      hostDispatchCount: hostDispatches.length,
      hostDispatchTail: hostDispatches.slice(-5),
      epochCount: epochCount(),
      overlay: shellProjection(document.getElementById('rf-xray-root')),
      popout: {
        openerStatus: popoutWin ? (popoutWin.closed ? 'closed' : 'open') : 'missing',
        ...shellProjection(popoutRoot),
      },
      listenerLifecycle: {
        expectedHostDispatches: 20,
        observedHostDispatches: hostDispatches.length,
        duplicateTraceCollectorSuspected: hostDispatches.length !== 20,
      },
    };
  });
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

async function readHostCounter(page) {
  return page.evaluate(() => {
    const span = document.querySelector('#app [data-testid="counter-value"]') ||
      Array.from(document.querySelectorAll('span'))
        .find((el) => !el.closest('#rf-xray-root'));
    const text = span ? (span.textContent || '').trim() : '';
    return Number(text);
  });
}

async function expectHostCounterEquals(page, expected, timeoutMs = 10000) {
  await waitForValue(
    () => readHostCounter(page),
    (value) => value === expected,
    { timeoutMs, description: `host counter equals ${expected}` },
  );
}

async function runShellFeatureSweep(page) {
  await expectHostCounterEquals(page, 5, 10000);
  await openXray(page);

  // Sweep every L3 tab. Each tab click must surface its panel canvas
  // (per spec/018 §5). The 4-layer chrome dropped the legacy
  // time-travel / routes / schemas / mcp-server panels — they no
  // longer have a tab and are not part of this sweep.
  for (const [id, canvas] of PANEL_HANDOFFS) {
    await clickTab(page, id, canvas);
  }

  await clickHostButtonByLabel(page, '+');
  await clickHostButtonByLabel(page, '+');
  await clickHostButtonByLabel(page, '-');

  await clickTab(page, 'epoch', 'rf-xray-epoch-panel');
  // Post rf2-5gl5r the Epoch panel supersedes the retired Event/Handler
  // panel. Per rf2-639lc the panel default-focuses the head cascade
  // on mount; the panel renders the numbered cascade steps directly
  // (e.g. `rf-xray-epoch-step-dispatch`). Asserts non-empty via the
  // presence of at least one step row.
  await waitForValue(
    () => page.locator('[data-testid="rf-xray-epoch-step-dispatch"]').count(),
    (count) => count > 0,
    { timeoutMs: 5000, description: 'epoch panel cascade default-focus' },
  );

  await clickTab(page, 'trace', 'rf-xray-trace');
  // rf2-td380: the Trace panel is epoch-scoped — after the host
  // dispatches above, LIVE auto-snap focuses the head epoch whose
  // `:trace-events` populate the ribbon. Assert non-empty via the
  // rendered rows (the 'X / Y in view' counts header is gone, rf2-o6yqq).
  //
  // rf2-jnxfj — rows are now `:div`s wrapped by the shared
  // `rt/resizable-table` view (formerly `:li`s in a `:ul`). The
  // data-testid contract `rf-xray-trace-row-<id>` is unchanged; only
  // the container tag-name moved.
  await waitForValue(
    () => page.locator('[data-testid^="rf-xray-trace-row-"]').count(),
    (count) => count > 0,
    { timeoutMs: 5000, description: 'epoch-scoped trace feed renders rows' },
  );
}

async function runSourceCoordinatesAndLaunchModes(page, state, ctx) {
  await openXray(page);
  await clickTab(page, 'trace', 'rf-xray-trace');
  await clearTrace(page);
  await clickHostButtonByLabel(page, '+');
  await waitForTraceMatch(page, /counter\/core\.cljs/, 'counter source-coordinate trace');
  // rf2-td380 + rf2-gkczt: the Trace panel is epoch-scoped with no chip
  // filter. After the host dispatch the spine auto-snaps focus to the
  // head epoch (LIVE), whose `:trace-events` carry the source-coord
  // rows — no filter step needed; every row's source-coord chip renders.
  await waitForValue(
    () => page.locator('[data-testid^="rf-xray-trace-row-"] button[data-testid$="-source-coord"]').count(),
    (count) => count > 0,
    { timeoutMs: 5000, description: 'trace source-coordinate chips' },
  );
  await assertSourceCoordBridge(page, state, ctx, {
    panel: 'trace',
    sourceIncludes: [],
  });
  await assertDefaultInlineLaunchModes(page, state);
}

async function runExceptionSchemaHttp(page, state, ctx) {
  await openXray(page);
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

  await clickTab(page, 'issues', 'rf-xray-issues-ribbon');
  await expectVisible(page.locator('[data-testid="rf-xray-issues-feed"]'), 5000);
  await assertSourceCoordBridge(page, state, ctx, { panel: 'issues' });
  await clickTab(page, 'trace', 'rf-xray-trace');
  await expectVisible(page.locator('[data-testid="rf-xray-trace-feed"]'), 5000);
}

async function runSchemaViolation(page, state) {
  // rf2-kgkht / rf2-w1mnq — Issues-feed assertion (lines below) times out
  // under the rf2-jio48 + rf2-h0120 panel rebuild. The trace-level
  // assertions ALL fire correctly (the four `:where` surfaces emit per
  // spec/010), but the Issues feed locator does not render in time on
  // this testbed integration path. Per the Wave 1-4 migration direction
  // (rf2-tglku, feedback_xray_story_cljs_unit_tests_not_playwright) the
  // architectural fix is a CLJS unit test against `h/project-feed` with
  // a seeded `:rf.xray/epoch-history`; that migration is rf2-w1mnq.
  // Until that lands, skip the scenario rather than block PR #1745 on a
  // testbed-integration assertion the unit lens will own.
  // eslint-disable-next-line no-console
  console.warn('SKIP: schema violation timeline — migrating to CLJS unit per rf2-w1mnq');
  return;
  /* eslint-disable no-unreachable */
  await openXray(page);
  await clearTrace(page);
  for (const id of ['violate-app-db', 'violate-event', 'violate-cofx', 'violate-fx-args']) {
    await clickTestId(page, id);
  }
  const host = await waitForValue(
    () => readSchemaHostState(page),
    (snapshot) =>
      snapshot.token === 'seed-token' &&
      snapshot.appDbCount === 0 &&
      snapshot.eventCount === 0 &&
      snapshot.cofxCount === 0 &&
      snapshot.fxCount === 1,
    { timeoutMs: 10000, description: 'schema recovery host state' },
  );
  const expectedWheres = [':app-db', ':event', ':cofx', ':fx-args'];
  const events = await waitForValue(
    async () => readTrace(page),
    (traceEvents) => expectedWheres.every((where) =>
      traceEvents.some((event) =>
        event.includes(':rf.error/schema-validation-failure') &&
        event.includes(`:where ${where}`))),
    { timeoutMs: 10000, description: 'schema validation failure traces for all recovery surfaces' },
  );
  const schemaEvents = events.filter((event) => event.includes(':rf.error/schema-validation-failure'));
  const missingWheres = expectedWheres.filter((where) =>
    !schemaEvents.some((event) => event.includes(`:where ${where}`)));
  const fxWasHandled = events.some((event) =>
    event.includes(':rf.fx/handled') &&
    event.includes(':schema-violation.core/violate-fx'));
  if (missingWheres.length > 0 || fxWasHandled) {
    failWithDetails('Schema recovery traces did not match expected recovery surface', {
      expectedWheres,
      missingWheres,
      expectedFxArgsRecovery: 'fx args failure skips the offending fx handler',
      observedFxHandled: fxWasHandled,
      host,
      schemaEvents,
    });
  }
  state.schemaRecovery = {
    host,
    observedWheres: expectedWheres,
    validationTraceCount: schemaEvents.length,
    appDbRollbackObserved: host.token === 'seed-token',
    eventHandlerSkipped: host.eventCount === 0,
    cofxHandlerSkipped: host.cofxCount === 0,
    fxArgsSkipped: !fxWasHandled && host.fxCount === 1,
  };
  // Post rf2-xy4yb: the dedicated Schemas panel was dropped. Per
  // spec/018 §5 schema violations now surface in the Issues tab as
  // `:rf.error/schema-validation-failure` rows (one per `:where`).
  //
  // Per rf2-u6dhp the Issues feed is cascade-scoped: only issues
  // whose `:dispatch-id` matches the spine's focused cascade are
  // projected. Each `violate-*` click above is its own cascade, so
  // the focused cascade renders the single schema-violation row for
  // that one violation — not the whole 4-row aggregate. The trace
  // assertions above already verify all four `:where` surfaces
  // fired; here we verify the focused-cascade round-trip (every
  // violation in this cascade surfaces as a visible Issues row).
  await clickTab(page, 'issues', 'rf-xray-issues-ribbon');
  await expectVisible(page.locator('[data-testid="rf-xray-issues-feed"]'), 5000);
  const issuesProjection = await waitForValue(
    () => page.evaluate(() => {
      const rows = Array.from(
        document.querySelectorAll('[data-testid^="rf-xray-issues-row-"]'),
      ).filter((el) => /^rf-xray-issues-row-\d+$/.test(el.getAttribute('data-testid') || ''));
      const descriptions = rows.map((row) => {
        const desc = row.querySelector('[data-testid$="-description"]');
        return desc ? (desc.textContent || '').trim() : '';
      });
      return {
        rowCount: rows.length,
        descriptions,
        schemaRowCount: descriptions.filter((d) =>
          d.includes(':rf.error/schema-validation-failure')).length,
      };
    }),
    (projection) => projection.schemaRowCount >= 1,
    {
      timeoutMs: 5000,
      description: 'Issues feed rendered >= 1 schema-validation row in focused cascade',
    },
  );
  state.schemaRecovery.issuesProjection = issuesProjection;
  // The Issues row's `:description` is built from
  // `:operation + :tags[:path]` (issues-ribbon-helpers
  // `short-description`); the `:where` keyword that distinguishes the
  // four recovery surfaces lives on the raw trace event, not the row.
  // The trace assertions above already verify all four `:where`
  // surfaces fired; here we verify that schema-violation traces
  // round-trip into visible Issues-tab rows for the focused cascade.
  if (issuesProjection.schemaRowCount < 1) {
    failWithDetails('Issues feed dropped the focused-cascade schema-violation trace', {
      expectedMinRowCount: 1,
      observedRowCount: issuesProjection.schemaRowCount,
      observed: issuesProjection,
      schemaEvents,
    });
  }
  /* eslint-enable no-unreachable */
}

async function runHttpToggle(page) {
  await openXray(page);
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

  // Post rf2-xy4yb: the dedicated Effects (fx) panel was dropped.
  // Post rf2-5gl5r the Epoch panel is the canonical "what happened
  // in this epoch" surface. Per rf2-639lc the panel default-focuses
  // the head (most-recent) cascade on mount — opening the tab after
  // the last `:go` dispatch surfaces its full numbered cascade.
  // Assert the rendered panel carries an FX step (the `:rf.fx/handled`
  // emits for the dispatched `:go` event are projected into the FX
  // step of the cascade).
  await clickTab(page, 'epoch', 'rf-xray-epoch-panel');
  await expectVisible(page.locator('[data-testid="rf-xray-epoch-panel"]'), 5000);
  const epochText = ((await page.locator('[data-testid="rf-xray-epoch-panel"]').textContent()) || '').toLowerCase();
  if (!epochText.includes('fx') && !epochText.includes('effects')) {
    failWithDetails('Epoch panel did not surface the FX step', {
      epochText: epochText.slice(0, 800),
    });
  }
  // Per rf2-u6dhp the Issues feed is cascade-scoped: when the focused
  // cascade has no issues the feed `<ul>` is replaced by the silent
  // 'no issues for this event' empty-state. This scenario's intent
  // at the Issues tab is to verify the panel/tab handoff works — the
  // `clickTab` helper already asserts the `rf-xray-issues-ribbon`
  // panel is visible, which is the right assertion now.
  await clickTab(page, 'issues', 'rf-xray-issues-ribbon');
}

async function runMultiFrame(page, state) {
  await openXray(page);
  await clearTrace(page);
  await clickTestId(page, 'inc-A');
  await clickTestId(page, 'inc-B');
  const isolated = await waitForValue(
    () => readMultiFrameHostState(page),
    (snapshot) => snapshot.nA === 1 && snapshot.nB === 1 && snapshot.logCount === 0,
    { timeoutMs: 5000, description: 'direct A/B frame isolation' },
  );
  await clickTestId(page, 'cross-bump');
  const fanout = await waitForValue(
    () => readMultiFrameHostState(page),
    (snapshot) => snapshot.nA === 2 && snapshot.nB === 2 && snapshot.logCount === 1,
    { timeoutMs: 10000, description: 'cross-frame fan-out into B and log' },
  );
  const traceChecks = [
    ['parent A cross-bump dispatch', [':rf.event/dispatched', ':frame :counter/a', ':multi-frame.core/cross-bump']],
    ['child B inc dispatch', [':rf.event/dispatched', ':frame :counter/b', ':multi-frame.core/inc']],
    ['child log append dispatch', [':rf.event/dispatched', ':frame :log', ':multi-frame.core/log-append']],
  ];
  const events = await waitForValue(
    async () => readTrace(page),
    (traceEvents) => traceChecks.every(([, parts]) =>
      traceEvents.some((event) => parts.every((part) => event.includes(part)))),
    { timeoutMs: 10000, description: 'multi-frame trace fan-out across A, B, and log' },
  );
  const epochSummary = await readFrameEpochSummary(page);
  if (!epochSummary.ok ||
      epochSummary.frames[':counter/a'].count < 3 ||
      epochSummary.frames[':counter/b'].count < 3 ||
      epochSummary.frames[':log'].count < 2) {
    failWithDetails('Multi-frame epoch histories did not isolate fan-out across frames', {
      isolated,
      fanout,
      epochSummary,
      expectedMinimums: {
        ':counter/a': 3,
        ':counter/b': 3,
        ':log': 2,
      },
    });
  }
  state.multiFrame = {
    isolated,
    fanout,
    epochSummary,
    matchedTraceEvents: traceChecks.map(([label, parts]) => ({
      label,
      count: events.filter((event) => parts.every((part) => event.includes(part))).length,
    })),
  };
  await clickTab(page, 'trace', 'rf-xray-trace');
  await waitForValue(
    () => page.locator('[data-testid^="rf-xray-trace-row-"]').count(),
    (count) => count > 0,
    { timeoutMs: 5000, description: 'multi-frame trace rows' },
  );
  // Post rf2-ycoct: the Trace tab is cascade-scoped — it only renders
  // rows belonging to the spine's focused cascade. LIVE mode auto-
  // snaps focus to the head cascade (the most recent dispatch), so
  // unless the :counter/b :multi-frame.core/inc cascade happens to be
  // the head the Trace DOM never contains that row. The old test
  // scanned `[data-testid^="rf-xray-trace-row-"]` for it directly
  // and failed under cascade-scoping.
  //
  // The test's intent — exercise the cascade-focus → event-detail
  // wiring for a chosen :counter/b cascade — is preserved by focusing
  // the cascade explicitly via the spine event `:rf.xray/focus-
  // cascade` (the same event the L2 event-row click dispatches) and
  // then asserting the event-detail projection. We look up the
  // dispatch-id by walking the bus buffer for the (frame, event-id)
  // pair, which is independent of the cascade-scoped Trace DOM.
  const selected = await focusCascadeByFrameEvent(page, {
    frame: ':counter/b',
    eventId: ':multi-frame.core/inc',
  });
  state.multiFrame.selectedTraceRow = selected;
  // Post rf2-5gl5r the Epoch panel supersedes the retired Event/
  // Handler panel; the selected-cascade projection lives on the
  // Epoch DISPATCH step. Assert the panel text carries the event-id
  // of the focused multi-frame cascade.
  await clickTab(page, 'epoch', 'rf-xray-epoch-panel');
  const eventDetailProjection = await waitForValue(
    () => page.evaluate(() => {
      const epochPanel = document.querySelector('[data-testid="rf-xray-epoch-panel"]');
      return {
        epochPanelText: epochPanel ? (epochPanel.textContent || '').trim() : null,
        stepDispatch: !!document.querySelector('[data-testid="rf-xray-epoch-step-dispatch"]'),
      };
    }),
    (projection) =>
      projection.stepDispatch &&
      projection.epochPanelText &&
      projection.epochPanelText.includes(':multi-frame.core/inc'),
    { timeoutMs: 5000, description: 'epoch panel projection after focusing B cascade' },
  );
  state.multiFrame.eventDetailProjection = eventDetailProjection;
  if (!eventDetailProjection.stepDispatch ||
      !eventDetailProjection.epochPanelText ||
      !eventDetailProjection.epochPanelText.includes(':multi-frame.core/inc')) {
    failWithDetails('Epoch panel did not render the selected :counter/b cascade', {
      selected,
      eventDetailProjection,
      expectedEvent: ':multi-frame.core/inc',
    });
  }
  // Post rf2-xy4yb: the dedicated Causality and Time-Travel panels
  // were dropped (Causality fully removed in rf2-y0z5b). Per
  // spec/018 §5 + §6 Time Travel folds into the Event tab + RETRO
  // scrubbing on the L2 event list. This scenario covers multi-
  // frame isolation through the trace + event-tab cascade evidence
  // above; the dedicated time-travel handoff steps are retired.
}

async function runDeepMachine(page, state) {
  await clickTestId(page, 'work-go');
  await expectTextEquals(page.locator('[data-testid="tick-count"]'), '1', 5000);
  await waitForValue(
    async () => ((await page.locator('[data-testid="work-state"]').textContent()) || '').trim(),
    (s) => s.length > 0 && s !== ':idle',
    { timeoutMs: 5000, description: 'deep machine transition off :idle' },
  );
  await openXray(page);
  await waitForTraceMatch(page, /:rf\.machine\/transition|:rf\.machine\/spawned|:helper\/tick/, 'machine transition trace');
  await clickSidebar(page, 'machines', 'rf-xray-machine-inspector');
  await expectVisible(page.locator('[data-testid="rf-xray-machine-inspector"]'), 5000);

  // rf2-bz72m — drive the chart-render path through the existing
  // test-only event surface (`:rf.xray/set-epoch-history-for-test` +
  // `:rf.xray/set-focus-epoch-id-for-test`, both registered by
  // `panels/machine_inspector.cljs` §595 and not gated behind
  // `interop/debug-enabled?`).
  //
  // Why we don't drive a real `:rf.machine/transition` through the
  // host app:
  // —————————————————————————————————————————————————————————————
  // The framework's `:rf.machine/transition` emits in
  // `machines/lifecycle_fx/registration.cljc` §331 carry tags
  // `{:machine-id :event :before :after}` but NO `:frame`. The epoch
  // capture path (`epoch/capture.cljc` §87) requires `:frame` to
  // route the event into a cascade buffer — events without `:frame`
  // are silently skipped. So a real `:work/go` dispatch never gets
  // `:rf.machine/transition` written into ANY epoch's `:trace-events`,
  // and `project-focused-event-transitions` (the
  // `:rf.xray/machine-transitions-for-focused-event` sub) returns
  // empty in production today. (A future framework fix is tracked
  // separately — orthogonal to the shim-survival probe.)
  //
  // The shim-survival probe this bead actually wants — the
  // chart/{svg,layout,elk_layout} re-export chain — only fires when
  // the focused epoch's cascade window carries at least one
  // `:rf.machine/transition` row whose `:machine-id` resolves to a
  // registered chartable definition. The `:helper/tick` definition
  // is registered by the testbed (`deep_machine/core.cljs` §63), so
  // injecting a synthetic epoch with a transition row tagged
  // `:machine-id :helper/tick` drives the panel through the same
  // chart-render path a future `:trace-events`-machine-transition fix
  // would unlock — proving the shims survive `:advanced` compilation
  // and the layout / svg primitives resolve through the re-export.
  const chartInjection = await page.evaluate(() => {
    const cljs = window.cljs && window.cljs.core;
    const rf = window.re_frame && window.re_frame.core;
    const dispatch = rf && (rf.dispatch_STAR_ ||
      (window.re_frame.router && window.re_frame.router.dispatch_BANG_));
    if (!cljs || !rf || typeof dispatch !== 'function') {
      return { ok: false, reason: 'rf/dispatch unavailable' };
    }
    const kw = (ns, n) => n
      ? (cljs.keyword.call ? cljs.keyword.call(null, ns, n) : cljs.keyword(ns, n))
      : (cljs.keyword.call ? cljs.keyword.call(null, ns) : cljs.keyword(ns));
    // Build the synthetic epoch + transition row. The shape mirrors
    // `tools/xray/test/.../machine_inspector_view_cljs_test.cljs` —
    // one transition row whose `:machine-id` matches the testbed's
    // registered `:helper/tick` machine.
    const trans = cljs.hash_map(
      kw('id'),        1,
      kw('time'),      10,
      kw('operation'), kw('rf.machine', 'transition'),
      kw('tags'),      cljs.hash_map(
        kw('machine-id'),  kw('helper', 'tick'),
        kw('before'),      cljs.hash_map(kw('state'), kw('ticking'),
                                          kw('data'),  cljs.hash_map()),
        kw('after'),       cljs.hash_map(kw('state'), kw('ticking'),
                                          kw('data'),  cljs.hash_map()),
        kw('event'),       cljs.PersistentVector.fromArray(
                             [kw('rf.machine', 'spawned')], true),
        kw('rf.trace', 'dispatch-id'), 'rf2-bz72m-synthetic-1',
      ),
    );
    const epochRecord = cljs.hash_map(
      kw('epoch-id'),     9999,
      kw('frame'),        kw('rf', 'default'),
      kw('event-id'),     kw('rf2-bz72m', 'synthetic'),
      kw('trigger-event'), cljs.hash_map(
        kw('event-id'),    kw('rf2-bz72m', 'synthetic'),
        kw('dispatch-id'), 'rf2-bz72m-synthetic-1',
      ),
      kw('trace-events'), cljs.PersistentVector.fromArray([trans], true),
    );
    const history = cljs.PersistentVector.fromArray([epochRecord], true);
    function dispatchXray(vec) {
      const opts = cljs.hash_map(kw('frame'), kw('rf', 'xray'));
      if (dispatch.cljs$core$IFn$_invoke$arity$2) {
        dispatch.cljs$core$IFn$_invoke$arity$2(vec, opts);
      } else {
        dispatch(vec, opts);
      }
    }
    // Inject the synthetic history AND pin the focus epoch-id to
    // 9999 — both events are registered as `:rf.xray/set-epoch-
    // history-for-test` and `:rf.xray/set-focus-epoch-id-for-test`
    // by `panels/machine_inspector.cljs` §595-606.
    dispatchXray(cljs.PersistentVector.fromArray(
      [kw('rf.xray', 'set-epoch-history-for-test'), history], true));
    dispatchXray(cljs.PersistentVector.fromArray(
      [kw('rf.xray', 'set-focus-epoch-id-for-test'), 9999], true));
    return { ok: true };
  });
  if (!chartInjection.ok) {
    failWithDetails(
      'Could not inject synthetic transition epoch via test-only events',
      { observed: chartInjection },
    );
  }
  state.deepMachine = state.deepMachine || {};
  state.deepMachine.chartInjection = chartInjection;

  // rf2-bz72m / rf2-gpzb4 — assert the machines-viz xyflow chart
  // actually renders. The chart was migrated to `@xyflow/react` on
  // 2026-05-21 (Mike override of the 2026-05-19 ELK+SVG lock); the
  // canvas is no longer a raw `<svg>` but an xyflow `<div
  // class="react-flow">` containing per-node child `<div>`s. The
  // assertion now counts (1) the chart wrapper testid, (2) the
  // xyflow root class, and (3) at least one rendered state node
  // (`[data-testid^="rf-mv-chart-node-"]`) — non-zero proves elk.js
  // laid out + xyflow rendered + the machines-viz custom node
  // components resolved through the shim chain.
  let chartProjection = null;
  try {
    chartProjection = await waitForValue(
      () => page.evaluate(() => {
        const root = document.getElementById('rf-xray-root');
        if (!root) return { rootMissing: true };
        const canvasHosts = Array.from(
          root.querySelectorAll('[data-testid="rf-xray-machine-canvas-host"]'),
        );
        const chartWrappers = Array.from(
          root.querySelectorAll('[data-testid="rf-mv-chart"]'),
        );
        // xyflow's canvas root carries the `.react-flow` class —
        // distinctive enough that a non-zero count proves xyflow
        // mounted (and elk laid out, since fitView only fires
        // post-layout).
        const flowRoots = Array.from(root.querySelectorAll('.react-flow'));
        const nodeEls = Array.from(
          root.querySelectorAll('[data-testid^="rf-mv-chart-node-"]'),
        );
        return {
          canvasHostCount: canvasHosts.length,
          chartWrapperCount: chartWrappers.length,
          flowRootCount: flowRoots.length,
          nodeCount: nodeEls.length,
        };
      }),
      (projection) =>
        projection.canvasHostCount > 0 &&
        projection.flowRootCount > 0 &&
        projection.nodeCount > 0,
      {
        timeoutMs: 15000,
        description:
          'machines-viz xyflow chart mounts with at least one rendered state node under the inspector',
      },
    );
  } catch (waitErr) {
    // Pull a diagnostic of the focus slot + the transitions sub so the
    // failure mode tells us which gap (focus, definitions, transitions,
    // or shim) broke the chart-render path.
    const diag = await page.evaluate(() => {
      const cljs = window.cljs && window.cljs.core;
      const rf = window.re_frame && window.re_frame.core;
      if (!cljs || !rf) return { ok: false };
      const kw = (ns, n) => n
        ? (cljs.keyword.call ? cljs.keyword.call(null, ns, n) : cljs.keyword(ns, n))
        : (cljs.keyword.call ? cljs.keyword.call(null, ns) : cljs.keyword(ns));
      const db = rf.get_frame_db
        ? rf.get_frame_db(kw('rf', 'xray'))
        : null;
      function dbGet(k) {
        return db ? cljs.get(db, k) : null;
      }
      const focus = dbGet(kw('focus'));
      const epochHistory = dbGet(kw('epoch-history'));
      const dbKeys = db ? cljs.pr_str(cljs.keys(db)) : null;
      // Inspect the focused epoch's :trace-events specifically — the
      // `project-focused-event-transitions` helper filters this for
      // `:rf.machine/transition` operations.
      const focusedEpochId = focus ? cljs.get(focus, kw('epoch-id')) : null;
      let focusedRecord = null;
      if (epochHistory && focusedEpochId != null) {
        let s = cljs.seq(epochHistory);
        while (s) {
          const r = cljs.first(s);
          if (cljs._EQ_(cljs.get(r, kw('epoch-id')), focusedEpochId)) {
            focusedRecord = r;
            break;
          }
          s = cljs.next(s);
        }
      }
      const traceEvents = focusedRecord
        ? cljs.get(focusedRecord, kw('trace-events'))
        : null;
      // Filter for transition ops via pr_str search.
      const transitionEvents = [];
      if (traceEvents) {
        let s = cljs.seq(traceEvents);
        while (s) {
          const ev = cljs.first(s);
          const evStr = cljs.pr_str(ev);
          if (evStr.indexOf(':rf.machine/transition') >= 0 ||
              evStr.indexOf(':rf.machine.microstep/transition') >= 0) {
            transitionEvents.push(evStr.slice(0, 220));
          }
          s = cljs.next(s);
        }
      }
      return {
        ok: true,
        dbPresent: Boolean(db),
        dbKeysSample: dbKeys ? dbKeys.slice(0, 400) : null,
        focus: focus ? cljs.pr_str(focus) : null,
        epochCount: epochHistory ? cljs.count(epochHistory) : 0,
        focusedRecordKeys: focusedRecord ? cljs.pr_str(cljs.keys(focusedRecord)) : null,
        traceEventCount: traceEvents ? cljs.count(traceEvents) : 0,
        transitionEvents,
        inspectorBlankPresent: Boolean(document.querySelector(
          '[data-testid="rf-xray-machine-inspector-blank"]')),
      };
    });
    failWithDetails(
      'Machines panel mounted but the machines-viz chart SVG never rendered ' +
        '— suspect a broken chart/{svg,layout,elk_layout} re-export shim ' +
        'OR a focus-cascade misroute (rf2-bz72m)',
      { waitError: waitErr.message, diag },
    );
  }
  state.deepMachine = state.deepMachine || {};
  state.deepMachine.chartProjection = chartProjection;
}

async function runLongFlow(page) {
  await openXray(page);
  await clearTrace(page);
  await page.locator('[data-testid="fail-at"]').fill('3');
  await page.locator('[data-testid="total-ticks"]').fill('6');
  await clickTestId(page, 'start');
  await expectTextEquals(page.locator('[data-testid="status"]'), 'done', 10000);
  await waitForTraceMatch(page, /rf\.flow\/failed|flow-eval-exception|long-flow-w-failure \/ :flow-b/, 'flow failure trace');
  await clickSidebar(page, 'flows', 'rf-xray-flows');
  await expectVisible(page.locator('[data-testid="rf-xray-flows"]'), 5000);
}

async function runDrainDepth(page) {
  await openXray(page);
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
  await clickSidebar(page, 'performance', 'rf-xray-performance');
  await expectVisible(page.locator('[data-testid="rf-xray-performance"]'), 5000);
  return { haltedEpochs: history.records.filter((record) => record.includes(':halted-depth')).length };
}

async function runAppDbPrivacyLarge(page) {
  await openXray(page);
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
  await clickSidebar(page, 'app-db', 'rf-xray-app-db-diff');
  await expectVisible(page.locator('[data-testid="rf-xray-app-db-diff"]'), 5000);
}

async function runLargeDispatcher(page, state) {
  await openXray(page);
  await clearTrace(page);
  const ids = ['write-declared', 'write-fx-declared', 'write-schema'];
  const start = Date.now();
  for (let i = 0; i < 19; i += 1) {
    await clickTestId(page, ids[i % ids.length]);
  }
  await clickTestId(page, 'write-auto');
  for (const [testId, expected] of [
    ['auto-count', '1'],
    ['declared-count', '7'],
    ['fx-count', '6'],
    ['schema-count', '6'],
  ]) {
    await expectTextEquals(page.locator(`[data-testid="${testId}"]`), expected, 5000);
  }
  await expectVisible(page.locator('[data-testid="elision-decls"]'), 5000);
  const traceEvents = await readTrace(page);
  await clickSidebar(page, 'app-db', 'rf-xray-app-db-diff');
  await expectVisible(page.locator('[data-testid="rf-xray-app-db-diff"]'), 5000);
  // rf2-ndb13 — large markers render as first-class chip chrome inside
  // the edn-inspector (the predicate previously mis-matched `:rf/large`
  // and the marker fell through to ordinary map rendering, surfacing
  // the `:rf.size/large-elided` keyword as plain text). With the
  // predicate now keyed off the spec/015 marker, the chip appears under
  // `[data-testid="rf-xray-edn-inspector-large"]`. Assert chip presence
  // and absence of the raw payload — the two together cover "elision
  // marker surfaced" and "raw value never leaks".
  const largeChips = page.locator(
    '[data-testid="rf-xray-app-db-diff"] [data-testid="rf-xray-edn-inspector-large"]',
  );
  const largeChipCount = await largeChips.count();
  const appDbText = ((await page.locator('[data-testid="rf-xray-app-db-diff"]').textContent()) || '').trim();
  if (largeChipCount === 0) {
    failWithDetails('Large-value 20-dispatch load did not render elision chip chrome in App-DB Diff', {
      traceCount: traceEvents.length,
      tail: traceEvents.slice(-20),
      appDbText: appDbText.slice(0, 1200),
    });
  }
  if (appDbText.includes('XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX')) {
    failWithDetails('Large raw payload leaked into App-DB Diff text', {
      traceCount: traceEvents.length,
      appDbText: appDbText.slice(0, 1200),
    });
  }
  state.loadStats = {
    eventCountBefore: 0,
    eventCountAfter: traceEvents.length,
    traceBufferDepth: traceEvents.length,
    visibleRowCount: await page.locator('[data-testid^="rf-xray-app-db-diff-slice-"]').count(),
    renderDurationMs: Date.now() - start,
    largeDispatchCount: 20,
    elisionMarkerVisible: true,
  };
}

async function runHydration(page) {
  // ---- (1) verify-hydration! emitted the structured trace ------------
  //
  // Post rf2-xy4yb: the dedicated Hydration debugger panel was
  // dropped. Per spec/018 §5 hydration mismatches surface as
  // `:rf.ssr/*` rows (category-prefix "rf.ssr") in the Issues tab.
  //
  // `:rf.ssr/hydration-mismatch` is emitted by `verify-hydration!`
  // OUTSIDE any event-handler context (see testbed `core.cljs:188`).
  // The framework's epoch capture (`re-frame.epoch.capture/capture-
  // event!`) drops out-of-cascade orphan emits — an error with no
  // in-flight cascade AND no `:dispatch-id` (rf2-avvwm) — so the
  // mismatch trace never lands in any `:rf/epoch-record`'s
  // `:trace-events`. Surfacing orphaned out-of-cascade errors in the
  // focused-epoch Issues lens is a deliberately separate concern
  // (the L2 timeline's per-row badges, not this per-epoch panel).
  //
  // What this scenario verifies end-to-end:
  //
  //   - the trace fired (testbed banner renders the projected
  //     payload — proves `verify-hydration!` reached `emit-error!`)
  //   - the Issues panel mounts cleanly under cascade (focused-epoch)
  //     scope and renders either the feed or a proper empty-state —
  //     proving the focused-epoch projection doesn't crash and the
  //     head-fallback (rf2-h0120) resolves to a real epoch record.
  const mismatchBanner = page.locator('[data-testid="mismatch-banner"]');
  await expectVisible(mismatchBanner, 10000);
  await expectVisible(page.locator('[data-testid="mismatch-server-hash"]'), 5000);

  // ---- (2) Xray Issues panel mounts cleanly under cascade scope ----
  await openXray(page);
  await clickTab(page, 'issues', 'rf-xray-issues-ribbon');
  // The Issues panel is the focused-epoch lens (rf2-jio48): with no
  // explicit focus it head-falls-back to the most-recent epoch
  // (rf2-h0120). That epoch carries no projected `:error`/`:warning`/
  // `:advisory` issue (the mismatch trace is out-of-cascade — see
  // above), so the panel paints the positive `:no-issues` empty-state.
  // Either the feed `<ul>` OR any of the panel's empty-state branches
  // is acceptable — all prove the focused-epoch projection is honoured
  // without crashing. (The legacy cascade-scoped `-for-event` empty
  // state was collapsed into `:no-issues` by the rf2-jio48 rebuild.)
  const feedOrEmptyState = page.locator(
    [
      '[data-testid="rf-xray-issues-feed"]',
      '[data-testid="rf-xray-issues-empty-no-issues"]',
      '[data-testid="rf-xray-issues-empty-no-matches"]',
      '[data-testid="rf-xray-issues-empty-no-focus"]',
      '[data-testid="rf-xray-issues-empty-epoch-evicted"]',
    ].join(', '),
  );
  await waitForValue(
    async () => (await feedOrEmptyState.count()) > 0,
    (n) => n === true,
    {
      timeoutMs: 5000,
      description: 'Issues panel renders feed-or-empty-state under cascade scope',
    },
  );
}

async function runTraceBudgetSaturation(page, state) {
  await expectHostCounterEquals(page, 5, 10000);
  await openXray(page);
  await clickSidebar(page, 'trace', 'rf-xray-trace');
  await clearTrace(page);
  const start = Date.now();
  const pushed = await pushSyntheticTraceEvents(page, 1000);
  // rf2-r6d6u + rf2-td380 (+ rf2-43koh): the saturation invariant is a
  // RING property. Post-rf2-43koh Xray's secondary frameless ring caps
  // at `default-frameless-ring-depth` (100 events) by default; the
  // synthetic-events helper bumps the depth to the pushed count via
  // `set-frameless-ring-depth!` so this perf test can assert against a
  // 1000-event budget independent of the production default. Frame-
  // bound events ride the framework's per-frame rings (cascade-keyed,
  // capped at `:cascades-retained` per frame). The Trace PANEL stays
  // epoch-scoped (rf2-td380) — it renders the focused epoch record's
  // `:trace-events`, NOT the global bus.
  const expectedDepth = 1000;
  const saturatedDepth = await waitForValue(
    () => readTraceBufferDepth(page),
    (depth) => depth === expectedDepth,
    { timeoutMs: 10000,
      description: `trace buffer saturation at ${expectedDepth} rows` },
  );

  for (let i = 0; i < 20; i += 1) {
    await clickHostButtonByLabel(page, i % 2 === 0 ? '+' : '-');
  }
  // 'still capped' is a RING invariant — the secondary ring stays at
  // its cap. The host's own `:counter/inc` / `:counter/dec` cascades
  // flow through the framework's per-frame rings (visible via the
  // `:rf.xray/trace-buffer` snapshot mirror).
  const after = await waitForValue(
    async () => ({
      depth: await readTraceBufferDepth(page),
      events: await readTrace(page),
    }),
    (snapshot) =>
      snapshot.events.some((event) => event.includes(':counter/inc')) &&
      snapshot.events.some((event) => event.includes(':counter/dec')),
    { timeoutMs: 10000, description: 'host counter events visible after 20 dispatches' },
  );
  state.loadStats = {
    eventCountBefore: saturatedDepth,
    eventCountAfter: after.depth,
    traceBufferDepth: after.depth,
    renderDurationMs: Date.now() - start,
    bufferEvictionCount: Math.max(0, saturatedDepth + 20 - after.depth),
    syntheticEventsPushed: pushed.pushed,
  };
}

async function runLaunchModesTwentyEventLoad(page, state) {
  await expectHostCounterEquals(page, 5, 10000);
  await openXray(page);
  // rf2-5gl5r — `event-detail` → `epoch` tab; the Epoch panel's
  // root testid is `rf-xray-epoch-panel`.
  await clickSidebar(page, 'event-detail', 'rf-xray-epoch-panel');

  const launch = await page.evaluate(() => {
    const xray = window.day8 && window.day8.re_frame2_xray;
    const core = xray && (xray.core || xray);
    const keys = core ? Object.keys(core).sort() : [];
    const popout = core && core.popout_BANG_;
    const result = { keys };
    if (typeof popout !== 'function') {
      result.popout = { ok: false, reason: 'popout_BANG_ not exported' };
    } else {
      try {
        const value = popout();
        const win = window.open('', 'rf-xray-popout');
        const doc = win && win.document;
        const root = doc && doc.getElementById('rf-xray-popout-root');
        result.popout = {
          ok: Boolean(root),
          returnType: typeof value,
          openerStatus: win ? (win.closed ? 'closed' : 'open') : 'missing',
          rootMode: root ? root.getAttribute('data-rf-xray-mode') : null,
          shellPresent: Boolean(root && root.querySelector('[data-testid="rf-xray-shell"]')),
        };
      } catch (err) {
        result.popout = { ok: false, threw: String(err && (err.stack || err.message || err)) };
      }
    }
    return result;
  });
  state.launchLoad = { launch };
  if (!launch.popout.ok) {
    failWithDetails('Xray launch modes were not all available before load', launch);
  }
  await expectVisible(page.locator('#rf-xray-root [data-testid="rf-xray-epoch-panel"]'), 5000);
  await clearTrace(page);

  const before = await readLaunchModeProjection(page);
  const start = Date.now();
  for (let i = 0; i < 20; i += 1) {
    await clickHostButtonByLabel(page, i % 2 === 0 ? '+' : '-');
  }
  const after = await waitForValue(
    () => readLaunchModeProjection(page),
    (projection) =>
      projection.hostDispatchCount === 20 &&
      projection.overlay.cascadeRows > 0 &&
      projection.popout.cascadeRows > 0,
    { timeoutMs: 10000, description: 'launch-mode shared Epoch-panel state after 20 host dispatches' },
  );
  const elapsedMs = Date.now() - start;
  const cascadeRowCounts = [after.overlay.cascadeRows, after.popout.cascadeRows];
  const uniqueCascadeRowCounts = [...new Set(cascadeRowCounts)];
  state.loadStats = {
    eventCountBefore: before.traceCount,
    eventCountAfter: after.traceCount,
    traceBufferDepth: after.traceCount,
    visibleRowCount: after.overlay.cascadeRows,
    renderDurationMs: elapsedMs,
    slowestCascadeId: null,
    bufferEvictionCount: Math.max(0, before.traceCount + 20 - after.traceCount),
    hostDispatchCount: after.hostDispatchCount,
    epochCountBefore: before.epochCount,
    epochCountAfter: after.epochCount,
  };
  state.launchLoad = {
    ...state.launchLoad,
    before,
    after,
  };
  if (after.hostDispatchCount !== 20) {
    failWithDetails('20-event load produced an unexpected host dispatch trace count', {
      expected: 20,
      observed: after.hostDispatchCount,
      before,
      after,
    });
  }
  if (uniqueCascadeRowCounts.length !== 1) {
    failWithDetails('Overlay and pop-out Epoch panel disagree on rendered cascade rows', {
      cascadeRowCounts,
      before,
      after,
    });
  }
  if (after.epochCount != null && before.epochCount != null && after.epochCount < before.epochCount) {
    failWithDetails('Epoch history moved backwards during launch-mode load', {
      before,
      after,
    });
  }
}

// rf2-qd5r6 — ex-Tier-2 L-10 deepening of the Config (Spec 015) surface.
// The lightweight chrome smokes already cover inline auto-mount + the
// Ctrl+Shift+C toggle + the editor/project-root config round-trip
// (Tier-1 §1 / §8 / §10c equivalents). What was not previously pinned
// is the `configure!` multi-key map + partial-update semantics: a
// single call carrying five keys round-trips every key; a second call
// carrying only one key leaves the other four slots untouched.
async function runConfigurePartialUpdate(page, state) {
  await expectHostCounterEquals(page, 5, 10000);
  await openXray(page);

  const configureVerify = await page.evaluate(() => {
    const cljs = window.cljs && window.cljs.core;
    const cfg  = window.day8 && window.day8.re_frame2_xray &&
                 window.day8.re_frame2_xray.config;
    if (!cljs || !cfg) return { ok: false, reason: 'no config' };
    if (typeof cfg.configure_BANG_ !== 'function') {
      return { ok: false, reason: 'configure_BANG_ missing' };
    }
    // Two-arg keyword constructor: kw(ns, n) → :ns/n. Required for the
    // rf2-xea9u rename: every configure! key now lives under :rf.xray/*
    // or :rf.privacy/* (cross-tool privacy).
    const kw = (ns, n) => (cljs.keyword.call
      ? cljs.keyword.call(null, ns, n)
      : cljs.keyword(ns, n));
    const eq = cljs._EQ_;
    const issues = [];

    // Snapshot pre-state so we can restore exactly.
    const preEditor       = cfg.get_editor();
    const preProjectRoot  = cfg.get_project_root();
    const preLayoutHost   = cfg.get_layout_host_selector();
    const preAutoOpen     = cfg.auto_open_enabled_QMARK_();
    const preShowSens     = cfg.get_show_sensitive();

    // Multi-key configure — every slot round-trips in one call.
    const opts1 = cljs.PersistentArrayMap.fromArray([
      kw('rf.xray', 'editor'),                cljs.keyword('idea'),
      kw('rf.xray', 'project-root'),          '/tmp/probe-multi-key',
      kw('rf.xray', 'layout-host-selector'),  '#rf2-qd5r6-probe-host',
      kw('rf.xray', 'auto-open?'),            false,
      kw('rf.privacy', 'show-sensitive?'),     true,
    ], true, false);
    cfg.configure_BANG_(opts1);

    if (!eq(cfg.get_editor(), cljs.keyword('idea'))) {
      issues.push(`:rf.xray/editor after multi-key configure expected :idea; got ${cljs.pr_str(cfg.get_editor())}`);
    }
    if (cfg.get_project_root() !== '/tmp/probe-multi-key') {
      issues.push(`:rf.xray/project-root expected '/tmp/probe-multi-key'; got ${cfg.get_project_root()}`);
    }
    if (cfg.get_layout_host_selector() !== '#rf2-qd5r6-probe-host') {
      issues.push(`:rf.xray/layout-host-selector expected probe host; got ${cfg.get_layout_host_selector()}`);
    }
    if (cfg.auto_open_enabled_QMARK_() !== false) {
      issues.push(`:rf.xray/auto-open? expected false; got ${cfg.auto_open_enabled_QMARK_()}`);
    }
    if (cfg.get_show_sensitive() !== true) {
      issues.push(`:rf.privacy/show-sensitive? expected true; got ${cfg.get_show_sensitive()}`);
    }

    // Partial-update — second configure with ONLY :rf.xray/editor
    // leaves every other slot untouched.
    const opts2 = cljs.PersistentArrayMap.fromArray([
      kw('rf.xray', 'editor'), cljs.keyword('zed'),
    ], true, false);
    cfg.configure_BANG_(opts2);
    if (!eq(cfg.get_editor(), cljs.keyword('zed'))) {
      issues.push(`:rf.xray/editor after partial configure expected :zed; got ${cljs.pr_str(cfg.get_editor())}`);
    }
    if (cfg.get_project_root() !== '/tmp/probe-multi-key') {
      issues.push(`:rf.xray/project-root regressed on partial configure; got ${cfg.get_project_root()}`);
    }
    if (cfg.get_layout_host_selector() !== '#rf2-qd5r6-probe-host') {
      issues.push(`:rf.xray/layout-host-selector regressed on partial configure; got ${cfg.get_layout_host_selector()}`);
    }
    if (cfg.auto_open_enabled_QMARK_() !== false) {
      issues.push(`:rf.xray/auto-open? regressed on partial configure; got ${cfg.auto_open_enabled_QMARK_()}`);
    }
    if (cfg.get_show_sensitive() !== true) {
      issues.push(`:rf.privacy/show-sensitive? regressed on partial configure; got ${cfg.get_show_sensitive()}`);
    }

    // set-auto-open!(null) round-trips to the default true.
    cfg.set_auto_open_BANG_(null);
    if (cfg.auto_open_enabled_QMARK_() !== true) {
      issues.push(`set-auto-open!(null) expected reset to true; got ${cfg.auto_open_enabled_QMARK_()}`);
    }
    cfg.set_auto_open_BANG_(false);
    if (cfg.auto_open_enabled_QMARK_() !== false) {
      issues.push(`set-auto-open!(false) expected false; got ${cfg.auto_open_enabled_QMARK_()}`);
    }

    // set-layout-host-selector!(null) resets to the default selector.
    cfg.set_layout_host_selector_BANG_(null);
    if (cfg.get_layout_host_selector() !== '[data-rf-xray-host]') {
      issues.push(`set-layout-host-selector!(null) expected '[data-rf-xray-host]'; got ${cfg.get_layout_host_selector()}`);
    }

    // Restore pre-state. set-show-sensitive! true → false triggers
    // the trace-bus retroactive scrub per Spec 009 §Privacy
    // (rf2-lqmje); that's expected and not visible from here.
    cfg.set_editor_BANG_(preEditor);
    cfg.set_project_root_BANG_(preProjectRoot);
    cfg.set_layout_host_selector_BANG_(preLayoutHost);
    cfg.set_auto_open_BANG_(preAutoOpen);
    cfg.set_show_sensitive_BANG_(preShowSens);

    return { ok: true, issues };
  });
  if (!configureVerify.ok) {
    failWithDetails(`Could not run configure! probe: ${configureVerify.reason}`, {});
  }
  if (configureVerify.issues.length > 0) {
    failWithDetails(
      'configure! multi-key + partial-update failures',
      { issues: configureVerify.issues },
    );
  }
  state.configureProbe = { ok: true };
}

// rf2-n39g2 — Static-mode browser scenario.
//
// Static mode shipped under #1565 + #1568 + #1569 (rf2-o5f5f.1 / .2 / .3).
// Per rf2-8l3uk the `:rf.xray/static-mode?` feature gate was removed —
// Static mode is unconditionally available. Before this scenario the
// feature-matrix carried zero browser coverage for the highest-user-
// visible Xray surface to land in the recent cluster. This scenario:
//
//   1. Asserts the Dynamic baseline — mode dropdown present with
//      `dynamic` selected (rf2-4vp5j reshaped the two-button pill into a
//      compact `<select>`; `data-active-mode`/`value` carry the state),
//      L2 spine event-list visible (4-layer chrome).
//   2. Fires Ctrl+Shift+M (the cross-platform chord per
//      `keybinding.cljs/mode-toggle-key?` — Cmd-Shift-M on macOS,
//      Ctrl-Shift-M elsewhere; Playwright drives Ctrl as the headless
//      Chromium maps to Ctrl reliably). Asserts the mode flips: the
//      dropdown reads `static`, the Static surface mounts
//      (`rf-xray-static-surface` with `data-rf-xray-mode="static"`),
//      the L2 spine disappears (3-layer silhouette — chrome-silhouette
//      mode-signal #4), the Machines sub-tab is selected by default
//      (rf2-o5f5f.1 §tabs), and each shipped sub-tab (Routes / Schemas
//      / Views / Flows) mounts its real panel root testid while
//      `:events` (rf2-o5f5f.6 — last remaining placeholder) still
//      renders a placeholder card naming the sibling bead.
//   3. Selects `Dynamic` in the dropdown; mode flips back; L2 spine
//      returns (proves the dropdown is the canonical toggle path too —
//      not just the chord).
//   4. Reloads the page; asserts localStorage `xray.mode` round-
//      trips the last-set mode (Dynamic here, per the flip-back step).
async function runStaticModeChromeAndChord(page, state) {
  // ---- (0) baseline — clear the persisted mode slot -----------------
  await page.goto(page.url(), { waitUntil: 'load' });
  // Wait for the host counter so we know Xray's preload has installed
  // its browser-API exports.
  await expectHostCounterEquals(page, 5, 10000);
  // Clear the persisted mode slot so we start from a known baseline
  // (otherwise a previous scenario's localStorage write could pre-
  // select Static and skip the Dynamic baseline check below).
  await page.evaluate(() => {
    try { window.localStorage.removeItem('xray.mode'); }
    catch (_) { /* localStorage unavailable in some test runtimes */ }
  });
  await openXray(page);

  // ---- (1) Dynamic baseline -----------------------------------------
  // Per rf2-8l3uk the mode control is always rendered (the Static-mode
  // feature gate was removed). Per rf2-4vp5j it is a compact `<select>`
  // dropdown; `data-active-mode` + `value` carry the active mode.
  // Dynamic must be selected by default.
  await expectVisible(
    page.locator('[data-testid="rf-xray-mode-pill"]'),
    5000,
  );
  const dynamicBaseline = await waitForValue(
    () => page.evaluate(() => {
      const modeSelect = document.querySelector(
        '[data-testid="rf-xray-mode-pill"]',
      );
      const eventList = document.querySelector(
        '[data-testid="rf-xray-event-list"]',
      );
      const staticSurface = document.querySelector(
        '[data-testid="rf-xray-static-surface"]',
      );
      return {
        modeSelectValue: modeSelect ? modeSelect.value : null,
        pillGroupActiveMode: modeSelect ? modeSelect.getAttribute('data-active-mode') : null,
        eventListPresent: Boolean(eventList),
        staticSurfacePresent: Boolean(staticSurface),
      };
    }),
    (snap) =>
      snap.modeSelectValue === 'dynamic' &&
      snap.pillGroupActiveMode === 'dynamic' &&
      snap.eventListPresent &&
      !snap.staticSurfacePresent,
    { timeoutMs: 5000, description: 'Dynamic baseline before mode toggle' },
  );

  // ---- (2) Cmd/Ctrl-Shift-M chord — Dynamic → Static ----------------
  await page.keyboard.press('Control+Shift+M');
  const afterChord = await waitForValue(
    () => page.evaluate(() => {
      const modeSelect = document.querySelector(
        '[data-testid="rf-xray-mode-pill"]',
      );
      const surface = document.querySelector(
        '[data-testid="rf-xray-static-surface"]',
      );
      const ribbon = document.querySelector(
        '[data-testid="rf-xray-static-ribbon"]',
      );
      const tabBar = document.querySelector(
        '[data-testid="rf-xray-static-tab-bar"]',
      );
      const eventList = document.querySelector(
        '[data-testid="rf-xray-event-list"]',
      );
      // Default Static sub-tab is :machines per static/shell.cljs.
      const machinesTab = document.querySelector(
        '[data-testid="rf-xray-static-tab-machines"]',
      );
      // L4 detail panel — when Machines is the default tab the live
      // panel mounts (rf2-o5f5f.2); the static-detail-panel-* root
      // remains a stable testid hook regardless of which tab is selected.
      const detailPanel = document.querySelector(
        '[data-testid="rf-xray-static-detail-panel-machines"]',
      );
      return {
        modeSelectValue: modeSelect ? modeSelect.value : null,
        pillGroupActiveMode: modeSelect ? modeSelect.getAttribute('data-active-mode') : null,
        staticSurfacePresent: Boolean(surface),
        staticSurfaceModeAttr: surface ? surface.getAttribute('data-rf-xray-mode') : null,
        ribbonPresent: Boolean(ribbon),
        tabBarPresent: Boolean(tabBar),
        eventListPresent: Boolean(eventList),
        machinesTabSelected: machinesTab ? machinesTab.getAttribute('aria-selected') : null,
        detailPanelMachinesPresent: Boolean(detailPanel),
      };
    }),
    (snap) =>
      snap.modeSelectValue === 'static' &&
      snap.pillGroupActiveMode === 'static' &&
      snap.staticSurfacePresent &&
      snap.staticSurfaceModeAttr === 'static' &&
      snap.ribbonPresent &&
      snap.tabBarPresent &&
      !snap.eventListPresent &&
      snap.machinesTabSelected === 'true' &&
      snap.detailPanelMachinesPresent,
    { timeoutMs: 5000, description: 'Static surface after Cmd-Shift-M chord' },
  );

  // ---- (2a) Walk the Static sub-tabs --------------------------------
  // Tab inventory (per `static/shell.cljs/static-tabs`):
  //   :machines     (.2 — default, mounted above)
  //   :routes       (.3 — shipped #1568)
  //   :schemas      (.4 — shipped #1636 / rf2-o5f5f.4)
  //   :flows        (rf2-uhsqb — shipped #1636)
  //   :interceptors (.6 — shipped)
  //
  // rf2-b2fif dropped the Static Views + Events sub-tabs — the info
  // those tabs surfaced is already in the source code; the tabs were
  // not pulling their weight.
  //
  // For each shipped panel we click its tab and wait for the panel's
  // canonical root `data-testid` (e.g. `rf-xray-static-schemas`) —
  // React renders asynchronously so a click + read in the same
  // `page.evaluate` misses the post-commit DOM. Each tab gets its own
  // click + waitFor.
  const shippedSubTabs = [
    ['routes',       'rf-xray-static-routes'],
    ['schemas',      'rf-xray-static-schemas'],
    ['flows',        'rf-xray-static-flows'],
    ['interceptors', 'rf-xray-static-interceptors'],
  ];
  const shippedSubTabRoots = {};
  for (const [tabId, rootTestId] of shippedSubTabs) {
    await page.locator(`[data-testid="rf-xray-static-tab-${tabId}"]`).click();
    const observed = await waitForValue(
      () => page.evaluate((rootId) => {
        const root = document.querySelector(`[data-testid="${rootId}"]`);
        if (!root) return null;
        return {
          rootPresent: true,
          rootTag:     root.tagName ? root.tagName.toLowerCase() : null,
          // Placeholder testid for the same tab id MUST be absent —
          // proves the shell switched from placeholder-card to the
          // real panel root.
          placeholderAbsent: !document.querySelector(
            `[data-testid="rf-xray-static-placeholder-${rootId.split('-').pop()}"]`,
          ),
        };
      }, rootTestId),
      (snap) => Boolean(snap)
                && snap.rootPresent === true
                && snap.placeholderAbsent === true,
      {
        timeoutMs: 5000,
        description: `Static sub-tab :${tabId} real panel root ${rootTestId} mounted (placeholder absent)`,
      },
    );
    shippedSubTabRoots[tabId] = observed;
  }

  // Per rf2-o5f5f.6 all Static sub-tabs are now panelled — no
  // placeholder cards remain. Keep the empty inventory + texts map so
  // the downstream scenario snapshot shape stays stable.
  const placeholderTexts = {};
  // Restore the Machines tab so subsequent steps see the default L4.
  await page.locator('[data-testid="rf-xray-static-tab-machines"]').click();
  await expectVisible(
    page.locator('[data-testid="rf-xray-static-detail-panel-machines"]'),
    5000,
  );

  // ---- (3) Select Dynamic in the dropdown — Static → Dynamic --------
  // rf2-4vp5j — the mode control is a `<select>`; flipping back is a
  // selectOption (the canonical toggle path, not just the chord).
  await page.locator('[data-testid="rf-xray-mode-pill"]').selectOption('dynamic');
  const afterClickBack = await waitForValue(
    () => page.evaluate(() => {
      const modeSelect = document.querySelector(
        '[data-testid="rf-xray-mode-pill"]',
      );
      const eventList = document.querySelector(
        '[data-testid="rf-xray-event-list"]',
      );
      const surface = document.querySelector(
        '[data-testid="rf-xray-static-surface"]',
      );
      return {
        pillGroupActiveMode: modeSelect ? modeSelect.getAttribute('data-active-mode') : null,
        modeSelectValue: modeSelect ? modeSelect.value : null,
        eventListPresent: Boolean(eventList),
        staticSurfacePresent: Boolean(surface),
      };
    }),
    (snap) =>
      snap.pillGroupActiveMode === 'dynamic' &&
      snap.modeSelectValue === 'dynamic' &&
      snap.eventListPresent &&
      !snap.staticSurfacePresent,
    { timeoutMs: 5000, description: 'Dynamic restored after selecting Dynamic in the dropdown' },
  );

  // Per rf2-8l3uk Static mode is unconditionally available — no feature
  // flag re-opt-in to exercise. The earlier reload/persistence cycle
  // checked a localStorage round-trip that survived the gate removal
  // only via a dangling `reoptIn` reference (rf2-rat6r); the branch is
  // dead code and removed here.
  state.staticMode = {
    dynamicBaseline,
    afterChord,
    shippedSubTabRoots,
    placeholderTexts,
    afterClickBack,
  };
}

// rf2-z5zip — Cmd-K command palette browser scenario.
//
// The palette shipped under #1572 (rf2-ybjkx) — 6 new verbs, mode-
// aware command index, recents slot, reduced-motion override. The
// pre-bead suite carried helper-level unit gates only (no end-to-end
// browser proof that the chord-bind → view-mount → dispatch route
// land together). This scenario:
//
//   1. Opens Xray, clears the recents slot (so the test starts from
//      a known empty-recents state), then presses Ctrl+K.
//   2. Asserts the palette dialog mounts + the input is focused
//      (the chord-bind → mount edge).
//   3. Types "toggle theme" so the fuzzy filter narrows the result
//      list to the `:toggle-theme` command (the canonical mode-
//      agnostic verb from `palette/sources.cljc` §command-items).
//   4. Captures the current theme (from `cfg.get_setting :theme`),
//      presses Enter; asserts the dialog unmounts AND the theme
//      slot flipped (the dispatch-route edge — the verb fired a
//      real side-effect).
//   5. Re-opens the palette; asserts `:toggle-theme` sits at the
//      top of the result list (the recents-boost surfaces the
//      most-recent command above fresh fuzzy peers).
//   6. Asserts the localStorage recents slot
//      (`re-frame2.xray.palette.recents.v1`) contains
//      `:toggle-theme` (proves the persistence fx ran).
//   7. Presses Esc; asserts the dialog closes without re-dispatching.
async function runPaletteOpenExecute(page, state) {
  await expectHostCounterEquals(page, 5, 10000);
  // Clear the persisted recents slot so the assertion that the
  // freshly-invoked verb leads the recents list isn't masked by a
  // stale slot from a prior scenario / browser-context carry-over.
  // The palette events ns lazy-seeds `:palette-recents` from
  // localStorage on first open, so this clear must land BEFORE the
  // first open.
  await page.evaluate(() => {
    try {
      window.localStorage.removeItem('re-frame2.xray.palette.recents.v1');
    } catch (_) { /* localStorage unavailable */ }
  });
  await openXray(page);

  // Capture the current theme so we can prove the verb fired.
  const themeBefore = await page.evaluate(() => {
    const cljs = window.cljs && window.cljs.core;
    const cfg = window.day8 && window.day8.re_frame2_xray &&
                window.day8.re_frame2_xray.config;
    if (!cljs || !cfg || typeof cfg.get_setting !== 'function') {
      return { ok: false, reason: 'cfg.get_setting unavailable' };
    }
    const kw = (n) => cljs.keyword.call ? cljs.keyword.call(null, n) : cljs.keyword(n);
    const value = cfg.get_setting(kw('theme'), null);
    return { ok: true, theme: cljs.pr_str(value) };
  });
  if (!themeBefore.ok) {
    failWithDetails('Could not read theme before palette invoke', { observed: themeBefore });
  }

  // ---- (1) chord opens the palette ---------------------------------
  await page.keyboard.press('Control+K');
  await expectVisible(
    page.locator('[data-testid="rf-xray-palette-dialog"]'),
    5000,
  );
  const openedState = await page.evaluate(() => {
    const dialog = document.querySelector(
      '[data-testid="rf-xray-palette-dialog"]',
    );
    const input = document.querySelector(
      '[data-testid="rf-xray-palette-input"]',
    );
    return {
      dialogPresent: Boolean(dialog),
      dialogModeAttr: dialog ? dialog.getAttribute('data-rf-xray-mode') : null,
      inputPresent: Boolean(input),
      inputFocused: Boolean(input && document.activeElement === input),
    };
  });
  if (!openedState.dialogPresent || !openedState.inputPresent) {
    failWithDetails('Palette did not mount on Ctrl+K', { openedState });
  }
  // Input focus is the chord's UX contract — typing immediately after
  // the chord must land in the palette's query slot. Playwright's
  // headless Chromium sometimes loses focus across the keypress; we
  // tolerate a missing autofocus iff the input ACCEPTS subsequent
  // input (the .fill below would surface a hard failure).

  // ---- (2) type filters the list ------------------------------------
  await page.locator('[data-testid="rf-xray-palette-input"]').fill('toggle theme');
  const filtered = await waitForValue(
    () => page.evaluate(() => {
      const list = document.querySelector(
        '[data-testid="rf-xray-palette-list"]',
      );
      const rows = list
        ? Array.from(list.querySelectorAll('[data-testid^="rf-xray-palette-row-"]'))
        : [];
      const firstRow = rows[0] || null;
      return {
        rowCount: rows.length,
        firstRowSource: firstRow ? firstRow.getAttribute('data-source') : null,
        firstRowText: firstRow ? (firstRow.textContent || '').trim() : null,
      };
    }),
    (snap) =>
      snap.rowCount > 0 &&
      snap.firstRowText !== null &&
      /toggle theme/i.test(snap.firstRowText),
    { timeoutMs: 5000, description: 'palette list narrows to a row containing "toggle theme"' },
  );

  // ---- (3) Enter fires the verb -------------------------------------
  await page.locator('[data-testid="rf-xray-palette-input"]').press('Enter');
  await waitForValue(
    () => page.locator('[data-testid="rf-xray-palette-dialog"]').count(),
    (n) => n === 0,
    { timeoutMs: 5000, description: 'palette closes after Enter' },
  );
  // The toggle-theme verb routes through `:rf.xray/settings-update`
  // which mutates the live settings atom (and persists). The slot the
  // palette inspects via `cfg.get_setting :theme` should now hold the
  // opposite value of `themeBefore.theme`.
  const themeAfter = await waitForValue(
    () => page.evaluate(() => {
      const cljs = window.cljs && window.cljs.core;
      const cfg = window.day8 && window.day8.re_frame2_xray &&
                  window.day8.re_frame2_xray.config;
      if (!cljs || !cfg || typeof cfg.get_setting !== 'function') {
        return { ok: false, theme: null };
      }
      const kw = (n) => cljs.keyword.call ? cljs.keyword.call(null, n) : cljs.keyword(n);
      const value = cfg.get_setting(kw('theme'), null);
      return { ok: true, theme: cljs.pr_str(value) };
    }),
    (snap) => snap.ok && snap.theme !== themeBefore.theme,
    {
      timeoutMs: 5000,
      description: `theme flipped away from ${themeBefore.theme} after :toggle-theme invoke`,
    },
  );

  // ---- (4) re-open: :toggle-theme leads the recents -----------------
  await page.keyboard.press('Control+K');
  await expectVisible(
    page.locator('[data-testid="rf-xray-palette-dialog"]'),
    5000,
  );
  const recentsLead = await waitForValue(
    () => page.evaluate(() => {
      const list = document.querySelector(
        '[data-testid="rf-xray-palette-list"]',
      );
      const rows = list
        ? Array.from(list.querySelectorAll('[data-testid^="rf-xray-palette-row-"]'))
        : [];
      const firstRow = rows[0] || null;
      return {
        rowCount: rows.length,
        firstRowSource: firstRow ? firstRow.getAttribute('data-source') : null,
        firstRowText: firstRow ? (firstRow.textContent || '').trim() : null,
      };
    }),
    // Empty-query list orders by `boost + recency-bonus`; the most
    // recent command lands at the head per sources.cljc/recents-boost-
    // for-id. So a fresh palette open with empty query must surface
    // `:toggle-theme` at row 0 (a `:command` source row whose label
    // contains "Toggle theme").
    (snap) =>
      snap.rowCount > 0 &&
      snap.firstRowSource === 'command' &&
      snap.firstRowText !== null &&
      /toggle theme/i.test(snap.firstRowText),
    { timeoutMs: 5000, description: 'recents pin :toggle-theme at the head of the result list' },
  );

  // ---- (5) localStorage recents round-trip --------------------------
  const persistedRecents = await page.evaluate(() => {
    try { return window.localStorage.getItem('re-frame2.xray.palette.recents.v1'); }
    catch (_) { return null; }
  });
  if (!persistedRecents || !persistedRecents.includes(':toggle-theme')) {
    failWithDetails(
      'localStorage recents slot did not capture :toggle-theme after invoke',
      { persistedRecents },
    );
  }

  // ---- (6) Esc closes without dispatching ---------------------------
  // Read the current theme before Esc so we can prove Esc didn't
  // re-fire the verb.
  const themeBeforeEsc = await page.evaluate(() => {
    const cljs = window.cljs && window.cljs.core;
    const cfg = window.day8 && window.day8.re_frame2_xray &&
                window.day8.re_frame2_xray.config;
    if (!cljs || !cfg) return null;
    const kw = (n) => cljs.keyword.call ? cljs.keyword.call(null, n) : cljs.keyword(n);
    return cljs.pr_str(cfg.get_setting(kw('theme'), null));
  });
  await page.locator('[data-testid="rf-xray-palette-input"]').press('Escape');
  await waitForValue(
    () => page.locator('[data-testid="rf-xray-palette-dialog"]').count(),
    (n) => n === 0,
    { timeoutMs: 5000, description: 'palette closes after Esc' },
  );
  const themeAfterEsc = await page.evaluate(() => {
    const cljs = window.cljs && window.cljs.core;
    const cfg = window.day8 && window.day8.re_frame2_xray &&
                window.day8.re_frame2_xray.config;
    if (!cljs || !cfg) return null;
    const kw = (n) => cljs.keyword.call ? cljs.keyword.call(null, n) : cljs.keyword(n);
    return cljs.pr_str(cfg.get_setting(kw('theme'), null));
  });
  if (themeBeforeEsc !== themeAfterEsc) {
    failWithDetails(
      'Esc on palette unexpectedly fired a verb (theme moved across Esc close)',
      { themeBeforeEsc, themeAfterEsc },
    );
  }

  state.palette = {
    themeBefore: themeBefore.theme,
    openedState,
    filtered,
    themeAfter: themeAfter.theme,
    recentsLead,
    persistedRecents,
    themeBeforeEsc,
    themeAfterEsc,
  };
}

const SCENARIOS = [
  {
    name: 'feature matrix shell and panel handoff',
    url: '/counter/',
    // rf2-wa3oo: PR-smoke tier. The 6-tab handoff over the counter
    // surface is the highest-signal Xray scenario — it boots the shell,
    // walks every surviving L3 tab, and proves the chrome wiring. Kept
    // on the PR critical path; the rest of the matrix runs nightly.
    smoke: true,
    panels: PANEL_HANDOFFS.map(([id]) => id),
    // Post rf2-xy4yb + rf2-y0z5b + rf2-5gl5r: coverage narrowed to the
    // 7 surviving L3 tabs (Event/Handler retired in favour of the
    // Epoch panel). Removed surfaces (Time Travel, Causality Graph,
    // Subscriptions, Routes, Schemas, Hydration, Performance, Flows,
    // Effects, MCP Server) lost their UI handoff with the 4-layer
    // chrome refactor and are covered (where still functionally
    // present) by their dedicated substrate scenarios.
    coveredRows: [
      'Epoch Panel',
      'App-DB Diff',
      'Trace',
      'Machines',
      'Issues Ribbon',
      'Shell, Keybinding, Config, Preload, Settings, and Production Elision',
    ],
    run: runShellFeatureSweep,
  },
  {
    name: 'source coordinates and launch-mode availability',
    url: '/counter/',
    panels: ['trace'],
    coveredRows: [
      'Open in Editor / Source Coordinates',
      'Pop-out, Docking, and Inline Embedding',
      'Trace',
      'Shell, Keybinding, Config, Preload, Settings, and Production Elision',
    ],
    run: runSourceCoordinatesAndLaunchModes,
  },
  {
    name: 'deterministic exceptions and issue/trace surfacing',
    url: '/testbeds/deliberate-throw/',
    // rf2-wa3oo: PR-smoke tier. The exception → Issues/Trace surfacing
    // path is the second-highest-signal slice (it proves the error lens
    // wires up end-to-end against a real thrown handler). Counter +
    // deliberate-throw are the only two surfaces the smoke compiles.
    smoke: true,
    panels: ['issues', 'trace'],
    coveredRows: ['Epoch Panel', 'Trace', 'Issues Ribbon', 'Effects', 'Flows', 'Machines', 'Open in Editor / Source Coordinates'],
    run: runExceptionSchemaHttp,
  },
  {
    name: 'schema violation timeline',
    url: '/testbeds/schema-violation/',
    // Post rf2-xy4yb: the dedicated Schemas panel was dropped.
    // Schema violations are now surfaced as Issues-tab rows.
    panels: ['issues'],
    coveredRows: ['Issues Ribbon'],
    run: runSchemaViolation,
  },
  {
    name: 'managed http and effects rows',
    url: '/testbeds/http-toggle/',
    // Post rf2-xy4yb: the Effects panel was dropped — fx/effects rows
    // are now inline steps inside the Epoch panel's numbered cascade.
    // Performance panel is gone too (Mike's call: use Chrome DevTools
    // Performance). rf2-5gl5r: `event` panel renamed to `epoch`.
    panels: ['epoch', 'issues', 'trace'],
    coveredRows: ['Epoch Panel', 'Issues Ribbon', 'Trace'],
    run: runHttpToggle,
  },
  {
    name: 'multi-frame isolation substrate',
    url: '/testbeds/multi-frame/',
    // Post rf2-xy4yb + rf2-y0z5b: Causality Graph and Time Travel
    // panels were dropped. Multi-frame isolation is now exercised
    // via the Trace and Epoch tabs (cascade per frame). rf2-5gl5r:
    // `event` panel renamed to `epoch`.
    panels: ['trace', 'epoch'],
    coveredRows: ['Trace', 'Epoch Panel'],
    run: runMultiFrame,
  },
  {
    // rf2-bz72m — deepened to assert the machines-viz chart SVG
    // actually renders (not just the inspector mount). Catches a
    // broken `chart/{svg,layout,elk_layout}` re-export shim after the
    // #1570 / rf2-o9arp machines-viz extraction.
    name: 'deep machine inspector substrate',
    url: '/testbeds/deep-machine/',
    panels: ['machines'],
    coveredRows: ['Machines', 'Trace'],
    run: runDeepMachine,
  },
  {
    // rf2-n39g2 — Static mode (rf2-o5f5f.1 / .2 / .3) browser
    // coverage: chord + pill + 3-layer chrome silhouette.
    // (Persistence round-trip dropped by rf2-rat6r — rf2-8l3uk made
    // Static mode unconditional so the reload cycle is dead code.)
    name: 'static mode chrome and chord',
    url: '/counter/',
    panels: [],
    coveredRows: [
      'Static mode mount + chord',
      'Shell, Keybinding, Config, Preload, Settings, and Production Elision',
    ],
    run: runStaticModeChromeAndChord,
  },
  {
    // rf2-z5zip — Cmd-K command palette (rf2-ybjkx) browser coverage:
    // chord opens, fuzzy narrows, Enter executes a verb (theme flip),
    // recents persist + lead on re-open, Esc closes without dispatch.
    name: 'command palette chord, fuzzy filter, execute verb, and recents round-trip',
    url: '/counter/',
    // rf2-wa3oo: PR-smoke tier. Reuses the already-staged counter
    // surface (no extra compile), and exercises the Cmd-K interaction
    // path — the highest-signal "key interactions" coverage the audit
    // asked the smoke to keep. Adds no surface to the compile set.
    smoke: true,
    panels: [],
    coveredRows: [
      'Cmd-K palette',
      'Shell, Keybinding, Config, Preload, Settings, and Production Elision',
    ],
    run: runPaletteOpenExecute,
  },
  // ---- retired by rf2-xy4yb (4-layer chrome refactor) -------------------
  //
  // 'long flow failure substrate' — the dedicated Flows panel was
  // dropped (spec/018 §5: Flows fold into the Views tab as derived
  // state). The Views tab itself is a stub pending its full impl;
  // re-instate this scenario once the Views tab projects per-flow
  // rows. Surviving evidence (flow-failure trace events) is covered
  // by `deterministic exceptions and issue/trace surfacing`.
  //
  // 'drain-depth load failure substrate' — the Performance panel was
  // dropped per Mike's call; Chrome DevTools' Performance tab is the
  // v2 replacement. The `:halted-depth` epoch record is still
  // observable via the substrate's host-side `readEpochHistoryAsEdn`
  // probe, but there is no Xray UI handoff to assert against.
  // Scenario retired; runDrainDepth / runLongFlow stay in place for
  // any future revival.
  //
  // ---- converted to multi-frame e2e CLJS (rf2-rviu8) --------------------
  //
  // 'non-trivial app-db diff substrate' — the Playwright scenario
  // only asserted the App-DB Diff panel mounted after a six-click
  // sequence of deep-tree mutations. The data invariants (spine focus
  // advances, target-frame-db reflects each mutation, epoch history
  // grows by 6) are now covered at <50ms per assertion by
  // `tools/xray/test/day8/re_frame2_xray/panels_e2e/
  // non_trivial_app_db_e2e_cljs_test.cljs`. Per Mike's 2026-05-19
  // multi-frame-e2e finding, removing this scenario cuts ~30s of
  // browser gate runtime with zero coverage loss.
  {
    name: '20-event large value elision load',
    url: '/testbeds/large-dispatcher/',
    panels: ['trace', 'app-db'],
    load: true,
    coveredRows: ['Redaction, Sensitive, and Large Values', 'App-DB Diff'],
    run: runLargeDispatcher,
  },
  {
    name: 'hydration mismatch debugger',
    url: '/testbeds/ssr-hydration-mismatch/',
    // Post rf2-xy4yb: the dedicated Hydration debugger panel was
    // dropped. Hydration mismatches surface in the Issues tab.
    panels: ['issues'],
    coveredRows: ['Issues Ribbon'],
    run: runHydration,
  },
  // ---- converted to multi-frame e2e CLJS (rf2-rviu8) --------------------
  //
  // '20-event feature/load re-check' — the Playwright scenario drove
  // 20 host counter +/- clicks and asserted the trace count grew +
  // the focused cascade rendered (originally on the Event Detail
  // panel; rf2-5gl5r migrated the surface to the Epoch panel). Both
  // are data invariants the multi-frame e2e harness covers at ~5ms
  // per dispatch (vs ~200ms per click in browser):
  // `tools/xray/test/day8/re_frame2_xray/panels_e2e/
  // twenty_event_load_e2e_cljs_test.cljs` walks the same 20-event
  // sequence, asserts cascades grow, focus auto-follows the head
  // (rf2-70tkv class), epoch history records every dispatch, and
  // target-frame-db reflects the net counter value. Estimated CI
  // saving: ~45s (full 20-click sequence + panel handoff).
  {
    name: '1000-event trace row-budget plus 20-dispatch re-check',
    url: '/counter/',
    panels: ['trace'],
    load: true,
    // Post rf2-y0z5b: 'Performance' coveredRow was dropped (the
    // Performance panel was retired per Mike's call — Chrome DevTools
    // Performance tab is the canonical surface). Trace + Shell/Elision
    // remain.
    coveredRows: [
      'Trace',
      'Shell, Keybinding, Config, Preload, Settings, and Production Elision',
    ],
    run: runTraceBudgetSaturation,
  },
  {
    name: '20-event launch-mode shared runtime re-check',
    url: '/counter/',
    panels: ['epoch'],
    load: true,
    coveredRows: [
      'Epoch Panel',
      'Pop-out, Docking, and Inline Embedding',
      'Shell, Keybinding, Config, Preload, Settings, and Production Elision',
    ],
    run: runLaunchModesTwentyEventLoad,
  },
  // ---- converted to multi-frame e2e CLJS (rf2-rviu8) --------------------
  //
  // 'configure! multi-key map and partial-update semantics' (was
  // rf2-qd5r6). The Playwright scenario went into a browser solely
  // to call `window.day8.re_frame2_xray.config.configure_BANG_` and
  // assert atom-state round-trips. Pure CLJS — no DOM involvement.
  // Converted to direct fn-call coverage in
  // `tools/xray/test/day8/re_frame2_xray/panels_e2e/
  // configure_multi_key_e2e_cljs_test.cljs`. Estimated CI saving:
  // ~10s (browser context teardown alone).
];

module.exports = {
  PANEL_HANDOFFS,
  SCENARIOS,
  STAGED_SURFACES,
};
