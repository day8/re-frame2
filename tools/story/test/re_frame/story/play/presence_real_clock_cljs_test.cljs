(ns re-frame.story.play.presence-real-clock-cljs-test
  "S4-H (rf2-qwzmt) — the Story presence rung driven against the REAL
  framework verb and the REAL presence clock.

  The shipped bridge advances `re-frame.freehand.presence-runtime`'s exit
  scheduler — the SAME registry a mounted `(v/presence {:timeout-ms n} …)`
  boundary arms its retention timers in. Scheduling a real exit and driving
  it through Story's `[:flush-presence]` step proves the rung reaches the
  framework verb FOR REAL, no stub, with no DOM required. The mounted proof
  that a real RETAINED CHILD leaves the DOM under this rung is the browser
  companion `presence-freehand-dom-cljs-test`; the framework's own
  three-phase behaviour (`:mounting` → `:present` → `:unmounting`) is pinned
  at `re-frame.freehand.presence-dom-cljs-test` and Story does not re-prove
  it.

  Pure `.cljs`, not `.cljc`, for two reasons that point the same way:

  - the rung must be exercised against a PROMISE-backed host as well as the
    shipped synchronous one, so these tests are `async`, and cljs.test then
    requires MAP fixtures — which a `.cljc` may not use
    (`re-frame.story.meta-fixtures-test`: the map form silently skips every
    deftest on the JVM half);
  - the presence CLOCK is a CLJS-only surface. The JVM arm of the bridge is
    a documented no-op — the structural host has no lifecycle to advance —
    so there is nothing here for the JVM to run.

  The host-agnostic half of the rung (the grammar, the capability +
  determinism classification, the hook, and the red/green playback proof
  against a stub presence host) lives in the `.cljc` sibling
  `presence-cljs-test`, whose harness this ns reuses.

  The wall clock is DISABLED throughout, so the logical advance is the sole
  removal driver — the determinism the whole rung exists for.

  This namespace establishes EVERYTHING it consumes and is run standalone as
  well as in the consolidated bundle (rf2-i36h6). In particular it takes every
  advance through the run loop's own settle seam rather than assuming a
  macrotask yield outran the host's own settlement; see
  `flush-presence-step!`.

  The final block (rf2-iz0t8) covers the PROMISE-BACKED-ness itself rather
  than the clock: resolved and rejected thenable controls driven through the
  real run loop and the real interactive stepper. Those need a host whose
  Promise the test can settle on demand, which the real clock cannot give —
  so they use two-line stub hosts, and the real-clock tests above are what
  keep the stubs honest about the shape they stand in for."
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            [re-frame.core                     :as rf]
            [re-frame.router                   :as router]
            [re-frame.story.play               :as play]
            [re-frame.story.play.presence      :as story-presence]
            ;; The shipped optional bridge — the one canonical installation
            ;; path (rf2-36biz). Requiring it is exactly what a consuming app
            ;; does; `install!` re-arms it after the shared fixture's teardown.
            [re-frame.story.play.presence-host :as presence-host]
            [re-frame.story.play.runner-events :as re]
            [re-frame.freehand.presence-runtime :as presence-rt]
            ;; The shared harness (fresh registrar + runtime + variant frame,
            ;; and the same private step executor) — one harness across both
            ;; halves of the rung's coverage.
            [re-frame.story.play.presence-cljs-test :as shared]))

;; cljs.test honours the MAP fixture form, and REQUIRES it once a ns carries
;; `async` tests: a fn fixture would return before the async body settles, so
;; its teardown could never be honoured. Legitimate here precisely because
;; this file is pure `.cljs` (see the ns docstring).
(use-fixtures :each {:before shared/setup! :after shared/teardown!})

(defn- install-real-presence-host! []
  (presence-rt/reset-clock!)
  ;; Test-only determinism: the logical advance is the SOLE removal driver
  ;; here. The BRIDGE deliberately leaves the wall clock armed (a variant a
  ;; human is merely clicking through in the canvas has no [:flush-presence]
  ;; step to release its retained children) — see `presence-host`.
  (presence-rt/set-wall-clock! false)
  ;; Install through the SHIPPED bridge (rf2-36biz), not a hand-rolled copy of
  ;; it, so this whole suite is an acceptance test of the one canonical
  ;; integration path. The bridge reuses the framework verb's two arities
  ;; EXACTLY — Story adds no clock, no phase model, no scheduler of its own.
  (presence-host/install!))

(defn- schedule-real-exit!
  "Arm a REAL retained exit due at 300ms of logical time. Its removal
  callback stands in for the app-visible consequence of the terminal
  unmount — a mounted boundary's removal releases the retained subtree's
  handles; headless, a dispatched event is the observable."
  []
  (presence-rt/schedule-exit!
    300 #(router/dispatch-sync! [:presence/exited] {:frame shared/presence-frame})))

(def ^:private settle-step-result!
  "The private SETTLED-result seam, reached via var-quote — the same
  established Story-test seam `shared/exec-step!` uses, one step further down
  the run loop. `(settle-step-result! idx step provisional k)` calls `k` with
  the step's FINAL result: for a Promise-backed `[:flush-presence]` that means
  once the host's thenable has settled, for every other step immediately."
  @#'re/settle-step-result!)

(defn- flush-presence-step!
  "Drive ONE `[:flush-presence …]` step to completion and hand `k` its SETTLED
  step-result. These are the two moves `runner-events/run-loop!` makes, in
  this order:

    1. SETTLE the advance (`settle-step-result!`). For the shipped Freehand
       bridge that is SYNCHRONOUS — the advance runs inside the substrate's
       `flushSync` commit — so the callback fires before the call returns.
       For a Promise-backed host it AWAITS the thenable, because 'the advance
       is over' is then a fact ONLY that thenable can report.
    2. THEN yield one macrotask, so any commits the advance queued have
       landed before the next step reads them.

  A test driving `exec-step!` DIRECTLY owes BOTH. This namespace used to owe
  only the yield, on the premise that a `setTimeout` 0 lands after a
  Promise-backed host has settled. It does not: a macrotask is not ordered
  against a host's own microtask chain, and whenever the yield lost that race
  the NEXT step re-entered a host operation still in flight and advanced
  NOTHING.

  That made this namespace's pass ORDER-DEPENDENT (rf2-i36h6): green under
  `npm run test:cljs`, where a namespace ahead of it had already exercised the
  act path, and RED for anyone narrowing to it alone — a result carrying no
  information, and actively misleading to a developer whose own bug it is not.
  The fix is to establish the settlement HERE, the way the run loop does,
  rather than to pin a suite order that would only hide the next instance."
  [idx step k]
  (settle-step-result!
    idx step (shared/exec-step! shared/presence-frame idx step)
    (fn [settled] (js/setTimeout #(k settled) 0))))

(defn- advance-reached-the-verb!
  "POSITIVE CONTROL for one advance. The retention assertions around it hold
  in TWO different worlds — the advance ran, or the advance never happened at
  all (`pending-count` is unchanged either way below `:timeout-ms`, and a
  never-fired exit leaves `:toast` nil exactly like a not-yet-due one). The
  STEP-RESULT is what tells those worlds apart, so every advance is checked
  through it: a reached, settled advance carries no `:exception` and no
  `:cannot-run?`. Without this the in-flight-host failure above would have
  surfaced only as a downstream retention assertion two ticks later, which is
  precisely why it read as somebody else's bug."
  [result]
  (is (nil? (:exception result))
      "the advance REACHED the framework verb and settled cleanly")
  (is (nil? (:cannot-run? result))
      "a presence host was installed — this is a real advance, not a refusal"))

(deftest real-flush-presence-advances-the-framework-clock
  (async done
    (install-real-presence-host!)
    (schedule-real-exit!)
    (is (= 1 (presence-rt/pending-count)) "one exit retained")
    (flush-presence-step!
      0 [:flush-presence 100]
      (fn [r]
        (advance-reached-the-verb! r)
        (is (= 1 (presence-rt/pending-count))
            "below :timeout-ms the exit is STILL retained — the partial-advance
             arity is what lets a script observe the :unmounting phase")
        (is (nil? (:toast (shared/db))))
        (flush-presence-step!
          1 [:flush-presence]
          (fn [r]
            (advance-reached-the-verb! r)
            (is (zero? (presence-rt/pending-count))
                "advancing to quiescence fired the retained exit")
            (is (= :removed (:toast (shared/db)))
                "the terminal removal is observable to the next script step")
            (flush-presence-step!
              2 [:flush-presence]
              (fn [r]
                ;; exactly-once: a further advance re-fires nothing.
                (advance-reached-the-verb! r)
                (is (zero? (presence-rt/pending-count)))
                (is (= :removed (:toast (shared/db))))
                ;; `flush-presence-step!` already awaited THIS act and yielded
                ;; past its commits, so no act is in flight as the test ends —
                ;; one would collide with the next test's advance.
                (done)))))))))

(deftest real-playback-settles-a-presence-bearing-script
  (async done
    (install-real-presence-host!)
    (schedule-real-exit!)
    ;; The whole point: ONE play, driven by the real run loop, against the real
    ;; framework clock — the retained child is asserted still present below
    ;; :timeout-ms and removed after quiescence, with no [:wait ms] anywhere.
    (re/run! shared/presence-frame "real-presence"
             {:name   "real-presence"
              :script [[:dispatch [:presence/tick]]
                       [:flush-presence 100]
                       [:assert-db [:toast] :retained]
                       [:flush-presence]
                       [:assert-db [:toast] :removed]]}
             (fn [state]
               (is (= :pass (:status state))
                   "a presence-bearing variant plays back deterministically")
               (is (= :removed (:toast (shared/db))))
               (is (zero? (presence-rt/pending-count)))
               (done)))))

(deftest real-playback-without-the-step-cannot-settle-the-retention
  (async done
    (install-real-presence-host!)
    (schedule-real-exit!)
    ;; RED — the same script with the rung REMOVED. Ordinary settlement drains
    ;; the router to a fixed point and still cannot touch the presence clock,
    ;; so the retained exit never fires and the removal assertion FAILS. This
    ;; is the race `[:flush-presence]` exists to close.
    (re/run! shared/presence-frame "no-presence"
             {:name   "no-presence"
              :script [[:dispatch [:presence/tick]]
                       [:assert-db [:toast] :removed]]}
             (fn [state]
               (is (= :fail (:status state))
                   "playback alone raced the presence timeout")
               (is (= :retained (:toast (shared/db)))
                   "the child is still retained, exactly as the failure said")
               (is (= 1 (presence-rt/pending-count))
                   "the real exit is still pending — nothing advanced the clock")
               (done)))))

;; ===========================================================================
;; Promise-backed host settlement (rf2-iz0t8)
;; ===========================================================================
;;
;; The shipped Freehand bridge is SYNCHRONOUS, but the hook's contract is not
;; — a host MAY return a Promise, and the donor's did. `presence/advance!`
;; used to call the verb inside a synchronous `try` and immediately return
;; `{:status :advanced}` — so the executor recorded a clean step, the run loop
;; merely yielded a `setTimeout` 0, and a LATER rejection landed outside the
;; `try`, became an unhandled rejection, and could no longer change the
;; already-recorded result. A presence-bearing play reported `:pass` over a
;; flush that had actually failed. These controls are now the ONLY coverage of
;; that arm — no shipped host exercises it — which is exactly why they stay.
;;
;; The two hosts below differ in exactly ONE thing — whether their Promise
;; resolves or rejects — so neither verdict can be an artefact of anything
;; else. The resolving host is the POSITIVE CONTROL: without it, "always
;; fail the step" would pass the rejection test while breaking every real
;; presence run.

(defn- install-thenable-host!
  "Install a host whose advance returns a Promise settling on the next
  microtask. `outcome` is `:resolve` or `:reject`. Records each advance's ms
  into `calls` so the tests can prove the verb was actually reached."
  [outcome calls]
  (story-presence/install-presence-flush!
    (fn [ms]
      (swap! calls conj ms)
      (if (= :reject outcome)
        (js/Promise.reject (ex-info "presence flush failed" {:ms ms}))
        (js/Promise.resolve :ok)))))

(deftest resolving-thenable-host-passes-the-run
  (async done
    (let [calls (atom [])]
      (install-thenable-host! :resolve calls)
      (re/run! shared/presence-frame "resolving"
               {:name   "resolving"
                :script [[:dispatch [:presence/tick]]
                         [:flush-presence 100]
                         [:assert-db [:toast] :retained]]}
               (fn [state]
                 (is (= :pass (:status state))
                     "a host whose Promise RESOLVES still passes — the await
                      must not turn every Promise-backed flush into a failure")
                 (is (= [100] @calls) "the verb was reached, ms threaded")
                 (done))))))

(deftest rejecting-thenable-host-fails-the-run
  (async done
    (let [calls (atom [])]
      (install-thenable-host! :reject calls)
      (re/run! shared/presence-frame "rejecting"
               {:name   "rejecting"
                :script [[:dispatch [:presence/tick]]
                         [:flush-presence 100]
                         [:assert-db [:toast] :retained]]}
               (fn [state]
                 (is (not= :pass (:status state))
                     "rf2-iz0t8 — the flush FAILED, so the run must not
                      report a pass. The following :assert-db holds either
                      way (the toast is retained because nothing advanced,
                      not because the advance stayed below :timeout-ms), so
                      only the STEP can carry the failure")
                 (is (= :fail (:status state)))
                 (is (= [100] @calls)
                     "the host really ran — this is a rejection, not a
                      never-installed host")
                 (let [flush-result (->> (:results state)
                                         (filter #(= :flush-presence (:type %)))
                                         first)]
                   (is (true? (:exception flush-result))
                       "the rejection lands in the EXISTING step-exception
                        vocabulary — the same one a synchronously throwing
                        host produces, not a new async status")
                   (is (false? (:passed? flush-result)))
                   (is (nil? (:cannot-run? flush-result))
                       "a rejected flush is a failure, not a refusal — the
                        host WAS installed and WAS reached"))
                 (done))))))

(deftest stepper-and-auto-run-agree-on-a-rejecting-host
  (async done
    (let [calls (atom [])]
      (install-thenable-host! :reject calls)
      ;; Seed the stepper cursor directly rather than through
      ;; `begin-stepper!`, which resolves the script off a REGISTERED
      ;; variant — registering one would add a story fixture without adding
      ;; any coverage. `stepper-state` is the documented substrate surface,
      ;; and `step-once!` (the fn under test) is driven exactly as the UI
      ;; widget drives it.
      (swap! play/stepper-state assoc shared/presence-frame
             {:remaining [[:flush-presence 100]] :ran [] :results []})
      (play/step-once! shared/presence-frame)
      ;; The stepper is synchronous, so at THIS point the host's Promise has
      ;; not settled. One macrotask later it has, and the recorded result must
      ;; have become the same failure the auto-run loop records — otherwise
      ;; the debugger would show a clean flush for a run that fails.
      (js/setTimeout
        (fn []
          (let [result (first (:results (get @play/stepper-state
                                             shared/presence-frame)))]
            (is (some? result) "the stepper recorded a result for the step")
            (is (true? (:exception result))
                "rf2-iz0t8 — the interactive stepper records the SETTLED
                 result, agreeing with the auto-run path")
            (is (false? (:passed? result)))
            (is (= [100] @calls)))
          ;; `stepper-state` is process-global — leave no cursor behind.
          (swap! play/stepper-state dissoc shared/presence-frame)
          (done))
        0))))
