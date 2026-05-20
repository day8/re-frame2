(ns re-frame.parallel-test
  "Per Spec 005 §Parallel regions and rf2-l67o (Nine States Stage 2).

  Parallel-region semantics covered:
    - A `:type :parallel` machine's initial snapshot has `:state` as a
      map of region-name → that region's cascaded initial state.
    - Every region is active simultaneously when the machine is active.
    - Events broadcast across regions — each region's active state
      independently resolves through deepest-wins; resolved regions
      transition, undeclined regions stay put.
    - Shared `:data` flows sequentially through region actions in
      declaration order (each region's action sees the prior region's
      :data writes).
    - Tag union composes across regions (per Spec 005 §Tags compose
      across regions): snapshot's `:tags` is the union of every active
      state's :tags across every region.
    - Compound region: a region's state-tree can itself be hierarchical
      (its own :initial cascade + LCA exit/entry semantics, snapshot's
      region-value is a vector path inside the region).
    - Per-region `:always`: a region's eventless transitions fire only
      against that region's state, not against siblings'.
    - Initial snapshot stamped at registration with the full region map.
    - Print/read round-trip: parallel snapshots survive pr-str ↔
      read-string with shape intact.
    - Registration-time rejection of bad shape (`:type :parallel`
      without `:regions`, nested parallel, malformed region body).

  These JVM tests pair with the conformance fixtures
  spec/conformance/fixtures/parallel-*.edn."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- snapshot [machine-id]
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

;; ---- 1. flat two-region parallel machine — initial state map ------------

(deftest parallel-flat-two-regions-initial-state
  (testing "initial snapshot's :state is a map of region → that region's :initial"
    (let [m {:type    :parallel
             :data    {:count 0}
             :regions {:left  {:initial :a
                               :states  {:a {:on {:left/go :b}}
                                         :b {}}}
                       :right {:initial :x
                               :states  {:x {:on {:right/go :y}}
                                         :y {}}}}}]
      (rf/reg-machine :par/two-region m)
      ;; Force initialisation via a no-op event the machine doesn't handle.
      (rf/dispatch-sync [:par/two-region [:no-match]])
      (let [s (snapshot :par/two-region)]
        (is (map? (:state s)) "snapshot :state is a map for parallel machines")
        (is (= {:left :a :right :x} (:state s))
            "each region's initial state lands at the region key")
        (is (= {:count 0} (select-keys (:data s) [:count]))
            ":data carries the declared initial data (no per-region :data slot)")))))

;; ---- 2. event broadcasts to every region --------------------------------

(deftest parallel-event-broadcasts-to-every-region
  (testing "one event reaches every region; matching regions transition independently"
    (let [m {:type    :parallel
             :data    {}
             :regions {:left  {:initial :a
                               :states  {:a {:tags #{:left/a} :on {:flip :b}}
                                         :b {:tags #{:left/b} :on {:flip :a}}}}
                       :right {:initial :x
                               :states  {:x {:tags #{:right/x} :on {:flip :y}}
                                         :y {:tags #{:right/y} :on {:flip :x}}}}}}]
      (rf/reg-machine :par/broadcast m)
      (rf/dispatch-sync [:par/broadcast [:flip]])
      (is (= {:left :b :right :y} (:state (snapshot :par/broadcast)))
          "both regions transitioned on the single broadcast event")
      (is (= #{:left/b :right/y} (:tags (snapshot :par/broadcast)))
          "tag union reflects both regions' new states"))))

;; ---- 3. event handled by ONE region; sibling stays put -----------------

(deftest parallel-event-handled-by-one-region
  (testing "region without matching :on stays put; matching region transitions"
    (let [m {:type    :parallel
             :data    {}
             :regions {:left  {:initial :a
                               :states  {:a {:on {:left-only :b}}
                                         :b {}}}
                       :right {:initial :x
                               :states  {:x {}
                                         :y {}}}}}]
      (rf/reg-machine :par/one-region m)
      (rf/dispatch-sync [:par/one-region [:left-only]])
      (is (= {:left :b :right :x} (:state (snapshot :par/one-region)))
          ":left transitioned; :right stayed at its initial"))))

;; ---- 4. tags compose across regions ------------------------------------

(deftest parallel-tags-union-across-regions
  (testing "snapshot's :tags is the union of every active state's :tags across regions"
    (let [m {:type    :parallel
             :data    {}
             :regions {:data {:initial :loading
                              :states  {:loading {:tags #{:data/loading :data/transient}}}}
                       :form {:initial :neutral
                              :states  {:neutral {:tags #{:form/neutral}}}}
                       :mode {:initial :active
                              :states  {:active {:tags #{:mode/active}}}}}}]
      (rf/reg-machine :par/tags m)
      (rf/dispatch-sync [:par/tags [:no-match]])
      (is (= #{:data/loading :data/transient :form/neutral :mode/active}
             (:tags (snapshot :par/tags)))
          "tag union picks up tags from every active state across every region"))))

;; ---- 5. compound region — vector path inside the region ----------------

(deftest parallel-compound-region
  (testing "a region's state-tree may itself be a compound state"
    (let [m {:type    :parallel
             :data    {}
             :regions {:auth      {:initial :authenticated
                                   :states
                                   {:authenticated
                                    {:tags    #{:auth/in}
                                     :initial :dashboard
                                     :states  {:dashboard {:tags #{:dash}
                                                           :on   {:open-settings :settings}}
                                               :settings  {:tags #{:settings}}}}}}
                       :lifecycle {:initial :idle
                                   :states  {:idle {}}}}}]
      (rf/reg-machine :par/compound m)
      (rf/dispatch-sync [:par/compound [:open-settings]])
      (let [s (snapshot :par/compound)]
        (is (= {:auth      [:authenticated :settings]
                :lifecycle :idle}
               (:state s))
            "compound region carries a vector path inside that region")
        (is (= #{:auth/in :settings} (:tags s))
            "tag union walks the compound region's active path")))))

;; ---- 6. shared :data — actions in different regions write through it ---

(deftest parallel-shared-data-flows-through-regions
  (testing "actions across regions write to the same :data map in declaration order"
    (let [m {:type    :parallel
             :data    {:count 0}
             :actions {:bump (fn [d _] {:data (update d :count inc)})}
             :regions {:left  {:initial :a
                               :states  {:a {:on {:bump {:target :a :action :bump}}}}}
                       :right {:initial :x
                               :states  {:x {:on {:bump {:target :x :action :bump}}}}}}}]
      (rf/reg-machine :par/shared-data m)
      (rf/dispatch-sync [:par/shared-data [:bump]])
      ;; Both regions handle :bump, so the action runs TWICE against the
      ;; shared :data (once per region) — :count goes 0 → 1 → 2.
      (is (= 2 (get-in (snapshot :par/shared-data) [:data :count]))
          "shared :data accumulates writes from both regions"))))

;; ---- 7. per-region :always cascade -------------------------------------

(deftest parallel-always-cascade-per-region
  (testing ":always microsteps fire scoped to the region that transitioned"
    (let [m {:type    :parallel
             :data    {:left-ready? false}
             :guards  {:ready? (fn [d _] (true? (:left-ready? d)))}
             :actions {:mark-ready (fn [d _] {:data (assoc d :left-ready? true)})}
             :regions {:left  {:initial :idle
                               :states  {:idle
                                         {:always [{:guard :ready? :target :resolved}]
                                          :on     {:prep {:target :idle :action :mark-ready}}}
                                         :resolved {:tags #{:left/done}}}}
                       :right {:initial :a
                               :states  {:a {:tags #{:right/a}}}}}}]
      (rf/reg-machine :par/always m)
      (rf/dispatch-sync [:par/always [:prep]])
      (let [s (snapshot :par/always)]
        (is (= :resolved (get-in s [:state :left]))
            ":always microstep advanced :left after :prep made the guard true")
        (is (= :a (get-in s [:state :right]))
            ":right is unaffected by :left's :always cascade")
        (is (= #{:left/done :right/a} (:tags s))
            "tag union reflects the post-:always-microstep states")))))

;; ---- 8. pure machine-transition surface --------------------------------

(deftest parallel-pure-machine-transition
  (testing "pure machine-transition broadcasts the event and merges regions"
    (let [m {:type    :parallel
             :data    {}
             :regions {:left  {:initial :a
                               :states  {:a {:tags #{:left/a} :on {:flip :b}}
                                         :b {:tags #{:left/b}}}}
                       :right {:initial :x
                               :states  {:x {:tags #{:right/x} :on {:flip :y}}
                                         :y {:tags #{:right/y}}}}}}
          initial {:state {:left :a :right :x} :data {} :tags #{:left/a :right/x}}
          {snap1 ::result/snap fx1 ::result/fx} (machines/machine-transition m initial [:flip])]
      (is (= {:left :b :right :y} (:state snap1))
          "pure transition broadcasts to both regions")
      (is (= #{:left/b :right/y} (:tags snap1))
          "pure transition recomputes tag union")
      (is (= [] fx1) "no fx emitted for pure self-transitions"))))

;; ---- 9. snapshot print/read round-trip ---------------------------------

(deftest parallel-snapshot-print-read-roundtrip
  (testing "parallel-region snapshot survives pr-str ↔ read-string with shape intact"
    (let [m {:type    :parallel
             :data    {:items [:a :b]}
             :regions {:data {:initial :loaded
                              :states  {:loaded {:tags #{:data/loaded}}}}
                       :form {:initial :neutral
                              :states  {:neutral {:tags #{:form/neutral}}}}}}]
      (rf/reg-machine :par/print m)
      (rf/dispatch-sync [:par/print [:no-match]])
      (let [snap         (snapshot :par/print)
            serialised   (pr-str snap)
            deserialised (edn/read-string serialised)]
        (is (= snap deserialised)
            "round-trip pr-str → read-string yields = value")
        (is (= {:data :loaded :form :neutral} (:state deserialised))
            ":state map round-trips as a keyword-keyed map")
        (is (= #{:data/loaded :form/neutral} (:tags deserialised))
            ":tags survives the round-trip")))))

;; ---- 10. registration-time validation ----------------------------------

(deftest parallel-registration-time-validation
  (testing ":type :parallel without :regions is rejected at registration"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-parallel-bad-shape"
          (machines/make-machine-handler {:type :parallel}))
        ":type :parallel requires :regions"))

  (testing ":type :parallel with :initial / :states is rejected at registration"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-parallel-bad-shape"
          (machines/make-machine-handler {:type :parallel
                                            :initial :foo
                                            :regions {:r {:initial :s :states {:s {}}}}}))
        ":type :parallel is mutually exclusive with :initial / :states at the root"))

  (testing "region missing :initial is rejected at registration"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-parallel-bad-shape"
          (machines/make-machine-handler {:type :parallel
                                            :regions {:r {:states {:s {}}}}}))
        "each region body must declare :initial"))

  (testing "nested :type :parallel is rejected at registration"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-parallel-nested-not-supported"
          (machines/make-machine-handler
            {:type    :parallel
             :regions {:outer {:type    :parallel
                               :regions {:inner {:initial :s :states {:s {}}}}}}}))
        "a region cannot itself declare :type :parallel"))

  (testing "nested :type :parallel deeper in a region's state-tree is rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-parallel-nested-not-supported"
          (machines/make-machine-handler
            {:type    :parallel
             :regions {:r {:initial :compound
                           :states  {:compound {:type    :parallel
                                                :regions {:in {:initial :s
                                                               :states  {:s {}}}}}}}}})))))

;; ---- 11. :rf/machine-has-tag? sub works on parallel snapshots ----------

(deftest parallel-has-tag-sub
  (testing ":rf/machine-has-tag? returns true iff the union contains the tag"
    (let [m {:type    :parallel
             :data    {}
             :regions {:data {:initial :loading
                              :states  {:loading {:tags #{:data/loading}}}}
                       :form {:initial :neutral
                              :states  {:neutral {:tags #{:form/neutral}}}}}}]
      (rf/reg-machine :par/has-tag m)
      (rf/dispatch-sync [:par/has-tag [:no-op]])
      (is (= true  @(rf/subscribe [:rf/machine-has-tag? :par/has-tag :data/loading])))
      (is (= true  @(rf/subscribe [:rf/machine-has-tag? :par/has-tag :form/neutral])))
      (is (= false @(rf/subscribe [:rf/machine-has-tag? :par/has-tag :missing]))))))

;; ---- 12. snapshot stored at [:rf/machines <id>] like any other --------

(deftest parallel-snapshot-lives-at-rf-machines-id
  (testing "parallel-region snapshots are byte-compatible with single-machine storage"
    (let [m {:type    :parallel
             :data    {}
             :regions {:a {:initial :one :states {:one {}}}
                       :b {:initial :two :states {:two {}}}}}]
      (rf/reg-machine :par/storage m)
      (rf/dispatch-sync [:par/storage [:no-match]])
      (is (some? (snapshot :par/storage))
          "snapshot synthesised at [:rf/machines :par/storage]"))))

;; ---- 13. region-machine memoization (rf2-s83iu) ---------------------------

(deftest region-machine-result-is-memoised-per-machine
  (testing "region-machine returns identical-equal results across repeat calls for the same parent-machine"
    (let [m {:type    :parallel
             :data    {}
             :regions {:a {:initial :one :states {:one {}}}
                       :b {:initial :two :states {:two {}}}}}]
      (rf/reg-machine :par/cache m)
      (let [cached  (rf/machine-meta :par/cache)
            first-a (re-frame.machines.parallel/region-machine cached :a)
            again-a (re-frame.machines.parallel/region-machine cached :a)
            first-b (re-frame.machines.parallel/region-machine cached :b)]
        (is (identical? first-a again-a)
            "second region-machine call returns the cached spec object")
        (is (not (identical? first-a first-b))
            "different regions yield different objects")
        (is (= :a (:rf/region first-a)))
        (is (= :b (:rf/region first-b)))))))
