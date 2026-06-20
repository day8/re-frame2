(ns re-frame.resources-skeleton-cljs-test
  "Surface + wiring smoke tests for the Resources artefact (rf2-p10npe,
  Spec 016 §EP-0003).

  These tests lock the artefact's public surface and registration wiring —
  the load-time guarantees that the runtime behaviour tests then build on:

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
    8. the `:rf.resource/*` event family is registered (and carries
       framework-write authority);
    9. the late-bound routing accepted-key extension accepts `:resources`.

  Runtime BEHAVIOUR (entry transitions, work ledger, stale suppression,
  GC, invalidation, hydration) is exercised by the sibling runtime tests
  (`resources_runtime_cljs_test`, `resources_work_ledger_cljs_test`, …)."
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
  "A minimal, valid resource METADATA map — the REQUIRED metadata keys (Spec
  016 §Resource registration spec). The `:request` handler is the THIRD
  registration slot (rf2-wvh95f F1); see `valid-request`."
  []
  {:doc           "test resource"
   :scope         :rf.scope/global
   :params-schema [:map [:slug :string]]})

(def ^:private valid-request
  "The request handler for `valid-spec` — the THIRD reg-resource slot."
  (fn [_params _ctx]
    {:request {:method :get :url "/api/x"}}))

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
    (resources/reg-resource :test/article (valid-spec) valid-request)
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
                                  (dissoc (valid-spec) :scope)
                                  valid-request))))
  (testing "reg-resource with no :params-schema throws"
    (is (thrown-with-msg?
          js/Error #"invalid-resource-spec"
          (resources/reg-resource :test/no-params
                                  (dissoc (valid-spec) :params-schema)
                                  valid-request))))
  (testing "reg-resource with :request inside the metadata map throws (it is the
            THIRD slot, rf2-wvh95f F1)"
    (is (thrown-with-msg?
          js/Error #"invalid-resource-spec"
          (resources/reg-resource :test/no-request
                                  (assoc (valid-spec) :request valid-request)
                                  valid-request)))))

(deftest reserved-scope-namespace-typo-rejected-fail-closed
  ;; rf2-y7lcqy — a bare keyword in the framework-reserved :rf.scope/*
  ;; namespace that is NOT one of the closed enum (:rf.scope/global,
  ;; :rf.scope/from-caller) is a TYPO. It MUST be rejected loudly at
  ;; registration (fail-closed) rather than silently accepted as a literal
  ;; scope that would resolve to the wrong [:rf.scope/glabal] cache scope.
  (testing "a :rf.scope/* typo throws :rf.error/resource-missing-scope-policy"
    (is (thrown-with-msg?
          js/Error #"resource-missing-scope-policy"
          (resources/reg-resource :test/typo
                                  (assoc (valid-spec) :scope :rf.scope/glabal) valid-request)))
    (is (thrown-with-msg?
          js/Error #"resource-missing-scope-policy"
          (resources/reg-resource :test/typo2
                                  (assoc (valid-spec) :scope :rf.scope/sesssion) valid-request))))
  ;; The closed enum members stay valid.
  (testing ":rf.scope/global and :rf.scope/from-caller remain valid"
    (is (= :test/global
           (resources/reg-resource :test/global
                                   (assoc (valid-spec) :scope :rf.scope/global) valid-request)))
    (is (= :test/from-caller
           (resources/reg-resource :test/from-caller
                                   (assoc (valid-spec) :scope :rf.scope/from-caller) valid-request))))
  ;; An app-namespaced keyword is a legitimate literal scope — NOT in the
  ;; reserved :rf.scope/* namespace, so it is accepted unchanged.
  (testing "an app-namespaced keyword scope is accepted as a literal scope"
    (is (= :test/app-ns
           (resources/reg-resource :test/app-ns
                                   (assoc (valid-spec) :scope :my.app/whatever) valid-request))))
  ;; Data-value scopes (the legitimate data-value-resolver feature) stay
  ;; valid: a [:rf.scope/session {…}] tuple is a value, not a bare keyword,
  ;; and a map / string scope is likewise a literal data value.
  (testing "data-value scopes (tuple / map / string) remain valid"
    (is (= :test/tuple
           (resources/reg-resource :test/tuple
                                   (assoc (valid-spec)
                                          :scope [:rf.scope/session {:user-id "u-1"}])
                                   valid-request)))
    (is (= :test/map
           (resources/reg-resource :test/map
                                   (assoc (valid-spec) :scope {:tenant-id "acme"}) valid-request)))
    (is (= :test/string
           (resources/reg-resource :test/string
                                   (assoc (valid-spec) :scope "tenant-acme") valid-request))))
  ;; A fn resolver is valid.
  (testing "a fn resolver scope is accepted"
    (is (= :test/fn
           (resources/reg-resource :test/fn
                                   (assoc (valid-spec) :scope (fn [] :rf.scope/global)) valid-request))))))

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

(def ^:private resource-event-family
  "The CURRENT, complete `:rf.resource/*` + `:rf.resource.internal/*` event
  family the façade registers (re-frame.resources `reg-event` calls). Kept
  in lock-step with the façade registrations — when a resource event is
  added/removed there, this list moves with it so the smoke is never stale
  (rf2-l1a0s7). The `:rf.mutation/*` causal-write family is a SEPARATE
  surface (covered by the mutation suite), deliberately not enumerated here."
  [;; public, user-causable events
   :rf.resource/ensure
   :rf.resource/refetch
   :rf.resource/invalidate-tags
   :rf.resource/release-owner
   :rf.resource/clear-scope
   :rf.resource/remove
   ;; focus / reconnect revalidation events (host listeners dispatch these;
   ;; user code MUST NOT) — landed events the prior smoke omitted
   :rf.resource/window-focused
   :rf.resource/network-reconnected
   ;; framework-internal reply handlers (user code MUST NOT dispatch)
   :rf.resource.internal/succeeded
   :rf.resource.internal/failed
   :rf.resource.internal/aborted
   :rf.resource.internal/stale-fired      ;; landed; prior smoke omitted
   :rf.resource.internal/gc-fired
   :rf.resource.internal/stale-suppressed])

(deftest resource-events-registered
  (testing "the CURRENT :rf.resource/* event family is registered AND every
            member carries framework-write authority (rf2-l1a0s7)"
    (doseq [event-id resource-event-family]
      (let [handler (registrar/lookup :event event-id)]
        (is (some? handler)
            (str event-id " event should be registered"))
        (is (true? (:rf/framework-authority? handler))
            (str event-id " should carry framework-write authority"))))))

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
             {:params    [:map [:slug :string]]
              :resources [{:resource :test/article
                           :params   (fn [route] {:slug (get-in route [:params :slug])})}]} "/x/:slug")))
    (registrar/clear-kind! :route)))
