(ns re-frame.resources.events
  "The resource event handlers: the causal write surface over the resource
  cache. The public events take map payloads. The `:rf.resource.internal/*`
  replies carry only the verification payload (`:work/id`, `:generation`,
  `:rf.frame/id`) and resolve the scoped key through the receiving frame's
  work-ledger row; user code MUST NOT dispatch them.

  Stale suppression by work-id + generation, enforced on the entry, is the
  correctness boundary; transport abort is opportunistic. Handlers write
  `:rf.db/runtime` under framework-write authority, registered by the
  `re-frame.resources` façade.
  Spec 016 §Events (map payloads, not positional argument vectors)"
  (:require [clojure.set :as set]
            [re-frame.error :as rf.error]
            [re-frame.reply :as rf.reply]
            [re-frame.resources.classification :as rf.resources.classification]
            [re-frame.resources.registry :as rf.resources.registry]
            [re-frame.resources.reply :as rf.resources.reply]
            [re-frame.resources.reply-handlers :as rf.resources.reply-handlers]
            [re-frame.resources.route :as rf.resources.route]
            [re-frame.resources.scope-registry :as rf.resources.scope-registry]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.transport :as rf.resources.transport]
            [re-frame.resources.transport.http :as rf.resources.transport.http]
            [re-frame.resources.work-ledger :as rf.resources.work-ledger]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- per-instance classification lowering ----------------------------------

(defn with-classification-lowering
  "Wraps handler `f` so the `:rf.db/runtime` of its returned effects is
  reconciled through `rf.resources.classification/reconcile-registry` before
  commit, keeping the frame's elision registry in step with `:entries`. An
  effects map without `:rf.db/runtime` passes through unchanged. The
  reconciliation is idempotent, so every entry-mutating handler is wrapped."
  [f]
  (fn [& args]
    (let [effects (apply f args)]
      (if (and (map? effects) (contains? effects :rf.db/runtime))
        (update effects :rf.db/runtime rf.resources.classification/reconcile-registry rf.resources.registry/resource-meta)
        effects))))

;; Handlers here take "now" from the triggering token's `:rf/time-ms`
;; (declared via `:rf.cofx/requires`, read flat from the coeffects), never
;; from the host clock: a freshness decision made on a live clock would let a
;; replay take a different branch. EP-0010 §The World-Input Rule

;; ---- infinite-feed page context and reply addressing -----------------------
;; An infinite feed reuses the whole load path and differs in two places: a
;; first load seeds `empty-infinite-entry`, and the request carries the
;; reserved page ctx with its reply addressed to the page handlers, so a page
;; success appends rather than overwrites.

(def page-reserved-ctx-param-key
  "The reserved `:request` ctx key carrying this page's page-param.
  Spec 016 §Registration — `:infinite`"
  :rf.resource/page-param)

(def page-reserved-ctx-index-key
  "The reserved `:request` ctx key carrying this page's 0-based index.
  Spec 016 §Registration — `:infinite`"
  :rf.resource/page-index)

(defn page-request-ctx
  "The reserved `:request` ctx for an infinite-feed page fetch. A
  non-infinite resource lowers with a nil ctx.
  Spec 016 §Registration — `:infinite`"
  [page-param page-index]
  {page-reserved-ctx-param-key page-param
   page-reserved-ctx-index-key page-index})

(def page-succeeded-reply
  "The internal page-success reply id. Unlike
  `:rf.resource.internal/succeeded` it appends the page rather than replacing
  `:data`. Spec 016 §Causal event — `:rf.resource/load-more` (R2)"
  :rf.resource.internal/page-succeeded)

(def page-failed-reply
  "The internal page-failure reply id. A load-more failure is the third
  error channel (`:page-error`), which keeps the feed.
  Spec 016 §Causal event — `:rf.resource/load-more` (R2)"
  :rf.resource.internal/page-failed)

(defn- infinite-page-reply-payload
  "The verification payload for a page fetch: the scalar payload's
  `:work/id` and `:generation` plus the page param and index, so the settle
  records the param it fetched rather than re-deriving it."
  [generation work-id page-param page-index]
  {:work/id                  work-id
   :generation               generation
   page-reserved-ctx-param-key page-param
   page-reserved-ctx-index-key page-index})

;; ---- read completion continuations (call-site :reply-to) -------------------
;; A read joins in flight, so `:reply-to` targets accumulate on the shared work
;; record's `:reply-targets` and the one accepted terminal reply fans out to
;; each exactly once; a stale reply delivers nothing. A fresh-skip cache hit
;; has no record and dispatches immediately.
;; Spec 016 §Read completion continuations — call-site `:reply-to`

(defn- read-continuation-reply
  "The canonical `reply` plus the top-level read facts a continuation reads
  directly: `:resource`, `:params`, `:scope`, `:resource/key`, `:cache-hit?`.
  Pure."
  [reply {:keys [resource-key cache-hit?]}]
  (let [[scope resource-id params] resource-key]
    (assoc reply
           :resource     resource-id
           :params       params
           :scope        scope
           :resource/key resource-key
           :cache-hit?   (boolean cache-hit?))))

(defn- read-reply-continuation-fxs
  "The `[:dispatch …]` fxs delivering an accepted terminal `reply` to each
  `:reply-to` target on the settling work `record`, one per target, or nil
  when it has none. Pure."
  [record reply resource-key]
  (when-let [targets (seq (:reply-targets record))]
    (let [enriched (read-continuation-reply reply {:resource-key resource-key
                                                   :cache-hit? false})]
      (into [] (keep (fn [t] (when-let [ev (rf.reply/complete t enriched)]
                               [:dispatch ev])))
            targets))))

(defn- infinite-reply-value
  "The `:reply-to` `:value` for an infinite feed: the merged item list over
  `entry`'s pages, so a continuation sees one shape whether the read settled
  from cache or from a fetch. `where` names the surface in the
  `:rf.error/infinite-missing-page-accessor` error. Pure."
  [entry where]
  (let [spec (rf.resources.registry/resource-meta (:resource/id entry))]
    (rf.resources.state/merge-pages->items
      (:data entry)
      (rf.resources.state/resolve-page->items (:page->items spec))
      (:resource/id entry)
      where)))

(def ^:private reply-to-where
  "The `where` both continuation value sites pass, so a misconfigured feed
  raises the same error from cache or from a fetch."
  'rf.resource/reply-to)

(defn- emit-resource-replied!
  "Emits `:rf.resource/replied` for an accepted read reply that continued
  into `targets`; nothing when `targets` is empty. Names the attempt by
  `:work/id` and `:resource/id`, never by scoped key. Returns nil."
  [frame-id work-id status targets cache-hit?]
  (when (seq targets)
    (rf.trace/emit! :rf.event :rf.resource/replied
                 {:rf.frame/id frame-id
                  :resource/id (rf.resources.work-ledger/work-id-resource-id work-id)
                  :work/id work-id :status status
                  :targets (vec targets) :cache-hit? (boolean cache-hit?)})))

(def ^:private last-gc-window-path
  "Runtime-db path to the id of the last GC window the frame opened."
  [rf.resources.state/resources-key :last-gc-window])

(defn open-gc-window
  "`runtime-db` with `resource-key`'s entry moved to a new GC window when
  `gc-delay-ms` arms a GC timer on a client frame. The timer stamps its
  re-check with that window, so `gc-fired-handler` can tell a superseded
  check from the current one.

  Window ids come from one counter per frame, not per entry: an entry removed
  and rewritten at the same key would otherwise reopen ids its predecessor's
  queued check still carries."
  [runtime-db frame-id resource-key gc-delay-ms]
  (if (and gc-delay-ms (not (rf.resources.state/server-frame? frame-id)))
    (let [window (inc (get-in runtime-db last-gc-window-path 0))]
      (-> runtime-db
          (assoc-in last-gc-window-path window)
          (assoc-in (conj (rf.resources.state/entry-path resource-key) :gc-window) window)))
    runtime-db))

;; ---- ensure / refetch — the load-causing events ---------------------------

(declare revalidation-cause?)

(defn- ensure-load
  "Shared core of ensure and refetch. Resolves scope and canonical params to
  the scoped key, then takes one branch:
    - a revalidation refetch whose entry has gone: no-op;
    - fresh-skip (ensure only): a settled, fresh `:loaded` entry serves its
      cached value and attaches the owner, with no fetch;
    - dedupe (ensure only): join live in-flight work;
    - otherwise start a new attempt at the recorded generation allocation,
      superseding any in-flight attempt when `force-new?`.
  Returns the effects map. Spec 016 §Race and in-flight semantics"
  [{rt :rf.db/runtime, frame-id :rf.frame/id
    gen-allocation :rf.resource/generation-allocation
    time-ms :rf/time-ms, app-db :db}
   {:keys [resource owner cause keep-previous? reply-to] :as payload} {:keys [force-new? where]}]
  (let [runtime-db (or rt {})
        spec       (rf.resources.registry/require-resource-spec! resource where)
        ;; Validated at issue, before any write: the target rides the durable
        ;; work record, so a malformed or non-serializable one fails now
        ;; rather than at completion.
        reply-to'  (when (some? reply-to) (rf.reply/durable-target reply-to))
        scope      (rf.resources.registry/resolve-scope-for-event
                     resource spec {:payload-scope (:scope payload) :db app-db} where)
        cparams    (rf.resources.registry/validate+canonicalize-params
                     resource spec (rf.resources.state/params-present? payload) where)
        scoped-key (rf.resources.state/scoped-resource-key* scope resource cparams)
        infinite?  (rf.resources.registry/infinite-resource? spec)
        existing   (get-in runtime-db (rf.resources.state/entry-path scoped-key))
        entry      (or existing
                       (if infinite?
                         (rf.resources.state/empty-infinite-entry resource scoped-key)
                         (rf.resources.state/empty-entry resource scoped-key)))
        prior-work (:current-work entry)
        in-flight? (some? prior-work)
        ;; Only LIVE prior work is joinable. Release-owner leaves
        ;; `:current-work` pointing at an `:abort-requested` record, so the
        ;; pointer alone does not prove there is work to join.
        joinable?  (rf.resources.work-ledger/live-work? runtime-db prior-work)
        ;; The freshness basis is the token's `:rf/time-ms`, never the host
        ;; clock, so a replay takes the same serve-vs-fetch branch.
        fresh-skip? (and (not force-new?)
                         (not in-flight?)
                         (= :loaded (:status entry))
                         (not (rf.resources.state/entry-stale? entry time-ms)))
        owner-newly-attached? (and (some? owner)
                                   (not (contains? (:active-owners entry) owner)))
        transport-id rf.resources.transport/managed-http-transport
        prev-key   (when (and keep-previous? (not (rf.resources.state/has-data? entry)))
                     (rf.resources.state/prior-loaded-sibling-key
                       (get-in runtime-db (rf.resources.state/entries-path)) scoped-key))]
    (cond
      ;; A revalidation refetch whose entry left the cache refreshes nothing:
      ;; loading it would re-create a key nobody holds, and after an identity
      ;; switch fetch the new principal's bytes under the old scope.
      ;; Spec 016 §Changing principal
      (and force-new? (nil? existing) (nil? owner) (revalidation-cause? cause))
      (do (rf.trace/emit! :rf.event :rf.resource/refetch-decision
                          {:rf.frame/id frame-id :resource/key scoped-key
                           :scope scope :active? false :decision :no-entry :cause cause})
          {})
      ;; fresh-skip
      fresh-skip?
      ;; `attach-owner` bumps `:revision` on a new owner, so an optimistic
      ;; rollback cannot drop it.
      (let [hit  (rf.resources.state/attach-owner entry owner)
            rdb' (-> runtime-db
                     (assoc-in (rf.resources.state/entry-path scoped-key) hit)
                     (cond->
                       owner (update-in (rf.resources.state/owner-index-path)
                                        update owner (fnil conj #{}) (rf.resources.state/key-id scoped-key)))
                     ;; a fresh blocking route resource resolves now: no reply
                     ;; will ever land to do it
                     (rf.resources.route/reconcile-readiness))
            ;; Reviving an owner-free entry re-arms its timers, because a
            ;; settle while owner-free armed no poll. The stale timer arms
            ;; for the time left until `:stale-at`.
            was-owner-free? (empty? (:active-owners entry))
            arm-timers?    (and owner-newly-attached? was-owner-free?)
            spec           (rf.resources.registry/resource-meta (:resource/id entry))
            poll-delay-ms  (when (and arm-timers? (seq (:active-owners hit)))
                             (rf.resources.state/positive-or-nil (:poll-interval-ms spec)))
            stale-delay-ms (when arm-timers?
                             (rf.resources.state/stale-wake-delay-ms hit time-ms))
            gc-delay-ms    (when arm-timers?
                             (rf.resources.state/positive-or-nil (:gc-after-ms spec)))
            ;; No work record, so `:reply-to` dispatches now, with a work id
            ;; derived from the entry's generation. An infinite feed delivers
            ;; its merged items, the same shape a fetched page-0 delivers.
            hit-reply  (when reply-to'
                         (read-continuation-reply
                           (rf.resources.reply/success-reply
                             {:work/id      (rf.resources.work-ledger/resource-work-id
                                              resource (:generation entry))
                              :resource/key scoped-key
                              :scope        scope
                              :generation   (:generation entry)
                              :rf.frame/id  frame-id}
                             (if (rf.resources.state/infinite-entry? entry)
                               (infinite-reply-value entry reply-to-where)
                               (:data entry))
                             {:work-kind    rf.resources.reply/work-kind-resource
                              :completed-at time-ms})
                           {:resource-key scoped-key :cache-hit? true}))
            hit-cont-fx (when hit-reply
                          (when-let [ev (rf.reply/complete reply-to' hit-reply)]
                            [:dispatch ev]))]
        (rf.trace/emit! :rf.event :rf.resource/cache-hit
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :generation (:generation entry) :owner owner :cause cause})
        (when owner-newly-attached?
          (rf.trace/emit! :rf.event :rf.resource/owner-attached
                       {:rf.frame/id frame-id :resource/key scoped-key
                        :generation (:generation entry) :owner owner :cause cause
                        :work/id nil :joined-in-flight? false}))
        (when hit-cont-fx
          (emit-resource-replied!
            frame-id (:rf.reply/work-id hit-reply) :ok [reply-to'] true))
        (let [timers-fx (when (or stale-delay-ms gc-delay-ms poll-delay-ms)
                          [[:rf.resource/schedule-timers
                            {:frame-id     frame-id
                             :resource/key scoped-key
                             ;; naming all three kinds cancels any whose policy
                             ;; is absent
                             :timers       {:stale stale-delay-ms
                                            :gc    gc-delay-ms
                                            :poll  poll-delay-ms}
                             :server?      (rf.resources.state/server-frame? frame-id)}]])
              fx        (cond-> (vec timers-fx)
                          hit-cont-fx (conj hit-cont-fx))]
          (cond-> {:rf.db/runtime (open-gc-window rdb' frame-id scoped-key gc-delay-ms)}
            (seq fx) (assoc :fx fx))))
      ;; dedupe: join the live in-flight attempt, no new generation
      (and joinable? (not force-new?))
      (let [joined (rf.resources.state/attach-owner entry owner)
            rdb'   (-> runtime-db
                       (assoc-in (rf.resources.state/entry-path scoped-key) joined)
                       (rf.resources.work-ledger/update-record
                         prior-work rf.resources.work-ledger/join-owner+cause owner cause)
                       (cond->
                         reply-to' (rf.resources.work-ledger/update-record
                                     prior-work rf.resources.work-ledger/add-reply-target reply-to'))
                       (cond->
                         owner (update-in (rf.resources.state/owner-index-path)
                                          update owner (fnil conj #{}) (rf.resources.state/key-id scoped-key))))]
        (rf.trace/emit! :rf.event :rf.resource/deduped
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :generation (:generation entry) :owner owner :cause cause
                      :work/id prior-work})
        (when owner-newly-attached?
          (rf.trace/emit! :rf.event :rf.resource/owner-attached
                       {:rf.frame/id frame-id :resource/key scoped-key
                        :generation (:generation entry) :owner owner :cause cause
                        :work/id prior-work :joined-in-flight? true}))
        {:rf.db/runtime rdb'})
      ;; a new load attempt
      :else
      ;; The generation is the recorded allocation, not a re-mint, so replay
      ;; reproduces it and the `:work/id` derived from it.
      (let [generation (rf.resources.state/allocated-generation gen-allocation)
            work-id    (rf.resources.work-ledger/resource-work-id resource generation)
            ;; frame-qualified: two frames can hold the same work-id
            request-id (rf.resources.work-ledger/managed-request-id frame-id work-id)
            ;; from the token, so replay mints the same `:started-at` and
            ;; `:deadline-at`
            started-at time-ms
            deadline   (when-let [ms (:timeout-ms spec)] (+ started-at ms))
            ;; A refetch, or an ensure of an invalidated feed, refreshes page 0
            ;; in place; a multi-page `:refetch` policy also arms a sweep cursor
            ;; that `page-succeeded-handler` drives one leg at a time.
            ;; Spec 016 §Refetch and invalidation of an infinite feed
            refetch-policy (when infinite? (:refetch spec))
            entry'     (cond-> (rf.resources.state/entry-start-load
                                 entry {:generation generation :work-id work-id
                                        :request-id request-id :owner owner})
                         prev-key (assoc :previous-key prev-key)
                         (and infinite? (or force-new? (some? (:invalidated-at entry))))
                         (rf.resources.state/entry-begin-refetch-sweep refetch-policy))
            page-param (when infinite? (rf.resources.state/page-param-for-spec spec))
            page-index 0
            req-ctx    (when infinite? (page-request-ctx page-param page-index))
            superseding? (and in-flight? force-new?)
            ;; A superseded attempt hands its `:reply-to` targets to its
            ;; successor: left on the suppressed row, no delivery site could
            ;; reach them. Read before the row settles below.
            superseded-targets (when superseding?
                                 (:reply-targets
                                   (rf.resources.work-ledger/get-record runtime-db prior-work)))
            record     (rf.resources.work-ledger/work-record
                         {:work-id      work-id
                          :frame-id     frame-id
                          :resource/key scoped-key
                          :generation   generation
                          :transport    transport-id
                          ;; every holder needs the attempt, so one owner
                          ;; releasing cannot abort an ownerless refetch
                          :owners       (:active-owners entry')
                          :cause        cause
                          :started-at   started-at
                          :deadline-at  deadline
                          ;; 0, not nil: not a load-more, so `:fetching-next?`
                          ;; reads false
                          :page-index   (when infinite? page-index)
                          ;; the predecessor's targets first, for append order
                          :reply-targets superseded-targets})
            ;; through `add-reply-target`, so a target the superseded read
            ;; already carried is not delivered twice
            record     (cond-> record
                         reply-to' (rf.resources.work-ledger/add-reply-target reply-to'))
            rdb'       (-> runtime-db
                           (assoc-in (rf.resources.state/entry-path scoped-key) entry')
                           (cond->
                             superseding?
                             ;; hand over rather than copy: a terminal row must
                             ;; not advertise targets it can never deliver
                             (-> (rf.resources.work-ledger/update-record
                                   prior-work dissoc :reply-targets)
                                 (rf.resources.work-ledger/settle-terminal
                                   prior-work
                                   :suppressed {:reason :superseded :by work-id})))
                           (rf.resources.work-ledger/put-record work-id record)
                           (cond->
                             owner (update-in (rf.resources.state/owner-index-path)
                                              update owner (fnil conj #{}) (rf.resources.state/key-id scoped-key))))
            http-args  (let [req-fn (:request spec)]
                         (req-fn cparams req-ctx))
            reply-overrides (when infinite?
                              {:on-success-id page-succeeded-reply
                               :on-failure-id page-failed-reply
                               :reply-payload (infinite-page-reply-payload
                                                generation work-id
                                                page-param page-index)})
            lower-fx   (rf.resources.transport.http/lower
                         (merge
                           {:http-args    http-args
                            :request-id   request-id
                            :work-id      work-id
                            :resource/key scoped-key
                            :frame-id     frame-id
                            :generation   generation
                            :where        where}
                           reply-overrides))
            superseding-abort-fx (when superseding?
                                   (rf.resources.work-ledger/abort-fx transport-id frame-id prior-work))]
        (rf.trace/emit! :rf.event :rf.resource/work-started
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :generation generation :work/id work-id
                      :status :running :owner owner :cause cause
                      :superseded (when superseding? prior-work)})
        (rf.trace/emit! :rf.event :rf.resource/fetch-started
                     {:rf.frame/id frame-id :resource/id resource
                      :generation generation :work/id work-id
                      :status (:status entry') :owner owner :cause cause})
        (when owner-newly-attached?
          (rf.trace/emit! :rf.event :rf.resource/owner-attached
                       {:rf.frame/id frame-id :resource/key scoped-key
                        :generation generation :owner owner :cause cause
                        :work/id work-id :joined-in-flight? false}))
        {:rf.db/runtime rdb'
         :fx (cond-> [[:rf.resource/commit-generation {:value generation}]
                      [:rf.resource/record-work-handle
                       {:frame-id frame-id :work-id work-id
                        :transport transport-id :request-id request-id}]
                      lower-fx]
               superseding?
               (-> (conj [:rf.resource/clear-work-handle
                          {:frame-id frame-id :work-id prior-work}])
                   (cond-> superseding-abort-fx (conj superseding-abort-fx))))}))))

(defn ensure-handler
  "`:rf.resource/ensure`: load the instance if absent, join in-flight work,
  attach `:owner`, record `:cause`. Payload `{:resource :scope :params :owner
  :cause :keep-previous? :reply-to}`.
  Spec 016 §Events (map payloads, not positional argument vectors)"
  [cofx [_event-id payload]]
  (ensure-load cofx payload {:force-new? false :where 'rf.resource/ensure}))

(defn refetch-handler
  "`:rf.resource/refetch`: force a new generation, superseding any in-flight
  attempt. Same payload as ensure. Spec 016 §Race and in-flight semantics"
  [cofx [_event-id payload]]
  (ensure-load cofx payload {:force-new? true :where 'rf.resource/refetch}))

;; ---- load-more — extend an infinite feed by one page -----------------------

(defn- load-more-loaded
  "Core of `:rf.resource/load-more`: fetches the next page (param derived
  from the tail, at index `page-count`), or skips with `:no-feed`,
  `:no-next-page` or `:in-flight`. Returns the effects map."
  [{rt :rf.db/runtime, frame-id :rf.frame/id
    gen-allocation :rf.resource/generation-allocation
    time-ms :rf/time-ms, app-db :db}
   {:keys [resource cause] supplied-owner :owner :as payload} {:keys [where]}]
  (let [runtime-db (or rt {})
        spec       (rf.resources.registry/require-resource-spec! resource where)
        scope      (rf.resources.registry/resolve-scope-for-event
                     resource spec {:payload-scope (:scope payload) :db app-db} where)
        cparams    (rf.resources.registry/validate+canonicalize-params
                     resource spec (rf.resources.state/params-present? payload) where)
        scoped-key (rf.resources.state/scoped-resource-key* scope resource cparams)
        ;; A load-more is ownerless: the feed's liveness is the route owner's.
        ;; A supplied owner would leak a durable owner onto the feed, so it is
        ;; dropped and warned about below; the page still loads.
        ;; Conventions §No silent swallow — recognised input MUST signal
        owner      nil
        entry      (get-in runtime-db (rf.resources.state/entry-path scoped-key))
        prior-work (:current-work entry)
        in-flight? (rf.resources.work-ledger/live-work? runtime-db prior-work)
        pages      (:data entry)
        next-param (rf.resources.state/next-param-for (:next-page-param spec) pages)
        terminal?  (rf.resources.state/terminal? next-param)
        page-index (rf.resources.state/page-count entry)]
    (when (some? supplied-owner)
      (rf.trace/emit! :warning :rf.warning/resource-load-more-owner-ignored
                   {:rf.frame/id  frame-id
                    :resource     resource
                    :resource/key scoped-key
                    :owner        supplied-owner
                    :cause        cause
                    :recovery     :remove-owner
                    :hint         (str ":rf.resource/load-more was given :owner "
                                       (pr-str supplied-owner)
                                       ", but a load-more is OWNERLESS — the feed's "
                                       "liveness is the route owner's (the route that "
                                       "ensured page 0). The owner is IGNORED (it is "
                                       "NOT attached to :active-owners / :owner-index, "
                                       "so no durable owner leaks) and the page still "
                                       "fetches + appends. Remove :owner from the "
                                       "load-more payload. Per Spec 016 §Causal event "
                                       "— load-more.")}))
    (cond
      ;; no feed yet: the first page is ensure's page 0, never a load-more
      (or (nil? entry) (not (rf.resources.state/infinite-entry? entry)) (empty? pages))
      (do
        (rf.trace/emit! :rf.event :rf.resource/load-more-skipped
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :reason :no-feed :owner owner :cause cause})
        {:rf.db/runtime runtime-db})

      terminal?
      (do
        (rf.trace/emit! :rf.event :rf.resource/load-more-skipped
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :reason :no-next-page :page-count page-index
                      :owner owner :cause cause})
        {:rf.db/runtime runtime-db})

      ;; join the live page fetch, attributing it to this cause too
      in-flight?
      (let [rdb' (rf.resources.work-ledger/update-record
                   runtime-db prior-work rf.resources.work-ledger/join-owner+cause owner cause)]
        (rf.trace/emit! :rf.event :rf.resource/load-more-skipped
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :reason :in-flight :work/id prior-work
                      :page-count page-index :owner owner :cause cause})
        {:rf.db/runtime rdb'})

      :else
      (let [generation (rf.resources.state/allocated-generation gen-allocation)
            work-id    (rf.resources.work-ledger/resource-work-id resource generation)
            request-id (rf.resources.work-ledger/managed-request-id frame-id work-id)
            started-at time-ms
            deadline   (when-let [ms (:timeout-ms spec)] (+ started-at ms))
            transport-id rf.resources.transport/managed-http-transport
            entry'     (rf.resources.state/entry-start-load
                         entry {:generation generation :work-id work-id
                                :request-id request-id :owner owner})
            record     (rf.resources.work-ledger/work-record
                         {:work-id      work-id
                          :frame-id     frame-id
                          :resource/key scoped-key
                          :generation   generation
                          :transport    transport-id
                          ;; mints no owner, but the feed's holders need the page
                          :owners       (:active-owners entry')
                          :cause        cause
                          :started-at   started-at
                          :deadline-at  deadline
                          ;; a positive index is what tells `:fetching-next?`
                          ;; apart from a whole-feed refresh
                          :page-index   page-index})
            owner-newly-attached? (and (some? owner)
                                       (not (contains? (:active-owners entry) owner)))
            rdb'       (-> runtime-db
                           (assoc-in (rf.resources.state/entry-path scoped-key) entry')
                           (rf.resources.work-ledger/put-record work-id record)
                           (cond->
                             owner (update-in (rf.resources.state/owner-index-path)
                                              update owner (fnil conj #{}) (rf.resources.state/key-id scoped-key))))
            req-ctx    (page-request-ctx next-param page-index)
            http-args  (let [req-fn (:request spec)]
                         (req-fn cparams req-ctx))
            lower-fx   (rf.resources.transport.http/lower
                         {:http-args    http-args
                          :request-id   request-id
                          :work-id      work-id
                          :resource/key scoped-key
                          :frame-id     frame-id
                          :generation   generation
                          :where        where
                          :on-success-id page-succeeded-reply
                          :on-failure-id page-failed-reply
                          :reply-payload (infinite-page-reply-payload
                                           generation work-id
                                           next-param page-index)})]
        (rf.trace/emit! :rf.event :rf.resource/load-more
                     {:rf.frame/id frame-id :resource/id resource
                      :generation generation :work/id work-id
                      :page-param next-param :page-index page-index
                      :page-count page-index :owner owner :cause cause})
        (rf.trace/emit! :rf.event :rf.resource/work-started
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :generation generation :work/id work-id
                      :status :running :owner owner :cause cause})
        (rf.trace/emit! :rf.event :rf.resource/fetch-started
                     {:rf.frame/id frame-id :resource/id resource
                      :generation generation :work/id work-id
                      :status (:status entry') :owner owner :cause cause})
        (when owner-newly-attached?
          (rf.trace/emit! :rf.event :rf.resource/owner-attached
                       {:rf.frame/id frame-id :resource/key scoped-key
                        :generation generation :owner owner :cause cause
                        :work/id work-id :joined-in-flight? false}))
        {:rf.db/runtime rdb'
         :fx [[:rf.resource/commit-generation {:value generation}]
              [:rf.resource/record-work-handle
               {:frame-id frame-id :work-id work-id
                :transport transport-id :request-id request-id}]
              lower-fx]}))))

(defn load-more-handler
  "`:rf.resource/load-more`: extend an infinite feed by one page, appended on
  success; a failure records `:page-error` and keeps the feed. A terminal
  feed, a page fetch already in flight, or no page 0 yet is a no-op.
  Ownerless: a supplied `:owner` is ignored with
  `:rf.warning/resource-load-more-owner-ignored`. Payload `{:resource :scope
  :params :cause}`. Spec 016 §Causal event — `:rf.resource/load-more` (R2)"
  [cofx [_event-id payload]]
  (load-more-loaded cofx payload {:where 'rf.resource/load-more}))

;; ---- refetch-page — one leg of a multi-page refetch sweep -------------------

(defn- refetch-page-loaded
  "Core of `:rf.resource.internal/refetch-page`: re-fetches the page at the
  payload's page param and index, which replaces that page in place on
  success. A vanished feed, or a page index past the feed's end, is a no-op."
  [{rt :rf.db/runtime, frame-id :rf.frame/id
    gen-allocation :rf.resource/generation-allocation
    time-ms :rf/time-ms, app-db :db}
   {:keys [resource owner cause] page-param :rf.resource/page-param
    page-index :rf.resource/page-index :as payload} {:keys [where]}]
  (let [runtime-db (or rt {})
        spec       (rf.resources.registry/require-resource-spec! resource where)
        scope      (rf.resources.registry/resolve-scope-for-event
                     resource spec {:payload-scope (:scope payload) :db app-db} where)
        cparams    (rf.resources.registry/validate+canonicalize-params
                     resource spec (rf.resources.state/params-present? payload) where)
        scoped-key (rf.resources.state/scoped-resource-key* scope resource cparams)
        entry      (get-in runtime-db (rf.resources.state/entry-path scoped-key))]
    (cond
      (or (nil? entry) (not (rf.resources.state/infinite-entry? entry)))
      (do
        (rf.trace/emit! :rf.event :rf.resource/load-more-skipped
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :reason :no-feed :owner owner :cause cause})
        {:rf.db/runtime runtime-db})

      ;; a feed that shrank under the sweep drops the stale leg
      (>= page-index (rf.resources.state/page-count entry))
      (do
        (rf.trace/emit! :rf.event :rf.resource/load-more-skipped
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :reason :no-next-page :page-count (rf.resources.state/page-count entry)
                      :owner owner :cause cause})
        {:rf.db/runtime runtime-db})

      :else
      (let [generation (rf.resources.state/allocated-generation gen-allocation)
            work-id    (rf.resources.work-ledger/resource-work-id resource generation)
            request-id (rf.resources.work-ledger/managed-request-id frame-id work-id)
            started-at time-ms
            deadline   (when-let [ms (:timeout-ms spec)] (+ started-at ms))
            transport-id rf.resources.transport/managed-http-transport
            entry'     (rf.resources.state/entry-start-load
                         entry {:generation generation :work-id work-id
                                :request-id request-id :owner owner})
            record     (rf.resources.work-ledger/work-record
                         {:work-id      work-id
                          :frame-id     frame-id
                          :resource/key scoped-key
                          :generation   generation
                          :transport    transport-id
                          :owners       (:active-owners entry')
                          :cause        cause
                          :started-at   started-at
                          :deadline-at  deadline
                          :page-index   page-index})
            rdb'       (-> runtime-db
                           (assoc-in (rf.resources.state/entry-path scoped-key) entry')
                           (rf.resources.work-ledger/put-record work-id record)
                           (cond->
                             owner (update-in (rf.resources.state/owner-index-path)
                                              update owner (fnil conj #{}) (rf.resources.state/key-id scoped-key))))
            req-ctx    (page-request-ctx page-param page-index)
            http-args  (let [req-fn (:request spec)]
                         (req-fn cparams req-ctx))
            lower-fx   (rf.resources.transport.http/lower
                         {:http-args    http-args
                          :request-id   request-id
                          :work-id      work-id
                          :resource/key scoped-key
                          :frame-id     frame-id
                          :generation   generation
                          :where        where
                          :on-success-id page-succeeded-reply
                          :on-failure-id page-failed-reply
                          :reply-payload (infinite-page-reply-payload
                                           generation work-id
                                           page-param page-index)})]
        (rf.trace/emit! :rf.event :rf.resource/work-started
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :generation generation :work/id work-id
                      :status :running :owner owner :cause cause})
        (rf.trace/emit! :rf.event :rf.resource/fetch-started
                     {:rf.frame/id frame-id :resource/id resource
                      :generation generation :work/id work-id
                      :status (:status entry') :owner owner :cause cause})
        {:rf.db/runtime rdb'
         :fx [[:rf.resource/commit-generation {:value generation}]
              [:rf.resource/record-work-handle
               {:frame-id frame-id :work-id work-id
                :transport transport-id :request-id request-id}]
              lower-fx]}))))

(defn refetch-page-handler
  "`:rf.resource.internal/refetch-page`: one leg of a multi-page refetch
  sweep, chained by `page-succeeded-handler`. Payload `{:resource :scope
  :params :cause :rf.resource/page-param :rf.resource/page-index}`.
  Spec 016 §Refetch and invalidation of an infinite feed"
  [cofx [_event-id payload]]
  (refetch-page-loaded cofx payload {:where 'rf.resource.internal/refetch-page}))

;; ---- focus / reconnect revalidation ---------------------------------------
;; Owners select which entries are worth refetching, durable timestamps decide
;; whether, and the refetch carries a cause, never an owner, so revalidation
;; never creates liveness. Spec 016 §Stale and GC scheduling

(def focus-cause
  "The cause on a window-focus refetch: a cause, never an owner.
  Spec 016 §Causes explain why work happened"
  :focus)

(def reconnect-cause
  "The cause on a network-reconnect refetch: a cause, never an owner.
  Spec 016 §Causes explain why work happened"
  :reconnect)

(def poll-cause
  "The cause on a poll-tick refetch: a cause, never an owner, so polling
  stops when the last real owner releases. Spec 016 §Polling"
  :poll)

(defn- revalidation-cause?
  "True for the causes of the framework's automatic refetches: focus,
  reconnect, a poll tick, or an invalidation (`[:invalidate …]`). Such a
  refetch never re-creates an entry that has left the cache (`ensure-load`)."
  [cause]
  (or (contains? #{focus-cause reconnect-cause poll-cause} cause)
      (and (vector? cause) (= :invalidate (first cause)))))

(defn- entry-revalidation-in-flight?
  "True when `entry` has live work in flight. A `:current-work` pointer alone
  is not proof: the linked record may be terminal or `:abort-requested`."
  [runtime-db entry]
  (rf.resources.work-ledger/live-work? runtime-db (:current-work entry)))

(defn- active-stale-scan
  "The `{:resource/key :scope :resource :params}` of every entry that has an
  active owner, is stale at `clock-ms`, and has no live work in flight. The
  in-flight exclusion coalesces a tab return's focus and visibilitychange
  signals into one refetch. Pure."
  [runtime-db clock-ms]
  (let [entries (get-in runtime-db (rf.resources.state/entries-path))]
    (into []
          (keep (fn [[_k-id entry]]
                  (when (and (seq (:active-owners entry))
                             (rf.resources.state/entry-stale? entry clock-ms)
                             (not (entry-revalidation-in-flight? runtime-db entry)))
                    ;; the map key is the opaque byte id; the vector lives on
                    ;; the entry
                    (let [scoped-key (:resource/key entry)
                          [scope resource-id params] scoped-key]
                      {:resource/key scoped-key
                       :scope        scope
                       :resource     resource-id
                       :params       params}))))
          entries)))

(defn- revalidate-handler
  "Shared core of the focus and reconnect events: dispatches a background
  `:rf.resource/refetch` with `cause` for every entry `active-stale-scan`
  selects, and emits one `:rf.resource/revalidate-scan` summary. Writes
  nothing itself. The selection reads the token's `:rf/time-ms`, so a replay
  selects the same set."
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms} signal cause]
  (let [runtime-db (or rt {})
        eligible   (active-stale-scan runtime-db time-ms)
        refetches  (mapv (fn [{:keys [resource scope params]}]
                           [:dispatch [:rf.resource/refetch
                                       {:resource resource :scope scope
                                        :params   params :cause cause}]])
                         eligible)]
    (rf.trace/emit! :rf.event :rf.resource/revalidate-scan
                 {:rf.frame/id frame-id :signal signal :cause cause
                  :scanned    (count (get-in runtime-db (rf.resources.state/entries-path)))
                  :refetched  (count eligible)
                  :keys       (mapv :resource/key eligible)})
    {:fx refetches}))

(defn window-focused-handler
  "`:rf.resource/window-focused`: revalidates active stale entries with cause
  `:focus`. Dispatched by the host listener, never by user code.
  Spec 016 §Stale and GC scheduling"
  [cofx [_event-id]]
  (revalidate-handler cofx :rf.resource/window-focused focus-cause))

(defn network-reconnected-handler
  "`:rf.resource/network-reconnected`: revalidates active stale entries with
  cause `:reconnect`. Dispatched by the host `online` listener, never by user
  code. Spec 016 §Stale and GC scheduling"
  [cofx [_event-id]]
  (revalidate-handler cofx :rf.resource/network-reconnected reconnect-cause))

;; ---- active-owner polling --------------------------------------------------

(defn poll-fired-handler
  "`:rf.resource.internal/poll-fired`: the timer is advisory, so this
  re-checks the live entry and decides:
    - `:no-entry` / `:no-owner`: stop, no re-arm (a poll never pins an
      owner-free entry);
    - `:paused-hidden`: hidden document, no refetch, re-arm;
    - `:coalesced`: live work in flight, no refetch, re-arm;
    - `:polled`: refetch with cause `:poll` whatever the staleness, re-arm.
  The re-arm names `:poll` alone, so the stale and GC timers stay armed. A
  failed tick is an ordinary refresh failure and the next tick still fires.
  Spec 016 §Polling"
  [{rt :rf.db/runtime, frame-id :rf.frame/id}
   [_event-id {resource-key :resource/key :keys [hidden?]}]]
  (let [runtime-db (or rt {})
        entry      (get-in runtime-db (rf.resources.state/entry-path resource-key))
        owned?     (seq (:active-owners entry))
        in-flight? (and entry (entry-revalidation-in-flight? runtime-db entry))
        interval   (rf.resources.state/positive-or-nil
                     (:poll-interval-ms (rf.resources.registry/resource-meta (:resource/id entry))))
        decision   (cond
                     (nil? entry)     :no-entry
                     (not owned?)     :no-owner
                     hidden?          :paused-hidden
                     in-flight?       :coalesced
                     :else            :polled)
        re-arm?    (and interval (contains? #{:paused-hidden :coalesced :polled} decision))
        refetch?   (= :polled decision)]
    (rf.trace/emit! :rf.event :rf.resource/poll-fired
                 {:rf.frame/id frame-id :resource/key resource-key
                  :decision decision :rearmed? (boolean re-arm?)})
    (cond-> {:rf.db/runtime runtime-db}
      (or refetch? re-arm?)
      (assoc :fx
        (cond-> []
          refetch?
          (conj [:dispatch [:rf.resource/refetch
                            {:resource (second resource-key)
                             :scope    (first resource-key)
                             :params   (nth resource-key 2)
                             :cause    poll-cause}]])
          re-arm?
          (conj [:rf.resource/schedule-timers
                 {:frame-id     frame-id
                  :resource/key resource-key
                  :timers       {:poll interval}
                  :server?      (rf.resources.state/server-frame? frame-id)}]))))))

;; ---- invalidate-tags — exact tag invalidation -----------------------------

(defn match-invalidation-keys
  "The pure match both the invalidation engine and the mutation settle use,
  so the keys a mutation reports as affected are exactly the keys
  invalidation marks. `entries` is keyed by byte `key-id`; `cscope` is nil
  iff `cross-scope?`; `exempt-ids` are the key-ids a same-mutation
  `:populates` kept fresh. Returns:
    - `:matched` / `:matched-ids`: in-scope, not-exempt entries whose tags
      intersect `tag-set` (scoped-key vectors / key-ids);
    - `:exempt-hit`: exempt keys that would have matched;
    - `:other-scope-hit?`: whether the tags match in another scope (scoped
      invalidation only);
    - `:in-flight-unmatched-ids`: in-scope, not-exempt, unmatched entries
      with work in flight. Their tags describe old data, so the reply may
      still produce a match.
  Spec 016 §Invalidation"
  [entries cscope cross-scope? tag-set exempt-ids]
  (let [tags-hit? (fn [entry] (seq (set/intersection (set (:tags entry)) tag-set)))
        sk-of     (fn [entry] (:resource/key entry))
        in-scope? (fn [entry] (or cross-scope? (= cscope (first (sk-of entry)))))
        matched   (into {}
                        (filter (fn [[k-id entry]] (and (not (contains? exempt-ids k-id))
                                                        (in-scope? entry) (tags-hit? entry))))
                        entries)
        in-flight-unmatched-ids
        (into #{}
              (comp (filter (fn [[k-id entry]] (and (not (contains? exempt-ids k-id))
                                                    (in-scope? entry)
                                                    (not (tags-hit? entry))
                                                    (some? (:current-work entry)))))
                    (map key))
              entries)
        exempt-hit (into []
                         (comp (filter (fn [[k-id entry]] (and (contains? exempt-ids k-id)
                                                               (in-scope? entry) (tags-hit? entry))))
                               (map (fn [[_k-id entry]] (sk-of entry))))
                         entries)
        other-scope-hit?
        (and (not cross-scope?)
             (boolean
               (some (fn [[_k-id entry]] (and (not= cscope (first (sk-of entry))) (tags-hit? entry)))
                     entries)))]
    {:matched     (mapv (fn [[_k-id entry]] (sk-of entry)) matched)
     :matched-ids (set (keys matched))
     :exempt-hit  exempt-hit
     :other-scope-hit? other-scope-hit?
     :in-flight-unmatched-ids in-flight-unmatched-ids}))

(defn- record-pending-invalidation
  "Records an invalidation of `tag-set` at `invalidated-at` on an in-flight
  work `record` whose entry the tags did not match; repeats union their tags
  and keep the latest time. A terminal record is left alone. The note lives
  on the attempt, so it dies with a failure, abort or supersession, and an
  optimistic restore on the entry cannot erase it."
  [record tag-set invalidated-at]
  (if (rf.resources.work-ledger/terminal? (:status record))
    record
    (-> record
        (update-in [:pending-invalidation :tags] (fnil into #{}) tag-set)
        (assoc-in [:pending-invalidation :invalidated-at] invalidated-at))))

(defn- settle-invalidations
  "Resolves the invalidations that landed during the attempt a success just
  settled (`entry` pre-settle, `settled` post-settle, `record` the work
  record). A mark written during the attempt and kept by the settle stands
  (read off the pre-settle entry, so an older mark a feed page settle keeps
  is not this attempt's to follow up). A recorded invalidation whose tags
  intersect the produced tags marks the entry stale at its recorded time; one
  that does not is discarded. Returns `[settled' follow-up-tags]`,
  `follow-up-tags` non-nil when the entry ended stale with active owners: the
  caller then dispatches one refetch. Spec 016 §Race and in-flight semantics"
  [entry settled record]
  (let [pending       (:pending-invalidation record)
        pending-hit?  (and pending
                           (seq (set/intersection (set (:tags settled))
                                                  (set (:tags pending)))))
        definite?     (some? (rf.resources.state/invalidation-kept-by-settle entry))
        settled'      (if (and pending-hit? (not definite?))
                        (rf.resources.state/entry-invalidate
                          settled (:invalidated-at pending))
                        settled)]
    [settled'
     (when (and (or definite? pending-hit?) (seq (:active-owners settled')))
       (if definite? (:tags entry) (:tags pending)))]))

(defn- follow-up-refetch-fx
  "The one follow-up `:rf.resource/refetch` for an owned entry a success
  left stale. Spec 016 §Race and in-flight semantics"
  [resource-key tags]
  (let [[s rid p] resource-key]
    [:dispatch [:rf.resource/refetch
                {:resource rid :scope s :params p
                 :cause [:invalidate {:tags tags}]}]]))

(defn invalidate-tags-handler
  "`:rf.resource/invalidate-tags`: marks the entries whose tags intersect
  `:tags` stale, refetches the ones with active owners, and leaves ownerless
  ones stale for GC. The payload is either scoped, `{:scope :tags :cause?}`
  with `:scope` a concrete scope or a `{:from-db …}` reference resolved
  against app-db, or a cross-scope sweep, `{:cross-scope? true :tags :cause}`.
  Throws when a sweep carries `:scope` or lacks `:cause`, when a scoped call
  lacks `:scope`, and when a reference resolves nil. `:exempt-keys` spares
  keys a same-mutation `:populates` just seeded. Emits one summary trace plus
  one decision per matched entry. Spec 016 §Invalidation"
  [{rt :rf.db/runtime, frame-id :rf.frame/id, app-db :db, time-ms :rf/time-ms}
   [_event-id {:keys [scope tags cause cross-scope? exempt-keys]}]]
  (let [runtime-db (or rt {})
        _          (when (and cross-scope? (some? scope))
                     (rf.error/throw-error!
                       :rf.error/resource-cross-scope-scope-conflict
                       'rf.resource/invalidate-tags
                       (str "a :cross-scope? true :rf.resource/invalidate-tags "
                            "MUST NOT carry a :scope — cross-scope is "
                            "scope-AGNOSTIC (it matches the tags in EVERY "
                            "scope), so a :scope alongside it is a "
                            "contradiction (which scope would win?). Drop the "
                            ":scope for a cross-scope sweep, or drop "
                            ":cross-scope? true to invalidate one resolved "
                            "scope. Per Spec 016 §The cross-scope lattice.")
                       {:recovery :fix-scope
                        :extra    {:tags tags :scope scope}}))
        _          (when (and (not cross-scope?) (nil? scope))
                     (rf.error/throw-error!
                       :rf.error/resource-invalidate-scope-required
                       'rf.resource/invalidate-tags
                       (str "a scoped :rf.resource/invalidate-tags MUST "
                            "supply an explicit :scope. A missing scope "
                            "is fail-closed (it would silently match "
                            "nothing — or the wrong nil-scope set — "
                            "rather than the intended scope). To "
                            "invalidate the tags in EVERY scope, opt in "
                            "explicitly with :cross-scope? true. Per Spec "
                            "016 §Invalidation.")
                       {:recovery :fix-scope
                        :extra    {:tags tags}}))
        _          (when (and cross-scope? (nil? cause))
                     (rf.error/throw-error!
                       :rf.error/resource-cross-scope-cause-required
                       'rf.resource/invalidate-tags
                       (str "a :cross-scope? true :rf.resource/invalidate-tags "
                            "MUST carry :cause evidence. Cross-scope is the "
                            "AUDITED escape — it can stale or refetch data "
                            "across every user, tenant, story frame, and SSR "
                            "request, so the runtime records WHY the cache "
                            "reached outside the mutation's own resolved "
                            "scope (a privacy-relevant trace, EP-0015). A "
                            "cross-scope invalidation with no :cause is "
                            "rejected — never a silent unaudited sweep. Per "
                            "Spec 016 §The cross-scope lattice.")
                       {:recovery :fix-cause
                        :extra    {:tags tags}}))
        from-db?   (rf.resources.scope-registry/from-db-reference? scope)
        cscope     (when (some? scope)
                     (rf.resources.scope-registry/resolve-scope-input
                       scope app-db 'rf.resource/invalidate-tags nil))
        _          (when (and from-db? (nil? cscope))
                     (rf.error/throw-error!
                       :rf.error/resource-scope-unresolved-reference
                       'rf.resource/invalidate-tags
                       (str "a :rf.resource/invalidate-tags {:from-db …} :scope "
                            "referenced named scope resolver "
                            (pr-str (:from-db scope)) ", but it resolved NIL "
                            "against the current db — FAIL-CLOSED. A derived "
                            "scope that cannot resolve is the unresolved "
                            "condition, never permission to invalidate global "
                            "or fall through to another tier. The resolver's "
                            "declared :inputs are not present in db (e.g. no "
                            "logged-in user). Per Spec 016 §Resolver "
                            "references.")
                       {:recovery :fix-scope
                        :extra    {:from-db (:from-db scope) :tags tags}}))
        ;; a lone vector tag is one tag, not a set of scalars
        tag-set    (rf.resources.state/normalize-tag-set tags)
        ;; from the token, so replay rewrites the same `:invalidated-at`
        invalidated-at time-ms
        entries    (get-in runtime-db (rf.resources.state/entries-path))
        ;; matched by byte identity, so list- and vector-params keys agree
        exempt     (into #{} (map rf.resources.state/key-id) exempt-keys)
        {matched-ids :matched-ids
         exempt-hit  :exempt-hit
         other-scope-hit? :other-scope-hit?
         in-flight-unmatched-ids :in-flight-unmatched-ids}
        (match-invalidation-keys entries cscope cross-scope? tag-set exempt)
        ;; `entry-invalidate` bumps `:revision`: a stale mark is a write an
        ;; optimistic rollback could clobber
        rdb'       (reduce
                     (fn [db k-id]
                       (update-in db (rf.resources.state/entry-path-by-id k-id)
                                  rf.resources.state/entry-invalidate invalidated-at))
                     runtime-db matched-ids)
        ;; An unmatched entry with a reply in flight may still be answered by
        ;; tags that reply produces, so the invalidation is recorded on the
        ;; attempt (`settle-invalidations`), not the entry.
        rdb'       (reduce
                     (fn [db k-id]
                       (rf.resources.work-ledger/update-record
                         db (:current-work (get entries k-id))
                         record-pending-invalidation tag-set invalidated-at))
                     rdb' in-flight-unmatched-ids)
        decisions  (mapv (fn [k-id]
                           (let [entry (get entries k-id)]
                             {:resource/key (:resource/key entry)
                              :active? (boolean (seq (:active-owners entry)))
                              :decision (if (seq (:active-owners entry))
                                          :refetch :left-stale)
                              :tags (vec (:tags entry))}))
                         matched-ids)
        refetches  (into []
                         (comp
                           (filter :active?)
                           (map (fn [{k :resource/key}]
                                  (let [[s rid p] k]
                                    [:dispatch [:rf.resource/refetch
                                                {:resource rid :scope s :params p
                                                 :cause [:invalidate {:tags tags}]}]]))))
                         decisions)]
    (rf.trace/emit! :rf.event :rf.resource/invalidated
                 {:rf.frame/id frame-id :scope cscope :tags tags :cause cause
                  :cross-scope? (boolean cross-scope?)
                  :matched (mapv :resource/key decisions)
                  :refetched (count refetches)
                  :left-stale (count (remove :active? decisions))
                  :exempt exempt-hit
                  :any-tag-match-other-scope? other-scope-hit?})
    (doseq [{:keys [active? decision tags] resource-key :resource/key :as _d} decisions]
      (rf.trace/emit! :rf.event :rf.resource/refetch-decision
                   {:rf.frame/id frame-id :resource/key resource-key
                    :scope (first resource-key) :active? active?
                    :decision decision :tags tags :cause cause}))
    {:rf.db/runtime rdb'
     :fx refetches}))

;; ---- release-owner --------------------------------------------------------

(defn- release-owner-from-identities
  "Releases `owner` from the entries at byte `k-ids`: detaches it from each
  entry and the owner index, drops it from in-flight work records and aborts
  any left with no owner, cancels polling on entries left owner-free and
  re-arms their GC for a full `:gc-after-ms`, and emits one
  `:rf.resource/owner-released`. Clears no route slot. An id the owner does
  not hold is a no-op. Returns `{:rf.db/runtime :fx}`.
  Spec 016 §Race and in-flight semantics"
  [runtime-db frame-id owner k-ids]
  (let [k-ids (or k-ids #{})
        ;; only an existing entry is written: detaching from nil would mint
        ;; an empty slot
        rdb1  (-> (reduce
                    (fn [db k-id]
                      (let [path (rf.resources.state/entry-path-by-id k-id)]
                        (if-let [e (get-in db path)]
                          (assoc-in db path (rf.resources.state/detach-owner e owner))
                          db)))
                    runtime-db k-ids)
                  (update-in (rf.resources.state/owner-index-path)
                             (fn [index]
                               (let [remaining (reduce disj (get index owner #{}) k-ids)]
                                 (if (empty? remaining)
                                   (dissoc index owner)
                                   (assoc index owner remaining))))))
        {rdb2 :rdb aborts :aborts}
        (reduce
          (fn [acc k-id]
            (let [db   (:rdb acc)
                  e    (get-in db (rf.resources.state/entry-path-by-id k-id))
                  wid  (:current-work e)
                  rec  (when wid (rf.resources.work-ledger/get-record db wid))]
              (if (and wid rec)
                (let [rec' (rf.resources.work-ledger/release-owner-from-record rec owner)
                      orphaned? (empty? (:owners rec'))
                      rec'' (if orphaned? (rf.resources.work-ledger/mark-abort-requested rec') rec')]
                  {:rdb (rf.resources.work-ledger/put-record db wid rec'')
                   :aborts (cond-> (:aborts acc)
                             orphaned? (conj [wid (:transport rec'')]))})
                acc)))
          {:rdb rdb1 :aborts []}
          k-ids)
        ;; The GC window restarts at the final release, so a quick re-attach
        ;; (a route bounce, a remount) reuses the cached data.
        now-owner-free
        (into []
              (keep (fn [k-id]
                      (let [e (get-in rdb2 (rf.resources.state/entry-path-by-id k-id))]
                        (when (and e (empty? (:active-owners e)))
                          e))))
              k-ids)
        ;; `:never` normalizes to no delay and opens no window
        gc-rearms
        (into []
              (keep (fn [e]
                      (when-let [gc-delay (rf.resources.state/positive-or-nil
                                            (:gc-after-ms (rf.resources.registry/resource-meta (:resource/id e))))]
                        [(:resource/key e) gc-delay])))
              now-owner-free)
        ;; Named by scoped key, never key-id: a key-id is reversible
        ;; plaintext CEDN inside a string, which egress must treat as opaque,
        ;; while a scoped key tokenizes by its owner's classification.
        released
        (into []
              (keep (fn [k-id]
                      (:resource/key (get-in runtime-db (rf.resources.state/entry-path-by-id k-id)))))
              k-ids)]
    (rf.trace/emit! :rf.event :rf.resource/owner-released
                 {:rf.frame/id frame-id :owner owner :released released
                  :aborted (mapv first aborts)})
    {:rf.db/runtime (reduce (fn [db [rkey gc-delay]]
                              (open-gc-window db frame-id rkey gc-delay))
                            rdb2 gc-rearms)
     :fx (-> (into [] (keep (fn [[wid transport]]
                              (rf.resources.work-ledger/abort-fx transport frame-id wid)))
                   aborts)
             (cond->
               (seq now-owner-free)
               (conj [:rf.resource/cancel-poll-timers
                      {:frame-id frame-id :resource/keys (mapv :resource/key now-owner-free)}]))
             (into (map (fn [[rkey gc-delay]]
                          [:rf.resource/schedule-timers
                           {:frame-id     frame-id
                            :resource/key rkey
                            ;; partial re-arm: `:gc` only
                            :timers       {:gc gc-delay}
                            :server?      (rf.resources.state/server-frame? frame-id)}]))
                   gc-rearms))}))

(defn release-owner-handler
  "`:rf.resource/release-owner`: releases `owner` from everything it holds.
  In-flight work is aborted only when no other owner needs it, and the abort
  is opportunistic. Releasing a route owner `[:route route-id nav-token]`
  also clears its nav-token's blocking and plan slots, which no reply can
  drain once the owner is gone. Payload `{:owner …}`.
  Spec 016 §Active owners and causes"
  [{rt :rf.db/runtime, frame-id :rf.frame/id} [_event-id {:keys [owner]}]]
  (let [runtime-db (or rt {})
        owned      (get-in runtime-db (conj (rf.resources.state/owner-index-path) owner))
        rdb0       (if (and (vector? owner) (= :route (first owner)))
                     (-> runtime-db
                         (rf.resources.route/clear-blocking-slot (nth owner 2))
                         (rf.resources.route/clear-plan-slot (nth owner 2)))
                     runtime-db)]
    (release-owner-from-identities rdb0 frame-id owner (or owned #{}))))

(defn release-owner-identities-handler
  "`:rf.resource.internal/release-owner-identities`: releases `owner` from
  the subset `identities` (`{<key-id> <scoped-key>}`) only, keeping its other
  holds. The route planner dispatches it on a same-token replan for the
  identities the new plan dropped; a whole-owner release and re-ensure would
  orphan and abort work the plan still needs. Clears no route slot.
  Spec 016 §Route-plan replan — same-token reconciliation"
  [{rt :rf.db/runtime, frame-id :rf.frame/id} [_event-id {:keys [owner identities]}]]
  (let [runtime-db (or rt {})
        owned      (get-in runtime-db (conj (rf.resources.state/owner-index-path) owner))
        k-ids      (into #{} (filter (or owned #{})) (keys identities))]
    (release-owner-from-identities runtime-db frame-id owner k-ids)))

;; ---- adopt-owner — the kept-identity attach-before-release primitive -------

(defn adopt-route-owner-handler
  "`:rf.resource.internal/adopt-owner`: attaches an owner to an existing
  entry without fetching. The route planner dispatches it for a kept
  identity ahead of the prior owner's release, so the identity is never
  ownerless. It joins the owner to any in-flight work, so that release cannot
  orphan and abort it, and re-projects route readiness from the entry's
  state. A missing entry is a no-op. Payload `{:resource :scope :params :owner
  :cause}`, scope and params already canonical.
  Spec 016 §Plan diff and owner handoff"
  [{rt :rf.db/runtime, frame-id :rf.frame/id}
   [_event-id {:keys [resource scope params owner cause]}]]
  (let [runtime-db (or rt {})
        scoped-key (rf.resources.state/scoped-resource-key* scope resource params)
        entry      (get-in runtime-db (rf.resources.state/entry-path scoped-key))]
    (if (nil? entry)
      {:rf.db/runtime runtime-db}
      (let [newly? (and (some? owner)
                        (not (contains? (:active-owners entry) owner)))
            hit    (rf.resources.state/attach-owner entry owner)
            wid    (:current-work hit)
            rdb0   (-> runtime-db
                       (assoc-in (rf.resources.state/entry-path scoped-key) hit)
                       (cond->
                         owner (update-in (rf.resources.state/owner-index-path)
                                          update owner (fnil conj #{}) (rf.resources.state/key-id scoped-key)))
                       (cond->
                         (and owner wid) (rf.resources.work-ledger/update-record
                                           wid rf.resources.work-ledger/join-owner+cause owner cause)))
            rdb    (rf.resources.route/reconcile-readiness rdb0)]
        (when newly?
          (rf.trace/emit! :rf.event :rf.resource/owner-attached
                       {:rf.frame/id frame-id :resource/key scoped-key
                        :owner owner :adopted? true}))
        {:rf.db/runtime rdb}))))

;; ---- clear-scope — the causal logout / tenant-switch boundary --------------

(defn- clear-scope-handler*
  "Clears the canonical concrete scope `cscope`: removes its entries and
  their ledger rows, settles their in-flight work `:cancelled` with the
  token's `:completed-at`, reindexes, aborts the in-flight requests and
  cancels the entries' timers."
  [runtime-db frame-id cscope cause time-ms]
  (let [entries    (get-in runtime-db (rf.resources.state/entries-path))
        in-scope-ids (into #{} (comp (filter (fn [[_k-id e]] (= cscope (first (:resource/key e)))))
                                     (map key))
                           entries)
        in-scope     (into #{} (comp (filter (fn [[_k-id e]] (= cscope (first (:resource/key e)))))
                                     (map (fn [[_k-id e]] (:resource/key e))))
                           entries)
        in-flight  (into []
                         (keep (fn [k-id]
                                 (let [e (get entries k-id)
                                       wid (:current-work e)]
                                   (when wid
                                     [wid (:transport (rf.resources.work-ledger/get-record runtime-db wid))]))))
                         in-scope-ids)
        rdb'       (-> runtime-db
                       (update-in (rf.resources.state/entries-path)
                                  (fn [es] (reduce dissoc es in-scope-ids)))
                       (as-> db (reduce (fn [d [wid _]]
                                          (rf.resources.work-ledger/update-record
                                            d wid rf.resources.work-ledger/mark-terminal
                                            :cancelled {:reason :clear-scope
                                                        :completed-at time-ms}))
                                        db in-flight))
                       ;; A cleared key has no Xray tail to serve, so its whole
                       ;; ledger holding goes, not just a bounded tail.
                       ;; Spec 016 §Ledger row retention and identity
                       (as-> db (reduce rf.resources.work-ledger/drop-rows-for-key
                                        db in-scope))
                       (update rf.resources.state/resources-key rf.resources.state/reindex-keys
                               entries in-scope-ids))]
    (rf.trace/emit! :rf.event :rf.resource/removed
                 {:rf.frame/id frame-id :scope cscope :cause cause
                  :removed (vec in-scope) :reason :clear-scope
                  :aborted (mapv first in-flight) :completed-at time-ms})
    {:rf.db/runtime rdb'
     :fx (cond-> (into [] (keep (fn [[wid transport]] (rf.resources.work-ledger/abort-fx transport frame-id wid)))
                       in-flight)
           (seq in-scope)
           (conj [:rf.resource/cancel-timers
                  {:frame-id frame-id :resource/keys (vec in-scope)}]))}))

(defn clear-scope-handler
  "`:rf.resource/clear-scope`: removes every entry in the concrete `:scope`
  and cancels its in-flight work. Concrete only: dispatched from a logout
  handler's `:fx`, a `{:from-db …}` reference would resolve against the
  post-logout db, so the caller pre-resolves with `rf/resolve-resource-scope`.
  A reference map is refused, as is a malformed scope, so a typo never clears
  the wrong scope. Payload `{:scope :cause}`. Spec 016 §`clear-scope` is causal"
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms}
   [_event-id {:keys [scope cause]}]]
  (let [runtime-db (or rt {})
        cscope     (rf.resources.state/canonicalize-scope
                     scope 'rf.resource/clear-scope nil)]
    (clear-scope-handler* runtime-db frame-id cscope cause time-ms)))

;; ---- change-principal — the identity switch --------------------------------

(defn change-principal-handler
  "`:rf.resource/change-principal`: the identity switch. Payload `{:from
  [<scope> …] :cause <edn>}`, `:from` the departing principal's concrete
  scopes (nil members skipped). Every scope is validated before any is
  cleared; each is then cleared as by `clear-scope`, and a frame with an
  active route dispatches `[:rf.route/replan-resources {:cause cause}]`.

  Clearing first means no queued focus, reconnect, poll or invalidation
  refetch can load an old-scope key with the new credentials. Explicit rather
  than automatic, because a resolver's answer changing is not by itself a
  principal change. Spec 016 §Changing principal"
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms}
   [_event-id {:keys [from cause]}]]
  (when-not (or (nil? from) (vector? from))
    (rf.error/throw-error!
      :rf.error/resource-invalid-scope
      're-frame.resources.events/change-principal-handler
      (str ":rf.resource/change-principal was given :from " (pr-str from)
           " — :from is a VECTOR of the departing principal's concrete scopes, "
           "e.g. {:from [old-session old-viewer]}, so a single scope is wrapped: "
           "{:from [old-scope]}. Per Spec 016 §Changing principal.")
      {:recovery :fix-scope
       :extra    {:from from}}))
  (let [runtime-db (or rt {})
        ;; Eager, so every member is validated before the first clear.
        cscopes    (into []
                         (comp (remove nil?)
                               (map #(rf.resources.state/canonicalize-scope
                                       % 're-frame.resources.events/change-principal-handler nil))
                               (distinct))
                         from)
        {rdb :rdb fx :fx}
        (reduce (fn [{:keys [rdb fx]} cscope]
                  (let [{rdb' :rf.db/runtime fx' :fx}
                        (clear-scope-handler* rdb frame-id cscope cause time-ms)]
                    {:rdb rdb' :fx (into fx fx')}))
                {:rdb runtime-db :fx []}
                cscopes)
        route?     (some? (get-in rdb (conj (rf.resources.route/current-path) :route-id)))]
    (cond-> {:rf.db/runtime rdb}
      (or (seq fx) route?)
      (assoc :fx (cond-> fx
                   route? (conj [:dispatch [:rf.route/replan-resources {:cause cause}]]))))))

;; ---- remove — single-instance cache removal --------------------------------

(defn remove-handler
  "`:rf.resource/remove`: removes one instance and its ledger rows,
  cancelling any in-flight attempt with the token's `:completed-at`. Payload
  `{:resource :scope :params}`.
  Spec 016 §Events (map payloads, not positional argument vectors)"
  [{rt :rf.db/runtime, frame-id :rf.frame/id, app-db :db, time-ms :rf/time-ms}
   [_event-id {:keys [resource] :as payload}]]
  (let [runtime-db (or rt {})
        spec       (rf.resources.registry/require-resource-spec! resource 'rf.resource/remove)
        scope      (rf.resources.registry/resolve-scope-for-event
                     resource spec {:payload-scope (:scope payload) :db app-db} 'rf.resource/remove)
        cparams    (rf.resources.registry/validate+canonicalize-params
                     resource spec (rf.resources.state/params-present? payload) 'rf.resource/remove)
        scoped-key (rf.resources.state/scoped-resource-key* scope resource cparams)
        entry      (get-in runtime-db (rf.resources.state/entry-path scoped-key))
        wid        (:current-work entry)
        transport  (when wid (:transport (rf.resources.work-ledger/get-record runtime-db wid)))
        old-entries (get-in runtime-db (rf.resources.state/entries-path))
        rdb'       (-> runtime-db
                       ;; by byte key-id: a vector dissoc would no-op and leak
                       ;; the entry
                       (update-in (rf.resources.state/entries-path) dissoc (rf.resources.state/key-id scoped-key))
                       ;; Settle before the drop: a row the inverse index
                       ;; missed stays terminal rather than surviving as
                       ;; falsely in-flight into an epoch snapshot.
                       (cond-> wid (rf.resources.work-ledger/update-record
                                     wid rf.resources.work-ledger/mark-terminal
                                     :cancelled {:reason :remove :completed-at time-ms}))
                       ;; A removed key has no Xray tail to serve.
                       ;; Spec 016 §Ledger row retention and identity
                       (rf.resources.work-ledger/drop-rows-for-key scoped-key)
                       (update rf.resources.state/resources-key rf.resources.state/reindex-keys
                               old-entries [(rf.resources.state/key-id scoped-key)]))]
    (rf.trace/emit! :rf.event :rf.resource/removed
                 {:rf.frame/id frame-id :resource/key scoped-key :reason :remove
                  :aborted (when wid [wid]) :completed-at time-ms})
    {:rf.db/runtime rdb'
     :fx (conj (if-let [fx (and wid (rf.resources.work-ledger/abort-fx transport frame-id wid))] [fx] [])
               [:rf.resource/cancel-timers
                {:frame-id frame-id :resource/keys [scoped-key]}])}))

;; ---- framework-internal reply handlers ------------------------------------
;; They verify frame + work-id + generation before writing, through the shared
;; `re-frame.resources.reply-handlers` substrate. The transport appends its
;; public reply as the event's last arg, and the handlers re-lift it with the
;; verification payload into the canonical reply map. A test may inline
;; `:data` / `:error` in the verification payload instead.
;; Managed-Effects §The uniform reply envelope

(defn- resolve-reply-payload
  "The reply `payload` with `:resource/key` and `:scope` taken from the
  receiving frame's ledger row; a key or scope the payload carries is
  discarded, never trusted. With no row (a cross-frame reply, or the row left
  with its entry) the payload has no key, and `live-entry-for-reply`
  suppresses it. Spec 016 §Transport"
  [runtime-db receiving-frame-id payload]
  (let [resource-key (:resource/key (rf.resources.reply-handlers/ledger-row-for-reply
                                      runtime-db receiving-frame-id payload))]
    (cond-> (dissoc payload :resource/key :scope)
      (some? resource-key) (assoc :resource/key resource-key
                                  :scope        (first resource-key)))))

(defn- entry-path-for-reply
  "The resource family's slot path-fn: the entry path for the resolved key,
  or nil when none resolved."
  [{resource-key :resource/key}]
  (when (some? resource-key)
    (rf.resources.state/entry-path resource-key)))

(defn- resource-correlation
  "The resource family's stale-reply correlation: `:resource/key` and
  `:scope`, each omitted when absent."
  [{resource-key :resource/key :keys [scope]}]
  (cond-> {}
    (some? resource-key) (assoc :resource/key resource-key)
    (some? scope)        (assoc :scope scope)))

(def ^:private resource-stale-knobs
  "The resource family's knobs for
  `rf.resources.reply-handlers/stale-suppress-reply`."
  {:work-kind      rf.resources.reply/work-kind-resource
   :stale-reason   :rf.resource/superseded
   :correlation-fn resource-correlation})

(defn- stale-suppress-reply
  "The canonical `:status :stale` outcome for a superseded or vanished
  resource reply, via the shared substrate. `extra` adds diagnostic facts.
  Managed-Effects §Stale suppression"
  [runtime-db _resource-key payload extra]
  (rf.resources.reply-handlers/stale-suppress-reply
    runtime-db entry-path-for-reply payload resource-stale-knobs extra))

(defn- emit-resource-stale-suppressed!
  "Emits `:rf.resource/stale-suppressed` via the shared emitter. Names the
  attempt, not its scoped key: the resource id comes from the work id, so a
  reply whose row is gone is still attributed."
  [frame-id work-id generation outcome stale]
  (rf.resources.reply-handlers/emit-stale-suppressed!
    :rf.resource/stale-suppressed
    {:rf.frame/id frame-id
     :resource/id (rf.resources.work-ledger/work-id-resource-id work-id)
     :generation generation :outcome outcome}
    stale))

(defn- live-entry-for-reply
  "The live entry the reply still owns, else nil (the reply MUST be
  suppressed). Spec 016 §Cancellation is opportunistic; stale suppression is mandatory"
  [runtime-db receiving-frame-id payload]
  (rf.resources.reply-handlers/live-slot-for-reply
    runtime-db receiving-frame-id entry-path-for-reply payload))

(defn succeeded-handler
  "`:rf.resource.internal/succeeded`: on a verified reply, settles the entry
  `:loaded` with the decoded value as `:data` (the old value kept when `=`),
  `:loaded-at` from the reply token, `:stale-at` and the produced `:tags`;
  resolves invalidations that landed meanwhile, arms timers, and fans out to
  `:reply-to` targets last. A stale reply is suppressed. Event `[_
  <verification-payload> <http-result>]`. Spec 016 §Status semantics"
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms}
   [_event-id reply-payload http-result]]
  (let [runtime-db (or rt {})
        {work-id :work/id resource-key :resource/key :keys [generation] :as payload}
        (resolve-reply-payload runtime-db frame-id reply-payload)
        resource-id (rf.resources.work-ledger/work-id-resource-id work-id)
        value      (rf.resources.reply/transport-success-value payload http-result :data)
        ;; the completion time is the reply token's `:rf/time-ms`; the
        ;; handler never reads the clock
        completed-at time-ms
        reply      (rf.resources.reply/success-reply payload value
                                         {:work-kind rf.resources.reply/work-kind-resource
                                          :completed-at completed-at})
        entry      (live-entry-for-reply runtime-db frame-id payload)
        ;; read before the row settles; delivered only on the accepted branch
        record     (rf.resources.work-ledger/get-record runtime-db work-id)
        cont-fxs   (read-reply-continuation-fxs record reply resource-key)]
    (if (nil? entry)
      ;; stale: the row settles `:suppressed` and no reply target runs
      (let [stale (stale-suppress-reply runtime-db resource-key payload
                                        {:outcome :success})]
        (emit-resource-stale-suppressed!
          frame-id work-id generation :success stale)
        (rf.resources.reply-handlers/settle-suppressed-work!
          runtime-db frame-id payload {:reason :stale-reply :outcome :success}))
      (let [spec      (rf.resources.registry/resource-meta (:resource/id entry))
            data      (:value reply)
            loaded-at (:completed-at reply)
            stale-at  (rf.resources.state/stale-at-for spec loaded-at)
            stale-delay-ms (rf.resources.state/positive-or-nil (:stale-after-ms spec))
            gc-delay-ms    (rf.resources.state/positive-or-nil (:gc-after-ms spec))
            tags-fn   (:tags spec)
            tags      (when tags-fn (set (tags-fn (nth resource-key 2) data)))
            [entry' follow-up-tags]
            (settle-invalidations
              entry
              (rf.resources.state/entry-succeeded
                entry {:data data :loaded-at loaded-at
                       :stale-at stale-at :tags tags})
              record)
            ;; a poll never pins an owner-free entry
            poll-delay-ms (when (seq (:active-owners entry'))
                            (rf.resources.state/positive-or-nil (:poll-interval-ms spec)))
            old-entries (get-in runtime-db (rf.resources.state/entries-path))
            rdb'      (-> runtime-db
                          (assoc-in (rf.resources.state/entry-path resource-key) entry')
                          (rf.resources.work-ledger/settle-terminal
                            work-id
                            :completed {:loaded-at loaded-at})
                          (update rf.resources.state/resources-key rf.resources.state/reindex-keys
                                  old-entries [(rf.resources.state/key-id resource-key)])
                          (rf.resources.route/reconcile-readiness))]
        (rf.resources.work-ledger/clear-handle! frame-id work-id)
        (rf.trace/emit! :rf.event :rf.resource/work-completed
                     {:rf.frame/id frame-id :resource/id resource-id
                      :work/id work-id :generation generation :status :completed})
        (rf.trace/emit! :rf.event :rf.resource/succeeded
                     {:rf.frame/id frame-id :resource/id resource-id
                      :work/id work-id :generation generation
                      :status-before (:status entry) :status-after :loaded})
        ;; after `:rf.resource/succeeded`, so the trace follows settlement
        (emit-resource-replied!
          frame-id work-id (:status reply) (:reply-targets record) false)
        ;; The `:reply-to` fan-out goes last, after cache settlement.
        ;; Spec 016 §Read completion continuations — call-site `:reply-to`
        (let [timers-fx (when (or stale-delay-ms gc-delay-ms poll-delay-ms)
                          [[:rf.resource/schedule-timers
                            {:frame-id     frame-id
                             :resource/key resource-key
                             ;; naming all three kinds cancels any whose policy
                             ;; is absent
                             :timers       {:stale stale-delay-ms
                                            :gc    gc-delay-ms
                                            :poll  poll-delay-ms}
                             :server?      (rf.resources.state/server-frame? frame-id)}]])
              fx        (cond-> (vec timers-fx)
                          follow-up-tags (conj (follow-up-refetch-fx resource-key follow-up-tags)))
              fx        (into fx cont-fxs)]
          (cond-> {:rf.db/runtime (open-gc-window rdb' frame-id resource-key gc-delay-ms)}
            (seq fx) (assoc :fx fx)))))))

(defn- entry-abort-settled
  "Settles a live entry whose attempt was aborted, a cancellation rather
  than a failure: a refresh returns to `:loaded` keeping `:data`, a first
  load settles `:idle`. No error facts; `:current-work` is cleared and
  `:revision` bumped, so an optimistic rollback cannot resurrect the
  cancelled pointer. Spec 016 §Status semantics"
  [entry]
  (rf.resources.state/bump-revision
    (if (rf.resources.state/has-data? entry)
      (assoc entry :status :loaded :current-work nil)
      (assoc entry :status :idle  :current-work nil))))

(defn failed-handler
  "`:rf.resource.internal/failed`: on a verified reply, a first-load failure
  settles `:error`, and a refresh failure returns to `:loaded` keeping
  `:data` and recording `:refresh-error`. An abort (`:rf.http/aborted`) is a
  cancellation instead: `entry-abort-settled`, the work row `:cancelled`, no
  error facts. A first-load settle arms the stale and GC timers, since no
  success will arm them, so an owner-free entry is still reaped; a refresh
  keeps the timers its prior success armed. A stale reply, abort or not, is
  suppressed. Event `[_ <verification-payload> <http-result>]`.
  Spec 016 §Status semantics"
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms}
   [_event-id reply-payload http-result]]
  (let [runtime-db (or rt {})
        {work-id :work/id resource-key :resource/key :keys [generation] :as payload}
        (resolve-reply-payload runtime-db frame-id reply-payload)
        resource-id (rf.resources.work-ledger/work-id-resource-id work-id)
        error      (rf.resources.reply/transport-failure-envelope payload http-result)
        ;; the completion time is the reply token's `:rf/time-ms`, as on success
        completed-at time-ms
        reply      (rf.resources.reply/failure-reply payload error
                                         {:work-kind rf.resources.reply/work-kind-resource
                                          :completed-at completed-at})
        aborted?   (= :cancelled (:status reply))
        entry      (live-entry-for-reply runtime-db frame-id payload)
        record     (rf.resources.work-ledger/get-record runtime-db work-id)
        cont-fxs   (read-reply-continuation-fxs record reply resource-key)]
    (cond
      ;; Stale wins over natural status: a stale abort settles `:suppressed`,
      ;; never an accepted `:cancelled`.
      (nil? entry)
      (let [stale (stale-suppress-reply runtime-db resource-key payload
                                        {:outcome (if aborted? :aborted :failure)})]
        (emit-resource-stale-suppressed!
          frame-id work-id generation
          (if aborted? :aborted :failure) stale)
        (rf.resources.reply-handlers/settle-suppressed-work!
          runtime-db frame-id payload
          {:reason :stale-reply
           :outcome (if aborted? :aborted :failure)
           :completed-at completed-at}))

      aborted?
      (let [entry'    (entry-abort-settled entry)
            first-load-abort? (= :idle (:status entry'))
            spec      (rf.resources.registry/resource-meta (:resource/id entry'))
            rdb'   (-> runtime-db
                       (assoc-in (rf.resources.state/entry-path resource-key) entry')
                       (rf.resources.work-ledger/settle-terminal
                         work-id
                         :cancelled {:reason :aborted :completed-at completed-at})
                       (rf.resources.route/reconcile-readiness))
            stale-delay-ms (when first-load-abort?
                             (rf.resources.state/positive-or-nil (:stale-after-ms spec)))
            gc-delay-ms    (when first-load-abort?
                             (rf.resources.state/positive-or-nil (:gc-after-ms spec)))]
        (rf.resources.work-ledger/clear-handle! frame-id work-id)
        (rf.trace/emit! :rf.event :rf.resource/work-abort-requested
                     {:rf.frame/id frame-id :resource/id resource-id
                      :work/id work-id :generation generation
                      :status-before (:status entry) :status-after (:status entry')})
        (emit-resource-replied!
          frame-id work-id (:status reply) (:reply-targets record) false)
        (let [timers-fx (when (or stale-delay-ms gc-delay-ms)
                          [[:rf.resource/schedule-timers
                            {:frame-id     frame-id
                             :resource/key resource-key
                             ;; a first-load settle arms stale/GC and cancels
                             ;; poll
                             :timers       {:stale stale-delay-ms
                                            :gc    gc-delay-ms
                                            :poll  nil}
                             :server?      (rf.resources.state/server-frame? frame-id)}]])
              fx        (into (vec timers-fx) cont-fxs)]
          (cond-> {:rf.db/runtime (open-gc-window rdb' frame-id resource-key gc-delay-ms)}
            (seq fx) (assoc :fx fx))))

      :else
      (let [entry' (rf.resources.state/entry-failed entry {:error error})
            first-load-error? (= :error (:status entry'))
            op     (if first-load-error?
                     :rf.resource/failed :rf.resource/refresh-failed)
            spec   (rf.resources.registry/resource-meta (:resource/id entry'))
            rdb'   (-> runtime-db
                       (assoc-in (rf.resources.state/entry-path resource-key) entry')
                       (rf.resources.work-ledger/settle-terminal
                         work-id
                         :failed {:error error :completed-at completed-at})
                       (rf.resources.route/reconcile-readiness))
            stale-delay-ms (when first-load-error?
                             (rf.resources.state/positive-or-nil (:stale-after-ms spec)))
            gc-delay-ms    (when first-load-error?
                             (rf.resources.state/positive-or-nil (:gc-after-ms spec)))]
        (rf.resources.work-ledger/clear-handle! frame-id work-id)
        (rf.trace/emit! :rf.event :rf.resource/work-completed
                     {:rf.frame/id frame-id :resource/id resource-id
                      :work/id work-id :generation generation :status :failed})
        (rf.trace/emit! :rf.event op
                     {:rf.frame/id frame-id :resource/id resource-id
                      :work/id work-id :generation generation
                      :status-before (:status entry) :status-after (:status entry')})
        (emit-resource-replied!
          frame-id work-id (:status reply) (:reply-targets record) false)
        (let [timers-fx (when (or stale-delay-ms gc-delay-ms)
                          [[:rf.resource/schedule-timers
                            {:frame-id     frame-id
                             :resource/key resource-key
                             ;; a first-load settle arms stale/GC and cancels
                             ;; poll
                             :timers       {:stale stale-delay-ms
                                            :gc    gc-delay-ms
                                            :poll  nil}
                             :server?      (rf.resources.state/server-frame? frame-id)}]])
              fx        (into (vec timers-fx) cont-fxs)]
          (cond-> {:rf.db/runtime (open-gc-window rdb' frame-id resource-key gc-delay-ms)}
            (seq fx) (assoc :fx fx)))))))

;; ---- infinite-feed page reply handlers -------------------------------------
;; Distinct from the scalar handlers: a page success writes one page, and a
;; load-more failure is the third error channel (`:page-error`). Verification
;; is the same, so a stale page reply never touches a newer feed.

(defn page-succeeded-handler
  "`:rf.resource.internal/page-succeeded`: on a verified reply, writes the
  page at its index (an append at the tail, otherwise an in-place replace),
  records its page param, and returns the feed to `:loaded`; chains the next
  refetch-sweep leg when one is armed. A stale reply is suppressed.
  Spec 016 §Causal event — `:rf.resource/load-more` (R2)"
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms}
   [_event-id reply-payload http-result]]
  (let [runtime-db   (or rt {})
        {work-id :work/id resource-key :resource/key
         :keys [generation]
         page-param :rf.resource/page-param
         page-index :rf.resource/page-index :as payload}
        (resolve-reply-payload runtime-db frame-id reply-payload)
        resource-id (rf.resources.work-ledger/work-id-resource-id work-id)
        page         (rf.resources.reply/transport-success-value payload http-result :data)
        completed-at time-ms
        reply        (rf.resources.reply/success-reply payload page
                                           {:work-kind rf.resources.reply/work-kind-resource
                                            :completed-at completed-at})
        entry        (live-entry-for-reply runtime-db frame-id payload)
        ;; An infinite ensure/refetch settles its `:reply-to` here, with the
        ;; merged items over the post-settle feed as `:value`.
        record       (rf.resources.work-ledger/get-record runtime-db work-id)]
    (if (nil? entry)
      (let [stale (stale-suppress-reply runtime-db resource-key payload
                                        {:outcome :page-success})]
        (emit-resource-stale-suppressed!
          frame-id work-id generation :page-success stale)
        (rf.resources.reply-handlers/settle-suppressed-work!
          runtime-db frame-id payload {:reason :stale-reply :outcome :page-success}))
      (let [spec      (rf.resources.registry/resource-meta (:resource/id entry))
            decoded   (:value reply)
            loaded-at (:completed-at reply)
            stale-at  (rf.resources.state/stale-at-for spec loaded-at)
            stale-delay-ms (rf.resources.state/positive-or-nil (:stale-after-ms spec))
            gc-delay-ms    (rf.resources.state/positive-or-nil (:gc-after-ms spec))
            replaced  (rf.resources.state/entry-replace-page
                        entry {:page decoded :page-param page-param
                               :page-index page-index
                               :next-page-param-fn (:next-page-param spec)
                               :loaded-at loaded-at :stale-at stale-at})
            sweep-leg (rf.resources.state/next-refetch-sweep-leg replaced)
            swept     (if sweep-leg
                        (rf.resources.state/entry-advance-refetch-sweep replaced)
                        replaced)
            ;; tags over the accumulated pages, so invalidation can find the
            ;; feed
            tags-fn   (:tags spec)
            [entry' follow-up-tags]
            (settle-invalidations
              entry
              (cond-> swept
                tags-fn (assoc :tags (set (tags-fn (nth resource-key 2) (:data swept)))))
              record)
            ;; guarded on targets, so the merge and its loud error never run
            ;; for a load-more
            cont-fxs  (when (seq (:reply-targets record))
                        (read-reply-continuation-fxs
                          record
                          (assoc reply :value (infinite-reply-value entry' reply-to-where))
                          resource-key))
            poll-delay-ms (when (seq (:active-owners entry'))
                            (rf.resources.state/positive-or-nil (:poll-interval-ms spec)))
            rdb'      (-> runtime-db
                          (assoc-in (rf.resources.state/entry-path resource-key) entry')
                          (rf.resources.work-ledger/settle-terminal
                            work-id
                            :completed {:loaded-at loaded-at :page-index page-index})
                          (update rf.resources.state/resources-key rf.resources.state/reindex-keys
                                  (get-in runtime-db (rf.resources.state/entries-path))
                                  [(rf.resources.state/key-id resource-key)])
                          (rf.resources.route/reconcile-readiness))
            ;; self-dispatched, so the leg mints its own replay-stable
            ;; generation
            sweep-fx  (when sweep-leg
                        (let [[leg-param leg-index] sweep-leg]
                          [:dispatch [:rf.resource.internal/refetch-page
                                      {:resource (second resource-key)
                                       :scope    (first resource-key)
                                       :params   (nth resource-key 2)
                                       :cause    [:rf.resource/refetch-sweep leg-index]
                                       :rf.resource/page-param leg-param
                                       :rf.resource/page-index leg-index}]]))]
        (rf.resources.work-ledger/clear-handle! frame-id work-id)
        (rf.trace/emit! :rf.event :rf.resource/work-completed
                     {:rf.frame/id frame-id :resource/id resource-id
                      :work/id work-id :generation generation :status :completed})
        (rf.trace/emit! :rf.event :rf.resource/page-appended
                     {:rf.frame/id frame-id :resource/id resource-id
                      :work/id work-id :generation generation
                      :page-index page-index :page-count (rf.resources.state/page-count entry')
                      :next-page-param (:next-page-param entry')
                      :terminal? (rf.resources.state/terminal? (:next-page-param entry'))})
        (emit-resource-replied!
          frame-id work-id (:status reply) (:reply-targets record) false)
        (let [timers-fx (when (or stale-delay-ms gc-delay-ms poll-delay-ms)
                          [:rf.resource/schedule-timers
                           {:frame-id     frame-id
                            :resource/key resource-key
                            ;; naming all three kinds cancels any whose policy
                            ;; is absent
                            :timers       {:stale stale-delay-ms
                                           :gc    gc-delay-ms
                                           :poll  poll-delay-ms}
                            :server?      (rf.resources.state/server-frame? frame-id)}])
              fx        (cond-> []
                          timers-fx (conj timers-fx)
                          sweep-fx  (conj sweep-fx)
                          follow-up-tags (conj (follow-up-refetch-fx resource-key follow-up-tags)))
              fx        (into fx cont-fxs)]
          (cond-> {:rf.db/runtime (open-gc-window rdb' frame-id resource-key gc-delay-ms)}
            (seq fx) (assoc :fx fx)))))))

(defn page-failed-handler
  "`:rf.resource.internal/page-failed`: on a verified reply, the settlement
  splits on page identity:
    - first-load abort (page 0, no pages yet): `:idle`, no error, stale/GC
      timers armed;
    - first-load failure (page 0, no pages yet): the first-load `:error`
      channel, stale/GC timers armed;
    - any other abort: `:loaded`, pages kept, no `:page-error`;
    - page-0 failure over existing pages: a whole-feed refresh failure
      (`:refresh-error`);
    - page N>0 failure: `:page-error`, pages kept.
  Any abort or failure stops a refetch sweep. An aborted empty feed settles
  `:idle`, never `:loaded`, which would let a later ensure fresh-skip a feed
  that never loaded. A stale reply is suppressed. Spec 016 §Status semantics"
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms}
   [_event-id reply-payload http-result]]
  (let [runtime-db   (or rt {})
        {work-id :work/id resource-key :resource/key :keys [generation]
         page-index :rf.resource/page-index :as payload}
        (resolve-reply-payload runtime-db frame-id reply-payload)
        resource-id (rf.resources.work-ledger/work-id-resource-id work-id)
        error        (rf.resources.reply/transport-failure-envelope payload http-result)
        completed-at time-ms
        reply        (rf.resources.reply/failure-reply payload error
                                           {:work-kind rf.resources.reply/work-kind-resource
                                            :completed-at completed-at})
        aborted?     (= :cancelled (:status reply))
        entry        (live-entry-for-reply runtime-db frame-id payload)
        ;; page 0 over pages already loaded is a refresh, not a first load
        first-load?  (and (some? entry)
                          (= 0 page-index)
                          (zero? (rf.resources.state/page-count entry)))
        record       (rf.resources.work-ledger/get-record runtime-db work-id)
        cont-fxs     (read-reply-continuation-fxs record reply resource-key)]
    (cond
      ;; stale wins over natural status
      (nil? entry)
      (let [stale (stale-suppress-reply runtime-db resource-key payload
                                        {:outcome (if aborted? :aborted :page-failure)})]
        (emit-resource-stale-suppressed!
          frame-id work-id generation
          (if aborted? :aborted :page-failure) stale)
        (rf.resources.reply-handlers/settle-suppressed-work!
          runtime-db frame-id payload {:reason :stale-reply
                                       :outcome (if aborted? :aborted :page-failure)
                                       :completed-at completed-at}))

      ;; first-load abort
      (and aborted? first-load?)
      (let [entry' (-> (assoc entry :status :idle :current-work nil)
                       rf.resources.state/clear-refetch-sweep
                       rf.resources.state/bump-revision)
            spec   (rf.resources.registry/resource-meta (:resource/id entry'))
            rdb'   (-> runtime-db
                       (assoc-in (rf.resources.state/entry-path resource-key) entry')
                       (rf.resources.work-ledger/settle-terminal
                         work-id
                         :cancelled {:reason :aborted :completed-at completed-at})
                       (rf.resources.route/reconcile-readiness))
            stale-delay-ms (rf.resources.state/positive-or-nil (:stale-after-ms spec))
            gc-delay-ms    (rf.resources.state/positive-or-nil (:gc-after-ms spec))]
        (rf.resources.work-ledger/clear-handle! frame-id work-id)
        (rf.trace/emit! :rf.event :rf.resource/work-abort-requested
                     {:rf.frame/id frame-id :resource/id resource-id
                      :work/id work-id :generation generation
                      :status-before (:status entry) :status-after (:status entry')})
        (emit-resource-replied!
          frame-id work-id (:status reply) (:reply-targets record) false)
        (let [timers-fx (when (or stale-delay-ms gc-delay-ms)
                          [[:rf.resource/schedule-timers
                            {:frame-id     frame-id
                             :resource/key resource-key
                             ;; a first-load settle arms stale/GC and cancels
                             ;; poll
                             :timers       {:stale stale-delay-ms
                                            :gc    gc-delay-ms
                                            :poll  nil}
                             :server?      (rf.resources.state/server-frame? frame-id)}]])
              fx        (into (vec timers-fx) cont-fxs)]
          (cond-> {:rf.db/runtime (open-gc-window rdb' frame-id resource-key gc-delay-ms)}
            (seq fx) (assoc :fx fx))))

      ;; any other abort: the feed keeps its pages, and the timers its prior
      ;; success armed
      aborted?
      (let [entry' (-> (assoc entry :status :loaded :current-work nil)
                       rf.resources.state/clear-refetch-sweep
                       rf.resources.state/bump-revision)
            rdb'   (-> runtime-db
                       (assoc-in (rf.resources.state/entry-path resource-key) entry')
                       (rf.resources.work-ledger/settle-terminal
                         work-id
                         :cancelled {:reason :aborted :completed-at completed-at})
                       (rf.resources.route/reconcile-readiness))]
        (rf.resources.work-ledger/clear-handle! frame-id work-id)
        (rf.trace/emit! :rf.event :rf.resource/work-abort-requested
                     {:rf.frame/id frame-id :resource/id resource-id
                      :work/id work-id :generation generation
                      :status-before (:status entry) :status-after (:status entry')})
        (emit-resource-replied!
          frame-id work-id (:status reply) (:reply-targets record) false)
        (cond-> {:rf.db/runtime rdb'}
          (seq cont-fxs) (assoc :fx (vec cont-fxs))))

      ;; first-load failure, traced as `:rf.resource/failed` so the channel
      ;; and the trace agree
      first-load?
      (let [entry' (rf.resources.state/entry-failed entry {:error error})
            spec   (rf.resources.registry/resource-meta (:resource/id entry'))
            rdb'   (-> runtime-db
                       (assoc-in (rf.resources.state/entry-path resource-key) entry')
                       (rf.resources.work-ledger/settle-terminal
                         work-id
                         :failed {:error error :completed-at completed-at})
                       (rf.resources.route/reconcile-readiness))
            stale-delay-ms (rf.resources.state/positive-or-nil (:stale-after-ms spec))
            gc-delay-ms    (rf.resources.state/positive-or-nil (:gc-after-ms spec))]
        (rf.resources.work-ledger/clear-handle! frame-id work-id)
        (rf.trace/emit! :rf.event :rf.resource/work-completed
                     {:rf.frame/id frame-id :resource/id resource-id
                      :work/id work-id :generation generation :status :failed})
        (rf.trace/emit! :rf.event :rf.resource/failed
                     {:rf.frame/id frame-id :resource/id resource-id
                      :work/id work-id :generation generation
                      :status-before (:status entry) :status-after (:status entry')
                      :error error})
        (emit-resource-replied!
          frame-id work-id (:status reply) (:reply-targets record) false)
        (let [timers-fx (when (or stale-delay-ms gc-delay-ms)
                          [[:rf.resource/schedule-timers
                            {:frame-id     frame-id
                             :resource/key resource-key
                             ;; a first-load settle arms stale/GC and cancels
                             ;; poll
                             :timers       {:stale stale-delay-ms
                                            :gc    gc-delay-ms
                                            :poll  nil}
                             :server?      (rf.resources.state/server-frame? frame-id)}]])
              fx        (into (vec timers-fx) cont-fxs)]
          (cond-> {:rf.db/runtime (open-gc-window rdb' frame-id resource-key gc-delay-ms)}
            (seq fx) (assoc :fx fx))))

      ;; whole-feed refresh failure: page 0 over existing pages, so a view
      ;; warns "refresh failed" rather than offering a load-more retry
      (= 0 page-index)
      (let [entry' (-> (rf.resources.state/entry-failed entry {:error error})
                       rf.resources.state/clear-refetch-sweep)
            rdb'   (-> runtime-db
                       (assoc-in (rf.resources.state/entry-path resource-key) entry')
                       (rf.resources.work-ledger/settle-terminal
                         work-id
                         :failed {:error error :completed-at completed-at})
                       (rf.resources.route/reconcile-readiness))]
        (rf.resources.work-ledger/clear-handle! frame-id work-id)
        (rf.trace/emit! :rf.event :rf.resource/work-completed
                     {:rf.frame/id frame-id :resource/id resource-id
                      :work/id work-id :generation generation :status :failed})
        (rf.trace/emit! :rf.event :rf.resource/refresh-failed
                     {:rf.frame/id frame-id :resource/id resource-id
                      :work/id work-id :generation generation
                      :status-before (:status entry) :status-after (:status entry')})
        (emit-resource-replied!
          frame-id work-id (:status reply) (:reply-targets record) false)
        (cond-> {:rf.db/runtime rdb'}
          (seq cont-fxs) (assoc :fx (vec cont-fxs))))

      ;; load-more (page N>0) failure: the third error channel
      :else
      (let [entry' (-> (rf.resources.state/entry-page-failed entry {:error error})
                       rf.resources.state/clear-refetch-sweep)
            rdb'   (-> runtime-db
                       (assoc-in (rf.resources.state/entry-path resource-key) entry')
                       (rf.resources.work-ledger/settle-terminal
                         work-id
                         :failed {:error error :completed-at completed-at})
                       (rf.resources.route/reconcile-readiness))]
        (rf.resources.work-ledger/clear-handle! frame-id work-id)
        (rf.trace/emit! :rf.event :rf.resource/work-completed
                     {:rf.frame/id frame-id :resource/id resource-id
                      :work/id work-id :generation generation :status :failed})
        (rf.trace/emit! :rf.event :rf.resource/page-failed
                     {:rf.frame/id frame-id :resource/id resource-id
                      :work/id work-id :generation generation
                      :status-before (:status entry) :status-after (:status entry')
                      :page-error error})
        (emit-resource-replied!
          frame-id work-id (:status reply) (:reply-targets record) false)
        (cond-> {:rf.db/runtime rdb'}
          (seq cont-fxs) (assoc :fx (vec cont-fxs)))))))

(defn stale-fired-handler
  "`:rf.resource.internal/stale-fired`: the freshness wake. Subs compare
  `:stale-at` with the live clock but only re-run on a commit, so this
  supplies one at the deadline. It re-checks the live entry: gone, nothing to
  do; past `:stale-at`, records it as `:stale-wake-at`, once per deadline;
  still fresh, re-arms `:stale` for the time remaining. Never fetches and
  never changes `:status`. Spec 016 §Freshness clock contract"
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms}
   [_event-id {resource-key :resource/key}]]
  (let [runtime-db (or rt {})
        path       (rf.resources.state/entry-path resource-key)
        entry      (get-in runtime-db path)
        stale-at   (:stale-at entry)
        ;; the shared derivation, on the token's `:rf/time-ms`, so a replayed
        ;; wake classifies the entry as the recorded one did
        stale?     (rf.resources.state/entry-stale? entry time-ms)
        decision   (cond (nil? entry) :no-entry
                         stale?       :now-stale
                         :else        :still-fresh)]
    (rf.trace/emit! :rf.event :rf.resource/stale-fired
                 {:rf.frame/id frame-id :resource/key resource-key
                  :decision decision})
    (case decision
      ;; The marker restates `:stale-at`, so it bumps no `:revision`: a bump
      ;; would turn every optimistic mutation in flight across a deadline into
      ;; a false conflict. An entry stale only by `:invalidated-at` already
      ;; committed, so it writes nothing.
      :now-stale
      (if (and (some? stale-at) (>= time-ms stale-at)
               (not= stale-at (:stale-wake-at entry)))
        {:rf.db/runtime (assoc-in runtime-db path (assoc entry :stale-wake-at stale-at))}
        {:rf.db/runtime runtime-db})

      ;; `:stale` alone, and nothing for a deadline-free entry: a `:stale nil`
      ;; would cancel rather than skip
      :still-fresh
      (if-let [delay-ms (rf.resources.state/stale-wake-delay-ms entry time-ms)]
        {:rf.db/runtime runtime-db
         :fx [[:rf.resource/schedule-timers
               {:frame-id     frame-id
                :resource/key resource-key
                :timers       {:stale delay-ms}
                :server?      (rf.resources.state/server-frame? frame-id)}]]}
        {:rf.db/runtime runtime-db})

      :no-entry
      {:rf.db/runtime runtime-db})))

(defn gc-fired-handler
  "`:rf.resource.internal/gc-fired`: re-checks the live entry and removes it,
  with its ledger rows, only when it is owner-free, has no work in flight,
  and the check's `:gc-window` is current. GC has no durable deadline, so the
  window stands in for the spec's generation re-check: every event that arms
  GC opens a new window, and a check queued before a newer window opened (on
  this entry, or on one rewritten at the same key) never collects. A check
  with no window counts as current.

  Skips are `:no-entry`, `:has-owner`, `:superseded` and `:in-flight`. Only
  `:in-flight` re-arms, because a failed or aborted settle arms no GC; an
  owned entry's final release re-arms GC itself.
  Spec 016 §Stale and GC scheduling"
  [{rt :rf.db/runtime, frame-id :rf.frame/id}
   [_event-id {resource-key :resource/key gc-window :gc-window}]]
  (let [runtime-db  (or rt {})
        entry       (get-in runtime-db (rf.resources.state/entry-path resource-key))
        superseded? (and gc-window (< gc-window (:gc-window entry 0)))]
    (if (and entry (empty? (:active-owners entry)) (nil? (:current-work entry))
             (not superseded?))
      ;; by byte key-id: a vector dissoc would no-op and leak the entry
      (let [old-entries (get-in runtime-db (rf.resources.state/entries-path))
            rdb' (-> runtime-db
                     (update-in (rf.resources.state/entries-path) dissoc (rf.resources.state/key-id resource-key))
                     ;; Nothing is in flight here, so the whole ledger holding
                     ;; goes; a retained tail would outlive the collected entry.
                     ;; Spec 016 §Ledger row retention and identity
                     (rf.resources.work-ledger/drop-rows-for-key resource-key)
                     (update rf.resources.state/resources-key rf.resources.state/reindex-keys
                             old-entries [(rf.resources.state/key-id resource-key)]))]
        (rf.trace/emit! :rf.event :rf.resource/gc-fired
                     {:rf.frame/id frame-id :resource/key resource-key})
        {:rf.db/runtime rdb'
         ;; the paired stale timer may still be armed
         :fx [[:rf.resource/cancel-timers
               {:frame-id frame-id :resource/keys [resource-key]}]]})
      (let [reason   (cond (nil? entry)                  :no-entry
                           (seq (:active-owners entry))  :has-owner
                           superseded?                   :superseded
                           :else                         :in-flight)
            gc-delay (when (= :in-flight reason)
                       (rf.resources.state/positive-or-nil (:gc-after-ms (rf.resources.registry/resource-meta (:resource/id entry)))))]
        (rf.trace/emit! :rf.event :rf.resource/gc-skipped
                     {:rf.frame/id frame-id :resource/key resource-key
                      :reason reason
                      :rescheduled? (some? gc-delay)})
        (cond-> {:rf.db/runtime (open-gc-window runtime-db frame-id resource-key gc-delay)}
          gc-delay
          (assoc :fx [[:rf.resource/schedule-timers
                       {:frame-id     frame-id
                        :resource/key resource-key
                        ;; partial re-arm: `:gc` only
                        :timers       {:gc gc-delay}
                        :server?      (rf.resources.state/server-frame? frame-id)}]]))))))

(defn stale-suppressed-handler
  "`:rf.resource.internal/stale-suppressed`: records a suppressed late reply
  in trace for tools; the reply handlers already suppress inline.
  Spec 016 §Cancellation is opportunistic; stale suppression is mandatory"
  [{rt :rf.db/runtime, frame-id :rf.frame/id}
   [_event-id reply-payload]]
  (let [runtime-db (or rt {})
        {work-id :work/id resource-key :resource/key :keys [generation] :as payload}
        (resolve-reply-payload runtime-db frame-id reply-payload)
        stale      (stale-suppress-reply runtime-db resource-key payload nil)]
    (emit-resource-stale-suppressed!
      frame-id work-id generation nil stale)
    {:rf.db/runtime runtime-db}))
