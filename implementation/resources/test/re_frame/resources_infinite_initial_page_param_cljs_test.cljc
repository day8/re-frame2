(ns re-frame.resources-infinite-initial-page-param-cljs-test
  "EVENT/runtime coverage for the infinite-feed `:initial-page-param` override
  (EP-0021 R8, Spec 016 §Causal event — load-more — the page-0 cursor, the
  TanStack `initialPageParam` analogue).

  The nil default is pinned by the load-more suite's page-0 request (no
  cursor sent). This suite pins the override: that a non-nil
  `:initial-page-param` actually RIDES
  into the page-0 request and is recorded as page-0's `:page-params` entry.
  Ignoring the override on the page-0 fetch (e.g. hardcoding nil) would pass
  every default-param test — every other event/example test uses the
  framework `nil` default.

  In `ensure-load` (events.cljc) the page-0 param is
  `(rf.resources.state/page-param-for-spec spec)` and the reserved request ctx is
  `(page-request-ctx page-param 0)`, which the resource's `:request` fn reads
  via `{:rf.resource/keys [page-param page-index]}`. The capturing transport
  below REPLAYS the live managed-HTTP reply shape (Spec 014 §Reply addressing)
  so the page reply handler runs against the genuine 3-element event."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   ;; production HTTP fx surface (so the transport feature probe resolves);
   ;; the fetch itself is overridden by the capturing reply stub below.
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

;; ---- capturing transport that REPLAYS the real reply-append shape ----------

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

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))

(defn- entry [scoped-key]
  (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))

(defn- reply-success!
  "Dispatch the captured `:on-success` reply with the transport's success
  result appended as the LAST arg — the live managed-HTTP transport shape."
  [data]
  (rf/dispatch-sync (conj (:on-success @last-managed-args) {:status :ok :value data})))

(def ^:private next-cursor
  (fn [last-page _all-pages] (get-in last-page [:page-info :next-cursor])))

(defn- page [items next-c] {:items items :page-info {:next-cursor next-c}})

(defn- feed-spec
  "A minimal valid infinite-feed spec. The `:request` fn surfaces the
  runtime-threaded page-param as the request's `:cursor` so a test can observe
  exactly which page-0 param rode into the fetch."
  [overrides]
  (merge {:scope            :rf.scope/global
          :infinite         true
          :params-schema    [:map [:filter :keyword]]
          :next-page-param  next-cursor
          :page->items      :items
          :tags             (fn [{:keys [filter]} _data] #{[:feed filter]})}
         overrides))

(def ^:private feed-spec-request
  (fn [{:keys [filter]} {:rf.resource/keys [page-param page-index]}]
    {:request {:method :get :url "/api/feed"
               :params (cond-> {:filter filter :page-index page-index}
                         page-param (assoc :cursor page-param))}}))

(defn- feed-key [resource]
  (rf.resources.state/scoped-resource-key :rf.scope/global resource {:filter :recent}))

(defn- ensure! [resource]
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource resource :scope :rf.scope/global
                      :params {:filter :recent} :owner [:test :w]}]))

;; ===========================================================================
;; :initial-page-param override RIDES into the page-0 request + page-params
;; ===========================================================================

(deftest initial-page-param-rides-into-page-0-and-is-recorded
  (rf/reg-resource :ipp/feed (feed-spec {:initial-page-param "p0"}) feed-spec-request)
  (ensure! :ipp/feed)
  (testing "a non-nil :initial-page-param is threaded into the page-0 FETCH —
            the request carries it as the derived page-0 cursor (R8)"
    (let [req-params (get-in @last-managed-args [:request :params])]
      (is (= 0 (:page-index req-params)) "still the page-0 fetch (index 0)")
      (is (= "p0" (:cursor req-params))
          "the :initial-page-param override rode into the page-0 request's cursor")))
  (reply-success! (page [:a :b] "c1"))
  (testing "after the page-0 reply settles, :page-params records the OVERRIDE
            (not nil) for page 0 — the durable cursor fact (R8)"
    (let [e (entry (feed-key :ipp/feed))]
      (is (= :loaded (:status e)))
      (is (= [(page [:a :b] "c1")] (:data e)) "page-0 accumulated")
      (is (= ["p0"] (:page-params e))
          "page-0's recorded param is the override 'p0', NOT the framework nil default")
      (is (= "c1" (:next-page-param e)) "cursor then advances from the page envelope"))))
