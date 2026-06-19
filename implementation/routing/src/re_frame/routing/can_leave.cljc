(ns re-frame.routing.can-leave
  "`:can-leave` gating + the pending-nav protocol for re-frame2 routing.

  Per Spec 012 §Navigation blocking — pending-nav protocol. Owns:
    - `can-leave-query` / `can-leave-guard-id` / `can-leave?` —
      :can-leave sub resolution + the closed-contract boolean check
      (rf2-5pyyl);
    - `maybe-block-navigation` — the unified leave-guard precheck used
      by `:rf.route/navigate`, `:rf.route/transitioned`,
      `:rf.route/handle-url-change`, and `:rf/url-requested`;
    - `:rf/url-requested` — the link-click + programmatic URL-request
      entry point (preventDefault + pushState + :rf.route/transitioned);
    - `:rf.route/continue` / `:rf.route/cancel` — pending-nav protocol
      resolution events;
    - `:rf.route/navigation-blocked` — the default no-op user event
      runtime dispatches on every block (apps re-register with their
      own confirm-dialog / analytics handler).

  Internal namespace; the public facade is `re-frame.routing`. The
  facade registers the four events
  (`events/reg-event :rf/url-requested`, `:rf.route/navigation-blocked`,
  `:rf.route/continue`, `:rf.route/cancel`) so a `:reload` of the façade
  re-wires them on a fresh registrar (the `clear-all!` test-fixture
  path). Per the rf2-2yabr cohesion split: CAN-LEAVE + PENDING-NAV
  seam."
  (:require [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.routing.egress :as egress]
            [re-frame.routing.registry :as registry]
            [re-frame.routing.url :as url]
            [re-frame.trace :as trace]))

(defn- can-leave-query [route-meta]
  (let [declared (:can-leave route-meta)]
    (cond
      (vector? declared) declared
      (keyword? declared) [declared]
      :else nil)))

(defn- can-leave-guard-id
  "The guard's sub-id — the head of the normalised `:can-leave` query.
  `(first [kw])` is `kw`, `(first vec)` is its head, `(first nil)` is
  nil, so the single `can-leave-query` normalisation covers every case."
  [route-meta]
  (first (can-leave-query route-meta)))

(defn- can-leave?
  "Resolve and call the route's `:can-leave` sub against the live frame.
  Per Spec 012 §Navigation blocking §Default flow (rf2-5pyyl): the
  guard contract is closed — only the literals `true` (allow) and
  `false` (block) are accepted. Any non-boolean return BLOCKS and emits
  `:rf.error/can-leave-non-boolean`, forcing the author to write
  `(boolean ...)` / `(not ...)` rather than rely on truthiness (the
  classic polarity bug: a sub returning the dirty-flag value silently
  let the user navigate away and lose form state).

  Returns `false` (block) when:
    - the sub returns the literal value `false`;
    - the sub returns any non-boolean value — emits the structured
      `:rf.error/can-leave-non-boolean` trace and blocks.

  Returns `true` (proceed) when:
    - no `:can-leave` is declared (no guard);
    - the sub returns the literal value `true`;
    - `:subs/subscribe-once` is unset (consumer opted out of the subs
      artefact; the runtime has no way to evaluate the sub, so it
      cannot fail the closed contract). The warning
      `:rf.warning/can-leave-subs-artefact-missing` fires so tooling
      surfaces the misconfiguration.

  `route-id` is the active route's id keyword — threaded in so the
  `:rf.error/can-leave-non-boolean` trace tags the real id rather than
  the route's `:path` pattern string.

  rf2-dbmj6x — `frame` is BOTH the live frame the `:can-leave` sub
  resolves against AND the in-flight cascade's frame stamp the diagnostic
  traces tag under `:tags :frame`. The guard already had `frame` in hand;
  stamping the `:rf.error/can-leave-non-boolean` /
  `:rf.warning/can-leave-subs-artefact-missing` emits with it means they
  enter the emitting frame's epoch and obey the frame trace-disable gate,
  closing the same drop/leak this finding fixes on the lifecycle traces."
  [frame route-id route-meta]
  (if-let [query (can-leave-query route-meta)]
    (if-let [subscribe-once (late-bind/get-fn :subs/subscribe-once)]
      (let [v (subscribe-once frame query)]
        (cond
          (true?  v) true
          (false? v) false
          :else
          ;; rf2-5pyyl closed contract: non-boolean BLOCKs + emits
          ;; `:rf.error/can-leave-non-boolean`. rf2-dbmj6x: stamp the
          ;; carried `:frame` so the error reaches epoch capture / Xray.
          (do (trace/emit-error! :rf.error/can-leave-non-boolean
                                 (cond-> {:route-id route-id
                                          :query    query
                                          :value    v
                                          :reason   (str "Non-boolean returned from :can-leave sub; "
                                                         "the contract requires true (allow) or "
                                                         "false (block). Did you mean (boolean ...) "
                                                         "or (not ...)?")
                                          :recovery :blocked-navigation}
                                   frame (assoc :frame frame)))
              false)))
      (do (trace/emit! :warning :rf.warning/can-leave-subs-artefact-missing
                       (cond-> {:query query}
                         frame (assoc :frame frame)))
          true))
    true))

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

(defn maybe-block-navigation
  "Run the active route's `:can-leave` guard before allowing a transition
  to `requested-url`. Returns nil when the navigation should proceed (no
  guard, guard allows, or `bypass-leave-guard?`); returns the effects map
  `{:rf.db/runtime ... :fx ...}` that writes the routing pending-navigation slot
  (`[:rf.runtime/routing :pending-navigation]`) and dispatches
  `:rf.route/navigation-blocked` when the guard blocks.

  Public so the four event entry points (`:rf.route/navigate`,
  `:rf.route/transitioned`, `:rf.route/handle-url-change`,
  `:rf/url-requested`) share one gate; the per-event handler `or`s the
  block result with its happy-path cofx so a single failure path
  collapses cleanly.

  Per Spec 012 §Navigation blocking §Default flow step 4c — *the URL
  does not change* on a block. For a FORWARD nav (`:rf/url-requested`,
  `:rf.route/navigate`, `:rf.route/transitioned`) the browser URL has
  not moved yet, so blocking simply declines to push. But a POPSTATE
  block (the triggering event is `:rf.route/handle-url-change` — Back /
  Forward) has ALREADY moved the browser address bar to the rejected
  URL; without a restore the address bar and the `:rf/route` slice
  diverge (the slice stays on the rejecting route, the URL shows the
  destination). On such a block this emits a `:rf.nav/replace-url` that
  restores the address bar to the current route slice's URL — no new
  history entry, URL and slice agree again (rf2-ede1h.3). `:rf.route/
  cancel` / `:rf.route/continue` then operate from a consistent state.

  rf2-vcop6y: `pending-nav-allocation` is the RECORDABLE allocation
  `{:id \"pn-N\" :counter N}` delivered by the generator-backed
  `:rf.route/pending-nav-allocation` cofx — the pending-nav id is PUBLISHED
  from `:id` (recorded on the causal token, so a recorded
  `[:rf.route/continue \"pn-N\"]` re-matches under replay) and the
  `:counter` high-water bump rides a `:rf.route/commit-nav-counter` fx in
  the returned `:fx` vector."
  [rdb frame-id event-vec requested-url bypass-leave-guard? pending-nav-allocation]
  (let [current-route (get-in rdb [:rf.runtime/routing :current])
        current-meta  (registrar/lookup :route (:route-id current-route))
        ok?           (or bypass-leave-guard?
                          (can-leave? frame-id (:route-id current-route) current-meta))]
    (when-not ok?
      (let [{pn-id :id pn-counter :counter} pending-nav-allocation
            guard-id    (can-leave-guard-id current-meta)
            ;; rf2-ede1h.3: a blocked popstate (Back/Forward) has already
            ;; moved the address bar; restore it to the slice's URL so URL
            ;; and slice agree. Only `:rf.route/handle-url-change` carries
            ;; that already-moved-URL semantics; the forward-nav entry
            ;; points never moved the URL, so they emit no restore.
            popstate?   (= :rf.route/handle-url-change (first event-vec))
            restore-url (when popstate? (current-slice->url current-route))
            ;; rf2-8zvajk: record whether THIS block performed a URL
            ;; restore. `:rf.route/continue` reads it to decide whether the
            ;; resume must re-move the address bar. A blocked popstate's
            ;; resume re-dispatches the original
            ;; `[:rf.route/handle-url-change requested-url]` (bypassing the
            ;; guard); that handler rewrites the slice but emits NO history
            ;; mutation because it assumes the browser already moved. That
            ;; assumption held at the ORIGINAL popstate, but the restore
            ;; above moved the address bar BACK to the rejecting route — so
            ;; on resume the browser URL is stale and `continue-handler`
            ;; must replace it with `:requested-url`, or the committed slice
            ;; and the address bar diverge (refresh / copy-URL / Back-Forward
            ;; all operate on the wrong URL). Forward-nav blocks (no restore)
            ;; never moved the URL, so their resume re-runs the full
            ;; `:rf/url-requested` policy chain that pushes — no extra
            ;; restore needed; the flag stays absent.
            url-restored? (some? restore-url)
            pending-nav (cond-> {:id                 pn-id
                                 :requested-by-event (vec event-vec)
                                 :requested-url      requested-url
                                 :reason             :can-leave
                                 :rejecting-route    (:route-id current-route)}
                          guard-id      (assoc :rejecting-guard guard-id)
                          url-restored? (assoc :url-restored? true))]
        ;; Per Spec 012 §Navigation blocking §Default flow step 4e: the
        ;; trace marks the blocked transition for tools. rf2-dbmj6x: stamp
        ;; the carried `frame-id` so the block diagnostic enters the
        ;; emitting frame's epoch + obeys the frame trace-disable gate — in
        ;; a multi-frame app a block can otherwise not be filtered to the
        ;; frame that caused it.
        ;; EP-0015 (rf2-jfaucw): the blocked-navigation diagnostic trace
        ;; carries the raw `:requested-url` under a custom slot the
        ;; per-registration marks chokepoint does not walk. A blocked target
        ;; URL can carry query/fragment carriers (`?token=…`,
        ;; `#access_token=…`), and this trace is a framework-shaped
        ;; observation record (epoch / Xray / MCP / log egress). Redact the
        ;; query/fragment carrier VALUES (keep the path) before the trace
        ;; crosses the egress boundary. The DURABLE pending-nav slot below
        ;; keeps the raw `:requested-url` — `continue-handler` re-dispatches
        ;; it to resume the navigation — and its off-box epoch egress is
        ;; already fail-closed by the redact-whole-runtime-db default
        ;; (EP-0001 ruling #14); only this trace slot needed projection.
        (trace/emit! :rf.event :rf.route/navigation-blocked
                     (egress/redact-url-tag
                       (cond-> {:requested-url   requested-url
                                :rejecting-route (:route-id current-route)
                                :rejecting-guard guard-id}
                         frame-id (assoc :frame frame-id))
                       :requested-url))
        ;; Per Spec 012 §Navigation blocking §Default flow step 4d and the
        ;; Events table: `:rf.route/navigation-blocked` is a USER event the
        ;; runtime dispatches when a `:can-leave` guard rejects — apps may
        ;; register their own handler (a confirmation-dialog policy, an
        ;; analytics ping). The runtime writes the pending-navigation slot
        ;; at [:rf.runtime/routing :pending-navigation] FIRST (the slice
        ;; below), then dispatches the event carrying the pending-nav map
        ;; as its single arg so a handler reads it without a separate
        ;; subscription. A default no-op handler (registered below) keeps
        ;; the dispatch resolving cleanly when the app declares none.
        ;; EP-0001 (rf2-vzld77): the pending-navigation slot is durable
        ;; routing runtime-db state — write runtime-db. rf2-oosjmh: the
        ;; pending-nav COUNTER is host-side transient state — its high-water
        ;; bump rides the `:rf.route/commit-nav-counter` fx below (not
        ;; runtime-db), so `rdb` (not a counter-bumped db') is written here.
        {:rf.db/runtime (assoc-in rdb [:rf.runtime/routing :pending-navigation] pending-nav)
         :fx (cond-> [;; rf2-oosjmh: persist the pending-nav high-water mark
                      ;; into the host-side counter cache (WRITE half of the
                      ;; pure seam). FIRST so the bump lands before the
                      ;; blocked-event dispatch.
                      [:rf.route/commit-nav-counter
                       {:counter-key :pending-nav-counter :value pn-counter}]]
               ;; Restore the address bar FIRST (before the user event)
               ;; so a confirm-dialog handler that reads `current-url`
               ;; sees the restored value. No-op on JVM/SSR
               ;; (`:rf.nav/replace-url` is `:platforms #{:client}`).
               restore-url (conj [:rf.nav/replace-url restore-url])
               :always     (conj [:dispatch [:rf.route/navigation-blocked pending-nav]]))}))))

;; Per Spec 012 §Navigation blocking §Default flow step 4d: the runtime
;; dispatches `[:rf.route/navigation-blocked pending-nav]` on every block.
;; The framework ships a no-op default handler so the dispatch always
;; resolves (no `:rf.error/no-such-handler`); apps that want to react
;; (render a confirm dialog, log) re-register their own handler under the
;; same id. The pending-nav map is already at
;; `[:rf.runtime/routing :pending-navigation]` (a sub reads it), so the
;; default handler intentionally does nothing.
(defn navigation-blocked-handler
  "`:rf.route/navigation-blocked` no-op default handler. Registered by
  the façade so a `:reload` re-wires it on a fresh registrar."
  [_ _]
  {})

;; rf2-cylse.4: the open-redirect classifier (`safe-in-app-url?` /
;; `external-url?` / `request-url->app-url`) is now shared in
;; `re-frame.routing.url` so the programmatic `:rf.route/navigate {:url}`
;; sink gates through the SAME fail-closed logic as `:rf/url-requested`.

(defn- inject-bypass-leave-guard [event-vec fallback-url]
  (let [event-id (first event-vec)]
    (case event-id
      :rf/url-requested
      (let [request (if (map? (second event-vec)) (second event-vec) {})]
        [:rf/url-requested (assoc request :bypass-leave-guard? true)])

      :rf.route/navigate
      (let [[_ target params opts] event-vec]
        [:rf.route/navigate target params (assoc (or opts {}) :bypass-leave-guard? true)])

      :rf.route/transitioned
      (let [[_ url opts] event-vec]
        [:rf.route/transitioned url (assoc (or opts {}) :bypass-leave-guard? true)])

      :rf.route/handle-url-change
      (let [[_ url opts] event-vec]
        [:rf.route/handle-url-change url (assoc (or opts {}) :bypass-leave-guard? true)])

      [:rf/url-requested {:url fallback-url :bypass-leave-guard? true}])))

(defn url-requested-handler
  "`:rf/url-requested` event handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar. rf2-vcop6y: declares only the
  RECORDABLE `:rf.route/pending-nav-allocation` cofx — its only allocation
  is a pending-nav id minted on a `:can-leave` block (it never mints a
  nav-token; the forward push synthesises `:rf.route/transitioned`, which
  mints its own)."
  [{frame :rf.frame/id rdb :rf.db/runtime
    pending-nav-allocation :rf.route/pending-nav-allocation}
   [_ {:keys [url bypass-leave-guard?] :as _request} :as event-vec]]
    ;; Per Spec 012 §Navigation blocking — pending-nav protocol the
    ;; runtime fires :can-leave for the active route on every
    ;; :rf/url-requested; rejection writes
    ;; [:rf.runtime/routing :pending-navigation] with the full slot
    ;; shape `{:id :requested-by-event :requested-url :reason
    ;; :rejecting-route :rejecting-guard}` per Spec-Schemas.md
    ;; §:rf/pending-navigation (rf2-b8ugt). EP-0001 (rf2-vzld77): the
    ;; pending-nav slot is durable routing runtime-db state — read it from
    ;; the `:rf.db/runtime` coeffect.
    ;;
    ;; The :bypass-leave-guard? request flag is the rf2-yursn one-shot
    ;; escape hatch :rf.route/continue uses to re-issue the original
    ;; navigation request without re-running the leave guard.
    (let [;; EP-0002 carried invariant — `:rf/url-requested` is a cascade
          ;; event, so the cofx carries the frame stamp under `:rf.frame/id`;
          ;; a nil stamp is an invariant failure
          ;; (`:rf.error/no-frame-context`), never a synthesised `:rf/default`.
          frame     (frame/require-frame-stamp!
                      frame :rf/url-requested
                      {:where 'rf.route/url-requested-handler})
          external? (url/external-url? url)
          app-url   (url/request-url->app-url url)
          blocked   (when-not external?
                      (maybe-block-navigation (or rdb {}) frame
                                              event-vec app-url bypass-leave-guard?
                                              pending-nav-allocation))]
      (cond
        external?
        (do
          ;; rf2-dbmj6x: stamp the carried `frame` so the external-url
          ;; diagnostic enters the emitting frame's epoch + obeys the frame
          ;; trace-disable gate — symmetric with the sibling programmatic
          ;; external path (`navigate.cljc` `:rf.route/external-url-requested`),
          ;; which already frame-tags.
          (trace/emit! :rf.event :rf.route/external-url-requested
                       (cond-> {:url url}
                         frame (assoc :frame frame)))
          {})

        blocked
        blocked

        :else
        ;; can leave — push the URL and dispatch :rf.route/transitioned.
        ;; Per Spec 012 §URL changes are events route-link clicks call
        ;; `.preventDefault` and dispatch :rf/url-requested; the browser's
        ;; URL has NOT updated. The handler is responsible for pushing
        ;; the new URL (history pushState) and then synthesising the
        ;; :rf.route/transitioned event the slice + on-match write keys off.
        ;;
        ;; rf2-6si0sr: this re-dispatch INTENTIONALLY does NOT carry
        ;; `:source :router` — unlike the link-click (`link.cljc`) and
        ;; on-match-error (`on_match_error.cljc`) sites. Those two are
        ;; IMPERATIVE `router/dispatch!` calls at a non-event boundary (a DOM
        ;; click / an error-listener), where the source would otherwise
        ;; default to `:ui` / `:unknown`, so they must stamp `:source :router`
        ;; explicitly. This site is a `:dispatch` FX returned from inside an
        ;; event handler: core's `:dispatch` fx-handler (`fx.cljc`)
        ;; UNCONDITIONALLY stamps the child envelope `:source :fx-dispatch`
        ;; (per rf2-ejtpd) — already a substrate-internal origin Xray's L2
        ;; renders as "from fx :dispatch · parent epoch #N", never `:ui`. The
        ;; `[:dispatch event]` effect form carries no `:source` opt slot, and
        ;; the fx-handler hardcodes it regardless, so `:fx-dispatch` is both
        ;; the correct tag and the only achievable one here.
        {:fx [[:rf.nav/push-url app-url]
              [:dispatch [:rf.route/transitioned app-url {:bypass-leave-guard? true}]]]})))

(defn continue-handler
  "`:rf.route/continue` event handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar."
  [{rdb :rf.db/runtime} [_ pn-id]]
    ;; Per Spec 012 §Navigation blocking — pending-nav protocol continue
    ;; re-issues the original navigation request, *bypassing* the leave
    ;; guard for this one shot (rf2-yursn): re-emit :rf/url-requested with
    ;; :bypass-leave-guard? true so the same policy chain runs, rather
    ;; than dispatching :rf.route/transitioned + :rf.nav/push-url directly
    ;; (which would skip the policy interceptors and race the slice write
    ;; with the URL push). EP-0001 (rf2-vzld77): the pending-nav slot is
    ;; durable routing runtime-db state.
    (let [db       (or rdb {})
          pending  (get-in db [:rf.runtime/routing :pending-navigation])
          original (:requested-by-event pending)
          url      (:requested-url pending)
          ;; rf2-8zvajk: when the block was a popstate that RESTORED the
          ;; address bar (`:url-restored?`), the resume must re-move the
          ;; browser URL to `:requested-url` itself — the re-dispatched
          ;; `[:rf.route/handle-url-change requested-url {:bypass-leave-guard?
          ;; true}]` (built by `inject-bypass-leave-guard`) rewrites the
          ;; slice but emits NO history mutation (it assumes the browser
          ;; already moved). After the restore that assumption is false, so
          ;; without this the slice commits to `:requested-url` while the
          ;; address bar stays on the rejecting route. A `:rf.nav/replace-url`
          ;; (not push) keeps the popstate entry's place in the history stack
          ;; — confirming a blocked Back/Forward must not grow history.
          ;; Emitted FIRST so the address bar is correct by the time the
          ;; bypassed handler synchronously rewrites the slice. Forward-nav
          ;; resumes (`:url-restored?` absent — no restore happened) re-run
          ;; the full `:rf/url-requested` policy chain, which pushes on its
          ;; own; adding a replace there would clobber that push.
          restore-fx (when (:url-restored? pending)
                       [:rf.nav/replace-url url])
          dispatch-fx [:dispatch (if (vector? original)
                                   (inject-bypass-leave-guard original url)
                                   [:rf/url-requested {:url url
                                                       :bypass-leave-guard? true}])]]
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
