(ns long-running-work.schema
  "Malli schemas for the two machines in this example.

   A machine's snapshot doesn't live in app-db — it lives in runtime-db
   (docs/guide/glossary.md#runtime-db). So instead of registering an
   app-db path, each machine carries its own `:data` schema in its
   `[:schemas :data]` slot, and the runtime checks it after every
   transition and at spawn time. See the schemas guide's machine
   section (docs/guide/how-to/validate-with-schemas.md).

   Two machines, two schemas:

   - `:work/flow`       — the parent coordinator. There's only ever one,
                          at the fixed id `:work/flow`. `FlowData` rides
                          on its `[:schemas :data]` slot in `reg-machine`.
   - `:work/processor`  — the child. Each spawn gets a fresh
                          runtime-assigned id (`:work/processor#0`,
                          `#1`, …), so no single app-db path could ever
                          name them all — which is exactly why
                          `[:schemas :data]` is the right surface.
                          `ProcessorData` rides on the child's slot
                          (see `worker.cljs`) and is checked at spawn
                          time, so a child built with malformed `:data`
                          is turned away at the door."
  (:require [re-frame.core :as rf]
            ;; Requiring re-frame.schemas wires in the validator, so the
            ;; machines' `[:schemas :data]` slots actually get checked.
            [re-frame.schemas]))

;; ============================================================================
;; SHARD-PROGRESS MAP — the parent's :data :progress slot
;; ============================================================================
;;
;; Just a tally: shard-id → items done so far. The parent bumps an entry
;; every time a child reports `:progress`, and the aggregate-progress sub
;; sums the values and divides by the total.

(def ProgressMap
  [:map-of :keyword :int])

;; ============================================================================
;; PARENT SNAPSHOT — :work/flow
;; ============================================================================
;;
;; The whole snapshot looks like this:
;;   {:state    <:idle | :working | :complete | :cancelled | :error>
;;    :data     {:total      <int>           ;; items per shard
;;               :shards     [<keyword> ...]  ;; which shards are spawned
;;               :progress   {<shard-id> <items-done>}
;;               :outcome    <:complete | :cancelled | :error | nil>}
;;    :tags     #{...}}                       ;; runtime-owned union

;; ...but we only schema-check `:data`. The runtime owns `:state` and
;; `:tags`, so it isn't our place to describe them. FlowData gets bolted
;; on via the `[:schemas :data]` slot of `(reg-machine :work/flow ...)`
;; over in `worker.cljs`.
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
;; This is a single child's working memory — everything it needs to chew
;; through its shard. As with the parent, we only describe `:data`; the
;; runtime owns `:state`, the `:tags` union, and the reserved `:rf/*`
;; slots. ProcessorData hangs off the `[:schemas :data]` slot of
;; `(reg-machine :work/processor ...)` in `worker.cljs`, and it's checked
;; the moment a child spawns. Everything below is required, because the
;; parent's `:spawn-all` invoke-spec hands every field over on spawn.

(def ProcessorData
  [:map
   [:shard     :keyword]   ;; which shard this child owns
   [:total     :int]       ;; items in that shard
   [:processed :int]       ;; how many it's finished so far
   [:tick-ms   :int]       ;; ms it yields to the browser between chunks
   ;; The runtime stamps a child's own address into its :data on spawn.
   ;; We list these (optional) so the schema documents them — a Malli
   ;; :map is open anyway, so they'd sail through undeclared.
   [:rf/self-id   {:optional true} :any]
   [:rf/parent-id {:optional true} :any]
   [:rf/invoke-id {:optional true} :any]])

;; ============================================================================
;; SCHEMA ATTACHMENT
;; ============================================================================
;;
;; And that's it — both schemas attach over in `worker.cljs` via each
;; machine's `[:schemas :data]` slot: `FlowData` on `:work/flow`,
;; `ProcessorData` on `:work/processor`. You won't find a `reg-app-schema`
;; in this example, and for a reason: that one guards the app-db
;; partition, but machine snapshots live in runtime-db. For a machine,
;; `[:schemas :data]` is the surface that does the checking.
