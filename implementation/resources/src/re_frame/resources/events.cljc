(ns re-frame.resources.events
  "The resource event handlers — the causal write surface over the
  resource cache. Per Spec 016 §Public API §Events.

  The public resource events take MAP payloads (not positional argument
  vectors):

    [:rf.resource/ensure          {:resource … :scope … :params … :owner … :cause …}]
    [:rf.resource/refetch         {:resource … :scope … :params … :cause …}]
    [:rf.resource/invalidate-tags {:scope … :tags … :cause …}]
    [:rf.resource/release-owner   {:owner …}]
    [:rf.resource/clear-scope     {:scope … :cause …}]
    [:rf.resource/remove          {:resource … :scope … :params …}]

  Plus the framework-INTERNAL replies (`:rf.resource.internal/*`) that
  carry the verification payload (`:work-id` / `:resource-key` / `:scope`
  / `:generation` / `:rf.frame/id`) — user code MUST NOT dispatch them.

  Every handler carries framework-write authority
  (`state/framework-authority-meta`) so a returned `:rf.db/runtime`
  effect is in-bounds (Spec 016 §Write authority); the registrations live
  in the `re-frame.resources` façade so a `(require … :reload)` on a
  fresh registrar re-wires them.

  ## Slice boundary (rf2-pbxj48 resource runtime)

  This slice implements the CACHE-ENTRY runtime: canonical params /
  scopes / scoped-key identity, the compact lifecycle status transition
  function, structural sharing, the durable entries map (facts not derived
  booleans), per-frame isolation, owner / tag indexes, exact tag
  invalidation, scope clear, owner release, and remove. Stale suppression
  is enforced on the ENTRY (the reply handlers verify generation + work-id
  against the live entry before writing — Spec 016 §Cancellation is
  opportunistic; stale suppression is mandatory).

  The parallel serializable `:rf.runtime/work-ledger` records, host-side
  side tables (AbortControllers / timer handles), and opportunistic abort
  are the work-ledger substrate slice (rf2-afpdkn) — out of this slice. GC
  scheduling / timers are the invalidation+GC slice. The HTTP request
  execution is the managed-HTTP slice (rf2-p19360); this slice LOWERS into
  the existing transport seam (`transport/lower-ensure`)."
  (:require [clojure.set :as set]
            [re-frame.resources.registry :as registry]
            [re-frame.resources.state :as state]
            [re-frame.resources.transport :as transport]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- shared clock + work-id -----------------------------------------------

(defn- now-ms
  "Current epoch-ms. Used for `:loaded-at` / `:stale-at` durable
  timestamps (Spec 016 §Stale and GC scheduling: freshness is computed
  from durable timestamps). Host-platform clock."
  []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn- work-id-for
  "Build the work-id `[:rf.work/resource <scoped-key> <generation>]` that
  embeds the generation (Spec 016 §Frame work ledger — the work-id embeds
  the generation; stale suppression keys on `:work/id`). The full
  serializable work-ledger record is the rf2-afpdkn slice; this slice
  carries the work-id on the entry's `:current-work` so the reply handlers
  can verify it."
  [scoped-key generation]
  [:rf.work/resource scoped-key generation])

(defn- stale-at-for
  "Compute `:stale-at` from `loaded-at` + the resource's `:stale-after-ms`
  policy, or nil when the resource declares no staleness policy (it never
  goes stale on a timer). Per Spec 016 §Stale and GC scheduling."
  [spec loaded-at]
  (when-let [ms (:stale-after-ms spec)]
    (+ loaded-at ms)))

;; ---- ensure / refetch — the load-causing events ---------------------------

(defn- ensure-load
  "Shared ensure/refetch core. Resolves the scope + canonical params into a
  scoped resource key, mints the next monotone generation (from the cofx
  snapshot), transitions the entry to its in-flight status
  (`:loading`/`:fetching`), attaches the owner + records the cause, and
  lowers into the resource's transport. `force-new?` true (refetch) always
  starts a new generation even when a request is already in flight (Spec
  016 §Race: refetch may force a new generation). `force-new?` false
  (ensure) joins an in-flight request for the SAME generation/scoped-key
  when one exists (dedupe).

  Returns the event-fx map `{:rf.db/runtime :fx}`."
  [{rt :rf.db/runtime, frame-id :rf.frame/id, gen-snapshot :rf.resource/generation}
   {:keys [resource params owner cause] :as payload} {:keys [force-new? where]}]
  (let [runtime-db (or rt {})
        spec       (registry/require-resource-spec! resource where)
        scope      (registry/resolve-scope-for-event
                     resource spec {:payload-scope (:scope payload)} where)
        cparams    (registry/validate+canonicalize-params resource spec params where)
        scoped-key (state/scoped-resource-key scope resource cparams)
        entry      (or (get-in runtime-db (state/entry-path scoped-key))
                       (state/empty-entry resource))
        in-flight? (some? (:current-work entry))]
    (if (and in-flight? (not force-new?))
      ;; ----- dedupe: join the in-flight request (ensure only) -------------
      ;; Attach any supplied owner to the existing entry + record the cause;
      ;; do NOT start a new generation. Per Spec 016 §Race (ensure while in
      ;; flight joins the existing current work record).
      (let [joined (cond-> entry
                     owner (update :active-owners (fnil conj #{}) owner))
            rdb'   (-> runtime-db
                       (assoc-in (state/entry-path scoped-key) joined)
                       (cond->
                         owner (update-in (state/owner-index-path)
                                          update owner (fnil conj #{}) scoped-key)))]
        (trace/emit! :rf.event :rf.resource/deduped
                     {:rf.frame/id frame-id :resource-key scoped-key
                      :generation (:generation entry) :owner owner :cause cause})
        {:rf.db/runtime rdb'})
      ;; ----- start a new load attempt (fresh generation) -----------------
      (let [generation (state/next-generation gen-snapshot)
            work-id    (work-id-for scoped-key generation)
            request-id work-id
            entry'     (state/entry-start-load
                         entry {:generation generation :work-id work-id
                                :request-id request-id :owner owner})
            rdb'       (-> runtime-db
                           (assoc-in (state/entry-path scoped-key) entry')
                           (cond->
                             owner (update-in (state/owner-index-path)
                                              update owner (fnil conj #{}) scoped-key)))
            ;; lower into the resource's transport (the existing seam). The
            ;; runtime owns reply addressing: the internal reply payloads
            ;; stamp the qualified :rf.frame/id + :work-id + :resource-key +
            ;; :scope + :generation so the reply handlers verify before
            ;; writing (stale suppression is the correctness boundary).
            http-args  (let [req-fn (:request spec)]
                         (req-fn cparams nil))
            lower-fx   (transport/lower-ensure
                         (:transport spec)
                         {:http-args    http-args
                          :request-id   request-id
                          :work-id      work-id
                          :resource-key scoped-key
                          :scope        scope
                          :frame-id     frame-id
                          :generation   generation
                          :where        where})]
        (trace/emit! :rf.event :rf.resource/fetch-started
                     {:rf.frame/id frame-id :resource-key scoped-key
                      :generation generation :work-id work-id
                      :status (:status entry') :owner owner :cause cause})
        {:rf.db/runtime rdb'
         ;; WRITE half of the host-side generation seam + the transport fx.
         :fx [[:rf.resource/commit-generation {:value generation}]
              lower-fx]}))))

(defn ensure-handler
  "`:rf.resource/ensure` — ensure a resource instance is loaded (load it
  if absent; join the in-flight work record if one exists; attach `:owner`
  to the entry; record `:cause`). Per Spec 016 §Events and §Race and
  in-flight semantics. Payload: `{:resource :scope :params :owner :cause}`."
  [cofx [_event-id payload]]
  (ensure-load cofx payload {:force-new? false :where 'rf.resource/ensure}))

(defn refetch-handler
  "`:rf.resource/refetch` — force a refresh of a resource instance (forces
  a new generation; supersede + suppress any in-flight prior request by
  generation). Per Spec 016 §Events and §Race and in-flight semantics.
  Payload: `{:resource :scope :params :cause}`."
  [cofx [_event-id payload]]
  (ensure-load cofx payload {:force-new? true :where 'rf.resource/refetch}))

;; ---- invalidate-tags — exact tag invalidation -----------------------------

(defn invalidate-tags-handler
  "`:rf.resource/invalidate-tags` — exact tag invalidation (Spec 016
  §Invalidation). Scoped by default: marks every entry whose provided tags
  intersect `:tags` AND whose scope matches `:scope` as stale (sets
  `:invalidated-at`); active-owner entries are refetched, inactive entries
  are left stale / GC-eligible. Emits one decision summary + per-entry
  details. Payload: `{:scope :tags :cause}`.

  This slice marks entries stale + records the invalidation, and dispatches
  a `:rf.resource/refetch` for each active-owner entry. The refetch-vs-
  leave-stale decision keys on `:active-owners` presence."
  [{rt :rf.db/runtime, frame-id :rf.frame/id} [_event-id {:keys [scope tags cause]}]]
  (let [runtime-db (or rt {})
        cscope     (state/canonicalize scope)
        invalidated-at (now-ms)
        entries    (get-in runtime-db (state/entries-path))
        ;; matched: scope matches AND tags intersect (exact tag match)
        matched    (into {}
                         (filter (fn [[k entry]]
                                   (and (= cscope (first k))
                                        (seq (set/intersection
                                               (set (:tags entry)) (set tags))))))
                         entries)
        ;; mark each matched entry stale (durable :invalidated-at fact)
        rdb'       (reduce-kv
                     (fn [db k entry]
                       (assoc-in db (state/entry-path k)
                                 (assoc entry :invalidated-at invalidated-at)))
                     runtime-db matched)
        ;; refetch only the active-owner entries (Spec 016 §Invalidation 3)
        refetches  (into []
                         (comp
                           (filter (fn [[_ entry]] (seq (:active-owners entry))))
                           (map (fn [[k _]]
                                  (let [[s rid p] k]
                                    [:dispatch [:rf.resource/refetch
                                                {:resource rid :scope s :params p
                                                 :cause [:invalidate {:tags tags}]}]]))))
                         matched)]
    (trace/emit! :rf.event :rf.resource/invalidated
                 {:rf.frame/id frame-id :scope cscope :tags tags :cause cause
                  :matched (vec (keys matched)) :refetched (count refetches)})
    {:rf.db/runtime rdb'
     :fx refetches}))

;; ---- release-owner --------------------------------------------------------

(defn release-owner-handler
  "`:rf.resource/release-owner` — release a liveness lease (drop the owner
  from every entry's `:active-owners` + the owner-index). Per Spec 016
  §Active owners and causes. Payload: `{:owner …}`.

  Abort of in-flight work with no remaining owner is the work-ledger
  substrate slice (rf2-afpdkn); this slice drops the owner from the durable
  entry + index. Stale suppression by generation already protects any late
  reply (the entry's generation is unchanged, so a current reply still
  lands; only an abort-on-no-owner optimisation is deferred)."
  [{rt :rf.db/runtime, frame-id :rf.frame/id} [_event-id {:keys [owner]}]]
  (let [runtime-db (or rt {})
        owned      (get-in runtime-db (conj (state/owner-index-path) owner))
        rdb'       (-> (reduce
                         (fn [db k]
                           (update-in db (state/entry-path k)
                                      (fn [e] (when e (update e :active-owners disj owner)))))
                         runtime-db (or owned #{}))
                       (update-in (state/owner-index-path) dissoc owner))]
    (trace/emit! :rf.event :rf.resource/owner-released
                 {:rf.frame/id frame-id :owner owner :released (vec (or owned #{}))})
    {:rf.db/runtime rdb'}))

;; ---- clear-scope — the causal logout / tenant-switch boundary --------------

(defn clear-scope-handler
  "`:rf.resource/clear-scope` — causal scope clear (Spec 016 §clear-scope
  is causal). Removes every entry in the scope, releases its owners from
  the owner-index, and emits an explaining trace. Aborting in-flight
  requests with no remaining owner outside the scope is the work-ledger
  slice (rf2-afpdkn); stale suppression by generation already protects a
  late reply — the entry it would write into is gone, so the reply
  handler's existence check suppresses it. Payload: `{:scope :cause}`."
  [{rt :rf.db/runtime, frame-id :rf.frame/id} [_event-id {:keys [scope cause]}]]
  (let [runtime-db (or rt {})
        cscope     (state/canonicalize scope)
        entries    (get-in runtime-db (state/entries-path))
        in-scope   (into #{} (comp (filter (fn [[k _]] (= cscope (first k))))
                                   (map key))
                         entries)
        ;; remove the entries, then recompute the indexes from what remains
        rdb'       (-> runtime-db
                       (update-in (state/entries-path)
                                  (fn [es] (reduce dissoc es in-scope)))
                       (update state/resources-key state/recompute-indexes))]
    (trace/emit! :rf.event :rf.resource/removed
                 {:rf.frame/id frame-id :scope cscope :cause cause
                  :removed (vec in-scope) :reason :clear-scope})
    {:rf.db/runtime rdb'}))

;; ---- remove — single-instance cache removal --------------------------------

(defn remove-handler
  "`:rf.resource/remove` — remove a single resource instance from the cache
  by its scoped key, and drop its owner/tag-index rows. Per Spec 016
  §Events. Payload: `{:resource :scope :params}`."
  [{rt :rf.db/runtime, frame-id :rf.frame/id} [_event-id {:keys [resource params] :as payload}]]
  (let [runtime-db (or rt {})
        spec       (registry/require-resource-spec! resource 'rf.resource/remove)
        scope      (registry/resolve-scope-for-event
                     resource spec {:payload-scope (:scope payload)} 'rf.resource/remove)
        cparams    (registry/validate+canonicalize-params
                     resource spec params 'rf.resource/remove)
        scoped-key (state/scoped-resource-key scope resource cparams)
        rdb'       (-> runtime-db
                       (update-in (state/entries-path) dissoc scoped-key)
                       (update state/resources-key state/recompute-indexes))]
    (trace/emit! :rf.event :rf.resource/removed
                 {:rf.frame/id frame-id :resource-key scoped-key :reason :remove})
    {:rf.db/runtime rdb'}))

;; ---- framework-internal reply handlers ------------------------------------
;;
;; These carry the verification payload and MUST verify frame + work-id +
;; generation before writing (Spec 016 §Transport — stale suppression is
;; the correctness boundary). User code MUST NOT dispatch them.

(defn- live-entry-for-reply
  "Look the live entry up for an internal reply and verify it is still the
  one the reply belongs to: the entry exists AND its `:current-work` equals
  the reply's `:work-id` AND its `:generation` equals the reply's
  `:generation`. Returns the entry on a match, nil on a stale / superseded /
  vanished reply (which MUST be suppressed — Spec 016 §Cancellation is
  opportunistic; stale suppression is mandatory). The work-id is the single
  identity (it embeds the generation); the generation check is belt-and-
  braces for a future transport that reuses a work-id."
  [runtime-db {:keys [resource-key work-id generation]}]
  (when-let [entry (get-in runtime-db (state/entry-path resource-key))]
    (when (and (= work-id (:current-work entry))
               (= generation (:generation entry)))
      entry)))

;; ---- transport reply payload extraction -----------------------------------
;;
;; The managed-HTTP transport (Spec 014 §Reply addressing) APPENDS its
;; result to the runtime-supplied `:on-success` / `:on-failure` internal
;; reply event vector as the LAST arg, so a live reply lands as a 3-element
;; event:
;;
;;   [:rf.resource.internal/succeeded <verification-payload> {:kind :success :value <decoded-data>}]
;;   [:rf.resource.internal/failed    <verification-payload> {:kind :failure :failure <:rf.http/* envelope>}]
;;
;; `<verification-payload>` (arg 2) is the `{:work-id :resource-key :scope
;; :generation :rf.frame/id}` map resource lowering supplied (the stale-
;; suppression identity, the boundary the runtime OWNS). `<http-result>`
;; (arg 3) is the transport's outcome. The runtime reads the verification
;; identity from arg 2 and the data / error from arg 3. A test that feeds an
;; internal reply directly may inline `:data` / `:error` in arg 2 (no
;; transport in the loop); the reader below falls back to that shape so the
;; runtime-slice tests keep exercising the entry semantics deterministically.

(defn- reply-success-data
  "Extract the decoded success data from a managed-HTTP success reply. The
  transport appends `{:kind :success :value <decoded-data>}` as `http-result`
  (arg 3); read its `:value`. Falls back to an inline `:data` on the
  verification payload (the direct-dispatch test shape)."
  [verification-payload http-result]
  (if (contains? http-result :value)
    (:value http-result)
    (:data verification-payload)))

(defn- reply-failure-error
  "Extract the failure envelope from a managed-HTTP failure reply. The
  transport appends `{:kind :failure :failure <:rf.http/* envelope>}` as
  `http-result` (arg 3); read its `:failure` (the closed `:rf.http/*`
  failure shape, the same envelope `:error` / `:refresh-error` carry — Spec
  016 §Status semantics). Falls back to an inline `:error` on the
  verification payload (the direct-dispatch test shape)."
  [verification-payload http-result]
  (if (contains? http-result :failure)
    (:failure http-result)
    (:error verification-payload)))

(defn succeeded-handler
  "`:rf.resource.internal/succeeded` — a transport read succeeded. Verifies
  frame + work-id + generation against the live entry; on match installs the
  decoded `:data` (`:loaded`), preserving the old `:data` value when the new
  data is `=` (structural sharing), and records `:loaded-at` / `:stale-at` /
  produced `:tags`. A stale / superseded reply is SUPPRESSED (it MUST NEVER
  mutate a newer entry). Per Spec 016 §Transport / §Structural sharing /
  §Status semantics.

  Event shape: `[_ <verification-payload> <http-result>]` — the managed-HTTP
  transport appends `{:kind :success :value <decoded-data>}` as the last arg
  (Spec 014 §Reply addressing); the decoded data is read from there
  (`reply-success-data`)."
  [{rt :rf.db/runtime, frame-id :rf.frame/id}
   [_event-id {:keys [resource-key work-id generation] :as payload} http-result]]
  (let [runtime-db (or rt {})
        data       (reply-success-data payload http-result)
        entry      (live-entry-for-reply runtime-db payload)]
    (if (nil? entry)
      (do (trace/emit! :rf.event :rf.resource/stale-suppressed
                       {:rf.frame/id frame-id :resource-key resource-key
                        :work-id work-id :generation generation :outcome :success})
          {:rf.db/runtime runtime-db})
      (let [spec      (registry/resource-meta (:resource/id entry))
            loaded-at (now-ms)
            stale-at  (stale-at-for spec loaded-at)
            tags-fn   (:tags spec)
            ;; tags are produced from the params + decoded data; the canonical
            ;; params are the third element of the scoped key
            tags      (when tags-fn (set (tags-fn (nth resource-key 2) data)))
            entry'    (state/entry-succeeded
                        entry {:data data :loaded-at loaded-at
                               :stale-at stale-at :tags tags})
            ;; on a successful load the tag index for this key is REPLACED
            ;; with the new tags (old tags removed); recompute is the simple,
            ;; correct way to keep both indexes consistent.
            rdb'      (-> runtime-db
                          (assoc-in (state/entry-path resource-key) entry')
                          (update state/resources-key state/recompute-indexes))]
        (trace/emit! :rf.event :rf.resource/succeeded
                     {:rf.frame/id frame-id :resource-key resource-key
                      :work-id work-id :generation generation
                      :status-before (:status entry) :status-after :loaded})
        {:rf.db/runtime rdb'}))))

(defn failed-handler
  "`:rf.resource.internal/failed` — a transport read failed. Verifies frame
  + work-id + generation; a first-load failure settles `:error` (no usable
  data); a background-refresh failure returns to `:loaded`, keeps prior
  `:data`, and records `:refresh-error`. A stale / superseded reply is
  suppressed. Per Spec 016 §Status semantics.

  Event shape: `[_ <verification-payload> <http-result>]` — the managed-HTTP
  transport appends `{:kind :failure :failure <:rf.http/* envelope>}` as the
  last arg (Spec 014 §Reply addressing); the failure envelope is read from
  there (`reply-failure-error`)."
  [{rt :rf.db/runtime, frame-id :rf.frame/id}
   [_event-id {:keys [resource-key work-id generation] :as payload} http-result]]
  (let [runtime-db (or rt {})
        error      (reply-failure-error payload http-result)
        entry      (live-entry-for-reply runtime-db payload)]
    (if (nil? entry)
      (do (trace/emit! :rf.event :rf.resource/stale-suppressed
                       {:rf.frame/id frame-id :resource-key resource-key
                        :work-id work-id :generation generation :outcome :failure})
          {:rf.db/runtime runtime-db})
      (let [entry' (state/entry-failed entry {:error error})
            op     (if (= :error (:status entry'))
                     :rf.resource/failed :rf.resource/refresh-failed)]
        (trace/emit! :rf.event op
                     {:rf.frame/id frame-id :resource-key resource-key
                      :work-id work-id :generation generation
                      :status-before (:status entry) :status-after (:status entry')})
        {:rf.db/runtime (assoc-in runtime-db (state/entry-path resource-key) entry')}))))

(defn aborted-handler
  "`:rf.resource.internal/aborted` — a transport read was aborted. The work
  row reconciliation + host-handle teardown is the work-ledger substrate
  slice (rf2-afpdkn); for the cache entry this is a stale reply — the
  verification gate suppresses it (the entry settles to its last stable
  status through its own subsequent transitions, never left stranded). This
  slice no-ops the entry (no durable write) and records the abort.

  SKELETON: full work-ledger reconciliation lands in rf2-afpdkn."
  [{rt :rf.db/runtime, frame-id :rf.frame/id}
   [_event-id {:keys [resource-key work-id generation]}]]
  (trace/emit! :rf.event :rf.resource/work-abort-requested
               {:rf.frame/id frame-id :resource-key resource-key
                :work-id work-id :generation generation})
  {:rf.db/runtime (or rt {})})

(defn gc-fired-handler
  "`:rf.resource.internal/gc-fired` — an inactive-GC timer fired. Re-check
  owner sets + entry generation after wake (timers are advisory); remove
  the entry only if still GC-eligible. Per Spec 016 §Stale and GC
  scheduling.

  SKELETON: GC timer scheduling lands in the stale/GC slice; the handler
  here performs the advisory re-check + removal of a still-inactive entry."
  [{rt :rf.db/runtime, frame-id :rf.frame/id}
   [_event-id {:keys [resource-key]}]]
  (let [runtime-db (or rt {})
        entry      (get-in runtime-db (state/entry-path resource-key))]
    (if (and entry (empty? (:active-owners entry)) (nil? (:current-work entry)))
      (let [rdb' (-> runtime-db
                     (update-in (state/entries-path) dissoc resource-key)
                     (update state/resources-key state/recompute-indexes))]
        (trace/emit! :rf.event :rf.resource/gc-fired
                     {:rf.frame/id frame-id :resource-key resource-key})
        {:rf.db/runtime rdb'})
      (do (trace/emit! :rf.event :rf.resource/gc-skipped
                       {:rf.frame/id frame-id :resource-key resource-key
                        :reason (cond (nil? entry) :no-entry
                                      (seq (:active-owners entry)) :has-owner
                                      :else :in-flight)})
          {:rf.db/runtime runtime-db}))))

(defn stale-suppressed-handler
  "`:rf.resource.internal/stale-suppressed` — a late reply carrying a
  superseded work-id / generation was suppressed (it MUST NEVER mutate a
  newer entry). This is an internal NOTIFICATION the reply handlers already
  enforce inline (`live-entry-for-reply`); the standalone handler records
  the suppression in trace for tools. Per Spec 016 §Cancellation is
  opportunistic; stale suppression is mandatory."
  [{rt :rf.db/runtime, frame-id :rf.frame/id}
   [_event-id {:keys [resource-key work-id generation]}]]
  (trace/emit! :rf.event :rf.resource/stale-suppressed
               {:rf.frame/id frame-id :resource-key resource-key
                :work-id work-id :generation generation})
  {:rf.db/runtime (or rt {})})
