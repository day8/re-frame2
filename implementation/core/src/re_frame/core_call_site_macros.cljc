(ns re-frame.core-call-site-macros
  "Helpers for the call-site-capturing macros — `dispatch`,
  `dispatch-sync`, and `subscribe`. Each expansion calls a stable `^:no-doc`
  alias in `re-frame.core`; those aliases point at the owning router or subs
  function without introducing a second implementation. The alias prevents
  the CLJS compiler from baking the owner's fixed arities into call sites, so
  tools can safely replace the seam with a differently shaped test fn.

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
  (:require [re-frame.source-coords :as rf.source-coords]))

#?(:clj (set! *warn-on-reflection* true))

#?(:clj
   (defn call-site-form
     "Build the literal call-site cond-> map for a callable's macro
     form. Returns the unguarded form; callers wrap in their own `(if
     interop/debug-enabled? ... ...)` so the entire branch (binding
     scope or opts-key assoc) DCEs under `goog.DEBUG=false`."
     [form-meta ns-sym file]
     (rf.source-coords/coords-form form-meta file ns-sym)))

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
     "Emit the STAMPED call-site-family call — ALWAYS a 2-arity `disp` call so the
     `:rf.trace/call-site` keyword literal lives ONLY in THIS debug-gated macro
     expansion (DCE'd in production), never in the production-reachable owning-ns
     fn body. `disp-sym` is the fully-qualified `^:no-doc` seam var
     (`re-frame.core/dispatch-impl` / `-dispatch-sync-impl` / `subscribe-impl`
     — `def`-aliases of `re-frame.router/dispatch!` / `-dispatch-sync!` /
     `re-frame.subs/subscribe`, NOT the
     owning-ns fns directly: a `defn` there would let the CLJS compiler attach
     inline fixed-arity metadata to a call site referencing it directly, so a
     `with-redefs`'d fn whose arity set differs couldn't satisfy it). When the
     user wrote two args, `(disp arg1 (stamp-opts opts {:rf.trace/call-site
     cs}))` — `arg1` being the event-vec for the dispatch pair, the query-v for
     `subscribe`; [[re-frame.core/stamp-opts]] merges tolerantly so a malformed
     non-map opts still reaches the callee for its own clean error. The 1-arg
     case is the ambient-frame form, stamped via the 2-arity opts map.

     The emitted form is a plain CALL whose only non-literal operand is the
     user's own expression — no `binding`, no `cond->`, nothing that the CLJS
     compiler could lower to an awaited async IIFE in the caller's context
     (rf2-i3dvj)."
     [disp-sym arg1 arg2 cs-form]
     (if arg2
       `(~disp-sym ~arg1 (re-frame.core/stamp-opts
                           ~arg2 {:rf.trace/call-site ~cs-form}))
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
     target}`), nil for the 1-arity. Targets the `^:no-doc`
     `re-frame.core/subscribe-impl` seam (a `def`-alias of
     `re-frame.subs/subscribe`) for the same with-redefs-safety reason
     `build-dispatch-form` targets `dispatch-impl`. The OUTERMOST debug-gate
     keeps the whole stamped branch DCE-able under `:advanced` +
     `goog.DEBUG=false`.

     The call-site coord rides the SHARED [[build-stamped-2]] opts-map seam —
     the same `:rf.trace/call-site` key `dispatch` / `dispatch-sync` use — and
     `re-frame.subs/subscribe` establishes the `trace/with-call-site` scope
     from it INSIDE ITS OWN BODY (the mirror of `router/dispatch!`'s existing
     `(with-call-site (:rf.trace/call-site opts) ...)`).

     Per rf2-i3dvj this placement is a CORRECTNESS requirement, not a
     tidy-up. `with-call-site` expands to a `binding`, and a `binding`
     spliced into the CALLER's context compiles to `await (async
     function(){...})()` when the call site sits in a CLJS async context —
     inserting a real microtask yield immediately before the read. The
     caller-side wrapper this replaces was an independent yield source of
     exactly the class the `coords-form` fix removes. See
     [[re-frame.source-coords/coords-form]] for the standing principle:
     a call-site expansion splices ONLY yield-free expression forms into
     the caller; dynamic scope is established in the callee's body."
     [form-meta ns-sym file arg1 arg2]
     (let [cs-form (call-site-form form-meta ns-sym file)
           stamped (build-stamped-2 're-frame.core/subscribe-impl arg1 arg2 cs-form)
           plain   (if arg2
                     `(re-frame.core/subscribe-impl ~arg1 ~arg2)
                     `(re-frame.core/subscribe-impl ~arg1))]
       (gate stamped plain))))
