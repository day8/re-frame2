(ns re-frame.machine-cascade-instrumentation-test
  "The `:rf.machine/transition` trace carries a structured `:cascade` field:
  the ordered exit / transition-action / entry steps (+ initial-descent +
  `:always` microsteps + per-region structure) that explain HOW a transition
  reached its after-state, so tooling (Xray's epoch panel) can render the
  cascade rather than only `{from}->{to} + {n} microstep(s)`.

  The cascade step shape (the contract tooling renders) is a vector of
  self-describing step maps in execution order:

    {:kind   :exit | :action | :entry | :microstep
     :state  <state-path-vector>          ;; LCA-relative for :action
     :region <region-name-or-nil>          ;; parallel region; nil flat/compound
     :action <action-id-or-nil>            ;; nil = boundary with no declared action
     :data-delta {<k> <new-v>}}            ;; this step's :data contribution

  `:microstep` steps add `:microstep-index` / `:from` / `:to` / `:steps`
  (the microstep's own nested exit/action/entry cascade).

  The HVAC fixture mirrors the live machine-epochs testbed
  (`:hvac/controller`) whose own `:data :trail` records the cascade order
  — that trail is the ORACLE these tests cross-check the emitted cascade
  against."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; Routed through the shared `mtest/with-trace-capture`
;; — guaranteed unregister in a `finally`.
(defn- record-traces! [body-fn]
  (mtest/with-trace-capture seen
    (body-fn)
    @seen))

(defn- ops [evs op] (filterv #(= op (:operation %)) evs))

(defn- the-transition
  "The single `:rf.machine/transition` trace produced by the body — the
  outer macrostep trace whose `:cascade` we assert on. (Bootstrap +
  user-event in separate dispatches each produce one; callers pick.)"
  [evs]
  (last (ops evs :rf.machine/transition)))

(defn- cascade-of [evs] (-> (the-transition evs) :tags :cascade))

;; A `:trail`-appending action: conj's `label` onto `[:data :trail]`, so
;; the post-macrostep trail is the cascade order made visible — the same
;; oracle the live `:hvac/controller` testbed uses.
(defn- trail-action [label]
  (fn [{data :data}]
    {:data (update data :trail (fnil conj []) label)}))

;; =====================================================================
;; HVAC fixture — a faithful copy of the live machine-epochs testbed's
;; :hvac/controller (parallel, deep-compound :climate region + flatter
;; :fan region). The oracle: dispatching [:hvac/power-cycle] from the
;; rest configuration produces the trail
;;   [:action:power-on :entry:running :entry:conditioning :entry:heating
;;    :action:fan-on :entry:fan-on]
;; =====================================================================

(defn- reg-hvac! []
  (rf/reg-machine :hvac/controller
    {:type :parallel
     :data {:trail []}
     :regions
     {:climate
      {:initial :idle
       :states
       {:idle    {:on {:hvac/power-cycle {:target :running :action :enter-running}}}
        :running {:initial :conditioning
                  :entry   :enter-running-level
                  :exit    :exit-running-level
                  :on      {:hvac/power-cycle {:target :idle :action :back-to-idle}}
                  :states
                  {:conditioning
                   {:initial :heating
                    :entry   :enter-conditioning
                    :exit    :exit-conditioning
                    :states
                    {:heating {:entry :enter-heating
                               :exit  :exit-heating
                               :on    {:hvac/mode-toggle {:target :cooling :action :swap-mode}}}
                     :cooling {:entry :enter-cooling
                               :exit  :exit-cooling
                               :on    {:hvac/mode-toggle {:target :heating :action :swap-mode}}}}}}}}}
      :fan
      {:initial :off
       :states
       {:off {:on {:hvac/power-cycle {:target :on :action :fan-on}}}
        :on  {:entry :enter-fan-on
              :exit  :exit-fan-on
              :on    {:hvac/power-cycle {:target :off :action :fan-off}
                      :hvac/nudge {:target :same-state :reenter? true :action :nudge-fan}
                      :hvac/tweak {:action :tweak-fan}}}}}}
     :actions
     {:enter-running       (trail-action :action:power-on)
      :enter-running-level (trail-action :entry:running)
      :exit-running-level  (trail-action :exit:running)
      :back-to-idle        (trail-action :action:power-off)
      :enter-conditioning  (trail-action :entry:conditioning)
      :exit-conditioning   (trail-action :exit:conditioning)
      :enter-heating       (trail-action :entry:heating)
      :exit-heating        (trail-action :exit:heating)
      :enter-cooling       (trail-action :entry:cooling)
      :exit-cooling        (trail-action :exit:cooling)
      :swap-mode           (trail-action :action:swap-mode)
      :fan-on              (trail-action :action:fan-on)
      :fan-off             (trail-action :action:fan-off)
      :enter-fan-on        (trail-action :entry:fan-on)
      :exit-fan-on         (trail-action :exit:fan-on)
      :nudge-fan           (trail-action :action:nudge)
      :tweak-fan           (trail-action :action:tweak)}}))

(defn- boot-hvac! []
  (rf/dispatch-sync [:hvac/controller [:rf.machine/start]]))

(defn- trail-of []
  (get-in @(rf/subscribe [:rf/machine :hvac/controller]) [:data :trail]))

;; ---- the deep compound + parallel cascade (the headline case) -------------

(deftest power-cycle-emits-ordered-cascade-matching-the-trail-oracle
  (testing "[:hvac/power-cycle] emits a :rf.machine/transition whose :cascade
   lists the ordered exit/action/entry steps matching the engine's actual
   cascade — cross-checked against the testbed's :data :trail oracle"
    (reg-hvac!)
    (boot-hvac!)
    (let [evs     (record-traces!
                    (fn [] (rf/dispatch-sync [:hvac/controller [:hvac/power-cycle]])))
          tr      (the-transition evs)
          cascade (-> tr :tags :cascade)
          trail   (trail-of)]
      ;; RED guard: the field exists and is a non-empty structured vector.
      (is (some? tr) "a :rf.machine/transition trace fired")
      (is (vector? cascade) ":cascade is a vector (RED: was absent)")
      (is (seq cascade) ":cascade is non-empty (RED: only before/after+count)")

      ;; The trail oracle for power-cycle from rest (climate region first,
      ;; then fan region — declaration order).
      (is (= [:action:power-on :entry:running :entry:conditioning :entry:heating
              :action:fan-on :entry:fan-on]
             trail)
          "trail oracle confirms the cascade order")

      ;; Every cascade step that RAN an action must, in order, project to
      ;; that action's trail label — i.e. the cascade is the trail made
      ;; structured. (Boundaries with no action carry nil :action and an
      ;; empty :data-delta; they don't append to the trail.)
      (let [acting (filterv :action (remove #(= :microstep (:kind %)) cascade))
            ;; each action's :data-delta :trail is the WHOLE trail-so-far;
            ;; the step's contribution is its LAST element.
            labels (mapv #(last (get-in % [:data-delta :trail])) acting)]
        (is (= trail labels)
            "the ordered acting-step labels reconstruct the trail exactly"))

      ;; Per-step structure: kinds + states + regions are self-describing.
      ;; The cascade is a COMPLETE configuration walk: :idle / :off are
      ;; EXITED (no :exit action declared → :action nil, empty delta) ahead
      ;; of the action + entry boundaries. The trail oracle only captures
      ;; the action-bearing boundaries; the cascade additionally records the
      ;; action-free exits so the geometry is complete.
      (let [climate (filterv #(= :climate (:region %)) cascade)
            fan     (filterv #(= :fan (:region %)) cascade)]
        (is (= [:exit :action :entry :entry :entry] (mapv :kind climate))
            ":climate — exit :idle (no action) -> transition action @ LCA -> 3-level entry/initial-descent")
        (is (= [[:idle] [:idle] [:running] [:running :conditioning]
                [:running :conditioning :heating]]
               (mapv :state climate))
            ":climate step :state paths are region-relative (exit @ [:idle], action @ decl-path, entries shallowest-first)")
        (is (= [:exit :action :entry] (mapv :kind fan))
            ":fan — exit :off (no action) -> action -> single entry")
        (is (= [nil :enter-running :enter-running-level :enter-conditioning :enter-heating]
               (mapv :action climate))
            ":climate action-ids in cascade order (leading nil = action-free :idle exit)")
        (is (= [nil :fan-on :enter-fan-on] (mapv :action fan))
            ":fan action-ids in cascade order (leading nil = action-free :off exit)")))))

;; ---- the LCA cascade: exit (deepest-first) -> action -> entry -------------

(deftest mode-toggle-emits-exit-action-entry-across-the-lca
  (testing ":hvac/mode-toggle on :heating crosses the :conditioning LCA to
   :cooling — exit :heating (deepest-first) -> swap-mode @ LCA -> entry
   :cooling (shallowest-first); the cascade carries all three in order"
    (reg-hvac!)
    (boot-hvac!)
    (rf/dispatch-sync [:hvac/controller [:hvac/power-cycle]]) ;; land on :heating
    (let [evs     (record-traces!
                    (fn [] (rf/dispatch-sync [:hvac/controller [:hvac/mode-toggle]])))
          cascade (cascade-of evs)
          climate (filterv #(= :climate (:region %)) cascade)]
      (is (= [:exit :action :entry] (mapv :kind climate))
          "exit -> action -> entry order")
      (is (= [:exit-heating :swap-mode :enter-cooling] (mapv :action climate)))
      ;; trail oracle for the toggle leg only (it appends after power-cycle).
      (is (= [:exit:heating :action:swap-mode :entry:cooling]
             (take-last 3 (trail-of)))
          "trail oracle: exit:heating -> action:swap-mode -> entry:cooling"))))

;; ---- self-transitions (external = exit+entry; internal = action only) -----

(deftest external-self-transition-shows-exit-and-entry
  (testing ":hvac/nudge (external self-transition, :target :same-state +
   :reenter? true) on :fan :on re-enters itself: exit :fan-on -> nudge ->
   entry :fan-on"
    (reg-hvac!)
    (boot-hvac!)
    (rf/dispatch-sync [:hvac/controller [:hvac/power-cycle]]) ;; :fan -> :on
    (let [evs     (record-traces!
                    (fn [] (rf/dispatch-sync [:hvac/controller [:hvac/nudge]])))
          cascade (cascade-of evs)
          fan     (filterv #(= :fan (:region %)) cascade)]
      (is (= [:exit :action :entry] (mapv :kind fan))
          "external self-transition fires exit + action + entry")
      (is (= [:exit-fan-on :nudge-fan :enter-fan-on] (mapv :action fan))))))

(deftest internal-self-transition-shows-action-only
  (testing ":hvac/tweak (internal self-transition, no :target) on :fan :on
   fires the action ONLY — no exit, no entry"
    (reg-hvac!)
    (boot-hvac!)
    (rf/dispatch-sync [:hvac/controller [:hvac/power-cycle]]) ;; :fan -> :on
    (let [evs     (record-traces!
                    (fn [] (rf/dispatch-sync [:hvac/controller [:hvac/tweak]])))
          cascade (cascade-of evs)
          fan     (filterv #(= :fan (:region %)) cascade)]
      (is (= [:action] (mapv :kind fan))
          "internal self-transition is action-only — no exit/entry boundary")
      (is (= [:tweak-fan] (mapv :action fan))))))

;; ---- flat single-step transition: minimal correct cascade -----------------

(deftest flat-transition-emits-minimal-cascade
  (testing "a flat single-step transition emits a minimal correct cascade:
   one exit boundary (no action), the transition action, one entry boundary
   (no action)"
    (rf/reg-machine :casc/flat
      {:initial :a
       :actions {:go-act (fn [{d :data}] {:data (assoc d :went true)})}
       :states  {:a {:on {:go {:target :b :action :go-act}}}
                 :b {}}})
    (rf/dispatch-sync [:casc/flat [:rf.machine/start]])
    (let [evs     (record-traces!
                    (fn [] (rf/dispatch-sync [:casc/flat [:go]])))
          cascade (cascade-of evs)]
      (is (= [{:kind :exit  :state [:a] :region nil :action nil  :data-delta {}}
              {:kind :action :state [:a] :region nil :action :go-act :data-delta {:went true}}
              {:kind :entry :state [:b] :region nil :action nil  :data-delta {}}]
             cascade)
          "flat cascade: exit[:a] (no action) -> action @ decl-path -> entry[:b] (no action)"))))

;; ---- :always / eventless microsteps appear in the cascade -----------------

(deftest always-microstep-appears-in-cascade
  (testing "an :always-driven cascade appends a :microstep step carrying the
   eventless transition's own nested exit/action/entry :steps, so microsteps
   are explainable (not just counted)"
    (rf/reg-machine :casc/quiz
      {:initial :asking
       :data    {:correct 9}
       :guards  {:enough? (fn [{d :data}] (>= (:correct d) 10))}
       :actions {:count (fn [{d :data}] {:data {:correct (inc (:correct d))}})
                 :win   (fn [{d :data}] {:data (assoc d :won true)})}
       :states  {:asking {:always [{:guard :enough? :target :winner :action :win}]
                          :on     {:answer {:action :count}}}
                 :winner {}}})
    (rf/dispatch-sync [:casc/quiz [:rf.machine/start]])
    (let [evs       (record-traces!
                      (fn [] (rf/dispatch-sync [:casc/quiz [:answer]])))
          cascade   (cascade-of evs)
          microstep (first (filterv #(= :microstep (:kind %)) cascade))]
      ;; the :answer transition's action (:count) is the headline step
      (is (some #(= :count (:action %)) cascade)
          "the event-driven :count action is in the cascade")
      ;; the :always step that flipped to :winner rides as a :microstep
      (is (some? microstep) "a :microstep step is present in the cascade")
      (is (= 0 (:microstep-index microstep)))
      (is (= :asking (:from microstep)))
      (is (= :winner (:to microstep)))
      (is (vector? (:steps microstep)) ":microstep carries its own nested :steps")
      (is (some #(= :win (:action %)) (:steps microstep))
          "the eventless transition's :win action is explainable inside the microstep"))))

;; ---- privacy / size: no source-literal or large-payload leak --------------

(deftest cascade-carries-only-deltas-not-whole-data
  (testing "each step's :data-delta carries ONLY the keys that step changed
   — not the whole (possibly large) :data map — and carries no source
   literals / fn objects; the cascade is keyword/state/delta data only"
    (rf/reg-machine :casc/delta
      {:initial :a
       :data    {:big (vec (range 1000)) :n 0}  ;; a large pre-existing slot
       :actions {:bump (fn [{d :data}] {:data {:n (inc (:n d))}})}
       :states  {:a {:on {:go {:target :b :action :bump}}}
                 :b {}}})
    (rf/dispatch-sync [:casc/delta [:rf.machine/start]])
    (let [evs     (record-traces!
                    (fn [] (rf/dispatch-sync [:casc/delta [:go]])))
          cascade (cascade-of evs)
          act     (first (filterv #(= :action (:kind %)) cascade))]
      (is (= {:n 1} (:data-delta act))
          ":data-delta is the changed key only — the large :big slot does NOT leak in")
      (is (not (contains? (:data-delta act) :big))
          "unchanged large slot is absent from every step delta")
      ;; no fn objects / source strings anywhere in the cascade
      (is (every? (fn [step]
                    (every? (fn [[_ v]] (not (fn? v)))
                            (:data-delta step)))
                  (remove #(= :microstep (:kind %)) cascade))
          "no fn objects ride the cascade :data-delta"))))
