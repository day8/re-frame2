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
  are the WORK-LEDGER SUBSTRATE slice (rf2-afpdkn) — landed here. GC
  scheduling / timers are the invalidation+GC slice. The HTTP request
  execution is the managed-HTTP slice (rf2-p19360); this slice LOWERS into
  the existing transport seam (`transport/lower-ensure`).

  ## Work-ledger substrate (rf2-afpdkn)

  Each load-causing attempt now also writes a SERIALIZABLE work record at
  `[:rf.runtime/work-ledger <work-id>]` (the entry points at it via
  `:current-work`; the record carries status / owners / causes / deadline /
  outcome — NO host handles). Host abort handles live in a side table keyed
  by `[frame-id work-id]` (`work-ledger/handle-table`), recorded via the
  `:rf.resource/record-work-handle` fx and dropped on terminate / frame
  destroy. ABORT IS OPPORTUNISTIC (best-effort `:rf.http/managed-abort` on
  supersession / owner-loss / scope-clear); STALE SUPPRESSION by work-id +
  generation is the MANDATORY correctness boundary (enforced on the entry by
  `live-entry-for-reply`). Terminal rows are pruned on the linked entry's
  next successful transition, retaining a bounded per-key tail for Xray."
  (:require [clojure.set :as set]
            [re-frame.frame :as frame]
            [re-frame.resources.registry :as registry]
            [re-frame.resources.route :as route]
            [re-frame.resources.state :as state]
            [re-frame.resources.transport :as transport]
            [re-frame.resources.work-ledger :as work-ledger]
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

(defn- stale-at-for
  "Compute `:stale-at` from `loaded-at` + the resource's `:stale-after-ms`
  policy, or nil when the resource declares no staleness policy (it never
  goes stale on a timer). Per Spec 016 §Stale and GC scheduling."
  [spec loaded-at]
  (when-let [ms (:stale-after-ms spec)]
    (+ loaded-at ms)))

(defn- positive-or-nil
  "Return `ms` when it is a positive number, else nil (a non-positive /
  absent policy never arms a timer). Guards a timer delay derived from an
  absolute timestamp comparison so a clock-skewed or already-elapsed deadline
  yields nil rather than a negative wall-clock delay."
  [ms]
  (when (and (number? ms) (pos? ms)) ms))

(defn- server-frame?
  "True iff `frame-id` is an SSR / server frame (its `:config :platform` is
  `:server`, set by the `:ssr-server` preset). Reads ONLY the FRAME's
  platform — NOT the host-wide `active-platform` default (which is `:server`
  on the JVM, so a JVM client-mode unit test must still arm timers). Per Spec
  016 §Stale and GC scheduling (no wall-clock background timers under SSR)."
  [frame-id]
  (= :server (:platform (frame/frame-meta frame-id))))

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

  `force-new?` false (ensure) ALSO short-circuits a FRESH-SKIP: an
  `ensure` of an already-`:loaded` entry that is still fresh-by-policy
  (`state/entry-stale?` false against `now-ms`) neither dedupes (no
  in-flight work to join) nor starts a fetch — it serves the cached value,
  attaches the supplied owner lease, emits `:rf.resource/cache-hit`, and
  (for a route-owned blocking resource) drains the blocking slot
  immediately treating the fresh entry as already-`:success`. Per Spec 016
  §Lifecycle is an FSM (a `:loaded` entry transitions to `:fetching` ONLY
  on `stale/refetch`; a fresh `ensure` has no transition) / §Restore and
  replay (a settled entry \"refetches only on the next `ensure` from a live
  owner … gated by the entry's own stale/fresh policy\"). The in-flight
  dedupe still WINS when work is in flight; fresh-skip applies only to a
  SETTLED fresh `:loaded` entry.

  Returns the event-fx map `{:rf.db/runtime :fx}`."
  [{rt :rf.db/runtime, frame-id :rf.frame/id, gen-snapshot :rf.resource/generation}
   {:keys [resource params owner cause keep-previous?] :as payload} {:keys [force-new? where]}]
  (let [runtime-db (or rt {})
        spec       (registry/require-resource-spec! resource where)
        scope      (registry/resolve-scope-for-event
                     resource spec {:payload-scope (:scope payload)} where)
        cparams    (registry/validate+canonicalize-params resource spec params where)
        scoped-key (state/scoped-resource-key scope resource cparams)
        entry      (or (get-in runtime-db (state/entry-path scoped-key))
                       (state/empty-entry resource))
        prior-work (:current-work entry)
        in-flight? (some? prior-work)
        ;; FRESH-SKIP gate (Spec 016 §Lifecycle is an FSM / §Restore and
        ;; replay): an `ensure` (never a `refetch`) of an already-`:loaded`
        ;; entry that is NOT in flight and is still fresh-by-policy serves
        ;; the cached value — no new generation, no fetch, no work record.
        ;; The in-flight dedupe takes precedence (a fresh-but-in-flight
        ;; entry can't exist — `:fetching`/`:loading` is the in-flight
        ;; status — but the explicit `(not in-flight?)` guard keeps the two
        ;; branches disjoint and order-independent).
        fresh-skip? (and (not force-new?)
                         (not in-flight?)
                         (= :loaded (:status entry))
                         (not (state/entry-stale? entry (now-ms))))
        ;; a NEW owner lease lands on the entry when an owner is supplied and
        ;; was not already in the active-owner set. `:rf.resource/owner-attached`
        ;; marks that liveness change distinctly from work — symmetric with the
        ;; existing `:rf.resource/owner-released` row so the owner-lease lifecycle
        ;; is a readable pair in the Xray timeline / AI-Audit (Spec 016 §Xray and
        ;; AI tooling; §Active owners and causes — owners are liveness leases).
        owner-newly-attached? (and (some? owner)
                                   (not (contains? (:active-owners entry) owner)))
        ;; default the transport (a spec that declares none gets managed
        ;; HTTP — the only initial-scope transport; the transport seam
        ;; defaults identically). The work record + side-table handle +
        ;; opportunistic-abort fx all key off the concrete transport id.
        transport-id (or (:transport spec) transport/default-transport)
        ;; `:keep-previous?` (Spec 016 §Paginated and previous data): when a
        ;; new page/filter key FIRST-loads (no usable data of its own), record
        ;; a PROJECTION POINTER to the prior loaded sibling key so the sub
        ;; layer can show old data while the new key loads — WITHOUT inserting
        ;; that data into this entry or borrowing its tags. Only on a genuine
        ;; first load (an entry that already has data needs no placeholder).
        prev-key   (when (and keep-previous? (not (state/has-data? entry)))
                     (state/prior-loaded-sibling-key
                       (get-in runtime-db (state/entries-path)) scoped-key))]
    (cond
      ;; ----- fresh-skip: serve the cached value (ensure only) -------------
      ;; A fresh `:loaded` entry needs no work — attach the owner lease,
      ;; emit `:rf.resource/cache-hit`, drain any blocking route slot
      ;; immediately (the fresh entry IS already a success), and return
      ;; WITHOUT a new generation / fetch / work record. Per Spec 016
      ;; §Lifecycle is an FSM (a fresh `ensure` from `:loaded` has no
      ;; transition) / §Restore and replay. No `:previous-key` projection is
      ;; needed (the entry has its own fresh data); arms no timers; supersedes
      ;; nothing.
      fresh-skip?
      (let [hit  (cond-> entry
                   owner (update :active-owners (fnil conj #{}) owner))
            rdb' (-> runtime-db
                     (assoc-in (state/entry-path scoped-key) hit)
                     (cond->
                       owner (update-in (state/owner-index-path)
                                        update owner (fnil conj #{}) scoped-key))
                     ;; route blocking: a route-owned blocking resource that
                     ;; is already fresh MUST settle the nav-token blocking
                     ;; slot NOW (no fetch will ever land a reply to drain
                     ;; it) — treat the fresh entry as already-`:success` or
                     ;; the route hangs forever (Spec 016 §Route integration).
                     ;; No-op for a non-route-owned / non-blocking resource.
                     (route/drain-blocking scoped-key hit :success))]
        (trace/emit! :rf.event :rf.resource/cache-hit
                     {:rf.frame/id frame-id :resource-key scoped-key
                      :generation (:generation entry) :owner owner :cause cause})
        ;; the cache-hit attached a new owner lease — record that distinct
        ;; liveness change (symmetric with the dedupe / fresh-load paths).
        (when owner-newly-attached?
          (trace/emit! :rf.event :rf.resource/owner-attached
                       {:rf.frame/id frame-id :resource-key scoped-key
                        :generation (:generation entry) :owner owner :cause cause
                        :work-id nil :joined-in-flight? false}))
        {:rf.db/runtime rdb'})
      ;; ----- dedupe: join the in-flight request (ensure only) -------------
      ;; Attach any supplied owner to the existing entry + record the cause;
      ;; do NOT start a new generation. Join the SAME work-ledger record
      ;; (attach owner / append cause). Per Spec 016 §Race (ensure while in
      ;; flight joins the existing current work record).
      (and in-flight? (not force-new?))
      (let [joined (cond-> entry
                     owner (update :active-owners (fnil conj #{}) owner))
            rdb'   (-> runtime-db
                       (assoc-in (state/entry-path scoped-key) joined)
                       (work-ledger/update-record
                         prior-work work-ledger/join-owner+cause owner cause)
                       (cond->
                         owner (update-in (state/owner-index-path)
                                          update owner (fnil conj #{}) scoped-key)))]
        (trace/emit! :rf.event :rf.resource/deduped
                     {:rf.frame/id frame-id :resource-key scoped-key
                      :generation (:generation entry) :owner owner :cause cause
                      :work-id prior-work})
        ;; the ensure joined the in-flight work (no new generation) but ALSO
        ;; attached a new owner lease — record that distinct liveness change.
        (when owner-newly-attached?
          (trace/emit! :rf.event :rf.resource/owner-attached
                       {:rf.frame/id frame-id :resource-key scoped-key
                        :generation (:generation entry) :owner owner :cause cause
                        :work-id prior-work :joined-in-flight? true}))
        {:rf.db/runtime rdb'})
      ;; ----- start a new load attempt (fresh generation) -----------------
      :else
      (let [generation (state/next-generation gen-snapshot)
            work-id    (work-ledger/resource-work-id scoped-key generation)
            request-id work-id
            now        (now-ms)
            deadline   (when-let [ms (:timeout-ms spec)] (+ now ms))
            entry'     (cond-> (state/entry-start-load
                                 entry {:generation generation :work-id work-id
                                        :request-id request-id :owner owner})
                         ;; `:keep-previous?` projection pointer (a pointer
                         ;; only — never this key's data / tags).
                         prev-key (assoc :previous-key prev-key))
            ;; a forced refetch over an in-flight prior attempt SUPERSEDES it:
            ;; mark the old work record :superseded (terminal) and emit a
            ;; best-effort abort (opportunistic; stale suppression already
            ;; protects the late reply by work-id + generation). Per Spec 016
            ;; §Race (refetch may force a new generation).
            superseding? (and in-flight? force-new?)
            record     (work-ledger/work-record
                         {:work-id      work-id
                          :frame-id     frame-id
                          :resource-key scoped-key
                          :generation   generation
                          :transport    transport-id
                          :owner        owner
                          :cause        cause
                          :started-at   now
                          :deadline-at  deadline})
            rdb'       (-> runtime-db
                           (assoc-in (state/entry-path scoped-key) entry')
                           (cond->
                             superseding?
                             (work-ledger/update-record
                               prior-work work-ledger/mark-terminal
                               :suppressed {:reason :superseded :by work-id}))
                           (work-ledger/put-record work-id record)
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
                         transport-id
                         {:http-args    http-args
                          :request-id   request-id
                          :work-id      work-id
                          :resource-key scoped-key
                          :scope        scope
                          :frame-id     frame-id
                          :generation   generation
                          :where        where})]
        (trace/emit! :rf.event :rf.resource/work-started
                     {:rf.frame/id frame-id :resource-key scoped-key
                      :generation generation :work-id work-id
                      :status :running :owner owner :cause cause
                      :superseded (when superseding? prior-work)})
        (trace/emit! :rf.event :rf.resource/fetch-started
                     {:rf.frame/id frame-id :resource-key scoped-key
                      :generation generation :work-id work-id
                      :status (:status entry') :owner owner :cause cause})
        ;; a fresh load that also attaches a NEW owner lease — record the
        ;; liveness change distinctly from the work it kicked off (symmetric
        ;; with `:rf.resource/owner-released`).
        (when owner-newly-attached?
          (trace/emit! :rf.event :rf.resource/owner-attached
                       {:rf.frame/id frame-id :resource-key scoped-key
                        :generation generation :owner owner :cause cause
                        :work-id work-id :joined-in-flight? false}))
        {:rf.db/runtime rdb'
         ;; WRITE half of the host-side generation seam + the transport fx +
         ;; the work-handle side-table record + (when superseding) a
         ;; best-effort abort of the prior in-flight attempt (drop its
         ;; side-table handle + a transport abort — opportunistic).
         :fx (cond-> [[:rf.resource/commit-generation {:value generation}]
                      [:rf.resource/record-work-handle
                       {:frame-id frame-id :work-id work-id
                        :transport transport-id :request-id request-id}]
                      lower-fx]
               superseding?
               (-> (conj [:rf.resource/clear-work-handle
                          {:frame-id frame-id :work-id prior-work}])
                   (cond-> (work-ledger/abort-fx transport-id prior-work)
                     (conj (work-ledger/abort-fx transport-id prior-work)))))}))))

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

;; ---- focus / reconnect revalidation (rf2-vtblcq) --------------------------
;;
;; The first public-beta gate item (Spec 016 §Stale and GC scheduling: "on
;; focus or reconnect, the first public-beta revalidation slice scans active
;; stale entries and refetches by event"; §Deferred slices:
;; `:rf.resource/window-focused` / `:rf.resource/network-reconnected`
;; expressed as resource EVENTS, NOT subscription-driven fetching).
;;
;; The host focus / online listeners (`re-frame.resources.revalidate-listeners`,
;; CLJS-only, registered per-frame, cancelled on frame-destroy via the existing
;; `:resources/on-frame-destroyed!` hook) dispatch these events; the algorithm
;; reuses the landed v1 primitives wholesale:
;;
;;   - ACTIVE OWNERS decide WHICH entries are worth refetching — only an entry
;;     with a live lease (`:active-owners` non-empty) is scanned (an inactive
;;     entry is GC fodder, not a revalidation target);
;;   - DURABLE stale/fresh timestamps decide WHETHER — `state/entry-stale?`
;;     (the single shared freshness derivation the subs / SSR / stale-timer
;;     re-check all use) against the live clock; a fresh entry is LEFT ALONE;
;;   - GENERATION CHECKS suppress stale replies — the refetch lowers through
;;     the ordinary `ensure-load` path (force-new generation), so a
;;     focus-triggered refetch is just another CAUSE; it attaches NO owner, so
;;     it NEVER creates liveness (Spec 016 §Active owners and causes: `:focus`
;;     / `:reconnect` are causes, not owners), and the work-id + generation
;;     stale-suppression boundary protects late replies exactly as for any
;;     refetch;
;;   - the TRANSPORT adapter owns retry / abort (unchanged);
;;   - TRACE rows explain the decision (one scan summary + the per-entry
;;     refetch decisions ride the ordinary refetch traces).
;;
;; Background, NON-blocking: the scan dispatches `:rf.resource/refetch` per
;; eligible entry (each a normal background refresh — prior data stays visible
;; in `:fetching`), never blocking a route transition or the UI.

(def focus-cause
  "The revalidation cause recorded on a window-focus-triggered refetch
  (`:focus`). A CAUSE, never an owner — it explains why the work happened
  without changing liveness / GC / polling (Spec 016 §Active owners and
  causes). User code MUST NOT dispatch the focus event directly; the host
  focus listener does."
  :focus)

(def reconnect-cause
  "The revalidation cause recorded on a network-reconnect-triggered refetch
  (`:reconnect`). A CAUSE, never an owner (Spec 016 §Active owners and
  causes)."
  :reconnect)

(defn- active-stale-scan
  "Pure scan: given the frame's `runtime-db` value and the live `clock-ms`,
  return the vector of `{:resource-key :scope :resource :params}` for every
  cache entry that is BOTH active (has at least one `:active-owner` — a live
  lease worth refetching) AND stale-by-policy (`state/entry-stale?` against
  the durable timestamps). Fresh entries and owner-free entries are excluded.
  Per Spec 016 §Stale and GC scheduling / §Deferred slices (focus/reconnect
  active-stale scan). A pure selection — it never mutates; the caller turns
  the selection into background `:rf.resource/refetch` dispatches."
  [runtime-db clock-ms]
  (let [entries (get-in runtime-db (state/entries-path))]
    (into []
          (keep (fn [[scoped-key entry]]
                  (when (and (seq (:active-owners entry))
                             (state/entry-stale? entry clock-ms))
                    (let [[scope resource-id params] scoped-key]
                      {:resource-key scoped-key
                       :scope        scope
                       :resource     resource-id
                       :params       params}))))
          entries)))

(defn- revalidate-handler
  "Shared focus / reconnect active-stale revalidation core (Spec 016
  §Stale and GC scheduling / §Deferred slices). Scans the frame's
  active-owner entries that are stale-by-policy and dispatches a background
  `:rf.resource/refetch` per eligible entry, carrying `cause` (`:focus` /
  `:reconnect`) — a CAUSE, never an owner (the refetch attaches no owner, so
  it never creates liveness; generation + stale-suppression protect late
  replies exactly as for any refetch). Fresh entries and owner-free entries
  are left untouched. Emits one `:rf.resource/refetch-decision` scan-summary
  trace (the broad-tab-return storm stays readable) plus the per-entry
  refetch ride their ordinary refetch traces. Returns the event-fx map
  (`:fx` only — the scan itself makes NO durable write; the refetch
  dispatches do).

  `signal` is the revalidation op (`:rf.resource/window-focused` /
  `:rf.resource/network-reconnected`) for the trace; `cause` is the cause
  keyword recorded on each refetch."
  [{rt :rf.db/runtime, frame-id :rf.frame/id} signal cause]
  (let [runtime-db (or rt {})
        eligible   (active-stale-scan runtime-db (now-ms))
        refetches  (mapv (fn [{:keys [resource scope params]}]
                           [:dispatch [:rf.resource/refetch
                                       {:resource resource :scope scope
                                        :params   params :cause cause}]])
                         eligible)]
    ;; ONE scan summary (a tab-return / reconnect can touch many entries — keep
    ;; the trace readable). The per-entry refetch decisions ride the ordinary
    ;; `:rf.resource/work-started` / `fetch-started` traces each refetch emits.
    (trace/emit! :rf.event :rf.resource/revalidate-scan
                 {:rf.frame/id frame-id :signal signal :cause cause
                  :scanned    (count (get-in runtime-db (state/entries-path)))
                  :refetched  (count eligible)
                  :keys       (mapv :resource-key eligible)})
    {:fx refetches}))

(defn window-focused-handler
  "`:rf.resource/window-focused` — the window-focus revalidation signal
  (Spec 016 §Deferred slices: focus/reconnect revalidation as resource
  EVENTS, not subscription-driven fetching). Scans the frame's active-owner
  stale entries and refetches them in the background with cause `:focus`
  (a cause, never an owner). Dispatched by the host focus / visibilitychange
  listener (`re-frame.resources.revalidate-listeners`); user code MUST NOT
  dispatch it directly. Per Spec 016 §Stale and GC scheduling."
  [cofx [_event-id]]
  (revalidate-handler cofx :rf.resource/window-focused focus-cause))

(defn network-reconnected-handler
  "`:rf.resource/network-reconnected` — the network-reconnect revalidation
  signal (Spec 016 §Deferred slices). Scans the frame's active-owner stale
  entries and refetches them in the background with cause `:reconnect` (a
  cause, never an owner). Dispatched by the host `online` listener
  (`re-frame.resources.revalidate-listeners`); user code MUST NOT dispatch
  it directly. Per Spec 016 §Stale and GC scheduling."
  [cofx [_event-id]]
  (revalidate-handler cofx :rf.resource/network-reconnected reconnect-cause))

;; ---- invalidate-tags — exact tag invalidation -----------------------------

(defn invalidate-tags-handler
  "`:rf.resource/invalidate-tags` — exact tag invalidation (Spec 016
  §Invalidation). Payload: `{:scope :tags :cause :cross-scope?}`.

  Algorithm (Spec 016 §Invalidation):
    1. find entries whose produced `:tags` intersect the invalidated `:tags`;
    2. mark each matched entry stale (durable `:invalidated-at` fact);
    3. refetch the matched entries that have ACTIVE OWNERS (a live lease
       needs fresh data now) — by dispatching `:rf.resource/refetch`;
    4. leave the matched OWNERLESS entries stale / GC-eligible (their stale?
       sub derives true from `:invalidated-at`; their GC timer reaps them);
    5. emit ONE decision summary + per-entry detail (so Xray shows a
       broad-tag storm without flooding the trace).

  **Scoped by DEFAULT** (Spec 016 §clear-scope is causal / §Invalidation): a
  match requires the entry's scope to equal `:scope`. A CROSS-SCOPE
  invalidation (`:cross-scope? true`) opts in explicitly — it ignores the
  scope filter (matching the tags in every scope) and is loudly Xray-visible
  (the summary carries `:cross-scope? true` so a broad multi-tenant /
  multi-user storm is observable + lintable). A cross-scope invalidation with
  no `:scope` is permitted (it is scope-agnostic by construction).

  **No-match distinction** (Spec 016 §Invalidation): the summary carries
  `:matched` (the matched keys), `:any-tag-match-other-scope?` (whether the
  tags match an entry in ANOTHER scope — \"no match HERE\" vs \"no resource
  provides this tag in any scope\"), so Xray can tell the two apart."
  [{rt :rf.db/runtime, frame-id :rf.frame/id}
   [_event-id {:keys [scope tags cause cross-scope?]}]]
  (let [runtime-db (or rt {})
        cscope     (when (some? scope) (state/canonicalize scope))
        tag-set    (set tags)
        invalidated-at (now-ms)
        entries    (get-in runtime-db (state/entries-path))
        tags-hit?  (fn [entry] (seq (set/intersection (set (:tags entry)) tag-set)))
        in-scope?  (fn [k] (or cross-scope? (= cscope (first k))))
        ;; matched: tags intersect AND (cross-scope OR scope matches)
        matched    (into {}
                         (filter (fn [[k entry]] (and (in-scope? k) (tags-hit? entry))))
                         entries)
        ;; tag matches that fell OUTSIDE the requested scope (the
        ;; "no match HERE, but the tag exists in another scope" signal — only
        ;; meaningful for a scoped invalidation)
        other-scope-hit?
        (and (not cross-scope?)
             (boolean
               (some (fn [[k entry]] (and (not= cscope (first k)) (tags-hit? entry)))
                     entries)))
        ;; mark each matched entry stale (durable :invalidated-at fact)
        rdb'       (reduce-kv
                     (fn [db k entry]
                       (assoc-in db (state/entry-path k)
                                 (assoc entry :invalidated-at invalidated-at)))
                     runtime-db matched)
        ;; per-entry decision: active-owner entries refetch (Spec 016
        ;; §Invalidation 3); ownerless entries are left stale / GC-eligible
        ;; (§Invalidation 4). Collected once for both the dispatches and the
        ;; per-entry trace detail.
        decisions  (mapv (fn [[k entry]]
                           {:resource-key k
                            :active? (boolean (seq (:active-owners entry)))
                            :decision (if (seq (:active-owners entry))
                                        :refetch :left-stale)
                            :tags (vec (:tags entry))})
                         matched)
        refetches  (into []
                         (comp
                           (filter :active?)
                           (map (fn [{k :resource-key}]
                                  (let [[s rid p] k]
                                    [:dispatch [:rf.resource/refetch
                                                {:resource rid :scope s :params p
                                                 :cause [:invalidate {:tags tags}]}]]))))
                         decisions)]
    ;; ONE decision summary (broad-tag storms stay readable) ...
    (trace/emit! :rf.event :rf.resource/invalidated
                 {:rf.frame/id frame-id :scope cscope :tags tags :cause cause
                  :cross-scope? (boolean cross-scope?)
                  :matched (mapv :resource-key decisions)
                  :refetched (count refetches)
                  :left-stale (count (remove :active? decisions))
                  ;; no-match distinction: no match in this scope vs no resource
                  ;; provides this tag in any scope (Spec 016 §Invalidation)
                  :any-tag-match-other-scope? other-scope-hit?})
    ;; ... PLUS one per-entry detail trace (the refetch-vs-leave-stale
    ;; decision per matched key — Spec 016 §Invalidation 5 / the Xray
    ;; invalidation graph)
    (doseq [{:keys [resource-key active? decision tags] :as _d} decisions]
      (trace/emit! :rf.event :rf.resource/refetch-decision
                   {:rf.frame/id frame-id :resource-key resource-key
                    :scope (first resource-key) :active? active?
                    :decision decision :tags tags :cause cause}))
    {:rf.db/runtime rdb'
     :fx refetches}))

;; ---- release-owner --------------------------------------------------------

(defn release-owner-handler
  "`:rf.resource/release-owner` — release a liveness lease (drop the owner
  from every entry's `:active-owners` + the owner-index). Per Spec 016
  §Active owners and causes. Payload: `{:owner …}`.

  Per Spec 016 §Race (owner release while a request is in flight aborts
  ONLY when no remaining owner needs that work record — a shared request is
  NOT cancelled just because one route / machine / lease went away). This
  drops the owner from the durable entry + index AND from the linked work
  record's `:owners`; for any in-flight attempt whose `:owners` are now
  EMPTY it emits a best-effort `:rf.http/managed-abort` (opportunistic) and
  marks the work row `:abort-requested`. Stale suppression by work-id +
  generation remains the correctness boundary — the abort is an
  optimisation, not relied on."
  [{rt :rf.db/runtime, frame-id :rf.frame/id} [_event-id {:keys [owner]}]]
  (let [runtime-db (or rt {})
        owned      (get-in runtime-db (conj (state/owner-index-path) owner))
        ;; rf2-l2gofj: releasing a ROUTE owner ([:route route-id nav-token])
        ;; happens on every route leave / supersession (route-resource-plan
        ;; dispatches it). Deterministically clear that nav-token's blocking
        ;; slot here so a superseded token's blocking state cannot accumulate
        ;; — reply-driven drain misses it (the owner is already gone from the
        ;; entries, and an orphaned/aborted in-flight resource never replies).
        rdb0       (if (and (vector? owner) (= :route (first owner)))
                     (route/clear-blocking-slot runtime-db (nth owner 2))
                     runtime-db)
        ;; drop the owner from each owned entry + the index
        rdb1       (-> (reduce
                         (fn [db k]
                           (update-in db (state/entry-path k)
                                      (fn [e] (when e (update e :active-owners disj owner)))))
                         rdb0 (or owned #{}))
                       (update-in (state/owner-index-path) dissoc owner))
        ;; for each owned entry that is still in flight, drop the owner from
        ;; the work record; collect the work ids whose owners are now empty
        ;; (orphaned in-flight attempts → opportunistic abort).
        {rdb2 :rdb aborts :aborts}
        (reduce
          (fn [acc k]
            (let [db   (:rdb acc)
                  e    (get-in db (state/entry-path k))
                  wid  (:current-work e)
                  rec  (when wid (work-ledger/get-record db wid))]
              (if (and wid rec)
                (let [rec' (work-ledger/release-owner-from-record rec owner)
                      orphaned? (empty? (:owners rec'))
                      rec'' (if orphaned? (work-ledger/mark-abort-requested rec') rec')]
                  {:rdb (work-ledger/put-record db wid rec'')
                   :aborts (cond-> (:aborts acc)
                             orphaned? (conj [wid (:transport rec'')]))})
                acc)))
          {:rdb rdb1 :aborts []}
          (or owned #{}))]
    (trace/emit! :rf.event :rf.resource/owner-released
                 {:rf.frame/id frame-id :owner owner :released (vec (or owned #{}))
                  :aborted (mapv first aborts)})
    {:rf.db/runtime rdb2
     :fx (into [] (keep (fn [[wid transport]]
                          (work-ledger/abort-fx transport wid)))
               aborts)}))

;; ---- clear-scope — the causal logout / tenant-switch boundary --------------

(defn clear-scope-handler
  "`:rf.resource/clear-scope` — causal scope clear (Spec 016 §clear-scope
  is causal). Removes every entry in the scope, releases its owners from
  the owner-index, marks each in-scope in-flight work record terminal
  `:cancelled`, best-effort aborts those attempts (opportunistic), and
  emits an explaining trace. Stale suppression by work-id + generation
  remains the correctness boundary — the entry a late reply would write
  into is gone, so the reply handler's existence check suppresses it; the
  abort is the optimisation. Payload: `{:scope :cause}`."
  [{rt :rf.db/runtime, frame-id :rf.frame/id} [_event-id {:keys [scope cause]}]]
  (let [runtime-db (or rt {})
        cscope     (state/canonicalize scope)
        entries    (get-in runtime-db (state/entries-path))
        in-scope   (into #{} (comp (filter (fn [[k _]] (= cscope (first k))))
                                   (map key))
                         entries)
        ;; collect the in-flight work ids for the cleared entries (best-effort
        ;; abort + terminal :cancelled work rows)
        in-flight  (into []
                         (keep (fn [k]
                                 (let [e (get entries k)
                                       wid (:current-work e)]
                                   (when wid
                                     [wid (:transport (work-ledger/get-record runtime-db wid))]))))
                         in-scope)
        ;; remove the entries, settle their in-flight work rows :cancelled,
        ;; then recompute the indexes from what remains
        rdb'       (-> runtime-db
                       (update-in (state/entries-path)
                                  (fn [es] (reduce dissoc es in-scope)))
                       (as-> db (reduce (fn [d [wid _]]
                                          (work-ledger/update-record
                                            d wid work-ledger/mark-terminal
                                            :cancelled {:reason :clear-scope}))
                                        db in-flight))
                       (update state/resources-key state/recompute-indexes))]
    (trace/emit! :rf.event :rf.resource/removed
                 {:rf.frame/id frame-id :scope cscope :cause cause
                  :removed (vec in-scope) :reason :clear-scope
                  :aborted (mapv first in-flight)})
    {:rf.db/runtime rdb'
     ;; best-effort abort of each in-scope in-flight attempt PLUS cancel the
     ;; cleared entries' advisory stale / GC timers (their durable facts are
     ;; gone — release the host handles promptly rather than waiting for frame
     ;; destroy). Stale suppression by work-id + generation remains the
     ;; correctness boundary; the abort + timer-cancel are the optimisation.
     :fx (cond-> (into [] (keep (fn [[wid transport]] (work-ledger/abort-fx transport wid)))
                       in-flight)
           (seq in-scope)
           (conj [:rf.resource/cancel-timers
                  {:frame-id frame-id :resource-keys (vec in-scope)}]))}))

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
        entry      (get-in runtime-db (state/entry-path scoped-key))
        wid        (:current-work entry)
        transport  (when wid (:transport (work-ledger/get-record runtime-db wid)))
        rdb'       (-> runtime-db
                       (update-in (state/entries-path) dissoc scoped-key)
                       (cond-> wid (work-ledger/update-record
                                     wid work-ledger/mark-terminal
                                     :cancelled {:reason :remove}))
                       (update state/resources-key state/recompute-indexes))]
    (trace/emit! :rf.event :rf.resource/removed
                 {:rf.frame/id frame-id :resource-key scoped-key :reason :remove
                  :aborted (when wid [wid])})
    {:rf.db/runtime rdb'
     ;; best-effort abort of the removed instance's in-flight attempt
     ;; (opportunistic; stale suppression protects correctness) PLUS cancel
     ;; its advisory stale / GC timers (the entry's durable facts are gone).
     :fx (conj (if-let [fx (and wid (work-ledger/abort-fx transport wid))] [fx] [])
               [:rf.resource/cancel-timers
                {:frame-id frame-id :resource-keys [scoped-key]}])}))

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
      ;; STALE SUPPRESSION (mandatory): a superseded / vanished reply never
      ;; mutates a newer entry. Settle its (already-superseded) work row to a
      ;; terminal :suppressed outcome if it still exists, and clear the host
      ;; handle. Per Spec 016 §Cancellation is opportunistic; stale
      ;; suppression is mandatory.
      (do (trace/emit! :rf.event :rf.resource/stale-suppressed
                       {:rf.frame/id frame-id :resource-key resource-key
                        :work-id work-id :generation generation :outcome :success})
          (work-ledger/clear-handle! frame-id work-id)
          {:rf.db/runtime (work-ledger/update-record
                            runtime-db work-id work-ledger/mark-terminal
                            :suppressed {:reason :stale-reply :outcome :success})})
      (let [spec      (registry/resource-meta (:resource/id entry))
            loaded-at (now-ms)
            stale-at  (stale-at-for spec loaded-at)
            ;; arm the advisory stale / GC timers from the resource's policy
            ;; (Spec 016 §Stale and GC scheduling). The DELAYS are relative
            ;; from now (the durable absolute :stale-at / :loaded-at remain the
            ;; freshness facts the re-check derives against; the timer is only
            ;; an advisory nudge). A resource declaring no :stale-after-ms /
            ;; :gc-after-ms arms neither. nil when this resource arms no timers
            ;; (no schedule-timers fx emitted).
            stale-delay-ms (positive-or-nil (:stale-after-ms spec))
            gc-delay-ms    (positive-or-nil (:gc-after-ms spec))
            tags-fn   (:tags spec)
            ;; tags are produced from the params + decoded data; the canonical
            ;; params are the third element of the scoped key
            tags      (when tags-fn (set (tags-fn (nth resource-key 2) data)))
            entry'    (state/entry-succeeded
                        entry {:data data :loaded-at loaded-at
                               :stale-at stale-at :tags tags})
            ;; on a successful load the tag index for this key is REPLACED
            ;; with the new tags (old tags removed); recompute is the simple,
            ;; correct way to keep both indexes consistent. The work row
            ;; settles :completed; terminal rows for this key are then PRUNED
            ;; (bounded per-key tail kept for Xray) — Spec 016 §Ledger row
            ;; retention. The host handle is cleared (the attempt settled).
            rdb'      (-> runtime-db
                          (assoc-in (state/entry-path resource-key) entry')
                          (work-ledger/update-record
                            work-id work-ledger/mark-terminal
                            :completed {:loaded-at loaded-at})
                          (work-ledger/prune-terminal-for-key resource-key)
                          (update state/resources-key state/recompute-indexes)
                          ;; route blocking: a route-owned blocking resource
                          ;; settling drops it from the nav-token blocking
                          ;; slot + lands the route transition when the slot
                          ;; empties (Spec 016 §Route integration). No-op for
                          ;; a non-route-owned / non-blocking resource.
                          (route/drain-blocking resource-key entry' :success))]
        (work-ledger/clear-handle! frame-id work-id)
        (trace/emit! :rf.event :rf.resource/work-completed
                     {:rf.frame/id frame-id :resource-key resource-key
                      :work-id work-id :generation generation :status :completed})
        (trace/emit! :rf.event :rf.resource/succeeded
                     {:rf.frame/id frame-id :resource-key resource-key
                      :work-id work-id :generation generation
                      :status-before (:status entry) :status-after :loaded})
        ;; arm the advisory stale / GC timers (host-side side table) for this
        ;; freshly-loaded entry — the WRITE rides an fx exactly as the
        ;; generation high-water bump + work-handle side-table writes do.
        ;; Cancel-then-arm so a re-load reschedules a single live timer per
        ;; [key kind]. Skipped under SSR by the fx's `:platforms #{:client}`
        ;; gate (the re-check handler re-derives freshness from the durable
        ;; :stale-at, so a never-fired server timer is harmless). Only emitted
        ;; when the resource declares at least one policy. Per Spec 016 §Stale
        ;; and GC scheduling.
        (cond-> {:rf.db/runtime rdb'}
          (or stale-delay-ms gc-delay-ms)
          (assoc :fx [[:rf.resource/schedule-timers
                       {:frame-id       frame-id
                        :resource-key   resource-key
                        :stale-delay-ms stale-delay-ms
                        :gc-delay-ms    gc-delay-ms
                        :server?        (server-frame? frame-id)}]]))))))

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
      ;; STALE SUPPRESSION (mandatory): a superseded / vanished failure
      ;; reply never mutates a newer entry. Settle its work row terminal +
      ;; clear the handle.
      (do (trace/emit! :rf.event :rf.resource/stale-suppressed
                       {:rf.frame/id frame-id :resource-key resource-key
                        :work-id work-id :generation generation :outcome :failure})
          (work-ledger/clear-handle! frame-id work-id)
          {:rf.db/runtime (work-ledger/update-record
                            runtime-db work-id work-ledger/mark-terminal
                            :suppressed {:reason :stale-reply :outcome :failure})})
      (let [entry' (state/entry-failed entry {:error error})
            op     (if (= :error (:status entry'))
                     :rf.resource/failed :rf.resource/refresh-failed)
            ;; the work row settles :failed (terminal) with the error
            ;; envelope as its outcome summary (Xray gets the summary). The
            ;; host handle is cleared (the attempt settled).
            rdb'   (-> runtime-db
                       (assoc-in (state/entry-path resource-key) entry')
                       (work-ledger/update-record
                         work-id work-ledger/mark-terminal
                         :failed {:error error})
                       ;; route blocking: a blocking FIRST-load failure flips
                       ;; the route transition to :error + populates
                       ;; :rf.route/error; a background-refresh failure (data
                       ;; kept, status back to :loaded) settles the slot like
                       ;; a success. drain-blocking keys on entry' status.
                       ;; (Spec 016 §Route integration.)
                       (route/drain-blocking resource-key entry' :failure))]
        (work-ledger/clear-handle! frame-id work-id)
        (trace/emit! :rf.event :rf.resource/work-completed
                     {:rf.frame/id frame-id :resource-key resource-key
                      :work-id work-id :generation generation :status :failed})
        (trace/emit! :rf.event op
                     {:rf.frame/id frame-id :resource-key resource-key
                      :work-id work-id :generation generation
                      :status-before (:status entry) :status-after (:status entry')})
        {:rf.db/runtime rdb'}))))

(defn aborted-handler
  "`:rf.resource.internal/aborted` — a transport read was aborted. For the
  cache ENTRY this is a stale reply — the verification gate suppresses it
  (the entry settles to its last stable status through its own subsequent
  transitions, never left stranded), so this handler makes NO durable entry
  write. For the WORK LEDGER it settles the work row terminal `:cancelled`
  and clears the host handle. Per Spec 016 §Cancellation is opportunistic;
  stale suppression is mandatory / §Ledger row retention and identity."
  [{rt :rf.db/runtime, frame-id :rf.frame/id}
   [_event-id {:keys [resource-key work-id generation]}]]
  (let [runtime-db (or rt {})]
    (work-ledger/clear-handle! frame-id work-id)
    (trace/emit! :rf.event :rf.resource/work-abort-requested
                 {:rf.frame/id frame-id :resource-key resource-key
                  :work-id work-id :generation generation})
    {:rf.db/runtime (work-ledger/update-record
                      runtime-db work-id work-ledger/mark-terminal
                      :cancelled {:reason :aborted})}))

(defn stale-fired-handler
  "`:rf.resource.internal/stale-fired` — a stale timer fired. The timer is
  ADVISORY: freshness is computed from the entry's DURABLE `:stale-at`
  (the `:rf.resource/stale?` sub already derives it against the live clock),
  so this handler does NOT need to flip a stored boolean — it RE-CHECKS the
  live entry and records the freshness fact for tools, never writing a stale
  decision. Per Spec 016 §Stale and GC scheduling (\"a stale timer may
  enqueue a resource event, but the handler MUST re-check the current entry
  before writing\").

  The re-check (against the LIVE durable facts, not the timer's wake-time
  assumptions):
    - the entry is gone (removed / GC'd / cleared) — no-op (a superseded
      timer);
    - the entry has been re-loaded to a NEWER generation since the timer
      armed (its `:loaded-at` moved past the timer's basis) — no-op; a fresh
      schedule-timers fx already re-armed it;
    - otherwise the entry IS now stale-by-policy — the durable `:stale-at`
      already makes the `:stale?` sub true (no write needed); emit a trace so
      Xray's lifecycle timeline shows the staleness boundary crossed. Refetch
      is NOT forced on a stale timer — staleness is orthogonal to refetch (a
      stale entry refreshes on its next live cause: route re-entry, an
      explicit event, or the focus/reconnect active-stale scan
      `revalidate-handler`, rf2-vtblcq)."
  [{rt :rf.db/runtime, frame-id :rf.frame/id}
   [_event-id {:keys [resource-key]}]]
  (let [runtime-db (or rt {})
        entry      (get-in runtime-db (state/entry-path resource-key))
        ;; re-derive staleness from the DURABLE :stale-at (the timer is
        ;; advisory — never trust "the timer fired on time"). An entry
        ;; re-loaded since the timer armed has a future :stale-at and is not
        ;; yet stale, so the re-check naturally no-ops. Shared derivation
        ;; (`state/entry-stale?`) so it never drifts from the subs / SSR view.
        stale?     (state/entry-stale? entry (now-ms))]
    (trace/emit! :rf.event :rf.resource/stale-fired
                 {:rf.frame/id frame-id :resource-key resource-key
                  :decision (cond (nil? entry) :no-entry
                                  stale?       :now-stale
                                  :else        :still-fresh)})
    ;; durable :stale-at is the freshness fact; no write needed.
    {:rf.db/runtime runtime-db}))

(defn gc-fired-handler
  "`:rf.resource.internal/gc-fired` — an inactive-GC timer fired. Re-check
  owner sets + entry generation after wake (timers are advisory); remove
  the entry only if still GC-eligible. Per Spec 016 §Stale and GC
  scheduling (\"inactive GC may use host timers, but GC MUST re-check owner
  sets and entry generation after wake\").

  GC-eligibility re-check (against the LIVE durable facts):
    - the entry is gone — no-op (`:no-entry`);
    - the entry has an active owner — pinned alive, no GC (`:has-owner`);
    - the entry has work in flight (`:current-work`) — no GC (`:in-flight`);
    - otherwise the entry is owner-free + idle — REMOVE it (recompute the
      reverse indexes) and cancel its advisory timers."
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
        {:rf.db/runtime rdb'
         ;; the entry is gone — cancel its (now orphaned) stale / GC timer
         ;; handles so they don't leak (the GC timer that fired is already
         ;; one-shot, but the paired stale timer may still be armed).
         :fx [[:rf.resource/cancel-timers
               {:frame-id frame-id :resource-keys [resource-key]}]]})
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
