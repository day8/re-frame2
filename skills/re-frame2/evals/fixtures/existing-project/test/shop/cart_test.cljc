(ns shop.cart-test
  (:require [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [shop.cart]
            #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest add-item-appends-to-the-cart
  (rf/dispatch-sync [:cart/add-item {:sku "A1" :price 10}])
  (rf/dispatch-sync [:cart/add-item {:sku "B2" :price 5}])
  (rf.test-support/assert-path-equals [:cart/items]
                         [{:sku "A1" :price 10} {:sku "B2" :price 5}]))

(deftest items-sub-reads-the-cart
  (is (= [{:sku "A1" :price 10}]
         (rf/compute-sub [:cart/items] {:cart/items [{:sku "A1" :price 10}]}))))
