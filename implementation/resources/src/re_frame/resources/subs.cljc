(ns re-frame.resources.subs
  "The passive resource subscriptions: the read API over the resource cache.
  Subs never fetch; route entry, events and machines cause fetches.

  They are frame-state subs, reading app-db and runtime-db from one snapshot,
  so a `{:from-db …}` scope re-keys when the resolver's app-db inputs change.
  A reference that resolves nil reads no entry and projects
  `:status :unresolved`. The derived booleans are computed here, never stored.
  Spec 016 §Subscriptions (passive)"
  (:require [re-frame.error :as rf.error]
            [re-frame.interop :as rf.interop]
            [re-frame.resources.registry :as rf.resources.registry]
            [re-frame.resources.route :as rf.resources.route]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.work-ledger :as rf.resources.work-ledger]
            [re-frame.subs :as rf.subs]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- frame-state partition extraction -------------------------------------

(defn- runtime-of
  "The runtime-db partition of `frame-state`. A bare runtime-db map (the
  direct `compute-sub` test path) passes through."
  [frame-state]
  (if (or (contains? frame-state :rf.db/runtime)
          (contains? frame-state :rf.db/app))
    (get frame-state :rf.db/runtime)
    frame-state))

(defn- app-of
  "The app-db partition of `frame-state` (nil for a bare runtime-db map)."
  [frame-state]
  (get frame-state :rf.db/app))

;; ---- scoped-key resolution -----------------------------------------------

(defn resolve-scoped-key
  "Resolves the `{:resource :scope :params}` sub payload to its scoped key, or
  nil when a `{:from-db …}` scope resolves nil (no identity, so no entry is
  read). `db` is the frame app-db that a reference resolves against; pass
  `{}` when none is needed. Pure: subs never see the route tier. Throws
  `:rf.error/resource-not-registered` for an unknown resource.
  Spec 016 §Subscription-side scope resolution"
  [{:keys [resource] :as payload} db]
  (let [spec    (rf.resources.registry/require-resource-spec! resource 'rf.resource/subscribe)
        scope   (rf.resources.registry/resolve-scope-for-sub
                  resource spec (:scope payload) 'rf.resource/subscribe db)
        ;; Thread absent-vs-explicit-nil `:params` so the sub keys to the
        ;; same identity the ensure produced.
        cparams (rf.resources.registry/validate+canonicalize-params
                  resource spec (rf.resources.state/params-present? payload) 'rf.resource/subscribe)]
    ;; Both are already canonical, so use the trusted constructor: this
    ;; re-runs on every frame-state change.
    (when (some? scope)
      (rf.resources.state/scoped-resource-key* scope resource cparams))))

(defn- key+entry
  "`[scoped-key entry]` for a sub payload. The key is nil when its scope
  resolved nil; the entry is nil when none exists."
  [runtime-db app-db payload]
  (let [k (resolve-scoped-key payload app-db)]
    [k (when k (get-in runtime-db (rf.resources.state/entry-path k)))]))


;; ---- derived freshness ----------------------------------------------------
;; The clock is the wall clock `epoch-now-ms`, never the perf clock `now-ms`,
;; which is incomparable with `:stale-at`. Spec 016 §Freshness clock contract

(defn- stale?
  "True iff `entry` is invalidated or past `:stale-at`, through the shared
  `entry-stale?` so every reader agrees. Spec 016 §Status semantics"
  [entry]
  (rf.resources.state/entry-stale? entry (rf.interop/epoch-now-ms)))

;; ---- the projections (public derived sub values) -------------------------

(defn- previous-projection
  "The `:keep-previous?` view for a `:loading` entry with a `:previous-key`:
  `{:previous? true :previous-key … :previous-data …}`, read from the previous
  key's own entry and never inserted into this one. Otherwise
  `{:previous? false}`. Spec 016 §Paginated and previous data"
  [runtime-db e]
  (let [prev-key (:previous-key e)]
    (if (and prev-key (nil? (:data e)))
      (let [prev-entry (get-in runtime-db (rf.resources.state/entry-path prev-key))]
        {:previous?     true
         :previous-key  prev-key
         :previous-data (:data prev-entry)})
      {:previous? false})))

(defn- empty-view
  "The view-model of a read with no entry, under `status`: `:idle` for an
  identified key nobody has ensured, `:unresolved` when the scope resolved
  nil, `:unplanned` when the active route plan holds no identity."
  [status]
  {:status status :data nil :error nil :refresh-error nil
   :loading? false :fetching? false :stale? false :has-data? false
   :previous? false})

(defn- view-of
  "The public resource view-model of entry `e` (read from `rt`)."
  [rt e]
  (merge
    {:status        (:status e)
     :data          (:data e)
     :error         (:error e)
     :refresh-error (:refresh-error e)
     :loading?      (= :loading (:status e))
     :fetching?     (= :fetching (:status e))
     :stale?        (stale? e)
     ;; Not `(some? :data)`: an infinite feed's `:data` is seeded `[]`.
     :has-data?     (rf.resources.state/has-data? e)}
    (previous-projection rt e)))

(defn state-sub-fn
  "The `:rf/resource` view-model: the stored facts, the derived booleans and
  the `:keep-previous?` projection, or the empty shape at `:idle` (or
  `:unresolved` when there is no key). Spec 016 §Status semantics"
  [frame-state [_id payload]]
  (let [rt    (runtime-of frame-state)
        app   (app-of frame-state)
        [k e] (key+entry rt app payload)]
    (if (nil? e)
      (empty-view (if k :idle :unresolved))
      (view-of rt e))))

;; ---- the route-bound read -------------------------------------------------

(defn route-resource-key
  "The scoped key the active route's committed plan holds for `resource-id`,
  or nil when it holds none. Throws `:rf.error/resource-route-read-ambiguous`
  when the plan holds several identities (the read never picks one), and
  `:rf.error/resource-not-registered` for an unknown id.
  Spec 016 §Route-bound reads — `[:rf/route-resource <resource-id>]`"
  [runtime-db resource-id]
  (rf.resources.registry/require-resource-spec! resource-id 're-frame.resources.subs/route-resource-key)
  (let [nav-token  (get-in runtime-db (conj (rf.resources.route/current-path) :nav-token))
        identities (when (some? nav-token)
                     (vals (get-in runtime-db (rf.resources.route/plan-path nav-token))))
        matches    (sort-by rf.resources.state/key-id
                            (filter #(= resource-id (second %)) identities))]
    (case (count matches)
      0 nil
      1 (first matches)
      (rf.error/throw-error!
        :rf.error/resource-route-read-ambiguous
        're-frame.resources.subs/route-resource-key
        (str "[:rf/route-resource " (pr-str resource-id) "] is ambiguous: the "
             "active route's plan holds " (pr-str resource-id) " under "
             (count matches) " identities — " (pr-str (vec matches)) ". A "
             "route-bound read never picks one; read each with [:rf/resource "
             "{:resource … :params …}]. Per Spec 016 §Route-bound reads.")
        {:recovery :fix-params
         :extra    {:resource-id resource-id
                    :identities  (vec matches)}}))))

(defn route-resource-sub-fn
  "The `[:rf/route-resource <resource-id>]` view-model: as `:rf/resource` for
  the identity `route-resource-key` finds, or the empty shape at
  `:unplanned`. Spec 016 §Route-bound reads — `[:rf/route-resource <resource-id>]`"
  [frame-state [_id resource-id]]
  (let [rt (runtime-of frame-state)
        k  (route-resource-key rt resource-id)
        e  (when k (get-in rt (rf.resources.state/entry-path k)))]
    (cond
      (nil? k) (empty-view :unplanned)
      (nil? e) (empty-view :idle)
      :else    (view-of rt e))))

(def ^:private projection-subs
  "The focused subs, `[sub-id view-model-key doc]`, each projecting one key of
  `state-sub-fn`. They are frame-state subs rather than layered over
  `[:rf/resource …]` because off-box egress classifies a value against its
  inputs, which must be the frame-state whose runtime-db holds the instances
  the resources classification hook claims."
  [[:rf.resource/data :data
    "Passive read of a resource instance's last-known-good :data (or nil). Per Spec 016 §Subscriptions."]
   [:rf.resource/status :status
    "Passive read of a resource instance's :status keyword (:idle / :loading / :fetching / :loaded / :error, or :unresolved when its {:from-db} scope resolved nil). Per Spec 016 §Subscriptions."]
   [:rf.resource/loading? :loading?
    "Passive read: true iff a resource instance is on its first load with no usable data. Per Spec 016 §Status semantics."]
   [:rf.resource/fetching? :fetching?
    "Passive read: true iff a resource instance is refreshing while prior data stays visible. Per Spec 016 §Status semantics."]
   [:rf.resource/stale? :stale?
    "Passive read: true iff a resource instance is stale (freshness is orthogonal to load status). Per Spec 016 §Status semantics."]
   [:rf.resource/error :error
    "Passive read of a resource instance's first-load error envelope (or nil). Per Spec 016 §Status semantics."]
   [:rf.resource/refresh-error :refresh-error
    "Passive read of a resource instance's background-refresh error envelope (or nil); the prior :data is kept. Per Spec 016 §Status semantics."]
   [:rf.resource/has-data? :has-data?
    "Passive read: true iff a resource instance currently has usable :data. Per Spec 016 §Status semantics."]
   [:rf.resource/previous-data :previous-data
    "Passive read of the prior key's data, projected while a new page/filter resource first-loads under :keep-previous? (not inserted into the new entry). Per Spec 016 §Paginated and previous data."]])

(defn- projection-sub-fn
  "The sub fn projecting key `k` of the `:rf/resource` view-model."
  [k]
  (fn [frame-state query-v]
    (get (state-sub-fn frame-state query-v) k)))

;; ---- the infinite-feed projection family ----------------------------------
;; Spec 016 §Subscription contract: the merged list and page metadata (R3)

(defn- feed-key+entry
  "`[scoped-key feed-entry]`, with the entry nil unless it is an infinite
  feed. The key tells an unresolved scope from a key with no feed."
  [runtime-db app-db payload]
  (let [[k e] (key+entry runtime-db app-db payload)]
    [k (when (rf.resources.state/infinite-entry? e) e)]))

(defn- feed-entry-for
  "The infinite-feed entry for `payload`, or nil. A non-feed resource reads
  as an empty feed rather than throwing."
  [runtime-db app-db payload]
  (second (feed-key+entry runtime-db app-db payload)))

(defn merged-items
  "The flattened item list of feed `entry`, `[]` when empty. A non-vector page
  needs the resource's `:page->items`, else
  `:rf.error/infinite-missing-page-accessor`.

  There is no cache here: the calling subs are memoised by output `=`. A
  process-global memo keyed on the feed would outlive GC, `remove` and the
  frame, so memoise through the signal graph if ever needed."
  [entry where]
  (let [pages (:data entry)]
    (if (empty? pages)
      []
      (let [spec     (rf.resources.registry/resource-meta (:resource/id entry))
            accessor (rf.resources.state/resolve-page->items (:page->items spec))]
        (rf.resources.state/merge-pages->items
          pages accessor (:resource/id entry) where)))))

(defn items-sub-fn
  "`:rf.resource/items`: the merged item list, `[]` without a feed."
  [frame-state [_id payload]]
  (let [e (feed-entry-for (runtime-of frame-state) (app-of frame-state) payload)]
    (if e (merged-items e 'rf.resource/items) [])))

(defn pages-sub-fn
  "`:rf.resource/pages`: the raw ordered page vector, `[]` without a feed."
  [frame-state [_id payload]]
  (let [e (feed-entry-for (runtime-of frame-state) (app-of frame-state) payload)]
    (vec (:data e))))

(defn page-count-sub-fn
  "`:rf.resource/page-count`: the number of pages, 0 without a feed."
  [frame-state [_id payload]]
  (let [e (feed-entry-for (runtime-of frame-state) (app-of frame-state) payload)]
    (rf.resources.state/page-count e)))

(defn has-next-page?-sub-fn
  "`:rf.resource/has-next-page?`: true iff `:next-page-param` is not terminal."
  [frame-state [_id payload]]
  (let [e (feed-entry-for (runtime-of frame-state) (app-of frame-state) payload)]
    (and (some? e) (not (rf.resources.state/terminal? (:next-page-param e))))))

(defn page-error-sub-fn
  "`:rf.resource/page-error`: the last load-more failure, or nil."
  [frame-state [_id payload]]
  (:page-error (feed-entry-for (runtime-of frame-state) (app-of frame-state) payload)))

(defn- fetching-next?*
  "True iff a load-more is in flight on feed `entry`. Load-more and a whole-feed
  refresh both sit at `:fetching`, so it reads the live attempt and checks for
  a positive `:page-index`.
  Spec 016 §Causal event — `:rf.resource/load-more` (R2)"
  [entry]
  (boolean
    (when (and (some? entry) (= :fetching (:status entry)))
      (and (rf.resources.work-ledger/live-work? entry)
           (let [pi (:page-index (:work entry))]
             (and (integer? pi) (pos? pi)))))))

(defn fetching-next?-sub-fn
  "`:rf.resource/fetching-next?`: a load-more in flight, distinct from
  `:fetching?`."
  [frame-state [_id payload]]
  (let [rt (runtime-of frame-state)
        e  (feed-entry-for rt (app-of frame-state) payload)]
    (fetching-next?* e)))

(defn infinite-state-sub-fn
  "`:rf.resource/infinite-state`: the combined feed view-model, or the
  empty-feed shape at `:idle` (`:unresolved` without a key). `:fetching?` and
  `:fetching-next?` are disjoint.
  Spec 016 §Subscription contract: the merged list and page metadata (R3)"
  [frame-state [_id payload]]
  (let [rt    (runtime-of frame-state)
        [k e] (feed-key+entry rt (app-of frame-state) payload)]
    (if (nil? e)
      {:status (if k :idle :unresolved) :items [] :pages [] :page-count 0
       :has-next-page? false
       :loading? false :fetching? false :fetching-next? false
       :stale? false :has-data? false
       :error nil :refresh-error nil :page-error nil}
      (let [next? (fetching-next?* e)]
        {:status         (:status e)
         :items          (merged-items e 'rf.resource/infinite-state)
         :pages          (vec (:data e))
         :page-count     (rf.resources.state/page-count e)
         :has-next-page? (not (rf.resources.state/terminal? (:next-page-param e)))
         :loading?       (= :loading (:status e))
         :fetching?      (and (= :fetching (:status e)) (not next?))
         :fetching-next? next?
         :stale?         (stale? e)
         :has-data?      (rf.resources.state/has-data? e)
         :error          (:error e)
         :refresh-error  (:refresh-error e)
         :page-error     (:page-error e)}))))

;; ---- registration helper -------------------------------------------------

(defn register-subs!
  "Registers the `:rf.resource/*` passive sub family. Called from the façade so
  a `:reload` re-wires them. Spec 016 §Subscriptions (passive)"
  []
  (rf.subs/reg-frame-state-sub :rf/resource
    {:doc "Passive read of a resource instance's full view-model `{:status :data :error :refresh-error :loading? :fetching? :stale? :has-data?}`. Resolves scope per Spec 016 §Subscription-side scope resolution (incl. `{:from-db <id>}` named-resolver references against app-db); a reference that resolves nil reads no entry and projects :status :unresolved with the empty shape, never global and never :idle. Per Spec 016 §Subscriptions."}
    state-sub-fn)
  (rf.subs/reg-frame-state-sub :rf/route-resource
    {:doc "Passive read of the resource the ACTIVE route's plan ensured: `[:rf/route-resource <resource-id>]` projects the same view-model as `:rf/resource` for the one identity the plan holds under that id, so a view restates none of the route's params. A plan holding the id under several identities throws :rf.error/resource-route-read-ambiguous naming them; a plan holding none reads :status :unplanned with the empty shape. Per Spec 016 §Route-bound reads."}
    route-resource-sub-fn)
  (doseq [[sub-id k doc] projection-subs]
    (rf.subs/reg-frame-state-sub sub-id {:doc doc} (projection-sub-fn k)))
  (rf.subs/reg-frame-state-sub :rf.resource/items
    {:doc "Passive read of an infinite feed's MERGED / flattened item list (the headline read) — DERIVED + framework-owned-memoised by concatenating each accumulated page's items (R3). A vector page flattens by identity; a non-vector / enveloped page REQUIRES a :page->items accessor (else :rf.error/infinite-missing-page-accessor). [] for a non-feed / empty feed. Per Spec 016 §Subscription contract (R3)."}
    items-sub-fn)
  (rf.subs/reg-frame-state-sub :rf.resource/pages
    {:doc "Passive read of an infinite feed's raw ordered page vector (the TanStack `pages` analogue) — for views that need page boundaries. [] for a non-feed. Per Spec 016 §Subscription contract (R3)."}
    pages-sub-fn)
  (rf.subs/reg-frame-state-sub :rf.resource/page-count
    {:doc "Passive read of an infinite feed's accumulated page count (0 when no feed). Per Spec 016 §Subscription contract."}
    page-count-sub-fn)
  (rf.subs/reg-frame-state-sub :rf.resource/has-next-page?
    {:doc "Passive read: true iff an infinite feed has a next page ((some? :next-page-param) — nil is the single terminal). false for a non-feed / terminal feed. Per Spec 016 §Subscription contract."}
    has-next-page?-sub-fn)
  (rf.subs/reg-frame-state-sub :rf.resource/fetching-next?
    {:doc "Passive read: true iff a LOAD-MORE is in flight on an infinite feed (R2) — distinct from :fetching? (a whole-feed refresh). Derived from the in-flight attempt's page index (a load-more appends at a positive index). Per Spec 016 §Subscription contract / §Causal event — load-more."}
    fetching-next?-sub-fn)
  (rf.subs/reg-frame-state-sub :rf.resource/page-error
    {:doc "Passive read of an infinite feed's last LOAD-MORE failure envelope (the third error channel; distinct from :error first-load and :refresh-error whole-feed), or nil. Per Spec 016 §Subscription contract / §Causal event — load-more."}
    page-error-sub-fn)
  (rf.subs/reg-frame-state-sub :rf.resource/infinite-state
    {:doc "Passive read of an infinite feed's combined view-model `{:status :items :pages :page-count :has-next-page? :loading? :fetching? :fetching-next? :stale? :has-data? :error :refresh-error :page-error}` — the feed analogue of :rf/resource, with the framework-owned-memoised merged :items (R3). Empty-feed shape when no infinite entry, with :status :unresolved when its {:from-db} scope resolved nil. Per Spec 016 §Subscription contract (R3)."}
    infinite-state-sub-fn)
  nil)
