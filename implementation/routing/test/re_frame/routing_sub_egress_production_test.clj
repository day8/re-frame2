(ns re-frame.routing-sub-egress-production-test
  "Route subscription egress redacts sensitive values and elides large values in both postures."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.privacy :as rf.privacy]
            [re-frame.routing.sub-egress :as rf.routing.sub-egress]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(def ^:private token-secret "secret-oauth-token-u2x6w")
(def ^:private param-secret "topsecret-upload-key")

(defn- navigate-to-classified-route!
  "Register a classified route and navigate to it for real, so the
  classification reaches the frame's elision registry through activation's
  lowering rather than a hand-installed registry."
  []
  (rf/reg-route :route/oauth
                {:sensitive [[:query :token]]
                 :large     [[:query :payload]]
                 :query     [:map [:token :string] [:payload :string]]}
                "/oauth")
  (rf/dispatch-sync [:rf.route/handle-url-change
                     (str "/oauth?token=" token-secret "&payload=blobdata") {:rf.route/cause :link}]))

(defn- route-slice
  "The raw route slice: what `@(rf/subscribe [:rf/route])` returns in-process."
  []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          [:rf.runtime/routing :current]))

(deftest route-sub-egress-redacts-the-classified-slice-off-box
  (navigate-to-classified-route!)
  (let [wire (rf.elision/elide-wire-value (route-slice) {:query-v [:rf/route] :frame :rf/default})]
    (is (= [rf.privacy/redacted-sentinel true :route/oauth false]
           [(get-in wire [:query :token])
            (rf.elision/marker? (get-in wire [:query :payload]))
            (:route-id wire)
            (.contains (pr-str wire) token-secret)]))))

(deftest the-other-two-seed-table-entries-redact-too
  (testing ":rf.route/query and :rf.route/params return sub-projections of the
            slice, each re-seeded at its own storage position"
    (navigate-to-classified-route!)
    (is (= rf.privacy/redacted-sentinel
           (:token (rf.elision/elide-wire-value (:query (route-slice))
                                                {:query-v [:rf.route/query] :frame :rf/default}))))
    (rf/reg-route :route/upload {:sensitive [[:params :secret]]} "/upload/:secret")
    (rf/dispatch-sync [:rf.route/handle-url-change (str "/upload/" param-secret) {:rf.route/cause :link}])
    (is (= rf.privacy/redacted-sentinel
           (:secret (rf.elision/elide-wire-value (:params (route-slice))
                                                 {:query-v [:rf.route/params] :frame :rf/default}))))))

(deftest a-non-route-sub-is-untouched
  (testing "only the framework route read subs are projected; an app sub whose
            value has the same shape rides verbatim"
    (navigate-to-classified-route!)
    (is (= {:query {:token token-secret}}
           (rf.routing.sub-egress/project-route-sub-egress
             :some-app/sub {:query {:token token-secret}} {:frame :rf/default})))))

(deftest frameless-route-sub-egress-fails-closed-in-production
  (testing "a frame id that resolves to no live frame leaves no registry to walk
            under, so the slice redacts whole rather than riding verbatim"
    (navigate-to-classified-route!)
    (is (= rf.privacy/redacted-sentinel
           (rf.elision/elide-wire-value (route-slice) {:query-v [:rf/route] :frame :no/such-frame})))))
