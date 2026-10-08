(ns re-frame.resources-machine-owner-release-cljs-test
  "A destroyed state-machine actor releases its resource owner
  [:machine actor-id] (Spec 016 §Release authority is per owner kind), whether
  destroyed explicitly or by reaching a :final? state, and releases only its
  own owner. Machine teardown dispatches :rf.resource/release-owner by keyword,
  since machines never requires resources; machines is a test-only dep here."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.machines]
   [re-frame.machines.paths :as rf.machines.paths]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as substrate]]
       :cljs [[re-frame.adapter.reagent :as substrate]])))

(defn- capturing-fixture
  [f]
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf.fx/reg-fx :rf.http/managed-abort (fn [_ctx _wid] nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ctx _args] nil))
  ;; ensures here pass an explicit :scope; the resolver's slot stays unwritten
  (rf/reg-resource-scope :t/caller-scope
    {:inputs {:scope [:db [:t/scope]]}}
    (fn [{:keys [scope]} _ctx] scope))
  (rf/reg-resource :art/by-slug
    {:scope         {:from-db :t/caller-scope}
     :params-schema [:map [:slug :string]]
     :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
    (fn [{:keys [slug]} _ctx]
      {:request {:method :get :url (str "/api/articles/" slug)}}))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter})
  capturing-fixture)

(def ^:private scope {:app :reader})
(def ^:private owner [:machine :reader/proc])

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- slug-key [slug]
  (rf.resources.state/scoped-resource-key scope :art/by-slug {:slug slug}))
(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))
(defn- machine-snapshot [id]
  (get-in (runtime-db) (rf.machines.paths/snapshot-path id)))
(defn- owner-index
  "The owner index with its byte key-id members mapped back to scoped keys."
  []
  (let [rdb    (runtime-db)
        es     (get-in rdb (rf.resources.state/entries-path))
        id->sk (into {} (map (fn [[k-id e]] [k-id (:resource/key e)])) es)]
    (into {} (map (fn [[owner members]]
                    [owner (into #{} (map #(get id->sk % %)) members)]))
          (get-in rdb (rf.resources.state/owner-index-path)))))
(defn- succeed! [scoped-key data]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key scoped-key :work/id (:current-work e)
                        :generation (:generation e) :data data}])))

(defn- start-reader!
  "Register and start a singleton :reader/proc whose initial state ensures
  `slug` under [:machine :reader/proc] (a singleton's actor id is its machine
  id), then settle the load. `extra-states` and `reading-on` shape the rest."
  [slug extra-states reading-on]
  (rf/reg-machine :reader/proc
    {:initial :reading
     :data    {:slug slug}
     :states  (merge
                {:reading
                 {:entry (fn [{{s :slug} :data}]
                           {:fx [[:dispatch
                                  [:rf.resource/ensure
                                   {:resource :art/by-slug
                                    :scope    scope
                                    :params   {:slug s}
                                    :owner    owner
                                    :cause    [:machine-action :reader/read]}]]]})
                  :on reading-on}}
                extra-states)})
  (rf/dispatch-sync [:reader/proc [:rf.machine/start]])
  (succeed! (slug-key slug) {:title slug}))

(defn- destroy-reader! []
  (rf/reg-event ::destroy-reader (fn [_ _] {:fx [[:rf.machine/destroy :reader/proc]]}))
  (rf/dispatch-sync [::destroy-reader]))

(defn- released?
  "[actor gone, entry owner-free, owner unindexed]"
  [k]
  [(nil? (machine-snapshot :reader/proc)) (empty? (:active-owners (entry k))) (nil? (get (owner-index) owner))])

(deftest explicit-actor-destroy-releases-machine-owned-resource-owner
  (let [k (slug-key "resources-101")]
    (start-reader! "resources-101" {:idle {}} {:stop :idle})
    (is (= [true true true]
           [(some? (machine-snapshot :reader/proc)) (contains? (:active-owners (entry k)) owner)
            (contains? (get (owner-index) owner) k)])
        "precondition: the live actor owns the entry")
    (destroy-reader!)
    (is (= [true true true] (released? k)) "the owner does not outlive the actor")))

(deftest final-state-auto-destroy-releases-machine-owned-resource-owner
  ;; the other teardown path: finalize-machine appends the release to its :fx
  (let [k (slug-key "owners-vs-causes")]
    (start-reader! "owners-vs-causes" {:done {:final? true}} {:finish :done})
    (is (contains? (:active-owners (entry k)) owner) "precondition: owned before finish")
    (rf/dispatch-sync [:reader/proc [:finish]])
    (is (= [true true true] (released? k)) "the auto-destroy released the owner too")))

(deftest actor-destroy-release-is-scoped-to-the-destroyed-actor
  (let [ka      (slug-key "a")
        kb      (slug-key "b")
        sibling [:machine :other/proc]]
    (start-reader! "a" {:idle {}} {:stop :idle})
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :art/by-slug :scope scope :params {:slug "b"} :owner sibling}])
    (succeed! kb {:title "B"})
    (destroy-reader!)
    (is (= [true true true] (released? ka)) "A's owner released")
    (is (= [true true] [(contains? (:active-owners (entry kb)) sibling)
                        (contains? (get (owner-index) sibling) kb)])
        "the sibling owner on B is untouched and still indexed")))
