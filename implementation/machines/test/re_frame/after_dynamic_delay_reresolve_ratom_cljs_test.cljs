(ns re-frame.after-dynamic-delay-reresolve-ratom-cljs-test
  "A machine's `:after [sub-vec]` dynamic delay RE-RESOLVES on the ratom
  family, driven by nothing but the timer's own wiring.

  On the ratom family a subscription is a bare `reagent.ratom/Reaction`, built
  without `:auto-run`, and a Reaction learns its sources only through
  `deref-capture`. A plain deref leaves `watching` nil, so the node sits in no
  source's watcher set and an `add-watch` on it records a callback that can
  never fire: the first arming would resolve correctly and then never
  re-resolve, with no trace and no error. Spec 005 §Delayed `:after`
  transitions and `docs/api/re-frame.machines.md` promise the re-resolution
  unconditionally, so the observation port
  (`re-frame.substrate.observation/build-node-handle!`) activates the node,
  then watches.

  Only the HOST CLOCK is stubbed. `subs/subscribe` is the real one and the
  watched reaction is the real cached subscription node: the plain-atom
  stand-in other timer suites use always notifies, so it would hide exactly
  this failure.

  CLJS-only (the ratom family is CLJS); the `-cljs-test` ns suffix enrols it in
  the consolidated `:node-test` build."
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

;; White-box, and only ever corroborating: `watching` is the Reagent field that
;; says "this reaction is subscribed to its sources".
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

(deftest dynamic-after-delay-re-resolves-when-the-delay-sub-moves
  (testing "a machine armed on `:after {[:dyn/ms] …}` cancels and re-arms at
            the NEW duration every time the delay subscription moves — with a
            REAL subscription node under the watch, on the Reagent adapter"
    (register-dynamic-delay-machine!)
    (rf/dispatch-sync [:dyn/set-ms 5000])
    (let [arms (atom [])]
      ;; `managed-timer/cancel!` swallows throws, so the opaque sentinel
      ;; handle is safe on the cancellation path.
      (with-redefs [rf.interop/schedule-after!
                    (fn [_thunk ms] (swap! arms conj ms) (js-obj "rf-fake-handle" ms))]

        (rf/dispatch-sync [:dyn/timer [:go]])
        (is (capturing? (:reaction (live-entry)))
            "the timer ACTIVATED the delay node before watching it: it is
             subscribed to its sources. Without activation this is nil —
             watchable, watched, and unable to notify")

        (rf/dispatch-sync [:dyn/set-ms 9000])
        (ratom/flush!)

        (is (= [5000 9000] @arms)
            "the delay sub moved, so the timer cancelled and re-armed at the
             new duration. A watch on an unactivated node would leave this at
             [5000]")
        (is (= 9000 (:resolved-ms (live-entry)))
            "…and the live registry entry carries the re-resolved duration")
        (is (some #(= :on-resolution (:reason (:tags %)))
                  (rf.machines.test-support/events-of :rf.machine.timer/cancelled))
            "the cancellation was traced with `:reason :on-resolution`")

        ;; The re-arm cancels first, dropping the held subscription and so
        ;; disposing the reaction; the second arming subscribes anew, so
        ;; activation must happen at EVERY arming, not once at birth.
        (is (capturing? (:reaction (live-entry)))
            "the RE-ARMED node is on the push path too")

        (rf/dispatch-sync [:dyn/set-ms 12000])
        (ratom/flush!)

        (is (= [5000 9000 12000] @arms)
            "a second move re-resolves as well — one activated arming does not
             buy the next one's channel")
        (is (= 12000 (:resolved-ms (live-entry))))))))

;; Activation puts the node on the push path; it must not turn every app-db
;; write into a cancel-and-reschedule, which would keep pushing the timeout back.
(deftest a-write-that-does-not-move-the-delay-does-not-re-arm
  (register-dynamic-delay-machine!)
  (rf/dispatch-sync [:dyn/set-ms 5000])
  (let [arms (atom [])]
    (with-redefs [rf.interop/schedule-after!
                  (fn [_thunk ms] (swap! arms conj ms) (js-obj "rf-fake-handle" ms))]
      (rf/dispatch-sync [:dyn/timer [:go]])

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
        (is (= [5000 7000] @arms))))))
