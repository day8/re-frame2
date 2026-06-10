(ns re-frame.resources.state
  "Resource runtime-db paths + the durable entry / work-record shapes.
  Per Spec 016 §Cache home and write authority and §Frame work ledger.

  SKELETON slice (rf2-p10npe): this namespace fixes the reserved
  runtime-db key paths and the canonical durable shapes the runtime
  reads/writes, plus the framework-write-authority registration-meta
  stamp every resource event handler carries. The actual swaps over
  these paths (entry transition function, work-ledger join/dedupe, host
  side-table bookkeeping) land in the runtime slices (rf2-afpdkn /
  rf2-pbxj48). The paths and shapes are pinned here so siblings agree on
  one home.

  Cache lives ONLY at `:rf.runtime/resources` inside the runtime-db
  partition (`:rf.db/runtime`); the work ledger lives at
  `:rf.runtime/work-ledger`. Both are reserved runtime-db keys (per
  [Conventions §Reserved runtime-db keys]) — allocated lazily, per-frame
  isolated, never an app-db location."
  (:require [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- reserved runtime-db paths -------------------------------------------
;;
;; Inside runtime-db itself, framework code reads/writes the bare
;; `[:rf.runtime/resources …]` paths; inside a full frame-state
;; projection the resource subtree is at
;; `[:rf.db/runtime :rf.runtime/resources …]`. Per Spec 016 §Cache home.

(def resources-key
  "The reserved runtime-db key for the resource cache subtree
  (`:rf.runtime/resources`). Per Spec 016 §Cache home and write authority."
  :rf.runtime/resources)

(def work-ledger-key
  "The reserved runtime-db key for the frame work ledger subtree
  (`:rf.runtime/work-ledger`). Named neutrally — resources are its first
  writer, later slices extend it to timers / streams / route loaders /
  spawned actors / machine async work. Per Spec 016 §Frame work ledger."
  :rf.runtime/work-ledger)

(defn entries-path
  "Runtime-db-relative path to the cache entries map
  `{<scoped-resource-key> <entry>}`. Per Spec 016 §Cache home."
  []
  [resources-key :entries])

(defn tag-index-path
  "Runtime-db-relative path to the reverse tag index
  `{<tag> #{<scoped-resource-key> …}}`. Recomputable-from-`:entries`
  (rebuilt on restore/hydration, never trusted from the snapshot). Per
  Spec 016 §Cache home / §Restore and replay."
  []
  [resources-key :tag-index])

(defn owner-index-path
  "Runtime-db-relative path to the reverse owner index
  `{<owner> #{<scoped-resource-key> …}}`. Recomputable-from-`:entries`.
  Per Spec 016 §Cache home / §Restore and replay."
  []
  [resources-key :owner-index])

(defn entry-path
  "Runtime-db-relative path to a single cache entry by its scoped
  resource key `[cache-scope resource-id canonical-params]`. Per Spec 016
  §Resource identity."
  [scoped-resource-key]
  [resources-key :entries scoped-resource-key])

(defn work-record-path
  "Runtime-db-relative path to a single work record by its `:work/id`.
  Per Spec 016 §Frame work ledger."
  [work-id]
  [work-ledger-key work-id])

;; ---- framework-write authority -------------------------------------------
;;
;; `:rf.runtime/resources` and `:rf.runtime/work-ledger` are framework-
;; owned runtime-db children, so resource writes MUST mint framework-write
;; authority — ordinary app authority is not enough. Every resource
;; `reg-event-fx` registration site stamps this reserved registration-meta
;; key so the runtime recognises a returned `:rf.db/runtime` effect from a
;; resource handler as in-bounds (it governs only the
;; `:rf.warning/app-handler-runtime-effect` ownership diagnostic — a
;; convention, not a capability gate; Spec 002 Mike ruling #4). Mirrors
;; routing's `framework-authority-meta`. Per Spec 016 §Write authority.

(def framework-authority-meta
  "Reserved registration-meta map (`{:rf/framework-authority? true}`)
  stamped on every resource event handler so a returned runtime-db effect
  is recognised as a framework write. Per Spec 016 §Write authority."
  {:rf/framework-authority? true})

;; ---- durable shapes (documentation-grade defaults) -----------------------
;;
;; These constructors fix the canonical durable shapes the runtime slices
;; fill in. They allocate plain EDN — no host handles, which live OUTSIDE
;; durable frame-state in side tables keyed by `[frame-id work-id]`.

(def lifecycle-states
  "The five resource lifecycle FSM states (cache-entry status). The
  transition function over these states lands in the runtime slice
  (rf2-pbxj48); pinned here so siblings agree on the closed set. Per
  Spec 016 §Lifecycle is an FSM."
  #{:idle :loading :fetching :loaded :error})

(def terminal-work-statuses
  "The terminal work-ledger statuses an attempt may reach. Terminal rows
  are pruned on the linked entry's next successful transition (a small
  bounded per-resource-key tail is retained for Xray). Per Spec 016
  §Ledger row retention and identity."
  #{:completed :failed :timed-out :suppressed :cancelled})

(defn empty-entry
  "Construct an empty `:idle` durable cache entry for `resource-id`. The
  durable entry stores FACTS, not derived booleans (`:stale?` /
  `:loading?` / `:has-data?` are public derived sub values, computed in
  the subs layer, never stored). Per Spec 016 §Status semantics.

  SKELETON: the runtime slices populate / transition this shape; this
  constructor pins the canonical key set so an entry written by one
  sibling reads correctly in another."
  [resource-id]
  {:resource/id    resource-id
   :status         :idle
   :data           nil
   :error          nil
   :refresh-error  nil
   :loaded-at      nil
   :stale-at       nil
   :invalidated-at nil
   :attempt        0
   :generation     0
   :request-id     nil
   :current-work   nil
   :tags           #{}
   :active-owners  #{}})

;; ---- host-side transient generation allocator (skeleton) ------------------
;;
;; Per Spec 016 §Restore and replay part 1: the generation allocator is a
;; per-frame, HOST-SIDE monotonic high-water mark — never rewound by epoch
;; restore, so a pre-restore in-flight reply's generation can never match a
;; post-restore live entry (stale-suppression is structurally safe). This
;; is deliberately the OPPOSITE discipline from machine spawn-ids (which
;; never escape the frame and so may be snapshot-local).
;;
;; SKELETON: the allocator table + monotone bump land with the runtime
;; slice (rf2-pbxj48). The host-side home is pinned here (a per-frame
;; module-level atom, like routing's nav-counter cache) so the runtime and
;; the frame-destroy teardown agree on where it lives.

(defonce
  ^{:doc "Per-frame host-side generation high-water marks
   `{<frame-id> <int>}`. Host-side transient state (NOT runtime-db), so an
   epoch restore cannot rewind it and recycle a generation — the
   anti-recycling correctness boundary (Spec 016 §Restore and replay part
   1). Populated by the runtime slice (rf2-pbxj48)."}
  generation-cache
  (atom {}))

(defn release-frame!
  "Drop the destroyed frame's host-side generation high-water mark.
  Invoked by the resources frame-destroy teardown hook. Per Spec 016
  §Stale and GC scheduling (frame destroy cancels all resource timers /
  clears host handles for that frame) and §Restore and replay part 5."
  [frame-id]
  (swap! generation-cache dissoc frame-id)
  nil)

(defn reset-cache!
  "Drop EVERY frame's host-side generation high-water mark (test
  isolation). Published as a reset hook so the shared CLJS
  `make-reset-runtime-fixture` reset-hooks table clears it per test (it is
  host-side transient state, not cleared by the runtime/frames reset)."
  []
  (reset! generation-cache {})
  nil)

;; A no-op consult of late-bind so the require is load-bearing even in the
;; skeleton (the runtime slice will consult `:router/dispatch!` etc. from
;; here). Keeps the ns honest about its dependency surface.
(defn ^:private _late-bind-touch [] (late-bind/get-fn :router/dispatch!))
