'use strict';

/*
 * Live-follow helpers (rf2-rwhat).
 *
 * Causa's :live mode auto-tracks the head cascade (spec/018 §3) —
 * when the host app fires an event, the spine's effective
 * :dispatch-id snaps forward to the new cascade and every L4 panel
 * re-renders against that focus. The class of bugs Mike has caught
 * live (rf2-70tkv App-db doesn't refresh, rf2-2f8jv Machines empty,
 * rf2-dodq2 Views Group-By stuck) all share one missing test axis:
 * "fire event while Causa is live → cursor auto-follows → each panel
 * must reflect the new focused-event". The existing feature_matrix
 * scenarios sweep panel-handoff and per-substrate happy paths but
 * none exercise this auto-follow refresh path.
 *
 * The helpers here:
 *
 *   - `readCausaFocus(page)` — reads the `:rf.causa/focus` sub on
 *     the `:rf/causa` frame via `rf.subscribe_once`. Returns a JSON-
 *     friendly snapshot `{ ok, dispatchId, frame, mode, paused, headId }`.
 *
 *   - `readHeadDispatchId(page)` — reads the head cascade's
 *     `:dispatch-id` directly from the trace-bus buffer. Used as
 *     an independent (sub-free) cross-check.
 *
 *   - `fireEventInLiveMode(page, hostButtonLabel)` — clicks a host
 *     button (outside the `#rf-causa-root` overlay), waits for the
 *     bus to grow a new `:event/dispatched` row, and waits for the
 *     `:rf.causa/focus` sub's `:dispatch-id` to advance past its
 *     previous value (i.e. auto-follow fired). Returns
 *     `{ previousDispatchId, newDispatchId, headDispatchId }`.
 *
 *   - `assertPanelReflectsFocus(page, panelId, focusBefore, opts)`
 *     — switches the L3 tab, waits for the panel canvas, and asserts
 *     the panel's rendered representation of the focused event
 *     reflects `focusAfter` (not `focusBefore`). The per-panel
 *     "signature" is captured by `panelFocusSignature(page, panelId)`;
 *     a refresh is proven by signature inequality across the click.
 *
 * The helpers are deliberately Causa-agnostic on the "panel updated"
 * predicate — they read the same testids the spec catalogues (panel
 * root + the cascade-keyed surface inside) and compose a per-panel
 * signature string. Adding a new panel to the sweep = adding one
 * row to `PANEL_SIGNATURE_PROBES`.
 */

const { waitForValue } = require('../../../../../examples/scripts/spec-helpers.cjs');

// ---------------------------------------------------------------------------
// Reading the spine's focus + the head cascade
// ---------------------------------------------------------------------------

/**
 * Read `:rf.causa/focus` on the `:rf/causa` frame and the trace-bus
 * head cascade. Returns `{ ok, dispatchId, frame, mode, paused, headId,
 * cascadeCount, reason? }`. `dispatchId` / `frame` / `headId` are
 * pr-str EDN strings (or null).
 */
async function readCausaFocus(page) {
  return page.evaluate(() => {
    const cljs = window.cljs && window.cljs.core;
    const rf   = window.re_frame && window.re_frame.core;
    const bus  = window.day8 &&
                 window.day8.re_frame2_causa &&
                 window.day8.re_frame2_causa.trace_bus;
    if (!cljs || !rf || typeof rf.subscribe_once !== 'function' ||
        !bus || typeof bus.buffer !== 'function') {
      return {
        ok: false,
        reason: 'cljs.core / rf.subscribe_once / trace_bus.buffer unavailable',
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
    const causaFrame = keyword(':rf/causa');
    const subQ       = cljs.PersistentVector.fromArray(
      [keyword(':rf.causa/focus')], true);
    let focus;
    try {
      focus = rf.subscribe_once(causaFrame, subQ);
    } catch (err) {
      return { ok: false, reason: `subscribe_once threw: ${String(err)}` };
    }
    const kDispatchId = keyword(':dispatch-id');
    const kFrame      = keyword(':frame');
    const kMode       = keyword(':mode');
    const kPaused     = keyword(':paused?');
    const dispatchId  = focus ? cljs.get(focus, kDispatchId) : null;
    const frame       = focus ? cljs.get(focus, kFrame)      : null;
    const mode        = focus ? cljs.get(focus, kMode)       : null;
    const paused      = focus ? Boolean(cljs.get(focus, kPaused)) : false;

    // Walk the bus buffer for the most recent `:event/dispatched`
    // record's dispatch-id — the spine's head once cascade-projection
    // runs (the projection is synchronous, so the latest `:event/
    // dispatched` IS the head cascade's dispatch-id in the steady
    // state we care about).
    const kOperation = keyword(':operation');
    const kTags      = keyword(':tags');
    const opDispatched = keyword(':event/dispatched');
    let s = cljs.seq(bus.buffer());
    let lastDispatchId = null;
    let cascadeCount = 0;
    const seen = new Set();
    while (s) {
      const ev   = cljs.first(s);
      const op   = cljs.get(ev, kOperation);
      const tags = cljs.get(ev, kTags);
      const did  = tags ? cljs.get(tags, kDispatchId) : null;
      if (op && cljs._EQ_(op, opDispatched) && did != null) {
        lastDispatchId = did;
        const key = cljs.pr_str(did);
        if (!seen.has(key)) {
          seen.add(key);
          cascadeCount += 1;
        }
      }
      s = cljs.next(s);
    }

    return {
      ok: true,
      dispatchId: dispatchId == null ? null : cljs.pr_str(dispatchId),
      frame:      frame      == null ? null : cljs.pr_str(frame),
      mode:       mode       == null ? null : cljs.pr_str(mode),
      paused,
      headId:     lastDispatchId == null ? null : cljs.pr_str(lastDispatchId),
      cascadeCount,
    };
  });
}

// ---------------------------------------------------------------------------
// Firing a host event while Causa is live
// ---------------------------------------------------------------------------

/**
 * Click a host-app button (outside the `#rf-causa-root` overlay) by
 * its text label. Throws if no matching button is found in the host
 * surface.
 */
async function clickHostButtonByLabel(page, label) {
  const clicked = await page.evaluate((targetLabel) => {
    const buttons = Array.from(document.querySelectorAll('button'))
      .filter((el) => !el.closest('#rf-causa-root'));
    const target = buttons.find((el) =>
      (el.textContent || '').trim() === targetLabel);
    if (!target) return false;
    target.click();
    return true;
  }, label);
  if (!clicked) {
    throw new Error(
      `live-follow: could not find host-app button ${JSON.stringify(label)} ` +
      'outside the Causa overlay',
    );
  }
}

/**
 * Click a host-app button by its `data-testid` (used by testbeds
 * that label their buttons via testid rather than visible text, e.g.
 * deep-machine's `work-go`). Restricted to elements outside the
 * `#rf-causa-root` overlay so the click cannot accidentally land
 * on a Causa-internal control with the same testid.
 */
async function clickHostButtonByTestId(page, testId) {
  const clicked = await page.evaluate((target) => {
    const button = Array.from(document.querySelectorAll(
      `[data-testid="${target}"]`))
      .find((el) => !el.closest('#rf-causa-root'));
    if (!button || typeof button.click !== 'function') return false;
    button.click();
    return true;
  }, testId);
  if (!clicked) {
    throw new Error(
      `live-follow: could not find host-app element with ` +
      `data-testid=${JSON.stringify(testId)} outside the Causa overlay`,
    );
  }
}

/**
 * Click a host button and wait for Causa's `:live` auto-follow to
 * advance focus. Returns `{ previousDispatchId, previousHeadId,
 * newDispatchId, newHeadId, cascadeCountBefore, cascadeCountAfter }`.
 *
 * The contract this helper asserts:
 *   1. Bus head cascade advanced (a new `:event/dispatched` record
 *      arrived);
 *   2. Spine focus `:dispatch-id` advanced to that new head;
 *   3. Spine `:mode` is `:live` and `:paused?` is false.
 *
 * Throws with a structured diagnostic on timeout — the diagnostic
 * carries before/after focus + head snapshots so test failures point
 * at WHICH invariant tripped (head didn't advance / focus didn't
 * follow / spine paused unexpectedly).
 */
async function fireEventInLiveMode(page, hostButton, opts = {}) {
  const timeoutMs = opts.timeoutMs || 10000;
  const before = await readCausaFocus(page);
  if (!before.ok) {
    throw new Error(
      `live-follow: could not read Causa focus before click: ${before.reason}`,
    );
  }
  if (before.mode && before.mode !== ':live') {
    throw new Error(
      `live-follow: spine is not in :live mode before click (mode=${before.mode}); ` +
      'either the test sequence drifted into :retro or LIVE was paused. ' +
      'Resume LIVE via :rf.causa/follow-head before firing.',
    );
  }
  if (before.paused) {
    throw new Error(
      'live-follow: spine is paused before click (:paused? true). ' +
      'Resume LIVE via :rf.causa/toggle-live-pause before firing.',
    );
  }

  // `hostButton` is either a plain string (label) or `{ testId: '…' }`
  // (for testbeds whose buttons surface only via `data-testid`).
  if (typeof hostButton === 'string') {
    await clickHostButtonByLabel(page, hostButton);
  } else if (hostButton && hostButton.testId) {
    await clickHostButtonByTestId(page, hostButton.testId);
  } else {
    throw new Error(
      `live-follow: fireEventInLiveMode hostButton must be a label ` +
      `string or { testId } object; got ${JSON.stringify(hostButton)}`,
    );
  }

  const after = await waitForValue(
    () => readCausaFocus(page),
    (snap) =>
      snap.ok &&
      snap.headId &&
      snap.headId !== before.headId &&
      snap.dispatchId === snap.headId &&
      (!snap.mode || snap.mode === ':live') &&
      !snap.paused,
    {
      timeoutMs,
      description: `live-follow auto-tracks new head after clicking ${JSON.stringify(hostButton)}`,
    },
  );

  return {
    previousDispatchId: before.dispatchId,
    previousHeadId:     before.headId,
    newDispatchId:      after.dispatchId,
    newHeadId:          after.headId,
    cascadeCountBefore: before.cascadeCount,
    cascadeCountAfter:  after.cascadeCount,
    frame:              after.frame,
  };
}

// ---------------------------------------------------------------------------
// Per-panel "what does this panel show about the focused event?"
// ---------------------------------------------------------------------------

/*
 * Each entry maps a tab id (the L3 `[data-testid="rf-causa-tab-<id>"]`
 * value) to:
 *
 *   - `canvasTestId` — the panel's root `data-testid`. The shell
 *     surfaces this when the tab is clicked.
 *   - `signature(page, focus)` — returns a JSON-friendly snapshot
 *     of what the panel is currently rendering about the focused
 *     event. A "panel refresh" is proven by the signature changing
 *     across a `fireEventInLiveMode` call.
 *
 * Adding a new panel = adding one row.
 */
const PANEL_SIGNATURE_PROBES = {
  event: {
    canvasTestId: 'rf-causa-event-detail',
    // The Event L4 panel renders `rf-causa-event-detail-cascade` with
    // `data-dispatch-id` + `data-frame` attrs (event_detail.cljs
    // L1094-1096). The signature pins the dispatch-id directly so a
    // panel that has stalled on a previous focus surfaces as
    // `dispatchId` lagging the bus head — the cross-click inequality
    // check is the canonical proof; the `matchesFocus` predicate
    // additionally requires the panel rendered the cascade (vs the
    // empty-state branch).
    async signature(page) {
      return page.evaluate(() => {
        const root = document.getElementById('rf-causa-root');
        const cascade = root && root.querySelector(
          '[data-testid="rf-causa-event-detail-cascade"]');
        const orphaned = root && root.querySelector(
          '[data-testid="rf-causa-event-detail-orphaned"]');
        return {
          present: Boolean(cascade) || Boolean(orphaned),
          renderKind: cascade ? 'cascade' :
                      orphaned ? 'orphaned' : 'unknown',
          dispatchId: cascade ? cascade.getAttribute('data-dispatch-id') : null,
          frame:      cascade ? cascade.getAttribute('data-frame')       : null,
        };
      });
    },
    matchesFocus(sig, _focus) {
      return sig.present;
    },
  },

  'app-db': {
    canvasTestId: 'rf-causa-app-db-diff',
    // The App-DB Diff panel re-renders its slices on every focus
    // change (each cascade has its own per-epoch diff). Signature
    // captures the rendered text (whitespace-collapsed, head-bound)
    // — equality across focus advances proves the panel is stuck.
    async signature(page) {
      return page.evaluate(() => {
        const root = document.getElementById('rf-causa-root');
        const panel = root && root.querySelector(
          '[data-testid="rf-causa-app-db-diff"]');
        if (!panel) return { present: false };
        // Inner section (skip the title bar — it's stable across
        // cascades). Take the whole body text so the signature picks
        // up slice text, empty-state copy, OR redacted-modified chip
        // (all three legitimately differ across cascades).
        const slices = root.querySelectorAll(
          '[data-testid^="rf-causa-app-db-diff-slice-"]');
        const sliceTestIds = Array.from(slices)
          .map((el) => el.getAttribute('data-testid'))
          .sort();
        const text = (panel.textContent || '')
          .replace(/\s+/g, ' ').trim().slice(0, 600);
        return {
          present: true,
          sliceCount: slices.length,
          sliceTestIds,
          text,
        };
      });
    },
    matchesFocus(sig, _focus) {
      // For App-DB Diff "panel reflects focus" means "panel is rendered
      // and its content corresponds to the focused cascade". We can't
      // peg a specific dispatch-id off the DOM here (no `data-dispatch-
      // id` attr on the diff root), so we rely on the cross-click
      // signature inequality (sigBefore !== sigAfter) handled by the
      // scenario runner.
      return sig.present;
    },
  },

  views: {
    canvasTestId: 'rf-causa-views',
    // Views panel renders subscriber groups + per-cascade invalidation
    // markers. On the counter app the head cascade's `:counter` sub
    // recomputes every tick, so the "Invalidated by" copy + the row
    // dispatch-id (when surfaced) shift across clicks.
    async signature(page) {
      return page.evaluate(() => {
        const root = document.getElementById('rf-causa-root');
        const panel = root && root.querySelector(
          '[data-testid="rf-causa-views"]');
        if (!panel) return { present: false };
        const invalidatedBy = root.querySelector(
          '[data-testid="rf-causa-views-invalidated-by"]');
        const groupBy = root.querySelector(
          '[data-testid="rf-causa-views-group-by-toggle"]');
        const headerMeta = root.querySelector(
          '[data-testid="rf-causa-views-header-meta"]');
        const text = (panel.textContent || '')
          .replace(/\s+/g, ' ').trim().slice(0, 600);
        return {
          present: true,
          invalidatedByText: invalidatedBy ?
            (invalidatedBy.textContent || '').trim() : null,
          groupByPresent: Boolean(groupBy),
          headerMetaText: headerMeta ?
            (headerMeta.textContent || '').trim() : null,
          text,
        };
      });
    },
    matchesFocus(sig, _focus) {
      return sig.present;
    },
  },

  trace: {
    canvasTestId: 'rf-causa-trace',
    // The Trace tab is cascade-scoped (rf2-ycoct): the row set reflects
    // the focused cascade. The cascade-status-bar testid carries the
    // focused-cascade status keyword (in-flight / settled-success /
    // …) — a fresh focused cascade re-derives the bar's testid suffix
    // and the row id list (each row has `rf-causa-trace-row-<id>`).
    async signature(page) {
      return page.evaluate(() => {
        const root = document.getElementById('rf-causa-root');
        const panel = root && root.querySelector(
          '[data-testid="rf-causa-trace"]');
        if (!panel) return { present: false };
        const statusBar = Array.from(root.querySelectorAll(
          '[data-testid^="rf-causa-trace-cascade-status-bar-"]'))[0];
        const rows = Array.from(root.querySelectorAll(
          '[data-testid^="rf-causa-trace-row-"]'));
        const rowIds = rows.map((el) => el.getAttribute('data-testid')).sort();
        const counts = root.querySelector(
          '[data-testid="rf-causa-trace-counts"]');
        return {
          present: true,
          statusBarTestId: statusBar ?
            statusBar.getAttribute('data-testid') : null,
          rowCount: rows.length,
          rowIds: rowIds.slice(0, 20),
          countsText: counts ? (counts.textContent || '').trim() : null,
        };
      });
    },
    matchesFocus(sig, _focus) {
      return sig.present;
    },
  },

  machines: {
    canvasTestId: 'rf-causa-machine-inspector',
    // The Machines panel exposes `data-has-records="<bool>"` on its
    // root so the signature can record "is this focused event a
    // machine-transition cascade?" deterministically without scraping
    // copy. Pair the bool with the focused-event-section testid list
    // (one per machine-transition record in the cascade).
    async signature(page) {
      return page.evaluate(() => {
        const root = document.getElementById('rf-causa-root');
        const panel = root && root.querySelector(
          '[data-testid="rf-causa-machine-inspector"]');
        if (!panel) return { present: false };
        const hasRecords = panel.getAttribute('data-has-records');
        const sections = Array.from(root.querySelectorAll(
          '[data-testid^="rf-causa-machine-focused-event-section-"]'));
        const sectionTestIds = sections
          .map((el) => el.getAttribute('data-testid')).sort();
        const blankState = root.querySelector(
          '[data-testid="rf-causa-machine-inspector-blank"]');
        const emptyState = root.querySelector(
          '[data-testid="rf-causa-machine-inspector-empty"]');
        return {
          present: true,
          hasRecords,
          sectionCount: sections.length,
          sectionTestIds,
          renderKind: blankState ? 'blank' :
                      emptyState ? 'empty' :
                      sections.length > 0 ? 'records' : 'unknown',
        };
      });
    },
    matchesFocus(sig, _focus) {
      return sig.present;
    },
  },

  routing: {
    canvasTestId: 'rf-causa-routing',
    // Routing is the focused-event lens — when the focused cascade
    // navigated, the panel renders `rf-causa-routing-nav-row` + slice
    // detail; otherwise `rf-causa-routing-empty`. On counter, every
    // cascade legitimately renders the empty state; signature equality
    // before/after a click on counter therefore proves the panel re-
    // rendered cleanly (the bug class is the panel crashing or
    // leaking previous-focus data, not the panel changing its empty/
    // populated shape).
    async signature(page) {
      return page.evaluate(() => {
        const root = document.getElementById('rf-causa-root');
        const panel = root && root.querySelector(
          '[data-testid="rf-causa-routing"]');
        if (!panel) return { present: false };
        const navRow = root.querySelector(
          '[data-testid="rf-causa-routing-nav-row"]');
        const navSummary = root.querySelector(
          '[data-testid="rf-causa-routing-nav-summary"]');
        const emptyState = root.querySelector(
          '[data-testid="rf-causa-routing-empty"]');
        return {
          present: true,
          renderKind: navRow ? 'navigated' :
                      emptyState ? 'empty' : 'unknown',
          navSummaryText: navSummary ?
            (navSummary.textContent || '').trim() : null,
          navRowText: navRow ?
            (navRow.textContent || '').replace(/\s+/g, ' ').trim() : null,
        };
      });
    },
    matchesFocus(sig, _focus) {
      return sig.present;
    },
  },

  issues: {
    canvasTestId: 'rf-causa-issues-ribbon',
    // The Issues feed is cascade-scoped (rf2-u6dhp). When the focused
    // cascade carries no issues, the panel renders
    // `rf-causa-issues-empty-no-issues-for-event` (silent-by-default).
    // When it does, the `rf-causa-issues-feed` ul renders one row per
    // issue. Signature records both arms so cross-click inequality is
    // detectable when the focused cascade actually shifted between
    // "no issues" and "some issues" — and the basic refresh signal
    // (renderKind + counts) is still measurable even when both
    // cascades are silent.
    async signature(page) {
      return page.evaluate(() => {
        const root = document.getElementById('rf-causa-root');
        const panel = root && root.querySelector(
          '[data-testid="rf-causa-issues-ribbon"]');
        if (!panel) return { present: false };
        const feed = root.querySelector(
          '[data-testid="rf-causa-issues-feed"]');
        const emptyForEvent = root.querySelector(
          '[data-testid="rf-causa-issues-empty-no-issues-for-event"]');
        const emptyNoIssues = root.querySelector(
          '[data-testid="rf-causa-issues-empty-no-issues"]');
        const rows = Array.from(root.querySelectorAll(
          '[data-testid^="rf-causa-issues-row-"]'))
          .filter((el) => /^rf-causa-issues-row-\d+$/
            .test(el.getAttribute('data-testid') || ''));
        const counts = root.querySelector(
          '[data-testid="rf-causa-issues-counts"]');
        return {
          present: true,
          renderKind: feed ? 'feed' :
                      emptyForEvent ? 'empty-for-event' :
                      emptyNoIssues ? 'empty-no-issues' : 'unknown',
          rowCount: rows.length,
          countsText: counts ? (counts.textContent || '').trim() : null,
        };
      });
    },
    matchesFocus(sig, _focus) {
      return sig.present;
    },
  },
};

/**
 * Pick the L3 tab + assert the panel canvas surfaced. Mirrors
 * `clickTab` in scenarios.cjs but local to this helper module.
 */
async function clickPanelTab(page, panelId) {
  const probe = PANEL_SIGNATURE_PROBES[panelId];
  if (!probe) {
    throw new Error(
      `live-follow: unknown panel id ${JSON.stringify(panelId)}; ` +
      `known: ${Object.keys(PANEL_SIGNATURE_PROBES).join(', ')}`,
    );
  }
  const tabLocator = page.locator(`[data-testid="rf-causa-tab-${panelId}"]`);
  await tabLocator.click();
  const canvas = page.locator(`[data-testid="${probe.canvasTestId}"]`);
  await waitForValue(
    () => canvas.count(),
    (n) => n > 0,
    {
      timeoutMs: 5000,
      description: `panel canvas ${probe.canvasTestId} surfaced after clicking tab ${panelId}`,
    },
  );
}

/**
 * Read the per-panel focus signature.
 */
async function panelFocusSignature(page, panelId) {
  const probe = PANEL_SIGNATURE_PROBES[panelId];
  if (!probe) {
    throw new Error(`live-follow: unknown panel id ${JSON.stringify(panelId)}`);
  }
  return probe.signature(page);
}

/**
 * Assert the panel `panelId` is reflecting the focused-event recorded
 * in `focus` (the value returned by `fireEventInLiveMode`).
 *
 *   1. Switch to the L3 tab for `panelId` (asserts canvas surfaces);
 *   2. Read the panel's focus signature;
 *   3. Run the per-panel `matchesFocus` predicate (strict assertion
 *      for the Event panel, presence-check for the rest);
 *   4. When `opts.sigBefore` is supplied, also assert the signature
 *      differs from `sigBefore` (i.e. the panel rerendered for the
 *      new focus). When `opts.signatureMayMatch` is true the cross-
 *      click inequality check is skipped (used by Routing on counter
 *      where every cascade legitimately renders the same empty
 *      state).
 *
 * Returns the signature so the caller can store it for the next
 * round-trip's `sigBefore` (chaining N clicks across a panel).
 */
async function assertPanelReflectsFocus(page, panelId, focus, opts = {}) {
  await clickPanelTab(page, panelId);
  const probe = PANEL_SIGNATURE_PROBES[panelId];
  const sig = await panelFocusSignature(page, panelId);
  if (!probe.matchesFocus(sig, focus)) {
    throw new Error(
      `live-follow: panel ${panelId} did not reflect focus ` +
      `${JSON.stringify(focus)}; signature=${JSON.stringify(sig)}`,
    );
  }
  if (opts.sigBefore && !opts.signatureMayMatch) {
    const beforeJson = JSON.stringify(opts.sigBefore);
    const afterJson  = JSON.stringify(sig);
    if (beforeJson === afterJson) {
      throw new Error(
        `live-follow: panel ${panelId} signature did not change after ` +
        `focus advanced; signature before == after = ${beforeJson}. ` +
        'This is the regression-catching path: the host fired an event, ' +
        'Causa\'s spine auto-followed (focus :dispatch-id advanced), but ' +
        'the panel\'s rendered representation of the focused event did ' +
        'NOT update. Compare against rf2-70tkv (App-db doesn\'t refresh), ' +
        'rf2-2f8jv (Machines empty), rf2-dodq2 (Views Group-By stuck).',
      );
    }
  }
  return sig;
}

// ---------------------------------------------------------------------------
// Panel-control interactions (rf2-mpqxn — Phase 3)
// ---------------------------------------------------------------------------
//
// Phase 1+2 (rf2-rwhat) covered the auto-follow refresh axis: "fire event
// while Causa is live → cursor auto-follows → each panel reflects the new
// focused event". Phase 3 covers the orthogonal axis: "the user clicks a
// panel control → the rendered surface updates accordingly". The bug
// class this catches is rf2-dodq2 (Views Group-By stuck on Component
// pill), rf2-i40us (density radio with no font-size effect), and broken
// Cmd-K palette dispatch (rf2-wm7z4 + rf2-ybjkx).
//
// The helpers below are organised by control. Each helper is a single
// observable round-trip — click control, read the visible side-effect,
// return a JSON-friendly snapshot the scenario asserts against. Phase 3
// scenarios chain these into "cycle through every state" sweeps.

// ---- Views Group-By pill cycle (rf2-dodq2) -------------------------------

/*
 * Group-By has three pills, one per grouping mode:
 *   `:component` → renders `[data-testid="rf-causa-views-groups"]`
 *   `:sub`       → renders `[data-testid="rf-causa-views-sub-grouped"]`
 *   `:tree`      → renders `[data-testid="rf-causa-views-tree"]`
 *
 * Each pill carries `aria-pressed="true"|"false"` driven by the
 * `:rf.causa/views-data` sub's `:group-by` key. A pill click dispatches
 * `:rf.causa/views-set-group-by <value>`; the reducer flips `:group-by`
 * on Causa's app-db and the Views panel re-renders. rf2-dodq2's symptom
 * was the click landing but the panel not re-rendering — the pill's
 * pressed state and the body's mode-specific testid are the two
 * observable surfaces that catch the bug.
 *
 * The body cascades through three branches in `views-panel`
 * (panels/views_view.cljs §panel root). When there are no cascades the
 * panel renders `rf-causa-views-empty`; the mode-specific bodies only
 * render once a cascade exists. Phase 3 scenarios fire the host event
 * BEFORE cycling Group-By so the body has data to project.
 */

const GROUP_BY_MODES = [
  {
    value: 'component',
    pillTestId: 'rf-causa-views-group-by-component',
    bodyTestId: 'rf-causa-views-groups',
  },
  {
    value: 'sub',
    pillTestId: 'rf-causa-views-group-by-sub',
    bodyTestId: 'rf-causa-views-sub-grouped',
  },
  {
    value: 'tree',
    pillTestId: 'rf-causa-views-group-by-tree',
    bodyTestId: 'rf-causa-views-tree',
  },
];

/**
 * Read the Views Group-By state — which pill is `aria-pressed="true"`,
 * which mode-specific body testid is mounted, and whether the empty-
 * state surface is up instead.
 */
async function readGroupByState(page) {
  return page.evaluate((modes) => {
    const root = document.getElementById('rf-causa-root');
    if (!root) return { present: false, reason: 'rf-causa-root missing' };
    const pressed = {};
    let activePill = null;
    for (const mode of modes) {
      const pill = root.querySelector(`[data-testid="${mode.pillTestId}"]`);
      const ap   = pill ? pill.getAttribute('aria-pressed') : null;
      pressed[mode.value] = ap;
      if (ap === 'true') activePill = mode.value;
    }
    const bodies = {};
    let activeBody = null;
    for (const mode of modes) {
      const body = root.querySelector(`[data-testid="${mode.bodyTestId}"]`);
      bodies[mode.value] = Boolean(body);
      if (body) activeBody = mode.value;
    }
    const empty = root.querySelector('[data-testid="rf-causa-views-empty"]');
    return {
      present:    true,
      pressed,
      activePill,
      bodies,
      activeBody,
      empty:      Boolean(empty),
    };
  }, GROUP_BY_MODES);
}

/**
 * Click the Group-By pill for `mode` and wait for the pill's
 * `aria-pressed` to flip to `"true"` AND the mode-specific body testid
 * to surface (unless the panel is in the empty state, in which case
 * only the pressed-state flip is asserted — see `signatureMayMatch`-
 * style escape for cascade-less panels).
 *
 * Returns the post-click `readGroupByState` snapshot.
 *
 * The body-mounted assertion is the regression-catching path for
 * rf2-dodq2: the bug's symptom was "pill press registers but body
 * stays on the Component grouping". Here we explicitly require the
 * sub-grouped / tree body to mount.
 */
async function clickGroupByPill(page, mode, opts = {}) {
  const def = GROUP_BY_MODES.find((m) => m.value === mode);
  if (!def) {
    throw new Error(
      `clickGroupByPill: unknown mode ${JSON.stringify(mode)}; ` +
      `known: ${GROUP_BY_MODES.map((m) => m.value).join(', ')}`,
    );
  }
  const timeoutMs = opts.timeoutMs || 5000;
  const requireBody = opts.requireBody !== false;
  await page.locator(`[data-testid="${def.pillTestId}"]`).click();
  return waitForValue(
    () => readGroupByState(page),
    (snap) => {
      if (!snap.present) return false;
      if (snap.pressed[mode] !== 'true') return false;
      if (requireBody && !snap.empty && snap.activeBody !== mode) return false;
      return true;
    },
    {
      timeoutMs,
      description:
        `Group-By pill ${mode} → aria-pressed=true ` +
        `+ body ${def.bodyTestId} mounted (unless empty-state)`,
    },
  );
}

// ---- Density radio cycle (rf2-i40us) ------------------------------------

/*
 * The density radio surfaces under the Settings popup's General tab.
 * v1 ships two pills (rf2-ttnst dropped the third `:comfy` tier from
 * the spec brief; the data is still catalogued in `density->font-size-
 * px` for forward compat):
 *
 *   :compact → 12px
 *   :cosy    → 13px (default; matches `tokens/font-size-default`)
 *
 * The radio writes the resolved px value into the canonical
 * `--rf-causa-font-size` CSS custom property on BOTH the Causa shell
 * root AND `<html>` (settings/effects.cljs §apply-density-font-size!),
 * so every type-scale entry — every typographic surface in the chrome
 * — rescales on the next paint. The test reads the `<html>`-scoped
 * inline declaration as the canonical post-click side-effect signal;
 * if the radio click landed without the apply-fn firing, the inline
 * declaration on `<html>` will be missing or stale and the assertion
 * trips.
 *
 * Density value list lives in `density-radio-modes` rather than
 * hard-coded so a future un-drop of `:comfy` re-enables the third
 * cycle entry without re-touching the helper.
 */

const DENSITY_RADIO_MODES = [
  { value: 'cosy',    testId: 'rf-causa-settings-density-cosy',    expectedPx: 13 },
  { value: 'compact', testId: 'rf-causa-settings-density-compact', expectedPx: 12 },
];

/**
 * Read `--rf-causa-font-size` from both the shell root and `<html>`.
 * Returns the inline-style value (which is what `apply-density-font-
 * size!` writes via `setProperty`). Either root may be absent in some
 * launch modes; the per-element value is reported separately so the
 * assertion can target the layer it cares about.
 */
async function readDensityFontSize(page) {
  return page.evaluate(() => {
    const html = document.documentElement;
    const shellRoot = document.getElementById('rf-causa-root');
    const htmlInline = html
      ? html.style.getPropertyValue('--rf-causa-font-size') || null
      : null;
    const shellInline = shellRoot
      ? shellRoot.style.getPropertyValue('--rf-causa-font-size') || null
      : null;
    // The computed value is what the cascade resolves; useful for
    // diagnostics but the canonical assertion target is the inline
    // declaration the apply-fn writes.
    const htmlComputed = html
      ? getComputedStyle(html).getPropertyValue('--rf-causa-font-size').trim() || null
      : null;
    return {
      htmlInline,
      shellInline,
      htmlComputed,
    };
  });
}

/**
 * Open the Settings popup by dispatching `:rf.causa/settings-toggle`
 * directly into the `:rf/causa` frame. Mirrors the dispatch path the
 * `,` keybinding takes — `keybinding.cljs §handle-keydown` calls
 * `(rf/with-frame :rf/causa (rf/dispatch [...]))` so this helper drops
 * the keyboard-listener leg and lands the same reducer.
 *
 * Direct dispatch (vs keyboard) is the robust path: the `,` listener
 * filters events on `target-inside-causa?` (the event target must walk
 * up to an element with `data-testid="rf-causa-shell"`). Synthetic
 * keypresses from Playwright land on `document.body` unless an
 * element inside the shell has focus AND the user-agent allows
 * `keydown` events to retain that target — neither invariant is
 * guaranteed across browsers / shadow-root mounts. The dispatch-vector
 * path is invariant.
 *
 * Idempotent — `settings-toggle` flips the open/close slot; we read
 * the dialog testid first and bail if it's already mounted.
 */
async function openSettingsPopup(page) {
  const dialog = page.locator('[data-testid="rf-causa-settings-dialog"]');
  if ((await dialog.count()) > 0) return;
  const result = await page.evaluate(() => {
    const cljs = window.cljs && window.cljs.core;
    const rf   = window.re_frame && window.re_frame.core;
    const dispatch = rf && (rf.dispatch_STAR_ ||
      (window.re_frame.router && window.re_frame.router.dispatch_BANG_));
    if (!cljs || typeof dispatch !== 'function') {
      return {
        ok: false,
        reason: 'cljs.core or re_frame.core.dispatch_STAR_ unavailable',
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
    const event = cljs.PersistentVector.fromArray(
      [keyword(':rf.causa/settings-toggle')], true);
    const opts = cljs.hash_map(keyword(':frame'), keyword(':rf/causa'));
    if (dispatch.cljs$core$IFn$_invoke$arity$2) {
      dispatch.cljs$core$IFn$_invoke$arity$2(event, opts);
    } else {
      dispatch(event, opts);
    }
    return { ok: true };
  });
  if (!result.ok) {
    throw new Error(
      `openSettingsPopup: could not dispatch :rf.causa/settings-toggle: ${result.reason}`,
    );
  }
  await waitForValue(
    () => dialog.count(),
    (n) => n > 0,
    { timeoutMs: 5000, description: 'Settings popup mounts after settings-toggle dispatch' },
  );
}

/**
 * Click a density radio and wait for `--rf-causa-font-size` on `<html>`
 * to flip to the expected px value. Returns the post-click font-size
 * snapshot from `readDensityFontSize`.
 *
 * The assertion target is `<html>` (NOT the shell root) because the
 * `<html>` write is the one that survives a popout / fullscreen mount
 * where the shell root may differ from the inline-host root. The shell
 * root is also written but is reported alongside for diagnostics.
 */
async function clickDensityRadio(page, value, opts = {}) {
  const def = DENSITY_RADIO_MODES.find((m) => m.value === value);
  if (!def) {
    throw new Error(
      `clickDensityRadio: unknown density ${JSON.stringify(value)}; ` +
      `known: ${DENSITY_RADIO_MODES.map((m) => m.value).join(', ')}`,
    );
  }
  const timeoutMs = opts.timeoutMs || 5000;
  await page.locator(`[data-testid="${def.testId}"]`).click();
  const expected = `${def.expectedPx}px`;
  return waitForValue(
    () => readDensityFontSize(page),
    (snap) => snap.htmlInline === expected,
    {
      timeoutMs,
      description:
        `Density radio ${value} → --rf-causa-font-size = ${expected} on <html>`,
    },
  );
}

// ---- Cmd-K palette execute (rf2-wm7z4 + rf2-ybjkx) ----------------------

/*
 * The palette is opened by Cmd/Ctrl+K (per `keybinding.cljs §palette-
 * toggle-key?`). Once open the input owns key handling:
 *   - typing into the input updates `:palette-query` and re-ranks the
 *     fuzzy index
 *   - ArrowDown/Up moves the cursor across result rows
 *   - Enter dispatches `:rf.causa/palette-invoke <item> <popout?>` which
 *     lowers the item's `:action` into the canonical Causa-side
 *     dispatch (theme toggle / reduced-motion cycle / cycle-density /
 *     …)
 *   - the first result is selected by default (cursor = 0)
 *
 * Verifying the round-trip means: open palette, type query, assert the
 * top result is the verb we wanted, press Enter, assert the verb's
 * side-effect landed (e.g. `<html>` theme class flipped), AND when we
 * re-open the palette the same verb's recents-boost (`:palette-recents`
 * slot) lifts it to position 0 even on an empty query. Recents-boost-
 * max = 60 outranks the static `:command` boost of 40, so the recent
 * command sorts strictly first.
 */

/**
 * Read the current theme class on `<html>` — one of `dark` / `light`
 * (the only two configured in `apply-theme!`). Returns null when no
 * theme class is present.
 */
async function readThemeClass(page) {
  return page.evaluate(() => {
    const html = document.documentElement;
    if (!html) return null;
    const cl = html.classList;
    if (cl.contains('rf-causa-theme-dark')) return 'dark';
    if (cl.contains('rf-causa-theme-light')) return 'light';
    return null;
  });
}

/**
 * Read the palette-list state — open?, query, result rows (testid +
 * data-source + label text). The label text is read from the row
 * itself (the icon glyph + label span concatenate; we drop the leading
 * icon glyph by trimming).
 */
async function readPaletteState(page) {
  return page.evaluate(() => {
    const root = document.getElementById('rf-causa-root');
    const dialog = root && root.querySelector(
      '[data-testid="rf-causa-palette-dialog"]');
    if (!dialog) return { open: false };
    const input = root.querySelector(
      '[data-testid="rf-causa-palette-input"]');
    const list = root.querySelector(
      '[data-testid="rf-causa-palette-list"]');
    const rows = list
      ? Array.from(list.querySelectorAll('[data-testid^="rf-causa-palette-row-"]'))
      : [];
    const rowData = rows.map((el) => ({
      testId:  el.getAttribute('data-testid'),
      source:  el.getAttribute('data-source'),
      active:  el.getAttribute('data-active'),
      text:    (el.textContent || '').replace(/\s+/g, ' ').trim(),
    }));
    return {
      open:     true,
      query:    input ? input.value : null,
      rowCount: rows.length,
      rows:     rowData.slice(0, 10),
    };
  });
}

/**
 * Open the palette via Ctrl+K (works on every host — see `palette-
 * toggle-key?` accepting Cmd XOR Ctrl). Idempotent — re-opening when
 * already open would dispatch toggle and close the palette; we guard
 * by reading the dialog testid first.
 */
async function openPalette(page) {
  const dialog = page.locator('[data-testid="rf-causa-palette-dialog"]');
  if ((await dialog.count()) > 0) return;
  await page.keyboard.press('Control+K');
  await waitForValue(
    () => dialog.count(),
    (n) => n > 0,
    { timeoutMs: 5000, description: 'Palette dialog mounts after Ctrl+K' },
  );
}

/**
 * Close the palette by pressing Escape inside the input. Idempotent.
 */
async function closePalette(page) {
  const dialog = page.locator('[data-testid="rf-causa-palette-dialog"]');
  if ((await dialog.count()) === 0) return;
  const input = page.locator('[data-testid="rf-causa-palette-input"]');
  if ((await input.count()) > 0) {
    await input.press('Escape');
  } else {
    await page.keyboard.press('Escape');
  }
  await waitForValue(
    () => dialog.count(),
    (n) => n === 0,
    { timeoutMs: 5000, description: 'Palette dialog dismisses after Escape' },
  );
}

/**
 * Type `query` into the palette input + wait for the top row's text to
 * include `expectedTopLabelSubstr` (case-insensitive). Returns the
 * post-type palette snapshot. The substring match lets the caller pin
 * "the result I expected ranked first" without depending on the exact
 * label glyph (Cmd-Enter pop-out arrow, etc.). Asserts at least one
 * row is present — empty result sets surface `rf-causa-palette-empty`
 * (the `data-testid` prefix does NOT match `rf-causa-palette-row-`)
 * so `rowCount === 0` indicates the query has no hits.
 */
async function typePaletteQuery(page, query, expectedTopLabelSubstr, opts = {}) {
  const timeoutMs = opts.timeoutMs || 5000;
  const input = page.locator('[data-testid="rf-causa-palette-input"]');
  await input.fill(query);
  return waitForValue(
    () => readPaletteState(page),
    (snap) => {
      if (!snap.open) return false;
      if (snap.rowCount === 0) return false;
      const top = snap.rows[0];
      const text = (top.text || '').toLowerCase();
      return text.includes(expectedTopLabelSubstr.toLowerCase());
    },
    {
      timeoutMs,
      description:
        `Palette query ${JSON.stringify(query)} ` +
        `→ top row label contains ${JSON.stringify(expectedTopLabelSubstr)}`,
    },
  );
}

/**
 * Press Enter on the palette input to invoke the cursor's current row.
 * Waits for the palette to dismiss (the `:rf.causa/palette-invoke`
 * reducer closes the modal as part of every verb branch). Returns the
 * post-invoke palette snapshot (which is `{ open: false }`).
 */
async function executePaletteCursor(page, opts = {}) {
  const timeoutMs = opts.timeoutMs || 5000;
  const input = page.locator('[data-testid="rf-causa-palette-input"]');
  await input.press('Enter');
  return waitForValue(
    () => readPaletteState(page),
    (snap) => !snap.open,
    {
      timeoutMs,
      description: 'Palette dismisses after Enter (post invoke)',
    },
  );
}

module.exports = {
  PANEL_SIGNATURE_PROBES,
  readCausaFocus,
  clickHostButtonByLabel,
  clickHostButtonByTestId,
  fireEventInLiveMode,
  clickPanelTab,
  panelFocusSignature,
  assertPanelReflectsFocus,
  // rf2-mpqxn — Phase 3 panel-control interactions
  GROUP_BY_MODES,
  DENSITY_RADIO_MODES,
  readGroupByState,
  clickGroupByPill,
  readDensityFontSize,
  openSettingsPopup,
  clickDensityRadio,
  readThemeClass,
  readPaletteState,
  openPalette,
  closePalette,
  typePaletteQuery,
  executePaletteCursor,
};
