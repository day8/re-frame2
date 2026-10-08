(ns re-frame.resources-infinite-ssr-restore-cljs-test
  "An infinite feed is an ordinary resource entry whose :data is the page
  vector, so it rides the same SSR projection and epoch-restore reconcile as a
  scalar entry (Spec 016 §Durable cache shape, §SSR and hydration, §Restore and
  replay). A three-page feed with a load-more in flight goes through both: the
  page facts survive verbatim, and the vanished load-more settles rather than
  dangling as a phantom fetching-next?."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.frame :as rf.frame]
   [re-frame.resources]
   [re-frame.resources.ssr :as rf.resources.ssr]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.subs :as rf.resources.subs]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private next-cursor
  (fn [last-page _all-pages] (get-in last-page [:page-info :next-cursor])))

(def ^:private prev-cursor
  (fn [first-page _all-pages] (get-in first-page [:page-info :prev-cursor])))

(defn- page
  "An enveloped page, so flattening needs the registered :page->items."
  ([items next-c] (page items next-c nil))
  ([items next-c prev-c]
   {:items items :page-info {:next-cursor next-c :prev-cursor prev-c}}))

(defn- reg-feed! []
  (rf/clear :resource :feed/timeline)
  (rf/reg-resource :feed/timeline
    {:scope           :rf.scope/global
     :infinite        true
     :params-schema   [:map [:filter :keyword]]
     :next-page-param next-cursor
     :prev-page-param prev-cursor
     :page->items     :items
     :tags            (fn [{:keys [filter]} _data] #{[:feed filter]})}
    (fn [{:keys [filter]} {:rf.resource/keys [page-param page-index]}]
      {:request {:method :get :url "/api/feed"
                 :params (cond-> {:filter filter :page-index page-index}
                           page-param (assoc :cursor page-param))}})))

(def ^:private fkey
  (rf.resources.state/scoped-resource-key :rf.scope/global :feed/timeline {:filter :recent}))

;; three non-terminal pages: a fourth exists at cursor "c3"
(def ^:private p0 (page [:a :b] "c1" "c0"))
(def ^:private p1 (page [:c :d] "c2"))
(def ^:private p2 (page [:e :f] "c3"))

(defn- loaded-feed-entry
  "The three pages appended through the real pure transition, :loaded and fresh."
  []
  (reduce (fn [e [pg param loaded-at]]
            (rf.resources.state/entry-append-page
              e {:page pg :page-param param :next-page-param-fn next-cursor
                 :prev-page-param-fn prev-cursor :loaded-at loaded-at :stale-at 9.0e15}))
          (rf.resources.state/empty-infinite-entry :feed/timeline fkey)
          [[p0 nil 1000] [p1 "c1" 1100] [p2 "c2" 1200]]))

;; the load-more appends at page index 3, the evidence fetching-next? reads
(def ^:private load-more-wid (rf.resources.work-ledger/resource-work-id fkey 7))

(defn- feed-with-load-more-in-flight
  "A runtime-db holding the feed :fetching under `load-more-wid`, with a
  :running page-3 work row stamped for `frame-id`."
  [frame-id]
  {rf.resources.state/resources-key
   {:entries   {(rf.resources.state/key-id fkey)
                (assoc (loaded-feed-entry) :status :fetching :current-work load-more-wid :resource/key fkey)}
    :tag-index {} :owner-index {}}
   rf.resources.state/work-ledger-key
   {(rf.resources.work-ledger/work-id-id load-more-wid)
    (-> (rf.resources.work-ledger/work-record
          {:work-id load-more-wid :frame-id frame-id :resource/key fkey
           :generation 7 :transport :rf.http/managed :started-at 1300 :page-index 3})
        (assoc :status :running))}})

(defn- snapshot-of [entry]
  {rf.resources.state/resources-key {:entries   {(rf.resources.state/key-id fkey) (assoc entry :resource/key fkey)}
                                     :tag-index {} :owner-index {}}})

(defn- fkey-entry [rdb]
  (get-in rdb [rf.resources.state/resources-key :entries (rf.resources.state/key-id fkey)]))

(def ^:private expected-pages [p0 p1 p2])
(def ^:private expected-items [:a :b :c :d :e :f])

(def ^:private page-facts (juxt :data :page-params :next-page-param :prev-page-param))
(def ^:private expected-page-facts [expected-pages [nil "c1" "c2"] "c3" "c0"])

(defn- merged-items*
  "The framework-owned merged-items projection over `entry`."
  [entry]
  (#'rf.resources.subs/merged-items entry 'rf.resource/items))

(deftest projection-ships-page-vector-and-infinite-facts-verbatim
  (reg-feed!)
  (let [proj (rf.resources.ssr/project-resources-runtime-db (feed-with-load-more-in-flight :app/main))
        es   (get-in proj [rf.resources.state/resources-key :entries])
        we   (val (first es))]
    (is (= [#{rf.resources.state/resources-key} #{:entries} 1]
           [(set (keys proj)) (set (keys (get proj rf.resources.state/resources-key))) (count es)])
        "only the resources :entries ride, the whole feed in one slot")
    (is (= [true expected-page-facts nil :fetching false]
           [(:infinite? we) (page-facts we) (:page-error we) (:status we) (contains? we :current-work)])
        "every page fact rides verbatim; the in-flight :current-work pointer is stripped")))

(deftest restore-rehydrates-page-vector-and-cursor-intact
  (reg-feed!)
  (let [out (rf.resources.ssr/reconcile-on-restore (feed-with-load-more-in-flight :app/main) :app/main)
        e   (fkey-entry out)
        row (get-in out [rf.resources.state/work-ledger-key (rf.resources.work-ledger/work-id-id load-more-wid)])]
    (is (= [true expected-page-facts false expected-items]
           [(rf.resources.state/infinite-entry? e) (page-facts e)
            (rf.resources.state/terminal? (:next-page-param e)) (merged-items* e)])
        "pages, params, cursor and prev mirror rehydrate intact, in order")
    (is (= [:loaded nil :suppressed :dangling]
           [(:status e) (:current-work e) (:status row) (get-in row [:outcome :reason])])
        "the vanished load-more settles to last-stable :loaded and its row dangles")))

(deftest restore-fetching-next?-resolves-false-no-phantom-load-more
  ;; through the real reconcile, a live frame and the live subs
  (reg-feed!)
  (let [fid        :restore/infinite-fetching-next
        reconciled (rf.resources.ssr/reconcile-on-restore (feed-with-load-more-in-flight fid) fid)]
    (rf/make-frame {:id fid :doc "restore infinite fetching-next? frame"})
    (rf.frame/replace-runtime-db! fid reconciled)
    (let [q   {:resource :feed/timeline :scope :rf.scope/global :params {:filter :recent}}
          sub #(deref (rf/subscribe [% q] {:frame fid}))
          vm  (sub :rf.resource/infinite-state)]
      (is (= [expected-items expected-pages 3 true nil false]
             (map sub [:rf.resource/items :rf.resource/pages :rf.resource/page-count
                       :rf.resource/has-next-page? :rf.resource/page-error :rf.resource/fetching-next?]))
          "the feed reads intact and no phantom load-more is in flight")
      (is (= {:fetching-next? false :fetching? false :status :loaded :items expected-items :has-data? true}
             (select-keys vm [:fetching-next? :fetching? :status :items :has-data?]))))
    (rf.frame/destroy-frame! fid)))

(deftest restore-with-page-error-rehydrates-third-error-channel
  (reg-feed!)
  (let [failed (rf.resources.state/entry-page-failed (loaded-feed-entry) {:error {:kind :rf.http/server :status 503}})
        e      (fkey-entry (rf.resources.ssr/reconcile-on-restore (snapshot-of failed) :app/main))]
    (is (= [{:kind :rf.http/server :status 503} nil nil expected-pages :loaded]
           ((juxt :page-error :error :refresh-error :data :status) e))
        "the page-error channel survives restore, distinct from :error and :refresh-error, with the pages kept")))

(deftest ssr-round-trip-project-then-hydrate-rehydrates-feed-intact
  (reg-feed!)
  (let [projected (rf.resources.ssr/project-resources-runtime-db (feed-with-load-more-in-flight :app/main))
        out       (rf.resources.ssr/hydrate-runtime-db projected :app/main)
        e         (fkey-entry out)]
    (is (= [expected-pages "c3" expected-items :loaded nil]
           [(:data e) (:next-page-param e) (merged-items* e) (:status e) (:current-work e)])
        "the feed survives project then hydrate, the dangling :fetching settled to :loaded")
    (is (not (contains? (into #{} (map :resource/key) (rf.resources.ssr/hydrate-refetch-plan out 5000)) fkey))
        "a fresh accumulated feed is not refetched on the client")))
