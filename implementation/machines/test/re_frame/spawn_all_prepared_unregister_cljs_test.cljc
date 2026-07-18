(ns re-frame.spawn-all-prepared-unregister-cljs-test
  "An ADMITTED+prepared `:spawn-all` child CONSUMES its invoke's authoritative
  preflight verdict — `spawn-fx` must NOT re-run its child-local
  `unregistered-spawn-type?` registry recheck against an already-prepared child
  (rf2-v4oqd).

  `spawn-all-init-fx` (the FIRST fx in the entry vector) resolves, stamps,
  builds, and validates every declarative `:spawn-all` child EXACTLY ONCE and
  RETAINS the prepared result under the join slot's `:rf/prepared` scratch
  (rf2-ek435). But the per-child `:rf.machine/spawn` fx STILL consulted the
  CURRENT registry via `unregistered-spawn-type?` BEFORE reading its keyed
  prepared entry. A `:rf.machine.spawn-all/started` listener that UNREGISTERED
  the admitted child TYPE between the preflight and the install then flipped the
  already-admitted child to rejected: the per-child spawn emitted a SECOND
  `:rf.error/machine-spawn-unregistered-type` reject and installed NO snapshot —
  after the live child-bearing join had already been published. The result was
  the exact impossible half-live join (a join naming a child whose snapshot a
  second verdict omitted) the authoritative handoff exists to make impossible.

  The sibling `spawn_all_authoritative_preflight` suite (rf2-ek435) pins the
  RE-REGISTER seam (the type stays present, so the recheck passed through) and
  the all-valid validator cardinality. This suite pins the UNREGISTER seam that
  note called out as NOT covered — the recheck's fail-closed branch — on JVM +
  CLJS."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; Loading the machines facade registers `rf/reg-machine` + the reserved
   ;; machine fxs when this ns runs alone.
   [re-frame.machines]
   [re-frame.machines.test-support :as mtest]
   ;; Unregister a machine TYPE mid-drain (from the started-listener) by
   ;; dropping its `:event` registrar entry — the seam the recheck tripped on.
   [re-frame.registrar :as registrar]
   ;; The schemas artefact ships the registered-validator hot path the
   ;; `:where :machine-data` boundary routes through; the `.malli` adapter
   ;; publishes Malli's validate/explain into the late-bind table.
   [re-frame.schemas]
   [re-frame.schemas.malli]
   [re-frame.trace :as trace]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  mtest/trace-capture-fixture)

;; ---------------------------------------------------------------------------
;; Fixtures under test.
;; ---------------------------------------------------------------------------

(def ^:private plain-child
  "An unconstrained child — a real live actor once its snapshot installs."
  {:initial :running
   :data    {}
   :states  {:running {}}})

(defn- parent-over
  "A `:spawn-all` parent over `children` with join mode `:all`."
  [children]
  {:initial :idle
   :states
   {:idle    {:on {:start :forking}}
    :forking {:spawn-all {:children         children
                          :join             :all
                          :on-child-done    :sa/done
                          :on-child-error   :sa/failed
                          :on-all-complete  [:all/done]}
              :on {:all/done :ready}}
    :ready   {}}})

(defn- join-slot [parent-id]
  (get-in (mtest/runtime-db) [:rf.runtime/machines :spawned parent-id [:forking]]))

(defn- snap-of [actor-id]
  (get-in (mtest/runtime-db) [:rf.runtime/machines :snapshots actor-id]))

(defn- unregistered-rejects []
  (mtest/events-of :rf.error/machine-spawn-unregistered-type))

(defn- on-started-once
  "Register a synchronous trace listener that runs `f` exactly once, on the
  first `:rf.machine.spawn-all/started` emit (fired by `spawn-all-init-fx`
  AFTER it publishes the live join, BEFORE the per-child spawn fxs run). Returns
  an unregister thunk."
  [listener-id f]
  (let [fired (atom false)]
    (trace/register-listener!
      listener-id
      (fn [ev]
        (when (and (= :rf.machine.spawn-all/started (:operation ev))
                   (not @fired))
          (reset! fired true)
          (f))))
    #(trace/unregister-listener! listener-id)))

;; ===========================================================================
;; (1) THE BUG — a started-listener UNREGISTERS an admitted child TYPE between
;;     the preflight and the install. The child must STILL install from its
;;     prepared entry, with no duplicate reject and a fully-live join.
;; ===========================================================================

(deftest unregister-between-preflight-and-install-still-installs-the-admitted-child
  (testing "a :rf.machine.spawn-all/started listener that UNREGISTERS an admitted
            child TYPE cannot flip the already-prepared child into a rejected one:
            the per-child install consumes the prepared entry rather than
            re-consulting the (now-empty) registry, so the child installs its
            prepared snapshot, the join stays fully live, and NO duplicate
            :rf.error/machine-spawn-unregistered-type reject fires (the pre-fix
            recheck rejected the child + stranded a snapshotless half-live join)."
    (rf/reg-machine :sa/plain plain-child)
    (rf/reg-machine :sup/unreg (parent-over [{:id :a :machine-id :sa/plain}
                                             {:id :b :machine-id :sa/plain}]))
    (let [off (on-started-once ::unreg
                #(registrar/unregister! :event :sa/plain))]
      (try
        (rf/dispatch-sync [:sup/unreg [:start]])
        (finally (off))))
    (let [slot (join-slot :sup/unreg)]
      (is (contains? slot :children)
          "a LIVE child-bearing join was seeded (not the reject sentinel)")
      (is (= 2 (count (:children slot)))
          "the join names both admitted children")
      (is (not (contains? slot :rf/prepared))
          "every admitted child consumed + dropped its prepared scratch — the durable join retains none")
      (doseq [id (vals (:children slot))]
        (is (some? (snap-of id))
            (str "child " id " INSTALLED its prepared snapshot even though its TYPE was unregistered mid-drain"))
        (is (= :running (mtest/machine-state id))
            (str "child " id " installed the PREPARED spec verbatim (state :running), not a re-derivation"))))
    (is (empty? (unregistered-rejects))
        "NO :rf.error/machine-spawn-unregistered-type reject fired — the recheck was skipped for the prepared child")))

;; ===========================================================================
;; (2) Adversarial: unregister BOTH admitted children between preflight and
;;     install — every admitted child still installs, no reject fans.
;; ===========================================================================

(deftest unregister-all-children-between-preflight-and-install-installs-all
  (testing "when the started listener unregisters EVERY child TYPE, EVERY
            admitted child still installs from its prepared entry — the whole
            batch consumes its authoritative verdict; no reject, no partial install."
    (rf/reg-machine :sa/one plain-child)
    (rf/reg-machine :sa/two plain-child)
    (rf/reg-machine :sup/allunreg (parent-over [{:id :a :machine-id :sa/one}
                                                {:id :b :machine-id :sa/two}]))
    (let [off (on-started-once ::allunreg
                (fn []
                  (registrar/unregister! :event :sa/one)
                  (registrar/unregister! :event :sa/two)))]
      (try
        (rf/dispatch-sync [:sup/allunreg [:start]])
        (finally (off))))
    (let [slot (join-slot :sup/allunreg)]
      (is (contains? slot :children) "a live child-bearing join was seeded")
      (is (not (contains? slot :rf/prepared)) "all prepared scratch consumed")
      (doseq [id (vals (:children slot))]
        (is (some? (snap-of id))
            (str "child " id " installed despite both TYPEs being unregistered mid-drain"))))
    (is (empty? (unregistered-rejects))
        "no duplicate unregistered-type reject fired for either child")))

;; ===========================================================================
;; (3) Cardinality control — the [:schemas :data] validator runs EXACTLY ONCE
;;     per admitted child even when the TYPE is unregistered between the
;;     preflight and the install (the recheck, and any second validation, is
;;     skipped for the prepared child).
;; ===========================================================================

(deftest schema-validator-runs-once-even-when-type-unregistered-mid-drain
  (testing "a counting [:schemas :data] validator runs EXACTLY once for the
            attempt — the preflight validates it once and the per-child install
            consumes the prepared snapshot rather than re-validating — and the
            child still installs even though its TYPE was unregistered between
            the preflight and the install (the recheck's fail-closed branch is
            skipped for the prepared child)."
    (let [calls (atom 0)]
      (rf/reg-machine :sa/counted
                      {:initial :running
                       :data    {:n 1}
                       :schemas {:data [:fn (fn [v] (swap! calls inc) (pos-int? (:n v)))]}
                       :states  {:running {}}})
      (rf/reg-machine :sup/count1 (parent-over [{:id :c :machine-id :sa/counted}]))
      (let [off (on-started-once ::count1
                  #(registrar/unregister! :event :sa/counted))]
        (try
          (rf/dispatch-sync [:sup/count1 [:start]])
          (finally (off))))
      (is (some? (snap-of :sa/counted#1))
          "the child installed its prepared snapshot despite the mid-drain unregister")
      (is (= 1 @calls)
          "the [:schemas :data] validator ran EXACTLY once — the install consumed the prepared result, it did not re-validate")
      (is (empty? (unregistered-rejects))
          "no unregistered-type reject fired for the prepared child"))))
