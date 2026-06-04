(ns re-frame.core-reg-macros
  "Helpers for the registration-site `reg-*` macros. Per Spec 001
  §Source-coordinate capture (CLJS reference) and Tool-Pair §Source-
  mapping: every `reg-*` registration's metadata carries `:ns` /
  `:line` / `:file` auto-supplied at compile time. The canonical
  capture skeleton — `(meta &form)`'s `:line` / `:column` plus `*ns*` /
  `*file*` bound around the underlying fn — is centralised here as
  the `with-coords-form` helper plus the `defreg-macro` macro-defining
  macro.

  Carved out of `re-frame.core` so the public namespace stays a thin
  facade focused on user-visible Var resolution rather than macro
  expansion bulk; this ns owns the cohesive responsibility of every
  registration-site `reg-*` macro's compile-time coord stamping. The
  user-facing `defmacro reg-event-db` / `reg-sub` / `reg-flow` / …
  shells live in `re-frame.core` itself (they MUST, so
  `rf/reg-event-db` resolves alias-qualified per Clojure's
  `ns-alias/Var` lookup); each shell is a one-line `(defreg-macro …)`
  form that delegates here.

  rf2-xnym: the rationale for `(symbol (str (ns-name *ns*)))` rather
  than `(ns-name *ns*)` — in CLJS macro context the ns-symbol may
  carry the consumer namespace's `:doc` metadata, which would then get
  serialised into the bundle and defeat production elision. Every
  reg-* macro routes its `(meta &form)` / `*file*` / `*ns*` capture
  through `with-coords-form` so the rationale lives in one place.

  File naming uses the flat dash-form (per Conventions; rf2-2vbm):
  CLJS `goog.provide` for `re-frame.core` overwrites its parent
  object, which would wipe a previously-loaded `re-frame.core.X`."
  (:require [re-frame.source-coords :as source-coords]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- with-coords-form ----------------------------------------------------

#?(:clj
   (defn with-coords-form
     "Wrap `body-form` in a binding of `source-coords/*pending-coords*`
     to the compile-time coord map for `form-meta` / `file` / `ns-sym`.
     Caller passes `(meta &form)`, `*file*`, and the metadata-free
     ns-symbol (per the rf2-xnym rationale above). Returns a syntax-
     quote-safe form suitable for a reg-* defmacro to emit.

     The rf2-52gw helper: centralises the `(binding [...] (target ...))`
     skeleton that every reg-* macro emits, so each defmacro becomes a
     one-line delegation rather than a 12-line repetition.

     Per rf2-3un2g §Production elision: the bound coord-map is emitted
     under an `(if interop/debug-enabled? <dev> <prod>)` gate. Dev rides
     the full [[source-coords/coords-form]] (with `:column`); prod rides
     [[source-coords/prod-coords-form]] (no `:column`). Closure folds the
     constant `interop/debug-enabled?` (alias of `goog.DEBUG`) under
     `:advanced` + `goog.DEBUG=false` and DCEs the dev branch — only
     the slim prod coords literal survives into the bundle. The bound
     coords are STILL captured at runtime via `*pending-coords*` so
     `registrar/register!`'s `remember-error-coords!` hook (always-on)
     populates the parallel error-coord registry; only the public
     registry-meta merge is suppressed in prod (see
     [[source-coords/merge-coords]])."
     [form-meta file ns-sym body-form]
     `(binding [re-frame.source-coords/*pending-coords*
                (if re-frame.interop/debug-enabled?
                  ~(source-coords/coords-form      form-meta file ns-sym)
                  ~(source-coords/prod-coords-form form-meta file ns-sym))]
        ~body-form)))

;; ---- defreg-macro --------------------------------------------------------
;;
;; `defreg-macro` (rf2-bd6zl) is a macro-defining macro that emits a
;; canonical reg-* defmacro body: captures source-coords at the caller's
;; call site and splices the args through to a fn-form delegate.
;; `~'&form` / `~'*file*` / `~'*ns*` escapes resolve at the INNER
;; defmacro's expansion time (the user's call site).
;;
;; `delegate-sym` is resolved through `re-frame.core`'s aliases at
;; `defreg-macro` expansion time (so the inner-macro emission baked into
;; the consumer's classfile carries a fully-qualified symbol — the user's
;; namespace never needs to alias the producing ns). `re-frame.core` is
;; the resolution namespace because (a) defreg-macro is called FROM
;; re-frame.core to define the user-facing macros in that ns (so
;; `rf/reg-event-db` etc. resolve via standard alias-qualified lookup),
;; and (b) re-frame.core aliases all the delegate namespaces.

#?(:clj
   (defn resolve-delegate-sym
     "Resolve `sym` against `re-frame.core`'s aliases and return the
     fully-qualified symbol. Lets `defreg-macro` emit a delegate call
     that doesn't depend on the consumer's namespace aliasing the
     producing ns."
     [sym]
     (let [v (ns-resolve (find-ns 're-frame.core) sym)]
       (when (nil? v)
         (throw (ex-info ":rf.error/defreg-macro-bad-delegate"
                         {:rf.error/id :rf.error/defreg-macro-bad-delegate
                          :where       'defreg-macro
                          :recovery    :fix-registration
                          :reason      (str "defreg-macro cannot resolve delegate symbol " sym " in re-frame.core")
                          :sym         sym})))
       (symbol (str (.ns ^clojure.lang.Var v))
               (str (.sym ^clojure.lang.Var v))))))

#?(:clj
   (defmacro defreg-macro
     "Emits a canonical `defmacro` for a `reg-*` surface. The emitted
     macro captures `(meta &form)` / `*file*` / `*ns*` and splices the
     consumer's args through to `delegate-sym` (a symbol that must
     resolve in `re-frame.core`)."
     [macro-sym delegate-sym docstring & [attr-map]]
     (let [qualified (resolve-delegate-sym delegate-sym)]
       `(defmacro ~macro-sym
          ~docstring
          ~(or attr-map {})
          [~'& args#]
          (with-coords-form (meta ~'&form) ~'*file*
                            (symbol (str (ns-name ~'*ns*)))
                            (list* '~qualified args#))))))

;; ---- defreg-event-macro --------------------------------------------------
;;
;; Per Spec 009 §`:rf.handler/source` and Xray Spec 021 §11.2 B.7
;; stretch (rf2-xgfuy): `reg-event-db` / `reg-event-fx` / `reg-event-ctx`
;; additionally capture the WHOLE `(reg-event-X :id ...)` form as a
;; string under `:rf.handler/source` so Xray's Event panel can render
;; the source inline.
;;
;; Scope decision (rf2-xgfuy): capture the WHOLE form (`(reg-event-X
;; :id [interceptors] (fn ...))`), not just the handler-fn. The Xray
;; Event panel mockup (Spec 021 §2.2) renders the macro name + id +
;; full handler-fn body — the whole form gives the consumer everything
;; in one slot rather than forcing it to re-derive the wrapping shape
;; from `:event/kind` + `:handler-fn`.
;;
;; CLJS production elision: the emitted form binds
;; `source-coords/*pending-form-source*` to
;; `(if interop/debug-enabled? <pr-str-of-form> nil)` so Closure
;; constant-folds the bound value to `nil` under `:advanced` +
;; `goog.DEBUG=false` and DCEs the literal source-string bytes. JVM:
;; the same expansion is emitted but `interop/debug-enabled?` is a
;; runtime flag (dev-default `true`), so JVM/SSR/test builds carry the
;; source string. The elision-probe asserts the production absence.

#?(:clj
   (defn with-form-source-form
     "Wrap `body-form` in a binding of `source-coords/*pending-form-
     source*` to the compile-time `pr-str` of `whole-form` (the entire
     `(reg-event-X :id ...)` form as the user wrote it). Returns a
     syntax-quote-safe form suitable for a reg-event-* defmacro to
     emit. Per rf2-xgfuy.

     The bound value rides an outer `(if interop/debug-enabled? <src>
     nil)` gate so Closure DCEs the source-string literal under
     `:advanced` + `goog.DEBUG=false`. JVM/SSR/test builds with
     `interop/debug-enabled?` true carry the source string into the
     registry meta via `events/merge-form-source`."
     [whole-form body-form]
     (let [src-string (pr-str whole-form)]
       `(binding [re-frame.source-coords/*pending-form-source*
                  (if re-frame.interop/debug-enabled? ~src-string nil)]
          ~body-form))))

#?(:clj
   (defmacro defreg-event-macro
     "Emits a `defmacro` for a `reg-event-{db,fx,ctx}` surface. Same
     coord-capture skeleton as [[defreg-macro]] PLUS the form-source
     capture per rf2-xgfuy: the emitted macro binds
     `source-coords/*pending-form-source*` to a DEBUG-gated `pr-str`
     of the whole user-written form so `re-frame.events/register-
     event!` can stamp `:rf.handler/source` into the registry meta.

     `delegate-sym` resolves through `re-frame.core`'s aliases at
     `defreg-event-macro` expansion time (see
     [[resolve-delegate-sym]])."
     [macro-sym delegate-sym docstring & [attr-map]]
     (let [qualified (resolve-delegate-sym delegate-sym)]
       `(defmacro ~macro-sym
          ~docstring
          ~(or attr-map {})
          [~'& args#]
          (with-form-source-form ~'&form
            (with-coords-form (meta ~'&form) ~'*file*
                              (symbol (str (ns-name ~'*ns*)))
                              (list* '~qualified args#)))))))

;; ---- reg-machine expansion (co-located per-element source) ---------------
;;
;; The bespoke reg-* form (Spec 005 §Source-coord stamping; rf2-xbtj) —
;; doesn't fit the splice-through pattern because the spec form is walked
;; at compile time. Per rf2-npvsx the walk produces ONE cohesive map per
;; guard / action / on-spawn-action — `:guards {<id> {:fn .. :source-coords
;; .. :source-code ..}}` — and per rf2-vqja2 it CO-LOCATES a reference-site
;; `:source-coords` onto each MAP node inside the `:states` tree (state-node
;; / transition map) at its spec path, rather than building a flat
;; `:rf.machine/state-coords` side-index that paralleled `:states`. The
;; walker drops to the unchanged spec for non-literal forms (a runtime
;; symbol) and tools fall back to the top-level handler-meta call-site
;; coords.

#?(:clj
   (def ^:private machine-element-slots
     "The machine-spec slots whose `{<id> <fn>}` values are co-located into
     `{<id> {:fn .. :source-coords .. :source-code ..}}` per rf2-npvsx."
     [:guards :actions :on-spawn-actions]))

#?(:clj
   (defn stamp-machine-spec-expr
     "Walk the literal machine-spec form `spec-form` at expansion time and
     return either the `value-expr` unchanged (when the spec is not a
     literal map — a symbol / let-bound expr the walker can't see into, so
     there's nothing to stamp) OR an `(if interop/debug-enabled? <dev>
     <prod>)` expression.

     Per rf2-npvsx the two arms CO-LOCATE per-element source onto each
     `:guards` / `:actions` / `:on-spawn-actions` entry, and per rf2-vqja2
     they CO-LOCATE a reference-site `:source-coords` onto each MAP node
     inside the `:states` tree — rather than building the old
     `:rf.machine/source-coords` / `:rf.machine/handler-source` /
     `:rf.machine/state-coords` side-indexes:

     - DEV arm: `source-coords/collocate-element-source` merges the per-id
       `{:source-coords {...} :source-code \"...\"}` data (built by the
       compile-time `walk-element-source`) onto each entry, yielding
       `{<id> {:fn <fn> :source-coords {...} :source-code \"...\"}}`. Plus
       `source-coords/collocate-state-source` splices the reference-site
       `:source-coords` onto each `:states`-tree map node at its spec-path
       (the `{<map-spec-path> <coord>}` index from `walk-machine-spec`).
     - PROD arm: `source-coords/wrap-element-fns` collapses each entry to
       `{<id> {:fn <fn>}}` and NO state-source splice runs — NO source
       literals, so Closure DCEs the dev arm (with its coord maps + `pr-str`
       strings + the state-coord index) under `:advanced` +
       `goog.DEBUG=false`.

     `value-expr` is the runtime expression that evaluates to the spec map
     (the spec form itself for `reg-machine`; a gensym bound to it for
     `defmachine`). `ns-sym` / `file` come from the calling macro's compile
     environment.

     This is the SHARED stamping core behind both the `reg-machine` macro
     (which stamps the spec it registers) and the `defmachine` macro (which
     stamps the spec it `def`s, so a value-registered machine —
     `(defmachine m …)` then `(reg-machine :id m)` — carries per-element
     source even though `reg-machine` sees only the symbol; rf2-gwj8l)."
     [spec-form ns-sym file value-expr]
     (if-not (map? spec-form)
       value-expr
       (let [;; Per-slot dev source data — {<id> {:source-coords <form>
             ;; :source-code <pr-str>}}. Empty when the slot is absent.
             slot->src    (into {}
                                (map (fn [slot]
                                       [slot (source-coords/walk-element-source
                                               spec-form slot ns-sym file)]))
                                machine-element-slots)
             ;; Reference-site (states-tree) coords, keyed by each MAP node's
             ;; spec-path. Built syntax-quote-safe (`:ns` quoted) so the
             ;; splice doesn't namespace-resolve the consumer's ns at compile
             ;; time (ClassNotFoundException). Per rf2-vqja2 these are
             ;; co-located ONTO each state-node / transition map at runtime
             ;; rather than assoc'd into a flat `:rf.machine/state-coords`
             ;; side-index.
             state-coords (into {}
                                (map (fn [[path coords]]
                                       [path
                                        (cond-> {:ns (list 'quote (:ns coords))}
                                          (:file coords)   (assoc :file (:file coords))
                                          (:line coords)   (assoc :line (:line coords))
                                          (:column coords) (assoc :column (:column coords)))]))
                                (source-coords/walk-machine-spec spec-form ns-sym file))
             ;; Only co-locate slots actually present on the spec; both
             ;; helpers no-op on an absent slot, but emitting calls only for
             ;; present slots keeps the expansion lean.
             present-slots (filterv #(map? (get spec-form %)) machine-element-slots)
             ;; The state-source co-location is a single extra `->` step,
             ;; gated on the coords being non-empty.
             state-step   (when (seq state-coords)
                            [`(source-coords/collocate-state-source ~state-coords)])
             dev-form     `(-> ~value-expr
                              ~@(map (fn [slot]
                                       `(source-coords/collocate-element-source
                                          ~slot ~(get slot->src slot)))
                                     present-slots)
                              ~@state-step)
             prod-form    `(-> ~value-expr
                              ~@(map (fn [slot]
                                       `(source-coords/wrap-element-fns ~slot))
                                     present-slots))]
         (if (and (empty? present-slots) (empty? state-coords))
           ;; Nothing debug-only to emit (no element slots, no states coords).
           value-expr
           `(if re-frame.interop/debug-enabled?
              ~dev-form
              ~prod-form))))))

#?(:clj
   (defn expand-reg-machine
     "Build the expansion form for a `reg-machine` macro call. Per
     Spec 005 §Source-coord stamping; rf2-xbtj / rf2-npvsx. `form-meta`
     is `(meta &form)`; `ns-sym` / `file` are `*ns*` / `*file*` at
     expansion time. The per-element co-location + reference-site coord
     index ride [[stamp-machine-spec-expr]]'s `interop/debug-enabled?`
     gate so the dev-only source DCEs under :advanced + `goog.DEBUG=false`.

     The DEV arm co-locates `{:fn .. :source-coords .. :source-code ..}`
     onto each `:guards` / `:actions` / `:on-spawn-actions` entry of the
     spec, which `reg-machine*` stores under `:rf/machine` in the machine's
     `:event` registration. Tooling reads `(rf/handler-meta :machine-guard
     [machine-id guard-id])`, which DERIVES the fn-source on demand from
     that `:event` spec (rf2-ftrcv, supersedes rf2-npvsx — no registrar
     side-table). The PROD arm collapses each entry to `{:fn <fn>}` (no
     source), so the derivation returns nil under elision.

     When `machine` is NOT a literal map (a symbol bound by `def` /
     `defmachine`, a let-bound expr), [[stamp-machine-spec-expr]] returns
     the spec expr unchanged — the co-located source lives on the
     def'd value instead (via `defmachine`; rf2-gwj8l)."
     [form-meta ns-sym file machine-id machine]
     (let [machine-sym (gensym "machine__")
           stamped     (stamp-machine-spec-expr machine ns-sym file machine-sym)
           inline?     (not (identical? stamped machine-sym))]
       ;; Per rf2-3un2g §Production elision: the binding-value rides an
       ;; outer `interop/debug-enabled?` gate so Closure DCEs the dev
       ;; coords (with `:column`) under `:advanced + goog.DEBUG=false`.
       ;; See [[with-coords-form]] for the rationale.
       `(binding [re-frame.source-coords/*pending-coords*
                  (if re-frame.interop/debug-enabled?
                    ~(source-coords/coords-form      form-meta file ns-sym)
                    ~(source-coords/prod-coords-form form-meta file ns-sym))]
          ~(if-not inline?
             ;; Symbol / non-literal spec: register verbatim. A
             ;; `defmachine`-stamped value already carries its source.
             `(re-frame.core-machines/reg-machine ~machine-id ~machine)
             `(let [~machine-sym ~machine
                    stamped# ~stamped]
                (re-frame.core-machines/reg-machine ~machine-id stamped#)))))))

#?(:clj
   (defn expand-defmachine
     "Build the expansion form for a `defmachine` macro call (rf2-gwj8l).
     `(defmachine name spec)` expands to a `(def name <stamped-spec>)`
     where the literal spec is walked at expansion time and per-element
     source is co-located onto each `:guards` / `:actions` /
     `:on-spawn-actions` entry (plus a reference-site `:source-coords`
     co-located onto each `:states`-tree map node per rf2-vqja2), gated on
     `interop/debug-enabled?` so the dev-only source DCEs under
     `:advanced + goog.DEBUG=false`.

     The whole point: a VALUE-registered machine —
     `(defmachine door-machine {…}) … (reg-machine :door/main
     door-machine)` — carries per-element source even though
     `reg-machine` sees only the `door-machine` symbol at its call site.
     The co-located source travels WITH the value, so when the machine is
     registered the stamped spec (with its `:source-code` entries) is stored
     under `:rf/machine` in the `:event` registration, and `(rf/handler-meta
     :machine-guard [machine-id guard-id])` derives the fn-source from it on
     demand (rf2-ftrcv — no registrar side-table written).

     `ns-sym` / `file` are `*ns*` / `*file*` at expansion time. An
     optional leading `doc` string rides through to the emitted `def`
     so `defmachine` is a drop-in for `def`. (There is no call-site
     coord binding to emit — `def` is not a registration; per-element
     coords come from the literal walk, and the top-level call-site
     coords are captured by the later `reg-machine` form.)"
     [ns-sym file name doc-or-spec spec-or-nil]
     (let [has-doc?    (and (some? spec-or-nil) (string? doc-or-spec))
           doc         (when has-doc? doc-or-spec)
           spec-form   (if has-doc? spec-or-nil doc-or-spec)
           stamped     (stamp-machine-spec-expr spec-form ns-sym file spec-form)
           name'       (cond-> name doc (vary-meta assoc :doc doc))]
       `(def ~name' ~stamped))))
