(ns re-frame.frame
  "Frame container, lifecycle, and the frame registry. Per Spec 002.

  A frame is an isolated runtime boundary identified by a keyword. Every
  frame holds its own app-db (a substrate-managed reactive container),
  its own per-frame router queue, and its own sub-cache.

  Frames are not values — they are mutable runtime objects. User code
  holds keywords; this namespace holds the frame records.

  Reserved frame ids:
    :rf/default              — universal default frame (always present)
    :rf.frame/<gensym>       — anonymous instances from make-frame"
  (:require [clojure.string]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the frame record -----------------------------------------------------
;;
;; Per Spec 002 §What lives in a frame, a frame is a map with:
;;   :id          the keyword identity
;;   :frame-state the ONE physical durable container (opaque; through adapter)
;;                — holds BOTH partitions as a frame-state value
;;                `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`.
;;   :app-db      the app-db PROJECTION REACTION over :frame-state
;;                (`make-derived-value [frame-state] :rf.db/app`). Read-only —
;;                layer-1 app subs read it; writes go through the frame-state
;;                container, never `replace-container!` on this projection.
;;   :runtime-db  the runtime-db PROJECTION REACTION over :frame-state
;;                (`make-derived-value [frame-state] :rf.db/runtime`). Read-only
;;                — framework subs read it.
;;   :router      per-frame queue + drain-state FSM (defined in router.cljc)
;;   :sub-cache   per-frame sub-cache (defined in subs.cljc)
;;   :lifecycle   {:created-at :destroyed? :listeners}
;;   :config      the metadata that reg-frame was given
;;
;; Per Spec 002 §One physical container, two projection reactions + Spec 006
;; §Frame-state container and partition projections (EP-0001 decision #3):
;; the frame holds ONE physical frame-state container; app-db and runtime-db
;; are PROJECTION REACTIONS over it. Partition-aware sub-cache invalidation
;; falls out of `make-derived-value`'s memoised `=`-equality — NO dirty flags
;; (decision #7): a runtime-only commit recomputes the app-db projection,
;; finds `:rf.db/app` `=`, and does not propagate to app subs; an app-only
;; commit is symmetric.
;;
;; Frame records are stored in `frames` keyed by id.
;;
;; The two reserved partition keys inside the physical frame-state value.
;; `:rf.db/app` is the app-db partition slot; `:rf.db/runtime` is the
;; runtime-db partition slot (per Spec 002 §The two-partition frame contract
;; and Conventions §Reserved partition keys). Held here as the single source
;; of truth for the commit + projection machinery in this ns.

(def ^:const app-partition-key
  "Reserved frame-state key naming the app-db partition (`:rf.db/app`)."
  :rf.db/app)

(def ^:const runtime-partition-key
  "Reserved frame-state key naming the runtime-db partition (`:rf.db/runtime`)."
  :rf.db/runtime)

(defonce
  ^{:doc "Map of frame-id → frame-record. Per-process (one global frame registry)."}
  frames
  (atom {}))

;; ---- destroy-in-flight guard (rf2-r1ciy) ---------------------------------
;;
;; Tracks frame-ids whose `destroy-frame!` call is currently mid-flight so
;; a re-entrant `(destroy-frame! id)` from inside the same id's
;; `:on-destroy` handler (or downstream teardown hook) is a silent no-op.
;; Without this guard a re-entrant destroy would recursively re-enter
;; teardown — re-firing `:on-destroy`, re-running the machine cascade,
;; re-disposing the sub-cache — and likely throw on a half-torn-down
;; frame. Per Spec 002 §Destroy — re-entrant destroy is idempotent.

(defonce ^:private destroying-frames
  (atom #{}))

;; ---- frame resolution at call sites --------------------------------------
;;
;; *current-frame* is the dynamic var that with-frame binds. Subscribe and
;; dispatch default to (current-frame) when no :frame is supplied, so views
;; nested under a (with-frame ...) wrapper or a Reagent frame-provider
;; auto-route to the right frame.

(def ^:dynamic *current-frame* nil)

(defn current-frame
  "Resolution chain: dynamic var → :rf/default. CLJS-side
  re-frame.views extends this with a React-context lookup."
  []
  (or *current-frame* :rf/default))

;; Per Spec 009 §Per-frame trace rings (rf2-g1b2m / rf2-8uwce): publish
;; the in-flight frame-id through `late-bind` so the trace tooling
;; sibling can route emit-site trace events to their owning frame's
;; ring. Returns nil when no cascade is in flight (frameless emits).
;; The hook is sticky (rf2-f72pd) and read on every push-to-ring!.
(late-bind/set-fn! :frame/current-frame-id (fn [] *current-frame*))

(defn resolve-current-frame
  "Resolve the active frame at a no-explicit-frame call site. The
  3-tier resolution chain — dynamic var → React context → `:rf/default` —
  per Spec 002 §Reading the frame from React context and Spec 006
  §Lookup algorithm.

  On CLJS this consults the `:adapter/current-frame` late-bind hook
  so the React-context tier is LIVE — adapters publish their React-
  context-aware impl through the hook at ns-load time. When the hook
  is unbound (no adapter loaded yet, or JVM build) the fallback is
  `current-frame` which honours the dynamic-var tier and the
  `:rf/default` tier; the React-context tier silently no-ops.

  This is the canonical 3-tier resolver — `subs/subscribe`,
  `router/dispatch*`'s default-frame computation, and
  `core/current-frame-id` all delegate here so the React-context tier is
  single-sourced (rf2-jj8xf)."
  []
  ;; Sticky hook (rf2-f72pd) — `:adapter/current-frame` is published
  ;; once per loaded React-shaped adapter at ns-load time and routed
  ;; via `current-adapter`; it fires on every default-frame resolution
  ;; (every dispatch and every subscribe).
  #?(:cljs (if-let [f (late-bind/get-fn-cached :adapter/current-frame)]
             (f)
             (current-frame))
     :clj  (current-frame)))

;; ---- lookup ---------------------------------------------------------------

(defn frame
  "Return the frame record for id, or nil if not registered or destroyed.

  2-level lookup written as keyword-invoke (`(-> f :lifecycle :destroyed?)`)
  rather than `(get-in f [:lifecycle :destroyed?])` — `get-in` allocates
  a path vector per call (rf2-mqv4m), and `frame` runs on every dispatch
  / subscribe through `current-frame` resolution."
  [id]
  (when-let [f (get @frames id)]
    (when-not (-> f :lifecycle :destroyed?)
      f)))

(defn frame-disposed-for-drain?
  "Per Spec 002 §Frame disposal mid-drain: predicate used by the
  router's drain loop to interrupt a pass when the frame was destroyed
  mid-cycle. True when EITHER:

    (a) The frame record still exists but `:destroyed?` is flipped
        (post-step-3 of `destroy-frame!`, before step-6 dissoc), OR
    (b) The frame record is absent from the `frames` atom (post-step-6
        of `destroy-frame!` — the dissoc step has run).

  Returns false when `id` is registered and not destroyed. Calling for
  a never-registered `id` returns true — that case is benign for the
  drain-loop caller (a drain cannot run on a frame that was never
  registered), but the predicate is named `*-for-drain?` to make the
  intended seam explicit and avoid suggesting general
  destroyed-vs-never-registered discrimination."
  [id]
  (if-let [f (get @frames id)]
    (true? (-> f :lifecycle :destroyed?))
    ;; Absent from the atom — destroy-frame!'s step 6 ran, OR the id
    ;; was never registered. The drain-loop caller only consults this
    ;; while a pass is already in flight, so the latter case cannot
    ;; arise from that seam.
    true))

(defn frame-meta
  "Per Spec 002 §The public registrar query API and Spec-Schemas
  §`:rf/frame-meta`: return the effective metadata map for a frame as a
  flat shape — `:id` plus the post-preset-expansion user-supplied
  metadata keys (`:preset`, `:fx-overrides`, `:drain-depth`, `:doc`,
  `:tags`, `:url-bound?`, `:platform`, `:on-error`, `:ssr`, …) merged
  with the lifecycle fields (`:created-at`, `:destroyed?`, `:listeners`).

  Per Spec 002 §Frame presets, the `:preset` key is preserved verbatim
  on the returned map so tools can inspect which preset was applied; the
  expansion keys appear at the top level alongside it. The internal
  storage groupings (`:config` / `:lifecycle` on the frame record) are
  flattened away — tools must not depend on the registry's storage
  organisation, only on the canonical `:rf/frame-meta` shape."
  [id]
  (when-let [f (frame id)]
    (merge (:config f)
           (:lifecycle f)
           {:id (:id f)})))

(defn frame-ids
  "All registered, non-destroyed frame ids.

  Two arities:
    (frame-ids)
      Return the full id set.
    (frame-ids ns-prefix)
      Return the subset whose id-namespace starts with `ns-prefix`
      (a string). Namespaceless ids (e.g. `:rf/default`'s namespace is
      `\"rf\"` — keyword-namespace, not value-namespace) are matched
      against the keyword's `namespace` component; ids with no
      namespace are excluded.

  Per Spec 002 §The public registrar query API."
  ([]
   (into #{}
         (comp (filter (fn [[_ f]] (not (-> f :lifecycle :destroyed?))))
               (map key))
         @frames))
  ([ns-prefix]
   (let [prefix (str ns-prefix)]
     (into #{}
           (comp (filter (fn [[_ f]] (not (-> f :lifecycle :destroyed?))))
                 (map key)
                 (filter (fn [k]
                           (when-let [ns (namespace k)]
                             (clojure.string/starts-with? ns prefix)))))
           @frames))))

(defn frame-state-container
  "Return the frame's ONE physical frame-state **container** — the
  substrate-managed reactive cell that holds the frame-state VALUE
  `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}` (an `r/atom` under
  the stock Reagent adapter, a `clojure.core/atom` under plain-atom /
  React-hook adapters). This is the single physical write target; every
  durable state write flows through it via `commit-frame-transition!` /
  the partition mutators.

  Internals only: the router commit path and the partition write helpers
  call `replace-container!` against this cell. App-db and runtime-db are
  READ-ONLY projection reactions over it (`app-db-container` /
  `runtime-db-container`).

  Returns `nil` when the frame is not registered or has been destroyed.
  Per Spec 002 §One physical container, two projection reactions and
  Spec 006 §Frame-state container and partition projections."
  [id]
  (:frame-state (frame id)))

(defn app-db-container
  "Return the app-db **projection reaction** for the frame — the read-only
  derived value `(make-derived-value [frame-state] :rf.db/app)` over the
  one physical frame-state container. Layer-1 app subs read it as their
  signal source, so the subscription machinery only ever sees app-db (the
  partition split is invisible to the invalidation algorithm — a
  runtime-only commit recomputes this projection, finds `:rf.db/app` `=`,
  and does not propagate). Distinct from `frame-state-container`, the
  writable physical cell.

  READ-ONLY: this is a `make-derived-value` result, so
  `adapter/replace-container!` on it throws `:rf.error/derived-container-
  replaced` (per Spec 006 §`make-derived-value`). App-db writes go through
  `swap-frame-db!` / `replace-app-db!` / `commit-frame-transition!`, which
  write the app-db partition of the physical frame-state container.

  Distinct from `re-frame.core/app-db-value`, which returns the deref'd
  app-db **value** (a plain map). User handlers receive `db` via cofx.

  Returns `nil` when the frame is not registered or has been destroyed.
  Per Spec 002 §One physical container, two projection reactions."
  [id]
  (:app-db (frame id)))

(defn runtime-db-container
  "Return the runtime-db **projection reaction** for the frame — the
  read-only derived value `(make-derived-value [frame-state] :rf.db/runtime)`
  over the one physical frame-state container. Framework subs
  (`sub-machine`, `[:rf.route/*]`) read it as their signal source; an
  app-only commit leaves `:rf.db/runtime` `=`, so the projection does not
  propagate and framework subs are untouched.

  READ-ONLY (a derived value); runtime-db writes go through
  `replace-runtime-db!` / `commit-frame-transition!`, which write the
  runtime-db partition of the physical frame-state container.

  Returns `nil` when the frame is not registered or has been destroyed.
  Per Spec 002 §One physical container, two projection reactions."
  [id]
  (:runtime-db (frame id)))

(defn frame-app-db-value
  "Read the current app-db value for a frame as a plain map (deref the
  app-db projection through the substrate adapter)."
  [id]
  (when-let [container (app-db-container id)]
    (adapter/read-container container)))

;; ---- EP-0001 two-partition readers (rf2-q4i9ko / rf2-adwcv6) --------------
;;
;; Per Spec 002 §The two-partition frame contract a frame owns two durable
;; partitions — user `app-db` and framework `runtime-db` — projected as a
;; coherent `frame-state` value `{:rf.db/app … :rf.db/runtime …}`.
;;
;; rf2-q4i9ko (bead 3) introduced the read SURFACE; rf2-adwcv6 (bead 5, this
;; one) makes the physical one-container frame-state + projection reactions
;; real, so `frame-runtime-db-value` now reads the live runtime-db partition.

(defn frame-runtime-db-value
  "Read the current runtime-db partition value for a frame — the
  framework-owned subsystem state. Returns `nil` for an unknown / destroyed
  frame.

  rf2-adwcv6 (bead 5): reads the real `:rf.db/runtime` partition off the one
  physical frame-state container (via the runtime-db projection). A fresh
  frame's runtime-db starts `{}`. Per Spec 002 §The two-partition frame
  contract."
  [id]
  (when-let [container (runtime-db-container id)]
    (adapter/read-container container)))

(defn frame-state-value
  "Read the coherent frame-state projection for a frame —
  `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`. Returns `nil` for an
  unknown / destroyed frame.

  rf2-adwcv6 (bead 5): reads the one physical frame-state container directly
  (a single deref) rather than composing two reads, so the returned value is
  the exact coherent snapshot the commit installed. Per Spec 002 §The
  two-partition frame contract."
  [id]
  (when-let [container (frame-state-container id)]
    (adapter/read-container container)))

;; ---- EP-0001 partition commit + write helpers (rf2-adwcv6) ----------------
;;
;; The frame-state container is the ONE physical write target. Every durable
;; state write — the router's per-event commit, the privileged runtime
;; mutators, full-frame tool install — flows through `replace-container!` on
;; it. Per Spec 002 §An ordinary :db return replaces only app-db + §Write
;; authority is by convention, and Spec 006 §Commit boundary.

(defn commit-frame-transition!
  "Atomically install a frame transition into the ONE physical frame-state
  container (Spec 002 §Drain-loop pseudocode §commit; Spec 006 §Commit
  boundary). `partitions` is a map that MAY carry `:rf.db/app` (the new
  app-db value — the ordinary `:db` effect, scoped to the app-db partition)
  and/or `:rf.db/runtime` (the new runtime-db value — the reserved
  `:rf.db/runtime` effect). The partition(s) NOT present are carried forward
  unchanged from the current frame-state, so:

    - an APP-ONLY commit (`{:rf.db/app v}`) replaces only the app-db slice;
      runtime-db is untouched — the handler cannot drop it through `:db`;
    - a RUNTIME-ONLY commit (`{:rf.db/runtime v}`) replaces only runtime-db;
    - a commit touching BOTH installs the combined result as ONE coherent
      transition — there is never a window where one partition is committed
      and the other is not.

  Returns the SET of partition keys that actually changed by `=` (a subset
  of `#{:rf.db/app :rf.db/runtime}`) — the caller uses it to drive the
  partition-tagged change traces (`:rf.event/db-changed` /
  `:rf.event/frame-state-changed`). A no-op partition (the supplied value
  `=` the current slice) is NOT reported as changed, so the projection
  reactions and the change signals agree. Returns `nil` for an unknown /
  destroyed frame (the nil-container guard in `replace-container!` also
  covers the destroy-race when called through it).

  NOTE the `partitions` map keys are the frame-state partition keys
  (`:rf.db/app` / `:rf.db/runtime`), NOT the effect keys (`:db` /
  `:rf.db/runtime`) — the router maps `:db` effect → `:rf.db/app` partition
  before calling this."
  [id partitions]
  (when-let [container (frame-state-container id)]
    (let [current   (adapter/read-container container)
          app-given? (contains? partitions app-partition-key)
          rt-given?  (contains? partitions runtime-partition-key)
          next-app  (if app-given? (get partitions app-partition-key)
                        (get current app-partition-key))
          next-rt   (if rt-given? (get partitions runtime-partition-key)
                        (get current runtime-partition-key))
          next-fs   {app-partition-key     next-app
                     runtime-partition-key next-rt}
          changed   (cond-> #{}
                      (and app-given?
                           (not= next-app (get current app-partition-key)))
                      (conj app-partition-key)
                      (and rt-given?
                           (not= next-rt (get current runtime-partition-key)))
                      (conj runtime-partition-key))]
      ;; ONE atomic frame-state install — both partitions in one write, per
      ;; Spec 006 §Commit boundary. Even a no-op (changed empty) re-installs
      ;; the equal value; the projection reactions' `=`-memoisation collapses
      ;; the downstream notification so a value-equal commit costs nothing.
      (adapter/replace-container! container next-fs)
      changed)))

(defn replace-app-db!
  "Replace ONLY the app-db partition of `id`'s frame-state, leaving
  runtime-db untouched (Spec 002 §Frame-state value accessors and mutators,
  Mike ruling #1 / #10 — a db-shaped name never silently replaces
  runtime-db). Atomic install through the one physical container. Returns
  the set of changed partition keys, or `nil` for an unknown / destroyed
  frame. Internal write boundary used by the Tool-Pair `replace-app-db!` /
  epoch `replace-app-db!` path."
  [id app-db]
  (commit-frame-transition! id {app-partition-key app-db}))

(defn replace-runtime-db!
  "Replace ONLY the runtime-db partition of `id`'s frame-state, leaving
  app-db untouched (Spec 002 §Frame-state value accessors and mutators).
  The privileged runtime / full-frame write surface. Atomic install through
  the one physical container. Returns the set of changed partition keys, or
  `nil` for an unknown / destroyed frame."
  [id runtime-db]
  (commit-frame-transition! id {runtime-partition-key runtime-db}))

(defn replace-frame-state!
  "Replace BOTH partitions of `id` atomically with `frame-state`
  (`{:rf.db/app … :rf.db/runtime …}`) — the full-frame install for
  tool-driven replay / fixture install (epoch restore, time travel, SSR
  hydration, frame reset). A db-shaped name never silently replaces
  runtime-db; this is the explicit full-frame surface (Mike ruling #10).
  Both partitions install in ONE atomic write. Returns the set of changed
  partition keys, or `nil` for an unknown / destroyed frame.

  `frame-state` MUST carry both partition keys; a missing key installs
  `nil` for that partition (a full-frame replace is whole-value by
  contract). Use `replace-app-db!` / `replace-runtime-db!` for a
  single-partition write."
  [id frame-state]
  (commit-frame-transition! id {app-partition-key     (get frame-state app-partition-key)
                                runtime-partition-key (get frame-state runtime-partition-key)}))

(defn swap-frame-db!
  "Mutate the frame's app-db PARTITION: read the current app-db value,
  compute `(apply f db args)`, and install the result into the app-db
  partition of the one physical frame-state container (runtime-db
  untouched). Returns the new app-db, or nil if the frame is not registered.

  Models `swap!` over the app-db partition. Under the single-drainer
  invariant (Spec 002 §Single drainer per frame) the read-then-replace is
  effectively atomic — `commit-frame-transition!` is the only writer during
  fx drain. The helper is the canonical \"mutate the frame's app-db\"
  surface; the read / partition-commit dance belongs here, not at every
  fx-handler call site.

  EP-0001 (rf2-adwcv6): now writes the app-db partition of the physical
  frame-state container (was a direct `replace-container!` on the old app-db
  store). The machine / routing / SSR `:rf/runtime`-under-app-db writers that
  call this keep working unchanged — `:rf/runtime` still lives inside app-db
  until bead 6 (rf2-vzld77) migrates them to runtime-db."
  [id f & args]
  (when-let [container (frame-state-container id)]
    (let [current (adapter/read-container container)
          old-db  (get current app-partition-key)
          new-db  (apply f old-db args)]
      (adapter/replace-container! container
                                  (assoc current app-partition-key new-db))
      new-db)))

(defn swap-runtime-db!
  "Mutate the frame's runtime-db PARTITION: read the current runtime-db
  value, compute `(apply f runtime-db args)`, and install the result into the
  runtime-db partition of the one physical frame-state container (app-db
  untouched). Returns the new runtime-db, or nil if the frame is not
  registered.

  The runtime-db sibling of `swap-frame-db!` — the canonical \"mutate the
  frame's runtime-db\" surface for framework subsystems' direct (out-of-
  cascade / mid-fx) writes (machine spawn / destroy / update-snapshot,
  routing scroll/can-leave fx). Models `swap!` over the runtime-db partition;
  under the single-drainer invariant (Spec 002 §Single drainer per frame) the
  read-then-replace is effectively atomic. Per Spec 002 §The two-partition
  frame contract — runtime-db is reserved BY CONVENTION (decision #4); this
  is the framework-authority write surface."
  [id f & args]
  (when-let [container (frame-state-container id)]
    (let [current        (adapter/read-container container)
          old-runtime-db (get current runtime-partition-key)
          new-runtime-db (apply f old-runtime-db args)]
      (adapter/replace-container! container
                                  (assoc current runtime-partition-key new-runtime-db))
      new-runtime-db)))

;; ---- lifecycle-vs-drain serialization (rf2-2woz9) -------------------------
;;
;; Some per-frame registry mutations must be ATOMIC with respect to that
;; frame's event drain — they read-modify-write shared registry state AND
;; app-db, and a concurrent drain that interleaves between the steps can
;; observe a half-applied lifecycle change. The flows artefact has two such
;; ops (rf2-2woz9):
;;
;;   - `clear-flow` vacates the output path THEN removes the flow from the
;;     registry. A drain that starts in that window still sees the flow,
;;     recomputes it, and re-commits the output that clear-flow already
;;     vacated — leaving stale derived state no live flow maintains.
;;   - `reg-flow` replacement publishes the new flow into the registry
;;     (visible to the drain) BEFORE the registrar replacement-hook drops
;;     the stale `last-inputs` row. A drain in that window sees the new flow
;;     with the OLD input cache and skips recompute on `=`-equal inputs.
;;
;; The frame's `:drain-lock` is the existing single-drainer serialization
;; primitive (the router CAS-acquires it for the whole drain pass — see
;; `re-frame.router/drain-loop!`). `call-serialized-with-drain!` runs `f`
;; under that lock so the lifecycle mutation is mutually exclusive with any
;; concurrent drain, closing the windows above with ONE mechanism rather
;; than per-op reordering / token threading (which would touch the hot
;; dirty-check path). The drain path itself is untouched — it still just
;; CAS-acquires the lock as before; only the cold lifecycle ops now contend
;; for it.
;;
;; REENTRANCY is the load-bearing subtlety. `clear-flow` / `reg-flow` can be
;; invoked MID-DRAIN via the `:rf.fx/clear-flow` / `:rf.fx/reg-flow` effects
;; (do-fx runs inside the drain pass, on the draining thread, which already
;; holds `:drain-lock`). A naive acquire would deadlock the drainer against
;; a lock it itself holds. So we first ask the router whether THIS thread is
;; the frame's active drainer (the same `:in-drain?` thread marker the
;; `dispatch-sync` nesting guard reads): if so we are already inside the
;; single-drainer window and run `f` directly; only a DIFFERENT thread (or a
;; non-drain call site) acquires the lock. On CLJS — single-threaded — the
;; marker is `true`/`nil` and the same equality discriminates; an
;; uncontended top-level call CAS-acquires the false lock on the first try.

(defn- current-thread-is-drainer?
  "True when the calling thread is the frame's currently-active drainer.
  Reads the router's `:in-drain?` marker (stamped by
  `re-frame.router/mark-drainer!` to the drainer thread on JVM, `true` on
  CLJS). The flows lifecycle ops use this to take the reentrant fast-path
  when invoked mid-drain via `:rf.fx/reg-flow` / `:rf.fx/clear-flow` — they
  are already inside the single-drainer window, so re-taking `:drain-lock`
  would self-deadlock."
  [frame-record]
  (let [in-drain (:in-drain? @(:router frame-record))]
    #?(:clj  (identical? in-drain (Thread/currentThread))
       :cljs (true? in-drain))))

(defn call-serialized-with-drain!
  "Run thunk `f` serialized against `frame-id`'s event drain, returning its
  value (rf2-2woz9). Used by per-frame registry mutations that must not
  interleave with a concurrent `run-flows-on-db` pass.

  - Frame absent (unregistered / destroyed): nothing can be draining it, so
    just run `f`.
  - Calling thread is the frame's active drainer (mid-drain `:rf.fx/*`
    call): already inside the single-drainer window — run `f` directly to
    avoid self-deadlocking on `:drain-lock`.
  - Otherwise: spin-CAS-acquire `:drain-lock` (the same acquire shape
    `re-frame.router/drain-block!` uses — bounded wait: an active drainer
    holds it for at most `drain-depth` events), run `f`, release in a
    `finally`."
  [frame-id f]
  (if-let [frame-record (frame frame-id)]
    (if (current-thread-is-drainer? frame-record)
      (f)
      (let [drain-lock (:drain-lock frame-record)]
        (loop []
          (when-not (compare-and-set! drain-lock false true)
            #?(:clj (Thread/yield))
            (recur)))
        (try
          (f)
          (finally
            (reset! drain-lock false)))))
    (f)))

;; ---- frame presets (Spec 002 §Frame presets) ------------------------------
;;
;; A :preset key in metadata expands at registration time into a fixed
;; bundle of metadata keys. User-supplied keys win on conflict.
;; Per Spec 002 §Frame presets, the v1 closed list is:
;;   :default :test :story :ssr-server

(defn- preset-expansion [preset]
  ;; Per Spec 002 §Frame presets and Spec-Schemas §:rf/preset-expansion.
  ;; The four canonical expansions:
  ;;   :default    -> {} (explicit no-op; identical to omitting :preset)
  ;;   :test       -> redirect :rf.http/managed to its canned-success stub
  ;;                  (Spec 014); explicit :drain-depth 100 (matches the
  ;;                  framework default — surfaced so tooling can read the
  ;;                  bound off frame-meta without consulting the global default).
  ;;   :story      -> same HTTP redirect as :test; tighter :drain-depth 16
  ;;                  so a runaway dispatch cascade fails fast under a story.
  ;;   :ssr-server -> :platform :server (gates fx via reg-fx :platforms);
  ;;                  :on-error :rf.error/server-projection (server-side
  ;;                  exception projection per Spec 011).
  ;; User-supplied keys win on conflict; see expand-preset.
  ;;
  ;; rf2-cdmle — the :test / :story redirect targets
  ;; `:rf.http/managed-canned-success`, which registers from the test-
  ;; support namespace `re-frame.http-test-support`. Apps that use these
  ;; presets must `:require [re-frame.http-test-support]` (alongside
  ;; `re-frame.http-managed`) so the redirect target resolves. Production
  ;; / SSR code paths use `:default` / `:ssr-server` and never reach this
  ;; branch.
  (case preset
    :default    {}
    :test       {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}
                 :drain-depth  100}
    :story      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}
                 :drain-depth  16}
    :ssr-server {:platform     :server
                 :on-error     :rf.error/server-projection}
    nil         {}
    (throw (ex-info ":rf.error/unknown-preset"
                    {:preset preset
                     :valid  #{:default :test :story :ssr-server}}))))

(defn- expand-preset [metadata]
  (let [preset    (:preset metadata)
        expansion (preset-expansion preset)]
    ;; user-supplied keys win on conflict
    (merge expansion metadata)))

;; ---- registration ---------------------------------------------------------

(defn- new-frame-record [id config]
  ;; ONE physical frame-state container holding both partitions (Spec 002
  ;; §One physical container, two projection reactions; EP-0001 decision #3).
  ;; A fresh frame starts with an empty app-db (Spec 002 §Frames always start
  ;; with app-db = {}) and an empty runtime-db.
  (let [frame-state (adapter/make-state-container {app-partition-key     {}
                                                   runtime-partition-key {}})]
   {:id          id
    :frame-state frame-state
    ;; app-db / runtime-db are READ-ONLY projection reactions over the one
    ;; physical container — `make-derived-value` memoises on `=`, so a
    ;; runtime-only commit does not propagate to app subs (and vice versa),
    ;; with no dirty flags (decision #7). The compute-fn is the bare keyword
    ;; lookup of the partition slice; `make-derived-value`'s recompute closure
    ;; arity-specialises the 1-source case so the projection costs a single
    ;; keyword invoke per recompute.
    :app-db      (adapter/make-derived-value [frame-state] app-partition-key)
    :runtime-db  (adapter/make-derived-value [frame-state] runtime-partition-key)
    :router      (atom {:queue interop/empty-queue :scheduled? false})
   ;; Single-drainer invariant: a separate CAS-able cell that admits
   ;; at most one thread into `drain!` at a time. On the JVM the
   ;; executor's `next-tick` callback can wake while the calling
   ;; thread is mid-drain (e.g. `dispatch-sync!`); without this guard,
   ;; both threads' peek+pop sequence on `:queue` is non-atomic and
   ;; double-processes / drops envelopes. The loser of the CAS no-ops;
   ;; the winning drainer rechecks the queue before releasing the
   ;; flag so envelopes queued in the gap are not orphaned. CLJS is
   ;; single-threaded so the CAS is uncontended there, but the same
   ;; flag preserves the contract under any future concurrent host.
   :drain-lock (atom false)
    :sub-cache  (atom {})
    :lifecycle  {:created-at (interop/now-ms)
                 :destroyed? false
                 :listeners  []}
    :config     config}))

(declare destroy-frame!)

(defn reg-frame
  "Atomic create-and-register. Per Spec 002 §reg-frame is atomic:
  - If the id is unregistered, create the frame container, run :on-create
    events synchronously, return the keyword.
  - If the id is already registered, perform a SURGICAL UPDATE: existing
    runtime state (app-db, sub-cache, queue) is preserved; only the
    metadata/config is replaced. Hot-reload Just Works."
  [id metadata]
  (let [config (source-coords/merge-coords (expand-preset metadata))]
    (registrar/register! :frame id config)
    ;; Frame-level trace-emission gate (rf2-2qaqh): a frame registered
    ;; with `:rf.trace/frame-no-emit? true` is a tool / inspector frame
    ;; (e.g. Xray's `:rf/xray`) whose own reactive substrate must NOT
    ;; flood the shared trace ring it inspects. The flag is the frame-
    ;; scoped sibling of the handler-scoped `:rf.trace/no-emit?`
    ;; (Spec 009 §Trace-emission opt-out). Honoured on BOTH first
    ;; registration and re-registration so a hot-reload can flip it
    ;; either way; `trace.cljc` owns the canonical set + predicate.
    (trace/set-frame-no-emit! id (true? (:rf.trace/frame-no-emit? config)))
    ;; Per Spec 009 §Retention contract (rf2-g1b2m / rf2-8uwce): apply
    ;; the per-frame `:rf.trace/cascades-retained` override at
    ;; registration time. Honoured on BOTH first registration and re-
    ;; registration so a hot-reload can flip it either way. When the
    ;; key is absent the frame inherits the process-default. Routed via
    ;; late-bind so production CLJS bundles (where trace.tooling is
    ;; not loaded) short-circuit cleanly — the trace-ring machinery is
    ;; dev-only and there's nothing to configure in prod.
    (when (contains? config :rf.trace/cascades-retained)
      (when-let [set-retained! (late-bind/get-fn-cached
                                :trace.tooling/set-frame-cascades-retained!)]
        (set-retained! id (:rf.trace/cascades-retained config))))
    (let [existing (get @frames id)]
      (cond
        ;; First registration: create everything.
        (nil? existing)
        (let [f (new-frame-record id config)]
          (swap! frames assoc id f)
          ;; Run :on-create events BEFORE emitting :frame/created
          ;; (Spec 002 §Frame creation). The router/dispatch ns is
          ;; reached through late-bind to avoid a cyclic dep at
          ;; compile time.
          ;;
          ;; Per Spec 002 §`reg-frame` / `make-frame` called from inside
          ;; a handler: when a handler creates a child frame mid-
          ;; cascade, the child's `:on-create` MUST be async-queued
          ;; (not dispatch-sync'd) — synchronous dispatch-sync from
          ;; inside a handler is an error, and even were it permitted
          ;; the two cascades would interleave (forbidden by the no-
          ;; cross-frame-drain rule in Spec 002 §Run-to-completion).
          ;; The signal for "inside a handler" is `*current-frame*`
          ;; being bound — the router binds it in `process-event!`
          ;; for the duration of the cascade.
          ;; Per rf2-hxj0d: stamp the frame-init dispatch with
          ;; `:source :frame-init` so the Epoch panel's DISPATCH step
          ;; renders "from frame-init" instead of being mislabelled
          ;; via the previous `:ui` default. Additionally, capture the
          ;; `reg-frame` call-site coord as `:rf.trace/call-site` so
          ;; the click-to-source affordance jumps to the
          ;; `(rf/reg-frame :foo {:on-create [...]})` line. The macro
          ;; form of `reg-frame` (via `defreg-macro`) binds
          ;; `*pending-coords*`, which `source-coords/merge-coords`
          ;; merges directly INTO `config` as `:ns`/`:file`/`:line`/
          ;; `:column` — so the call-site is already on the config
          ;; map, no separate capture path needed. Gated on
          ;; `interop/debug-enabled?` so production CLJS builds DCE
          ;; the call-site read.
          (when-let [on-create (:on-create config)]
            (let [init-opts (cond-> {:frame  id
                                     :source :frame-init}
                              (and interop/debug-enabled?
                                   (or (:file config) (:line config)))
                              (assoc :rf.trace/call-site
                                     (cond-> {}
                                       (:ns     config) (assoc :ns     (:ns     config))
                                       (:file   config) (assoc :file   (:file   config))
                                       (:line   config) (assoc :line   (:line   config))
                                       (:column config) (assoc :column (:column config)))))]
              (if *current-frame*
                ;; Handler-created child frame: async-queue on the child.
                (when-let [dispatch (late-bind/get-fn :router/dispatch!)]
                  (dispatch on-create init-opts))
                ;; Top-level (no in-flight cascade): synchronous, as before.
                (when-let [dispatch-sync (late-bind/get-fn :router/dispatch-sync!)]
                  (dispatch-sync on-create init-opts)))))
          (trace/emit! :rf.frame :rf.frame/created
                       {:frame id :config config})
          id)

        ;; Re-registration: surgical update of replaceable slots only.
        ;; Per Spec 002 §Re-registration — surgical update.
        :else
        (do
          (swap! frames update id assoc :config config)
          (trace/emit! :rf.frame :rf.frame/re-registered
                       {:frame id :config config})
          id)))))

(defn make-frame
  "Anonymous-instance creation. Generates a gensym'd id under :rf.frame/.
  Returns the gensym'd id. Per Spec 002 §Per-instance frames."
  [config]
  (let [id (keyword "rf.frame" (str (gensym "")))]
    (reg-frame id config)
    id))

;; ---- destruction ----------------------------------------------------------
;;
;; destroy-frame! runs an ordered teardown. Each step lives in its own
;; named helper so the body of destroy-frame! reads as a step list. Order
;; matters — see destroy-frame!'s docstring for the authoritative recipe.

;; Frame id of the in-flight `destroy-frame!`, bound for the duration of
;; the teardown so `safe-call-hook!` can stamp `:frame` on a hook-failure
;; diagnostic regardless of the hook's arg shape (the cache-reset hooks
;; take no frame arg). Per rf2-x3m8c.
(def ^:dynamic *destroying-frame-id* nil)

;; Pre-cascade frame-state snapshot of the in-flight dequeued event, bound by
;; the router around `process-event!` (see `re-frame.router/run-one-pass!`).
;; A handler that calls `destroy-frame!` on its own frame mid-drain runs
;; INSIDE that binding, so `destroy-frame!` can recover the whole frame-state
;; (both partitions) held BEFORE the in-flight event's cascade began — the
;; `:frame-state-before` slot the `:halted-destroy` epoch record carries per
;; Spec-Schemas §`:rf/epoch-record` §Outcomes (rf2-9neiq). EP-0001
;; (rf2-3aizt1, decision #2): the canonical snapshot unit is the whole
;; frame-state; the epoch derives `:db-before` from its app-db projection.
;; nil outside a drain (an out-of-cascade `destroy-frame!` — hot-reload,
;; `reset-frame!`, REPL — commits no `:halted-destroy` record, so the slot
;; is moot there).
(def ^:dynamic *cascade-frame-state-before* nil)

(defn- safe-call-hook!
  "Fire a late-bound cleanup hook by key. No-op when unbound. Exceptions
  are caught so one bad hook can't block the rest of teardown — but the
  failure is NOT silent (rf2-x3m8c): before continuing we emit a
  `:rf.warning/teardown-hook-exception` trace carrying the hook key, the
  in-flight frame id (when known via `*destroying-frame-id*`), and the
  exception, so a leaked optional-artefact cleanup (stale schemas, flow
  rows, side-channel atoms, trace rings) leaves a causal breadcrumb in
  long-lived SSR / test / tooling processes. Best-effort teardown
  semantics are preserved — the throw is swallowed and teardown
  continues. The emit rides `interop/debug-enabled?` (inside
  `trace/emit-error!`) so production CLJS bundles DCE it."
  [hook-key & args]
  (when-let [f (late-bind/get-fn hook-key)]
    (try (apply f args)
         (catch #?(:clj Throwable :cljs :default) ex
           (trace/emit-error! :rf.warning/teardown-hook-exception
                              {:category  :rf.warning/teardown-hook-exception
                               :hook      hook-key
                               :frame     *destroying-frame-id*
                               :exception ex
                               :where     :safe-call-hook!})
           nil))))

(defn- fire-on-destroy-event!
  "Run the user-supplied `:on-destroy` event synchronously, then continue
  teardown regardless of outcome. Per Spec 002 §Destroy — `:on-destroy`
  handler throw semantics (rf2-r1ciy decision b): a throw from the user's
  handler MUST NOT abort teardown. Emit `:rf.error/on-destroy-handler-exception`
  via `trace/emit-error!` and continue — every downstream step
  (machine cascade, sub-cache disposal, cleanup hooks, `:frame/destroyed`,
  registry dissoc) MUST still run so the frame is fully torn down.

  Mechanism: the router catches handler throws and converts them to
  `:rf.error/handler-exception` traces — `dispatch-sync!` does not re-
  throw. To surface the throw as the dedicated `:rf.error/on-destroy-
  handler-exception` category (Mike's decision), we observe the trace
  stream for the duration of the dispatch: any `:rf.error/handler-
  exception` whose `:frame` matches us is captured and re-emitted under
  the new category. We also wrap the dispatch itself in try/catch as a
  defence-in-depth: if `dispatch-sync!` ever re-throws (e.g. a fault
  inside the dispatch infrastructure itself, not the user handler),
  we catch it here.

  This mirrors the swallow-then-continue shape of `safe-call-hook!` below
  but ALSO emits a structured error trace (where `safe-call-hook!` is
  silent) — the user's `:on-destroy` is application code; its failure
  is a first-class diagnostic event."
  [id f]
  (when-let [on-destroy (-> f :config :on-destroy)]
    (when-let [dispatch-sync (late-bind/get-fn :router/dispatch-sync!)]
      (let [captured (atom nil)
            ;; The trace-buffer / listener registry lives in the optional
            ;; trace.tooling sibling per rf2-qwm0a. Reach it through
            ;; late-bind so this fn carries no static dep on the tooling
            ;; ns; in production CLJS builds where trace.tooling is not
            ;; loaded, the listener install is a silent no-op (no trace
            ;; surface to observe, no trace to re-emit).
            register   (late-bind/get-fn :trace.tooling/register-listener!)
            remove-cb  (late-bind/get-fn :trace.tooling/unregister-listener!)
            listener-k ::on-destroy-throw-watch
            listener   (fn [ev]
                         (when (and (= :rf.error/handler-exception (:operation ev))
                                    (= id (-> ev :tags :frame))
                                    (nil? @captured))
                           (reset! captured ev)))]
        (when (and register remove-cb)
          (register listener-k listener))
        (try
          (try
            (dispatch-sync on-destroy {:frame id})
            (catch #?(:clj Throwable :cljs :default) ex
              ;; Defence-in-depth: dispatch-sync! normally swallows
              ;; handler throws, but if the dispatch infrastructure
              ;; itself fails we still emit the dedicated category.
              (trace/emit-error! :rf.error/on-destroy-handler-exception
                                 {:frame     id
                                  :event     on-destroy
                                  :exception ex
                                  :where     :fire-on-destroy-event!})))
          (finally
            (when (and register remove-cb)
              (remove-cb listener-k))))
        ;; If the router converted a handler throw to a trace, re-emit
        ;; under the dedicated :on-destroy category so consumers can
        ;; discriminate teardown failures from regular handler throws.
        (when-let [ev @captured]
          (let [tags (:tags ev)]
            (trace/emit-error! :rf.error/on-destroy-handler-exception
                               {:frame             id
                                :event             on-destroy
                                :exception         (:exception tags)
                                :exception-message (:exception-message tags)
                                :where             :fire-on-destroy-event!})))))))

(defn- notify-machine-destruction!
  "Frame-destroy machine-cascade entry-point.

  Per rf2-vsigt — Spec 005 §Cross-Spec Interactions §1: when the
  machines artefact is loaded, delegate the full cascade
  (reverse-creation walk, per-machine `:exit` cascade, HTTP abort,
  unified teardown projection, system-id release, handler unregister)
  to the late-bind hook `:machines/teardown-on-frame-destroy!`. The
  hook is published by `re-frame.machines` so core never statically
  requires the optional machines artefact.

  Fallback (no machines artefact on the classpath): preserve the
  legacy minimal behaviour — fire the `:http/abort-on-actor-destroy`
  hook per snapshot key and emit `:rf.machine.lifecycle/destroyed`
  with `:reason :parent-frame-destroyed`. Without the machines
  artefact there are no live `:exit` cascades to run, no actor
  handlers to unregister, and no system-id reverse index to release."
  [id]
  (if-let [teardown! (late-bind/get-fn :machines/teardown-on-frame-destroy!)]
    (teardown! id)
    ;; Fallback path — minimal contract when the machines artefact is absent.
    ;; EP-0001 (rf2-vzld77): machine snapshots are durable runtime-db state.
    (let [container  (runtime-db-container id)
          rt         (when container (adapter/read-container container))
          machines   (get-in rt [:rf.runtime/machines :snapshots])
          abort-http (late-bind/get-fn :http/abort-on-actor-destroy)]
      (doseq [[machine-id snapshot] machines]
        (when abort-http
          (try (abort-http machine-id)
               (catch #?(:clj Throwable :cljs :default) _ nil)))
        (trace/emit! :rf.machine.lifecycle/destroyed :rf.machine.lifecycle/destroyed
                     {:frame      id
                      :machine-id machine-id
                      :last-state (:state snapshot)
                      :reason     :parent-frame-destroyed})))))

(defn- mark-frame-destroyed!
  [id]
  (swap! frames update id assoc-in [:lifecycle :destroyed?] true))

(defn- tear-down-sub-cache!
  "Dispose every cached subscription reaction for the destroyed frame.

  Per rf2-x3m8c: route through the sub-cache-owned
  `:subs.cache/dispose-all-for-frame-destroy!` hook so each eviction
  emits a `:rf.sub/dispose` trace (reason `:frame-destroy`) — frame
  teardown is a real eviction class and MUST appear in the sub-cache
  lifecycle stream like `unsubscribe` / hot-reload / `clear-sub-cache!`
  do (the bypass that disposed reactions directly was invisible to
  tooling). `subs.cache` requires `frame` (this ns), so the call is
  late-bound to keep the dependency one-directional. The fallback
  (hook unbound — only reachable if `re-frame.subs.cache` was never
  loaded, e.g. a frame with subs but no subscribe path) preserves the
  best-effort direct disposal so teardown never leaks reactions."
  [id f]
  (when-let [cache (:sub-cache f)]
    (if-let [dispose-all! (late-bind/get-fn :subs.cache/dispose-all-for-frame-destroy!)]
      (dispose-all! cache id)
      (do
        (doseq [[_k entry] @cache]
          (when-let [r (:reaction entry)]
            (try (interop/dispose! r)
                 (catch #?(:clj Throwable :cljs :default) _ nil))))
        (reset! cache {})))))

(defn- tear-down-partition-projections!
  "Dispose the two partition projection reactions (`:app-db` /
  `:runtime-db`) that `make-derived-value` layered over the physical
  frame-state container (rf2-adwcv6). Each projection holds a watch on the
  physical container (on the React-hook / plain-atom spine) or a Reagent
  reaction; left undisposed across a `destroy-frame!`, those watches /
  reactions leak in long-lived processes (test bundles, SSR per-request
  frame churn, hot-reload). Best-effort — a throwing dispose does not abort
  teardown. The physical frame-state container itself is GC'd with the
  dropped frame record once `dissoc-frame!` runs; no explicit dispose."
  [f]
  (doseq [k [:app-db :runtime-db]]
    (when-let [proj (get f k)]
      (try (interop/dispose! proj)
           (catch #?(:clj Throwable :cljs :default) _ nil)))))

(defn- emit-frame-destroyed-trace!
  [id]
  (trace/emit! :rf.frame :rf.frame/destroyed
               {:frame id}))

(defn- dissoc-frame!
  [id]
  (swap! frames dissoc id))

(defn- unregister-frame!
  [id]
  (registrar/unregister! :frame id))

(defn- notify-epoch-listeners!
  "Fire the epoch destroy hook, threading the two frame-state snapshots the
  `:halted-destroy` epoch record carries per Spec-Schemas §`:rf/epoch-record`
  §Outcomes (rf2-9neiq). EP-0001 (rf2-3aizt1, decision #2): the canonical
  snapshot unit is the whole frame-state (both partitions); the epoch surface
  derives the `:db-before` / `:db-after` app-db projections from them.

    `fs-before` — the pre-cascade snapshot (frame-state before the in-flight
                  event's cascade began), recovered from the router-bound
                  `*cascade-frame-state-before*` dynamic var. nil outside a drain.
    `fs-after`  — the state at destroy-time: the live frame-state value read
                  at the TOP of `destroy-frame!`, before any teardown step
                  mutated or removed the container. The partial cascade's
                  already-committed writes survive in this value; once
                  teardown runs the live container can no longer be read
                  (`frame-state-value` returns nil for a destroyed frame).

  Both snapshots are captured BEFORE the frame is removed and passed
  explicitly so the epoch surface (which fires AFTER `dissoc-frame!`,
  step 6) does not have to read a container that is already gone — the
  root cause of the prior nil-`:db-before` / nil-`:db-after` records."
  [id fs-before fs-after]
  (safe-call-hook! :epoch/on-frame-destroyed id fs-before fs-after))

(defn destroy-frame!
  "Tear down a frame. Per Spec 002 §Destroy, the ordered steps are:

    1. fire-on-destroy-event!       — run user :on-destroy while frame
                                      is still alive.
    2. notify-machine-destruction!  — per Spec 005 §Cross-Spec Interactions §1:
                                      delegates to the machines artefact's
                                      `:machines/teardown-on-frame-destroy!`
                                      hook (rf2-vsigt). That walks each
                                      active machine in reverse-creation
                                      order: runs the `:exit` cascade
                                      against a live container, applies
                                      the unified teardown projection
                                      (snapshot + system-id + spawn-slot
                                      prune), unregisters the live handler,
                                      and emits
                                      `:rf.machine.lifecycle/destroyed`
                                      with :reason :parent-frame-destroyed.
                                      Falls back to minimal HTTP-abort +
                                      trace when the machines artefact is
                                      absent.
    3. mark-frame-destroyed!        — flip :lifecycle :destroyed?.
    4. tear-down-sub-cache!         — dispose every cached reaction
                                      via the sub-cache-owned
                                      `:subs.cache/dispose-all-for-
                                      frame-destroy!` hook, so each
                                      eviction emits `:rf.sub/dispose`
                                      with `:rf.sub/reason
                                      :frame-destroy` (rf2-x3m8c).
    *. cleanup hooks (best-effort, no-op when artefact absent):
         :privacy/clear-suppression-cache!  — reset sensitive-without-
                                              redaction warn-once cache.
         :elision/clear-warning-cache!      — reset schema-first elision
                                              warning cache.
         :ssr/on-frame-destroyed            — clear SSR side-channel
                                              atoms for this frame.
         :machines/on-frame-destroyed!      — clear the machines
                                              artefact's frame-scoped
                                              `:after` timer table.
         :schemas/on-frame-destroyed!       — drop schemas registered
                                              against this frame
                                              (rf2-wkxng / rf2-6m0se).
         :flows/teardown-on-frame-destroy!  — drop flows + last-inputs
                                              rows + dead `:flow`
                                              registrar slots
                                              (rf2-wbtjn).
    5. emit-frame-destroyed-trace!  — emit :frame/destroyed AFTER the
                                      machine cascade.
    6. dissoc-frame!                — remove from the `frames` atom.
    7. unregister-frame!            — drop from the registrar.
    8. notify-epoch-listeners!      — fire the epoch hook so tools see
                                      :rf.epoch.cb/silenced-on-frame-destroy,
                                      threading the pre-cascade
                                      (`*cascade-frame-state-before*`) and
                                      destroy-time (live frame-state value
                                      captured at the TOP of this fn, before
                                      any teardown) frame-state snapshots so a
                                      mid-drain destroy's :halted-destroy
                                      epoch record carries real
                                      :frame-state-before / :frame-state-after
                                      (and their :db-* app-db projections) per
                                      Spec-Schemas §:rf/epoch-record §Outcomes
                                      (rf2-9neiq / rf2-3aizt1) — not the prior
                                      nil/nil.

  Subsequent dispatch / subscribe against a destroyed frame raises
  :rf.error/frame-destroyed.

  Re-entrancy (rf2-r1ciy): if `destroy-frame!` is called for `id` while
  an outer `destroy-frame!` for the same `id` is still on the stack
  (e.g. the user's `:on-destroy` handler itself calls `destroy-frame!`,
  or a machine `:exit` cascade does so), the re-entrant call is a
  silent no-op — the outer call's teardown is already in flight and
  re-running the recipe would re-fire `:on-destroy`, re-run the
  machine cascade, and corrupt the half-torn-down state. Idempotent
  destroy is the existing pattern (a destroyed frame's `(frame id)`
  lookup already returns nil, so a *later* `destroy-frame!` short-
  circuits at the outer `when-let`); the in-flight guard closes the
  RE-ENTRANT window before `mark-frame-destroyed!` flips the flag."
  [id]
  ;; Re-entrancy guard: short-circuit if we're already destroying this id.
  ;; Silent no-op (idempotent destroy is already a no-op pattern; no new
  ;; trace event needed per rf2-r1ciy decision).
  (when-not (contains? @destroying-frames id)
    (when-let [f (frame id)]
      (swap! destroying-frames conj id)
      ;; Capture the DESTROY-TIME frame-state value BEFORE any teardown step
      ;; runs. After `mark-frame-destroyed!` (step 3) flips :destroyed?,
      ;; `frame-state-value` returns nil; after `dissoc-frame!` (step 6)
      ;; the container is gone entirely. Reading it here yields the state
      ;; the partial cascade left the frame in at the moment destroy was
      ;; requested — the `:frame-state-after` slot the `:halted-destroy`
      ;; epoch record carries (rf2-9neiq). The pre-cascade
      ;; `:frame-state-before` rides the router-bound
      ;; `*cascade-frame-state-before*` dynamic var (nil outside a drain).
      ;; Both are passed to `notify-epoch-listeners!` (step 8). EP-0001
      ;; (rf2-3aizt1, decision #2): the whole frame-state, both partitions.
      (let [cascade-fs-before *cascade-frame-state-before*
            fs-at-destroy     (frame-state-value id)]
       (binding [*destroying-frame-id* id]
        (try
        (fire-on-destroy-event! id f)
        (notify-machine-destruction! id)
        (mark-frame-destroyed! id)
        (tear-down-sub-cache! id f)
        ;; Dispose the app-db / runtime-db projection reactions (rf2-adwcv6)
        ;; AFTER the sub-cache (the sub-cache's layer-1 reactions watch the
        ;; app-db projection; disposing the projection first would orphan
        ;; their source watch). The projections watch the physical
        ;; frame-state container; disposing here releases those watches.
        (tear-down-partition-projections! f)
        (safe-call-hook! :privacy/clear-suppression-cache!)
        (safe-call-hook! :elision/clear-warning-cache!)
        (safe-call-hook! :ssr/on-frame-destroyed id)
        (safe-call-hook! :machines/on-frame-destroyed! id)
        ;; Per rf2-wkxng / rf2-6m0se: drop every schema registered against
        ;; the destroyed frame so a re-registered frame starts with a
        ;; clean schema slate. Without this hook, orphan app-db schemas
        ;; from a prior `reg-frame` cycle persist and re-fire under the
        ;; rollback contract — manifesting as spurious rollbacks against
        ;; paths the new frame's :on-create never wrote. No-op when
        ;; re-frame.schemas is absent (the artefact is optional per
        ;; rf2-p7va).
        (safe-call-hook! :schemas/on-frame-destroyed! id)
        ;; Per rf2-wbtjn: drop every flow registered against the destroyed
        ;; frame plus its cached `last-inputs` rows, and prune the
        ;; `:flow` registrar slot when the destroyed frame was the last
        ;; owner. Symmetric with the machines teardown hook above
        ;; (rf2-vsigt). Without this hook a long-running SSR JVM with
        ;; per-request frame churn grows the flow registry unboundedly.
        ;; No-op when re-frame.flows is absent (the artefact is optional
        ;; per rf2-tfw3).
        (safe-call-hook! :flows/teardown-on-frame-destroy! id)
        (emit-frame-destroyed-trace! id)
        ;; Per Spec 009 §Per-frame trace rings (rf2-g1b2m / rf2-8uwce):
        ;; release the destroyed frame's cascade-keyed ring so no
        ;; residual trace events leak across the frame lifecycle. Fired
        ;; AFTER `:rf.frame/destroyed` emits so the destroyed trace
        ;; itself (which is frameless and bypasses the ring anyway)
        ;; still flows through the live stream cleanly. Routed via
        ;; late-bind so production CLJS bundles (no trace.tooling) no-op.
        (safe-call-hook! :trace.tooling/release-frame-ring! id)
        (dissoc-frame! id)
        (unregister-frame! id)
        (notify-epoch-listeners! id cascade-fs-before fs-at-destroy)
        nil
        (finally
          ;; Always clear the in-flight marker — even if a downstream step
          ;; throws unexpectedly, future `destroy-frame!` calls for `id`
          ;; (after a fresh `reg-frame`) must not see a stale entry.
          (swap! destroying-frames disj id))))))))

(defn reset-frame!
  "destroy-frame! followed by reg-frame with the same config. Per Spec 002
  §reset-frame! — full replace, opt-in."
  [id]
  (when-let [f (frame id)]
    (let [config (:config f)]
      (destroy-frame! id)
      (reg-frame id config))))

;; ---- :rf/default ----------------------------------------------------------

(defn ensure-default-frame!
  "The :rf/default frame is registered automatically the first time the
  runtime boots. Idempotent."
  []
  (when-not (get @frames :rf/default)
    (reg-frame :rf/default {:doc "Universal default frame."})))
