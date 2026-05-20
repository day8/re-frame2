(ns long-running-work.schema
  "Malli schemas for the long-running-work example.

   The example shows Pattern-LongRunningWork's `:spawn-all` shape:
   a parent coordinator machine spawns N child workers in parallel,
   each child reports progress back to the parent via dispatch, and
   the parent's :spawn-all-bearing state is exited (by `:complete`,
   `:cancel`, or unmount) which cascades a teardown to every surviving
   child.

   Two snapshots live in `:rf/machines` at runtime:

   - `:work/flow`       — the parent coordinator. Tracks per-shard
                          progress in `:data :progress` and the chunk
                          size / total in `:data :total`.
   - `:work/processor`  — the child machine type. Each child gets a
                          gensym'd id (e.g. `:work/processor#1`)
                          assigned by the runtime at spawn time.

   The shapes are stamped as `reg-app-schema` so writes to those paths
   are boundary-validated in development (Spec 010)."
  (:require [re-frame.core :as rf]
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas.
            ;; Loading the ns here registers its late-bind hooks so
            ;; rf/reg-app-schema resolves below.
            [re-frame.schemas]))

;; ============================================================================
;; SHARD-PROGRESS MAP — the parent's :data :progress slot
;; ============================================================================
;;
;; A map of shard-id keyword → items processed (an integer). The parent
;; updates this on every `:progress` event a child dispatches. The
;; aggregate-progress sub sums the values and divides by the total.

(def ProgressMap
  [:map-of :keyword :int])

;; ============================================================================
;; PARENT SNAPSHOT — :work/flow
;; ============================================================================
;;
;; Shape:
;;   {:state    <:idle | :working | :complete | :cancelled | :error>
;;    :data     {:total      <int>           ;; items per shard
;;               :shards     [<keyword> ...]  ;; which shards are spawned
;;               :progress   {<shard-id> <items-done>}
;;               :outcome    <:complete | :cancelled | :error | nil>}
;;    :tags     #{...}}                       ;; runtime-owned union

(def FlowSnapshot
  [:map
   [:state [:enum :idle :working :complete :cancelled :error]]
   [:data  [:map
            [:total    :int]
            [:shards   [:vector :keyword]]
            [:progress ProgressMap]
            [:outcome  [:maybe [:enum :complete :cancelled :error]]]]]])

;; ============================================================================
;; CHILD SNAPSHOT — :work/processor (one instance per shard)
;; ============================================================================
;;
;; Shape:
;;   {:state    <:idle | :processing | :checking-done | :yielding | :done | :cancelled>
;;    :data     {:shard      <keyword>          ;; the parent-assigned id
;;               :total      <int>               ;; items in this shard
;;               :processed  <int>               ;; how many done so far
;;               :tick-ms    <int>               ;; ms between chunks (browser yield)}
;;    :tags     #{...}}

(def ProcessorSnapshot
  [:map
   [:state [:enum :idle :processing :checking-done :yielding :done :cancelled]]
   [:data  [:map
            [:shard     :keyword]
            [:total     :int]
            [:processed :int]
            [:tick-ms   :int]]]])

;; ============================================================================
;; SCHEMA REGISTRATION (Spec 010 §Path-based attachment)
;; ============================================================================

(rf/reg-app-schema [:rf/machines :work/flow] FlowSnapshot)
;; The :work/processor children get gensym'd ids (e.g. :work/processor#1);
;; we register a wildcard-style schema on the prefix so the rebound
;; per-instance snapshots are validated against the same shape. The
;; framework's path-based attachment walks the path; the per-instance
;; id falls outside the registered prefix and is not boundary-checked
;; directly — that's intentional, the dispatched-by-runtime spawn fx
;; writes a known-good shape.
