(ns re-frame.story.plan-network-cljs-test
  "Tests for the first-class `:network` world slot.

  Per `tools/story/spec/017-Testing-Story.md` §Network world. The plan
  compiler is a pure data →
  data fn, so every test runs host-free on the JVM and CLJS: variant
  bodies are supplied through an explicit `:lookup` map of RAW bodies.

  `:network` is the higher-level affordance for `:rf.http/managed`. The
  compiler keeps the route map at `[:world :network]` (the source of truth
  that feeds `:plan-hash` through `:world` + `explain`); each route's value
  is the response a stub transport gives. The runner installs the map on
  the variant frame as a route transport, so the fail-closed posture for
  unmatched routes is the transport's, enforced at run time, and is not
  pinned here.

  Named `-cljs-test` so the `:node-test` build's `cljs-test$` ns-regexp
  selects it; a `-test` name would run it on the JVM only."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.story.plan        :as rf.story.plan]
            [re-frame.story.fingerprint :as rf.story.fingerprint]
            [re-frame.story.schemas     :as rf.story.schemas]))

;; ---- helpers ------------------------------------------------------------

(defn- plan-of
  [target m]
  (rf.story.plan/variant-plan target {:lookup m}))

(def ^:private cart-route   [:get  "/api/cart"])
(def ^:private checkout-route [:post "/api/checkout"])

;; ===========================================================================
;; The route map rides the :world slot and nothing else
;; ===========================================================================

(deftest no-network-no-world-slot
  (testing "a variant without :network carries no network slot and no override"
    (let [m {:story.plain/v {:setup [[:dispatch [:a]]]}}
          p (plan-of :story.plain/v m)]
      (is (nil? (get-in p [:world :network])))
      (is (nil? (get-in p [:world :frame :fx-overrides])))
      (is (nil? (get-in p [:explain :network]))))))

(deftest network-adds-no-fx-override
  (testing ":network keeps its routes at [:world :network] and adds no
            :fx-overrides entry: the routes are answered beneath the real
            :rf.http/managed"
    (let [routes {cart-route {:body {:items []}}}
          p      (plan-of :story.net/v {:story.net/v {:network routes}})]
      (is (= routes (get-in p [:world :network])))
      (is (nil? (get-in p [:world :frame :fx-overrides]))))))

;; ===========================================================================
;; arg substitution inside :network response data
;; ===========================================================================

(deftest network-response-data-substitutes-args
  (testing "[:arg key] placeholders in :network response data substitute"
    (let [m {:story.session/v
             {:args    {:uid 42}
              :network {[:get "/api/session"] {:body {:user/id [:arg :uid]}}}}}
          p (plan-of :story.session/v m)]
      (is (= {:body {:user/id 42}}
             (get-in p [:world :network [:get "/api/session"]]))))))

;; ===========================================================================
;; :network vs explicit :fx-overrides conflict (predictable resolution)
;; ===========================================================================

(deftest network-and-managed-fx-override-conflict-fails
  (doseq [[label variant-id body fragments]
          [[":network + a direct :fx-overrides on :rf.http/managed is a hard error"
            :story.checkout/conflict
            {:network      {cart-route {:body {:items []}}}
             :fx-overrides {:rf.http/managed :some/other-stub}}
            {}]
           ["a COMPOSED FRAGMENT's :fx-overrides on :rf.http/managed is the same
             hard conflict as a direct author override"
            :story.checkout/compose-conflict
            {:network {cart-route {:body {:items []}}}
             :compose [:fragment.http/managed-override]}
            {:fragment.http/managed-override
             {:fx-overrides {:rf.http/managed :some/fragment-stub}}}]]]
    (testing label
      (let [data (try (rf.story.plan/variant-plan variant-id
                                                  {:lookup          {variant-id body}
                                                   :fragment-lookup fragments})
                      (catch #?(:clj Exception :cljs :default) e (ex-data e)))]
        (is (= {:rf.error/id :rf.error/story-network-fx-conflict
                :fx-id       :rf.http/managed
                :variant/id  variant-id}
               (select-keys data [:rf.error/id :fx-id :variant/id])))))))

(deftest network-and-non-managed-fx-override-coexist
  (testing ":network and an :fx-overrides on a DIFFERENT fx coexist"
    (let [m {:story.checkout/mixed-fx
             {:network      {cart-route {:body {:items []}}}
              :fx-overrides {:analytics/track :analytics/noop-stub}}}
          p (plan-of :story.checkout/mixed-fx m)]
      (is (= {:analytics/track :analytics/noop-stub}
             (get-in p [:world :frame :fx-overrides])))
      (is (= {cart-route {:body {:items []}}}
             (get-in p [:world :network]))))))

;; ===========================================================================
;; explain — per-route stubs visible
;; ===========================================================================

(deftest explain-shows-per-route-network-stubs
  (testing "explain surfaces the per-route stubs"
    (let [routes {cart-route     {:body {:items []}}
                  checkout-route {:status 409 :body {:error "sold out"}}}
          m {:story.checkout/explained {:network routes}}
          ex (rf.story.plan/explain :story.checkout/explained {:lookup m})]
      (is (= {:routes routes} (:network ex))
          "explain shows each route's response"))))

;; ===========================================================================
;; :network participates in :plan-hash (via the :world slot)
;; ===========================================================================

(deftest network-perturbs-plan-hash
  (testing "different per-route responses perturb the plan-hash (§Network stubs)"
    (let [base {:story.h/v {:network {cart-route {:body {:items []}}}}}
          alt  {:story.h/v {:network {cart-route {:body {:items [{:sku "A"}]}}}}}
          p1   (plan-of :story.h/v base)
          p2   (plan-of :story.h/v alt)]
      (is (not= (rf.story.fingerprint/plan-hash p1) (rf.story.fingerprint/plan-hash p2))
          "a semantic change to a route response changes the plan-hash"))))

;; ===========================================================================
;; :network inherits through :extends (world context flows down)
;; ===========================================================================

(deftest network-inherits-through-extends
  (testing ":network is world context — it inherits root→child (per-route merge)"
    (let [m {:story.n/parent
             {:network {cart-route {:body {:items []}}}}
             :story.n/child
             {:extends :story.n/parent
              :network {checkout-route {:status 409}}}}
          p (plan-of :story.n/child m)]
      (is (= {cart-route     {:body {:items []}}
              checkout-route {:status 409}}
             (get-in p [:world :network]))
          "both parent + child routes present (disjoint routes union)"))))

(deftest network-same-route-child-response-replaces-parent-through-extends
  (testing "a child flipping the SAME route to a failure REPLACES the parent's
            response. A route's value is one response, so the child wins per
            route — exactly as :compose resolves the same slot. A deep merge
            would compile a 409 carrying the parent's body."
    (let [m {:story.n/ok    {:network {cart-route {:body {:items [1]}}}}
             :story.n/fails {:extends :story.n/ok
                             :network {cart-route {:status 409}}}}
          p (plan-of :story.n/fails m)]
      (is (= {:status 409}
             (get-in p [:world :network cart-route]))
          "the child's response replaces the parent's wholesale"))))

;; ===========================================================================
;; Schema — the Variant schema accepts :network and rejects malformed shapes
;; ===========================================================================

(deftest network-schema-accepts-stub-transport-responses
  (are [routes] (nil? (rf.story.schemas/validate :variant {:network routes}))
    {cart-route {}}
    {cart-route {:status 200 :headers {"x-total" "3"} :body {:items []} :delay-ms 20}}
    {cart-route {:failure {:kind :rf.http/transport :message "offline"}}}
    {cart-route {:failure {:kind :rf.http/timeout}}}))

(deftest network-schema-rejects-malformed-routes
  (are [routes] (some? (rf.story.schemas/validate :variant {:network routes}))
    ;; a key no stub-transport response carries
    {cart-route {:reply {:ok 1}}}
    ;; a failure kind a transport cannot report — it is decided from a response
    {cart-route {:failure {:kind :rf.http/http-4xx}}}
    ;; a non-integer status
    {cart-route {:status "200"}}
    ;; a route key that is not a [method url] pair: a bare string, a bad method
    {"/api/cart" {:body 1}}
    {[:teleport "/api/cart"] {:body 1}}))
