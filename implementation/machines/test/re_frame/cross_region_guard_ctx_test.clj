(ns re-frame.cross-region-guard-ctx-test
  "Per Spec 005 §Cross-region coordination — tags as `stateIn`
  (rf2-46ly6 / rf2-69d1n).

  XState v5 / SCXML gold standard: a parallel region's guard can
  predicate on a SIBLING region's active state via `stateIn(stateValue)`
  (XState v5 `xstate/guards`) / the `In(stateID)` predicate (W3C SCXML
  B.1) — the canonical orthogonal-region coordination primitive.

  re-frame2 expresses this WITHOUT a separate `stateIn` primitive
  (rf2-69d1n — behavioural parity, not API mimicry). `reduce-regions`
  threads the machine-wide active-configuration `:tags` union and the
  full `:all-state` region map into every region's guard/action ctx
  (rf2-46ly6). A region guard then reads a sibling region's state via
  either:
    - `(contains? (:tags ctx) :some/state-tag)` — the coarse tag query
      (the documented `stateIn` substitute);
    - `(= :valid (:form (:all-state ctx)))` — the precise sibling-state
      read.

  Coverage (the bead's comprehensive test list):
    (a) a region guard reading `(:tags ctx)` sees a SIBLING region's
        state-tag and fires / blocks accordingly — the core cross-region
        coordination case, impossible before rf2-46ly6.
    (b) the §State-tags-as-stateIn worked example from spec/005 actually
        works from a region guard.
    (c) tags reflect the EVOLVING snapshot mid-macrostep — a sibling
        transition earlier in the same macrostep is visible to a later
        region's guard.
    (d) region guards still see their OWN state correctly (regression).
    (e) non-parallel (flat / compound) guard ctx is UNAFFECTED — neither
        `:tags` nor `:all-state` appears (regression).
    (f) action ctx gets the same `:tags` + `:all-state` threading."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]
            [re-frame.machines.parallel :as parallel]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- snapshot [machine-id]
  (get-in (rf/app-db-value :rf/default) [:rf/runtime :machines :snapshots machine-id]))

;; ---- (a) region guard reads a sibling region's tag ------------------------

(deftest region-guard-reads-sibling-tag
  (testing "a :gate region guard reading (:tags ctx) sees the :form region's
            tag and fires / blocks accordingly"
    (let [m {:type    :parallel
             :data    {}
             :guards  {:form-valid? (fn [{:keys [tags]}]
                                      (contains? tags :form/valid))}
             :regions
             {:form {:initial :invalid
                     :states  {:invalid {:tags #{:form/invalid} :on {:validate :valid}}
                               :valid   {:tags #{:form/valid}}}}
              :gate {:initial :closed
                     :states  {:closed {:on {:try {:target :open :guard :form-valid?}}}
                               :open   {}}}}}]
      (rf/reg-machine :xreg/tag-gate m)
      ;; Form still :invalid → :gate's :try guard reads NO :form/valid → blocked.
      (rf/dispatch-sync [:xreg/tag-gate [:try]])
      (is (= {:form :invalid :gate :closed} (:state (snapshot :xreg/tag-gate)))
          ":gate stays :closed — sibling :form has not advertised :form/valid")
      ;; Flip the form valid; now its tag is in the machine-wide union.
      (rf/dispatch-sync [:xreg/tag-gate [:validate]])
      (is (= :valid (get-in (snapshot :xreg/tag-gate) [:state :form])))
      ;; :try again — :gate's guard now reads :form/valid in (:tags ctx) → fires.
      (rf/dispatch-sync [:xreg/tag-gate [:try]])
      (is (= :open (get-in (snapshot :xreg/tag-gate) [:state :gate]))
          ":gate opens — sibling :form now advertises :form/valid in the tag union"))))

;; ---- (a') region guard reads a sibling's precise :all-state value ----------

(deftest region-guard-reads-sibling-all-state
  (testing "a region guard reading (:all-state ctx) sees the sibling region's
            discrete state value (the precise stateIn equivalent)"
    (let [m {:type    :parallel
             :data    {}
             :guards  {:form-is-valid? (fn [{:keys [all-state]}]
                                         (= :valid (:form all-state)))}
             :regions
             {:form {:initial :invalid
                     :states  {:invalid {:on {:validate :valid}}
                               :valid   {}}}
              :gate {:initial :closed
                     :states  {:closed {:on {:try {:target :open :guard :form-is-valid?}}}
                               :open   {}}}}}]
      (rf/reg-machine :xreg/state-gate m)
      (rf/dispatch-sync [:xreg/state-gate [:try]])
      (is (= :closed (get-in (snapshot :xreg/state-gate) [:state :gate]))
          ":gate blocked — sibling :form is not :valid")
      (rf/dispatch-sync [:xreg/state-gate [:validate]])
      (rf/dispatch-sync [:xreg/state-gate [:try]])
      (is (= :open (get-in (snapshot :xreg/state-gate) [:state :gate]))
          ":gate opens — (:all-state ctx) :form read as :valid"))))

;; ---- (b) the spec/005 worked example actually works from a region guard ----

(deftest spec-005-stateIn-substitute-worked-example
  (testing "the spec/005 §Cross-region coordination worked example —
            :checkout's :submit guarded on the :form region being :valid —
            works end to end via the tag union"
    ;; This mirrors the canonical xstate `stateIn({form: 'valid'})` example,
    ;; expressed as the re-frame2 tag substitute. The :form region carries a
    ;; :form/valid tag in its :valid state; the :checkout region's :submit
    ;; transition is guarded on that tag being in the machine-wide union.
    (let [m {:type    :parallel
             :data    {}
             :guards  {:form-valid? (fn [{:keys [tags]}]
                                      (contains? tags :form/valid))}
             :regions
             {:form     {:initial :editing
                         :states  {:editing {:tags #{:form/editing}
                                             :on   {:complete :valid}}
                                   :valid   {:tags #{:form/valid}}}}
              :checkout {:initial :idle
                         :states  {:idle      {:on {:submit {:target :submitting
                                                             :guard  :form-valid?}}}
                                   :submitting {:tags #{:checkout/submitting}}}}}}]
      (rf/reg-machine :xreg/checkout m)
      ;; :submit while the form is :editing → blocked (no :form/valid tag).
      (rf/dispatch-sync [:xreg/checkout [:submit]])
      (is (= :idle (get-in (snapshot :xreg/checkout) [:state :checkout]))
          ":submit blocked while :form is :editing")
      ;; Complete the form, then submit → fires.
      (rf/dispatch-sync [:xreg/checkout [:complete]])
      (rf/dispatch-sync [:xreg/checkout [:submit]])
      (is (= :submitting (get-in (snapshot :xreg/checkout) [:state :checkout]))
          ":submit fires once the :form region advertises :form/valid")
      (is (contains? (:tags (snapshot :xreg/checkout)) :checkout/submitting)
          "the committed tag union reflects :checkout's new state too"))))

;; ---- (c) tags reflect the evolving mid-macrostep snapshot ------------------

(deftest tag-union-reflects-evolving-macrostep
  (testing "a sibling region's transition EARLIER in the same macrostep is
            visible to a later region's guard via the evolving tag union"
    ;; Declaration order: :form before :gate. A single :go event makes :form
    ;; transition :invalid → :valid (advertising :form/valid). Because :form is
    ;; processed first within the one broadcast, :gate's :go guard — evaluated
    ;; against the EVOLVING snapshot — already sees :form/valid and fires, all
    ;; in ONE macrostep with NO intermediate dispatch.
    (let [m {:type    :parallel
             :data    {}
             :guards  {:form-valid? (fn [{:keys [tags]}]
                                      (contains? tags :form/valid))}
             :regions
             {:form {:initial :invalid
                     :states  {:invalid {:tags #{:form/invalid} :on {:go :valid}}
                               :valid   {:tags #{:form/valid}}}}
              :gate {:initial :closed
                     :states  {:closed {:on {:go {:target :open :guard :form-valid?}}}
                               :open   {}}}}}]
      (rf/reg-machine :xreg/evolving m)
      (rf/dispatch-sync [:xreg/evolving [:go]])
      (is (= {:form :valid :gate :open} (:state (snapshot :xreg/evolving)))
          ":gate saw :form's same-macrostep transition to :valid and opened"))))

(deftest tag-union-evolving-pure-fn
  (testing "pure machine-transition: the evolving union is current as of the
            region's position in the broadcast (declaration order)"
    (let [m {:type    :parallel
             :data    {}
             :guards  {:form-valid? (fn [{:keys [tags]}]
                                      (contains? tags :form/valid))}
             :regions
             {:form {:initial :invalid
                     :states  {:invalid {:tags #{:form/invalid} :on {:go :valid}}
                               :valid   {:tags #{:form/valid}}}}
              :gate {:initial :closed
                     :states  {:closed {:on {:go {:target :open :guard :form-valid?}}}
                               :open   {}}}}}
          initial {:state {:form :invalid :gate :closed}
                   :data  {} :tags #{:form/invalid}}
          {snap ::result/snap} (parallel/machine-transition m initial [:go])]
      (is (= {:form :valid :gate :open} (:state snap))
          "pure transition: :gate's guard read the evolving :form/valid")
      (is (= #{:form/valid} (:tags snap))
          "committed union reflects both regions' settled states"))))

;; ---- (d) a region guard still sees its OWN state correctly (regression) ---

(deftest region-guard-sees-own-state
  (testing "a region guard reading :state / :data still resolves against its
            OWN region (unaffected by the cross-region threading)"
    (let [seen (atom [])
          m {:type    :parallel
             :data    {:n 0}
             :guards  {:own-idle?  (fn [{:keys [state data]}]
                                     (swap! seen conj {:state state :data data})
                                     (= :idle state))}
             :regions
             {:a {:initial :idle
                  :states  {:idle {:on {:advance {:target :busy :guard :own-idle?}}}
                            :busy {}}}
              :b {:initial :x
                  :states  {:x {}}}}}]
      (rf/reg-machine :xreg/own m)
      (rf/dispatch-sync [:xreg/own [:advance]])
      (is (= :busy (get-in (snapshot :xreg/own) [:state :a]))
          "guard reading its OWN :state (= :idle) fired the transition")
      (is (every? #(= :idle (:state %)) @seen)
          "the guard's :state was the region's OWN discrete value, never the
           sibling's nor the full region map"))))

;; ---- (e) flat / compound guard ctx is UNAFFECTED (regression) -------------

(deftest flat-guard-ctx-has-no-cross-region-keys
  (testing "a FLAT machine's guard ctx is exactly {:data :event :state :meta}
            — no :tags, no :all-state"
    (let [captured (atom nil)
          m {:initial :idle
             :data    {:ok true}
             :guards  {:capture (fn [ctx]
                                   (reset! captured ctx)
                                   true)}
             :states  {:idle {:tags #{:flat/idle}
                              :on   {:go {:target :done :guard :capture}}}
                       :done {}}}]
      (rf/reg-machine :flat/ctx m)
      (rf/dispatch-sync [:flat/ctx [:go]])
      (is (= :done (:state (snapshot :flat/ctx))))
      (is (= #{:data :event :state :meta} (set (keys @captured)))
          "flat guard ctx carries ONLY the four base keys")
      (is (not (contains? @captured :tags))
          "flat guard ctx has no :tags (the committed :tags slot does NOT leak)")
      (is (not (contains? @captured :all-state))
          "flat guard ctx has no :all-state (parallel-region marker absent)")
      (is (= :idle (:state @captured)) "flat guard still sees its OWN :state")
      (is (= {:ok true} (:data @captured)) "flat guard still sees :data"))))

(deftest compound-guard-ctx-has-no-cross-region-keys
  (testing "a COMPOUND machine's guard ctx is also exactly the four base keys"
    (let [captured (atom nil)
          m {:initial :parent
             :data    {}
             :guards  {:capture (fn [ctx] (reset! captured ctx) true)}
             :states  {:parent {:initial :child
                                :states  {:child {:on {:go {:target :sibling
                                                            :guard  :capture}}}
                                          :sibling {}}}}}]
      (rf/reg-machine :compound/ctx m)
      (rf/dispatch-sync [:compound/ctx [:go]])
      (is (= #{:data :event :state :meta} (set (keys @captured)))
          "compound guard ctx carries ONLY the four base keys")
      (is (not (contains? @captured :all-state))))))

;; ---- (f) action ctx gets the same :tags + :all-state threading ------------

(deftest region-action-receives-cross-region-ctx
  (testing "a region ACTION receives the machine-wide :tags union and the
            :all-state region map, same as a guard"
    (let [captured (atom nil)
          m {:type    :parallel
             :data    {}
             :actions {:capture (fn [{:keys [tags all-state] :as ctx}]
                                  (reset! captured (select-keys ctx [:tags :all-state]))
                                  {:data {:saw-tags      tags
                                          :saw-all-state all-state}})}
             :regions
             {:form {:initial :valid
                     :states  {:valid {:tags #{:form/valid}}}}
              :gate {:initial :closed
                     :states  {:closed {:on {:snap {:target :closed
                                                    :action :capture}}}}}}}]
      (rf/reg-machine :xreg/action-ctx m)
      (rf/dispatch-sync [:xreg/action-ctx [:snap]])
      (is (some? @captured) "the action ran")
      (is (= #{:form/valid} (:tags @captured))
          "action ctx carries the machine-wide tag union (sibling :form's tag)")
      (is (= {:form :valid :gate :closed} (:all-state @captured))
          "action ctx carries the full :all-state region map"))))

;; ---- bonus: cross-region keys do NOT leak into the committed snapshot ------

(deftest cross-region-ctx-keys-do-not-leak-into-snapshot
  (testing ":all-state is a ctx-only key — it never appears on the committed
            parallel snapshot (only the derived :tags union does)"
    (let [m {:type    :parallel
             :data    {}
             :regions {:a {:initial :one :states {:one {:tags #{:a/one}}}}
                       :b {:initial :two :states {:two {:tags #{:b/two}}}}}}]
      (rf/reg-machine :xreg/no-leak m)
      (rf/dispatch-sync [:xreg/no-leak [:no-match]])
      (let [s (snapshot :xreg/no-leak)]
        (is (not (contains? s :all-state))
            ":all-state is ctx-only — never committed onto the snapshot")
        (is (= #{:a/one :b/two} (:tags s))
            "the derived :tags union is committed as usual")))))
