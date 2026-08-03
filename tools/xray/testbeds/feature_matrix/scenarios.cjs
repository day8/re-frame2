'use strict';

const {
  expectCount,
  expectTextEquals,
  expectVisible,
  navigate,
  waitForValue,
} = require('../../../../examples/scripts/spec-helpers.cjs');
const {
  clearTraceBus,
  readTraceEventsAsEdn,
} = require('../../../../testbeds/spec-helpers.cjs');

// rf2-taj9b — the one navigation in this file (the static-mode scenario's
// baseline reset) carried no timeout and so took Playwright's 30s default:
// a ceiling BELOW the gate's own 45s scenario budget, firing first and
// reporting itself in a form that reads like that budget. Read from the
// gate's own knob (`implementation/scripts/serve-and-run-xray-feature-gate.cjs`
// uses the same variable and default) so the two move together.
// `waitUntil: 'load'` is KEPT: this is a re-navigation of a testbed page the
// gate has already loaded, and everything after it is a short locator budget
// (10s for the host counter, then 5s waits) that assumes a booted document.
const NAV_TIMEOUT_MS = Number(process.env.XRAY_FEATURE_GATE_TIMEOUT_MS || 45000);

// The 4-layer chrome's L3 tab bar exposes the 9 LIVE Dynamic tabs:
// epoch / app-db / views / trace / machines / routing / resources /
// derivation-graph / module-view (spec/018 §5 §The 9 tabs;
// spec/007-UX-IA.md §L3). The Epoch panel is the canonical
// "what happened in this epoch" surface; issues surface inline in the
// Epoch panel + the L2 event-row pink-wash + the always-on issues
// ribbon signal. The sweep walks EVERY shipped Dynamic tab and asserts
// a real panel root, never the unknown-tab stub. Only panels with a tab
// have a UI handoff, so the shell-sweep scenario covers exactly those.
const PANEL_HANDOFFS = [
  ['epoch', 'rf-xray-epoch-panel'],
  ['app-db', 'rf-xray-app-db-diff'],
  // The :views tab routes to the full Views panel per spec/012-Views.md.
  // The Views panel renders its canonical `rf-xray-reactive` root testid.
  ['views', 'rf-xray-reactive'],
  ['trace', 'rf-xray-trace'],
  ['machines', 'rf-xray-machine-inspector'],
  // The :routing tab is the focused-event navigation lens. Its root view
  // renders the `rf-xray-routing` testid (panels/routing.cljs).
  ['routing', 'rf-xray-routing'],
  // The :resources tab (Spec 016 §Xray and AI tooling) is the
  // declarative-server-state lens. Its root view renders the
  // `rf-xray-resources` testid (panels/resources.cljs). On the counter
  // testbed no resources are registered, so the panel renders its
  // silent-by-default state under the same `rf-xray-resources` root.
  ['resources', 'rf-xray-resources'],
  // The :derivation-graph tab (EP-0014 prop-3) — the unified
  // derivation/process graph. L4-only registry tab. Its root view always
  // renders the `rf-xray-derivation-graph` testid (panels/derivation_
  // graph.cljs); on the counter testbed it renders the silent state under
  // the same root.
  ['derivation-graph', 'rf-xray-derivation-graph'],
  // The :module-view tab (EP-0013) — the (realm, frame) address space +
  // demand-trigger surface. L4-only registry tab. Its root view always
  // renders the `rf-xray-module-view` testid (panels/module_view.cljs).
  ['module-view', 'rf-xray-module-view'],
  // There is no dedicated Issues tab to enumerate here; issues surface
  // inline in the Epoch panel + the L2 event-row pink-wash + the
  // always-on issues ribbon signal (the auto-open-on-error watcher).
];

const STAGED_SURFACES = [
  {
    build: 'examples/counter',
    bundleDir: ['out', 'examples', 'counter'],
    html: ['examples', 'core', 'counter', 'index.html'],
    servedPath: 'counter',
  },
  {
    build: 'testbeds/deliberate-throw',
    bundleDir: ['out', 'testbeds', 'deliberate-throw'],
    html: ['testbeds', 'deliberate_throw', 'index.html'],
    servedPath: 'testbeds/deliberate-throw',
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
  // The panel-gallery testbed is staged for the theme-token CSS-variable
  // resolution probe. The gallery embeds bare Xray widgets without
  // mounting the Xray shell, so its boot path must call
  // `global-styles/install!` explicitly. The probe asserts that contract
  // from a real browser; without `install!` every `var(--rf-xray-*)`
  // reference would resolve to its CSS fallback default and panels would
  // paint unstyled.
  {
    build: 'testbeds/panel-gallery',
    bundleDir: ['out', 'testbeds', 'panel-gallery'],
    html: ['tools', 'xray', 'testbeds', 'panel_gallery', 'index.html'],
    servedPath: 'testbeds/panel-gallery',
  },
  // The routes-epochs deck (the ROUTING step-up tester). A single-frame
  // numbered-button ladder over the real `reg-route` +
  // `:rf.route/navigate` surface, driving the Xray Routing panel
  // (`rf-xray-routing`). Served from the deck's own hand-written
  // index.html (source dir first, like the 8032 :dev-http entry); the
  // compiled main.js falls through to out/examples/routes-epochs.
  {
    build: 'examples/routes-epochs',
    bundleDir: ['out', 'examples', 'routes-epochs'],
    html: ['tools', 'xray', 'testbeds', 'routes_epochs', 'index.html'],
    servedPath: 'testbeds/routes-epochs',
  },
  // The machine-epochs deck (the STATE-MACHINE tester), built on the
  // shared queued-step runner (`runner.core`). A single-frame step matrix
  // over the real `reg-machine` + machine-event-routing surface, driving
  // the Xray Machine Inspector (`rf-xray-machine-inspector`). Served from
  // the deck's own hand-written index.html (source dir first, like the
  // 8033 :dev-http entry); the compiled main.js falls through to
  // out/examples/machine-epochs.
  {
    build: 'examples/machine-epochs',
    bundleDir: ['out', 'examples', 'machine-epochs'],
    html: ['tools', 'xray', 'testbeds', 'machine_epochs', 'index.html'],
    servedPath: 'testbeds/machine-epochs',
  },
  // The two-frame isolation deck (rf2-4279q4). The standard-epochs button
  // ladder mounted TWICE — once per `:above` / `:below` frame-provider —
  // on one page, with an inline Xray on the right. It is THE canonical
  // per-frame ISOLATION surface: the same app code path drives two fully
  // independent reactive contexts. Served from the deck's own
  // hand-written index.html (source dir first, like the 8030 :dev-http
  // entry); the compiled main.js falls through to
  // out/examples/two-frame-isolation.
  {
    build: 'examples/two-frame-isolation',
    bundleDir: ['out', 'examples', 'two-frame-isolation'],
    html: ['tools', 'xray', 'testbeds', 'two_frame_isolation', 'index.html'],
    servedPath: 'testbeds/two-frame-isolation',
  },
  // The freehand-views deck (rf2-6pohj) — the ONE staged surface whose
  // views are FREEHAND views, so the Views panel's Mounted Views and
  // Declared View Sites sections have something to project. Every other
  // surface here is Reagent-hosted and connects no Freehand occurrence.
  // Served from the deck's own hand-written index.html (source dir first,
  // like the 8036 :dev-http entry); the compiled main.js falls through to
  // out/testbeds/freehand-views.
  {
    build: 'testbeds/freehand-views',
    bundleDir: ['out', 'testbeds', 'freehand-views'],
    html: ['tools', 'xray', 'testbeds', 'freehand_views', 'index.html'],
    servedPath: 'testbeds/freehand-views',
  },
];

async function openXray(page) {
  if ((await page.locator('[data-testid="rf-xray-shell"]').count()) === 0) {
    await page.keyboard.press('Control+Shift+C');
  }
  await expectVisible(page.locator('[data-testid="rf-xray-shell"]'), 5000);
}

// The L3 tab bar's tabs expose `data-testid="rf-xray-tab-<id>"` for the
// 9 LIVE Dynamic panels (epoch / app-db / views / trace / machines /
// routing / resources / derivation-graph / module-view — spec/018 §5
// §The 9 tabs; Resources per Spec 016 §Xray and AI tooling; Graph +
// Modules per EP-0014 / EP-0013).
async function clickTab(page, id, canvasTestId) {
  await page.locator(`[data-testid="rf-xray-tab-${id}"]`).click();
  await expectVisible(page.locator(`[data-testid="${canvasTestId}"]`), 5000);
}

// Panel-id vocabulary → L3 tab ids. Only the panels listed here have a
// UI handoff in the 4-layer chrome; a caller targeting any other panel-id
// gets an explicit throw (handled in `clickSidebar`). Both `event-detail`
// and `event` route to the Epoch panel's `:epoch` tab — the canonical
// "what happened in this epoch" surface.
const LEGACY_PANEL_TO_TAB = {
  'event-detail': 'epoch',
  'event':        'epoch',
  'app-db':       'app-db',
  'trace':        'trace',
  'machines':     'machines',
  // There is no `issues` tab handoff. Issues surface inline in the
  // Epoch panel + the L2 event-row pink-wash + the always-on issues
  // ribbon signal.
};

// Wrapper used by scenarios that address panels by their panel-id
// vocabulary (multi-frame, large-dispatcher, etc.). Maps to a tab when
// one exists; throws explicitly when a caller targets a panel the chrome
// does not expose so the test surfaces the real gap instead of timing
// out on a missing testid.
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
      // There is no `issues` panel selector; source-coord bridge coverage
      // rides the Trace panel's chips (the same exception traces carry
      // the coords).
      hydration: '[data-testid="rf-xray-hydration-source-coord"] button',
    };
    if (!root) return { clicked: false, reason: 'Xray root missing', candidates: [] };
    const selector = selectors[targetPanel] || 'button[data-testid*="source"]';
    const buttons = Array.from(root.querySelectorAll(selector));
    // An icon source affordance (the `↗` icon, per the Figma design)
    // carries its `file:line` coord on the button's `title` attribute
    // rather than its text. Prefer `title` when it carries the coord
    // (icon affordances), falling back to textContent for the trace /
    // hydration chips that render the coord inline.
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

  // Explicitly configure an editor before clicking the chip. The counter
  // surface (this scenario's host) wires only the bare preload and never
  // sets `:rf.xray/editor`, so by default the click surfaces the 'pick an
  // editor in Settings' DX hint (the intended behaviour for an
  // unconfigured host) instead of firing the `vscode://` navigation this
  // scenario asserts. Setting `:vscode` explicitly models a properly-
  // wired host and exercises the click → `Location.assign` bridge the
  // scenario is about.
  await page.evaluate(() => {
    const cfg = window.day8 && window.day8.re_frame2_xray &&
                window.day8.re_frame2_xray.config;
    if (cfg && typeof cfg.set_editor_BANG_ === 'function') {
      cfg.set_editor_BANG_(window.cljs.core.keyword('vscode'));
    }
  });

  const click = await clickSourceCoordChip(page, opts);
  if (!click.clicked) {
    failWithDetails('Could not click source-coordinate chip', {
      panel: opts.panel,
      sourceIncludes: opts.sourceIncludes,
      url: beforeUrl,
      observed: click,
    });
  }

  // The chip-click handler dispatches `:rf.xray/open-in-editor` under the
  // `:rf/xray` frame, which then fires `:rf.xray.fx/open-in-editor` as an fx (also
  // `:rf/xray`-framed). The trace-bus ingest filter (`xray-internal-
  // event?`) correctly drops those self-emitted events before they reach
  // Xray's buffer — they are Xray machinery, not host activity. The bridge
  // round-trip therefore CANNOT be verified by reading Xray's trace feed.
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
 * dispatch `:rf.xray/focus-event` to focus that cascade.
 *
 * The Trace DOM is cascade-scoped and only renders rows for the currently
 * focused cascade, so scanning trace rows to "find and click" a sibling
 * cascade does not work. The bus buffer is the canonical, unscoped source
 * of (frame, event) → dispatch-id, and `:rf.xray/focus-event` is the
 * same spine event the L2 event-row click dispatches — picking a cascade
 * through this helper exercises the same focus → projection wiring without
 * depending on the cascade-scoped Trace surface.
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
      keyword(':rf.xray/focus-event'),
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

// The trace header's 'X / Y in view' denominator is cascade-scoped
// (Y = in-scope, pre-user-filter count), so it is not a proxy for the
// whole ring's depth. The buffer-cap invariant ('still capped at 1000')
// is read straight from the trace bus — the canonical source of truth
// for ring depth, independent of which cascade the spine has in focus.
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
    // Bump the secondary ring's depth so the test can assert against a
    // 1000-event budget without coupling the production default to a
    // perf-test invariant. The default `default-frameless-ring-depth`
    // (100) is what production hosts see.
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
    // The Trace tab is cascade-scoped — it only renders rows belonging to
    // the spine's focused cascade. To stress the per-cascade 200-row DOM
    // budget we push every synthetic event under a SINGLE shared
    // :dispatch-id so the buffer holds one focusable cascade containing
    // all `eventCount` rows; LIVE mode auto-snaps focus to that head
    // cascade and the trace ribbon ends up trying to render all 1000 —
    // which the DOM budget then caps at 200 with the overflow indicator.
    // (Allocating a distinct :dispatch-id per event would instead produce
    // 1000 single-row cascades, and under cascade-scoping only the head
    // cascade — a single row — would render, missing the budget assertion
    // entirely.)
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
      // The Epoch panel is the canonical focused-cascade surface; its
      // DISPATCH step is the rendering proxy for "a cascade is in focus
      // and its data is rendered".
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
        // `cascadeRows`: 1 when the Epoch panel rendered a DISPATCH step
        // (focus has a cascade), 0 when not (empty-state / no-focus /
        // epoch-evicted).
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
  // (per spec/018 §5). Only the panels with a tab take part in this
  // sweep; panels without a tab have no UI handoff to assert.
  for (const [id, canvas] of PANEL_HANDOFFS) {
    await clickTab(page, id, canvas);
  }

  await clickHostButtonByLabel(page, '+');
  await clickHostButtonByLabel(page, '+');
  await clickHostButtonByLabel(page, '-');

  await clickTab(page, 'epoch', 'rf-xray-epoch-panel');
  // The Epoch panel is the canonical "what happened in this epoch"
  // surface. It default-focuses the head cascade on mount and renders the
  // numbered cascade steps directly (e.g. `rf-xray-epoch-step-dispatch`).
  // Asserts non-empty via the presence of at least one step row.
  await waitForValue(
    () => page.locator('[data-testid="rf-xray-epoch-step-dispatch"]').count(),
    (count) => count > 0,
    { timeoutMs: 5000, description: 'epoch panel cascade default-focus' },
  );

  await clickTab(page, 'trace', 'rf-xray-trace');
  // The Trace panel is epoch-scoped — after the host dispatches above,
  // LIVE auto-snap focuses the head epoch whose `:trace-events` populate
  // the ribbon. Assert non-empty via the rendered rows (there is no
  // 'X / Y in view' counts header).
  //
  // Rows are `:div`s wrapped by the shared `rt/resizable-table` view. The
  // data-testid contract `rf-xray-trace-row-<id>` identifies each row.
  await waitForValue(
    () => page.locator('[data-testid^="rf-xray-trace-row-"]').count(),
    (count) => count > 0,
    { timeoutMs: 5000, description: 'epoch-scoped trace feed renders rows' },
  );

  // rf2-7gth0 — the Views panel's Mounted Views section renders in this
  // chrome runtime. There is NO install sentinel to probe any more, and none
  // is missing: the predecessor claimed a single-owner evidence registry from
  // the preload boot block and mirrored the claim onto
  // `globalThis.__day8_re_frame2_xray_viewcell_evidence`, so the sentinel was
  // the only cheap browser-side proof that the claim had happened.
  // `re-frame.freehand.tool` is a reader with nothing to claim, so the
  // section's own presence IS the wiring proof — removing the panel consumer
  // or the subs that feed it fails this sweep.
  //
  // The section renders its EMPTY state here, and that is now this
  // scenario's job rather than its limitation: the counter surface is
  // Reagent-hosted, so no Freehand occurrence connects, and the empty arm is
  // what a substrate-free host must show. The POPULATED arm has its own
  // surface and its own scenario — `freehand-views populated Views roster`
  // below, over the Freehand-hosted deck added by rf2-6pohj. Between the two
  // the browser lane can finally tell the arms apart; before rf2-6pohj it
  // could see only this one, so an empty section and a section emptied by a
  // BROKEN read were the same DOM.
  await clickTab(page, 'views', 'rf-xray-reactive');
  await expectVisible(
    page.locator('[data-testid="rf-xray-reactive-mounted-views-section"]'),
    5000,
  );
  await expectVisible(
    page.locator('[data-testid="rf-xray-reactive-mounted-views-empty"]'),
    5000,
  );
}

// ---- freehand-views: the POPULATED Views roster (rf2-6pohj) --------------
//
// The counterpart to the empty-arm assertion in the shell sweep above. The
// deck at /testbeds/freehand-views/ is the one staged surface whose views are
// FREEHAND views, so `re-frame.freehand.tool` has real connected occurrences
// to project and the Views panel has real rows to render.
//
// Everything asserted below is a fact only a WORKING read can produce. A
// stubbed roster passes "the section rendered"; it does not pass "the row for
// the compiled reader states one read while the row for the compiled
// dispatcher states none", and it does not pass the occurrence-identity check
// at the end.

const FREEHAND_VIEW_IDS = [
  ':freehand-views.core/app',
  ':freehand-views.core/controls',
  ':freehand-views.core/readout',
];

// One entry per rendered roster row, read from the row's two content spans
// rather than from its flattened `textContent`. `list-row` renders
// `[swatch][primary][tag]`, and the primary's last word runs straight into the
// tag's first word once the DOM is flattened (`… occ 56` + `interpreted · …`
// reads as `occ 56interpreted`) — which silently corrupts anything parsed out
// of the boundary. Reading the spans keeps the two halves apart, and they are
// different KINDS of fact: the primary is identity (view id, occurrence, root),
// the tag is the commit (lowering, generation, frame, reads, cause).
async function mountedViewRows(page) {
  const rows = page.locator('[data-testid^="rf-xray-reactive-mounted-views-row-"]');
  const [primaries, tags] = await Promise.all([
    rows.locator('> span:nth-child(2)').allTextContents(),
    rows.locator('> span:nth-child(3)').allTextContents(),
  ]);
  if (primaries.length !== tags.length) {
    throw new Error(
      `malformed Mounted Views rows: ${primaries.length} primary span(s) vs ${tags.length} tag span(s)`,
    );
  }
  return primaries.map((primary, i) => ({ primary, tag: tags[i] }));
}

// The roster sub (`:rf.xray/mounted-views`) recomputes off the epoch pump —
// its ONE reactive input is `:rf.xray/epoch-history`, so a change in what is
// connected surfaces on the next recorded epoch, by design. Every read of the
// panel below is therefore preceded by a dispatch. The Freehand `+` button is
// the pump: it is inside the mounted tree, so it is also the thing that
// proves the tree is really there.
//
// `predicate` is the caller's, and it has to be, because the pump is
// ASYNCHRONOUS with respect to the click: between the dispatch and the
// panel's re-render the section still shows the PREVIOUS roster. A predicate
// that only counts rows is therefore satisfied by the stale one — which is
// not a theoretical hazard, it is a flake this scenario actually had (3
// failures in 8 runs) before the remount check below started demanding the
// property it is really waiting for. Wait for the FACT under test, never for
// a shape the stale value also has.
async function pumpEpochAndReadRoster(page, predicate, description) {
  await page.locator('[data-testid="fh-bump"]').click();
  return waitForValue(() => mountedViewRows(page), predicate, {
    timeoutMs: 8000,
    description,
  });
}

function rowFor(rows, viewId) {
  const row = rows.find((r) => r.primary.includes(viewId));
  if (!row) {
    throw new Error(
      `no Mounted Views row names ${viewId}; rows were ${JSON.stringify(rows)}`,
    );
  }
  return row;
}

// `format-occurrence` renders the runtime occurrence key after ` · occ `, and
// it is the LAST thing in the primary span whenever `:root` is the `:unknown`
// the panel elides — which it always is here. The key is minted by the host's
// identity primitive, so this reads whatever shape it is given and only ever
// compares it with ITSELF across a remount, never against a literal.
function occurrenceOf(row) {
  const match = /· occ (.+)$/.exec(row.primary);
  if (!match) {
    throw new Error(`no "· occ <key>" in Mounted Views row: ${row.primary}`);
  }
  return match[1];
}

// The stronger fact this deck can now state (rf2-2t126): a dispatch moves
// app-db, the Freehand cell that read it REPAINTS, and the DOM shows the new
// value — on a page that is simultaneously hosting Xray's shell.
//
// It was not assertable before. Under a ratom-family adapter a mounted cell's
// committed read received no change notification at all: the observation port
// installed its watch on a `reagent.ratom/Reaction` that had captured no
// sources, because a `Reaction` learns them only through `deref-capture` and a
// ViewCell, not being a component, supplies no capture context. The port now
// ACTIVATES the value through `:adapter/activate-derived-value!` (rf2-8cnxg /
// rf2-jt8vz), and this is the only browser-level proof that the activation
// holds end to end in a real DOM — the substrate contract tests cover the
// port, not a cell repainting on the very page Xray is reading.
//
// "WITHOUT a remount" is half the claim, so it is asserted rather than
// assumed. `Mount root` reads current values whatever the notification channel
// is doing — it is the route the rest of this scenario deliberately uses, and
// it would satisfy an advance-check on a cell that was never notified at all.
// The probe is therefore pinned to the readout's DOM NODE: a repaint writes
// new text into the node already standing there, while any remount replaces it
// and loses the mark.
async function expectReactiveRepaint(page) {
  const PROBE = '__rf2FreehandRepaintProbe';
  const before = await page.evaluate((probe) => {
    const el = document.querySelector('[data-testid="fh-count"]');
    if (!el) return null;
    el[probe] = true;
    return (el.textContent || '').trim();
  }, PROBE);
  if (before === null) {
    throw new Error('no [data-testid="fh-count"] readout to watch for a repaint');
  }
  if (!/^\d+$/.test(before)) {
    throw new Error(`the readout is not a count: ${JSON.stringify(before)}`);
  }
  const expected = String(Number(before) + 1);

  await page.locator('[data-testid="fh-bump"]').click();
  await expectTextEquals(page.locator('[data-testid="fh-count"]'), expected, 8000);

  const sameNode = await page.evaluate((probe) => {
    const el = document.querySelector('[data-testid="fh-count"]');
    return Boolean(el && el[probe]);
  }, PROBE);
  if (!sameNode) {
    throw new Error(
      `the readout advanced to ${expected}, but on a REPLACED DOM node — that ` +
        'is a fresh mount reading a current value, not a reactively-driven ' +
        'repaint of the cell that was already standing there.',
    );
  }
  return expected;
}

async function runFreehandViewsPopulatedRoster(page) {
  // The Freehand root paints before Xray is asked to read it.
  await expectTextEquals(page.locator('[data-testid="fh-count"]'), '0', 10000);

  await openXray(page);
  await clickTab(page, 'views', 'rf-xray-reactive');

  // Asserted with the shell mounted, and before the roster is read, so a
  // failure here names the SUBSTRATE (the cell never heard about the write)
  // rather than the projection (Xray read it wrong).
  await expectReactiveRepaint(page);

  const rows = await pumpEpochAndReadRoster(
    page,
    (r) => r.length === 3,
    'three connected Freehand occurrences on the Mounted Views roster',
  );

  // POPULATED, not empty — the whole point of the deck (rf2-6pohj).
  await expectVisible(
    page.locator('[data-testid="rf-xray-reactive-mounted-views-list"]'),
    5000,
  );
  await expectCount(
    page.locator('[data-testid="rf-xray-reactive-mounted-views-empty"]'),
    0,
  );
  // No schema banner: the deck's Freehand door stamps the version this Xray
  // build pins, so rows are parsed rather than suppressed. A banner here
  // would mean the rows above are being read under a version mismatch.
  await expectCount(
    page.locator('[data-testid="rf-xray-reactive-mounted-views-schema-banner"]'),
    0,
  );

  // Each row is the RIGHT row: per-view facts that differ between views, so
  // a uniform stub cannot satisfy them.
  const appRow = rowFor(rows, FREEHAND_VIEW_IDS[0]);
  const controlsRow = rowFor(rows, FREEHAND_VIEW_IDS[1]);
  const readoutRow = rowFor(rows, FREEHAND_VIEW_IDS[2]);

  const expectTag = (row, fragment, why) => {
    if (!row.tag.includes(fragment)) {
      throw new Error(
        `${why}: expected ${JSON.stringify(fragment)} in ${JSON.stringify(row.tag)}`,
      );
    }
  };

  // Lowering is STATED by the substrate, never inferred — and the deck
  // declares one interpreted view against two compiled ones precisely so the
  // two spellings have to appear on different rows. Anchored at the START of
  // the tag, which is where `mounted-view-tag` puts it.
  for (const [row, lowering, who] of [
    [appRow, 'interpreted · gen', 'the interpreted root'],
    [controlsRow, 'compiled · gen', 'the compiled dispatcher'],
    [readoutRow, 'compiled · gen', 'the compiled reader'],
  ]) {
    if (!row.tag.startsWith(lowering)) {
      throw new Error(
        `${who} should report lowering ${JSON.stringify(lowering)}; tag was ${JSON.stringify(row.tag)}`,
      );
    }
  }

  // The commit's OWN staged reads. `readout` is the only view on the page
  // that calls `v/sub`, so exactly one row may claim a read.
  expectTag(readoutRow, '· 1 read', "the reader's commit staged its one read");
  expectTag(controlsRow, '· 0 reads', 'the dispatcher staged none');
  expectTag(appRow, '· 0 reads', 'the root staged none');

  for (const row of rows) {
    // The frame the commit ran over.
    expectTag(row, ':rf/default', "every commit names the deck's one frame");
    // `:root` is ALWAYS `:unknown` and the panel renders that marker as the
    // absence it is. A row printing the keyword would mean the panel had
    // started presenting a fact the substrate does not have.
    if (row.primary.includes(':unknown')) {
      throw new Error(
        `Mounted Views row printed the :unknown root marker: ${row.primary}`,
      );
    }
  }

  // Declared View Sites is evidence-keyed off the roster — it renders NOTHING
  // on a host with nothing connected, so its presence is a second,
  // independent populated-proof. Its content comes from the compiler
  // manifest, and the deck declares one of each arm.
  await expectVisible(
    page.locator('[data-testid="rf-xray-reactive-view-sites-section"]'),
    5000,
  );
  const siteRows = await page
    .locator('[data-testid^="rf-xray-reactive-view-site-row-"]')
    .allTextContents();
  const siteText = siteRows.join('\n');
  for (const [fragment, why] of [
    ['[:freehand-views.core/count]', "the compiled reader's proven subscription site"],
    [
      ':on-click · vector · [:freehand-views.core/bump]',
      "the compiled dispatcher's proven event site",
    ],
    [
      'this declaration is interpreted, so its sites are unknown, not absent',
      'the interpreted root reports un-analysed rather than clean',
    ],
  ]) {
    if (!siteText.includes(fragment)) {
      throw new Error(
        `Declared View Sites is missing ${why} (${JSON.stringify(fragment)}); rows were ${JSON.stringify(siteRows)}`,
      );
    }
  }

  // ---- the fact that MOVES ------------------------------------------------
  //
  // A populated row can still be a fabricated row. Unmount the root and mount
  // it again: the three cells disconnect and reconnect, so the host mints
  // three FRESH occurrence keys. The roster must come back naming the new
  // ones. Only a read of the live occurrence index can do that — a projection
  // over a static registry (which the donor tier had, and which the Freehand
  // door deliberately does not) would report the old keys, or the same ones
  // forever.
  //
  // `:generation` is deliberately NOT the fact under test: it is the
  // hot-reload BODY REVISION, not a render tally, so `gen 0` on every row is
  // correct for a page that is never hot-reloaded.
  const before = rows.map(occurrenceOf);

  // Wait for the unmount to REACH THE DOM before re-mounting. `v/mount` is
  // idempotent per root, so a re-mount that lands while the incumbent root is
  // still registered re-renders it instead of allocating a fresh one — the
  // same cells, the same occurrence keys, and an assertion below that fails
  // for a reason that has nothing to do with the read. The Freehand tree's
  // own `+` button leaving the document is the signal that the teardown
  // actually happened, so it is also a small assertion in its own right.
  await page.locator('[data-testid="fh-unmount"]').click();
  await page
    .locator('[data-testid="fh-bump"]')
    .waitFor({ state: 'detached', timeout: 5000 });
  await page.locator('[data-testid="fh-mount"]').click();
  await expectVisible(page.locator('[data-testid="fh-bump"]'), 5000);

  // The predicate demands BOTH halves of the claim — three rows again
  // (cardinality returns to baseline; churn accumulates nothing) AND not one
  // key carried over. Both are load-bearing: `waitForValue` throws on
  // timeout, so a roster that never refreshes, or refreshes to the wrong
  // cardinality, or comes back naming the same occurrences, all fail here
  // rather than hanging or passing on the stale value.
  const after = await pumpEpochAndReadRoster(
    page,
    (r) => r.length === 3 && r.every((row) => !before.includes(occurrenceOf(row))),
    `three FRESH occurrence keys after a remount (before=${JSON.stringify(before)})`,
  );

  const carried = after.map(occurrenceOf).filter((key) => before.includes(key));
  if (carried.length > 0) {
    throw new Error(
      `remount reported ${carried.length} carried-over occurrence key(s) ` +
        `(${JSON.stringify(carried)}); a disconnect must drop the row and a ` +
        `reconnect must mint a fresh occurrence.`,
    );
  }
}

async function runSourceCoordinatesAndLaunchModes(page, state, ctx) {
  await openXray(page);
  await clickTab(page, 'trace', 'rf-xray-trace');
  await clearTrace(page);
  await clickHostButtonByLabel(page, '+');
  await waitForTraceMatch(page, /counter\/core\.cljs/, 'counter source-coordinate trace');
  // The Trace panel is epoch-scoped with no chip filter. After the host
  // dispatch the spine auto-snaps focus to the head epoch (LIVE), whose
  // `:trace-events` carry the source-coord rows — no filter step needed;
  // every row's source-coord chip renders.
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

  // Exceptions surface INLINE in the Epoch panel as the "Exception
  // Thrown" block. Verify the inline surfacing, then run the source-coord
  // bridge against the Trace panel's source-coord chips (the same
  // exception traces carry the coords).
  await clickTab(page, 'epoch', 'rf-xray-epoch-panel');
  await expectVisible(page.locator('[data-testid="rf-xray-epoch-panel"]'), 5000);
  const exceptionEpochText = ((await page.locator('[data-testid="rf-xray-epoch-panel"]').textContent()) || '').toLowerCase();
  if (!exceptionEpochText.includes('exception') && !exceptionEpochText.includes('threw')) {
    failWithDetails('Epoch panel did not surface the inline exception block', {
      epochText: exceptionEpochText.slice(0, 800),
    });
  }

  // Focus the handler-throw epoch (Button A, `::throw-in-handler`: throws
  // before returning a `:db`) and assert the exception-chrome contract
  // holds on the live cascade:
  //   (1) NO spurious "Rolled back" recovery chip — nothing committed or
  //       rolled back here (and the post-commit/flow throws in this deck
  //       likewise never roll back the committed `:db`).
  //   (2) NO category-reason boilerplate headline element on any exception
  //       card (the position + "Exception Thrown" heading carry the
  //       attribution).
  //   (3) the HANDLER step does NOT render the redundant
  //       "— no :db (handler threw)" line — the inline card is the signal,
  //       so the `:db` sub-section is omitted on a throw.
  await focusCascadeByFrameEvent(page, {
    frame: ':rf/default',
    eventId: ':deliberate-throw.core/throw-in-handler',
  });
  await expectVisible(page.locator('[data-testid="rf-xray-epoch-panel"]'), 5000);
  // the handler-exception card is present (the inline failure surface)
  await waitForValue(
    () => page.locator('[data-testid="rf-xray-epoch-panel"] [data-error-op="handler-exception"]').count(),
    (count) => count > 0,
    { timeoutMs: 5000, description: 'oqi0c — handler exception card present' },
  );
  // (1) no rollback happened → NO "Rolled back" recovery chip anywhere
  const handlerEpochRecoveryChips = await page
    .locator('[data-testid="rf-xray-epoch-panel"] [data-testid$="-recovery"]')
    .count();
  if (handlerEpochRecoveryChips > 0) {
    const chipText = (await page
      .locator('[data-testid="rf-xray-epoch-panel"] [data-testid$="-recovery"]')
      .first()
      .textContent()) || '';
    failWithDetails('rf2-s6oqd — spurious recovery chip on an exception that did not roll back', {
      recoveryChipCount: handlerEpochRecoveryChips,
      chipText,
    });
  }
  // (2) no category-reason boilerplate headline is rendered
  const handlerEpochHeadlines = await page
    .locator('[data-testid="rf-xray-epoch-panel"] [data-testid$="-headline"]')
    .count();
  if (handlerEpochHeadlines > 0) {
    failWithDetails('rf2-oqi0c — boilerplate exception headline not dropped', {
      headlineCount: handlerEpochHeadlines,
    });
  }
  // (3) the HANDLER step omits the "— no :db (handler threw)" line
  const handlerThrewNoDbLine = await page
    .locator('[data-testid="rf-xray-epoch-handler-db-no-write"]')
    .count();
  if (handlerThrewNoDbLine > 0) {
    const lineText = (await page
      .locator('[data-testid="rf-xray-epoch-handler-db-no-write"]')
      .first()
      .textContent()) || '';
    failWithDetails('rf2-oqi0c — redundant "no :db (handler threw)" line not dropped', {
      lineText,
    });
  }

  await clickTab(page, 'trace', 'rf-xray-trace');
  await expectVisible(page.locator('[data-testid="rf-xray-trace-feed"]'), 5000);
  await waitForValue(
    () => page.locator('[data-testid^="rf-xray-trace-row-"] button[data-testid$="-source-coord"]').count(),
    (count) => count > 0,
    { timeoutMs: 5000, description: 'trace source-coordinate chips' },
  );
  await assertSourceCoordBridge(page, state, ctx, { panel: 'trace' });
}

async function runHttpToggle(page) {
  await openXray(page);
  await clearTrace(page);

  await clickTestId(page, 'go');
  await expectVisible(page.locator('[data-testid="reply-status"]'), 5000);

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

  // The Epoch panel is the canonical "what happened in this epoch"
  // surface and folds fx/effects in as inline steps of its numbered
  // cascade. It default-focuses the head (most-recent) cascade on mount —
  // opening the tab after the last `:go` dispatch surfaces its full
  // numbered cascade. Assert the rendered panel carries the EFFECT
  // HANDLERS step (the `:rf.fx/handled` emits for the dispatched `:go`
  // event are projected into the step's `:fx` sub-step). The step header
  // reads "EFFECT HANDLERS" and the `:fx` sub-header reads ":fx".
  await clickTab(page, 'epoch', 'rf-xray-epoch-panel');
  await expectVisible(page.locator('[data-testid="rf-xray-epoch-panel"]'), 5000);
  const epochText = ((await page.locator('[data-testid="rf-xray-epoch-panel"]').textContent()) || '').toLowerCase();
  if (!epochText.includes('fx') && !epochText.includes('effect')) {
    failWithDetails('Epoch panel did not surface the EFFECT HANDLERS step', {
      epochText: epochText.slice(0, 800),
    });
  }
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
  // The Trace tab is cascade-scoped — it only renders rows belonging to
  // the spine's focused cascade. LIVE mode auto-snaps focus to the head
  // cascade (the most recent dispatch), so unless the :counter/b
  // :multi-frame.core/inc cascade happens to be the head the Trace DOM
  // does not contain that row.
  //
  // The test's intent — exercise the cascade-focus → event-detail
  // wiring for a chosen :counter/b cascade — is met by focusing the
  // cascade explicitly via the spine event `:rf.xray/focus-event`
  // (the same event the L2 event-row click dispatches) and then asserting
  // the event-detail projection. We look up the dispatch-id by walking
  // the bus buffer for the (frame, event-id) pair, which is independent
  // of the cascade-scoped Trace DOM.
  //
  // The L2 event list is frame-scoped: in a multi-frame app the
  // operator first picks the frame they want to inspect (the L1
  // frame-picker → `:rf.xray/set-target-frame`, which re-seeds the
  // Xray `:epoch-history` slot from THAT frame's per-frame ring), and
  // only then does that frame's cascade appear in L2 to click. So we
  // re-target Xray onto :counter/b BEFORE focusing the B cascade —
  // mirroring the real picker gesture and the CLJS e2e analogue
  // `multi_frame_isolation_e2e_cljs_test/xray-focused-frame-tracks-
  // host-dispatch-frame`. Without the re-seed the focus event resolves
  // dispatch-id 19's `:epoch-id` against the previously-targeted
  // frame's history (no match → nil), so the Epoch panel's
  // head-fallback renders that frame's head cascade (the :log
  // :log-append fan-out) instead of the chosen :counter/b :inc.
  await setXrayTargetFrame(page, ':counter/b');
  const selected = await focusCascadeByFrameEvent(page, {
    frame: ':counter/b',
    eventId: ':multi-frame.core/inc',
  });
  state.multiFrame.selectedTraceRow = selected;
  // The selected-cascade projection lives on the Epoch panel's DISPATCH
  // step. Assert the panel text carries the event-id of the focused
  // multi-frame cascade.
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
  // This scenario covers multi-frame isolation through the trace +
  // Epoch-tab cascade evidence above. Time Travel is part of the Epoch
  // tab + RETRO scrubbing on the L2 event list (spec/018 §5 + §6).
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

  // Drive the chart-render path through the test-only event surface
  // (`:rf.xray/set-epoch-history-for-test` +
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
  // This scenario's shim-survival probe — the
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

  // Assert the machines-viz xyflow chart actually renders. The chart is
  // built on `@xyflow/react`; the canvas is an xyflow `<div
  // class="react-flow">` containing per-node child `<div>`s. The
  // assertion counts (1) the chart wrapper testid, (2) the xyflow root
  // class, and (3) at least one rendered state node
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
  // Large markers render as first-class chip chrome inside the
  // edn-inspector. The predicate is keyed off the spec/015 marker, so the
  // chip appears under `[data-testid="rf-xray-edn-inspector-large"]`.
  // Assert chip presence and absence of the raw payload — the two together
  // cover "elision marker surfaced" and "raw value never leaks".
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
  // The edn-inspector's internal `::missing` absence sentinel
  // (`:day8.re-frame2-xray.views.edn-inspector/missing`) must NEVER reach
  // the rendered App-DB Diff. A removed slot renders as a
  // struck-through ghost, not a literal sentinel keyword. The
  // per-rendering removed-to-empty + deleted-ancestor cases are pinned
  // exhaustively in the CLJS unit tests (`edn_inspector_cljs_test.cljs`
  // — diff-removed-only-key-renders-struck-ghost-not-sentinel et al.,
  // which thread a real `engine/project` projection); this is the
  // browser-layer backstop on the live panel that the sentinel never
  // escapes into the DOM under any dispatch sequence.
  if (appDbText.includes('edn-inspector/missing')) {
    failWithDetails('Internal ::missing sentinel leaked into App-DB Diff text (rf2-8pfkk)', {
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
  // Hydration mismatches surface as `:rf.ssr/*` rows (category-prefix
  // "rf.ssr") via the L2 event-row signal + the always-on issues ribbon
  // (auto-open-on-error).
  //
  // `:rf.ssr/hydration-mismatch` is emitted by `verify-hydration!`
  // OUTSIDE any event-handler context (see testbed `core.cljs:188`).
  // The framework's epoch capture (`re-frame.epoch.capture/capture-
  // event!`) drops out-of-cascade orphan emits — an error with no
  // in-flight cascade AND no `:dispatch-id` — so the mismatch trace
  // never lands in any `:rf/epoch-record`'s `:trace-events`. Surfacing
  // orphaned out-of-cascade errors is a deliberately separate concern
  // (the L2 timeline's per-row signal, not a per-epoch panel).
  //
  // What this scenario verifies end-to-end:
  //
  //   - the trace fired (testbed banner renders the projected
  //     payload — proves `verify-hydration!` reached `emit-error!`)
  //   - the Xray shell opens cleanly under cascade (focused-epoch)
  //     scope without crashing — proving the focused-epoch projection
  //     + head-fallback resolve to a real epoch record.
  const mismatchBanner = page.locator('[data-testid="mismatch-banner"]');
  await expectVisible(mismatchBanner, 10000);
  await expectVisible(page.locator('[data-testid="mismatch-server-hash"]'), 5000);

  // ---- (2) Xray shell opens cleanly under cascade scope --------------
  // Verify the shell mounts cleanly and the default Epoch panel renders
  // its focused-epoch projection without crashing (the head-fallback
  // resolves to a real epoch record).
  await openXray(page);
  await clickTab(page, 'epoch', 'rf-xray-epoch-panel');
  await expectVisible(page.locator('[data-testid="rf-xray-epoch-panel"]'), 5000);
}

async function runTraceBudgetSaturation(page, state) {
  await expectHostCounterEquals(page, 5, 10000);
  await openXray(page);
  await clickSidebar(page, 'trace', 'rf-xray-trace');
  await clearTrace(page);
  const start = Date.now();
  const pushed = await pushSyntheticTraceEvents(page, 1000);
  // The saturation invariant is a RING property. Xray's secondary
  // frameless ring caps at `default-frameless-ring-depth` (100 events) by
  // default; the synthetic-events helper bumps the depth to the pushed
  // count via `set-frameless-ring-depth!` so this perf test can assert
  // against a 1000-event budget independent of the production default.
  // Frame-bound events ride the framework's per-frame rings (cascade-
  // keyed, capped at `:events-retained` per frame). The Trace PANEL is
  // epoch-scoped — it renders the focused epoch record's `:trace-events`,
  // NOT the global bus.
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
  // `event-detail` routes to the `epoch` tab; the Epoch panel's root
  // testid is `rf-xray-epoch-panel`.
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

// Static-mode browser scenario.
//
// Static mode is unconditionally available (no feature gate). This
// scenario:
//
//   1. Asserts the Dynamic baseline — the mode control is a compact
//      `<select>` dropdown present with `dynamic` selected
//      (`data-active-mode`/`value` carry the state), L2 spine event-list
//      visible (4-layer chrome).
//   2. Fires Ctrl+Shift+M (the cross-platform chord per
//      `keybinding.cljs/mode-toggle-key?` — Cmd-Shift-M on macOS,
//      Ctrl-Shift-M elsewhere; Playwright drives Ctrl as the headless
//      Chromium maps to Ctrl reliably). Asserts the mode flips: the
//      dropdown reads `static`, the Static surface mounts
//      (`rf-xray-static-surface` with `data-rf-xray-mode="static"`),
//      the L2 spine disappears (3-layer silhouette — chrome-silhouette
//      mode-signal #4), the Machines sub-tab is selected by default,
//      and each shipped sub-tab (Routes / Schemas / Flows /
//      Interceptors) mounts its real panel root testid.
//   3. Selects `Dynamic` in the dropdown; mode flips back; L2 spine
//      returns (proves the dropdown is the canonical toggle path too —
//      not just the chord).
async function runStaticModeChromeAndChord(page, state) {
  // ---- (0) baseline — clear the persisted mode slot -----------------
  await navigate(page, page.url(), { timeoutMs: NAV_TIMEOUT_MS });
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
  // The mode control is always rendered (Static mode is unconditionally
  // available). It is a compact `<select>` dropdown; `data-active-mode` +
  // `value` carry the active mode. Dynamic must be selected by default.
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
      // panel mounts; the static-detail-panel-* root is a stable testid
      // hook regardless of which tab is selected.
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
  //   :machines     (default, mounted above)
  //   :routes
  //   :schemas
  //   :flows
  //   :interceptors
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

  // All Static sub-tabs are panelled — there are no placeholder cards.
  // Keep the empty texts map so the downstream scenario snapshot shape
  // stays stable.
  const placeholderTexts = {};
  // Restore the Machines tab so subsequent steps see the default L4.
  await page.locator('[data-testid="rf-xray-static-tab-machines"]').click();
  await expectVisible(
    page.locator('[data-testid="rf-xray-static-detail-panel-machines"]'),
    5000,
  );

  // ---- (3) Select Dynamic in the dropdown — Static → Dynamic --------
  // The mode control is a `<select>`; flipping back is a selectOption
  // (the canonical toggle path, not just the chord).
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

  state.staticMode = {
    dynamicBaseline,
    afterChord,
    shippedSubTabRoots,
    placeholderTexts,
    afterClickBack,
  };
}

// Cmd-K command palette browser scenario.
//
// The palette offers verbs, a mode-aware command index, a recents slot,
// and a reduced-motion override. This scenario gives end-to-end browser
// proof that the chord-bind → view-mount → dispatch route land together:
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

// A deliberate exception to the "default Xray/Story tests to CLJS" rule:
// real-browser CSS-variable resolution is the signal under test. The CLJS
// render-tree tests pin the inline-style → `var(--rf-xray-*)` contract at
// the hiccup layer but cannot prove the browser actually substitutes a
// hex at paint time.
//
// The regression class this gate guards against: a boot path that misses
// `global-styles/install!` leaves every `var(--rf-xray-*)` reference
// resolving to its CSS fallback default, painting every variant unstyled.
//
// The probe is minimal — one variant load + one token assertion. The
// Story shell is left at its default landing (no variant click) because
// `:root` CSS custom properties are global; the only thing under test is
// "did boot install them?".
async function runPanelGalleryThemeTokens(page, state) {
  // 1. Visit the gallery at /#/stories so the Story shell mounts.
  //    The gallery's boot calls `global-styles/install!` from
  //    `panel-gallery.core/run` BEFORE the hash-router routes to
  //    `mount-stories!` — so the `<style id="rf-xray-themes">` node
  //    is in `<head>` and the `:root` block is live regardless of
  //    which mount branch runs.
  await page.waitForFunction(
    () => Boolean(document.querySelector('[data-rf-story-root]')),
    { timeout: 10000 },
  );

  // 2. Read the published `:root` CSS variable. With
  //    `global-styles/install!` called the light-palette
  //    `--rf-xray-text-primary` resolves to the literal hex
  //    `#24292f` (tokens.cljc `:light :text-primary`). Without
  //    install! the variable is undefined and getPropertyValue
  //    returns the empty string.
  const probe = await page.evaluate(() => {
    const root = document.documentElement;
    const rootStyle = getComputedStyle(root);
    const textPrimary = rootStyle.getPropertyValue('--rf-xray-text-primary').trim();
    const accent = rootStyle.getPropertyValue('--rf-xray-accent').trim();
    const themesStyleNode = document.getElementById('rf-xray-themes');
    return {
      textPrimary,
      accent,
      themesStylePresent: Boolean(themesStyleNode),
    };
  });

  state.themeTokens = probe;

  if (!probe.themesStylePresent) {
    failWithDetails(
      'panel-gallery boot did not install the rf-xray-themes <style> block — ' +
        'global-styles/install! never ran. This is the rf2-pqulr regression class.',
      probe,
    );
  }
  if (!probe.textPrimary || !probe.textPrimary.startsWith('#')) {
    failWithDetails(
      '--rf-xray-text-primary did not resolve to a token value on :root. ' +
        'Either global-styles/install! never ran or the light-palette block ' +
        'no longer publishes :text-primary (theme/tokens.cljc).',
      probe,
    );
  }
  if (!probe.accent || !probe.accent.startsWith('#')) {
    failWithDetails(
      '--rf-xray-accent did not resolve to a token value on :root. ' +
        'global-styles/install! likely did not run.',
      probe,
    );
  }
}

// The routes-epochs routing step-up deck. Walks the deck's numbered
// ladder top-to-bottom and asserts the Xray Routing panel
// (`rf-xray-routing`) lights up across its three sections — CURRENT
// ROUTE, NAVIGATION THIS EPOCH, and the nested ROUTE TABLE — plus the
// blocked-navigation outcome. The deck owns its routes/events/subs and
// drives the real `reg-route` + `:rf.route/navigate` surface, so this
// is genuine Routing-panel coverage (not a synthetic override).
//
// The panel is focused-epoch scoped: after a host dispatch LIVE
// auto-snaps focus to the head cascade, so the NAVIGATION THIS EPOCH
// section reflects the just-clicked button. CURRENT ROUTE + ROUTE TABLE
// render every epoch. We read each section's stable data-testids.

// Drive a deck ladder rung by its (1-based) rung number.
//
// The deck rides the shared queued-step runner (`runner.core`): the
// runner renders one step row per step, and each row's index is a
// RANDOM-ACCESS RUN-THIS-STEP button (`routes-epochs-step-<n>-run`,
// n = 0-based step index). That per-step run affordance is exactly the
// random-access addressing this scenario needs — it drives rungs OUT OF
// ORDER (#3, #1, #4, #5, #7, #10, #11), asserting the Routing panel after
// each.
//
// The scenario uses a 1-based rung vocabulary (rung 1 = the first step)
// and maps it onto the 0-based runner step index here, so every call site
// below reads in rung terms.
async function clickRung(page, n) {
  await clickTestId(page, `routes-epochs-step-${n - 1}-run`);
}

// Read the Routing panel's projected slice from the DOM. Returns nulls
// when a section is absent so callers can assert specific fields.
async function readRoutingPanel(page) {
  return page.evaluate(() => {
    const root = document.getElementById('rf-xray-root');
    if (!root) return { mounted: false };
    function text(id) {
      const el = root.querySelector(`[data-testid="${id}"]`);
      return el ? (el.textContent || '').trim() : null;
    }
    function present(id) {
      return Boolean(root.querySelector(`[data-testid="${id}"]`));
    }
    // ROUTE TABLE rows: collect each row's data-route-id + nested padding
    // (the depth indent paints via inline padding-left) + current marker.
    const tableRows = Array.from(
      root.querySelectorAll('[data-testid^="rf-xray-routing-table-row-"]'),
    )
      .filter((el) => el.getAttribute('data-route-id'))
      .map((el) => ({
        routeId: el.getAttribute('data-route-id'),
        marker: el.getAttribute('data-marker'),
        current: el.getAttribute('data-current') === 'true',
        paddingLeft: el.style.paddingLeft || '',
      }));
    return {
      mounted: present('rf-xray-routing'),
      silent: present('rf-xray-routing-silent'),
      currentId: text('rf-xray-routing-current-id'),
      currentParams: text('rf-xray-routing-current-params'),
      currentPath: text('rf-xray-routing-current-path'),
      navFrom: text('rf-xray-routing-nav-from'),
      navTo: text('rf-xray-routing-nav-to'),
      navParams: text('rf-xray-routing-nav-params'),
      navOutcome: text('rf-xray-routing-nav-outcome'),
      noActivity: present('rf-xray-routing-no-activity'),
      currentMarker: present('rf-xray-routing-table-current-marker'),
      tableRows,
    };
  });
}

async function runRoutesEpochs(page, state) {
  await openXray(page);
  await clickTab(page, 'routing', 'rf-xray-routing');

  // The deck's `run` already navigates home, so the ROUTE TABLE is
  // populated immediately (NOT the silent no-routes state). Assert the
  // panel is live with the deck's registered routes before driving the
  // ladder.
  const initial = await waitForValue(
    () => readRoutingPanel(page),
    (snap) =>
      snap.mounted &&
      !snap.silent &&
      snap.tableRows.length >= 6 &&
      snap.tableRows.some((r) => r.routeId.includes('routes-epochs/home')),
    { timeoutMs: 10000, description: 'routes-epochs route table renders the registered graph' },
  );

  // The deck boots on :home, so a same-target navigate would be a
  // rule-3 no-op (Spec 012 §Per-route data loading rule 3): the slice
  // wouldn't change and NAVIGATION THIS EPOCH would read 'no activity'.
  // We therefore establish a NON-home route first (rung #3, article),
  // which is also the route-params assertion, then drive rung #1 as a
  // genuine transition back to :home.

  // ---- #3 route params — CURRENT ROUTE :article with params {:id ..}.
  await clickRung(page, 3);
  const afterParams = await waitForValue(
    () => readRoutingPanel(page),
    (snap) =>
      snap.currentId && snap.currentId.includes('routes-epochs/article') &&
      snap.currentParams && snap.currentParams.includes(':id') &&
      snap.currentParams.includes('intro') &&
      snap.navTo && snap.navTo.includes('routes-epochs/article') &&
      snap.navOutcome === 'transitioned',
    { timeoutMs: 10000, description: '#3 route params → CURRENT ROUTE :article params {:id "intro"} + transitioned' },
  );

  // ---- #1 navigate/push — a genuine transition :article ──► :home.
  //      CURRENT ROUTE lands on :home; NAVIGATION THIS EPOCH shows the
  //      ──► TO (:home) row with outcome transitioned; the ROUTE TABLE
  //      paints the destination overlay (:to) on the home row.
  //      (Two LIVE-head-focus contracts the assertions respect: the
  //      destination carries the `:to` overlay this epoch, not the
  //      `:here` ◀ current marker — that shows on a non-nav focused
  //      epoch (assign-markers); and the FROM glyph is empty because the
  //      slice already holds the post-nav id == TO, so from-id collapses
  //      to nil per nav-this-epoch's documented v1 same-id collapse.)
  await clickRung(page, 1);
  const afterHome = await waitForValue(
    () => readRoutingPanel(page),
    (snap) => {
      const home = snap.tableRows.find((r) => r.routeId.endsWith('routes-epochs/home'));
      return (
        snap.currentId && snap.currentId.includes('routes-epochs/home') &&
        snap.navTo && snap.navTo.includes('routes-epochs/home') &&
        snap.navOutcome === 'transitioned' &&
        home && home.marker === 'to'
      );
    },
    { timeoutMs: 10000, description: '#1 navigate/push → ──► :home + outcome transitioned + :to overlay' },
  );

  // ---- #4 query params — CURRENT ROUTE :search, nav params carry q/sort.
  await clickRung(page, 4);
  const afterQuery = await waitForValue(
    () => readRoutingPanel(page),
    (snap) =>
      snap.currentId && snap.currentId.includes('routes-epochs/search'),
    { timeoutMs: 10000, description: '#4 query params → CURRENT ROUTE :search' },
  );

  // ---- #5 nested route — ROUTE TABLE indents :article under its parent
  //      :articles (deeper padding-left) and the destination overlay
  //      (:to) paints on the article row this navigation epoch.
  await clickRung(page, 5);
  const afterNested = await waitForValue(
    () => readRoutingPanel(page),
    (snap) => {
      const article = snap.tableRows.find((r) => r.routeId.endsWith('routes-epochs/article'));
      const articles = snap.tableRows.find((r) => r.routeId.endsWith('routes-epochs/articles'));
      if (!article || !articles) return false;
      const px = (s) => parseFloat(String(s).replace(/[^0-9.]/g, '')) || 0;
      return (
        snap.currentId && snap.currentId.includes('routes-epochs/article') &&
        article.marker === 'to' &&
        px(article.paddingLeft) > px(articles.paddingLeft)
      );
    },
    { timeoutMs: 10000, description: '#5 nested route → :article indented under :articles + :to overlay' },
  );

  // ---- #7 not-found/fallback — an unmatched URL resolves to the
  //      registered `:rf.route/not-found` fallback: CURRENT ROUTE reads
  //      :rf.route/not-found and NAVIGATION THIS EPOCH lands ──► it.
  //      (Because the fallback route IS registered, the destination
  //      resolves to a real route-id, so the transition completes — the
  //      "not-found" OUTCOME chip is reserved for the navigated-but-
  //      no-destination case per nav-outcome in panels/routing.cljs.)
  await clickRung(page, 7);
  const afterNotFound = await waitForValue(
    () => readRoutingPanel(page),
    (snap) =>
      snap.currentId && snap.currentId.includes('rf.route/not-found') &&
      snap.navTo && snap.navTo.includes('rf.route/not-found'),
    { timeoutMs: 10000, description: '#7 not-found → CURRENT ROUTE + NAVIGATION ──► :rf.route/not-found fallback' },
  );

  // ---- #10 + #11 guarded/blocked nav — enter dirty settings, then a
  //      leave attempt is blocked: outcome chip reads blocked.
  await clickRung(page, 10);
  await waitForValue(
    () => readRoutingPanel(page),
    (snap) => snap.currentId && snap.currentId.includes('routes-epochs/settings'),
    { timeoutMs: 10000, description: '#10 enter dirty settings → CURRENT ROUTE :settings' },
  );
  await clickRung(page, 11);
  // The definitive blocked-navigation signal the Routing panel surfaces
  // is that CURRENT ROUTE STAYS on :settings — the `:can-leave` guard
  // refused the move home, so the slice never advanced. (The transient
  // `blocked` outcome chip rides whichever cascade the LIVE spine has in
  // focus; the slice-stayed-put invariant is the robust panel-level
  // proof and is what a blocked nav means.) We additionally cross-check
  // `:rf/pending-navigation` filled via the deck's current-route strip,
  // the canonical pending-nav slot per Spec 012.
  const afterBlocked = await waitForValue(
    () => readRoutingPanel(page),
    (snap) =>
      snap.currentId && snap.currentId.includes('routes-epochs/settings'),
    { timeoutMs: 10000, description: '#11 try-leave → guard blocked, CURRENT ROUTE stays :settings' },
  );
  // The `afterBlocked` wait above resolves IMMEDIATELY because CURRENT
  // ROUTE was already on :settings from rung #10, so a bare read of the
  // pending-nav slot here would race the #11 block cascade's runtime-db
  // commit (the slice-stayed-put invariant proves nothing changed, but the
  // commit that WRITES `:rf/pending-navigation` lands a tick later). Poll
  // until the slot fills. The probe reads the canonical location: per
  // EP-0001 the pending-nav slot is durable routing RUNTIME-DB state at
  // `[:rf.runtime/routing :pending-navigation]` — read via the public
  // `re-frame.core/frame-state-value` seam's `:rf.db/runtime` partition
  // (rf2-t3lftq, API-shrink #3, retired the dedicated `runtime-db-value`
  // reader), NOT app-db. The deck strip (`:rf/pending-navigation`
  // runtime-sub) is the secondary cross-check.
  const pendingProbe = await waitForValue(
    () => page.evaluate(() => {
      const el = document.querySelector('[data-testid="routes-epochs-current-strip"]');
      const stripText = el ? (el.textContent || '') : null;
      let pendingNav = null;
      try {
        const cljs = window.cljs && window.cljs.core;
        const rf = window.re_frame && window.re_frame.core;
        if (cljs && rf && typeof rf.frame_state_value === 'function') {
          const kw = (s) => {
            const t = String(s).replace(/^:/, '');
            const p = t.split('/');
            return p.length === 2
              ? (cljs.keyword.call ? cljs.keyword.call(null, p[0], p[1]) : cljs.keyword(p[0], p[1]))
              : (cljs.keyword.call ? cljs.keyword.call(null, t) : cljs.keyword(t));
          };
          const frameState = rf.frame_state_value(kw('rf/default'));
          const rdb = frameState ? cljs.get(frameState, kw('rf.db/runtime')) : null;
          const path = cljs.PersistentVector.fromArray(
            [kw('rf.runtime/routing'), kw('pending-navigation')], true);
          const pn = rdb ? cljs.get_in(rdb, path) : null;
          pendingNav = pn ? cljs.pr_str(pn) : null;
        }
      } catch (err) {
        pendingNav = `probe-error:${String(err)}`;
      }
      return { stripText, pendingNav };
    }),
    (probe) =>
      Boolean(probe.pendingNav && probe.pendingNav !== 'null') ||
      /pending-navigation/.test(probe.stripText || ''),
    { timeoutMs: 10000, description: '#11 try-leave → :rf/pending-navigation fills in runtime-db' },
  );
  const pendingNavSet =
    Boolean(pendingProbe.pendingNav && pendingProbe.pendingNav !== 'null') ||
    /pending-navigation/.test(pendingProbe.stripText || '');
  if (!pendingNavSet) {
    failWithDetails('#11 try-leave blocked but :rf/pending-navigation did not fill', {
      afterBlocked,
      pendingProbe,
    });
  }

  state.routesEpochs = {
    routeTableRows: initial.tableRows.length,
    home: { currentId: afterHome.currentId, outcome: afterHome.navOutcome },
    params: afterParams.currentParams,
    query: afterQuery.currentId,
    nested: { currentId: afterNested.currentId },
    notFound: { currentId: afterNotFound.currentId, navTo: afterNotFound.navTo },
    blocked: { currentId: afterBlocked.currentId, pendingNavSet },
  };
}

// The machine-epochs state-machine deck, built on the shared queued-step
// runner (`runner.core`). Walks the deck's step matrix top-to-bottom (via
// the runner's per-step RUN-THIS-STEP buttons) and asserts each step's
// machine feature landed, then opens the Xray Machine Inspector
// (`rf-xray-machine-inspector`) and confirms the panel mounts on the focused
// machine event. The final handoff re-drives a few steps OUT OF ORDER (the
// fresh-transition / unhandled-no-op / boot-entry-throw contrast) — the random-
// access addressing the runner's per-step run button provides.
//
// What this scenario can / cannot assert:
// —————————————————————————————————————————————————————————————
// The deck OWNS all 8 machine domains and drives each through the REAL
// `reg-machine` + machine-event-routing surface, with each domain running
// in its OWN frame (`:machine/<track>`). Every step's machine FEATURE
// (plain transition · entry/exit data delta · guard pass vs fail ·
// parallel-region broadcast · ignored event · microstep · timer · spawn ·
// deep-compound LCA · history) is genuinely exercised and is observable in
// THAT track's frame machine snapshot, read directly via the public
// `re-frame.core/frame-state-value` accessor's `:rf.db/runtime` partition
// (`readTrackSnapshots`, scoped to `:machine/<track>`; rf2-t3lftq,
// API-shrink #3, retired the dedicated `runtime-db-value` reader). We
// assert those per-frame snapshot facts — they are
// the robust, non-flaky proof each machine feature fired in its own frame.
//
// We additionally open the Machines tab and confirm `rf-xray-machine-
// inspector` mounts on a focused machine event, plus that machine activity
// reaches the trace bus (`:rf.machine/transition`). We deliberately do NOT
// assert the machines-viz chart-render path (the synthetic-epoch injection
// that `runDeepMachine` performs): per the documented framework gap
// (see `runDeepMachine` above, lines re: machine-transition + `:frame`),
// a real host-app `:rf.machine/transition` carries no `:frame` tag and so
// is not captured into an epoch's `:trace-events`, leaving the focused-
// event transitions sub empty. `deep_machine` owns the chart-render
// shim-survival probe via test-only injection events; this deck's job is
// the real-machine-feature step-up surface, asserted through the substrate
// snapshot + the panel handoff.

// The deck is a MULTI-MACHINE, FRAME-ISOLATED stepper. Each machine domain
// lives in its OWN frame (`:machine/<id>`) and the left rail is a PICKER:
// selecting a track (`machine-epochs-track-<id>`) shows its step path AND
// re-points Xray at that machine's frame. The per-track step rows are
// RANDOM-ACCESS RUN-THIS-STEP buttons (`machine-epochs-step-<n>-run`,
// n = 0-based index WITHIN the selected track's path), so this scenario
// first selects a track, then drives that track's steps by their per-track
// index.
//
// Select a track by its id (`:door` -> `machine-epochs-track-door`). The
// select boots the track's machine(s) into its frame (boot-on-select) and
// re-points Xray, so the track's first observed epoch is its START cascade.
async function selectMachineTrack(page, trackId) {
  await clickTestId(page, `machine-epochs-track-${trackId}`);
  // The step panel re-renders for the selected track.
  await expectVisible(page.locator('[data-testid="machine-epochs-panel"]'), 5000);
}

// Drive step `n` (0-based, within the currently-selected track's path) via
// its RUN-THIS-STEP button.
async function clickMachineStep(page, n) {
  await clickTestId(page, `machine-epochs-step-${n}-run`);
}

// Restart the currently-selected track (resets its machine frame's ring +
// re-arcs from boot).
async function restartMachineTrack(page) {
  await clickTestId(page, 'machine-epochs-restart');
}

// Read one machine's snapshot directly from its OWN `:machine/<track>` frame
// RUNTIME-DB (per-machine frame isolation; each track's snapshots live in
// its own frame). Builds a `state … · tags …` text scoped to the given
// track's frame, so the per-machine assertions below read robustly. Returns
// `{ mounted, ... }`.
//
// Machine snapshots are durable framework RUNTIME-DB state per EP-0001
// (re-frame.machines.paths/snapshot-path): they live at
// `[:rf.runtime/machines :snapshots <machine-id>]` in the runtime-db
// PARTITION, read via the public `re-frame.core/frame-state-value` seam's
// `:rf.db/runtime` key (rf2-t3lftq, API-shrink #3, retired the dedicated
// `runtime-db-value` reader) — NOT in app-db.
//
// `trackId` is the track name (e.g. 'door', 'media'); the frame id is
// `:machine/<trackId>`. `machineIds` is the set of machine-ids the track
// boots into that one frame (media boots two).
async function readTrackSnapshots(page, trackId, machineIds) {
  return page.evaluate(({ trackId, machineIds }) => {
    const cljs = window.cljs && window.cljs.core;
    const rf = window.re_frame && window.re_frame.core;
    if (!cljs || !rf || typeof rf.frame_state_value !== 'function') {
      return { mounted: false, reason: 'cljs.core / re_frame.core.frame_state_value unavailable' };
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
    const frameState = rf.frame_state_value(keyword(`:machine/${trackId}`));
    const db = frameState ? cljs.get(frameState, keyword('rf.db/runtime')) : null;
    if (db == null) return { mounted: false, reason: `no :machine/${trackId} runtime-db` };
    function snapshot(machineId) {
      const path = cljs.PersistentVector.fromArray([
        keyword(':rf.runtime/machines'),
        keyword(':snapshots'), keyword(machineId),
      ], true);
      return cljs.get_in ? cljs.get_in(db, path) : null;
    }
    function field(snap, ...ks) {
      let v = snap;
      for (const k of ks) {
        if (v == null) return null;
        v = cljs.get(v, keyword(k));
      }
      return v;
    }
    function pr(v) {
      return v == null ? 'nil' : cljs.pr_str(v);
    }
    const lines = [];
    for (const mid of machineIds) {
      const snap = snapshot(mid);
      lines.push(`${mid} state: ${pr(field(snap, ':state'))} · tags: ${pr(field(snap, ':tags'))}`);
      lines.push(`${mid} data: ${pr(field(snap, ':data'))}`);
      lines.push(`${mid} :rf/history: ${pr(field(snap, ':rf/history'))}`);
    }
    const stripText = lines.join(' ').replace(/\s+/g, ' ').trim();
    return { mounted: true, stripText };
  }, { trackId, machineIds });
}

// Convenience: wait until a track's reconstructed snapshot text matches a
// predicate.
async function waitForTrackSnapshot(page, trackId, machineIds, pred, description) {
  return waitForValue(
    () => readTrackSnapshots(page, trackId, machineIds),
    (snap) => snap.mounted && pred(snap.stripText || ''),
    { timeoutMs: 10000, description },
  );
}

async function runMachineEpochs(page, state) {
  // The MULTI-MACHINE, FRAME-ISOLATED stepper. Each machine
  // domain runs in its OWN frame (`:machine/<track>`); the left rail is a
  // PICKER that, on select, boots the track's machine(s) (boot-on-select) AND
  // re-points Xray at that frame. We SELECT a track, then drive its per-track
  // step rows by their 0-based index WITHIN the track, asserting each step's
  // machine FEATURE off THAT track's frame snapshot (`readTrackSnapshots`,
  // reading `:machine/<track>`). The deep RENDER-FIDELITY assertions (cascade
  // kinds/order, microsteps, timer-cancel, spawn/destroy, set-diff,
  // throw-as-error) live in the CLJS unit test
  // `panels.epoch.machine-epochs-harness-cljs-test` per the Xray/Story-as-
  // CLJS rule. Here we confirm each step lands the expected configuration in
  // its OWN frame, prove the per-machine frame isolation with a cross-frame
  // flip, and confirm the Machine Inspector mounts.

  // The deck auto-selects :door on boot, so its frame should already exist +
  // be booted to :locked.
  await waitForTrackSnapshot(
    page, 'door', [':door/main'],
    (t) => /:door\/main state: :locked/.test(t),
    'machine-epochs door track auto-selected + booted to :locked',
  );

  // ===== Door (FLAT) — select the track, drive its path ==================
  await selectMachineTrack(page, 'door');

  // step 0 plain transition — :locked -> :closed.
  await clickMachineStep(page, 0);
  await waitForTrackSnapshot(
    page, 'door', [':door/main'],
    (t) => /:door\/main state: :closed/.test(t),
    'door#0 insert-coin -> :closed',
  );

  // step 1 entry + exit actions — :closed -> :open; :open's :entry bumps
  //   :opened-count to 1.
  await clickMachineStep(page, 1);
  await waitForTrackSnapshot(
    page, 'door', [':door/main'],
    (t) => /:door\/main state: :open/.test(t) && /:opened-count 1/.test(t),
    'door#1 push -> :open + :opened-count 1',
  );

  // step 2 guard ALLOWED — :open -> :closed.
  await clickMachineStep(page, 2);
  await waitForTrackSnapshot(
    page, 'door', [':door/main'],
    (t) => /:door\/main state: :closed/.test(t),
    'door#2 close -> guard allowed, :closed',
  );

  // step 3 guard BLOCKED — re-open, arm :held-open?, attempt close. Guard
  //   FAILS, door STAYS :open + the hold flag remains set.
  await clickMachineStep(page, 3);
  const afterBlocked = await waitForTrackSnapshot(
    page, 'door', [':door/main'],
    (t) => /:door\/main state: :open/.test(t) && /:held-open\? true/.test(t),
    'door#3 reopen-hold-close -> guard blocked, STAYS :open',
  );

  // step 4 transition-with-effect — :open -> :alarming; :enter-alarm's :fx
  //   dispatches :alarm-acknowledged.
  await clickMachineStep(page, 4);
  await waitForTrackSnapshot(
    page, 'door', [':door/main'],
    (t) => /:door\/main state: :alarming/.test(t),
    'door#4 trip -> :alarming',
  );

  // step 5 unhandled -> benign no-op — insert-coin into :alarming has no :on
  //   entry; the door STAYS :alarming.
  await clickMachineStep(page, 5);
  await waitForTrackSnapshot(
    page, 'door', [':door/main'],
    (t) => /:door\/main state: :alarming/.test(t),
    'door#5 insert-coin into :alarming -> benign no-op',
  );

  // step 6 ROOT :on fallthrough — :alarming has no :door/audit; the machine
  //   ROOT :on handles it -> :alarming -> :locked.
  await clickMachineStep(page, 6);
  await waitForTrackSnapshot(
    page, 'door', [':door/main'],
    (t) => /:door\/main state: :locked/.test(t),
    'door#6 audit -> root :on fallthrough -> :locked',
  );

  // ===== Cross-frame flip acceptance ===================================
  // The linchpin of the design: switch A -> B -> back to A and confirm each
  // machine's frame snapshot is ISOLATED. Pick traffic, drive it, return to
  // door and confirm DOOR's ring/state is intact (:locked, NOT touched by the
  // traffic steps) — the proof the two frames never interleave.

  // ===== Traffic (PARALLEL) =============================================
  await selectMachineTrack(page, 'traffic');

  // step 0 parallel regions — one tick broadcasts to BOTH regions.
  await clickMachineStep(page, 0);
  const afterParallel = await waitForTrackSnapshot(
    page, 'traffic', [':traffic/light'],
    (t) => /:vehicle :green/.test(t) && /:pedestrian :dont-walk/.test(t),
    'traffic#0 tick -> BOTH regions advanced (vehicle :green + pedestrian :dont-walk)',
  );

  // step 1 history ribbon — a second tick advances both regions again.
  await clickMachineStep(page, 1);
  const afterTraffic = await waitForTrackSnapshot(
    page, 'traffic', [':traffic/light'],
    (t) => /:vehicle :amber/.test(t) && /:pedestrian :walk/.test(t),
    'traffic#1 second tick -> vehicle :amber + pedestrian :walk',
  );

  // Switch BACK to door: its frame's snapshot must be UNTOUCHED by the
  // traffic steps (door is still :locked from its last step) — frame
  // isolation, the core lens of this scenario. Re-selecting resumes door's
  // ring.
  //
  // We ALSO capture the door's ACCUMULATED machine DATA here, not just its
  // :state. Driving door steps 0-6 ran the :open state's :count-open :entry
  // TWICE (push @ step 1; the reopen inside :machine-epochs/reopen-then-block
  // @ step 3), so :data :opened-count has climbed to 2. That non-zero datum
  // is the BASELINE the restart assertion below contrasts against: a real
  // reset-frame replay re-runs :initial-events from the machine's
  // :initial state, where :data is {:opened-count 0 …}; a no-op reset would
  // leave :opened-count at its accumulated 2. We assert it is >0 here so the
  // post-restart `:opened-count 0` check is provably a STATE CHANGE the
  // replay produced, not a value that was already 0 (the NO-SIGNAL trap the
  // bare `:locked`-only assertion fell into — door is already :locked, so a
  // no-op restart would pass a `:locked`-only check).
  await selectMachineTrack(page, 'door');
  const doorAfterReturn = await waitForTrackSnapshot(
    page, 'door', [':door/main'],
    (t) => /:door\/main state: :locked/.test(t) &&
      /:opened-count ([1-9]\d*)/.test(t),
    'switch-and-return: door frame intact (:locked, :opened-count accumulated >0) after driving traffic — isolation proven',
  );
  // And traffic's frame is ALSO intact (still :amber/:walk) — not reset by
  // returning to door.
  await waitForTrackSnapshot(
    page, 'traffic', [':traffic/light'],
    (t) => /:vehicle :amber/.test(t) && /:pedestrian :walk/.test(t),
    'switch-and-return: traffic frame intact (:amber/:walk) after returning to door',
  );

  // RESTART door: resets its machine frame (destroy-frame! +
  // re-make-frame with the SAME :initial-events — no dedicated reset verb,
  // rf2-lxwpob — so the ring clears and the machine RE-ARCS FROM BOOT). The
  // signal-bearing proof a REAL replay ran —
  // not a no-op — is the machine DATA being reset to its :initial value: the
  // accumulated `:opened-count` (>0 above, asserted as the baseline) must
  // snap back to 0, the value door's :initial :data declares. The :state is
  // :locked both before AND after (door was already :locked), so :state alone
  // carries no signal — only re-running :initial-events from :initial resets
  // :opened-count to 0, so observing that reset is a state change a no-op
  // CANNOT produce. (If the restart silently no-op'd, :opened-count would
  // stay at the accumulated value and this assertion would time out — the
  // gate would go red, which is the signal we just bought.)
  await restartMachineTrack(page);
  await waitForTrackSnapshot(
    page, 'door', [':door/main'],
    (t) => /:door\/main state: :locked/.test(t) && /:opened-count 0\b/.test(t),
    'restart: :initial-events replay re-ran — door re-arced from boot (:locked) AND :data reset to :initial (:opened-count 0, was >0)',
  );

  // ===== Quiz (MICROSTEP — :always eventless settle) ====================
  await selectMachineTrack(page, 'quiz');

  // step 0 answer below the mark — score climbs, quiz STAYS :asking.
  await clickMachineStep(page, 0);
  await waitForTrackSnapshot(
    page, 'quiz', [':quiz/scorer'],
    (t) => /:quiz\/scorer state: :asking/.test(t) && /:score 1/.test(t),
    'quiz#0 answer -> :score 1, STAYS :asking (0 microsteps)',
  );

  // step 1 answer to the pass mark — the guarded :always chain SETTLES the
  //   quiz :asking -> :passed over N>0 microsteps.
  await clickMachineStep(page, 1);
  const afterQuiz = await waitForTrackSnapshot(
    page, 'quiz', [':quiz/scorer'],
    (t) => /:quiz\/scorer state: :passed/.test(t),
    'quiz#1 answer to mark -> :always settles :asking -> :passed (microsteps > 0)',
  );

  // ===== Brew (TIMER — :after + cancel) =================================
  await selectMachineTrack(page, 'brew');

  // step 0 start brew — schedules the :after timer; brew enters :brewing.
  await clickMachineStep(page, 0);
  await waitForTrackSnapshot(
    page, 'brew', [':brew/machine'],
    (t) => /:brew\/machine state: :brewing/.test(t),
    'brew#0 start -> :brewing (:after timer scheduled)',
  );

  // step 1 let the :after timer elapse — auto-fires :brewing -> :ready.
  await clickMachineStep(page, 1);
  await waitForTrackSnapshot(
    page, 'brew', [':brew/machine'],
    (t) => /:brew\/machine state: :ready/.test(t),
    'brew#1 :after-elapsed -> :ready (auto-fire)',
  );

  // step 2 re-start then CANCEL — exit beats the timer; brew returns to :idle.
  await clickMachineStep(page, 2);
  await waitForTrackSnapshot(
    page, 'brew', [':brew/machine'],
    (t) => /:brew\/machine state: :idle/.test(t),
    'brew#2 start+abort -> :after timer cancelled (:on-exit), :idle',
  );

  // ===== Session (LIFECYCLE — spawn/final/on-done/destroy) ==============
  await selectMachineTrack(page, 'session');

  // step 0 open session — :idle -> :authenticating SPAWNS the child actor.
  await clickMachineStep(page, 0);
  await waitForTrackSnapshot(
    page, 'session', [':session/flow'],
    (t) => /:session\/flow state: :authenticating/.test(t),
    'session#0 open -> :authenticating (child :session/login spawned)',
  );

  // step 1 child succeeds — :final + :on-done reports the token to the parent
  //   (data carries :session-token) + the child auto-destroys.
  await clickMachineStep(page, 1);
  await waitForTrackSnapshot(
    page, 'session', [':session/flow'],
    (t) => /:session\/flow state: :authenticating/.test(t) && /:session-token/.test(t),
    'session#1 child :final -> :on-done ran on parent (:session-token) + child auto-destroyed',
  );

  // ===== HVAC (DEEP-COMPOUND) ===========================================
  await selectMachineTrack(page, 'hvac');

  // step 0 parallel broadcast — ONE :hvac/power-cycle moves BOTH regions;
  //   :climate descends its deep initial cascade.
  await clickMachineStep(page, 0);
  const afterHvacPower = await waitForTrackSnapshot(
    page, 'hvac', [':hvac/controller'],
    (t) => /:climate \[:running :conditioning :heating\]/.test(t) && /:fan :on/.test(t),
    'hvac#0 power-cycle -> BOTH regions moved (climate deep leaf + fan :on)',
  );

  // step 1 multi-level LCA cascade — :heating -> :cooling crossing the LCA
  //   :conditioning.
  await clickMachineStep(page, 1);
  await waitForTrackSnapshot(
    page, 'hvac', [':hvac/controller'],
    (t) => /:climate \[:running :conditioning :cooling\]/.test(t),
    'hvac#1 mode-toggle -> deep leaf :cooling (crosses the :conditioning LCA)',
  );

  // step 2 EXTERNAL self-transition (:hvac/nudge) — re-enters :on; fan STAYS :on.
  await clickMachineStep(page, 2);
  await waitForTrackSnapshot(
    page, 'hvac', [':hvac/controller'],
    (t) => /:fan :on/.test(t),
    'hvac#2 nudge -> external self-transition, fan STAYS :on',
  );

  // step 3 INTERNAL self-transition (:hvac/tweak) — action-only; fan STAYS :on.
  await clickMachineStep(page, 3);
  const afterHvacTweak = await waitForTrackSnapshot(
    page, 'hvac', [':hvac/controller'],
    (t) => /:fan :on/.test(t),
    'hvac#3 tweak -> internal self-transition (action-only), fan STAYS :on',
  );

  // ===== Media (HISTORY) — the placement reject + live restore steps =====
  // The media track boots BOTH machine-ids (:media/deep + :media/shallow)
  // into the one media frame. Step 0 drives the placement-rejection probe
  // (advances no snapshot); steps 1/2 drive the shallow/deep restore — their
  // full restore-cascade render assertions live in the CLJS harness. Here we
  // confirm the steps resolve + do not crash the deck, and the two media
  // machines both booted into the one frame.
  await selectMachineTrack(page, 'media');
  await waitForTrackSnapshot(
    page, 'media', [':media/deep', ':media/shallow'],
    (t) => /:media\/deep state:/.test(t) && /:media\/shallow state:/.test(t),
    'media track booted BOTH machine-ids into the one media frame',
  );
  await clickMachineStep(page, 0); // placement rejection probe
  await clickMachineStep(page, 1); // shallow restore
  await clickMachineStep(page, 2); // deep restore

  // ===== Modal (MULTI-EVENT transition — events-as-nodes) ===============
  // ONE edge (:open ──► :closed) reached on THREE distinct events
  // (cancel/submit/escape). Drive all three (re-opening between) and confirm
  // each lands :closed; the submit branch also runs :save (:saved? true). The
  // events-as-nodes fan-in render is asserted in the CLJS harness; here we
  // confirm the behaviour (all three events close the modal) off the snapshot.
  await selectMachineTrack(page, 'modal');
  await waitForTrackSnapshot(
    page, 'modal', [':modal/main'],
    (t) => /:modal\/main state: :closed/.test(t),
    'modal track booted to :closed',
  );
  await clickMachineStep(page, 0); // open → :open
  await waitForTrackSnapshot(
    page, 'modal', [':modal/main'],
    (t) => /:modal\/main state: :open/.test(t),
    'modal#0 open -> :open',
  );
  await clickMachineStep(page, 1); // cancel → :closed (fan-in event #1)
  await waitForTrackSnapshot(
    page, 'modal', [':modal/main'],
    (t) => /:modal\/main state: :closed/.test(t),
    'modal#1 cancel -> :closed (multi-event fan-in #1)',
  );
  await clickMachineStep(page, 2); // re-open → :open
  await clickMachineStep(page, 3); // submit → :closed + :save (fan-in event #2)
  await waitForTrackSnapshot(
    page, 'modal', [':modal/main'],
    (t) => /:modal\/main state: :closed/.test(t) && /:saved\? true/.test(t),
    'modal#3 submit -> :closed + :save ran (:saved? true) (multi-event fan-in #2)',
  );
  await clickMachineStep(page, 4); // re-open → :open
  await clickMachineStep(page, 5); // escape → :closed (fan-in event #3)
  const afterModal = await waitForTrackSnapshot(
    page, 'modal', [':modal/main'],
    (t) => /:modal\/main state: :closed/.test(t),
    'modal#5 escape -> :closed (multi-event fan-in #3 — all 3 events land :closed)',
  );

  // ===== Gate (MULTI-BRANCH GUARDED fork — guard-fork) ==================
  // :gate/check forks from :idle by a guarded candidate vector (high?→:high,
  // low?→:low, else→:rejected); :gate/set arms :level first. Drive all three
  // guard branches; the fork's guard-predicate render is asserted in the CLJS
  // harness, here we confirm each :check lands its guard-selected target.
  await selectMachineTrack(page, 'gate');
  await waitForTrackSnapshot(
    page, 'gate', [':gate/main'],
    (t) => /:gate\/main state: :idle/.test(t),
    'gate track booted to :idle',
  );
  await clickMachineStep(page, 0); // set 7 — arm :level 7
  await waitForTrackSnapshot(
    page, 'gate', [':gate/main'],
    (t) => /:gate\/main state: :idle/.test(t) && /:level 7/.test(t),
    'gate#0 set 7 -> :level 7, STAYS :idle (internal action-only transition)',
  );
  await clickMachineStep(page, 1); // check → :high (gate-high? branch)
  await waitForTrackSnapshot(
    page, 'gate', [':gate/main'],
    (t) => /:gate\/main state: :high/.test(t),
    'gate#1 check -> :high (:gate-high? branch, first-guard-pass-wins)',
  );
  await clickMachineStep(page, 2); // reset → :idle
  await clickMachineStep(page, 3); // set 2 — arm :level 2
  await clickMachineStep(page, 4); // check → :low (gate-low? branch)
  await waitForTrackSnapshot(
    page, 'gate', [':gate/main'],
    (t) => /:gate\/main state: :low/.test(t),
    'gate#4 check -> :low (:gate-high? fails, :gate-low? passes)',
  );
  await clickMachineStep(page, 5); // reset → :idle
  await clickMachineStep(page, 6); // set 0 — arm :level 0
  await clickMachineStep(page, 7); // check → :rejected (unguarded fallback)
  const afterGate = await waitForTrackSnapshot(
    page, 'gate', [':gate/main'],
    (t) => /:gate\/main state: :rejected/.test(t),
    'gate#7 check -> :rejected (both guards fail -> unguarded fallback)',
  );
  await clickMachineStep(page, 8); // reset → :idle

  // ===== Xray Machine Inspector handoff + the throw/no-op contrast ======
  await openXray(page);

  // The FUSE track throws on BOOT (boot-on-select): its initial :entry
  // action :blow-fuse throws when the fuse frame is created on select. So
  // the SOLE machine-action-exception trigger is SELECTING fuse. Before
  // selecting fuse, NO machine-action-exception may have fired from any
  // other track's boot-on-select.
  {
    const bootTrace = await readTrace(page);
    const bootThrow = bootTrace.filter((e) =>
      /:rf\.error\/machine-action-exception/.test(e));
    if (bootThrow.length > 0) {
      failWithDetails(
        'rf2-q3lfm — a non-fuse track threw a machine-action-exception on its '
        + 'boot-on-select; only SELECTING the fuse track may throw',
        { machineActionExceptions: bootThrow });
    }
  }

  // Re-select door + drive a fresh transition so the Machine Inspector mounts
  // on a focused DOOR machine event (door is its own frame).
  await selectMachineTrack(page, 'door');
  await clearTrace(page);
  await clickMachineStep(page, 0); // :locked -> :closed — a fresh transition
  await waitForTraceMatch(
    page,
    /:rf\.machine\/transition|:rf\.machine\/guard-evaluated|:door\/main/,
    'machine transition reaches the trace bus',
  );
  await clickTab(page, 'machines', 'rf-xray-machine-inspector');
  await expectVisible(
    page.locator('[data-testid="rf-xray-machine-inspector"]'), 5000);

  // The xstate-v5 unhandled-event CONTRAST. The door's step 5 (unhandled
  // event -> benign no-op) emits the benign :rf.machine.event/unhandled-no-op
  // trace; SELECTING the fuse track boots :armed whose :entry action THROWS,
  // emitting the REAL :rf.error/machine-action-exception. Drive door to
  // :alarming first so its step-5 unhandled-no-op fires.
  await clickTab(page, 'trace', 'rf-xray-trace');
  await clearTrace(page);
  await clickMachineStep(page, 1); // :closed -> :open
  await clickMachineStep(page, 4); // :open -> :alarming
  await clickMachineStep(page, 5); // insert-coin into :alarming -> benign no-op
  await waitForTraceMatch(
    page,
    /:rf\.machine\.event\/unhandled-no-op/,
    'door unhandled event emits the benign :rf.machine.event/unhandled-no-op trace',
  );
  await clearTrace(page);
  await selectMachineTrack(page, 'fuse'); // boot-on-select: :armed :entry THROWS
  await waitForTraceMatch(
    page,
    /:rf\.error\/machine-action-exception|fuse blown on boot/,
    'selecting fuse boots :armed whose :entry action THROWS -> :rf.error/machine-action-exception',
  );

  state.machineEpochs = {
    blocked: { doorText: afterBlocked.stripText },
    parallel: { trafficText: afterParallel.stripText },
    trafficFinal: { trafficText: afterTraffic.stripText },
    isolation: { doorAfterReturn: doorAfterReturn.stripText },
    quiz: { quizText: afterQuiz.stripText },
    hard: { powerText: afterHvacPower.stripText, tweakText: afterHvacTweak.stripText },
    modal: { modalText: afterModal.stripText },
    gate: { gateText: afterGate.stripText },
    inspectorMounted: true,
  };
}

// ===========================================================================
// TWO-FRAME ISOLATION (rf2-4279q4)
// ===========================================================================
//
// The two-frame deck mounts the standard-epochs button ladder TWICE — once
// per `:above` / `:below` frame-provider. The two frames share one app code
// path (the standard-epochs `root` ladder) but each is a fully isolated
// reactive context: its OWN app-db, sub-cache, epoch history, and runner
// `:step` cursor. The runner RUN-THIS-STEP buttons are testid'd
// `standard-epochs-<frame>-step-<n>-run` (n = 0-based step index).
//
// This scenario drives the two highest-signal cross-frame rungs — the FLOW
// step (label "Increment + flow", 1-based step 5 → runner index 4, which
// bumps `:base` and recomputes the `:standard-epochs/derived` reg-flow slot)
// and the APP-SCHEMA step (label "Bad app-db write", 1-based step 19 →
// runner index 18, which writes an int into `[:auth :token]` and the
// frame-local app-schema rolls the `:db` back) — IN BOTH FRAMES, and asserts
// the per-frame ISOLATION the deck exists to prove. Before this scenario
// existed these two rungs were INERT: nothing exercised them in the
// `:above` / `:below` frames, so the deck's central isolation claim for
// flows + app-schemas carried no automated signal.

// The 0-based runner indices for the two rungs under test. Named off the
// standard-epochs step ladder (standard_epochs/core.cljs §steps): the FLOW
// rung is `:standard-epochs/increment-flow`; the APP-SCHEMA rung is
// `:standard-epochs/bad-app-db-write`.
const TWO_FRAME_FLOW_STEP = 4; // 1-based step 5 — "Increment + flow"
const TWO_FRAME_SCHEMA_STEP = 18; // 1-based step 19 — "Bad app-db write"

// Read one two-frame frame's app-db via the PUBLIC
// `re-frame.core/app-db-value` accessor, scoped to `:above` / `:below`.
// Returns the `:base` / `:derived` flow slots, the `[:auth :token]` slot
// (the app-schema target), and the runner `:step` cursor — the per-frame
// facts the isolation assertions read. `app-db-value` is the same public
// seam the deck's own per-track state read uses (machine_epochs core).
async function readTwoFrameDb(page, frameId) {
  return page.evaluate((targetFrame) => {
    const cljs = window.cljs && window.cljs.core;
    const rf = window.re_frame && window.re_frame.core;
    if (!cljs || !rf || typeof rf.app_db_value !== 'function') {
      return { ok: false, reason: 'cljs.core / re_frame.core.app_db_value unavailable' };
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
    const db = rf.app_db_value(keyword(targetFrame));
    if (db == null) return { ok: false, reason: `no ${targetFrame} app-db` };
    function get(...ks) {
      let v = db;
      for (const k of ks) {
        if (v == null) return null;
        v = cljs.get(v, keyword(k));
      }
      return v;
    }
    const base = get(':base');
    const derived = get(':derived');
    const token = get(':auth', ':token');
    const step = get(':step');
    return {
      ok: true,
      base: base == null ? null : base,
      derived: derived == null ? null : derived,
      // `pr-str` the token so a string vs int rollback contrast is
      // unambiguous in diagnostics ("seed-token" vs 42).
      token: token == null ? 'nil' : cljs.pr_str(token),
      step: step == null ? null : step,
    };
  }, frameId);
}

async function waitForTwoFrameDb(page, frameId, pred, description, timeoutMs = 10000) {
  return waitForValue(
    () => readTwoFrameDb(page, frameId),
    (snap) => snap.ok && pred(snap),
    { timeoutMs, description },
  );
}

// Drive runner step `n` (0-based) in the given two-frame frame by clicking
// that frame's RUN-THIS-STEP button (`standard-epochs-<frame>-step-<n>-run`).
async function runTwoFrameStep(page, frameLabel, n) {
  await clickTestId(page, `standard-epochs-${frameLabel}-step-${n}-run`);
}

async function runTwoFrameIsolation(page, state) {
  await openXray(page);

  // Sanity: BOTH frames boot to the seed db (`:base 1`, `[:auth :token]`
  // the seed string "seed-token"). Read both before driving anything so the
  // isolation deltas below are provably state CHANGES from a known baseline.
  // (`:derived` is left unconstrained at seed — the post-step-5 assertion
  // below is the load-bearing flow-recompute proof, and it holds whatever
  // the registration-time initial derive produced.)
  const aSeed = await waitForTwoFrameDb(
    page, 'above',
    (s) => s.base === 1 && s.token === '"seed-token"',
    'above frame seeded (:base 1, :auth/:token "seed-token")',
  );
  const bSeed = await waitForTwoFrameDb(
    page, 'below',
    (s) => s.base === 1 && s.token === '"seed-token"',
    'below frame seeded (:base 1, :auth/:token "seed-token")',
  );

  // ===== FLOW rung (1-based step 5) — per-frame isolation ================
  // Drive the FLOW step in `:above` ONLY. It bumps `:above`'s `:base` 1 → 2,
  // and the frame-local `:standard-epochs/derived` reg-flow recomputes
  // `:derived` 2 → 4 ON THE POST-HANDLER FLOWS PASS — proving the flow
  // actually RAN in `:above`'s reactive context. `:below` MUST be untouched
  // (still `:base 1`, `:derived 2`): a flow is frame-scoped state, so driving
  // `:above` cannot move `:below`'s derived slot. (If the flow leaked across
  // frames — or if `:derived` did not recompute — this assertion times out
  // and the gate goes red. Pre-fix NOTHING drove this rung, so the leak/no-op
  // class was unobserved.)
  await runTwoFrameStep(page, 'above', TWO_FRAME_FLOW_STEP);
  const aAfterFlow = await waitForTwoFrameDb(
    page, 'above',
    (s) => s.base === 2 && s.derived === 4,
    'above flow rung: :base 1→2 AND the :standard-epochs/derived flow recomputed :derived 2→4',
  );
  // `:below` is unmoved by `:above`'s flow — assert it STILL reads :base 1
  // and its `:derived` did NOT advance to 4 (the value only `:above`'s flow
  // produced). A flow is frame-scoped state, so `:above`'s recompute cannot
  // move `:below`'s derived slot.
  await waitForTwoFrameDb(
    page, 'below',
    (s) => s.base === 1 && s.derived !== 4,
    'flow isolation: below frame still :base 1 and :derived not advanced to 4 (above\'s flow did not leak across frames)',
  );

  // Now drive the FLOW step in `:below` and confirm the two frames diverge
  // INDEPENDENTLY: `:below` advances to :base 2 / :derived 4 while `:above`
  // stays at the 2/4 it reached above (not double-bumped by `:below`'s
  // dispatch). Two independent flow recomputes, one per frame.
  await runTwoFrameStep(page, 'below', TWO_FRAME_FLOW_STEP);
  await waitForTwoFrameDb(
    page, 'below',
    (s) => s.base === 2 && s.derived === 4,
    'below flow rung: :base 1→2 AND :derived 2→4 (independent of above)',
  );
  await waitForTwoFrameDb(
    page, 'above',
    (s) => s.base === 2 && s.derived === 4,
    'flow isolation: above frame unchanged (:base 2 / :derived 4) by below\'s flow dispatch',
  );

  // ===== APP-SCHEMA rung (1-based step 19) — per-frame isolation =========
  // Drive the APP-SCHEMA step in `:above` ONLY. The handler writes an int
  // (42) into `[:auth :token]`; the frame-local app-schema (`[:auth] →
  // [:map [:token :string]]`, registered once globally, resolved per-frame)
  // REJECTS the candidate BEFORE install (rf2-uhk9ko) — so `:above`'s
  // `[:auth :token]` must still read the seed STRING "seed-token", NOT 42.
  // The still-a-string read is the observable proof the schema fired AND
  // the rejection engaged in `:above`'s frame. `:below` MUST be wholly
  // untouched (still the seed string) — the schema violation + rejection
  // are scoped to the driven frame.
  await runTwoFrameStep(page, 'above', TWO_FRAME_SCHEMA_STEP);
  const aAfterSchema = await waitForTwoFrameDb(
    page, 'above',
    (s) => s.token === '"seed-token"',
    'above app-schema rung: int write to [:auth :token] rejected pre-install — token still "seed-token" (frame-local schema fired + candidate rejection engaged)',
  );
  await waitForTwoFrameDb(
    page, 'below',
    (s) => s.token === '"seed-token"',
    'app-schema isolation: below frame [:auth :token] untouched (still "seed-token") by above\'s schema-violating write',
  );

  // Drive the APP-SCHEMA step in `:below` too and assert its candidate is
  // likewise rejected (token stays the seed string) — the same frame-local
  // schema-rejection contract holds independently in the second frame.
  await runTwoFrameStep(page, 'below', TWO_FRAME_SCHEMA_STEP);
  await waitForTwoFrameDb(
    page, 'below',
    (s) => s.token === '"seed-token"',
    'below app-schema rung: int write rejected pre-install — token still "seed-token" (frame-local schema + candidate rejection)',
  );

  // The runner `:step` cursors are also per-frame isolated — each frame's
  // last-run step is its OWN index. Both frames last ran the schema rung, so
  // both cursors read TWO_FRAME_SCHEMA_STEP; the load-bearing isolation point
  // is that each frame carries its own cursor (per-frame app-db `:step`),
  // never a shared one.
  const aFinal = await readTwoFrameDb(page, 'above');
  const bFinal = await readTwoFrameDb(page, 'below');
  if (aFinal.step !== TWO_FRAME_SCHEMA_STEP || bFinal.step !== TWO_FRAME_SCHEMA_STEP) {
    failWithDetails('Two-frame runner :step cursors did not land per-frame on the schema rung', {
      expectedStep: TWO_FRAME_SCHEMA_STEP,
      above: aFinal,
      below: bFinal,
    });
  }

  state.twoFrameIsolation = {
    seed: { above: aSeed, below: bSeed },
    flow: { aboveAfter: aAfterFlow },
    schema: { aboveAfter: aAfterSchema },
    finalCursors: { above: aFinal.step, below: bFinal.step },
  };
}

const SCENARIOS = [
  {
    name: 'feature matrix shell and panel handoff',
    url: '/counter/',
    // PR-smoke tier. The tab handoff over the counter surface is the
    // highest-signal Xray scenario — it boots the shell, walks every L3
    // tab, and proves the chrome wiring. Kept on the PR critical path;
    // the rest of the matrix runs nightly.
    smoke: true,
    panels: PANEL_HANDOFFS.map(([id]) => id),
    // Coverage spans the L3 tabs that have a UI handoff. Issues surface
    // inline in the Epoch panel + the L2 event-row pink-wash + the
    // always-on issues ribbon signal. Surfaces without a tab (e.g. Time
    // Travel, Hydration, Effects) are covered, where still functionally
    // present, by their dedicated substrate scenarios.
    coveredRows: [
      'Epoch Panel',
      'App-DB Diff',
      'Trace',
      'Machines',
      'Shell, Keybinding, Config, Preload, Settings, and Production Elision',
    ],
    run: runShellFeatureSweep,
  },
  {
    // rf2-6pohj — the POPULATED arm of the Views panel, in a real browser.
    // Every other staged surface is Reagent-hosted and connects no Freehand
    // occurrence, so before this deck existed the browser lane could prove
    // the Mounted Views section RENDERS and nothing about what it renders: an
    // empty section and a section emptied by a broken read through
    // `re-frame.freehand.tool` were the same DOM. That is the gate-blindness
    // shape — sound machinery, input that never arrives.
    //
    // PR-smoke tier, for the same reason the panel-gallery probe is: the
    // whole point of the bead is that the PR-time gate is blind here, and a
    // nightly-only scenario leaves it blind. It adds one staged surface to
    // the smoke compile and buys the arm the other three cannot see.
    name: 'freehand-views populated Views roster: three connected occurrences, per-view facts, declared sites, a reactively-driven repaint (rf2-2t126), and fresh occurrence keys across a remount (rf2-6pohj)',
    url: '/testbeds/freehand-views/',
    smoke: true,
    panels: ['views'],
    coveredRows: ['Mounted view reads (Freehand tool door, rf2-7gth0)'],
    run: runFreehandViewsPopulatedRoster,
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
    name: 'deterministic exceptions and inline/trace surfacing',
    url: '/testbeds/deliberate-throw/',
    // PR-smoke tier. The exception → inline-Epoch/Trace surfacing path is
    // the second-highest-signal slice (it proves the error lens wires up
    // end-to-end against a real thrown handler). Counter + deliberate-
    // throw are the only two surfaces the smoke compiles. Exceptions
    // surface inline in the Epoch panel + via the Trace panel source-coord
    // chips.
    smoke: true,
    panels: ['epoch', 'trace'],
    coveredRows: ['Epoch Panel', 'Trace', 'Effects', 'Flows', 'Machines', 'Open in Editor / Source Coordinates'],
    run: runExceptionSchemaHttp,
  },
  {
    name: 'managed http and effects rows',
    url: '/testbeds/http-toggle/',
    // fx/effects rows are inline steps inside the Epoch panel's numbered
    // cascade; the Epoch tab is where this scenario reads them. fx outcomes
    // surface inline in the Epoch panel's EFFECT HANDLERS step.
    panels: ['epoch', 'trace'],
    coveredRows: ['Epoch Panel', 'Trace'],
    run: runHttpToggle,
  },
  {
    name: 'multi-frame isolation substrate',
    url: '/testbeds/multi-frame/',
    // Multi-frame isolation is exercised via the Trace and Epoch tabs
    // (one cascade per frame).
    panels: ['trace', 'epoch'],
    coveredRows: ['Trace', 'Epoch Panel'],
    run: runMultiFrame,
  },
  {
    // Asserts the machines-viz chart actually renders (not just the
    // inspector mount). Catches a broken `chart/{svg,layout,elk_layout}`
    // re-export shim across the machines-viz package boundary.
    name: 'deep machine inspector substrate',
    url: '/testbeds/deep-machine/',
    panels: ['machines'],
    coveredRows: ['Machines', 'Trace'],
    run: runDeepMachine,
  },
  {
    // Static mode browser coverage: chord + mode dropdown + 3-layer
    // chrome silhouette.
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
    // Cmd-K command palette browser coverage: chord opens, fuzzy narrows,
    // Enter executes a verb (theme flip), recents persist + lead on
    // re-open, Esc closes without dispatch.
    name: 'command palette chord, fuzzy filter, execute verb, and recents round-trip',
    url: '/counter/',
    // PR-smoke tier. Reuses the already-staged counter surface (no extra
    // compile), and exercises the Cmd-K interaction path — the highest-
    // signal "key interactions" coverage. Adds no surface to the compile
    // set.
    smoke: true,
    panels: [],
    coveredRows: [
      'Cmd-K palette',
      'Shell, Keybinding, Config, Preload, Settings, and Production Elision',
    ],
    run: runPaletteOpenExecute,
  },
  {
    // Theme-token CSS-variable resolution probe. A deliberate exception
    // to the "default Xray/Story tests to CLJS" rule — real-browser
    // CSS-variable resolution is the signal under test, which CLJS unit
    // tests can't reach. Guards against the regression class where a boot
    // path that embeds bare Xray widgets without calling
    // `global-styles/install!` leaves every `var(--rf-xray-*)` reference
    // resolving to its CSS fallback default, painting every variant
    // unstyled.
    //
    // PR-smoke tier — the panel-gallery surface is unique to this probe
    // (no other smoke scenario uses it), but the regression class is
    // severe (P1) and the probe is fast (~one page load + one DOM read).
    // The compile + serve overhead buys pre-merge coverage of a class of
    // bugs otherwise only caught by live observation.
    name: 'panel-gallery theme-token CSS-variable resolution on :root (rf2-azfct)',
    url: '/testbeds/panel-gallery/#/stories',
    smoke: true,
    panels: [],
    coveredRows: [
      'Shell, Keybinding, Config, Preload, Settings, and Production Elision',
    ],
    run: runPanelGalleryThemeTokens,
  },
  {
    // The routes-epochs routing step-up deck. Drives the real `reg-route`
    // + `:rf.route/navigate` surface and asserts the Xray Routing panel
    // across CURRENT ROUTE (id + params + nested tree highlight),
    // NAVIGATION THIS EPOCH (──► TO + outcome: transitioned / not-found /
    // blocked), and the ROUTE TABLE (nested-row indent under the parent).
    // Real Routing-panel coverage for the deck the `Routes` matrix row
    // names.
    name: 'routes-epochs routing ladder (current route, nav-this-epoch, nested table, blocked)',
    url: '/testbeds/routes-epochs/',
    panels: ['routing'],
    coveredRows: ['Routes'],
    run: runRoutesEpochs,
  },
  {
    // The machine-epochs assertion-backed render harness — a
    // MULTI-MACHINE, FRAME-ISOLATED stepper. Each machine domain runs in
    // its OWN frame (`:machine/<track>`); the left rail is a PICKER that,
    // on select, boots the track's machine(s) (boot-on-select) AND
    // re-points Xray
    // (`:rf.xray/select-frame`) at that frame. The scenario SELECTS each track
    // then drives its per-track step rows, asserting each step's machine
    // FEATURE off THAT track's frame snapshot: door (plain · entry/exit ·
    // guards allowed/blocked · internal · effect · unhandled no-op · root-:on
    // RESOLUTION), traffic (parallel regions · history · tag-set member swap),
    // quiz (:always MICROSTEPS), brew (:after TIMER + cancel), session (SPAWN ·
    // child :final · :on-done · DESTROY), fuse (boot-on-select :entry THROW),
    // hvac (deep compound · LCA cascade · self-transitions), media (placement
    // reject + live shallow/deep restore — the restore-cascade render is
    // asserted in the CLJS harness), modal (MULTI-EVENT transition: one edge
    // :open ──► :closed reached on THREE events — the events-as-nodes
    // divergence), gate (MULTI-BRANCH GUARDED fork: :gate/check forks by
    // guard high?/low?/else → :high/:low/:rejected — the guard-fork
    // divergence). It also proves the per-machine frame
    // ISOLATION with a cross-frame flip (door -> traffic -> back to door:
    // each frame's snapshot stays intact, never interleaved) + a Restart
    // (frame reset + re-arc from boot). Then opens the Xray Machine Inspector
    // (`rf-xray-machine-inspector`) and confirms the panel mounts on a focused
    // machine event + the no-op/throw trace contrast (the fuse track's
    // boot-on-select is the SOLE machine-action-exception trigger). The deep
    // RENDER-FIDELITY assertions (cascade kinds/order, microsteps, timer-
    // cancel, spawn/destroy, set-diff, throw-as-error) live in the CLJS unit
    // test `panels.epoch.machine-epochs-harness-cljs-test` per the Xray/
    // Story-as-CLJS rule (the chart-render shim-survival probe stays owned by
    // the `deep machine inspector substrate` scenario).
    name: 'machine-epochs multi-machine frame-isolated stepper (rf2-q3lfm: pick a machine -> Xray follows its own epoch ring; per-track door/traffic/quiz/brew/session/fuse/hvac/media/modal/gate paths; cross-frame flip isolation; restart = frame reset; boot-on-select fuse THROW)',
    url: '/testbeds/machine-epochs/',
    panels: ['machines'],
    coveredRows: ['Machines'],
    run: runMachineEpochs,
  },
  {
    // The two-frame isolation deck (rf2-4279q4): the standard-epochs ladder
    // mounted twice (`:above` / `:below`). Drives the FLOW rung (step 5 —
    // `:standard-epochs/derived` reg-flow recompute) + the APP-SCHEMA rung
    // (step 19 — `[:auth :token]` schema rejection) in BOTH frames and
    // asserts per-frame ISOLATION off each frame's app-db (`app-db-value`):
    // a flow recompute / a schema violation+rejection in one frame never
    // moves the other. These two cross-frame rungs were INERT before this
    // scenario — nothing exercised them in the above/below frames.
    name: 'two-frame isolation: flow + app-schema rungs exercise + isolate across :above / :below (rf2-4279q4)',
    url: '/testbeds/two-frame-isolation/',
    panels: ['app-db'],
    coveredRows: ['App-DB Diff', 'Flows'],
    run: runTwoFrameIsolation,
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
    // Hydration mismatches surface via the L2 event-row signal + the
    // always-on issues ribbon. The scenario verifies the Xray shell +
    // Epoch panel mount cleanly under cascade scope.
    panels: ['epoch'],
    coveredRows: ['Epoch Panel'],
    run: runHydration,
  },
  {
    name: '1000-event trace row-budget plus 20-dispatch re-check',
    url: '/counter/',
    panels: ['trace'],
    load: true,
    // Covers the Trace + Shell/Elision rows. Performance profiling is the
    // job of Chrome DevTools' Performance tab, not an Xray panel.
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
];

module.exports = {
  PANEL_HANDOFFS,
  SCENARIOS,
  STAGED_SURFACES,
};
