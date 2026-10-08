(ns re-frame.resources-infinite-initial-page-param-cljs-test
  "A non-nil :initial-page-param rides into the page-0 request and is recorded
  as page 0's :page-params entry (Spec 016 §Causal event — load-more). Every
  other suite uses the nil default, so a page-0 fetch that ignored the
  override would pass them all."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture
  [f]
  (reset! last-managed-args nil)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-transport-fixture)

(defn- page [items next-c] {:items items :page-info {:next-cursor next-c}})

(deftest initial-page-param-rides-into-page-0-and-is-recorded
  (rf/reg-resource :ipp/feed
    {:scope              :rf.scope/global
     :infinite           true
     :params-schema      [:map [:filter :keyword]]
     :next-page-param    (fn [last-page _all-pages] (get-in last-page [:page-info :next-cursor]))
     :page->items        :items
     :initial-page-param "p0"
     :tags               (fn [{:keys [filter]} _data] #{[:feed filter]})}
    ;; the request surfaces the runtime-threaded page param as :cursor
    (fn [{:keys [filter]} {:rf.resource/keys [page-param page-index]}]
      {:request {:method :get :url "/api/feed"
                 :params (cond-> {:filter filter :page-index page-index}
                           page-param (assoc :cursor page-param))}}))
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :ipp/feed :scope :rf.scope/global
                      :params {:filter :recent} :owner [:test :w]}])
  (is (= {:page-index 0 :cursor "p0"}
         (select-keys (get-in @last-managed-args [:request :params]) [:page-index :cursor]))
      "the override rode into the page-0 request")
  (rf/dispatch-sync (conj (:on-success @last-managed-args) {:status :ok :value (page [:a :b] "c1")}))
  (let [k (rf.resources.state/scoped-resource-key :rf.scope/global :ipp/feed {:filter :recent})
        e (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) (rf.resources.state/entry-path k))]
    (is (= [:loaded [(page [:a :b] "c1")] ["p0"] "c1"]
           ((juxt :status :data :page-params :next-page-param) e))
        "page 0 records the override as its param, and the cursor advances from the page")))
