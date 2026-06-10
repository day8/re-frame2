(ns re-frame.resources.subs
  "The passive resource subscriptions — the read API over the resource
  cache. Per Spec 016 §Public API §Subscriptions (passive) and §Status
  semantics.

  Subscriptions are PURE passive reads (Spec 016: \"No v1 subscription
  fetches\"): a view reads a resource through `[:rf.resource/state …]` (or
  a narrower projection like `[:rf.resource/data …]`); route entry,
  events, and machines CAUSE the fetch. A resource sub resolves its scope
  per Spec 016 §Subscription-side scope resolution (payload `:scope`, or a
  sub-resolvable spec policy) and raises
  `:rf.error/resource-sub-unresolved-scope` rather than reading global or
  returning a silent `:idle` — the read-side counterpart of the write-side
  fail-closed gate.

  The framework resource subs read the frame's RUNTIME-DB projection
  (`reg-runtime-sub`) — the durable cache lives at
  `[:rf.runtime/resources :entries <scoped-resource-key>]` in runtime-db.
  The derived booleans (`:stale?` / `:loading?` / `:fetching?` /
  `:has-data?`) are PUBLIC DERIVED SUB VALUES computed here from the
  durable entry facts, NOT stored on the entry (Spec 016 §Status
  semantics).

  SKELETON slice (rf2-p10npe): the sub registrations are real and the
  projection shapes are pinned, but scope resolution + params
  canonicalization (which compute the scoped resource key the entry is
  looked up under) land with the runtime slice (rf2-pbxj48). Until then
  `resolve-scoped-key` returns nil and the projections degrade to the
  documented empty-state shape — the subs compile, load, and read cleanly;
  they just have no live entry to project yet."
  (:require [re-frame.resources.state :as state]
            [re-frame.subs :as subs]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- scoped-key resolution (skeleton) ------------------------------------

(defn resolve-scoped-key
  "Resolve the `{:resource :scope :params}` sub payload to the scoped
  resource key `[cache-scope resource-id canonical-params]` the cache
  entry is stored under — canonicalizing the scope + params maps (Spec
  016 §Canonicalization rule) and applying the sub-side scope-resolution
  precedence (Spec 016 §Subscription-side scope resolution).

  SKELETON: returns nil (no live key resolution yet). The runtime slice
  (rf2-pbxj48) supplies canonicalization + sub-side scope resolution and
  the fail-closed `:rf.error/resource-sub-unresolved-scope` raise. With a
  nil key the projections below return the documented empty-state shape."
  [_payload]
  nil)

(defn- entry-for
  "Look up the durable cache entry for a sub payload, or nil when no
  scoped key resolves yet (skeleton) or no entry exists."
  [runtime-db payload]
  (when-let [k (resolve-scoped-key payload)]
    (get-in runtime-db (state/entry-path k))))

;; ---- the projections (public derived sub values) -------------------------

(defn state-sub-fn
  "Project the public `:rf.resource/state` view-model from a durable
  entry: the stored facts plus the DERIVED booleans (`:loading?` /
  `:fetching?` / `:stale?` / `:has-data?`) computed here, never stored.
  Per Spec 016 §Status semantics. Empty-state shape when no entry."
  [runtime-db [_id payload]]
  (let [e (entry-for runtime-db payload)]
    (if (nil? e)
      ;; No entry yet — the documented idle empty-state projection.
      {:status :idle :data nil :error nil :refresh-error nil
       :loading? false :fetching? false :stale? false :has-data? false}
      {:status        (:status e)
       :data          (:data e)
       :error         (:error e)
       :refresh-error (:refresh-error e)
       ;; Derived (Spec 016 §Status semantics): :loading? = first load
       ;; with no usable data; :fetching? = refresh in flight; :has-data? =
       ;; usable data present; :stale? = freshness vs :stale-at (runtime
       ;; slice computes the live clock comparison — pinned shape here).
       :loading?      (= :loading (:status e))
       :fetching?     (= :fetching (:status e))
       :stale?        false
       :has-data?     (some? (:data e))})))

(defn data-sub-fn
  "Project `:rf.resource/data` — the entry's last-known-good `:data` (or
  nil). Per Spec 016 §Subscriptions."
  [runtime-db [_id payload]]
  (:data (entry-for runtime-db payload)))

(defn status-sub-fn
  "Project `:rf.resource/status` — the entry's `:status` keyword (or
  `:idle` when no entry). Per Spec 016 §Subscriptions."
  [runtime-db [_id payload]]
  (or (:status (entry-for runtime-db payload)) :idle))

(defn loading?-sub-fn
  "Project `:rf.resource/loading?` — first load with no usable data. Per
  Spec 016 §Status semantics."
  [runtime-db [_id payload]]
  (= :loading (:status (entry-for runtime-db payload))))

(defn fetching?-sub-fn
  "Project `:rf.resource/fetching?` — work in flight while prior data
  stays visible. Per Spec 016 §Status semantics."
  [runtime-db [_id payload]]
  (= :fetching (:status (entry-for runtime-db payload))))

(defn stale?-sub-fn
  "Project `:rf.resource/stale?` — freshness orthogonal to load status
  (a `:loaded` entry may be stale). Per Spec 016 §Status semantics.
  SKELETON: returns false until the runtime slice computes the live
  `:stale-at` comparison."
  [_runtime-db [_id _payload]]
  false)

(defn error-sub-fn
  "Project `:rf.resource/error` — the first-load error envelope (or nil).
  Per Spec 016 §Status semantics."
  [runtime-db [_id payload]]
  (:error (entry-for runtime-db payload)))

(defn refresh-error-sub-fn
  "Project `:rf.resource/refresh-error` — a failed background refresh
  (prior data kept). Per Spec 016 §Status semantics."
  [runtime-db [_id payload]]
  (:refresh-error (entry-for runtime-db payload)))

(defn has-data?-sub-fn
  "Project `:rf.resource/has-data?` — usable data present. Per Spec 016
  §Status semantics."
  [runtime-db [_id payload]]
  (some? (:data (entry-for runtime-db payload))))

(defn previous-data-sub-fn
  "Project `:rf.resource/previous-data` — the prior key's data projected
  while a new page/filter resource first-loads under `:keep-previous?`.
  NOT inserted into the new entry. Per Spec 016 §Paginated and previous
  data. SKELETON: returns nil until the runtime slice tracks previous
  keys."
  [_runtime-db [_id _payload]]
  nil)

;; ---- registration helper -------------------------------------------------

(defn register-subs!
  "Register the `:rf.resource/*` passive sub family. Called from the
  `re-frame.resources` façade so a `(require … :reload)` on a fresh
  registrar re-wires them. Per Spec 016 §Subscriptions."
  []
  (subs/reg-runtime-sub :rf.resource/state
    {:doc "Passive read of a resource instance's full view-model `{:status :data :error :refresh-error :loading? :fetching? :stale? :has-data?}`. Resolves scope per Spec 016 §Subscription-side scope resolution; raises :rf.error/resource-sub-unresolved-scope rather than reading global / returning a silent :idle. Per Spec 016 §Subscriptions."}
    state-sub-fn)
  (subs/reg-runtime-sub :rf.resource/data
    {:doc "Passive read of a resource instance's last-known-good :data (or nil). Per Spec 016 §Subscriptions."}
    data-sub-fn)
  (subs/reg-runtime-sub :rf.resource/status
    {:doc "Passive read of a resource instance's :status keyword (:idle / :loading / :fetching / :loaded / :error). Per Spec 016 §Subscriptions."}
    status-sub-fn)
  (subs/reg-runtime-sub :rf.resource/loading?
    {:doc "Passive read: true iff a resource instance is on its first load with no usable data. Per Spec 016 §Status semantics."}
    loading?-sub-fn)
  (subs/reg-runtime-sub :rf.resource/fetching?
    {:doc "Passive read: true iff a resource instance is refreshing while prior data stays visible. Per Spec 016 §Status semantics."}
    fetching?-sub-fn)
  (subs/reg-runtime-sub :rf.resource/stale?
    {:doc "Passive read: true iff a resource instance is stale (freshness is orthogonal to load status). Per Spec 016 §Status semantics."}
    stale?-sub-fn)
  (subs/reg-runtime-sub :rf.resource/error
    {:doc "Passive read of a resource instance's first-load error envelope (or nil). Per Spec 016 §Status semantics."}
    error-sub-fn)
  (subs/reg-runtime-sub :rf.resource/refresh-error
    {:doc "Passive read of a resource instance's background-refresh error envelope (or nil); the prior :data is kept. Per Spec 016 §Status semantics."}
    refresh-error-sub-fn)
  (subs/reg-runtime-sub :rf.resource/has-data?
    {:doc "Passive read: true iff a resource instance currently has usable :data. Per Spec 016 §Status semantics."}
    has-data?-sub-fn)
  (subs/reg-runtime-sub :rf.resource/previous-data
    {:doc "Passive read of the prior key's data, projected while a new page/filter resource first-loads under :keep-previous? (not inserted into the new entry). Per Spec 016 §Paginated and previous data."}
    previous-data-sub-fn)
  nil)
