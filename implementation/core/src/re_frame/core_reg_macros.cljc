(ns re-frame.core-reg-macros
  "Helpers for the registration-site `reg-*` macros. Per Spec 001
  §Source-coordinate capture (CLJS reference) and Tool-Pair §Source-
  mapping: every `reg-*` registration's metadata carries `:ns` /
  `:line` / `:file` auto-supplied at compile time. The canonical
  capture skeleton — `(meta &form)`'s `:line` / `:column` plus `*ns*` /
  `*file*` bound around the underlying fn — is centralised here as
  the `with-coords-form` helper plus the `defreg-macro` macro-defining
  macro.

  Carved out of `re-frame.core` per rf2-4rnui so the public namespace
  stays under the 250-LoC leaf ceiling (rf2-zkca8). The user-facing
  `defmacro reg-event-db` / `reg-sub` / `reg-flow` / … shells live in
  `re-frame.core` itself (they MUST, so `rf/reg-event-db` resolves
  alias-qualified per Clojure's standard `ns-alias/Var` lookup); each
  shell is a one-line `(defreg-macro …)` form. The bulk LoC sits here.

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
     one-line delegation rather than a 12-line repetition."
     [form-meta file ns-sym body-form]
     `(binding [re-frame.source-coords/*pending-coords*
                ~(source-coords/coords-form form-meta file ns-sym)]
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
         (throw (ex-info (str "defreg-macro: cannot resolve delegate symbol " sym
                              " in re-frame.core")
                         {:sym sym})))
       (symbol (str (.ns ^clojure.lang.Var v))
               (str (.sym ^clojure.lang.Var v))))))

#?(:clj
   (defmacro defreg-macro
     "Emits a canonical `defmacro` for a `reg-*` surface. The emitted
     macro captures `(meta &form)` / `*file*` / `*ns*` and splices the
     consumer's args through to `delegate-sym` (a symbol that must
     resolve in `re-frame.core`). Per rf2-bd6zl."
     [macro-sym delegate-sym docstring & [attr-map]]
     (let [qualified (resolve-delegate-sym delegate-sym)]
       `(defmacro ~macro-sym
          ~docstring
          ~(or attr-map {})
          [~'& args#]
          (with-coords-form (meta ~'&form) ~'*file*
                            (symbol (str (ns-name ~'*ns*)))
                            (list* '~qualified args#))))))

;; ---- reg-machine expansion (per-element coord stamping) ------------------
;;
;; The bespoke reg-* form (Spec 005 §Source-coord stamping; rf2-xbtj) —
;; doesn't fit the splice-through pattern because the spec form is
;; walked at compile time and a per-element coord index is emitted
;; under `:rf.machine/source-coords`. The walker drops to {} for non-
;; literal spec forms (a runtime symbol) and tools fall back to the
;; top-level handler-meta call-site coords.

#?(:clj
   (defn expand-reg-machine
     "Build the expansion form for a `reg-machine` macro call. Per
     Spec 005 §Source-coord stamping; rf2-xbtj. `form-meta` is `(meta
     &form)`; `ns-sym` / `file` are `*ns*` / `*file*` at expansion time.
     The per-element coord-index literal is gated on
     `interop/debug-enabled?` so it DCEs under :advanced +
     `goog.DEBUG=false`."
     [form-meta ns-sym file machine-id machine]
     (let [per-el-coords  (source-coords/walk-machine-spec machine ns-sym file)
           ;; Symbols inside `:ns` need explicit quoting — otherwise the
           ;; syntax-quote splice would namespace-resolve them at compile
           ;; time (ClassNotFoundException for the consumer's ns).
           per-el-form    (into {}
                                (map (fn [[path coords]]
                                       [path
                                        (cond-> {:ns (list 'quote (:ns coords))}
                                          (:file coords)   (assoc :file (:file coords))
                                          (:line coords)   (assoc :line (:line coords))
                                          (:column coords) (assoc :column (:column coords)))])
                                     per-el-coords))
           machine-sym    (gensym "machine__")]
       `(binding [re-frame.source-coords/*pending-coords*
                  ~(source-coords/coords-form form-meta file ns-sym)]
          ~(if (empty? per-el-coords)
             `(re-frame.core-machines/reg-machine ~machine-id ~machine)
             `(let [~machine-sym ~machine
                    stamped# (if re-frame.interop/debug-enabled?
                               (assoc ~machine-sym
                                      :rf.machine/source-coords
                                      ~per-el-form)
                               ~machine-sym)]
                (re-frame.core-machines/reg-machine ~machine-id stamped#)))))))
