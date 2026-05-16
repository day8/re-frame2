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

  const expectedUri = click.sourceCoord
    ? `vscode://file/${click.sourceCoord}:1`
    : null;
  const events = await waitForValue(
    async () => readTrace(page),
    (traceEvents) => {
      const hasOpenEvent = traceEvents.some((event) =>
        event.includes(':rf.causa/open-in-editor') &&
        event.includes(click.sourceCoord));
      const hasBridgeFx = traceEvents.some((event) =>
        event.includes(':rf.editor/open') &&
        (!expectedUri || event.includes(expectedUri) || event.includes('vscode://file/')));
      return hasOpenEvent && hasBridgeFx;
    },
    {
      timeoutMs: 10000,
      description: `open-in-editor bridge for ${opts.panel} ${click.sourceCoord}`,
    },
  );
  await sleep(150);
  const requestFailures = ctx && ctx.browserState
    ? ctx.browserState.requestFailures.slice(requestStart)
    : [];
  const afterUrl = page.url();
  const record = {
    panel: opts.panel,
    sourceCoord: click.sourceCoord,
    testId: click.testId,
    expectedUri,
    beforeUrl,
    afterUrl,
    requestFailures,
    observedOpenDispatch: events.some((event) =>
      event.includes(':rf.causa/open-in-editor') && event.includes(click.sourceCoord)),
    observedBridgeFx: events.some((event) =>
      event.includes(':rf.editor/open') &&
      (!expectedUri || event.includes(expectedUri) || event.includes('vscode://file/'))),
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

async function clickTraceRowByFrame(page, { frame, event }) {
  const result = await page.evaluate(({ targetFrame, targetEvent }) => {
    const root = document.getElementById('rf-causa-root');
    if (!root) return { clicked: false, reason: 'Causa root missing', candidates: [] };
    const rows = Array.from(root.querySelectorAll('[data-testid^="rf-causa-trace-row-"]'));
    const candidates = rows.map((row) => ({
      testId: row.getAttribute('data-testid'),
      text: (row.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 240),
    }));
    const target = rows.find((row) => {
      const text = (row.textContent || '').trim();
      return text.includes(targetFrame) && text.includes(targetEvent);
    });
    if (!target) {
      return {
        clicked: false,
        reason: `No trace row matched frame=${targetFrame} event=${targetEvent}`,
        candidates: candidates.slice(0, 20),
      };
    }
    target.click();
    return {
      clicked: true,
      testId: target.getAttribute('data-testid'),
      text: (target.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 240),
    };
  }, { targetFrame: frame, targetEvent: event });
  if (!result.clicked) {
    failWithDetails('Could not select trace row by frame', { frame, event, observed: result });
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
    const now = Date.now();
    for (let i = 0; i < eventCount; i += 1) {
      const dispatchId = 500000 + i;
      const tags = cljs.hash_map(
        keyword(':frame'), keyword(':rf/default'),
        keyword(':event-id'), keyword(':causa.synthetic/load'),
        keyword(':event'), cljs.PersistentVector.fromArray([keyword(':causa.synthetic/load'), i], true),
        keyword(':dispatch-id'), dispatchId,
        keyword(':origin'), keyword(':app'),
        keyword(':source'), keyword(':synthetic'),
      );
      const ev = cljs.hash_map(
        keyword(':id'), dispatchId,
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
        cascadeRows: count(root, '[data-testid^="rf-causa-cascade-row-"]'),
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

async function runSourceCoordinatesAndLaunchModes(page, state, ctx) {
  await openCausa(page);
  await clickSidebar(page, 'trace', 'rf-causa-trace');
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

  await clickSidebar(page, 'issues', 'rf-causa-issues-ribbon');
  await expectVisible(page.locator('[data-testid="rf-causa-issues-feed"]'), 5000);
  await assertSourceCoordBridge(page, state, ctx, { panel: 'issues' });
  await clickSidebar(page, 'trace', 'rf-causa-trace');
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
  await clickSidebar(page, 'schemas', 'rf-causa-schema-violation-timeline');
  await expectVisible(page.locator('[data-testid="rf-causa-schema-violation-timeline"]'), 5000);
  const timelineProjection = await page.evaluate(() => {
    function text(testId) {
      const el = document.querySelector(`[data-testid="${testId}"]`);
      return el ? (el.textContent || '').trim() : null;
    }
    const dots = Array.from(
      document.querySelectorAll('circle[data-testid^="rf-causa-schema-timeline-row-"][data-testid*="-dot-"]'),
    ).map((dot) => ({
      testId: dot.getAttribute('data-testid'),
      schemaKind: dot.getAttribute('data-schema-kind'),
      recovery: dot.getAttribute('data-recovery'),
      where: dot.getAttribute('data-where'),
    }));
    return {
      rowCount: document.querySelectorAll('[data-testid^="rf-causa-schema-timeline-row-"]').length,
      dotCount: dots.length,
      dots,
      noSchemas: text('rf-causa-schema-timeline-empty-no-schemas'),
      noViolations: text('rf-causa-schema-timeline-empty-no-violations'),
    };
  });
  state.schemaRecovery.timelineProjection = timelineProjection;
  if (timelineProjection.rowCount === 0 || timelineProjection.dotCount < 4) {
    failWithDetails('Schema timeline rendered rows without the expected recovery dots', {
      expectedDotCount: 4,
      expectedWheres,
      observed: timelineProjection,
      schemaEvents,
    });
  }
  const missingTimelineWheres = expectedWheres.filter((where) =>
    !timelineProjection.dots.some((dot) => dot.where === where));
  if (missingTimelineWheres.length > 0) {
    failWithDetails('Schema timeline dots did not preserve recovery surfaces', {
      expectedWheres,
      missingTimelineWheres,
      observed: timelineProjection,
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

  await clickSidebar(page, 'fx', 'rf-causa-fx');
  await expectVisible(page.locator('[data-testid="rf-causa-fx-list"]'), 5000);
  await clickSidebar(page, 'issues', 'rf-causa-issues-ribbon');
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
  await clickSidebar(page, 'trace', 'rf-causa-trace');
  await waitForValue(
    () => page.locator('[data-testid^="rf-causa-trace-row-"]').count(),
    (count) => count > 0,
    { timeoutMs: 5000, description: 'multi-frame trace rows' },
  );
  const selected = await clickTraceRowByFrame(page, {
    frame: ':counter/b',
    event: ':multi-frame.core/inc',
  });
  state.multiFrame.selectedTraceRow = selected;
  await clickSidebar(page, 'event-detail', 'rf-causa-event-detail');
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
    { timeoutMs: 5000, description: 'event-detail projection after selecting B trace row' },
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
  await clickSidebar(page, 'causality', 'rf-causa-causality-graph');
  await expectVisible(page.locator('[data-testid="rf-causa-causality-graph"]'), 5000);
  await setCausaTargetFrame(page, ':counter/b');
  await clickSidebar(page, 'time-travel', 'rf-causa-time-travel');
  await expectVisible(page.locator('[data-testid="rf-causa-time-travel"]'), 5000);
  const timeTravelProjection = await waitForValue(
    () => page.evaluate(() => {
      const slider = document.querySelector('[data-testid="rf-causa-time-travel-slider"]');
      const empty = document.querySelector('[data-testid="rf-causa-time-travel-empty"]');
      const frameEl = document.querySelector('[data-testid="rf-causa-time-travel-target-frame"]');
      return {
        targetFrame: frameEl ? (frameEl.textContent || '').trim() : null,
        sliderVisible: Boolean(slider && slider.getBoundingClientRect().width > 0),
        sliderMax: slider ? Number(slider.getAttribute('max')) : null,
        emptyText: empty ? (empty.textContent || '').trim() : null,
      };
    }),
    (projection) => projection.targetFrame === ':counter/b' &&
      (projection.sliderVisible || Boolean(projection.emptyText)),
    { timeoutMs: 5000, description: 'time-travel target frame projection for :counter/b' },
  );
  state.multiFrame.timeTravelProjection = timeTravelProjection;
  if (timeTravelProjection.sliderVisible &&
      (!Number.isFinite(timeTravelProjection.sliderMax) || timeTravelProjection.sliderMax < 2)) {
    failWithDetails('Expected multi-frame time-travel slider to expose at least two epochs', {
      selected,
      timeTravelProjection,
      epochSummary,
    });
  }
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

async function runSensitiveDispatcher(page, state) {
  await openCausa(page);
  await clearTrace(page);
  const start = Date.now();
  for (let i = 0; i < 20; i += 1) {
    if (i % 5 === 4) {
      await clickTestId(page, 'sign-in-throw');
    } else {
      await clickTestId(page, i % 2 === 0 ? 'sign-in-plain' : 'sign-in-redacted');
    }
  }
  await expectTextEquals(page.locator('[data-testid="plain-count"]'), '8', 5000);
  await expectTextEquals(page.locator('[data-testid="redacted-count"]'), '8', 5000);
  await expectTextEquals(page.locator('[data-testid="throw-count"]'), '0', 5000);
  await expectVisible(page.locator('[data-testid="rf-causa-redacted-indicator"]'), 5000);
  const indicatorText = ((await page.locator('[data-testid="rf-causa-redacted-indicator"]').textContent()) || '').trim();
  const indicatorCount = Number((/REDACTED\s+(\d+)/.exec(indicatorText) || [])[1]);
  if (!Number.isFinite(indicatorCount) || indicatorCount < 20) {
    failWithDetails('Sensitive 20-dispatch load did not increment the redaction indicator enough', {
      indicatorText,
      indicatorCount,
      expectedAtLeast: 20,
    });
  }
  const bodyText = await page.locator('body').textContent();
  if ((bodyText || '').includes('shhh-this-is-secret')) {
    throw new Error('Raw sensitive password leaked into DOM.');
  }
  const traceEvents = await readTrace(page);
  const sensitiveErrors = traceEvents.filter((event) =>
    event.includes('sensitive-dispatcher / sign-in-throw'));
  if (sensitiveErrors.length > 0 &&
      !sensitiveErrors.every((event) => event.includes(':rf/redacted') && !event.includes('shhh-this-is-secret'))) {
    failWithDetails('Sensitive error traces were not redacted', {
      sensitiveErrors,
    });
  }
  await clickSidebar(page, 'trace', 'rf-causa-trace');
  await expectVisible(page.locator('[data-testid="rf-causa-trace-feed"]'), 5000);
  state.loadStats = {
    eventCountBefore: 0,
    eventCountAfter: traceEvents.length,
    traceBufferDepth: traceEvents.length,
    visibleRowCount: await page.locator('[data-testid^="rf-causa-trace-row-"]').count(),
    renderDurationMs: Date.now() - start,
    sensitiveDispatchCount: 20,
    redactionIndicatorCount: indicatorCount,
  };
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
  await clickSidebar(page, 'hydration', 'rf-causa-hydration-debugger');
  await expectVisible(page.locator('[data-testid="rf-causa-hydration-mismatch-list"]'), 5000);
  await expectVisible(page.locator('[data-testid="rf-causa-hydration-mismatch-detail"]'), 5000);
}

async function runTwentyEventLoad(page, state) {
  await expectHostCounterEquals(page, 5, 10000);
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
    name: '20-event sensitive redaction load',
    url: '/testbeds/sensitive-dispatcher/',
    panels: ['trace'],
    load: true,
    coveredRows: ['Redaction, Sensitive, and Large Values', 'Trace'],
    run: runSensitiveDispatcher,
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
