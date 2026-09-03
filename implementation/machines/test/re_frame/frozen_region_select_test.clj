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
    (1) `:data` write in region A is NOT visible to region B's same-event
        GUARD (B sees the frozen pre-event `:data`); B fires only on a
        later event / raised rebroadcast.
    (2) region B's `:all-state` / `:tags` GUARD does NOT see region A's
        same-event transition during selection (frozen).
    (3) an ACTION's `:all-state` / `:tags` ALSO reflect the frozen
        pre-broadcast snapshot (not an evolving rebuild), while `:data`
        accumulation across co-selected regions in declaration order holds.
    (4) CONVERGENCE — guarded `:always` settles in parent-owned rounds after
        the complete event set applies; raised events remain FIFO behind the
        eventless fixed point.
    (5) parallel SELECTION is DECLARATION-ORDER-INDEPENDENT — the same
        machine with regions declared a-then-b vs b-then-a yields the same
        selected set / same committed state."
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

;; ---- (1) :data write in A is NOT visible to B's same-event guard -----------
;;
;; The canonical xstate@5.32.0 case (a=:done, b=:idle, x=1): region a on :go
;; does (assign :x inc) and goes :done; region b on :go has guard (pos? x);
;; x starts 0. Result a=:done, b=:idle, x=1 — b's guard saw x=0 (frozen).
;;
;; The reader region b is declared AFTER the writer a — the adversarial order.
;; The SELECT/APPLY split (rf2-lq5yo3) resolves EVERY region's event guard in
;; a SELECT pass against ONE frozen pre-event view (which now freezes `:data`
;; alongside `:all-state` / `:tags`) BEFORE any region's action applies, so b's
;; guard reads the pre-event `:x=0` regardless of declaration order. The one
;; value that flows (`:data`) accumulates only in the APPLY phase, reaching a
;; later region's ACTION — never an earlier-selected guard. (Pre-fix, this
;; fixture leaked: with b declared after a, a's `:bump` had already threaded
;; `cur-data {:x 1}` into b's guard, so b fired — the declaration-order-
;; dependent footgun this split closes.)

(deftest data-write-not-visible-to-same-event-guard
  (testing "region b's same-event guard reads the frozen pre-event :data — b
            does NOT fire on the event a writes the value, even with b declared
            AFTER the writer a (xstate@5.32.0 a=:done, b=:idle, x=1)"
    (let [m {:type    :parallel
             :data    {:x 0}
             :guards  {:x-pos? (fn [{:keys [data]}] (pos? (:x data)))}
             :actions {:bump  (fn [{:keys [data]}] {:data (update data :x inc)})}
             :regions
             ;; a (the WRITER) declared FIRST; b (the reader) declared AFTER —
             ;; the order an evolving-:data model would leak on. Frozen SELECT
             ;; blocks the leak.
             {:a {:initial :idle
                  :states  {:idle {:on {:go {:target :done :action :bump}}}
                            :done {}}}
              :b {:initial :idle
                  :states  {:idle {:on {:go {:target :done :guard :x-pos?}}}
                            :done {}}}}}]
      (rf/reg-machine :frozen/data m)
      (rf/dispatch-sync [:frozen/data [:go]])
      (let [s (snapshot :frozen/data)]
        (is (= {:a :done :b :idle} (:state s))
            "a transitioned + bumped :x; b's guard saw frozen :x=0 → b stayed :idle")
        (is (= 1 (get-in s [:data :x]))
            ":x accumulated to 1 from a's :bump action"))
      ;; A SECOND :go now sees :x=1 (prior-microstep value) → b fires.
      (rf/dispatch-sync [:frozen/data [:go]])
      (is (= :done (get-in (snapshot :frozen/data) [:state :b]))
          "on a LATER event b's guard reads the committed :x=1 → b fires"))))

;; ---- (2) B's :all-state / :tags guard does NOT see A's same-event move -----

(deftest all-state-guard-frozen-during-selection
  (testing "region b's :all-state guard does NOT see region a's same-event
            transition — it reads the frozen pre-event sibling config"
    (let [m {:type    :parallel
             :data    {}
             :guards  {:a-done? (fn [{:keys [all-state]}]
                                  (= :done (:a all-state)))}
             :regions
             {:a {:initial :idle
                  :states  {:idle {:on {:go :done}}
                            :done {}}}
              :b {:initial :idle
                  :states  {:idle {:on {:go {:target :fire :guard :a-done?}}}
                            :fire {}}}}}]
      (rf/reg-machine :frozen/all-state m)
      (rf/dispatch-sync [:frozen/all-state [:go]])
      (is (= {:a :done :b :idle} (:state (snapshot :frozen/all-state)))
          "a moved to :done; b's :all-state guard saw frozen :a=:idle → b blocked")
      ;; Later event: b's guard now reads the committed :a=:done → b fires.
      (rf/dispatch-sync [:frozen/all-state [:go]])
      (is (= :fire (get-in (snapshot :frozen/all-state) [:state :b]))
          "on a later event b's guard reads committed :a=:done → b fires")))

  (testing ":tags guard is likewise frozen during selection"
    (let [m {:type    :parallel
             :data    {}
             :guards  {:a-tagged? (fn [{:keys [tags]}]
                                    (contains? tags :a/done))}
             :regions
             {:a {:initial :idle
                  :states  {:idle {:on {:go :done}}
                            :done {:tags #{:a/done}}}}
              :b {:initial :idle
                  :states  {:idle {:on {:go {:target :fire :guard :a-tagged?}}}
                            :fire {}}}}}]
      (rf/reg-machine :frozen/tags m)
      (rf/dispatch-sync [:frozen/tags [:go]])
      (is (= {:a :done :b :idle} (:state (snapshot :frozen/tags)))
          "b's :tags guard saw the frozen union (no :a/done yet) → b blocked"))))

;; ---- (3) ACTION :all-state / :tags frozen; :data accumulation in order -----

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

;; ---- (4) CONVERGENCE — parent eventless rounds + raised FIFO ---------------

(deftest convergence-via-parent-always-round
  (testing "a guarded :always reading a sibling's state converges in the SAME
            macrostep, after the complete selected event set has applied"
    (let [m {:type    :parallel
             :data    {}
             :guards  {:a-done? (fn [{:keys [all-state]}]
                                  (= :done (:a all-state)))}
             :regions
             {:a {:initial :idle
                  :states  {:idle {:on {:go :done}}
                            :done {}}}
              ;; b uses a guarded :always reading sibling :a's state.
              :b {:initial :waiting
                  :states  {:waiting {:always {:target :ready :guard :a-done?}}
                            :ready   {}}}}}]
      (rf/reg-machine :frozen/converge m)
      (rf/dispatch-sync [:frozen/converge [:go]])
      (is (= {:a :done :b :ready} (:state (snapshot :frozen/converge)))
          "event actions completed, then the frozen parent :always round saw
           :a=:done and moved b before the one macrostep committed")))

  (testing "same-event coordination via :raise settles same-macrostep, next
            microstep — the IN-MACROSTEP convergence path"
    (let [m {:type    :parallel
             :data    {}
             :actions {:announce (fn [{:keys [data]}]
                                   {:data data :fx [[:raise [:a-ready]]]})}
             :regions
             {:a {:initial :idle
                  :states  {:idle {:on {:go {:target :done :action :announce}}}
                            :done {}}}
              :b {:initial :waiting
                  :states  {:waiting {:on {:a-ready :ready}}
                            :ready   {}}}}}]
      (rf/reg-machine :frozen/raise m)
      (rf/dispatch-sync [:frozen/raise [:go]])
      (is (= {:a :done :b :ready} (:state (snapshot :frozen/raise)))
          "a's :go raised :a-ready; the FIFO re-broadcast delivered it to b,
           which fired on the next microstep — bounded, convergent"))))

;; ---- (5) SELECTION is DECLARATION-ORDER-INDEPENDENT ------------------------

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
  GUARD reads `:x` positive. Under the frozen SELECT pass (rf2-lq5yo3) b's
  guard reads the pre-event `:x=0` in BOTH declaration orders, so the selected
  set is order-independent — the `:data`-guard analog of the `:all-state`
  case. (Pre-fix, the a-then-b order leaked a's `:x=1` write into b's guard.)"
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

  (testing "pure machine-transition is also declaration-order-independent"
    (let [initial-ab {:state {:a :idle :b :idle} :data {}}
          initial-ba {:state {:b :idle :a :idle} :data {}}
          {snap-ab :snapshot} (rf.machines.parallel/machine-transition
                                    (order-machine [:a :b]) initial-ab [:go])
          {snap-ba :snapshot} (rf.machines.parallel/machine-transition
                                    (order-machine [:b :a]) initial-ba [:go])]
      (is (= {:a :done :b :idle} (:state snap-ab)))
      (is (= {:a :done :b :idle} (:state snap-ba)))
      (is (= (:state snap-ab) (:state snap-ba))
          "pure selection is identical across declaration orders")))

  (testing ":data-guard selection is ALSO declaration-order-independent — a
            region reading shared :data a SIBLING writes same-event sees the
            frozen pre-event :data in BOTH orders → same committed state
            (rf2-lq5yo3). Pre-fix, a-then-b leaked a's :x=1 into b's guard and
            b fired → the two orders diverged."
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
