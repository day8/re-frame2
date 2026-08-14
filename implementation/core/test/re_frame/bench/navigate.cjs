'use strict';

/*
 * The bench drivers' ONE navigation, with its ceiling NAMED (rf2-p9fa3).
 *
 * THE DEFECT
 * ----------
 * `page.goto(url, { waitUntil: 'load' })` with no `timeout:` takes
 * Playwright's 30s default. On a bench driver that default is a SECOND
 * budget: invisible in the source, unreachable from the driver's own knob,
 * and — the part that costs an afternoon — indistinguishable in a log from
 * the budget the driver does own. `page.goto: Timeout 30000ms exceeded`
 * reads like the bench's own timeout, so the fix reached for is a bigger
 * bench timeout, which cannot move it.
 *
 * `'load'` is not merely tight here, it is the WRONG EVENT. Every bench page
 * in this directory is a plain `:browser` module whose `:init-fn` runs at
 * bundle-execution time — inside the `<script>` the document is still
 * loading. So `load` cannot fire until the benchmark has yielded, which is
 * precisely what each driver's own `waitForFunction` sentinel already waits
 * for, against a budget of five to twenty MINUTES. Waiting for `load` is
 * waiting for the measurement, against the one budget that was never meant
 * to bound it.
 *
 * Demonstrated (rf2-p9fa3), against this exact document shape — a page whose
 * script burns 35s synchronously during load:
 *
 *     goto{waitUntil:'load'}                      30007ms  THREW (30000ms)
 *     goto{waitUntil:'commit',timeout:60000}+poll 35020ms  RESOLVED
 *
 * WHY THIS FILE EXISTS RATHER THAN FIVE COPIES
 * --------------------------------------------
 * `b10_prod_run.cjs` was ALREADY given `{ waitUntil: 'commit', timeout:
 * 60000 }` by hand while its four siblings kept the defect — one driver hit
 * the ceiling, one driver was patched, and the directory drifted. Per-file
 * drift is the failure mode, so the directory gets one navigation and the
 * per-site judgement stays where it belongs: at the call, in the `budget` it
 * names.
 *
 * WHY `timeoutMs` IS REQUIRED AND NOT DEFAULTED
 * ---------------------------------------------
 * A default would be the anonymous ceiling again, merely relocated into our
 * own source. `NAV_TIMEOUT_MS` below is the number every driver here passes;
 * it is exported so there is one of it, and passed explicitly so each call
 * still says so.
 */

/*
 * 60s for a `'commit'` — the point at which the local `http.createServer`
 * these drivers stand up has answered with headers. That is a filesystem
 * read over loopback; 60s is enormous for it, and enormously smaller than
 * the five-to-twenty-minute sentinel waits it must stay distinguishable
 * from. Handing the navigation the bench's own budget would trade a
 * mislabelled failure for a silent one: a driver that never reached its page
 * would sit for twenty minutes looking like a slow benchmark.
 *
 * The number is `b10_prod_run.cjs`'s, kept: it was the one site that had
 * already made this call correctly.
 */
const NAV_TIMEOUT_MS = 60 * 1000;

/**
 * Navigate, and make a navigation failure say that it IS one.
 *
 * @param page        Playwright page.
 * @param url         Where to go.
 * @param timeoutMs   REQUIRED. This navigation's own ceiling.
 * @param waitUntil   REQUIRED. `'commit'` for every bench page today — see above.
 * @param budget      What this ceiling is NOT, in words, for the failure line.
 */
async function navigate(page, url, { timeoutMs, waitUntil, budget } = {}) {
  if (typeof timeoutMs !== 'number' || !Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    throw new Error(
      `navigate: timeoutMs is REQUIRED and must be a positive number (got ` +
        `${JSON.stringify(timeoutMs)}). Omitting it hands the navigation ` +
        "Playwright's 30s default — a ceiling no bench budget can reach, and " +
        "whose failure line reads like the bench's own timeout (rf2-p9fa3)."
    );
  }
  if (!waitUntil) {
    throw new Error(
      'navigate: waitUntil is REQUIRED. Omitting it takes Playwright\'s ' +
        "'load', which on a bench page cannot fire until the benchmark has " +
        'yielded (rf2-p9fa3).'
    );
  }
  try {
    await page.goto(url, { waitUntil, timeout: timeoutMs });
  } catch (err) {
    throw new Error(
      `NAVIGATION FAILED — this is the page.goto ceiling (waitUntil: ` +
        `'${waitUntil}', timeout: ${timeoutMs}ms), NOT ${budget}, which had ` +
        `not yet started. The page at ${url} was never reached, so nothing ` +
        `was measured and there is no figure to distrust. Raising the bench ` +
        `budget cannot move this (rf2-p9fa3). Underlying: ${err.message}`
    );
  }
}

module.exports = { navigate, NAV_TIMEOUT_MS };
