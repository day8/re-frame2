'use strict';

/*
 * The bench drivers' ONE sentinel wait, raced against the page dying
 * (rf2-f5roa, from the PR #7268 and #7269 audits).
 *
 * THE DEFECT
 * ----------
 * Every driver in this directory ends its page's work the same way:
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
 * documents, wearing its other hat. There the budget was too small and
 * absorbed a fault it was never meant to bound; here it is enormous and
 * absorbs one it already knows about. Both end the same way: a log line that
 * says `Timeout 1200000ms exceeded` when what happened was a ReferenceError
 * at page-load, and an afternoon spent enlarging a budget that was never the
 * problem.
 *
 * RECORDED, not hypothetical. A contaminated Shadow cache produced a
 * ReactDOM `pageerror` under `hd8_run.cjs`; the driver logged it and then
 * waited the full twenty minutes for `HD8_DONE` before timing out. The
 * source was clean — rebuilding fixed it — which is exactly why this
 * matters: the *common* cause of a page error here is environmental, so it
 * is the case a driver meets most often and the one it handles worst.
 *
 * WHY A FILE AND NOT A PATCH IN EACH DRIVER
 * -----------------------------------------
 * `navigate.cjs`'s reasoning, unchanged: `b10_prod_run.cjs` was already
 * given the right navigation by hand while its four siblings kept the
 * defect, so the directory drifted. Two drivers carry this sentinel today
 * and both had the same fault. Per-file drift is the failure mode; the
 * directory gets one sentinel.
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
 * ...AND IT ONCE ONLY HELPED THE DRIVERS THAT USED IT (rf2-jvheq, settled
 * 2026-08-04; closed by rf2-sib23, 2026-08-05)
 * -------------------------------------------------------------------------
 * Nine drivers did not, and instead installed a bare handler that printed:
 *
 *     page.on('pageerror', (e) => console.error(`[b8] page error: ${e.message}`))
 *
 * with no array and no reference from the exit block. They were filed as an
 * AUDIT rather than a fault, because the sweep that found them could not show
 * the gap was reachable: each also waits on `'... || window.X_ERROR'`, which
 * covers every throw the app itself catches and reports. THE AUDIT WAS
 * SETTLED BY RUNNING IT RATHER THAN BY READING, AND THE GAP IS REAL:
 *
 *   1. Chromium raises `pageerror` for a throw inside `requestAnimationFrame`,
 *      inside `setTimeout`, and for an unhandled promise rejection, while a
 *      completion sentinel set elsewhere STILL becomes true. Measured against
 *      the pinned Playwright (1.59.1); all three cases exit 0 today.
 *
 *   2. Worse, and the reason no page-side `try/catch` can close it: React
 *      19.2.0 — the pin — does NOT rethrow an uncaught render error to the
 *      caller of `flushSync`/`render`. `defaultOnUncaughtError` hands it to
 *      `reportGlobalError` -> `reportError`
 *      (`react-dom-client.production.js` ~5888-5890, ~2307), which raises a
 *      global error and returns. So a render throw is INVISIBLE to the
 *      `(catch :default e ...)` in every one of those apps' `-main`, sets no
 *      `window.*_ERROR`, does not reject the `page.evaluate` that the
 *      READY-style drivers rely on — and the app carries on and sets its
 *      sentinel. Every one of the nine mounts through `react-dom/flushSync`.
 *
 *   3. `b10_prod_run.cjs` has a second, non-React path: `b10_two_clock.cljs`
 *      ~670/694/709 drives the run from two `setInterval`s and a live
 *      `MutationObserver`. A throw in any of those is a detached task — it
 *      escapes `-main`'s `try` AND the promise chain's `.catch`, and
 *      `setInterval` keeps firing, so the run still completes and sets
 *      `B10_DONE`.
 *
 * ALL NINE NOW CALL `watchPage` AND READ ITS `failures` AT THEIR EXIT
 * (rf2-sib23). The remedy is the one this file's other callers already had —
 * collect into an array and refuse — and nothing was removed to get it: each
 * bare handler was REPLACED by this collector, which still prints the same
 * error and additionally records it, plus the two failures the bare handler
 * could never see (`crash`, and a failed `document`/`script` request).
 *
 *   b6_prod_run.cjs   b6_profile_run.cjs   b7_run.cjs   b8_run.cjs
 *   b10_prod_run.cjs  reads_ladder_run.cjs spine_ablation_run.cjs
 *   ../../../../core/test/re_frame/bench/p0_run.cjs
 *   ../../../../adapters/reagent/test/re_frame/bench/hicasso_narrow_run.cjs
 *
 * ...AND THE NINE NOW RACE, TOO (rf2-qv761, 2026-08-05)
 * ---------------------------------------------------
 * rf2-sib23 gave the nine the COLLECTOR and deliberately left their sentinel
 * waits as `page.waitForFunction`, recording the absence so it would not be
 * read as an oversight. All twelve of those waits are now `race` calls, which
 * is the other half of this file finally reaching the drivers that needed it
 * most: a page that dies at load under `b10_prod_run.cjs` cost TWENTY
 * MINUTES to say so, and under `p0_run.cjs`'s clock row, thirty.
 *
 * TWO PROPERTIES MAKE THE CONVERSION SAFE, and they are worth stating because
 * neither is obvious:
 *
 *   1. IT CANNOT SHORTEN A RUN THAT WOULD HAVE PASSED. `race` rejects only on
 *      a recorded failure, and since rf2-sib23 every one of the nine already
 *      refuses at its exit on exactly that array. So any run this reports on
 *      early was already going to be non-zero; what changes is when, and the
 *      failure line names the cause instead of naming the clock.
 *   2. NO NEW EXIT CODE. Every rejection lands in a path each driver already
 *      had — `drive()`'s rejection handler in `b6_prod`, `b6_profile` and
 *      `b10_prod`; the existing `catch` in `b7`, `p0`, the ladder and the
 *      ablation; `failed` in `b8`; `hicasso_narrow`'s `main` catch — and all
 *      of those are that driver's existing 1.
 *
 * WHAT IT DOES COST, stated rather than discovered later. In rf2-sib23's own
 * case — the page that throws and STILL reaches its sentinel — the run now
 * fails AT THE WAIT rather than after printing its table, so the partial rows
 * a late throw would have left behind are no longer printed. The verdict is
 * identical either way (both are that driver's 1, both name the page error);
 * what is lost is diagnostic residue in the narrow window between "some rows
 * measured" and "sentinel set". The motivating case in rf2-f5roa and
 * rf2-qv761 is a `ReferenceError` at page load, where there is no residue to
 * lose and fifteen to thirty minutes to save. The exit-block refusals
 * rf2-sib23 installed are NOT removed and are not dead: a failure recorded
 * after the sentinel has already flipped — during the result `evaluate`s, the
 * measurement loop that follows a READY wait, or teardown — still reaches the
 * exit block and nowhere else.
 *
 * `pageerror_exit_path.test.cjs` pins both halves of the class: each driver's
 * refusal, the wiring from the handler to the exit code in all nine, and that
 * every sentinel wait in the fleet is raced rather than bare.
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
            `anonymous-ceiling defect (rf2-p9fa3).`
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
            `which had not yet run out and would have said only that time passed ` +
            `(rf2-f5roa). What happened:\n  ` +
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
