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
            [re-frame.features :as rf.features]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.resources :as rf.resources]
            ;; The route + ssr siblings carry the late-bound integration
            ;; publication; the façade transitively loads them, but require
            ;; them explicitly so a hostile load-order can't hide a miss.
            [re-frame.resources.route :as rf.resources.route]
            [re-frame.resources.registry :as rf.resources.registry]
            [re-frame.routing.registry :as rf.routing.registry]))

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
  {:before (fn [] (rf.registrar/clear-kind! :resource))
   :after  (fn [] (rf.registrar/clear-kind! :resource))})

(deftest artefact-loads
  (testing "the resources façade ns loaded (the require is the smoke)"
    (is (fn? rf.resources/reg-resource))
    (is (fn? rf.resources/clear-resource))
    (is (fn? rf.resources/resource-meta))
    (is (fn? rf.resources/resources))))

(deftest reg-resource-registers-under-resource-kind
  (testing "reg-resource writes a :resource-kind registrar entry"
    (rf.resources/reg-resource :test/article (valid-spec) valid-request)
    (is (contains? (rf.registrar/registrations :resource) :test/article))
    (is (= :test/article (first (rf.resources/resource-ids))))
    (testing "resource-meta reads the spec back"
      (is (= "test resource" (:doc (rf.resources/resource-meta :test/article))))
      (is (= :rf.scope/global (:scope (rf.resources/resource-meta :test/article)))))
    (testing "clear-resource removes the entry"
      (rf.resources/clear-resource :test/article)
      (is (not (contains? (rf.registrar/registrations :resource) :test/article))))))

(deftest gc-after-ms-normalizes-at-registration
  ;; rf2-bbpu11 (Option A) — `:gc-after-ms` is normalized AT REGISTRATION so
  ;; `resource-meta` (and every downstream `positive-or-nil` read site) sees
  ;; exactly one of: the finite framework default, the auditable `:never`
  ;; opt-out, the caller's own positive number, or a loud registration error.
  ;; Never today's silent "any non-number becomes nil".
  (testing "absent :gc-after-ms normalizes to the finite framework default (300000ms)"
    (rf.resources/reg-resource :test/gc-absent (valid-spec) valid-request)
    (is (= 300000 (:gc-after-ms (rf.resources/resource-meta :test/gc-absent))))
    (is (= 300000 rf.resources.registry/default-gc-after-ms)))
  (testing ":gc-after-ms :never is stored verbatim — the explicit, auditable
            opt-out for intentional unowned-entry pinning, distinct from an
            accidental nil"
    (rf.resources/reg-resource :test/gc-never
                             (assoc (valid-spec) :gc-after-ms :never)
                             valid-request)
    (is (= :never (:gc-after-ms (rf.resources/resource-meta :test/gc-never)))))
  (testing "a positive :gc-after-ms is stored unchanged"
    (rf.resources/reg-resource :test/gc-positive
                             (assoc (valid-spec) :gc-after-ms 45000)
                             valid-request)
    (is (= 45000 (:gc-after-ms (rf.resources/resource-meta :test/gc-positive)))))
  (testing "a bad :gc-after-ms (zero, negative, a string, an explicit nil, or
            any keyword other than :never) throws :rf.error/resource-bad-spec
            rather than silently disarming GC"
    (doseq [bad [0 -1 "5min" :neverr nil]]
      (is (thrown-with-msg?
            js/Error #"resource-bad-spec"
            (rf.resources/reg-resource :test/gc-bad
                                    (assoc (valid-spec) :gc-after-ms bad)
                                    valid-request))
          (str "bad :gc-after-ms value " (pr-str bad) " must throw")))))

(deftest scope-policy-is-required-fail-closed
  (testing "reg-resource with no :scope throws :rf.error/resource-missing-scope-policy"
    (is (thrown-with-msg?
          js/Error #"resource-missing-scope-policy"
          (rf.resources/reg-resource :test/no-scope
                                  (dissoc (valid-spec) :scope)
                                  valid-request))))
  (testing "reg-resource with no :params-schema throws"
    (is (thrown-with-msg?
          js/Error #"resource-bad-spec"
          (rf.resources/reg-resource :test/no-params
                                  (dissoc (valid-spec) :params-schema)
                                  valid-request))))
  (testing "reg-resource with :request inside the metadata map throws (it is the
            THIRD slot, rf2-wvh95f F1)"
    (is (thrown-with-msg?
          js/Error #"resource-bad-spec"
          (rf.resources/reg-resource :test/no-request
                                  (assoc (valid-spec) :request valid-request)
                                  valid-request)))))

(deftest reg-resource-rejects-non-map-metadata
  ;; rf2-t65lqt — the metadata MIDDLE slot must be a map BEFORE reconstruction.
  ;; A non-map metadata (vector / string / nil) must surface the canonical
  ;; :rf.error/resource-bad-spec naming the resource, NOT a raw host
  ;; IllegalArgumentException ("Key must be integer") from the `:request`
  ;; `assoc`. Mirrors reg-route's `reg-route-rejects-non-map-metadata`.
  (testing "a vector metadata is rejected with the canonical error id"
    (let [ex (try (rf.resources/reg-resource :test/bad-vec [] valid-request)
                  nil
                  (catch :default e e))]
      (is (some? ex) "a non-map metadata must throw, not silently mis-register")
      (is (= :rf.error/resource-bad-spec (:rf.error/id (ex-data ex)))
          "non-map metadata surfaces the canonical resource registration error")
      (is (= [] (:value (ex-data ex)))
          "the rejected non-map value rides the :value ex-data slot")))
  (testing "a string metadata is rejected with the canonical error id"
    (is (thrown-with-msg?
          js/Error #"resource-bad-spec"
          (rf.resources/reg-resource :test/bad-str "nope" valid-request))))
  (testing "a nil metadata is rejected with the canonical error id"
    (is (thrown-with-msg?
          js/Error #"resource-bad-spec"
          (rf.resources/reg-resource :test/bad-nil nil valid-request)))))

(defn- defn-request
  "A `defn`'d handler — the shape `#'defn-request` below takes a Var of."
  [_params _ctx]
  {:request {:method :get :url "/api/defn"}})

(deftest reg-resource-rejects-non-callable-request
  ;; rf2-76md — the THIRD slot is the resource's HANDLER, and the ensure path
  ;; invokes it as `((:request spec) params ctx)`. Presence (`contains?`) was
  ;; the only gate, so a non-callable value registered cleanly, stayed
  ;; introspectable, and failed at the FIRST read instead of at the mistake.
  ;; The displaced failure has TWO distinct shapes, and the second is the
  ;; dangerous one:
  ;;   * 42 / "nope"  -> raw host cast error, `ex-data` nil, naming neither
  ;;                     the resource nor its definition site;
  ;;   * :kw / {:a 1} -> `ifn?`, so it is INVOKED happily and returns nil as
  ;;                     the 2-arity not-found default => a SILENT nil request.
  (testing "the two rejected classes are genuinely distinct (discriminator —
            without this row the suite could pass while the silent class
            regressed, since only the loud class throws on its own)"
    (is (and (not (fn? 42)) (not (ifn? 42)))
        "a number is not invokable at all — the LOUD host-cast class")
    (is (and (not (fn? :kw)) (ifn? :kw))
        "a keyword IS ifn? — the SILENT class a bare `ifn?` gate would admit")
    (is (nil? (:kw {:slug "s"} nil))
        "and invoking it 2-arity yields nil, which is exactly why the gate is
         not `ifn?`"))
  (testing "every non-callable :request is rejected AT REGISTRATION with the
            canonical structured error, not a downstream host throw"
    (doseq [bad [42 "nope" :kw {:a 1} #{:a} [:a] nil]]
      (let [ex (try (rf.resources/reg-resource :test/nonfn-request (valid-spec) bad)
                    nil
                    (catch :default e e))
            d  (ex-data ex)]
        (is (some? ex)
            (str "a non-callable :request " (pr-str bad) " must throw at "
                 "registration, not register and fail at the first read"))
        (is (= :rf.error/resource-bad-spec (:rf.error/id d))
            (str (pr-str bad) " surfaces the canonical registration error id"))
        (is (= :fix-registration (:recovery d))
            (str (pr-str bad) " carries the :fix-registration recovery"))
        (is (= :test/nonfn-request (:resource-id d))
            (str (pr-str bad) " names the offending resource in ex-data"))
        (is (= bad (:value d))
            (str (pr-str bad) " rides the :value ex-data slot"))
        (is (nil? (rf.resources/resource-meta :test/nonfn-request))
            (str "a rejected " (pr-str bad) " is NOT introspectable — the "
                 "rejection precedes registry mutation")))))
  (testing "OVER-REJECTION GUARD — every legitimate handler shape still
            registers unchanged. This half is the one that protects working
            code: the gate is `fn? or var?`, deliberately not bare `fn?`,
            because `#'my-fetch` (the idiomatic hot-reload / REPL-redefinition
            indirection) invokes fine on both hosts but is NOT `fn?` on the
            JVM — `clojure.lang.Var` implements `IFn` but not `Fn`. This is a
            .cljs suite, so the row below runs on the host where CLJS `Var`
            DOES list `Fn`; the JVM half of the asymmetry is pinned by the
            sibling .cljc mutation suite, which runs on both."
    (doseq [[label good] [["inline fn"      (fn [_p _c] {:request {:url "/i"}})]
                          ["defn'd fn"      defn-request]
                          ["Var of a defn"  #'defn-request]
                          ["partial"        (partial (fn [_x _p _c] {:request {:url "/p"}}) 1)]
                          ["comp"           (comp identity (fn [_p _c] {:request {:url "/c"}}))]
                          ["memoized fn"    (memoize (fn [_p _c] {:request {:url "/m"}}))]
                          ["fn with meta"   (with-meta (fn [_p _c] {:request {:url "/w"}}) {:tag 1})]]]
      (is (= :test/good-request
             (rf.resources/reg-resource :test/good-request (valid-spec) good))
          (str label " must still register — the gate must not reject working code"))
      (is (some? (rf.resources/resource-meta :test/good-request))
          (str label " is introspectable after registration"))
      (rf.resources/clear-resource :test/good-request))
    (is (var? #'defn-request)
        "control: `#'defn-request` really is a Var, so the Var row above is
         not vacuously just another ordinary fn")
    (is (not (identical? defn-request #'defn-request))
        "control: the Var and the fn it holds are genuinely different values,
         so the Var row cannot pass by silently testing the plain fn twice")))

(deftest reserved-scope-namespace-typo-rejected-fail-closed
  ;; rf2-y7lcqy — a bare keyword in the framework-reserved :rf.scope/*
  ;; namespace that is NOT one of the closed enum (:rf.scope/global,
  ;; :rf.scope/from-caller) is a TYPO. It MUST be rejected loudly at
  ;; registration (fail-closed) rather than silently accepted as a literal
  ;; scope that would resolve to the wrong [:rf.scope/glabal] cache scope.
  (testing "a :rf.scope/* typo throws :rf.error/resource-missing-scope-policy"
    (is (thrown-with-msg?
          js/Error #"resource-missing-scope-policy"
          (rf.resources/reg-resource :test/typo
                                  (assoc (valid-spec) :scope :rf.scope/glabal) valid-request)))
    (is (thrown-with-msg?
          js/Error #"resource-missing-scope-policy"
          (rf.resources/reg-resource :test/typo2
                                  (assoc (valid-spec) :scope :rf.scope/sesssion) valid-request))))
  ;; The closed enum members stay valid.
  (testing ":rf.scope/global and :rf.scope/from-caller remain valid"
    (is (= :test/global
           (rf.resources/reg-resource :test/global
                                   (assoc (valid-spec) :scope :rf.scope/global) valid-request)))
    (is (= :test/from-caller
           (rf.resources/reg-resource :test/from-caller
                                   (assoc (valid-spec) :scope :rf.scope/from-caller) valid-request))))
  ;; An app-namespaced keyword is a legitimate literal scope — NOT in the
  ;; reserved :rf.scope/* namespace, so it is accepted unchanged.
  (testing "an app-namespaced keyword scope is accepted as a literal scope"
    (is (= :test/app-ns
           (rf.resources/reg-resource :test/app-ns
                                   (assoc (valid-spec) :scope :my.app/whatever) valid-request))))
  ;; Data-value scopes (the legitimate data-value-resolver feature) stay
  ;; valid: a [:rf.scope/session {…}] tuple is a value, not a bare keyword,
  ;; and a map / string scope is likewise a literal data value.
  (testing "data-value scopes (tuple / map / string) remain valid"
    (is (= :test/tuple
           (rf.resources/reg-resource :test/tuple
                                   (assoc (valid-spec)
                                          :scope [:rf.scope/session {:user-id "u-1"}])
                                   valid-request)))
    (is (= :test/map
           (rf.resources/reg-resource :test/map
                                   (assoc (valid-spec) :scope {:tenant-id "acme"}) valid-request)))
    (is (= :test/string
           (rf.resources/reg-resource :test/string
                                   (assoc (valid-spec) :scope "tenant-acme") valid-request))))
  ;; A fn resolver is valid.
  (testing "a fn resolver scope is accepted"
    (is (= :test/fn
           (rf.resources/reg-resource :test/fn
                                   (assoc (valid-spec) :scope (fn [] :rf.scope/global)) valid-request)))))

(deftest resource-kind-in-closed-set
  (testing ":resource is a valid registrar kind"
    (is (rf.registrar/valid-kind? :resource))
    (is (contains? rf.registrar/kinds :resource)))
  (testing ":query is NOT a registrar kind (deliberate — Spec 016)"
    (is (not (rf.registrar/valid-kind? :query)))
    (is (not (contains? rf.registrar/kinds :query)))))

(deftest feature-probe-published
  (testing "the :resources feature is loaded? (the probe key is published)"
    (is (some? (rf.late-bind/get-fn :resources/reg-resource)))
    (is (true? (rf.features/feature-loaded? :resources)))
    (is (= "day8/re-frame2-resources" (:maven (:resources (rf.features/features)))))))

(deftest public-api-hooks-published
  (testing "every public-API late-bind hook resolves"
    (doseq [k [:resources/reg-resource :resources/clear-resource
               :resources/resource-meta :resources/resource-state
               :resources/resources]]
      (is (some? (rf.late-bind/get-fn k)) (str k " should be published")))))

(deftest resource-subs-registered
  (testing "the passive :rf.resource/* sub family is registered"
    (doseq [sub-id [:rf/resource :rf.resource/data :rf.resource/status
                    :rf.resource/loading? :rf.resource/fetching?
                    :rf.resource/stale? :rf.resource/error
                    :rf.resource/refresh-error :rf.resource/has-data?
                    :rf.resource/previous-data]]
      (is (some? (rf.registrar/lookup :sub sub-id))
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
   :rf.resource.internal/stale-fired      ;; landed; prior smoke omitted
   :rf.resource.internal/gc-fired
   :rf.resource.internal/stale-suppressed])

(deftest resource-events-registered
  (testing "the CURRENT :rf.resource/* event family is registered AND every
            member carries framework-write authority (rf2-l1a0s7)"
    (doseq [event-id resource-event-family]
      (let [handler (rf.registrar/lookup :event event-id)]
        (is (some? handler)
            (str event-id " event should be registered"))
        (is (true? (:rf/framework-authority? handler))
            (str event-id " should carry framework-write authority"))))))

(deftest late-bound-routing-accepts-resources-key
  (testing "the :routing/extra-route-keys hook publishes #{:resources}"
    (is (= #{:resources} ((rf.late-bind/get-fn :routing/extra-route-keys)))))
  (testing "routing's accepted-key extension lets a route carry :resources"
    ;; routing.registry/reg-route validates bare metadata keys; with the
    ;; resources extension loaded, :resources is accepted (it would
    ;; otherwise throw :rf.error/route-bad-metadata).
    (rf.registrar/clear-kind! :route)
    (is (= :test/route
           (rf.routing.registry/reg-route
             :test/route
             {:params    [:map [:slug :string]]
              :resources [{:resource :test/article
                           :params   (fn [route] {:slug (get-in route [:params :slug])})}]} "/x/:slug")))
    (rf.registrar/clear-kind! :route)))
