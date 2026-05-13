(ns re-frame.core-reg-macros
  "Registration-site source-coord-capturing macro EMITTERS (per
  rf2-4rnui split from `re-frame.core`).

  The defmacro entrypoints live in `re-frame.core` so CLJS macro
  propagation works for `(:require [re-frame.core :as rf])` users
  (same-name rule). This namespace owns the EMITTER FNS that each
  re-frame.core `defmacro` delegates to. Result: each macro entry in
  `core.cljc` is a one-line `(emit-reg-X &form (ns-name *ns*) *file*
  args)` delegation; the heavy lifting (binding-form construction,
  per-element coord walks) lives here.

  Per Spec 001 §Source-coordinate capture each `reg-*` registration's
  metadata carries `:ns` / `:line` / `:file` auto-supplied at compile
  time. The emitter binds `re-frame.source-coords/*pending-coords*`
  around the underlying fn call; the fn merges the coords into the
  registered metadata.

  Per rf2-xnym the `(symbol (str (ns-name *ns*)))` form (rather than
  bare `(ns-name *ns*)`) drops any `:doc` metadata the consuming
  namespace's ns-symbol may carry — without the strip those docstrings
  would serialise into the bundle and defeat production elision. The
  emitters take a `ns-sym` arg already stripped at the macro
  entrypoint."
  (:require [re-frame.source-coords :as source-coords]))

;; ---- emitters: the splice-through reg-* shape ----------------------------
;;
;; Each emitter takes `form-meta` (the caller's `(meta &form)`),
;; `ns-sym` (the stripped ns-symbol), `file` (the caller's `*file*`),
;; the delegate symbol (fully qualified, in scope at the user's call
;; site), and the user's arg vector. Returns a syntax-quoted form
;; binding `*pending-coords*` around the delegate call.

#?(:clj
   (defn emit-reg
     "Build the `(binding [*pending-coords* ...] (<delegate> ~@args))`
     form for the canonical splice-through reg-* shape. The 13 reg-*
     macros in `re-frame.core` (reg-event-db / -fx / -ctx, reg-sub /
     -fx / -cofx / -frame, reg-flow / -route / -app-schema / -app-
     schemas / -error-projector / -head) all share this body."
     [form-meta ns-sym file delegate-sym args]
     `(binding [source-coords/*pending-coords*
                ~(source-coords/coords-form form-meta file ns-sym)]
        (~delegate-sym ~@args))))

;; ---- reg-machine emitter (bespoke; per-element coord walker) -------------
;;
;; Per Spec 005 §Source-coord stamping (rf2-8bp3): walks the literal
;; machine spec at compile time and stamps each transition / state with
;; its source coord. The DCE gate `interop/debug-enabled?` ensures the
;; per-element index drops under `:advanced` + `goog.DEBUG=false`.

#?(:clj
   (defn emit-reg-machine
     "Build the expansion form for `reg-machine`. Walks the literal spec
     form, builds a `:rf.machine/source-coords` per-element index when
     the spec is a literal map, and emits a debug-gated `if` branch so
     the index DCEs in production."
     [form-meta ns-sym file delegate-sym machine-id machine-form]
     (let [;; Walk the literal spec form at compile time. When `machine`
           ;; is a non-map (a symbol bound to a value at runtime) the
           ;; walker returns {} — tools fall back to the call-site coords
           ;; on the top-level handler-meta.
           per-el-coords (source-coords/walk-machine-spec machine-form ns-sym file)
           ;; Build a syntax-quote-safe literal form for the coord index.
           ;; Symbols inside `:ns` need to be quoted (otherwise the syntax
           ;; quote splice would try to namespace-resolve them at compile
           ;; time and the compiler would throw ClassNotFoundException
           ;; for the consumer's ns).
           per-el-form   (into {}
                               (map (fn [[path coords]]
                                      [path
                                       (cond-> {:ns (list 'quote (:ns coords))}
                                         (:file coords)   (assoc :file (:file coords))
                                         (:line coords)   (assoc :line (:line coords))
                                         (:column coords) (assoc :column (:column coords)))])
                                    per-el-coords))
           machine-sym   (gensym "machine__")
           coord-binding `(binding [source-coords/*pending-coords*
                                    ~(source-coords/coords-form form-meta file ns-sym)])]
       (if (empty? per-el-coords)
         `(binding [source-coords/*pending-coords*
                    ~(source-coords/coords-form form-meta file ns-sym)]
            (~delegate-sym ~machine-id ~machine-form))
         `(binding [source-coords/*pending-coords*
                    ~(source-coords/coords-form form-meta file ns-sym)]
            (let [~machine-sym ~machine-form
                  stamped# (if re-frame.interop/debug-enabled?
                             (assoc ~machine-sym
                                    :rf.machine/source-coords
                                    ~per-el-form)
                             ~machine-sym)]
              (~delegate-sym ~machine-id stamped#)))))))
