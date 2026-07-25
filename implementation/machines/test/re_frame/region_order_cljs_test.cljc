(ns re-frame.region-order-cljs-test
  "Acceptance coverage for rf2-3fc89f.8 — parallel-region declaration order is
  an EXPLICIT registration contract (`:region-order`), normalised once, and is
  preserved across EVERY order-sensitive parallel operation once the region
  map crosses the PersistentArrayMap→PersistentHashMap threshold (>8 regions).

  Before the fix, a >8-region machine recovered order from `(keys (:regions
  …))` — hash order (e.g. r7,r6,r8,r2,r9,r3,r1,r0,r4,r5 in CLJ, a DIFFERENT
  order in CLJS). Every test here uses TEN regions (therefore a
  PersistentHashMap `:regions`), so it FAILS on the old code (the committed
  order was hash order) and passes on the fixed code (authored order r0..r9).

  Runs in both CLJ and CLJS (pure engine — no runtime/fixture), so it pins the
  CLJ/CLJS parity the fix guarantees. Covers literal AND computed `:regions`
  input, and asserts authored action-data, fx, cascade and spawn order, the
  birth entry cascade, the destroy exit cascade, and the root multi-target
  apply — plus the documented registration outcomes for missing/mismatched
  order and the ≤8 small-map convenience derivation.

  The `-cljs-test` suffix is what MAKES that parity claim true. Shadow's
  `:node-test` build selects on `cljs-test$`, so the original
  `-cljc-test` ns — chosen to advertise dual-target — matched neither
  lane's CLJS filter, and the CLJ/CLJS parity this file exists to pin had
  never once been checked on CLJS (rf2-lgozq)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.result :as result]))

;; ---- helpers --------------------------------------------------------------

(def ^:private r10
  "Ten region names in authored declaration order."
  (mapv #(keyword (str "r" %)) (range 10)))

(defn- boot
  "Fresh initial snapshot for a parallel machine spec."
  [m]
  (parallel/build-initial-snapshot m {:bootstrap-pending? false}))

(defn- go-machine
  "A parallel machine whose regions are exactly `order` (a vector of region
  names). Each region's `:on :go` appends its own id to shared [:data :order]
  and emits a `[:noted <id>]` fx. `:regions` / `:actions` are built via
  `into {}` so for 10 regions they are PersistentHashMaps (order lost) — the
  authored order lives ONLY in the explicit `:region-order` when
  `include-order?`."
  [order include-order?]
  (let [regions (into {} (map (fn [rn]
                                [rn {:initial :idle
                                     :states  {:idle {:on {:go {:target :idle
                                                                :action rn}}}}}]))
                      order)
        actions (into {} (map (fn [rn]
                                [rn (fn [{d :data}]
                                      {:data (update d :order (fnil conj []) rn)
                                       :fx   [[:noted rn]]})]))
                      order)]
    (cond-> {:type :parallel :data {:order []} :actions actions :regions regions}
      include-order? (assoc :region-order order))))

(defn- noted-fx [r]
  (filterv #(= :noted (first %)) (result/fx r)))

;; A TEN-region machine written as a MAP LITERAL — >8 entries, so the reader
;; produces a PersistentHashMap and authored order r0..r9 is gone at read time.
;; The explicit `:region-order` carries it. This is the "literal reg-machine*
;; input" leg of the acceptance matrix (the computed leg is `go-machine`).
(def ^:private literal-ten
  {:type         :parallel
   :data         {:order []}
   :region-order [:r0 :r1 :r2 :r3 :r4 :r5 :r6 :r7 :r8 :r9]
   :actions      {:r0 (fn [{d :data}] {:data (update d :order conj :r0)})
                  :r1 (fn [{d :data}] {:data (update d :order conj :r1)})
                  :r2 (fn [{d :data}] {:data (update d :order conj :r2)})
                  :r3 (fn [{d :data}] {:data (update d :order conj :r3)})
                  :r4 (fn [{d :data}] {:data (update d :order conj :r4)})
                  :r5 (fn [{d :data}] {:data (update d :order conj :r5)})
                  :r6 (fn [{d :data}] {:data (update d :order conj :r6)})
                  :r7 (fn [{d :data}] {:data (update d :order conj :r7)})
                  :r8 (fn [{d :data}] {:data (update d :order conj :r8)})
                  :r9 (fn [{d :data}] {:data (update d :order conj :r9)})}
   :regions      {:r0 {:initial :idle :states {:idle {:on {:go {:target :idle :action :r0}}}}}
                  :r1 {:initial :idle :states {:idle {:on {:go {:target :idle :action :r1}}}}}
                  :r2 {:initial :idle :states {:idle {:on {:go {:target :idle :action :r2}}}}}
                  :r3 {:initial :idle :states {:idle {:on {:go {:target :idle :action :r3}}}}}
                  :r4 {:initial :idle :states {:idle {:on {:go {:target :idle :action :r4}}}}}
                  :r5 {:initial :idle :states {:idle {:on {:go {:target :idle :action :r5}}}}}
                  :r6 {:initial :idle :states {:idle {:on {:go {:target :idle :action :r6}}}}}
                  :r7 {:initial :idle :states {:idle {:on {:go {:target :idle :action :r7}}}}}
                  :r8 {:initial :idle :states {:idle {:on {:go {:target :idle :action :r8}}}}}
                  :r9 {:initial :idle :states {:idle {:on {:go {:target :idle :action :r9}}}}}}})

;; ---- 1. action-data accumulation order (the core repro) -------------------

(deftest action-data-order-preserved-computed
  (testing ">8 regions (computed :regions): shared :data accumulates in
            DECLARATION order r0..r9, NOT :regions hash order"
    (let [m (go-machine r10 true)
          r (parallel/machine-transition m (boot m) [:go])]
      (is (result/ok? r))
      (is (instance? #?(:clj clojure.lang.PersistentHashMap
                        :cljs cljs.core/PersistentHashMap)
                     (:regions m))
          ":regions is a PersistentHashMap — the >8 threshold is crossed")
      (is (= r10 (get-in (result/snap r) [:data :order]))
          "committed action-data order is authored r0..r9"))))

(deftest action-data-order-preserved-literal
  (testing ">8 regions (LITERAL :regions map): shared :data accumulates in
            DECLARATION order r0..r9"
    (let [r (parallel/machine-transition literal-ten (boot literal-ten) [:go])]
      (is (result/ok? r))
      (is (= r10 (get-in (result/snap r) [:data :order]))))))

;; ---- 2. cascade order -----------------------------------------------------

(deftest cascade-region-order-preserved
  (testing ">8 regions: cascade steps are concatenated in declaration order"
    (let [m (go-machine r10 true)
          r (parallel/machine-transition m (boot m) [:go])]
      (is (result/ok? r))
      (is (= r10 (vec (distinct (keep :region (result/cascade r)))))
          "distinct region sequence across cascade steps is authored r0..r9"))))

;; ---- 3. fx order ----------------------------------------------------------

(deftest fx-order-preserved
  (testing ">8 regions: per-region fx accumulate in declaration order"
    (let [m (go-machine r10 true)
          r (parallel/machine-transition m (boot m) [:go])]
      (is (result/ok? r))
      (is (= (mapv (fn [rn] [:noted rn]) r10) (noted-fx r))
          "emitted [:noted rN] fx are ordered r0..r9"))))

;; ---- 4. spawn allocation order (at birth) ---------------------------------

(deftest spawn-allocation-order-preserved
  (testing ">8 regions: declarative :entry spawns allocate + emit in
            declaration order at birth"
    (let [order   r10
          regions (into {} (map (fn [rn]
                                  [rn {:initial :idle
                                       :states  {:idle {:spawn {:machine-id rn}}}}]))
                        order)
          m       {:type :parallel :data {} :region-order order :regions regions}
          r       (parallel/apply-initial-entry-cascade m (boot m))
          spawned (->> (result/fx r)
                       (filterv #(= :rf.machine/spawn (first %)))
                       (mapv #(:machine-id (second %))))]
      (is (result/ok? r))
      (is (= order spawned)
          "spawn fx are emitted in declaration order r0..r9"))))

;; ---- 5. birth entry-cascade order -----------------------------------------

(deftest birth-entry-cascade-order-preserved
  (testing ">8 regions: initial :entry actions fire in declaration order"
    (let [order   r10
          actions (into {} (map (fn [rn]
                                  [rn (fn [{d :data}]
                                        {:data (update d :birth (fnil conj []) rn)})]))
                        order)
          regions (into {} (map (fn [rn]
                                  [rn {:initial :idle
                                       :states  {:idle {:entry rn}}}]))
                        order)
          m       {:type :parallel :data {:birth []} :region-order order
                   :actions actions :regions regions}
          r       (parallel/apply-initial-entry-cascade m (boot m))]
      (is (result/ok? r))
      (is (= order (get-in (result/snap r) [:data :birth]))
          "birth entry-action order is authored r0..r9"))))

;; ---- 6. destroy exit-cascade order ----------------------------------------

(deftest destroy-exit-cascade-order-preserved
  (testing ">8 regions: active-configuration :exit actions fire in
            declaration order on destroy"
    (let [order   r10
          actions (into {} (map (fn [rn]
                                  [rn (fn [{d :data}]
                                        {:data (update d :exit (fnil conj []) rn)})]))
                        order)
          regions (into {} (map (fn [rn]
                                  [rn {:initial :idle
                                       :states  {:idle {:exit rn}}}]))
                        order)
          m       {:type :parallel :data {:exit []} :region-order order
                   :actions actions :regions regions}
          r       (parallel/run-active-exit-cascade m (boot m))]
      (is (result/ok? r))
      (is (= order (get-in (result/snap r) [:data :exit]))
          "destroy exit-action order is authored r0..r9"))))

;; ---- 7. root multi-target apply order -------------------------------------

(deftest root-multi-target-apply-order-preserved
  (testing ">8 regions: a root :on multi-region target applies in declaration
            order (:data accumulates via each targeted region's :entry)"
    (let [order   r10
          actions (into {} (map (fn [rn]
                                  [rn (fn [{d :data}]
                                        {:data (update d :entered (fnil conj []) rn)})]))
                        order)
          regions (into {} (map (fn [rn]
                                  [rn {:initial :one
                                       :states  {:one {}
                                                 :two {:entry rn}}}]))
                        order)
          ;; root :on fires the ancestor fallback (no region has an :on :go),
          ;; targeting EVERY region's :two — applied in declaration order.
          targets (mapv (fn [rn] [rn :two]) order)
          m       {:type :parallel :data {:entered []} :region-order order
                   :actions actions :regions regions
                   :on {:go {:target targets}}}
          r       (parallel/machine-transition m (boot m) [:go])]
      (is (result/ok? r))
      (is (= (into {} (map (fn [rn] [rn :two])) order)
             (:state (result/snap r)))
          "every targeted region moved to :two")
      (is (= order (get-in (result/snap r) [:data :entered]))
          "targeted-region :entry order is authored r0..r9"))))

;; ---- 8. selection semantics unchanged -------------------------------------

(deftest selection-unchanged-only-apply-order-varies
  (testing "reordering :region-order changes the APPLY order (data
            accumulation), NOT which regions are selected / the final state"
    (let [fwd (go-machine r10 true)
          rev-order (vec (reverse r10))
          rev (go-machine rev-order true)
          rf  (parallel/machine-transition fwd (boot fwd) [:go])
          rr  (parallel/machine-transition rev (boot rev) [:go])]
      (is (= r10 (get-in (result/snap rf) [:data :order])))
      (is (= rev-order (get-in (result/snap rr) [:data :order]))
          "apply order follows the declared :region-order")
      (is (= (:state (result/snap rf)) (:state (result/snap rr)))
          "the selected / committed state is IDENTICAL regardless of order")
      (is (= (set r10) (set (keep :region (result/cascade rf)))
             (set (keep :region (result/cascade rr))))
          "the SET of regions that fired is identical — selection is
           declaration-order-independent"))))

;; ---- 9. registration outcome: >8 map without :region-order rejected -------

(deftest missing-region-order-rejected
  (testing ">8 :regions map with NO :region-order throws the documented
            registration error (order is unrecoverable from a hash-map)"
    (is (thrown-with-msg?
          #?(:clj Exception :cljs js/Error)
          #":rf.error/machine-parallel-region-order-required"
          (let [m (go-machine r10 false)]
            (parallel/machine-transition m {:state {} :data {}} [:go]))))))

;; ---- 10. registration outcome: mismatched :region-order rejected ----------

(deftest mismatched-region-order-rejected
  (testing "an explicit :region-order that is not an exact permutation of the
            :regions keyset (missing / extra / duplicate) is rejected"
    (let [base (go-machine r10 false)]
      (testing "missing a region"
        (is (thrown-with-msg?
              #?(:clj Exception :cljs js/Error)
              #":rf.error/machine-parallel-region-order-mismatch"
              (parallel/machine-transition
                (assoc base :region-order (vec (butlast r10)))
                {:state {} :data {}} [:go]))))
      (testing "an extra (non-declared) region"
        (is (thrown-with-msg?
              #?(:clj Exception :cljs js/Error)
              #":rf.error/machine-parallel-region-order-mismatch"
              (parallel/machine-transition
                (assoc base :region-order (conj r10 :r99))
                {:state {} :data {}} [:go]))))
      (testing "a duplicate region"
        (is (thrown-with-msg?
              #?(:clj Exception :cljs js/Error)
              #":rf.error/machine-parallel-region-order-mismatch"
              (parallel/machine-transition
                (assoc base :region-order (assoc r10 9 :r0))  ; :r9 → dup :r0
                {:state {} :data {}} [:go])))))))

;; ---- 11. ≤8 small-map convenience derivation ------------------------------

(deftest small-array-map-order-derived
  (testing "a ≤8-region ARRAY-MAP :regions with NO :region-order derives the
            authored order from insertion order (order-preserving map)"
    ;; A three-region LITERAL is a PersistentArrayMap — key order IS authored
    ;; order in both CLJ and CLJS.
    (let [m {:type    :parallel
             :data    {:order []}
             :actions {:a (fn [{d :data}] {:data (update d :order conj :a)})
                       :b (fn [{d :data}] {:data (update d :order conj :b)})
                       :c (fn [{d :data}] {:data (update d :order conj :c)})}
             :regions {:a {:initial :idle :states {:idle {:on {:go {:target :idle :action :a}}}}}
                       :b {:initial :idle :states {:idle {:on {:go {:target :idle :action :b}}}}}
                       :c {:initial :idle :states {:idle {:on {:go {:target :idle :action :c}}}}}}}]
      (is (instance? #?(:clj clojure.lang.PersistentArrayMap
                        :cljs cljs.core/PersistentArrayMap)
                     (:regions m))
          "a 3-entry :regions literal is an order-preserving array-map")
      (is (= [:a :b :c] (parallel/region-order m))
          "region-order is derived from the array-map's insertion order")
      (let [r (parallel/machine-transition m (boot m) [:go])]
        (is (= [:a :b :c] (get-in (result/snap r) [:data :order])))))))

;; ---- 12. normalisation is idempotent --------------------------------------

(deftest normalise-region-order-idempotent
  (testing "re-normalising a canonical machine is a no-op"
    (let [m  (go-machine r10 true)
          m1 (parallel/normalise-region-order m)
          m2 (parallel/normalise-region-order m1)]
      (is (= r10 (:region-order m1)))
      (is (identical? m1 m2) "second normalise returns the same value"))))
