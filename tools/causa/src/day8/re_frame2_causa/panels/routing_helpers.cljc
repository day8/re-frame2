(ns day8.re-frame2-causa.panels.routing-helpers
  "Pure projection helpers for the Causa Routing tab (rf2-nrbs9, rf2-lq0ef).

  ## Why a separate `.cljc` ns

  The panel view in `routing.cljs` paints the routes lens. The
  *logic* — project the registered-routes registrar into a flat
  catalogue, derive the current-vs-from-vs-to highlight from the
  focused cascade, filter by a substring query, and simulate a URL
  against the registered patterns — is pure data → data. Splitting
  the algebra into `.cljc` so it runs under the JVM unit-test target
  (`clojure -M:test`) is required by the standing rule
  `feedback_jvm_interop_must_work.md`.

  ## Lens model (post-rf2-lq0ef reshape)

  The lens is a **flat catalogue sorted by `:path`** — never a tree.
  The audit (`ai/findings/2026-05-19-routing-inheritance-audit.md`
  verdict B) found that the previous URL-path-segmentation indentation
  was decorative: routes are flat in the spec + impl, `:parent` plays
  no role in matching, and the match-resolver is structural
  (6-rule rank on URL pattern). The previous tree conflated URL-prefix
  similarity with semantic hierarchy.

  The flat-list shape mirrors the contract. The load-bearing
  interactive surface is **Simulate-URL** — paste a URL and see the
  6-rule rank tuple per candidate plus the winner; that exposes the
  match contract Causa users actually need to reason about.

  Per-focused-event highlighting (unchanged from rf2-nrbs9):

  - `◆ HERE` on the current matched route (always — orientation).
  - `◆ FROM` / `◆ TO` arrow when the focused cascade caused
    navigation; the `:rf.route.nav-token/allocated` trace event plus
    the slice change identify the FROM (prior route) and TO (newly
    matched route).
  - Show params + query + fragment for the active route.
  - When the app has no routes registered: every projection helper
    returns the silent shape (`{:routes [] :silent? true}`) and the
    view honours silent-by-default per rf2-g3ghh.

  ## Data shape contract

  The composite the view consumes:

      {:silent?    <bool>             ;; true when no routes registered
       :routes     [<row> ...]        ;; flat, sorted by :path
       :current    <route-slice>      ;; the active :rf/route slice
       :from-id    <route-id-or-nil>  ;; nav origin when the focused
                                      ;; cascade caused navigation
       :to-id      <route-id-or-nil>  ;; nav destination when the
                                      ;; focused cascade caused navigation
       :navigated? <bool>             ;; true iff the focused cascade
                                      ;; carries a :rf.route.nav-token/
                                      ;; allocated trace event
       :query      <string-or-nil>    ;; substring filter applied to rows
       :sim-url    <string-or-nil>    ;; URL pasted into the simulator
       :sim-result <map-or-nil>}      ;; result of simulate-url for sim-url

  Each `<row>` is:

      {:route-id   <keyword>
       :path       <string>
       :doc        <string-or-nil>
       :parent     <route-id-or-nil>   ;; from registrar meta
       :tags       <set-or-nil>        ;; from registrar meta
       :has-on-match? <bool>
       :has-can-leave? <bool>
       :rank       <vector-or-nil>     ;; the 6-tuple :rf.route/rank
       :meta       <map>               ;; full registrar meta (for click-to-expand)
       :marker     <:here :from :to nil>}"
  (:require [clojure.string :as str]
            [re-frame.routing.match :as match]))

;; ---- route catalogue projection -----------------------------------------

(defn project-routes
  "Project the registered-routes map (`{<id> <meta>}`) into a vector
  of catalogue rows sorted lexicographically by `:path`. Each row
  carries the route-id, path, doc, parent, tags, the rank tuple, and
  the full meta map (so the view's click-to-expand surface can render
  the registrar entry verbatim).

  Per the audit (verdict B): no `:depth` field, no indentation hint.
  Routes are flat in the spec + impl; the catalogue mirrors that.

  Returns `[]` when the registrar is empty — the silent-by-default
  branch the view honours per rf2-g3ghh."
  [routes-map]
  (->> routes-map
       (map (fn [[id meta]]
              {:route-id        id
               :path            (or (:path meta) "")
               :doc             (:doc meta)
               :parent          (:parent meta)
               :tags            (:tags meta)
               :has-on-match?   (some? (:on-match meta))
               :has-can-leave?  (some? (:can-leave meta))
               :rank            (:rf.route/rank meta)
               :meta            meta}))
       (sort-by :path)
       vec))

;; ---- substring filter ---------------------------------------------------

(defn- row-haystack
  "Compose the searchable haystack for a single row — route-id, path,
  and doc, joined with spaces. Lower-cased once at projection time so
  the filter can do case-insensitive `clojure.string/includes?` without
  re-lowering."
  [row]
  (str/lower-case
    (str (some-> (:route-id row) str) " "
         (:path row) " "
         (or (:doc row) ""))))

(defn filter-rows
  "Substring filter. Empty / blank query returns rows verbatim;
  otherwise keep rows whose route-id, path, or doc contains the
  lower-cased query as a substring. Case-insensitive — the user's
  query is lower-cased once and matched against pre-lowered haystacks."
  [rows query]
  (let [q (some-> query str/trim)]
    (if (or (nil? q) (= "" q))
      rows
      (let [needle (str/lower-case q)]
        (filterv (fn [row] (str/includes? (row-haystack row) needle))
                 rows)))))

;; ---- Simulate-URL -------------------------------------------------------
;;
;; Per Spec 012 §Bidirectional URL ↔ params §match-url, ranking is the
;; pre-sorted route table walked in :rf.route/rank descending order;
;; the first pattern that matches the path is the winner. For the
;; simulator we want to surface ALL matching candidates with their
;; rank tuples, not just the winner — that's the load-bearing
;; interactive surface that exposes the 6-rule cascade.

(defn- split-url
  "Strip fragment + query off a URL string, returning just the path
  segment. Mirrors the splitting `match-url` performs before invoking
  `match-against`. Fragments are dropped (do not participate in
  matching per Spec 012 §Fragments); query strings are dropped
  (route patterns match against the path only)."
  [url]
  (when (string? url)
    (let [[no-frag] (str/split url #"#" 2)
          [path]    (str/split no-frag #"\?" 2)]
      (cond
        (str/blank? path) "/"
        :else             path))))

(defn- normalize-path
  "Strip a trailing slash from a multi-segment path so `/cart/` and
  `/cart` both match the same pattern. Single `/` is preserved.
  Mirrors `normalize-match-path` in re-frame.routing — the simulator
  must use the same normalization so its results match what
  match-url would actually return."
  [path]
  (cond
    (or (nil? path) (= "/" path)) (or path "/")
    (and (str/ends-with? path "/") (< 1 (count path))) (subs path 0 (dec (count path)))
    :else path))

(defn- compile-pattern-on-demand
  "If a registrar entry was seeded without `:rf.route/compiled` (e.g.
  test fixtures pass bare `{:path ...}` maps), compile the pattern on
  the fly. The 6-tuple ranks at the test-only override slot will not
  have the `(- reg-index)` trailing element, but the structural 5-tuple
  is enough to demonstrate the simulator's contract; the production
  path always has the full 6-tuple."
  [meta]
  (or (:rf.route/compiled meta)
      (when-let [path (:path meta)]
        (try
          (match/parse-pattern path)
          (catch #?(:clj Exception :cljs :default) _ nil)))))

(defn simulate-url
  "Run `url` against every registered route's pattern. Returns
  {:url <input>
   :path <stripped path>
   :candidates [{:route-id :rank :params :winner? :path} ...]
   :winner <route-id-or-nil>}

  Candidates are sorted by `:rf.route/rank` descending — the same
  order `match-url` walks the registry table. The first candidate is
  the winner (the route `match-url` would resolve to). Non-matching
  routes are excluded; an empty `:candidates` vector means
  `match-url` would return nil for this URL.

  This is purely structural — query coercion and `:params` / `:query`
  schema validation are out of scope for the simulator. The lens is
  about the rank cascade, not full match semantics."
  [routes-map url]
  (let [trimmed (some-> url str/trim)]
    (cond
      (or (nil? trimmed) (= "" trimmed))
      {:url        nil
       :path       nil
       :candidates []
       :winner     nil}

      :else
      (let [path (normalize-path (split-url trimmed))
            candidates
            (->> routes-map
                 (keep (fn [[id meta]]
                         (when-let [compiled (compile-pattern-on-demand meta)]
                           (when-let [params (match/match-against compiled path)]
                             {:route-id id
                              :path     (:path meta)
                              :rank     (or (:rf.route/rank meta)
                                            (:rank compiled))
                              :params   params}))))
                 (sort-by :rank #(compare %2 %1))
                 vec)
            winner-id (some-> candidates first :route-id)
            decorated (mapv #(assoc % :winner? (= (:route-id %) winner-id))
                            candidates)]
        {:url        trimmed
         :path       path
         :candidates decorated
         :winner     winner-id}))))

;; ---- hermetic Simulate-navigation preview -------------------------------
;;
;; The Static Routes panel surfaces a 'Simulate navigation' button per
;; row that previews what would land if the user navigated to the
;; given route — WITHOUT actually dispatching any framework event.
;; That preview is structural: the matched params (derived from the
;; row's pattern + the chosen URL), the registered `:on-match` event
;; vector, and the expected app-db slot (`[:rf/route ...]`).

(defn simulate-navigation-preview
  "Pure data → data. Given a registered-routes map + a `route-id`, plus
  an OPTIONAL `url` (the user's input from the row's Simulate-URL
  surface), return a preview map describing what a real navigation
  would carry:

      {:route-id    <keyword>
       :path        <string-or-nil>     ;; the route's registered pattern
       :url         <string-or-nil>     ;; the simulated URL (or nil)
       :matched?    <bool>              ;; did url match this route's pattern?
       :params      <map-or-nil>        ;; matched params (nil when no url)
       :on-match    <vector-or-nil>     ;; registered :on-match event vector
       :db-slot     [:rf/route]         ;; where the slice would land
       :slot-shape  <map>               ;; preview of the :rf/route map
                                        ;; that would land in app-db
       :unknown?    <bool>}             ;; true when route-id not registered

  Hermetic — no dispatch, no fx, no app-db mutation. The shape mirrors
  what the framework's `:rf.route/navigate` handler would write into
  `[:rf/route]` so the user can reason about the cascade without
  triggering it.

  When `route-id` is not in `routes-map` returns `{:unknown? true
  :route-id route-id}` — the view surfaces this as an unregistered
  route hint."
  ([routes-map route-id]
   (simulate-navigation-preview routes-map route-id nil))
  ([routes-map route-id url]
   (if-let [meta (get routes-map route-id)]
     (let [path     (:path meta)
           on-match (:on-match meta)
           sim      (when (and url (not (str/blank? url)))
                      (simulate-url routes-map url))
           winner   (some-> sim :candidates first)
           matched? (and (some? winner)
                         (= route-id (:route-id winner)))
           params   (when matched? (:params winner))
           slot     (cond-> {:id route-id}
                      (some? path)   (assoc :path path)
                      (some? params) (assoc :params params))]
       {:route-id   route-id
        :path       path
        :url        url
        :matched?   (boolean matched?)
        :params     params
        :on-match   on-match
        :db-slot    [:rf/route]
        :slot-shape slot
        :unknown?   false})
     {:route-id route-id
      :unknown? true})))

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
                         (and (not navigated?)
                              current-id
                              (= id current-id))
                         :here
                         :else nil)]
            (assoc row :marker marker)))
        rows))

;; ---- Static-surface composite projection -------------------------------
;;
;; The Static Routes panel is event-INDEPENDENT — no FROM/TO/HERE
;; markers, no focused-cascade gating, no slice detail. Just a flat
;; sorted catalogue + substring filter + Simulate-URL surface. Pure
;; data → data so the JVM unit-test target can cover the projection.

(defn project-static-data
  "View-facing composite for the Static Routes panel. Folds the
  registered-routes map + UI controls (search query + Simulate-URL)
  into the shape `static/routes/panel.cljs` consumes:

      {:silent?       <bool>             ;; true when no routes registered
       :routes        [<row> ...]        ;; flat, sorted by :path, filtered
       :total-routes  <count>            ;; pre-filter row count
       :filtered?     <bool>             ;; query removed at least one row
       :query         <string-or-nil>
       :sim-url       <string-or-nil>
       :sim-result    <map-or-nil>}

  No `:current` / `:from-id` / `:to-id` / `:navigated?` slots — those
  are Runtime concerns. Rows carry no `:marker` (the Static surface
  shows no orientation chip; orientation lives on the Runtime
  Routing lens)."
  [routes-map query sim-url]
  (let [rows       (project-routes routes-map)
        silent?    (empty? rows)
        filtered   (filter-rows rows query)
        sim-result (when (and sim-url (not (str/blank? sim-url)))
                     (simulate-url routes-map sim-url))]
    {:silent?       silent?
     :routes        filtered
     :total-routes  (count rows)
     :filtered?     (not= (count rows) (count filtered))
     :query         query
     :sim-url       sim-url
     :sim-result    sim-result}))

;; ---- topology projection (rf2-3kjlo) -----------------------------------
;;
;; The Routing panel renders a topology-plus-overlay shape per spec/021 §7:
;; the FULL routing tree is always visible (registered routes nested by
;; their `:parent` meta) and the focused epoch's nav-token activity
;; overlays as a `:to` / `:from` / `:here` marker on the relevant nodes.
;;
;; The projection produces a vector of `{:row :depth :children}` entries
;; suitable for textual `├─ └─ │` tree rendering (per §7.1 density note —
;; most route trees are shallow ≤ 4 levels, so the textual tree is denser
;; than xyflow). `:parent`-less routes become tree roots; orphaned
;; `:parent` references (parent route-id not in the registrar) also
;; become roots so the projection never drops a row.

(defn- children-by-parent
  "Group `rows` (already projected by `project-routes`) by their
  `:parent` route-id. Returns `{<parent-id-or-nil> [row ...]}` with
  children stable-sorted by `:path` so the tree renders deterministic."
  [rows]
  (->> rows
       (group-by :parent)
       (reduce-kv (fn [acc parent kids]
                    (assoc acc parent (vec (sort-by :path kids))))
                  {})))

(defn- rooted?
  "A row is a tree root when its `:parent` is nil OR when the parent
  reference points to a route-id that is not in the registrar. The
  second branch keeps orphaned children visible — the projection
  promises every row appears exactly once."
  [row registered-ids]
  (or (nil? (:parent row))
      (not (contains? registered-ids (:parent row)))))

(defn project-topology
  "Project the registered-routes map into a depth-decorated topology
  vector — the data shape the Routing panel's tree-render consumes.

  Returns `[{:row <row-from-project-routes>
             :depth <int>           ;; 0 for roots, +1 per :parent hop
             :last-at-depth? <bool> ;; true when this row is the last
                                    ;; sibling at its depth (used by
                                    ;; the view to pick `└─` vs `├─`)
             } ...]`

  Routes with `:parent` references that resolve to a registered
  route-id nest beneath that route; routes without a parent (or with
  an orphan parent) render at depth 0. Within each parent's children,
  rows are sorted by `:path` so the topology is deterministic.

  Cycles in the `:parent` graph (e.g. A → B → A) are protected against
  via a `visited` set — a row that would re-enter the walk is skipped
  at the second visit. The first visit wins; the second occurrence
  drops out so the projection terminates and rows still appear exactly
  once."
  [routes-map]
  (let [rows           (project-routes routes-map)
        registered-ids (set (map :route-id rows))
        kids           (children-by-parent rows)
        roots          (->> rows
                            (filter #(rooted? % registered-ids))
                            (sort-by :path)
                            vec)
        walk (fn walk [row depth visited last-sibling?]
               (let [id (:route-id row)]
                 (if (contains? visited id)
                   ;; Cycle protection — drop the re-entry.
                   []
                   (let [visited' (conj visited id)
                         children (get kids id [])
                         last-idx (dec (count children))
                         child-rows
                         (->> children
                              (map-indexed
                                (fn [i child]
                                  (walk child (inc depth) visited'
                                        (= i last-idx))))
                              (reduce into []))]
                     (into [{:row            row
                             :depth          depth
                             :last-at-depth? (boolean last-sibling?)}]
                           child-rows)))))
        last-root-idx (dec (count roots))]
    (->> roots
         (map-indexed
           (fn [i root]
             (walk root 0 #{} (= i last-root-idx))))
         (reduce into []))))

;; ---- per-epoch routing-activity projection (rf2-3kjlo) ------------------
;;
;; The "This epoch" block (per spec/021 §7.2) reads four short lines:
;;
;;     Phase     :on-match
;;     From      /
;;     To        /cart
;;     Match     {:route :cart}
;;     Events    [:rf/url-changed] [:cart/route-entered]
;;
;; The phase derives from the trace-event mix in the focused cascade:
;;   - cascade carries :rf.route.nav-token/allocated → :on-match
;;     (a navigation actually landed this epoch)
;;   - cascade carries :rf.route/navigation-blocked → :navigation-blocked
;;   - cascade carries :rf.route/fragment-changed → :fragment-changed
;;   - otherwise → nil (no routing activity this epoch)
;;
;; The phase + from/to + matched-params + the event-vector list go into
;; the "This epoch" block; when the projection returns nil for everything
;; the view renders the "No route activity in this epoch." caption per
;; spec §7.2 empty-state branch.

(def ^:private routing-phase-ops
  "Trace operations that indicate a routing-related phase. Order is
  significant: nav-token/allocated wins (the actual on-match), then
  navigation-blocked, then fragment-changed. The first match wins."
  [[:rf.route.nav-token/allocated :on-match]
   [:rf.route/navigation-blocked  :navigation-blocked]
   [:rf.route/fragment-changed    :fragment-changed]])

(defn- cascade-event-vectors
  "Collect raw event vectors from a cascade. The cascade's top-level
  `:event` slot is the root event; the `:effects` and `:other` buckets
  may carry `:event/dispatched` trace records whose `:tags :event`
  carries downstream event vectors. Returns a vector of event vectors
  in insertion order, de-duplicated by identity."
  [cascade]
  (when cascade
    (let [root       (:event cascade)
          downstream (->> (cascade-trace-events cascade)
                          (keep (fn [ev]
                                  (when (and (map? ev)
                                             (= :event/dispatched
                                                (:operation ev)))
                                    (get-in ev [:tags :event])))))
          all        (cond->> downstream
                       root (cons root))]
      (->> all
           (filter some?)
           distinct
           vec))))

(defn epoch-routing-activity
  "Derive a `{:phase :events :match}` map describing what the focused
  cascade did to the route system this epoch. Returns nil when the
  cascade is nil OR carries no routing-related trace events (the
  view's 'No route activity' branch).

  `:phase` is one of `#{:on-match :navigation-blocked :fragment-changed}`
  per the trace-event mix.
  `:events` is the cascade's event-vector list (root + downstream
  dispatches), useful for the spec §7.2 'Events' row.
  `:match` is the matched params map when phase is `:on-match` —
  read off the framework's slice after the navigate handler wrote it.

  The view layer renders this alongside the topology overlay; both
  read off the same focused-cascade so they stay in sync."
  [cascade current-slice]
  (when cascade
    (let [trace-evs (cascade-trace-events cascade)
          phase    (some (fn [[op phase-kw]]
                           (when (some #(and (map? %)
                                             (= op (:operation %)))
                                       trace-evs)
                             phase-kw))
                         routing-phase-ops)]
      (when phase
        {:phase  phase
         :events (cascade-event-vectors cascade)
         :match  (when (= :on-match phase)
                   (:params current-slice))}))))

;; ---- composite projection ----------------------------------------------

(defn project-data
  "The view-facing composite. Folds the registered-routes map +
  current route slice + focused cascade + UI controls (search query +
  Simulate-URL) into the shape the panel consumes (see ns doc §Data
  shape contract).

  Inputs are all pre-projected by the registry sub layer; this fn is
  pure data → data so it slots into the JVM unit-test target.

  Silent-by-default per rf2-g3ghh: when no routes are registered the
  fn returns `{:silent? true :routes [] ...}` and the view renders
  the empty section (no `(none)` placeholder)."
  ([routes-map current-slice focused-cascade]
   (project-data routes-map current-slice focused-cascade nil nil))
  ([routes-map current-slice focused-cascade query sim-url]
   (let [rows         (project-routes routes-map)
         silent?      (empty? rows)
         nav          (from-to-from-cascade focused-cascade current-slice)
         decorated    (assign-markers rows
                                      (assoc nav
                                        :current-id (:id current-slice)))
         filtered     (filter-rows decorated query)
         sim-result   (when (and sim-url (not (str/blank? sim-url)))
                        (simulate-url routes-map sim-url))]
     {:silent?       silent?
      :routes        filtered
      :total-routes  (count rows)
      :filtered?     (not= (count rows) (count filtered))
      :current       current-slice
      :from-id       (:from-id nav)
      :to-id         (:to-id nav)
      :navigated?    (:navigated? nav)
      :query         query
      :sim-url       sim-url
      :sim-result    sim-result})))

;; ---- topology-plus-overlay composite (rf2-3kjlo) -----------------------

(defn project-topology-data
  "The view-facing composite for the Routing panel's topology-plus-overlay
  shape (per spec/021 §7).

  Returns:

      {:silent?    <bool>                 ;; true when no routes registered
       :topology   [{:row :depth :last-at-depth? :marker} ...]
                                          ;; the full route tree, depth-decorated
       :current    <route-slice>          ;; the active :rf/route slice
       :from-id    <route-id-or-nil>      ;; nav origin this epoch
       :to-id      <route-id-or-nil>      ;; nav destination this epoch
       :navigated? <bool>                 ;; true iff focused cascade navigated
       :activity   <map-or-nil>}          ;; per-epoch routing activity
                                          ;; (phase + events + match);
                                          ;; nil ⇒ \"no route activity in this
                                          ;; epoch\" branch per spec §7.2

  The topology is always the FULL tree — overlays land via `:marker`
  on the matching rows (`:to` for the destination, `:from` for the
  origin, `:here` for the current route when no navigation happened
  this epoch). The view paints the topology unconditionally so the
  operator's mental map of the registered routes stays stable across
  epoch focus changes."
  [routes-map current-slice focused-cascade]
  (let [topology       (project-topology routes-map)
        silent?        (empty? topology)
        nav            (from-to-from-cascade focused-cascade current-slice)
        marker-input   (assoc nav :current-id (:id current-slice))
        decorated      (mapv (fn [{:keys [row] :as entry}]
                               (let [marked-row (first
                                                  (assign-markers
                                                    [row]
                                                    marker-input))]
                                 (assoc entry
                                        :row    marked-row
                                        :marker (:marker marked-row))))
                             topology)
        activity       (epoch-routing-activity focused-cascade current-slice)]
    {:silent?    silent?
     :topology   decorated
     :current    current-slice
     :from-id    (:from-id nav)
     :to-id      (:to-id nav)
     :navigated? (:navigated? nav)
     :activity   activity}))
