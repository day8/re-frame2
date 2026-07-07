(ns re-frame.core-machines
  "Public-API wrappers for the optional machines artefact (Spec 005).
  Implementation ships in `day8/re-frame2-machines` (`re-frame.machines`).
  See [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention).

  `reg-machine` / `reg-machine*` keep a bespoke shape (they share the
  `:where`-symbol parameter via `reg-machine-impl` so the macro and the
  plain-fn surface raise with their own faithful `:where` symbol). (The
  `dispatch-to-system` FN was demoted off the facade and relocated to
  `re-frame.machines` — rf2-gkt25a / rf2-80mmlf. The `machine-has-tag?` /
  `sub-machine` subscription-sugar fns were removed — rf2-il99l3, reversing
  rf2-2cmcas — leaving the `[:rf/machine-has-tag? …]` / `[:rf/machine …]`
  subscription vectors as the one machine-read grammar.)"
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [defwrapper]]
                                        :cljs [:refer-macros [defwrapper]])]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]))

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
  active frame's `[:rf.runtime/machines :system-ids]` reverse index, or nil.

  EP-0002 carried invariant: the 1-arity ambient form resolves the frame
  through the scope/hold chain (`with-frame` / frame-provider / a carried
  stamp) via `frame/require-current-frame!` — a lookup under no established
  scope raises `:rf.error/no-frame-context`, with NO `:rf/default` floor.
  Pass the public opts form `(machine-by-system-id system-id {:frame
  target})` to name a frame explicitly (async callbacks / tools /
  cross-frame lookups); `target` is a frame-id keyword or a live frame
  value. Per rf2-f28bno the 2-arity is SHAPE-DISCRIMINATED on the second
  arg (mirroring `subscribe`'s frame-first arity): an opts map ⇒ the public
  form; a bare frame target ⇒ the internal frame-last plumbing.

  Per Spec 005 §Named addressing via :system-id + Spec 002 §Resolver
  surface. Returns nil when the machines artefact is not on the classpath."
  {:hook :machines/machine-by-system-id :artefact machines-artefact :on-absent :nil}
  ([system-id]            :delegate)
  ([system-id frame-or-opts] :delegate))

;; ---- machine guard/action handler-meta — DERIVED, not registered --------
;;
;; Per rf2-ftrcv (supersedes rf2-ypu5i / rf2-npvsx): machine guards/actions
;; are NOT registrar kinds. Their dev-only fn-source handler-meta surface is
;; DERIVED on demand from the machine's existing `:event` registration spec
;; — the `reg-machine` macro co-locates `{:fn .. :source-coords ..
;; :source-code ..}` onto each `:guards` / `:actions` entry, and `reg-machine*`
;; stores the whole stamped spec under `:rf/machine` in the `:event`
;; registrar entry (see `re-frame.machines.lifecycle-fx.registration`). So the
;; source the Xray focused-transition lens + re-frame-pair source-jump render
;; already lives in the registry; we read it back rather than duplicating it
;; into a second registrar entry.

(def ^:private machine-kind->slot
  "Map the two derived machine handler-meta kinds to their machine-spec
  slot + the bare-id marker key the derived meta stamps (parity with the
  former registrar entries, so tools enumerating the meta pivot on
  `:rf/guard-id` / `:rf/action-id` without re-parsing the 2-vector id)."
  {:machine-guard  {:slot :guards  :marker-key :rf/guard-id}
   :machine-action {:slot :actions :marker-key :rf/action-id}})

(defn machine-handler-meta
  "Derive the dev-only fn-source handler-meta for a machine guard/action
  from the machine's `:event` registration spec (rf2-ftrcv). `kind` is
  `:machine-guard` / `:machine-action`; `id` is the 2-vector
  `[<machine-id> <guard-or-action-id>]`. Returns the same shape the former
  `:machine-guard` / `:machine-action` registrar entries carried —
  `{:rf/guard-id|:rf/action-id <id> :rf/machine-id <mid> :rf.handler/source
  <pr-str> :handler-fn <fn>}` merged with the per-element `:source-coords`
  (`:ns` / `:line` / `:file` / `:column`) — or nil.

  `re-frame.core/handler-meta` dispatches the two machine kinds here; all
  other kinds fall through to `registrar/handler-meta`. The Xray lens +
  re-frame-pair source-jump call sites are unchanged — they still call
  `(rf/handler-meta :machine-guard [machine-id guard-id])`.

  Returns nil when:
    - `kind` is not a machine kind, or `id` is not a 2-vector;
    - the machine-id has no `:event` registration (or it is not a machine);
    - the guard/action id is absent, is a keyword-reference indirection
      (`{<id> :other-id}`), or carries no `:source-code` (the plain-fn
      `reg-machine*` surface, a let-bound fn the macro walker skipped, or a
      production build where the `:source-*` slots are elided).

  Production-elided per Spec 009: under `interop/debug-enabled? false` the
  derivation returns nil immediately (the `:event` spec carries no
  `:source-*` slots in `:advanced` + `goog.DEBUG=false` bundles anyway, so
  there is nothing to derive)."
  [kind id]
  (when interop/debug-enabled?
    (when-let [{:keys [slot marker-key]} (machine-kind->slot kind)]
      (when (and (vector? id) (= 2 (count id)))
        (let [[machine-id element-id] id
              spec  (:rf/machine (registrar/lookup :event machine-id))
              entry (get-in spec [slot element-id])]
          (when (and (map? entry) (:source-code entry))
            (cond-> {marker-key         element-id
                     :rf/machine-id     machine-id
                     :rf.handler/source (:source-code entry)
                     :handler-fn        (:fn entry)}
              (:source-coords entry) (merge (:source-coords entry)))))))))

;; ---- reg-machine* / reg-machine — bespoke -------------------------------
;;
;; Both surfaces share the late-bind throw via `reg-machine-impl` but stamp
;; their own `:where` symbol on the missing-artefact ex-info so the trace
;; matches what the user wrote at the call site.

(defn ^:private reg-machine-impl
  "Shared impl behind both `reg-machine*` (plain-fn surface) and the
  `re-frame.core/reg-machine` macro's emitted form. The `where-sym`
  arg lets each user-facing surface stamp its own symbol on the
  missing-artefact error trace so `:where` matches what the user
  wrote at the call site.

  Delegates straight to the machines artefact's `:machines/reg-machine`
  hook. Per rf2-ftrcv there is NO registrar side-table to maintain — the
  machine's guard/action fn-source handler-meta is derived on demand from
  the `:event` registration spec via [[machine-handler-meta]].

  Per rf2-wgmipl the 4-arg form threads an `opts` registration-metadata map
  (the `:schema` event-vector boundary validator, plus any other metadata)
  to the hook's 3-arity. Per rf2-wvh95f F2 `opts` is the canonical Spec 001
  MIDDLE slot, so the hook is called `(machine-id opts machine)`. The 3-arg
  form (no opts) keeps the bare 2-arity so programmatic callers / hooks that
  only implement the 2-arity still resolve."
  ([where-sym machine-id machine]
   ((late-bind/require-fn! :machines/reg-machine
                           where-sym
                           machines-artefact
                           {:machine-id machine-id})
    machine-id machine))
  ([where-sym machine-id opts machine]
   ((late-bind/require-fn! :machines/reg-machine
                           where-sym
                           machines-artefact
                           {:machine-id machine-id})
    machine-id opts machine)))

(defn reg-machine*
  "Plain-fn surface for machine registration. Per Spec 005 §reg-machine
  vs reg-machine* (rf2-8bp3). Used by code-gen pipelines that already
  carry a stamped spec, REPL workflows that bypass the macro path, and
  the macro's own emitted form. Programmatic callers see no
  per-element source-coord index (only the macro can walk the literal
  spec at expansion time). Late-bound via :machines/reg-machine.

  Per rf2-wgmipl the 3-arity accepts an `opts` registration-metadata map
  whose `:schema` key validates the dispatched OUTER event vector at the
  `:where :event` boundary — the machine + event-vector-schema shape (login /
  realworld auth). Per rf2-wvh95f F2 `opts` is the canonical Spec 001 MIDDLE
  slot: `(reg-machine* machine-id opts machine)`. The framework-owned
  `:rf/machine?` / `:rf/machine` keys are stamped by the registration home and
  MUST NOT appear in `opts`."
  ([machine-id machine]
   (reg-machine-impl 'rf/reg-machine* machine-id machine))
  ([machine-id opts machine]
   (reg-machine-impl 'rf/reg-machine* machine-id opts machine)))

(defn reg-machine
  "Fn-form delegate the `re-frame.core/reg-machine` macro routes through
  when the spec form is not stamped (no per-element source-coords). The
  separation from `reg-machine*` keeps `:where` symbols faithful to the
  user-facing surface — `rf/reg-machine` raises with `:where
  'rf/reg-machine`, `rf/reg-machine*` raises with `:where 'rf/reg-machine*`.

  Callers should NOT invoke this directly — use `rf/reg-machine`
  (macro) or `rf/reg-machine*` (plain fn). It is public only because
  the macro emits a reference to it.

  Per rf2-wgmipl the 3-arity threads the macro's `opts` registration-metadata
  map (carrying the event-vector `:schema`) through to the hook. Per rf2-wvh95f
  F2 `opts` is the canonical Spec 001 MIDDLE slot: `(reg-machine machine-id
  opts machine)`."
  ([machine-id machine]
   (reg-machine-impl 'rf/reg-machine machine-id machine))
  ([machine-id opts machine]
   (reg-machine-impl 'rf/reg-machine machine-id opts machine)))

;; ---- dispatch-to-system relocation note ----------------------------------
;;
;; The `dispatch-to-system` FN was DEMOTED off the `re-frame.core` facade
;; (rf2-gkt25a / rf2-80mmlf — exactly one in-repo caller) and RELOCATED to
;; `re-frame.machines` (the optional machines artefact ns) as an
;; implementation-tier helper. The canonical action-side surface is the
;; reserved `[:rf.machine/dispatch-to-system [system-id event]]` fx tuple.
;;
;; The `machine-has-tag?` / `sub-machine` subscription-sugar fns that once
;; lived here were REMOVED (rf2-il99l3, reversing rf2-2cmcas). A machine read
;; is a subscription VECTOR — `(subscribe [:rf/machine-has-tag? machine-id
;; tag])` / `(subscribe [:rf/machine machine-id])` — one read grammar; the
;; `{:frame …}` target is carried by `subscribe`'s own frame-first arity.

