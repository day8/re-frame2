(ns day8.re-frame2-causa.panels.routes-helpers
  "Pure-data helpers for Causa's Routes panel (Phase 5, rf2-6blai,
  parent rf2-5aw5v).

  ## Why a separate `.cljc` ns

  Same dual-target pattern every other panel uses (flows,
  subscriptions, causality-graph, time-travel, ...). The panel view in
  `routes.cljs` builds the hiccup; the *logic* — folding the registered-
  route set + the `:rf/route` slice + the trace-buffer's `:route.*` /
  `:rf.route/*` event slice into per-row data — is pure data → data
  and runs under the JVM unit-test target (`clojure -M:test`).

  ## What this panel surfaces (per the bead's minimum-viable contract)

  Three sections, stacked top-to-bottom:

    1. **Active route** — a breadcrumb-style strip showing the
       currently-matched `:rf/route` slice for the target frame. Per
       Spec 012 §The `:rf/route` slice the slice carries
       `{:id :params :query :fragment :transition :error :nav-token}`.

    2. **Registered routes** — one row per `(rf/handlers :route)` entry.
       Each row shows the route-id + path-pattern + (when present) the
       `:doc` summary. The active-route row is highlighted. Clicking a
       row selects it (`:rf.causa/select-route`); the v1 surface carries
       the selection only — the deeper detail view (click → source
       coord) lands when the cross-panel jump API stabilises.

    3. **Navigation history** — recent navigation trace events from the
       Causa buffer (newest first). Per Spec 012 §Trace events the
       runtime emits:

         :rf.route.nav-token/allocated       — fresh navigation begins
         :rf.route.nav-token/stale-suppressed — stale async result dropped
         :rf.route/url-changed             — fragment-only URL update

       Each row shows the timestamp + operation + (when available) the
       route-id and nav-token. Click → `:rf.causa/select-dispatch-id`
       (pivots to event-detail, parity with the Issues ribbon / Trace
       panel row-click).

  ## Empty state

  Per the bead's minimum-viable contract: \"No routes registered.\" The
  empty state surfaces only when `(rf/handlers :route)` is empty AND
  the override slot is unset. If a frame has the route surface live
  but no routes registered yet the same copy surfaces — the contract
  is 'nothing in the registry, nothing to show'.

  ## What v1 does NOT include

    - No nav-token timeline visualisation. The history list is the v1
      surface; the timeline (per spec/000-Vision.md L97 'nav-token
      timeline with stale-result suppression') rides a follow-on bead
      once the cross-panel time-axis primitive lands (shared with the
      Trace + Schema panels).
    - No can-leave / pending-navigation slot inspector. The slot is
      part of Spec 012 but the panel's v1 reads only `:rf/route`; the
      `:rf/pending-navigation` surface lands with the
      navigation-blocked detail bead.
    - No route-link affordance. The panel is read-only at v1 — it does
      not navigate the host app. Programmatic navigation rides through
      the framework's `:rf.route/navigate` event; the panel surfaces
      the result, not the trigger.

  ## What this doesn't do

  Pure data. No subscription, no atom, no `js/` interop — the same
  fn runs under CLJ and CLJS. The CLJS-only surfaces (`rf/handlers`
  on a populated registrar, `rf/get-frame-db` on a frame) are read by
  the composite sub in `registry.cljs`; the result is handed to this
  ns as a plain map."
  (:require [clojure.string :as str]
            [day8.re-frame2-causa.panels.common-helpers :as common]))

;; ---- canonical operation taxonomy ---------------------------------------

(def history-operations
  "Trace operations the history feed surfaces. Per Spec 012 §Trace
  events. The order here is informational only — actual feed ordering
  is newest-first via :time / :id."
  #{:rf.route.nav-token/allocated
    :rf.route.nav-token/stale-suppressed
    :rf.route/url-changed})

;; ---- predicates ---------------------------------------------------------

(defn route-history-event?
  "True when `ev` is one of the route-history trace operations.
  Pure predicate — JVM-runnable; the composite sub uses it to filter
  the Causa trace buffer to the history slice."
  [{:keys [operation] :as _ev}]
  (boolean (contains? history-operations operation)))

(defn filter-history-events
  "Return only the route-history events from a trace-event vector.
  Oldest first (matches the buffer's natural order). Pure fn."
  [events]
  (filterv route-history-event? (or events [])))

;; ---- defensive tag access -----------------------------------------------

;; Re-export `tag-of` under the local name `tag` so existing call sites
;; (`(tag ev :route-id)`, etc.) keep working without churn. Body lives in
;; `common-helpers`.
(def ^:private tag common/tag-of)

;; ---- registered-route projection ----------------------------------------

(defn project-route-row
  "Project one `[route-id metadata]` entry into the row shape the view
  consumes. Pure fn — JVM-runnable.

  Per Spec 012 §Reserved route-metadata keys the registrar reserves
  `:doc :path :params :query :tags :parent :on-match :on-error
  :scroll`. The row carries the keys most directly useful for at-a-
  glance scanning; the deeper metadata reads via `(rf/handler-meta
  :route route-id)` from a follow-on detail bead.

  Row shape:

      {:route-id <id>
       :path     <string-or-nil>
       :doc      <string-or-nil>
       :tags     <set-or-nil>
       :parent   <route-id-or-nil>
       :on-match <vec-or-nil>}"
  [[route-id meta]]
  {:route-id route-id
   :path     (:path meta)
   :doc      (:doc meta)
   :tags     (:tags meta)
   :parent   (:parent meta)
   :on-match (:on-match meta)})

(defn project-route-rows
  "Project the registered-routes map into a sorted vector of row maps.
  Pure fn — JVM-runnable.

  Sort order: by `:path` (lexical, nil last) then by stringified
  `:route-id` for deterministic test output. The lexical sort keeps
  the canonical / catch-all routes (`/`, `/*`) grouped at the top of
  the list, with longer paths following in segment-order — the same
  reading order a programmer scanning a route table expects."
  [routes-map]
  (if (empty? routes-map)
    []
    (let [rows (mapv project-route-row routes-map)]
      (vec
        (sort-by (fn [{:keys [path route-id]}]
                   [(if (nil? path) 1 0)
                    (str path)
                    (pr-str route-id)])
                 rows)))))

;; ---- active-route projection --------------------------------------------

(defn project-active-route
  "Project the target frame's `:rf/route` slice into a flat map the
  view's breadcrumb consumes. Per Spec 012 §The `:rf/route` slice the
  slice carries `{:id :params :query :fragment :transition :error
  :nav-token}`. Returns nil when no slice is present.

  Pure fn — JVM-runnable. The composite sub reads
  `(get target-frame-db :rf/route)` and hands the result here."
  [route-slice]
  (when (map? route-slice)
    {:route-id   (:id route-slice)
     :params     (:params route-slice)
     :query      (:query route-slice)
     :fragment   (:fragment route-slice)
     :transition (:transition route-slice)
     :error      (:error route-slice)
     :nav-token  (:nav-token route-slice)}))

;; ---- history projection -------------------------------------------------

(defn project-history-entry
  "Project one route-history trace event into the row shape the view
  consumes. Pure fn — JVM-runnable.

  Row shape:

      {:id           <trace-event-id>
       :time         <ms>
       :operation    <kw>
       :route-id     <id-or-nil>
       :nav-token    <token-or-nil>
       :fragment     <string-or-nil>
       :dispatch-id  <id-or-nil>
       :raw          <trace-event>}

  The `:dispatch-id` slot, when present, drives the row-click pivot
  into the event-detail panel (same affordance the Issues ribbon /
  Trace panel ship). nil `:dispatch-id` makes the row non-pivotable —
  the click handler degrades gracefully."
  [{:keys [id time operation] :as ev}]
  {:id          id
   :time        time
   :operation   operation
   :route-id    (tag ev :route-id)
   :nav-token   (tag ev :nav-token)
   :fragment    (tag ev :fragment)
   :dispatch-id (tag ev :dispatch-id)
   :raw         ev})

(defn project-history
  "Project the route-history trace events into a vector of entry maps.
  Newest first per the view's display order. Pure fn — JVM-runnable.

  `n` caps the rendered count — the panel surfaces only the most
  recent navigations; older entries remain in the underlying trace
  buffer for the Trace panel to scroll through."
  ([events]
   (project-history events 50))
  ([events n]
   (->> (or events [])
        reverse
        (take n)
        (mapv project-history-entry))))

;; ---- composite projection (the panel reads this) ------------------------

(defn project-feed
  "Top-level projection — produces every slot the view needs in one
  pass. Pure data → data; JVM-runnable.

  Inputs:

    `routes-map`     — `{route-id metadata}` from `(rf/handlers :route)`.
                       May be empty / nil; the projection returns rows
                       = [] and the view renders the empty state.

    `route-slice`    — `(get target-frame-db :rf/route)`. May be nil
                       (no navigation has settled yet); the view falls
                       back to the empty active-route breadcrumb.

    `history-events` — trace events filtered to route-history
                       operations, oldest first.

    `selected-route-id` — the user's active selection, or nil.

  Returns:

      {:rows              [<row> ...]
       :total             <int>
       :active-route      <projected-or-nil>
       :selected-route-id <id-or-nil>
       :history           [<entry> ...]
       :empty-kind        <:no-routes / :no-history / nil>}

  `:empty-kind` discriminates the panel's two empty branches:

      :no-routes  — `routes-map` is empty. The view paints the
                    'No routes registered.' empty state.
      nil         — at least one route is registered; render the
                    rows + active-route + history."
  [routes-map route-slice history-events selected-route-id]
  (let [rows         (project-route-rows routes-map)
        active       (project-active-route route-slice)
        history-rows (project-history history-events)
        empty-kind   (cond
                       (empty? rows) :no-routes
                       :else         nil)]
    {:rows              rows
     :total             (count rows)
     :active-route      active
     :selected-route-id selected-route-id
     :history           history-rows
     :empty-kind        empty-kind}))

;; ---- formatting helpers (consumed by the view) -------------------------

(defn format-route-id
  "Render a route-id for compact display in the mono column. Keywords
  keep their `:` prefix; everything else falls back to `str` trimmed."
  [route-id]
  (cond
    (keyword? route-id) (str route-id)
    (symbol?  route-id) (str route-id)
    :else               (str/trim (str route-id))))

(defn format-path
  "Pretty-print a path-pattern for display. Strings come through
  unchanged (the canonical Spec 012 path-pattern grammar is a string).
  Nil falls back to the empty-marker `\"—\"` so the mono column stays
  stable across row widths."
  [path]
  (cond
    (nil? path)    "—"
    (string? path) path
    :else          (try (pr-str path)
                        (catch #?(:clj Throwable :cljs :default) _
                          (str path)))))

(defn format-params
  "Render a path-params / query-params map for display. Empty maps
  collapse to `\"—\"`; populated maps render via `pr-str` to keep the
  same printable shape consumers see at the REPL."
  [m]
  (if (empty? m)
    "—"
    (try (pr-str m)
         (catch #?(:clj Throwable :cljs :default) _
           (str m)))))

(defn format-time
  "Render `t` (ms-since-epoch) as `HH:MM:SS.mmm`. Pure-ish — uses the
  platform Date constructor. Mirrors `issues-ribbon-helpers/format-
  time`; copied here rather than reused so the routes panel has no
  cross-panel coupling."
  [t]
  (when (number? t)
    #?(:clj  (let [^java.time.Instant inst (java.time.Instant/ofEpochMilli (long t))
                   ^java.time.LocalTime lt (.toLocalTime
                                             (.atZone inst (java.time.ZoneId/systemDefault)))]
               (format "%02d:%02d:%02d.%03d"
                       (.getHour lt)
                       (.getMinute lt)
                       (.getSecond lt)
                       (long (mod t 1000))))
       :cljs (let [d (js/Date. t)
                   pad (fn [n w]
                         (let [s (str n)]
                           (if (< (count s) w)
                             (str (apply str (repeat (- w (count s)) "0")) s)
                             s)))]
               (str (pad (.getHours d) 2) ":"
                    (pad (.getMinutes d) 2) ":"
                    (pad (.getSeconds d) 2) "."
                    (pad (.getMilliseconds d) 3))))))

(defn format-operation
  "Render a history-entry operation as a short label. Keeps the table
  legible in the mono column without losing the operation's identity."
  [op]
  (case op
    :rf.route.nav-token/allocated        "nav · allocated"
    :rf.route.nav-token/stale-suppressed "nav · stale"
    :rf.route/url-changed             "url-changed (fragment)"
    (str op)))
