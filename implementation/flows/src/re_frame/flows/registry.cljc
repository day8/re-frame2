(ns re-frame.flows.registry
  "Per-frame flow registry — owns the `flows` registry atom and the
  PER-FRAME `last-inputs` dirty-check containers, flow-map validation,
  registration (`reg-flow` / `clear-flow`), and the registrar
  replacement-hook that invalidates the dirty-check on hot reload.

  Per Spec 013 flows are FRAME-SCOPED: same flow-id can register against
  two frames with different `:inputs` / `:output` / `:path`, and
  undo / time-travel semantics belong to one frame's history. The
  registry shape is `{frame-id {flow-id flow-map}}`.

  Dirty-check storage is PER-FRAME by construction (rf2-94ol5). Each
  frame owns its own `last-inputs` container (`atom {flow-id inputs}`),
  held in the `frame-last-inputs` registry keyed by frame-id. A frame's
  drain reads and writes ONLY its own atom; the failed-flow rollback
  snapshots and restores ONLY the draining frame's atom. Cross-frame
  interference during a concurrent drain is therefore impossible by
  construction — a frame can no more touch a sibling's dirty-check rows
  than it can touch a sibling's app-db. (The prior single global atom
  keyed `{flow-id {frame-id inputs}}` made the wholesale-snapshot
  rollback over-broad: on a throw it reverted EVERY frame's rows, which
  on the JVM could clobber a concurrently-draining sibling's just-
  advanced rows. Mike ruled the structural per-frame-atom fix B over the
  minimal per-frame-slice scoping A — 2026-06-01.)

  Per rf2-mnu8z this is the second leg of the flows split. The façade
  (`re-frame.flows`) re-exports the public surface — `reg-flow`,
  `clear-flow`, `reset-flows!`, `reset-last-inputs!`, plus the
  rf2-4gvb4 read accessors `flows-snapshot` / `last-inputs-snapshot`.
  The underlying atoms themselves are PRIVATE to this artefact: external
  consumers (production code, the late-bind directory, test fixtures
  across artefacts) reach the registry state through the accessor seam
  rather than dereferencing the atom Vars directly. `last-inputs-snapshot`
  re-aggregates the per-frame atoms back into the canonical
  `{flow-id {frame-id inputs}}` observation shape so its public contract
  is unchanged across the storage restructure."
  (:require [re-frame.flows.topo :as topo]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.source-coords :as source-coords]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

;; ---- state ---------------------------------------------------------------
;;
;; Per rf2-4gvb4 — the atoms below are PRIVATE to this artefact. External
;; consumers reach the registry state through the public read accessors
;; (`flows-snapshot` / `last-inputs-snapshot`) and the reset fns
;; (`reset-flows!` / `reset-last-inputs!`) — the facade re-exports them at
;; `re-frame.flows`. The atoms themselves are NOT a public surface; treating
;; them as one couples consumers to the internal shape (the per-frame
;; container restructure below would otherwise force every test fixture to
;; follow). The accessor seam keeps the flexibility on the framework side
;; while giving tests the read-and-reset affordances they actually need.

(defonce
  ^{:doc     "frame-id → flow-id → flow-map. Per-frame so undo / time-travel
              / clear semantics are unambiguous."
    :private true}
  flows
  (atom {}))

;; Per rf2-94ol5 — PER-FRAME dirty-check storage. `frame-last-inputs` is a
;; registry mapping `frame-id → (atom {flow-id last-seen-input-vec})`: each
;; frame owns its OWN inner atom of dirty-check rows, mirroring how every
;; other per-frame runtime cell (app-db container, router queue, sub-cache,
;; drain-lock — see `re-frame.frame/new-frame-record`) is held independently
;; per frame.
;;
;; This is the structural fix (Mike ruled B 2026-06-01): a frame's drain
;; reads / writes ONLY `(get @frame-last-inputs frame-id)`, and the failed-
;; flow rollback in `re-frame.flows/run-flows-on-db` snapshots / restores
;; ONLY that one atom. A frame can no more clobber a sibling's dirty-check
;; rows than it can clobber a sibling's app-db — cross-frame interference is
;; impossible BY CONSTRUCTION, not merely avoided. The prior single global
;; atom keyed `{flow-id {frame-id inputs}}` made the wholesale-snapshot
;; rollback over-broad: on a throw it `reset!`-reverted EVERY frame's rows,
;; so on the JVM a concurrently-draining sibling's just-advanced rows were
;; clobbered (spurious recompute + sub-invalidation storm; violated the
;; documented per-frame-independence invariant).
;;
;; The outer `frame-last-inputs` atom is mutated only on frame-slot
;; lifecycle (first touch creates the inner atom; teardown / reset removes
;; it). The hot per-drain path mutates the INNER atom in place — so the
;; per-frame container identity is stable across a frame's lifetime and the
;; reader / writer never contend on the outer registry.
(defonce
  ^{:doc     "frame-id → (atom {flow-id last-seen-input-vec}). Per-frame
              dirty-check containers (rf2-94ol5). Each frame's inner atom is
              read / written only by that frame's drain; the failed-flow
              rollback restores only the draining frame's own atom."
    :private true}
  frame-last-inputs
  (atom {}))

(defn- ^:no-doc ensure-frame-last-inputs-atom!
  "Return the inner `last-inputs` atom for `frame-id`, creating (and
  registering) it on first touch. The create-if-absent is done under a
  single `swap!` over the outer `frame-last-inputs` registry so concurrent
  first-touches on the JVM converge on ONE inner atom (the loser's freshly
  allocated atom is discarded; both threads then read the winner via the
  returned value). Idempotent for an already-present frame — returns the
  existing atom without allocating."
  [frame-id]
  (or (get @frame-last-inputs frame-id)
      (get (swap! frame-last-inputs
                  (fn [m]
                    (if (contains? m frame-id)
                      m
                      (assoc m frame-id (atom {})))))
           frame-id)))

;; ---- public read accessors (rf2-4gvb4) -----------------------------------
;;
;; `flows-snapshot` and `last-inputs-snapshot` are the public seam external
;; consumers (test fixtures, conformance harnesses, the cross-artefact
;; integration tests in http / epoch / routing / ssr / core) use to observe
;; the registry shape without dereferencing the private atoms above. Reads
;; only — mutation goes through `reg-flow` / `clear-flow` /
;; `teardown-on-frame-destroy!` / `reset-flows!` / `reset-last-inputs!`.

(defn flows-snapshot
  "Return the per-frame flow registry value: `{frame-id {flow-id flow-map}}`.
  Snapshot — observers MUST NOT mutate (the underlying atom is private)."
  []
  @flows)

(defn last-inputs-snapshot
  "Return the dirty-check value re-aggregated to the canonical observation
  shape `{flow-id {frame-id inputs}}`. Reads every per-frame container
  (rf2-94ol5 storage) and inverts the `{frame-id {flow-id inputs}}` layout
  back to `flow-id`-outer so the public contract is unchanged across the
  per-frame restructure. Empty per-frame containers contribute nothing, so
  a frame whose dirty-check rows all cleared leaves no key behind.
  Snapshot — observers MUST NOT mutate (the underlying atoms are private)."
  []
  (reduce-kv
    (fn [acc frame-id inner-atom]
      (reduce-kv
        (fn [acc flow-id inputs]
          (assoc-in acc [flow-id frame-id] inputs))
        acc
        @inner-atom))
    {}
    @frame-last-inputs))

;; ---- intra-artefact-only mutation helpers (rf2-4gvb4 / rf2-94ol5) --------
;;
;; `re-frame.flows` (the facade evaluation path) reads / advances the
;; draining frame's `last-inputs` on every successful flow recompute and
;; snapshots / restores it across the drain. These helpers keep the mutation
;; contained in this namespace — the atom never escapes — and are frame-
;; scoped: every read / write / rollback names the frame it operates on, so
;; no path can touch a sibling frame's container. NOT a public surface;
;; ns-doc names the consumer.

(defn ^:no-doc frame-last-inputs-snapshot
  "Return the draining frame's dirty-check rows as a plain `{flow-id inputs}`
  map (the drain-start snapshot for the failed-flow rollback). Empty map
  when the frame has no container yet."
  [frame-id]
  (if-let [a (get @frame-last-inputs frame-id)]
    @a
    {}))

(defn ^:no-doc get-frame-flow-last-inputs
  "Read the last-seen input vec for `[frame-id flow-id]` — the dirty-check
  skip-path read. `nil` when the frame has no container or the flow has no
  row yet."
  [frame-id flow-id]
  (when-let [a (get @frame-last-inputs frame-id)]
    (get @a flow-id)))

(defn ^:no-doc set-frame-flow-last-inputs!
  "Advance the dirty-check row for `[frame-id flow-id]` to `inputs` after a
  successful recompute. Mutates only the frame's own inner atom (creating it
  on first touch)."
  [frame-id flow-id inputs]
  (swap! (ensure-frame-last-inputs-atom! frame-id) assoc flow-id inputs))

(defn ^:no-doc reset-frame-last-inputs-to!
  "Restore the draining frame's `last-inputs` container to `prior` (its
  drain-start snapshot, a plain `{flow-id inputs}` map). Called by
  `re-frame.flows/run-flows-on-db`'s catch arm to roll back the draining
  frame's — and ONLY the draining frame's — dirty-check bookkeeping when a
  flow throws mid-cascade. A concurrently-draining sibling's container is a
  different atom and is untouched (rf2-94ol5)."
  [frame-id prior]
  (reset! (ensure-frame-last-inputs-atom! frame-id) prior))

;; ---- last-inputs row maintenance (rf2-94ol5) -----------------------------
;;
;; With per-frame `last-inputs` containers, dropping one flow's dirty-check
;; row for a frame is a plain `dissoc` on that frame's own inner atom — no
;; two-level-map walk, and structurally incapable of touching a sibling
;; frame's container. The clear-flow / frame-destroy / hot-reload-invalidate
;; paths share this one helper so the "drop this flow's row for this frame"
;; invariant lives in exactly one place. (The prior `prune-frame-row`
;; maintained the global `{flow-id {frame-id v}}` map's inner-map emptiness;
;; per-frame storage makes the dissoc unconditional and frame-local.)

(defn- drop-frame-flow-row!
  "Drop `flow-id`'s dirty-check row from `frame-id`'s `last-inputs`
  container. No-op when the frame has no container. Frame-local by
  construction (rf2-94ol5)."
  [frame-id flow-id]
  (when-let [a (get @frame-last-inputs frame-id)]
    (swap! a dissoc flow-id)))

(defn- owning-frames
  "The frame-ids in the per-frame `flows` map (`{frame-id {flow-id
  flow-map}}`) that still register `flow-id`. Empty when the
  destroyed/cleared frame was the last owner and the shared `:flow`
  registrar slot can be released; otherwise names the surviving owners
  `realign-registrar-owner!` re-points the slot to. O(F) over frame
  count (typically 1-3 at v1).

  Cost note: if a profile-driven hot path ever shows many-frame
  topologies stressing this, the optimisation is a reverse index
  `{flow-id #{frame-id ...}}` maintained by `reg-flow` / `clear-flow`,
  making the check O(1). Deferred until measurement warrants the extra
  atom."
  [flows-map flow-id]
  (filterv #(contains? (get flows-map %) flow-id) (keys flows-map)))

;; ---- validation ----------------------------------------------------------
;;
;; Per Spec 013 §Flow shape: `:inputs` is a vector of app-db paths, `:path`
;; is an app-db path. A path is a non-empty vector of scalar map keys. The
;; prior validator only enforced `vector?` on each, so three classes of
;; malformed input slipped through (per audit rf2-o3hok findings Q5 / TE4):
;;
;;   - `:inputs [:foo :bar]` (vector of bare keywords) — passed; then
;;     topo's `prefix?` threw on `(count :foo)`.
;;   - `:inputs [[:foo] :bar]` (mixed) — same path, same delayed boom.
;;   - `:path []` — passed; `(prefix? [] anything)` is true, so the empty-
;;     path flow silently became a depends-on prerequisite of EVERY other
;;     flow in the frame (per Spec 013 §Dependency rule).
;;
;; The tightened validator rejects each malformation up front with a stable
;; error id and ex-data that names the offending entries / elements so
;; callers don't have to chase the failure into the topo / evaluator stack.

(defn- valid-path-element?
  "Path elements are scalar map keys: keyword, string, integer, symbol, or
  boolean. Collections (vectors / maps / sets / seqs) are never the right
  value for a `get-in` path step and almost always indicate a caller bug
  (e.g. passing a bare keyword where a vector-of-paths was expected, then
  wrapping it one level too many)."
  [x]
  (or (keyword? x) (string? x) (integer? x) (symbol? x) (boolean? x)))

(defn- valid-path?
  "A path is a non-empty vector of valid path elements."
  [x]
  (and (vector? x) (seq x) (every? valid-path-element? x)))

;; Every validation throw shares the canonical thrown-error skeleton
;; (per Spec 009 §The thrown-error shape):
;;
;;   {:rf.error/id <category-kw>    ;; CANONICAL DISCRIMINATOR — :rf.error/<category>
;;    :where       'rf/reg-flow     ;; user-facing fn for greping the call site
;;    :recovery    :fix-registration ;; "the caller fixes their flow map and retries"
;;    :reason      "<diagnostic>"
;;    :flow        <the supplied flow>}
;;
;; The `:rf.error/id` discriminator slot is read uniformly by every
;; consumer (Xray's error widget, the pair-tool overlay, `:on-error`
;; policies); the message string is the stringified kw so `.getMessage`
;; pivots to the same category without ex-data. The `:where` / `:recovery`
;; slots mirror the missing-artefact throw shape standardised by
;; `re-frame.late-bind/require-fn!` and used by every
;; `re-frame.core-<artefact>` wrapper. Per-clause extras (`:bad-entries`
;; / `:bad-elements`) merge on top.

(defn- flow-error
  "Build the validate-flow ex-info with the canonical thrown-error shape
  (per Spec 009 §The thrown-error shape). `error-kw` becomes the message
  AND the `:rf.error/id` discriminator slot; `reason` is the human-
  readable diagnostic; `extras` merges per-clause slots (e.g.
  `:bad-entries`)."
  ([error-kw reason flow] (flow-error error-kw reason flow nil))
  ([error-kw reason flow extras]
   (ex-info (str error-kw)
            (merge {:rf.error/id error-kw
                    :where       'rf/reg-flow
                    :recovery    :fix-registration
                    :reason      reason
                    :flow        flow}
                   extras))))

;; The validation rules, in evaluation order. Each rule has a predicate
;; over the flow map (returns truthy to accept), a stable `:error-kw`
;; discriminator, a `:reason` diagnostic, and optional `:extras` that
;; build per-clause ex-data slots (`:bad-entries` / `:bad-elements`).
;; `validate-flow` walks this vector and throws on the first failing
;; predicate — matching the original `cond` evaluation order so existing
;; tests pinning rejection ids see no shift.
;;
;; Data-driven so the rules are introspectable (a test or the spec can
;; read the table) and adding a clause is a single conj.
(def ^:private validation-rules
  [{:pred     (fn [flow] (some? (:id flow)))
    :error-kw :rf.error/flow-missing-id
    :reason   ":id is required (flow registration must name an id)"}

   {:pred     (fn [flow] (vector? (:inputs flow)))
    :error-kw :rf.error/flow-bad-inputs
    :reason   ":inputs must be a vector of paths"}

   ;; One clause for both "entry isn't a vector" and "entry isn't a valid
   ;; path" — `valid-path?` already requires `vector?`, so the older
   ;; two-arm split (the prior code carried a separate `(every? vector?
   ;; ...)` check) was strictly subsumed by this one. The single rejection
   ;; message names what the entry must be; the `:bad-entries` slot points
   ;; at the offending values so callers can fix them without a stack-trace
   ;; dig.
   {:pred     (fn [flow] (every? valid-path? (:inputs flow)))
    :error-kw :rf.error/flow-bad-inputs
    :reason   ":inputs entries must each be a non-empty vector of scalar keys (keyword / string / integer / symbol / boolean)"
    :extras   (fn [flow] {:bad-entries (vec (remove valid-path? (:inputs flow)))})}

   {:pred     (fn [flow] (fn? (:output flow)))
    :error-kw :rf.error/flow-bad-output
    :reason   ":output must be a fn"}

   {:pred     (fn [flow] (vector? (:path flow)))
    :error-kw :rf.error/flow-bad-path
    :reason   ":path must be a vector"}

   {:pred     (fn [flow] (seq (:path flow)))
    :error-kw :rf.error/flow-bad-path
    :reason   ":path must be non-empty (an empty :path would make this flow a depends-on prerequisite of every other flow per Spec 013 §Dependency rule)"}

   {:pred     (fn [flow] (every? valid-path-element? (:path flow)))
    :error-kw :rf.error/flow-bad-path
    :reason   ":path elements must each be a scalar key (keyword / string / integer / symbol / boolean)"
    :extras   (fn [flow] {:bad-elements (vec (remove valid-path-element? (:path flow)))})}])

(defn- validate-flow [flow]
  (some (fn [{:keys [pred error-kw reason extras]}]
          (when-not (pred flow)
            (throw (flow-error error-kw reason flow (when extras (extras flow))))))
        validation-rules))

;; ---- registration --------------------------------------------------------

;; `reg-flow`'s same-frame `:path`-change branch (rf2-73pi1) vacates the
;; OLD output path via `vacate-output-path!`, which is defined alongside
;; `clear-flow` further down (both share the app-db dissoc semantics).
;; Forward-declare it so the registration path can reference it without
;; reordering the large clear/teardown block above the hot registration
;; surface.
(declare vacate-output-path!)

(defn reg-flow
  "Register a flow against a frame. Per Spec 013 — flows are frame-
  scoped: their lifecycle, evaluation, undo / time-travel semantics
  all belong to one frame.

  Required keys on the flow map: :id :inputs :output :path.
  Optional: :doc :schema.

  The frame to register against comes from the optional :frame opt;
  default is (frame/current-frame) — usually :rf/default unless
  called inside a (with-frame ...) wrapper or under a frame-provider."
  ([flow] (reg-flow flow {}))
  ([flow {:keys [frame] :as _opts}]
   (validate-flow flow)
   (let [frame-id (or frame (frame/current-frame))
         flow-id  (:id flow)]
     ;; ATOMIC check-and-insert (rf2-qxwib). The cycle check and the
     ;; commit are ONE `swap!` update fn over `@flows`: it reads the
     ;; frame's CURRENTLY-COMMITTED flow-map, runs topo-sort on the
     ;; prospective map, and — only if that passes — returns the map
     ;; with the new flow assoc'd in. `swap!` re-invokes the update fn
     ;; (re-reading committed state, re-running the cycle check) whenever
     ;; a concurrent writer wins the compare-and-set, so a cycle can
     ;; NEVER be admitted by interleaving.
     ;;
     ;; Pre-fix (rf2-7csri-era) this was check-THEN-act: it read
     ;; `@flows` once, built a prospective map, ran topo-sort, then
     ;; committed in a SEPARATE `swap!`. Two threads reg-flow'ing on the
     ;; same frame two flows that together form a cycle (T1: A reads B's
     ;; path; T2: B reads A's path) could each pass the check against the
     ;; OTHER's not-yet-committed flow (neither sees it), then both
     ;; commit — leaving a cyclic registry that throws on every drain.
     ;; Folding the validation into the update fn closes that TOCTOU.
     ;;
     ;; Single-threaded path is unchanged in cost: `swap!` invokes the
     ;; update fn exactly once with no contention, so this runs the same
     ;; single topo-sort the prior code did. Only contended same-frame
     ;; registration pays the retry (re-running the cheap Kahn sort over
     ;; a handful of nodes), and only then to preserve correctness.
     ;;
     ;; The cycle-check throw propagates OUT of `swap!`, so on rejection
     ;; the atom is left untouched and the prior registration survives —
     ;; preserving the rf2-7csri "a rejected REPLACEMENT does not vacate
     ;; the slot the prior entry shares" guarantee, now atomically.
     ;;
     ;; `prior-on-frame` captures THIS frame's currently-committed flow
     ;; entry for `flow-id`, recorded from inside the update fn so it
     ;; reflects the state the WINNING CAS observed (a contended retry
     ;; re-records against the re-read map; the last invocation — the one
     ;; whose return value commits — leaves the authoritative value). It
     ;; drives two decisions below: (rf2-mb9vq) per-frame first-vs-
     ;; replacement for the `:rf.flow/registered` trace, and (rf2-73pi1)
     ;; the same-frame `:path`-change vacate. `nil` ⇒ first-time
     ;; registration of `flow-id` on THIS frame (even if a SIBLING frame
     ;; already holds the global registrar slot).
     (let [prior-on-frame (volatile! nil)]
       (swap! flows
              (fn [m]
                (let [prior-frame (get m frame-id)]
                  (vreset! prior-on-frame (get prior-frame flow-id))
                  (let [prospective (assoc prior-frame flow-id flow)]
                    ;; Two registration-time rejections, both run on the
                    ;; PROSPECTIVE map inside this update fn so they share
                    ;; the cycle check's atomicity (rf2-qxwib): a throw
                    ;; propagates out of `swap!`, the CAS never fires, and
                    ;; the prior registration survives untouched.
                    ;;
                    ;; 1. Throws :rf.error/flow-path-overlap (rf2-um6d9) if
                    ;;    the new flow's output :path overlaps an already-
                    ;;    registered sibling's :path (one a prefix of the
                    ;;    other). The topo dependency rule compares :path vs
                    ;;    :inputs only, so overlapping outputs get no edge
                    ;;    and their eval order is undefined — reject before
                    ;;    any state mutates (Spec 013 §Disjoint output
                    ;;    paths). Checked BEFORE the cycle sort so a frame
                    ;;    with overlapping outputs reports the more specific
                    ;;    footgun rather than whatever (or no) cycle the topo
                    ;;    walk happens to surface.
                    ;; 2. Throws :rf.error/flow-cycle if the prospective map
                    ;;    is cyclic; the update fn never returns and the CAS
                    ;;    never fires, so the atom is unchanged.
                    (topo/detect-output-path-overlap! prospective)
                    (topo/topo-sort prospective)
                    (assoc m frame-id prospective)))))
       ;; rf2-73pi1 finding 2: a same-frame re-registration that MOVES the
       ;; output to a new `:path` must vacate the OLD path from this
       ;; frame's app-db — otherwise the previous definition's last write
       ;; lingers and downstream reads see stale derived state at the
       ;; abandoned slot. Only fires on a real same-frame replacement
       ;; (`prior-on-frame` non-nil) whose `:path` actually changed; the
       ;; commit above already installed the new definition, so the new
       ;; path recomputes on the next drain. First-time registration and
       ;; same-path hot-reload leave app-db untouched.
       (when-let [prior @prior-on-frame]
         (let [old-path (:path prior)]
           (when (not= old-path (:path flow))
             (vacate-output-path! frame-id old-path))))
       ;; Cycle check + commit done atomically above. The :flow registrar
       ;; slot keys on flow-id only; stamp :frame into the metadata so
       ;; introspection
       ;; / hot-reload hooks can read the owning frame. `register!`
       ;; returns `{:was previous :now metadata}` — `:was` is nil on
       ;; first-time registration, non-nil on hot-reload re-registration.
       ;; Per rf2-v5ttb: stamp `:handler-fn` so the registrar's
       ;; `:different-fn?` calculation (registrar.cljc) can tell a real
       ;; body change from an idempotent reload. The registrar reads
       ;; `:handler-fn` uniformly across kinds; events / subs / fx all
       ;; populate it at their registration sites, but flows historically
       ;; stored the body under `:output` only — so `(not= nil nil)` was
       ;; the answer for every flow re-registration and `:different-fn?`
       ;; was always `false` (re-frame-10x's flow panel / Xray / re-frame2-pair
       ;; missed every real body swap). The `:output` slot is preserved
       ;; for the flow-eval site that reads it; the additional
       ;; `:handler-fn` stamp aligns the cross-kind hot-reload trace
       ;; surface Spec 001 standardises.
       (registrar/register!
         :flow flow-id
         (source-coords/merge-coords
           (assoc flow
                  :frame      frame-id
                  :handler-fn (:output flow))))
       ;; Per Spec 009 §:op-type vocabulary: :rf.flow/registered fires
       ;; on FIRST-TIME registration AGAINST THIS FRAME. Pre-rf2-mb9vq
       ;; this gated on the GLOBAL registrar `:was` (flow-id-scoped, not
       ;; frame-scoped), so registering the same flow-id against a SECOND
       ;; frame — an INDEPENDENT first-time registration per Spec 013
       ;; §Frame-scoping line 102 — was misclassified as a replacement and
       ;; the trace was suppressed: a per-frame flow inventory built from
       ;; `op-type :flow` missed the second frame's flow even though
       ;; `flows-snapshot` showed it. Gate on the PER-FRAME prior slot
       ;; (`prior-on-frame`) instead: a genuine same-frame re-registration
       ;; (`prior-on-frame` non-nil) still suppresses — its hot-reload
       ;; signal rides `:rf.registry/handler-replaced` (emitted by
       ;; `registrar/register!` per Spec 001 §Hot-reload trace surface) —
       ;; while a same-id/different-frame FIRST registration emits
       ;; `:rf.flow/registered` with the correct `:frame` tag.
       ;;
       ;; The outer `debug-enabled?` gate matches the hot-path emits in
       ;; flows.cljc (per Spec 009 §Production builds, "keep the gate
       ;; OUTERMOST"); reg-flow is a cold path so the cost is negligible,
       ;; but the gate keeps the tag-map literal out of CLJS prod and the
       ;; convention uniform across every flow emit site (rf2-ee38b.9).
       (when (and interop/debug-enabled? (nil? @prior-on-frame))
         (trace/emit! :flow :rf.flow/registered
                      {:flow-id flow-id
                       :inputs  (:inputs flow)
                       :path    (:path flow)
                       :frame   frame-id})))
     ;; Per Spec 015 §7. Flows — stash `:sensitive` / `:large` and
     ;; `:sensitive?` / `:large?` declarations so emit-time projection
     ;; resolves the flow's output marks. Late-bound — no-op when the
     ;; marks artefact is absent (which it never is in core, but the
     ;; indirection keeps flows decoupled).
     (when-let [register-marks! (late-bind/get-fn :marks/register-marks!)]
       (register-marks! :flow flow-id flow))
     flow-id)))

(defn- dissoc-in-safe
  "Like `dissoc-in` over `(butlast path) → (last path)` but robust against
  the two unmaterialised-output failure modes flagged by audit rf2-q25os:

  - **Unmaterialised parent.** When a flow with `:path [:step-2 :result]`
    is cleared BEFORE its first drain, the parent slot `:step-2` may not
    exist. The naïve `(update-in db [:step-2] dissoc :result)` returns
    `(dissoc nil :result)` ⇒ `nil`, producing `{:step-2 nil}` — a
    spurious nil parent. Detect this case and leave `db` unchanged.
  - **Non-map intermediate.** When an intermediate path step holds a
    non-map value (a scalar already wrote past the flow's planned path),
    the naïve `update-in` calls `(dissoc 1 :result)` and throws
    `ClassCastException`. Treat this as a no-op — the flow's `:path`
    never materialised, so there's nothing to clear.

  Single-element paths and non-vector paths are handled by the caller's
  earlier branches; this helper is only called for `(>= (count path) 2)`."
  [db path]
  (let [parent-path (vec (butlast path))
        leaf        (last path)
        parent      (get-in db parent-path ::missing)]
    (cond
      ;; Parent was never materialised — leave db as-is. Per audit
      ;; rf2-q25os Repro 1: registering a nested-path flow then clearing
      ;; before any drain would write `{<parent> nil}` otherwise.
      (or (= ::missing parent) (nil? parent)) db
      ;; Parent is non-map (scalar / vector / set) — there's no
      ;; meaningful "dissoc this leaf" on a non-map intermediate. Per
      ;; audit rf2-q25os Repro 2: throwing ClassCastException for a
      ;; cleanup operation is poor manners; leave the value untouched
      ;; (it's not OUR flow's output anyway).
      (not (map? parent)) db
      :else (update-in db parent-path dissoc leaf))))

(defn- vacate-output-path!
  "Dissoc a flow's output `:path` from `frame-id`'s app-db (only that
  frame's container). Shared by `clear-flow` (full deregistration) and
  the same-frame `:path`-change branch of `reg-flow` (rf2-73pi1 finding
  2 — moving a flow's output to a new path must vacate the OLD path so
  downstream reads don't see stale derived state at the abandoned slot).

  `validate-flow` guarantees `:path` is a non-empty vector at
  registration (rejects non-vector and empty via :rf.error/flow-bad-path),
  so a `:path` read back out of the registry always carries one — no
  non-vector / empty-path arms are reachable here.

  rf2-aqt7: when :path is a single-element vector [:k], (butlast [:k])
  is () and (update-in db [] dissoc :k) does NOT dissoc — Clojure's
  update-in on the empty path falls into (assoc {} nil (apply f val
  args)), producing {... nil nil}. Special-case length 1 so the leaf
  is dissoc'd directly. The (>= 2) branch routes through
  `dissoc-in-safe` which handles the unmaterialised-parent / non-map-
  intermediate cases without writing nil parents or throwing (per audit
  rf2-q25os).

  Per rf2-2vpac: skips `replace-container!` when the dissoc branch was a
  no-op (missing key, or `dissoc-in-safe` returning `db` literally on
  unmaterialised-parent / non-map-intermediate). Otherwise we trigger
  reactive sub-cache invalidation for a no-op write — cheap-but-needless
  walk of the sub graph (`identical?` is O(1)). No-op when the frame has
  no app-db container."
  [frame-id path]
  (when-let [container (frame/app-db-container frame-id)]
    (let [db     (adapter/read-container container)
          new-db (if (= 1 (count path))
                   (dissoc db (first path))
                   (dissoc-in-safe db path))]
      (when-not (identical? new-db db)
        (adapter/replace-container! container new-db)))))

;; ---- registrar-slot owner maintenance (rf2-73pi1) ------------------------
;;
;; The `:flow` registrar slot is keyed by flow-id alone and carries the
;; MOST-RECENTLY-REGISTERED frame's flow-map with `:frame` stamped in
;; (Spec 013 §Frame-scoping line 105). On clear / destroy of the frame
;; whose metadata currently occupies the slot, that metadata becomes
;; STALE: it points at a frame that no longer owns the id. Runtime
;; evaluation is unaffected (the per-frame `flows` registry is the source
;; of truth), but registrar-backed tooling / hot-reload goes stale — the
;; next surviving-frame re-registration computes `:different-fn?` against
;; the dead frame's `:handler-fn`, and a Xray flow panel reading the slot
;; attributes the flow to a destroyed frame.

(defn- realign-registrar-owner!
  "After `flow-id` was removed from `cleared-frame-id`, keep the shared
  `:flow` registrar slot pointing at an actually-live owner (rf2-73pi1).

  - When NO surviving frame in `flows-map` holds `flow-id`, unregister
    the slot (the cleared/destroyed frame was the last owner).
  - When a surviving frame holds it AND the registrar slot's current
    `:frame` is the cleared/destroyed frame (its metadata is now stale),
    re-point the slot to a deterministic surviving owner — re-stamping
    that owner's flow-map (with `:frame` / `:handler-fn`) exactly as
    `reg-flow` would. The surviving owner is chosen by sorted frame-id
    for determinism across calls.
  - When a surviving frame holds it but the registrar slot already names
    a still-living owner, leave it untouched (no churn).

  Returns nil — side-effects the registrar only."
  [flows-map flow-id cleared-frame-id]
  (let [owners (owning-frames flows-map flow-id)]
    (if (empty? owners)
      ;; Last owner released the id — drop the slot.
      (registrar/unregister! :flow flow-id)
      ;; A sibling still owns the id. Only re-point when the slot's
      ;; metadata named the frame we just cleared (so its body / path /
      ;; frame attribution is stale).
      (let [current-owner (:frame (registrar/lookup :flow flow-id))]
        (when (= current-owner cleared-frame-id)
          (let [survivor (first (sort owners))
                flow     (get-in flows-map [survivor flow-id])]
            (registrar/register!
              :flow flow-id
              (source-coords/merge-coords
                (assoc flow
                       :frame      survivor
                       :handler-fn (:output flow))))))))))

(defn clear-flow
  "Deregister a flow from a frame; dissoc its output path from that
  frame's app-db (only that frame). Frame defaults to (current-frame).

  Per audit rf2-q25os: the nested-path dissoc is robust against the
  output path never having been materialised (no spurious nil parent
  created) and against a non-map intermediate (no ClassCastException
  thrown) — see `dissoc-in-safe` / `vacate-output-path!` above.

  Vacation contract (rf2-ee38b.9): clearing a flow with `:path
  [:wizard :result]` removes the LEAF (`:result`) only. If `:result`
  was the sole key under `:wizard`, an empty parent map `{:wizard {}}`
  remains — this is deliberate, not a leak. The flow's *value* is
  fully gone (the spec's \"vacate the slot\" requirement); pruning empty
  ancestor maps would risk deleting unrelated sibling slots that happen
  to be empty and own ancestors this flow never created, so leaf-only
  vacation is the correct contract. Downstream consumers read the leaf,
  not the parent's emptiness."
  ([id] (clear-flow id {}))
  ([id {:keys [frame] :as _opts}]
   (let [frame-id (or frame (frame/current-frame))]
     (when-let [flow (get-in @flows [frame-id id])]
       (let [path (:path flow)]
         (vacate-output-path! frame-id path)
         ;; Drop the flow from `frame-id`'s per-frame slot; when that was
         ;; the LAST flow on the frame, prune the now-empty `frame-id` key
         ;; entirely rather than leave a `{frame-id {}}` husk (rf2-4bbaw).
         ;; The husk was harmless (bounded by frame count, tolerated by the
         ;; concurrency-stress invariant) but a naive `flows-snapshot`
         ;; consumer would iterate the empty entry; pruning keeps the
         ;; registry exactly symmetric with `teardown-on-frame-destroy!`'s
         ;; `(swap! flows dissoc frame-id)`.
         (swap! flows (fn [m]
                        (let [m' (update m frame-id dissoc id)]
                          (cond-> m'
                            (empty? (get m' frame-id)) (dissoc frame-id)))))
         ;; Drop the cleared flow's dirty-check row from THIS frame's own
         ;; `last-inputs` container (rf2-94ol5). Frame-local — a sibling
         ;; frame registering the same id keeps its own row untouched.
         (drop-frame-flow-row! frame-id id)
         ;; Keep the shared `:flow` registrar slot aligned with a live
         ;; owner (rf2-73pi1): unregister when this was the LAST frame
         ;; holding the id, or re-point the slot to a surviving owner
         ;; when the cleared frame was the slot's current (now-stale)
         ;; metadata writer. Pre-fix this only unregistered on
         ;; last-owner-release, leaving the slot pointing at the cleared
         ;; frame whenever a sibling still held the id.
         (realign-registrar-owner! @flows id frame-id)
         ;; Per Spec 009 §:op-type vocabulary: :rf.flow/cleared fires after
         ;; clear-flow has removed the flow from the per-frame registry
         ;; and dissoc-in'd its output path. Tools observe this to drop
         ;; their per-flow display state. The outer `debug-enabled?` gate
         ;; matches the hot-path emits in flows.cljc (per Spec 009
         ;; §Production builds, "keep the gate OUTERMOST"); clear-flow is
         ;; a cold path so the cost is negligible, but the gate keeps the
         ;; tag-map literal out of CLJS prod and the convention uniform
         ;; across every flow emit site (rf2-ee38b.9).
         (when interop/debug-enabled?
           (trace/emit! :flow :rf.flow/cleared
                        {:flow-id id
                         :path    path
                         :frame   frame-id}))))
     nil)))

;; ---- frame-destroy teardown ---------------------------------------------
;;
;; Per rf2-wbtjn — symmetric with the machines `:teardown-on-frame-destroy!`
;; hook (rf2-vsigt). On `destroy-frame!`, the flows registered against the
;; destroyed frame, the per-frame `last-inputs` rows, AND any `:flow`
;; registrar entries whose last owning frame was the destroyed one MUST
;; clear — otherwise SSR-style per-request frame churn / pair-tool
;; time-travel / `make-frame` ephemeral usage leak flow definitions and
;; cached input vectors indefinitely (audit
;; `ai/findings/flows-security-audit-2026-05-15.md` F1).

(defn teardown-on-frame-destroy!
  "Drop every per-frame entry the flows artefact holds against `frame-id`:

   1. Snapshot the flow-ids the destroyed frame owned (needed for the
      registrar prune in step 4).
   2. Dissoc `frame-id` from the per-frame flow registry.
   3. Dissoc the destroyed frame's `last-inputs` container from the
      per-frame `frame-last-inputs` registry — one step (rf2-94ol5),
      since per-frame storage holds the destroyed frame's rows in its
      own inner atom and removing the frame-keyed slot drops them all.
   4. For each flow-id the destroyed frame owned, keep the `:flow`
      registrar slot aligned with a live owner (rf2-73pi1): unregister
      the slot when no surviving frame still registers the id, or
      re-point it to a surviving owner when the destroyed frame was the
      slot's current (now-stale) metadata writer. The `:frame` stamped
      onto the registrar entry was the destroyed frame whenever it was
      the most-recent registrant — leaving that metadata pointing at the
      dead frame staled registrar-backed tooling / hot-reload.

   Idempotent against a frame the registry never recorded (a frame
   destroy before any `reg-flow`). Published via the
   `:flows/teardown-on-frame-destroy!` late-bind hook so
   `frame/destroy-frame!` reaches it without statically requiring the
   flows artefact."
  [frame-id]
  (when frame-id
    (let [owned-flow-ids (keys (get @flows frame-id))]
      (swap! flows dissoc frame-id)
      ;; Drop the destroyed frame's entire `last-inputs` container in one
      ;; step (rf2-94ol5) — per-frame storage means the destroyed frame's
      ;; rows ARE its inner atom, so removing the frame-keyed slot drops
      ;; every row at once and cannot touch any sibling frame's container.
      (swap! frame-last-inputs dissoc frame-id)
      ;; Registrar realignment (rf2-73pi1): for each flow-id the destroyed
      ;; frame owned, either unregister the `:flow` slot (no surviving
      ;; owner) or re-point it to a surviving owner when the destroyed
      ;; frame was the slot's stale metadata writer — the shared
      ;; `realign-registrar-owner!` helper `clear-flow` also uses.
      (let [remaining @flows]
        (doseq [flow-id owned-flow-ids]
          (realign-registrar-owner! remaining flow-id frame-id)))))
  nil)

;; ---- hot-reload invalidation --------------------------------------------
;;
;; Per Spec 001 §Hot-reload semantics: when a flow re-registers, the
;; per-frame :last-inputs entry MUST clear so the new flow re-evaluates
;; on the next drain regardless of whether inputs changed. Without this,
;; a hot-reloaded flow with a different :output fn but identical recent
;; inputs would silently keep serving the previous result.

(defn- invalidate-flow-on-replace!
  [{:keys [kind id now]}]
  (when (= kind :flow)
    ;; Per rf2-jfpf3: Spec 013 §Re-registration scopes the invalidation
    ;; to `[frame-id flow-id]`, NOT every frame holding the flow id.
    ;; Pre-fix, a re-registration on frame `:left` wiped
    ;; `last-inputs[id]` entirely — `:right`'s row for the same id
    ;; recomputed unnecessarily on its next drain, weakening frame
    ;; isolation and wasting work under multi-frame setups (per-tenant
    ;; SSR, pair-tool replays). The registrar replacement-hook payload
    ;; carries `:now` (the new metadata) with `:frame` stamped at
    ;; `reg-flow`-time; read the frame from there and drop only that
    ;; frame's row from its own `last-inputs` container (rf2-94ol5 —
    ;; per-frame storage makes the sibling-frame untouched guarantee
    ;; structural rather than reliant on careful keying).
    (let [frame-id (:frame now)]
      (drop-frame-flow-row! frame-id id))))

(defonce ^:private _hot-reload-hook
  ;; `defonce` only needs the side-effect to fire once at namespace
  ;; load; the value bound to the var is incidental. `add-replacement-hook!`
  ;; returns nil — let that be the bound value.
  (registrar/add-replacement-hook! invalidate-flow-on-replace!))

;; ---- test-only resets ----------------------------------------------------

(defn reset-last-inputs!
  "Test-only: clear ALL per-frame dirty-check `last-inputs` containers
  (rf2-94ol5 — drops the whole `frame-last-inputs` registry, discarding
  every frame's inner atom). The flows reset-runtime fixture uses this to
  drop stale per-flow state between tests so re-registration does not
  silently no-op when new-inputs =-equal a stale entry from a sibling test.
  Per rf2-tfw3 (the fourth per-feature split): this is published through
  the late-bind hook table so `re-frame.test-support`'s reset-runtime
  fixture can call it without statically requiring `re-frame.flows`."
  []
  (reset! frame-last-inputs {})
  nil)

(defn reset-flows!
  "Test-only: clear the per-frame flow registry AND the paired
  dirty-check `last-inputs` map. Per rf2-tfw3 — exposed via the
  late-bind hook table so `re-frame.test-support` can reset state
  without a static require on this namespace.

  Per rf2-mb65w: resets BOTH atoms in lockstep. Pre-fix, the function
  cleared only `flows` and left `last-inputs` standing. A test fixture
  / re-frame2-pair / Xray harness calling `reset-flows!` standalone (the
  function's name suggests \"reset all flow state\") then re-registered
  the same flow-id would silently no-op the first evaluation when
  new-inputs `=`-equalled a leftover entry. The two-atom reset is the
  single sound invariant — anything calling `reset-flows!` wants flow
  state cleared, and `last-inputs` is downstream cache for the same
  registry. Per rf2-94ol5 the dirty-check reset now drops every
  per-frame `last-inputs` container (the `frame-last-inputs` registry)."
  []
  (reset! flows {})
  (reset! frame-last-inputs {})
  nil)
