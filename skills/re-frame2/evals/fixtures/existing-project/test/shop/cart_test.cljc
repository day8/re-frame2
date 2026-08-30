(ns shop.cart-test
  (:require [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]
            [shop.cart]
            #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])))

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest add-item-appends-to-the-cart
  (rf/dispatch-sync [:cart/add-item {:sku "A1" :price 10}])
  (rf/dispatch-sync [:cart/add-item {:sku "B2" :price 5}])
  (ts/assert-path-equals [:cart/items]
                         [{:sku "A1" :price 10} {:sku "B2" :price 5}]))

(deftest items-sub-reads-the-cart
  (is (= [{:sku "A1" :price 10}]
         (rf/compute-sub [:cart/items] {:cart/items [{:sku "A1" :price 10}]}))))
