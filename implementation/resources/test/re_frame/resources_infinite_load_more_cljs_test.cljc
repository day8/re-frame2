(ns re-frame.resources-infinite-load-more-cljs-test
  "The infinite-feed `:rf.resource/load-more` event, the page reply handlers and
  the refetch reset (Spec 016 §Infinite resources and load-more feeds), driven
  through the event layer. A load-more fetches the next page and appends it; a
  terminal feed, a feed with no page 0 and a load-more already in flight fire
  nothing; a superseded page reply is suppressed; a page failure keeps the feed
  and records `:page-error`; and a refetch keeps the visible window, refreshing
  page 0 in place unless `:refetch-all-pages?` or `:refetch-window` sweeps more
  pages in sequence.

  The capturing transport replays the real reply-append shape (Spec 014 §Reply
  addressing: the result is conj'd as the last arg of the internal reply
  event). The subscriptions are `resources-infinite-subs-cljs-test`'s."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   [re-frame.resources.test-support]
   ;; production HTTP fx surface (so the transport feature probe resolves);
   ;; the fetch itself is overridden by the capturing reply stub below.
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   [re-frame.trace.tooling :as rf.trace.tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private last-managed-args (atom nil))

;; the `:rf.resource/schedule-timers` fx, captured so timer arming is asserted
;; without a wall clock
(def ^:private scheduled-timers (atom []))

;; the frame-qualified request-ids of `:rf.http/managed-abort`
(def ^:private aborts (atom []))

(defn- capturing-transport-fixture
  [f]
  (reset! last-managed-args nil)
  (reset! scheduled-timers [])
  (reset! aborts [])
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf.fx/reg-fx :rf.http/managed-abort (fn [_ctx request-id] (swap! aborts conj request-id) nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ctx args] (swap! scheduled-timers conj args) nil))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-transport-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))

(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))

(defn- page-count [scoped-key] (rf.resources.state/page-count (entry scoped-key)))

(defn- reply-success!
  "Dispatch the captured `:on-success` reply with the transport's success
  result appended as the LAST arg — the live managed-HTTP transport shape."
  ([data] (reply-success! @last-managed-args data))
  ([args data]
   (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data}))))

(defn- reply-failure! [failure]
  (rf/dispatch-sync (conj (:on-failure @last-managed-args) {:status :error :error failure})))

(defn- reply-aborted!
  "An `:rf.http/aborted` envelope: an intentional cancellation, not a failure."
  []
  (reply-failure! {:kind :rf.http/aborted :reason :user}))

(defn- in-flight-page-index []
  (:rf.resource/page-index (second (:on-success @last-managed-args))))

(defn- last-schedule-for [scoped-key]
  (last (filter #(= scoped-key (:resource/key %)) @scheduled-timers)))

(defn- work-status [work-id]
  (:status (rf.resources.work-ledger/get-record (runtime-db) work-id)))

(def ^:private next-cursor
  (fn [last-page _all-pages] (get-in last-page [:page-info :next-cursor])))

(def ^:private prev-cursor
  (fn [first-page _all-pages] (get-in first-page [:page-info :prev-cursor])))

(defn- page
  "An enveloped page: items + a page-info cursor envelope."
  ([items next-c] (page items next-c nil))
  ([items next-c prev-c]
   {:items items :page-info {:next-cursor next-c :prev-cursor prev-c}}))

(defn- feed-spec
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

(defn- ensure! [resource]
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource resource :scope :rf.scope/global
                      :params {:filter :recent} :owner [:test :w]}]))

(defn- load-more!
  "The supported idiom: a load-more is OWNERLESS — the feed's liveness is the
  route/ensure owner's, never a per-page owner."
  [resource]
  (rf/dispatch-sync [:rf.resource/load-more
                     {:resource resource :scope :rf.scope/global
                      :params {:filter :recent}
                      :cause [:user :feed/load-more]}]))

(defn- refetch-feed! [resource]
  (rf/dispatch-sync [:rf.resource/refetch {:resource resource :scope :rf.scope/global
                                           :params {:filter :recent} :cause [:test :refresh]}]))

(defn- invalidate-feed! []
  (rf/dispatch-sync [:rf.resource/invalidate-tags {:scope :rf.scope/global
                                                   :tags #{[:feed :recent]}
                                                   :cause [:test :write]}]))

(defn- release-and-invalidate!
  "Release the ensure owner, then invalidate: the owner-free feed is only marked
  stale, with no request."
  []
  (rf/dispatch-sync [:rf.resource/release-owner {:owner [:test :w]}])
  (invalidate-feed!))

(defn- load-page-0!
  "Ensure (page-0) a feed and settle it with `pg`. Returns the scoped key."
  [resource pg]
  (rf/reg-resource resource (feed-spec) feed-spec-request)
  (ensure! resource)
  (reply-success! pg)
  (feed-key resource))

(defn- accumulate-3!
  "Register + load page 0 + two load-mores → a 3-page feed. Returns the key."
  [resource spec-overrides]
  (rf/reg-resource resource (feed-spec spec-overrides) feed-spec-request)
  (ensure! resource) (reply-success! (page [:a] "c1"))
  (load-more! resource) (reply-success! (page [:b] "c2"))
  (load-more! resource) (reply-success! (page [:c] "c3"))
  (feed-key resource))

;; ===========================================================================
;; load-more appends, a terminal / pageless / in-flight load-more fires nothing
;; ===========================================================================

(deftest load-more-appends-and-advances-cursor
  (let [k (load-page-0! :lm/feed (page [:a] "c1"))]
    (load-more! :lm/feed)
    (is (= [:fetching 1 {:page-index 1 :cursor "c1"}]
           [(:status (entry k)) (page-count k)
            (select-keys (get-in @last-managed-args [:request :params]) [:page-index :cursor])])
        "the next page is fetched with the derived cursor while the pages stay visible")
    (reply-success! (page [:b] "c2"))
    (is (= [:loaded [(page [:a] "c1") (page [:b] "c2")] [nil "c1"] "c2"]
           ((juxt :status :data :page-params :next-page-param) (entry k)))
        "the page appends in order with its param, and the cursor advances")))

(deftest load-more-recomputes-prev-mirror
  (let [k (load-page-0! :lmp/feed (page [:a] "c1" "p-head"))]
    (load-more! :lmp/feed)
    (reply-success! (page [:b] "c2" "p-tail"))
    (is (= "p-head" (:prev-page-param (entry k))) "prev comes from the FIRST page")))

(deftest load-more-terminal-is-noop
  (let [k (load-page-0! :term/feed (page [:a] nil))]
    (reset! last-managed-args nil)
    (load-more! :term/feed)
    (let [e (entry k)]
      (is (= [nil nil :loaded 1 nil]
             [(:next-page-param e) @last-managed-args (:status e) (page-count k) (:current-work e)])
          "a nil cursor is terminal: no request, and the feed is unchanged"))))

(deftest load-more-no-feed-is-noop
  (rf/reg-resource :nf/feed (feed-spec) feed-spec-request)
  (load-more! :nf/feed)
  (is (= [nil nil] [@last-managed-args (entry (feed-key :nf/feed))])
      "the first page is ensure's: a load-more fires nothing and conjures no entry"))

(deftest concurrent-load-more-dedupes
  (let [k (load-page-0! :dd/feed (page [:a] "c1"))]
    (load-more! :dd/feed)
    (let [e1    (entry k)
          args1 @last-managed-args]
      (reset! last-managed-args nil)
      (load-more! :dd/feed)
      (is (= [nil (:generation e1) (:current-work e1)]
             [@last-managed-args (:generation (entry k)) (:current-work (entry k))])
          "the second load-more joins the page in flight: no request, no new generation")
      (reply-success! args1 (page [:b] "c2"))
      (is (= [(page [:a] "c1") (page [:b] "c2")] (:data (entry k)))
          "the single page appends once"))))

(deftest stale-page-reply-suppressed
  (let [k (load-page-0! :st/feed (page [:a] "c1"))]
    (load-more! :st/feed)
    (let [args1 @last-managed-args
          wid1  (:current-work (entry k))]
      (refetch-feed! :st/feed)
      (let [gen2 (:generation (entry k))]
        (reply-success! args1 (page [:STALE] "cX"))
        (is (= [[(page [:a] "c1")] gen2 :suppressed]
               [(:data (entry k)) (:generation (entry k)) (work-status wid1)])
            "a refetch supersedes the load-more, whose late page is suppressed, not appended")))))

;; ===========================================================================
;; a load-more failure is the THIRD error channel (keep feed + :page-error)
;; ===========================================================================

(deftest load-more-failure-keeps-the-feed-and-recovers-on-next-success
  (let [k        (load-page-0! :pfr/feed (page [:a] "c1"))
        envelope {:kind :rf.http/server :status 503}]
    (load-more! :pfr/feed)
    (let [wid (:current-work (entry k))]
      (reply-failure! envelope)
      (is (= [:loaded [(page [:a] "c1")] "c1" envelope nil nil nil :failed]
             (conj ((juxt :status :data :next-page-param :page-error :error :refresh-error :current-work)
                    (entry k))
                   (work-status wid)))
          "the feed and its cursor are kept, :page-error records the failure, the row settles :failed"))
    (load-more! :pfr/feed)
    (reply-success! (page [:b] "c2"))
    (is (= [nil 2] [(:page-error (entry k)) (page-count k)])
        "the retried page appends and clears :page-error")))

;; ===========================================================================
;; refetch keeps the window: page 0 in place by default, or a sequential sweep
;; ===========================================================================

(deftest refetch-preserves-window-by-default
  (let [k (accumulate-3! :rw/feed {})]
    (refetch-feed! :rw/feed)
    (is (= [:fetching 3 {:page-index 0}]
           [(:status (entry k)) (page-count k)
            (select-keys (get-in @last-managed-args [:request :params]) [:page-index :cursor])])
        "a refresh-class refetch of page 0 with no cursor; the window is not collapsed")
    (reply-success! (page [:a*] "c1"))
    (is (= [:loaded [(page [:a*] "c1") (page [:b] "c2") (page [:c] "c3")]]
           ((juxt :status :data) (entry k)))
        "page 0 is replaced in place and the tail kept")))

(deftest refetch-all-pages-opt-in-refreshes-every-page-in-sequence
  (let [k (accumulate-3! :ra/feed {:refetch {:refetch-all-pages? true}})]
    (refetch-feed! :ra/feed)
    (is (= [3 0] [(page-count k) (in-flight-page-index)]))
    (reply-success! (page [:a*] "c1"))
    (is (= [(page [:a*] "c1") 3 1 "c1"]
           [(nth (:data (entry k)) 0) (page-count k) (in-flight-page-index)
            (:rf.resource/page-param (second (:on-success @last-managed-args)))])
        "page 0 is replaced in place, then the sweep chains page 1 with its original param")
    (reply-success! (page [:b*] "c2"))
    (is (= [(page [:b*] "c2") 2] [(nth (:data (entry k)) 1) (in-flight-page-index)]))
    (reply-success! (page [:c*] "c3"))
    (let [e (entry k)]
      (is (= [[(page [:a*] "c1") (page [:b*] "c2") (page [:c*] "c3")] false :loaded]
             [(:data e) (contains? e :refetch-sweep) (:status e)])
          "every page is replaced in order, and the exhausted sweep is cleared"))))

(deftest refetch-window-opt-in-refreshes-the-bounded-window-in-sequence
  (let [k (accumulate-3! :rwn/feed {:refetch {:refetch-window 2}})]
    (refetch-feed! :rwn/feed)
    (is (= [3 0] [(page-count k) (in-flight-page-index)]))
    (reply-success! (page [:a*] "c1"))
    (is (= 1 (in-flight-page-index)) "the sweep chains page 1")
    (reply-success! (page [:b*] "c2"))
    (let [e (entry k)]
      (is (= [[(page [:a*] "c1") (page [:b*] "c2") (page [:c] "c3")] false]
             [(:data e) (contains? e :refetch-sweep)])
          ":refetch-window 2 refreshes pages 0 and 1 and leaves page 2 untouched"))))

(deftest refetch-sweep-failure-stops-the-sweep-keeps-pages
  (let [k (accumulate-3! :rsf/feed {:refetch {:refetch-all-pages? true}})]
    (refetch-feed! :rsf/feed)
    (reply-success! (page [:a*] "c1"))
    (is (= 1 (in-flight-page-index)) "FIXTURE — the page-1 leg is in flight")
    (reply-failure! {:kind :rf.http/server :status 503})
    (let [e (entry k)]
      (is (= [:loaded 3 true false]
             [(:status e) (page-count k) (some? (:page-error e)) (contains? e :refetch-sweep)])
          "a failed leg records :page-error, keeps every page and stops the sweep"))))

(deftest ensure-infinite-fresh-skip-serves-cache
  (let [k    (load-page-0! :fs/feed (page [:a] "c1"))
        gen0 (:generation (entry k))]
    (reset! last-managed-args nil)
    (ensure! :fs/feed)
    (is (= [nil gen0 1] [@last-managed-args (:generation (entry k)) (page-count k)])
        "a second ensure of a fresh loaded feed serves the cache")))

;; ===========================================================================
;; a page-0 abort or failure with no pages is a FIRST load, not a load-more
;; ===========================================================================
;;
;; Settling an aborted first load `:loaded` would leave a zero-page feed the
;; fresh-skip above cannot tell from a loaded one, so the feed would hang.

(deftest page-0-abort-settles-idle-so-a-later-ensure-refetches
  (rf/reg-resource :ab0/feed (feed-spec) feed-spec-request)
  (ensure! :ab0/feed)
  (let [k   (feed-key :ab0/feed)
        wid (:current-work (entry k))]
    (reply-aborted!)
    (is (= [:idle [] nil nil :cancelled]
           (conj ((juxt :status :data :current-work :error) (entry k)) (work-status wid)))
        "an aborted first load settles :idle with no error, never :loaded")
    (reset! last-managed-args nil)
    (ensure! :ab0/feed)
    (is (= [0 :loading] [(get-in @last-managed-args [:request :params :page-index]) (:status (entry k))])
        "so a later ensure fetches page 0 again rather than fresh-skipping the empty feed")))

(deftest page-0-abort-arms-gc-and-stale-timers
  (rf/reg-resource :ab2/feed (feed-spec {:gc-after-ms 5000 :stale-after-ms 1000}) feed-spec-request)
  (ensure! :ab2/feed)
  (reset! scheduled-timers [])
  (reply-aborted!)
  (is (= {:gc 5000 :stale 1000 :poll nil} (:timers (last-schedule-for (feed-key :ab2/feed))))
      "an aborted first load arms GC and stale, as the scalar first-load abort does"))

(deftest page-0-failure-arms-gc-and-stale-timers
  (rf/reg-resource :fl0/feed (feed-spec {:gc-after-ms 5000 :stale-after-ms 1000}) feed-spec-request)
  (ensure! :fl0/feed)
  (let [k   (feed-key :fl0/feed)
        wid (:current-work (entry k))]
    (reset! scheduled-timers [])
    (reply-failure! {:kind :rf.http/server :status 503})
    (is (= [:error nil :failed] [(:status (entry k)) (:current-work (entry k)) (work-status wid)])
        "a failed first load settles :error and its row :failed")
    (is (= {:gc 5000 :stale 1000 :poll nil} (:timers (last-schedule-for k)))
        "and arms GC and stale, as the scalar first-load :error does")))

(deftest load-more-abort-keeps-loaded-no-rearm
  (rf/reg-resource :lma/feed (feed-spec {:gc-after-ms 5000}) feed-spec-request)
  (ensure! :lma/feed)
  (reply-success! (page [:a] "c1"))
  (let [k (feed-key :lma/feed)]
    (is (some? (last-schedule-for k)) "FIXTURE — the page-0 success armed the GC timer")
    (reset! scheduled-timers [])
    (load-more! :lma/feed)
    (reply-aborted!)
    (is (= [:loaded 1 nil nil]
           [(:status (entry k)) (page-count k) (:page-error (entry k)) (last-schedule-for k)])
        "an aborted load-more keeps the feed :loaded, records no :page-error and re-arms no timer")))

;; ===========================================================================
;; an :owner on a load-more is warned about and dropped
;; ===========================================================================
;;
;; A load-more is OWNERLESS by contract: the feed's liveness is the owner that
;; ensured page 0, and honouring a stray owner would attach a second durable
;; owner, extending the feed's lifetime until an explicit release. So a
;; supplied owner raises a recoverable warning, is normalized to nil, and the
;; page is still fetched and appended; the :cause is kept.

(defn- record-resource-traces!
  "Run `body-fn` with a trace listener installed; return every `:rf.resource/*`
  and `:rf.warning/*` trace it emits, in capture order."
  [body-fn]
  (let [seen (atom [])
        k    ::resource-trace-recorder]
    (rf.trace.tooling/register-listener!
      k (fn [ev]
          (when (and (keyword? (:operation ev))
                     (contains? #{"rf.resource" "rf.warning"}
                                (namespace (:operation ev))))
            (swap! seen conj ev))))
    (try (body-fn)
         (finally (rf.trace.tooling/unregister-listener! k)))
    @seen))

(defn- owner-ignored-warnings [traces]
  (filterv #(= :rf.warning/resource-load-more-owner-ignored (:operation %)) traces))

(defn- owners-for-key
  "Owners in the :owner-index whose set contains this feed's key-id."
  [scoped-key]
  (let [kid (rf.resources.state/key-id scoped-key)]
    (->> (get-in (runtime-db) (rf.resources.state/owner-index-path))
         (filter (fn [[_owner key-ids]] (contains? key-ids kid)))
         (map key)
         set)))

(deftest load-more-owner-is-warned-dropped-and-the-page-still-appends
  (let [k (load-page-0! :mo/feed (page [:a] "c1"))]
    (is (empty? (owner-ignored-warnings (record-resource-traces! #(load-more! :mo/feed))))
        "CONTROL — the supported ownerless load-more does not warn")
    (reply-success! (page [:b] "c2"))
    (let [warns (owner-ignored-warnings
                  (record-resource-traces!
                    #(rf/dispatch-sync [:rf.resource/load-more
                                        {:resource :mo/feed :scope :rf.scope/global
                                         :params {:filter :recent} :owner [:wrong :owner]
                                         :cause [:user :feed/load-more]}])))
          w     (first warns)]
      ;; emit!'s third arg lands under :tags
      (is (= [1 :warning [:wrong :owner] true]
             [(count warns) (:op-type w) (get-in w [:tags :owner]) (some? (get-in w [:tags :hint]))])
          "a supplied owner raises one recoverable warning naming it, with a fix hint"))
    (let [rec (rf.resources.work-ledger/get-record (runtime-db) (:current-work (entry k)))]
      (is (= [:fetching "c2" #{[:test :w]} #{[:test :w]} #{[:test :w]} [[:user :feed/load-more]]]
             [(:status (entry k)) (get-in @last-managed-args [:request :params :cursor])
              (:active-owners (entry k)) (owners-for-key k) (:owners rec) (:causes rec)])
          "the owner is dropped from the entry, the owner index and the work row; the request and :cause go ahead"))
    (reply-success! (page [:c] "c3"))
    (is (= [[(page [:a] "c1") (page [:b] "c2") (page [:c] "c3")] "c3" #{[:test :w]}]
           ((juxt :data :next-page-param :active-owners) (entry k)))
        "the page appends in order and the feed still has its one owner")))

(deftest page-attempts-inherit-the-feeds-held-owners
  ;; A load-more / sweep leg never MINTS an owner, but the owners already
  ;; holding the feed still need its request: the new work row starts from the
  ;; entry's :active-owners, so releasing one of two owners does not abort a
  ;; page the other still needs (Spec 016 §Race).
  (rf/reg-resource :own/feed (feed-spec {:refetch {:refetch-all-pages? true}}) feed-spec-request)
  (let [q   {:resource :own/feed :scope :rf.scope/global :params {:filter :recent}}
        k   (feed-key :own/feed)
        a   [:test :a]
        b   [:test :b]
        rec #(rf.resources.work-ledger/get-record (runtime-db) (:current-work (entry k)))]
    (rf/dispatch-sync [:rf.resource/ensure (assoc q :owner a)])
    (rf/dispatch-sync [:rf.resource/ensure (assoc q :owner b)])
    (reply-success! (page [:a] "c1"))
    (load-more! :own/feed)
    (let [wid (:current-work (entry k))]
      (is (= #{a b} (:owners (rec))) "the load-more row carries the held owners and mints none")
      (rf/dispatch-sync [:rf.resource/release-owner {:owner a}])
      (is (= [false #{b}]
             [(contains? (set @aborts) (rf.resources.work-ledger/managed-request-id :rf/default wid))
              (:owners (rec))])
          "releasing one owner does not abort the page the other still holds")
      (reply-success! (page [:b] "c2"))
      (is (= 2 (page-count k)) "its page reply is accepted"))
    (refetch-feed! :own/feed)
    (reply-success! (page [:a*] "c1"))
    (is (= [1 #{b}] [(in-flight-page-index) (:owners (rec))])
        "a refetch-sweep leg inherits the held owner too")))

;; ===========================================================================
;; an accepted page success produces + indexes the feed :tags
;; ===========================================================================

(defn- tag-members [tag]
  (get-in (runtime-db) (conj (rf.resources.state/tag-index-path) tag)))

(deftest page-success-produces-feed-tags-so-invalidation-reaches-the-feed
  (rf/reg-resource :tg/feed (feed-spec) feed-spec-request)
  (rf/reg-resource :tg/idle-feed (feed-spec) feed-spec-request)
  (rf/reg-resource :tg/scalar
                   {:scope         :rf.scope/global
                    :params-schema [:map [:filter :keyword]]
                    :tags          (fn [{:keys [filter]} _data] #{[:feed filter]})}
                   (fn [_params _ctx] {:request {:method :get :url "/api/scalar"}}))
  (let [kf (feed-key :tg/feed)
        ki (feed-key :tg/idle-feed)
        ks (feed-key :tg/scalar)]
    (ensure! :tg/feed)   (reply-success! (page [:a] "c1"))
    (ensure! :tg/scalar) (reply-success! {:v 1})
    (rf/dispatch-sync [:rf.resource/ensure {:resource :tg/idle-feed :scope :rf.scope/global
                                            :params {:filter :recent} :owner [:test :idle]}])
    (reply-success! (page [:x] nil))
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:test :idle]}])
    (is (= [#{[:feed :recent]} (set (map rf.resources.state/key-id [kf ki ks]))]
           [(:tags (entry kf)) (tag-members [:feed :recent])])
        "the feed carries its produced tag and is indexed under it, like the scalar")
    (invalidate-feed!)
    (is (= [:fetching true :fetching]
           [(:status (entry kf))
            (rf.resources.work-ledger/live-work? (runtime-db) (:current-work (entry kf)))
            (:status (entry ks))])
        "invalidation refetches the OWNED feed, as it does the owned scalar control")
    (is (= [true :loaded nil]
           [(some? (:invalidated-at (entry ki))) (:status (entry ki)) (:current-work (entry ki))])
        "the OWNER-FREE feed goes durably stale without a request")))

(deftest feed-tags-follow-the-accumulated-pages
  (let [item-tags (fn [{:keys [filter]} pages]
                    (into #{[:feed filter]}
                          (comp (mapcat :items) (map (fn [item] [:item item])))
                          pages))]
    (rf/reg-resource :tgd/feed (feed-spec {:tags item-tags}) feed-spec-request)
    (rf/reg-resource :tgd/other (feed-spec {:tags item-tags}) feed-spec-request)
    (let [k   (feed-key :tgd/feed)
          ko  (feed-key :tgd/other)
          kid (rf.resources.state/key-id k)]
      (ensure! :tgd/other) (reply-success! (page [:a] nil))
      (ensure! :tgd/feed)  (reply-success! (page [:a] "c1"))
      (is (= #{[:feed :recent] [:item :a]} (:tags (entry k))) "page 0's items tag the feed")
      (load-more! :tgd/feed)
      (reply-success! (page [:b] nil))
      (is (= [#{[:feed :recent] [:item :a] [:item :b]} #{kid}]
             [(:tags (entry k)) (tag-members [:item :b])])
          "an appended page adds its items' tags")
      (refetch-feed! :tgd/feed)
      (reply-success! (page [:a2] "c1"))
      (is (= [#{[:feed :recent] [:item :a2] [:item :b]} #{kid} #{(rf.resources.state/key-id ko)}]
             [(:tags (entry k)) (tag-members [:item :a2]) (tag-members [:item :a])])
          "a page replaced in place drops the tags only it produced; the other feed keeps its own"))))

;; ===========================================================================
;; a failed page-0 refresh of a LOADED feed is :refresh-error
;; ===========================================================================

(deftest loaded-feed-page-0-refresh-failure-is-a-refresh-error
  (let [k        (load-page-0! :rfe/feed (page [:a] "c1"))
        q        {:resource :rfe/feed :scope :rf.scope/global :params {:filter :recent}}
        envelope {:kind :rf.http/http-5xx :status 503}]
    (rf/dispatch-sync [:rf.resource/refetch (assoc q :cause :focus)])
    (reply-failure! envelope)
    (is (= [:loaded [(page [:a] "c1")] envelope nil nil nil]
           ((juxt :status :data :refresh-error :page-error :error :current-work) (entry k)))
        "the feed survives :loaded and the failure lands on :refresh-error alone")
    (is (= [envelope nil]
           [@(rf/subscribe [:rf.resource/refresh-error q]) @(rf/subscribe [:rf.resource/page-error q])])
        "the public projections agree")
    (rf/dispatch-sync [:rf.resource/refetch (assoc q :cause :focus)])
    (reply-success! (page [:a*] "c1"))
    (is (nil? (:refresh-error (entry k))) "the next successful refresh clears it")))

(deftest loaded-feed-page-0-refresh-failure-stops-the-sweep
  (let [k (accumulate-3! :rfs/feed {:refetch {:refetch-all-pages? true}})]
    (refetch-feed! :rfs/feed)
    (reply-failure! {:kind :rf.http/server :status 503})
    (let [e (entry k)]
      (is (= [:loaded 3 true nil false]
             [(:status e) (page-count k) (some? (:refresh-error e)) (:page-error e)
              (contains? e :refetch-sweep)])
          "a multi-page refresh whose page 0 fails records :refresh-error, keeps every page, chains no leg"))))

;; ===========================================================================
;; a page settle clears only a stale mark its attempt COVERS
;; ===========================================================================
;;
;; A settle keeps a stale mark written DURING the settling attempt (an
;; invalidation landing during a read survives that read's success). A feed page
;; covers less than a scalar reply: an appended page refreshes none of the pages
;; the feed already held, and one sweep leg refreshes one page of the window. So
;; a load-more never clears the mark, and a sweep clears one only when it
;; predates the sweep, at the leg that completes the refresh window.

(deftest a-load-more-keeps-a-mark-the-feed-already-had
  (let [k    (accumulate-3! :wa/feed {})
        held (:data (entry k))]
    (release-and-invalidate!)
    (is (some? (:invalidated-at (entry k))) "FIXTURE — the owner-free feed is marked stale")
    (load-more! :wa/feed)
    (reply-success! (page [:d] nil))
    (is (= [(conj held (page [:d] nil)) true] [(:data (entry k)) (some? (:invalidated-at (entry k)))])
        "page 3 appends and refreshes none of the pages the mark covers")
    (ensure! :wa/feed)
    (is (= 0 (in-flight-page-index)) "so the next ensure refetches rather than fresh-skipping")))

(deftest a-later-sweep-leg-keeps-a-mark-written-during-an-earlier-one
  (let [k (accumulate-3! :wb/feed {:refetch {:refetch-all-pages? true}})]
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:test :w]}])
    (refetch-feed! :wb/feed)
    (invalidate-feed!)
    (reply-success! (page [:a*] "c1"))
    (is (some? (:invalidated-at (entry k))) "page 0 keeps the mark written during it")
    (reply-success! (page [:b*] "c2"))
    (is (some? (:invalidated-at (entry k))) "page 1 does not refresh page 0's pre-write data")
    (reply-success! (page [:c*] "c3"))
    (is (= [false true] [(contains? (entry k) :refetch-sweep) (some? (:invalidated-at (entry k)))])
        "the sweep ran to its end, and page 0 still holds pre-invalidation data, so the feed ends stale")))

(deftest a-sweep-clears-a-mark-it-covers-only-once-the-window-is-refetched
  (testing "a mark that predates the sweep survives until the final leg"
    (let [k (accumulate-3! :wc/feed {:refetch {:refetch-all-pages? true}})]
      (release-and-invalidate!)
      (ensure! :wc/feed)
      (reply-success! (page [:a*] "c1"))
      (is (= [true 1] [(some? (:invalidated-at (entry k))) (in-flight-page-index)])
          "pages 1-2 still hold pre-invalidation data, and the ensure's sweep continues")
      (reply-success! (page [:b*] "c2"))
      (is (some? (:invalidated-at (entry k))))
      (reply-success! (page [:c*] "c3"))
      (is (nil? (:invalidated-at (entry k))) "the final leg completes the refresh window")))
  (testing "a leg that fails leaves the feed stale"
    (let [k (accumulate-3! :wd/feed {:refetch {:refetch-all-pages? true}})]
      (release-and-invalidate!)
      (ensure! :wd/feed)
      (reply-success! (page [:a*] "c1"))
      (reply-failure! {:kind :rf.http/server :status 503})
      (is (= [false true] [(contains? (entry k) :refetch-sweep) (some? (:invalidated-at (entry k)))])
          "the failed leg stops the sweep, and pages 1-2 were never refreshed")))
  (testing "CONTROL — the window-preserving default refreshes page 0 only, which covers it"
    (let [k (accumulate-3! :we/feed {})]
      (release-and-invalidate!)
      (ensure! :we/feed)
      (reply-success! (page [:a*] "c1"))
      (is (nil? (:invalidated-at (entry k)))))))

;; ===========================================================================
;; an authoritative write drops the superseded read's sweep
;; ===========================================================================
;;
;; A `:populates` / `:patches` write supersedes a read in flight. The sweep
;; that read would have chained goes with it, or the next load-more's settle
;; picks its obsolete cursor up and re-fetches pages the write just installed.

(def ^:private written-pages
  [(page [:A] "c1") (page [:B] "c2") (page [:C] "c3")])

(defn- reg-feed-writes! [resource]
  (let [target {:resource resource :params {:filter :recent} :scope :rf.scope/global}]
    (rf/reg-mutation :wp/populate
      {:scope :rf.scope/global
       :params-schema [:map [:filter :keyword]]
       :populates (fn [_params result] {target result})}
      (fn [_ _] {:request {:method :put :url "/api/feed"}}))
    (rf/reg-mutation :wp/patch
      {:scope :rf.scope/global
       :params-schema [:map [:filter :keyword]]
       :patches (fn [_params _result] {target (fn [_old result] result)})}
      (fn [_ _] {:request {:method :put :url "/api/feed"}}))))

(defn- write-over-a-sweep-then-load-more! [resource mutation-id]
  (let [k (accumulate-3! resource {:refetch {:refetch-all-pages? true}})]
    (reg-feed-writes! resource)
    (refetch-feed! resource)
    (let [page-0 @last-managed-args]
      (is (= [["c1" 1] ["c2" 2]] (:refetch-sweep (entry k))) "FIXTURE — the sweep tail is armed")
      (rf/dispatch-sync [:rf.mutation/execute {:mutation mutation-id :params {:filter :recent}
                                               :instance :w5p2p}])
      (reply-success! written-pages)
      (let [e (entry k)]
        (is (= [written-pages nil false] [(:data e) (:current-work e) (contains? e :refetch-sweep)])
            "the write lands, supersedes the read in flight, and drops that read's sweep"))
      (reply-success! page-0 (page [:old] "c1"))
      (is (= written-pages (:data (entry k))) "the late page-0 reply is suppressed")
      (load-more! resource)
      (let [load-more-args @last-managed-args]
        (is (= 3 (in-flight-page-index)) "FIXTURE — the load-more fetches page 3")
        (reply-success! (page [:D] nil))
        (is (= [(conj written-pages (page [:D] nil)) true nil]
               [(:data (entry k)) (identical? load-more-args @last-managed-args) (:current-work (entry k))])
            "the page appends, no abandoned sweep leg is fetched, and nothing is in flight")))
    (refetch-feed! resource)
    (is (= [["c1" 1] ["c2" 2] ["c3" 3]] (:refetch-sweep (entry k)))
        "a deliberate refetch still sweeps per the policy")))

(deftest a-populate-drops-the-sweep-of-the-read-it-supersedes
  (write-over-a-sweep-then-load-more! :wpp/feed :wp/populate))

(deftest a-patch-drops-the-sweep-of-the-read-it-supersedes
  (write-over-a-sweep-then-load-more! :wpq/feed :wp/patch))
