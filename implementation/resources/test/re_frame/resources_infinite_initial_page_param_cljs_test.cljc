(ns re-frame.resources-infinite-initial-page-param-cljs-test
  "EVENT/runtime coverage for the infinite-feed `:initial-page-param` override
  (EP-0021 R8, Spec 016 §Causal event — load-more — the page-0 cursor, the
  TanStack `initialPageParam` analogue).

  The pure boundary (`state/page-param-for-spec`) is already pinned in
  `resources_infinite_state_cljs_test` (`page-0-param-default`). This slice
  closes the RUNTIME gap: that a non-nil `:initial-page-param` actually RIDES
  into the page-0 request and is recorded as page-0's `:page-params` entry. A
  regression that ignored the override on the page-0 fetch (e.g. hardcoding
  nil) would pass every default-param test — every existing event/example test
  uses the framework `nil` default.

  In `ensure-load` (events.cljc) the page-0 param is
  `(state/page-param-for-spec spec)` and the reserved request ctx is
  `(page-request-ctx page-param 0)`, which the resource's `:request` fn reads
  via `{:rf.resource/keys [page-param page-index]}`. The capturing transport
  below REPLAYS the live managed-HTTP reply shape (Spec 014 §Reply addressing)
  so the page reply handler runs against the genuine 3-element event."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.resources]
   [re-frame.resources.state :as state]
   [re-frame.resources.test-support]
   ;; production HTTP fx surface (so the transport feature probe resolves);
   ;; the fetch itself is overridden by the capturing reply stub below.
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- capturing transport that REPLAYS the real reply-append shape ----------

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture
  [f]
  (reset! last-managed-args nil)
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (f))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  capturing-transport-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (rf/runtime-db-value :rf/default))

(defn- entry [scoped-key]
  (get-in (runtime-db) (state/entry-path scoped-key)))

(defn- reply-success!
  "Dispatch the captured `:on-success` reply with the transport's success
  result appended as the LAST arg — the live managed-HTTP transport shape."
  [data]
  (rf/dispatch-sync (conj (:on-success @last-managed-args) {:kind :success :value data})))

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
  (state/scoped-resource-key :rf.scope/global resource {:filter :recent}))

(defn- ensure! [resource]
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource resource :scope :rf.scope/global
                      :params {:filter :recent} :owner [:test :w]}]))

;; ===========================================================================
;; :initial-page-param override RIDES into the page-0 request + page-params
;; ===========================================================================

(deftest initial-page-param-rides-into-page-0-request
  (testing "a non-nil :initial-page-param is threaded into the page-0 FETCH —
            the request carries it as the derived page-0 cursor (R8)"
    (rf/reg-resource :ipp/feed (feed-spec {:initial-page-param "p0"}) feed-spec-request)
    (ensure! :ipp/feed)
    (let [req-params (get-in @last-managed-args [:request :params])]
      (is (= 0 (:page-index req-params)) "still the page-0 fetch (index 0)")
      (is (= "p0" (:cursor req-params))
          "the :initial-page-param override rode into the page-0 request's cursor"))))

(deftest initial-page-param-recorded-as-page-0-param
  (testing "after the page-0 reply settles, :page-params records the OVERRIDE
            (not nil) for page 0 — the durable cursor fact (R8)"
    (rf/reg-resource :ipp2/feed (feed-spec {:initial-page-param "p0"}) feed-spec-request)
    (ensure! :ipp2/feed)
    (reply-success! (page [:a :b] "c1"))
    (let [e (entry (feed-key :ipp2/feed))]
      (is (= :loaded (:status e)))
      (is (= [(page [:a :b] "c1")] (:data e)) "page-0 accumulated")
      (is (= ["p0"] (:page-params e))
          "page-0's recorded param is the override 'p0', NOT the framework nil default")
      (is (= "c1" (:next-page-param e)) "cursor then advances from the page envelope"))))

(deftest default-initial-page-param-is-nil-baseline
  (testing "BASELINE — with no override the page-0 request carries no cursor +
            records nil (so the override assertions above are meaningful)"
    (rf/reg-resource :ippd/feed (feed-spec {}) feed-spec-request)
    (ensure! :ippd/feed)
    (is (not (contains? (get-in @last-managed-args [:request :params]) :cursor))
        "no override → page-0 request carries no cursor")
    (reply-success! (page [:a] "c1"))
    (is (= [nil] (:page-params (entry (feed-key :ippd/feed))))
        "page-0 param recorded nil by default")))
