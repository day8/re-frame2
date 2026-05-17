(ns day8.re-frame2-causa.panels.views-sub-diff-subs
  "Composite sub for the sub-output structural diff in the Views panel
  (rf2-xjhhp — Phase 2 of rf2-abts7).

  ## What this ships

  One composite sub:

      :rf.causa/views-sub-diff-for-focused-event
        → {:dispatch-id <int>
           :records [{:sub-id        <kw>
                      :query-v       <vec>
                      :diff-sections [<section> ...]
                      :before-value  <v>
                      :after-value   <v>
                      :unchanged?    <bool>}
                     ...]}

  One record per `:sub-runs` entry in the focused cascade's
  `:rf/epoch-record`. The records vector preserves the sub-runs order
  the runtime captured (deepest dependency leaves recompute first; the
  view consumes the order verbatim so the rendered drilldown matches
  the cascade timeline).

  ## How the diff is computed

  Per the bead's Phase-2 scope: render the structural diff between
  each sub's PRIOR cached value and its NEW value as sections-per-
  cluster, reusing the Phase 1 engine
  (`day8.re-frame2-causa.diff.annotated-tree` +
  `.diff.section-grouping`).

  The framework's `:sub-runs` projection carries `{:sub-id :query-v
  :recomputed?}` — no value-before / value-after slot today
  (`implementation/epoch/src/re_frame/epoch/capture.cljc` §`:sub-runs`).
  To reconstruct the values without an instrumentation extension we
  use `re-frame.core/compute-sub`:

    - PRIOR value = `(compute-sub query-v (:db-before record))`
    - NEW value   = `(compute-sub query-v (:db-after  record))`

  `compute-sub` is the documented pure-compute form per
  `spec/API.md` §Dispatch and subscribe — it bypasses the live
  per-frame sub cache and runs the sub's handler-fn against the
  supplied db snapshot. The result is stable per `(query-v, db)`
  pair so the per-`:epoch-id` records cache is sound.

  ### Why this beats reading the live cache

  The live `rf/sub-cache` only holds the CURRENT cascade's value; the
  PRIOR value is already evicted by the time the user clicks the
  drilldown. The cascade pair sub already carries the prior cascade's
  record (which carries its `:db-after`, which IS the current
  cascade's `:db-before`), so the snapshots needed for the diff are
  always reachable through the epoch ring buffer.

  ### Divergence — `compute-sub` emits a `:sub/run` trace

  Per `subs.cljc` §`compute-sub`, the pure compute form fires the
  same `:sub/run` op-type the reactive recompute path emits — Causa
  may observe its own diff-driven recomputes as extra `:sub/run`
  entries in subsequent cascades. The trace pollution is bounded
  (one emit per recomputed sub per drilldown render) and gated by
  the user opening the drilldown; we document the cost here and
  ship. A future bead can route the diff path through a
  `:rf.trace/no-emit? true` flag if the noise becomes a problem.

  ## Sentinel handling

  Sub outputs may contain Spec 015 redacted / large sentinels — the
  Phase 1 engine handles them uniformly (per
  `annotated_tree.cljc` §Sentinel handling). When both sides are the
  same redacted sentinel the structural `=` check returns true →
  `:same`; when one side is a sentinel and the other is a real value
  the walker emits a `:modified` leaf carrying both sides preserved.

  ## N+1 sub recomputation cost

  Worst case: a focused cascade with N recomputed subs that each
  carry M-input `:<-` chains. `compute-sub` is N^2 on diamond
  chains (the same input subs resolve once per consumer; no shared
  cache across the recurse) — see `subs.cljc` §`compute-sub` §Cost.
  The diff is computed twice per sub (once for before, once for
  after), so the worst-case cost is `2 · N · M`. Bounded:

    - N is typically < 10 per cascade (cascades touch a small
      number of subs; the Views panel renders one drilldown per row
      so the per-tick budget is one cascade × one row).
    - Subs cache per `:epoch-id` so re-renders of the same drilldown
      pay zero recompute after the first.

  A follow-on bead can add a per-input memo across the recurse if
  real-corpus profiling shows this matters."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.diff.annotated-tree :as at]
            [day8.re-frame2-causa.diff.section-grouping :as sg]))

(defonce sub-diff-cache
  ;; Per-`:epoch-id` cache for the composite sub's `:records` vector.
  ;; The cascade pair already pins the focused cascade's :epoch-id; we
  ;; reuse that key so an inspector navigation away + back replays the
  ;; same record vector without re-running compute-sub. Tests reset
  ;; this atom between cases.
  (atom {}))

(defn- safe-compute-sub
  "Wrap `rf/compute-sub` so a sub registered AFTER the cascade was
  captured (or a sub that has been re-registered with a different
  shape) doesn't blow up the diff composite. Returns the computed
  value or nil on any exception. Per the framework contract
  (`subs.cljc` §`compute-sub`) the inner handler invocation is
  already wrapped in `try/catch`; this outer guard catches
  registrar lookup failures (`(registrar/lookup :sub query-id)`
  returning nil → the body still runs the trace/emit + body-fn
  guarded path)."
  [query-v db]
  (try
    (rf/compute-sub query-v db)
    (catch :default _ nil)))

(defn- sub-record
  "Build one diff record for a `:sub-runs` entry. Returns
    `{:sub-id <kw>
      :query-v <vec>
      :before-value <v>
      :after-value  <v>
      :diff-sections [<section> ...]
      :unchanged? <bool>}`."
  [sub-run db-before db-after]
  (let [{:keys [sub-id query-v]} sub-run
        before     (safe-compute-sub query-v db-before)
        after      (safe-compute-sub query-v db-after)
        annotated  (at/diff-tree before after)
        sections   (sg/group-into-sections annotated)]
    {:sub-id        sub-id
     :query-v       query-v
     :before-value  before
     :after-value   after
     :diff-sections sections
     :unchanged?    (= :same (at/op-of annotated))}))

(defn build-records
  "Pure helper — given the current epoch record (carrying `:sub-runs`,
  `:db-before`, `:db-after`), produce the records vector. Exposed for
  unit testing without re-frame."
  [current]
  (let [sub-runs   (or (:sub-runs current) [])
        db-before  (:db-before current)
        db-after   (:db-after current)]
    (mapv #(sub-record % db-before db-after) sub-runs)))

(defn install!
  "Idempotent install — register
  `:rf.causa/views-sub-diff-for-focused-event`. Called from
  `panels/views.cljs` facade's `install!`."
  []
  (rf/reg-sub :rf.causa/views-sub-diff-for-focused-event
    :<- [:rf.causa/views-focused-cascade-pair]
    :<- [:rf.causa/epoch-history]
    (fn [[pair history] _query]
      (let [current     (:current pair)
            dispatch-id (:dispatch-id (:focus pair))
            epoch-id    (:epoch-id current)]
        (if-not current
          {:dispatch-id dispatch-id
           :records     []}
          (let [cached (get @sub-diff-cache epoch-id ::miss)]
            (if (not= ::miss cached)
              {:dispatch-id dispatch-id
               :records     cached}
              (let [records (build-records current)
                    ;; Prune the cache to epochs the ring buffer still
                    ;; carries — same strategy as the app-db-diff
                    ;; `:selected-epoch-diff` cache (see
                    ;; `app_db_diff_subs.cljs`). Without the prune the
                    ;; cache would grow without bound as the ring
                    ;; buffer evicts old epochs.
                    live-ids (into #{} (map :epoch-id) history)]
                (swap! sub-diff-cache
                       (fn [m]
                         (-> m
                             (select-keys live-ids)
                             (assoc epoch-id records))))
                {:dispatch-id dispatch-id
                 :records     records})))))))
  nil)
