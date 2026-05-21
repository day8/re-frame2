(ns day8.re-frame2-causa.panels.reactive-panel-subs
  "Subscriptions for the Reactive panel (rf2-wyvf2 · spec/021 §3).

  The panel reads the focused epoch record's normalized structured
  projections — `:sub-runs` and `:renders` — assembled by the
  substrate on each `:rf/epoch-record` (per spec/018). It does NOT
  re-derive rows from raw `:trace-events` by op-keyword: the canonical
  ops are `:sub/run` / `:rf.sub/skip` / `:rf.view/rendered`
  (Spec 009 §:op-type vocabulary), and the earlier op-keyword path
  grepped for names the substrate never emits (`:rf.sub/computed` /
  `:rf.sub/skipped`), pinning subs-ran / subs-skipped to zero
  regardless of how many subs actually ran.

  - `:sub-runs` → subs-ran (`:recomputed?` true) + subs-skipped
    (memo-hit, `:recomputed?` false)
  - `:renders`  → views-rendered (`:render-key` → top-level `:view-id`)
  - flow counts → tallied from `:trace-events` (`:rf.flow/computed` /
    `:rf.flow/skip`), the one slice with no structured projection.

  No new instrumentation — pure consumer over the epoch record.

  ## Public surface

  - `:rf.causa/reactive-data` — composite sub the panel view reads.
    Shape:

        {:focus           {<focus map>}
         :frame           <frame-kw>
         :dispatch-id     <id-of-focused-cascade>
         :has-cascade?    <bool>
         :triggered-by    <event-vec>
         :seed-paths      [<path> ...]
         :subs-ran        [{:sub-id _ :payload _} ...]   ; rf.sub/computed
         :subs-skipped    [{:sub-id _ :payload _} ...]   ; rf.sub/skipped
         :views-rendered  [{:view-id _ :payload _} ...]  ; rf.view/rendered
         :counts          {:subs-ran N :subs-skipped N
                           :views-rendered N
                           :flows-recomputed N}}

  Per spec/021 §3.4 the panel's 'Show unchanged subs' disclosure is
  view-local (panel UI state); the always-expand override lives in
  Settings (`:rf.causa/show-unchanged-subs?` · `:general` section).

  ## Install

  `install!` registers `:rf.causa/reactive-data` + the panel-local
  disclosure-toggle state slot. Idempotent."
  (:require [re-frame.core :as rf]))

;; ---- pure helpers (exposed for test) ------------------------------------

(defn focused-epoch-record
  "Locate the focused epoch record in `epoch-history`. Mirrors the
  legacy Views panel lookup: match by `:epoch-id`, fall back to head
  when the focus has no `:epoch-id` (LIVE / cold-start) or the focused
  record has been evicted from the ring."
  [epoch-history epoch-id]
  (when (seq epoch-history)
    (or (when epoch-id
          (some (fn [record] (when (= epoch-id (:epoch-id record)) record))
                epoch-history))
        (peek (vec epoch-history)))))

(defn- op-kw
  "Trace-event op keyword. The substrate's `:rf/*` ops carry the kw on
  `:operation`; tests sometimes shape it as `:op` — accept both."
  [event]
  (or (:operation event) (:op event)))

(defn project-record
  "Project an epoch record into the Reactive-panel shape. Pure data.

  Reads the substrate's normalized structured projections — `:sub-runs`
  and `:renders` — directly off the `:rf/epoch-record` (per spec/018),
  rather than re-deriving rows from raw `:trace-events` by op-keyword.
  The op-keyword path this replaced grepped for names the substrate
  never emits (`:rf.sub/computed` / `:rf.sub/skipped`) where the
  canonical ops are `:sub/run` / `:rf.sub/skip` (Spec 009 §:op-type
  vocabulary), so subs-ran / subs-skipped were perpetually zero.

  `:sub-runs` entries carry `:sub-id` / `:query-v` / `:recomputed?`;
  the `:recomputed?` flag splits genuine recomputes (subs-ran) from
  memo-hit skips (subs-skipped). `:renders` entries carry `:render-key`
  (a `[view-id idx]` vector); `:view-id` is lifted to the top level for
  the panel's row renderer.

  Flows have no structured projection on the record, so flow counts are
  tallied from `:trace-events` using the canonical `:rf.flow/computed`
  / `:rf.flow/skip` op names.

  Returns the map shape documented in the ns docstring (sans the
  focus / frame / dispatch-id keys; those come from the spine sub)."
  [record]
  (let [sub-runs (vec (:sub-runs record))
        ran      (filterv :recomputed? sub-runs)
        skipped  (filterv (complement :recomputed?) sub-runs)
        renders  (mapv (fn [r] (assoc r :view-id (first (:render-key r))))
                       (:renders record))
        grouped  (group-by op-kw (or (:trace-events record) []))
        flows-comp    (count (get grouped :rf.flow/computed []))
        flows-skipped (count (get grouped :rf.flow/skip []))]
    {:subs-ran        ran
     :subs-skipped    skipped
     :views-rendered  renders
     :counts          {:subs-ran         (count ran)
                       :subs-skipped     (count skipped)
                       :views-rendered   (count renders)
                       :flows-recomputed flows-comp
                       :flows-skipped    flows-skipped}}))

(defn- triggered-by
  "Reconstruct the triggering event from the epoch record. Reuses the
  `:event` slot the spine seeds on every record (per spec/018)."
  [record]
  (or (:event record)
      (some (fn [e] (when (= :rf/event-dispatched (op-kw e))
                      (or (:payload e) (:args e))))
            (:trace-events record))))

(defn- seed-paths
  "Derive seed paths from the cascade `:db-before` → `:db-after` diff.
  The handler set state and that mutation kicks the subs cascade. v1
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
  (rf/reg-sub :rf.causa/reactive-show-unchanged?
    (fn [db _query]
      (boolean (get db :reactive/show-unchanged?))))

  ;; -- composite the view reads --------------------------------------
  (rf/reg-sub :rf.causa/reactive-data
    :<- [:rf.causa/focus]
    :<- [:rf.causa/epoch-history]
    (fn [[focus history] _query]
      (let [record (focused-epoch-record history (:epoch-id focus))
            proj   (project-record record)]
        (merge proj
               {:focus        focus
                :frame        (:frame focus)
                :dispatch-id  (:dispatch-id focus)
                :triggered-by (when record (triggered-by record))
                :seed-paths   (when record (seed-paths record))
                :has-cascade? (some? record)}))))

  nil)
