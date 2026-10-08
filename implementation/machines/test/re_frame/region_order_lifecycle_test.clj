(ns re-frame.region-order-lifecycle-test
  "`reg-machine` refuses a >8-region parallel machine that omits the explicit
  `:region-order` at registration, not at its first dispatch. The ordering
  itself is pinned in region_order_cljs_test.cljc."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest registered-parallel-without-region-order-rejected
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #":rf.error/machine-parallel-region-order-required"
        (rf/reg-machine :rord/bad
          {:type    :parallel
           :regions (into {} (map (fn [i] [(keyword (str "r" i)) {:initial :idle :states {:idle {}}}]))
                          (range 10))}))))
