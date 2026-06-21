(ns re-frame.routing.scroll
  "Scroll-restoration helpers + `:rf.nav/scroll` / `:rf.nav/capture-scroll`
  fxs for re-frame2 routing.

  Per Spec 012 §Scroll restoration §Multi-frame routing.

  ## Storage: host-side per-frame TRANSIENT cache (rf2-1hncp2)

  Saved scroll positions are a **host-side transient cache** keyed by
  frame-id — NOT runtime-db state. They are host-derived (read from
  `window.scrollX/Y`), bounded LRU caches, meaningless server-side, and
  not needed to reconstitute a coherent frame-state on restore /
  SSR-hydration / time-travel (Mike ruling, EP-0001 decision #13:
  runtime-db = serializable facts NEEDED for restore; host handles +
  dirty caches stay TRANSIENT). Holding them in a module-level `defonce`
  atom keeps them OFF the trace / epoch / snapshot egress wire entirely
  — the storage location, not an egress filter, is what keeps them local.

  The cache lives in `scroll-positions-cache` below: a
  `{frame-id {:positions {url [x y]} :order [url ...]}}` atom, mirroring
  the HTTP in-flight registry pattern (`re-frame.http.registry`, EP-0001
  ~line 901). It is LRU-capped per-frame by `scroll-positions-cap` with
  recency tracked by the per-frame `:order` vector. A frame's entry is
  released by `release-frame!` on frame destroy (analogous to the other
  transient teardown hooks).

  The pure LRU helpers (`lookup-scroll-position` / `save-scroll-position`)
  operate on a plain per-frame cache map (`{:positions :order}`), so the
  nav-planning seam can thread the saved-position map as an EXPLICIT arg
  and stay pure / JVM-testable — it never reaches the host atom directly.

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the two `fx/reg-fx` calls so a `:reload` re-wires them on
  a fresh registrar. Per the rf2-2yabr cohesion split: SCROLL seam."
  (:require [re-frame.frame :as frame]
            [re-frame.routing.registry :as registry]
            [re-frame.trace :as trace]))

(def scroll-positions-cap
  "Soft upper bound on tracked URLs in the per-frame scroll-positions cache.
  Sized for typical SPA navigation depth — large enough that real
  Back-button restoration hits saved positions, small enough that the
  per-frame host cache stays bounded over long sessions."
  50)

;; ---- host-side per-frame transient cache (rf2-1hncp2) ---------------------

(defonce scroll-positions-cache
  ;; frame-id → {:positions {url [x y]} :order [url ...]}.
  ;;
  ;; Host-side TRANSIENT cache: scroll positions are host-derived
  ;; (window.scrollX/Y), bounded LRU, and meaningless on the server /
  ;; after a restore to a different route — NOT runtime-db state and NOT
  ;; serialized into epochs / SSR payloads (per EP-0001 decision #13 +
  ;; the rf2-1hncp2 ruling). Keyed by frame-id so multi-frame apps keep
  ;; isolated per-frame caches; the entry is dropped on frame destroy via
  ;; `release-frame!`. Mirrors `re-frame.http.registry`'s `in-flight`
  ;; defonce atom — host-owned ephemeral state, not in the reactive db.
  (atom {}))

;; ---- pure LRU helpers (operate on a per-frame cache map) ------------------

(defn lookup-scroll-position
  "Return the saved [x y] for `url` in `cache`, or nil if none. `cache` is
  a per-frame cache map `{:positions {url [x y]} :order [...]}` (the value
  stored under a frame-id in `scroll-positions-cache`), or nil. Pure."
  [cache url]
  (get-in cache [:positions url]))

(defn save-scroll-position
  "Pure: return `cache` with the scroll position for `url` recorded under
  `:positions`. `cache` is a per-frame cache map
  `{:positions {url [x y]} :order [...]}` (or nil for an empty cache). The
  cache is LRU-capped at `scroll-positions-cap` entries — re-saving an
  existing url promotes it to most-recent; new saves past the cap evict
  the least-recently-used entry. The `:order` vector is the recency anchor."
  [cache url xy]
  (let [order   (or (:order cache) [])
        order'  (-> (filterv #(not= url %) order)
                    (conj url))
        over    (- (count order') scroll-positions-cap)
        dropped (when (pos? over) (subvec order' 0 over))
        order'' (if (pos? over) (subvec order' over) order')
        positions  (as-> (or (:positions cache) {}) m
                     (if dropped (apply dissoc m dropped) m)
                     (assoc m url xy))]
    (assoc cache
           :positions positions
           :order     order'')))

;; ---- host-cache wrappers (frame-keyed) ------------------------------------

(defn frame-scroll-cache
  "Read the per-frame cache map (`{:positions :order}`) for `frame-id` from
  the host `scroll-positions-cache`, or nil when none. The value threaded
  into the pure nav-planning seam (`plan/scroll-plan`)."
  [frame-id]
  (get @scroll-positions-cache frame-id))

(defn save-scroll-position!
  "Record `xy` for `url` under `frame-id` in the host
  `scroll-positions-cache`, applying the LRU cap via the pure
  `save-scroll-position`. Returns nil."
  [frame-id url xy]
  (swap! scroll-positions-cache update frame-id save-scroll-position url xy)
  nil)

(defn release-frame!
  "Drop `frame-id`'s entry from the host `scroll-positions-cache`. Invoked
  on frame destroy (the `:routing/on-frame-destroyed!` teardown hook),
  analogous to the other per-frame transient teardown. Idempotent —
  no-op on an absent frame. Returns nil."
  [frame-id]
  (swap! scroll-positions-cache dissoc frame-id)
  nil)

(defn reset-cache!
  "Test-time helper: drop the whole host `scroll-positions-cache`. Test
  fixtures call this between runs so a saved position does not leak across
  tests. Returns nil."
  []
  (reset! scroll-positions-cache {})
  nil)

(defn route-descriptor*
  "Build the canonical `{:id :params :query}` descriptor — the shape
  :rf.nav/scroll's :from / :to args carry. `:params` / `:query` are
  included only when non-empty. Single builder shared by
  `route-descriptor` (slice-driven :from) and the navigate /
  url-change :to sites (explicit args)."
  [id params query]
  (cond-> {:id id}
    (seq params) (assoc :params params)
    (seq query)  (assoc :query  query)))

(defn route-descriptor
  "Build the {:id :params :query} descriptor used by :rf.nav/scroll's
  :from / :to args from a route slice (or nil if no slice yet). The
  slice lives at [:rf.runtime/routing :current]."
  [route-slice]
  (when (and route-slice (:route-id route-slice))
    (route-descriptor* (:route-id route-slice)
                       (:params route-slice)
                       (:query route-slice))))

(defn resolve-scroll-strategy
  "Per Spec 012 §Scroll restoration, resolution order:
    1. opts' :scroll (per-call override)
    2. route metadata's :scroll
    3. implicit default (caller-supplied — :top for forward, :restore
       for popstate / initial)
  Returns the resolved strategy, or ::suppress when the resolved value
  is `false` (which means: do not emit the fx)."
  [route-meta opts default]
  (let [from-opts (when (and (map? opts) (contains? opts :scroll))
                    (:scroll opts))
        from-meta (:scroll route-meta)]
    (cond
      ;; per-call override wins; explicit `false` suppresses
      (some? from-opts) (if (false? from-opts) ::suppress from-opts)
      (false? from-meta) ::suppress
      (some? from-meta) from-meta
      :else             default)))

(defn scroll-fx-entry
  "Build the [:rf.nav/scroll args] fx entry for a navigation, or nil
  when the resolved strategy is ::suppress (no fx emission).

  Per Spec 012 §Scroll restoration §`:rf.nav/scroll` integration the args
  shape is {:strategy :from :to :saved-pos :fragment}."
  [{:keys [strategy from to saved-pos fragment]}]
  (when (not= ::suppress strategy)
    [:rf.nav/scroll
     (cond-> {:strategy strategy}
       from      (assoc :from      from)
       to        (assoc :to        to)
       saved-pos (assoc :saved-pos saved-pos)
       fragment  (assoc :fragment  fragment))]))

(defn- current-route-url
  "Best-effort URL reconstruction for the active route slice. Used only
  to key scroll-position capture; route deletion or invalid historical
  slices skip capture rather than failing navigation."
  [route-slice]
  (when-let [id (:route-id route-slice)]
    (try
      (registry/route-url id
                          (or (:params route-slice) {})
                          (or (:query route-slice) {})
                          (:fragment route-slice))
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn capture-scroll-fx-entry
  "Build the `[:rf.nav/capture-scroll {:url ...}]` fx entry that saves
  the scroll position of the route the user is LEAVING, keyed by that
  route's reconstructed URL. Returns nil when the current slice has no
  reconstructable URL (no active route, or `route-url` throws — e.g. the
  route was unregistered mid-session), so navigation never fails on a
  capture miss. Emitted by both nav entry points before the transition
  commits so a later `:restore` to this URL finds the saved position."
  [db]
  (when-let [url (current-route-url (get-in db [:rf.runtime/routing :current]))]
    [:rf.nav/capture-scroll {:url url}]))

(def capture-scroll-meta
  "Metadata for the `:rf.nav/capture-scroll` fx registration.

  EP-0015 (rf2-1wmni6 / rf2-pbbo68): the args carry `{:url ...}` — a
  reconstructed route URL whose query/fragment are carrier-shaped values
  (`?token=…`, `#access_token=…`). The core fx trace surface records
  `:rf.fx/args` verbatim onto `:rf.fx/handled` (and the JVM
  `:rf.fx/skipped-on-platform` branch echoes `:url`), so the URL would
  otherwise reach the trace bus / Xray / MCP / epoch egress raw. The
  `:sensitive` path-mark declares `[:url]` so the marks chokepoint
  (`re-frame.classification/project-trace-event` via `re-frame.trace/build-event`)
  redacts it to `:rf/redacted` on the EGRESS copy — the handler still
  receives the real URL in-process (the projection touches only the trace
  tags, never the handler input), so scroll capture keeps working."
  {:platforms #{:client}
   :sensitive [[:url]]
   :doc       "Capture the current browser scroll position into the
host-side per-frame transient scroll-position cache (keyed by url)
before leaving a route. The cache is NOT runtime-db state and does not
egress to trace / epochs / SSR (rf2-1hncp2)."})

(defn capture-scroll-handler
  "`:rf.nav/capture-scroll` fx handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar.

  rf2-1hncp2: scroll positions are a host-side TRANSIENT cache — write
  the module-level `scroll-positions-cache` atom (keyed by frame-id), NOT
  the runtime-db partition. This keeps them off the trace / epoch / SSR
  egress wire and out of local time-travel frame-state."
  [{:keys [frame]} {:keys [url position]}]
  #?(:cljs
     (when url
       (let [;; EP-0002 carried invariant — the fx context carries the
             ;; cascade envelope frame as `:frame`; a nil stamp is an
             ;; invariant failure (`:rf.error/no-frame-context`), never a
             ;; synthesised `:rf/default`.
             frame-id (frame/require-frame-stamp!
                        frame :rf.nav/capture-scroll
                        {:where 'rf.nav/capture-scroll-handler})
             pos (or position
                     [(or (.-scrollX js/window) (.-pageXOffset js/window) 0)
                      (or (.-scrollY js/window) (.-pageYOffset js/window) 0)])]
         (save-scroll-position! frame-id url pos)))
     :clj
     (trace/emit! :rf.fx :rf.fx/skipped-on-platform
                  {:rf.fx/id :rf.nav/capture-scroll :url url})))

(def scroll-fx-meta
  "Metadata for the `:rf.nav/scroll` fx registration.

  EP-0015 (rf2-1wmni6 / rf2-pbbo68): the args carry `:from` / `:to` route
  DESCRIPTORS (each `{:id :params :query}`) and a `:fragment` — all
  carrier-shaped (route params can be document-ids / tokens; a `#fragment`
  can be an OAuth implicit-grant token). The core fx trace records
  `:rf.fx/args` verbatim onto `:rf.fx/handled`, so these would reach the
  trace bus / Xray / MCP / epoch egress raw. The `:sensitive` path-marks
  declare `[:from :params]` / `[:from :query]` / `[:to :params]` /
  `[:to :query]` / `[:fragment]` so the marks chokepoint redacts them to
  `:rf/redacted` on the EGRESS copy. This is invisible to the handler:
  `scroll-fx-handler` reads ONLY `:strategy` / `:saved-pos` / `:fragment`
  and never touches `:from` / `:to`, and the marks projection runs in
  `build-event` on the trace tags AFTER the handler already received the
  real args — so scroll restoration / fragment scrolling are unaffected.
  The route `:id` keyword is kept (it names the shape, carries no secret).
  Marks are unconditional path declarations (the canonical EP-0015 fx-args
  mechanism) — route params/query/fragment are always carrier-shaped, so a
  blanket trace scrub of those slots is the correct posture rather than a
  per-route schema decision (which the fx layer cannot make — it does not
  carry the matched route's schema)."
  {:platforms #{:client}
   :sensitive [[:from :params] [:from :query]
               [:to :params]   [:to :query]
               [:fragment]]
   :doc       "Per Spec 012 §Scroll restoration. Args: {:strategy :from
:to :saved-pos :fragment}. Standard strategies are :top, :restore,
:preserve. Map-form strategies are host-extensible; the runtime treats
unknown strategies as :preserve (no-op)."})

(defn scroll-fx-handler
  "`:rf.nav/scroll` fx handler. Registered by the façade so a `:reload`
  re-wires it on a fresh registrar."
  [_ {:keys [strategy saved-pos fragment]}]
  #?(:cljs
     (case strategy
       :top      (if-let [el (and fragment
                                  (.getElementById js/document fragment))]
                   (.scrollIntoView el)
                   (.scrollTo js/window 0 0))
       :restore  (when (and saved-pos (sequential? saved-pos))
                   (.scrollTo js/window
                              (first saved-pos)
                              (second saved-pos)))
       :preserve nil
       ;; map-form / unknown → host-extensible; default no-op so the
       ;; runtime doesn't blow up on a strategy it doesn't recognise.
       nil)
     :clj
     (trace/emit! :rf.fx :rf.fx/skipped-on-platform
                  {:rf.fx/id :rf.nav/scroll :strategy strategy})))
