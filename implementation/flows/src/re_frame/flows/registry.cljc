(ns re-frame.flows.registry
  "Per-frame flow registry — owns the `flows` and `last-inputs` atoms,
  flow-map validation, registration (`reg-flow` / `clear-flow`), and
  the registrar replacement-hook that invalidates the dirty-check on
  hot reload.

  Per Spec 013 flows are FRAME-SCOPED: same flow-id can register against
  two frames with different `:inputs` / `:output` / `:path`, and
  undo / time-travel semantics belong to one frame's history. The
  registry shape is `{frame-id {flow-id flow-map}}`.

  Per rf2-mnu8z this is the second leg of the flows split. The façade
  (`re-frame.flows`) re-exports the public surface — `reg-flow`,
  `clear-flow`, `reset-flows!`, `reset-last-inputs!`, plus the
  rf2-4gvb4 read accessors `flows-snapshot` / `last-inputs-snapshot`.
  The underlying atoms themselves are PRIVATE to this artefact: external
  consumers (production code, the late-bind directory, test fixtures
  across artefacts) reach the registry state through the accessor seam
  rather than dereferencing the atom Vars directly."
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
;; Per rf2-4gvb4 — the two atoms below are PRIVATE to this artefact. External
;; consumers reach the registry state through the public read accessors
;; (`flows-snapshot` / `last-inputs-snapshot`) and the reset fns
;; (`reset-flows!` / `reset-last-inputs!`) — the facade re-exports them at
;; `re-frame.flows`. The atoms themselves are NOT a public surface; treating
;; them as one couples consumers to the internal shape (a future move to a
;; concurrent map, a sharded structure, or a different bookkeeping layout
;; would force every test fixture to follow). The accessor seam keeps the
;; flexibility on the framework side while giving tests the read-and-reset
;; affordances they actually need.

(defonce
  ^{:doc     "frame-id → flow-id → flow-map. Per-frame so undo / time-travel
              / clear semantics are unambiguous."
    :private true}
  flows
  (atom {}))

(defonce
  ^{:doc     "Per-flow, per-frame last-inputs index: `flow-id → frame-id →
              last-seen input vec`. Drives the dirty-check skip path in
              `re-frame.flows/evaluate-flow!` (rf2-719e).

              Shape note: the outer key is `flow-id` (not `[frame-id flow-id]`
              flat) so the hot-reload invalidation hook (which fires on
              `:flow` registrar replacement) can drop every per-frame entry
              for the replaced flow with one `dissoc` — O(1) instead of the
              prior O(N) walk over all entries. Per-frame slots stay
              independent (each flow id can register against multiple
              frames with its own dirty-check window per Spec 013
              §Frame-scoping)."
    :private true}
  last-inputs
  (atom {}))

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
  "Return the dirty-check `last-inputs` value: `{flow-id {frame-id inputs}}`.
  Snapshot — observers MUST NOT mutate (the underlying atom is private)."
  []
  @last-inputs)

;; ---- intra-artefact-only mutation helpers (rf2-4gvb4) --------------------
;;
;; `re-frame.flows` (the facade evaluation path) updates `last-inputs` on
;; every successful flow recompute and snapshots / restores it across the
;; drain. These helpers keep the mutation contained in this namespace —
;; the atom never escapes. NOT a public surface; ns-doc names the consumer.

(defn ^:no-doc swap-last-inputs!
  [f & args]
  (apply swap! last-inputs f args))

(defn ^:no-doc reset-last-inputs-to!
  "Restore `last-inputs` to `prior` (the drain-start snapshot). Called by
  `re-frame.flows/run-flows-on-db`'s catch arm to roll back dirty-check
  bookkeeping when a flow throws mid-cascade."
  [prior]
  (reset! last-inputs prior))

;; ---- two-level-map maintenance helpers -----------------------------------
;;
;; Both the `last-inputs` cache (`{flow-id {frame-id inputs}}`) and the
;; per-frame `flows` registry (`{frame-id {flow-id flow-map}}`) are
;; two-level maps maintained across three lifecycle paths (clear-flow,
;; frame-destroy teardown, hot-reload invalidate). The two operations
;; below are the invariants those paths share — extracted to one home
;; each (rf2-ee38b.9 clarity) so the `{flow-id {frame-id ...}}` /
;; "last surviving frame?" shapes live in exactly one place instead of
;; being open-coded (and kept in sync) at every call site.

(defn- prune-frame-row
  "Drop `frame-id`'s slot for `flow-id` in a `{flow-id {frame-id v}}`
  map; remove the `flow-id` key entirely when its inner map empties.
  The single home for the `last-inputs` two-level-map maintenance the
  clear / teardown / hot-reload paths share."
  [m flow-id frame-id]
  (let [m' (update m flow-id dissoc frame-id)]
    (if (empty? (get m' flow-id))
      (dissoc m' flow-id)
      m')))

(defn- no-frame-holds?
  "True iff no frame in the per-frame `flows` map (`{frame-id {flow-id
  flow-map}}`) still registers `flow-id` — i.e. the destroyed/cleared
  frame was the last owner and the shared `:flow` registrar slot can be
  released. `not-any?` short-circuits on the first holding frame; O(F)
  over frame count (typically 1-3 at v1).

  Cost note: if a profile-driven hot path ever shows many-frame
  topologies stressing this, the optimisation is a reverse index
  `{flow-id #{frame-id ...}}` maintained by `reg-flow` / `clear-flow`,
  making the check O(1). Deferred until measurement warrants the extra
  atom."
  [flows-map flow-id]
  (not-any? #(contains? % flow-id) (vals flows-map)))

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
   (let [frame-id     (or frame (frame/current-frame))
         flow-id      (:id flow)
         prior-frame  (get @flows frame-id)
         ;; Per rf2-7csri: detect cycles on a PROSPECTIVE flow-map
         ;; BEFORE mutating the atom or the registrar. The earlier
         ;; write-then-rollback path silently deleted the prior
         ;; registration along with the rejected one when a REPLACEMENT
         ;; introduced a cycle — the rollback dissoc'd by flow-id,
         ;; vacating the slot the prior entry was sharing. Now we run
         ;; topo-sort on (prior-frame `assoc` new-entry) up-front; if it
         ;; throws, nothing has been written and the prior registration
         ;; stays intact.
         prospective  (assoc prior-frame flow-id flow)]
     (topo/topo-sort prospective)
     ;; Cycle check passed — commit. The :flow registrar slot keys on
     ;; flow-id only; stamp :frame into the metadata so introspection
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
     (let [{:keys [was]} (registrar/register!
                           :flow flow-id
                           (source-coords/merge-coords
                             (assoc flow
                                    :frame      frame-id
                                    :handler-fn (:output flow))))]
       (swap! flows assoc-in [frame-id flow-id] flow)
       ;; Per Spec 009 §:op-type vocabulary: :rf.flow/registered fires
       ;; on FIRST-TIME registration only. On re-registration the
       ;; cross-kind `:rf.registry/handler-replaced` trace (emitted by
       ;; `registrar/register!` per Spec 001 §Hot-reload trace surface)
       ;; carries the hot-reload signal. Pre-rf2-ehxez both traces
       ;; fired on every re-registration; tools subscribed to both
       ;; op-types (10x flow panel reads `:flow`; epoch buffer reads
       ;; everything) double-counted re-registrations in their per-frame
       ;; reload ledger. Gate to first-time-only so each registration
       ;; surfaces exactly once on the trace bus — `:rf.flow/registered`
       ;; for the first-time path, `:rf.registry/handler-replaced` for
       ;; the hot-reload path. Op-type :flow is the discriminator for
       ;; the whole flow trace stream (per Spec 009 §:op-type
       ;; vocabulary, §Flow tracing).
       ;;
       ;; The outer `debug-enabled?` gate matches the hot-path emits in
       ;; flows.cljc (per Spec 009 §Production builds, "keep the gate
       ;; OUTERMOST"); reg-flow is a cold path so the cost is negligible,
       ;; but the gate keeps the tag-map literal out of CLJS prod and the
       ;; convention uniform across every flow emit site (rf2-ee38b.9).
       (when (and interop/debug-enabled? (nil? was))
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

(defn clear-flow
  "Deregister a flow from a frame; dissoc its output path from that
  frame's app-db (only that frame). Frame defaults to (current-frame).

  Per audit rf2-q25os: the nested-path dissoc is robust against the
  output path never having been materialised (no spurious nil parent
  created) and against a non-map intermediate (no ClassCastException
  thrown) — see `dissoc-in-safe` above.

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
         (when-let [container (frame/get-frame-db frame-id)]
           (let [db     (adapter/read-container container)
                 ;; `validate-flow` guarantees `:path` is a non-empty
                 ;; vector at registration (rejects non-vector and empty
                 ;; via :rf.error/flow-bad-path), so a flow read back out
                 ;; of the registry always carries one — no non-vector /
                 ;; empty-path arms are reachable here.
                 ;;
                 ;; rf2-aqt7: when :path is a single-element vector [:k],
                 ;; (butlast [:k]) is () and (update-in db [] dissoc :k)
                 ;; does NOT dissoc — Clojure's update-in on the empty
                 ;; path falls into (assoc {} nil (apply f val args)),
                 ;; producing {... nil nil}. Special-case length 1 so
                 ;; the leaf is dissoc'd directly.
                 ;;
                 ;; The (>= 2) branch routes through `dissoc-in-safe`
                 ;; which handles the unmaterialised-parent / non-map-
                 ;; intermediate cases without writing nil parents or
                 ;; throwing (per audit rf2-q25os).
                 new-db (if (= 1 (count path))
                          (dissoc db (first path))
                          (dissoc-in-safe db path))]
             ;; Per rf2-2vpac: skip `replace-container!` when the dissoc
             ;; branch was a no-op (empty-path, missing key, or
             ;; `dissoc-in-safe` returning `db` literally on
             ;; unmaterialised-parent / non-map-intermediate). Otherwise
             ;; we trigger reactive sub-cache invalidation for a no-op
             ;; write — cheap-but-needless walk of the sub graph
             ;; (`identical?` is O(1); the prior unconditional write
             ;; forced an O(n) sub-graph walk for every clear of an
             ;; absent slot, common during teardown).
             (when-not (identical? new-db db)
               (adapter/replace-container! container new-db))))
         (swap! flows update frame-id dissoc id)
         ;; `last-inputs` is shaped {flow-id {frame-id inputs}} — clear
         ;; this frame's slot for the cleared flow id, dropping the whole
         ;; flow row when no other frame still holds an entry (the shared
         ;; `prune-frame-row` invariant).
         (swap! last-inputs prune-frame-row id frame-id)
         ;; Only unregister from the registrar if this was the LAST frame
         ;; holding the flow id (the shared `no-frame-holds?` predicate) —
         ;; otherwise other frames still need the registry slot for
         ;; hot-reload tracking.
         (when (no-frame-holds? @flows id)
           (registrar/unregister! :flow id))
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
   3. For each flow-id present in `last-inputs`, dissoc the destroyed
      frame's row. Drop the whole flow-id key when no other frame still
      holds an entry for it.
   4. For each flow-id the destroyed frame owned, drop the `:flow`
      registrar slot when no other frame still registers that id — the
      `:frame` stamped onto the registrar entry was the destroyed frame.

   Idempotent against a frame the registry never recorded (a frame
   destroy before any `reg-flow`). Published via the
   `:flows/teardown-on-frame-destroy!` late-bind hook so
   `frame/destroy-frame!` reaches it without statically requiring the
   flows artefact."
  [frame-id]
  (when frame-id
    (let [owned-flow-ids (keys (get @flows frame-id))]
      (swap! flows dissoc frame-id)
      ;; Drop the destroyed frame's row from every flow-id via the shared
      ;; `prune-frame-row` invariant (drops the flow-id key when its inner
      ;; map empties).
      (swap! last-inputs
             (fn [m]
               (reduce (fn [acc flow-id] (prune-frame-row acc flow-id frame-id))
                       m
                       (keys m))))
      ;; Registrar prune: drop the `:flow` slot for any flow-id the
      ;; destroyed frame owned that no surviving frame still holds (the
      ;; shared `no-frame-holds?` predicate — same shape `clear-flow`
      ;; uses).
      (let [remaining @flows]
        (doseq [flow-id owned-flow-ids]
          (when (no-frame-holds? remaining flow-id)
            (registrar/unregister! :flow flow-id))))))
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
    ;; `reg-flow`-time; read the frame from there and dissoc only that
    ;; frame's row via the shared `prune-frame-row` invariant (drops the
    ;; whole flow-id key when no frame still holds an entry).
    (let [frame-id (:frame now)]
      (swap! last-inputs prune-frame-row id frame-id))))

(defonce ^:private _hot-reload-hook
  ;; `defonce` only needs the side-effect to fire once at namespace
  ;; load; the value bound to the var is incidental. `add-replacement-hook!`
  ;; returns nil — let that be the bound value.
  (registrar/add-replacement-hook! invalidate-flow-on-replace!))

;; ---- test-only resets ----------------------------------------------------

(defn reset-last-inputs!
  "Test-only: clear the dirty-check `last-inputs` map. The flows
  reset-runtime fixture uses this to drop stale per-flow state between
  tests so re-registration does not silently no-op when new-inputs
  =-equal a stale entry from a sibling test. Per rf2-tfw3 (the fourth
  per-feature split): this is published through the late-bind hook
  table so `re-frame.test-support`'s reset-runtime fixture can call it
  without statically requiring `re-frame.flows`."
  []
  (reset! last-inputs {})
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
  registry."
  []
  (reset! flows {})
  (reset! last-inputs {})
  nil)
