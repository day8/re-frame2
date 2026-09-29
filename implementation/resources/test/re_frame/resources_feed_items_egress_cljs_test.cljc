(ns re-frame.resources-feed-items-egress-cljs-test
  "An infinite feed's MERGED item list redacts, on `:rf.sub/run`, the fields
  its owner declares against the PAGE — through the feed's `:page->items`
  accessor, keyword or callable.

  An enveloped feed declares `[:data :items :ssn]`: the page is
  `{:items [...] ...}`, and the accessor takes the `:items` level away when it
  merges the pages. So `:rf.resource/items` and `:rf.resource/infinite-state`'s
  `:items` carry bare items, where the page-relative path names nothing, while
  `:rf.resource/pages` still carries the page it was written against and is the
  control that the declaration is live.

  The in-process reads stay raw: classification applies at egress only.

  Every assertion read off the trace bus sits inside a
  `(when rf.interop/debug-enabled? …)` arm: `trace/emit!` is dev
  instrumentation, and the negative census assertions would pass vacuously
  with no trace to read.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex, Shadow's `:node-test` build via the `cljs-test$` regex."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.interop :as rf.interop]
   [re-frame.privacy :as rf.privacy]
   ;; load-bearing side-effecting requires: the facade registers the
   ;; :rf.resource/* events and subs and publishes the read-sub egress
   ;; projector; schemas binds the shared walker hooks.
   [re-frame.resources]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

;; ---- capturing transport --------------------------------------------------

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture
  "Replace the real `:rf.http/managed` fx with one that records its args, so a
  test replies through the request's own `:on-success` continuation."
  [f]
  (reset! last-managed-args nil)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ _] nil))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-transport-fixture)

(def ^:private ssn-0 "feed-items-ssn-PAGE-ZERO")
(def ^:private ssn-1 "feed-items-ssn-PAGE-ONE")

;; ---- helpers --------------------------------------------------------------

(defn- reply!
  "Reply success with `value` through the last request's `:on-success`."
  [value]
  (rf/dispatch-sync (conj (:on-success @last-managed-args) {:status :ok :value value})))

(defn- capture-traces
  "Run `f` and return every trace event emitted meanwhile, always unregistering
  the listener."
  [f]
  (let [captured (atom [])]
    (rf/register-listener! :trace ::capture (fn [ev] (swap! captured conj ev)))
    (try
      (f)
      @captured
      (finally
        (rf/unregister-listener! :trace ::capture)))))

(defn- sub-runs
  [events]
  (filterv #(= :rf.sub/run (:operation %)) events))

(defn- last-run-tags
  "The tags of the LAST `:rf.sub/run` trace for `query-v` in `events`."
  [query-v events]
  (let [runs (filterv #(= query-v (get-in % [:tags :rf.sub/query-v])) (sub-runs events))]
    (is (seq runs) (str query-v " recomputed inside the window"))
    (:tags (peek runs))))

(defn- carries?
  [secret v]
  (str/includes? (pr-str v) secret))

(defn- hold
  "Subscribe to every query vector in `qvs` and return a thunk reading them
  all, keyed by query vector."
  [qvs]
  (let [held (into {} (map (fn [qv] [qv (rf/subscribe qv)])) qvs)]
    (fn [] (into {} (map (fn [[qv r]] [qv @r])) held))))

(def ^:private redacted rf.privacy/redacted-sentinel)

(defn- page
  "An enveloped page: its items beside a cursor the feed pages by."
  [items next-cursor]
  {:items items :cursor next-cursor})

(defn- reg-feed!
  "An enveloped infinite feed paging by its `:cursor`, declaring each item's
  `:ssn` sensitive, merged through `accessor`."
  [resource-id accessor declaration]
  (rf/reg-resource resource-id
    {:scope           :rf.scope/global
     :infinite        true
     :params-schema   [:map]
     :page->items     accessor
     :next-page-param (fn [last-page _pages] (:cursor last-page))
     :sensitive       declaration}
    (fn [_ _] {:request {:method :get :url "/feed"}})))

;; ===========================================================================
;; The merged list takes the page declaration through the accessor
;; ===========================================================================

(defn- assert-merged-items-redact
  "Load page 0, then load page 1, of an enveloped feed merged by `accessor`,
  and check every carrier of the merged list redacts the declared field."
  [resource-id accessor]
  (reg-feed! resource-id accessor [[:data :items :ssn]])
  (let [q       {:resource resource-id :scope :rf.scope/global :params {}}
        items-q [:rf.resource/items q]
        pages-q [:rf.resource/pages q]
        state-q [:rf.resource/infinite-state q]
        read!   (hold [items-q pages-q state-q])
        _       (read!)
        load    (capture-traces
                  (fn []
                    (rf/dispatch-sync [:rf.resource/ensure (assoc q :owner [:app :feed])])
                    (reply! (page [{:ssn ssn-0 :name "zero"}] "c1"))
                    (read!)))
        more    (capture-traces
                  (fn []
                    (rf/dispatch-sync [:rf.resource/load-more (assoc q :cause [:user :more])])
                    (reply! (page [{:ssn ssn-1 :name "one"}] nil))
                    (read!)))
        live    (read!)]
    (testing "the in-process reads stay raw"
      (is (= [{:ssn ssn-0 :name "zero"} {:ssn ssn-1 :name "one"}] (get live items-q)))
      (is (= ssn-1 (get-in live [state-q :items 1 :ssn]))))
    (when rf.interop/debug-enabled?
      (testing "control: the raw pages redact the field the page declares"
        (is (= [(page [{:ssn redacted :name "zero"}] "c1")]
               (:rf.sub/value (last-run-tags pages-q load)))))
      (testing "the merged list redacts the declared field and keeps its siblings"
        (is (= [{:ssn redacted :name "zero"}]
               (:rf.sub/value (last-run-tags items-q load))))
        (let [state (:rf.sub/value (last-run-tags state-q load))]
          (is (= [{:ssn redacted :name "zero"}] (:items state)))
          (is (= [(page [{:ssn redacted :name "zero"}] "c1")] (:pages state)))))
      (testing "after a load-more, the prior merged list is redacted too"
        (let [items (last-run-tags items-q more)
              state (last-run-tags state-q more)]
          (is (= [{:ssn redacted :name "zero"}] (:rf.sub/prev-value items)))
          (is (= [{:ssn redacted :name "zero"} {:ssn redacted :name "one"}]
                 (:rf.sub/value items)))
          (is (= [{:ssn redacted :name "zero"}] (get-in state [:rf.sub/prev-value :items])))
          (is (= [{:ssn redacted :name "zero"} {:ssn redacted :name "one"}]
                 (get-in state [:rf.sub/value :items])))))
      (testing "no sub run in either window carries either secret"
        (doseq [window [load more]]
          (is (seq (sub-runs window)))
          (is (not (carries? ssn-0 (sub-runs window))))
          (is (not (carries? ssn-1 (sub-runs window)))))))))

(deftest a-keyword-page-accessors-merged-items-redact-the-page-declaration
  (assert-merged-items-redact :feed/keyword-accessor :items))

(deftest a-callable-page-accessors-merged-items-redact-the-page-declaration
  (assert-merged-items-redact :feed/callable-accessor (fn [pg] (:items pg))))

(deftest a-declaration-covering-a-pages-whole-item-list-redacts-the-merged-list
  (testing "the accessor finds no items left to merge, so the whole list redacts"
    (reg-feed! :feed/sealed-items (fn [pg] (:items pg)) [[:data :items]])
    (let [q       {:resource :feed/sealed-items :scope :rf.scope/global :params {}}
          items-q [:rf.resource/items q]
          read!   (hold [items-q])
          _       (read!)
          events  (capture-traces
                    (fn []
                      (rf/dispatch-sync [:rf.resource/ensure (assoc q :owner [:app :feed])])
                      (reply! (page [{:ssn ssn-0 :name "zero"}] nil))
                      (read!)))]
      (is (= [{:ssn ssn-0 :name "zero"}] (get (read!) items-q)) "the in-process read stays raw")
      (when rf.interop/debug-enabled?
        (is (= redacted (:rf.sub/value (last-run-tags items-q events))))
        (is (not (carries? ssn-0 (sub-runs events))))))))
