(ns re-frame.resources-time-ms-cofx-cljs-test
  "Every resource and mutation handler that consumes the causal `:rf/time-ms`
  declares it in `:rf.cofx/requires`, so it is delivered flat (EP-0017
  declared-only delivery); and a suppressed failure still records the causal
  `:completed-at`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture [f]
  (reset! last-managed-args nil)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-transport-fixture)

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))
(defn- record [wid] (rf.resources.work-ledger/get-record (runtime-db) wid))

(defn- article-spec []
  {:scope          :rf.scope/global
   :params-schema  [:map [:slug :string]]
   :stale-after-ms 60000
   :tags           (fn [{:keys [slug]} _data] #{[:article slug]})})

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

(def ^:private time-consuming-resource-events
  [:rf.resource/ensure
   :rf.resource/refetch
   :rf.resource/invalidate-tags
   :rf.resource/window-focused
   :rf.resource/network-reconnected
   :rf.resource.internal/succeeded
   :rf.resource.internal/failed
   :rf.resource.internal/stale-fired
   :rf.mutation/execute
   :rf.mutation.internal/succeeded
   :rf.mutation.internal/failed])

(deftest time-consuming-handlers-declare-time-ms-cofx
  (is (= [] (remove #(contains? (set (:rf.cofx/requires
                                       (rf/handler-meta {:source :store :kind :event :id %})))
                                :rf/time-ms)
                    time-consuming-resource-events))))

(deftest stale-suppressed-failure-carries-completed-at
  (rf/reg-resource :sf/article (article-spec) article-spec-request)
  (let [scoped-key (rf.resources.state/scoped-resource-key :rf.scope/global :sf/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :sf/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:app :sf 1]}])
    (let [wid1 (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource/refetch {:resource :sf/article :scope :rf.scope/global
                                               :params {:slug "w"}}])
      (rf/dispatch-sync [:rf.resource.internal/failed
                         {:resource/key scoped-key :work/id wid1 :generation 1
                          :error {:kind :rf.http/http-5xx :status 503}}]
                        {:rf.cofx {:rf/time-ms 1781444444444}})
      (is (= [:suppressed 1781444444444]
             ((juxt :status (comp :completed-at :outcome)) (record wid1)))))))
