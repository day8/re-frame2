(ns re-frame.routing.scroll
  "Scroll-restoration helpers + `:rf.nav/scroll` / `:rf.nav/capture-scroll`
  fxs for re-frame2 routing.

  Per Spec 012 §Scroll restoration §Multi-frame routing.

  ## Storage: host-side per-frame transient cache

  Saved scroll positions are a **host-side transient cache** keyed by
  frame-id — NOT runtime-db state. They are host-derived (read from
  `window.scrollX/Y`), bounded LRU caches, meaningless server-side, and
  not needed to reconstitute a coherent frame-state on restore /
  SSR hydration or time-travel. Holding the cache in a module-level
  `defonce` atom keeps the stored cache out of runtime-db snapshots and
  hydration. Individual positions may still flow through registered effect
  arguments, whose trace copies use the ordinary effect classification.

  The cache lives in `scroll-positions-cache` below: a
  `{frame-id {:positions {url [x y]} :order [url ...]}}` atom, mirroring
  other host-side registries. It is LRU-capped per-frame by
  `scroll-positions-cap`, with
  recency tracked by the per-frame `:order` vector. A frame's entry is
  released by `release-frame!` on frame destroy (analogous to the other
  transient teardown hooks).

  The pure LRU helpers (`lookup-scroll-position` / `save-scroll-position`)
  operate on a plain per-frame cache map (`{:positions :order}`), so the
  nav-planning seam can thread the saved-position map as an EXPLICIT arg
  and stay pure / JVM-testable — it never reaches the host atom directly.

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the two `fx/reg-fx` calls so a `:reload` re-wires them on
  a fresh registrar."
  (:require [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.routing.nav-fx-schemas :as rf.routing.nav-fx-schemas]
            [re-frame.routing.registry :as rf.routing.registry]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.trace :as rf.trace]))

(def scroll-positions-cap
  "Soft upper bound on tracked URLs in the per-frame scroll-positions cache.
  Sized for typical SPA navigation depth — large enough that real
  Back-button restoration hits saved positions, small enough that the
  per-frame host cache stays bounded over long sessions."
  50)

;; ---- host-side per-frame transient cache ----------------------------------

(defonce scroll-positions-cache
  ;; frame-id → {:positions {url [x y]} :order [url ...]}.
  ;;
  ;; Host-side TRANSIENT cache: scroll positions are host-derived
  ;; (window.scrollX/Y), bounded LRU, and meaningless on the server /
  ;; after a restore to a different route — NOT runtime-db state and NOT
  ;; serialized into epochs / SSR payloads. Keyed by frame-id so multi-frame apps keep
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
  on frame destroy (routing's `:post-dead` lifecycle member),
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

(defn cause-default-strategy
  "The implicit scroll strategy for a navigation of `cause`, step 3 of
  `resolve-scroll-strategy`'s order: `:top` for a forward move (`:navigate`,
  `:link`), so a new page opens at its top, and `:restore` for a move to a
  history entry (`:popstate`, `:initial`, `:ssr`), so Back/Forward or a reload
  puts the saved position back. Spec 012 §Scroll restoration."
  [cause]
  (if (#{:navigate :link} cause) :top :restore))

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

(defn route-slice-url
  "The URL a route-slice-shaped map `{:route-id :params :query :fragment}`
  stands for, or nil when it stands for none. A named route's URL is its
  CANONICAL `rf.routing.registry/route-url` reconstruction. A
  `:rf.route/not-found` slice stands for the URL the user asked for, which its
  `:params` record under `:url` (Spec 012 §Route-not-found), never the fallback
  route's own pattern. Nil for a slice with no `:route-id`, or one `route-url`
  cannot build (an unregistered or invalid route), so callers skip rather than
  fail the navigation.

  It keys the scroll cache on BOTH sides: CAPTURE saves the leaving slice's
  position under its URL, and RESTORE looks up under the URL of the incoming
  popstate's resolved target. Both keys are canonical (canonical query-key
  order, no trailing slash, canonical percent-encoding), so a
  non-canonically-spelled history entry (`/cart/`, reordered query) still finds
  its saved position. It is also the URL a URL-driven rejection restores the
  address bar to, and the base an app reference resolves against
  (`re-frame.routing.decisions`)."
  [route-slice]
  (let [id (:route-id route-slice)]
    (cond
      (nil? id)
      nil

      (= :rf.route/not-found id)
      (let [requested (get-in route-slice [:params :url])]
        (when (string? requested) requested))

      :else
      (try
        (rf.routing.registry/route-url {:to       id
                                        :params   (or (:params route-slice) {})
                                        :query    (or (:query route-slice) {})
                                        :fragment (:fragment route-slice)})
        (catch #?(:clj Throwable :cljs :default) _ nil)))))

(defn capture-scroll-fx-entry
  "Build the `[:rf.nav/capture-scroll {:url ...}]` fx entry that saves
  the scroll position of the route the user is LEAVING, keyed by that
  route's reconstructed URL. Returns nil when the current slice has no
  reconstructable URL (no active route, or `route-url` throws — e.g. the
  route was unregistered mid-session), so navigation never fails on a
  capture miss. Emitted by both nav entry points before the transition
  commits so a later `:restore` to this URL finds the saved position."
  [db]
  (when-let [url (route-slice-url (get-in db [:rf.runtime/routing :current]))]
    [:rf.nav/capture-scroll {:url url}]))

(def capture-scroll-meta
  "Metadata for the `:rf.nav/capture-scroll` fx registration.

  EP-0015: the args carry `{:url ...}` — a
  reconstructed route URL whose query/fragment are carrier-shaped values
  (`?token=…`, `#access_token=…`). The core fx trace surface records
  `:rf.fx/args` verbatim onto `:rf.fx/handled` (and the JVM
  `:rf.fx/skipped-on-platform` branch echoes `:url`), so the URL would
  otherwise reach the trace bus / Xray / MCP / epoch egress raw. The
  `:sensitive` path-mark declares `[:url]` so the marks chokepoint
  (`re-frame.classification/project-trace-event` via `re-frame.trace/build-event`)
  redacts it to `:rf/redacted` on the EGRESS copy — the handler still
  receives the real URL in-process (the projection touches only the trace
  tags, never the handler input), so scroll capture keeps working.

  Carries the `:rf.fx.nav/capture-scroll-args` `:schema`
  (`[:map [:url :string]]`) per [Spec-Schemas §Standard fx args
  schemas]. `:url` is the cache KEY, so a missing / non-string one
  fails at the Spec 010 §step-5 `:fx-args` boundary rather than
  silently writing nothing (or a garbage key) into the host-side
  scroll-position cache. Malli maps are open, so the handler's internal
  `:position` test-injection seam still validates."
  {:platforms #{:client}
   :sensitive [[:url]]
   :schema    rf.routing.nav-fx-schemas/capture-scroll-args
   :doc       "Capture the current browser scroll position into the
host-side per-frame transient scroll-position cache (keyed by url)
before leaving a route. The cache is NOT runtime-db state and does not
egress to trace / epochs / SSR."})

(defn capture-scroll-handler
  "`:rf.nav/capture-scroll` fx handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar.

  Scroll positions are a host-side TRANSIENT cache — write
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
             frame-id (rf.frame/require-frame-stamp!
                        frame :rf.nav/capture-scroll
                        {:where 'rf.nav/capture-scroll-handler})
             pos (or position
                     [(or (.-scrollX js/window) (.-pageXOffset js/window) 0)
                      (or (.-scrollY js/window) (.-pageYOffset js/window) 0)])]
         (save-scroll-position! frame-id url pos)))
     :clj
     (rf.trace/emit! :rf.fx :rf.fx/skipped-on-platform
                  {:rf.fx/id :rf.nav/capture-scroll :url url})))

(def scroll-fx-meta
  "Metadata for the `:rf.nav/scroll` fx registration.

  EP-0015: the args carry `:from` / `:to` route
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
  carry the matched route's schema).

  Carries the `:rf.fx.nav/scroll-args` `:schema` per
  [Spec-Schemas §Standard fx args schemas]. The gate is orthogonal to
  the `:sensitive` marks above — `:schema` runs on the handler's INPUT
  (Spec 010 §step 5, before the handler), the marks run on the trace
  EGRESS copy (after). `:saved-pos` members are `number?` rather than
  `:int` because `window.scrollX/Y` are fractional at non-100% zoom and
  on HiDPI displays; the `:fragment` slot is optional. See
  `rf.routing.nav-fx-schemas/scroll-args` for the full rationale."
  {:platforms #{:client}
   :sensitive [[:from :params] [:from :query]
               [:to :params]   [:to :query]
               [:fragment]]
   ;; Kept beside the authoring-boundary checks in `reg-route` and the
   ;; navigate gate: `:rf.nav/scroll` is a public fx id that user code can
   ;; emit directly.
   :schema    rf.routing.nav-fx-schemas/scroll-args
   :doc       "Per Spec 012 §Scroll restoration. Args: {:strategy :from
:to :saved-pos :fragment}. `:strategy` is :top or :restore; any other value
throws (:rf.error/fx-handler-exception). To leave the page where it is,
emit no scroll effect: `:scroll false` on a route or a navigation does that."})

#?(:cljs
   (defn- scroll-dom!
     "The DOM half of `:rf.nav/scroll` — the only part that touches the page.
     A no-op without a `window`: deferred, it runs outside the fx's error
     isolation, so a pageless client host must not throw from here."
     [strategy saved-pos fragment]
     (when (exists? js/window)
       (case strategy
         :top     (if-let [el (and fragment (exists? js/document)
                                   (.getElementById js/document fragment))]
                    (.scrollIntoView el)
                    (.scrollTo js/window 0 0))
         :restore (when (and saved-pos (sequential? saved-pos))
                    (.scrollTo js/window (first saved-pos) (second saved-pos)))))))

#?(:cljs
   (defn- navigation-identity
     "What a deferred scroll must still find when it fires: the frame's
     incarnation (an atom, compared by identity) and the `:nav-token` of the
     route slice its commit installed."
     [frame]
     [(rf.frame/frame-incarnation-token frame)
      (get-in (rf.frame/frame-runtime-db-value frame)
              [:rf.runtime/routing :current :nav-token])]))

#?(:cljs
   (defn- after-commit!
     "Run `f` once the view substrate has committed the navigation that
     emitted it. The fx runs inside the navigating event,
     before any render, so a scroll made here would read and move the page
     being LEFT.

     Through the installed adapter's `:adapter/after-render` hook when there
     is one — decided by the hook's PRESENCE, never by what it returns, since
     a hook that has scheduled `f` may answer nil. With no installed adapter
     publishing the hook, `f` runs at once — including mid-teardown, when the
     departing adapter's hook is still published but nothing will render.

     A deferred `f` is dropped when the navigation it belongs to has been
     superseded (or its frame torn down) by the time it fires: the scroll
     is that navigation's, never the page a later one landed on."
     [frame f]
     (if-not (and (rf.substrate.adapter/current-adapter)
                  (rf.late-bind/get-fn :adapter/after-render))
       (f)
       (let [nav (navigation-identity frame)]
         (rf.interop/after-render
           (fn [] (when (= nav (navigation-identity frame)) (f))))
         nil))))

(defn scroll-fx-handler
  "`:rf.nav/scroll` fx handler. Registered by the façade so a `:reload`
  re-wires it on a fresh registrar.

  `:top` and `:restore` touch the page only after the new
  route has committed (`after-commit!`).

  Any other strategy throws, so it surfaces as the ordinary always-on
  `:rf.error/fx-handler-exception`, including on a host without the optional
  schemas artefact, whose `:fx-args` gate would otherwise refuse it first. A
  navigation never hands the fx such a value — `reg-route` and the navigate
  gate check `:scroll` where it is written — so it arrives only from an app
  emitting `:rf.nav/scroll` itself. The message leaves the value out; the
  dev trace's `:rf.fx/args` carries it."
  [{:keys [frame]} {:keys [strategy saved-pos fragment]}]
  #?(:cljs
     (case strategy
       (:top :restore) (after-commit! frame #(scroll-dom! strategy saved-pos fragment))
       (rf.error/throw-error!
         :rf.error/fx-handler-exception 're-frame.routing.scroll
         (str "`:rf.nav/scroll` takes a `:strategy` of `:top` or `:restore`. To leave "
              "the page where it is, emit no scroll effect: `:scroll false` on a "
              "route or a navigation does that.")))
     :clj
     (rf.trace/emit! :rf.fx :rf.fx/skipped-on-platform
                  {:rf.fx/id :rf.nav/scroll :strategy strategy})))
