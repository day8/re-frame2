(ns re-frame.ssr-routing-egress-production-test
  "Route classification leaves the box in PRODUCTION through the hydration
  payload, so this namespace runs the whole chain — a real `reg-route` and
  `:rf.route/handle-url-change` activation, the per-frame registry,
  `project-runtime-db`, `build-payload` — and holds in dev AND under
  `-Dre-frame.debug=false` (the `jvm-ssr-prod-gate` job runs it there).

  `re-frame.ssr-route-slice-projection-test` pins the projector against a
  registry it installs by hand; this one also proves activation lowers the
  declarations, the half a debug gate could plausibly be added to."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; Loading routing publishes the route classification machinery and
            ;; the routing events; the reset fixture reloads it.
            [re-frame.routing]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private token-secret "secret-oauth-token-u2x6w")
(def ^:private blob-secret "huge-callback-blob-value-u2x6w")

(deftest the-hydration-payload-a-visitor-receives-carries-no-raw-route-secret
  (rf/reg-route :route/oauth-callback
                {:sensitive [[:query :token]]
                 :large     [[:query :payload]]
                 :query     [:map
                             [:token     :string]
                             [:payload   :string]
                             [:return-to :string]]}
                "/oauth/callback")
  (rf/dispatch-sync [:rf.route/handle-url-change
                     (str "/oauth/callback?token=" token-secret
                          "&payload=" blob-secret
                          "&return-to=/dashboard") {:rf.route/cause :link}])
  (let [payload (rf.ssr.payload-policy/build-payload
                  :rf/default {:public/page :callback} "h1"
                  {:version    1
                   :runtime-db (rf.ssr.payload-policy/project-runtime-db
                                 (rf.frame/frame-runtime-db-value :rf/default) :rf/default)})]
    ;; The large value rides whole (the hydration wire applies no size
    ;; elision); the unclassified sibling rides verbatim.
    (is (= {:route-id :route/oauth-callback
            :query    {:token :rf/redacted :payload blob-secret :return-to "/dashboard"}}
           (-> payload
               (get-in [:rf/runtime-db :rf.runtime/routing :current])
               (select-keys [:route-id :query]))))
    (is (not (.contains (pr-str payload) token-secret))
        "the blob the client receives carries no raw secret")))
