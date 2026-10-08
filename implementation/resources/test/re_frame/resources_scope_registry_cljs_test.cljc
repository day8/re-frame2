(ns re-frame.resources-scope-registry-cljs-test
  "Named resource-scope resolvers — `reg-resource-scope` /
  `clear-resource-scope` / `resolve-resource-scope` (Spec 016 §Named
  resource-scope resolvers): registration under the `:resource-scope` kind,
  fail-closed validation of the 3-slot grammar (metadata with a required
  `:inputs`, then the resolve fn), the reserved `[:runtime path]` source,
  `[:db path]` input evaluation, a derived `:whole-db?`, use-time
  `{:from-db id}` resolution failing closed on nil, and canonicalization of the
  resolved scope."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.registrar :as rf.registrar]
   [re-frame.resources :as rf.resources]
   [re-frame.resources.scope-registry :as rf.resources.scope-registry]
   [re-frame.trace.tooling :as rf.trace.tooling]))

;; FN form, not the `{:before … :after …}` map form: clojure.test calls a map
;; fixture as a function, a key lookup that never runs the test, so the JVM lane
;; would silently report zero tests for this namespace.
(use-fixtures :each
  (fn [test-fn]
    (rf.registrar/clear-kind! :resource-scope)
    (try
      (test-fn)
      (finally
        (rf.registrar/clear-kind! :resource-scope)))))

(defn- scope-meta [id]
  (:rf/resource-scope (rf/handler-meta {:source :store :kind :resource-scope :id id})))

(def ^:private session-meta
  "The canonical declared-inputs metadata: the 3-slot MIDDLE slot."
  {:doc    "Viewer session scope."
   :inputs {:username [:db [:auth :user :username]]}})

(defn- session-resolve
  "The canonical resolver fn: the 3-slot VALUE slot."
  [{:keys [username]} _ctx]
  (when username [:rf.scope/session {:username username}]))

(defn- whole-db-resolve [{:keys [db]} _ctx]
  (when-let [u (get-in db [:auth :user :username])]
    [:rf.scope/session {:username u}]))

;; ===========================================================================
;; registration, validation, the reserved source
;; ===========================================================================

(deftest reg-resource-scope-registers-and-introspects
  ;; the core registrar refuses a kind outside its closed set, so registering
  ;; at all pins the kind's membership
  (is (= [:realworld/session [:realworld/session]]
         [(rf.resources/reg-resource-scope :realworld/session session-meta session-resolve)
          (keys (rf/registrations {:source :store :kind :resource-scope}))]))
  (let [m (scope-meta :realworld/session)]
    (is (= [true {:username [:db [:auth :user :username]]} false]
           [(identical? session-resolve (:resolve m)) (:inputs m) (:whole-db? m)])
        "the value-slot fn is stored as :resolve beside the declared inputs"))
  (rf/clear :resource-scope :realworld/session)
  (is (= [nil false]
         [(scope-meta :realworld/session) (contains? (rf.registrar/registrations :resource-scope) :realworld/session)])
      "clear-resource-scope removes the registration"))

(deftest reg-resource-scope-fail-closed
  (doseq [[label metadata resolve-fn]
          [["a non-fn value slot"           {:inputs {:x [:db [:x]]}}                           "not a fn"]
           ["a non-map metadata slot"       "not a map"                                         (fn [_ _] nil)]
           ["a :resolve left in metadata"   {:inputs {:x [:db [:x]]} :resolve (fn [_ _] nil)}   (fn [_ _] nil)]
           ["a non-map :inputs"             {:inputs [:not :a :map]}                            (fn [_ _] nil)]
           ["a descriptor not a 2-vector"   {:inputs {:x [:db]}}                                (fn [_ _] nil)]
           ["an unknown source head"        {:inputs {:x [:cookie [:x]]}}                       (fn [_ _] nil)]]]
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-scope-spec"
          (rf.resources/reg-resource-scope :s/bad metadata resolve-fn))
        label)))

(deftest runtime-source-is-reserved-not-shipped
  ;; `[:runtime path]` is named in the input vocabulary but not shipped, so it
  ;; is a NAMED reservation error rather than an unknown-source typo
  (is (thrown-with-msg?
        #?(:clj Throwable :cljs js/Error) #"resource-scope-source-reserved"
        (rf.resources/reg-resource-scope :s/tenant
                                         {:inputs {:tenant [:runtime [:rf.runtime/routing :current :params :tenant]]}}
                                         (fn [{:keys [tenant]} _]
                                           (when tenant [:rf.scope/tenant {:tenant tenant}]))))))

(deftest inputs-is-required
  (testing ":doc-only metadata is a loud registration error naming :inputs"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-scope-spec"
          (rf.resources/reg-resource-scope :s/doc-only {:doc "Whole-db, documented."} (fn [_inputs _ctx] nil))))
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #":inputs"
          (rf.resources/reg-resource-scope :s/doc-only {:doc "Whole-db, documented."} (fn [_inputs _ctx] nil))))
    (is (nil? (scope-meta :s/doc-only))))
  (testing "an EMPTY metadata map is the same error"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-scope-spec"
          (rf.resources/reg-resource-scope :s/empty-meta {} (fn [_inputs _ctx] nil)))))
  (testing "the 2-arity spelling is rejected loudly on both hosts"
    ;; `apply` defeats a static arity check; CLJS does not arity-check at
    ;; runtime, so the resolver lands in the metadata slot and that guard fires
    #?(:cljs (is (thrown-with-msg?
                   js/Error #"metadata \(the MIDDLE slot\) must be a map"
                   (apply rf.resources/reg-resource-scope
                          [:s/two-arity (fn [_db _ctx] nil)])))
       :clj  (is (thrown? Throwable
                          (apply rf.resources/reg-resource-scope
                                 [:s/two-arity (fn [_db _ctx] nil)]))))
    (is (nil? (scope-meta :s/two-arity)))))

;; ===========================================================================
;; resolution
;; ===========================================================================

(deftest eval-inputs-reads-db-paths
  (is (= [{:username "jake" :locale :en} {:username nil}]
         [(rf.resources.scope-registry/eval-inputs
            {:username [:db [:auth :user :username]] :locale [:db [:i18n :locale]]}
            {:auth {:user {:username "jake"}} :i18n {:locale :en}})
          (rf.resources.scope-registry/eval-inputs {:username [:db [:auth :user :username]]} {})])
      "each [:db path] is read off the supplied db, a missing one as nil"))

(deftest resolve-resource-scope-unregistered-is-loud
  (is (thrown-with-msg?
        #?(:clj Throwable :cljs js/Error) #"resource-scope-not-registered"
        (rf.resources/resolve-resource-scope {} :s/nope))))

(deftest resolved-scope-routes-through-canonicalization
  ;; a :rf.scope/* typo must never become a silent wrong cache scope
  (rf.resources/reg-resource-scope :s/typo {:inputs {}} (fn [_ _] :rf.scope/glabal))
  (is (thrown-with-msg?
        #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
        (rf.resources/resolve-resource-scope {} :s/typo))))

(deftest whole-db-is-a-declared-root-path-input
  ;; the whole db is the root-path input `{:db [:db []]}`, and the `:whole-db?`
  ;; cost mark tooling reads is DERIVED from the declaration
  (rf.resources/reg-resource-scope :s/whole-db {:inputs {:db [:db []]}} whole-db-resolve)
  (rf.resources/reg-resource-scope :s/mixed {:inputs {:db [:db []] :user [:db [:auth :user]]}} (fn [_ _] nil))
  (rf.resources/reg-resource-scope :s/narrow session-meta session-resolve)
  (is (= [true {:db [:db []]} true false]
         [(:whole-db? (scope-meta :s/whole-db)) (:inputs (scope-meta :s/whole-db))
          (:whole-db? (scope-meta :s/mixed)) (:whole-db? (scope-meta :s/narrow))])
      "a root input, alone or beside a narrow one, derives :whole-db? true; a narrow declaration false")
  (is (= [[:rf.scope/session {:username "jake"}] nil]
         [(rf.resources/resolve-resource-scope {:auth {:user {:username "jake"}}} :s/whole-db)
          (rf.resources/resolve-resource-scope {} :s/whole-db)])
      "the resolver gets the inputs map, resolves at use time, and fails closed on nil")
  (let [seen (atom [])
        k    ::scope-resolved-recorder]
    (rf.trace.tooling/register-listener!
      k (fn [ev] (when (= :rf.resource/scope-resolved (:operation ev)) (swap! seen conj (:tags ev)))))
    (try
      (rf.resources.scope-registry/resolve-scope*
        :s/whole-db (scope-meta :s/whole-db) {:auth {:user {:username "jake"}}} 'rf/resolve-resource-scope)
      (finally (rf.trace.tooling/unregister-listener! k)))
    (is (= [true [:db]]
           ((juxt :whole-db? :inputs) (some #(when (= :s/whole-db (:resource-id %)) %) @seen)))
        "the traced resolve's :rf.resource/scope-resolved row carries the derived mark and the declared input name")))

(deftest from-db-reference-resolution
  (rf.resources/reg-resource-scope :realworld/session session-meta session-resolve)
  (is (= [true false false false]
         (map (comp boolean rf.resources.scope-registry/from-db-reference?)
              [{:from-db :realworld/session} :rf.scope/global [:rf.scope/session {:username "jake"}] {:tenant "acme"}]))
      "only a {:from-db …} map is a reference")
  (is (= [[:rf.scope/session {:username "jake"}] nil]
         [(rf.resources.scope-registry/resolve-from-db-reference
            {:from-db :realworld/session} {:auth {:user {:username "jake"}}} 'test)
          (rf.resources.scope-registry/resolve-from-db-reference {:from-db :realworld/session} {} 'test)])
      "a reference resolves at use time, and a nil resolution fails closed for the caller to interpret")
  (is (thrown-with-msg?
        #?(:clj Throwable :cljs js/Error) #"resource-scope-not-registered"
        (rf.resources.scope-registry/resolve-from-db-reference {:from-db :s/nope} {} 'test))))

(deftest output-sensitivity-claim-silently-ignored
  ;; there is no derived-sensitivity propagation enum (Spec 015 §No
  ;; propagation): the key is ignored if present, never validated
  (doseq [claim [:rf.egress/inherit :rf.egress/sensitive :rf.egress/public :rf.egress/publik]]
    (is (= [:s/claim nil]
           [(rf.resources/reg-resource-scope :s/claim
                                             (assoc session-meta :rf.egress/output-sensitivity claim)
                                             session-resolve)
            (:rf.egress/output-sensitivity (scope-meta :s/claim))])
        (str claim " registers and is not stored"))))
