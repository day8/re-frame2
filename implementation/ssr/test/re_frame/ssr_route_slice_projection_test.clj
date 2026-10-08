(ns re-frame.ssr-route-slice-projection-test
  "The hydration `:rf/runtime-db` projection redacts a route's classified
  `:query` / `:params` rather than shipping the durable `:current` slice raw.

  A route declares its classification projection-relative; at activation it is
  re-rooted under `[:rf.runtime/routing :current …]` into the per-frame elision
  registry (Spec 012 §Route data classification), which
  `project-routing-egress` walks. Here the lowered registry is installed
  directly into the ambient `:rf/default` frame and projected with the
  one-arity `project-runtime-db`; `re-frame.ssr-routing-egress-production-test`
  drives a real route activation instead."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.elision :as rf.elision]
            ;; Loading routing publishes the route classification machinery
            ;; and the routing fxs; the reset fixture reloads it.
            [re-frame.routing]
            [re-frame.routing.classification :as rf.routing.classification]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private route-id :route/oauth-callback)

(defn- install-route-classification! []
  (let [lowered (rf.routing.classification/apply-route-classification
                  {}
                  (rf.routing.classification/validate+extract
                    route-id {:sensitive [[:query :token]] :large [[:params :payload]]}))]
    (rf.elision/swap-elision-slot! :rf/default (constantly (:rf.runtime/elision lowered)))))

(deftest sensitive-route-query-redacted-in-hydration-projection
  ;; The large value rides whole (the hydration wire applies no size elision)
  ;; and `:pending-navigation` is not durable.
  (install-route-classification!)
  (is (= {:rf.runtime/routing
          {:current {:route-id route-id
                     :query    {:token :rf/redacted :return-to "/dashboard"}
                     :params   {:payload "huge-callback-blob-value"}}}}
         (rf.ssr.payload-policy/project-runtime-db
           {:rf.runtime/routing
            {:current            {:route-id route-id
                                  :query    {:token     "secret-oauth-token"
                                             :return-to "/dashboard"}
                                  :params   {:payload "huge-callback-blob-value"}}
             :pending-navigation {:id "pn-1" :reason :can-leave}}}))))

(deftest unclassified-route-slice-rides-verbatim
  (let [rt {:rf.runtime/routing {:current {:route-id :route/home
                                           :query    {:token "still-here"}}}}]
    (is (= rt (rf.ssr.payload-policy/project-runtime-db rt)))))
