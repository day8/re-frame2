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
