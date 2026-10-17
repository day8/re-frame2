(ns day8.re-frame2-xray.panels.machine-after-rings-runtime-test
  "The countdown-ring fold over the timer rows the machines runtime really
  emits. Every `:rf.machine.timer/*` row reports the armed milliseconds under
  `:delay`, so a timer whose delay is not a literal — an ISO-8601 string, a
  `:delays` name, a fn — still draws a ring, and its `/cancelled` row closes
  the record its `/scheduled` row opened. Every row also names its timer by
  the `:after` key under `:delay-key`, so two keys resolving to the same
  milliseconds stay two rings, and names the node that declared it under
  `:decl-path` and `:region`, so two parallel regions entering same-named
  leaves stay two rings on two nodes."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [day8.re-frame2-machines-viz.chart.layout :as chart-layout]
            [day8.re-frame2-xray.panels.machine-after-rings-helpers :as rings]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- waiting-machine [after-key extra]
  (merge {:initial :idle
          :data    {:ms 250}
          :states  {:idle    {:on {:go :waiting}}
                    :waiting {:after {after-key :done}
                              :on    {:leave :idle}}
                    :done    {}}}
         extra))

(defn- trace-buffer
  "Enter `:waiting` (the host clock stubbed) and leave it again, returning
  the trace rows captured up to the arm and up to the leave."
  [machine-id]
  (let [buf (atom [])]
    (rf/register-listener! :trace ::rings (fn [ev] (swap! buf conj ev)))
    (try
      (with-redefs [rf.interop/schedule-after! (fn [_thunk _ms] ::handle)]
        (rf/dispatch-sync [machine-id [:go]]))
      (let [armed @buf]
        (rf/dispatch-sync [machine-id [:leave]])
        [armed @buf])
      (finally (rf/unregister-listener! :trace ::rings)))))

(deftest a-non-literal-delay-draws-a-ring-that-its-cancel-closes
  (doseq [[id label after-key extra]
          [[:rings-rt/iso   "an ISO-8601 duration" "PT0.25S" {}]
           [:rings-rt/named "a :delays name"       :warn     {:delays {:warn 250}}]
           [:rings-rt/fn    "a delay fn"           (fn [{:keys [snapshot]}] (-> snapshot :data :ms)) {}]]]
    (rf/reg-machine id (waiting-machine after-key extra))
    (let [[armed all] (trace-buffer id)
          ring        (first (rings/timers-for-machine armed id))]
      (is (= [250 1.0] [(:duration-ms ring) (rings/ring-fraction ring (:armed-at ring))])
          (str label ": the armed timer draws a full countdown ring"))
      (is (= [:cancelled] (mapv :status (rings/project-timers all id)))
          (str label ": leaving the state closes that ring")))))

(deftest equal-duration-timers-keep-their-own-rings
  ;; `:warning` and `:expiry` both resolve to 250ms. They are two runtime
  ;; timers, so they are two rings, and `:warning`'s guard-suppressed firing
  ;; closes its own ring and leaves `:expiry` counting down.
  (rf/reg-machine :rings-rt/twins
                  {:initial :idle
                   :delays  {:warning 250 :expiry 250}
                   :guards  {:never (constantly false)}
                   :states  {:idle    {:on {:go :waiting}}
                             :waiting {:after {:warning {:guard :never :target :done}
                                               :expiry  :done}}
                             :done    {}}})
  (let [buf  (atom [])
        ring (fn [] (into {} (map (juxt :delay-key (juxt :status :duration-ms)))
                          (rings/project-timers @buf :rings-rt/twins)))]
    (rf/register-listener! :trace ::twins (fn [ev] (swap! buf conj ev)))
    (try
      (with-redefs [rf.interop/schedule-after! (fn [_thunk _ms] ::handle)]
        (rf/dispatch-sync [:rings-rt/twins [:go]]))
      (is (= {:warning [:armed 250] :expiry [:armed 250]} (ring))
          "each timer arms its own ring")
      (let [epoch (some #(when (= :rf.machine.timer/scheduled (:operation %))
                           (get-in % [:tags :epoch]))
                        @buf)]
        (rf/dispatch-sync [:rings-rt/twins
                           [:rf.machine.timer/after-elapsed :warning epoch [:waiting]]]))
      (is (= {:warning [:guard-suppressed 250] :expiry [:armed 250]} (ring))
          "the suppressed :warning closes its own ring, not :expiry's")
      (finally (rf/unregister-listener! :trace ::twins)))))

(deftest same-named-region-leaves-keep-their-own-rings
  ;; Both regions enter an `:idle` leaf carrying the same `:after` key. They
  ;; are two runtime timers, so they are two rings on two nodes, and leaving
  ;; one region cancels only its own ring.
  (let [region (fn [extra-on]
                 {:initial :off
                  :states  {:off  {:on {:go :idle}}
                            :idle {:after {250 :done} :on extra-on}
                            :done {}}})]
    (rf/reg-machine :rings-rt/regions
                    {:type    :parallel
                     :regions {:left  (region {:leave :off})
                               :right (region {})}}))
  (let [buf     (atom [])
        rings   (fn [] (into {} (map (juxt :region :status))
                             (rings/project-timers @buf :rings-rt/regions)))
        node-id (comp first chart-layout/highlight-ids)]
    (rf/register-listener! :trace ::regions (fn [ev] (swap! buf conj ev)))
    (try
      (with-redefs [rf.interop/schedule-after! (fn [_thunk _ms] ::handle)]
        (rf/dispatch-sync [:rings-rt/regions [:go]]))
      (is (= {:left :armed :right :armed} (rings))
          "each region's timer arms its own ring")
      (is (= #{"region__left__idle" "region__right__idle"}
             (set (map :node-id (rings/timers->ring-specs
                                  (rings/timers-for-machine @buf :rings-rt/regions)
                                  node-id nil))))
          "each ring sits on its own region's node")
      (rf/dispatch-sync [:rings-rt/regions [:leave]])
      (is (= {:left :cancelled :right :armed} (rings))
          "leaving :left cancels its ring and leaves :right's armed")
      (finally (rf/unregister-listener! :trace ::regions)))))
