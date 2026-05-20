(ns day8.re-frame2-causa.panels.reactive-panel-subs
  "Subscriptions for the Reactive panel (rf2-wyvf2 · spec/021 §3).

  The panel reads the focused cascade's `:trace-events` slot for the
  three substrate ops landed in #1728 / #1729:

  - `:rf.sub/computed`  · sub recomputed (existing)
  - `:rf.sub/skipped`   · sub skipped — input-unchanged
                          short-circuit (#1729)
  - `:rf.view/rendered` · view re-render at render-commit boundary
                          (#1728)
  - `:rf.cascade/captured` · optional end-of-epoch aggregate
                              (counts only) (#1729)

  No new instrumentation — pure consumer over the trace stream the
  epoch capture carries on `:rf/epoch-record :trace-events`.

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

(defn project-trace-events
  "Project an epoch record's `:trace-events` into the Reactive-panel
  shape. Pure data — keyed off the canonical op keywords.

  Returns the map shape documented in the ns docstring (sans the
  focus / frame / dispatch-id keys; those come from the spine sub)."
  [trace-events]
  (let [grouped (group-by op-kw (or trace-events []))
        subs-computed (mapv (fn [e] (or (:payload e) e))
                            (get grouped :rf.sub/computed []))
        subs-skipped  (mapv (fn [e] (or (:payload e) e))
                            (get grouped :rf.sub/skipped []))
        views-rend    (mapv (fn [e] (or (:payload e) e))
                            (get grouped :rf.view/rendered []))
        flows-comp    (count (get grouped :rf.flow/computed []))
        flows-skipped (count (get grouped :rf.flow/skipped []))
        aggregate     (some-> (first (get grouped :rf.cascade/captured))
                              (or {})
                              :payload)
        ;; Prefer the aggregate's counts when present; fall back to
        ;; the projected vector lengths so the panel renders even when
        ;; the optional :rf.cascade/captured op is absent.
        counts        (or aggregate
                          {:subs-ran         (count subs-computed)
                           :subs-skipped     (count subs-skipped)
                           :views-rendered   (count views-rend)
                           :flows-recomputed flows-comp
                           :flows-skipped    flows-skipped})]
    {:subs-ran        subs-computed
     :subs-skipped    subs-skipped
     :views-rendered  views-rend
     :counts          counts}))

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
            proj   (project-trace-events (when record (:trace-events record)))]
        (merge proj
               {:focus        focus
                :frame        (:frame focus)
                :dispatch-id  (:dispatch-id focus)
                :triggered-by (when record (triggered-by record))
                :seed-paths   (when record (seed-paths record))
                :has-cascade? (some? record)}))))

  nil)
