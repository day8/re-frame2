(ns re-frame.region-order-lifecycle-test
  "End-to-end registration + dispatch coverage for rf2-3fc89f.8 — a >8-region
  parallel machine registered via `reg-machine` (the real registration path,
  which normalises region order through `install-region-cache`) preserves
  authored declaration order across a live event dispatch, and rejects a >8
  region map that omits the explicit `:region-order`.

  The pure-engine matrix (action-data / fx / cascade / spawn / birth / destroy
  / root-multi-target / rejection / ≤8 convenience) lives in
  region_order_cljs_test.cljc; this file pins the same core invariant through
  the registrar + router so the registration-time normalisation is exercised."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private snapshot mtest/snapshot)

(def ^:private r10
  (mapv #(keyword (str "r" %)) (range 10)))

(defn- go-machine
  "A ten-region parallel machine (regions computed via `into {}` → a
  PersistentHashMap, order lost). Each region's `:on :go` appends its id to
  shared [:data :order]. `:region-order` carries the authored order when
  `include-order?`."
  [order include-order?]
  (let [regions (into {} (map (fn [rn]
                                [rn {:initial :idle
                                     :states  {:idle {:on {:go {:target :idle
                                                                :action rn}}}}}]))
                      order)
        actions (into {} (map (fn [rn]
                                [rn (fn [{d :data}]
                                      {:data (update d :order (fnil conj []) rn)})]))
                      order)]
    (cond-> {:type :parallel :data {:order []} :actions actions :regions regions}
      include-order? (assoc :region-order order))))

(deftest registered-parallel-preserves-declaration-order-end-to-end
  (testing ">8-region machine registered + dispatched preserves authored order"
    (rf/reg-machine :rord/ten (go-machine r10 true))
    (rf/dispatch-sync [:rord/ten [:go]])
    (let [s (snapshot :rord/ten)]
      (is (= (into #{} r10) (set (keys (:state s))))
          "all ten regions present in the snapshot state")
      (is (= r10 (get-in s [:data :order]))
          "shared :data accumulated in authored declaration order r0..r9"))))

(deftest registered-parallel-without-region-order-rejected
  (testing "registering a >8-region machine with NO :region-order is rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-parallel-region-order-required"
          (rf/reg-machine :rord/bad (go-machine r10 false))))))

(deftest registered-parallel-mismatched-region-order-rejected
  (testing "an explicit :region-order that is not an exact permutation is
            rejected at registration"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-parallel-region-order-mismatch"
          (rf/reg-machine :rord/bad2
            (assoc (go-machine r10 false)
                   :region-order (vec (butlast r10))))))))
