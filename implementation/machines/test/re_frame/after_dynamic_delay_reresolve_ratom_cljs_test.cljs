(ns re-frame.after-dynamic-delay-reresolve-ratom-cljs-test
  "rf2-wmpte — a machine's `:after [sub-vec]` dynamic delay RE-RESOLVES on the
  ratom family, driven by nothing but the timer's own wiring.

  THE DEFECT THIS PINS. `re-frame.machines.timer` resolved a sub-vec delay by
  `subs/subscribe` → plain `@reaction` → `add-watch`, on the stated premise
  that the subscribe \"keeps the reaction live\". On the ratom family that is
  false, and silently so: a subscription IS a bare `reagent.ratom/Reaction`,
  built deliberately WITHOUT `:auto-run`, and a Reaction learns its sources
  only through `deref-capture`. The plain deref runs outside `*ratom-context*`,
  so it runs the body raw and leaves `watching` nil — the node sits in no
  source's watcher set, `_handle-change` is never called, `_queued-run`
  short-circuits on `(some? watching)`, and even `reagent.core/flush` moves
  nothing. The `add-watch` RECORDED a callback that could not fire.

  Observably: the first arming resolved correctly and the delay then never
  re-resolved for the rest of that arming's life. No trace, no error. Spec 005
  §Delayed `:after` transitions and `docs/api/re-frame.machines.md` promise the
  re-resolution unconditionally. The fix is the observation port's
  (`re-frame.substrate.observation/build-node-handle!`, rf2-8cnxg): ACTIVATE,
  then watch.

  WHY NO SUITE SAW IT — the untested-combination axis. Every CLJS timer suite
  that drives a dynamic delay installs plain-atom and, KNOWING a plain-atom
  derived value does not push, `with-redefs`es a plain controllable atom in
  place of the reaction (`after_fire_reap_cljs_test.cljc` says so in its own
  header). A plain atom always notifies: the substitution that makes those
  tests deterministic is the substitution that makes them blind. So this file
  stubs the HOST CLOCK and nothing else — `subs/subscribe` is the real one and
  the reaction under the watch is the real cached subscription node, because
  the stand-in is precisely what hid the bug.

  CLJS-only (the ratom family is CLJS); the `-cljs-test` ns suffix enrols it in
  the consolidated `:node-test` build. No DOM and no React are needed — the
  claim is about a notification channel, not a render."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.ratom :as ratom]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            ;; Loading `re-frame.machines` installs the machines-artefact
            ;; late-bind hooks (`reg-machine`, `reset-timers!`); under a
            ;; single-ns run nothing else pulls it in.
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.machines.timer :as timer]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter reagent-adapter/adapter})
  mtest/trace-capture-fixture)

(defn- live-entry
  "The `:rf/default` frame's single live `:after` timer entry, or nil."
  []
  (first (vals (get @timer/after-timers :rf/default {}))))

;; White-box, and only ever CORROBORATING: `watching` is the Reagent field that
;; says \"this reaction is subscribed to its sources\". Read against anything
;; that is not a Reaction it would go vacuously nil, so every arm that consults
;; it also asserts the behaviour it is there to explain.
(defn- capturing? [rx]
  (some? (.-watching rx)))

(defn- register-dynamic-delay-machine! []
  (rf/reg-event :dyn/set-ms (fn [{:keys [db]} [_ ms]] {:db (assoc db :ms ms)}))
  (rf/reg-event :dyn/set-other (fn [{:keys [db]} [_ v]] {:db (assoc db :other v)}))
  (rf/reg-sub :dyn/ms (fn [db _] (:ms db)))
  (rf/reg-machine :dyn/timer
                  {:initial :idle
                   :data    {}
                   :states  {:idle    {:on {:go :running}}
                             :running {:after {[:dyn/ms] :expired}}
                             :expired {}}}))

;; ===========================================================================
;; the regression
;; ===========================================================================

(deftest dynamic-after-delay-re-resolves-when-the-delay-sub-moves
  (testing "a machine armed on `:after {[:dyn/ms] …}` cancels and re-arms at
            the NEW duration every time the delay subscription moves — with a
            REAL subscription node under the watch, on the Reagent adapter"
    (register-dynamic-delay-machine!)
    (rf/dispatch-sync [:dyn/set-ms 5000])
    (let [arms (atom [])]
      ;; The HOST CLOCK is the only thing stubbed: record each arm's duration
      ;; and hand back an opaque sentinel instead of a real setTimeout handle.
      ;; `managed-timer/cancel!` swallows throws, so the sentinel is safe on
      ;; the cancellation path.
      (with-redefs [interop/schedule-after!
                    (fn [_thunk ms] (swap! arms conj ms) (js-obj "rf-fake-handle" ms))]

        (rf/dispatch-sync [:dyn/timer [:go]])
        (is (= :running (mtest/machine-state :dyn/timer))
            "precondition — entered the `:after`-bearing state")
        (is (= [5000] @arms)
            "precondition — armed once, at the delay sub's current value")
        (is (= 5000 (:resolved-ms (live-entry)))
            "precondition — the registry entry records that resolution")

        (let [rx (:reaction (live-entry))]
          (is (some? rx) "precondition — a sub-vec delay holds a reaction")
          (is (satisfies? IWatchable rx)
              "precondition — the node IS watchable, so a silent channel here
               is the timer's fault and not the host's")
          (is (capturing? rx)
              "the timer ACTIVATED the delay node before watching it: it is
               subscribed to its sources. Before rf2-wmpte this was nil —
               watchable, watched, and unable to notify"))

        ;; ---- the movement the whole bead is about ------------------------
        (rf/dispatch-sync [:dyn/set-ms 9000])
        (ratom/flush!)

        (is (= [5000 9000] @arms)
            "THE REGRESSION — the delay sub moved, so the timer cancelled and
             re-armed at the new duration. Before the fix this stayed [5000]:
             the watch was recorded on a node that could never fire")
        (is (= 9000 (:resolved-ms (live-entry)))
            "…and the live registry entry carries the re-resolved duration")
        (is (some #(= :on-resolution (:reason (:tags %)))
                  (mtest/events-of :rf.machine.timer/cancelled))
            "the cancellation was traced with `:reason :on-resolution` — the
             re-resolution path ran, not some other cancel")

        ;; ---- and again, because the re-arm builds a FRESH node ------------
        ;; `on-sub-changed!` cancels (dropping the held subscription ref-count,
        ;; which disposes the reaction) before rescheduling, so the second
        ;; arming subscribes anew. The activation must therefore happen at
        ;; EVERY arming, not once at birth.
        (let [rx2 (:reaction (live-entry))]
          (is (capturing? rx2)
              "the RE-ARMED node is on the push path too — activation lives on
               the arming path, so it covers the reschedule as well as birth"))

        (rf/dispatch-sync [:dyn/set-ms 12000])
        (ratom/flush!)

        (is (= [5000 9000 12000] @arms)
            "a second move re-resolves as well — one activated arming does not
             buy the next one's channel")
        (is (= 12000 (:resolved-ms (live-entry))))))))

;; ===========================================================================
;; the negative control — activation must not make the channel chatty
;; ===========================================================================

(deftest a-write-that-does-not-move-the-delay-does-not-re-arm
  (testing "activation puts the node on the push path; it must not turn every
            app-db write into a cancel-and-reschedule"
    (register-dynamic-delay-machine!)
    (rf/dispatch-sync [:dyn/set-ms 5000])
    (let [arms (atom [])]
      (with-redefs [interop/schedule-after!
                    (fn [_thunk ms] (swap! arms conj ms) (js-obj "rf-fake-handle" ms))]
        (rf/dispatch-sync [:dyn/timer [:go]])
        (is (= [5000] @arms) "precondition — one arming")

        (rf/dispatch-sync [:dyn/set-ms 5000])
        (ratom/flush!)
        (is (= [5000] @arms)
            "an equal re-write moved nothing, so nothing re-armed")

        (rf/dispatch-sync [:dyn/set-other :anything])
        (ratom/flush!)
        (is (= [5000] @arms)
            "a write to a key this delay sub does not read moved nothing")

        (testing "positive control — the channel really is armed, so the two
                  silences above are silences and not a dead watch"
          (rf/dispatch-sync [:dyn/set-ms 7000])
          (ratom/flush!)
          (is (= [5000 7000] @arms)))))))
