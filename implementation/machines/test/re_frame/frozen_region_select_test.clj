(ns re-frame.frozen-region-select-test
  "Per Spec 005 §Transition broadcast (select-then-apply) + §Cross-region
  coordination (XState v5 / SCXML parity, verified vs xstate@5.32.0).

  XState v5 selects the enabled transition set for a parallel macrostep
  against the PRE-EVENT configuration / context, THEN runs the selected
  actions. re-frame2 aligns: every region's enabled transition is SELECTED
  against ONE frozen pre-broadcast snapshot of `:all-state` / `:tags`; the
  selected transitions are then APPLIED in declaration order (with `:data`
  accumulating — the one value that flows). The cross-region `:all-state` /
  `:tags` a guard OR action reads are frozen (statechart atomicity).

  A region guard / action reads a sibling's state via `:all-state`
  (precise) / `:tags` (coarse), resolved against the frozen pre-event
  snapshot — not an evolving same-event view.

  Acceptance fixtures:
    (1) an ACTION's `:all-state` / `:tags` reflect the frozen pre-broadcast
        snapshot (not an evolving rebuild), while `:data` accumulation across
        co-selected regions in declaration order holds.
    (2) parallel SELECTION is DECLARATION-ORDER-INDEPENDENT — the same
        machine with regions declared a-then-b vs b-then-a yields the same
        selected set / same committed state. A guard that saw a sibling's
        same-event `:data` write or transition would break this symmetry.

  Convergence of guarded `:always` in parent-owned rounds is pinned in
  `final_region_sourcing_test.clj`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; snapshot lookup via the shared machines test-support
;; — no hardcoded `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot rf.machines.test-support/snapshot)

;; ---- (1) ACTION :all-state / :tags frozen; :data accumulation in order -----

(deftest action-all-state-frozen-data-accumulates
  (testing "a co-selected region's ACTION sees the frozen pre-broadcast
            :all-state / :tags, while :data accumulates across regions in
            declaration order"
    (let [seen (atom [])
          m {:type    :parallel
             :data    {:log []}
             :actions {:rec-a (fn [{:keys [all-state tags data]}]
                                (swap! seen conj {:region :a :all-state all-state :tags tags})
                                {:data (update data :log conj :a)})
                       :rec-b (fn [{:keys [all-state tags data]}]
                                (swap! seen conj {:region :b
                                                  :all-state all-state
                                                  :tags tags
                                                  :data-seen data})
                                {:data (update data :log conj :b)})}
             :regions
             ;; Both regions co-select on :go and run an action. a goes
             ;; :start → :end (tag flips); b's action reads :all-state / :tags.
             {:a {:initial :start
                  :states  {:start {:tags #{:a/start}
                                    :on   {:go {:target :end :action :rec-a}}}
                            :end   {:tags #{:a/end}}}}
              :b {:initial :one
                  :states  {:one {:on {:go {:target :two :action :rec-b}}}
                            :two {}}}}}]
      (rf/reg-machine :frozen/action m)
      (rf/dispatch-sync [:frozen/action [:go]])
      (let [s    (snapshot :frozen/action)
            b-ev (first (filter #(= :b (:region %)) @seen))]
        (is (= {:a :end :b :two} (:state s)) "both regions transitioned")
        ;; FROZEN: b's action saw a's PRE-event state / tag, not the post-move.
        (is (= {:a :start :b :one} (:all-state b-ev))
            "b's action :all-state is the FROZEN pre-broadcast config (a=:start)")
        (is (= #{:a/start} (:tags b-ev))
            "b's action :tags is the FROZEN pre-broadcast union (a/start, not a/end)")
        ;; :data DID accumulate in declaration order (a before b).
        (is (= [:a :b] (get-in s [:data :log]))
            ":data accumulated across regions in declaration order (a then b)")
        (is (= [:a] (get-in b-ev [:data-seen :log]))
            "b's action saw a's :data write (the one value that flows)")))))

;; ---- (2) SELECTION is DECLARATION-ORDER-INDEPENDENT ------------------------

(defn- order-machine
  "Build the same parallel machine with regions in `region-order` — a vector
  of region names. region :a moves :idle → :done on :go (no guard); region :b
  fires :idle → :fire on :go ONLY if its :all-state guard reads :a as :done."
  [region-order]
  (let [bodies {:a {:initial :idle
                    :states  {:idle {:on {:go :done}} :done {}}}
                :b {:initial :idle
                    :states  {:idle {:on {:go {:target :fire :guard :a-done?}}}
                              :fire {}}}}]
    {:type    :parallel
     :data    {}
     :guards  {:a-done? (fn [{:keys [all-state]}] (= :done (:a all-state)))}
     :regions (into {} (map (fn [rn] [rn (get bodies rn)])) region-order)}))

(defn- data-order-machine
  "Like `order-machine` but the cross-region read is via shared `:data`, not
  `:all-state`: region :a moves :idle → :done on :go and its `:bump` action
  writes `:data :x`; region :b fires :idle → :fire on :go ONLY if its `:data`
  GUARD reads `:x` positive. Under the frozen SELECT pass b's
  guard reads the pre-event `:x=0` in BOTH declaration orders, so the selected
  set is order-independent — the `:data`-guard analog of the `:all-state`
  case. (Without it, the a-then-b order would leak a's `:x=1` write into b's
  guard.)"
  [region-order]
  (let [bodies {:a {:initial :idle
                    :states  {:idle {:on {:go {:target :done :action :bump}}}
                              :done {}}}
                :b {:initial :idle
                    :states  {:idle {:on {:go {:target :fire :guard :x-pos?}}}
                              :fire {}}}}]
    {:type    :parallel
     :data    {:x 0}
     :guards  {:x-pos? (fn [{:keys [data]}] (pos? (:x data)))}
     :actions {:bump   (fn [{:keys [data]}] {:data (update data :x inc)})}
     :regions (into {} (map (fn [rn] [rn (get bodies rn)])) region-order)}))

(deftest selection-declaration-order-independent
  (testing "reordering the regions (a-then-b vs b-then-a) yields the SAME
            selected set / SAME committed state — frozen selection is
            declaration-order-independent"
    (rf/reg-machine :frozen/order-ab (order-machine [:a :b]))
    (rf/reg-machine :frozen/order-ba (order-machine [:b :a]))
    (rf/dispatch-sync [:frozen/order-ab [:go]])
    (rf/dispatch-sync [:frozen/order-ba [:go]])
    (let [ab (:state (snapshot :frozen/order-ab))
          ba (:state (snapshot :frozen/order-ba))]
      ;; Under FROZEN selection, b's guard sees frozen :a=:idle in BOTH
      ;; orderings → b stays :idle in both. An evolving same-event model
      ;; would leak a=:done to b for a-then-b and open it — the
      ;; declaration-order-dependent footgun frozen selection avoids.
      (is (= {:a :done :b :idle} ab)
          "a-then-b: b saw frozen :a=:idle → b blocked")
      (is (= {:a :done :b :idle} ba)
          "b-then-a: b saw frozen :a=:idle → b blocked")
      (is (= ab ba)
          "the selected set is IDENTICAL regardless of declaration order")))

  (testing ":data-guard selection is ALSO declaration-order-independent — a
            region reading shared :data a SIBLING writes same-event sees the
            frozen pre-event :data in BOTH orders → same committed state.
            An evolving :data view would leak a's :x=1 into b's guard for
            a-then-b and fire b → the two orders would diverge."
    (let [{snap-ab :snapshot} (rf.machines.parallel/machine-transition
                                    (data-order-machine [:a :b])
                                    {:state {:a :idle :b :idle} :data {:x 0}} [:go])
          {snap-ba :snapshot} (rf.machines.parallel/machine-transition
                                    (data-order-machine [:b :a])
                                    {:state {:b :idle :a :idle} :data {:x 0}} [:go])]
      (is (= {:a :done :b :idle} (:state snap-ab))
          "a-then-b: b's :data guard saw frozen :x=0 → b blocked; a bumped :x")
      (is (= {:a :done :b :idle} (:state snap-ba))
          "b-then-a: same frozen selection → b blocked")
      (is (= (:state snap-ab) (:state snap-ba))
          ":data-guard selection is identical across declaration orders")
      (is (= 1 (get-in snap-ab [:data :x]))
          ":x accumulated to 1 from a's :bump — the value flows in APPLY, AFTER
           every region's guard was already selected against the frozen view"))))
