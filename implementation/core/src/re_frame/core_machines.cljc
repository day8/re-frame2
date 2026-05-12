(ns re-frame.core-machines
  "Public-API wrappers for the optional machines artefact (Spec 005).

  Per rf2-xbtj the machines implementation ships in the
  `day8/re-frame2-machines` Maven artefact. The core artefact MUST NOT
  statically `:require [re-frame.machines]` — that would pull the
  machines namespace and its `:rf/machine` sub registration onto every
  consumer's classpath even when no machine is registered.

  The fns in this namespace look the machines API up through the
  late-bind hook table at call time, which the machines artefact
  populates from its own ns-load.

  Per rf2-hoiu these wrappers live here (and `re-frame.core` re-exports
  them) so `core.cljc` is not cluttered with optional-artefact glue.
  The single-import contract is preserved: users continue to write
  `rf/reg-machine` after `(:require [re-frame.core :as rf])`."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.router :as router]
            [re-frame.subs :as subs]))

(defn create-machine-handler
  "Build an event-fx handler from a machine spec. Per Spec 005
  §Registration. Late-bound via :machines/create-machine-handler."
  [machine]
  (if-let [f (late-bind/get-fn :machines/create-machine-handler)]
    (f machine)
    (throw (ex-info ":rf.error/machines-artefact-missing"
                    {:where    'create-machine-handler
                     :recovery :no-recovery
                     :reason   "rf/create-machine-handler requires day8/re-frame2-machines on the classpath; add it to deps and require re-frame.machines at app boot."}))))

(defn machine-transition
  "Pure (machine, snapshot, event) -> [snapshot fx]. Per Spec 005
  §Drain semantics §Level 3. Late-bound via :machines/machine-transition."
  [machine snapshot event]
  (if-let [f (late-bind/get-fn :machines/machine-transition)]
    (f machine snapshot event)
    (throw (ex-info ":rf.error/machines-artefact-missing"
                    {:where    'machine-transition
                     :recovery :no-recovery
                     :reason   "rf/machine-transition requires day8/re-frame2-machines on the classpath; add it to deps and require re-frame.machines at app boot."}))))

(defn machines
  "Return a sequence of registered machine ids. Per Spec 005
  §Querying machines. Returns `[]` when the machines artefact is not
  on the classpath."
  []
  (if-let [f (late-bind/get-fn :machines/machines)]
    (f)
    []))

(defn machine-meta
  "Return the registered machine spec map for machine-id, or nil. Per
  Spec 005 §Querying machines. Returns nil when the machines artefact
  is not on the classpath."
  [machine-id]
  (when-let [f (late-bind/get-fn :machines/machine-meta)]
    (f machine-id)))

(defn machine-by-system-id
  "Look up the spawned-machine id currently bound to `system-id` in the
  active frame's `[:rf/system-ids]` reverse index, or nil. The optional
  `frame-id` arg targets an explicit frame; without it, resolution uses
  the current frame (per `with-frame` / frame-provider, defaulting to
  `:rf/default`).

  Per Spec 005 §Named addressing via :system-id. Returns nil when the
  machines artefact is not on the classpath."
  ([system-id]
   (when-let [f (late-bind/get-fn :machines/machine-by-system-id)]
     (f system-id)))
  ([system-id frame-id]
   (when-let [f (late-bind/get-fn :machines/machine-by-system-id)]
     (f system-id frame-id))))

(defn ^:private reg-machine-impl
  "Shared impl behind both `reg-machine*` (plain-fn surface) and the
  `re-frame.core/reg-machine` macro's emitted form. The `where-sym`
  arg lets each user-facing surface stamp its own symbol on the
  missing-artefact error trace so `:where` matches what the user
  wrote at the call site."
  [where-sym reason machine-id machine]
  (if-let [f (late-bind/get-fn :machines/reg-machine)]
    (f machine-id machine)
    (throw (ex-info ":rf.error/machines-artefact-missing"
                    {:where      where-sym
                     :machine-id machine-id
                     :recovery   :no-recovery
                     :reason     reason}))))

(defn reg-machine*
  "Plain-fn surface for machine registration. Per Spec 005 §reg-machine
  vs reg-machine* (rf2-8bp3). Used by code-gen pipelines that already
  carry a stamped spec, REPL workflows that bypass the macro path, and
  the macro's own emitted form. Programmatic callers see no
  per-element source-coord index (only the macro can walk the literal
  spec at expansion time). Late-bound via :machines/reg-machine."
  [machine-id machine]
  (reg-machine-impl
    'reg-machine*
    "rf/reg-machine* requires day8/re-frame2-machines on the classpath; add it to deps and require re-frame.machines at app boot."
    machine-id machine))

(defn reg-machine
  "Fn-form delegate the `re-frame.core/reg-machine` macro routes through
  when the spec form is not stamped (no per-element source-coords). The
  separation from `reg-machine*` keeps `:where` symbols faithful to the
  user-facing surface — `rf/reg-machine` raises with `:where
  'reg-machine`, `rf/reg-machine*` raises with `:where 'reg-machine*`.

  Callers should NOT invoke this directly — use `rf/reg-machine`
  (macro) or `rf/reg-machine*` (plain fn). It is public only because
  the macro emits a reference to it."
  [machine-id machine]
  (reg-machine-impl
    'reg-machine
    "rf/reg-machine requires day8/re-frame2-machines on the classpath; add it to deps and require re-frame.machines at app boot."
    machine-id machine))

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
