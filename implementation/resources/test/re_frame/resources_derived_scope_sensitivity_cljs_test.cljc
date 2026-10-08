(ns re-frame.resources-derived-scope-sensitivity-cljs-test
  "No derived-sensitivity propagation (Spec 015 §No propagation, no taint;
  Spec 016 §No derived-sensitivity propagation): a resource whose
  `{:from-db <id>}` scope resolver reads a frame-sensitive app-db path does
  not inherit `:sensitive`, so its value serializes (the fail-open the spec
  names: classify the path you care about). Only the resource's own coarse
  claim governs its whole-entry disposition."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.elision :as rf.elision]
   [re-frame.frame :as rf.frame]
   [re-frame.registrar :as rf.registrar]
   [re-frame.resources.ssr :as rf.resources.ssr]
   [re-frame.resources.state :as rf.resources.state]
   ;; load-bearing side-effecting requires: register the :resource +
   ;; :resource-scope registrar kinds + the schemas walker hooks.
   [re-frame.resources]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private frame-id :rf/default)

(defn- init!
  "The frame classifies the viewer-identity path sensitive; a named resolver
  reads it to derive a session scope; a feed resource takes that scope and
  declares nothing."
  []
  (rf.registrar/clear-kind! :resource-scope)
  (rf.registrar/clear-kind! :resource)
  (rf/make-frame {:id frame-id})
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive [[:auth :user :username]]})))
  (rf/reg-resource-scope :t/session
    {:inputs {:username [:db [:auth :user :username]]}}
    (fn [{:keys [username]} _ctx]
      (when username [:rf.scope/session {:username username}])))
  (rf/reg-resource :t/feed
    {:scope         {:from-db :t/session}
     :params-schema [:map [:page :int]]}
    (fn [{:keys [page]} _ctx]
      {:request {:method :get :url "/feed" :params {:page page}}})))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter :init-fn init!}
       :cljs {:adapter rf.adapter.reagent/adapter :init-fn init!})))

(defn- runtime-db-with
  "One `:loaded` entry under `scoped-key`, in the runtime's byte-keyed shape."
  [scoped-key data]
  {rf.resources.state/resources-key
   {:entries     {(rf.resources.state/key-id scoped-key)
                  (merge (rf.resources.state/empty-entry (second scoped-key))
                         {:status :loaded :data data :loaded-at 1000 :stale-at 9.0e15
                          :resource/key scoped-key})}
    :tag-index   {} :owner-index {}}})

(defn- project [runtime-db]
  (rf/with-frame frame-id (rf.resources.ssr/project-resources-runtime-db runtime-db)))

(deftest ssr-no-inheritance-resource-serializes
  (let [k  (rf.resources.state/scoped-resource-key [:rf.scope/session {:username "jake"}] :t/feed {:page 1})
        we (val (first (get-in (project (runtime-db-with k {:articles [:a :b]}))
                               [rf.resources.state/resources-key :entries])))]
    (is (= [k {:articles [:a :b]}] [(:resource/key we) (:data we)])
        "the key and data the author did not classify ride verbatim")))

(deftest ssr-owner-declared-sensitive-redacts-via-own-claim
  ;; the control: the owner's own coarse claim, not propagation. Tokenizing
  ;; both scope and params leaves an identity no live client derives, so the
  ;; row is withheld outright.
  (rf/reg-resource :t/secret-feed
    {:scope         {:from-db :t/session}
     :sensitive?    true
     :params-schema [:map [:page :int]]}
    (fn [_ _] {:request {:method :get :url "/secret"}}))
  (let [k    (rf.resources.state/scoped-resource-key [:rf.scope/session {:username "jake"}] :t/secret-feed {:page 1})
        rdb  (runtime-db-with k {:articles [:a :b]})
        proj (project rdb)
        m    (rf/with-frame frame-id
               (first (rf.resources.ssr/projection-metadata
                        frame-id 5000 (get-in rdb [rf.resources.state/resources-key :entries]))))
        wk   (:projected-key m)]
    (is (= [:redacted true true :t/secret-feed true true false]
           [(:disposition m) (:withheld? m) (empty? (get-in proj [rf.resources.state/resources-key :entries]))
            (nth wk 1) (contains? (nth wk 0) :rf/redacted) (contains? (nth wk 2) :rf/redacted)
            (str/includes? (pr-str proj) "jake")])
        "the entry is redacted by its own claim, its key tokenized, its row withheld, the identity nowhere")))
