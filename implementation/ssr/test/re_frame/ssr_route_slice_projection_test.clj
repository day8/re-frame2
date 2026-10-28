(ns re-frame.ssr-route-slice-projection-test
  "The hydration `:rf/runtime-db` projection redacts a route's classified
  `:query` / `:params` rather than shipping the durable `:current` slice raw.

  A route declares its classification projection-relative; at egress it is
  re-rooted under `[:rf.runtime/routing :current …]` onto the current slice
  (Spec 012 §Route data classification), which `project-routing-egress`
  walks. Here the slice is installed directly into the `:rf/default` frame
  and projected under it;
  `re-frame.ssr-routing-egress-production-test` drives a real route
  activation instead."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; Loading routing publishes the route classification hook and the
            ;; routing fxs; the reset fixture reloads it.
            [re-frame.routing]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private route-id :route/oauth-callback)

(defn- install-route-classification!
  "Register the route's declaration and make `runtime-db` the frame's own, so
  the frame classifies the slice it holds."
  [runtime-db]
  (rf/reg-route route-id {:sensitive [[:query :token]] :large [[:params :payload]]}
                "/oauth/callback")
  (rf.frame/swap-runtime-db! :rf/default (constantly runtime-db))
  runtime-db)

(deftest sensitive-route-query-redacted-in-hydration-projection
  ;; The large value rides whole (the hydration wire applies no size elision)
  ;; and `:pending-navigation` is not durable.
  (is (= {:rf.runtime/routing
          {:current {:route-id route-id
                     :query    {:token :rf/redacted :return-to "/dashboard"}
                     :params   {:payload "huge-callback-blob-value"}}}}
         (rf.ssr.payload-policy/project-runtime-db
           (install-route-classification!
             {:rf.runtime/routing
              {:current            {:route-id route-id
                                    :query    {:token     "secret-oauth-token"
                                               :return-to "/dashboard"}
                                    :params   {:payload "huge-callback-blob-value"}}
               :pending-navigation {:id "pn-1" :reason :can-leave}}})
           :rf/default))))

(deftest unclassified-route-slice-rides-verbatim
  (let [rt {:rf.runtime/routing {:current {:route-id :route/home
                                           :query    {:token "still-here"}}}}]
    (is (= rt (rf.ssr.payload-policy/project-runtime-db rt :rf/default)))))
