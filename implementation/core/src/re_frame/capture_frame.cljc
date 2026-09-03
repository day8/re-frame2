(ns re-frame.capture-frame
  "The frame api behind `re-frame.core/capture-frame` and the `reg-view`
  injection sugar: the one constructor (`make-capture-frame`) and the
  incarnation fence its ops run through.

  An implementation namespace, not an app surface — apps call
  `re-frame.core/capture-frame`. It sits BELOW the facade so the `reg-view`
  macro's emitted body can name the constructor fully-qualified without a
  compiler-only Var living on `re-frame.core` (rf2-93sxp; until then
  `make-capture-frame` was a facade export whose manifest row read
  `:tier :implementation` — annotation, not removal, per Conventions
  §Removing or demoting a facade export).

  The ops route through the facade's `^:no-doc` `dispatch-impl` /
  `dispatch-sync-impl` / `subscribe-impl` seams — the same `def`-aliases the
  call-site macros target — so a `with-redefs` on a seam is honoured by a
  frame api captured earlier (tool tests spy on exactly that). A leaf
  namespace cannot `:require` the facade, so `re-frame.core` publishes the
  three through `re-frame.late-bind` (`:core/dispatch-impl` /
  `:core/dispatch-sync-impl` / `:core/subscribe-impl`, the cycle-breaking
  flavour) and this ns reads them at capture time, falling back to the
  owning fns when the facade has not loaded."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.router :as rf.router]
            [re-frame.subs :as rf.subs]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.trace :as rf.trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- capture-frame incarnation fence (rf2-9pyles) -------------------------
;;
;; A frame api is LOCKED to one frame, and moftbs (rf2-moftbs) made a frame's
;; identity its EXACT incarnation (the record's `:drain-lock`), distinct across a
;; `destroy-frame!` + same-id reconstruction. So a frame api captured against a
;; LIVE frame A must stay bound to incarnation A: if A is later destroyed and a
;; same-id successor incarnation B reseats under the id, an op fired from a stale
;; async closure (the motivating case: a predecessor React root's DEFERRED
;; layout/effect cleanup, firing after `destroy-adapter!` returned and a fresh
;; generation reseated frame B) must NOT dispatch into B. The bare-id resolution
;; `dispatch!`/`dispatch-sync!` perform at call time would otherwise silently
;; retarget the successor.
;;
;; The fence PINS the incarnation live at capture and treats a superseded target
;; as destroyed (recover-but-emit `:rf.error/frame-destroyed`, NOT a throw — these
;; ops fire from host cleanup where a throw would break the host teardown). It
;; applies UNIFORMLY to every op that resolves the target — `:dispatch`,
;; `:dispatch-sync` (rf2-9pyles) AND `:subscribe` (rf2-tdjv7p, closing the same
;; silent cross-incarnation retarget for the read op so subscribe cannot read a
;; successor's app-db or leak a persisted reaction into its sub-cache). The pin
;; is EXACT even over a frame VALUE: the value carries its own construction token
;; (`:rf.frame/incarnation-token`, rf2-moftbs), preferred before the id-keyed
;; registry lookup so `(capture-frame <value>)` pins the same incarnation
;; `(capture-frame <id>)` does (rf2-vclh63). When the captured id was NOT live at
;; capture (`capture-frame`'s 1-arity lock-to-id form used from outside any scope,
;; a not-yet-mounted id, or a derived-read value carrying no token) nothing is
;; pinned and the op stays address-directed — the documented dynamic-id
;; semantics. This is the async-safe, RECOVER-BUT-EMIT form of the incarnation
;; fence: where a synchronous fence throws on a superseded incarnation, this one
;; emits `:rf.error/frame-destroyed` and drops the op.

(defn- capture-target-incarnation
  "The EXACT incarnation token (`:drain-lock`) pinning the capture TARGET's live
  incarnation, else nil (unpinned / address-directed). `frame-target` is a
  keyword id OR a frame VALUE.

  A construction frame VALUE carries its own exact token
  (`:rf.frame/incarnation-token`, rf2-moftbs) — PREFER it so `(capture-frame
  <value>)` pins the SAME incarnation `(capture-frame <id>)` does. The id-keyed
  registry lookup (`frame-incarnation-token`) returns nil for a value map (the
  registry is keyed by id, not by the value), so without this the value path
  silently lost its pin (rf2-vclh63). A keyword id — or a derived-read value that
  carries no construction token — falls to the id-keyed lookup: a live id pins,
  an absent/not-yet-mounted id (or a derived-read value, whose map is not a
  registry key) pins nothing. Namespace-qualified `rf.frame/…` is never shadowed by
  a local `frame`."
  [frame-target]
  (or (rf.frame/frame-value-incarnation-token frame-target)
      (rf.frame/frame-incarnation-token frame-target)))

(defn- capture-target-superseded?
  "True when a pinned `captured-incarnation` no longer identifies the capture
  TARGET's live frame — the exact captured incarnation was destroyed, whether the
  id is now unclaimed or a same-id successor incarnation reseated. `frame-target`
  is NORMALIZED to its frame id (`frame-target->id`) before the liveness lookup so
  a frame VALUE's carried token is compared against the current live incarnation
  under its id — the id-keyed token lookup does not accept a value map, so without
  this a pinned value would read nil-live and be spuriously superseded on every
  op (rf2-vclh63). nil `captured-incarnation` (an unpinned capture) is never
  superseded."
  [frame-target captured-incarnation]
  (and (some? captured-incarnation)
       (not (rf.frame/frame-incarnation-live?
              (rf.frame/frame-target->id frame-target) captured-incarnation))))

(defn- capture-dispatch!
  "Route a captured `:dispatch`/`:dispatch-sync` op through the incarnation fence:
  when the pinned incarnation is superseded, recover-but-emit `:rf.error/frame-
  destroyed` (never enqueue into a same-id successor); otherwise delegate to
  `dispatch-fn` (the `dispatch-impl` / `dispatch-sync-impl` alias, preserving the
  single `with-redefs` interception seam). `frame-target` is a keyword id OR a
  frame VALUE; the recover-but-emit stamps the normalized frame id (identity for
  a keyword) so the diagnostic carries an id, never a value map (rf2-vclh63).
  `op` (rf2-7xlvt) is the ALREADY-KNOWN operation realm — `:dispatch` or
  `:dispatch-sync` — carried through the recover-but-emit so the frame-destroyed
  source-coord resolves under `[:event id]` exactly, never the realm-ambiguous
  fallback that could steal a same-keyword subscription's coord."
  [dispatch-fn op frame-target captured-incarnation event opts]
  (if (capture-target-superseded? frame-target captured-incarnation)
    (rf.router/emit-captured-frame-superseded!
      event (rf.frame/frame-target->id frame-target) op opts)
    ;; rf2-dlld6: the `capture-target-superseded?` pre-check above and the
    ;; ordinary address-directed dispatch below are SEPARATE operations. On the
    ;; concurrent JVM host frame A can be destroyed AND a same-id successor B
    ;; installed in the window between them, so a capture that just validated A
    ;; would enqueue into B — a bare-id resolve, violating the exact-incarnation
    ;; promise. Carry the EXACT captured incarnation through as
    ;; `:rf.frame/expected-incarnation` so `dispatch!` / `dispatch-sync!`
    ;; validate it against the SAME record they resolve for enqueue (one
    ;; exact-incarnation operation) and recover-but-emit rather than leak into B.
    ;; nil `captured-incarnation` (an unpinned capture made while its target was
    ;; ABSENT) carries nothing and stays deliberately address-directed.
    (dispatch-fn event (cond-> opts
                         (some? captured-incarnation)
                         (assoc :rf.frame/expected-incarnation captured-incarnation)))))

(defn- capture-subscribe!
  "Route a captured `:subscribe` op through the SAME incarnation fence as
  `capture-dispatch!` (rf2-tdjv7p): when the pinned incarnation is superseded,
  recover-but-emit `:rf.error/frame-destroyed` and return nil — never resolve a
  reaction against a same-id successor (which would read the successor's app-db
  and cache a reaction in its sub-cache). Otherwise delegate to `subscribe-thunk`
  (the live read, which itself applies the dev-only `:rf.trace/call-site`
  wrapper). The subscribe half of the async-safe, recover-but-emit incarnation
  fence — the dispatch half is above; reuses the dispatch fence's emit seam,
  passing `subscribe-call-site` as the `:rf.trace/call-site` so the drop is
  attributed to the subscribe coord, and the `:subscribe` operation realm
  (rf2-7xlvt) so the frame-destroyed source-coord resolves under `[:sub id]`
  exactly — never a same-keyword event's coord."
  [subscribe-thunk frame-target captured-incarnation query-v subscribe-call-site]
  (if (capture-target-superseded? frame-target captured-incarnation)
    (rf.router/emit-captured-frame-superseded!
      query-v (rf.frame/frame-target->id frame-target) :subscribe
      {:rf.trace/call-site subscribe-call-site})
    (subscribe-thunk)))

(defn- seam
  "The facade seam published under `hook-key`, else `default` (the owning fn
  the seam aliases — identical unless a tool has redefined the seam).
  Resolved once per capture: the published fn reads the facade var at call
  time, so a `with-redefs` on `re-frame.core/dispatch-impl` reaches an op
  captured before it."
  [hook-key default]
  (or (rf.late-bind/get-fn-cached hook-key) default))

(defn make-capture-frame
  "Build a frame api locked to `frame` — the constructor behind
  `re-frame.core/capture-frame` (both arities) and the `reg-view` injection
  sugar, whose emitted body names it fully-qualified. Not an app surface:
  call `capture-frame`.

    {:frame         frame
     :dispatch      (fn ([event] [event opts]))
     :dispatch-sync (fn ([event] [event opts]))
     :subscribe     (fn [query-v])}

  The captured `frame` is closed over by every op — no dynamic-var read
  at op-call time — so the frame api dispatches / subscribes into `frame`
  even when an op fires after the surrounding `with-frame` /
  `frame-provider` / `frame-root` scope has unwound (the async-boundary
  case).

  Per the frame-affordance redesign (rf2-kkut0) the captured frame is
  AUTHORITATIVE: `:frame` is assoc'd LAST in the dispatch opts, so a
  per-call `:frame` in `opts` CANNOT override it — the frame api is
  locked to one frame.

  `opts` (the second arg) supports the `reg-view` source-coord sugar:
    :dispatch-opts        base dispatch opts merged BELOW the captured
                          `:frame` (and below any per-call `opts`). The
                          `reg-view` macro injects
                          `{:source :ui :rf.trace/call-site <view-coord>}`
                          here so a view's on-click `#((:dispatch h) [...])`
                          classifies as `:source :ui` + carries the view's
                          call-site for Xray's dispatch 'go to code'.
    :subscribe-call-site  a source-coord stamped (under
                          `rf.interop/debug-enabled?`) onto any error emitted
                          inside the synchronous subscribe miss path
                          (`:rf.error/no-such-sub`, `:rf.error/frame-
                          destroyed`). Mirrors the `subscribe` macro's
                          `rf.trace/with-call-site` wrapper; subscriptions
                          carry no `:source` axis. DCEs in production."
  [frame {:keys [dispatch-opts subscribe-call-site]}]
  ;; rf2-9pyles: pin the EXACT incarnation live at capture so a later op cannot
  ;; leak into a same-id successor. nil (id not live at capture) ⇒ address-directed.
  (let [captured-incarnation (capture-target-incarnation frame)
        dispatch-impl        (seam :core/dispatch-impl      rf.router/dispatch!)
        dispatch-sync-impl   (seam :core/dispatch-sync-impl rf.router/dispatch-sync!)
        subscribe-impl       (seam :core/subscribe-impl     rf.subs/subscribe)]
    {:frame frame
     :dispatch
     (fn dispatch-fn
       ([event]      (capture-dispatch! dispatch-impl :dispatch frame captured-incarnation
                                        event (merge dispatch-opts {:frame frame})))
       ([event opts] (capture-dispatch! dispatch-impl :dispatch frame captured-incarnation
                                        event (merge dispatch-opts opts {:frame frame}))))
     :dispatch-sync
     (fn dispatch-sync-fn
       ([event]      (capture-dispatch! dispatch-sync-impl :dispatch-sync frame captured-incarnation
                                        event (merge dispatch-opts {:frame frame})))
       ([event opts] (capture-dispatch! dispatch-sync-impl :dispatch-sync frame captured-incarnation
                                        event (merge dispatch-opts opts {:frame frame}))))
     :subscribe
     ;; rf2-tdjv7p: fence subscribe on the SAME incarnation pin as dispatch — a
     ;; capture pinned to incarnation A whose frame was destroyed and reseated as
     ;; a same-id successor B must NOT subscribe into B (reading B's app-db,
     ;; caching a reaction in B's sub-cache); it recover-but-emits and returns nil.
     (fn subscribe-fn
       [query-v]
       ;; rf2-dlld6: carry the EXACT captured incarnation into the read so
       ;; `subscribe-in-frame` validates it against the SAME record it resolves
       ;; from the sub-cache (one exact-incarnation operation) — closing the
       ;; identical check-then-use window the dispatch arm has: a same-id
       ;; successor B installed between the `capture-subscribe!` pre-check and
       ;; the resolve cannot be read (nor a reaction cached in B's sub-cache).
       (let [sub-opts (cond-> {:frame frame}
                        (some? captured-incarnation)
                        (assoc :rf.frame/expected-incarnation captured-incarnation))]
         (capture-subscribe!
           (fn []
             (if (and subscribe-call-site rf.interop/debug-enabled?)
               (rf.trace/with-call-site subscribe-call-site
                 (subscribe-impl query-v sub-opts))
               (subscribe-impl query-v sub-opts)))
           frame captured-incarnation query-v subscribe-call-site)))}))
