(ns re-frame.routing-prefetch-test
  "EP-0037 R3 — resource-only intent prefetch: the PURE routing surfaces.

  The always-on prefetch-address structural gate (`address/prefetch-address-
  error`) and the pure `[:rf.route/prefetch …]` payload synthesis
  (`link/prefetch-payload`) the `rf/route-link` intent handlers + the substrate-
  neutral `:routing/prefetch-payload` seam are built on. The event's warm-plan
  behaviour (isolation, dedupe, reuse, planning failure) is proven end-to-end by
  the `ep-0037-r3-prefetch-*` conformance fixtures; the DOM intent arm (hover /
  focus / touch → dispatch) is proven by the CLJS route-link tests. Per Spec 012
  §Route-plan prefetch."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.routing.address :as address]
            [re-frame.routing.link :as link]))

(deftest prefetch-address-error-accepts-only-a-closed-route-address
  (testing "a well-formed named :rf/route-address passes the gate"
    (is (nil? (address/prefetch-address-error {:to :route/article})))
    (is (nil? (address/prefetch-address-error {:to :route/article :params {:slug "x"}})))
    (is (nil? (address/prefetch-address-error {:to :route/article :params {:slug "x"}
                                               :query {:tab "comments"} :fragment "reply"})))
    (is (nil? (address/prefetch-address-error {:to :route/article :fragment nil}))))

  (testing "a raw :url escape is NOT a RouteAddress — rejected as an unknown key"
    (is (= {:reason :unknown-keys :keys [:url]}
           (address/prefetch-address-error {:url "/articles/x"}))))

  (testing "a policy / edit key is rejected as unknown (prefetch takes address only)"
    (is (= {:reason :unknown-keys :keys [:replace?]}
           (address/prefetch-address-error {:to :route/article :replace? true})))
    (is (= {:reason :unknown-keys :keys [:query-merge]}
           (address/prefetch-address-error {:to :route/article :query-merge {:tab "x"}}))))

  (testing "a missing / non-keyword :to is rejected before planning"
    (is (= {:reason :missing-to :keys [:to]}
           (address/prefetch-address-error {:params {:slug "x"}})))
    (is (= {:reason :missing-to :keys [:to]}
           (address/prefetch-address-error {:to "route/article"}))))

  (testing "a structurally-wrong address value (non-map :params) is a bad address"
    (is (= {:reason :bad-address :keys []}
           (address/prefetch-address-error {:to :route/article :params [:not :a :map]}))))

  (testing "a non-map request rejects (never reaches planning)"
    (is (= {:reason :request-not-a-map :keys []}
           (address/prefetch-address-error nil)))))

(deftest prefetch-payload-synthesises-the-event-only-on-intent-opt-in
  (testing "a link that opts into :prefetch :intent yields the address-only event"
    (is (= [:rf.route/prefetch {:to :route/article :params {:slug "x"}}]
           (link/prefetch-payload {:to :route/article :params {:slug "x"}
                                   :prefetch :intent :class "title"})))
    (is (= [:rf.route/prefetch {:to :route/article :params {:slug "x"} :query {:tab "c"}}]
           (link/prefetch-payload {:to :route/article :params {:slug "x"}
                                   :query {:tab "c"} :prefetch :intent}))))

  (testing "the payload carries ONLY the address — no policy / DOM / behaviour keys leak"
    (is (= [:rf.route/prefetch {:to :route/article}]
           (link/prefetch-payload {:to :route/article :prefetch :intent
                                   :class "title" :on-click identity
                                   :on-mouse-enter identity}))))

  (testing "no opt-in (or any value other than :intent) yields nil — passive by default"
    (is (nil? (link/prefetch-payload {:to :route/article})))
    (is (nil? (link/prefetch-payload {:to :route/article :prefetch true})))
    (is (nil? (link/prefetch-payload {:to :route/article :prefetch :render})))))
