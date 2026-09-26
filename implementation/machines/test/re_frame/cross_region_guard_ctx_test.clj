(ns re-frame.cross-region-guard-ctx-test
  "Per Spec 005 §Cross-region coordination — tags as `stateIn`.

  XState v5 / SCXML: a parallel region's guard can
  predicate on a SIBLING region's active state via `stateIn(stateValue)`
  (XState v5 `xstate/guards`) / the `In(stateID)` predicate (W3C SCXML
  B.1) — the canonical orthogonal-region coordination primitive.

  re-frame2 expresses this WITHOUT a separate `stateIn` primitive
  (behavioural parity, not API mimicry). `reduce-regions` threads the
  machine-wide active-configuration `:tags` union and the full `:all-state`
  region map into every region's guard/action ctx. A region guard then reads
  a sibling region's state via either:
    - `(contains? (:tags ctx) :some/state-tag)` — the coarse tag query
      (the documented `stateIn` substitute);
    - `(= :valid (:form (:all-state ctx)))` — the precise sibling-state
      read.

  Coverage:
    (b) the §State-tags-as-stateIn worked example from spec/005 works from
        a region guard: the guard reads a SIBLING region's state-tag and
        blocks, then fires once the sibling advertises it.
    (d) region guards see their OWN state correctly.
    (e) non-parallel (flat / compound) guard ctx carries neither `:tags`
        nor `:all-state`.
    `:all-state` stays a ctx-only key, never committed onto the snapshot.

  A guard reading a sibling through `:all-state`, FROZEN selection (a
  sibling's same-event transition is not visible to a later region's
  guard), and the same `:tags` / `:all-state` threading into ACTION ctx are
  pinned in `frozen_region_select_test.clj`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; snapshot lookup via the shared machines test-support — no hardcoded
;; `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot rf.machines.test-support/snapshot)

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

;; ---- (d) a region guard sees its OWN state correctly ----------------------

(deftest region-guard-sees-own-state
  (testing "a region guard reading :state / :data resolves against its
            OWN region, alongside the cross-region threading"
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

;; ---- (e) flat / compound guard ctx carries no cross-region keys -----------

(deftest flat-guard-ctx-has-no-cross-region-keys
  (testing "a FLAT machine's guard ctx carries the four base keys — no :tags,
            no :all-state (the parallel-region keys never leak to a flat
            machine). A router-driven dispatch ALSO carries the EP-0010 causal
            :rf.cofx token; the cross-region keys stay
            absent."
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
      (is (= #{:data :event :state :meta}
             (set (keys (dissoc @captured :rf.cofx))))
          "flat guard ctx carries the four base keys (+ the EP-0010 causal
           token a router dispatch always stamps)")
      (is (not (contains? @captured :tags))
          "flat guard ctx has no :tags (the committed :tags slot does NOT leak)")
      (is (not (contains? @captured :all-state))
          "flat guard ctx has no :all-state (parallel-region marker absent)")
      (is (= :idle (:state @captured)) "flat guard sees its OWN :state")
      (is (= {:ok true} (:data @captured)) "flat guard sees :data"))))

(deftest compound-guard-ctx-has-no-cross-region-keys
  (testing "a COMPOUND machine's guard ctx also carries the four base keys and
            none of the parallel-region keys (a router dispatch additionally
            carries the EP-0010 :rf.cofx token)"
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
      (is (= #{:data :event :state :meta}
             (set (keys (dissoc @captured :rf.cofx))))
          "compound guard ctx carries the four base keys (+ the EP-0010 causal
           token a router dispatch always stamps)")
      (is (not (contains? @captured :tags))
          "compound guard ctx has no :tags (parallel-region key absent)")
      (is (not (contains? @captured :all-state))))))

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
