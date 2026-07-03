(ns re-frame.after-timer-completed-at-test
  "The machine `:after` timer reply path carries the CAUSAL `:completed-at`
  (EP-0011 §Timer Reply / Managed-Effects §Causal completion metadata).

  The spawned-machine `:rf.machine/done` reply threads the finishing
  dispatch's `:rf.cofx :rf/time-ms` into the reply + trace; the `:after`
  timer reply path does the same. The synthetic `:after`-elapsed dispatch is
  itself a causal token carrying a fresh router-stamped `:rf/time-ms` (the
  same fire-time token the firing guard / action read), and a fired `:after`
  timer's transition can mutate machine snapshot `:data` — so per
  Managed-Effects §Causal completion metadata the completion time rides the
  fired-timer reply, and §Tracing carries it on the stale-timer suppression
  trace too.

  These tests drive the REAL `interop/schedule-after!` fire boundary so the
  timer-fire dispatch is router-stamped with a known fire-time clock value,
  then assert `:rf.reply/completed-at` rides the `:rf.machine.timer/fired`
  and `:rf.machine.timer/stale-after` traces. rf2-o6c2jr — the reply MAP still
  carries the canonical `:completed-at`; the trace-tag rows carry ONLY the
  reply-envelope `:rf.reply/completed-at` (the bare duplicate was dropped)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines]
            [re-frame.machines.reply :as m-reply]
            [re-frame.machines.test-support :as mtest]
            [re-frame.router :as router]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private snapshot mtest/snapshot)

(def ^:private PARENT-TIME-MS 1000000000)
(def ^:private FIRE-TIME-MS   2000000000)

;; ---- pure reply-helper level ----------------------------------------------

(deftest after-fired-reply-carries-completed-at
  (testing "rf2-hawtjr — after-fired-reply threads :completed-at when supplied"
    (let [r (m-reply/after-fired-reply
              {:actor-id :a/m :state :loading :delay 5000
               :decl-path [:loading] :epoch 1 :frame :rf/default
               :completed-at FIRE-TIME-MS})]
      (is (= FIRE-TIME-MS (:completed-at r))
          "fired reply carries the causal completion timestamp")
      (is (= :ok (:status r))))
    (testing "omitted (not nil-filled) when absent"
      (let [r (m-reply/after-fired-reply
                {:actor-id :a/m :state :loading :delay 5000
                 :decl-path [:loading] :epoch 1 :frame :rf/default})]
        (is (not (contains? r :completed-at))
            "no causal token ⇒ :completed-at omitted, not nil-filled")))))

(deftest after-stale-reply-carries-completed-at
  (testing "rf2-hawtjr — after-stale-reply threads :completed-at when supplied"
    (let [r (m-reply/after-stale-reply
              {:actor-id :a/m :state :loading :delay 5000
               :decl-path [:loading] :scheduled-epoch 1 :current-epoch 2
               :frame :rf/default :completed-at FIRE-TIME-MS})]
      (is (= FIRE-TIME-MS (:completed-at r)))
      (is (= :stale (:status r))))
    (testing "omitted (not nil-filled) when absent"
      (let [r (m-reply/after-stale-reply
                {:actor-id :a/m :state :loading :delay 5000
                 :decl-path [:loading] :scheduled-epoch 1 :current-epoch 2
                 :frame :rf/default})]
        (is (not (contains? r :completed-at)))))))

;; ---- integration: real fire boundary, trace-stamped --------------------

(deftest after-fired-trace-carries-causal-completed-at
  (testing "rf2-hawtjr — the :rf.machine.timer/fired trace carries the
            firing dispatch's causal :completed-at (router-stamped fire-time
            :rf/time-ms), driven through the REAL schedule-after! boundary"
    (let [clock          (atom PARENT-TIME-MS)
          captured-thunk (atom nil)
          m {:initial :idle
             :data    {}
             :states  {:idle    {:on {:fetch :loading}}
                       :loading {:after {5000 :timeout}}
                       :timeout {}}}
          orig-dispatch! (late-bind/get-fn :router/dispatch!)]
      (rf/reg-machine :hawtjr/fired m)
      (mtest/with-trace-capture captured
        (with-redefs [interop/schedule-after!
                      (fn [f _ms] (reset! captured-thunk f) ::stub-handle)
                      interop/epoch-now-ms (fn [] @clock)]
          (rf/dispatch-sync [:hawtjr/fired [:fetch]]
                            {:rf.cofx {:rf/time-ms PARENT-TIME-MS}})
          (is (= :loading (:state (snapshot :hawtjr/fired))))
          (is (some? @captured-thunk) "armed a host-clock callback")
          ;; advance the wall clock to a DISTINCT fire-time value
          (reset! clock FIRE-TIME-MS)
          (try
            (late-bind/set-fn! :router/dispatch! router/dispatch-sync!)
            (@captured-thunk)
            (finally
              (late-bind/set-fn! :router/dispatch! orig-dispatch!))))
        (is (= :timeout (:state (snapshot :hawtjr/fired)))
            "the timer fired and drove the transition")
        (let [fired (->> @captured
                         (filter #(and (= :rf.machine.timer/fired (:operation %))
                                       (true? (:fired? (:tags %)))))
                         first)]
          (is (some? fired) "a :rf.machine.timer/fired trace was emitted")
          ;; rf2-o6c2jr — the causal completion time rides ONLY as the
          ;; reply-envelope :rf.reply/completed-at (the bare :completed-at
          ;; trace-tag duplicate was dropped).
          (is (= FIRE-TIME-MS (:rf.reply/completed-at (:tags fired)))
              "the fired trace carries the FRESH fire-time causal :rf.reply/completed-at")
          (is (not (contains? (:tags fired) :completed-at))
              "no bare :completed-at duplicate on the reply-envelope fired trace")
          (is (not= PARENT-TIME-MS (:rf.reply/completed-at (:tags fired)))
              "NOT the parent scheduling-time token"))))))

(deftest after-stale-trace-carries-causal-completed-at
  (testing "rf2-hawtjr — the :rf.machine.timer/stale-after trace carries the
            firing dispatch's causal :completed-at"
    (let [m {:initial :idle
             :data    {}
             :states  {:idle    {:on {:fetch :loading}}
                       :loading {:after {5000 :warn}
                                 :on    {:loaded :ready}}
                       :warn    {}
                       :ready   {}}}]
      (rf/reg-machine :hawtjr/stale m)
      (mtest/with-trace-capture captured
        (rf/dispatch-sync [:hawtjr/stale [:fetch]])
        (let [epoch (get-in (snapshot :hawtjr/stale)
                            [:data :rf/after-epoch [:loading]])]
          ;; Move off :loading so the [:loading] node is no longer active —
          ;; the next firing of its scheduled-epoch timer is stale.
          (rf/dispatch-sync [:hawtjr/stale [:loaded]])
          (is (= :ready (:state (snapshot :hawtjr/stale))))
          ;; Fire the now-stale timer with a scripted causal token.
          (rf/dispatch-sync [:hawtjr/stale [:rf.machine.timer/after-elapsed
                                            5000 epoch [:loading]]]
                            {:rf.cofx {:rf/time-ms FIRE-TIME-MS}})
          (let [stale (->> @captured
                           (filter #(= :rf.machine.timer/stale-after (:operation %)))
                           first)]
            (is (some? stale) "a :rf.machine.timer/stale-after trace was emitted")
            ;; rf2-o6c2jr — carries the causal time ONLY as the reply-envelope
            ;; :rf.reply/completed-at (bare :completed-at duplicate dropped).
            (is (= FIRE-TIME-MS (:rf.reply/completed-at (:tags stale)))
                "the stale-after trace carries the causal :rf.reply/completed-at")
            (is (not (contains? (:tags stale) :completed-at))
                "no bare :completed-at duplicate on the reply-envelope stale trace")))))))
