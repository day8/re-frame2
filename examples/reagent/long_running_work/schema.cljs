(ns long-running-work.schema
  "Malli schemas for the long-running-work example.

   The example shows Pattern-LongRunningWork's `:spawn-all` shape:
   a parent coordinator machine spawns N child workers in parallel,
   each child reports progress back to the parent via dispatch, and
   the parent's :spawn-all-bearing state is exited (by `:complete`,
   `:cancel`, or unmount) which cascades a teardown to every surviving
   child.

   Two machines run at runtime. Machine snapshots live in the framework's
   runtime-db partition at `[:rf.runtime/machines :snapshots <id>]`, NOT in
   app-db — and `reg-app-schema` validates the app-db partition only (EP-0001,
   Mike ruling #11). So both machines validate their snapshot's `:data` via the
   machine's own `[:schemas :data]` slot, the surface the runtime addresses for
   runtime-db-resident snapshots:

   - `:work/flow`       — the parent coordinator, a singleton at the fixed
                          id `:work/flow`. `FlowData` is attached via its
                          `[:schemas :data]` slot on `reg-machine`.
   - `:work/processor`  — the child machine type. Each child is
                          spawned via `:spawn-all` and gets a gensym'd
                          id (e.g. `:work/processor#0`) assigned by the
                          runtime at spawn time, so its snapshot lives
                          at an UNKNOWN runtime-db path that varies per
                          instance — another reason `[:schemas :data]` (not a
                          fixed path) is the right surface. `ProcessorData`
                          is attached via the
                          child machine's `[:schemas :data]` slot on `reg-machine`
                          (see `worker.cljs`), which validates each spawned
                          instance's initial `:data` at spawn time, before
                          the snapshot installs (Spec 005 §Schema
                          validation §Spawn-time validation) — so a child
                          spawned with malformed `:data` never enters the
                          runtime, regardless of its gensym'd id.

   Both shapes are boundary-validated in development (Spec 010 §Per-step
   recovery row 7 — `:where :machine-data` for the child)."
  (:require [re-frame.core :as rf]
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas.
            ;; Loading the ns here registers its late-bind hooks so the
            ;; machines' `[:schemas :data]` slots are validated at runtime.
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

;; The parent snapshot's `:data` slot. The surrounding snapshot (`:state`,
;; `:tags`) is runtime-owned; the machine `[:schemas :data]` describes `:data`
;; only (Spec 005 §Schema validation). Attached via the `[:schemas :data]` slot
;; on `(reg-machine :work/flow ...)` in `worker.cljs`.
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
;; The child machine's `:data` slot — the user-domain working memory the
;; child carries. The runtime owns the surrounding snapshot (`:state`, the
;; `:tags` union, the reserved `:rf/*` slots), so the machine `[:schemas :data]`
;; describes `:data` ONLY (Spec 005 §Schema validation). Attached via the
;; `[:schemas :data]` slot on `(reg-machine :work/processor ...)` in `worker.cljs`,
;; which validates each spawned instance's initial `:data` at spawn time —
;; the value here is what the parent's per-child `:spawn-all` invoke-spec
;; plants (every field below is supplied, so all are required).

(def ProcessorData
  [:map
   [:shard     :keyword]   ;; the parent-assigned shard id
   [:total     :int]       ;; items in this shard
   [:processed :int]       ;; how many done so far
   [:tick-ms   :int]       ;; ms between chunks (browser yield)
   ;; Runtime-stamped address keys — the spawn fx stamps these into the
   ;; spawned actor's initial :data (Spec 005 §Reserved snapshot-internal
   ;; keys). Optional + declared so the schema documents them; a Malli
   ;; :map is open, so they'd pass undeclared too.
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
;; live in the runtime-db partition, and `reg-app-schema` validates the
;; app-db partition only (EP-0001, Mike ruling #11). `[:schemas :data]` is the
;; snapshot-validation surface for runtime-db-resident machine state.
