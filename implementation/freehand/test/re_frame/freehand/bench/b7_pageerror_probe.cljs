(ns re-frame.freehand.bench.b7-pageerror-probe
  "B7's page-error probe — **the real B7 app, plus one detached throw**.

  rf2-va5nm. Not an instrument and not a stub: this namespace exists so
  `b7_run.cjs`'s `pageerror` refusal can be WATCHED FIRING in the driver's
  own process, end to end, against a real `:advanced` bundle and a real
  headless Chromium.

  ## Why it is the real app and not a fixture

  rf2-sib23 wired the refusal into all nine bench drivers and could only
  watch five of them fire. The four it could not — this driver, the reads
  ladder, the spine ablation and P0 — have no `--no-build` knob, so their
  refusal is unreachable without a release build, and their page APIs
  (`window.B7H`'s reader surface here) are too large to stub faithfully.
  rf2-va5nm records the judgement that mattered: *a wrong stub is not a
  cheaper proof, it is a fiction that can pass or fail for reasons that
  have nothing to do with the gate.*

  So there is no stub. `-main` below calls `b7-app/-main` — the published
  entry, unchanged, doing everything it always does — and then schedules a
  throw from a task the app does not own. That is rf2-sib23's fault shape
  exactly: the page throws AND STILL reaches its own completion sentinel,
  which is the case no page-side `try`/`catch` can close under React 19.2
  and which every one of the nine used to exit 0 underneath.

  ## The throw is DETACHED, and that is the whole point

  A `setTimeout` callback is a task the page's `-main` has already returned
  from. Its throw escapes `b7-app/-main`'s `(catch :default e ...)`, sets no
  `window.B7_ERROR`, and rejects no `page.evaluate` — it reaches Playwright
  as `pageerror` and nowhere else. `sentinel.cjs`'s header carries the
  measured account of why.

  A note from rf2-sib23 that cost it a false green, kept here because this
  file is where the next reader will need it: **a page that finishes before
  the throw can fire proves nothing in either direction.** Its first
  throwing stub exited 0 — correctly — because the stub settled in
  milliseconds and the timer never ran. Here the sentinel is set inside the
  `<script>` and the driver then makes two CDP round trips before it closes
  the browser, so the task queue is drained long before the page goes away.
  The proof is not the exit code alone: the driver prints
  `[b7] PAGE PAGEERROR: ...` from `watchPage` when it records one, and that
  line is what says the throw actually happened.

  ## Reproducing it (rf2-va5nm's proof, both directions)

      # 1. the CLEAN direction — the published entry, exit 0
      cd implementation
      B7_MOUNT_QUERY='&rounds=1&warmup=1&samples=2' \\
        node freehand/test/re_frame/freehand/bench/b7_run.cjs --only mount-frame

      # 2. the THROWING direction — same driver, same build id, same row,
      #    one extra detached throw. Exit 1, naming the error.
      B7_INIT_FN=re-frame.freehand.bench.b7-pageerror-probe/-main \\
      B7_OUT_DIR=out/b7-pageerror-probe \\
      B7_MOUNT_QUERY='&rounds=1&warmup=1&samples=2' \\
        node freehand/test/re_frame/freehand/bench/b7_run.cjs --only mount-frame

  `B7_INIT_FN` and `B7_OUT_DIR` are the driver's OWN published seams — they
  exist so an ablation ladder can ride this collector rather than grow a
  second one — so nothing in `b7_run.cjs` had to change to make its refusal
  observable. **No `--no-build` knob was added**: direction 2 above builds
  the bundle exactly as a published run does, which is the point.
  `--only mount-frame` is used because that row's exit consults `B7_ERROR`
  and the page's failures and NOTHING else, so a non-zero exit cannot be
  the arm-order guard or the positive control wearing this gate's clothes.

  NOTHING SHIPS THIS. No driver names this namespace by default, no build
  in `shadow-cljs.edn` points at it, and `pageerror_exit_path.test.cjs`
  pins both facts."
  (:require [re-frame.freehand.bench.b7-app :as b7-app]))

(defn ^:export -main
  []
  (b7-app/-main)
  ;; AFTER the app has returned, and therefore after the sentinel it set.
  (js/setTimeout
   (fn []
     (js/console.log ";; B7 probe: throwing from a detached task (rf2-va5nm)")
     (throw (js/Error. "rf2-va5nm b7 probe: a detached task threw after B7's sentinel was set")))
   0)
  nil)
