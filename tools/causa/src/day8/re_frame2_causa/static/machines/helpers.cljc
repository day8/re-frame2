(ns day8.re-frame2-causa.static.machines.helpers
  "Pure-data helpers for the Static Machines sub-tab (rf2-o5f5f.2).

  ## Why a separate `.cljc` ns

  Same dual-target pattern every other panel uses. The view code in
  `panel.cljs` / `browse_list.cljs` / `definition_detail.cljs` builds
  the hiccup; the projection + sort + search + sub-mode validation is
  pure data → data and runs under the JVM unit-test target
  (`clojure -M:test`).

  ## What this projects

  Per the bead (rf2-o5f5f.2) the browse-all list enumerates
  `(rf/machines)` and renders one row per registered machine. Each row
  carries enough data for the L4-left list AND the L4-right header to
  render without recomputing: machine-id, state-count, live-instance
  count, and the lifted source-coord (when present in the registered
  spec's metadata).

  ## Sort axes

  Three sort axes per the bead's §Browse-all list:

    - `:name`   — alphabetical by `(name machine-id)`; default.
    - `:states` — by state-count DESC.
    - `:live`   — by live-instance count DESC.

  Ties break on the next axis (name) so the order is deterministic.

  ## Sub-modes (the 4-mode sub-strip)

  Four pills: `:topology` (default) · `:sim` · `:instances` · `:cascade`.
  Per bead's §4-mode sub-strip the Sim cell is a placeholder until
  sibling rf2-r4nao lands the real Sim view; the Cascade pill is
  rendered but greyed (Runtime-only surface)."
  (:require [clojure.string :as str]))

;; ---- sub-mode taxonomy --------------------------------------------------

(def sub-modes
  "Four sub-modes per the bead's §4-mode sub-strip."
  [:topology :sim :instances :cascade])

(def sub-mode-ids
  "Set of valid sub-mode ids for input validation. Per spec the strip
  ALWAYS exposes all four cells (even if Sim is a placeholder + Cascade
  is dimmed); a stored value outside this set normalises back to
  `:topology` (the default mode)."
  (set sub-modes))

(def default-sub-mode :topology)

(defn normalise-sub-mode
  "Coerce a raw sub-mode value (keyword OR string OR nil) to a valid
  sub-mode keyword. Unknown values fall back to `:topology`.

  Pure data — JVM-runnable so the JVM test corpus can cover the
  normalisation round-trip without a CLJS runtime."
  [v]
  (cond
    (keyword? v) (if (contains? sub-mode-ids v) v default-sub-mode)
    (string? v)  (let [kw (keyword v)]
                   (if (contains? sub-mode-ids kw) kw default-sub-mode))
    :else        default-sub-mode))

(def sub-mode-mnemonics
  "Per-sub-mode keyboard mnemonic letter. The keybinding wiring is
  follow-on (per the bead's §Mnemonics deferral); this map carries the
  pure-data half so the click affordance can surface the letter in its
  `title`."
  {:topology  "t"
   :sim       "s"
   :instances "i"
   :cascade   "c"})

;; ---- sort axes ----------------------------------------------------------

(def sort-keys
  "Three sort axes the browse-all list cycles through."
  [:name :states :live])

(def sort-key-ids
  (set sort-keys))

(def default-sort-key :name)

(defn normalise-sort-key
  "Coerce a raw sort-key value to a valid sort axis. Unknown values
  fall back to `:name`."
  [v]
  (cond
    (keyword? v) (if (contains? sort-key-ids v) v default-sort-key)
    (string? v)  (let [kw (keyword v)]
                   (if (contains? sort-key-ids kw) kw default-sort-key))
    :else        default-sort-key))

(def sort-key-labels
  "Per-axis display label for the sort-cycle button."
  {:name   "Name"
   :states "States"
   :live   "Live"})

;; ---- source-coord lifting -----------------------------------------------

(defn lift-source-coord
  "Pull the source-coord map off a registered machine's spec (the
  return value of `(rf/machine-meta machine-id)`). Per Spec 005 +
  Spec 009 the registrar lifts the `defmacro`/`reg-machine` call site
  into the spec's metadata; the canonical slot is `:source-coord` (the
  same shape `editor-uri/editor-uri` consumes).

  Returns nil when the definition is missing OR carries no source-coord
  — the chip degrades gracefully (no chip rather than a broken link).
  Pure data — JVM-runnable.

  Tolerates the alternate `:source` slot some legacy registrars used —
  same lenient policy `open_in_editor.cljs/coerce-coord` applies on
  the dispatch surface."
  [definition]
  (when (map? definition)
    (or (get definition :source-coord)
        (get definition :source))))

(defn format-source-coord
  "Render a source-coord as a compact `file:line` display string. nil
  when the coord lacks a usable `:file` slot. Mirrors the projection
  trace events use so the chip text reads identically across surfaces."
  [coord]
  (when (and (map? coord) (some? (:file coord)))
    (let [file (str (:file coord))
          line (:line coord)]
      (if line (str file ":" line) file))))

;; ---- machine-row projection ---------------------------------------------

(defn- state-count
  "Count of states in the machine's definition. Reads the canonical
  `:states` slot (Spec 005 §Definition shape). Returns 0 when the
  definition is missing or has no states map."
  [definition]
  (count (get definition :states {})))

(defn- live-instance-count
  "Number of times the machine-id appears in the snapshots map. v1
  reads ONE frame's snapshots (the panel's target-frame); per the
  bead's §Browse-all list the cross-frame sum is the eventual surface
  but the reactive cross-frame walker is a follow-on bead. With one
  frame, the count is either 0 (uninitialised) or 1 (initialised) —
  the pip cluster degrades cleanly to a single dot in the common case.

  Pure-data here so the v1 single-frame OR a future multi-frame
  caller can feed the same projection without changing the row
  shape."
  [snapshots machine-id]
  (let [snap (get snapshots machine-id)]
    (if (some? snap) 1 0)))

(defn project-row
  "Build one row for the browse-all list. Pure data.

  Inputs:
    `machine-id`  — the registered machine keyword.
    `definition`  — `(rf/machine-meta machine-id)` map (nil if missing).
    `snapshots`   — `{machine-id snapshot}` for the target-frame.

  Output:
    {:machine-id    <kw>
     :state-count   <int>
     :live-count    <int>
     :source-coord  <map-or-nil>
     :source-label  <\"file:line\"-or-nil>}"
  [machine-id definition snapshots]
  (let [coord (lift-source-coord definition)]
    {:machine-id   machine-id
     :state-count  (state-count definition)
     :live-count   (live-instance-count snapshots machine-id)
     :source-coord coord
     :source-label (format-source-coord coord)}))

(defn project-rows
  "Project every registered machine into a vector of rows. Sorted
  alphabetically by default; the view applies the user's sort axis
  via `apply-sort`. Pure data — JVM-runnable."
  [machines definitions snapshots]
  (->> (or machines [])
       (map (fn [id]
              (project-row id (get definitions id) (or snapshots {}))))
       vec))

;; ---- search + sort ------------------------------------------------------

(defn search-text
  "Concatenate the searchable surface for a row into one lowercase
  string. Covers the machine-id's name AND namespace AND the source
  coord's file path — per the bead's §Browse-all list. Pure data."
  [{:keys [machine-id source-coord]}]
  (let [name-s (when (keyword? machine-id) (name machine-id))
        ns-s   (when (keyword? machine-id) (namespace machine-id))
        file-s (when (map? source-coord) (str (:file source-coord)))
        parts  [name-s ns-s file-s]]
    (str/lower-case (str/join "|" (remove nil? parts)))))

(defn apply-search
  "Filter rows to those whose `search-text` contains `query` (case-
  insensitive substring). Empty / nil query returns rows unchanged.
  Pure data."
  [rows query]
  (if (or (nil? query) (str/blank? query))
    (vec rows)
    (let [needle (str/lower-case (str/trim query))]
      (vec (filter (fn [row] (str/includes? (search-text row) needle))
                   rows)))))

(defn- name-key
  "Stable sort key for the machine-id — string form of the keyword. Pure."
  [{:keys [machine-id]}]
  (str machine-id))

(defn apply-sort
  "Sort rows by `sort-key` per the bead's §Browse-all list. Ties break
  on name for deterministic output. Pure data."
  [rows sort-key]
  (let [k (normalise-sort-key sort-key)]
    (case k
      :name
      (vec (sort-by name-key rows))

      :states
      (vec (sort-by (juxt (comp - :state-count) name-key) rows))

      :live
      (vec (sort-by (juxt (comp - :live-count) name-key) rows)))))

(defn project-browse-list
  "Full browse-all projection — rows + search + sort + selection
  resolution. Returns the data the view consumes:

    {:rows         <filtered-and-sorted-rows>
     :total        <int — pre-filter count>
     :visible      <int — post-filter count>
     :selected-id  <kw-or-nil — effective selection, defaults to first
                   row when the user's selection is missing OR filtered
                   out>}

  Pure data — JVM-runnable."
  [machines definitions snapshots search-query sort-key selected-id]
  (let [rows     (project-rows machines definitions snapshots)
        filtered (apply-search rows search-query)
        sorted   (apply-sort filtered sort-key)
        ;; Default to first row when user's pick is absent OR filtered out.
        effective (or (some (fn [{:keys [machine-id]}]
                              (when (= machine-id selected-id) machine-id))
                            sorted)
                      (some-> sorted first :machine-id))]
    {:rows        sorted
     :total       (count rows)
     :visible     (count sorted)
     :selected-id effective}))

;; ---- live-instance pip cluster ------------------------------------------

(def pip-cap
  "Maximum number of pips rendered in a row's pip cluster per the
  bead's §Browse-all list. Anything beyond renders as a `>{cap} N live`
  textual count."
  12)

(defn pip-render-plan
  "Decide how the per-row live-instance cluster should render.

  Returns one of:
    {:kind :pips :count <int>}    — render N filled dots (N ≤ pip-cap)
    {:kind :count :count <int>}   — render textual `>{cap} N live`
    {:kind :none}                 — no live instances (silent — rf2-g3ghh)

  Pure data."
  [live-count]
  (cond
    (nil? live-count)      {:kind :none}
    (zero? live-count)     {:kind :none}
    (<= live-count pip-cap) {:kind :pips  :count live-count}
    :else                  {:kind :count :count live-count}))
