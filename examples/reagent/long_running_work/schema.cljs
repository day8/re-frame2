(ns long-running-work.schema
  "Malli schemas for the long-running-work example.

   Two machines run at runtime: the `:work/flow` parent coordinator and
   the `:work/processor` child it spawns. Each machine's snapshot lives
   in runtime-db (docs/guide/glossary.md#runtime-db), not app-db, so it
   carries its own `:data` schema in its `[:schemas :data]` slot rather
   than registering an app-db path. The runtime validates that slot
   after every transition and at spawn time. See the schemas guide's
   machine section (docs/guide/how-to/validate-with-schemas.md).

   - `:work/flow`       — the parent coordinator, a singleton at the
                          fixed id `:work/flow`. `FlowData` is attached
                          via its `[:schemas :data]` slot on `reg-machine`.
   - `:work/processor`  — the child machine type. Each child is spawned
                          via `:spawn-all` and gets a runtime-assigned
                          id (e.g. `:work/processor#0`), so its snapshot
                          lives at a path that varies per instance —
                          another reason `[:schemas :data]` (not a fixed
                          app-db path) is the right surface. `ProcessorData`
                          is attached via the child's `[:schemas :data]`
                          slot on `reg-machine` (see `worker.cljs`). It
                          validates each spawned instance's initial
                          `:data` at spawn time, so a child spawned with
                          malformed `:data` never enters the runtime."
  (:require [re-frame.core :as rf]
            ;; Loading re-frame.schemas wires up the validator so the
            ;; machines' `[:schemas :data]` slots are checked at runtime.
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

;; The parent snapshot's `:data` slot. The runtime owns the rest of the
;; snapshot (`:state`, `:tags`), so the machine `[:schemas :data]` describes
;; `:data` only. Attached via the `[:schemas :data]` slot on
;; `(reg-machine :work/flow ...)` in `worker.cljs`.
(def FlowData
  [:map
   [:total    :int]
   [:shards   [:vector :keyword]]
   [:progress ProgressMap]
   [:outcome  [:maybe [:enum :complete :cancelled :error]]]])

;; ============================================================================
;; CHILD :data SHAPE — :work/processor (one instance per shard)
;; ============================================================================
;;
;; The child machine's `:data` slot — the working memory each child
;; carries. The runtime owns the rest of the snapshot (`:state`, the
;; `:tags` union, the reserved `:rf/*` slots), so the machine
;; `[:schemas :data]` describes `:data` only. Attached via the
;; `[:schemas :data]` slot on `(reg-machine :work/processor ...)` in
;; `worker.cljs`, which validates each spawned instance's initial `:data`
;; at spawn time. The value here is what the parent's per-child
;; `:spawn-all` invoke-spec plants (every field below is supplied, so all
;; are required).

(def ProcessorData
  [:map
   [:shard     :keyword]   ;; the parent-assigned shard id
   [:total     :int]       ;; items in this shard
   [:processed :int]       ;; how many done so far
   [:tick-ms   :int]       ;; ms between chunks (browser yield)
   ;; Runtime-stamped address keys — the spawn fx stamps these into the
   ;; spawned child's initial :data. Declared (and optional) so the schema
   ;; documents them; a Malli :map is open, so they'd pass undeclared too.
   [:rf/self-id   {:optional true} :any]
   [:rf/parent-id {:optional true} :any]
   [:rf/invoke-id {:optional true} :any]])

;; ============================================================================
;; SCHEMA ATTACHMENT
;; ============================================================================
;;
;; Both machines attach their `:data` schema via the machine `[:schemas :data]`
;; slot in `worker.cljs` — `FlowData` on `:work/flow`, `ProcessorData` on
;; `:work/processor`. There is no `reg-app-schema` here: machine snapshots
;; live in runtime-db, and `reg-app-schema` validates the app-db partition
;; only. `[:schemas :data]` is the validation surface for a machine snapshot.
