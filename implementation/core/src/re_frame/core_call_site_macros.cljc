(ns re-frame.core-call-site-macros
  "Helpers for the call-site-capturing macros — `dispatch`,
  `dispatch-sync`, and `subscribe`. Each expansion calls a stable `^:no-doc`
  alias in `re-frame.core`; those aliases point at the owning router or subs
  function without introducing a second implementation. The alias prevents
  the CLJS compiler from baking the owner's fixed arities into call sites, so
  tools can safely replace the seam with a differently shaped test fn.

  `->interceptor` is a separate definition-site-capturing macro. It shares the
  coordinate and debug-gate machinery but is not part of the dispatch family.

  Carved out of `re-frame.core` so the public namespace stays a thin
  facade focused on user-visible Var resolution rather than macro
  expansion bulk; this ns owns the cohesive responsibility of every
  call-site-capturing macro's debug-gated stamping branch. The user-
  facing `defmacro dispatch` / `subscribe` / etc. shells live in
  `re-frame.core` itself (they MUST, so `rf/dispatch` resolves alias-
  qualified per Clojure's standard `ns-alias/Var` lookup); each shell
  is a one-line call into a `build-…-form` plain fn here.

  Each shell emits an `(if interop/debug-enabled? <stamping> <plain>)`
  branch around the matching `*`-fn call. Under `:advanced` +
  `goog.DEBUG=false` the closure compiler constant-folds the gate to
  false and the entire stamping branch — including the literal
  `:rf.trace/call-site` map — DCEs.

  `emit-error!` reads `trace/*handler-scope*`'s `:call-site` slot and
  attaches the value as `:rf.trace/call-site` (a flat sibling of
  `:rf.trace/trigger-handler`) on the emitted event. The `coords-form`
  helper is reused from `re-frame.source-coords` so the literal map
  carries the same `{:ns :file :line :column}` shape as registration-
  site coords."
  (:require [re-frame.source-coords :as source-coords]))

#?(:clj (set! *warn-on-reflection* true))

#?(:clj
   (defn call-site-form
     "Build the literal call-site cond-> map for a callable's macro
     form. Returns the unguarded form; callers wrap in their own `(if
     interop/debug-enabled? ... ...)` so the entire branch (binding
     scope or opts-key assoc) DCEs under `goog.DEBUG=false`."
     [form-meta ns-sym file]
     (source-coords/coords-form form-meta file ns-sym)))

#?(:clj
   (defn- gate
     "Emit `(if interop/debug-enabled? stamped plain)` — wraps each call-
     site macro's gate in a single shape so Closure-DCE still elides the
     `stamped` branch under `:advanced` + `goog.DEBUG=false` (the gate
     must be OUTERMOST per Spec 009). `gate` is a CLJ-time fn that
     PRODUCES the if-form at expansion-time; it does not itself gate at
     CLJS runtime, so DCE remains intact by construction."
     [stamped plain]
     `(if re-frame.interop/debug-enabled? ~stamped ~plain)))

;; ---- per-macro form builders ---------------------------------------------
;;
;; Each `build-…-form` is a plain CLJ fn invoked from the matching
;; `defmacro` shell in `re-frame.core`. The shell passes `(meta &form)`
;; / `*ns*` / `*file*` through; we emit the same gated expansion the
;; original inlined `defmacro` body produced.

;; The public dispatch shapes are `[event-vec]` and `[event-vec opts]`. The
;; stamped branch always calls the stable alias with two args, keeping the
;; call-site keyword literal inside the debug-only macro expansion. The gate
;; stays outermost so Closure removes the whole stamped branch under
;; `:advanced` + `goog.DEBUG=false`.

#?(:clj
   (defn- build-stamped-2
     "Emit the STAMPED dispatch-family call — ALWAYS a 2-arity `disp` call so the
     `:rf.trace/call-site` keyword literal lives ONLY in THIS debug-gated macro
     expansion (DCE'd in production), never in the production-reachable owning-ns
     fn body. `disp-sym` is the fully-qualified `^:no-doc` seam var
     (`re-frame.core/dispatch-impl` / `re-frame.core/dispatch-sync-impl` —
     `def`-aliases of `re-frame.router/dispatch!` / `-dispatch-sync!`, NOT the
     owning-ns fns directly: a `defn` there would let the CLJS compiler attach
     inline fixed-arity metadata to a call site referencing it directly, so a
     `with-redefs`'d fn whose arity set differs couldn't satisfy it). When the
     user wrote two args, `(disp event-vec (assoc opts :rf.trace/call-site
     cs))`. The 1-arg case is the ambient-frame form, stamped via the 2-arity
     opts map."
     [disp-sym arg1 arg2 cs-form]
     (if arg2
       `(~disp-sym ~arg1 (assoc ~arg2 :rf.trace/call-site ~cs-form))
       `(~disp-sym ~arg1 {:rf.trace/call-site ~cs-form}))))

#?(:clj
   (defn build-dispatch-form
     [form-meta ns-sym file arg1 arg2]
     (let [cs-form (call-site-form form-meta ns-sym file)
           stamped (build-stamped-2 're-frame.core/dispatch-impl arg1 arg2 cs-form)
           plain   (if arg2
                     `(re-frame.core/dispatch-impl ~arg1 ~arg2)
                     `(re-frame.core/dispatch-impl ~arg1))]
       (gate stamped plain))))

#?(:clj
   (defn build-dispatch-sync-form
     [form-meta ns-sym file arg1 arg2]
     (let [cs-form (call-site-form form-meta ns-sym file)
           stamped (build-stamped-2 're-frame.core/dispatch-sync-impl arg1 arg2 cs-form)
           plain   (if arg2
                     `(re-frame.core/dispatch-sync-impl ~arg1 ~arg2)
                     `(re-frame.core/dispatch-sync-impl ~arg1))]
       (gate stamped plain))))

#?(:clj
   (defn build-subscribe-form
     "Build the expansion for the `subscribe` macro. `arg1` is the user's
     `query-v`; `arg2` is the optional `opts` map (may carry `{:frame
     target}`), nil for the 1-arity. The call-site coord rides a
     `trace/with-call-site` wrapper (not an opts assoc). The two forms emit
     POSITIONALLY (`(subscribe-impl arg1 arg2)`), matching
     `re-frame.subs/subscribe`'s `[query-v]` / `[query-v opts]` sig exactly.
     Targets the `^:no-doc` `re-frame.core/subscribe-impl` seam (a `def`-alias
     of `re-frame.subs/subscribe`) for the same with-redefs-safety reason
     `build-dispatch-form` targets `dispatch-impl`. The OUTERMOST debug-gate
     keeps the whole stamped branch DCE-able under `:advanced` +
     `goog.DEBUG=false`."
     [form-meta ns-sym file arg1 arg2]
     (let [cs-form (call-site-form form-meta ns-sym file)
           stamped (if arg2
                     `(re-frame.trace/with-call-site ~cs-form
                        (re-frame.core/subscribe-impl ~arg1 ~arg2))
                     `(re-frame.trace/with-call-site ~cs-form
                        (re-frame.core/subscribe-impl ~arg1)))
           plain   (if arg2
                     `(re-frame.core/subscribe-impl ~arg1 ~arg2)
                     `(re-frame.core/subscribe-impl ~arg1))]
       (gate stamped plain))))

;; ---- ->interceptor definition-site coordinate capture --------------------
;;
;; `->interceptor` is the only interceptor constructor with a USER
;; definition site to jump to (the std interceptor `path` and
;; the cofx injector are framework-built; the reg-event handler-wrappers
;; carry the event's own coord). The macro captures `(meta &form)` and bakes
;; the result into `->interceptor*`'s `:source-coord` kwarg. The coordinate
;; stays on the exact interceptor instance through chain errors and
;; `:rf.error/interceptor-exception`; it is not resolved through the registrar.
;;
;; The gate is OUTERMOST so the whole `:source-coord` literal DCEs under
;; `:advanced` + `goog.DEBUG=false`: the prod branch omits the kwarg
;; entirely, expanding to the identical fn call the bare fn-path produces.

#?(:clj
   (defn build-interceptor-form
     "Build the expansion for the `->interceptor` macro. `kwargs` is the
     verbatim `& {:keys [id before after]}` arg-seq the user wrote;
     `form-meta` / `ns-sym` / `file` come from the call site. Emits a
     gated `(if interop/debug-enabled? <with-coord> <plain>)` around
     `re-frame.core/->interceptor*` — the dev branch splices the
     captured `:source-coord` kwarg in, the prod branch is the bare
     fn-call so the coord literal DCEs."
     [form-meta ns-sym file kwargs]
     (let [cs-form (call-site-form form-meta ns-sym file)
           stamped `(re-frame.core/->interceptor* ~@kwargs :source-coord ~cs-form)
           plain   `(re-frame.core/->interceptor* ~@kwargs)]
       (gate stamped plain))))
