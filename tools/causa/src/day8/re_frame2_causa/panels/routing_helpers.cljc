(ns day8.re-frame2-causa.panels.routing-helpers
  "Pure projection helpers for the Causa Routing tab (rf2-nrbs9).

  ## Why a separate `.cljc` ns

  The panel view in `routing.cljs` paints the route-tree lens. The
  *logic* — project the registered-routes registrar into a stable
  tree structure, derive the current-vs-from-vs-to highlight from
  the focused cascade's trace events + app-db slice — is pure data
  → data. Splitting the algebra into `.cljc` so it runs under the
  JVM unit-test target (`clojure -M:test`) is required by the
  standing rule `feedback_jvm_interop_must_work.md`.

  ## Lens model

  Always-shown structure: the full route tree (every registered
  route, sorted by path so the rendering is deterministic).

  Per-focused-event highlighting:

  - `◆ HERE` on the current matched route (always — orientation).
  - `◆ FROM` / `◆ TO` arrow when the focused cascade caused
    navigation; the `:rf.route.nav-token/allocated` trace event
    plus the slice change identify the FROM (prior route) and TO
    (newly matched route).
  - Show params + query for the active route.
  - No transition arrow when the focused event has no routing
    impact (still shows `◆ HERE`).
  - When the app has no routes registered: every projection helper
    returns the silent shape (`{:routes [] :silent? true}`) and the
    view honours silent-by-default per rf2-g3ghh.

  ## Data shape contract

  The composite the view consumes:

      {:silent?    <bool>             ;; true when no routes registered
       :routes     [<row> ...]        ;; the route tree, sorted by path
       :current    <route-slice>      ;; the active :rf/route slice
       :from-id    <route-id-or-nil>  ;; nav origin when the focused
                                      ;; cascade caused navigation
       :to-id      <route-id-or-nil>  ;; nav destination when the
                                      ;; focused cascade caused navigation
       :navigated? <bool>}            ;; true iff the focused cascade
                                      ;; carries a :rf.route.nav-token/
                                      ;; allocated trace event

  Each `<row>` is:

      {:route-id   <keyword>
       :path       <string>
       :depth      <int>              ;; tree depth (0 = root)
       :doc        <string-or-nil>
       :marker     <:here :from :to nil>}  ;; render glyph hint"
  (:require [clojure.string :as str]))

;; ---- route tree projection ----------------------------------------------

(defn- path-segments
  "Split a route path into its `/`-delimited segments. The root path
  `/` collapses to `[]` so it slots in at depth 0; an empty / nil
  path is treated as the root."
  [path]
  (let [p (or path "")]
    (->> (str/split p #"/")
         (remove str/blank?)
         vec)))

(defn- path-depth
  "Tree depth for a route path. Root (`/` or empty) sits at depth 0;
  every `/`-separated segment adds one level."
  [path]
  (count (path-segments path)))

(defn project-route-tree
  "Project the registered-routes map (`{<id> <meta>}`) into a vector
  of `{:route-id :path :depth :doc}` rows sorted by path. The path
  sort is lexicographic so siblings under a common prefix render
  contiguously; depth comes for free from the path itself.

  Returns `[]` when the registrar is empty — the silent-by-default
  branch the view honours per rf2-g3ghh."
  [routes-map]
  (->> routes-map
       (map (fn [[id meta]]
              {:route-id id
               :path     (or (:path meta) "")
               :depth    (path-depth (:path meta))
               :doc      (:doc meta)}))
       (sort-by :path)
       vec))

;; ---- focused-cascade routing detection ----------------------------------

(def ^:private nav-allocated-op
  "Trace operation emitted by `:rf.route/navigate` and
  `:rf.route/handle-url-change` when a navigation cascade allocates a
  nav-token (per `implementation/routing/src/re_frame/routing.cljc`
  §`trace/emit! :event :rf.route.nav-token/allocated`)."
  :rf.route.nav-token/allocated)

(defn focused-cascade
  "Find the cascade in `cascades` whose `:dispatch-id` matches
  `focused-id`. Returns nil when the id has aged out of the buffer
  or the spine has no focus yet."
  [cascades focused-id]
  (when focused-id
    (some #(when (= focused-id (:dispatch-id %)) %) cascades)))

(defn- cascade-trace-events
  "Flatten every trace-event bucket on a cascade into one seq. Per
  `re-frame.trace.projection/group-cascades` a cascade record carries
  raw trace maps under `:handler`, `:fx`, `:effects`, `:subs`,
  `:renders`, and `:other`; the `:event` slot is the bare event
  vector (not a trace map) so it's excluded. The
  `:rf.route.nav-token/allocated` emit lands in `:other` per the
  projection's catch-all bucket."
  [cascade]
  (concat
    (when-let [handler (:handler cascade)] [handler])
    (when-let [fx (:fx cascade)] [fx])
    (:effects cascade)
    (:subs cascade)
    (:renders cascade)
    (:other cascade)))

(defn nav-token-allocated-in-cascade
  "Scan a cascade's trace-event buckets for a
  `:rf.route.nav-token/allocated` event. Returns the trace event map
  (with its `:tags` carrying `:route-id` + `:nav-token`) when present;
  nil otherwise. The emit fires inside both `:rf.route/navigate` (a
  programmatic dispatch) and `:rf/url-changed` (browser-driven URL
  change), so this catches both navigation paths uniformly."
  [cascade]
  (when cascade
    (some (fn [ev]
            (when (and (map? ev)
                       (= nav-allocated-op (:operation ev)))
              ev))
          (cascade-trace-events cascade))))

(defn from-to-from-cascade
  "Derive a `{:from-id :to-id :navigated?}` map from a focused cascade
  + the current route slice.

  - `:to-id` comes from the nav-token-allocated trace event (the new
    route the cascade navigated to).
  - `:from-id` is the current slice's `:id` IFF it differs from
    `:to-id`. Two cases collapse to no FROM:
      - first navigation in the session (no prior slice — `current`
        is nil so `:from-id` resolves to nil).
      - same-route re-navigation (different params/query but same
        route-id — surfacing a FROM equal to TO is noise).

  The slice's `:id` is the *post-navigation* value (the navigate
  handler writes the slice before this projection runs); for the
  prior route we would need to scan trace history, which is
  out-of-scope for v1 — the same-id collapse keeps the lens honest.

  Pre-navigation contract: when the cascade carries no nav-token
  emit, returns `{:navigated? false :from-id nil :to-id nil}`."
  [cascade current-slice]
  (let [nav-ev (nav-token-allocated-in-cascade cascade)
        to-id  (some-> nav-ev :tags :route-id)
        current-id (:id current-slice)
        from-id (when (and to-id current-id (not= to-id current-id))
                  current-id)]
    (cond
      (nil? nav-ev)
      {:navigated? false :from-id nil :to-id nil}

      :else
      {:navigated? true
       :from-id    from-id
       :to-id      to-id})))

;; ---- row marker assignment ---------------------------------------------

(defn assign-markers
  "Decorate each row in `rows` with `:marker` ∈ #{:here :from :to nil}
  per the lens contract.

  Marker priority (per the spec):
    1. `:to` wins (the navigation destination — the new HERE).
    2. `:from` for the navigation origin.
    3. `:here` for the current route when no navigation happened.

  A route can hold `:to` AND `:here` simultaneously (TO is the new
  current); we prefer the stronger `:to` glyph so the lens shows the
  navigation outcome at a glance. The view can decide to render both
  glyphs if it wants — the helper returns a single marker per row to
  keep the contract crisp."
  [rows {:keys [from-id to-id current-id navigated?]}]
  (mapv (fn [row]
          (let [id (:route-id row)
                marker (cond
                         (and to-id (= id to-id))          :to
                         (and from-id (= id from-id))      :from
                         ;; HERE shows whether or not we navigated —
                         ;; it's the orientation glyph. When :to is
                         ;; set the same row carries :to, so HERE
                         ;; only surfaces independently when the
                         ;; cascade didn't navigate.
                         (and (not navigated?)
                              current-id
                              (= id current-id))
                         :here
                         :else nil)]
            (assoc row :marker marker)))
        rows))

;; ---- composite projection ----------------------------------------------

(defn project-data
  "The view-facing composite. Folds the registered-routes map +
  current route slice + focused cascade into the shape the panel
  consumes (see ns doc §Data shape contract).

  Inputs are all pre-projected by the registry sub layer; this fn is
  pure data → data so it slots into the JVM unit-test target.

  Silent-by-default per rf2-g3ghh: when no routes are registered the
  fn returns `{:silent? true :routes [] ...}` and the view renders
  the empty section (no `(none)` placeholder)."
  [routes-map current-slice focused-cascade]
  (let [rows         (project-route-tree routes-map)
        silent?      (empty? rows)
        nav          (from-to-from-cascade focused-cascade current-slice)
        decorated    (assign-markers rows
                                     (assoc nav
                                       :current-id (:id current-slice)))]
    {:silent?    silent?
     :routes     decorated
     :current    current-slice
     :from-id    (:from-id nav)
     :to-id      (:to-id nav)
     :navigated? (:navigated? nav)}))
