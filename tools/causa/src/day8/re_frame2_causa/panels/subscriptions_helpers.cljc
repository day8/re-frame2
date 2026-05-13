(ns day8.re-frame2-causa.panels.subscriptions-helpers
  "Pure-data helpers for Causa's Subscriptions panel
  (Phase 5, rf2-x0f5v, parent rf2-5aw5v).

  ## Why a separate `.cljc` ns

  The panel view in `subscriptions.cljs` builds the table + chip
  hiccup; the *logic* — folding the live sub-cache + the just-settled
  epoch's `:sub-runs` projection into a per-sub status badge — is
  pure data → data. Splitting that algebra into `.cljc` so it runs
  under the JVM unit-test target (`clojure -M:test`) is required by
  `feedback_jvm_interop_must_work.md` and mirrors the
  `causality_graph_helpers.cljc` / `time_travel_helpers.cljc` shape.

  ## The five-status taxonomy (per spec/012-Subscriptions.md §The
  five statuses)

  Every sub the panel renders carries **exactly one** status badge.
  The taxonomy is small by design — five statuses, each paired with
  a colour + a shape + a tooltip label so colour-blind users get the
  same signal:

  | Status              | Glyph | Colour token   | Tooltip               |
  |---------------------|-------|----------------|-----------------------|
  | `:fresh`            | `●`   | `:green`       | Fresh                 |
  | `:re-running`       | `◐`   | `:cyan`        | Re-running            |
  | `:invalidated`      | `◌`   | `:yellow`      | Invalidated           |
  | `:cached-no-watcher`| `○`   | `:text-tertiary` | Cached, no watcher  |
  | `:error`            | `▲`   | `:red`         | Error                 |

  Note the deliberate **absence** of a `:stale` status (per spec
  §\"Stale vs invalidated\") — re-frame's sub-cache is equality-driven,
  not wall-clock-driven; there is no middle ground.

  ## Inputs to the projection

  The composite sub `:rf.causa/subscriptions-data` feeds three
  sources into `project-rows`:

    1. **`sub-cache`** — the live `{query-v {:value :ref-count}}`
       map (`(rf/sub-cache frame-id)`). CLJS-only; nil on JVM.

    2. **`epoch-record`** — the just-settled epoch's record (per
       Spec-Schemas `:rf/epoch-record`). The `:sub-runs` slot is the
       per-cascade `:sub-id` / `:query-v` / `:recomputed?` projection
       Causa needs to mark a sub as `:fresh` for this epoch.

    3. **`error-cache`** — Causa-internal map of `query-v → throwable`
       collected on framework `:error` trace events that carry a
       `:sub-id`. Causa stores it on its own app-db; the helpers here
       treat the cache as an opaque lookup.

  ## What this doesn't do

  This ns is **pure data**. No subscription, no atom, no `js/`
  interop — the same fn runs under CLJ and CLJS. The CLJS-only
  surfaces (`(rf/sub-cache ...)`) are read by the composite sub in
  `registry.cljs`; the result is handed to this ns as a plain map.

  ## Invalidation-chain (per spec §Invalidation-chain affordance)

  v1 ships the badge taxonomy + a one-level chain walk. The
  `compute-chain` fn surfaces:

  - the focused sub's `query-v`
  - its inputs (lifted off the sub-cache entry's `:input-subs` slot)
  - which inputs **re-ran** this cascade (from `:sub-runs`)
  - the originating `app-db` paths when an input is layer-1

  Deeper walks (recursive inputs-of-inputs) are out of v1 scope —
  the spec caps the chain at 8 layers but the v1 helper renders the
  top layer + one level of inputs and links into the App-DB Diff
  panel for the slice-level walk. This is the minimum surface that
  validates the data plane; the deeper recursion lands in a
  follow-on bead once the panel is wired to a real cascade."
  (:require [clojure.string :as str]))

;; ---- design tokens (mirrors view tokens — kept here for tests) -----------
;;
;; The view consumes these via this ns so the status → token mapping
;; has one source of truth. The pure-data side stays JVM-portable.

(def status->token
  "Per spec/012-Subscriptions.md §Colour is never alone — the
  status → colour-token mapping. Each token resolves to a hex on
  the view side. Source of truth for the panel + every other
  Causa surface that renders a sub badge."
  {:fresh             :green
   :re-running        :cyan
   :invalidated       :yellow
   :cached-no-watcher :text-tertiary
   :error             :red})

(def status->glyph
  "Per spec §Colour is never alone — the shape pair. The glyph
  carries the taxonomy without any colour signal, so the panel is
  legible to colour-blind users."
  {:fresh             "●"
   :re-running        "◐"
   :invalidated       "◌"
   :cached-no-watcher "○"
   :error             "▲"})

(def status->tooltip
  "Per spec §Colour is never alone — the tooltip label. Hover over
  a badge surfaces this string after 250ms."
  {:fresh             "Fresh"
   :re-running        "Re-running"
   :invalidated       "Invalidated"
   :cached-no-watcher "Cached, no watcher"
   :error             "Error"})

(def statuses
  "Canonical ordering — errors first, then re-running, then
  invalidated, then fresh, then cached-no-watcher. Per spec
  §Filtering and grouping — the default sort. Tests assert against
  this order so a panel-wide change is one edit."
  [:error :re-running :invalidated :fresh :cached-no-watcher])

;; ---- helpers -------------------------------------------------------------

(defn- sub-runs-by-query-v
  "Index a `:sub-runs` vector by `:query-v` → the sub-run record.
  Used by `compute-status` to ask 'did this sub re-run this epoch?'
  in O(1). The vector is small (per-cascade) so a fresh re-index on
  every projection call is fine."
  [sub-runs]
  (into {}
        (map (juxt :query-v identity))
        (or sub-runs [])))

(defn compute-status
  "Project one cache entry's status. Pure fn — takes the cache entry
  (the sub-cache value for a `query-v`), the just-settled cascade's
  sub-run record for the same `query-v` (or nil), and a flag
  indicating the sub threw on its most recent compute. Returns one
  of `#{:fresh :re-running :invalidated :cached-no-watcher :error}`.

  Decision order (per spec §The five statuses):

    1. `:error`            — most recent compute threw (explicit flag).
    2. `:re-running`       — `:rerunning?` is true on the cache entry.
       (The runtime sets the flag during a layer-2+ recompute inside
       the drain; Causa observes it via the sub-cache.)
    3. `:fresh`            — the sub re-ran this cascade and produced
       its current cached value (`:sub-runs` carries a record with
       `:recomputed? true`).
    4. `:invalidated`      — `:invalidated?` is true on the cache entry,
       but the sub has no watcher; a future deref would recompute.
    5. `:cached-no-watcher` — fallback when ref-count is zero.
    6. `:fresh`             — fallback when the sub has a watcher but
       no signal of invalidation (a stable cached value the panel
       displays as current).

  Note this fn does **not** consult `:ref-count` directly for the
  fresh / invalidated split — that distinction is the runtime's
  call (the `:invalidated?` flag) per spec/006 §Invalidation
  algorithm. The fn falls back on `:ref-count` only to choose
  between `:fresh` and `:cached-no-watcher` for stably-cached subs."
  [{:keys [ref-count rerunning? invalidated?] :as _cache-entry}
   sub-run
   error?]
  (cond
    error?                                 :error
    rerunning?                             :re-running
    (and sub-run (:recomputed? sub-run))   :fresh
    invalidated?                           :invalidated
    (or (nil? ref-count) (zero? ref-count)) :cached-no-watcher
    :else                                  :fresh))

(defn- query-v-of-cache-entry
  "Read the canonical `query-v` off a cache entry. The runtime
  carries `:query-v` on every entry per Spec 006 §Subscription
  cache. Tests + the helper fall back on the entry's map-key when
  the slot is missing (e.g. a test fixture that uses the map key
  directly)."
  [[k v]]
  (or (:query-v v) k))

(defn project-rows
  "Project the live sub-cache + the just-settled epoch's `:sub-runs`
  + the Causa error-cache into a vector of row maps the view
  consumes. Pure fn — JVM-runnable.

  Row shape:

      {:query-v      [<sub-id> & args]
       :sub-id       <sub-id>
       :status       :fresh | :re-running | :invalidated
                     | :cached-no-watcher | :error
       :layer        1 | 2 | 3 | nil      ;; nil when unknown
       :ref-count    <int>
       :input-subs   [<query-v> ...]      ;; from cache entry
       :recomputed?  <bool>               ;; this epoch
       :error        <throwable or nil>}

  Inputs:

    `sub-cache`   — `{query-v {:value :ref-count :input-subs
                                :layer :invalidated? :rerunning?}}`.
                    May be nil (JVM) or empty (no subs materialised);
                    the projection returns `[]` in either case.

    `sub-runs`    — vector of `:sub-runs` records from the just-
                    settled `:rf/epoch-record`. May be nil.

    `error-cache` — `{query-v <throwable>}`. May be nil.

  The returned vector is sorted into the spec's default order —
  errors first, then re-running, then invalidated, then fresh,
  then cached-no-watcher (per spec §Filtering and grouping). Within
  a status, rows are sorted by `query-v` for deterministic test
  output."
  [sub-cache sub-runs error-cache]
  (let [by-q-v   (sub-runs-by-query-v sub-runs)
        err      (or error-cache {})
        rows     (for [[k entry] (or sub-cache {})
                       :let [q-v       (query-v-of-cache-entry [k entry])
                             sub-run   (get by-q-v q-v)
                             error     (get err q-v)
                             status    (compute-status entry sub-run
                                                       (some? error))]]
                   {:query-v      q-v
                    :sub-id       (first q-v)
                    :status       status
                    :layer        (:layer entry)
                    :ref-count    (or (:ref-count entry) 0)
                    :input-subs   (vec (or (:input-subs entry) []))
                    :recomputed?  (boolean (some-> sub-run :recomputed?))
                    :error        error})
        sorter   (zipmap statuses (range))]
    (vec
      (sort-by (fn [{:keys [status query-v]}]
                 [(get sorter status (count statuses))
                  (pr-str query-v)])
               rows))))

(defn status-counts
  "Per spec §Filtering and grouping + §Activity badges — the
  sidebar's `●N` chip reads the `:error` + `:invalidated` counts to
  decide whether to surface. Returns `{status count}` over the
  projected rows. Pure fn."
  [rows]
  (frequencies (map :status rows)))

(defn filter-by-status
  "Return only the rows whose status is in `keep-statuses` (a set).
  Pure fn. The view's filter chips toggle membership of the set
  via `:rf.causa/toggle-sub-filter` — see registry.cljs. Passing
  `nil` or an empty set returns all rows (the empty filter shows
  all per spec §Filtering and grouping)."
  [rows keep-statuses]
  (if (or (nil? keep-statuses) (empty? keep-statuses))
    rows
    (filterv #(contains? keep-statuses (:status %)) rows)))

;; ---- invalidation-chain affordance --------------------------------------

(defn- input-row
  "Project one input sub of the focused sub into a chain-link row.
  `input-q-v` is the input's `query-v`; `sub-cache` / `by-q-v` /
  `error-cache` are the same inputs `project-rows` consumes.

  The link-reason (`:input-changed` between sub layers; `:slice-changed`
  for layer-1) is derived from whether the input itself has inputs:
  a layer-1 sub reads `app-db` directly, so its 're-ran because' is
  the slice; a layer-2+ sub re-ran because *its* input re-ran."
  [input-q-v sub-cache by-q-v error-cache]
  (let [entry  (get sub-cache input-q-v)
        layer  (:layer entry)
        run    (get by-q-v input-q-v)
        error  (get error-cache input-q-v)
        status (compute-status (or entry {}) run (some? error))]
    {:query-v     input-q-v
     :sub-id      (first input-q-v)
     :layer       layer
     :status      status
     :recomputed? (boolean (some-> run :recomputed?))
     :link-reason (if (or (nil? layer) (<= layer 1))
                    :slice-changed
                    :input-changed)}))

(defn compute-chain
  "Project the invalidation chain for a focused sub. Pure fn —
  walks **one level** of the input chain in v1. Returns:

      {:focused      <row>                   ;; the focused sub
       :inputs       [<input-row> ...]       ;; one entry per input
                                              ;; that re-ran this
                                              ;; cascade
       :app-db-paths [<path> ...]             ;; layer-1 attribution
       :missing?     <bool>}                  ;; true when the sub
                                              ;; isn't in the cache

  `app-db-paths` is the set of `app-db` paths the focused sub or
  any of its layer-1 inputs read this cascade — surfaced for the
  'Open in App-DB Diff' affordance per spec §How the chain is
  computed step 3.

  `changed-paths` is the cascade's set of `app-db` paths that
  changed this epoch (per spec/004-App-DB-Diff §Changed-paths
  derivation). The intersection of layer-1 input paths with
  changed-paths is the *originating slice*; v1 returns the
  intersection unfiltered so the view can render both 'paths read'
  + 'paths changed'."
  [focused-q-v sub-cache sub-runs error-cache changed-paths]
  (let [by-q-v   (sub-runs-by-query-v sub-runs)
        entry    (get sub-cache focused-q-v)
        run      (get by-q-v focused-q-v)
        error    (get error-cache focused-q-v)]
    (if (nil? entry)
      {:focused      nil
       :inputs       []
       :app-db-paths []
       :missing?     true}
      (let [status   (compute-status entry run (some? error))
            inputs   (->> (or (:input-subs entry) [])
                          (map #(input-row % sub-cache by-q-v error-cache))
                          ;; Drop inputs that did not re-run this
                          ;; cascade — per spec §How the chain is
                          ;; computed step 2.
                          (filter (some-fn :recomputed?
                                           #(= :error (:status %))))
                          vec)
            ;; Layer-1 attribution: the focused sub's :paths slot
            ;; (when it's a layer-1 sub) plus every layer-1 input's
            ;; :paths slot. Intersect with changed-paths to surface
            ;; the originating slice(s).
            own-paths (when (and (some? (:layer entry))
                                 (<= (:layer entry) 1))
                        (or (:paths entry) []))
            input-paths (->> (or (:input-subs entry) [])
                             (mapcat (fn [iq]
                                       (let [ie (get sub-cache iq)]
                                         (when (and (some? (:layer ie))
                                                    (<= (:layer ie) 1))
                                           (or (:paths ie) [])))))
                             vec)
            all-paths (vec (distinct (concat own-paths input-paths)))
            attributed (if (seq changed-paths)
                         (filterv (set changed-paths) all-paths)
                         all-paths)]
        {:focused      {:query-v      focused-q-v
                        :sub-id       (first focused-q-v)
                        :status       status
                        :layer        (:layer entry)
                        :ref-count    (or (:ref-count entry) 0)
                        :input-subs   (vec (or (:input-subs entry) []))
                        :recomputed?  (boolean (some-> run :recomputed?))
                        :error        error}
         :inputs       inputs
         :app-db-paths attributed
         :missing?     false}))))

;; ---- formatting helpers (consumed by the view) --------------------------

(defn format-query-v
  "Pretty-print a `query-v` for display. Falls back to `str` if
  `pr-str` throws (e.g. on a JS-native value that can't be EDN-
  printed). Mirrors the format-edn helper in event_detail.cljs but
  lives here so the test suite can assert against the formatted
  output without booting the view."
  [q-v]
  (try
    (pr-str q-v)
    (catch #?(:clj Throwable :cljs :default) _
      (str q-v))))

(defn format-sub-id
  "Format a sub-id for compact display in the row's mono column.
  Keywords keep their `:` prefix; symbols / strings render plain."
  [sub-id]
  (cond
    (keyword? sub-id) (str sub-id)
    (symbol?  sub-id) (str sub-id)
    :else             (str/trim (str sub-id))))
