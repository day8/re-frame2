(ns long-running-work.schema
  "Malli schemas for the long-running-work example.

   The example shows Pattern-LongRunningWork's `:spawn-all` shape:
   a parent coordinator machine spawns N child workers in parallel,
   each child reports progress back to the parent via dispatch, and
   the parent's :spawn-all-bearing state is exited (by `:complete`,
   `:cancel`, or unmount) which cascades a teardown to every surviving
   child.

   Two machines run at runtime, and each gets a schema attached via
   the mechanism that fits HOW the runtime addresses its snapshot:

   - `:work/flow`       — the parent coordinator, a singleton at a
                          fixed id. Its snapshot lives at the fixed
                          path `[:rf/runtime :machines :snapshots
                          :work/flow]`, so `FlowSnapshot` is attached
                          with `rf/reg-app-schema` on that path.
   - `:work/processor`  — the child machine type. Each child is
                          spawned via `:spawn-all` and gets a gensym'd
                          id (e.g. `:work/processor#0`) assigned by the
                          runtime at spawn time, so its snapshot lives
                          at an UNKNOWN path that varies per instance.
                          A fixed `reg-app-schema` path cannot reach it.
                          Instead, `ProcessorData` is attached via the
                          child machine's `:schema` slot on `reg-machine`
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
;; CHILD :data SHAPE — :work/processor (one instance per shard)
;; ============================================================================
;;
;; The child machine's `:data` slot — the user-domain working memory the
;; child carries. The runtime owns the surrounding snapshot (`:state`, the
;; `:tags` union, the reserved `:rf/*` slots), so the machine `:schema`
;; describes `:data` ONLY (Spec 005 §Schema validation). Attached via the
;; `:schema` slot on `(reg-machine :work/processor ...)` in `worker.cljs`,
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
   [:rf/spawn-id  {:optional true} :any]])

;; ============================================================================
;; SCHEMA REGISTRATION
;; ============================================================================
;;
;; `:work/flow` is a singleton at a fixed id, so its full snapshot is
;; attached on the fixed snapshot path. (The `:work/processor` children
;; are attached via the machine `:schema` slot in `worker.cljs` — see the
;; ns docstring for why a fixed path cannot reach a gensym'd-id snapshot.)

(rf/reg-app-schema [:rf/runtime :machines :snapshots :work/flow] FlowSnapshot)
