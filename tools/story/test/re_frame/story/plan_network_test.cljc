(ns re-frame.story.plan-network-test
  "Tests for the first-class `:network` world slot (rf2-5x1wt.14).

  Per `tools/story/spec/017-Testing-Story.md` §Network world +
  `ai/findings/NewTestStory` §B2c. The plan compiler is a pure data →
  data fn, so every test runs host-free on the JVM and CLJS: variant
  bodies are supplied through an explicit `:lookup` map of RAW bodies.

  `:network` is the higher-level affordance for `:rf.http/managed`. The
  compiler keeps the per-route reply data at `[:world :network]` (the
  source of truth that feeds `:plan-hash` through `:world` + `explain`)
  and lowers it to the existing managed-request stub fx — the variant
  frame overrides `:rf.http/managed` with
  `re-frame.http.test-support/install-managed-request-stubs!`'s stub fx
  id. These tests pin: mixed success/failure per route, the fail-closed
  posture for unmatched routes (documented; enforced by the helper at run
  time), the predictable `:network` vs explicit `:fx-overrides` conflict,
  explain visibility, plan-hash sensitivity, and the schema acceptance."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [re-frame.story.plan        :as plan]
            [re-frame.story.fingerprint :as fp]
            [re-frame.story.schemas     :as schemas]))

;; ---- helpers ------------------------------------------------------------

(defn- plan-of
  [target m]
  (plan/variant-plan target {:lookup m}))

(def ^:private cart-route   [:get  "/api/cart"])
(def ^:private checkout-route [:post "/api/checkout"])

;; ===========================================================================
;; Lowering — :network keeps the route map AND lowers to the managed-stub fx
;; ===========================================================================

(deftest network-routes-preserved-at-world-network
  (testing ":network is preserved verbatim at [:world :network] (source of truth)"
    (let [routes {cart-route     {:reply {:ok {:items []}}}
                  checkout-route {:reply {:failure {:kind :rf.http/http-4xx
                                                    :status 409}}}}
          m {:story.checkout/mixed {:network routes}}
          p (plan-of :story.checkout/mixed m)]
      (is (= routes (get-in p [:world :network]))))))

(deftest network-lowers-to-managed-stub-fx-override
  (testing ":network lowers to a :rf.http/managed override on the frame"
    (let [m {:story.checkout/mixed
             {:network {cart-route {:reply {:ok {:items []}}}}}}
          p (plan-of :story.checkout/mixed m)]
      (testing "the frame's :fx-overrides redirects :rf.http/managed to the stub fx"
        (is (= {:rf.http/managed :rf.http/managed-test-stub}
               (get-in p [:world :frame :fx-overrides]))))
      (testing "the stub fx id matches the http-test-support helper's return"
        (is (= :rf.http/managed-test-stub plan/managed-stub-fx-id))
        (is (= :rf.http/managed plan/managed-fx-id))))))

(deftest one-route-succeeds-one-fails-in-one-variant
  (testing "mixed success/failure routes coexist in one variant (the §B2c case)"
    (let [routes {cart-route     {:reply {:ok {:items [{:sku "A"}]}}}
                  checkout-route {:reply {:failure {:kind :rf.http/http-5xx
                                                    :status 503}}}}
          m {:story.checkout/flaky {:network routes}}
          p (plan-of :story.checkout/flaky m)]
      (is (= {:reply {:ok {:items [{:sku "A"}]}}}
             (get-in p [:world :network cart-route])))
      (is (= {:reply {:failure {:kind :rf.http/http-5xx :status 503}}}
             (get-in p [:world :network checkout-route])))
      (testing "both routes route through the one managed-stub override"
        (is (= {:rf.http/managed :rf.http/managed-test-stub}
               (get-in p [:world :frame :fx-overrides])))))))

(deftest no-network-no-lowering
  (testing "a variant without :network carries no network slot and no managed override"
    (let [m {:story.plain/v {:setup [[:dispatch [:a]]]}}
          p (plan-of :story.plain/v m)]
      (is (nil? (get-in p [:world :network])))
      (is (nil? (get-in p [:world :frame :fx-overrides])))
      (is (nil? (get-in p [:explain :network]))))))

(deftest empty-network-map-is-a-noop
  (testing "an empty :network map lowers to nothing (no override, no slot)"
    (let [m {:story.plain/empty {:network {}}}
          p (plan-of :story.plain/empty m)]
      (is (nil? (get-in p [:world :network])))
      (is (nil? (get-in p [:world :frame :fx-overrides]))))))

;; ===========================================================================
;; lower-network — the pure lowering primitive
;; ===========================================================================

(deftest lower-network-unit
  (testing "lower-network keeps the routes and derives the managed override"
    (let [routes {cart-route {:reply {:ok 1}}}]
      (is (= {:network      routes
              :fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}
             (plan/lower-network routes))))
    (testing "nil for empty / nil input"
      (is (nil? (plan/lower-network {})))
      (is (nil? (plan/lower-network nil))))))

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
              :fx-overrides {:rf.http/managed :some/other-stub}}}]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-network-fx-conflict"
            (plan-of :story.checkout/conflict m)))
      (let [data (try (plan-of :story.checkout/conflict m)
                      (catch #?(:clj Exception :cljs :default) e (ex-data e)))]
        (is (= :rf.error/story-network-fx-conflict (:rf.error/id data)))
        (is (= :rf.http/managed (:fx-id data)))
        (is (= :story.checkout/conflict (:variant/id data)))))))

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
            is the same hard conflict as a direct author override (rf2-x0t0n).

            check-network-fx-conflict! runs against ctx-fx =
            (merge composed-fx (:fx-overrides ctx)) (plan.cljc:1238/1250), so a
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
          compile   #(plan/variant-plan :story.checkout/compose-conflict
                                        {:lookup          variants
                                         :fragment-lookup fragments})]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-network-fx-conflict"
            (compile)))
      (let [data (try (compile)
                      (catch #?(:clj Exception :cljs :default) e (ex-data e)))]
        (is (= :rf.error/story-network-fx-conflict (:rf.error/id data)))
        (is (= :rf.http/managed (:fx-id data)))
        (is (= :story.checkout/compose-conflict (:variant/id data)))))))

;; ===========================================================================
;; explain — per-route stubs + lowering visible
;; ===========================================================================

(deftest explain-shows-per-route-network-stubs
  (testing "explain surfaces the per-route stubs and the managed-stub lowering"
    (let [routes {cart-route     {:reply {:ok {:items []}}}
                  checkout-route {:reply {:failure {:kind :rf.http/http-4xx}}}}
          m {:story.checkout/explained {:network routes}}
          ex (plan/explain :story.checkout/explained {:lookup m})]
      (is (= routes (get-in ex [:network :routes]))
          "explain shows the per-route reply data")
      (is (= {:rf.http/managed :rf.http/managed-test-stub}
             (get-in ex [:network :lowered-to]))
          "explain shows the managed-stub fx the routes lower to"))))

(deftest explain-network-absent-when-no-network
  (testing "explain has no :network slot for a variant without :network"
    (let [m {:story.plain/v {:setup [[:dispatch [:a]]]}}
          ex (plan/explain :story.plain/v {:lookup m})]
      (is (nil? (:network ex))))))

;; ===========================================================================
;; :network participates in :plan-hash (via the :world slot)
;; ===========================================================================

(deftest network-perturbs-plan-hash
  (testing "different per-route replies perturb the plan-hash (§Network stubs)"
    (let [base {:story.h/v {:network {cart-route {:reply {:ok {:items []}}}}}}
          alt  {:story.h/v {:network {cart-route {:reply {:ok {:items [{:sku "A"}]}}}}}}
          p1   (plan-of :story.h/v base)
          p2   (plan-of :story.h/v alt)]
      (is (not= (fp/plan-hash p1) (fp/plan-hash p2))
          "a semantic change to a route reply changes the plan-hash"))))

(deftest network-failure-kind-perturbs-plan-hash
  (testing "a different failure :kind on a route perturbs the plan-hash"
    (let [p4xx (plan-of :story.h/v
                        {:story.h/v {:network {checkout-route {:reply {:failure {:kind :rf.http/http-4xx}}}}}})
          p5xx (plan-of :story.h/v
                        {:story.h/v {:network {checkout-route {:reply {:failure {:kind :rf.http/http-5xx}}}}}})]
      (is (not= (fp/plan-hash p4xx) (fp/plan-hash p5xx))))))

;; ===========================================================================
;; :network inherits through :extends (world context flows down)
;; ===========================================================================

(deftest network-inherits-through-extends
  (testing ":network is world context — it inherits root→child (deep-merge)"
    (let [m {:story.n/parent
             {:network {cart-route {:reply {:ok {:items []}}}}}
             :story.n/child
             {:extends :story.n/parent
              :network {checkout-route {:reply {:failure {:kind :rf.http/http-4xx}}}}}}
          p (plan-of :story.n/child m)]
      (testing "both parent + child routes present (deep-merge of the world map)"
        (is (= {:reply {:ok {:items []}}}
               (get-in p [:world :network cart-route])))
        (is (= {:reply {:failure {:kind :rf.http/http-4xx}}}
               (get-in p [:world :network checkout-route]))))
      (testing "one managed-stub override covers the inherited + own routes"
        (is (= {:rf.http/managed :rf.http/managed-test-stub}
               (get-in p [:world :frame :fx-overrides])))))))

;; ===========================================================================
;; Schema — the Variant schema accepts :network and rejects malformed shapes
;; ===========================================================================

(deftest variant-schema-accepts-network
  (testing "the Variant schema accepts a well-formed :network slot"
    (is (nil? (schemas/validate :variant
                {:network {cart-route     {:reply {:ok {:items []}}}
                           checkout-route {:reply {:failure {:kind :rf.http/http-4xx
                                                             :status 409}}}}})))))

(deftest network-schema-rejects-missing-reply
  (testing "a route value with no :reply is rejected"
    (is (some? (schemas/validate :variant
                 {:network {cart-route {:status :ok}}})))))

(deftest network-schema-rejects-both-ok-and-failure
  (testing "a :reply carrying BOTH :ok and :failure is rejected (xor)"
    (is (some? (schemas/validate :variant
                 {:network {cart-route {:reply {:ok 1 :failure {:kind :x}}}}})))))

(deftest network-schema-rejects-bad-route-key
  (testing "a route key that is not a [method url] pair is rejected"
    (is (some? (schemas/validate :variant
                 {:network {"/api/cart" {:reply {:ok 1}}}})))   ; bare string key
    (is (some? (schemas/validate :variant
                 {:network {[:teleport "/api/cart"] {:reply {:ok 1}}}}))))) ; bad method

(deftest network-spec-is-malli-valid
  (testing "NetworkSpec is a well-formed Malli schema"
    (is (m/validate schemas/NetworkSpec
                    {cart-route {:reply {:ok {:items []}}}}))
    (is (not (m/validate schemas/NetworkSpec
                         {cart-route {:reply {}}})))))
