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
  (`re-frame.flows`) re-exports `flows`, `last-inputs`, `reg-flow`,
  `clear-flow`, `reset-flows!`, `reset-last-inputs!` so external
  consumers — production code, the late-bind directory, and the test
  fixtures that `(reset! flows/flows {})` or
  `(resolve 're-frame.flows/last-inputs)` — continue to reach them at
  their documented namespace-qualified names."
  (:require [re-frame.flows.topo :as topo]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.source-coords :as source-coords]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

;; ---- state ---------------------------------------------------------------

(defonce
  ^{:doc "frame-id → flow-id → flow-map. Per-frame so undo / time-travel
          / clear semantics are unambiguous."}
  flows
  (atom {}))

(defonce
  ^{:doc "Per-flow, per-frame last-inputs index: `flow-id → frame-id →
          last-seen input vec`. Drives the dirty-check skip path in
          `re-frame.flows/evaluate-flow!` (rf2-719e).

          Shape note: the outer key is `flow-id` (not `[frame-id flow-id]`
          flat) so the hot-reload invalidation hook (which fires on
          `:flow` registrar replacement) can drop every per-frame entry
          for the replaced flow with one `dissoc` — O(1) instead of the
          prior O(N) walk over all entries. Per-frame slots stay
          independent (each flow id can register against multiple
          frames with its own dirty-check window per Spec 013
          §Frame-scoping)."}
  last-inputs
  (atom {}))

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

;; Every validation throw shares the same ex-data skeleton:
;;
;;   {:where     'rf/reg-flow      ;; user-facing fn for greping the call site
;;    :recovery  :fix-registration  ;; "the caller fixes their flow map and retries"
;;    :reason    "<diagnostic>"
;;    :flow      <the supplied flow>}
;;
;; The `:where` / `:recovery` slots mirror the late-bind-missing throw
;; shape standardised by `re-frame.late-bind/require-fn!` and used by
;; every `re-frame.core-<artefact>` wrapper — tools (Causa, 10x,
;; debuggers) read the same fields uniformly across error sources.
;; Per-clause extras (`:bad-entries` / `:bad-elements`) merge on top.

(defn- flow-error
  "Build the validate-flow ex-info with the standard shape. `error-kw`
  becomes the message AND the `:error` slot; `reason` is the human-
  readable diagnostic; `extras` merges per-clause slots (e.g.
  `:bad-entries`)."
  ([error-kw reason flow] (flow-error error-kw reason flow nil))
  ([error-kw reason flow extras]
   (ex-info (str error-kw)
            (merge {:error    error-kw
                    :where    'rf/reg-flow
                    :recovery :fix-registration
                    :reason   reason
                    :flow     flow}
                   extras))))

(defn- validate-flow [flow]
  (cond
    (nil? (:id flow))
    (throw (flow-error :rf.error/flow-missing-id
                       ":id is required (flow registration must name an id)"
                       flow))

    (not (vector? (:inputs flow)))
    (throw (flow-error :rf.error/flow-bad-inputs
                       ":inputs must be a vector of paths"
                       flow))

    ;; One clause for both "entry isn't a vector" and "entry isn't a
    ;; valid path" — `valid-path?` already requires `vector?`, so the
    ;; older two-arm split (the prior code carried a separate
    ;; `(every? vector? ...)` check) was strictly subsumed by this one.
    ;; The single rejection message names what the entry must be; the
    ;; `:bad-entries` slot points at the offending values so callers
    ;; can fix them without a stack-trace dig.
    (not (every? valid-path? (:inputs flow)))
    (throw (flow-error :rf.error/flow-bad-inputs
                       ":inputs entries must each be a non-empty vector of scalar keys (keyword / string / integer / symbol / boolean)"
                       flow
                       {:bad-entries (vec (remove valid-path? (:inputs flow)))}))

    (not (fn? (:output flow)))
    (throw (flow-error :rf.error/flow-bad-output
                       ":output must be a fn"
                       flow))

    (not (vector? (:path flow)))
    (throw (flow-error :rf.error/flow-bad-path
                       ":path must be a vector"
                       flow))

    (empty? (:path flow))
    (throw (flow-error :rf.error/flow-bad-path
                       ":path must be non-empty (an empty :path would make this flow a depends-on prerequisite of every other flow per Spec 013 §Dependency rule)"
                       flow))

    (not (every? valid-path-element? (:path flow)))
    (throw (flow-error :rf.error/flow-bad-path
                       ":path elements must each be a scalar key (keyword / string / integer / symbol / boolean)"
                       flow
                       {:bad-elements (vec (remove valid-path-element? (:path flow)))}))))

;; ---- registration --------------------------------------------------------

(defn reg-flow
  "Register a flow against a frame. Per Spec 013 — flows are frame-
  scoped: their lifecycle, evaluation, undo / time-travel semantics
  all belong to one frame.

  Required keys on the flow map: :id :inputs :output :path.
  Optional: :doc :spec.

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
     ;; was always `false` (re-frame-10x's flow panel / Causa / pair2
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
       (when (nil? was)
         (trace/emit! :flow :rf.flow/registered
                      {:flow-id flow-id
                       :inputs  (:inputs flow)
                       :path    (:path flow)
                       :frame   frame-id})))
     flow-id)))

(defn- dissoc-in-safe
  "Like `dissoc-in` over `(butlast path) → (last path)` but robust against
  the two unmaterialised-output failure modes flagged by audit rf2-q25os:

  - **Unmaterialised parent.** When a flow with `:path [:step-2 :result]`
    is cleared BEFORE its first drain, the parent slot `:step-2` may not
    exist. The naïve `(update-in cur [:step-2] dissoc :result)` returns
    `(dissoc nil :result)` ⇒ `nil`, producing `{:step-2 nil}` — a
    spurious nil parent. Detect this case and leave `cur` unchanged.
  - **Non-map intermediate.** When an intermediate path step holds a
    non-map value (a scalar already wrote past the flow's planned path),
    the naïve `update-in` calls `(dissoc 1 :result)` and throws
    `ClassCastException`. Treat this as a no-op — the flow's `:path`
    never materialised, so there's nothing to clear.

  Single-element paths and non-vector paths are handled by the caller's
  earlier branches; this helper is only called for `(>= (count path) 2)`."
  [cur path]
  (let [parent-path (vec (butlast path))
        leaf        (last path)
        parent      (get-in cur parent-path ::missing)]
    (cond
      ;; Parent was never materialised — leave cur as-is. Per audit
      ;; rf2-q25os Repro 1: registering a nested-path flow then clearing
      ;; before any drain would write `{<parent> nil}` otherwise.
      (or (= ::missing parent) (nil? parent)) cur
      ;; Parent is non-map (scalar / vector / set) — there's no
      ;; meaningful "dissoc this leaf" on a non-map intermediate. Per
      ;; audit rf2-q25os Repro 2: throwing ClassCastException for a
      ;; cleanup operation is poor manners; leave the value untouched
      ;; (it's not OUR flow's output anyway).
      (not (map? parent)) cur
      :else (update-in cur parent-path dissoc leaf))))

(defn clear-flow
  "Deregister a flow from a frame; dissoc its output path from that
  frame's app-db (only that frame). Frame defaults to (current-frame).

  Per audit rf2-q25os: the nested-path dissoc is robust against the
  output path never having been materialised (no spurious nil parent
  created) and against a non-map intermediate (no ClassCastException
  thrown) — see `dissoc-in-safe` above."
  ([id] (clear-flow id {}))
  ([id {:keys [frame] :as _opts}]
   (let [frame-id (or frame (frame/current-frame))]
     (when-let [flow (get-in @flows [frame-id id])]
       (let [path (:path flow)]
         (when-let [container (frame/get-frame-db frame-id)]
           (let [cur    (adapter/read-container container)
                 ;; rf2-aqt7: when :path is a single-element vector [:k],
                 ;; (butlast [:k]) is () and (update-in cur [] dissoc :k)
                 ;; does NOT dissoc — Clojure's update-in on the empty
                 ;; path falls into (assoc {} nil (apply f val args)),
                 ;; producing {... nil nil}. Special-case length 1 so
                 ;; the leaf is dissoc'd directly.
                 ;;
                 ;; The (>= 2) branch routes through `dissoc-in-safe`
                 ;; which handles the unmaterialised-parent / non-map-
                 ;; intermediate cases without writing nil parents or
                 ;; throwing (per audit rf2-q25os).
                 new-db (cond
                          (not (vector? path))         (dissoc cur path)
                          (empty? path)                cur
                          (= 1 (count path))           (dissoc cur (first path))
                          :else                        (dissoc-in-safe cur path))]
             ;; Per rf2-2vpac: skip `replace-container!` when the dissoc
             ;; branch was a no-op (empty-path, missing key, or
             ;; `dissoc-in-safe` returning `cur` literally on
             ;; unmaterialised-parent / non-map-intermediate). Otherwise
             ;; we trigger reactive sub-cache invalidation for a no-op
             ;; write — cheap-but-needless walk of the sub graph
             ;; (`identical?` is O(1); the prior unconditional write
             ;; forced an O(n) sub-graph walk for every clear of an
             ;; absent slot, common during teardown).
             (when-not (identical? new-db cur)
               (adapter/replace-container! container new-db))))
         (swap! flows update frame-id dissoc id)
         ;; `last-inputs` is shaped {flow-id {frame-id inputs}} — clear
         ;; this frame's slot for the cleared flow id, then drop the
         ;; whole flow row if no other frame still holds an entry.
         (swap! last-inputs
                (fn [m]
                  (let [m' (update m id dissoc frame-id)]
                    (if (empty? (get m' id))
                      (dissoc m' id)
                      m'))))
         ;; Only unregister from the registrar if this was the LAST
         ;; frame holding the flow id — otherwise other frames still
         ;; need the registry slot for hot-reload tracking.
         ;;
         ;; Cost shape: O(F) over frame count, short-circuits on first
         ;; hit via `not-any?` (cheaper than the prior double-negated
         ;; `every? not contains?`). At v1 frame counts (typically 1-3
         ;; — :rf/default plus the occasional `:left`/`:right`/`:scratch`)
         ;; this is trivial. If a profile-driven hot path shows
         ;; many-frame topologies stressing `clear-flow`, the optimisation
         ;; is a reverse index `{flow-id #{frame-id ...}}` maintained by
         ;; `reg-flow` and `clear-flow` — the contains-by-other-frame
         ;; check then becomes O(1). Deferred until measurement warrants
         ;; the extra atom.
         (when (not-any? #(contains? % id) (vals @flows))
           (registrar/unregister! :flow id))
         ;; Per Spec 009 §:op-type vocabulary: :rf.flow/cleared fires after
         ;; clear-flow has removed the flow from the per-frame registry
         ;; and dissoc-in'd its output path. Tools observe this to drop
         ;; their per-flow display state.
         (trace/emit! :flow :rf.flow/cleared
                      {:flow-id id
                       :path    path
                       :frame   frame-id})))
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
      (swap! last-inputs
             (fn [m]
               (reduce-kv
                 (fn [acc flow-id by-frame]
                   (let [by-frame' (dissoc by-frame frame-id)]
                     (if (empty? by-frame')
                       acc
                       (assoc acc flow-id by-frame'))))
                 {}
                 m)))
      ;; Registrar prune: drop the `:flow` slot for any flow-id the
      ;; destroyed frame owned that no other frame still holds. The
      ;; surviving-frame check mirrors `clear-flow`'s shape — O(F) over
      ;; remaining frame count, short-circuits on first hit.
      (let [remaining @flows]
        (doseq [flow-id owned-flow-ids]
          (when (not-any? #(contains? % flow-id) (vals remaining))
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
  [{:keys [kind id]}]
  (when (= kind :flow)
    ;; O(1) dissoc — `last-inputs`'s outer key is `flow-id`, so a single
    ;; `dissoc` drops every per-frame entry for the replaced flow at
    ;; once. The prior shape `{[frame-id flow-id] inputs}` required an
    ;; O(N) walk filtering by inner-key match.
    (swap! last-inputs dissoc id)))

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
  / pair2 / Causa harness calling `reset-flows!` standalone (the
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
