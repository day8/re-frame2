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
   [re-frame.elision :as elision]
   [re-frame.frame :as frame]
   [re-frame.resources.classification :as classification]
   [re-frame.resources.registry :as registry]
   [re-frame.resources.state :as state]
   [re-frame.resources]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

;; ---- helpers --------------------------------------------------------------

(defn- reg!
  [id overrides]
  (rf/clear-resource id)
  (let [spec (merge {:scope         :rf.scope/global
                     :params-schema [:map [:slug :string]]}
                    overrides)]
    (rf/reg-resource id spec (fn [_ _] {:request {:method :get :url "/x"}}))))

(defn- runtime-db-with [entries]
  {state/resources-key {:entries (into {}
                                       (map (fn [[sk e]]
                                              [(state/key-id sk) (assoc (state/empty-entry (second sk) sk)
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
    (let [k       (state/scoped-resource-key :rf.scope/global :profile/card
                                             {:account-id "a-1" :slug "x"})
          k-id    (state/key-id k)
          rdb     (runtime-db-with {k {:data {:ssn "x" :avatar-bytes "y"}}})
          out     (classification/reconcile-registry rdb registry/resource-meta)
          sens    (sensitive-decls out)
          large   (large-decls out)
          data-pre  [:rf.runtime/resources :entries k-id :data]
          key-pre   [:rf.runtime/resources :entries k-id :resource/key]]
      ;; :data-rooted sensitive → absolute entry :data path
      (is (= {:source :resource} (get sens (conj data-pre :ssn)))
          "the :data :ssn declaration is lowered at the absolute entry data path")
      ;; :params-rooted sensitive → the scoped-key params component (index 2)
      (is (= {:source :resource} (get sens (conj key-pre 2 :account-id)))
          "the :params :account-id declaration is lowered at the scoped-key params index")
      ;; :data-rooted large → absolute entry :data path
      (is (= {:source :resource} (get large (conj data-pre :avatar-bytes)))
          "the :data :avatar-bytes declaration is lowered as a large declaration"))))

(deftest reconcile-is-idempotent
  (reg! :profile/card2 {:sensitive [[:data :ssn]]})
  (testing "re-running reconcile-registry over its own output is a no-op (the
            full resource-sourced set is rebuilt from :entries deterministically)"
    (let [k    (state/scoped-resource-key :rf.scope/global :profile/card2 {:slug "x"})
          rdb  (runtime-db-with {k {:data {:ssn "x"}}})
          once (classification/reconcile-registry rdb registry/resource-meta)
          twice (classification/reconcile-registry once registry/resource-meta)]
      (is (= once twice) "reconciliation is idempotent"))))

(deftest reconcile-self-drops-evicted-entry
  (reg! :profile/card3 {:sensitive [[:data :ssn]]})
  (testing "an evicted entry's :source :resource declarations vanish on the next
            reconcile (the per-instance teardown — no separate drop hook)"
    (let [k     (state/scoped-resource-key :rf.scope/global :profile/card3 {:slug "x"})
          rdb   (runtime-db-with {k {:data {:ssn "x"}}})
          live  (classification/reconcile-registry rdb registry/resource-meta)
          ;; evict: drop the entry, keep the carried registry, reconcile again.
          evicted (-> live
                      (assoc-in [state/resources-key :entries] {})
                      (classification/reconcile-registry registry/resource-meta))]
      (is (seq (sensitive-decls live)) "the live entry lowered a declaration")
      (is (empty? (get-in evicted [:rf.runtime/elision :sensitive-declarations]))
          "the evicted entry's declaration is dropped"))))

(deftest reconcile-preserves-foreign-sourced-entries
  (reg! :profile/card4 {:sensitive [[:data :ssn]]})
  (testing "a non-resource-sourced registry entry (e.g. :source :effect) on a
            different path rides untouched through reconciliation"
    (let [k   (state/scoped-resource-key :rf.scope/global :profile/card4 {:slug "x"})
          rdb (-> (runtime-db-with {k {:data {:ssn "x"}}})
                  (assoc-in [:rf.runtime/elision :sensitive-declarations [:app :token]]
                            {:source :effect}))
          out (classification/reconcile-registry rdb registry/resource-meta)]
      (is (= {:source :effect} (get (sensitive-decls out) [:app :token]))
          "the :source :effect entry survives reconciliation"))))

(deftest reconcile-no-classification-no-registry
  (reg! :plain/card {})   ;; declares no classification
  (testing "a resource that declares no classification lowers nothing — no
            stray registry sub-tree"
    (let [k   (state/scoped-resource-key :rf.scope/global :plain/card {:slug "x"})
          rdb (runtime-db-with {k {:data {:title "t"}}})
          out (classification/reconcile-registry rdb registry/resource-meta)]
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
    (rf/reg-frame :reg/frame {})
    (let [k     (state/scoped-resource-key :rf.scope/global :acct/profile {:slug "x"})
          k-id  (state/key-id k)
          rdb   (runtime-db-with {k {:data {:ssn "123-45-6789" :name "Alice"}}})
          ;; lower the resource classification into the frame's registry…
          lowered (classification/reconcile-registry rdb registry/resource-meta)]
      (frame/swap-runtime-db! :reg/frame (constantly lowered))
      ;; …then a GENERIC registry reader (elide-wire-value over the frame's
      ;; registry) redacts the declared :data :ssn path of the entry value.
      (let [entry-data (get-in lowered [state/resources-key :entries k-id :data])
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
          (is (= {:source :resource}
                 (get (elision/sensitive-declarations :reg/frame)
                      [:rf.runtime/resources :entries k-id :data :ssn]))))))))
