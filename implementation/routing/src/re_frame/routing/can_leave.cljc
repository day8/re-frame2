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
  (`events/reg-event-fx :rf/url-requested`, `:rf.route/navigation-blocked`,
  `:rf.route/continue`, `:rf.route/cancel`) so a `:reload` of the façade
  re-wires them on a fresh registrar (the `clear-all!` test-fixture
  path). Per the rf2-2yabr cohesion split: CAN-LEAVE + PENDING-NAV
  seam."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.routing.events :as routing-events]
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
  the route's `:path` pattern string."
  [frame route-id route-meta]
  (if-let [query (can-leave-query route-meta)]
    (if-let [subscribe-once (late-bind/get-fn :subs/subscribe-once)]
      (let [v (subscribe-once frame query)]
        (cond
          (true?  v) true
          (false? v) false
          :else
          ;; rf2-5pyyl closed contract: non-boolean BLOCKs + emits
          ;; `:rf.error/can-leave-non-boolean`.
          (do (trace/emit-error! :rf.error/can-leave-non-boolean
                                 {:route-id route-id
                                  :query    query
                                  :value    v
                                  :reason   (str "Non-boolean returned from :can-leave sub; "
                                                 "the contract requires true (allow) or "
                                                 "false (block). Did you mean (boolean ...) "
                                                 "or (not ...)?")
                                  :recovery :blocked-navigation})
              false)))
      (do (trace/emit! :warning :rf.warning/can-leave-subs-artefact-missing
                       {:query query})
          true))
    true))

(defn maybe-block-navigation
  "Run the active route's `:can-leave` guard before allowing a transition
  to `requested-url`. Returns nil when the navigation should proceed (no
  guard, guard allows, or `bypass-leave-guard?`); returns the cofx map
  `{:db ... :fx ...}` that writes the routing pending-navigation slot
  (`[:rf/runtime :routing :pending-navigation]`) and dispatches
  `:rf.route/navigation-blocked` when the guard blocks.

  Public so the four event entry points (`:rf.route/navigate`,
  `:rf.route/transitioned`, `:rf.route/handle-url-change`,
  `:rf/url-requested`) share one gate; the per-event handler `or`s the
  block result with its happy-path cofx so a single failure path
  collapses cleanly."
  [db frame-id event-vec requested-url bypass-leave-guard?]
  (let [current-route (get-in db [:rf/runtime :routing :current])
        current-meta  (registrar/lookup :route (:id current-route))
        ok?           (or bypass-leave-guard?
                          (can-leave? frame-id (:id current-route) current-meta))]
    (when-not ok?
      (let [[db' pn-id] (routing-events/alloc-pending-nav-id db)
            guard-id    (can-leave-guard-id current-meta)
            pending-nav (cond-> {:id                 pn-id
                                 :requested-by-event (vec event-vec)
                                 :requested-url      requested-url
                                 :reason             :can-leave
                                 :rejecting-route    (:id current-route)}
                          guard-id (assoc :rejecting-guard guard-id))]
        ;; Per Spec 012 §Navigation blocking §Default flow step 4e: the
        ;; trace marks the blocked transition for tools.
        (trace/emit! :rf.event :rf.route/navigation-blocked
                     {:requested-url   requested-url
                      :rejecting-route (:id current-route)
                      :rejecting-guard guard-id})
        ;; Per Spec 012 §Navigation blocking §Default flow step 4d and the
        ;; Events table: `:rf.route/navigation-blocked` is a USER event the
        ;; runtime dispatches when a `:can-leave` guard rejects — apps may
        ;; register their own handler (a confirmation-dialog policy, an
        ;; analytics ping). The runtime writes the pending-navigation slot
        ;; at [:rf/runtime :routing :pending-navigation] FIRST (the slice
        ;; below), then dispatches the event carrying the pending-nav map
        ;; as its single arg so a handler reads it without a separate
        ;; subscription. A default no-op handler (registered below) keeps
        ;; the dispatch resolving cleanly when the app declares none.
        {:db (assoc-in db' [:rf/runtime :routing :pending-navigation] pending-nav)
         :fx [[:dispatch [:rf.route/navigation-blocked pending-nav]]]}))))

;; Per Spec 012 §Navigation blocking §Default flow step 4d: the runtime
;; dispatches `[:rf.route/navigation-blocked pending-nav]` on every block.
;; The framework ships a no-op default handler so the dispatch always
;; resolves (no `:rf.error/no-such-handler`); apps that want to react
;; (render a confirm dialog, log) re-register their own handler under the
;; same id. The pending-nav map is already at
;; `[:rf/runtime :routing :pending-navigation]` (a sub reads it), so the
;; default handler intentionally does nothing.
(defn navigation-blocked-handler
  "`:rf.route/navigation-blocked` no-op default handler. Registered by
  the façade so a `:reload` re-wires it on a fresh registrar."
  [_ _]
  {})

(defn- absolute-url-like? [url]
  (boolean
    (and (string? url)
         (or (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" url)
             (clojure.string/starts-with? url "//")))))

(defn- external-url? [url]
  #?(:cljs
     (try
       (if (and (exists? js/window) (.-location js/window))
         (let [loc      (.-location js/window)
               parsed   (js/URL. url (.-href loc))
               protocol (.-protocol parsed)]
           (or (not (#{"http:" "https:"} protocol))
               (not= (.-origin parsed) (.-origin loc))))
         (absolute-url-like? url))
       (catch :default _
         (absolute-url-like? url)))
     :clj
     (absolute-url-like? url)))

(defn- request-url->app-url [url]
  #?(:cljs
     (try
       (if (and (exists? js/window) (.-location js/window)
                (not (external-url? url)))
         (let [parsed (js/URL. url (.-href (.-location js/window)))]
           (str (.-pathname parsed) (.-search parsed) (.-hash parsed)))
         url)
       (catch :default _ url))
     :clj
     url))

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
  "`:rf/url-requested` event-fx handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar."
  [{:keys [db frame]}
   [_ {:keys [url bypass-leave-guard?] :as _request} :as event-vec]]
    ;; Per Spec 012 §Navigation blocking — pending-nav protocol the
    ;; runtime fires :can-leave for the active route on every
    ;; :rf/url-requested; rejection writes
    ;; [:rf/runtime :routing :pending-navigation] with the full slot
    ;; shape `{:id :requested-by-event :requested-url :reason
    ;; :rejecting-route :rejecting-guard}` per Spec-Schemas.md
    ;; §:rf/pending-navigation (rf2-b8ugt).
    ;;
    ;; The :bypass-leave-guard? request flag is the rf2-yursn one-shot
    ;; escape hatch :rf.route/continue uses to re-issue the original
    ;; navigation request without re-running the leave guard.
    (let [external? (external-url? url)
          app-url   (request-url->app-url url)
          blocked   (when-not external?
                      (maybe-block-navigation db (or frame :rf/default)
                                              event-vec app-url bypass-leave-guard?))]
      (cond
        external?
        (do
          (trace/emit! :rf.event :rf.route/external-url-requested
                       {:url url})
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
        {:fx [[:rf.nav/push-url app-url]
              [:dispatch [:rf.route/transitioned app-url {:bypass-leave-guard? true}]]]})))

(defn continue-handler
  "`:rf.route/continue` event-fx handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar."
  [{:keys [db]} [_ pn-id]]
    ;; Per Spec 012 §Navigation blocking — pending-nav protocol continue
    ;; re-issues the original navigation request, *bypassing* the leave
    ;; guard for this one shot (rf2-yursn): re-emit :rf/url-requested with
    ;; :bypass-leave-guard? true so the same policy chain runs, rather
    ;; than dispatching :rf.route/transitioned + :rf.nav/push-url directly
    ;; (which would skip the policy interceptors and race the slice write
    ;; with the URL push).
    (let [pending  (get-in db [:rf/runtime :routing :pending-navigation])
          original (:requested-by-event pending)
          url      (:requested-url pending)]
      (if (and pending (= pn-id (:id pending)))
        (cond-> {:db (update-in db [:rf/runtime :routing] dissoc :pending-navigation)}
          (or (vector? original) url)
          (assoc :fx [[:dispatch (if (vector? original)
                                   (inject-bypass-leave-guard original url)
                                   [:rf/url-requested {:url url
                                                       :bypass-leave-guard? true}])]]))
        {})))

(defn cancel-handler
  "`:rf.route/cancel` event-fx handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar."
  [{:keys [db]} [_ pn-id]]
  (if (= pn-id (get-in db [:rf/runtime :routing :pending-navigation :id]))
    {:db (update-in db [:rf/runtime :routing] dissoc :pending-navigation)}
    {}))
