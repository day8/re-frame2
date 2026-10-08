(ns re-frame.resources-feed-reply-egress-cljs-test
  "An infinite feed's read continuation reply redacts, off-box, the item
  fields its owner declares against the PAGE.

  The reply's `:value` is the feed's MERGED item list. An enveloped feed
  declares `[:data :items :ssn]` against a page `{:items [...] ...}`, and the
  `:page->items` accessor has taken the `:items` level away from the merged
  list, so the page-relative path names nothing in it. Through a keyword
  accessor the declared path is read both without the accessor key and as
  written, because a vector page merges by identity and never reaches the
  accessor, so its items keep the key. Through a callable
  accessor nothing says where a declared field lands, so a data declaration
  covers the whole `:value`: a sensitive one redacts it and a large one elides
  it, the sensitive one winning where both are declared. A vector-page feed's
  items are its
  pages' elements, so its declaration reads as written.

  The reply is read where it leaves the box: in the fx carrier slots
  (`:rf.fx/args`, `:rf.event/fx`) projected by
  `rf.resources.trace-egress/project-fx-args-egress`, the hook body the epoch
  tool-pair runs. The app's own `:reply-to` handler receives the raw body.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex, Shadow's `:node-test` build via the `cljs-test$` regex."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.privacy :as rf.privacy]
   ;; load-bearing side-effecting requires: the facade registers the
   ;; :rf.resource/* events; schemas binds the shared walker hooks.
   [re-frame.resources]
   [re-frame.resources.trace-egress :as rf.resources.trace-egress]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

;; ---- capturing transport --------------------------------------------------

(def ^:private last-managed-args (atom nil))

(def ^:private delivered
  "The replies the app's own `:reply-to` handler received, in process."
  (atom []))

(defn- capturing-transport-fixture
  "Replace the real `:rf.http/managed` fx with one that records its args, so a
  test replies through the request's own `:on-success` continuation."
  [f]
  (reset! last-managed-args nil)
  (reset! delivered [])
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ _] nil))
  (rf/reg-event :app/feed-loaded (fn [_ [_ reply]] (swap! delivered conj reply) {}))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-transport-fixture)

(def ^:private ssn "feed-reply-ssn-SECRET")

(def ^:private redacted rf.privacy/redacted-sentinel)

;; ---- helpers --------------------------------------------------------------

(def ^:private carrier-slot
  "The two FX carrier slots a continuation reply rides out on."
  [:rf.fx/args :rf.event/fx])

(defn- carrier-rows
  "Run `f` and return every trace row carrying an fx carrier slot, projected
  for off-box egress."
  [f]
  (let [captured (atom [])]
    (rf/register-listener! :trace ::capture (fn [ev] (swap! captured conj ev)))
    (try
      (f)
      (finally
        (rf/unregister-listener! :trace ::capture)))
    (into []
          (comp (filter (fn [ev] (some #(contains? (:tags ev) %) carrier-slot)))
                (map #(update % :tags rf.resources.trace-egress/project-fx-args-egress
                              (or (:rf.frame/id (:tags %)) :rf/default))))
          @captured)))

(defn- read-replies
  "Every read continuation reply reachable inside the carrier slots of `rows`,
  harvested by the reply's own marker."
  [rows]
  (let [found (atom [])
        walk  (fn walk [v]
                (cond
                  (map? v)  (do (when (= :resource (:rf.reply/work-kind v))
                                  (swap! found conj v))
                                (run! walk (vals v)))
                  (coll? v) (run! walk v)))]
    (doseq [tags (map :tags rows) slot carrier-slot :when (contains? tags slot)]
      (walk (get tags slot)))
    @found))

(defn- settle-feed
  "Register feed `resource-id` under `spec`, ensure it with a `:reply-to`, and
  reply with `page`. Returns the projected carrier rows of the reply."
  [resource-id spec page]
  (rf/reg-resource resource-id
    (merge {:scope           :rf.scope/global
            :infinite        true
            :params-schema   [:map]
            :next-page-param (fn [_ _] nil)}
           spec)
    (fn [_ _] {:request {:method :get :url "/feed"}}))
  (rf/dispatch-sync [:rf.resource/ensure {:resource resource-id :scope :rf.scope/global
                                          :params {} :owner [:app :feed]
                                          :reply-to [:app/feed-loaded]}])
  (carrier-rows
    #(rf/dispatch-sync (conj (:on-success @last-managed-args) {:status :ok :value page}))))

(defn- assert-reply-value
  "The projected replies in `rows` carry `expected` as their `:value` and no
  carrier carries `secret`, while the in-process reply carries the raw merged
  items `raw`."
  ([rows expected]
   (assert-reply-value rows expected [{:ssn ssn :name "zero"}] ssn))
  ([rows expected raw secret]
   (let [replies (read-replies rows)]
     (is (seq replies) "the continuation reply rides a carrier slot")
     (doseq [reply replies]
       (if (fn? expected)
         (is (expected (:value reply)) (pr-str (:value reply)))
         (is (= expected (:value reply)))))
     (is (not (str/includes? (pr-str (map :tags rows)) secret)) "no carrier carries the secret"))
   (is (= [raw] (map :value @delivered))
       "the app's own :reply-to handler receives the raw merged items")))

;; ===========================================================================

(deftest control-a-vector-page-feeds-reply-redacts-the-declaration-as-written
  (assert-reply-value
    (settle-feed :feed-reply/vector {:sensitive [[:data :ssn]]}
                 [{:ssn ssn :name "zero"}])
    [{:ssn redacted :name "zero"}]))

(deftest a-keyword-accessor-feeds-reply-redacts-the-declaration-through-the-accessor
  (assert-reply-value
    (settle-feed :feed-reply/keyword {:page->items :items :sensitive [[:data :items :ssn]]}
                 {:items [{:ssn ssn :name "zero"}] :cursor nil})
    [{:ssn redacted :name "zero"}]))

(deftest a-callable-accessor-feeds-reply-redacts-its-whole-value
  (testing "nothing says where a declared field lands, so the whole :value redacts"
    (assert-reply-value
      (settle-feed :feed-reply/callable {:page->items (fn [pg] (:items pg))
                                         :sensitive   [[:data :items :ssn]]}
                   {:items [{:ssn ssn :name "zero"}] :cursor nil})
      redacted)))

;; ---- a vector page under a keyword accessor --------------------------------

(def ^:private nested-page
  "A vector page whose items carry a nested field under the key a keyword
  accessor would name."
  [{:items {:ssn ssn} :name "zero"}])

(deftest a-keyword-accessor-feeds-vector-page-reply-redacts-the-declaration-as-written
  (testing "a vector page merges by identity, so its items keep the accessor key"
    (assert-reply-value
      (settle-feed :feed-reply/keyword-vector {:page->items :items :sensitive [[:data :items :ssn]]}
                   nested-page)
      [{:items {:ssn redacted} :name "zero"}]
      nested-page
      ssn)))

;; ---- the large axis --------------------------------------------------------

(def ^:private blob "feed-reply-blob-LARGE")

(defn- large-elided? [v] (and (map? v) (contains? v :rf.size/large-elided)))

(deftest control-a-keyword-accessor-feeds-reply-elides-the-large-field-through-the-accessor
  (assert-reply-value
    (settle-feed :feed-reply/keyword-large {:page->items :items :large [[:data :items :blob]]}
                 {:items [{:blob blob :name "zero"}] :cursor nil})
    (fn [v] (and (large-elided? (:blob (first v))) (= "zero" (:name (first v)))))
    [{:blob blob :name "zero"}]
    blob))

(deftest a-callable-accessor-feeds-reply-elides-its-whole-value-as-large
  (testing "nothing says where a declared large field lands, so the whole :value elides"
    (assert-reply-value
      (settle-feed :feed-reply/callable-large {:page->items (fn [pg] (:items pg))
                                               :large       [[:data :items :blob]]}
                   {:items [{:blob blob :name "zero"}] :cursor nil})
      large-elided?
      [{:blob blob :name "zero"}]
      blob))
  (testing "a sensitive declaration on the same feed still redacts the whole :value"
    (reset! delivered [])
    (assert-reply-value
      (settle-feed :feed-reply/callable-both {:page->items (fn [pg] (:items pg))
                                              :sensitive   [[:data :items :ssn]]
                                              :large       [[:data :items :blob]]}
                   {:items [{:ssn ssn :blob blob :name "zero"}] :cursor nil})
      redacted
      [{:ssn ssn :blob blob :name "zero"}]
      ssn)))
