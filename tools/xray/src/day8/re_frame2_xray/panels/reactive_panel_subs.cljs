(ns day8.re-frame2-xray.panels.reactive-panel-subs
  "Subscriptions for the Views panel (rf2-wyvf2 / rf2-8ve8z · spec/021 §3).

  The panel reads the focused epoch record's normalized structured
  projections — `:sub-runs` and `:renders` — assembled by the
  substrate on each `:rf/epoch-record` (per spec/018), plus the
  view-side capture ops (`:rf.view/rendered` / `:rf.view/unmounted`)
  off the raw `:trace-events`. It does NOT re-derive sub rows from raw
  `:trace-events` by op-keyword: the canonical ops are `:rf.sub/run` /
  `:rf.sub/skip` / `:rf.view/rendered` (Spec 009 §:op-type vocabulary),
  and the earlier op-keyword path grepped for names the substrate never
  emits (`:rf.sub/computed` / `:rf.sub/skipped`), pinning subs-ran /
  subs-skipped to zero regardless of how many subs actually ran.

  - `:sub-runs` → subs-ran (`:recomputed?` true) + subs-skipped
    (memo-hit, `:recomputed?` false); each carries `:value-changed?`.
  - `:renders`  → views-rendered (`:render-key` → top-level `:view-id`)
  - flow counts → tallied from `:trace-events` (`:rf.flow/computed` /
    `:rf.flow/skip`), the one slice with no structured projection.

  ## phase-B Views redesign (rf2-8ve8z)

  The Views panel is THREE STACKED TABLES mirroring the reactive
  event-bundle flowing toward the UI:

    1. Level 1 subs (observe app-db)  — `:inputs []` per `sub-topology`
    2. Level 2+ subs                  — non-empty `:inputs`
    3. Views                          — one row per render/unmount, with
                                        an `action` (mount/rerender/
                                        unmount) and a `reason`

  The sub-level partition uses `re-frame.subs.tooling/sub-topology` —
  the static `:<-` dependency graph (`{sub-id {:inputs [...] :ns :line
  :file}}`). `:inputs []` is Level 1 (reads app-db directly); non-empty
  is Level 2+. Topology also supplies the `:ns/:line/:file` source coord
  for the `code` column's jump-to-source chip and the input-sub names
  for the Level 2+ `inputs` column.

  The view ACTION + REASON ride phase-A's (rf2-9hoos) additions to the
  view-render trace ops, read off the epoch record's `:trace-events`:

    - `:rf.view/rendered` carries `:rf.view/mount?` (true → mount,
      false → rerender) and `:rf.view/deref-subs` (the `[query-id args]`
      query-vectors THIS view derefs — its per-view read-set; absent for
      a structural render that derefs no subs).
    - `:rf.view/unmounted` is a teardown op → action unmount.

  The REASON is computed by intersecting a render's `:deref-subs`
  query-ids with the set of subs that `:value-changed?` this event-bundle:
  non-empty → reactive (list those changed sub names); empty → the
  view rendered with no own sub change → structural (`← parent
  re-render`, deliberately UNNAMED). Unmount rows have no reason.

  No new instrumentation — pure consumer over the epoch record + the
  static topology snapshot.

  ## Public surface

  - `:rf.xray/reactive-data` — composite sub the panel view reads.
    Shape:

        {:focus           {<focus map>}
         :frame           <frame-kw>
         :dispatch-id     <id-of-focused-event-bundle>
         :has-event-bundle?    <bool>
         :triggered-by    <event-vec>
         :seed-paths      [<path> ...]
         :subs-ran        [{:sub-id _ :value-changed? _ ...} ...]
         :subs-skipped    [{:sub-id _ ...} ...]
         :views-rendered  [{:view-id _ ...} ...]      ; legacy count slot
         :level-1-subs    [{:sub-id _ :changed? _ :coord _ :readers [...]} ...]
         :level-2-subs    [{:sub-id _ :changed? _ :inputs [...] :coord _
                            :readers [...]} ...]
         :sub-readers     {<sub-id> [<view-id> ...] ...}
         :view-rows       [{:view-id _ :action _ :reason {...} :coord _} ...]
         :counts          {:subs-ran N :subs-skipped N
                           :views-rendered N
                           :flows-recomputed N}}

  EP-0025: the standing derived-output declassification audit
  (`:public-declassifications`) is REMOVED — classification no longer
  propagates input → output, so there is no `:rf.egress/output-sensitivity
  :rf.egress/public` declassify claim to enumerate. (A sensitive derived
  value is now just a classified output PATH.)

  `:sub-readers` (rf2-y23uw) is the shared-subscription edge map — for
  each sub-id, the views that deref'd it this event-bundle ('which views read
  sub X'), derived from the per-view `:rf.view/deref-subs` read-sets. The
  same list rides each sub row's `:readers` slot so the Views panel can
  show, per sub, the views that share it.

  The `:reason` slot on a `:view-row` is `{:kind :reactive :subs [...]}`
  | `{:kind :structural}` | `{:kind :none}` (unmount). The view layer
  truncates the `:subs` list honestly with a `+N more` overflow per the
  Spec 009 event-bundle-cap posture.

  Per spec/021 §3.4 the panel's 'Show unchanged subs' disclosure is
  view-local (panel UI state); the always-expand override lives in
  Settings (`:rf.xray/show-unchanged-subs?` · `:general` section).

  ## Install

  `install!` registers `:rf.xray/reactive-data` + the panel-local
  disclosure-toggle state slot. Idempotent."
  (:require [re-frame.core :as rf]
            [re-frame.subs.tooling :as subs-tooling]
            [day8.re-frame2-xray.panels.shared.focus-resolver :as focus]))

;; ---- pure helpers (exposed for test) ------------------------------------

(defn focused-epoch-record
  "Locate the focused epoch record in `epoch-history`.

  Routes through the shared `panels.shared.focus-resolver/find-epoch-record`
  (rf2-uo0rc.1) so the Views panel resolves focus identically to every
  other L4 panel:

    - NIL `epoch-id` + non-empty history → HEAD record (rf2-h0120
      head-fallback — the natural LIVE / cold-start debugging UX).
    - `epoch-id` MATCHES a record → that record.
    - `epoch-id` pinned but EVICTED from the ring → nil. The composite
      then reports `:has-event-bundle? false` and the panel renders its
      empty/§10.7-evicted placeholder rather than silently falling back
      to HEAD and showing the LATEST event-bundle, which lied about which
      epoch the operator was inspecting.
    - empty history → nil."
  [epoch-history epoch-id]
  (focus/find-epoch-record epoch-id epoch-history))

(defn- op-kw
  "Trace-event op keyword. The substrate's `:rf/*` ops carry the kw on
  `:operation`; tests sometimes shape it as `:op` — accept both."
  [event]
  (or (:operation event) (:op event)))

;; ---- view action + reason (rf2-8ve8z, phase-A rf2-9hoos contract) ---------

(defn- changed-sub-id-set
  "Set of sub-ids whose value CHANGED this event-bundle — `:value-changed?`
  true on the `:sub-runs` projection. The basis for the per-view
  reactive-vs-structural reason classification.

  nil-safe: a `:sub-runs` entry without `:value-changed?` (the base-
  shape `compute-sub` emit omits it) is simply not counted as changed."
  [sub-runs]
  (into #{}
        (comp (filter :value-changed?)
              (map :sub-id))
        (or sub-runs [])))

(defn- query-id
  "Extract the sub-id (query-id) from a `:deref-subs` entry. Entries are
  `[query-id args]` query-vectors; a bare keyword query-id (no args) is
  tolerated. Returns nil for any other shape."
  [qv]
  (cond
    (vector? qv)  (first qv)
    (keyword? qv) qv
    :else         nil))

(defn compute-view-reason
  "Classify WHY a view rendered, given its `:deref-subs` query-vectors
  and the set of subs that `:value-changed?` this event-bundle.

  Returns a tagged map:

    {:kind :reactive  :subs [<changed-sub-id> ...]}  — the view derefs
        at least one sub that changed this event-bundle. `:subs` is the
        INTERSECTION (the view's own changed reasons), order preserved
        from the view's deref order so the most-relevant reads lead.
    {:kind :structural}  — the view rendered but none of the subs it
        derefs changed (or it derefs no subs at all). The unnamed
        `← parent re-render` case — we deliberately never name the
        parent (permanent, per rf2-8ve8z).

  Pure. nil-safe on both args."
  [deref-subs changed-set]
  (let [changed   (or changed-set #{})
        ;; preserve the view's deref order; keep only the changed ones
        own-changed (into []
                          (comp (keep query-id)
                                (filter changed)
                                (distinct))
                          (or deref-subs []))]
    (if (seq own-changed)
      {:kind :reactive :subs own-changed}
      {:kind :structural})))

(defn view-rows
  "Project the focused event-bundle's view-render + view-unmount trace events
  into ordered `:view-row` maps for the Views table (rf2-8ve8z).

  Reads `:rf.view/rendered` and `:rf.view/unmounted` ops off the raw
  `:trace-events` (the phase-A rf2-9hoos additions). The structured
  `:renders` projection is intentionally NOT used here — it projects
  from `:rf.view/render` (the render-START marker) and carries neither
  `:rf.view/mount?` nor `:rf.view/deref-subs`. The action/reason data
  rides the post-render `:rf.view/rendered` op + the `:rf.view/unmounted` op.

  Each row:

    {:view-id      <kw/id>
     :render-key   <[view-id token]>
     :action       :mount | :rerender | :unmount
     :reason       {:kind :reactive :subs [...]} | {:kind :structural}
                   | {:kind :none}                 ; unmount
     :triggered-by <sub-id>?    ; rf2-8wrzz.1 — the SINGLE cause sub
     :elapsed-ms   <number>?}   ; rf2-8wrzz.1 — render wall-clock

  `:triggered-by` (the per-view re-render cause) + `:elapsed-ms` (render
  timing) ride the `:rf.view/rendered` op from rf2-8wrzz.1; the flow
  graph (spec/021 §3.2) uses them to label each sub→view edge's cause +
  the view node's timing. Both are absent on a structural re-render /
  outside an event-bundle — the slot is simply omitted.

  `changed-set` is the set of changed sub-ids this event-bundle (from
  `changed-sub-id-set`). nil-safe: missing tags / absent fields degrade
  to a structural reason rather than crashing. Events without a
  `:view-id` are skipped (they can't anchor a row)."
  [trace-events changed-set]
  (into []
        (keep (fn [ev]
                (let [op   (op-kw ev)
                      tags (:tags ev)
                      view-id (or (:rf.view/id tags) (:rf.view/id ev))]
                  (cond
                    (nil? view-id) nil

                    (= :rf.view/rendered op)
                    (let [mount?       (true? (:rf.view/mount? tags))
                          deref-subs   (:rf.view/deref-subs tags)
                          triggered-by (:rf.view/triggered-by tags)
                          elapsed-ms   (:rf.view/elapsed-ms tags)]
                      (cond-> {:view-id    view-id
                               :render-key (:rf.view/render-key tags)
                               :action     (if mount? :mount :rerender)
                               :reason     (compute-view-reason deref-subs changed-set)}
                        (some? triggered-by) (assoc :triggered-by triggered-by)
                        (some? elapsed-ms)   (assoc :elapsed-ms elapsed-ms)))

                    (= :rf.view/unmounted op)
                    {:view-id    view-id
                     :render-key (:rf.view/render-key tags)
                     :action     :unmount
                     :reason     {:kind :none}}

                    :else nil))))
        (or trace-events [])))

;; ---- reactive teardown sections (spec/021 §3.2 · Figma design) ------------

(defn unmounted-views
  "Project the epoch's UNMOUNTED VIEWS section rows (spec/021 §3.2 ·
  Figma `ViewsPanel`). One row per `:rf.view/unmounted` op this
  event-bundle — views whose component instance tore down this epoch.

  Reads the same `:rf.view/unmounted` teardown op `view-rows` reads for
  its `:unmount` action; surfaced here as a dedicated section per the
  Figma design (the graph shows the live event-bundle; teardown lists below
  it). Each row `{:view-id <kw/id>}`. First-seen order, de-duplicated by
  view-id so two instances of one view tearing down list once. nil-safe;
  ops without a `:view-id` are skipped."
  [trace-events]
  (->> (or trace-events [])
       (keep (fn [ev]
               (when (= :rf.view/unmounted (op-kw ev))
                 (let [tags (:tags ev)]
                   (or (:rf.view/id tags) (:rf.view/id ev))))))
       (distinct)
       (mapv (fn [view-id] {:view-id view-id}))))

(defn destroyed-subscriptions
  "Project the epoch's DESTROYED SUBSCRIPTIONS section rows (spec/021
  §3.2) — subs cleaned up when their last reader unmounted.

  Reads the `:rf.sub/dispose` teardown op (the sub-dispose op spec/021
  §3.5 pairs with view-unmount). Per rf2-uo4e2 the framework-emitted
  op-name is `:rf.sub/dispose` (singular form per spec/023's rf2-2v3p7
  typo fix); pre-fix this panel read the past-tense form which never
  matched any framework-emitted trace and rendered as silent dead code.
  Each `:rf.sub/dispose` op anchors a row `{:sub-id <kw/id>}`. First-
  seen order, de-duplicated. nil-safe; ops without a `:sub-id` are
  skipped."
  [trace-events]
  (->> (or trace-events [])
       (keep (fn [ev]
               (when (= :rf.sub/dispose (op-kw ev))
                 (let [tags (:tags ev)]
                   (or (:rf.sub/id tags) (:sub-id tags)
                       (:rf.sub/id ev) (:sub-id ev))))))
       (distinct)
       (mapv (fn [sub-id] {:sub-id sub-id}))))

;; ---- shared-subscription edges (rf2-y23uw) --------------------------------

(defn sub-readers
  "Build the sub→readers map for this event-bundle — `{sub-id [view-id ...]}` —
  the 'which views read sub X' shared-subscription edge set.

  Walks the event-bundle's `:rf.view/rendered` ops and, for each, unions its
  view-id into the reader list of every sub-id it derefs (its
  `:rf.view/deref-subs` read-set). The result lets the Views panel show,
  per sub row, the set of views that read it — the shared-sub detection
  the per-render `:rf.view/cause-subs`/`:rf.sub/reader-render-key` pair
  can't supply (cause-subs is event-bundle-wide and over-reports; the
  reader-render-key names only the TRIGGERING reader of a recompute, not
  every reader, and not unchanged subs a view also reads).

  Reader lists preserve first-seen view order across the trace and
  de-duplicate (a view that re-derefs the same sub, or two instances of
  the same view-id, contribute the view-id once). nil-safe; non-view ops
  and ops without a `:view-id` / `:deref-subs` are skipped."
  [trace-events]
  (-> (reduce
        (fn [acc ev]
          (if (= :rf.view/rendered (op-kw ev))
            (let [tags    (:tags ev)
                  view-id (or (:rf.view/id tags) (:rf.view/id ev))]
              (if (nil? view-id)
                acc
                (reduce
                  (fn [m qv]
                    (if-let [sid (query-id qv)]
                      ;; Ordered-distinct union: a sorted set would lose
                      ;; first-seen order, so track an explicit vector +
                      ;; membership in one entry, flattened below.
                      (update m sid
                              (fn [{:keys [seen order] :or {seen #{} order []}}]
                                (if (contains? seen view-id)
                                  {:seen seen :order order}
                                  {:seen (conj seen view-id)
                                   :order (conj order view-id)})))
                      m))
                  acc
                  (or (:rf.view/deref-subs tags) []))))
            acc))
        {}
        (or trace-events []))
      (->> (reduce-kv (fn [m sid entry] (assoc m sid (:order entry))) {}))))

;; ---- sub-level partition (rf2-8ve8z, sub-topology contract) ---------------

(defn topology-coord
  "Extract the jump-to-source coord (`{:file :line :ns}`) for a sub from
  a `sub-topology` entry. Returns nil when the entry carries no `:file`
  (degrade gracefully — the `code` column simply omits the chip)."
  [topo-entry]
  (when-let [file (:file topo-entry)]
    (when (string? file)
      {:file file :line (:line topo-entry) :ns (:ns topo-entry)})))

(defn level-1?
  "True when a sub is a Level 1 sub — it observes app-db DIRECTLY,
  reported by `sub-topology` as `:input-kind :db` (`:inputs []`).
  `:static` and `:parametric` subs compose upstream subs and are
  Level 2+. A sub MISSING from the topology (not statically
  registered, e.g. a test-injected literal) is treated as Level 1 by
  default — the conservative bucket, since a sub with no declared
  topology entry has no upstream sub edges.

  Keys off `:input-kind` (rf2-e3acps): a `:parametric` sub reports
  `:inputs :parametric` (a keyword, not a vector), so the old
  `(empty? (:inputs topo-entry))` test would throw on it. `:input-kind`
  is the precise discriminator — `:db` is Level 1, everything else
  composes upstream subs."
  [topo-entry]
  (case (:input-kind topo-entry)
    :db    true
    :static false
    :parametric false
    ;; Missing entry / unrecognised kind → conservative Level 1.
    (empty? (:inputs topo-entry))))

(defn topology-input-sub-ids
  "Project a `sub-topology` entry's `:inputs` to the vector of upstream
  SUB-IDs the Level 2+ flow-graph resolves edges against (rf2-e3acps).

  The static `sub-topology` reports `:static` inputs as full query-
  vectors (`[[:items] [:filter]]`, args preserved — per Spec 002 §The
  public registrar query API). The reactive flow-graph keys edge
  endpoints by sub-id, so this strips each query-vector to its head:
  `[[:items] [:filter]]` → `[:items :filter]`.

  `:parametric` subs report `:inputs :parametric` (the realized edge set
  is per-concrete-query-v runtime cache state, NOT statically
  enumerable — the EP §Tooling two-level contract). The static surface
  has NO edges to draw for them, so this returns `[]` rather than
  fabricating un-materialized edges. The REALIZED parametric edges live
  in the live/cache view (`sub-cache` / the `:rf.sub/inputs` event-bundle
  tag), not the static topology partition.

  `:db` / missing entry → `[]`."
  [topo-entry]
  (let [inputs (:inputs topo-entry)]
    (if (vector? inputs)
      (mapv (fn [q] (if (vector? q) (first q) q)) inputs)
      ;; `:parametric` sentinel (or any non-vector) → no static edges.
      [])))

(defn partition-subs-by-level
  "Partition the event-bundle's subs into the Level 1 / Level 2+ table rows
  using the static `sub-topology` snapshot (rf2-8ve8z).

  `subs-ran` is the `:sub-runs` projection slice (each entry carries
  `:sub-id`, `:value-changed?`, `:value`, `:prev-value`). `topology` is
  the `re-frame.subs.tooling/sub-topology` map (`{sub-id {:input-kind _
  :inputs [...] :ns :line :file}}`). `readers` (optional, rf2-y23uw) is
  the sub→readers map from `sub-readers` (`{sub-id [view-id ...]}`).

  Returns `{:level-1 [row ...] :level-2 [row ...]}` where each row:

    Level 1: {:sub-id _ :changed? bool :coord {...}? :readers [view-id ...]?}
    Level 2: {:sub-id _ :changed? bool :input-kind _
              :inputs [<input-sub-id> ...] :coord {...}? :readers [...]?}

  Level partitioning keys off the topology entry's `:input-kind`
  (rf2-e3acps): `:db` is Level 1 (reads app-db directly), `:static` /
  `:parametric` are Level 2+. The Level 2 `:inputs` slot carries the
  upstream SUB-IDs (the flow-graph's edge-endpoint key space): the
  static `:<-` heads for a `:static` sub, and `[]` for a `:parametric`
  sub — whose realized edges are per-concrete-query-v runtime state, NOT
  statically enumerable, so the STATIC partition draws no edges for them
  (the EP §Tooling: don't fabricate un-materialized parametric edges).
  The row also carries `:input-kind` so the panel can badge a parametric
  sub. Realized parametric edges surface in the live/cache view.

  `:readers` is the views that deref this sub THIS event-bundle — the
  shared-subscription edge (rf2-y23uw); absent when no rendered view read
  it (e.g. a handler-side or upstream-input sub no view directly derefs).

  Order preserved from `subs-ran` within each level. nil-safe: a sub
  absent from the topology degrades to Level 1 with no inputs / no
  coord; absent / nil `readers` simply omits the slot. All args may be
  nil."
  ([subs-ran topology] (partition-subs-by-level subs-ran topology nil))
  ([subs-ran topology readers]
   (let [topo (or topology {})
         rdrs (or readers {})]
     (reduce
       (fn [acc sub-run]
         (let [sub-id     (:sub-id sub-run)
               topo-entry (get topo sub-id)
               changed?   (boolean (:value-changed? sub-run))
               coord      (topology-coord topo-entry)
               sub-rdrs   (get rdrs sub-id)]
           (if (level-1? topo-entry)
             (update acc :level-1 conj
                     (cond-> {:sub-id sub-id :changed? changed?}
                       coord          (assoc :coord coord)
                       (seq sub-rdrs)  (assoc :readers (vec sub-rdrs))))
             (update acc :level-2 conj
                     (cond-> {:sub-id     sub-id :changed? changed?
                              :input-kind (:input-kind topo-entry)
                              :inputs     (topology-input-sub-ids topo-entry)}
                       coord          (assoc :coord coord)
                       (seq sub-rdrs)  (assoc :readers (vec sub-rdrs)))))))
       {:level-1 [] :level-2 []}
       (or subs-ran [])))))

;; ---- record projection ----------------------------------------------------

(defn project-record
  "Project an epoch record into the Views-panel shape. Pure data.

  Reads the substrate's normalized structured projections — `:sub-runs`
  and `:renders` — directly off the `:rf/epoch-record` (per spec/018),
  the view-side capture ops (`:rf.view/rendered` / `:rf.view/unmounted`)
  off the raw `:trace-events`, and flow counts off `:trace-events`'
  canonical `:rf.flow/computed` / `:rf.flow/skip` ops.

  `topology` (optional) is the `re-frame.subs.tooling/sub-topology`
  snapshot used to partition subs into Level 1 / Level 2+ and supply
  the `inputs` + `code` columns. Absent / nil topology degrades every
  sub to Level 1 with no inputs / no coord — the panel still renders.

  `:sub-runs` entries carry `:sub-id` / `:query-v` / `:recomputed?` /
  `:value-changed?`; `:recomputed?` splits genuine recomputes (subs-ran)
  from memo-hit skips (subs-skipped). The view rows ride the phase-A
  rf2-9hoos fields on the view-render ops.

  Returns the map shape documented in the ns docstring (sans the
  focus / frame / dispatch-id keys; those come from the spine sub)."
  ([record] (project-record record nil))
  ([record topology]
   (let [sub-runs (vec (:sub-runs record))
         ran      (filterv :recomputed? sub-runs)
         skipped  (filterv (complement :recomputed?) sub-runs)
         renders  (mapv (fn [r] (assoc r :view-id (first (:render-key r))))
                        (:renders record))
         trace-events (or (:trace-events record) [])
         grouped  (group-by op-kw trace-events)
         flows-comp    (count (get grouped :rf.flow/computed []))
         flows-skipped (count (get grouped :rf.flow/skip []))
         changed-set   (changed-sub-id-set ran)
         readers       (sub-readers trace-events)
         {:keys [level-1 level-2]} (partition-subs-by-level ran topology readers)
         v-rows        (view-rows trace-events changed-set)
         unmounted     (unmounted-views trace-events)
         destroyed     (destroyed-subscriptions trace-events)]
     {:subs-ran        ran
      :subs-skipped    skipped
      :views-rendered  renders
      :level-1-subs    level-1
      :level-2-subs    level-2
      :sub-readers     readers
      :view-rows       v-rows
      :unmounted-views unmounted
      :destroyed-subs  destroyed
      :counts          {:subs-ran         (count ran)
                        :subs-skipped     (count skipped)
                        :views-rendered   (count renders)
                        :view-rows        (count v-rows)
                        :unmounted-views  (count unmounted)
                        :destroyed-subs   (count destroyed)
                        :flows-recomputed flows-comp
                        :flows-skipped    flows-skipped}})))

;; EP-0025: the standing derived-output declassification audit
;; (`public-declassification-rows`) is REMOVED — classification no longer
;; propagates input → output, so there is no `:rf.egress/output-sensitivity
;; :rf.egress/public` declassify claim to enumerate.

(defn- triggered-by
  "The triggering event vector for the epoch. Reads the `:event` slot
  the spine seeds on every epoch record (per spec/018) — the single
  reliable source. Returns nil for records without a seeded event."
  [record]
  (:event record))

(defn- seed-paths
  "Derive seed paths from the event-bundle `:db-before` → `:db-after` diff.
  The handler set state and that mutation kicks the subs event-bundle. v1
  surfaces the changed top-level paths the diff provides; deeper-path
  resolution can ride a follow-on."
  [record]
  (let [diff (:rf/changed-paths record)]
    (cond
      (vector? diff) diff
      (sequential? diff) (vec diff)
      :else [])))

(defn install!
  []
  ;; -- panel-local UI state slot --------------------------------------
  (rf/reg-sub :rf.xray/reactive-show-unchanged?
    (fn [db _query]
      (boolean (get db :reactive/show-unchanged?))))

  ;; -- composite the view reads --------------------------------------
  (rf/reg-sub :rf.xray/reactive-data
    :<- [:rf.xray/focus]
    :<- [:rf.xray/epoch-history]
    (fn [[focus history] _query]
      (let [record   (focused-epoch-record history (:epoch-id focus))
            ;; Static topology snapshot — read once per event-bundle. Free
            ;; (registry-only); used to partition L1 / L2+ subs and
            ;; supply the inputs + code columns. Defensive try so a
            ;; topology read never crashes the panel.
            topology (try (subs-tooling/sub-topology) (catch :default _ nil))
            proj     (project-record record topology)]
        (merge proj
               {:focus        focus
                :frame        (:frame focus)
                :dispatch-id  (:dispatch-id focus)
                :triggered-by (when record (triggered-by record))
                :seed-paths   (when record (seed-paths record))
                :has-event-bundle? (some? record)}))))

  nil)
