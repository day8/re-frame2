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
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.routing.nav-fx-schemas :as rf.routing.nav-fx-schemas]
            [re-frame.routing.registry :as rf.routing.registry]
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

(defn route-slice-url
  "Best-effort CANONICAL URL reconstruction for a route-slice-shaped map
  `{:route-id :params :query :fragment}` via `rf.routing.registry/route-url`. Returns
  nil (rather than throwing) for a slice with no `:route-id` or one
  `route-url` can't build (an unregistered/invalid route), so callers skip
  rather than failing navigation.

  This is the SINGLE scroll-cache keying function, used SYMMETRICALLY on both
  sides (rf2-g1i5m6): CAPTURE keys the leaving slice's position under this
  reconstruction, and RESTORE looks up under the SAME reconstruction of the
  incoming popstate URL's resolved route. Because both sides run through
  `route-url`, the key is canonical on both (canonical query-key order, no
  trailing slash, canonical percent-encoding), so a non-canonically-spelled
  history entry (`/cart/`, reordered query) still finds its saved position."
  [route-slice]
  (when-let [id (:route-id route-slice)]
    (try
      (rf.routing.registry/route-url {:to       id
                           :params   (or (:params route-slice) {})
                           :query    (or (:query route-slice) {})
                           :fragment (:fragment route-slice)})
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
  (when-let [url (route-slice-url (get-in db [:rf.runtime/routing :current]))]
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
  tags, never the handler input), so scroll capture keeps working.

  rf2-sqams: carries the `:rf.fx.nav/capture-scroll-args` `:schema`
  (`[:map [:url :string]]`) per [Spec-Schemas §Standard fx args
  schemas]. `:url` is the cache KEY, so a missing / non-string one now
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
  carry the matched route's schema).

  rf2-sqams: carries the `:rf.fx.nav/scroll-args` `:schema` per
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
   :schema    rf.routing.nav-fx-schemas/scroll-args
   :doc       "Per Spec 012 §Scroll restoration. Args: {:strategy :from
:to :saved-pos :fragment}. `:strategy` is the closed three-value enum
:top / :restore / :preserve; any other value is rejected loudly
(:rf.error/unsupported-scroll-strategy) rather than silently ignored."})

(def supported-scroll-strategies
  "The CLOSED `:rf.nav/scroll` `:strategy` vocabulary, per Spec 012
  §Scroll restoration. Ordered for the diagnostic message; the schema
  (`rf.routing.nav-fx-schemas/scroll-args`) carries the same three as an `:enum`."
  [:top :restore :preserve])

(def unsupported-strategy-reason
  "The human diagnosis for a rejected `:rf.nav/scroll` `:strategy`.

  A CONSTANT, deliberately: it names the closed vocabulary and the fix but
  NEVER the offending value (rf2-s3n6h). That is what lets it ride the
  always-on, production-surviving, non-privacy-gated record alongside the
  structural slots — an interpolated `(pr-str strategy)` could not. The raw
  value is not lost to a developer: it rides the dev-trace `:strategy` tag,
  one slot away, on the channel that stays on the box."
  (str "Unsupported :rf.nav/scroll strategy. Supported strategies are "
       ":top, :restore and :preserve. Set the route's :scroll metadata "
       "(or the :rf.route/navigate :scroll opt) to one of those, or to "
       "false to suppress the scroll effect entirely."))

(defn- emit-unsupported-strategy!
  "Fan the closed-vocabulary rejection out on BOTH error channels through the
  shared `rf.error-emit/emit-error-both!` seam (rf2-2hkfy).

  rf2-px26m made the handler's default branch loud instead of nil, but it
  emitted through `rf.trace/emit-error!` ALONE — and that surface is wrapped in
  `rf.interop/debug-enabled?`, so it DCEs under CLJS `:advanced` +
  `goog.DEBUG=false`. The rejection therefore only ever fired where the
  OPTIONAL schemas artefact had already caught the same value one step
  earlier at the Spec 010 §step-5 `:fx-args` boundary. On a schemas-less
  PRODUCTION host — the exact configuration the branch exists to cover, and
  the consumers least likely to notice — the handler ran, performed no
  scroll, emitted nothing, and returned nil: the original rf2-px26m defect,
  intact.

  `emit-error-both!` is the existing two-channel helper every catalogued
  production-reachable runtime error site already uses. Axis 1 is the
  always-on `dispatch-on-error!` listener registry — NOT gated on
  `rf.interop/debug-enabled?`, so the record survives `goog.DEBUG=false` and
  reaches off-box shippers. Axis 2 is the dev-only `rf.trace/emit-error!`
  surface, which keeps the EXACT tag map rf2-px26m shipped, so dev-trace
  consumers (the existing suite, Xray, epoch capture) see no change. One
  call, one record per channel — no double emission.

  Production is therefore no less safe than dev: under `goog.DEBUG=false`
  the human `:reason` prose still rides the record (this is a caller-authored
  config value, not user data), and only the dev TRACE half is stripped. The
  rejection itself is unconditional on every build.

  `event` is the originating event vector the fx context carries (Spec 002
  §The binary fx-handler signature) — absent for a direct handler call, in
  which case both it and `:event-id` ride as nil, exactly as the other
  non-dispatch-attributed always-on rows do.

  ## The two channels carry DIFFERENT payloads (rf2-s3n6h)

  rf2-2hkfy made the record always-on; rf2-s3n6h made it SAFE to be always-on.
  Its first cut copied the rejected `:strategy` verbatim into `record-attrs`
  and interpolated `(pr-str strategy)` into `:reason`, which put an arbitrary
  runtime value on axis 1.

  Axis 1 is not the dev trace. `dispatch-on-error!` passes the positional
  `event` through `elision/elide-wire-value` — the per-path `:sensitive?` /
  `:large?` seam, which FAILS CLOSED on an unknown / destroyed frame — but it
  merges `record-attrs` UNCHANGED, and contracts callers to keep them to tight
  identifiers precisely because the listener registry is production-surviving
  and NOT privacy-gated. A `:rf.route/navigate` call's per-call `:scroll` opt
  is runtime data, not necessarily static author configuration, and on the
  schemas-less path it may be any map / string / collection / host value. So
  the raw copy bypassed the elision seam on the one channel that ships off-box.
  Measured on the pre-fix code, a 2000-key strategy produced a 4.8 MB record —
  and the same record's `:event` slot had already been redacted to
  `:rf/redacted` by the seam the attrs walked around.

  The split, therefore:

    axis 2 (dev trace, DCE'd in prod) — the raw `:strategy`, for local
      debugging: this channel does not leave the box.
    axis 1 (always-on record, off-box) — STRUCTURAL only: the fixed supported
      vocabulary, the recovery, and `:strategy-type`, a closed-vocabulary
      SHAPE tag that cannot reproduce the value.

  `:strategy-type` reuses `re-frame.error/diag-value-summary`'s `:type` axis —
  the established EP-0015-safe diagnostic vocabulary, already read this way at
  other framework surfaces — rather than inventing a
  scroll-local one. Only `:type` is taken: the summary's `:keys` leg returns
  EVERY top-level map key unbounded and reproduces key content, so it is not
  itself a bound.

  `:reason` is now a CONSTANT (`unsupported-strategy-reason`) that names the
  vocabulary and the fix without naming the offending value. That is what makes
  it safe on axis 1, and it also removes the `pr-str` of an arbitrary value from
  the rejection path entirely — no gate needed, on any build. The rejection
  itself is unchanged and remains unconditional: nothing here is wrapped in
  `rf.interop/debug-enabled?`, so #6376's always-on guarantee stands."
  [frame event strategy]
  (rf.error-emit/emit-error-both!
    :rf.error/unsupported-scroll-strategy
    event
    (when (vector? event) (first event))
    frame
    nil                                   ;; no exception — a rejected value, not a throw
    0                                     ;; not a timed path
    (rf.interop/now-ms)
    ;; Axis 2 — the dev-trace tags. Keeps the RAW rejected value (this channel
    ;; is DCE'd in production and does not egress). `:recovery` is hoisted to
    ;; the envelope top level by `rf.trace/build-event`.
    (cond-> {:strategy  strategy
             :supported supported-scroll-strategies
             :reason    unsupported-strategy-reason
             :recovery  :no-scroll}
      frame (assoc :frame frame))
    ;; Axis 1 — the always-on record's category-specific attribution. `:frame`
    ;; already rides positionally; these are the slots Spec 009 promises survive
    ;; production. Every one is fixed-size and value-free, so the record's size
    ;; does not track the rejected value at all.
    {:supported     supported-scroll-strategies
     :strategy-type (:type (rf.error/diag-value-summary strategy))
     :reason        unsupported-strategy-reason
     :recovery      :no-scroll}))

(defn scroll-fx-handler
  "`:rf.nav/scroll` fx handler. Registered by the façade so a `:reload`
  re-wires it on a fresh registrar.

  rf2-px26m: the strategy vocabulary is CLOSED. The default branch used
  to return nil, which made every map-form strategy — the shape Spec 012
  once advertised as \"host-extensible\" — a silent no-op: accepted by
  the schema, carried through the planner, and then ignored, with no
  diagnostic and no scroll. There is no extension seam here (no registry,
  callback, or late-bound hook interprets a strategy), so an unrecognised
  value is a caller bug, not an extension point. It emits a loud
  `:rf.error/unsupported-scroll-strategy` naming the offending value and
  the supported set.

  This is the ALWAYS-ON leg, and rf2-2hkfy made it genuinely so. The
  `:schema` on the registration rejects the same values one step earlier
  (Spec 010 §step 5, `:fx-args`), but only when the OPTIONAL schemas
  artefact is on the classpath — without it, fx-args validation soft-passes
  and this branch is the only thing standing between the author and
  silence. It therefore fans through `emit-unsupported-strategy!`'s
  two-channel seam rather than the dev-only trace surface, so the record
  survives `:advanced` + `goog.DEBUG=false` on a schemas-less host."
  [{:keys [frame event]} {:keys [strategy saved-pos fragment]}]
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
       (emit-unsupported-strategy! frame event strategy))
     :clj
     (rf.trace/emit! :rf.fx :rf.fx/skipped-on-platform
                  {:rf.fx/id :rf.nav/scroll :strategy strategy})))
