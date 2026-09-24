'use strict';

/*
 * The bench drivers' ONE sentinel wait, raced against the page dying.
 *
 * THE HAZARD
 * ----------
 * A naive driver ends its page's work like this:
 *
 *     page.on('pageerror', (e) => { errors.push(e.message); });
 *     await page.waitForFunction('window.X_DONE === true || window.X_ERROR',
 *                                null, { timeout: 20 * 60 * 1000 });
 *     if (errors.length) { ...fail... }
 *
 * Read the order. A benchmark that THROWS never reaches its `done!`, so the
 * sentinel never becomes true, so the wait runs its budget out — TWENTY
 * MINUTES — and only then does the driver look at the array that has held
 * the answer since the first second. The failure is instantaneous and the
 * report of it is twenty minutes late.
 *
 * That is not a slow gate, it is the anonymous-ceiling defect `navigate.cjs`
 * documents, wearing its other hat. There the budget is too small and
 * absorbs a fault it is never meant to bound; here it is enormous and
 * absorbs one it already knows about. Both end the same way: a log line that
 * says `Timeout 1200000ms exceeded` when what happened was a ReferenceError
 * at page-load, and an afternoon spent enlarging a budget that was never the
 * problem.
 *
 * NOT HYPOTHETICAL. A contaminated Shadow cache raises a ReactDOM
 * `pageerror` from clean source — a rebuild clears it — and a driver that
 * logs it and then waits the full twenty minutes for its sentinel before
 * timing out is exactly the naive shape above. The *common* cause of a page
 * error here is environmental, so it is the case a driver meets most often
 * and the one the naive shape handles worst.
 *
 * WHY A FILE AND NOT A PATCH IN EACH DRIVER
 * -----------------------------------------
 * `navigate.cjs`'s reasoning: a fix patched into one driver by hand leaves
 * its siblings with the defect, and the drivers drift. Per-file drift is the
 * failure mode; the directory gets one sentinel.
 *
 * WHAT COUNTS AS THE PAGE DYING
 * -----------------------------
 * Three things, and deliberately not a fourth:
 *
 *   `pageerror`     an uncaught exception. A benchmark that threw and kept
 *                   going publishes a precise number for a page that is not
 *                   the page under test.
 *   `crash`         the renderer is gone; nothing will ever set the sentinel.
 *   `requestfailed` for a DOCUMENT or SCRIPT only. These drivers serve the
 *                   bundle from a loopback `http.createServer`, so a script
 *                   that fails at the network level means the benchmark was
 *                   never loaded and the sentinel is unreachable by
 *                   construction. Other resource types are NOT fatal: an
 *                   image or a favicon that fails has nothing to do with the
 *                   measurement, and a gate that goes red for one is a gate
 *                   that gets disabled.
 *
 * WHAT THIS DOES NOT DO
 * ---------------------
 * It does not adjudicate anything. A driver still reads its own
 * `window.*_ERROR`, its own guard verdict and its own control after the wait
 * returns; this only guarantees that the wait RETURNS — promptly, and saying
 * which of the two things happened.
 *
 * WHY A BARE `pageerror` PRINT IS NOT ENOUGH
 * ------------------------------------------
 * A driver that installs only a printing handler —
 *
 *     page.on('pageerror', (e) => console.error(`[drv] page error: ${e.message}`))
 *
 * — with no array and no reference from the exit block exits 0 over a dead
 * page, even though it also waits on `'... || window.X_ERROR'`, which covers
 * every throw the app itself catches and reports. Run rather than read, the
 * gap is real:
 *
 *   1. Chromium raises `pageerror` for a throw inside `requestAnimationFrame`,
 *      inside `setTimeout`, and for an unhandled promise rejection, while a
 *      completion sentinel set elsewhere STILL becomes true. Measured against
 *      Playwright 1.59.1; all three cases exit 0 under a print-only handler.
 *
 *   2. Worse, and the reason no page-side `try/catch` can close it: React
 *      (measured on 19.2.0) does NOT rethrow an uncaught render error to the
 *      caller of `flushSync`/`render`. `defaultOnUncaughtError` hands it to
 *      `reportGlobalError` -> `reportError` (19.2.0's
 *      `react-dom-client.production.js` ~5888-5890, ~2307), which raises a
 *      global error and returns. So a render throw is INVISIBLE to a
 *      `(catch :default e ...)` in an app's `-main`, sets no
 *      `window.*_ERROR`, does not reject the `page.evaluate` that
 *      READY-style drivers rely on — and the app carries on and sets its
 *      sentinel.
 *
 *   3. A run driven from `setInterval`s or a live `MutationObserver` has a
 *      second, non-React path: a throw in any of those is a detached task —
 *      it escapes `-main`'s `try` AND the promise chain's `.catch`, and
 *      `setInterval` keeps firing, so the run still completes and sets its
 *      sentinel.
 *
 * So a driver calls `watchPage` and reads its `failures` at exit: the
 * collector prints the same error a bare handler would and additionally
 * records it, plus the two failures a bare handler never sees (`crash`, and
 * a failed `document`/`script` request).
 *
 * ...AND EVERY SENTINEL WAIT RACES
 * --------------------------------
 * A bare `page.waitForFunction` sentinel wait over a page that dies at load
 * spends its whole budget — twenty or thirty minutes — saying so. A sentinel
 * wait goes through `race` instead.
 *
 * TWO PROPERTIES MAKE `race` SAFE, and they are worth stating because
 * neither is obvious:
 *
 *   1. IT CANNOT SHORTEN A RUN THAT WOULD HAVE PASSED. `race` rejects only on
 *      a recorded failure, and a driver refuses at its exit on exactly that
 *      array. So any run this reports on early would be non-zero anyway;
 *      what changes is when, and the failure line names the cause instead of
 *      naming the clock.
 *   2. NO NEW EXIT CODE. Every rejection lands in a path the driver already
 *      has — a `drive()` rejection handler, a `catch`, a `failed` flag — and
 *      all of those are that driver's existing 1.
 *
 * WHAT IT DOES COST, stated rather than discovered later. A page that throws
 * and STILL reaches its sentinel fails AT THE WAIT rather than after printing
 * its table, so the partial rows a late throw would leave behind are not
 * printed. The verdict is identical either way (both are that driver's 1,
 * both name the page error); what is lost is diagnostic residue in the
 * narrow window between "some rows measured" and "sentinel set". The
 * motivating case is a `ReferenceError` at page load, where there is no
 * residue to lose and fifteen to thirty minutes to save. The exit-block
 * refusals are NOT redundant: a failure recorded after the sentinel has
 * already flipped — during the result `evaluate`s, the measurement loop that
 * follows a READY wait, or teardown — reaches the exit block and nowhere
 * else.
 */

/**
 * Start watching `page` for the ways it can die. Call this as early as the
 * page exists — BEFORE navigation — because the fault this exists to catch
 * most often happens during bundle execution, which is inside the
 * navigation.
 *
 * @param page   Playwright page.
 * @param label  How this page is named in a failure line (e.g. `'hd8:slim'`).
 * @returns {{failures: Array, race: Function, dispose: Function}}
 */
function watchPage(page, label) {
  const failures = [];
  // Resolvers for a currently-pending `race`. A failure that arrives while
  // nothing is waiting is still RECORDED — `race` checks the array before it
  // waits, so an error thrown during navigation fails the very next wait
  // rather than being missed for having been early.
  const waiters = [];

  const record = (kind, detail) => {
    failures.push({ kind, detail });
    console.error(`[${label}] PAGE ${kind.toUpperCase()}: ${detail}`);
    while (waiters.length) waiters.pop()();
  };

  const onPageError = (e) => record('pageerror', e && e.message ? e.message : String(e));
  const onCrash = () => record('crash', 'the renderer process crashed');
  const onRequestFailed = (req) => {
    const type = typeof req.resourceType === 'function' ? req.resourceType() : '';
    if (type !== 'document' && type !== 'script') return;
    const why = req.failure && req.failure() ? req.failure().errorText : 'unknown';
    record('requestfailed', `${type} ${req.url()} — ${why}`);
  };

  page.on('pageerror', onPageError);
  page.on('crash', onCrash);
  page.on('requestfailed', onRequestFailed);

  return {
    failures,

    /**
     * Wait for `predicate` to become true in the page, OR for the page to
     * die — whichever happens first.
     *
     * @param predicate  A JS expression string, as `page.waitForFunction` takes.
     * @param timeoutMs  REQUIRED. The sentinel's own budget, for the case where
     *                   the page is alive and simply has not finished.
     * @param budget     What that budget IS, in words, for the failure line.
     * @throws           If the page died, or the budget ran out.
     */
    async race(predicate, { timeoutMs, budget } = {}) {
      if (typeof timeoutMs !== 'number' || !Number.isFinite(timeoutMs) || timeoutMs <= 0) {
        throw new Error(
          `sentinel.race: timeoutMs is REQUIRED and must be a positive number ` +
            `(got ${JSON.stringify(timeoutMs)}). An unnamed sentinel budget is the ` +
            `anonymous-ceiling defect navigate.cjs documents.`
        );
      }
      const died = new Promise((resolve) => {
        if (failures.length > 0) resolve();
        else waiters.push(resolve);
      }).then(() => 'died');

      // The LOSER of this race is abandoned but not cancelled: when the page
      // dies, `waitForFunction` stays pending and rejects later — at its own
      // timeout, or at once when the page closes. An abandoned rejection with
      // no handler is an unhandled rejection, which Node turns into a process
      // abort, and it would kill the driver BEFORE it printed the diagnosis
      // this whole file exists to print. So the rejection is folded into a
      // value here rather than left to escape.
      let waitError = null;
      const finished = page.waitForFunction(predicate, null, { timeout: timeoutMs }).then(
        () => 'finished',
        (e) => {
          waitError = e;
          return 'wait-failed';
        }
      );

      const outcome = await Promise.race([died, finished]);

      if (outcome === 'died' || failures.length > 0) {
        throw new Error(
          `[${label}] THE PAGE DIED BEFORE IT FINISHED — the benchmark did not ` +
            `reach its own completion sentinel, so nothing it may have recorded is ` +
            `a measurement. This is reported now rather than after ${budget}, ` +
            `which had not yet run out and would have said only that time passed. ` +
            `What happened:\n  ` +
            failures.map((f) => `${f.kind}: ${f.detail}`).join('\n  ')
        );
      }
      if (outcome === 'wait-failed') {
        throw new Error(
          `[${label}] ${budget} RAN OUT — the page was still alive and had not ` +
            `thrown, so this is the benchmark genuinely not finishing rather than ` +
            `a fault being reported late. Underlying: ${waitError && waitError.message}`
        );
      }
      return outcome;
    },

    dispose() {
      page.off('pageerror', onPageError);
      page.off('crash', onCrash);
      page.off('requestfailed', onRequestFailed);
    },
  };
}

module.exports = { watchPage };
