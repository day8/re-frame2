(ns re-frame.story.play.presence-real-clock-cljs-test
  "S4-H (rf2-qwzmt) — the Story presence rung driven against the REAL
  framework verb and the REAL presence clock.

  `re-frame.ui.test/flush-presence!` advances
  `re-frame.ui.presence-runtime`'s exit scheduler — the SAME registry a
  mounted `(ui/presence {:timeout-ms n} …)` boundary arms its retention
  timers in. Scheduling a real exit and driving it through Story's
  `[:flush-presence]` step proves the rung reaches the framework verb FOR
  REAL, no stub, with no DOM required: mounted three-phase behaviour
  (`:mounting` → `:present` → `:unmounting`) is the framework's own test
  (`re-frame.ui.presence-dom-cljs-test`) and Story does not re-prove it.

  Pure `.cljs`, not `.cljc`, for two reasons that point the same way:

  - the verb is PROMISE-backed on CLJS, so these tests are `async`, and
    cljs.test then requires MAP fixtures — which a `.cljc` may not use
    (`re-frame.story.meta-fixtures-test`: the map form silently skips every
    deftest on the JVM half);
  - the presence CLOCK is a CLJS-only surface. The JVM arm of
    `flush-presence!` is a documented no-op — the structural host has no
    lifecycle to advance — so there is nothing here for the JVM to run.

  The host-agnostic half of the rung (the grammar, the capability +
  determinism classification, the hook, and the red/green playback proof
  against a stub presence host) lives in the `.cljc` sibling
  `presence-cljs-test`, whose harness this ns reuses.

  The wall clock is DISABLED throughout, so the logical advance is the sole
  removal driver — the determinism the whole rung exists for."
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            [re-frame.core                     :as rf]
            [re-frame.router                   :as router]
            ;; The shipped optional bridge — the one canonical installation
            ;; path (rf2-36biz). Requiring it is exactly what a consuming app
            ;; does; `install!` re-arms it after the shared fixture's teardown.
            [re-frame.story.play.presence-host :as presence-host]
            [re-frame.story.play.runner-events :as re]
            [re-frame.ui.presence-runtime      :as presence-rt]
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
  leases; headless, a dispatched event is the observable."
  []
  (presence-rt/schedule-exit!
    300 #(router/dispatch-sync! [:presence/exited] {:frame shared/presence-frame})))

(defn- after-tick
  "Yield one macrotask — exactly what the run loop does after an async-yield
  step (`runner/async-yield-step-types`). The framework verb holds a SINGLE
  in-flight act token, so calling it again before the previous act settles is
  the framework's own `:rf.error/ui-test-overlapping-act` misuse error. A
  `setTimeout` 0 runs only after the microtask queue has drained to empty, so
  the act has settled. A test driving `exec-step!` DIRECTLY owes the same
  yield the loop gives — including after the LAST advance, or the still-active
  act leaks into the next test."
  [f]
  (js/setTimeout f 0))

(deftest real-flush-presence-advances-the-framework-clock
  (async done
    (install-real-presence-host!)
    (schedule-real-exit!)
    (is (= 1 (presence-rt/pending-count)) "one exit retained")
    (shared/exec-step! shared/presence-frame 0 [:flush-presence 100])
    ;; The advance + its removal callbacks run synchronously inside the awaited
    ;; act; only the React commits settle on the microtask queue.
    (is (= 1 (presence-rt/pending-count))
        "below :timeout-ms the exit is STILL retained — the partial-advance
         arity is what lets a script observe the :unmounting phase")
    (is (nil? (:toast (shared/db))))
    (after-tick
      (fn []
        (shared/exec-step! shared/presence-frame 1 [:flush-presence])
        (is (zero? (presence-rt/pending-count))
            "advancing to quiescence fired the retained exit")
        (is (= :removed (:toast (shared/db)))
            "the terminal removal is observable to the next script step")
        (after-tick
          (fn []
            ;; exactly-once: a further advance re-fires nothing.
            (shared/exec-step! shared/presence-frame 2 [:flush-presence])
            (is (zero? (presence-rt/pending-count)))
            (is (= :removed (:toast (shared/db))))
            ;; Yield once more so THIS act settles before the test ends — an
            ;; act still in flight would collide with the next test's advance.
            (after-tick done)))))))

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
