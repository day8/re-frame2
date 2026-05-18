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
// sidebar + bottom rail is dead. The L3 tab bar exposes 6 tabs only:
// event / app-db / views / trace / machines / issues (spec/018 §5).
// Panels without a tab no longer have a UI handoff and are dropped
// from the shell-sweep scenario.
const PANEL_HANDOFFS = [
  ['event', 'rf-causa-event-detail'],
  ['app-db', 'rf-causa-app-db-diff'],
  // The :views tab routes to the full Views panel per spec/012-Views.md
  // (rf2-21ob3 replaced the legacy Subscriptions panel). The Views
  // panel renders its canonical `rf-causa-views` root testid.
  ['views', 'rf-causa-views'],
  ['trace', 'rf-causa-trace'],
  ['machines', 'rf-causa-machine-inspector'],
  ['issues', 'rf-causa-issues-ribbon'],
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

// Post rf2-xy4yb: the L3 tab bar replaces the legacy sidebar. Tabs
// expose `data-testid="rf-causa-tab-<id>"` for the 6 surviving panels
// (event / app-db / views / trace / machines / issues — spec/018 §5).
async function clickTab(page, id, canvasTestId) {
  await page.locator(`[data-testid="rf-causa-tab-${id}"]`).click();
  await expectVisible(page.locator(`[data-testid="${canvasTestId}"]`), 5000);
}

// Legacy panel ids → new L3 tab ids. Panels removed by the 4-layer
// refactor (time-travel, causality, subs, fx, flows, routes,
// performance, schemas, hydration, mcp-server) have no UI handoff
// — callers that target them must be updated separately.
const LEGACY_PANEL_TO_TAB = {
  'event-detail': 'event',
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
    const root = document.getElementById('rf-causa-root');
    const selectors = {
      trace: '[data-testid^="rf-causa-trace-row-"] button[data-testid$="-source-coord"]',
      issues: '[data-testid^="rf-causa-issues-row-"] button[data-testid$="-source"]',
      hydration: '[data-testid="rf-causa-hydration-source-coord"] button',
    };
    if (!root) return { clicked: false, reason: 'Causa root missing', candidates: [] };
    const selector = selectors[targetPanel] || 'button[data-testid*="source"]';
    const buttons = Array.from(root.querySelectorAll(selector));
    const candidates = buttons.map((button) => {
      const rect = button.getBoundingClientRect();
      return {
        testId: button.getAttribute('data-testid'),
        text: (button.textContent || '').trim(),
        visible: rect.width > 0 && rect.height > 0,
      };
    });
    const target = buttons.find((button) => {
      const text = (button.textContent || '').trim();
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
    const sourceCoord = (target.textContent || '').trim();
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
  // `:rf.causa/open-in-editor` under the `:rf/causa` frame, which then
  // fires `:rf.editor/open` as an fx (also `:rf/causa`-framed). The
  // trace-bus ingest filter (`causa-internal-event?`) correctly drops
  // those self-emitted events before they reach Causa's buffer — they
  // are Causa machinery, not host activity. The bridge round-trip
  // therefore CANNOT be verified by reading Causa's trace feed
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
  await openCausa(page);
  const defaultInline = await page.evaluate(() => {
    const root = document.getElementById('rf-causa-root');
    const host = document.querySelector('[data-rf-causa-host]');
    const shell = document.querySelector('[data-testid="rf-causa-shell"]');
    const plus = Array.from(document.querySelectorAll('button'))
      .filter((button) => !button.closest('#rf-causa-root'))
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
      rootMode: root ? root.getAttribute('data-rf-causa-mode') : null,
      shellMode: shell ? shell.getAttribute('data-rf-causa-mode') : null,
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
        inCausa: Boolean(top.closest('#rf-causa-root')),
      } : null,
    };
  });
  if (!defaultInline.shell || defaultInline.shell.width < 200) {
    failWithDetails('Causa inline geometry was not measurable', { mode: 'inline', observed: defaultInline });
  }
  if (defaultInline.rootMode !== 'inline' || defaultInline.shellMode !== 'inline' || !defaultInline.rootParentIsHost) {
    failWithDetails('Causa did not auto-mount into the layout host', { mode: 'inline', observed: defaultInline });
  }
  if (defaultInline.bodyPaddingLeft || defaultInline.bodyPaddingRight) {
    failWithDetails('Causa inline default used body-padding layout tricks', { mode: 'inline', observed: defaultInline });
  }
  if (!defaultInline.hostPlus) {
    failWithDetails('Host app + button missing while Causa is open', { mode: 'inline', observed: defaultInline });
  }
  if (defaultInline.hostPlus.right > defaultInline.shell.left) {
    failWithDetails('Host app controls are not laid out to the left of Causa', { mode: 'inline', observed: defaultInline });
  }
  if (defaultInline.topAtHostPlus && defaultInline.topAtHostPlus.inCausa) {
    failWithDetails('Causa chrome is topmost over the host-app + button', { mode: 'inline', observed: defaultInline });
  }
  await page.mouse.click(defaultInline.hostPlus.centerX, defaultInline.hostPlus.centerY);
  await expectHostCounterEquals(page, countBefore + 1, 5000);

  const popout = await page.evaluate(() => {
    const causa = window.day8 && window.day8.re_frame2_causa;
    const core = causa && (causa.core || causa);
    const keys = core ? Object.keys(core).sort() : [];
    const fn = core && core.popout_BANG_;
    if (typeof fn !== 'function') {
      return { available: false, implemented: false, reason: 'Causa popout_BANG_ browser export not available', keys };
    }
    try {
      const beforeUrl = location.href;
      const value = fn();
      const popoutWindow = window.open('', 'rf-causa-popout');
      const doc = popoutWindow && popoutWindow.document;
      const root = doc && doc.getElementById('rf-causa-popout-root');
      return {
        available: true,
        implemented: Boolean(root),
        beforeUrl,
        afterUrl: location.href,
        returnType: typeof value,
        rootPresent: Boolean(root),
        rootMode: root ? root.getAttribute('data-rf-causa-mode') : null,
        shellPresent: Boolean(doc && doc.querySelector('[data-testid="rf-causa-shell"]')),
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
      failWithDetails('Causa launch mode is not implemented', { mode, observed: record });
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

async function setCausaTargetFrame(page, frame) {
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
      keyword(':rf.causa/set-target-frame'),
      keyword(targetFrame),
    ], true);
    const opts = cljs.hash_map(keyword(':frame'), keyword(':rf/causa'));
    if (dispatch.cljs$core$IFn$_invoke$arity$2) {
      dispatch.cljs$core$IFn$_invoke$arity$2(event, opts);
    } else {
      dispatch(event, opts);
    }
    return { ok: true, targetFrame };
  }, frame);
  if (!result.ok) {
    failWithDetails('Could not set Causa target frame', { frame, observed: result });
  }
}

async function setCausaTraceFilter(page, axis, value) {
  const result = await page.evaluate(({ axisName, valueName }) => {
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
      keyword(':rf.causa/set-trace-filter'),
      keyword(axisName),
      keyword(valueName),
    ], true);
    const opts = cljs.hash_map(keyword(':frame'), keyword(':rf/causa'));
    if (dispatch.cljs$core$IFn$_invoke$arity$2) {
      dispatch.cljs$core$IFn$_invoke$arity$2(event, opts);
    } else {
      dispatch(event, opts);
    }
    return { ok: true, axis: axisName, value: valueName };
  }, { axisName: axis, valueName: value });
  if (!result.ok) {
    failWithDetails('Could not set Causa trace filter', { axis, value, observed: result });
  }
}

/**
 * Find the `:dispatch-id` of the bus trace event matching the given
 * (`frame`, `eventId`) pair (an `:event/dispatched` record) and
 * dispatch `:rf.causa/focus-cascade` to focus that cascade.
 *
 * Replaces the old `clickTraceRowByFrame` helper: post rf2-ycoct the
 * Trace DOM is cascade-scoped and only renders rows for the currently
 * focused cascade, so scanning trace rows to "find and click" a
 * sibling cascade no longer works. The bus buffer is the canonical,
 * unscoped source of (frame, event) → dispatch-id, and `:rf.causa/
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
      window.day8.re_frame2_causa &&
      window.day8.re_frame2_causa.trace_bus;
    const dispatch = rf && (rf.dispatch_STAR_ ||
      (window.re_frame.router && window.re_frame.router.dispatch_BANG_));
    if (!cljs || !bus || typeof bus.buffer !== 'function' ||
        typeof dispatch !== 'function') {
      return {
        ok: false,
        reason: 'cljs.core / trace_bus.buffer / re_frame dispatch unavailable',
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
    const kEvent      = keyword(':event');
    const kTags       = keyword(':tags');
    const kOperation  = keyword(':operation');
    const kDispatchId = keyword(':dispatch-id');
    const opDispatched   = keyword(':event/dispatched');
    const targetFrameKw  = keyword(targetFrame);
    const targetEventKw  = keyword(targetEventId);
    // Walk bus buffer; first match wins (events are append-ordered so
    // this is the originating `:event/dispatched` record). The raw
    // framework trace puts the dispatched event vector under
    // `:tags :event`; the event-id is `(first event-vector)`. We do
    // NOT read `:tags :event-id` — Causa's projection materialises
    // that field but the trace-bus's stored events do not carry it.
    let s = cljs.seq(bus.buffer());
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
        reason: `No bus :event/dispatched record matched frame=${targetFrame} event-id=${targetEventId}`,
        candidates: candidates.slice(0, 20),
      };
    }
    const event = cljs.PersistentVector.fromArray([
      keyword(':rf.causa/focus-cascade'),
      match.dispatchId,
      match.frame,
    ], true);
    const opts = cljs.hash_map(keyword(':frame'), keyword(':rf/causa'));
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

async function readTraceCounts(page) {
  const text = ((await page.locator('[data-testid="rf-causa-trace-counts"]').textContent()) || '').trim();
  const match = /(\d+)\s*\/\s*(\d+)\s+in view/.exec(text);
  if (!match) {
    throw new Error(`Could not parse trace counts: ${JSON.stringify(text)}`);
  }
  return { rendered: Number(match[1]), total: Number(match[2]), text };
}

async function readTraceDomBudget(page) {
  return page.evaluate(() => {
    const root = document.getElementById('rf-causa-root');
    const feed = root && root.querySelector('[data-testid="rf-causa-trace-feed"]');
    const overflow = root && root.querySelector('[data-testid="rf-causa-trace-overflow-indicator"]');
    return {
      rootPresent: Boolean(root),
      feedPresent: Boolean(feed),
      rowCount: root ? root.querySelectorAll('li[data-testid^="rf-causa-trace-row-"]').length : 0,
      overflowPresent: Boolean(overflow),
      overflowText: overflow ? (overflow.textContent || '').trim() : null,
    };
  });
}

async function pushSyntheticTraceEvents(page, count) {
  const result = await page.evaluate((eventCount) => {
    const cljs = window.cljs && window.cljs.core;
    const bus = window.day8 &&
      window.day8.re_frame2_causa &&
      window.day8.re_frame2_causa.trace_bus;
    if (!cljs || !bus || typeof bus.collect_trace_BANG_ !== 'function') {
      return {
        ok: false,
        reason: 'cljs.core or day8.re_frame2_causa.trace_bus.collect_trace_BANG_ unavailable',
        busKeys: bus ? Object.keys(bus).sort().slice(0, 60) : [],
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
        keyword(':event-id'), keyword(':causa.synthetic/load'),
        keyword(':event'), cljs.PersistentVector.fromArray([keyword(':causa.synthetic/load'), i], true),
        keyword(':dispatch-id'), sharedDispatchId,
        keyword(':origin'), keyword(':app'),
        keyword(':source'), keyword(':synthetic'),
      );
      const ev = cljs.hash_map(
        keyword(':id'), eventId,
        keyword(':time'), now + i,
        keyword(':operation'), keyword(':event/dispatched'),
        keyword(':op-type'), keyword(':info'),
        keyword(':source'), keyword(':synthetic'),
        keyword(':tags'), tags,
      );
      bus.collect_trace_BANG_(ev);
    }
    return {
      ok: true,
      pushed: eventCount,
      depth: typeof bus.current_depth === 'function' ? bus.current_depth() : null,
      buffered: typeof bus.buffer === 'function' ? cljs.count(bus.buffer()) : null,
    };
  }, count);
  if (!result.ok) {
    failWithDetails('Could not push synthetic Causa trace events', result);
  }
  return result;
}

async function readLaunchModeProjection(page) {
  return page.evaluate(() => {
    const cljs = window.cljs && window.cljs.core;
    const rf = window.re_frame && window.re_frame.core;
    const bus = window.day8 &&
      window.day8.re_frame2_causa &&
      window.day8.re_frame2_causa.trace_bus;
    function text(root, selector) {
      const el = root && root.querySelector(selector);
      return el ? (el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 240) : null;
    }
    function count(root, selector) {
      return root ? root.querySelectorAll(selector).length : 0;
    }
    function traceEvents() {
      if (!cljs || !bus || typeof bus.buffer !== 'function') {
        return { ok: false, reason: 'trace bus unavailable', events: [] };
      }
      const events = [];
      let s = cljs.seq(bus.buffer());
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
      const shell = root && root.querySelector('[data-testid="rf-causa-shell"]');
      const active = root
        ? Array.from(root.querySelectorAll('[data-testid^="rf-causa-sidebar-item-"]'))
          .find((el) => (el.textContent || '').includes('◉'))
        : null;
      const cascade = root && root.querySelector('[data-testid="rf-causa-event-detail-cascade"]');
      return {
        present: Boolean(root),
        rootMode: root ? root.getAttribute('data-rf-causa-mode') : null,
        shellPresent: Boolean(shell),
        shellMode: shell ? shell.getAttribute('data-rf-causa-mode') : null,
        activePanel: active ? active.getAttribute('data-testid') : null,
        selectedDispatchId: cascade ? cascade.getAttribute('data-dispatch-id') : null,
        selectedFrame: cascade ? cascade.getAttribute('data-frame') : null,
        cascadeText: text(root, '[data-testid="rf-causa-event-detail-cascade"]'),
        // Per rf2-639lc the L4 Event panel default-focuses the head
        // cascade on mount — the cascade-detail container is the
        // primary surface. The legacy `rf-causa-cascade-row-*` list
        // only renders in the empty-state branch (no routable
        // cascades). `cascadeRows` is 1 when cascade-detail is
        // rendered, 0 when the empty container or orphaned branch is.
        cascadeRows: count(root, '[data-testid="rf-causa-event-detail-cascade"]'),
        traceRows: count(root, '[data-testid^="rf-causa-trace-row-"]'),
        traceCountsText: text(root, '[data-testid="rf-causa-trace-counts"]'),
      };
    }
    const trace = traceEvents();
    const hostDispatches = trace.events.filter((event) =>
      event.includes(':event/dispatched') &&
      (event.includes(':counter/inc') || event.includes(':counter/dec')));
    const popoutWin = window.open('', 'rf-causa-popout');
    const popoutDoc = popoutWin && popoutWin.document;
    const popoutRoot = popoutDoc && popoutDoc.getElementById('rf-causa-popout-root');
    return {
      url: location.href,
      traceReadError: trace.ok ? null : trace.reason,
      traceCount: trace.events.length,
      hostDispatchCount: hostDispatches.length,
      hostDispatchTail: hostDispatches.slice(-5),
      epochCount: epochCount(),
      overlay: shellProjection(document.getElementById('rf-causa-root')),
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
        .find((el) => !el.closest('#rf-causa-root'));
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
  await openCausa(page);

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

  await clickTab(page, 'event', 'rf-causa-event-detail');
  // Per rf2-639lc the L4 Event panel default-focuses the head cascade
  // on mount, so the panel renders `rf-causa-event-detail-cascade`
  // (six-domino detail) directly — no click-into-list step required.
  // The legacy `rf-causa-cascade-row-*` empty-state rows only appear
  // when the panel has no routable cascade to default-focus on.
  await waitForValue(
    () => page.locator('[data-testid="rf-causa-event-detail-cascade"]').count(),
    (count) => count > 0,
    { timeoutMs: 5000, description: 'event-detail cascade default-focus' },
  );

  await clickTab(page, 'trace', 'rf-causa-trace');
  const traceCounts = await readTraceCounts(page);
  if (traceCounts.total < 1) {
    throw new Error(`Expected non-empty trace feed, got ${traceCounts.text}.`);
  }
}

async function runSourceCoordinatesAndLaunchModes(page, state, ctx) {
  await openCausa(page);
  await clickTab(page, 'trace', 'rf-causa-trace');
  await clearTrace(page);
  await clickHostButtonByLabel(page, '+');
  await waitForTraceMatch(page, /counter\/core\.cljs/, 'counter source-coordinate trace');
  await setCausaTraceFilter(page, ':source', ':ui');
  await waitForValue(
    () => page.locator('[data-testid^="rf-causa-trace-row-"] button[data-testid$="-source-coord"]').count(),
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

  await clickTab(page, 'issues', 'rf-causa-issues-ribbon');
  await expectVisible(page.locator('[data-testid="rf-causa-issues-feed"]'), 5000);
  await assertSourceCoordBridge(page, state, ctx, { panel: 'issues' });
  await clickTab(page, 'trace', 'rf-causa-trace');
  await expectVisible(page.locator('[data-testid="rf-causa-trace-feed"]'), 5000);
}

async function runSchemaViolation(page, state) {
  await openCausa(page);
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
  await clickTab(page, 'issues', 'rf-causa-issues-ribbon');
  await expectVisible(page.locator('[data-testid="rf-causa-issues-feed"]'), 5000);
  const issuesProjection = await waitForValue(
    () => page.evaluate(() => {
      const rows = Array.from(
        document.querySelectorAll('[data-testid^="rf-causa-issues-row-"]'),
      ).filter((el) => /^rf-causa-issues-row-\d+$/.test(el.getAttribute('data-testid') || ''));
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
    (projection) => projection.schemaRowCount >= 4,
    {
      timeoutMs: 5000,
      description: 'Issues feed rendered >= 4 schema-validation rows',
    },
  );
  state.schemaRecovery.issuesProjection = issuesProjection;
  // The Issues row's `:description` is built from
  // `:operation + :tags[:path]` (issues-ribbon-helpers
  // `short-description`); the `:where` keyword that distinguishes the
  // four recovery surfaces lives on the raw trace event, not the row.
  // The trace assertions above already verify all four `:where`
  // surfaces fired; here we verify that every fired schema-violation
  // trace round-trips into a visible Issues-tab row.
  if (issuesProjection.schemaRowCount < schemaEvents.length) {
    failWithDetails('Issues feed dropped one or more schema-violation traces', {
      expectedRowCount: schemaEvents.length,
      observedRowCount: issuesProjection.schemaRowCount,
      observed: issuesProjection,
      schemaEvents,
    });
  }
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

  // Post rf2-xy4yb: the dedicated Effects (fx) panel was dropped.
  // Per spec/018 §5 fx-handlers-ran now lands as an `fx` + `effects`
  // domino row inside the cascade-detail of the Event tab. Per
  // rf2-639lc the L4 panel default-focuses the head (most-recent)
  // cascade on mount — so opening the tab after the last `:go`
  // dispatch already surfaces its cascade-detail. Assert the rendered
  // detail carries an fx row (the `:rf.fx/handled` emits for the
  // dispatched `:go` event are projected into the `effects` block).
  await clickTab(page, 'event', 'rf-causa-event-detail');
  await expectVisible(page.locator('[data-testid="rf-causa-event-detail-cascade"]'), 5000);
  const cascadeText = ((await page.locator('[data-testid="rf-causa-event-detail-cascade"]').textContent()) || '').toLowerCase();
  if (!cascadeText.includes('effects') && !cascadeText.includes('fx')) {
    const dispatchId = await page
      .locator('[data-testid="rf-causa-event-detail-cascade"]')
      .getAttribute('data-dispatch-id');
    failWithDetails('Event-tab cascade-detail did not surface the fx/effects domino rows', {
      dispatchId,
      cascadeText: cascadeText.slice(0, 800),
    });
  }
  await clickTab(page, 'issues', 'rf-causa-issues-ribbon');
  await expectVisible(page.locator('[data-testid="rf-causa-issues-feed"]'), 5000);
}

async function runMultiFrame(page, state) {
  await openCausa(page);
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
    ['parent A cross-bump dispatch', [':event/dispatched', ':frame :counter/a', ':multi-frame.core/cross-bump']],
    ['child B inc dispatch', [':event/dispatched', ':frame :counter/b', ':multi-frame.core/inc']],
    ['child log append dispatch', [':event/dispatched', ':frame :log', ':multi-frame.core/log-append']],
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
  await clickTab(page, 'trace', 'rf-causa-trace');
  await waitForValue(
    () => page.locator('[data-testid^="rf-causa-trace-row-"]').count(),
    (count) => count > 0,
    { timeoutMs: 5000, description: 'multi-frame trace rows' },
  );
  // Post rf2-ycoct: the Trace tab is cascade-scoped — it only renders
  // rows belonging to the spine's focused cascade. LIVE mode auto-
  // snaps focus to the head cascade (the most recent dispatch), so
  // unless the :counter/b :multi-frame.core/inc cascade happens to be
  // the head the Trace DOM never contains that row. The old test
  // scanned `[data-testid^="rf-causa-trace-row-"]` for it directly
  // and failed under cascade-scoping.
  //
  // The test's intent — exercise the cascade-focus → event-detail
  // wiring for a chosen :counter/b cascade — is preserved by focusing
  // the cascade explicitly via the spine event `:rf.causa/focus-
  // cascade` (the same event the L2 event-row click dispatches) and
  // then asserting the event-detail projection. We look up the
  // dispatch-id by walking the bus buffer for the (frame, event-id)
  // pair, which is independent of the cascade-scoped Trace DOM.
  const selected = await focusCascadeByFrameEvent(page, {
    frame: ':counter/b',
    eventId: ':multi-frame.core/inc',
  });
  state.multiFrame.selectedTraceRow = selected;
  await clickTab(page, 'event', 'rf-causa-event-detail');
  const eventDetailProjection = await waitForValue(
    () => page.evaluate(() => {
      const orphaned = document.querySelector('[data-testid="rf-causa-event-detail-orphaned"]');
      const cascade = document.querySelector('[data-testid="rf-causa-event-detail-cascade"]');
      return {
        cascadeRows: document.querySelectorAll('[data-testid^="rf-causa-cascade-row-"]').length,
        selectedCascadeFrame: cascade ? cascade.getAttribute('data-frame') : null,
        selectedCascadeDispatchId: cascade ? cascade.getAttribute('data-dispatch-id') : null,
        selectedCascadeText: cascade ? (cascade.textContent || '').trim() : null,
        orphanedText: orphaned ? (orphaned.textContent || '').trim() : null,
      };
    }),
    (projection) => projection.selectedCascadeFrame === ':counter/b',
    { timeoutMs: 5000, description: 'event-detail projection after focusing B cascade' },
  );
  state.multiFrame.eventDetailProjection = eventDetailProjection;
  if (eventDetailProjection.orphanedText ||
      !eventDetailProjection.selectedCascadeText.includes(':multi-frame.core/inc')) {
    failWithDetails('Event detail did not render the selected :counter/b cascade', {
      selected,
      eventDetailProjection,
      expectedFrame: ':counter/b',
      expectedEvent: ':multi-frame.core/inc',
    });
  }
  // Post rf2-xy4yb: the dedicated Causality and Time-Travel panels
  // were dropped. Per spec/018 §5 + §6 the Causality graph is
  // promoted to an on-demand popover (planned: `c` key — not yet
  // wired in shell/keybinding) and Time Travel folds into the Event
  // tab + RETRO scrubbing on the L2 event list. Until the popover
  // ships, this scenario covers multi-frame isolation through the
  // trace + event-tab cascade evidence above; the dedicated
  // causality / time-travel handoff steps are retired.
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

async function runLargeDispatcher(page, state) {
  await openCausa(page);
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
  await clickSidebar(page, 'app-db', 'rf-causa-app-db-diff');
  await expectVisible(page.locator('[data-testid="rf-causa-app-db-diff"]'), 5000);
  const appDbText = ((await page.locator('[data-testid="rf-causa-app-db-diff"]').textContent()) || '').trim();
  if (!appDbText.includes(':rf.size/large-elided')) {
    failWithDetails('Large-value 20-dispatch load did not surface elision markers in App-DB Diff', {
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
    visibleRowCount: await page.locator('[data-testid^="rf-causa-app-db-diff-slice-"]').count(),
    renderDurationMs: Date.now() - start,
    largeDispatchCount: 20,
    elisionMarkerVisible: true,
  };
}

async function runHydration(page) {
  await openCausa(page);
  // Post rf2-xy4yb: the dedicated Hydration debugger panel was
  // dropped. Per spec/018 §5 hydration mismatches now surface in the
  // Issues tab as `:rf.ssr/*` rows (category-prefix "rf.ssr").
  await clickTab(page, 'issues', 'rf-causa-issues-ribbon');
  await expectVisible(page.locator('[data-testid="rf-causa-issues-feed"]'), 5000);
  await waitForValue(
    () => page.evaluate(() => {
      const rows = Array.from(
        document.querySelectorAll('[data-testid^="rf-causa-issues-row-"]'),
      ).filter((el) => /^rf-causa-issues-row-\d+$/.test(el.getAttribute('data-testid') || ''));
      return rows.some((row) => {
        const cat = row.querySelector('[data-testid$="-category"]');
        const desc = row.querySelector('[data-testid$="-description"]');
        const catText = cat ? (cat.textContent || '').trim() : '';
        const descText = desc ? (desc.textContent || '').trim() : '';
        return catText.includes('rf.ssr') || descText.includes(':rf.ssr/');
      });
    }),
    (found) => found === true,
    {
      timeoutMs: 5000,
      description: 'Issues feed surfaces an :rf.ssr/* hydration mismatch row',
    },
  );
}

async function runTwentyEventLoad(page, state) {
  await expectHostCounterEquals(page, 5, 10000);
  await openCausa(page);
  await clickTab(page, 'trace', 'rf-causa-trace');
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
  await clickTab(page, 'event', 'rf-causa-event-detail');
  // Per rf2-639lc the L4 panel default-focuses the head cascade on
  // mount — assert the cascade-detail surface rendered (proves the
  // spine pipeline emitted routable cascades visible to L4).
  await waitForValue(
    () => page.locator('[data-testid="rf-causa-event-detail-cascade"]').count(),
    (count) => count > 0,
    { timeoutMs: 5000, description: 'event-detail cascade default-focus after load' },
  );
  // Post rf2-xy4yb: the Causality Graph panel was dropped (planned
  // future popover via `c` key — not yet wired). The 20-event
  // load-recheck still asserts trace + event-tab cascade growth,
  // which exercises the spine + projection pipeline end-to-end.
  state.loadStats = {
    eventCountBefore: before.total,
    eventCountAfter: after.total,
    visibleRows: after.rendered,
    traceBufferDepth: after.total,
    elapsedMs,
  };
}

async function runTraceBudgetSaturation(page, state) {
  await expectHostCounterEquals(page, 5, 10000);
  await openCausa(page);
  await clickSidebar(page, 'trace', 'rf-causa-trace');
  await clearTrace(page);
  const start = Date.now();
  const pushed = await pushSyntheticTraceEvents(page, 1000);
  const saturated = await waitForValue(
    () => readTraceCounts(page),
    (counts) => counts.total === 1000,
    { timeoutMs: 10000, description: 'trace buffer saturation at 1000 rows' },
  );
  const saturatedDom = await waitForValue(
    () => readTraceDomBudget(page),
    (budget) => budget.rowCount === 200 && budget.overflowPresent,
    { timeoutMs: 10000, description: 'trace DOM row budget at 200 with overflow indicator' },
  );
  if (saturatedDom.rowCount > 200) {
    failWithDetails('Trace panel exceeded its 200-row DOM budget under saturation', {
      pushed,
      counts: saturated,
      dom: saturatedDom,
    });
  }

  for (let i = 0; i < 20; i += 1) {
    await clickHostButtonByLabel(page, i % 2 === 0 ? '+' : '-');
  }
  const after = await waitForValue(
    async () => ({
      counts: await readTraceCounts(page),
      dom: await readTraceDomBudget(page),
      events: await readTrace(page),
    }),
    (snapshot) =>
      snapshot.counts.total === 1000 &&
      snapshot.dom.rowCount <= 200 &&
      snapshot.events.some((event) => event.includes(':counter/inc')) &&
      snapshot.events.some((event) => event.includes(':counter/dec')),
    { timeoutMs: 10000, description: 'trace budget still capped after 20 host dispatches' },
  );
  state.loadStats = {
    eventCountBefore: saturated.total,
    eventCountAfter: after.counts.total,
    traceBufferDepth: after.counts.total,
    visibleRowCount: after.dom.rowCount,
    renderDurationMs: Date.now() - start,
    bufferEvictionCount: Math.max(0, saturated.total + 20 - after.counts.total),
    overflowText: after.dom.overflowText,
    syntheticEventsPushed: pushed.pushed,
  };
}

async function runLaunchModesTwentyEventLoad(page, state) {
  await expectHostCounterEquals(page, 5, 10000);
  await openCausa(page);
  await clickSidebar(page, 'event-detail', 'rf-causa-event-detail');

  const launch = await page.evaluate(() => {
    const causa = window.day8 && window.day8.re_frame2_causa;
    const core = causa && (causa.core || causa);
    const keys = core ? Object.keys(core).sort() : [];
    const popout = core && core.popout_BANG_;
    const result = { keys };
    if (typeof popout !== 'function') {
      result.popout = { ok: false, reason: 'popout_BANG_ not exported' };
    } else {
      try {
        const value = popout();
        const win = window.open('', 'rf-causa-popout');
        const doc = win && win.document;
        const root = doc && doc.getElementById('rf-causa-popout-root');
        result.popout = {
          ok: Boolean(root),
          returnType: typeof value,
          openerStatus: win ? (win.closed ? 'closed' : 'open') : 'missing',
          rootMode: root ? root.getAttribute('data-rf-causa-mode') : null,
          shellPresent: Boolean(root && root.querySelector('[data-testid="rf-causa-shell"]')),
        };
      } catch (err) {
        result.popout = { ok: false, threw: String(err && (err.stack || err.message || err)) };
      }
    }
    return result;
  });
  state.launchLoad = { launch };
  if (!launch.popout.ok) {
    failWithDetails('Causa launch modes were not all available before load', launch);
  }
  await expectVisible(page.locator('#rf-causa-root [data-testid="rf-causa-event-detail"]'), 5000);
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
    { timeoutMs: 10000, description: 'launch-mode shared event-detail state after 20 host dispatches' },
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
    failWithDetails('Overlay and pop-out Event Detail disagree on rendered cascade rows', {
      cascadeRowCounts,
      selectedDispatchIds: [
        after.overlay.selectedDispatchId,
        after.popout.selectedDispatchId,
      ],
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

const SCENARIOS = [
  {
    name: 'feature matrix shell and panel handoff',
    url: '/counter/',
    panels: PANEL_HANDOFFS.map(([id]) => id),
    // Post rf2-xy4yb: coverage narrowed to the 6 surviving L3 tabs.
    // Removed surfaces (Time Travel, Causality Graph, Subscriptions,
    // Routes, Schemas, Hydration, Performance, Flows, Effects, MCP
    // Server) lost their UI handoff with the 4-layer chrome refactor
    // and are covered (where still functionally present) by their
    // dedicated substrate scenarios.
    coveredRows: [
      'Event Detail',
      'Causality Strip and Event Log',
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
    panels: ['issues', 'trace'],
    coveredRows: ['Event Detail', 'Trace', 'Issues Ribbon', 'Effects', 'Flows', 'Machines', 'Open in Editor / Source Coordinates'],
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
    // are now inline dominoes inside the Event-tab cascade. Performance
    // panel is gone too (Mike's call: use Chrome DevTools Performance).
    panels: ['event', 'issues', 'trace'],
    coveredRows: ['Event Detail', 'Issues Ribbon', 'Trace'],
    run: runHttpToggle,
  },
  {
    name: 'multi-frame isolation substrate',
    url: '/testbeds/multi-frame/',
    // Post rf2-xy4yb: Causality Graph and Time Travel panels were
    // dropped. Multi-frame isolation is now exercised via the Trace
    // and Event tabs (cascade per frame); the Causality popover
    // (planned via `c` key) lands in a follow-on bead.
    panels: ['trace', 'event'],
    coveredRows: ['Causality Strip and Event Log', 'Trace', 'Event Detail'],
    run: runMultiFrame,
  },
  {
    name: 'deep machine inspector substrate',
    url: '/testbeds/deep-machine/',
    panels: ['machines'],
    coveredRows: ['Machines', 'Trace'],
    run: runDeepMachine,
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
  // probe, but there is no Causa UI handoff to assert against.
  // Scenario retired; runDrainDepth / runLongFlow stay in place for
  // any future revival.
  {
    name: 'non-trivial app-db diff substrate',
    url: '/testbeds/non-trivial-app-db/',
    panels: ['app-db', 'time-travel'],
    coveredRows: ['App-DB Diff', 'Time Travel'],
    run: runAppDbPrivacyLarge,
  },
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
  {
    name: '20-event feature/load re-check',
    url: '/counter/',
    // Post rf2-xy4yb: causality / time-travel / performance panels
    // dropped. Load re-check exercises the surviving Trace + Event
    // tabs under 20-dispatch load.
    panels: ['trace', 'event'],
    load: true,
    coveredRows: [
      'Event Detail',
      'Causality Strip and Event Log',
      'Trace',
      'Shell, Keybinding, Config, Preload, Settings, and Production Elision',
    ],
    run: runTwentyEventLoad,
  },
  {
    name: '1000-event trace row-budget plus 20-dispatch re-check',
    url: '/counter/',
    panels: ['trace'],
    load: true,
    coveredRows: [
      'Trace',
      'Performance',
      'Shell, Keybinding, Config, Preload, Settings, and Production Elision',
    ],
    run: runTraceBudgetSaturation,
  },
  {
    name: '20-event launch-mode shared runtime re-check',
    url: '/counter/',
    panels: ['event-detail'],
    load: true,
    coveredRows: [
      'Event Detail',
      'Pop-out, Docking, and Inline Embedding',
      'Shell, Keybinding, Config, Preload, Settings, and Production Elision',
    ],
    run: runLaunchModesTwentyEventLoad,
  },
];

module.exports = {
  PANEL_HANDOFFS,
  SCENARIOS,
  STAGED_SURFACES,
};
