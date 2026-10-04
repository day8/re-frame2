(ns re-frame.story.plan-network-cljs-test
  "Tests for the first-class `:network` world slot.

  Per `tools/story/spec/017-Testing-Story.md` §Network world. The plan
  compiler is a pure data →
  data fn, so every test runs host-free on the JVM and CLJS: variant
  bodies are supplied through an explicit `:lookup` map of RAW bodies.

  `:network` is the higher-level affordance for `:rf.http/managed`. The
  compiler keeps the per-route reply data at `[:world :network]` (the
  source of truth that feeds `:plan-hash` through `:world` + `explain`)
  and lowers it to the managed-request stub fx — the variant
  frame overrides `:rf.http/managed` with
  `re-frame.http.test-support/install-managed-request-stubs!`'s stub fx
  id. These tests pin: mixed success/failure per route, the predictable
  `:network` vs explicit `:fx-overrides` conflict, explain visibility,
  plan-hash sensitivity, and the schema acceptance. The fail-closed posture
  for unmatched routes is the stub helper's, enforced at run time, and is
  not pinned here.

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
;; Lowering — :network keeps the route map AND lowers to the managed-stub fx
;; ===========================================================================

(deftest no-network-no-lowering
  (testing "a variant without :network carries no network slot and no managed override"
    (let [m {:story.plain/v {:setup [[:dispatch [:a]]]}}
          p (plan-of :story.plain/v m)]
      (is (nil? (get-in p [:world :network])))
      (is (nil? (get-in p [:world :frame :fx-overrides])))
      (is (nil? (get-in p [:explain :network]))))))

;; ===========================================================================
;; lower-network — the pure lowering primitive
;; ===========================================================================

;; ===========================================================================
;; arg substitution inside :network reply data
;; ===========================================================================

(deftest network-reply-data-substitutes-args
  (testing "[:arg key] placeholders in :network reply data substitute"
    (let [m {:story.session/v
             {:args    {:uid 42}
              :network {[:get "/api/session"] {:reply {:ok {:user/id [:arg :uid]}}}}}}
          p (plan-of :story.session/v m)]
      (is (= {:reply {:ok {:user/id 42}}}
             (get-in p [:world :network [:get "/api/session"]]))))))

(deftest network-missing-arg-fails
  (testing "a [:arg key] in :network referencing an undeclared arg fails"
    (let [m {:story.session/bad
             {:network {[:get "/api/session"] {:reply {:ok {:user/id [:arg :nope]}}}}}}]
      (is (= :rf.error/story-missing-arg
             (try (plan-of :story.session/bad m)
                  (catch #?(:clj Exception :cljs :default) e
                    (:rf.error/id (ex-data e)))))))))

;; ===========================================================================
;; :network vs explicit :fx-overrides conflict (predictable resolution)
;; ===========================================================================

(deftest network-and-managed-fx-override-conflict-fails
  (testing ":network + an explicit :fx-overrides on :rf.http/managed is a hard error"
    (let [m {:story.checkout/conflict
             {:network      {cart-route {:reply {:ok {:items []}}}}
              :fx-overrides {:rf.http/managed :some/other-stub}}}
          data (try (plan-of :story.checkout/conflict m)
                    (catch #?(:clj Exception :cljs :default) e (ex-data e)))]
      (is (= :rf.error/story-network-fx-conflict (:rf.error/id data)))
      (is (= :rf.http/managed (:fx-id data)))
      (is (= :story.checkout/conflict (:variant/id data))))))

(deftest network-and-non-managed-fx-override-coexist
  (testing ":network and an :fx-overrides on a DIFFERENT fx merge cleanly"
    (let [m {:story.checkout/mixed-fx
             {:network      {cart-route {:reply {:ok {:items []}}}}
              :fx-overrides {:analytics/track :analytics/noop-stub}}}
          p (plan-of :story.checkout/mixed-fx m)]
      (testing "both the managed-stub lowering and the author override survive"
        (is (= {:rf.http/managed :rf.http/managed-test-stub
                :analytics/track :analytics/noop-stub}
               (get-in p [:world :frame :fx-overrides])))))))

(deftest network-and-composed-fragment-managed-fx-override-conflict-fails
  (testing ":network + a COMPOSED FRAGMENT's :fx-overrides on :rf.http/managed
            is the same hard conflict as a direct author override.

            check-network-fx-conflict! runs against ctx-fx =
            (merge composed-fx (:fx-overrides ctx)) in variant-plan, so a
            fragment contributing :rf.http/managed (landing in composed-fx)
            collides with the variant's :network exactly as a direct override
            would. The DIRECT path is covered above; this exercises the
            compose branch so a future refactor of the strict-conflict merge
            cannot silently regress it."
    (let [fragments {:fragment.http/managed-override
                     {:fx-overrides {:rf.http/managed :some/fragment-stub}}}
          variants  {:story.checkout/compose-conflict
                     {:network {cart-route {:reply {:ok {:items []}}}}
                      :compose [:fragment.http/managed-override]}}
          compile   #(rf.story.plan/variant-plan :story.checkout/compose-conflict
                                        {:lookup          variants
                                         :fragment-lookup fragments})
          data      (try (compile)
                          (catch #?(:clj Exception :cljs :default) e (ex-data e)))]
      (is (= :rf.error/story-network-fx-conflict (:rf.error/id data)))
      (is (= :rf.http/managed (:fx-id data)))
      (is (= :story.checkout/compose-conflict (:variant/id data))))))

;; ===========================================================================
;; explain — per-route stubs + lowering visible
;; ===========================================================================

(deftest explain-shows-per-route-network-stubs
  (testing "explain surfaces the per-route stubs and the managed-stub lowering"
    (let [routes {cart-route     {:reply {:ok {:items []}}}
                  checkout-route {:reply {:failure {:kind :rf.http/http-4xx}}}}
          m {:story.checkout/explained {:network routes}}
          ex (rf.story.plan/explain :story.checkout/explained {:lookup m})]
      (is (= routes (get-in ex [:network :routes]))
          "explain shows the per-route reply data")
      (is (= {:rf.http/managed :rf.http/managed-test-stub}
             (get-in ex [:network :lowered-to]))
          "explain shows the managed-stub fx the routes lower to"))))

;; ===========================================================================
;; :network participates in :plan-hash (via the :world slot)
;; ===========================================================================

(deftest network-perturbs-plan-hash
  (testing "different per-route replies perturb the plan-hash (§Network stubs)"
    (let [base {:story.h/v {:network {cart-route {:reply {:ok {:items []}}}}}}
          alt  {:story.h/v {:network {cart-route {:reply {:ok {:items [{:sku "A"}]}}}}}}
          p1   (plan-of :story.h/v base)
          p2   (plan-of :story.h/v alt)]
      (is (not= (rf.story.fingerprint/plan-hash p1) (rf.story.fingerprint/plan-hash p2))
          "a semantic change to a route reply changes the plan-hash"))))

;; ===========================================================================
;; :network inherits through :extends (world context flows down)
;; ===========================================================================

(deftest network-inherits-through-extends
  (testing ":network is world context — it inherits root→child (per-route merge)"
    (let [m {:story.n/parent
             {:network {cart-route {:reply {:ok {:items []}}}}}
             :story.n/child
             {:extends :story.n/parent
              :network {checkout-route {:reply {:failure {:kind :rf.http/http-4xx}}}}}}
          p (plan-of :story.n/child m)]
      (testing "both parent + child routes present (disjoint routes union)"
        (is (= {cart-route     {:reply {:ok {:items []}}}
                checkout-route {:reply {:failure {:kind :rf.http/http-4xx}}}}
               (get-in p [:world :network]))))
      (testing "one managed-stub override covers the inherited + own routes"
        (is (= {:rf.http/managed :rf.http/managed-test-stub}
               (get-in p [:world :frame :fx-overrides])))))))

(deftest network-same-route-child-reply-replaces-parent-through-extends
  (testing "a child flipping the SAME route :ok -> :failure REPLACES the parent's
            reply. A route's value is one reply, so the child wins per
            route — exactly as :compose resolves the same slot. A deep merge
            would compile {:reply {:ok .. :failure ..}}, which the :reply
            schema rejects and the stub answers with :ok, losing the failure
            case."
    (let [failure {:kind :rf.http/http-4xx :status 409}
          m {:story.n/ok    {:network {cart-route {:reply {:ok {:items [1]}}}}}
             :story.n/fails {:extends :story.n/ok
                             :network {cart-route {:reply {:failure failure}}}}}
          p (plan-of :story.n/fails m)]
      (is (= {:reply {:failure failure}}
             (get-in p [:world :network cart-route]))
          "the child's reply replaces the parent's wholesale")
      (is (nil? (rf.story.schemas/validate :variant {:network (get-in p [:world :network])}))
          "the COMPILED route map satisfies the schema registration applies to bodies"))))

;; ===========================================================================
;; Schema — the Variant schema accepts :network and rejects malformed shapes
;; ===========================================================================

(deftest variant-schema-accepts-network
  (testing "the Variant schema accepts a well-formed :network slot"
    (is (nil? (rf.story.schemas/validate :variant
                {:network {cart-route     {:reply {:ok {:items []}}}
                           checkout-route {:reply {:failure {:kind :rf.http/http-4xx
                                                             :status 409}}}}})))))

(deftest network-schema-rejects-malformed-routes
  (are [routes] (some? (rf.story.schemas/validate :variant {:network routes}))
    ;; a route value with no :reply
    {cart-route {:status :ok}}
    ;; a :reply carrying BOTH :ok and :failure (they are exclusive)
    {cart-route {:reply {:ok 1 :failure {:kind :x}}}}
    ;; a route key that is not a [method url] pair: a bare string, a bad method
    {"/api/cart" {:reply {:ok 1}}}
    {[:teleport "/api/cart"] {:reply {:ok 1}}}
    ;; a :reply carrying neither :ok nor :failure
    {cart-route {:reply {}}}))
