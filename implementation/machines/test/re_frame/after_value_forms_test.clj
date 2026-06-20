(ns re-frame.after-value-forms-test
  "Exhaustive coverage of `:after` value-form resolution.

  The `:after` table value at a delay-key admits the SAME value-form
  grammar as an `:on` clause (Spec 005 §Delayed `:after` transitions
  §Transition spec — \"the same shape as an `:on` slot\" and §Multiple-
  candidate transitions):

    (a) bare keyword target            -> {:target <kw>}
    (b) single transition map, no guard
    (c) single transition map, guard passes
    (d) single transition map, guard fails   -> guard-suppressed (no transition)
    (e) guarded candidate-vector, first guard passes -> first target
    (f) guarded candidate-vector, first guard fails, unguarded fallback
        -> fallback target + its :action runs
    (g) guarded candidate-vector, all guards fail, no fallback
        -> guard-suppressed (no transition)
    (h) hierarchical guarded `:after` (leaf vs parent epoch / staleness)
    (i) parallel-region-scoped guarded `:after` (region decl-path routing)

  A guarded candidate-vector `:after` value resolves through the shared
  candidate-walk: each candidate's guard is evaluated in order, the first
  passing candidate's `:target` / `:action` drives the transition, and an
  unguarded candidate acts as the fallback. Cases (e), (f), (g) — the
  candidate-vector forms — pin this resolution against regression.

  These tests drive the PURE `machines/machine-transition` surface with the
  synthetic `[:rf.machine.timer/after-elapsed delay-key epoch decl-path]`
  event so the value-form resolution is observable without any wall-clock
  scheduling or runtime fixture. The snapshot's `:data` carries the
  per-decl-path `:rf/after-epoch` map so the timer is `live` (carried-epoch
  == current per-path epoch)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]))

;; ---- helpers --------------------------------------------------------------

(defn- after-event
  "The synthetic timer-elapsed event for `delay-key` carrying `epoch` +
  `decl-path` (the absolute scheduling-node path)."
  [delay-key epoch decl-path]
  [:rf.machine.timer/after-elapsed delay-key epoch decl-path])

(defn- fire
  "Drive a pure `machine-transition` for `spec` from `snapshot` on `event`;
  return `[next-state next-snapshot fx-vec]`. `next-state` is read off the
  returned snapshot's `:state` for the common state-change assertion."
  [spec snapshot event]
  (let [{snap ::result/snap fx ::result/fx} (machines/machine-transition spec snapshot event)]
    [(:state snap) snap fx]))

(defn- snap-at
  "Build a snapshot resting at `state` (a keyword or vector path) whose
  `:data` seeds `extra` plus the per-decl-path `:rf/after-epoch` entries in
  `epoch-map` (`{<decl-path-vector> <int>}`)."
  ([state epoch-map] (snap-at state epoch-map {}))
  ([state epoch-map extra]
   {:state state
    :data  (merge extra {:rf/after-epoch epoch-map})}))

;; ---- (a) bare keyword target ----------------------------------------------

(deftest after-bare-keyword-target
  (testing "a bare keyword :after value resolves to a sibling target"
    (let [spec {:initial :idle
                :data    {}
                :states  {:idle    {:on {:go :loading}}
                          :loading {:after {5000 :timeout}}
                          :timeout {}}}
          snap (snap-at :loading {[:loading] 1})
          [state] (fire spec snap (after-event 5000 1 [:loading]))]
      (is (= :timeout state)
          "keyword :after target transitions :loading → :timeout"))))

;; ---- (b) single map, no guard ---------------------------------------------

(deftest after-single-map-no-guard
  (testing "a single transition map :after value (no guard) transitions"
    (let [spec {:initial :idle
                :data    {}
                :states  {:idle    {:on {:go :loading}}
                          :loading {:after {5000 {:target :timeout}}}
                          :timeout {}}}
          snap (snap-at :loading {[:loading] 1})
          [state] (fire spec snap (after-event 5000 1 [:loading]))]
      (is (= :timeout state)
          "single-map :after target transitions :loading → :timeout"))))

;; ---- (c) single map, guard passes -----------------------------------------

(deftest after-single-map-guard-passes
  (testing "a single guarded :after map whose guard passes transitions"
    (let [spec {:initial :idle
                :data    {:slow? true}
                :guards  {:slow? (fn [{:keys [data]}] (:slow? data))}
                :states  {:idle    {:on {:go :loading}}
                          :loading {:after {5000 {:guard :slow? :target :warn}}}
                          :warn    {}}}
          snap (snap-at :loading {[:loading] 1} {:slow? true})
          [state] (fire spec snap (after-event 5000 1 [:loading]))]
      (is (= :warn state)
          "guard-pass single-map :after transitions :loading → :warn"))))

;; ---- (d) single map, guard fails -> guard-suppressed ----------------------

(deftest after-single-map-guard-fails-suppressed
  (testing "a single guarded :after map whose guard fails is suppressed —
            no transition, no epoch advance"
    (let [spec {:initial :idle
                :data    {:slow? false}
                :guards  {:slow? (fn [{:keys [data]}] (:slow? data))}
                :states  {:idle    {:on {:go :loading}}
                          :loading {:after {5000 {:guard :slow? :target :warn}}}
                          :warn    {}}}
          snap (snap-at :loading {[:loading] 1} {:slow? false})
          [state next-snap fx] (fire spec snap (after-event 5000 1 [:loading]))]
      (is (= :loading state)
          "guard-fail single-map :after must NOT transition (guard-suppressed)")
      (is (= 1 (get-in next-snap [:data :rf/after-epoch [:loading]]))
          "guard-suppressed firing does not advance the node's epoch")
      (is (empty? fx)
          "guard-suppressed firing emits no effects"))))

(deftest after-single-map-guard-fail-sibling-continues
  (testing "guard-suppressed :after leaves a sibling :after timer live"
    (let [spec {:initial :idle
                :data    {:slow? false}
                :guards  {:slow? (fn [{:keys [data]}] (:slow? data))}
                :states  {:idle    {:on {:go :loading}}
                          :loading {:after {5000  {:guard :slow? :target :warn}
                                            30000 :timeout}}
                          :warn    {}
                          :timeout {}}}
          snap (snap-at :loading {[:loading] 1} {:slow? false})
          ;; 5000 fires; guard false → suppressed; still at :loading, epoch unmoved.
          [state5 snap5] (fire spec snap (after-event 5000 1 [:loading]))
          ;; The 30000 sibling fires from the SAME (unchanged) epoch and transitions.
          [state30]      (fire spec snap5 (after-event 30000 1 [:loading]))]
      (is (= :loading state5) "5s guard-suppressed timer did not transition")
      (is (= :timeout state30)
          "sibling :after timer (same epoch) is still live and transitions"))))

;; ---- (e) guarded vector, first guard passes -> first target ---------------
;;
;; The candidate-vector form: the first passing guard's target fires.

(deftest after-guarded-vector-first-guard-passes
  (testing "guarded candidate-vector :after: first guard passes → first target"
    (let [spec {:initial :idle
                :data    {:handshake-ok? true}
                :guards  {:handshake-ok? (fn [{:keys [data]}] (:handshake-ok? data))}
                :states  {:idle          {:on {:go :authenticating}}
                          :authenticating
                          {:after {6000 [{:guard :handshake-ok? :target :connected}
                                         {:target :failed :action :record-error}]}}
                          :connected     {}
                          :failed        {}}
                :actions {:record-error (fn [{:keys [data]}]
                                          {:data (assoc data :error :handshake)})}}
          snap (snap-at :authenticating {[:authenticating] 1} {:handshake-ok? true})
          [state] (fire spec snap (after-event 6000 1 [:authenticating]))]
      (is (= :connected state)
          "first candidate's guard passes → transitions to its target :connected"))))

;; ---- (f) guarded vector, first fails -> unguarded fallback + action --------

(deftest after-guarded-vector-fallback-target-and-action
  (testing "guarded candidate-vector :after: first guard fails → unguarded
            fallback target fires AND its :action runs"
    (let [spec {:initial :idle
                :data    {:handshake-ok? false}
                :guards  {:handshake-ok? (fn [{:keys [data]}] (:handshake-ok? data))}
                :states  {:idle          {:on {:go :authenticating}}
                          :authenticating
                          {:after {6000 [{:guard :handshake-ok? :target :connected}
                                         {:target :failed :action :record-error}]}}
                          :connected     {}
                          :failed        {}}
                :actions {:record-error (fn [{:keys [data]}]
                                          {:data (assoc data :error :handshake)})}}
          snap (snap-at :authenticating {[:authenticating] 1} {:handshake-ok? false})
          [state next-snap] (fire spec snap (after-event 6000 1 [:authenticating]))]
      (is (= :failed state)
          "first guard fails → unguarded fallback target :failed fires")
      (is (= :handshake (get-in next-snap [:data :error]))
          "the fallback candidate's :action ran (recorded the error in :data)"))))

;; ---- (g) guarded vector, all fail, no fallback -> guard-suppressed ---------

(deftest after-guarded-vector-all-fail-suppressed
  (testing "guarded candidate-vector :after: every guard fails and there is
            no unguarded fallback → guard-suppressed (no transition)"
    (let [spec {:initial :idle
                :data    {:a? false :b? false}
                :guards  {:a? (fn [{:keys [data]}] (:a? data))
                          :b? (fn [{:keys [data]}] (:b? data))}
                :states  {:idle    {:on {:go :loading}}
                          :loading {:after {5000 [{:guard :a? :target :x}
                                                  {:guard :b? :target :y}]}}
                          :x       {}
                          :y       {}}}
          snap (snap-at :loading {[:loading] 1} {:a? false :b? false})
          [state next-snap fx] (fire spec snap (after-event 5000 1 [:loading]))]
      (is (= :loading state)
          "all candidate guards fail, no fallback → no transition (guard-suppressed)")
      (is (= 1 (get-in next-snap [:data :rf/after-epoch [:loading]]))
          "guard-suppressed firing does not advance the node's epoch")
      (is (empty? fx)
          "guard-suppressed firing emits no effects")))

  (testing "all-fail guarded vector leaves a sibling :after timer live"
    (let [spec {:initial :idle
                :data    {:a? false :b? false}
                :guards  {:a? (fn [{:keys [data]}] (:a? data))
                          :b? (fn [{:keys [data]}] (:b? data))}
                :states  {:idle    {:on {:go :loading}}
                          :loading {:after {5000  [{:guard :a? :target :x}
                                                   {:guard :b? :target :y}]
                                            30000 :timeout}}
                          :x       {}
                          :y       {}
                          :timeout {}}}
          snap (snap-at :loading {[:loading] 1} {:a? false :b? false})
          [state5 snap5] (fire spec snap (after-event 5000 1 [:loading]))
          [state30]      (fire spec snap5 (after-event 30000 1 [:loading]))]
      (is (= :loading state5) "all-fail guarded vector did not transition")
      (is (= :timeout state30)
          "sibling :after timer (same epoch) is still live"))))

;; ---- (h) hierarchical guarded :after — leaf vs parent epoch / staleness ----

(deftest after-guarded-vector-hierarchical
  (testing "guarded candidate-vector :after on a CHILD resolves via the
            child decl-path while the parent's epoch is independent"
    (let [spec {:initial :p
                :data    {:escalate? true}
                :guards  {:escalate? (fn [{:keys [data]}] (:escalate? data))}
                :states  {:p {:initial :a
                              :after   {30000 :timed-out}
                              :states  {:a {:after {5000 [{:guard :escalate? :target :a-hot}
                                                          {:target :a-warm}]}}
                                        :a-hot  {}
                                        :a-warm {}}}
                          :timed-out {}}}
          ;; Resting at [:p :a]; parent + child each at their own epoch.
          snap (snap-at [:p :a] {[:p] 1 [:p :a] 1} {:escalate? true})
          [state] (fire spec snap (after-event 5000 1 [:p :a]))]
      (is (= [:p :a-hot] state)
          "child guarded-vector :after resolves at the child decl-path
           (first guard passes → :a-hot, sibling under the same parent)")))

  (testing "the SAME child guarded-vector :after is stale once the child's
            per-path epoch advances (re-entry) — parent untouched"
    (let [spec {:initial :p
                :data    {:escalate? true}
                :guards  {:escalate? (fn [{:keys [data]}] (:escalate? data))}
                :states  {:p {:initial :a
                              :after   {30000 :timed-out}
                              :states  {:a {:after {5000 [{:guard :escalate? :target :a-hot}
                                                          {:target :a-warm}]}}
                                        :a-hot  {}
                                        :a-warm {}}}
                          :timed-out {}}}
          ;; Carried epoch 1 but the child's current per-path epoch is 2 → stale.
          snap (snap-at [:p :a] {[:p] 1 [:p :a] 2} {:escalate? true})
          [state next-snap] (fire spec snap (after-event 5000 1 [:p :a]))]
      (is (= [:p :a] state)
          "stale guarded-vector :after (epoch mismatch) does NOT transition")
      (is (= 2 (get-in next-snap [:data :rf/after-epoch [:p :a]]))
          "stale firing does not advance the child epoch")))

  (testing "a PARENT guarded-vector :after stays live across the child epoch
            and resolves via the parent decl-path"
    (let [spec {:initial :p
                :data    {:hard? true}
                :guards  {:hard? (fn [{:keys [data]}] (:hard? data))}
                :states  {:p {:initial :a
                              :after   {30000 [{:guard :hard? :target :timed-out}
                                               {:target :soft-stop}]}
                              :states  {:a {:after {5000 :a-warn}}
                                        :a-warn {}}}
                          :timed-out {}
                          :soft-stop {}}}
          snap (snap-at [:p :a] {[:p] 1 [:p :a] 1} {:hard? true})
          [state] (fire spec snap (after-event 30000 1 [:p]))]
      (is (= :timed-out state)
          "parent guarded-vector :after resolves at the parent decl-path
           (first guard passes → :timed-out)"))))

;; ---- (i) parallel-region-scoped guarded :after — region decl-path routing --

(deftest after-guarded-vector-parallel-region
  (testing "a guarded candidate-vector :after inside one parallel region
            resolves via the region decl-path; the bearing region transitions
            and the sibling region declines"
    (let [spec {:type    :parallel
                :data    {:ready? true}
                :guards  {:ready? (fn [{:keys [data]}] (:ready? data))}
                :regions {:net   {:initial :connecting
                                  :states  {:connecting
                                            {:after {6000 [{:guard :ready? :target :online}
                                                           {:target :degraded}]}}
                                            :online   {}
                                            :degraded {}}}
                          :audio {:initial :muted
                                  :states  {:muted    {:on {:unmute :playing}}
                                            :playing  {}}}}}
          ;; Parallel snapshot: :state is a per-region map; the region-scoped
          ;; epoch map lives under :rf/after-epoch-by-region.
          snap {:state {:net :connecting :audio :muted}
                :data  {:ready? true
                        :rf/after-epoch-by-region {:net   {[:connecting] 1}
                                                   :audio {}}}}
          ;; The synthetic event carries the region-name-prefixed decl-path.
          [state] (fire spec snap (after-event 6000 1 [:net :connecting]))]
      (is (= :online (:net state))
          "the :net region's guarded-vector :after resolves (first guard
           passes → :online) via the region-scoped decl-path")
      (is (= :muted (:audio state))
          "the sibling :audio region declines the synthetic timer event")))

  (testing "region guarded-vector :after with first guard failing falls back
            to the unguarded candidate within the region"
    (let [spec {:type    :parallel
                :data    {:ready? false}
                :guards  {:ready? (fn [{:keys [data]}] (:ready? data))}
                :regions {:net   {:initial :connecting
                                  :states  {:connecting
                                            {:after {6000 [{:guard :ready? :target :online}
                                                           {:target :degraded}]}}
                                            :online   {}
                                            :degraded {}}}
                          :audio {:initial :muted
                                  :states  {:muted   {:on {:unmute :playing}}
                                            :playing {}}}}}
          snap {:state {:net :connecting :audio :muted}
                :data  {:ready? false
                        :rf/after-epoch-by-region {:net   {[:connecting] 1}
                                                   :audio {}}}}
          [state] (fire spec snap (after-event 6000 1 [:net :connecting]))]
      (is (= :degraded (:net state))
          "first region candidate's guard fails → unguarded fallback :degraded"))))

;; ---- shared-helper proof: :on and :after go through one normaliser ---------

(deftest on-and-after-share-candidate-resolution
  (testing "the SAME guarded candidate-vector resolves identically whether it
            sits in an :on clause or an :after delay entry — proving both go
            through the one shared candidate-walk (rf2-vvbdl)"
    (let [candidates [{:guard :hot? :target :escalated :action :mark}
                      {:target :calm}]
          guards     {:hot? (fn [{:keys [data]}] (:hot? data))}
          actions    {:mark (fn [{:keys [data]}] {:data (assoc data :marked true)})}
          on-spec    {:initial :idle
                      :data    {}
                      :guards  guards
                      :actions actions
                      :states  {:idle      {:on {:poke candidates}}
                                :escalated {}
                                :calm      {}}}
          after-spec {:initial :idle
                      :data    {}
                      :guards  guards
                      :actions actions
                      :states  {:idle      {:after {5000 candidates}}
                                :escalated {}
                                :calm      {}}}]
      (testing "guard passes → first candidate target + action on both paths"
        (let [{on-snap ::result/snap}
              (machines/machine-transition on-spec {:state :idle :data {:hot? true}} [:poke])
              {after-snap ::result/snap}
              (machines/machine-transition
                after-spec
                {:state :idle
                 :data  {:hot? true :rf/after-epoch {[:idle] 1}}}
                (after-event 5000 1 [:idle]))]
          (is (= :escalated (:state on-snap)) ":on first candidate fires")
          (is (= :escalated (:state after-snap)) ":after first candidate fires")
          (is (true? (get-in on-snap [:data :marked])) ":on candidate action ran")
          (is (true? (get-in after-snap [:data :marked])) ":after candidate action ran")
          (is (= (:state on-snap) (:state after-snap))
              ":on and :after land on the SAME target for the same candidate-vector")))
      (testing "guard fails → unguarded fallback on both paths"
        (let [{on-snap ::result/snap}
              (machines/machine-transition on-spec {:state :idle :data {:hot? false}} [:poke])
              {after-snap ::result/snap}
              (machines/machine-transition
                after-spec
                {:state :idle
                 :data  {:hot? false :rf/after-epoch {[:idle] 1}}}
                (after-event 5000 1 [:idle]))]
          (is (= :calm (:state on-snap)) ":on falls back to the unguarded candidate")
          (is (= :calm (:state after-snap)) ":after falls back to the unguarded candidate")
          (is (= (:state on-snap) (:state after-snap))
              ":on and :after fall back to the SAME target"))))))
