(ns re-frame.bench.p0-pageerror-probe
  "P0's page-error probe — **the real P0 app, plus one detached throw**.

  Not an instrument and not a stub: this namespace exists so
  `p0_run.cjs`'s `pageerror` refusal can be WATCHED FIRING in the driver's
  own process, end to end, against a real `:advanced` bundle and a real
  headless Chromium.

  ## Why it is the real app and not a fixture

  P0's exit is an enumerated inline block with no pure exported verdict
  AND its page API is far too large to stub — four rows at one page per
  clock round, `window.P0H`, `window.P0A`, `window.P0_ROUND` — and *a wrong
  stub is not a cheaper proof, it is a fiction that can pass or fail for
  reasons that have nothing to do with the gate.*

  So there is no stub. `-main` below calls `rf.bench.p0-app/-main` — the published
  entry, as it is — and then schedules a throw from a task the app does
  not own. That is exactly the fault shape the refusal exists for: the
  page throws AND STILL reaches its own completion sentinel, the case no
  page-side `try`/`catch` can close under React 19.2.

  ## The throw is DETACHED, and that is the whole point

  A `setTimeout` callback is a task `rf.bench.p0-app/-main` has already returned
  from. Its throw escapes the app's `(catch :default e ...)`, sets no
  `window.P0_ERROR`, and rejects no `page.evaluate` — it reaches Playwright
  as `pageerror` and nowhere else. `sentinel.cjs`'s header carries the
  measured account of why.

  **A page that finishes before the throw can fire proves nothing in
  either direction**: a throwing stub that settles in milliseconds exits
  0 — correctly — because its timer never runs. Here the driver holds the heap page
  open for a whole measurement row after `P0_READY` flips, so the task
  queue is drained many times over. The exit code alone is not the proof:
  `watchPage` prints `[p0] PAGE PAGEERROR: ...` when it records one, and
  that line is what says the throw actually happened.

  ## Reproducing it, both directions

      # 1. the CLEAN direction — the published entry, exit 0
      cd bench/fresco
      node src/re_frame/bench/p0_run.cjs --only heap

      # 2. the THROWING direction — same driver, same build id, same row,
      #    one extra detached throw. Exit 1, naming the error.
      P0_INIT_FN=re-frame.bench.p0-pageerror-probe/-main \\
      P0_OUT_DIR=out/p0-pageerror-probe \\
        node src/re_frame/bench/p0_run.cjs --only heap

  `P0_INIT_FN` and `P0_OUT_DIR` are the driver's OWN seams and are
  recorded in its provenance (`out.initFn`), so the refusal is observable
  with nothing probe-specific in `p0_run.cjs`. **There is no `--no-build`
  knob**: direction 2 builds the bundle exactly as a published run does,
  which is the point.

  NOTHING SHIPS THIS. No driver names this namespace by default and no
  build in `shadow-cljs.edn` points at it."
  (:require [re-frame.bench.p0-app :as rf.bench.p0-app]))

(defn ^:export -main
  []
  (rf.bench.p0-app/-main)
  ;; AFTER the app has returned, and therefore after the sentinel it set.
  (js/setTimeout
   (fn []
     (js/console.log ";; P0 probe: throwing from a detached task")
     (throw (js/Error. "p0 probe: a detached task threw after P0's sentinel was set")))
   0)
  nil)
