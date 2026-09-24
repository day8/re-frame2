(ns re-frame.after-dynamic-delay-reresolve-ratom-cljs-test
  "A machine's `:after [sub-vec]` dynamic delay RE-RESOLVES on the
  ratom family, driven by nothing but the timer's own wiring.

  WHY THE NODE MUST BE ACTIVATED. On the ratom family a subscription IS a bare
  `reagent.ratom/Reaction`, built deliberately WITHOUT `:auto-run`, and a
  Reaction learns its sources only through `deref-capture`. A plain deref runs
  outside `*ratom-context*`, so it runs the body raw and leaves `watching`
  nil — the node sits in no source's watcher set, `_handle-change` is never
  called, `_queued-run` short-circuits on `(some? watching)`, and even
  `reagent.core/flush` moves nothing. An `add-watch` on such a node records a
  callback that cannot fire.

  So resolving a sub-vec delay by `subs/subscribe` → plain `@reaction` →
  `add-watch` would resolve the first arming correctly and then never
  re-resolve for the rest of that arming's life, with no trace and no error.
  Spec 005 §Delayed `:after` transitions and `docs/api/re-frame.machines.md`
  promise the re-resolution unconditionally. The observation port
  (`re-frame.substrate.observation/build-node-handle!`) therefore ACTIVATES,
  then watches.

  WHY ONLY THE CLOCK IS STUBBED — the untested-combination axis. Every other
  CLJS timer suite that drives a dynamic delay installs plain-atom and, KNOWING
  a plain-atom derived value does not push, `with-redefs`es a plain
  controllable atom in place of the reaction (`after_fire_reap_cljs_test.cljc`
  says so in its own header). A plain atom always notifies: the substitution
  that makes those tests deterministic is the substitution that makes them
  blind to this channel. So this file stubs the HOST CLOCK and nothing else —
  `subs/subscribe` is the real one and the reaction under the watch is the
  real cached subscription node, because the stand-in would hide exactly the
  failure this file pins.

  CLJS-only (the ratom family is CLJS); the `-cljs-test` ns suffix enrols it in
  the consolidated `:node-test` build. No DOM and no React are needed — the
  claim is about a notification channel, not a render."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.ratom :as ratom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            ;; Loading `re-frame.machines` installs the machines-artefact
            ;; late-bind hooks (`reg-machine`, `reset-timers!`); under a
            ;; single-ns run nothing else pulls it in.
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.adapter.reagent/adapter})
  rf.machines.test-support/trace-capture-fixture)

(defn- live-entry
  "The `:rf/default` frame's single live `:after` timer entry, or nil."
  []
  (first (vals (get @rf.machines.timer/after-timers :rf/default {}))))

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
;; the re-resolution
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
      (with-redefs [rf.interop/schedule-after!
                    (fn [_thunk ms] (swap! arms conj ms) (js-obj "rf-fake-handle" ms))]

        (rf/dispatch-sync [:dyn/timer [:go]])
        (is (= :running (rf.machines.test-support/machine-state :dyn/timer))
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
               subscribed to its sources. Without activation this is nil —
               watchable, watched, and unable to notify"))

        ;; ---- the movement this file is about -----------------------------
        (rf/dispatch-sync [:dyn/set-ms 9000])
        (ratom/flush!)

        (is (= [5000 9000] @arms)
            "the delay sub moved, so the timer cancelled and re-armed at the
             new duration. A watch on an unactivated node would leave this at
             [5000]: that node can never fire")
        (is (= 9000 (:resolved-ms (live-entry)))
            "…and the live registry entry carries the re-resolved duration")
        (is (some #(= :on-resolution (:reason (:tags %)))
                  (rf.machines.test-support/events-of :rf.machine.timer/cancelled))
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
      (with-redefs [rf.interop/schedule-after!
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
