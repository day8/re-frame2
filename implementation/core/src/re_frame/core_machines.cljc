(ns re-frame.core-machines
  "Public-API wrappers for the optional machines artefact (Spec 005).
  Implementation ships in `day8/re-frame2-machines` (`re-frame.machines`).
  See [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention).

  `reg-machine` / `reg-machine*` keep a bespoke shape (they share the
  `:where`-symbol parameter via `reg-machine-impl` so the macro and the
  plain-fn surface raise with their own faithful `:where` symbol). Sugar
  fns (`dispatch-to-system`, `sub-machine`, `machine-has-tag?`) are not late-
  bind surfaces — they layer over `router/dispatch!` / `subs/subscribe`."
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [defwrapper]]
                                        :cljs [:refer-macros [defwrapper]])]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.router :as router]
            [re-frame.subs :as subs]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private machines-artefact
  {:error-keyword :rf.error/machines-artefact-missing
   :maven         "day8/re-frame2-machines"
   :require-ns    "re-frame.machines"})

(defwrapper make-machine-handler
  "Build an event-fx handler from a machine spec. Per Spec 005
  §Registration. Late-bound via :machines/make-machine-handler."
  {:hook :machines/make-machine-handler :artefact machines-artefact :on-absent :throw}
  ([machine] :delegate))

(defwrapper machine-transition
  "Pure (machine, snapshot, event) -> [snapshot fx]. Per Spec 005
  §Drain semantics §Level 3. Late-bound via :machines/machine-transition."
  {:hook :machines/machine-transition :artefact machines-artefact :on-absent :throw}
  ([machine snapshot event] :delegate))

(defwrapper machines
  "Return a sequence of registered machine ids. Per Spec 005
  §Querying machines. Returns `[]` when the machines artefact is not
  on the classpath."
  {:hook :machines/machines :artefact machines-artefact :on-absent :empty-vec}
  ([] :delegate))

(defwrapper machine-meta
  "Return the registered machine spec map for machine-id, or nil. Per
  Spec 005 §Querying machines. Returns nil when the machines artefact
  is not on the classpath."
  {:hook :machines/machine-meta :artefact machines-artefact :on-absent :nil}
  ([machine-id] :delegate))

(defwrapper machine-by-system-id
  "Look up the spawned-machine id currently bound to `system-id` in the
  active frame's `[:rf/runtime :machines :system-ids]` reverse index, or nil. The optional
  `frame-id` arg targets an explicit frame; without it, resolution uses
  the current frame (per `with-frame` / frame-provider, defaulting to
  `:rf/default`).

  Per Spec 005 §Named addressing via :system-id. Returns nil when the
  machines artefact is not on the classpath."
  {:hook :machines/machine-by-system-id :artefact machines-artefact :on-absent :nil}
  ([system-id]          :delegate)
  ([system-id frame-id] :delegate))

;; ---- reg-machine* / reg-machine — bespoke -------------------------------
;;
;; Both surfaces share the late-bind throw via `reg-machine-impl` but stamp
;; their own `:where` symbol on the missing-artefact ex-info so the trace
;; matches what the user wrote at the call site.

(defn- register-machine-handler-metas!
  "Per rf2-npvsx (supersedes rf2-ypu5i / rf2-8bp3): write per-(machine-id,
  id) handler-meta entries into the registrar under `:machine-guard` /
  `:machine-action` for every co-located guard / action entry stamped by
  the `reg-machine` macro. Reads the CO-LOCATED `:guards` / `:actions`
  maps — each entry is `{:fn <fn> :source-coords {...} :source-code
  \"...\"}` in dev (the `:source-*` slots absent in production). No-op
  under `interop/debug-enabled? false` so production CLJS bundles DCE the
  call (the macro emits no `:source-*` slots in prod, so there is nothing
  to register beyond the bare `:fn`, and the gate skips the write).

  Only entries carrying `:source-code` are registered — a keyword-
  reference entry (`{<id> :other-id}`) and a bare `{:fn <fn>}` (prod, or a
  let-bound fn the walker skipped) carry no source and need no handler-meta
  slot. The registered meta carries `:rf.handler/source` (parity with the
  reg-event-* surface, Spec 009), `:handler-fn` (the actual fn), the
  `:rf/guard-id` / `:rf/action-id` marker (so tools enumerating
  `(rf/registrations :machine-guard)` pivot without re-parsing the 2-vector
  id), `:rf/machine-id` (the scope), and the merged source-coords."
  [machine-id machine]
  (when interop/debug-enabled?
    (let [write! (fn [kind marker-key slot-key]
                   (doseq [[id entry] (get machine slot-key)
                           :when (and (map? entry) (:source-code entry))]
                     (let [coord (:source-coords entry)
                           meta  (cond-> {marker-key         id
                                          :rf/machine-id     machine-id
                                          :rf.handler/source (:source-code entry)
                                          :handler-fn        (:fn entry)}
                                   coord (merge coord))]
                       (registrar/register! kind [machine-id id] meta))))]
      (write! :machine-guard  :rf/guard-id  :guards)
      (write! :machine-action :rf/action-id :actions))))

(defn- clear-machine-handler-metas!
  "Drop every `:machine-guard` / `:machine-action` registrar entry
  scoped to `machine-id`. Called before re-registering a machine so a
  hot-reload that renames or removes a guard/action does not leave
  stale entries behind. No-op under `interop/debug-enabled? false`."
  [machine-id]
  (when interop/debug-enabled?
    (doseq [kind [:machine-guard :machine-action]]
      (doseq [id (keys (registrar/registrations kind))]
        (when (and (vector? id) (= machine-id (first id)))
          (registrar/unregister! kind id))))))

(defn ^:private reg-machine-impl
  "Shared impl behind both `reg-machine*` (plain-fn surface) and the
  `re-frame.core/reg-machine` macro's emitted form. The `where-sym`
  arg lets each user-facing surface stamp its own symbol on the
  missing-artefact error trace so `:where` matches what the user
  wrote at the call site.

  Per rf2-npvsx (supersedes rf2-ypu5i): writes per-(machine-id, guard-id)
  and per-(machine-id, action-id) handler-meta entries into the registrar
  under `:machine-guard` / `:machine-action` kinds so the xray
  focused-transition lens can render fn-source via `(rf/handler-meta
  :machine-guard [machine-id guard-id])`. The source is read off the
  co-located `:guards` / `:actions` entry maps (`{:fn .. :source-code ..}`).
  Stale entries from a prior registration of the same `machine-id` are
  cleared first so a hot-reload that renames a guard does not leave the
  old id around. Production-elided via `interop/debug-enabled?`."
  [where-sym machine-id machine]
  ;; Run the handler-meta side-table churn around the canonical
  ;; registration. Clear-first preserves the slot-invariant under
  ;; hot reload (rename / remove a guard id and the stale slot
  ;; disappears). Both clear and register no-op under production
  ;; elision (the macro emits co-located entries with no `:source-code`
  ;; slot in prod, so there's nothing to register, and the unregister
  ;; loop touches an empty map).
  (clear-machine-handler-metas! machine-id)
  (let [result ((late-bind/require-fn! :machines/reg-machine
                                       where-sym
                                       machines-artefact
                                       {:machine-id machine-id})
                machine-id machine)]
    (register-machine-handler-metas! machine-id machine)
    result))

(defn reg-machine*
  "Plain-fn surface for machine registration. Per Spec 005 §reg-machine
  vs reg-machine* (rf2-8bp3). Used by code-gen pipelines that already
  carry a stamped spec, REPL workflows that bypass the macro path, and
  the macro's own emitted form. Programmatic callers see no
  per-element source-coord index (only the macro can walk the literal
  spec at expansion time). Late-bound via :machines/reg-machine."
  [machine-id machine]
  (reg-machine-impl 'rf/reg-machine* machine-id machine))

(defn reg-machine
  "Fn-form delegate the `re-frame.core/reg-machine` macro routes through
  when the spec form is not stamped (no per-element source-coords). The
  separation from `reg-machine*` keeps `:where` symbols faithful to the
  user-facing surface — `rf/reg-machine` raises with `:where
  'rf/reg-machine`, `rf/reg-machine*` raises with `:where 'rf/reg-machine*`.

  Callers should NOT invoke this directly — use `rf/reg-machine`
  (macro) or `rf/reg-machine*` (plain fn). It is public only because
  the macro emits a reference to it."
  [machine-id machine]
  (reg-machine-impl 'rf/reg-machine machine-id machine))

;; ---- sugar surfaces — not late-bind wrappers -----------------------------

(defn dispatch-to-system
  "Sugar: dispatch `event` to the spawned-machine bound to `system-id`
  in the active frame. Equivalent to
  `(when-let [m (machine-by-system-id system-id)] (dispatch [m event]))`,
  with a no-op fall-through when the system-id is unbound. Per Spec 005
  §Cross-machine messaging by name."
  ([system-id event]
   (when-let [machine-id (machine-by-system-id system-id)]
     (router/dispatch! [machine-id event])))
  ([system-id event frame-id]
   (when-let [machine-id (machine-by-system-id system-id frame-id)]
     (router/dispatch! [machine-id event] {:frame frame-id}))))

(defn sub-machine
  "Subscribe to a machine's snapshot. Sugar over (subscribe [:rf/machine
  machine-id]). Returns a reaction whose value is the snapshot
  {:state <kw> :data <map>} or nil if the machine is not yet
  initialised.

  The `sub-` prefix is re-frame's subscription-family verb (sibling of
  `subscribe`, `subscribe-once`, `subscriber`). It does NOT denote a
  child-machine relationship — declarative child-machine binding uses
  `:spawn` per rf2-5r4q2. Per audit-of-audits (rf2-cthfn) state-machines
  #11 and Spec 005 §Subscribing to machines via sub-machine."
  [machine-id]
  (subs/subscribe [:rf/machine machine-id]))

(defn machine-has-tag?
  "Subscribe to a machine's `:fsm/tags` containment-bit for `tag`. Sugar
  over `(subscribe [:rf/machine-has-tag? machine-id tag])`. Returns a
  reaction whose value is `true` iff the machine's current
  snapshot's `:tags` set contains `tag` — `false` for an unknown or
  not-yet-initialised machine.

  Per Spec 005 §State tags (rf2-ee0d / Nine States Stage 1).

  Composable with the rest of the sub graph (a Layer-3 sub may chain
  off this one) and elides on production builds the same way every
  framework sub does — the underlying registration is a standard
  `reg-sub`, no new registry."
  [machine-id tag]
  (subs/subscribe [:rf/machine-has-tag? machine-id tag]))

