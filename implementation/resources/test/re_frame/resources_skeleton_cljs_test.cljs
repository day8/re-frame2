(ns re-frame.resources-skeleton-cljs-test
  "Skeleton smoke tests for the Resources artefact (rf2-p10npe, Spec 016
  §EP-0003 slice 2 — the artefact SKELETON).

  This slice ships the public surface + the wiring, NOT the runtime logic.
  These tests lock what the skeleton GUARANTEES:

    1. the artefact ns loads cleanly (the require itself is the smoke);
    2. `reg-resource` registers under the `:resource` registrar kind, and
       the registry introspection accessors read it back;
    3. the REQUIRED, fail-closed `:scope` policy is enforced at
       registration (`:rf.error/resource-missing-scope-policy`);
    4. the `:resource` registrar kind is in the core registrar's closed
       kind set, and `:query` is NOT;
    5. the feature probe (`:resources/reg-resource`) is published, so
       `(rf/feature-loaded? :resources)` is true;
    6. the public-API late-bind hooks are published;
    7. the passive `:rf.resource/*` subs are registered;
    8. the resource event handlers are registered (their skeleton bodies
       raise `:rf.error/resource-not-implemented` — a later slice fills
       them in);
    9. the late-bound routing accepted-key extension accepts `:resources`.

  Runtime BEHAVIOUR (entry transitions, work ledger, stale suppression,
  GC, invalidation, hydration) is out of scope here — those land with the
  runtime slices."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.features :as features]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.resources :as resources]
            ;; The route + ssr siblings carry the late-bound integration
            ;; publication; the façade transitively loads them, but require
            ;; them explicitly so a hostile load-order can't hide a miss.
            [re-frame.resources.route :as route]
            [re-frame.resources.registry :as registry]
            [re-frame.routing.registry :as routing-registry]))

(defn- valid-spec
  "A minimal, valid resource spec — the three REQUIRED keys (Spec 016
  §Resource registration spec)."
  []
  {:doc           "test resource"
   :scope         :rf.scope/global
   :params-schema [:map [:slug :string]]
   :request       (fn [_params _ctx]
                    {:request {:method :get :url "/api/x"}})})

(use-fixtures :each
  {:before (fn [] (registrar/clear-kind! :resource))
   :after  (fn [] (registrar/clear-kind! :resource))})

(deftest artefact-loads
  (testing "the resources façade ns loaded (the require is the smoke)"
    (is (fn? resources/reg-resource))
    (is (fn? resources/clear-resource))
    (is (fn? resources/resource-meta))
    (is (fn? resources/resources))))

(deftest reg-resource-registers-under-resource-kind
  (testing "reg-resource writes a :resource-kind registrar entry"
    (resources/reg-resource :test/article (valid-spec))
    (is (contains? (registrar/registrations :resource) :test/article))
    (is (= :test/article (first (resources/resource-ids))))
    (testing "resource-meta reads the spec back"
      (is (= "test resource" (:doc (resources/resource-meta :test/article))))
      (is (= :rf.scope/global (:scope (resources/resource-meta :test/article)))))
    (testing "clear-resource removes the entry"
      (resources/clear-resource :test/article)
      (is (not (contains? (registrar/registrations :resource) :test/article))))))

(deftest scope-policy-is-required-fail-closed
  (testing "reg-resource with no :scope throws :rf.error/resource-missing-scope-policy"
    (is (thrown-with-msg?
          js/Error #"resource-missing-scope-policy"
          (resources/reg-resource :test/no-scope
                                  (dissoc (valid-spec) :scope)))))
  (testing "reg-resource with no :params-schema throws"
    (is (thrown-with-msg?
          js/Error #"invalid-resource-spec"
          (resources/reg-resource :test/no-params
                                  (dissoc (valid-spec) :params-schema)))))
  (testing "reg-resource with no :request throws"
    (is (thrown-with-msg?
          js/Error #"invalid-resource-spec"
          (resources/reg-resource :test/no-request
                                  (dissoc (valid-spec) :request))))))

(deftest resource-kind-in-closed-set
  (testing ":resource is a valid registrar kind"
    (is (registrar/valid-kind? :resource))
    (is (contains? registrar/kinds :resource)))
  (testing ":query is NOT a registrar kind (deliberate — Spec 016)"
    (is (not (registrar/valid-kind? :query)))
    (is (not (contains? registrar/kinds :query)))))

(deftest feature-probe-published
  (testing "the :resources feature is loaded? (the probe key is published)"
    (is (some? (late-bind/get-fn :resources/reg-resource)))
    (is (true? (features/feature-loaded? :resources)))
    (is (= "day8/re-frame2-resources" (:maven (:resources (features/features)))))))

(deftest public-api-hooks-published
  (testing "every public-API late-bind hook resolves"
    (doseq [k [:resources/reg-resource :resources/clear-resource
               :resources/resource-meta :resources/resource-state
               :resources/resources]]
      (is (some? (late-bind/get-fn k)) (str k " should be published")))))

(deftest resource-subs-registered
  (testing "the passive :rf.resource/* sub family is registered"
    (doseq [sub-id [:rf.resource/state :rf.resource/data :rf.resource/status
                    :rf.resource/loading? :rf.resource/fetching?
                    :rf.resource/stale? :rf.resource/error
                    :rf.resource/refresh-error :rf.resource/has-data?
                    :rf.resource/previous-data]]
      (is (some? (registrar/lookup :sub sub-id))
          (str sub-id " sub should be registered")))))

(deftest resource-events-registered
  (testing "the :rf.resource/* event family is registered"
    (doseq [event-id [:rf.resource/ensure :rf.resource/refetch
                      :rf.resource/invalidate-tags :rf.resource/release-owner
                      :rf.resource/clear-scope :rf.resource/remove
                      :rf.resource.internal/succeeded :rf.resource.internal/failed
                      :rf.resource.internal/aborted :rf.resource.internal/gc-fired
                      :rf.resource.internal/stale-suppressed]]
      (is (some? (registrar/lookup :event event-id))
          (str event-id " event should be registered"))))
  (testing "every resource handler carries framework-write authority"
    (is (true? (:rf/framework-authority? (registrar/lookup :event :rf.resource/ensure))))))

(deftest late-bound-routing-accepts-resources-key
  (testing "the :routing/extra-route-keys hook publishes #{:resources}"
    (is (= #{:resources} ((late-bind/get-fn :routing/extra-route-keys)))))
  (testing "routing's accepted-key extension lets a route carry :resources"
    ;; routing.registry/reg-route validates bare metadata keys; with the
    ;; resources extension loaded, :resources is accepted (it would
    ;; otherwise throw :rf.error/invalid-route-metadata).
    (registrar/clear-kind! :route)
    (is (= :test/route
           (routing-registry/reg-route
             :test/route
             {:path      "/x/:slug"
              :params    [:map [:slug :string]]
              :resources [{:resource :test/article
                           :params   (fn [route] {:slug (get-in route [:params :slug])})}]})))
    (registrar/clear-kind! :route)))
