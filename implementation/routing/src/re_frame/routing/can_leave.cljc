(ns re-frame.routing.can-leave
  "`:can-leave` + `:can-enter` gating + the pending-nav protocol for
  re-frame2 routing.

  Per Spec 012 §Navigation blocking — pending-nav protocol. `:can-enter`
  is the first-class mirror of `:can-leave` (rf2-p69yaz Option A): ONE
  gate consulted for EVERY entry door — `:rf.route/navigate`,
  `:rf.route/url-requested` (link clicks), and `:rf.route/transitioned` /
  `:rf.route/handle-url-change` (popstate / deep-links) — evaluates the
  CURRENT route's `:can-leave` THEN the TARGET route's `:can-enter`. Owns:

    - `guard-query` / `guard-id` / `guard?` — the shared `:can-leave` /
      `:can-enter` sub resolution + the closed-contract boolean check
      (rf2-5pyyl / rf2-p69yaz). The guard sub receives the pending TARGET
      appended as an argument (repairs the 012:1196 fragment-check hole);
    - `pending-target` — the `{:route-id :params :query :fragment :url}`
      map appended to both guard queries, derived from the requested URL
      via `match-url` (canonical, round-trips);
    - `maybe-block-navigation` — the unified leave-THEN-enter gate used
      by `:rf.route/navigate`, `:rf.route/transitioned`,
      `:rf.route/handle-url-change`, and `:rf.route/url-requested`;
    - `:rf.route/url-requested` — the link-click + programmatic URL-request
      entry point (preventDefault + pushState + :rf.route/transitioned);
    - `:rf.route/continue` / `:rf.route/cancel` — pending-nav protocol
      resolution events. `:rf.route/continue` bypasses `:leave` only and
      RE-RUNS `:can-enter` (rf2-p69yaz point 6);
    - `:rf.route/navigation-blocked` / `:rf.route/entry-blocked` — the
      default no-op user events runtime dispatches on a leave / enter
      block respectively (apps re-register with their own confirm-dialog
      / redirect / analytics handler).

  Internal namespace; the public facade is `re-frame.routing`. The
  facade registers the five events
  (`events/reg-event :rf.route/url-requested`, `:rf.route/navigation-blocked`,
  `:rf.route/entry-blocked`, `:rf.route/continue`, `:rf.route/cancel`) so
  a `:reload` of the façade re-wires them on a fresh registrar (the
  `clear-all!` test-fixture path). Per the rf2-2yabr cohesion split:
  CAN-LEAVE + CAN-ENTER + PENDING-NAV seam."
  (:require [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.routing.egress :as egress]
            [re-frame.routing.registry :as registry]
            [re-frame.routing.url :as url]
            [re-frame.trace :as trace]))

;; ---------------------------------------------------------------------------
;; Guard resolution — shared by `:can-leave` (current route) and
;; `:can-enter` (target route). Per Spec 012 §Navigation blocking the two
;; guards are STRUCTURAL MIRRORS: same normalised-query shape, same closed
;; boolean contract, same non-boolean → block + error-id discipline. The
;; ONLY differences are (a) WHICH route-metadata key names the sub
;; (`:can-leave` vs `:can-enter`), (b) which route it reads from (current
;; vs target), and (c) the error-id on a non-boolean return
;; (`:rf.error/can-leave-non-boolean` vs `:rf.error/can-enter-non-boolean`).
;; Holding the resolution in one shared helper keeps the two guards from
;; drifting.
;; ---------------------------------------------------------------------------

(defn- guard-query
  "Normalise a route-metadata guard slot (`:can-leave` / `:can-enter`)
  into a subscription query vector, or nil when the slot is absent.
  `(vector? declared)` → declared; `(keyword? declared)` → `[declared]`."
  [route-meta guard-key]
  (let [declared (get route-meta guard-key)]
    (cond
      (vector? declared) declared
      (keyword? declared) [declared]
      :else nil)))

(defn- guard-id
  "The guard's sub-id — the head of the normalised guard query.
  `(first [kw])` is `kw`, `(first vec)` is its head, `(first nil)` is
  nil, so the single `guard-query` normalisation covers every case."
  [route-meta guard-key]
  (first (guard-query route-meta guard-key)))

(defn- guard?
  "Resolve and call a route's `:can-leave` / `:can-enter` sub against the
  live frame, passing the pending TARGET appended as an argument.

  Per Spec 012 §Navigation blocking §Default flow (rf2-5pyyl /
  rf2-p69yaz): the guard contract is closed — only the literals `true`
  (allow) and `false` (block) are accepted. Any non-boolean return BLOCKS
  and emits the structured `error-id`, forcing the author to write
  `(boolean ...)` / `(not ...)` rather than rely on truthiness (the
  classic polarity bug: a sub returning the dirty-flag value silently let
  the user navigate away and lose form state).

  rf2-p69yaz point 2: the pending TARGET map (`{:route-id :params :query
  :fragment :url}`) is appended to the guard query as an ARGUMENT — the
  guard-sub receives `[<guard-id> <pending-target>]`, so `:can-leave` can
  finally implement the 012:1196 fragment-check contract (compare the
  current `:rf.route/fragment` against the requested fragment) and
  `:can-enter` can branch on WHERE the user is heading (auth on the target
  route's `:tags`, entering a wizard step out of order, …). A guard that
  ignores the extra arg (the common `:<- [:editor/dirty?]` shape) is
  unaffected — the arg rides in the query vector's tail.

  Returns `false` (block) when:
    - the sub returns the literal value `false`;
    - the sub returns any non-boolean value — emits the structured
      `error-id` trace and blocks.

  Returns `true` (proceed) when:
    - no guard is declared for `guard-key` (no guard);
    - the sub returns the literal value `true`;
    - `:subs/subscribe-once` is unset (consumer opted out of the subs
      artefact; the runtime has no way to evaluate the sub, so it cannot
      fail the closed contract). The warning
      `:rf.warning/can-leave-subs-artefact-missing` fires so tooling
      surfaces the misconfiguration (shared across both guards — the
      missing-artefact condition is guard-agnostic).

  `route-id` is the guarded route's id keyword — threaded in so the
  non-boolean trace tags the real id rather than the route's `:path`
  pattern string.

  rf2-dbmj6x — `frame` is BOTH the live frame the guard sub resolves
  against AND the in-flight cascade's frame stamp the diagnostic traces
  tag under `:tags :frame`."
  [frame route-id route-meta guard-key target error-id]
  (if-let [query (guard-query route-meta guard-key)]
    (if-let [subscribe-once (late-bind/get-fn :subs/subscribe-once)]
      ;; rf2-p69yaz point 2: append the pending target so the guard sub
      ;; receives it as `(fn [inputs [_ target] ...])`. Repairs the
      ;; 012:1196 unimplementable fragment-check hole for `:can-leave` and
      ;; gives `:can-enter` the destination to branch on.
      (let [query-with-target (conj (vec query) target)
            v (subscribe-once query-with-target {:frame frame})]
        (cond
          (true?  v) true
          (false? v) false
          :else
          ;; rf2-5pyyl / rf2-p69yaz closed contract: non-boolean BLOCKs +
          ;; emits `error-id`. rf2-dbmj6x: stamp the carried `:frame` so the
          ;; error reaches epoch capture / Xray.
          (do (trace/emit-error! error-id
                                 (cond-> {:route-id route-id
                                          :query    query
                                          :value    v
                                          :reason   (str "Non-boolean returned from " guard-key
                                                         " sub; the contract requires true (allow) "
                                                         "or false (block). Did you mean (boolean ...) "
                                                         "or (not ...)?")
                                          :recovery :blocked-navigation}
                                   frame (assoc :frame frame)))
              false)))
      (do (trace/emit! :warning :rf.warning/can-leave-subs-artefact-missing
                       (cond-> {:query query}
                         frame (assoc :frame frame)))
          true))
    true))

(defn- can-leave?
  "The current route's `:can-leave` guard. See `guard?`."
  [frame route-id route-meta target]
  (guard? frame route-id route-meta :can-leave target
          :rf.error/can-leave-non-boolean))

(defn- can-enter?
  "The target route's `:can-enter` guard. See `guard?`. rf2-p69yaz Option
  A: enter-gating is a first-class mirror of leave-gating — same closed
  boolean contract, same pending-target argument, its own error-id."
  [frame route-id route-meta target]
  (guard? frame route-id route-meta :can-enter target
          :rf.error/can-enter-non-boolean))

(defn- pending-target
  "Resolve the pending TARGET the guards branch on, from the requested
  URL string via `match-url` (rf2-p69yaz point 2). The URL is the
  canonical, round-tripping representation every entry door shares —
  `:rf.route/url-requested` / `:rf.route/transitioned` / `:rf.route/
  handle-url-change` all carry a URL, and `:rf.route/navigate` passes its
  BUILT url — so deriving the target here keeps ONE gate uniform across
  all doors without each caller pre-computing a target shape.

  Returns `{:route-id :params :query :fragment :url}`. On a URL that
  matches no route the `match-url` half is nil and only `:url` is
  populated (the target is `:rf.route/not-found`-shaped but the gate
  still evaluates `:can-leave` on the CURRENT route — leaving a page for
  a dead link is still a leave). A `:can-enter` guard on a matched target
  reads its own route from the registry via `route-id`.

  rf2-dqlfty: this runs on EVERY nav-guard evaluation — even when the
  route declares no `:can-leave` / `:can-enter` at all, `maybe-block-
  navigation` calls `pending-target` unconditionally before checking
  whether a guard is declared. A raw `match-url` call here, left
  unhandled, would let any unexpected throw escape the guard phase and
  crash the event drain (rf2-6t1xb) for `:rf.route/navigate`,
  `:rf.route/url-requested`, `:rf.route/transitioned`, and
  `:rf.route/handle-url-change` alike. `match-url-fail-closed` catches
  ANY throw and yields a nil match instead, so a hostile/throwing URL
  degrades to the same `:rf.route/not-found`-shaped target as a bare
  miss — exactly the fail-closed discipline `url-change-fx` and
  `:rf.route/navigate`'s `{:url ...}` target-form already apply at the
  commit phase."
  [requested-url]
  (let [{:keys [match]} (registry/match-url-fail-closed requested-url)
        {:keys [route-id params query fragment]} match]
    {:route-id route-id
     :params   params
     :query    query
     :fragment fragment
     :url      requested-url}))

(defn- current-slice->url
  "Rebuild the URL the current route slice represents, for restoring the
  browser address bar on a blocked popstate (rf2-ede1h.3). Returns the
  URL string, or nil when the slice cannot be rebuilt (no current route,
  a `:rf.route/not-found` slice with no registered pattern, or a
  `route-url` throw). Best-effort: a nil result simply omits the restore
  fx — the slice + blocked-state are already correct; only the URL sync
  is skipped in the rare unbuildable case.

  Built from the slice's `:route-id` / `:params` / `:query` / `:fragment` via
  the pure `route-url` builder — the exact inverse `match-url` used to
  populate the slice in the first place, so the restored URL matches the
  one the browser showed before the (rejected) Back/Forward."
  [current-route]
  (let [{:keys [route-id params query fragment]} current-route]
    (when (and route-id (keyword? route-id) (registrar/lookup :route route-id))
      (try
        (registry/route-url route-id (or params {}) (or query {}) fragment)
        (catch #?(:clj Throwable :cljs :default) _ nil)))))

;; ---------------------------------------------------------------------------
;; The ONE navigation gate: `:can-leave` (current) THEN `:can-enter`
;; (target), for every entry door. rf2-p69yaz Option A.
;; ---------------------------------------------------------------------------

(defn- event-opts
  "Extract the trailing opts map from a nav event vector — the slot the
  resume-chain carries `:rf.route/enter-attempts` in. `:rf.route/navigate`
  keeps opts in the 4th slot (`[_ target params opts]`); the URL-driven
  events + `:rf.route/url-requested` keep it in the 2nd (`[_ url-or-request
  opts?]` — for `:rf.route/url-requested` the request map itself IS the opts
  carrier). Returns a map (possibly empty)."
  [event-vec]
  (let [event-id (first event-vec)]
    (case event-id
      :rf.route/navigate          (or (nth event-vec 3 nil) {})
      :rf.route/url-requested           (let [a (second event-vec)] (if (map? a) a {}))
      (:rf.route/transitioned
       :rf.route/handle-url-change) (or (nth event-vec 2 nil) {})
      {})))

(defn- loop-count
  "The number of times THIS resume chain has already re-run the enter gate
  for the same target (rf2-p69yaz point 7 — loop detection). The count
  rides the re-issued event's `:rf.route/enter-attempts` opt, threaded
  forward by `:rf.route/continue` (which clears the pending slot BEFORE
  re-dispatching, so the count can't live only on the slot). Zero on a
  first (non-resume) navigation."
  [event-vec]
  (long (or (:rf.route/enter-attempts (event-opts event-vec)) 0)))

(def ^:private loop-guard-limit
  "The enter-gate re-run ceiling (rf2-p69yaz point 7). A `:can-enter` that
  blocks the SAME target this many times across resume attempts is a loop
  — the gate fails closed with `:rf.error/route-guard-loop` and STOPS
  re-issuing rather than spinning `continue → block → continue`."
  8)

(defn maybe-block-navigation
  "Run the CURRENT route's `:can-leave` guard THEN the TARGET route's
  `:can-enter` guard before allowing a transition to `requested-url`
  (rf2-p69yaz Option A — one gate, every door). Returns nil when the
  navigation should proceed (no guard, guards allow, or the relevant
  guard is bypassed); returns the effects map `{:rf.db/runtime ... :fx ...}`
  that writes the routing pending-navigation slot
  (`[:rf.runtime/routing :pending-navigation]`) and dispatches
  `:rf.route/navigation-blocked` (leave block) or `:rf.route/entry-blocked`
  (enter block) when a guard blocks.

  Public so the four event entry points (`:rf.route/navigate`,
  `:rf.route/transitioned`, `:rf.route/handle-url-change`,
  `:rf.route/url-requested`) share ONE gate; the per-event handler `or`s the
  block result with its happy-path cofx so a single failure path
  collapses cleanly.

  `bypass-guards` is the `:bypass-guards?` opt SET (rf2-p69yaz point 8):
  `#{:leave}` skips the leave gate, `#{:enter}` skips the enter gate,
  `#{:leave :enter}` skips both. A `:rf.route/continue` resume passes
  `#{:leave}` — the leave was already confirmed by the user, but the
  enter gate RE-RUNS (point 6).

  Per Spec 012 §Navigation blocking §Default flow step 4c — *the URL does
  not change* on a block. A POPSTATE leave block restores the address bar
  (rf2-ede1h.3, see `current-slice->url`); an enter block through
  popstate does the same (the browser already moved to the target the
  enter gate rejects).

  rf2-vcop6y: `pending-nav-allocation` is the RECORDABLE allocation
  `{:id \"pn-N\" :counter N}` delivered by the generator-backed
  `:rf.route/pending-nav-allocation` cofx."
  [rdb frame-id event-vec requested-url bypass-guards pending-nav-allocation]
  (let [bypass         (cond
                         (set? bypass-guards)     bypass-guards
                         ;; nil / false → nothing bypassed.
                         :else                    #{})
        current-route  (get-in rdb [:rf.runtime/routing :current])
        current-meta   (registrar/lookup :route (:route-id current-route))
        target         (pending-target requested-url)
        target-meta    (registrar/lookup :route (:route-id target))
        ;; rf2-p69yaz: current route's :can-leave FIRST, then target's
        ;; :can-enter. The guard subs receive the pending target appended.
        leave-ok?      (or (contains? bypass :leave)
                           (can-leave? frame-id (:route-id current-route) current-meta target))
        ;; Only evaluate the enter gate when the leave gate passed (a
        ;; blocked leave never reaches the target). rf2-p69yaz point 7:
        ;; when the enter gate re-runs past the loop ceiling, it is a
        ;; guard loop — fail closed rather than re-issue forever.
        attempts       (loop-count event-vec)
        looping?       (and leave-ok?
                            (not (contains? bypass :enter))
                            (guard-query target-meta :can-enter)
                            (>= attempts loop-guard-limit))
        enter-ok?      (or (contains? bypass :enter)
                           (not leave-ok?)  ;; leave already blocked; don't run enter
                           looping?         ;; loop → handled below, not an enter "pass"
                           (can-enter? frame-id (:route-id target) target-meta target))
        ;; Which guard blocked (nil = proceed). The loop case is a distinct
        ;; fail-closed outcome, surfaced as an enter block carrying the
        ;; loop error.
        direction      (cond
                         looping?          :enter
                         (not leave-ok?)   :leave
                         (not enter-ok?)   :enter
                         :else             nil)]
    (when direction
      (let [{pn-id :id pn-counter :counter} pending-nav-allocation
            enter?      (= :enter direction)
            reason      (if enter? :can-enter :can-leave)
            guarded-meta (if enter? target-meta current-meta)
            guarded-id   (guard-id guarded-meta (if enter? :can-enter :can-leave))
            rejecting-route (if enter? (:route-id target) (:route-id current-route))
            ;; rf2-ede1h.3: a blocked popstate (Back/Forward) has already
            ;; moved the address bar; restore it to the slice's URL so URL
            ;; and slice agree. Applies to BOTH a leave block and an enter
            ;; block reached through popstate — either way the browser
            ;; already moved to the rejected URL. Forward-nav entry points
            ;; never moved the URL, so they emit no restore.
            popstate?   (= :rf.route/handle-url-change (first event-vec))
            restore-url (when popstate? (current-slice->url current-route))
            url-restored? (some? restore-url)
            ;; rf2-p69yaz point 7: the enter-attempt count rides the slot so
            ;; a resume can increment it and the gate can detect a loop. A
            ;; leave block resets it (a leave block is a fresh chain).
            next-attempts (if enter? (inc attempts) 0)
            pending-nav (cond-> {:id                 pn-id
                                 :requested-by-event (vec event-vec)
                                 :requested-url      requested-url
                                 :reason             reason
                                 :direction          direction
                                 :rejecting-route    rejecting-route}
                          guarded-id    (assoc :rejecting-guard guarded-id)
                          enter?        (assoc :enter-attempts next-attempts)
                          url-restored? (assoc :url-restored? true))
            ;; rf2-p69yaz point 7: a detected loop is a fail-closed STOP —
            ;; write the loop error, do NOT keep a resumable pending slot.
            loop-error? looping?
            block-event (if enter? :rf.route/entry-blocked :rf.route/navigation-blocked)]
        (when loop-error?
          (trace/emit-error! :rf.error/route-guard-loop
                             (cond-> {:requested-url   requested-url
                                      :rejecting-route rejecting-route
                                      :rejecting-guard guarded-id
                                      :attempts        attempts
                                      :reason          (str "Enter-guard loop: " guarded-id
                                                            " blocked " requested-url " on "
                                                            attempts " consecutive resume attempts. "
                                                            "The runtime stopped re-issuing to avoid "
                                                            "spinning continue→block. Fix the "
                                                            ":can-enter sub (or its resume policy) so "
                                                            "the retried navigation can eventually "
                                                            "proceed or cancel.")
                                      :recovery        :blocked-navigation}
                               frame-id (assoc :frame frame-id))))
        ;; Per Spec 012 §Navigation blocking §Default flow step 4e: the
        ;; trace marks the blocked transition for tools. rf2-dbmj6x: stamp
        ;; the carried `frame-id`. EP-0015 (rf2-jfaucw): redact the
        ;; query/fragment carrier VALUES of `:requested-url` before egress.
        ;; rf2-p69yaz: stamp the route-phase taxonomy tag
        ;; (`:phase :can-leave` / `:can-enter`) so the Xray routing panel
        ;; (021 §7 Route phase taxonomy) can read WHICH gate blocked from the
        ;; focused epoch's trace-events — this is what makes the 021 §7.3
        ;; `:rf.route/can-enter` phase op REAL (it was a phantom before this
        ;; bead; no route trace carried a `:phase` tag).
        (trace/emit! :rf.event block-event
                     (egress/redact-url-tag
                       (cond-> {:requested-url   requested-url
                                :rejecting-route rejecting-route
                                :rejecting-guard guarded-id
                                :direction       direction
                                :phase           reason}   ;; :can-leave / :can-enter
                         frame-id (assoc :frame frame-id))
                       :requested-url))
        {:rf.db/runtime
         (if loop-error?
           ;; loop → do not strand a resumable slot; clear any prior pending.
           (update-in rdb [:rf.runtime/routing] dissoc :pending-navigation)
           (assoc-in rdb [:rf.runtime/routing :pending-navigation] pending-nav))
         :fx (cond-> [[:rf.route/commit-nav-counter
                       {:counter-key :pending-nav-counter :value pn-counter}]]
               ;; Restore the address bar FIRST (before the user event) so a
               ;; confirm-dialog handler that reads `current-url` sees the
               ;; restored value. No-op on JVM/SSR.
               restore-url (conj [:rf.nav/replace-url restore-url])
               ;; A detected loop dispatches no resumable user event — the
               ;; error trace already told tools; re-dispatching the block
               ;; user event would invite the same continue→block spin.
               (not loop-error?)
               (conj [:dispatch [block-event pending-nav]]))}))))

;; Per Spec 012 §Navigation blocking §Default flow step 4d: the runtime
;; dispatches `[:rf.route/navigation-blocked pending-nav]` (leave block) or
;; `[:rf.route/entry-blocked pending-nav]` (enter block). The framework
;; ships no-op default handlers so the dispatch always resolves (no
;; `:rf.error/no-such-handler`); apps that want to react (render a confirm
;; dialog, redirect to login, log) re-register their own handler under the
;; same id. The pending-nav map is already at
;; `[:rf.runtime/routing :pending-navigation]` (a sub reads it), so the
;; default handlers intentionally do nothing.
(defn navigation-blocked-handler
  "`:rf.route/navigation-blocked` no-op default handler (LEAVE block).
  Registered by the façade so a `:reload` re-wires it on a fresh
  registrar."
  [_ _]
  {})

(defn entry-blocked-handler
  "`:rf.route/entry-blocked` no-op default handler (ENTER block —
  rf2-p69yaz point 5). The enter-block mirror of
  `navigation-blocked-handler`. Registered by the façade so a `:reload`
  re-wires it on a fresh registrar. Apps re-register with their own
  policy (the canonical shape is an auth redirect: read the pending-nav
  slot's `:requested-url`, dispatch `:rf.route/navigate` to login, stash
  the requested target for a post-login bounce-back)."
  [_ _]
  {})

;; rf2-cylse.4: the open-redirect classifier (`safe-in-app-url?` /
;; `external-url?` / `request-url->app-url`) is now shared in
;; `re-frame.routing.url` so the programmatic `:rf.route/navigate {:url}`
;; sink gates through the SAME fail-closed logic as `:rf.route/url-requested`.

(defn- inject-bypass-guards
  "Re-issue the original navigation event with a `:bypass-guards?` SET
  merged in (rf2-p69yaz point 8; rf2-yursn one-shot escape hatch) and the
  running `enter-attempts` loop-guard count threaded forward under
  `:rf.route/enter-attempts` (point 7 — the count can't live only on the
  pending slot, which `continue` clears before re-dispatching). A
  `:rf.route/continue` resume passes `#{:leave}` — the user already
  confirmed the leave, but the enter gate RE-RUNS (point 6), so `:enter`
  is deliberately NOT bypassed."
  [event-vec fallback-url bypass enter-attempts]
  (let [event-id (first event-vec)
        add-opts (fn [opts]
                   (cond-> (assoc (or opts {}) :bypass-guards? bypass)
                     enter-attempts (assoc :rf.route/enter-attempts enter-attempts)))]
    (case event-id
      :rf.route/url-requested
      (let [request (if (map? (second event-vec)) (second event-vec) {})]
        [:rf.route/url-requested (add-opts request)])

      :rf.route/navigate
      (let [[_ target params opts] event-vec]
        [:rf.route/navigate target params (add-opts opts)])

      :rf.route/transitioned
      (let [[_ url opts] event-vec]
        [:rf.route/transitioned url (add-opts opts)])

      :rf.route/handle-url-change
      (let [[_ url opts] event-vec]
        [:rf.route/handle-url-change url (add-opts opts)])

      [:rf.route/url-requested (add-opts {:url fallback-url})])))

(defn url-requested-handler
  "`:rf.route/url-requested` event handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar. rf2-vcop6y: declares only the
  RECORDABLE `:rf.route/pending-nav-allocation` cofx — its only allocation
  is a pending-nav id minted on a guard block (it never mints a nav-token;
  the forward push synthesises `:rf.route/transitioned`, which mints its
  own)."
  [{frame :rf.frame/id rdb :rf.db/runtime
    pending-nav-allocation :rf.route/pending-nav-allocation}
   [_ {:keys [url bypass-guards?] :as _request} :as event-vec]]
    ;; Per Spec 012 §Navigation blocking — pending-nav protocol the
    ;; runtime fires the leave-THEN-enter gate for every :rf.route/url-requested;
    ;; a block writes [:rf.runtime/routing :pending-navigation]. EP-0001
    ;; (rf2-vzld77): the pending-nav slot is durable routing runtime-db
    ;; state — read it from the `:rf.db/runtime` coeffect.
    ;;
    ;; The :bypass-guards? request flag is the rf2-yursn one-shot escape
    ;; hatch :rf.route/continue uses to re-issue the original navigation
    ;; request without re-running the LEAVE guard (the enter guard still
    ;; runs — rf2-p69yaz point 6).
    (let [frame     (frame/require-frame-stamp!
                      frame :rf.route/url-requested
                      {:where 'rf.route/url-requested-handler})
          external? (url/external-url? url)
          app-url   (url/request-url->app-url url)
          blocked   (when-not external?
                      (maybe-block-navigation (or rdb {}) frame
                                              event-vec app-url bypass-guards?
                                              pending-nav-allocation))]
      (cond
        external?
        (do
          (trace/emit! :rf.event :rf.route/external-url-requested
                       (cond-> {:url url}
                         frame (assoc :frame frame)))
          {})

        blocked
        blocked

        :else
        ;; can leave + can enter — push the URL and dispatch
        ;; :rf.route/transitioned. Per Spec 012 §URL changes are events
        ;; route-link clicks call `.preventDefault` and dispatch
        ;; :rf.route/url-requested; the browser's URL has NOT updated. The handler
        ;; pushes the new URL and synthesises the :rf.route/transitioned
        ;; event the slice + on-match write keys off. It bypasses BOTH
        ;; guards (:bypass-guards? #{:leave :enter}) on the synthesised
        ;; re-dispatch — this gate already ran both and passed, so
        ;; re-running them on the transitioned handler would double-fire
        ;; the guard subs.
        {:fx [[:rf.nav/push-url app-url]
              [:dispatch [:rf.route/transitioned app-url {:bypass-guards? #{:leave :enter}}]]]})))

(defn continue-handler
  "`:rf.route/continue` event handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar.

  rf2-p69yaz point 6: continue RE-ISSUES the original navigation request,
  bypassing the LEAVE guard for this one shot (the user confirmed the
  leave), but the ENTER guard RE-RUNS — an enter-pending that survived a
  committed navigation, or an auth gate whose condition has NOT changed,
  must not sail through on resume. So `inject-bypass-guards` merges
  `#{:leave}`, not `#{:leave :enter}`."
  [{rdb :rf.db/runtime} [_ pn-id]]
    ;; EP-0001 (rf2-vzld77): the pending-nav slot is durable routing
    ;; runtime-db state.
    (let [db       (or rdb {})
          pending  (get-in db [:rf.runtime/routing :pending-navigation])
          original (:requested-by-event pending)
          url      (:requested-url pending)
          ;; rf2-p69yaz point 7: thread the running enter-attempt count
          ;; forward so the re-run enter gate can detect a resume loop. The
          ;; slot's `:enter-attempts` was already incremented at block time;
          ;; carry it into the re-issued event's opts (the slot is cleared
          ;; below before the re-dispatch runs, so the count must ride the
          ;; event). Absent on a leave-block resume (no enter attempts).
          attempts (:enter-attempts pending)
          ;; rf2-8zvajk: a blocked popstate that RESTORED the address bar
          ;; must re-move the browser URL on resume — see the leave-block
          ;; docstring above; identical for an enter block reached through
          ;; popstate.
          restore-fx (when (:url-restored? pending)
                       [:rf.nav/replace-url url])
          fallback-request (cond-> {:url url :bypass-guards? #{:leave}}
                             attempts (assoc :rf.route/enter-attempts attempts))
          dispatch-fx [:dispatch (if (vector? original)
                                   (inject-bypass-guards original url #{:leave} attempts)
                                   [:rf.route/url-requested fallback-request])]]
      (if (and pending (= pn-id (:id pending)))
        (cond-> {:rf.db/runtime (update-in db [:rf.runtime/routing] dissoc :pending-navigation)}
          (or (vector? original) url)
          (assoc :fx (cond-> []
                       restore-fx (conj restore-fx)
                       :always    (conj dispatch-fx))))
        {})))

(defn cancel-handler
  "`:rf.route/cancel` event handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar."
  [{rdb :rf.db/runtime} [_ pn-id]]
  ;; EP-0001 (rf2-vzld77): the pending-nav slot is durable routing runtime-db
  ;; state.
  (let [db (or rdb {})]
    (if (= pn-id (get-in db [:rf.runtime/routing :pending-navigation :id]))
      {:rf.db/runtime (update-in db [:rf.runtime/routing] dissoc :pending-navigation)}
      {})))
