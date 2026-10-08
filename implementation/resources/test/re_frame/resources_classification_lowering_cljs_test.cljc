(ns re-frame.resources-classification-lowering-cljs-test
  "Resources lower their projection-relative classification into the per-frame
  elision registry under `:source :resource`, per instance, at the entry's
  absolute runtime-db path (Spec 015 §Subsystem projection-relative
  classification, Spec 016 §Runtime-subsystem graduation), so a generic
  registry reader sees it. The reconcile is pure over the runtime-db value,
  idempotent, and self-dropping: an evicted entry's declarations vanish."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
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

(defn- reg!
  [id overrides]
  (rf/clear :resource id)
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

(defn- reconcile [runtime-db]
  (rf.resources.classification/reconcile-registry runtime-db rf.resources.registry/resource-meta))

(defn- evict-and-reconcile [runtime-db]
  (reconcile (assoc-in runtime-db [rf.resources.state/resources-key :entries] {})))

(defn- sensitive-decls [runtime-db]
  (get-in runtime-db [:rf.runtime/elision :sensitive-declarations]))

(def ^:private resource-owner #{{:source :resource}})

(deftest reconcile-lowers-data-and-params-declarations
  (reg! :profile/card {:sensitive [[:data :ssn] [:params :account-id]]
                       :large     [[:data :avatar-bytes]]
                       :params-schema [:map [:account-id :string] [:slug :string]]})
  (let [k        (rf.resources.state/scoped-resource-key :rf.scope/global :profile/card
                                                         {:account-id "a-1" :slug "x"})
        k-id     (rf.resources.state/key-id k)
        out      (reconcile (runtime-db-with {k {:data {:ssn "x" :avatar-bytes "y"}}}))
        data-pre [:rf.runtime/resources :entries k-id :data]]
    ;; a :params-rooted path lands on the scoped key's params component (index 2)
    (is (= [resource-owner resource-owner resource-owner]
           [(get (sensitive-decls out) (conj data-pre :ssn))
            (get (sensitive-decls out) [:rf.runtime/resources :entries k-id :resource/key 2 :account-id])
            (get-in out [:rf.runtime/elision :declarations (conj data-pre :avatar-bytes)])])
        "each declaration is lowered at the entry's absolute path")))

(deftest reconcile-is-idempotent
  (reg! :profile/card2 {:sensitive [[:data :ssn]]})
  (let [k    (rf.resources.state/scoped-resource-key :rf.scope/global :profile/card2 {:slug "x"})
        once (reconcile (runtime-db-with {k {:data {:ssn "x"}}}))]
    (is (= once (reconcile once)))))

(deftest reconcile-self-drops-evicted-entry
  (reg! :profile/card3 {:sensitive [[:data :ssn]]})
  (let [k    (rf.resources.state/scoped-resource-key :rf.scope/global :profile/card3 {:slug "x"})
        live (reconcile (runtime-db-with {k {:data {:ssn "x"}}}))]
    (is (seq (sensitive-decls live)) "FIXTURE — the live entry lowered a declaration")
    (is (empty? (sensitive-decls (evict-and-reconcile live)))
        "the evicted entry's declaration is dropped, with no separate drop hook")))

(deftest reconcile-no-classification-no-registry
  (reg! :plain/card {})
  (let [k (rf.resources.state/scoped-resource-key :rf.scope/global :plain/card {:slug "x"})]
    (is (not (contains? (reconcile (runtime-db-with {k {:data {:title "t"}}})) :rf.runtime/elision))
        "a resource that declares no classification leaves no stray registry sub-tree")))

(deftest registry-reader-sees-resource-classification-and-redacts
  ;; the elision-registry walker a generic registry reader (and the SSR
  ;; registry-projection defence in depth) uses, not the family projector
  (reg! :acct/profile {:sensitive [[:data :ssn]]})
  (rf/make-frame {:id :reg/frame})
  (let [k       (rf.resources.state/scoped-resource-key :rf.scope/global :acct/profile {:slug "x"})
        k-id    (rf.resources.state/key-id k)
        lowered (reconcile (runtime-db-with {k {:data {:ssn "123-45-6789" :name "Alice"}}}))]
    (rf.frame/swap-runtime-db! :reg/frame (constantly lowered))
    ;; the walker's opts map is closed: this is the :rf.egress/off-box-tool
    ;; floor `project-egress` would resolve, plus an explicit digest override
    (is (= {:ssn :rf/redacted :name "Alice"}
           (select-keys (rf.elision/elide-wire-value
                          (get-in lowered [rf.resources.state/resources-key :entries k-id :data])
                          {:frame :reg/frame
                           :path  [:rf.runtime/resources :entries k-id :data]
                           :rf.egress/include-digests? true})
                        [:ssn :name]))
        "the declared :ssn redacts and the undeclared sibling rides verbatim")))

(deftest resource-and-effect-claims-union-and-remove-independently
  (reg! :acct/card {:sensitive [[:data :ssn]]})
  (let [k       (rf.resources.state/scoped-resource-key :rf.scope/global :acct/card {:slug "x"})
        abs-ssn [:rf.runtime/resources :entries (rf.resources.state/key-id k) :data :ssn]
        ;; an app effect already classifies the same absolute path (Spec 015)
        unioned (reconcile (assoc-in (runtime-db-with {k {:data {:ssn "123-45-6789"}}})
                                     [:rf.runtime/elision :sensitive-declarations abs-ssn]
                                     #{{:source :effect}}))]
    (is (= #{{:source :effect} {:source :resource}} (get (sensitive-decls unioned) abs-ssn))
        "the resource claim unions with the effect claim")
    (is (= #{{:source :effect}} (get (sensitive-decls (evict-and-reconcile unioned)) abs-ssn))
        "eviction drops the resource claim and the effect claim survives")
    (is (= resource-owner
           (get-in (rf.elision/remove-owner (:rf.runtime/elision unioned)
                                            :sensitive-declarations {:source :effect})
                   [:sensitive-declarations abs-ssn]))
        "removing the effect owner leaves the resource claim standing")))
