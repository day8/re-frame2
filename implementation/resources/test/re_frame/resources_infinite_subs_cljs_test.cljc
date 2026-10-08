(ns re-frame.resources-infinite-subs-cljs-test
  "The infinite-feed subscriptions (Spec 016 §Subscription contract): the
  merged :rf.resource/items, the raw :pages, page metadata, :fetching-next? (a
  load-more in flight, distinct from a whole-feed :fetching?), the combined
  :infinite-state view-model, and the loud merge of a non-vector page with no
  :page->items. The subs are passive; they read entries the live event path
  accumulated."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.subs]
   [re-frame.resources.test-support]
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

(defn- reply-success! [data]
  (rf/dispatch-sync (conj (:on-success @last-managed-args) {:status :ok :value data})))

(defn- reply-failure! [failure]
  (rf/dispatch-sync (conj (:on-failure @last-managed-args) {:status :error :error failure})))

(def ^:private next-cursor
  (fn [last-page _all-pages] (get-in last-page [:page-info :next-cursor])))
(def ^:private prev-cursor
  (fn [first-page _all-pages] (get-in first-page [:page-info :prev-cursor])))

(defn- page
  ([items next-c] (page items next-c nil))
  ([items next-c prev-c]
   {:items items :page-info {:next-cursor next-c :prev-cursor prev-c}}))

(defn- feed-spec
  "Enveloped pages with a keyword :page->items accessor."
  ([] (feed-spec {}))
  ([overrides]
   (merge {:scope            :rf.scope/global
           :infinite         true
           :params-schema    [:map [:filter :keyword]]
           :next-page-param  next-cursor
           :prev-page-param  prev-cursor
           :page->items      :items
           :tags             (fn [{:keys [filter]} _data] #{[:feed filter]})}
          overrides)))

(def ^:private feed-spec-request
  (fn [{:keys [filter]} {:rf.resource/keys [page-param page-index]}]
    {:request {:method :get :url "/api/feed"
               :params (cond-> {:filter filter :page-index page-index}
                         page-param (assoc :cursor page-param))}}))

(defn- feed-key [resource]
  (rf.resources.state/scoped-resource-key :rf.scope/global resource {:filter :recent}))

(defn- q [resource]
  {:resource resource :scope :rf.scope/global :params {:filter :recent}})

(defn- sub [id resource] @(rf/subscribe [id (q resource)]))

(defn- ensure! [resource]
  (rf/dispatch-sync [:rf.resource/ensure (assoc (q resource) :owner [:test :w])]))

(defn- load-more! [resource]
  (rf/dispatch-sync [:rf.resource/load-more
                     (assoc (q resource) :owner [:test :w] :cause [:user :feed/load-more])]))

(defn- load-page-0!
  "Register and ensure a feed, then settle page 0 with `pg`."
  [resource pg]
  (rf/reg-resource resource (feed-spec) feed-spec-request)
  (ensure! resource)
  (reply-success! pg)
  resource)

(defn- view-model
  "The full :infinite-state view-model with `overrides` over the idle defaults."
  [overrides]
  (merge {:status :idle :items [] :pages [] :page-count 0
          :has-next-page? false :has-prev-page? false
          :loading? false :fetching? false :fetching-next? false
          :stale? false :has-data? false
          :error nil :refresh-error nil :page-error nil}
         overrides))

(deftest empty-feed-projections
  (rf/reg-resource :is1/feed (feed-spec) feed-spec-request)
  (is (= [[] [] 0 false false false nil]
         (map #(sub % :is1/feed) [:rf.resource/items :rf.resource/pages :rf.resource/page-count
                                  :rf.resource/has-next-page? :rf.resource/has-prev-page?
                                  :rf.resource/fetching-next? :rf.resource/page-error])))
  (is (= (view-model {}) (sub :rf.resource/infinite-state :is1/feed)))
  (is (nil? (entry (feed-key :is1/feed))) "subscribing conjured no entry"))

;; An infinite feed's :data is the page vector, seeded [] before page 0, so
;; the scalar subs must not read (some? :data) as having data.

(deftest scalar-has-data?-false-for-empty-infinite-feed
  (rf/reg-resource :is1b/feed (feed-spec) feed-spec-request)
  (ensure! :is1b/feed)
  (is (= [[] :loading] ((juxt :data :status) (entry (feed-key :is1b/feed))))
      "precondition: a first-loading feed with an empty page vector")
  (let [vm (sub :rf/resource :is1b/feed)]
    (is (= [false :loading false true false]
           [(sub :rf.resource/has-data? :is1b/feed) (:status vm) (:has-data? vm) (:loading? vm)
            (:has-data? (sub :rf.resource/infinite-state :is1b/feed))])
        "every public :has-data? reading agrees the empty feed has no data")))

(deftest scalar-has-data?-true-once-a-page-lands
  (load-page-0! :is1c/feed (page [:a :b] "c1"))
  (is (= [true true true]
         [(sub :rf.resource/has-data? :is1c/feed) (:has-data? (sub :rf/resource :is1c/feed))
          (:has-data? (sub :rf.resource/infinite-state :is1c/feed))])))

(deftest items-merges-pages-in-order
  (load-page-0! :is2/feed (page [:a :b] "c1"))
  (is (= [:a :b] (sub :rf.resource/items :is2/feed)))
  (load-more! :is2/feed) (reply-success! (page [:c :d] "c2"))
  (load-more! :is2/feed) (reply-success! (page [:e] nil))
  (is (= [[:a :b :c :d :e] [(page [:a :b] "c1") (page [:c :d] "c2") (page [:e] nil)]]
         [(sub :rf.resource/items :is2/feed) (sub :rf.resource/pages :is2/feed)])
      "items concatenate in load order; :pages keeps the page boundaries"))

(deftest items-vector-pages-flatten-by-identity
  ;; vector pages need no :page->items; a fixed two-page terminal
  (rf/reg-resource :isv/feed
    (dissoc (feed-spec {:next-page-param (fn [_last-page all-pages]
                                           (when (< (count all-pages) 2)
                                             (str "p" (count all-pages))))})
            :page->items)
    feed-spec-request)
  (ensure! :isv/feed) (reply-success! [:a :b])
  (is (= [:a :b] (sub :rf.resource/items :isv/feed)))
  (load-more! :isv/feed) (reply-success! [:c :d])
  (is (= [:a :b :c :d] (sub :rf.resource/items :isv/feed))))

(deftest page-metadata-projections
  (load-page-0! :is3/feed (page [:a] "c1" "p0"))
  (is (= [1 true true] (map #(sub % :is3/feed) [:rf.resource/page-count :rf.resource/has-next-page?
                                                :rf.resource/has-prev-page?]))
      "a non-terminal page with both cursors")
  (load-more! :is3/feed) (reply-success! (page [:b] nil))
  (is (= [2 false] (map #(sub % :is3/feed) [:rf.resource/page-count :rf.resource/has-next-page?]))
      "a nil next cursor is the terminal"))

(deftest has-prev-false-when-no-mirror
  (rf/reg-resource :isnp/feed (dissoc (feed-spec) :prev-page-param) feed-spec-request)
  (ensure! :isnp/feed) (reply-success! (page [:a] "c1"))
  (is (false? (sub :rf.resource/has-prev-page? :isnp/feed))))

(deftest fetching-next?-true-during-load-more
  (load-page-0! :is4/feed (page [:a] "c1"))
  (is (false? (sub :rf.resource/fetching-next? :is4/feed)) "a settled feed is not fetching-next?")
  (load-more! :is4/feed)
  (let [vm (sub :rf.resource/infinite-state :is4/feed)]
    (is (= [:fetching true true false [:a]]
           [(:status (entry (feed-key :is4/feed))) (sub :rf.resource/fetching-next? :is4/feed)
            (:fetching-next? vm) (:fetching? vm) (:items vm)])
        "a load-more in flight is fetching-next?, not a whole-feed fetching?, and keeps the items visible"))
  (reply-success! (page [:b] "c2"))
  (is (= [false [:a :b]] [(sub :rf.resource/fetching-next? :is4/feed) (sub :rf.resource/items :is4/feed)])))

(deftest fetching?-true-fetching-next?-false-on-whole-feed-refresh
  (load-page-0! :is4b/feed (page [:a] "c1"))
  (load-more! :is4b/feed) (reply-success! (page [:b] "c2"))
  (rf/dispatch-sync [:rf.resource/refetch (assoc (q :is4b/feed) :owner [:test :w])])
  (let [vm (sub :rf.resource/infinite-state :is4b/feed)]
    (is (= [:fetching false true false]
           [(:status (entry (feed-key :is4b/feed))) (:fetching-next? vm) (:fetching? vm)
            (sub :rf.resource/fetching-next? :is4b/feed)])
        "a whole-feed refetch is :fetching? and not :fetching-next?")))

(deftest page-error-projection
  (load-page-0! :is5/feed (page [:a] "c1"))
  (load-more! :is5/feed)
  (reply-failure! {:kind :rf.http/server :status 503})
  (is (= {:kind :rf.http/server :status 503} (sub :rf.resource/page-error :is5/feed)))
  (is (= {:page-error {:kind :rf.http/server :status 503} :error nil :refresh-error nil
          :items [:a] :status :loaded}
         (select-keys (sub :rf.resource/infinite-state :is5/feed)
                      [:page-error :error :refresh-error :items :status]))
      "a load-more failure is the page-error channel alone, and the feed is kept"))

(deftest infinite-state-view-model
  (load-page-0! :is6/feed (page [:a :b] "c1"))
  (load-more! :is6/feed) (reply-success! (page [:c] nil))
  (is (= (view-model {:status :loaded :items [:a :b :c] :pages [(page [:a :b] "c1") (page [:c] nil)]
                      :page-count 2 :has-data? true})
         (sub :rf.resource/infinite-state :is6/feed))))

(deftest missing-page-accessor-raises-at-merge
  ;; asserted at the merge projection: a sub-body throw is otherwise routed to
  ;; the runtime error path
  (rf/reg-resource :iserr/feed (dissoc (feed-spec) :page->items) feed-spec-request)
  (ensure! :iserr/feed)
  (reply-success! (page [:a :b] "c1"))
  (let [e (entry (feed-key :iserr/feed))]
    (is (= 1 (rf.resources.state/page-count e)) "the enveloped page accumulated; the merge is read-side")
    (testing "the framework-owned merged-items projection raises"
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
            #"infinite-missing-page-accessor"
            (#'re-frame.resources.subs/merged-items e 'rf.resource/items))))))
