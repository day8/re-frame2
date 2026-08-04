(ns re-frame.bench.p0-pageerror-probe
  "P0's page-error probe — **the real P0 app, plus one detached throw**.

  rf2-va5nm, and the sibling of
  `re-frame.freehand.bench.b7-pageerror-probe`. Not an instrument and not a
  stub: this namespace exists so `p0_run.cjs`'s `pageerror` refusal can be
  WATCHED FIRING in the driver's own process, end to end, against a real
  `:advanced` bundle and a real headless Chromium.

  ## Why it is the real app and not a fixture

  rf2-sib23 wired the refusal into all nine bench drivers and could only
  watch five of them fire. P0 was one of the four it could not, because its
  exit is an enumerated inline block with no pure exported verdict AND its
  page API is far too large to stub — four rows at one page per clock
  round, `window.P0H`, `window.P0A`, `window.P0_ROUND`. rf2-va5nm records
  the judgement: *a wrong stub is not a cheaper proof, it is a fiction that
  can pass or fail for reasons that have nothing to do with the gate.*

  So there is no stub. `-main` below calls `p0-app/-main` — the published
  entry, unchanged — and then schedules a throw from a task the app does
  not own. That is rf2-sib23's fault shape exactly: the page throws AND
  STILL reaches its own completion sentinel, the case no page-side
  `try`/`catch` can close under React 19.2.

  ## The throw is DETACHED, and that is the whole point

  A `setTimeout` callback is a task `p0-app/-main` has already returned
  from. Its throw escapes the app's `(catch :default e ...)`, sets no
  `window.P0_ERROR`, and rejects no `page.evaluate` — it reaches Playwright
  as `pageerror` and nowhere else. `sentinel.cjs`'s header carries the
  measured account of why.

  rf2-sib23's false green is worth repeating here: **a page that finishes
  before the throw can fire proves nothing in either direction.** Its first
  throwing stub exited 0 — correctly — because the stub settled in
  milliseconds and the timer never ran. Here the driver holds the heap page
  open for a whole measurement row after `P0_READY` flips, so the task
  queue is drained many times over. The exit code alone is not the proof:
  `watchPage` prints `[p0] PAGE PAGEERROR: ...` when it records one, and
  that line is what says the throw actually happened.

  ## Reproducing it (rf2-va5nm's proof, both directions)

      # 1. the CLEAN direction — the published entry, exit 0
      cd implementation
      node core/test/re_frame/bench/p0_run.cjs --only heap

      # 2. the THROWING direction — same driver, same build id, same row,
      #    one extra detached throw. Exit 1, naming the error.
      P0_INIT_FN=re-frame.bench.p0-pageerror-probe/-main \\
      P0_OUT_DIR=out/p0-pageerror-probe \\
        node core/test/re_frame/bench/p0_run.cjs --only heap

  `P0_INIT_FN` and `P0_OUT_DIR` are the driver's OWN seams and are already
  recorded in its provenance (`out.initFn`), so nothing in `p0_run.cjs` had
  to change to make its refusal observable. **No `--no-build` knob was
  added**: direction 2 builds the bundle exactly as a published run does,
  which is the point.

  NOTHING SHIPS THIS. No driver names this namespace by default, no build
  in `shadow-cljs.edn` points at it, and `pageerror_exit_path.test.cjs`
  pins both facts."
  (:require [re-frame.bench.p0-app :as p0-app]))

(defn ^:export -main
  []
  (p0-app/-main)
  ;; AFTER the app has returned, and therefore after the sentinel it set.
  (js/setTimeout
   (fn []
     (js/console.log ";; P0 probe: throwing from a detached task (rf2-va5nm)")
     (throw (js/Error. "rf2-va5nm p0 probe: a detached task threw after P0's sentinel was set")))
   0)
  nil)
