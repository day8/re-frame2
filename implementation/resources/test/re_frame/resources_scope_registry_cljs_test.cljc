(ns re-frame.resources-scope-registry-cljs-test
  "Named resource-scope resolvers — `reg-resource-scope` / `clear-resource-scope`
  / the `resolve-resource-scope` resolver helper (rf2-hls77w, EP-0016 D3 slice 2,
  Spec 016 §Named resource-scope resolvers).

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex; Shadow's `:node-test` build via the `cljs-test$` regex.

  What's under test (the slice's validation plan items):

    1. registration + introspection under the `:resource-scope` kind; the
       `:resource-scope` kind is in the core registrar's closed set;
    2. fail-closed validation — a non-map/non-fn resolver, a fn-less map, a
       malformed input descriptor;
    3. the RESERVED `[:runtime path]` source is rejected loudly (not shipped);
    4. `[:db path]` input evaluation against a supplied db (EP-0012 rf.path);
    5. the `resolve-resource-scope` resolver helper resolves against a given db
       and FAILS CLOSED on nil (no implicit global), throws on an
       unregistered id;
    6. whole-db fn sugar lowers to an explicit whole-db input (`:whole-db?
       true`, the resolver sees the whole db);
    7. the `{:from-db id}` reference resolver (use-time resolution + nil
       fail-closed);
    8. a resolved scope routes through the shared concrete-scope
       canonicalization (a `:rf.scope/*` typo rejected fail-closed)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.registrar :as registrar]
   [re-frame.resources :as resources]
   [re-frame.resources.scope-registry :as scope]))

;; ---- fixtures -------------------------------------------------------------

(use-fixtures :each
  {:before (fn [] (registrar/clear-kind! :resource-scope))
   :after  (fn [] (registrar/clear-kind! :resource-scope))})

;; rf2-bqstzr — the canonical declared-inputs resolver split into the 3-slot
;; grammar's metadata middle slot (`session-meta`: `:doc` + `:inputs`) and the
;; value `:resolve` fn (`session-resolve`), so call sites read
;; `(reg-resource-scope id session-meta session-resolve)`.
(def ^:private session-meta
  "The canonical declared-inputs resolver metadata (Spec 016 §The :inputs
  grammar) — the 3-slot MIDDLE slot."
  {:doc    "Viewer session scope."
   :inputs {:username [:db [:auth :user :username]]}})

(defn- session-resolve
  "The canonical resolver fn — the 3-slot VALUE slot."
  [{:keys [username]} _ctx]
  (when username [:rf.scope/session {:username username}]))

;; ===========================================================================
;; 1. Registration + introspection + the :resource-scope kind
;; ===========================================================================

(deftest resource-scope-kind-in-closed-set
  (testing ":resource-scope is a valid registrar kind"
    (is (registrar/valid-kind? :resource-scope))
    (is (contains? registrar/kinds :resource-scope))))

(deftest reg-resource-scope-registers-and-introspects
  (testing "reg-resource-scope writes a :resource-scope registrar entry"
    (is (= :realworld/session (resources/reg-resource-scope :realworld/session session-meta session-resolve)))
    (is (contains? (registrar/registrations :resource-scope) :realworld/session))
    (is (= [:realworld/session] (resources/scope-resolver-ids))))
  (testing "scope-resolver-meta reads the canonical spec back"
    (let [m (resources/scope-resolver-meta :realworld/session)]
      (is (fn? (:resolve m)))
      (is (= {:username [:db [:auth :user :username]]} (:inputs m)))
      (is (false? (:whole-db? m)))))
  (testing "clear-resource-scope removes the registration"
    (resources/clear-resource-scope :realworld/session)
    (is (nil? (resources/scope-resolver-meta :realworld/session)))
    (is (not (contains? (registrar/registrations :resource-scope) :realworld/session)))))

;; ===========================================================================
;; 2. Fail-closed validation at the authoring boundary
;; ===========================================================================

(deftest reg-resource-scope-fail-closed
  ;; rf2-bqstzr — the 3-slot grammar `(reg-resource-scope scope-id metadata
  ;; resolve-fn)`: the resolver fn is the VALUE slot, `:inputs` lives in the
  ;; metadata MIDDLE slot.
  (testing "a non-fn value slot throws invalid-resource-scope-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-scope-spec"
          (resources/reg-resource-scope :s/no-resolve
                                        {:inputs {:x [:db [:x]]}}
                                        "not a fn"))))
  (testing "a non-map non-fn value slot throws (2-arg sugar, value not a fn)"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-scope-spec"
          (resources/reg-resource-scope :s/bad "not a resolver"))))
  (testing "a non-map metadata slot throws"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-scope-spec"
          (resources/reg-resource-scope :s/bad-meta "not a map" (fn [_ _] nil)))))
  (testing "a :resolve left inside the metadata map is rejected as mislocated"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-scope-spec"
          (resources/reg-resource-scope :s/mislocated
                                        {:inputs {:x [:db [:x]]}
                                         :resolve (fn [_ _] nil)}
                                        (fn [_ _] nil)))))
  (testing "a non-map :inputs throws"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-scope-spec"
          (resources/reg-resource-scope :s/bad-inputs
                                        {:inputs [:not :a :map]}
                                        (fn [_ _] nil)))))
  (testing "a malformed input descriptor (not a 2-vector) throws"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-scope-spec"
          (resources/reg-resource-scope :s/bad-desc
                                        {:inputs {:x [:db]}}
                                        (fn [_ _] nil)))))
  (testing "an unknown source head throws"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-scope-spec"
          (resources/reg-resource-scope :s/bad-src
                                        {:inputs {:x [:cookie [:x]]}}
                                        (fn [_ _] nil))))))

(deftest runtime-source-is-reserved-not-shipped
  ;; Spec 016 §Route-derived scope is reserved — `[:runtime path]` is named
  ;; in the input vocabulary but NOT shipped in this slice. Declaring one is
  ;; a loud, NAMED reservation error (distinct from an unknown-source typo)
  ;; so a consumer knows it un-defers rather than that it is a typo.
  (testing "[:runtime path] is rejected with the reserved-source error"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-scope-source-reserved"
          (resources/reg-resource-scope :s/tenant
                                        {:inputs {:tenant [:runtime [:rf.runtime/routing :current :params :tenant]]}}
                                        (fn [{:keys [tenant]} _]
                                          (when tenant [:rf.scope/tenant {:tenant tenant}])))))))

;; ===========================================================================
;; 3. [:db path] input evaluation (EP-0012 rf.path)
;; ===========================================================================

(deftest eval-inputs-reads-db-paths
  (testing "eval-inputs reads each [:db path] off the supplied db (rf.path get)"
    (let [inputs {:username [:db [:auth :user :username]]
                  :locale   [:db [:i18n :locale]]}
          db     {:auth {:user {:username "jake"}} :i18n {:locale :en}}]
      (is (= {:username "jake" :locale :en} (scope/eval-inputs inputs db)))))
  (testing "a missing path resolves to nil (rf.path get, no throw)"
    (is (= {:username nil} (scope/eval-inputs {:username [:db [:auth :user :username]]} {})))))

;; ===========================================================================
;; 4. The resolve-resource-scope resolver helper (fail-closed nil)
;; ===========================================================================

(deftest resolve-resource-scope-against-supplied-db
  (resources/reg-resource-scope :realworld/session session-meta session-resolve)
  (testing "resolves the concrete scope from a supplied db value (canonicalized)"
    (is (= [:rf.scope/session {:username "jake"}]
           (resources/resolve-resource-scope {:auth {:user {:username "jake"}}}
                                             :realworld/session))))
  (testing "a resolver returning nil FAILS CLOSED — nil, never an implicit global"
    (is (nil? (resources/resolve-resource-scope {} :realworld/session)))))

(deftest resolve-resource-scope-unregistered-is-loud
  (testing "resolve-resource-scope on an unregistered id throws fail-closed"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-scope-not-registered"
          (resources/resolve-resource-scope {} :s/nope)))))

(deftest resolved-scope-routes-through-canonicalization
  ;; A resolver that returns a :rf.scope/* TYPO must be rejected fail-closed
  ;; (the shared concrete-scope canonicalization path) — it can never become
  ;; a silent wrong cache scope.
  (resources/reg-resource-scope :s/typo
                                {:inputs {}}
                                (fn [_ _] :rf.scope/glabal))
  (testing "a resolved :rf.scope/* typo is rejected at canonicalization"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
          (resources/resolve-resource-scope {} :s/typo)))))

;; ===========================================================================
;; 5. Whole-db function sugar (explicit-cost)
;; ===========================================================================

(deftest whole-db-fn-sugar-lowers-to-explicit-whole-db-input
  (testing "a bare (fn [db ctx] …) registers and is marked :whole-db? true"
    (resources/reg-resource-scope :s/sugar
                                  (fn [db _ctx]
                                    (when-let [u (get-in db [:auth :user :username])]
                                      [:rf.scope/session {:username u}])))
    (let [m (resources/scope-resolver-meta :s/sugar)]
      (is (true? (:whole-db? m)))
      ;; the synthetic explicit whole-db input — the root [:db []] path, so
      ;; tooling sees the cost on both axes (EP-0015 disposition 8)
      (is (= {:db [:db []]} (:inputs m)))))
  (testing "the sugar resolves against the whole db at use time"
    (is (= [:rf.scope/session {:username "jake"}]
           (resources/resolve-resource-scope {:auth {:user {:username "jake"}}}
                                             :s/sugar)))
    (is (nil? (resources/resolve-resource-scope {} :s/sugar)))))

;; ===========================================================================
;; 6. {:from-db id} reference resolution (use-time, nil fail-closed)
;; ===========================================================================

(deftest from-db-reference-resolution
  (resources/reg-resource-scope :realworld/session session-meta session-resolve)
  (testing "from-db-reference? recognises only {:from-db …} maps"
    (is (scope/from-db-reference? {:from-db :realworld/session}))
    (is (not (scope/from-db-reference? :rf.scope/global)))
    (is (not (scope/from-db-reference? [:rf.scope/session {:username "jake"}])))
    (is (not (scope/from-db-reference? {:tenant "acme"}))))
  (testing "a reference resolves at USE TIME against the supplied db"
    (is (= [:rf.scope/session {:username "jake"}]
           (scope/resolve-from-db-reference {:from-db :realworld/session}
                                            {:auth {:user {:username "jake"}}}
                                            'test))))
  (testing "a reference resolving nil FAILS CLOSED (nil — the caller interprets)"
    (is (nil? (scope/resolve-from-db-reference {:from-db :realworld/session}
                                               {} 'test))))
  (testing "a reference to an unregistered resolver is loud"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-scope-not-registered"
          (scope/resolve-from-db-reference {:from-db :s/nope} {} 'test)))))

;; ===========================================================================
;; 7. No :rf.egress/output-sensitivity claim (EP-0025, rf2-71dr8t) — the
;;    derived-sensitivity PROPAGATION enum is removed; the key is silently
;;    ignored if present (NOT validated fail-closed). The declared :db input
;;    paths accessor remains for tooling.
;; ===========================================================================

(deftest output-sensitivity-claim-silently-ignored
  (testing "a resolver carries no :output-sensitivity on its canonical spec
            (the propagation enum is gone — EP-0025)"
    (resources/reg-resource-scope :s/default session-meta session-resolve)
    (is (nil? (:output-sensitivity (resources/scope-resolver-meta :s/default)))))
  (testing "a present :rf.egress/output-sensitivity key is silently ignored, not
            stored, and registration does NOT throw (Spec 015 §No propagation:
            the key is gone and silently ignored if present)"
    (doseq [claim [:rf.egress/inherit :rf.egress/sensitive :rf.egress/public]]
      (is (= :s/claim
             (resources/reg-resource-scope :s/claim
                                           (assoc session-meta :rf.egress/output-sensitivity claim)
                                           session-resolve)))
      (is (nil? (:output-sensitivity (resources/scope-resolver-meta :s/claim))))))
  (testing "the whole-db fn sugar carries no :output-sensitivity"
    (resources/reg-resource-scope :s/sugar-claim (fn [_db _ctx] nil))
    (is (nil? (:output-sensitivity (resources/scope-resolver-meta :s/sugar-claim)))))
  (testing "a value that was a fail-closed enum typo is now silently ignored —
            no :rf.error/invalid-resource-scope-spec throw"
    (is (= :s/was-typo-claim
           (resources/reg-resource-scope :s/was-typo-claim
                                         (assoc session-meta :rf.egress/output-sensitivity :rf.egress/publik)
                                         session-resolve)))))

(deftest input-db-paths-extracts-the-dependency-graph
  (testing "input-db-paths returns the concrete :db paths a resolver reads"
    (is (= [[:auth :user :username]]
           (scope/input-db-paths {:username [:db [:auth :user :username]]})))
    (is (= #{[:auth :user :username] [:i18n :locale]}
           (set (scope/input-db-paths {:username [:db [:auth :user :username]]
                                       :locale   [:db [:i18n :locale]]})))))
  (testing "nil / empty inputs → [] (no dependency)"
    (is (= [] (scope/input-db-paths nil)))
    (is (= [] (scope/input-db-paths {}))))
  (testing "the whole-db sugar's synthetic [:db []] is the root path [[]]"
    (is (= [[]] (scope/input-db-paths {:db [:db []]})))))

;; ===========================================================================
;; 8. The canonical 3-slot registration grammar (rf2-bqstzr)
;; ===========================================================================

(deftest reg-resource-scope-conforms-to-3-slot-grammar
  ;; rf2-bqstzr — `reg-resource-scope` is `(reg-resource-scope scope-id
  ;; metadata resolve-fn)`: the `:resolve` fn is the value slot, `:inputs`
  ;; lives in the metadata middle slot, matching reg-resource / reg-mutation /
  ;; reg-route.
  (testing "the 3-arg form stores :inputs from the metadata slot and the value
            fn as :resolve"
    (resources/reg-resource-scope :s/three-slot
                                  {:doc "3-slot." :inputs {:username [:db [:auth :user :username]]}}
                                  session-resolve)
    (let [m (resources/scope-resolver-meta :s/three-slot)]
      (is (= {:username [:db [:auth :user :username]]} (:inputs m)))
      (is (identical? session-resolve (:resolve m)))
      (is (false? (:whole-db? m)))))
  (testing "the resolver first arg is the resolved inputs map"
    (is (= [:rf.scope/session {:username "jake"}]
           (resources/resolve-resource-scope {:auth {:user {:username "jake"}}}
                                             :s/three-slot))))
  (testing "the 2-arg sugar (no metadata) selects the whole-db form"
    (resources/reg-resource-scope :s/two-slot
                                  (fn [db _ctx]
                                    (when-let [u (get-in db [:auth :user :username])]
                                      [:rf.scope/session {:username u}])))
    (is (true? (:whole-db? (resources/scope-resolver-meta :s/two-slot))))
    (is (= [:rf.scope/session {:username "jake"}]
           (resources/resolve-resource-scope {:auth {:user {:username "jake"}}}
                                             :s/two-slot))))
  (testing ":doc-only metadata (no :inputs) still selects the whole-db form"
    (resources/reg-resource-scope :s/doc-only
                                  {:doc "Whole-db, documented."}
                                  (fn [db _ctx] (get-in db [:tenant])))
    (is (true? (:whole-db? (resources/scope-resolver-meta :s/doc-only))))))
