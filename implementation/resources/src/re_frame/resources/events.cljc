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
  carry the verification payload (`:work/id` / `:resource/key` / `:scope`
  / `:generation` / `:rf.frame/id`) — user code MUST NOT dispatch them.
  The internal replies RECEIVE the canonical uniform reply map (Managed-
  Effects §The uniform reply envelope) — `re-frame.resources.reply` builds
  it from the transport's public payload + the verification payload, so a
  resource completion settles through the SAME one-status reply shape every
  managed-async family produces (EP-0011 §Resource Reply And Work Ledger).

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
  managed HTTP (`transport.http/lower`) after the transport seam's
  registration-time guard (`transport/assert-managed-transport!`).

  ## Work-ledger substrate (rf2-afpdkn)

  Each load-causing attempt now also writes a SERIALIZABLE work record at
  `[:rf.runtime/work-ledger <work-id-id>]` — keyed on the CEDN-1 byte
  `work-ledger/work-id-id` (NOT the work-id vector; rf2-9e0tyq), via
  `work-ledger/record-path` (the entry points at it via
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
            [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.resources.registry :as registry]
            [re-frame.resources.reply :as rreply]
            [re-frame.resources.route :as route]
            [re-frame.resources.scope-registry :as scope-registry]
            [re-frame.resources.state :as state]
            [re-frame.resources.transport :as transport]
            [re-frame.resources.transport.http :as transport-http]
            [re-frame.resources.work-ledger :as work-ledger]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- shared timestamp helpers ---------------------------------------------
;;
;; EP-0010 §The World-Input Rule (rf2-95b0lc) + EP-0017 declared-only delivery
;; (rf2-601ife): event handlers in this namespace take their "now" from the
;; triggering token's causal `:rf/time-ms` — the one host-clock read the router
;; stamped at the causal boundary — DECLARED via `:rf.cofx/requires [:rf/time-ms]`
;; and consumed FLAT from the coeffects map (`(:rf/time-ms coeffects)`, NOT
;; reached through the whole `:rf.cofx` token), NOT an ambient `(.now js/Date)` /
;; `System/currentTimeMillis` read at the decision site. There is therefore no
;; private host-clock helper here: a handler reading the live host clock for a
;; freshness DECISION would be non-replayable (a replay under a later clock
;; could take a different branch). Passive view / SSR re-derivation reads
;; (`subs.cljc`, `ssr.cljc`) keep their own ambient clock — those are reads,
;; not handler decisions (EP §Restore endorses lazy on-read freshness for the
;; VIEW layer).
;;
;; The pure stale / timer helpers this ns reads (`stale-at-for` /
;; `positive-or-nil` / `server-frame?`) live in `state.cljc` — shared
;; byte-for-byte with the mutation-success path so a patched / populated entry
;; ages exactly as a fetched one (rf2-366u0g).

;; ---- infinite-feed page context + reply addressing (EP-0021 R8) -----------
;;
;; An infinite resource reuses the WHOLE load-causing path (`ensure-load`):
;; identity, scope, generation, work-ledger row, dedupe, stale suppression,
;; transport lowering. It differs in exactly two places (R1/R2/R8 — no new
;; entry kind, no 6th FSM state):
;;
;;   1. the entry it seeds on a first load is `empty-infinite-entry` (the
;;      `:data` page vector + page-param facts), not the scalar `empty-entry`;
;;   2. the transport request carries the RESERVED page ctx
;;      `{:rf.resource/page-param p :rf.resource/page-index i}` (R8 — the
;;      already-reserved `ctx` slot, NOT a new 3-arity), and the reply is
;;      addressed at the PAGE reply handlers (`:rf.resource.internal/page-*`)
;;      so a page success APPENDS (`entry-append-page`) rather than overwriting
;;      the whole value (`entry-succeeded`). The page-param + page-index ride
;;      the reply `:reply-payload` so the append knows which param to record.
;;
;; Page-0 (a first `ensure`, or a `refetch`'s replacement page-0) uses
;; `page-param-for-spec` (the framework default `nil`, overridable via
;; `:initial-page-param`) at index 0; a `load-more` uses `next-param-for` over
;; the accumulated pages at index `page-count`. The single home so the page-0
;; fetch and the load-more fetch lower identically.

(def page-reserved-ctx-param-key
  "The RESERVED `:request` ctx key carrying the resolved page-param for an
  infinite feed's THIS page (R8). The `:request` fn reads it via
  `{:rf.resource/keys [page-param page-index]}`. Per Spec 016 §Registration —
  :infinite (the reserved ctx is the page extension point)."
  :rf.resource/page-param)

(def page-reserved-ctx-index-key
  "The RESERVED `:request` ctx key carrying the 0-based page index for an
  infinite feed's THIS page (R8). Per Spec 016 §Registration — :infinite."
  :rf.resource/page-index)

(defn page-request-ctx
  "Build the RESERVED `:request` ctx for an infinite feed page fetch (R8):
  `{:rf.resource/page-param p :rf.resource/page-index i}`. The `:request` fn
  reads `p` / `i` from this map's reserved keys; a non-infinite resource never
  reaches here (it lowers with a nil ctx, unchanged). Per Spec 016
  §Registration — :infinite / §Causal event — load-more."
  [page-param page-index]
  {page-reserved-ctx-param-key page-param
   page-reserved-ctx-index-key page-index})

(def page-succeeded-reply
  "The framework-internal infinite-feed page-success reply event id
  (`:rf.resource.internal/page-succeeded`). DISTINCT from
  `:rf.resource.internal/succeeded` (the scalar whole-value settle): a page
  success APPENDS the decoded page to the feed's page vector
  (`entry-append-page`) rather than overwriting `:data`. User code MUST NOT
  dispatch it. Per Spec 016 §Causal event — load-more."
  :rf.resource.internal/page-succeeded)

(def page-failed-reply
  "The framework-internal infinite-feed page-failure reply event id
  (`:rf.resource.internal/page-failed`). DISTINCT from
  `:rf.resource.internal/failed`: a page (N>0 load-more) failure is the THIRD
  error channel (`entry-page-failed` keeps the feed + records `:page-error`),
  not a first-load `:error` / whole-feed `:refresh-error`. User code MUST NOT
  dispatch it. Per Spec 016 §Causal event — load-more (the third error
  channel)."
  :rf.resource.internal/page-failed)

(defn- infinite-page-reply-payload
  "The verification payload for an infinite-feed page fetch — the SAME
  stale-suppression identity the scalar reply carries (`:work/id` /
  `:resource/key` / `:scope` / `:generation`; the `:rf.frame/id` is merged by
  `build-managed-args`) PLUS the resolved `:rf.resource/page-param` and
  `:rf.resource/page-index` so the page-success reply records the right param
  in `:page-params` (the param is reply state, never re-derived at settle).
  Per Spec 016 §Causal event — load-more."
  [scoped-key scope generation work-id page-param page-index]
  {:work/id                  work-id
   :resource/key             scoped-key
   :scope                    scope
   :generation               generation
   page-reserved-ctx-param-key page-param
   page-reserved-ctx-index-key page-index})

;; ---- ensure / refetch — the load-causing events ---------------------------

(defn- ensure-load
  "Shared ensure/refetch core. Resolves the scope + canonical params into a
  scoped resource key, reads the next monotone generation from the recorded
  `:rf.resource/generation-allocation` cofx (rf2-abyycr — the generator
  minted it at processing-start and the runtime recorded the value on the
  token, so replay reproduces it), transitions the entry to its in-flight
  status
  (`:loading`/`:fetching`), attaches the owner + records the cause, and
  lowers into the resource's transport. `force-new?` true (refetch) always
  starts a new generation even when a request is already in flight (Spec
  016 §Race: refetch may force a new generation). `force-new?` false
  (ensure) joins an in-flight request for the SAME generation/scoped-key
  when one exists (dedupe).

  `force-new?` false (ensure) ALSO short-circuits a FRESH-SKIP: an
  `ensure` of an already-`:loaded` entry that is still fresh-by-policy
  (`state/entry-stale?` false against the token's causal declared-flat
  `:rf/time-ms`)
  neither dedupes (no
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

  Returns the reg-event effects map `{:rf.db/runtime :fx}`."
  [{rt :rf.db/runtime, frame-id :rf.frame/id
    gen-allocation :rf.resource/generation-allocation
    time-ms :rf/time-ms, app-db :db}
   {:keys [resource owner cause keep-previous?] :as payload} {:keys [force-new? where]}]
  (let [runtime-db (or rt {})
        spec       (registry/require-resource-spec! resource where)
        ;; EP-0016 D3 slice 3: a `{:from-db …}` payload-scope OR spec-policy
        ;; resolves against the handler's app-db coeffect (`app-db`, the
        ;; causal world input) at use time — fail-closed on nil. Concrete
        ;; scopes resolve as before.
        scope      (registry/resolve-scope-for-event
                     resource spec {:payload-scope (:scope payload) :db app-db} where)
        ;; rf2-hgy5kf — thread `:params` PRESENCE (absent vs explicit nil) to
        ;; the validation boundary; an absent slot becomes `{}` there, an
        ;; explicit `{:params nil}` reaches the schema unchanged.
        cparams    (registry/validate+canonicalize-params
                     resource spec (state/params-present? payload) where)
        ;; rf2-rplgkw: scope (resolve-scope-for-event → canonicalize-scope) +
        ;; cparams (validate+canonicalize-params) are ALREADY canonical.
        scoped-key (state/scoped-resource-key* scope resource cparams)
        ;; EP-0021 R1: an infinite feed is the SAME durable entry whose `:data`
        ;; is the ordered page vector — seed `empty-infinite-entry` (the page
        ;; facts) on a first load, NOT the scalar `empty-entry`. A registered
        ;; non-infinite resource seeds the scalar entry unchanged.
        infinite?  (registry/infinite-resource? spec)
        entry      (or (get-in runtime-db (state/entry-path scoped-key))
                       (if infinite?
                         (state/empty-infinite-entry resource scoped-key)
                         (state/empty-entry resource scoped-key)))
        prior-work (:current-work entry)
        in-flight? (some? prior-work)
        ;; JOINABLE work (rf2-v4ygg5): an `ensure` may DEDUPE onto the prior
        ;; attempt ONLY when that attempt is genuinely LIVE — its work record
        ;; exists and its status is `:queued` / `:running`. A record that has
        ;; been marked `:abort-requested` (the last owner released it, an
        ;; opportunistic abort was issued) or that has reached a TERMINAL
        ;; status (`:cancelled` / `:suppressed` / …) is DOOMED — joining it
        ;; would leave the new owner attached to dead / dying work that will
        ;; never produce a usable reply (the only reply it can produce is a
        ;; suppressed/aborted one). Such a stale `:current-work` pointer can
        ;; survive a route supersession (release-owner marks the record
        ;; `:abort-requested` but leaves the entry's `:current-work` set) or a
        ;; direct/internal aborted settlement (the `:rf.resource.internal/
        ;; aborted` handler settles the row terminal but makes no entry write),
        ;; so the pointer alone is NOT proof of joinable work — the LINKED
        ;; RECORD'S status is. A non-joinable prior pointer falls through to a
        ;; fresh load (a new generation), which is the correct re-ensure.
        prior-record (when prior-work (work-ledger/get-record runtime-db prior-work))
        prior-status (:status prior-record)
        joinable?  (and (some? prior-record)
                        (not (work-ledger/terminal? prior-status))
                        (not= :abort-requested prior-status))
        ;; FRESH-SKIP gate (Spec 016 §Lifecycle is an FSM / §Restore and
        ;; replay): an `ensure` (never a `refetch`) of an already-`:loaded`
        ;; entry that is NOT in flight and is still fresh-by-policy serves
        ;; the cached value — no new generation, no fetch, no work record.
        ;; The in-flight dedupe takes precedence (a fresh-but-in-flight
        ;; entry can't exist — `:fetching`/`:loading` is the in-flight
        ;; status — but the explicit `(not in-flight?)` guard keeps the two
        ;; branches disjoint and order-independent).
        ;;
        ;; EP-0010 §The World-Input Rule (clauses 2 + 5, rf2-95b0lc): this is
        ;; a FRESHNESS DECISION that gates a DURABLE runtime-db write — the
        ;; fresh branch serves cache (no new work-ledger row), the stale
        ;; branch mints a new generation + work record. The basis MUST be the
        ;; triggering token's causal declared-flat `:rf/time-ms` (the `time-ms`
        ;; binding above, also used below for the durable `:started-at`), NOT an
        ;; ambient `(now-ms)` host read — otherwise a replay under a LATER live
        ;; clock could take the OPPOSITE branch (refetch vs serve-cache) and
        ;; produce a divergent work-ledger, breaking the EP's
        ;; same-tokens→equal-durable-projections property at the decision
        ;; boundary. The durable `:stale-at`/`:loaded-at` facts the entry carries
        ;; are still the freshness data; `:rf/time-ms` is the causal "now" they
        ;; are compared against (EP §Restore: "freshness decisions made lazily
        ;; from that token plus durable timestamps").
        fresh-skip? (and (not force-new?)
                         (not in-flight?)
                         (= :loaded (:status entry))
                         (not (state/entry-stale? entry time-ms)))
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
                                        update owner (fnil conj #{}) (state/key-id scoped-key)))
                     ;; route blocking: a route-owned blocking resource that
                     ;; is already fresh MUST settle the nav-token blocking
                     ;; slot NOW (no fetch will ever land a reply to drain
                     ;; it) — treat the fresh entry as already-`:success` or
                     ;; the route hangs forever (Spec 016 §Route integration).
                     ;; No-op for a non-route-owned / non-blocking resource.
                     (route/drain-blocking scoped-key hit :success))]
        (trace/emit! :rf.event :rf.resource/cache-hit
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :generation (:generation entry) :owner owner :cause cause})
        ;; the cache-hit attached a new owner lease — record that distinct
        ;; liveness change (symmetric with the dedupe / fresh-load paths).
        (when owner-newly-attached?
          (trace/emit! :rf.event :rf.resource/owner-attached
                       {:rf.frame/id frame-id :resource/key scoped-key
                        :generation (:generation entry) :owner owner :cause cause
                        :work/id nil :joined-in-flight? false}))
        {:rf.db/runtime rdb'})
      ;; ----- dedupe: join the in-flight request (ensure only) -------------
      ;; Attach any supplied owner to the existing entry + record the cause;
      ;; do NOT start a new generation. Join the SAME work-ledger record
      ;; (attach owner / append cause). Per Spec 016 §Race (ensure while in
      ;; flight joins the existing current work record). Gated on `joinable?`
      ;; (rf2-v4ygg5): only a LIVE (:queued / :running) prior attempt is
      ;; joinable — an `:abort-requested` (owner-released, doomed) or terminal
      ;; (`:cancelled` / suppressed) prior record falls through to a fresh
      ;; load below, so a route supersession + immediate re-ensure never joins
      ;; dead work.
      (and joinable? (not force-new?))
      (let [joined (cond-> entry
                     owner (update :active-owners (fnil conj #{}) owner))
            rdb'   (-> runtime-db
                       (assoc-in (state/entry-path scoped-key) joined)
                       (work-ledger/update-record
                         prior-work work-ledger/join-owner+cause owner cause)
                       (cond->
                         owner (update-in (state/owner-index-path)
                                          update owner (fnil conj #{}) (state/key-id scoped-key))))]
        (trace/emit! :rf.event :rf.resource/deduped
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :generation (:generation entry) :owner owner :cause cause
                      :work/id prior-work})
        ;; the ensure joined the in-flight work (no new generation) but ALSO
        ;; attached a new owner lease — record that distinct liveness change.
        (when owner-newly-attached?
          (trace/emit! :rf.event :rf.resource/owner-attached
                       {:rf.frame/id frame-id :resource/key scoped-key
                        :generation (:generation entry) :owner owner :cause cause
                        :work/id prior-work :joined-in-flight? true}))
        {:rf.db/runtime rdb'})
      ;; ----- start a new load attempt (fresh generation) -----------------
      :else
      ;; rf2-abyycr — the generation is the RECORDED allocation value (the
      ;; generator-backed `:rf.resource/generation-allocation` cofx minted it
      ;; at processing-start and the runtime recorded it on the token), NOT a
      ;; `(inc snapshot)` re-mint from an ambient read at this write site. So
      ;; replay reproduces the identical generation (and therefore the
      ;; identical `:work/id`, which derives from it) — a recorded managed
      ;; reply keeps its current-vs-stale verdict on replay.
      (let [generation (:generation gen-allocation)
            work-id    (work-ledger/resource-work-id scoped-key generation)
            ;; rf2-sxyrzk — the transport correlation token is the
            ;; frame-QUALIFIED request-id, NOT the bare work-id. The
            ;; managed-HTTP in-flight registry keys by request-id
            ;; PROCESS-GLOBALLY and supersedes by equal request-id (Spec 014);
            ;; the work-id is frame-local, so two frames issuing the same
            ;; resource at the same generation would collide on a bare work-id
            ;; and supersede/abort each other's in-flight request. Qualifying
            ;; with the frame id isolates them.
            request-id (work-ledger/managed-request-id frame-id work-id)
            ;; EP-0010 §Resources, Mutations, And Work-Ledger Timestamps: the
            ;; durable work-ledger `:started-at` is the TRIGGERING TOKEN'S
            ;; `:time-ms` (the causal world input the router stamped once at
            ;; the dispatch boundary), and `:deadline-at` is computed from it
            ;; plus the configured timeout policy — NOT an ambient clock read
            ;; in the reducer. Replay-stable: the same ensure/refetch token
            ;; mints the same `:started-at` / `:deadline-at`.
            started-at time-ms
            deadline   (when-let [ms (:timeout-ms spec)] (+ started-at ms))
            ;; EP-0021 R6 — a forced REFETCH of an infinite feed resets the
            ;; accumulation per the resource's `:refetch` policy BEFORE the
            ;; replacement page-0 starts. The ruled DEFAULT is window-preserving
            ;; (keep the visible pages until the replacement page-0 succeeds —
            ;; a focus/reconnect/invalidation refetch never collapses a loaded
            ;; feed to page 0); the opt-ins `:refetch-all-pages?` /
            ;; `:refetch-window` are R6 day-one knobs handled at the reply layer
            ;; (the replacement page-0 success determines what is kept). On an
            ;; ENSURE (not force-new) the entry is left as-is — a fresh ensure of
            ;; an infinite feed first-loads page 0 (empty `:data`), and an ensure
            ;; of an already-loaded feed never reaches the `:else` fresh-load
            ;; branch (fresh-skip / dedupe handle it). Window-preserving is a
            ;; pure no-op on `entry'` here (the page vector simply stays), so the
            ;; reset is recorded on the entry only when an opt-in discards.
            refetch-policy (when infinite? (:refetch spec))
            entry'     (cond-> (state/entry-start-load
                                 entry {:generation generation :work-id work-id
                                        :request-id request-id :owner owner})
                         ;; `:keep-previous?` projection pointer (a pointer
                         ;; only — never this key's data / tags).
                         prev-key (assoc :previous-key prev-key)
                         ;; EP-0021 R6 — a forced REFETCH of an infinite feed
                         ;; resets the accumulation to the kept window per the
                         ;; `:refetch` policy (window-preserving default = a
                         ;; pure no-op; `:refetch-all-pages?` / `:refetch-window`
                         ;; truncate the tail). An ensure (not force-new) leaves
                         ;; the feed untouched (page-0 first load).
                         (and infinite? force-new?)
                         (state/entry-refetch-reset
                           {:next-page-param-fn (:next-page-param spec)
                            :prev-page-param-fn (:prev-page-param spec)
                            :refetch-policy     refetch-policy}))
            ;; EP-0021 R8 — the page context for THIS fetch. A first ensure /
            ;; a refetch's replacement fetch a page-0 (`page-param-for-spec` —
            ;; the framework `nil` default, overridable via `:initial-page-param`
            ;; — at index 0). A non-infinite resource passes a nil ctx (R8 — the
            ;; reserved ctx is empty for a non-infinite request, unchanged).
            page-param (when infinite? (state/page-param-for-spec spec))
            page-index 0
            req-ctx    (when infinite? (page-request-ctx page-param page-index))
            ;; a forced refetch over an in-flight prior attempt SUPERSEDES it:
            ;; mark the old work record :superseded (terminal) and emit a
            ;; best-effort abort (opportunistic; stale suppression already
            ;; protects the late reply by work-id + generation). Per Spec 016
            ;; §Race (refetch may force a new generation).
            superseding? (and in-flight? force-new?)
            record     (work-ledger/work-record
                         {:work-id      work-id
                          :frame-id     frame-id
                          :resource/key scoped-key
                          :generation   generation
                          :transport    transport-id
                          :owner        owner
                          :cause        cause
                          :started-at   started-at
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
                                              update owner (fnil conj #{}) (state/key-id scoped-key))))
            ;; lower into the resource's transport (the existing seam). The
            ;; runtime owns reply addressing: the internal reply payloads
            ;; stamp the qualified :rf.frame/id + :work/id + :resource/key +
            ;; :scope + :generation so the reply handlers verify before
            ;; writing (stale suppression is the correctness boundary).
            ;; EP-0021 R8 — the `:request` fn keeps its settled `(params ctx)`
            ;; shape; an infinite feed's reserved `ctx` carries the resolved
            ;; page context for THIS page, a non-infinite resource's ctx is
            ;; nil (unchanged). NO new arity.
            http-args  (let [req-fn (:request spec)]
                         (req-fn cparams req-ctx))
            ;; EP-0021 R1/R2 — an infinite page fetch is addressed at the PAGE
            ;; reply handlers (`:rf.resource.internal/page-*`) so a page success
            ;; APPENDS / REPLACES-IN-PLACE rather than overwriting the whole
            ;; value; the reply payload carries the resolved `:page-param` /
            ;; `:page-index` so the settle records the right param. A
            ;; non-infinite resource keeps the scalar reply addressing.
            reply-overrides (when infinite?
                              {:on-success-id page-succeeded-reply
                               :on-failure-id page-failed-reply
                               :reply-payload (infinite-page-reply-payload
                                                scoped-key scope generation
                                                work-id page-param page-index)})
            ;; rf2-rrcfwk — guard the declared transport (registration-time
            ;; misconfig throw), then lower directly into the only
            ;; initial-scope transport. The one-arm dispatch indirection
            ;; (`transport/lower-ensure`) is folded into this guarded call;
            ;; a real dispatch table returns only when a second transport
            ;; lands (the guard becomes the dispatch).
            lower-fx   (do (transport/assert-managed-transport! transport-id where)
                           (transport-http/lower
                             (merge
                               {:http-args    http-args
                                :request-id   request-id
                                :work-id      work-id
                                :resource/key scoped-key
                                :scope        scope
                                :frame-id     frame-id
                                :generation   generation
                                :where        where}
                               reply-overrides)))]
        (trace/emit! :rf.event :rf.resource/work-started
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :generation generation :work/id work-id
                      :status :running :owner owner :cause cause
                      :superseded (when superseding? prior-work)})
        (trace/emit! :rf.event :rf.resource/fetch-started
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :generation generation :work/id work-id
                      :status (:status entry') :owner owner :cause cause})
        ;; a fresh load that also attaches a NEW owner lease — record the
        ;; liveness change distinctly from the work it kicked off (symmetric
        ;; with `:rf.resource/owner-released`).
        (when owner-newly-attached?
          (trace/emit! :rf.event :rf.resource/owner-attached
                       {:rf.frame/id frame-id :resource/key scoped-key
                        :generation generation :owner owner :cause cause
                        :work/id work-id :joined-in-flight? false}))
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
                   ;; rf2-sxyrzk — abort by the frame-QUALIFIED request-id (the
                   ;; token the prior lower registered); a bare work-id misses it.
                   (cond-> (work-ledger/abort-fx transport-id frame-id prior-work)
                     (conj (work-ledger/abort-fx transport-id frame-id prior-work)))))}))))

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

;; ---- load-more — the infinite-feed page-extension event (EP-0021 R2) ------
;;
;; A causal event that extends an infinite feed by ONE page. It reuses the
;; WHOLE load-causing substrate — generation, work-ledger row, dedupe, stale
;; suppression, transport lowering — and differs from `ensure`/`refetch` only
;; in WHICH page it fetches: the NEXT page param derived from the accumulated
;; tail (`next-param-for`), at index `page-count` (an APPEND), addressed at the
;; PAGE reply handlers so the success appends rather than overwrites (R1/R2/R8).
;;
;; FSM (R2 — no 6th state): a `load-more` on a `:loaded` feed transitions to
;; `:fetching` (the existing refresh-class transition — the feed has data, so
;; `entry-start-load` chooses `:fetching` and the accumulated pages stay
;; visible). The two SKIP paths fire no request:
;;   - TERMINAL (`next-param-for` is nil — no more pages): a no-op that emits
;;     `:rf.resource/load-more-skipped` `:reason :no-next-page`;
;;   - IN-FLIGHT (a page fetch is already running): dedupe against the live
;;     work, emit `:rf.resource/load-more-skipped` `:reason :in-flight`.
;; A `load-more` on a feed that does not exist / is not yet loaded is also a
;; no-op (`:reason :no-feed` — there is no tail to derive the next param from;
;; the first page is `ensure`'s page-0, not a load-more).

(defn- load-more-loaded
  "Core of `:rf.resource/load-more` (EP-0021 R2). Resolves the feed's scoped
  key (the FEED identity — the per-page param is NOT in the key, R1), reads the
  live entry, and either SKIPS (terminal / in-flight / no-feed — no request) or
  issues the NEXT page fetch (derived param at index `page-count`) through the
  resource's `:request` with the reserved page ctx, recording a work-ledger row
  and transitioning the feed to `:fetching` (pages stay visible). Reuses the
  recorded generation allocation (replay-stable) and the page reply addressing
  so the success appends via `entry-append-page` and a failure records
  `:page-error` (`entry-page-failed`). Returns the reg-event effects map."
  [{rt :rf.db/runtime, frame-id :rf.frame/id
    gen-allocation :rf.resource/generation-allocation
    time-ms :rf/time-ms, app-db :db}
   {:keys [resource owner cause] :as payload} {:keys [where]}]
  (let [runtime-db (or rt {})
        spec       (registry/require-resource-spec! resource where)
        scope      (registry/resolve-scope-for-event
                     resource spec {:payload-scope (:scope payload) :db app-db} where)
        cparams    (registry/validate+canonicalize-params
                     resource spec (state/params-present? payload) where)
        scoped-key (state/scoped-resource-key* scope resource cparams)
        entry      (get-in runtime-db (state/entry-path scoped-key))
        prior-work (:current-work entry)
        prior-record (when prior-work (work-ledger/get-record runtime-db prior-work))
        prior-status (:status prior-record)
        ;; a page fetch (or any fetch) is genuinely in flight when the linked
        ;; work record is LIVE (`:queued` / `:running`) — the same `joinable?`
        ;; liveness the `ensure` dedupe uses (rf2-v4ygg5). A doomed
        ;; (`:abort-requested`) / terminal pointer is NOT in flight.
        in-flight? (and (some? prior-record)
                        (not (work-ledger/terminal? prior-status))
                        (not= :abort-requested prior-status))
        pages      (:data entry)
        next-param (state/next-param-for (:next-page-param spec) pages)
        terminal?  (state/terminal? next-param)
        page-index (state/page-count entry)]
    (cond
      ;; ----- no feed: load-more before page 0 exists — no-op --------------
      ;; The first page is `ensure`'s page-0, not a load-more; a load-more with
      ;; no accumulated tail has no param to derive (`next-param-for` returns
      ;; nil on an empty/absent page vector, so this also covers a not-yet-
      ;; loaded feed). Fail-quiet (a trace), never a spurious request.
      (or (nil? entry) (not (state/infinite-entry? entry)) (empty? pages))
      (do
        (trace/emit! :rf.event :rf.resource/load-more-skipped
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :reason :no-feed :owner owner :cause cause})
        {:rf.db/runtime runtime-db})

      ;; ----- terminal: no more pages — no-op (R2) -------------------------
      terminal?
      (do
        (trace/emit! :rf.event :rf.resource/load-more-skipped
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :reason :no-next-page :page-count page-index
                      :owner owner :cause cause})
        {:rf.db/runtime runtime-db})

      ;; ----- dedupe: a page fetch is already in flight — no-op (R2) -------
      ;; A second load-more while one is running JOINS (no new generation, no
      ;; second request), exactly as a duplicate `ensure` does. The owner /
      ;; cause are folded onto the live work record so the in-flight page is
      ;; attributed to the new caller too.
      in-flight?
      (let [rdb' (work-ledger/update-record
                   runtime-db prior-work work-ledger/join-owner+cause owner cause)]
        (trace/emit! :rf.event :rf.resource/load-more-skipped
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :reason :in-flight :work/id prior-work
                      :page-count page-index :owner owner :cause cause})
        {:rf.db/runtime rdb'})

      ;; ----- issue the next page fetch (fresh generation, APPEND) ---------
      :else
      (let [generation (:generation gen-allocation)
            work-id    (work-ledger/resource-work-id scoped-key generation)
            request-id (work-ledger/managed-request-id frame-id work-id)
            started-at time-ms
            deadline   (when-let [ms (:timeout-ms spec)] (+ started-at ms))
            transport-id (or (:transport spec) transport/default-transport)
            ;; transition the FEED to its in-flight status. The feed has data,
            ;; so `entry-start-load` chooses `:fetching` (the refresh-class
            ;; transition — pages stay visible, no skeleton); it bumps the
            ;; generation/attempt, records `:current-work` + `:request-id`, and
            ;; attaches the owner. The page vector is UNTOUCHED (a load-more
            ;; never collapses the feed). Per Spec 016 §Causal event — load-more.
            entry'     (state/entry-start-load
                         entry {:generation generation :work-id work-id
                                :request-id request-id :owner owner})
            record     (work-ledger/work-record
                         {:work-id      work-id
                          :frame-id     frame-id
                          :resource/key scoped-key
                          :generation   generation
                          :transport    transport-id
                          :owner        owner
                          :cause        cause
                          :started-at   started-at
                          :deadline-at  deadline})
            owner-newly-attached? (and (some? owner)
                                       (not (contains? (:active-owners entry) owner)))
            rdb'       (-> runtime-db
                           (assoc-in (state/entry-path scoped-key) entry')
                           (work-ledger/put-record work-id record)
                           (cond->
                             owner (update-in (state/owner-index-path)
                                              update owner (fnil conj #{}) (state/key-id scoped-key))))
            ;; R8 — the reserved page ctx for THIS (next) page: the derived
            ;; param at index `page-count` (an append).
            req-ctx    (page-request-ctx next-param page-index)
            http-args  (let [req-fn (:request spec)]
                         (req-fn cparams req-ctx))
            lower-fx   (do (transport/assert-managed-transport! transport-id where)
                           (transport-http/lower
                             {:http-args    http-args
                              :request-id   request-id
                              :work-id      work-id
                              :resource/key scoped-key
                              :scope        scope
                              :frame-id     frame-id
                              :generation   generation
                              :where        where
                              ;; page reply addressing — a page success APPENDS
                              ;; at this index; the payload carries the resolved
                              ;; param so the settle records it in :page-params.
                              :on-success-id page-succeeded-reply
                              :on-failure-id page-failed-reply
                              :reply-payload (infinite-page-reply-payload
                                               scoped-key scope generation
                                               work-id next-param page-index)}))]
        (trace/emit! :rf.event :rf.resource/load-more
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :generation generation :work/id work-id
                      :page-param next-param :page-index page-index
                      :page-count page-index :owner owner :cause cause})
        (trace/emit! :rf.event :rf.resource/work-started
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :generation generation :work/id work-id
                      :status :running :owner owner :cause cause})
        (trace/emit! :rf.event :rf.resource/fetch-started
                     {:rf.frame/id frame-id :resource/key scoped-key
                      :generation generation :work/id work-id
                      :status (:status entry') :owner owner :cause cause})
        (when owner-newly-attached?
          (trace/emit! :rf.event :rf.resource/owner-attached
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
  "`:rf.resource/load-more` — extend an infinite feed by one page (EP-0021
  R2). Computes the next page param from the feed's accumulated tail (via the
  resource's `:next-page-param`), issues the managed request for that page with
  the reserved page ctx `{:rf.resource/page-param p :rf.resource/page-index i}`,
  records a work-ledger row, and transitions the feed to `:fetching` (the
  existing refresh-class transition — the accumulated pages stay visible).

  A TERMINAL feed (`:next-page-param` nil) is a no-op (`:reason :no-next-page`);
  a load-more while a page fetch is already in flight DEDUPES against the live
  work (`:reason :in-flight`); a load-more before page 0 exists is a no-op
  (`:reason :no-feed`). On success the page is APPENDED (`entry-append-page`)
  via the `:rf.resource.internal/page-succeeded` reply; a failure records
  `:page-error` (`entry-page-failed`) via `:rf.resource.internal/page-failed` —
  the third error channel (the feed keeps its pages). Generation + work-id
  stale suppression protect a late page reply exactly as for any fetch. Per
  Spec 016 §Causal event — load-more. Payload:
  `{:resource :scope :params :owner :cause}`."
  [cofx [_event-id payload]]
  (load-more-loaded cofx payload {:where 'rf.resource/load-more}))

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
;;     re-check all use) against the focus/reconnect token's causal declared-flat
;;     `:rf/time-ms` (rf2-95b0lc / rf2-601ife — not an ambient host-clock read at
;;     the scan site, so the SELECTION is replay-stable); a fresh entry is LEFT
;;     ALONE;
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

(def poll-cause
  "The revalidation cause recorded on an interval-poll-triggered refetch
  (`:poll`, EP-0020). A CAUSE, never an owner — it explains why the work
  happened without changing liveness / GC / polling (Spec 016 §Active owners
  and causes). A poll therefore creates no liveness and extends no GC; the
  entry stops polling the instant its last real owner releases. User code
  MUST NOT dispatch the poll-fired event directly; the host `:poll` timer
  does."
  :poll)

(defn- entry-revalidation-in-flight?
  "True iff `entry` already has a LIVE refetch in flight — its
  `:current-work` points at a work-ledger record whose status is
  non-terminal AND not `:abort-requested` (i.e. `:queued` / `:running`: work
  that will produce a usable reply). Such an entry needs no new revalidation
  refetch — one is already running. A `:current-work` pointer alone is NOT
  proof of live work: the linked record may be terminal (a settled attempt
  whose entry write has not yet cleared the pointer) or `:abort-requested`
  (a doomed, owner-released attempt) — both fall through as NOT in-flight, so
  revalidation can legitimately start fresh work. Mirrors the `joinable?`
  gate in `ensure-load` (rf2-v4ygg5): only a genuinely live attempt blocks a
  new generation. Per Spec 016 §Race / §Deferred slices (rf2-wankrd:
  coalesce focus + visibility revalidation for in-flight stale entries)."
  [runtime-db entry]
  (when-let [work-id (:current-work entry)]
    (let [status (:status (work-ledger/get-record runtime-db work-id))]
      (and (some? status)
           (not (work-ledger/terminal? status))
           (not= :abort-requested status)))))

(defn- active-stale-scan
  "Pure scan: given the frame's `runtime-db` value and the live `clock-ms`,
  return the vector of `{:resource/key :scope :resource :params}` for every
  cache entry that is active (has at least one `:active-owner` — a live lease
  worth refetching), stale-by-policy (`state/entry-stale?` against the
  durable timestamps), AND not already mid-revalidation (no LIVE in-flight
  refetch — `entry-revalidation-in-flight?`). Fresh entries, owner-free
  entries, and entries with a live refetch already running are excluded.

  COALESCING (rf2-wankrd): a tab-return commonly fires BOTH `focus` (on
  `window`) and `visibilitychange` (on `document`), each dispatching
  `:rf.resource/window-focused`. Without the in-flight gate, the first scan
  starts a refetch (setting `:current-work` to a `:running` record) and the
  second scan would force a SECOND new generation over it — superseding +
  aborting the first (abort churn) for no benefit. Skipping entries with live
  in-flight work makes back-to-back focus / focus+visibility signals
  idempotent: one active-stale key yields at most one new generation.

  Per Spec 016 §Stale and GC scheduling / §Deferred slices (focus/reconnect
  active-stale scan) / §Race (refetch may force a new generation). A pure
  selection — it never mutates; the caller turns the selection into
  background `:rf.resource/refetch` dispatches."
  [runtime-db clock-ms]
  (let [entries (get-in runtime-db (state/entries-path))]
    (into []
          (keep (fn [[_k-id entry]]
                  (when (and (seq (:active-owners entry))
                             (state/entry-stale? entry clock-ms)
                             (not (entry-revalidation-in-flight? runtime-db entry)))
                    ;; rf2-9e0tyq — read the scoped-key VECTOR from the entry's
                    ;; `:resource/key` (the map key is now the opaque byte id).
                    (let [scoped-key (:resource/key entry)
                          [scope resource-id params] scoped-key]
                      {:resource/key scoped-key
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
  refetch ride their ordinary refetch traces. Returns the effects map
  (`:fx` only — the scan itself makes NO durable write; the refetch
  dispatches do).

  `signal` is the revalidation op (`:rf.resource/window-focused` /
  `:rf.resource/network-reconnected`) for the trace; `cause` is the cause
  keyword recorded on each refetch.

  EP-0010 §The World-Input Rule (rf2-95b0lc) + EP-0017 declared-only delivery
  (rf2-601ife): the active-stale SELECTION is a freshness decision — it picks
  which entries get a `:refetch` dispatched — so its basis is the triggering
  focus/reconnect token's causal declared-flat `:rf/time-ms` (consumed FLAT, not
  reached through the `:rf.cofx` token), not an ambient `(now-ms)` host read. The
  scan writes nothing durable itself (the child refetches do, each stamped with
  its own fresh world inputs), so this is the softer of the three sites; but a
  replayed focus/reconnect must still SELECT the same set the recorded
  `:rf/time-ms` dictated, or the replay diverges from the original run."
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms} signal cause]
  (let [runtime-db (or rt {})
        eligible   (active-stale-scan runtime-db time-ms)
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
                  :keys       (mapv :resource/key eligible)})
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

;; ---- active-owner polling (EP-0020, rf2-byl7bk.2) -------------------------
;;
;; A `:poll` timer (armed from the resource's `:poll-interval-ms` while the
;; entry is actively owned) fires `:rf.resource.internal/poll-fired`. The
;; timer is ADVISORY — identical discipline to `:stale` / `:gc`: the handler
;; RE-CHECKS the live durable entry before doing anything. The tick is the
;; timer-driven counterpart of the focus/reconnect scan, reusing the same
;; landed substrate:
;;
;;   - ACTIVE OWNER gate — a poll never pins an owner-free entry; the instant
;;     the last owner releases, polling STOPS (no re-arm). (Owners are
;;     liveness leases; a poll is a freshness mechanism, never a liveness one
;;     — Spec 016 §Active owners and causes / §Polling.)
;;   - DEFAULT-PAUSE-WHEN-HIDDEN — the firing thunk stamps host `:hidden?`
;;     onto the payload (read at the host boundary, never an ambient cascade
;;     read); a hidden tab PAUSES the tick (no refetch) but RE-ARMS the next
;;     poll so it resumes on tab return (which also fires the focus scan).
;;   - IN-FLIGHT COALESCING — the exact `entry-revalidation-in-flight?` gate
;;     the focus/reconnect scan uses: a tick that finds a live in-flight
;;     refetch SKIPS the refetch (no second generation, no overlap on a slow
;;     endpoint) but RE-ARMS. The interval effectively backs off to the
;;     response time.
;;   - UNCONDITIONAL TICK (Q3 ruling (a)) — when it does refetch, it does so
;;     by the INTERVAL, NOT gated on `:stale?`; the consumer who declared a
;;     poll interval asked for "re-read every N ms". `:stale-after-ms` stays
;;     the orthogonal focus/route knob.
;;   - CAUSE, NEVER OWNER — the refetch dispatches `:rf.resource/refetch` with
;;     cause `:poll`; generation + stale-reply suppression + dedupe all apply
;;     unchanged (a late poll reply over a superseded entry is suppressed by
;;     the single stale-suppression boundary).

(defn poll-fired-handler
  "`:rf.resource.internal/poll-fired` — an active-owner poll timer fired
  (EP-0020, Spec 016 §Polling). The timer is ADVISORY: this handler
  RE-CHECKS the live durable entry before refetching, and RE-ARMS the next
  poll while polling should continue (cancel-then-arm via the
  `:rf.resource/schedule-timers` fx). Dispatched by the host `:poll` timer
  (`re-frame.resources.timers`); user code MUST NOT dispatch it directly.

  The re-check + decision (against the LIVE durable facts):
    - entry GONE (removed / GC'd / cleared) — STOP, no re-arm (`:no-entry`);
    - entry has NO active owner (last owner released) — STOP polling, no
      re-arm (`:no-owner`); a poll never pins an owner-free entry;
    - document HIDDEN (`:hidden?` stamped by the firing thunk) — PAUSE the
      tick (no refetch) but RE-ARM so polling resumes on tab return
      (`:paused-hidden`); default-pause-when-hidden (Q2 ruling, the SWR / RTK
      / TanStack `refetchIntervalInBackground:false` default);
    - a LIVE in-flight refetch is already running (`entry-revalidation-in-flight?`)
      — SKIP the refetch (coalesce, no double-fetch) but RE-ARM
      (`:coalesced`); a slow endpoint never stacks overlapping requests;
    - otherwise — UNCONDITIONALLY dispatch a background `:rf.resource/refetch`
      with cause `:poll` (the interval IS the cadence, NOT `:stale?`-gated)
      AND RE-ARM the next poll (`:polled`).

  A failed poll tick is an ordinary background-refresh failure (the entry
  stays `:loaded`, keeps prior `:data`, records `:refresh-error`) — and the
  NEXT poll still fires (the re-arm is unconditional on a started/coalesced/
  paused tick), so a transient endpoint failure never permanently stops a
  monitor (Spec 016 §Polling — background-refresh failure)."
  [{rt :rf.db/runtime, frame-id :rf.frame/id}
   [_event-id {resource-key :resource/key :keys [hidden?]}]]
  (let [runtime-db (or rt {})
        entry      (get-in runtime-db (state/entry-path resource-key))
        owned?     (seq (:active-owners entry))
        in-flight? (and entry (entry-revalidation-in-flight? runtime-db entry))
        ;; the resource's declared interval — the re-arm delay (cancel-then-arm)
        interval   (state/positive-or-nil
                     (:poll-interval-ms (registry/resource-meta (:resource/id entry))))
        decision   (cond
                     (nil? entry)     :no-entry
                     (not owned?)     :no-owner
                     hidden?          :paused-hidden
                     in-flight?       :coalesced
                     :else            :polled)
        ;; STOP (drop the poll) on a gone / owner-free entry; otherwise RE-ARM
        ;; the next interval (paused-hidden / coalesced / polled all keep the
        ;; cadence alive). The re-arm fx is poll-only (nil stale / GC delays
        ;; leave those kinds as-is in schedule-timers-handler).
        re-arm?    (and interval (contains? #{:paused-hidden :coalesced :polled} decision))
        refetch?   (= :polled decision)]
    (trace/emit! :rf.event :rf.resource/poll-fired
                 {:rf.frame/id frame-id :resource/key resource-key
                  :decision decision :rearmed? (boolean re-arm?)})
    (cond-> {:rf.db/runtime runtime-db}
      (or refetch? re-arm?)
      (assoc :fx
        (cond-> []
          ;; unconditional active-owner refetch (cause :poll, never an owner)
          refetch?
          (conj [:dispatch [:rf.resource/refetch
                            {:resource (second resource-key)
                             :scope    (first resource-key)
                             :params   (nth resource-key 2)
                             :cause    poll-cause}]])
          ;; re-arm the next poll (cancel-then-arm; poll kind only)
          re-arm?
          (conj [:rf.resource/schedule-timers
                 {:frame-id       frame-id
                  :resource/key   resource-key
                  :stale-delay-ms nil
                  :gc-delay-ms    nil
                  :poll-delay-ms  interval
                  :server?        (state/server-frame? frame-id)}]))))))

;; ---- invalidate-tags — exact tag invalidation -----------------------------

(defn match-invalidation-keys
  "The PURE invalidation-match predicate, extracted so BOTH the invalidation
  engine (`invalidate-tags-handler`) AND the mutation-settlement reply
  (`re-frame.resources.mutation-events`, which must report the keys an
  `:invalidates` descriptor WILL mark stale BEFORE the dispatched
  `:rf.resource/invalidate-tags` runs — rf2-fi6tda.2) agree on the exact same
  match set.

  `entries` is the cache `:entries` map `{<key-id> <entry>}` (keyed on the
  CEDN-1 byte `key-id`, rf2-9e0tyq); `cscope` is the canonical concrete scope
  (nil iff `cross-scope?`); `tag-set` is the requested tag set; `exempt-ids`
  is the set of byte `key-id`s a same-mutation `:populates` kept authoritative
  (Rider 1 — excluded from the match). Returns a map with:

  - `:matched`    — vector of MATCHED scoped-key VECTORS (`:resource/key`),
                    tags-intersect AND in-scope AND not-exempt;
  - `:matched-ids`— the matched byte `key-id`s (the stale-mark write set);
  - `:exempt-hit` — the EXEMPT keys that WOULD have matched (Rider 1 trace);
  - `:other-scope-hit?` — whether the tags match an entry in ANOTHER scope
                    (\"no match HERE\" vs \"no resource provides this tag in
                    any scope\" — only meaningful for a scoped invalidation).

  Per Spec 016 §Invalidation / §Populate is an authoritative load."
  [entries cscope cross-scope? tag-set exempt-ids]
  (let [tags-hit? (fn [entry] (seq (set/intersection (set (:tags entry)) tag-set)))
        sk-of     (fn [entry] (:resource/key entry))
        in-scope? (fn [entry] (or cross-scope? (= cscope (first (sk-of entry)))))
        matched   (into {}
                        (filter (fn [[k-id entry]] (and (not (contains? exempt-ids k-id))
                                                        (in-scope? entry) (tags-hit? entry))))
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
     :other-scope-hit? other-scope-hit?}))

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

  **Cross-scope MUST carry `:cause`** (rf2-7r8kgd, Spec 016 §The cross-scope
  lattice — three precise rungs): `:cross-scope? true` is the AUDITED escape —
  it can stale or refetch data across every user / tenant / story frame / SSR
  request, so it MUST carry `:cause` evidence (the privacy-relevant trace
  record of WHY the cache reached outside the mutation's own resolved scope —
  EP-0015). A `:cross-scope? true` invalidation with a nil / absent `:cause` is
  a loud `:rf.error/resource-cross-scope-cause-required` — never a silent
  unaudited cross-scope sweep. The mutation engine always stamps `:cause`
  (`[:mutation <id> <instance>]`); this gate guards the direct public engine
  entry. Per Spec 016 §The cross-scope lattice.

  **Fail closed without a scope** (rf2-pvdae1, Spec 016 §Invalidation): a
  SCOPED invalidation (the default, `:cross-scope?` false / absent) with NO
  `:scope` is a loud `:rf.error/resource-invalidate-scope-required` — never
  a silent `(= nil entry-scope)` match that quietly invalidates nothing (or,
  worse, only the entries that happen to live in a nil scope). Cross-scope is
  the ONLY scope-agnostic path; it must be requested explicitly. The concrete
  `:scope` is routed through the shared `state/canonicalize-scope` validation
  path (rf2-hosnba) so a reserved-namespace typo (`:rf.scope/glabal`) or a
  host / non-EDN scope value fails closed through the SAME single path every
  other scope-bearing operation uses.

  **No-match distinction** (Spec 016 §Invalidation): the summary carries
  `:matched` (the matched keys), `:any-tag-match-other-scope?` (whether the
  tags match an entry in ANOTHER scope — \"no match HERE\" vs \"no resource
  provides this tag in any scope\"), so Xray can tell the two apart.

  **Populate exemption** (EP-0016 Rider 1, Spec 016 §Populate is an
  authoritative load): `:exempt-keys` is an optional set of EXACT scoped keys
  EXEMPT from this pass — a key a same-mutation `:populates` just seeded
  AUTHORITATIVELY is fresh for the mutation result, so this same mutation's
  invalidation pass must NOT re-stale or refetch it (unless the descriptor
  opted into `:refetch-populated? true`, in which case the mutation passes no
  exempt set). An exempt key is removed from the matched set BEFORE the
  stale-mark / refetch decision, so it keeps its fresh populated value and arms
  no refetch. The summary carries `:exempt` (the exempt keys that actually
  matched the tags, so Xray can show what the populate spared)."
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms}
   [_event-id {:keys [scope tags cause cross-scope? exempt-keys]}]]
  (let [runtime-db (or rt {})
        ;; FAIL CLOSED (rf2-pvdae1): a scoped (default) invalidation MUST
        ;; carry an explicit :scope — a missing scope would otherwise match
        ;; `(= nil (first k))` and silently invalidate nothing (or the wrong
        ;; set). Cross-scope is the only scope-agnostic path and must be
        ;; opted into explicitly. Per Spec 016 §Invalidation.
        _          (when (and (not cross-scope?) (nil? scope))
                     (error/throw-error!
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
        ;; AUDITED ESCAPE — cross-scope MUST carry :cause (rf2-7r8kgd, Spec 016
        ;; §The cross-scope lattice). :cross-scope? true can stale / refetch
        ;; data across every user, tenant, story frame, and SSR request, so it
        ;; is fail-closed without :cause evidence: a nil/absent :cause is a loud
        ;; :rf.error/resource-cross-scope-cause-required, never a silent
        ;; unaudited sweep. (The mutation engine always stamps
        ;; :cause [:mutation id instance]; this guards the direct public entry.)
        _          (when (and cross-scope? (nil? cause))
                     (error/throw-error!
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
        ;; route the concrete scope through the SHARED validation path
        ;; (rf2-hosnba, rf2-lzv9xc): rejects reserved-namespace typos +
        ;; host / non-EDN scope values, rejects the wrapped [:rf.scope/global]
        ;; singleton (rf2-bwwk6l — supply bare), canonicalizes — the SAME
        ;; single path event/sub resolution, route planning, and clear-scope
        ;; use. No resource-id (a tag invalidation spans resources); nil when
        ;; scope-agnostic cross-scope.
        cscope     (when (some? scope)
                     (state/canonicalize-scope scope 'rf.resource/invalidate-tags nil))
        ;; rf2-ru73k6 F1 — the SAME shared tag-input normalizer the mutation
        ;; `:invalidates` bare shorthand uses: a LONE vector tag written
        ;; directly (`:tags [:article slug]`) is the ONE tag `#{[:article
        ;; slug]}`, not the scalar set `#{:article slug}` (which would silently
        ;; match nothing). A tag-set (`#{[:article slug]}` / `[[:article slug]]`)
        ;; lowers unchanged.
        tag-set    (state/normalize-tag-set tags)
        ;; EP-0010 §Resources, Mutations, And Work-Ledger Timestamps: the
        ;; durable `:invalidated-at` written by an invalidation event is that
        ;; EVENT'S `:time-ms` (the causal world input the router stamped once
        ;; at the dispatch boundary), NOT an ambient clock read in the
        ;; reducer. Replay-stable: re-running the same invalidation token
        ;; rewrites the SAME `:invalidated-at`.
        invalidated-at time-ms
        entries    (get-in runtime-db (state/entries-path))
        ;; EP-0016 Rider 1 — keys this same mutation just POPULATED
        ;; authoritatively are EXEMPT from this pass (kept fresh, never
        ;; re-staled / refetched). The set is canonicalized at the producer
        ;; (the populate path re-keys by the canonical scoped key), so a plain
        ;; set membership test is identity-correct.
        ;; rf2-9e0tyq — `entries` is keyed on the byte `key-id`; every
        ;; scope/identity decision below reads the entry's `:resource/key`
        ;; VECTOR (`sk`). `exempt` is matched by byte identity (the populate
        ;; path supplies scoped-key vectors; reduce both to `key-id`) so a
        ;; list- vs vector-params exempt key matches exactly. The matched MAP
        ;; is keyed on the byte `key-id` (so `assoc-in (entry-path …)` and the
        ;; trace's `:resource/key` use the right form: byte for the db write,
        ;; vector for the trace/refetch).
        exempt     (into #{} (map state/key-id) exempt-keys)
        ;; the SHARED pure match (rf2-fi6tda.2): exactly the set the
        ;; mutation-settlement reply pre-computes for `:affected-keys`. Keyed
        ;; on the byte key-id; `:matched` are scoped-key VECTORS.
        {matched-ids :matched-ids
         exempt-hit  :exempt-hit
         other-scope-hit? :other-scope-hit?}
        (match-invalidation-keys entries cscope cross-scope? tag-set exempt)
        ;; mark each matched entry stale (durable :invalidated-at fact). Keyed
        ;; on the byte key-id — write straight to the entries-path slot.
        ;; EP-0019 / byl7bk: marking an entry stale is an authoritative durable
        ;; write a later optimistic rollback could clobber (it moves the entry's
        ;; freshness), so `state/entry-invalidate` bumps the per-entry :revision
        ;; write identity in the SAME swap — biasing to over-bump (a false
        ;; conflict costs one refetch; a missed one is silent corruption). The
        ;; SHARED durable stale mark the EP-0019 restore-dangle conflict-rollback
        ;; also uses (one home, identical `:stale?` derivation).
        rdb'       (reduce
                     (fn [db k-id]
                       (update-in db (state/entry-path-by-id k-id)
                                  state/entry-invalidate invalidated-at))
                     runtime-db matched-ids)
        ;; per-entry decision: active-owner entries refetch (Spec 016
        ;; §Invalidation 3); ownerless entries are left stale / GC-eligible
        ;; (§Invalidation 4). Collected once for both the dispatches and the
        ;; per-entry trace detail. `:resource/key` is the scoped-key VECTOR.
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
    ;; ONE decision summary (broad-tag storms stay readable) ...
    (trace/emit! :rf.event :rf.resource/invalidated
                 {:rf.frame/id frame-id :scope cscope :tags tags :cause cause
                  :cross-scope? (boolean cross-scope?)
                  :matched (mapv :resource/key decisions)
                  :refetched (count refetches)
                  :left-stale (count (remove :active? decisions))
                  ;; EP-0016 Rider 1: keys a same-mutation populate kept
                  ;; authoritative and spared from this pass (would have matched)
                  :exempt exempt-hit
                  ;; no-match distinction: no match in this scope vs no resource
                  ;; provides this tag in any scope (Spec 016 §Invalidation)
                  :any-tag-match-other-scope? other-scope-hit?})
    ;; ... PLUS one per-entry detail trace (the refetch-vs-leave-stale
    ;; decision per matched key — Spec 016 §Invalidation 5 / the Xray
    ;; invalidation graph)
    (doseq [{:keys [active? decision tags] resource-key :resource/key :as _d} decisions]
      (trace/emit! :rf.event :rf.resource/refetch-decision
                   {:rf.frame/id frame-id :resource/key resource-key
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
        ;; rf2-9e0tyq — `owned` is a set of byte `key-id`s (the owner-index
        ;; members), so resolve each to its entry via `entry-path-by-id` (NOT
        ;; `entry-path`, which would re-transform a scoped-key vector).
        ;; drop the owner from each owned entry + the index
        rdb1       (-> (reduce
                         (fn [db k-id]
                           (update-in db (state/entry-path-by-id k-id)
                                      (fn [e] (when e (update e :active-owners disj owner)))))
                         rdb0 (or owned #{}))
                       (update-in (state/owner-index-path) dissoc owner))
        ;; for each owned entry that is still in flight, drop the owner from
        ;; the work record; collect the work ids whose owners are now empty
        ;; (orphaned in-flight attempts → opportunistic abort).
        {rdb2 :rdb aborts :aborts}
        (reduce
          (fn [acc k-id]
            (let [db   (:rdb acc)
                  e    (get-in db (state/entry-path-by-id k-id))
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
          (or owned #{}))
        ;; EP-0020 §Polling: any entry whose `:active-owners` went EMPTY by
        ;; this release stops polling (a poll never pins an owner-free entry).
        ;; Collect the now-owner-free entries' scoped keys for the poll-only
        ;; cancel fx (the stale / GC timers stay armed — an owner-free entry
        ;; still GCs). The poll-fired re-check is the belt-and-braces; this
        ;; makes the stop deterministic + prompt.
        now-owner-free
        (into []
              (keep (fn [k-id]
                      (let [e (get-in rdb2 (state/entry-path-by-id k-id))]
                        (when (and e (empty? (:active-owners e)))
                          (:resource/key e)))))
              (or owned #{}))]
    (trace/emit! :rf.event :rf.resource/owner-released
                 {:rf.frame/id frame-id :owner owner :released (vec (or owned #{}))
                  :aborted (mapv first aborts)})
    {:rf.db/runtime rdb2
     ;; rf2-sxyrzk — abort by the frame-QUALIFIED request-id (the registered
     ;; token); a bare work-id would miss the orphaned in-flight request.
     :fx (cond-> (into [] (keep (fn [[wid transport]]
                                  (work-ledger/abort-fx transport frame-id wid)))
                       aborts)
           (seq now-owner-free)
           (conj [:rf.resource/cancel-poll-timers
                  {:frame-id frame-id :resource/keys now-owner-free}]))}))

;; ---- clear-scope — the causal logout / tenant-switch boundary --------------

(defn- clear-scope-handler*
  "The clear-scope body once `scope` is a resolved concrete value (a literal
  scope, or a `{:from-db …}` reference already resolved against app-db). Routes
  the concrete scope through the shared `state/canonicalize-scope` validation
  path, then removes the in-scope entries, settles their in-flight work rows
  `:cancelled`, recomputes indexes, and emits the explaining trace + fx."
  [runtime-db frame-id scope cause]
  (let [cscope     (state/canonicalize-scope scope 'rf.resource/clear-scope nil)
        entries    (get-in runtime-db (state/entries-path))
        ;; rf2-9e0tyq — `entries` is keyed on the byte `key-id`; the scope to
        ;; match against lives in each entry's `:resource/key` vector
        ;; (`(first (:resource/key entry))`), not the map key. `in-scope` is
        ;; the set of byte key-ids to remove + the scoped-key VECTORS for the
        ;; downstream trace / timer fx (which name resources by their vector).
        in-scope-ids (into #{} (comp (filter (fn [[_k-id e]] (= cscope (first (:resource/key e)))))
                                     (map key))
                           entries)
        in-scope     (into #{} (comp (filter (fn [[_k-id e]] (= cscope (first (:resource/key e)))))
                                     (map (fn [[_k-id e]] (:resource/key e))))
                           entries)
        ;; collect the in-flight work ids for the cleared entries (best-effort
        ;; abort + terminal :cancelled work rows)
        in-flight  (into []
                         (keep (fn [k-id]
                                 (let [e (get entries k-id)
                                       wid (:current-work e)]
                                   (when wid
                                     [wid (:transport (work-ledger/get-record runtime-db wid))]))))
                         in-scope-ids)
        ;; remove the entries, settle their in-flight work rows :cancelled,
        ;; then recompute the indexes from what remains
        rdb'       (-> runtime-db
                       (update-in (state/entries-path)
                                  (fn [es] (reduce dissoc es in-scope-ids)))
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
     ;; rf2-sxyrzk — abort by the frame-QUALIFIED request-id (the registered
     ;; token); the bare work-id would miss the in-scope in-flight requests.
     :fx (cond-> (into [] (keep (fn [[wid transport]] (work-ledger/abort-fx transport frame-id wid)))
                       in-flight)
           (seq in-scope)
           (conj [:rf.resource/cancel-timers
                  {:frame-id frame-id :resource/keys (vec in-scope)}]))}))

(defn clear-scope-handler
  "`:rf.resource/clear-scope` — causal scope clear (Spec 016 §clear-scope
  is causal). Removes every entry in the scope, releases its owners from
  the owner-index, marks each in-scope in-flight work record terminal
  `:cancelled`, best-effort aborts those attempts (opportunistic), and
  emits an explaining trace. Stale suppression by work-id + generation
  remains the correctness boundary — the entry a late reply would write
  into is gone, so the reply handler's existence check suppresses it; the
  abort is the optimisation. Payload: `{:scope :cause}`.

  The concrete `:scope` is routed through the shared
  `state/canonicalize-scope` validation path (rf2-hosnba, rf2-lzv9xc) so a
  reserved-namespace typo (`:rf.scope/glabal`) or a host / non-EDN scope
  value fails closed through the SAME single path every other scope-bearing
  operation uses — a typo can never silently clear the WRONG scope (a
  cross-tenant data wipe).

  **`{:from-db <id>}` reference resolution** (rf2-mfnc5i, Spec 016 §clear-scope
  is causal / §Named resource-scope resolvers): a `:scope` MAY be a
  `{:from-db <resolver-id>}` named-resolver reference, resolved at USE TIME
  against this handler's app-db coeffect (the single use-time rule, uniform
  with event / route / sub / remove). A reference that resolves NIL at a
  clear-scope site is FAIL-CLOSED with a loud
  `:rf.warning/resource-clear-scope-unresolved` diagnostic — NEVER a silent
  no-op (it INTENDED to derive the tenant / user / leak-boundary scope to wipe
  and could not; clearing nothing — or matching the literal reference map,
  which keys nothing — would be the silent no-op the spec prohibits). The
  resolved concrete scope is then routed through `state/canonicalize-scope`
  exactly as a literal scope is. Per Spec 016 §clear-scope is causal."
  [{rt :rf.db/runtime, frame-id :rf.frame/id, app-db :db} [_event-id {:keys [scope cause]}]]
  (let [runtime-db (or rt {})
        ;; EP-0016 D3 / rf2-mfnc5i: resolve a `{:from-db …}` reference at use
        ;; time against the handler's app-db coeffect. A reference that resolves
        ;; NIL is FAIL-CLOSED with a loud diagnostic (see the early return
        ;; below) — never canonicalized as a literal scope map (which would
        ;; match no entry → the silent no-op Spec 016 prohibits).
        from-db?   (scope-registry/from-db-reference? scope)
        resolved   (if from-db?
                     (scope-registry/resolve-from-db-reference
                       scope app-db 'rf.resource/clear-scope)
                     scope)]
    (if (and from-db? (nil? resolved))
      ;; FAIL-CLOSED: a {:from-db …} reference resolved nil at a clear-scope
      ;; site — emit the loud dev diagnostic and clear NOTHING. The resolver's
      ;; declared :inputs are not present in db (e.g. no logged-in user); a
      ;; derived scope that cannot resolve is the unresolved condition, never
      ;; permission to clear global or silently no-op. Per Spec 016 §clear-scope
      ;; is causal (EP-0016 issue-7 tripwire).
      (do
        (trace/emit! :warning :rf.warning/resource-clear-scope-unresolved
                     {:rf.frame/id frame-id
                      :scope       scope
                      :from-db     (:from-db scope)
                      :cause       cause
                      :recovery    :fix-scope
                      :hint        (str ":rf.resource/clear-scope referenced named "
                                        "scope resolver " (pr-str (:from-db scope))
                                        " via {:from-db …}, but it resolved NIL "
                                        "against the current db — FAIL-CLOSED. "
                                        "Nothing was cleared (a {:from-db …} that "
                                        "cannot resolve is NEVER a silent no-op, and "
                                        "NEVER a fall-through to clearing global / "
                                        "another tier). The resolver's declared "
                                        ":inputs are not present in db (e.g. no "
                                        "logged-in user at logout). Per Spec 016 "
                                        "§clear-scope is causal.")})
        {})
      (clear-scope-handler* runtime-db frame-id resolved cause))))

;; ---- remove — single-instance cache removal --------------------------------

(defn remove-handler
  "`:rf.resource/remove` — remove a single resource instance from the cache
  by its scoped key, and drop its owner/tag-index rows. Per Spec 016
  §Events. Payload: `{:resource :scope :params}`."
  [{rt :rf.db/runtime, frame-id :rf.frame/id, app-db :db} [_event-id {:keys [resource] :as payload}]]
  (let [runtime-db (or rt {})
        spec       (registry/require-resource-spec! resource 'rf.resource/remove)
        ;; EP-0016 D3 slice 3: resolve a `{:from-db …}` scope against app-db.
        scope      (registry/resolve-scope-for-event
                     resource spec {:payload-scope (:scope payload) :db app-db} 'rf.resource/remove)
        ;; rf2-hgy5kf — thread `:params` presence (absent vs explicit nil) to
        ;; the validation boundary so removal keys on the SAME identity an
        ;; explicit-nil-params ensure produced.
        cparams    (registry/validate+canonicalize-params
                     resource spec (state/params-present? payload) 'rf.resource/remove)
        ;; rf2-rplgkw: scope (resolve-scope-for-event → canonicalize-scope) +
        ;; cparams (validate+canonicalize-params) are ALREADY canonical.
        scoped-key (state/scoped-resource-key* scope resource cparams)
        entry      (get-in runtime-db (state/entry-path scoped-key))
        wid        (:current-work entry)
        transport  (when wid (:transport (work-ledger/get-record runtime-db wid)))
        rdb'       (-> runtime-db
                       ;; rf2-9e0tyq — dissoc by the byte key-id (a dissoc by
                       ;; the scoped-key vector would no-op and leak the entry).
                       (update-in (state/entries-path) dissoc (state/key-id scoped-key))
                       (cond-> wid (work-ledger/update-record
                                     wid work-ledger/mark-terminal
                                     :cancelled {:reason :remove}))
                       (update state/resources-key state/recompute-indexes))]
    (trace/emit! :rf.event :rf.resource/removed
                 {:rf.frame/id frame-id :resource/key scoped-key :reason :remove
                  :aborted (when wid [wid])})
    {:rf.db/runtime rdb'
     ;; best-effort abort of the removed instance's in-flight attempt
     ;; (opportunistic; stale suppression protects correctness) PLUS cancel
     ;; its advisory stale / GC timers (the entry's durable facts are gone).
     ;; rf2-sxyrzk — abort by the frame-QUALIFIED request-id (the registered
     ;; token); a bare work-id would miss the removed instance's in-flight request.
     :fx (conj (if-let [fx (and wid (work-ledger/abort-fx transport frame-id wid))] [fx] [])
               [:rf.resource/cancel-timers
                {:frame-id frame-id :resource/keys [scoped-key]}])}))

;; ---- framework-internal reply handlers ------------------------------------
;;
;; These carry the verification payload and MUST verify frame + work-id +
;; generation before writing (Spec 016 §Transport — stale suppression is
;; the correctness boundary). User code MUST NOT dispatch them.

(defn- entry-current-generation
  "Read the LIVE counterpart generation for a stale-suppression gate: the
  `:generation` of the entry currently occupying `resource-key` at
  completion. nil when no entry occupies the slot (it was removed / GC'd /
  cleared — no live counterpart, exactly the supersession the gate records).
  This is the `:current` half of the carried-vs-current pair the canonical
  stale reply carries; the `:carried` half is the generation stamped on the
  reply token (`(:generation payload)`)."
  [runtime-db resource-key]
  (:generation (get-in runtime-db (state/entry-path resource-key))))

(defn- stale-suppress-reply
  "Build the canonical `:status :stale` reply outcome for a superseded /
  vanished RESOURCE reply through the SHARED `re-frame.reply` substrate
  (via `rreply/stale-reply`), so the resource family lowers its stale
  outcome exactly as every other managed-async family does (Managed-Effects
  §Stale suppression). The carried correlation is the reply token's
  `:work/id` + `:generation`; the current correlation is the live entry's
  `:generation` (nil when the slot is gone — no live counterpart). The
  result rides `:rf.reply/status :stale` / `:rf.reply/work-status
  :suppressed` / `:rf.reply/stale-reason` / the carried-vs-current
  generation pair ADDITIVELY onto the existing `:rf.resource/stale-
  suppressed` trace via `emit-resource-stale-suppressed!`.

  Returns the `re-frame.reply/suppress` outcome map (`:deliver?` is false —
  the app reply target MUST NOT run; `:reply` is the data-only `:status
  :stale` reply; `:work/status :suppressed`). `extra` threads diagnostic
  facts (e.g. `:outcome`) onto the stale reply."
  [runtime-db resource-key {work-id :work/id :keys [generation scope] :as payload} extra]
  (let [carried-gen (:generation payload)
        current-gen (entry-current-generation runtime-db resource-key)]
    (rreply/stale-reply
      {:carried {:work/id work-id :generation carried-gen}
       :current {:generation current-gen}
       :extra   (merge {:work/id      work-id
                        :work/kind    rreply/work-kind-resource
                        :stale/reason :rf.resource/superseded
                        :correlation  (cond-> {:generation {:carried carried-gen
                                                             :current current-gen}}
                                        (some? resource-key) (assoc :resource/key resource-key)
                                        (some? scope)         (assoc :scope scope))}
                       extra)})))

(defn- emit-resource-stale-suppressed!
  "Emit the `:rf.resource/stale-suppressed` trace for a suppressed late
  resource reply, carrying its bespoke facts (`:resource/key` / `:work/id` /
  `:generation` / `:outcome`) PLUS the canonical reply-envelope vocabulary
  ADDITIVELY (joined to `:work/id` via the shared `:rf.reply/*` facts):
  `:rf.reply/status :stale`, `:rf.reply/work-status :suppressed`,
  `:rf.reply/stale-reason`, `:rf.reply/work-id`, and `:rf.reply/correlation`
  (the carried-vs-current generation gate) — the SAME additive shape the
  machine `:rf.machine/done` and HTTP / probe stale paths ride (Managed-
  Effects §Tracing / EP-0011). `stale` is the `stale-suppress-reply`
  outcome; its trace summary routes wire slots through the shared elider via
  `rreply/trace-reply`."
  [frame-id resource-key work-id generation outcome stale]
  (let [summary (rreply/trace-reply (:reply stale))]
    (trace/emit! :rf.event :rf.resource/stale-suppressed
                 {:rf.frame/id frame-id :resource/key resource-key
                  :work/id work-id :generation generation :outcome outcome
                  ;; reply-envelope vocabulary (Managed-Effects §9) — the
                  ;; canonical :status :stale reply produced via the shared
                  ;; substrate, recorded ADDITIVELY (the bespoke facts above
                  ;; are preserved).
                  :rf.reply/status      (:status summary)
                  :rf.reply/work-status (:work/status summary)
                  :rf.reply/work-id     (:work/id summary)
                  :rf.reply/stale-reason (:stale/reason summary)
                  :rf.reply/correlation (:correlation summary)
                  ;; rf2-waawic — the SHARED carried/current stale-gate facts
                  ;; `re-frame.reply/suppress` already computed on `(:trace
                  ;; stale)`. Projecting them here lets the uniform
                  ;; reply-envelope view read the stale gate without
                  ;; resource-family-specific parsing (the `:rf.reply/
                  ;; correlation` above is the reply's bespoke generation pair;
                  ;; these are the shared substrate facts Xray's
                  ;; reply-envelope panel reads at :518).
                  :rf.reply/carried     (:rf.reply/carried (:trace stale))
                  :rf.reply/current     (:rf.reply/current (:trace stale))})))

(defn- live-entry-for-reply
  "Look the live entry up for an internal reply and verify it is still the
  one the reply belongs to: the reply's stamped `:rf.frame/id` equals the
  RECEIVING frame (`receiving-frame-id`), the entry exists, its
  `:current-work` equals the reply's `:work/id`, AND its `:generation`
  equals the reply's `:generation`. Returns the entry on a match, nil on a
  cross-frame / stale / superseded / vanished reply (which MUST be suppressed
  — Spec 016 §Cancellation is opportunistic; stale suppression is mandatory).

  FRAME VERIFICATION (rf2-eu2ifi): the runtime stamps the qualified
  `:rf.frame/id` into every reply payload at lowering; the reply handler runs
  in the RECEIVING frame's cofx. A reply whose payload frame does not match
  the receiving frame is REJECTED without touching this frame's entry or
  ledger — a misrouted reply (a cross-frame request-id collision, or a reply
  re-dispatched into the wrong frame) can never mutate the wrong frame's
  cache. The frame stamp is checked FIRST (before the per-frame entry lookup)
  so a cross-frame reply is rejected even when both frames happen to hold the
  same scoped key at the same generation.

  The work-id is the single intra-frame identity (it embeds the generation);
  the generation check is belt-and-braces for a future transport that reuses
  a work-id. A reply with no stamped frame (a direct-dispatch test payload
  that omits `:rf.frame/id`) skips the frame check (nil never collides with a
  concrete frame id) and is verified by work-id + generation alone — the
  runtime-slice tests stay deterministic.

  The verification work identity is `:work/id` (EP-0007 — the qualified
  spelling the ledger row, the entry's `:current-work`, and the uniform
  reply envelope share). One attempt, one work id, one name."
  [runtime-db receiving-frame-id {work-id :work/id resource-key :resource/key :keys [generation] :as payload}]
  (let [reply-frame (:rf.frame/id payload)]
    (when (or (nil? reply-frame) (= reply-frame receiving-frame-id))
      (when-let [entry (get-in runtime-db (state/entry-path resource-key))]
        (when (and (= work-id (:current-work entry))
                   (= generation (:generation entry)))
          entry)))))

;; ---- transport reply payload extraction → canonical reply map --------------
;;
;; Resources lower THROUGH managed HTTP, so the transport (Spec 014 §Reply
;; addressing) APPENDS its PUBLIC reply payload to the runtime-supplied
;; `:on-success` / `:on-failure` internal reply event vector as the LAST arg,
;; so a live reply lands as a 3-element event:
;;
;;   [:rf.resource.internal/succeeded <verification-payload> {:kind :success :value <decoded-data>}]
;;   [:rf.resource.internal/failed    <verification-payload> {:kind :failure :failure <:rf.http/* envelope>}]
;;
;; `<verification-payload>` (arg 2) is the `{:work/id :resource/key :scope
;; :generation :rf.frame/id}` map resource lowering supplied (the stale-
;; suppression identity, the boundary the runtime OWNS — EP-0007: the work
;; identity is `:work/id`). `<http-result>` (arg 3) is the transport's
;; PUBLIC outcome.
;;
;; The reply handlers RE-LIFT (arg 2 + arg 3) into the ONE canonical reply
;; map every managed-async family produces — `re-frame.resources.reply`
;; builds `{:status :value/:error :work/id :work/kind :resource :work/status
;; :rf.frame/id :completed-at :correlation}` (Managed-Effects §The uniform
;; reply envelope / EP-0011 §Resource Reply And Work Ledger). The internal
;; resource reply targets are framework-INTERNAL, so they receive the
;; canonical reply map DIRECTLY (no public `{:kind …}` reshape — that reshape
;; is `:rf.http/managed`'s own public sugar). The handlers then branch on the
;; canonical `:status` and install `(:value reply)` under the durable entry's
;; `:data` (the entry layer's spelling of the same fact; the reply-map
;; spelling is `:value` — kh9jz6 / EP-0007).
;;
;; A test that feeds an internal reply directly may inline `:data` / `:error`
;; in arg 2 (no transport in the loop); the reader below falls back to that
;; shape so the runtime-slice tests keep exercising the entry semantics
;; deterministically.

;; The transport-payload extractors a reply handler lifts from arg 3 live in
;; `re-frame.resources.reply` (`transport-success-value` /
;; `transport-failure-envelope`) — shared with the mutation write path, which
;; differs only by the inline durable-layer fallback key (`:data` here for a
;; resource entry, `:result` for a mutation instance — rf2-366u0g).

(def ^:private http-aborted-kind
  "The managed-HTTP failure `:kind` for an intentional abort/cancellation
  (Spec 014 §Aborts — the transport classifies a `:rf.http/managed-abort`,
  an actor-destroy cancellation, or a timeout teardown to this kind). A
  failure envelope carrying this kind is a CANCELLATION, not a user-visible
  resource error (rf2-z70ujl). Note a `:request-id-superseded` abort never
  even dispatches a reply (the transport suppresses it, Spec 014 §Abort
  precedence) — so a superseded supersession is handled by the missing reply
  + stale-suppression, not here."
  :rf.http/aborted)

(defn- abort-failure?
  "True iff `error` is a managed-HTTP ABORT failure envelope
  (`{:kind :rf.http/aborted …}`) — an intentional cancellation that must NOT
  become a user-visible resource `:error` / `:refresh-error` or a failed
  ledger row (rf2-z70ujl, Spec 016 §Cancellation is opportunistic). Every
  other `:rf.http/*` category (transport / 5xx / 4xx / timeout-as-failure /
  decode / accept) is a genuine failure and flows the ordinary failed path."
  [error]
  (= http-aborted-kind (:kind error)))

(defn succeeded-handler
  "`:rf.resource.internal/succeeded` — a transport read succeeded. Re-lifts
  the transport's PUBLIC reply into the canonical reply map
  (`rreply/success-reply`, `:status :ok` carrying `:value` — Managed-Effects
  §The uniform reply envelope), verifies frame + `:work/id` + generation
  against the live entry, and on match installs `(:value reply)` as the
  durable entry `:data` (`:loaded`), preserving the old `:data` value when
  the new data is `=` (structural sharing), and records `:loaded-at` /
  `:stale-at` / produced `:tags`. (`:value` is the reply-map spelling of the
  decoded result everywhere; `:data` is the durable entry layer's spelling
  of the same fact — kh9jz6 / EP-0007.) A stale / superseded reply is
  SUPPRESSED (it MUST NEVER mutate a newer entry). Per Spec 016 §Transport /
  §Structural sharing / §Status semantics; EP-0011 §Resource Reply And Work
  Ledger.

  Event shape: `[_ <verification-payload> <http-result>]` — the managed-HTTP
  transport appends `{:kind :success :value <decoded-data>}` as the last arg
  (Spec 014 §Reply addressing); the decoded data is read from there
  (`rreply/transport-success-value` with the `:data` durable-layer fallback)
  and re-lifted into the canonical `:value`."
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms}
   [_event-id {work-id :work/id resource-key :resource/key :keys [generation] :as payload} http-result]]
  (let [runtime-db (or rt {})
        value      (rreply/transport-success-value payload http-result :data)
        ;; EP-0010 §Managed Effects And Reply Tokens / §Resources, Mutations,
        ;; And Work-Ledger Timestamps + EP-0017 declared-only delivery
        ;; (rf2-601ife): the reply is a CAUSAL TOKEN. The host completion time
        ;; (`:completed-at`, read ONCE at the transport finalisation boundary)
        ;; rides the reply event's causal `:rf/time-ms`, DECLARED via
        ;; `:rf.cofx/requires` and consumed FLAT here — the reply handler MUST
        ;; NOT re-read the clock NOR reach through the `:rf.cofx` token. It is
        ;; carried onto the canonical reply map as `:completed-at` (the
        ;; uniform-reply spelling) and is the source of the durable
        ;; `:loaded-at`.
        completed-at time-ms
        ;; the ONE canonical reply map every managed-async family produces
        ;; (Managed-Effects §The uniform reply envelope). The internal
        ;; resource reply target receives it DIRECTLY (no public `{:kind …}`
        ;; reshape). The decoded result is `:value` (EP-0007 / kh9jz6).
        reply      (rreply/success-reply payload value
                                         {:work-kind rreply/work-kind-resource
                                          :completed-at completed-at})
        entry      (live-entry-for-reply runtime-db frame-id payload)]
    (if (nil? entry)
      ;; STALE SUPPRESSION (mandatory): a superseded / vanished reply never
      ;; mutates a newer entry. Per Managed-Effects §Stale suppression the
      ;; completion is recorded `:status :stale` / `:work/status :suppressed`
      ;; through the SHARED `re-frame.reply` substrate (via
      ;; `rreply/stale-reply`), exactly as every other managed-async family
      ;; lowers its stale outcome — the canonical reply built above
      ;; (`rreply/success-reply`) is the SUCCESS reply for the live path; the
      ;; nil-entry path produces the canonical STALE reply instead. The app
      ;; reply target MUST NOT run (no live entry to settle), the
      ;; (already-superseded) work row settles terminal `:suppressed`, and the
      ;; host handle is cleared. Per Spec 016 §Cancellation is opportunistic;
      ;; stale suppression is mandatory.
      (let [stale (stale-suppress-reply runtime-db resource-key payload
                                        {:outcome :success})]
        (emit-resource-stale-suppressed!
          frame-id resource-key work-id generation :success stale)
        (work-ledger/clear-handle! frame-id work-id)
        {:rf.db/runtime (work-ledger/update-record
                          runtime-db work-id work-ledger/mark-terminal
                          :suppressed {:reason :stale-reply :outcome :success})})
      (let [spec      (registry/resource-meta (:resource/id entry))
            ;; the durable entry stores the canonical reply's `:value` under
            ;; `:data` (the entry layer's spelling of the same fact — the
            ;; reply-map spelling is `:value`, kh9jz6 / EP-0007).
            data      (:value reply)
            ;; EP-0010 §Resources, Mutations, And Work-Ledger Timestamps:
            ;; resource `:loaded-at` IS the successful reply's completion time
            ;; (`(:completed-at reply)`, carried on the reply token), and
            ;; `:stale-at` is computed from it + the resource's
            ;; `:stale-after-ms` policy — never an ambient clock read here.
            loaded-at (:completed-at reply)
            stale-at  (state/stale-at-for spec loaded-at)
            ;; arm the advisory stale / GC timers from the resource's policy
            ;; (Spec 016 §Stale and GC scheduling). The DELAYS are relative
            ;; from now (the durable absolute :stale-at / :loaded-at remain the
            ;; freshness facts the re-check derives against; the timer is only
            ;; an advisory nudge). A resource declaring no :stale-after-ms /
            ;; :gc-after-ms arms neither. nil when this resource arms no timers
            ;; (no schedule-timers fx emitted).
            stale-delay-ms (state/positive-or-nil (:stale-after-ms spec))
            gc-delay-ms    (state/positive-or-nil (:gc-after-ms spec))
            tags-fn   (:tags spec)
            ;; tags are produced from the params + decoded data; the canonical
            ;; params are the third element of the scoped key
            tags      (when tags-fn (set (tags-fn (nth resource-key 2) data)))
            entry'    (state/entry-succeeded
                        entry {:data data :loaded-at loaded-at
                               :stale-at stale-at :tags tags})
            ;; EP-0020 §Polling: arm the active-owner POLL timer from the
            ;; resource's `:poll-interval-ms` — but ONLY while the freshly-
            ;; loaded entry has at least one active owner (a poll never pins an
            ;; owner-free entry alive; an owner-free entry is GC fodder, not a
            ;; poll target). On fire the `:poll` re-check unconditionally
            ;; refetches (the interval IS the cadence) and re-arms the next
            ;; tick. A resource declaring no `:poll-interval-ms` (or one whose
            ;; entry is owner-free at settle) arms no poll timer.
            poll-delay-ms (when (seq (:active-owners entry'))
                            (state/positive-or-nil (:poll-interval-ms spec)))
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
                     {:rf.frame/id frame-id :resource/key resource-key
                      :work/id work-id :generation generation :status :completed})
        (trace/emit! :rf.event :rf.resource/succeeded
                     {:rf.frame/id frame-id :resource/key resource-key
                      :work/id work-id :generation generation
                      :status-before (:status entry) :status-after :loaded})
        ;; arm the advisory stale / GC / poll timers (host-side side table) for
        ;; this freshly-loaded entry — the WRITE rides an fx exactly as the
        ;; generation high-water bump + work-handle side-table writes do.
        ;; Cancel-then-arm so a re-load reschedules a single live timer per
        ;; [key kind]. Skipped under SSR by the fx's `:platforms #{:client}`
        ;; gate (the re-check handler re-derives freshness from the durable
        ;; :stale-at, so a never-fired server timer is harmless). Only emitted
        ;; when the resource declares at least one policy (stale / GC / poll).
        ;; Per Spec 016 §Stale and GC scheduling / §Polling.
        (cond-> {:rf.db/runtime rdb'}
          (or stale-delay-ms gc-delay-ms poll-delay-ms)
          (assoc :fx [[:rf.resource/schedule-timers
                       {:frame-id       frame-id
                        :resource/key   resource-key
                        :stale-delay-ms stale-delay-ms
                        :gc-delay-ms    gc-delay-ms
                        :poll-delay-ms  poll-delay-ms
                        :server?        (state/server-frame? frame-id)}]]))))))

(defn- entry-abort-settled
  "Settle a LIVE entry whose current attempt was ABORTED (a cancellation, NOT
  a failure — rf2-z70ujl). An abort must NEVER populate `:error` /
  `:refresh-error` or leave the entry stranded mid-flight:

    - a REFRESH abort (the entry was `:fetching` — it has prior data) returns
      to `:loaded` and KEEPS the prior `:data` (the cancelled background
      refresh leaves the last-known-good value intact, no `:refresh-error`);
    - a FIRST-load abort (the entry was `:loading` — no usable data) settles
      to a non-error stable `:idle` state (the load was cancelled, not
      failed; a subsequent live cause re-ensures it cleanly).

  Either way `:current-work` is cleared (the attempt settled) and no error
  facts are written. Per Spec 016 §Cancellation is opportunistic / §Status
  semantics. (Distinct from `state/entry-failed`, which records the failure
  envelope; an abort is the no-error settlement.)"
  [entry]
  (if (state/has-data? entry)
    (assoc entry :status :loaded :current-work nil)
    (assoc entry :status :idle  :current-work nil)))

(defn failed-handler
  "`:rf.resource.internal/failed` — a transport read failed. Verifies frame
  + work-id + generation; a first-load failure settles `:error` (no usable
  data); a background-refresh failure returns to `:loaded`, keeps prior
  `:data`, and records `:refresh-error`. A stale / superseded reply is
  suppressed. Per Spec 016 §Status semantics.

  An ABORT reply (`{:kind :rf.http/aborted}`, rf2-z70ujl) is NOT a failure:
  the managed-HTTP transport routes an intentional cancellation (owner-loss
  orphan abort, actor-destroy, timeout teardown) through this same
  `:on-failure` reply, but it must NOT become a user-visible resource error
  or a `:failed` ledger row. It is branched into CANCELLATION semantics —
  the live attempt settles to a non-error stable state (`entry-abort-settled`)
  WITHOUT `:error` / `:refresh-error`, the work row settles terminal
  `:cancelled`, and a route-owned blocking resource drains its slot like a
  non-error settle (the route un-blocks rather than flipping to `:error`).
  A stale / superseded abort reply is suppressed by the same
  `live-entry-for-reply` boundary (it can never mutate a newer entry).

  Event shape: `[_ <verification-payload> <http-result>]` — the managed-HTTP
  transport appends `{:kind :failure :failure <:rf.http/* envelope>}` as the
  last arg (Spec 014 §Reply addressing); the failure envelope is read from
  there (`rreply/transport-failure-envelope`) and re-lifted into the canonical
  reply map (`rreply/failure-reply` — `:status :error` with the envelope under
  `:error`, or `:status :cancelled` for an `:rf.http/aborted` envelope; per
  Managed-Effects §Status taxonomy / EP-0011 §Resource Reply And Work
  Ledger)."
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms}
   [_event-id {work-id :work/id resource-key :resource/key :keys [generation] :as payload} http-result]]
  (let [runtime-db (or rt {})
        error      (rreply/transport-failure-envelope payload http-result)
        ;; EP-0017 declared-only delivery + EP-0010 §Managed Effects And Reply
        ;; Tokens (rf2-rl27r2): a FAILED / CANCELLED completion is still a
        ;; managed-async completion with a reply token, so its causal completion
        ;; time is supplied as data — DECLARED `:rf/time-ms`, consumed FLAT — and
        ;; carried onto the canonical reply as `:completed-at`, symmetric with
        ;; the success reply + with mutation replies (which already carry it).
        ;; The handler MUST NOT re-read the clock. Dropping it made the resource
        ;; family asymmetric and weakened replay / tooling evidence for a failed
        ;; or cancelled load.
        completed-at time-ms
        ;; the ONE canonical reply map (Managed-Effects §The uniform reply
        ;; envelope) — `:status :error` (or `:cancelled` for an abort), now
        ;; carrying `:completed-at` (rf2-rl27r2). The internal reply target
        ;; receives it directly. `abort-failure?` is the family's classifier;
        ;; the canonical reply's `:status` then drives the branch.
        reply      (rreply/failure-reply payload error
                                         {:work-kind rreply/work-kind-resource
                                          :completed-at completed-at})
        aborted?   (= :cancelled (:status reply))
        entry      (live-entry-for-reply runtime-db frame-id payload)]
    (cond
      ;; STALE SUPPRESSION (mandatory): a superseded / vanished reply (failure
      ;; OR abort) never mutates a newer entry. Per Managed-Effects §Stale
      ;; suppression the completion is recorded `:status :stale` /
      ;; `:work/status :suppressed` through the SHARED `re-frame.reply`
      ;; substrate (via `stale-suppress-reply`), and the canonical reply-
      ;; envelope vocabulary rides ADDITIVELY on the `:rf.resource/stale-
      ;; suppressed` trace. STALE VALIDATION WINS OVER NATURAL STATUS
      ;; (rf2-jzh5gq): once the reply no longer correlates with a live target
      ;; the ledger row is ALWAYS `:suppressed` — never an accepted
      ;; `:cancelled`. A stale abort can never be an accepted cancellation:
      ;; there is no live target to cancel. The `:outcome` diagnostic still
      ;; distinguishes `:aborted` from `:failure` for tooling.
      (nil? entry)
      (let [stale (stale-suppress-reply runtime-db resource-key payload
                                        {:outcome (if aborted? :aborted :failure)})]
        (emit-resource-stale-suppressed!
          frame-id resource-key work-id generation
          (if aborted? :aborted :failure) stale)
        (work-ledger/clear-handle! frame-id work-id)
        {:rf.db/runtime (work-ledger/update-record
                          runtime-db work-id work-ledger/mark-terminal
                          :suppressed
                          ;; rf2-rl27r2: the terminal outcome summary records
                          ;; the causal completion time (the reply token's
                          ;; `:completed-at`) for a failed / cancelled completion.
                          {:reason :stale-reply
                           :outcome (if aborted? :aborted :failure)
                           :completed-at completed-at})})

      ;; ABORT (rf2-z70ujl): an intentional cancellation reached the failure
      ;; reply seam. Settle the LIVE attempt to a non-error stable state, mark
      ;; the work row terminal :cancelled, drain any route blocking slot like a
      ;; non-error settle (status :success keeps drain-blocking off the
      ;; route-:error branch — a cancelled blocking first-load un-blocks the
      ;; route rather than surfacing a spurious error). NO :error /
      ;; :refresh-error write. The host handle is cleared.
      aborted?
      (let [entry' (entry-abort-settled entry)
            rdb'   (-> runtime-db
                       (assoc-in (state/entry-path resource-key) entry')
                       (work-ledger/update-record
                         work-id work-ledger/mark-terminal
                         ;; rf2-rl27r2: a cancellation is a completion — its
                         ;; terminal outcome carries the reply token's causal
                         ;; `:completed-at`.
                         :cancelled {:reason :aborted :completed-at completed-at})
                       (route/drain-blocking resource-key entry' :success))]
        (work-ledger/clear-handle! frame-id work-id)
        (trace/emit! :rf.event :rf.resource/work-abort-requested
                     {:rf.frame/id frame-id :resource/key resource-key
                      :work/id work-id :generation generation
                      :status-before (:status entry) :status-after (:status entry')})
        {:rf.db/runtime rdb'})

      :else
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
                         ;; rf2-rl27r2: the failed terminal outcome carries the
                         ;; reply token's causal `:completed-at` alongside the
                         ;; error envelope (the summary represents the completion).
                         :failed {:error error :completed-at completed-at})
                       ;; route blocking: a blocking FIRST-load failure flips
                       ;; the route transition to :error + populates
                       ;; :rf.route/error; a background-refresh failure (data
                       ;; kept, status back to :loaded) settles the slot like
                       ;; a success. drain-blocking keys on entry' status.
                       ;; (Spec 016 §Route integration.)
                       (route/drain-blocking resource-key entry' :failure))]
        (work-ledger/clear-handle! frame-id work-id)
        (trace/emit! :rf.event :rf.resource/work-completed
                     {:rf.frame/id frame-id :resource/key resource-key
                      :work/id work-id :generation generation :status :failed})
        (trace/emit! :rf.event op
                     {:rf.frame/id frame-id :resource/key resource-key
                      :work/id work-id :generation generation
                      :status-before (:status entry) :status-after (:status entry')})
        {:rf.db/runtime rdb'}))))

(def ^:private aborted-event-failure
  "The synthetic `:rf.http/aborted` failure envelope the legacy
  `:rf.resource.internal/aborted` event lowers through the canonical failure
  path. The production managed-HTTP abort path arrives as an `:rf.http/aborted`
  FAILURE through `failed-handler`; this legacy/ad-hoc reply event carries no
  transport envelope of its own, so it synthesises the same abort kind so it
  flows the SAME `rreply/failure-reply` → `:status :cancelled` lowering, the
  SAME `live-entry-for-reply` verification, and the SAME stale-wins-over-
  cancelled suppression boundary (rf2-iu0z8t)."
  {:kind :rf.http/aborted :reason :aborted})

(defn aborted-handler
  "`:rf.resource.internal/aborted` — a transport read was aborted. A LEGACY /
  ad-hoc reply event: the PRODUCTION managed-HTTP abort path already arrives
  as an `:rf.http/aborted` FAILURE through `failed-handler` (Spec 014 routes
  an intentional cancellation through `:on-failure`), so this event is
  retained only for direct-dispatch / test callers — and it is now LOWERED
  through the SAME canonical failure / stale-suppression path rather than
  ad-hoc settling the row from the verification payload (rf2-iu0z8t). It:

   - builds the canonical reply via `rreply/failure-reply` with a synthetic
     `:rf.http/aborted` envelope → `:status :cancelled`;
   - runs `live-entry-for-reply` (frame + work-id + generation verification),
     so a STALE / superseded / CROSS-FRAME aborted event NEVER settles an
     accepted `:cancelled` — it is SUPPRESSED (stale validation wins over the
     natural cancellation status; a cross-frame event is rejected without
     touching this frame's entry or ledger);
   - on the live path settles the entry to a non-error stable state
     (`entry-abort-settled` — no `:error` / `:refresh-error`), marks the work
     row terminal `:cancelled`, drains any route blocking slot like a
     non-error settle, and clears the host handle.

  EP-0017 declared-only delivery (rf2-rl27r2): a cancellation is still a
  managed-async completion with a reply token, so its terminal work-ledger
  outcome carries the reply token's causal `:completed-at` (DECLARED
  `:rf/time-ms`, consumed FLAT) — symmetric with the failure / success paths.

  Event shape: `[_ <verification-payload>]`."
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms}
   [_event-id {work-id :work/id resource-key :resource/key :keys [generation] :as payload}]]
  (let [runtime-db   (or rt {})
        completed-at time-ms
        ;; lower through the SHARED failure reply builder — a synthetic
        ;; `:rf.http/aborted` envelope yields `:status :cancelled`
        ;; (Managed-Effects §Status taxonomy), exactly as the production abort
        ;; arriving via `failed-handler` does.
        _reply       (rreply/failure-reply payload aborted-event-failure
                                           {:work-kind rreply/work-kind-resource
                                            :completed-at completed-at})
        entry        (live-entry-for-reply runtime-db frame-id payload)]
    (if (nil? entry)
      ;; STALE / CROSS-FRAME SUPPRESSION (mandatory): a superseded / vanished /
      ;; cross-frame aborted event never mutates a newer (or another frame's)
      ;; entry, and the row settles terminal :suppressed — NEVER an accepted
      ;; :cancelled (stale validation wins over the natural cancellation
      ;; status, rf2-jzh5gq / rf2-iu0z8t). The :outcome diagnostic records
      ;; :aborted for tooling.
      (let [stale (stale-suppress-reply runtime-db resource-key payload
                                        {:outcome :aborted})]
        (emit-resource-stale-suppressed!
          frame-id resource-key work-id generation :aborted stale)
        (work-ledger/clear-handle! frame-id work-id)
        {:rf.db/runtime (work-ledger/update-record
                          runtime-db work-id work-ledger/mark-terminal
                          :suppressed {:reason :stale-reply :outcome :aborted
                                       :completed-at completed-at})})
      ;; LIVE accepted cancellation: settle the entry to a non-error stable
      ;; state, mark the work row terminal :cancelled, drain a route blocking
      ;; slot like a non-error settle. NO :error / :refresh-error write.
      (let [entry' (entry-abort-settled entry)
            rdb'   (-> runtime-db
                       (assoc-in (state/entry-path resource-key) entry')
                       (work-ledger/update-record
                         work-id work-ledger/mark-terminal
                         :cancelled {:reason :aborted :completed-at completed-at})
                       (route/drain-blocking resource-key entry' :success))]
        (work-ledger/clear-handle! frame-id work-id)
        (trace/emit! :rf.event :rf.resource/work-abort-requested
                     {:rf.frame/id frame-id :resource/key resource-key
                      :work/id work-id :generation generation
                      :status-before (:status entry) :status-after (:status entry')})
        {:rf.db/runtime rdb'}))))

;; ---- infinite-feed page reply handlers (EP-0021 R1/R2) --------------------
;;
;; A page fetch (a first ensure's page-0, a refetch's replacement page-0, or a
;; load-more's next page) settles through these reply handlers — DISTINCT from
;; the scalar `succeeded-handler` / `failed-handler` because a page success
;; APPENDS / REPLACES-IN-PLACE a single page (`entry-replace-page`, which
;; delegates to `entry-append-page` when the index is at the tail) rather than
;; overwriting the whole `:data`, and a page failure is the THIRD error channel
;; (`entry-page-failed` keeps the feed + records `:page-error`). They reuse the
;; SAME verification + stale-suppression substrate (`live-entry-for-reply` /
;; `stale-suppress-reply`) so a superseded / cross-frame / restored-dangling
;; page reply can NEVER append to a newer (or another frame's) feed — the
;; monotonic generation allocator guarantees a pre-supersession page reply's
;; generation never matches the live feed.

(defn page-succeeded-handler
  "`:rf.resource.internal/page-succeeded` — an infinite-feed page fetch
  succeeded (EP-0021 R1/R2). Re-lifts the transport's PUBLIC reply into the
  canonical reply map, verifies frame + `:work/id` + generation against the
  live feed entry, and on match APPENDS / REPLACES-IN-PLACE the decoded page at
  the reply's `:rf.resource/page-index` (`entry-replace-page` — an append when
  the index is at the tail, the load-more / page-0 case; an in-place replace for
  a window-preserving refetch's page-0), recording the resolved
  `:rf.resource/page-param` in `:page-params`, recomputing `:next-page-param` /
  `:prev-page-param`, clearing `:page-error`, and returning the feed to
  `:loaded` (structural sharing preserves every unchanged page). A stale /
  superseded / cross-frame page reply is SUPPRESSED — it MUST NEVER mutate a
  newer feed. Per Spec 016 §Causal event — load-more.

  Event shape: `[_ <verification-payload> <http-result>]` — the managed-HTTP
  transport appends `{:kind :success :value <decoded-page>}` as the last arg;
  the decoded page is read from there (with the `:data` durable-layer fallback
  for direct-dispatch tests) and appended."
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms}
   [_event-id {work-id :work/id resource-key :resource/key
               :keys [generation]
               page-param :rf.resource/page-param
               page-index :rf.resource/page-index :as payload} http-result]]
  (let [runtime-db   (or rt {})
        page         (rreply/transport-success-value payload http-result :data)
        completed-at time-ms
        reply        (rreply/success-reply payload page
                                           {:work-kind rreply/work-kind-resource
                                            :completed-at completed-at})
        entry        (live-entry-for-reply runtime-db frame-id payload)]
    (if (nil? entry)
      ;; STALE SUPPRESSION (mandatory) — a superseded / vanished / cross-frame
      ;; page reply never appends to a newer feed. Recorded `:status :stale` /
      ;; `:work/status :suppressed` through the shared reply substrate, exactly
      ;; as the scalar success path does.
      (let [stale (stale-suppress-reply runtime-db resource-key payload
                                        {:outcome :page-success})]
        (emit-resource-stale-suppressed!
          frame-id resource-key work-id generation :page-success stale)
        (work-ledger/clear-handle! frame-id work-id)
        {:rf.db/runtime (work-ledger/update-record
                          runtime-db work-id work-ledger/mark-terminal
                          :suppressed {:reason :stale-reply :outcome :page-success})})
      (let [spec      (registry/resource-meta (:resource/id entry))
            decoded   (:value reply)
            loaded-at (:completed-at reply)
            stale-at  (state/stale-at-for spec loaded-at)
            stale-delay-ms (state/positive-or-nil (:stale-after-ms spec))
            gc-delay-ms    (state/positive-or-nil (:gc-after-ms spec))
            entry'    (state/entry-replace-page
                        entry {:page decoded :page-param page-param
                               :page-index page-index
                               :next-page-param-fn (:next-page-param spec)
                               :prev-page-param-fn (:prev-page-param spec)
                               :loaded-at loaded-at :stale-at stale-at})
            poll-delay-ms (when (seq (:active-owners entry'))
                            (state/positive-or-nil (:poll-interval-ms spec)))
            rdb'      (-> runtime-db
                          (assoc-in (state/entry-path resource-key) entry')
                          (work-ledger/update-record
                            work-id work-ledger/mark-terminal
                            :completed {:loaded-at loaded-at :page-index page-index})
                          (work-ledger/prune-terminal-for-key resource-key)
                          ;; route blocking: a route-owned blocking infinite feed
                          ;; blocks on page 0 — drain the slot when page 0 lands.
                          (route/drain-blocking resource-key entry' :success))]
        (work-ledger/clear-handle! frame-id work-id)
        (trace/emit! :rf.event :rf.resource/work-completed
                     {:rf.frame/id frame-id :resource/key resource-key
                      :work/id work-id :generation generation :status :completed})
        (trace/emit! :rf.event :rf.resource/page-appended
                     {:rf.frame/id frame-id :resource/key resource-key
                      :work/id work-id :generation generation
                      :page-index page-index :page-count (state/page-count entry')
                      :next-page-param (:next-page-param entry')
                      :terminal? (state/terminal? (:next-page-param entry'))})
        (cond-> {:rf.db/runtime rdb'}
          (or stale-delay-ms gc-delay-ms poll-delay-ms)
          (assoc :fx [[:rf.resource/schedule-timers
                       {:frame-id       frame-id
                        :resource/key   resource-key
                        :stale-delay-ms stale-delay-ms
                        :gc-delay-ms    gc-delay-ms
                        :poll-delay-ms  poll-delay-ms
                        :server?        (state/server-frame? frame-id)}]]))))))

(defn page-failed-handler
  "`:rf.resource.internal/page-failed` — an infinite-feed page fetch failed
  (EP-0021 R2 — the THIRD error channel). Verifies frame + work-id +
  generation; on match the feed returns to `:loaded`, KEEPS ALL accumulated
  pages, and records `:page-error` (`entry-page-failed`) — distinct from a
  first-load `:error` and a whole-feed `:refresh-error`, so a view shows
  \"couldn't load more — retry\" without losing the feed. A stale / superseded /
  cross-frame reply is SUPPRESSED. An ABORT (`:rf.http/aborted`) is a
  cancellation, not a page error: the feed settles to `:loaded` (data kept)
  with NO `:page-error` written, and the work row settles `:cancelled`.

  Event shape: `[_ <verification-payload> <http-result>]` — the transport
  appends `{:kind :failure :failure <:rf.http/* envelope>}` as the last arg."
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms}
   [_event-id {work-id :work/id resource-key :resource/key :keys [generation] :as payload} http-result]]
  (let [runtime-db   (or rt {})
        error        (rreply/transport-failure-envelope payload http-result)
        completed-at time-ms
        reply        (rreply/failure-reply payload error
                                           {:work-kind rreply/work-kind-resource
                                            :completed-at completed-at})
        aborted?     (= :cancelled (:status reply))
        entry        (live-entry-for-reply runtime-db frame-id payload)]
    (cond
      ;; STALE SUPPRESSION (mandatory) — stale wins over the natural status
      ;; (a stale abort settles :suppressed, never an accepted :cancelled).
      (nil? entry)
      (let [stale (stale-suppress-reply runtime-db resource-key payload
                                        {:outcome (if aborted? :aborted :page-failure)})]
        (emit-resource-stale-suppressed!
          frame-id resource-key work-id generation
          (if aborted? :aborted :page-failure) stale)
        (work-ledger/clear-handle! frame-id work-id)
        {:rf.db/runtime (work-ledger/update-record
                          runtime-db work-id work-ledger/mark-terminal
                          :suppressed {:reason :stale-reply
                                       :outcome (if aborted? :aborted :page-failure)
                                       :completed-at completed-at})})

      ;; ABORT — an intentional cancellation of the page fetch. The feed keeps
      ;; its pages and returns to :loaded WITHOUT a :page-error (a cancellation
      ;; is not a load-more failure). The work row settles terminal :cancelled.
      aborted?
      (let [entry' (assoc entry :status :loaded :current-work nil)
            rdb'   (-> runtime-db
                       (assoc-in (state/entry-path resource-key) entry')
                       (work-ledger/update-record
                         work-id work-ledger/mark-terminal
                         :cancelled {:reason :aborted :completed-at completed-at})
                       (route/drain-blocking resource-key entry' :success))]
        (work-ledger/clear-handle! frame-id work-id)
        (trace/emit! :rf.event :rf.resource/work-abort-requested
                     {:rf.frame/id frame-id :resource/key resource-key
                      :work/id work-id :generation generation
                      :status-before (:status entry) :status-after (:status entry')})
        {:rf.db/runtime rdb'})

      ;; PAGE FAILURE — the third error channel: keep the feed, record
      ;; :page-error, return to :loaded. (`route/drain-blocking … :failure`
      ;; only flips a blocking FIRST-load — a feed that already has pages keeps
      ;; :loaded, so the slot drains like a success; a blocking page-0 failure
      ;; with no prior pages surfaces the route error via entry-page-failed
      ;; keeping :status :loaded — but a blocking infinite route blocks on page
      ;; 0, which on first failure has no data, so drain-blocking keys on the
      ;; entry's :status. entry-page-failed always returns :loaded, so a route
      ;; never errors on a load-more page failure — exactly the intent.)
      :else
      (let [entry' (state/entry-page-failed entry {:error error})
            rdb'   (-> runtime-db
                       (assoc-in (state/entry-path resource-key) entry')
                       (work-ledger/update-record
                         work-id work-ledger/mark-terminal
                         :failed {:error error :completed-at completed-at})
                       (route/drain-blocking resource-key entry' :success))]
        (work-ledger/clear-handle! frame-id work-id)
        (trace/emit! :rf.event :rf.resource/work-completed
                     {:rf.frame/id frame-id :resource/key resource-key
                      :work/id work-id :generation generation :status :failed})
        (trace/emit! :rf.event :rf.resource/page-failed
                     {:rf.frame/id frame-id :resource/key resource-key
                      :work/id work-id :generation generation
                      :status-before (:status entry) :status-after (:status entry')
                      :page-error error})
        {:rf.db/runtime rdb'}))))

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
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms}
   [_event-id {resource-key :resource/key}]]
  (let [runtime-db (or rt {})
        entry      (get-in runtime-db (state/entry-path resource-key))
        ;; re-derive staleness from the DURABLE :stale-at (the timer is
        ;; advisory — never trust "the timer fired on time"). An entry
        ;; re-loaded since the timer armed has a future :stale-at and is not
        ;; yet stale, so the re-check naturally no-ops. Shared derivation
        ;; (`state/entry-stale?`) so it never drifts from the subs / SSR view.
        ;;
        ;; EP-0010 §Resources / §The World-Input Rule (rf2-95b0lc) + EP-0017
        ;; declared-only delivery (rf2-601ife): a TIMER-FIRE event's freshness
        ;; re-check uses the timer-fire envelope's own causal `:rf/time-ms`
        ;; (DECLARED via `:rf.cofx/requires`, consumed FLAT), not an ambient
        ;; `(now-ms)` host read. This handler writes nothing durable (the
        ;; decision is trace-only — the durable `:stale-at` already drives the
        ;; `:stale?` sub), but the recorded `:decision` must be replay-stable: a
        ;; replayed `:stale-fired` under a later live clock must classify the
        ;; entry the same way the recorded `:rf/time-ms` did.
        stale?     (state/entry-stale? entry time-ms)]
    (trace/emit! :rf.event :rf.resource/stale-fired
                 {:rf.frame/id frame-id :resource/key resource-key
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
    - the entry is gone — no-op (`:no-entry`); nothing to GC or reschedule;
    - the entry has an active owner — pinned alive, no GC (`:has-owner`);
    - the entry has work in flight (`:current-work`) — no GC (`:in-flight`);
    - otherwise the entry is owner-free + idle — REMOVE it (recompute the
      reverse indexes) and cancel its advisory timers.

  RESCHEDULE-ON-SKIP (rf2-07693y): a GC timer that fires while the entry is
  still OWNED or IN-FLIGHT must NOT just skip and leave the entry uncollectable
  — if the owner later releases or the work settles AFTER the original
  `:gc-after-ms` deadline, nothing would re-fire and the now-inactive entry
  would linger indefinitely. So a `:has-owner` / `:in-flight` skip RE-ARMS a
  fresh GC timer (via `schedule-timers`, which cancel-then-arms — this also
  cleans up the FIRED one-shot handle still sitting in the side table). The
  re-armed timer fires another re-check after `:gc-after-ms`; once the entry is
  owner-free + idle that re-check collects it deterministically. A `:no-entry`
  skip does NOT reschedule (there is nothing left to collect; `cancel-timers`
  on the removal path already released the handles). A resource declaring no
  `:gc-after-ms` never armed a GC timer in the first place, so it never skips
  here — but the reschedule fx is gated on a positive delay for safety."
  [{rt :rf.db/runtime, frame-id :rf.frame/id}
   [_event-id {resource-key :resource/key}]]
  (let [runtime-db (or rt {})
        entry      (get-in runtime-db (state/entry-path resource-key))]
    (if (and entry (empty? (:active-owners entry)) (nil? (:current-work entry)))
      ;; rf2-9e0tyq — `:entries` is keyed on the byte `key-id`; dissoc by it
      ;; (a dissoc by the scoped-key VECTOR would be a no-op and the GC removal
      ;; would silently leak the entry).
      (let [rdb' (-> runtime-db
                     (update-in (state/entries-path) dissoc (state/key-id resource-key))
                     (update state/resources-key state/recompute-indexes))]
        (trace/emit! :rf.event :rf.resource/gc-fired
                     {:rf.frame/id frame-id :resource/key resource-key})
        {:rf.db/runtime rdb'
         ;; the entry is gone — cancel its (now orphaned) stale / GC timer
         ;; handles so they don't leak (the GC timer that fired is already
         ;; one-shot, but the paired stale timer may still be armed).
         :fx [[:rf.resource/cancel-timers
               {:frame-id frame-id :resource/keys [resource-key]}]]})
      (let [reason   (cond (nil? entry)                  :no-entry
                           (seq (:active-owners entry))  :has-owner
                           :else                         :in-flight)
            ;; rf2-07693y: a still-owned / in-flight skip RE-ARMS the GC timer
            ;; so a later release / work-settle is followed by another GC
            ;; re-check (and the fired one-shot handle is replaced). A
            ;; :no-entry skip has nothing to reschedule. The delay is the
            ;; resource's own :gc-after-ms (positive-guarded — a resource with
            ;; no GC policy never armed one, so it never reaches this branch).
            gc-delay (when (not= :no-entry reason)
                       (state/positive-or-nil (:gc-after-ms (registry/resource-meta (:resource/id entry)))))]
        (trace/emit! :rf.event :rf.resource/gc-skipped
                     {:rf.frame/id frame-id :resource/key resource-key
                      :reason reason
                      :rescheduled? (some? gc-delay)})
        (cond-> {:rf.db/runtime runtime-db}
          gc-delay
          (assoc :fx [[:rf.resource/schedule-timers
                       {:frame-id       frame-id
                        :resource/key   resource-key
                        ;; reschedule the GC re-check ONLY (leave stale as-is —
                        ;; nil disarms that kind in schedule-timers-handler)
                        :stale-delay-ms nil
                        :gc-delay-ms    gc-delay
                        :server?        (state/server-frame? frame-id)}]]))))))

(defn stale-suppressed-handler
  "`:rf.resource.internal/stale-suppressed` — a late reply carrying a
  superseded work-id / generation was suppressed (it MUST NEVER mutate a
  newer entry). This is an internal NOTIFICATION the reply handlers already
  enforce inline (`live-entry-for-reply`); the standalone handler records
  the suppression in trace for tools. Per Spec 016 §Cancellation is
  opportunistic; stale suppression is mandatory."
  [{rt :rf.db/runtime, frame-id :rf.frame/id}
   [_event-id {work-id :work/id resource-key :resource/key :keys [generation] :as payload}]]
  (let [runtime-db (or rt {})
        stale      (stale-suppress-reply runtime-db resource-key payload nil)]
    (emit-resource-stale-suppressed!
      frame-id resource-key work-id generation nil stale)
    {:rf.db/runtime runtime-db}))
