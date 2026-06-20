(ns re-frame.routing.reply
  "Spec 012 — lower route-loader async work onto the uniform reply
  envelope (EP-0011 §Route Loader Completion; the canonical contract is
  `spec/Managed-Effects.md` §The uniform reply envelope).

  ## What this namespace is

  The internal seam that expresses route-loader stale suppression as an
  ORDINARY reply-envelope `:suppress` gate rather than a bespoke
  per-family token mechanism. A route loader's async completion is keyed
  by the route work-id `[:rf.work/route route-id nav-token loader-id]`
  and gated by the data-only suppression map `{:route/nav-token
  nav-token}`, validated against the live `[:rf.runtime/routing :current
  :nav-token]` before the app reply target runs. The PUBLIC routing API
  (the `:rf.route/nav-token` cofx, `:rf.route/with-nav-token`, `:on-match` /
  loaders) is PRESERVED — this is internal lowering only.

  Two concerns, both consuming the shared `re-frame.reply` substrate:

   1. **Work-id correlation** (`work-id`). One route-loader attempt has
      one `:work/id` head `[:rf.work/route route-id nav-token loader-id]`
      (Managed-Effects §Work-id correlation). The nav-token is NOT a
      second stale-suppression key in its own right — it is the value of
      the ONE suppression gate (`:route/nav-token`) AND a component of
      the work-id tuple; the same fact, named once. Per EP-0007 there is
      no `:stale-key`-style synonym.

   2. **Stale-suppression gate** (`gate` / `current-gate` / `suppress?`
      / `suppress`). The carried gate `{:route/nav-token <captured>}` is
      matched against the current gate `{:route/nav-token <live>}` via
      `re-frame.reply/stale?` — the shared correctness boundary. On
      mismatch `re-frame.reply/suppress` produces the `:status :stale`
      reply (no app mutation) and the data-only trace facts joined to
      `:work/id`; the app reply target does NOT run. On match the live
      reply (`:ok` / `:error`, and `:cancelled` for an explicit user
      cancel) flows through to the handler unchanged.

  Pure — no atoms, no dispatch, no I/O. The caller reads the live
  `:nav-token` from frame runtime-db and hands it in; this namespace
  never reaches into runtime state. Trace summaries route every
  wire-bearing slot through the shared `re-frame.reply/trace-summary`
  (which calls `re-frame.elision/elide-wire-value`) — never a
  family-private elider (Managed-Effects §Tracing).

  Internal namespace; the public facade is `re-frame.routing`."
  (:require [re-frame.reply :as reply]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Work-id correlation (Managed-Effects §Work-id correlation).
;;
;; Route head: `[:rf.work/route route-id nav-token loader-id]`. One ATTEMPT
;; has one `:work/id` (the EP-0007 rule): a distinct nav-token (a fresh
;; navigation epoch) is a distinct attempt, so the nav-token rides IN the
;; work-id tuple. The nav-token is the same fact named once — it is the
;; component that discriminates a superseded attempt AND the value of the
;; sole `:route/nav-token` suppression gate; it is never a second
;; stale-suppression key alongside the work-id.
;; ---------------------------------------------------------------------------

(defn work-id
  "Build the route-loader work-id head for one attempt.

  `[:rf.work/route route-id nav-token loader-id]` (Managed-Effects
  §Work-id correlation — the Route-loader row). `=`-comparable and EDN-
  serializable. `loader-id` identifies which `:on-match` loader within
  the navigation produced this attempt; `route-id` is the matched route's
  id; `nav-token` is the navigation epoch the attempt was scheduled in.
  Any component may be nil when the caller has no finer identity (e.g. a
  loader that did not name itself) — the tuple stays a valid, distinct
  work id."
  [{:keys [route-id nav-token loader-id]}]
  [:rf.work/route route-id nav-token loader-id])

;; ---------------------------------------------------------------------------
;; The stale-suppression gate (Managed-Effects §Stale suppression). The
;; nav-token is lowered to the ONE data-only suppression gate
;; `{:route/nav-token <token>}` — exactly the gate shape EP-0011
;; §Specification and the §The reply target descriptor example use
;; (`:suppress {:route/nav-token "nav-7"}`). The carried gate is captured at
;; scheduling time; the current gate is read from the live route slice at
;; completion. They are compared by the shared `re-frame.reply/stale?` — no
;; bespoke comparison.
;; ---------------------------------------------------------------------------

(defn gate
  "Build the data-only suppression gate for a captured nav-token:
  `{:route/nav-token <nav-token>}`. This is the ONE gate the route
  family validates (Managed-Effects §Stale suppression: \"route
  `:nav-token` still matches `[:rf.runtime/routing :current
  :nav-token]`\"). Pure."
  [nav-token]
  {:route/nav-token nav-token})

(defn current-gate
  "Build the CURRENT gate from the live route-slice token (read by the
  caller from `[:rf.runtime/routing :current :nav-token]`):
  `{:route/nav-token <current>}`. The counterpart `gate` carries the
  captured token; `re-frame.reply/stale?` compares the two. Pure."
  [current-nav-token]
  {:route/nav-token current-nav-token})

(defn suppress?
  "True when a completion carrying `carried-nav-token` is stale relative
  to `current-nav-token` (a superseding navigation advanced the epoch),
  so its app reply MUST be suppressed. Delegates to the shared
  `re-frame.reply/stale?` over the `:route/nav-token` gate — the route
  family does NOT re-implement the comparison; this is identity-equal to
  the original `(= carried current)` check, just expressed as the shared
  gate. A nil captured token under a live navigation is suppressed (the
  present gate `{:route/nav-token nil}` never equals the active token —
  the regression guard for the pre-fix nil-cofx bug); a nil captured
  token against a nil current (no live navigation) matches."
  [carried-nav-token current-nav-token]
  (reply/stale? (gate carried-nav-token) (current-gate current-nav-token)))

;; ---------------------------------------------------------------------------
;; Stale suppression — THE correctness boundary, delegated to the shared
;; `re-frame.reply/suppress` (Managed-Effects §Stale suppression). The route
;; family supplies the carried + current gates and the route work-id; the
;; substrate produces the `:status :stale` reply + the data-only trace facts
;; joined to `:work/id`, and signals non-delivery.
;; ---------------------------------------------------------------------------

(def stale-reason
  "The `:stale/reason` a superseded route completion carries: the
  navigation epoch moved on. Named once (EP-0007)."
  :rf.route/nav-token-stale)

;; ---------------------------------------------------------------------------
;; Live completion (rf2-2avo53). When the carried nav-token is STILL current,
;; the route-loader completion is a LIVE reply (`:status :ok` — `:partial` is
;; not a route-loader case; HTTP decode lands `:ok`/`:error` and the route
;; gate runs on top). The route wrapper lowers the live continuation onto the
;; uniform reply envelope: it builds the data-only reply map and `complete`s
;; the `:rf/reply-to` target through the shared `re-frame.reply/complete` — the
;; same pure functor the EP-0011 mapping law is stated over — so the reply map
;; is appended as the final argument of the target event and the target's
;; accumulated `::post` transform is applied. The route surface therefore
;; exercises `re-frame.reply/complete` at the ACTUAL navigation wrapper, not
;; only in the pure unit helper.
;; ---------------------------------------------------------------------------

(defn live-reply
  "Build the LIVE `:status :ok` route-loader reply map for a completion whose
  carried nav-token is still current (Managed-Effects §The reply map / §Status
  taxonomy). Carries the route `:work/id` + `:work/kind :route`, the loader's
  decoded result `:value` (the reply-result spelling EVERYWHERE — EP-0007;
  `nil` for a loader that produced no payload — `:ok` REQUIRES `:value`
  present, so it rides even when nil rather than being omitted), the carried
  frame stamp, and the causal `:completed-at` (EP-0010 — the reply token's
  reading, never a fresh ambient clock read). Frame / completed-at are omitted
  when absent.

  `ctx` is `{:route-id … :nav-token <carried> :loader-id … :frame …
  :completed-at …}` — the same identity-fact map `suppress` consumes, so a live
  and a stale completion correlate by the identical `:work/id`."
  ([ctx] (live-reply ctx nil))
  ([{:keys [frame completed-at] :as ctx} value]
   (cond-> {:status      :ok
            ;; `:ok` REQUIRES `:value` present (Managed-Effects §Status
            ;; taxonomy / `validate-reply`); a successful load with no payload
            ;; rides `:value nil` rather than omitting the slot.
            :value       value
            :work/id     (work-id ctx)
            :work/kind   :route
            :work/status :completed}
     (some? frame)        (assoc :rf.frame/id frame)
     (some? completed-at) (assoc :completed-at completed-at))))

(defn complete-live
  "Complete the `:rf/reply-to` `target` for a LIVE route-loader completion:
  build the `:status :ok` reply map (`live-reply`) and append it to the
  target's event via the shared `re-frame.reply/complete` — the pure functor
  the EP-0011 mapping law (`complete (map-completed-event f t) == f (complete t)`) is
  stated over. Returns the dispatchable completed event vector (the target
  event with the reply map appended, then the target's `::post` transform
  applied), or nil when `target` is nil (no continuation).

  This is the production lowering the route wrapper drives on the live branch
  (rf2-2avo53): the route surface normalizes + completes a `:rf/reply-to`
  target through the shared substrate, rather than running an ad-hoc `:do` fx
  entry that never touches the reply envelope."
  ([ctx target] (complete-live ctx target nil))
  ([ctx target value]
   (reply/complete target (live-reply ctx value))))

(defn suppress
  "Produce the stale-suppression outcome for a route-loader completion
  whose carried nav-token has been superseded — WITHOUT dispatching the
  app reply target. Delegates to the shared `re-frame.reply/suppress`
  (the correctness boundary made concrete): the returned `:reply` is
  `:status :stale` (no `:value`, no app mutation) and `:deliver?` is
  false.

  Arguments:
    `ctx`     — `{:route-id … :nav-token <carried> :loader-id … :frame …
                 :completed-at …}`; supplies the route work-id and the
                carried nav-token, plus any identity facts to ride the
                stale reply verbatim.
    `current` — the LIVE nav-token read from
                `[:rf.runtime/routing :current :nav-token]` at completion.
    `target`  — the (optional) reply target, consulted only for its
                `:dispatch-stale?` opt-in (framework test / tool only).

  Returns the `re-frame.reply/suppress` shape:
    {:deliver?    <bool>          ;; false ⇒ DO NOT run the app reply target
     :reply       <:status :stale reply, data-only, app-state-safe>
     :work/status :suppressed     ;; the ledger terminal for a stale route load
     :trace       <data-only facts carrying CARRIED + CURRENT gates, joined
                   to :work/id>}

  The trace facts are joined to `:work/id` per the Route-Loader-Completion
  contract; the caller maps them onto the existing `:rf.route.nav-token/
  stale-suppressed` trace operation (carried-token / current-token /
  event-id ride alongside `:work/id`)."
  ([ctx current] (suppress ctx current nil))
  ([{:keys [nav-token frame completed-at] :as ctx} current target]
   (let [wid (work-id ctx)]
     (reply/suppress target
                     (gate nav-token)
                     (current-gate current)
                     (cond-> {:status       :stale
                              :work/id      wid
                              :work/kind    :route
                              :stale/reason stale-reason}
                       (some? frame)        (assoc :rf.frame/id frame)
                       (some? completed-at) (assoc :completed-at completed-at))))))

;; ---------------------------------------------------------------------------
;; Trace summary (Managed-Effects §Tracing). A data-only summary of a route
;; reply map for a managed-async trace row, routing every wire-bearing slot
;; through the shared `re-frame.reply/trace-summary` → `re-frame.elision/
;; elide-wire-value` walker. Never a family-private elider.
;; ---------------------------------------------------------------------------

(defn trace-reply
  "Build a DATA-ONLY trace summary of a route reply map for a
  managed-async trace row. The wire-bearing slots (`:value`, `:error`,
  `:correlation`, `:meta`) elide through the single shared
  `re-frame.elision/elide-wire-value` walker (via
  `re-frame.reply/trace-summary`) — never a family-private elider
  (Managed-Effects §Tracing); the identity facts (`:status`, `:work/id`,
  `:work/kind`, `:work/status`, `:rf.frame/id`, `:completed-at`) ride
  verbatim. `opts` is forwarded to the walker (e.g. `:frame`)."
  ([reply] (trace-reply reply nil))
  ([reply opts] (reply/trace-summary reply opts)))
