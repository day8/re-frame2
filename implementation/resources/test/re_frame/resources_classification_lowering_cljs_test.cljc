(ns re-frame.resources-classification-lowering-cljs-test
  "EP-0025 §subsystems (rf2-v8x9n8) — resources LOWER their projection-relative
  classification into the per-frame elision registry under `:source :resource`,
  PER INSTANCE, mirroring the machines / routing standard model. Per Spec 015
  §Subsystem projection-relative classification / Spec 016 §Runtime-subsystem
  graduation.

  THE CONTRACT under test: a registry-reading consumer (Xray 'what is
  classified', an MCP registry view, the SSR registry-projection defence-in-
  depth) SEES resource classification at the entry's absolute runtime-db path —
  it is no longer applied ONLY at the family-private project-data / project-params
  projectors. The reconciliation is PURE over the runtime-db value, idempotent,
  value-independent, and self-dropping (an evicted entry's declarations vanish).

  CLJC so the JVM run (`clojure -M:test`, the load-bearing gate) exercises it;
  the schemas artefact is a test-only dep, so the shared walker hooks are bound."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.elision :as rf.elision]
   [re-frame.frame :as rf.frame]
   [re-frame.resources.classification :as rf.resources.classification]
   [re-frame.resources.registry :as rf.resources.registry]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

;; ---- helpers --------------------------------------------------------------

(defn- reg!
  [id overrides]
  (rf/clear-resource id)
  (let [spec (merge {:scope         :rf.scope/global
                     :params-schema [:map [:slug :string]]}
                    overrides)]
    (rf/reg-resource id spec (fn [_ _] {:request {:method :get :url "/x"}}))))

(defn- runtime-db-with [entries]
  {rf.resources.state/resources-key {:entries (into {}
                                       (map (fn [[sk e]]
                                              [(rf.resources.state/key-id sk) (assoc (rf.resources.state/empty-entry (second sk) sk)
                                                                        :status :loaded
                                                                        :resource/key sk
                                                                        :data (:data e))]))
                                       entries)}})

(defn- sensitive-decls [runtime-db]
  (get-in runtime-db [:rf.runtime/elision :sensitive-declarations]))

(defn- large-decls [runtime-db]
  (get-in runtime-db [:rf.runtime/elision :declarations]))

;; ===========================================================================
;; 1. reconcile-registry lowers a resource's :data / :params declarations into
;;    the per-frame elision registry at the entry's ABSOLUTE runtime-db path.
;; ===========================================================================

(deftest reconcile-lowers-data-and-params-declarations
  (reg! :profile/card {:sensitive [[:data :ssn] [:params :account-id]]
                       :large     [[:data :avatar-bytes]]
                       :params-schema [:map [:account-id :string] [:slug :string]]})
  (testing "an entry's resource :sensitive / :large declarations are LOWERED
            into the per-frame elision registry under :source :resource, rooted
            at the entry's absolute runtime-db path (a generic registry reader
            now SEES them)"
    (let [k       (rf.resources.state/scoped-resource-key :rf.scope/global :profile/card
                                             {:account-id "a-1" :slug "x"})
          k-id    (rf.resources.state/key-id k)
          rdb     (runtime-db-with {k {:data {:ssn "x" :avatar-bytes "y"}}})
          out     (rf.resources.classification/reconcile-registry rdb rf.resources.registry/resource-meta)
          sens    (sensitive-decls out)
          large   (large-decls out)
          data-pre  [:rf.runtime/resources :entries k-id :data]
          key-pre   [:rf.runtime/resources :entries k-id :resource/key]]
      ;; :data-rooted sensitive → absolute entry :data path
      (is (= #{{:source :resource}} (get sens (conj data-pre :ssn)))
          "the :data :ssn declaration is lowered at the absolute entry data path")
      ;; :params-rooted sensitive → the scoped-key params component (index 2)
      (is (= #{{:source :resource}} (get sens (conj key-pre 2 :account-id)))
          "the :params :account-id declaration is lowered at the scoped-key params index")
      ;; :data-rooted large → absolute entry :data path
      (is (= #{{:source :resource}} (get large (conj data-pre :avatar-bytes)))
          "the :data :avatar-bytes declaration is lowered as a large declaration"))))

(deftest reconcile-is-idempotent
  (reg! :profile/card2 {:sensitive [[:data :ssn]]})
  (testing "re-running reconcile-registry over its own output is a no-op (the
            full resource-sourced set is rebuilt from :entries deterministically)"
    (let [k    (rf.resources.state/scoped-resource-key :rf.scope/global :profile/card2 {:slug "x"})
          rdb  (runtime-db-with {k {:data {:ssn "x"}}})
          once (rf.resources.classification/reconcile-registry rdb rf.resources.registry/resource-meta)
          twice (rf.resources.classification/reconcile-registry once rf.resources.registry/resource-meta)]
      (is (= once twice) "reconciliation is idempotent"))))

(deftest reconcile-self-drops-evicted-entry
  (reg! :profile/card3 {:sensitive [[:data :ssn]]})
  (testing "an evicted entry's :source :resource declarations vanish on the next
            reconcile (the per-instance teardown — no separate drop hook)"
    (let [k     (rf.resources.state/scoped-resource-key :rf.scope/global :profile/card3 {:slug "x"})
          rdb   (runtime-db-with {k {:data {:ssn "x"}}})
          live  (rf.resources.classification/reconcile-registry rdb rf.resources.registry/resource-meta)
          ;; evict: drop the entry, keep the carried registry, reconcile again.
          evicted (-> live
                      (assoc-in [rf.resources.state/resources-key :entries] {})
                      (rf.resources.classification/reconcile-registry rf.resources.registry/resource-meta))]
      (is (seq (sensitive-decls live)) "the live entry lowered a declaration")
      (is (empty? (get-in evicted [:rf.runtime/elision :sensitive-declarations]))
          "the evicted entry's declaration is dropped"))))

(deftest reconcile-preserves-foreign-sourced-entries
  (reg! :profile/card4 {:sensitive [[:data :ssn]]})
  (testing "a non-resource-sourced registry entry (e.g. :source :effect) on a
            different path rides untouched through reconciliation"
    (let [k   (rf.resources.state/scoped-resource-key :rf.scope/global :profile/card4 {:slug "x"})
          rdb (-> (runtime-db-with {k {:data {:ssn "x"}}})
                  (assoc-in [:rf.runtime/elision :sensitive-declarations [:app :token]]
                            #{{:source :effect}}))
          out (rf.resources.classification/reconcile-registry rdb rf.resources.registry/resource-meta)]
      (is (= #{{:source :effect}} (get (sensitive-decls out) [:app :token]))
          "the :source :effect entry survives reconciliation"))))

(deftest reconcile-no-classification-no-registry
  (reg! :plain/card {})   ;; declares no classification
  (testing "a resource that declares no classification lowers nothing — no
            stray registry sub-tree"
    (let [k   (rf.resources.state/scoped-resource-key :rf.scope/global :plain/card {:slug "x"})
          rdb (runtime-db-with {k {:data {:title "t"}}})
          out (rf.resources.classification/reconcile-registry rdb rf.resources.registry/resource-meta)]
      (is (not (contains? out :rf.runtime/elision))
          "no :rf.runtime/elision key when nothing classifies"))))

;; ===========================================================================
;; 2. END-TO-END — a :sensitive resource-declared path redacts VIA THE REGISTRY
;;    (a generic registry-reading egress walk, not the family-private projector).
;; ===========================================================================

(deftest registry-reader-sees-resource-classification-and-redacts
  (reg! :acct/profile {:sensitive [[:data :ssn]]})
  (testing "after lowering, the elision-registry walker (the SAME one a generic
            registry reader / SSR registry-projection defence-in-depth uses)
            redacts the resource's declared :data :ssn path — proving the
            classification is IN the registry, not only at the family projector"
    (rf/make-frame {:id :reg/frame})
    (let [k     (rf.resources.state/scoped-resource-key :rf.scope/global :acct/profile {:slug "x"})
          k-id  (rf.resources.state/key-id k)
          rdb   (runtime-db-with {k {:data {:ssn "123-45-6789" :name "Alice"}}})
          ;; lower the resource classification into the frame's registry…
          lowered (rf.resources.classification/reconcile-registry rdb rf.resources.registry/resource-meta)]
      (rf.frame/swap-runtime-db! :reg/frame (constantly lowered))
      ;; …then a GENERIC registry reader (elide-wire-value over the frame's
      ;; registry) redacts the declared :data :ssn path of the entry value.
      (let [entry-data (get-in lowered [rf.resources.state/resources-key :entries k-id :data])
            projected  (rf/elide-wire-value
                         entry-data
                         {:frame :reg/frame
                          :path  [:rf.runtime/resources :entries k-id :data]
                          :rf.egress/profile :rf.egress/off-box-tool})]
        (is (= :rf/redacted (:ssn projected))
            "the registry reader redacts the resource-declared :data :ssn path")
        (is (= "Alice" (:name projected))
            "the undeclared sibling rides verbatim")
        (testing "the registry carries the declaration under :source :resource"
          (is (= #{{:source :resource}}
                 (get (rf.elision/sensitive-declarations :reg/frame)
                      [:rf.runtime/resources :entries k-id :data :ssn]))))))))

;; ===========================================================================
;; (rf2-wdm1vg) MULTI-OWNER union — a resource's lowered :data claim and an app
;; effect claim on the SAME absolute entry path survive INDEPENDENTLY.
;; ===========================================================================

(deftest resource-and-effect-claims-union-and-remove-independently
  (reg! :acct/card {:sensitive [[:data :ssn]]})
  (testing "rf2-wdm1vg — a resource's lowered :data claim and an app effect claim
            on the SAME absolute entry path UNION through reconcile, and each
            removes INDEPENDENTLY: eviction drops the resource claim while the
            effect survives; removing the effect owner leaves the resource claim."
    (let [k       (rf.resources.state/scoped-resource-key :rf.scope/global :acct/card {:slug "x"})
          k-id    (rf.resources.state/key-id k)
          abs-ssn [:rf.runtime/resources :entries k-id :data :ssn]
          ;; base runtime-db: the entry exists AND an app effect ALREADY
          ;; classifies the same absolute path (Spec 015 L149).
          base    (-> (runtime-db-with {k {:data {:ssn "123-45-6789"}}})
                      (assoc-in [:rf.runtime/elision :sensitive-declarations abs-ssn]
                                #{{:source :effect}}))
          ;; reconcile LOWERS the resource claim → UNION with the effect claim
          unioned (rf.resources.classification/reconcile-registry base rf.resources.registry/resource-meta)
          owners  (get-in unioned [:rf.runtime/elision :sensitive-declarations abs-ssn])]
      (is (contains? owners {:source :effect})  "the effect claim is retained")
      (is (contains? owners {:source :resource}) "the resource claim UNIONS in")
      ;; EVICT the entry → reconcile drops ONLY the resource owner; effect survives
      (let [evicted  (-> unioned
                         (assoc-in [rf.resources.state/resources-key :entries] {})
                         (rf.resources.classification/reconcile-registry rf.resources.registry/resource-meta))
            e-owners (get-in evicted [:rf.runtime/elision :sensitive-declarations abs-ssn])]
        (is (= #{{:source :effect}} e-owners)
            "eviction drops the resource claim; the effect claim SURVIVES (no fail-open)"))
      ;; conversely, removing the effect owner leaves the resource claim standing
      (let [reg-only       (get unioned :rf.runtime/elision)
            without-effect (rf.elision/remove-owner reg-only :sensitive-declarations {:source :effect})
            r-owners       (get-in without-effect [:sensitive-declarations abs-ssn])]
        (is (= #{{:source :resource}} r-owners)
            "removing the effect owner leaves the resource claim standing")))))
