(ns re-frame.resources-runtime-classification-cljs-test
  "A resource's projection-relative classification applies at egress to the
  entries a runtime-db holds (Spec 015 §Subsystem projection-relative
  classification): the `:resources/runtime-classification` hook re-roots each
  entry's resource declaration onto its absolute `:data` path, so an entry is
  classified exactly while it is present and nothing is written into the
  frame's elision registry."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.elision :as rf.elision]
   [re-frame.frame :as rf.frame]
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

(defn- claims
  "The claims the frame's current policy makes on the entries `runtime-db` holds."
  [runtime-db]
  (rf.elision/with-runtime-classification nil [(rf.elision/frame-policy :rf/default)] runtime-db))

(defn- live-sensitive [] (rf.elision/sensitive-declarations :rf/default))

(defn- hold! [runtime-db] (rf.frame/swap-runtime-db! :rf/default #(merge % runtime-db)))

(defn- evict-all! []
  (rf.frame/swap-runtime-db! :rf/default #(assoc-in % [rf.resources.state/resources-key :entries] {})))

(defn- registry-resource-claims
  "Every `:source :resource` owner the frame's elision registry itself holds."
  []
  (for [[_ decls] (:rf.runtime/elision (:rf.db/runtime (rf/frame-state-value :rf/default)))
        :when (map? decls)
        [_ owners] decls
        owner owners
        :when (= :resource (:source owner))]
    owner))

(def ^:private resource-owner #{{:source :resource}})

(deftest each-entry-is-claimed-at-its-absolute-data-path
  (reg! :profile/card {:sensitive [[:data :ssn]]
                       :large     [[:data :avatar-bytes]]
                       :params-schema [:map [:account-id :string] [:slug :string]]})
  (let [k        (rf.resources.state/scoped-resource-key [:rf.scope/session {:user-id "u-1"}]
                                                         :profile/card
                                                         {:account-id "a-1" :slug "x"})
        k-id     (rf.resources.state/key-id k)
        data-pre [:rf.runtime/resources :entries k-id :data]]
    (is (= {:sensitive-declarations {(conj data-pre :ssn) resource-owner}
            :declarations           {(conj data-pre :avatar-bytes) resource-owner}}
           (claims (runtime-db-with {k {:data {:ssn "x" :avatar-bytes "y"}}})))
        "each declaration is claimed at the entry's absolute :data path, and
         nothing is claimed under the scoped key: params and scope are identity")))

(deftest an-entry-is-classified-exactly-while-the-frame-holds-it
  (reg! :profile/card3 {:sensitive [[:data :ssn]]})
  (let [k       (rf.resources.state/scoped-resource-key :rf.scope/global :profile/card3 {:slug "x"})
        abs-ssn [:rf.runtime/resources :entries (rf.resources.state/key-id k) :data :ssn]]
    (is (not (contains? (live-sensitive) abs-ssn)) "control: nothing is claimed before the entry lands")
    (hold! (runtime-db-with {k {:data {:ssn "x"}}}))
    (is (= resource-owner (get (live-sensitive) abs-ssn)) "the held entry is claimed, with no classification step")
    (is (empty? (registry-resource-claims)) "nothing is written into the frame's elision registry")
    (evict-all!)
    (is (not (contains? (live-sensitive) abs-ssn)) "the evicted entry's claim goes with it, with no drop step")))

(deftest a-resource-that-declares-nothing-claims-nothing
  (reg! :plain/card {})
  (let [k (rf.resources.state/scoped-resource-key :rf.scope/global :plain/card {:slug "x"})]
    (is (nil? (claims (runtime-db-with {k {:data {:title "t"}}}))))
    (is (not (contains? (get-in (rf.elision/frame-policy :rf/default) [:roots :rf.runtime/resources])
                        :plain/card))
        "the policy collects only the resources that declare a classification")))

(deftest resource-and-effect-claims-union
  (reg! :acct/card {:sensitive [[:data :ssn]]})
  (let [k       (rf.resources.state/scoped-resource-key :rf.scope/global :acct/card {:slug "x"})
        abs-ssn [:rf.runtime/resources :entries (rf.resources.state/key-id k) :data :ssn]]
    ;; an app effect already classifies the same absolute path (Spec 015)
    (hold! (assoc-in (runtime-db-with {k {:data {:ssn "123-45-6789"}}})
                     [:rf.runtime/elision :sensitive-declarations abs-ssn]
                     #{{:source :effect}}))
    (is (= #{{:source :effect} {:source :resource}} (get (live-sensitive) abs-ssn))
        "the resource claim unions with the effect claim")
    (evict-all!)
    (is (= #{{:source :effect}} (get (live-sensitive) abs-ssn))
        "the eviction takes the resource claim, and the effect claim survives")))
