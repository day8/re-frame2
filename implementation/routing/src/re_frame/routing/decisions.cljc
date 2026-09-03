(ns re-frame.routing.decisions
  "Pipeline stages 4 and 5 — **decide leave**, then **decide entry** — plus
  the leave-only pending-navigation protocol (EP-0037 R4; Spec 012
  §Navigation blocking — the leave-only pending protocol / §Entry is
  terminal).

  The two decisions are deliberately ASYMMETRIC:

    - **Leave is resumable.** A `:can-leave` guard returning `false` writes
      ONE leave-only pending value at `[:rf.runtime/routing
      :pending-navigation]` carrying the replayable
      `:destination` / `:target` / `:cause` / `:policy`, dispatches
      `:rf.route/navigation-blocked`, and leaves the current route
      untouched. `:rf.route/continue` replays the stored destination +
      policy with a one-shot `:bypass-leave?`; `:rf.route/cancel` drops it.
    - **Entry is terminal.** A `:can-enter` guard returning `false`
      commits nothing, creates NO pending value, dispatches
      `:rf.route/entry-denied` exactly once, and (on a URL-driven door,
      where the host address bar has already moved) restores the current
      slice's URL by REPLACE. There is no enter pending shape, no resume,
      no attempt counter and no loop ceiling: a post-denial return is an
      ordinary fresh navigation, so there is nothing to spin.

  Both guards are closed boolean subscriptions over the RESOLVED TARGET —
  a non-boolean fails closed with the structured `:rf.error/can-leave-non-boolean`
  / `:rf.error/can-enter-non-boolean` error.

  `decide` is stages 4-5 for EVERY door. Each door classifies its
  transition kind FIRST (stage 3) and calls `decide` only for a `:full` or
  `:fragment-only` transition — an exact no-op evaluates neither guard
  (Spec 012 §The one planning pipeline).

  Internal namespace; the public facade is `re-frame.routing`. The facade
  registers the five events (`:rf.route/url-requested`,
  `:rf.route/navigation-blocked`, `:rf.route/entry-denied`,
  `:rf.route/continue`, `:rf.route/cancel`) so a `:reload` of the façade
  re-wires them on a fresh registrar (the `clear-all!` test-fixture path)."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.privacy.url :as rf.privacy.url]
            [re-frame.registrar :as rf.registrar]
            [re-frame.routing.plan :as rf.routing.plan]
            [re-frame.routing.registry :as rf.routing.registry]
            [re-frame.routing.resolve :as rf.routing.resolve]
            [re-frame.routing.url :as rf.routing.url]
            [re-frame.trace :as rf.trace]))

;; ---------------------------------------------------------------------------
;; Guard resolution — shared by `:can-leave` (current route) and
;; `:can-enter` (target route). Per Spec 012 the two guards share the
;; normalised-query shape, the closed boolean contract, and the
;; non-boolean → fail-closed + error-id discipline. They differ only in
;; (a) WHICH route-metadata key names the sub (`:can-leave` / `:can-enter`),
;; (b) which route it reads from (current / target), (c) the error id, and
;; (d) what a `false` MEANS: a resumable leave block, or a terminal entry
;; denial. Holding the resolution in one shared helper keeps the two from
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
  live frame, passing the resolved TARGET appended as an argument.

  Per Spec 012 the guard contract is closed — only the literals `true`
  (allow) and `false` (reject) are accepted. Any non-boolean return
  REJECTS and emits the structured `error-id`, forcing the author to write
  `(boolean ...)` / `(not ...)` rather than rely on truthiness (the
  classic polarity bug: a sub returning the dirty-flag value silently let
  the user navigate away and lose form state).

  The resolved target (`{:route-id :params :query :fragment :url}`) is
  appended to the guard query as an ARGUMENT — the guard sub receives
  `[<guard-id> <target>]` — so `:can-leave` can implement the Spec 012
  fragment-check contract (compare the current `:rf.route/fragment`
  against the requested fragment) and `:can-enter` can branch on WHERE the
  user is heading (auth on the target route's `:tags`, entering a wizard
  step out of order, …). A guard that ignores the extra arg (the common
  `:<- [:editor/dirty?]` shape) is unaffected — the arg rides in the query
  vector's tail.

  Returns `false` (reject) when:
    - the sub returns the literal value `false`;
    - the sub returns any non-boolean value — emits the structured
      `error-id` trace and rejects.

  Returns `true` (proceed) when:
    - no guard is declared for `guard-key`;
    - the sub returns the literal value `true`;
    - the internal `:subs/subscribe-once` hook is unavailable, so the
      runtime cannot evaluate the declared guard. The warning
      `:rf.warning/can-leave-subs-artefact-missing` fires so tooling
      surfaces the degraded registration state. Shared by both guard kinds.

  `route-id` is the guarded route's id keyword — threaded in so the
  non-boolean trace tags the real id rather than the route's `:path`
  pattern string. `frame` is both the live frame the guard sub resolves
  against AND the in-flight cascade's frame stamp the diagnostic traces
  tag under `:tags :frame`."
  [frame route-id route-meta guard-key target error-id]
  (if-let [query (guard-query route-meta guard-key)]
    (if-let [subscribe-once (rf.late-bind/get-fn :subs/subscribe-once)]
      ;; Append the target so the guard sub receives it as
      ;; `(fn [inputs [_ target] ...])`.
      (let [query-with-target (conj (vec query) target)
            v (subscribe-once query-with-target {:frame frame})]
        (cond
          (true?  v) true
          (false? v) false
          :else
          ;; Closed contract: a non-boolean FAILS CLOSED + emits `error-id`.
          ;; The carried `:frame` reaches epoch capture / Xray.
          (do (rf.trace/emit-error! error-id
                                 (cond-> {:route-id route-id
                                          :query    query
                                          :value    v
                                          :reason   (str "Non-boolean returned from " guard-key
                                                         " sub; the contract requires true (allow) "
                                                         "or false (reject). Did you mean (boolean ...) "
                                                         "or (not ...)?")
                                          :recovery :blocked-navigation}
                                   frame (assoc :frame frame)))
              false)))
      (do (rf.trace/emit! :warning :rf.warning/can-leave-subs-artefact-missing
                       (cond-> {:query query}
                         frame (assoc :frame frame)))
          true))
    true))

;; ---------------------------------------------------------------------------
;; Replay values — the `RouteDestination` union and the normalised policy.
;; ---------------------------------------------------------------------------

(def policy-replay-keys
  "The navigation-policy keys a leave-pending value stores so continuation
  replays the caller's INTENT, not merely the URL — `:replace?` and
  `:scroll` (Spec 012 §The extraction law). `:bypass-leave?` is
  deliberately absent: a request carrying a true bypass could not have
  entered the pending slot in the first place, and `:rf.route/continue`
  owns its own one-shot bypass."
  [:replace? :scroll])

(defn normalize-policy
  "The `:policy` a leave-pending value stores — exactly the caller-authored
  `:replace?` / `:scroll` overrides, `{}` when neither was supplied.
  PRESENCE (not truthiness) selects, so `{:replace? false}` round-trips."
  [request]
  (select-keys (or request {}) policy-replay-keys))

(defn destination-of
  "The `:rf/route-destination` replay value for a resolved target — the
  closed union of the named `:rf/route-address` and the raw-URL escape
  (Spec-Schemas §`:rf/route-destination`).

  A target that resolved to a REGISTERED named route normalises to the
  named branch, so a matching raw-URL request replays as the canonical
  address it resolved to. A target that did NOT (an unmatched in-app URL,
  or the reserved `:rf.route/not-found` fallback) stays RAW: rewriting it
  as the not-found route's address would change the URL on replay."
  [{:keys [route-id params query fragment]} requested-url]
  (if (and (keyword? route-id)
           (not= :rf.route/not-found route-id)
           (rf.registrar/lookup :route route-id))
    (cond-> {:to route-id}
      (seq params)     (assoc :params params)
      (seq query)      (assoc :query query)
      (some? fragment) (assoc :fragment fragment))
    {:url requested-url}))

(defn- current-slice->url
  "Rebuild the URL the current route slice represents, for restoring the
  browser address bar after a URL-driven door rejected the transition.
  Returns the URL string, or nil when the slice cannot be rebuilt (no
  current route, a `:rf.route/not-found` slice with no registered pattern,
  or a `route-url` throw). Best-effort: a nil result simply omits the
  restore fx — the slice is already correct; only the URL sync is skipped
  in the rare unbuildable case.

  Built from the slice's `:route-id` / `:params` / `:query` / `:fragment`
  via `route-url`. The result is semantically equivalent to the URL that
  produced the slice, but may use canonical query ordering or slash
  spelling."
  [current-route]
  (let [{:keys [route-id params query fragment]} current-route]
    (when (and route-id (keyword? route-id) (rf.registrar/lookup :route route-id))
      (try
        (rf.routing.registry/route-url {:to route-id :params (or params {}) :query (or query {}) :fragment fragment})
        (catch #?(:clj Throwable :cljs :default) _ nil)))))

(defn server-frame?
  "True iff `frame-id` is an SSR / server frame — its `:config :platform`
  is `:server` (set by the `:ssr-server` preset). Reads ONLY the FRAME's
  platform, never the host-wide default (which is `:server` on the JVM, so
  a JVM client-mode unit test must not be mistaken for SSR). Gates the
  default `403` entry-denial floor (Spec 011 §Route entry denial — the
  default 403), and discriminates the `:ssr` navigation cause from the
  browser's `:initial` one on the shared `:rf.route/handle-url-change` door
  (`re-frame.routing.url-change/url-change-cause`) — one platform read, two
  callers, rather than a second notion of what a server frame is."
  [frame-id]
  (= :server (:platform (rf.frame/frame-meta frame-id))))

;; ---------------------------------------------------------------------------
;; Stages 4-5 — the one decision seam every door shares.
;; ---------------------------------------------------------------------------

(defn decide
  "Run the CURRENT route's `:can-leave` guard (stage 4) and then the TARGET
  route's `:can-enter` guard (stage 5) for one navigation. Returns nil when
  the navigation may PROCEED, else the effects map that terminates it.

  Callers pass a single map:

    :rdb                    the routing runtime-db value
    :frame                  the cascade's frame stamp
    :target                 the RESOLVED target
                            `{:route-id :params :query :fragment :url}`
    :requested-url          the URL the caller asked for (`:url` preserves
                            the caller's spelling; the target's semantic
                            fields are canonical)
    :cause                  `:link` / `:navigate` / `:popstate` / `:initial`
                            / `:ssr` — the door's navigation cause
    :policy                 the normalised `{:replace? :scroll}` caller
                            policy (`{}` when none)
    :bypass-leave?          the public boolean leave escape (OI-3)
    :url-driven?            true for `:rf.route/transitioned` /
                            `:rf.route/handle-url-change` — doors whose
                            host URL has ALREADY moved, so a rejection must
                            restore the address bar by REPLACE
    :pending-nav-allocation the recordable `{:id \"pn-N\" :counter N}`
                            allocation delivered by the
                            `:rf.route/pending-nav-allocation` cofx

  A LEAVE rejection returns `{:rf.db/runtime <rdb with the leave-only
  pending value> :fx [...]}` and dispatches `:rf.route/navigation-blocked`.
  An ENTRY rejection returns `{:fx [...]}` ONLY — no runtime-db write at
  all, because a denial commits nothing and creates no pending value — and
  dispatches `:rf.route/entry-denied` exactly once.

  The leave gate runs first; a rejected leave never reaches the target's
  entry guard, so at most ONE of the two events is ever dispatched per
  navigation attempt."
  [{:keys [rdb frame target requested-url cause policy bypass-leave?
           url-driven? pending-nav-allocation]}]
  (let [current      (get-in rdb [:rf.runtime/routing :current])
        current-meta (rf.registrar/lookup :route (:route-id current))
        target-meta  (rf.registrar/lookup :route (:route-id target))
        ;; The public `:bypass-leave?` is a plain boolean (OI-3). Only the
        ;; literal `true` bypasses; every other value (nil, false, a stray
        ;; set left over from a retired spelling) bypasses nothing.
        leave-ok?    (or (true? bypass-leave?)
                         (guard? frame (:route-id current) current-meta :can-leave
                                 target :rf.error/can-leave-non-boolean))
        ;; Only evaluate entry when the leave gate allowed — a rejected
        ;; leave never reaches the target. Entry has NO bypass.
        enter-ok?    (or (not leave-ok?)
                         (guard? frame (:route-id target) target-meta :can-enter
                                 target :rf.error/can-enter-non-boolean))
        ;; A URL-driven door has already moved the address bar (popstate,
        ;; or a host that pushed before dispatching), so both a leave block
        ;; and an entry denial restore the current slice's URL by REPLACE —
        ;; a replace, not a push, so no history entry is added. Forward
        ;; doors never moved it, so they emit no restore.
        restore-url  (when (and url-driven? (or (not leave-ok?) (not enter-ok?)))
                       (current-slice->url current))]
    (cond
      ;; ---- stage 4 rejected: a resumable leave-only pending value -------
      (not leave-ok?)
      (let [{pn-id :id pn-counter :counter} pending-nav-allocation
            rejecting-guard (guard-id current-meta :can-leave)
            pending (cond-> {:id              pn-id
                             :destination     (destination-of target requested-url)
                             :target          target
                             :cause           cause
                             :policy          (or policy {})
                             :requested-url   requested-url
                             :rejecting-route (:route-id current)}
                      rejecting-guard   (assoc :rejecting-guard rejecting-guard)
                      (some? restore-url) (assoc :url-restored? true))]
        ;; Spec 012 §The leave decision step e: the trace marks the blocked
        ;; transition for tools. EP-0015: redact the query/fragment carrier
        ;; VALUES of `:requested-url` before egress. The `:phase` tag is the
        ;; route-phase taxonomy Xray's routing panel reads (021 §7).
        (rf.trace/emit! :rf.event :rf.route/navigation-blocked
                     (rf.privacy.url/redact-url-tag
                       (cond-> {:requested-url   requested-url
                                :rejecting-route (:route-id current)
                                :rejecting-guard rejecting-guard
                                :cause           cause
                                :phase           :can-leave}
                         frame (assoc :frame frame))
                       :requested-url))
        {:rf.db/runtime (assoc-in rdb [:rf.runtime/routing :pending-navigation] pending)
         :fx (cond-> [[:rf.route/commit-nav-counter
                       {:counter-key :pending-nav-counter :value pn-counter}]]
               ;; Restore the address bar FIRST (before the user event) so a
               ;; confirm-dialog handler that reads `current-url` sees the
               ;; restored value. No-op on JVM/SSR.
               restore-url (conj [:rf.nav/replace-url restore-url])
               :always     (conj [:dispatch [:rf.route/navigation-blocked pending]]))})

      ;; ---- stage 5 rejected: TERMINAL denial ---------------------------
      (not enter-ok?)
      (let [denial {:destination   (destination-of target requested-url)
                    :target        target
                    :cause         cause
                    :requested-url requested-url
                    :guard         (guard-id target-meta :can-enter)}]
        (rf.trace/emit! :rf.event :rf.route/entry-denied
                     (rf.privacy.url/redact-url-tag
                       (cond-> {:requested-url   requested-url
                                :rejecting-route (:route-id target)
                                :rejecting-guard (guard-id target-meta :can-enter)
                                :cause           cause
                                :phase           :can-enter}
                         frame (assoc :frame frame))
                       :requested-url))
        ;; NO `:rf.db/runtime` key — a denial commits nothing and creates no
        ;; pending value, so the runtime-db (route slice, any unrelated
        ;; pending leave, the resource-blocking bookkeeping the Spec 016
        ;; readiness projector owns) is left untouched.
        {:fx (cond-> []
               ;; The host address bar already moved — put it back by replace.
               restore-url (conj [:rf.nav/replace-url restore-url])
               ;; Spec 011 §Route entry denial — the default 403: on a server
               ;; frame the runtime stamps the default status BEFORE the
               ;; denial event drains, so an application handler may supersede
               ;; it with `:rf.server/redirect` (redirect precedence) or an
               ;; explicit `:rf.server/set-status`.
               (server-frame? frame) (conj [:rf.server/set-status 403])
               :always     (conj [:dispatch [:rf.route/entry-denied denial]]))})

      ;; ---- both allowed ------------------------------------------------
      :else nil)))

;; Spec 012 §The leave decision / §Entry is terminal: the runtime dispatches
;; `[:rf.route/navigation-blocked pending]` (leave) or
;; `[:rf.route/entry-denied denial]` (entry). The framework ships no-op
;; default handlers so the dispatch always resolves (no
;; `:rf.error/no-such-handler`) and DENIAL IS SAFE WITH NO APPLICATION
;; HANDLER; apps that want to react (render a confirm dialog, redirect to
;; login, log) re-register their own handler under the same id.

(defn navigation-blocked-handler
  "`:rf.route/navigation-blocked` no-op default handler (LEAVE block). The
  pending value is already at `[:rf.runtime/routing :pending-navigation]`
  (the `:rf/pending-navigation` sub reads it), so the default intentionally
  does nothing. Registered by the façade so a `:reload` re-wires it on a
  fresh registrar."
  [_ _]
  {})

(defn entry-denied-handler
  "`:rf.route/entry-denied` no-op default handler (TERMINAL entry denial).
  Registered by the façade so a `:reload` re-wires it on a fresh registrar,
  and so an application is never REQUIRED to register a handler merely to
  make denial safe: with the default, client denial is a hard deny — the
  current route and URL stay in place (or the URL is restored after a
  URL-driven door), and no protected activation work runs.

  Applications replace it with their own policy. The canonical auth shape
  is the FRESH RETURN recipe: stash the denied `:destination`
  (a `:rf/route-destination`), replace-navigate to login, and after a
  successful sign-in dispatch a FRESH `[:rf.route/navigate <destination>]`
  — the guard re-evaluates because that is an ordinary new attempt."
  [_ _]
  {})

;; The open-redirect classifier (`safe-in-app-url?` / `external-url?` /
;; `request-url->app-url`) is shared in `re-frame.routing.url` so the
;; programmatic `:rf.route/navigate {:url}` sink gates through the SAME
;; fail-closed logic as `:rf.route/url-requested`.

;; The URL -> ResolvedTarget extraction (including the `:rf.route/not-found`
;; fallback normalisation) is the ONE shared definition in
;; `re-frame.routing.resolve/url-resolution` — the seam the commit hop lowers
;; to as well. The link door reads it through `rf.routing.resolve/target-of-url`, so
;; stage 3, the guards, and the commit all resolve the same URL to the same
;; target (EP-0037 R0b). Deriving it locally is what let a dead link bypass the
;; `:rf.route/not-found` route's `:can-enter` guard and push a history entry for
;; the already-active not-found URL.

(defn url-requested-handler
  "`:rf.route/url-requested` event handler — the LINK door. Registered by
  the façade so a `:reload` re-wires it on a fresh registrar. Declares only
  the recordable `:rf.route/pending-nav-allocation` cofx — its only
  allocation is a pending-nav id minted on a leave block (it never mints a
  nav-token; the forward push synthesises `:rf.route/transitioned`, which
  mints its own).

  The door runs the pipeline in order: classify the transition (stage 3) —
  an exact no-op terminates here, evaluating NEITHER guard and pushing
  NOTHING — then decide leave and entry (stages 4-5) BEFORE the address bar
  moves, so a rejected link click never adds a history entry. On an allowed
  transition it pushes the URL and synthesises `:rf.route/transitioned`,
  which owns the commit; the synthesised event carries the runtime-internal
  `:rf.route/decided?` rider on its trailing opts map, so an allowed link
  click does not decide the same target twice (Spec 012 §The request
  grammar). The rider is NOT part of the published `:rf.route/navigate`
  request roster — that roster is closed with no exemption."
  [{frame :rf.frame/id rdb :rf.db/runtime
    pending-nav-allocation :rf.route/pending-nav-allocation}
   [_ {:keys [url replace? bypass-leave?] :as request}]]
  (let [frame     (rf.frame/require-frame-stamp!
                    frame :rf.route/url-requested
                    {:where 'rf.route/url-requested-handler})
        rdb       (or rdb {})
        external? (rf.routing.url/external-url? url)
        app-url   (rf.routing.url/request-url->app-url url)]
    (if external?
      (do
        (rf.trace/emit! :rf.event :rf.route/external-url-requested
                     (cond-> {:url url}
                       frame (assoc :frame frame)))
        {})
      (let [target  (rf.routing.resolve/target-of-url app-url)
            current (get-in rdb [:rf.runtime/routing :current])
            ;; Stage 3 — an exact no-op (the link points at the already-active
            ;; target) terminates before guards, pending state, and history.
            no-op?  (rf.routing.plan/identical-route-target? current (:route-id target)
                                                  (:params target) (:query target)
                                                  (:fragment target))]
        (if no-op?
          {}
          (or (decide {:rdb                    rdb
                       :frame                  frame
                       :target                 target
                       :requested-url          app-url
                       :cause                  :link
                       :policy                 (normalize-policy request)
                       :bypass-leave?          bypass-leave?
                       :url-driven?            false
                       :pending-nav-allocation pending-nav-allocation})
              ;; Allowed — push the URL and synthesise the commit door. Per
              ;; Spec 012 §URL changes are events, route-link clicks call
              ;; `.preventDefault` and dispatch `:rf.route/url-requested`, so
              ;; the browser's URL has NOT updated: this door pushes it and
              ;; hands the slice write to `:rf.route/transitioned`.
              {:fx [(if replace?
                      [:rf.nav/replace-url app-url]
                      [:rf.nav/push-url    app-url])
                    [:dispatch [:rf.route/transitioned app-url
                                {:rf.route/decided? true}]]]}))))))

(defn continue-handler
  "`:rf.route/continue` event handler — the leave-only resume. Registered by
  the façade so a `:reload` re-wires it on a fresh registrar.

  Continue clears the pending slot and executes the STORED destination plus
  the stored policy through the normal pipeline with a one-shot
  `:bypass-leave? true` — the user confirmed the leave. Entry is evaluated
  normally: continuation replays intent, it does not carry a decision.

  Replay is one `[:rf.route/navigate <destination + policy + bypass>]`
  dispatch; the stored `:destination` is a `:rf/route-destination`, so both
  the named (`:to`) and raw (`:url`) branches are already valid navigate
  requests. When the blocked door had restored the host URL
  (`:url-restored? true` — a URL-driven transition whose address bar had
  already moved), the replay forces `:replace? true` so moving the address
  bar back to `:requested-url` preserves that door's no-extra-push
  semantics."
  [{rdb :rf.db/runtime} [_ pn-id]]
  (let [db      (or rdb {})
        pending (get-in db [:rf.runtime/routing :pending-navigation])]
    (if (and pending (= pn-id (:id pending)))
      (let [{:keys [destination policy url-restored?]} pending
            request (cond-> (merge (or destination {}) (or policy {})
                                   {:bypass-leave? true})
                      url-restored? (assoc :replace? true))]
        (cond-> {:rf.db/runtime (update-in db [:rf.runtime/routing] dissoc :pending-navigation)}
          (seq destination)
          (assoc :fx [[:dispatch [:rf.route/navigate request]]])))
      {})))

(defn cancel-handler
  "`:rf.route/cancel` event handler. Clears the pending leave slot; the URL
  and the current route stay as they are. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar."
  [{rdb :rf.db/runtime} [_ pn-id]]
  (let [db (or rdb {})]
    (if (= pn-id (get-in db [:rf.runtime/routing :pending-navigation :id]))
      {:rf.db/runtime (update-in db [:rf.runtime/routing] dissoc :pending-navigation)}
      {})))
