(ns re-frame.resources-invalidation-gc-cljs-test
  "Invalidation, owner liveness, clear-scope and the stale/GC timers (Spec 016
  §Invalidation, §Active owners and causes, §Stale and GC scheduling).
  Invalidation is scoped by default and cross-scope only on an audited opt-in;
  releasing the last owner aborts in-flight work; timers live in a host side
  table and re-check the live entry before acting."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.frame :as rf.frame]
   [re-frame.resources]
   [re-frame.resources.events :as rf.resources.events]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.timers :as rf.resources.timers]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private aborts (atom []))
(def ^:private scheduled-timers (atom []))

(defn- capturing-fixture
  "Capture managed-HTTP aborts and :rf.resource/schedule-timers arming, so no
  wall-clock timer fires; the fetch itself is a no-op."
  [f]
  (reset! aborts [])
  (reset! scheduled-timers [])
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf.fx/reg-fx :rf.http/managed-abort (fn [_ctx work-id] (swap! aborts conj work-id) nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ctx args] (swap! scheduled-timers conj args) nil))
  ;; ensures here pass an explicit :scope; the resolver's slot stays unwritten
  (rf/reg-resource-scope :t/caller-scope
    {:inputs {:scope [:db [:t/scope]]}}
    (fn [{:keys [scope]} _ctx] scope))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-fixture)

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))
(defn- invalidated? [scoped-key] (some? (:invalidated-at (entry scoped-key))))
(defn- work-record [wid] (rf.resources.work-ledger/get-record (runtime-db) wid))

(defn- article-spec
  ([] (article-spec {}))
  ([overrides]
   (merge {:scope         {:from-db :t/caller-scope}
           :params-schema [:map [:slug :string]]
           :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
          overrides)))

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

(defn- ensure! [resource scope slug owner]
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource resource :scope scope :params {:slug slug}
                      :owner owner}]))

(defn- reply!
  "Feed an internal reply for `scoped-key` against the LIVE entry's current
  work id and generation."
  [event-id scoped-key extra]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [event-id (merge {:resource/key scoped-key :work/id (:current-work e)
                                        :generation (:generation e)}
                                       extra)])))

(defn- succeed! [scoped-key data]
  (reply! :rf.resource.internal/succeeded scoped-key {:data data}))

(defn- fail!
  "A non-abort transport failure; a first-load failure settles :error."
  [scoped-key reason]
  (reply! :rf.resource.internal/failed scoped-key {:error {:kind :rf.http/server-error :reason reason}}))

(defn- abort!
  "An :rf.http/aborted failure: a cancellation, which never settles :error."
  [scoped-key]
  (reply! :rf.resource.internal/failed scoped-key {:error {:kind :rf.http/aborted :reason :user}}))

(defn- last-schedule-for [scoped-key]
  (last (filter #(= scoped-key (:resource/key %)) @scheduled-timers)))

(defn- timer-delays
  "Each captured schedule-timers emission as [resource-key stale gc]."
  []
  (mapv (juxt :resource/key (comp :stale :timers) (comp :gc :timers)) @scheduled-timers))

(defn- two-scope-ownerless!
  "Load slug \"w\" of `resource` in scopes {:user \"a\"} and {:user \"b\"},
  then release both owners so an invalidation marks stale rather than
  refetching. Returns the two scoped keys."
  [resource]
  (rf/reg-resource resource (article-spec) article-spec-request)
  (mapv (fn [[u owner]]
          (let [scope {:user u}
                k     (rf.resources.state/scoped-resource-key scope resource {:slug "w"})]
            (ensure! resource scope "w" owner)
            (succeed! k {:title u})
            (rf/dispatch-sync [:rf.resource/release-owner {:owner owner}])
            k))
        [["a" [:app :a 1]] ["b" [:app :b 1]]]))

(deftest invalidate-tags-is-scoped-by-default
  (let [[ka kb] (two-scope-ownerless! :iv/article)]
    (rf/dispatch-sync [:rf.resource/invalidate-tags {:scope {:user "a"} :tags #{[:article "w"]}}])
    (is (= [true false] (map invalidated? [ka kb])) "only the in-scope entry is marked stale")))

(deftest invalidate-tags-cross-scope-opt-in
  (let [[ka kb] (two-scope-ownerless! :ivx/article)]
    ;; the audited escape: a :cause and no :scope
    (rf/dispatch-sync [:rf.resource/invalidate-tags
                       {:tags #{[:article "w"]} :cross-scope? true
                        :cause [:admin/cache-poisoning-response]}])
    (is (= [true true] (map invalidated? [ka kb])) "the tag is matched in every scope")))

(defn- invalidate-cofx
  "A minimal cofx for a direct handler call. A handler throw through dispatch
  is captured by the router, so fail-closed throws are asserted here."
  [runtime-db]
  {:rf.db/runtime runtime-db :rf.frame/id :rf/default})

(deftest invalidate-tags-fails-closed-on-a-malformed-payload
  ;; Scoped needs a concrete, canonical scope; cross-scope needs a :cause and
  ;; no :scope. Never a silent nil-scope match or an unaudited sweep.
  (doseq [[payload error-id]
          [[{:tags #{[:article "w"]}} #"resource-invalidate-scope-required"]
           [{:scope nil :tags #{[:article "w"]}} #"resource-invalidate-scope-required"]
           [{:scope :rf.scope/glabal :tags #{[:article "w"]}} #"resource-invalid-scope"]
           [{:tags #{[:article "w"]} :cross-scope? true} #"resource-cross-scope-cause-required"]
           [{:tags #{[:article "w"]} :cross-scope? true :cause nil} #"resource-cross-scope-cause-required"]
           [{:scope {:user "a"} :tags #{[:article "w"]} :cross-scope? true :cause [:admin/x]}
            #"resource-cross-scope-scope-conflict"]]]
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) error-id
          (rf.resources.events/invalidate-tags-handler
            (invalidate-cofx {})
            [:rf.resource/invalidate-tags payload]))
        (pr-str payload))))

(deftest clear-scope-rejects-reserved-scope-typo
  ;; a typo must never silently clear the wrong scope
  (is (thrown-with-msg?
        #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
        (rf.resources.events/clear-scope-handler
          (invalidate-cofx {})
          [:rf.resource/clear-scope {:scope :rf.scope/glabal :cause :logout}]))))

(deftest invalidate-tags-refetches-active-leaves-inactive-stale
  (rf/reg-resource :ivr/article (article-spec) article-spec-request)
  (let [scope {:user "u"}
        kact  (rf.resources.state/scoped-resource-key scope :ivr/article {:slug "active"})
        kin   (rf.resources.state/scoped-resource-key scope :ivr/article {:slug "inactive"})]
    (ensure! :ivr/article scope "active" [:route :r 1])
    (succeed! kact {:title "Active"})
    (ensure! :ivr/article scope "inactive" [:app :x 1])
    (succeed! kin {:title "Inactive"})
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :x 1]}])
    (rf/dispatch-sync [:rf.resource/invalidate-tags
                       {:scope scope :tags #{[:article "active"] [:article "inactive"]}}])
    (is (= [:fetching {:title "Active"}] ((juxt :status :data) (entry kact)))
        "the owned entry refetches, keeping its data")
    (is (= [:loaded true] [(:status (entry kin)) (invalidated? kin)])
        "the ownerless entry is left stale, not refetched")))

(deftest successful-load-replaces-tag-index
  (rf/reg-resource :tagrep/article
                   (article-spec {:tags (fn [{:keys [slug]} data] #{[:article slug] [:rev (:rev data)]})})
                   article-spec-request)
  (let [scope {:user "u"}
        k     (rf.resources.state/scoped-resource-key scope :tagrep/article {:slug "w"})
        kid   (rf.resources.state/key-id k)
        index (fn [tag] (get-in (runtime-db) (conj (rf.resources.state/tag-index-path) tag)))]
    (ensure! :tagrep/article scope "w" [:app :t 1])
    (succeed! k {:rev 1})
    (is (= [#{[:article "w"] [:rev 1]} #{kid}] [(:tags (entry k)) (index [:rev 1])]))
    (rf/dispatch-sync [:rf.resource/refetch {:resource :tagrep/article :scope scope
                                             :params {:slug "w"}}])
    (succeed! k {:rev 2})
    (is (= [#{[:article "w"] [:rev 2]} #{kid} nil] [(:tags (entry k)) (index [:rev 2]) (index [:rev 1])])
        "the reload replaces the tags and drops the old tag from the index")))

(deftest release-owner-does-not-abort-shared-in-flight
  (rf/reg-resource :sh/article (article-spec) article-spec-request)
  (let [scope {:user "u"}
        k     (rf.resources.state/scoped-resource-key scope :sh/article {:slug "w"})]
    (ensure! :sh/article scope "w" [:route :r 1])
    (ensure! :sh/article scope "w" [:app :x 1])
    (is (= #{[:route :r 1] [:app :x 1]} (:active-owners (entry k))) "both owners joined one attempt")
    (let [wid (:current-work (entry k))]
      (reset! aborts [])
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:route :r 1]}])
      (is (= [#{[:app :x 1]} [] #{[:app :x 1]}]
             [(:active-owners (entry k)) @aborts (:owners (work-record wid))])
          "releasing one owner of a shared request does not abort it")
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :x 1]}])
      ;; the abort carries the frame-qualified request id, never the bare work id
      (is (= [true [(rf.resources.work-ledger/managed-request-id :rf/default wid)] :abort-requested]
             [(empty? (:active-owners (entry k))) @aborts (:status (work-record wid))])
          "releasing the last owner aborts the orphaned attempt"))))

;; The linked work record's status, not the entry's :current-work pointer, is
;; what makes an attempt joinable.

(deftest re-ensure-after-owner-release-does-not-join-abort-requested
  (rf/reg-resource :nj/article (article-spec) article-spec-request)
  (let [scope {:user "u"}
        k     (rf.resources.state/scoped-resource-key scope :nj/article {:slug "w"})]
    (ensure! :nj/article scope "w" [:route :r 1])
    (let [wid1 (:current-work (entry k))
          gen1 (:generation (entry k))]
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:route :r 1]}])
      (is (= [:abort-requested wid1] [(:status (work-record wid1)) (:current-work (entry k))])
          "precondition: the entry still points at the abort-requested work")
      (ensure! :nj/article scope "w" [:route :r 2])
      (let [e (entry k)]
        (is (= [(inc gen1) true :running true]
               [(:generation e) (not= wid1 (:current-work e)) (:status (work-record (:current-work e)))
                (contains? (:active-owners e) [:route :r 2])])
            "a fresh, live attempt carrying the new owner")))))

(deftest re-ensure-after-abort-settle-does-not-join-terminal
  (rf/reg-resource :njt/article (article-spec) article-spec-request)
  (let [scope {:user "u"}
        k     (rf.resources.state/scoped-resource-key scope :njt/article {:slug "w"})]
    (ensure! :njt/article scope "w" [:app :a 1])
    (let [wid1 (:current-work (entry k))
          gen1 (:generation (entry k))]
      (abort! k)
      (is (= :cancelled (:status (work-record wid1))) "precondition: the aborted row is terminal")
      (ensure! :njt/article scope "w" [:app :a 2])
      (is (= [(inc gen1) true] [(:generation (entry k)) (not= wid1 (:current-work (entry k)))])
          "a fresh generation and work id"))))

(deftest clear-scope-suppresses-late-reply
  (rf/reg-resource :clr/article (article-spec) article-spec-request)
  (let [scope {:user "u"}
        k     (rf.resources.state/scoped-resource-key scope :clr/article {:slug "w"})]
    (ensure! :clr/article scope "w" [:app :c 1])
    (let [stale-wid (:current-work (entry k))
          late!     (fn [data] (rf/dispatch-sync [:rf.resource.internal/succeeded
                                                  {:resource/key k :work/id stale-wid
                                                   :generation 1 :data data}]))]
      (rf/dispatch-sync [:rf.resource/clear-scope {:scope scope :cause :logout}])
      (is (nil? (entry k)) "entry removed by clear-scope")
      (late! {:title "Late"})
      (is (nil? (entry k)) "a late reply does not resurrect the entry")
      (testing "a recreated entry gets a higher generation, so the old reply never matches it"
        (ensure! :clr/article scope "w" [:app :c 2])
        (is (= 2 (:generation (entry k))))
        (late! {:title "ZombieLate"})
        (is (not= {:title "ZombieLate"} (:data (entry k))))))))

(deftest succeeded-arms-stale-and-gc-timers
  (rf/reg-resource :tm/article (article-spec {:stale-after-ms 60000 :gc-after-ms 300000}) article-spec-request)
  (let [scope {:user "u"}
        k     (rf.resources.state/scoped-resource-key scope :tm/article {:slug "w"})]
    (ensure! :tm/article scope "w" [:app :tm 1])
    (succeed! k {:title "W"})
    (is (= [[k 60000 300000]] (timer-delays))
        "one schedule-timers fx carrying the resource's policy delays")))

(deftest gc-after-ms-never-arms-no-gc-timer
  ;; the explicit, auditable opt-out: the owner-free entry lingers by intent
  (rf/reg-resource :npn/article (article-spec {:gc-after-ms :never}) article-spec-request)
  (let [scope {:user "u"}
        k     (rf.resources.state/scoped-resource-key scope :npn/article {:slug "w"})]
    (ensure! :npn/article scope "w" [:app :npn 1])
    (succeed! k {:title "W"})
    (is (= [] @scheduled-timers))))

;; A first load that fails or is aborted settles with no data and no work in
;; flight, so it must arm GC itself: otherwise an owner-free entry from it is
;; never reaped.

(deftest first-load-error-arms-gc-and-is-collected-after-release
  (rf/reg-resource :gce/article (article-spec {:gc-after-ms 5000}) article-spec-request)
  (let [scope {:user "u"}
        k     (rf.resources.state/scoped-resource-key scope :gce/article {:slug "w"})]
    (ensure! :gce/article scope "w" [:app :gce 1])
    (fail! k :transient-500)
    (is (= [:error nil] ((juxt :status :current-work) (entry k))))
    (is (= [5000 nil] ((juxt (comp :gc :timers) (comp :poll :timers)) (last-schedule-for k)))
        "the error settle armed GC at :gc-after-ms, and no poll")
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :gce 1]}])
    (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])
    (is (nil? (entry k)) "GC reaped the owner-free errored entry")))

(deftest first-load-abort-arms-gc-and-is-collected-after-release
  (rf/reg-resource :gca/article (article-spec {:gc-after-ms 5000}) article-spec-request)
  (let [scope {:user "u"}
        k     (rf.resources.state/scoped-resource-key scope :gca/article {:slug "w"})]
    (ensure! :gca/article scope "w" [:app :gca 1])
    (abort! k)
    (is (= [:idle nil] ((juxt :status :current-work) (entry k))))
    (is (= [5000 nil] ((juxt (comp :gc :timers) (comp :poll :timers)) (last-schedule-for k)))
        "the abort settle armed GC at :gc-after-ms, and no poll")
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :gca 1]}])
    (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])
    (is (nil? (entry k)) "GC reaped the owner-free aborted entry")))

(deftest gc-skip-while-owned-reschedules-and-collects-after-release
  ;; A skipped GC re-arms, so a release after the original deadline does not
  ;; strand the entry.
  (rf/reg-resource :gcr/article (article-spec {:gc-after-ms 1000}) article-spec-request)
  (let [scope {:user "u"}
        k     (rf.resources.state/scoped-resource-key scope :gcr/article {:slug "w"})]
    (ensure! :gcr/article scope "w" [:app :gcr 1])
    (succeed! k {:title "W"})
    (reset! scheduled-timers [])
    (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])
    (is (= :loaded (:status (entry k))) "the owned entry is kept")
    (is (= [[k nil 1000]] (timer-delays)) "one GC reschedule at :gc-after-ms; stale not re-armed")
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :gcr 1]}])
    (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])
    (is (nil? (entry k)) "the rescheduled check collects the now-inactive entry")))

(deftest gc-skip-while-in-flight-reschedules-and-collects-after-settle
  (rf/reg-resource :gci/article (article-spec {:gc-after-ms 1000}) article-spec-request)
  (let [scope {:user "u"}
        k     (rf.resources.state/scoped-resource-key scope :gci/article {:slug "w"})]
    (ensure! :gci/article scope "w" [:app :gci 1])
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :gci 1]}])
    (is (some? (:current-work (entry k))) "precondition: in flight, owner-free")
    (reset! scheduled-timers [])
    (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])
    (is (= [true [1000]] [(some? (entry k)) (mapv (comp :gc :timers) @scheduled-timers)])
        "the in-flight entry is kept and one GC reschedule armed")
    (let [wid (:current-work (entry k))]
      (rf/dispatch-sync [:rf.resource.internal/failed
                         {:resource/key k :work/id wid
                          :generation (:generation (entry k))
                          :rf.frame/id :rf/default}
                         {:status :cancelled :error {:kind :rf.http/aborted :reason :user}}]))
    (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])
    (is (nil? (entry k)) "once settled and idle, the rescheduled check collects it")))

(deftest gc-skip-no-entry-does-not-reschedule
  (rf/reg-resource :gcn/article (article-spec {:gc-after-ms 1000}) article-spec-request)
  (let [k (rf.resources.state/scoped-resource-key {:user "u"} :gcn/article {:slug "gone"})]
    (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])
    (is (empty? @scheduled-timers) "nothing to collect, so no reschedule")))

(deftest stale-fired-rechecks-durable-fact-no-write
  ;; freshness derives from the durable :stale-at; the timer is advisory
  (rf/reg-resource :sf/article (article-spec {:stale-after-ms 60000}) article-spec-request)
  (let [scope {:user "u"}
        k     (rf.resources.state/scoped-resource-key scope :sf/article {:slug "w"})]
    (ensure! :sf/article scope "w" [:app :sf 1])
    (succeed! k {:title "W"})
    (let [before (entry k)]
      (rf/dispatch-sync [:rf.resource.internal/stale-fired {:resource/key k}])
      (is (= before (entry k)) "stale-fired made no durable change"))))

(deftest frame-destroy-cancels-resource-timers
  (rf/reg-resource :fd/article (article-spec {:stale-after-ms 60000 :gc-after-ms 300000}) article-spec-request)
  (let [fa        :fd/frame-a
        k         (rf.resources.state/scoped-resource-key {:user "u"} :fd/article {:slug "w"})
        fa-timers #(count (filter (fn [[[fid _ _] _]] (= fid fa)) @rf.resources.timers/timer-table))]
    (rf/make-frame {:id fa :doc "frame-destroy timer frame"})
    (rf.resources.timers/schedule! fa k rf.resources.timers/stale-kind 1000000)
    (rf.resources.timers/schedule! fa k rf.resources.timers/gc-kind 1000000)
    (is (= 2 (fa-timers)) "precondition: two timers armed for frame A")
    (rf.frame/destroy-frame! fa)
    (is (= 0 (fa-timers)) "frame destroy cancels and drops the frame's timers")))

(deftest remove-cancels-instance-timers
  (rf/reg-resource :rmt/article (article-spec {:gc-after-ms 1000}) article-spec-request)
  (let [scope {:user "u"}
        k     (rf.resources.state/scoped-resource-key scope :rmt/article {:slug "w"})
        slot  [:rf/default (rf.resources.state/key-id k) rf.resources.timers/gc-kind]]
    (ensure! :rmt/article scope "w" [:app :rmt 1])
    (succeed! k {:title "W"})
    (rf.resources.timers/schedule! :rf/default k rf.resources.timers/gc-kind 1000000)
    (is (contains? @rf.resources.timers/timer-table slot) "precondition: a GC timer is armed")
    (rf/dispatch-sync [:rf.resource/remove {:resource :rmt/article :scope scope
                                            :params {:slug "w"}}])
    (is (= [nil false] [(entry k) (contains? @rf.resources.timers/timer-table slot)])
        "the instance is removed and its timer cancelled")))

;; :invalidated-at is a freshness fact, orthogonal to load status, so only a
;; settle that produced authoritative data may clear it. Without
;; :stale-after-ms it is the entry's only path to :stale?, so losing it to a
;; failed or aborted refetch would fresh-skip the pre-mutation data for good.

(deftest invalidation-survives-a-failed-refetch
  (rf/reg-resource :ifz/article (article-spec) article-spec-request)
  (let [scope {:user "u"}
        k     (rf.resources.state/scoped-resource-key scope :ifz/article {:slug "w"})
        owner [:app :ifz 1]]
    (ensure! :ifz/article scope "w" owner)
    (succeed! k {:title "pre-mutation"})
    (rf/dispatch-sync [:rf.resource/invalidate-tags {:scope scope :tags #{[:article "w"]}}])
    (is (some? (:current-work (entry k))) "precondition: the owned entry's invalidation refetches")
    (fail! k :boom)
    (let [e (entry k)]
      (is (= [:loaded {:title "pre-mutation"} true] [(:status e) (:data e) (some? (:refresh-error e))])
          "a background-refresh failure keeps the last-known-good data")
      (is (= [true nil true] [(some? (:invalidated-at e)) (:stale-at e) (rf.resources.state/entry-stale? e 0)])
          "the invalidation survives and is the only thing keeping the entry stale"))
    (ensure! :ifz/article scope "w" owner)
    (is (some? (:current-work (entry k))) "the next ensure refetches rather than fresh-skipping")))

(deftest invalidation-survives-an-aborted-refetch
  ;; the last owner leaves mid-refetch, and an abort writes no error facts, so
  ;; only the invalidation still marks the entry for a re-read
  (rf/reg-resource :ifza/article (article-spec) article-spec-request)
  (let [scope {:user "u"}
        k     (rf.resources.state/scoped-resource-key scope :ifza/article {:slug "w"})
        owner [:app :ifza 1]]
    (ensure! :ifza/article scope "w" owner)
    (succeed! k {:title "pre-mutation"})
    (rf/dispatch-sync [:rf.resource/invalidate-tags {:scope scope :tags #{[:article "w"]}}])
    (is (some? (:current-work (entry k))) "precondition: the owned entry's invalidation refetches")
    (rf/dispatch-sync [:rf.resource/release-owner {:owner owner}])
    (abort! k)
    (let [e (entry k)]
      (is (= [:loaded {:title "pre-mutation"} nil nil] ((juxt :status :data :error :refresh-error) e))
          "a refresh abort returns to :loaded with no error facts")
      (is (= [true true] [(some? (:invalidated-at e)) (rf.resources.state/entry-stale? e 0)])
          "the invalidation survives the aborted refetch"))
    (ensure! :ifza/article scope "w" [:app :ifza 2])
    (is (some? (:current-work (entry k))) "a later ensure refetches rather than fresh-skipping")))
