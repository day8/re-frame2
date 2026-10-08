(ns re-frame.after-timer-completed-at-test
  "A fired `:after` timer's dispatch is its own causal envelope (EP-0010
  §Dispatch Envelope Stamping, Spec 002 §The World-Input Rule): the timer
  callback supplies no `:rf.cofx`, so the router stamps `:rf/time-ms` at FIRE
  time, and the transition's guard and action read that stamp rather than the
  scheduling dispatch's token. The timer traces carry the firing dispatch's
  time as the causal completion time (Managed-Effects §Causal completion
  metadata), only ever as `:rf.reply/completed-at` — never a bare
  `:completed-at` (Conventions §The naming rules)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.router :as rf.router]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private PARENT-TIME-MS 1000000000)
(def ^:private FIRE-TIME-MS   2000000000)

(defn- completion-tags
  "The completion-time slots a trace row actually carries."
  [ev]
  (select-keys (:tags ev) [:rf.reply/completed-at :completed-at]))

(deftest after-timer-fire-stamps-fresh-causal-token
  ;; The host-clock thunk is captured and the epoch clock advanced between arm
  ;; and fire, so an inherited scheduling token and a fresh stamp differ.
  (let [clock          (atom PARENT-TIME-MS)
        captured-thunk (atom nil)
        guard-saw      (atom nil)
        action-saw     (atom nil)
        m {:initial :idle
           :data    {}
           :guards  {:capture (fn [{cofx :rf.cofx}] (reset! guard-saw (:rf/time-ms cofx)) true)}
           :actions {:capture (fn [{cofx :rf.cofx}] (reset! action-saw (:rf/time-ms cofx)) nil)}
           :states  {:idle    {:on {:fetch :loading}}
                     :loading {:after {5000 {:target :timeout
                                             :guard  :capture
                                             :action :capture}}}
                     :timeout {}}}
        orig-dispatch! (rf.late-bind/get-fn :router/dispatch!)]
    (rf/reg-machine :after-fresh/m m)
    (rf.machines.test-support/with-trace-capture captured
      (with-redefs [rf.interop/schedule-after! (fn [f _ms] (reset! captured-thunk f) ::stub-handle)
                    rf.interop/epoch-now-ms    (fn [] @clock)]
        (rf/dispatch-sync [:after-fresh/m [:fetch]]
                          {:rf.cofx {:rf/time-ms PARENT-TIME-MS}})
        (reset! clock FIRE-TIME-MS)
        ;; The thunk runs outside any handler, so sync routing is legal and
        ;; keeps the cascade inline.
        (try
          (rf.late-bind/set-fn! :router/dispatch! rf.router/dispatch-sync!)
          (@captured-thunk)
          (finally
            (rf.late-bind/set-fn! :router/dispatch! orig-dispatch!))))
      (is (= [FIRE-TIME-MS FIRE-TIME-MS] [@guard-saw @action-saw])
          "the timer's guard and action read the fire-time stamp, not the scheduling token")
      (is (= {:rf.reply/completed-at FIRE-TIME-MS}
             (completion-tags (first (filter #(and (= :rf.machine.timer/fired (:operation %))
                                                    (true? (:fired? (:tags %))))
                                              @captured))))
          "the fired trace carries the fire-time :rf.reply/completed-at, with no bare :completed-at"))))

(deftest after-stale-trace-carries-causal-completed-at
  (rf/reg-machine :hawtjr/stale
                  {:initial :idle
                   :data    {}
                   :states  {:idle    {:on {:fetch :loading}}
                             :loading {:after {5000 :warn}
                                       :on    {:loaded :ready}}
                             :warn    {}
                             :ready   {}}})
  (rf.machines.test-support/with-trace-capture captured
    (rf/dispatch-sync [:hawtjr/stale [:fetch]])
    (let [epoch (get-in (rf.machines.test-support/snapshot :hawtjr/stale)
                        [:data :rf/after-epoch [:loading]])]
      ;; Leaving :loading makes its timer stale; fire it by hand with a scripted token.
      (rf/dispatch-sync [:hawtjr/stale [:loaded]])
      (rf/dispatch-sync [:hawtjr/stale [:rf.machine.timer/after-elapsed 5000 epoch [:loading]]]
                        {:rf.cofx {:rf/time-ms FIRE-TIME-MS}})
      (is (= {:rf.reply/completed-at FIRE-TIME-MS}
             (completion-tags (first (filter #(= :rf.machine.timer/stale-after (:operation %))
                                              @captured))))
          "the stale-after trace carries the causal :rf.reply/completed-at, with no bare :completed-at"))))
