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
    6. the whole-db read is an ordinary root-path input (`{:db [:db []]}`) and
       `:whole-db?` is DERIVED from the declaration, never authored; `:inputs`
       is REQUIRED and the retired 2-arity / `:doc`-only spellings are rejected
       loudly (rf2-kuky.34);
    7. the `{:from-db id}` reference resolver (use-time resolution + nil
       fail-closed);
    8. a resolved scope routes through the shared concrete-scope
       canonicalization (a `:rf.scope/*` typo rejected fail-closed)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.registrar :as rf.registrar]
   [re-frame.resources :as rf.resources]
   [re-frame.resources.scope-registry :as rf.resources.scope-registry]
   [re-frame.trace.tooling :as rf.trace.tooling]))

;; ---- fixtures -------------------------------------------------------------

(use-fixtures :each
  {:before (fn [] (rf.registrar/clear-kind! :resource-scope))
   :after  (fn [] (rf.registrar/clear-kind! :resource-scope))})

(defn- record-scope-resolved!
  "Run `body-fn` with a trace listener attached, returning every
  `:rf.resource/scope-resolved` row it emitted."
  [body-fn]
  (let [seen (atom [])
        k    ::scope-resolved-recorder]
    (rf.trace.tooling/register-listener!
      k (fn [ev] (when (= :rf.resource/scope-resolved (:operation ev))
                   (swap! seen conj ev))))
    (try (body-fn) (finally (rf.trace.tooling/unregister-listener! k)))
    @seen))

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
    (is (rf.registrar/valid-kind? :resource-scope))
    (is (contains? rf.registrar/kinds :resource-scope))))

(deftest reg-resource-scope-registers-and-introspects
  (testing "reg-resource-scope writes a :resource-scope registrar entry"
    (is (= :realworld/session (rf.resources/reg-resource-scope :realworld/session session-meta session-resolve)))
    (is (contains? (rf.registrar/registrations :resource-scope) :realworld/session))
    (is (= [:realworld/session] (rf.resources/scope-resolver-ids))))
  (testing "scope-resolver-meta reads the canonical spec back"
    (let [m (rf.resources/scope-resolver-meta :realworld/session)]
      (is (fn? (:resolve m)))
      (is (= {:username [:db [:auth :user :username]]} (:inputs m)))
      (is (false? (:whole-db? m)))))
  (testing "clear-resource-scope removes the registration"
    (rf/clear :resource-scope :realworld/session)
    (is (nil? (rf.resources/scope-resolver-meta :realworld/session)))
    (is (not (contains? (rf.registrar/registrations :resource-scope) :realworld/session)))))

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
          (rf.resources/reg-resource-scope :s/no-resolve
                                        {:inputs {:x [:db [:x]]}}
                                        "not a fn"))))
  (testing "a non-map metadata slot throws"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-scope-spec"
          (rf.resources/reg-resource-scope :s/bad-meta "not a map" (fn [_ _] nil)))))
  (testing "a :resolve left inside the metadata map is rejected as mislocated"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-scope-spec"
          (rf.resources/reg-resource-scope :s/mislocated
                                        {:inputs {:x [:db [:x]]}
                                         :resolve (fn [_ _] nil)}
                                        (fn [_ _] nil)))))
  (testing "a non-map :inputs throws"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-scope-spec"
          (rf.resources/reg-resource-scope :s/bad-inputs
                                        {:inputs [:not :a :map]}
                                        (fn [_ _] nil)))))
  (testing "a malformed input descriptor (not a 2-vector) throws"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-scope-spec"
          (rf.resources/reg-resource-scope :s/bad-desc
                                        {:inputs {:x [:db]}}
                                        (fn [_ _] nil)))))
  (testing "an unknown source head throws"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-scope-spec"
          (rf.resources/reg-resource-scope :s/bad-src
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
          (rf.resources/reg-resource-scope :s/tenant
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
      (is (= {:username "jake" :locale :en} (rf.resources.scope-registry/eval-inputs inputs db)))))
  (testing "a missing path resolves to nil (rf.path get, no throw)"
    (is (= {:username nil} (rf.resources.scope-registry/eval-inputs {:username [:db [:auth :user :username]]} {})))))

;; ===========================================================================
;; 4. The resolve-resource-scope resolver helper (fail-closed nil)
;; ===========================================================================

(deftest resolve-resource-scope-against-supplied-db
  (rf.resources/reg-resource-scope :realworld/session session-meta session-resolve)
  (testing "resolves the concrete scope from a supplied db value (canonicalized)"
    (is (= [:rf.scope/session {:username "jake"}]
           (rf.resources/resolve-resource-scope {:auth {:user {:username "jake"}}}
                                             :realworld/session))))
  (testing "a resolver returning nil FAILS CLOSED — nil, never an implicit global"
    (is (nil? (rf.resources/resolve-resource-scope {} :realworld/session)))))

(deftest resolve-resource-scope-unregistered-is-loud
  (testing "resolve-resource-scope on an unregistered id throws fail-closed"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-scope-not-registered"
          (rf.resources/resolve-resource-scope {} :s/nope)))))

(deftest resolved-scope-routes-through-canonicalization
  ;; A resolver that returns a :rf.scope/* TYPO must be rejected fail-closed
  ;; (the shared concrete-scope canonicalization path) — it can never become
  ;; a silent wrong cache scope.
  (rf.resources/reg-resource-scope :s/typo
                                {:inputs {}}
                                (fn [_ _] :rf.scope/glabal))
  (testing "a resolved :rf.scope/* typo is rejected at canonicalization"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
          (rf.resources/resolve-resource-scope {} :s/typo)))))

;; ===========================================================================
;; 5. The whole-db read is a root-path input; :whole-db? is DERIVED
;; ===========================================================================

(deftest whole-db-is-a-declared-root-path-input
  ;; rf2-kuky.34 — there is no bare-fn sugar and no first-arg meaning-shift.
  ;; Reading the whole db is spelled `{:inputs {:db [:db []]}}`, and the
  ;; `:whole-db?` cost mark tooling reads (EP-0015 disposition 8) is DERIVED
  ;; from that declaration rather than authored by a second registration mode.
  (testing "a root-path input registers, and the resolver's first arg is still
            the inputs map"
    (rf.resources/reg-resource-scope :s/whole-db
                                  {:inputs {:db [:db []]}}
                                  (fn [{:keys [db]} _ctx]
                                    (when-let [u (get-in db [:auth :user :username])]
                                      [:rf.scope/session {:username u}])))
    (let [m (rf.resources/scope-resolver-meta :s/whole-db)]
      (is (true? (:whole-db? m)))
      (is (= {:db [:db []]} (:inputs m)))))
  (testing "it resolves against the whole db at use time and fails closed on nil"
    (is (= [:rf.scope/session {:username "jake"}]
           (rf.resources/resolve-resource-scope {:auth {:user {:username "jake"}}}
                                             :s/whole-db)))
    (is (nil? (rf.resources/resolve-resource-scope {} :s/whole-db))))
  (testing "a MIXED declaration — one root input beside a narrow one — derives
            :whole-db? true"
    (rf.resources/reg-resource-scope :s/mixed
                                  {:inputs {:db   [:db []]
                                            :user [:db [:auth :user]]}}
                                  (fn [_inputs _ctx] nil))
    (is (true? (:whole-db? (rf.resources/scope-resolver-meta :s/mixed)))))
  (testing "a NARROW declaration derives :whole-db? false"
    (rf.resources/reg-resource-scope :s/narrow session-meta session-resolve)
    (is (false? (:whole-db? (rf.resources/scope-resolver-meta :s/narrow)))))
  (testing "the DERIVED :whole-db? true rides the :rf.resource/scope-resolved
            trace row (the traced causal boundary, not the pure read)"
    (rf.resources/reg-resource-scope :s/whole-db-traced
                                  {:inputs {:db [:db []]}}
                                  (fn [{:keys [db]} _ctx]
                                    (when-let [u (get-in db [:auth :user :username])]
                                      [:rf.scope/session {:username u}])))
    (let [rows (record-scope-resolved!
                 (fn []
                   (rf.resources.scope-registry/resolve-scope*
                     :s/whole-db-traced
                     (rf.resources/scope-resolver-meta :s/whole-db-traced)
                     {:auth {:user {:username "jake"}}}
                     'rf/resolve-resource-scope)))
          row  (some (fn [ev] (when (= :s/whole-db-traced (:resource-id (:tags ev)))
                                (:tags ev)))
                     rows)]
      (is (some? row) "a scope-resolved row was emitted")
      (is (true? (:whole-db? row)))
      (is (= [:db] (:inputs row)) "the declared input NAME, not a synthetic one"))))

(deftest inputs-is-required
  ;; rf2-kuky.34 — the `:doc`-only metadata variant and the retired 2-arity
  ;; spelling are both gone; each is a loud registration error.
  (testing ":doc-only metadata (no :inputs) is a loud registration error naming
            :inputs"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-scope-spec"
          (rf.resources/reg-resource-scope :s/doc-only
                                        {:doc "Whole-db, documented."}
                                        (fn [_inputs _ctx] nil))))
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #":inputs"
          (rf.resources/reg-resource-scope :s/doc-only
                                        {:doc "Whole-db, documented."}
                                        (fn [_inputs _ctx] nil))))
    (is (nil? (rf.resources/scope-resolver-meta :s/doc-only))))
  (testing "an EMPTY metadata map is the same error"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-scope-spec"
          (rf.resources/reg-resource-scope :s/empty-meta {} (fn [_inputs _ctx] nil)))))
  (testing "the retired 2-arity spelling is rejected loudly on both hosts"
    ;; `apply` defeats any host-side STATIC arity check so both hosts exercise
    ;; the same call. CLJS does not arity-check a single-arity fn at runtime
    ;; either: the resolver lands in the metadata slot and the
    ;; metadata-must-be-a-map guard catches it, naming the MIDDLE slot. On the
    ;; JVM the arity check fires first.
    #?(:cljs (is (thrown-with-msg?
                   js/Error #"metadata \(the MIDDLE slot\) must be a map"
                   (apply rf.resources/reg-resource-scope
                          [:s/two-arity (fn [_db _ctx] nil)])))
       :clj  (is (thrown? Throwable
                          (apply rf.resources/reg-resource-scope
                                 [:s/two-arity (fn [_db _ctx] nil)]))))
    (is (nil? (rf.resources/scope-resolver-meta :s/two-arity)))))

;; ===========================================================================
;; 6. {:from-db id} reference resolution (use-time, nil fail-closed)
;; ===========================================================================

(deftest from-db-reference-resolution
  (rf.resources/reg-resource-scope :realworld/session session-meta session-resolve)
  (testing "from-db-reference? recognises only {:from-db …} maps"
    (is (rf.resources.scope-registry/from-db-reference? {:from-db :realworld/session}))
    (is (not (rf.resources.scope-registry/from-db-reference? :rf.scope/global)))
    (is (not (rf.resources.scope-registry/from-db-reference? [:rf.scope/session {:username "jake"}])))
    (is (not (rf.resources.scope-registry/from-db-reference? {:tenant "acme"}))))
  (testing "a reference resolves at USE TIME against the supplied db"
    (is (= [:rf.scope/session {:username "jake"}]
           (rf.resources.scope-registry/resolve-from-db-reference {:from-db :realworld/session}
                                            {:auth {:user {:username "jake"}}}
                                            'test))))
  (testing "a reference resolving nil FAILS CLOSED (nil — the caller interprets)"
    (is (nil? (rf.resources.scope-registry/resolve-from-db-reference {:from-db :realworld/session}
                                               {} 'test))))
  (testing "a reference to an unregistered resolver is loud"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-scope-not-registered"
          (rf.resources.scope-registry/resolve-from-db-reference {:from-db :s/nope} {} 'test)))))

;; ===========================================================================
;; 7. No :rf.egress/output-sensitivity claim (EP-0025, rf2-71dr8t) — the
;;    derived-sensitivity PROPAGATION enum is removed; the key is silently
;;    ignored if present (NOT validated fail-closed). The declared :db input
;;    paths accessor remains for tooling.
;; ===========================================================================

(deftest output-sensitivity-claim-silently-ignored
  (testing "a resolver carries no :output-sensitivity on its canonical spec
            (the propagation enum is gone — EP-0025)"
    (rf.resources/reg-resource-scope :s/default session-meta session-resolve)
    (is (nil? (:output-sensitivity (rf.resources/scope-resolver-meta :s/default)))))
  (testing "a present :rf.egress/output-sensitivity key is silently ignored, not
            stored, and registration does NOT throw (Spec 015 §No propagation:
            the key is gone and silently ignored if present)"
    (doseq [claim [:rf.egress/inherit :rf.egress/sensitive :rf.egress/public]]
      (is (= :s/claim
             (rf.resources/reg-resource-scope :s/claim
                                           (assoc session-meta :rf.egress/output-sensitivity claim)
                                           session-resolve)))
      (is (nil? (:output-sensitivity (rf.resources/scope-resolver-meta :s/claim))))))
  (testing "a whole-db (root-path input) resolver carries no :output-sensitivity"
    (rf.resources/reg-resource-scope :s/whole-db-claim
                                  {:inputs {:db [:db []]}}
                                  (fn [_inputs _ctx] nil))
    (is (nil? (:output-sensitivity (rf.resources/scope-resolver-meta :s/whole-db-claim)))))
  (testing "a value that was a fail-closed enum typo is now silently ignored —
            no :rf.error/invalid-resource-scope-spec throw"
    (is (= :s/was-typo-claim
           (rf.resources/reg-resource-scope :s/was-typo-claim
                                         (assoc session-meta :rf.egress/output-sensitivity :rf.egress/publik)
                                         session-resolve)))))

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
    (rf.resources/reg-resource-scope :s/three-slot
                                  {:doc "3-slot." :inputs {:username [:db [:auth :user :username]]}}
                                  session-resolve)
    (let [m (rf.resources/scope-resolver-meta :s/three-slot)]
      (is (= {:username [:db [:auth :user :username]]} (:inputs m)))
      (is (identical? session-resolve (:resolve m)))
      (is (false? (:whole-db? m)))))
  ;; rf2-kuky.34 — the 3-slot grammar is now the ONLY arity. The retired
  ;; 2-arity and `:doc`-only spellings are pinned as loud registration errors
  ;; by `inputs-is-required` above.
  (testing "the resolver first arg is the resolved inputs map"
    (is (= [:rf.scope/session {:username "jake"}]
           (rf.resources/resolve-resource-scope {:auth {:user {:username "jake"}}}
                                             :s/three-slot)))))
