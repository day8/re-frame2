(ns re-frame.core-machines
  "Public-API wrappers for the optional machines artefact (Spec 005).
  Implementation ships in `day8/re-frame2-machines`
  (`re-frame.machines` ns) per rf2-xbtj.

  Per [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention) — wrappers
  look the producing fns up via the late-bind hook table at call time;
  consumers reach the surfaces through `re-frame.core` re-exports.

  Per-feature carve-out: the machines artefact pulls the machine
  registry, the entry/exit cascade engine, and the `:rf/machine` /
  `:rf/machine-has-tag?` framework subs — none of which appear on a
  consumer's classpath when this wrapper's hooks are unregistered.

  Per rf2-h824v the canonical late-bind wrappers below are emitted by
  the `re-frame.core-artefact/defwrapper` factory. `reg-machine` /
  `reg-machine*` keep a bespoke shape — they share the `:where`-symbol
  parameter via `reg-machine-impl` so the macro and the plain-fn
  surface raise with their own faithful `:where` symbol. Sugar fns
  (`dispatch-to-system`, `sub-machine`, `has-tag?`) are not late-bind
  surfaces — they layer over `router/dispatch!` / `subs/subscribe`."
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [defwrapper]]
                                        :cljs [:refer-macros [defwrapper]])]
            [re-frame.late-bind :as late-bind]
            [re-frame.router :as router]
            [re-frame.subs :as subs]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private machines-artefact
  {:error-keyword :rf.error/machines-artefact-missing
   :maven         "day8/re-frame2-machines"
   :require-ns    "re-frame.machines"})

(defwrapper create-machine-handler
  "Build an event-fx handler from a machine spec. Per Spec 005
  §Registration. Late-bound via :machines/create-machine-handler."
  {:hook :machines/create-machine-handler :artefact machines-artefact :on-absent :throw}
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
  active frame's `[:rf/system-ids]` reverse index, or nil. The optional
  `frame-id` arg targets an explicit frame; without it, resolution uses
  the current frame (per `with-frame` / frame-provider, defaulting to
  `:rf/default`).

  Per Spec 005 §Named addressing via :system-id. Returns nil when the
  machines artefact is not on the classpath."
  {:hook :machines/machine-by-system-id :artefact machines-artefact :on-absent :nil}
  ([system-id]          :delegate)
  ([system-id frame-id] :delegate))

;; ---- reg-machine* / reg-machine — bespoke per rf2-8bp3 -------------------
;;
;; Both surfaces share the late-bind throw via `reg-machine-impl` but stamp
;; their own `:where` symbol on the missing-artefact ex-info so the trace
;; matches what the user wrote at the call site.

(defn ^:private reg-machine-impl
  "Shared impl behind both `reg-machine*` (plain-fn surface) and the
  `re-frame.core/reg-machine` macro's emitted form. The `where-sym`
  arg lets each user-facing surface stamp its own symbol on the
  missing-artefact error trace so `:where` matches what the user
  wrote at the call site."
  [where-sym machine-id machine]
  ((late-bind/require-fn! :machines/reg-machine
                          where-sym
                          machines-artefact
                          {:machine-id machine-id})
   machine-id machine))

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
  initialised. Per Spec 005 §Subscribing to machines via sub-machine."
  [machine-id]
  (subs/subscribe [:rf/machine machine-id]))

(defn has-tag?
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
