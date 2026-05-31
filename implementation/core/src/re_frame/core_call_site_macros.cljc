(ns re-frame.core-call-site-macros
  "Helpers for the call-site-capturing macros — `dispatch`,
  `dispatch-sync`, `subscribe`, `inject-cofx`. Each user-facing surface
  ships as a macro + `*`-fn pair (Conventions §`*`-suffix naming).

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

#?(:clj
   (defn build-dispatch-form
     [form-meta ns-sym file event-vec opts-form]
     (let [cs-form (call-site-form form-meta ns-sym file)
           stamped (if opts-form
                     `(re-frame.core/dispatch* ~event-vec
                                               (assoc ~opts-form :rf.trace/call-site ~cs-form))
                     `(re-frame.core/dispatch* ~event-vec {:rf.trace/call-site ~cs-form}))
           plain   (if opts-form
                     `(re-frame.core/dispatch* ~event-vec ~opts-form)
                     `(re-frame.core/dispatch* ~event-vec))]
       (gate stamped plain))))

#?(:clj
   (defn build-dispatch-sync-form
     [form-meta ns-sym file event-vec opts-form]
     (let [cs-form (call-site-form form-meta ns-sym file)
           stamped (if opts-form
                     `(re-frame.core/dispatch-sync* ~event-vec
                                                    (assoc ~opts-form :rf.trace/call-site ~cs-form))
                     `(re-frame.core/dispatch-sync* ~event-vec {:rf.trace/call-site ~cs-form}))
           plain   (if opts-form
                     `(re-frame.core/dispatch-sync* ~event-vec ~opts-form)
                     `(re-frame.core/dispatch-sync* ~event-vec))]
       (gate stamped plain))))

#?(:clj
   (defn build-subscribe-form
     [form-meta ns-sym file frame-form query-v]
     (let [cs-form (call-site-form form-meta ns-sym file)
           stamped (if frame-form
                     `(re-frame.trace/with-call-site ~cs-form
                        (re-frame.core/subscribe* ~frame-form ~query-v))
                     `(re-frame.trace/with-call-site ~cs-form
                        (re-frame.core/subscribe* ~query-v)))
           plain   (if frame-form
                     `(re-frame.core/subscribe* ~frame-form ~query-v)
                     `(re-frame.core/subscribe* ~query-v))]
       (gate stamped plain))))

#?(:clj
   (defn build-inject-cofx-form
     [form-meta ns-sym file cofx-id value-form]
     (let [cs-form (call-site-form form-meta ns-sym file)
           stamped (if value-form
                     `(re-frame.core/inject-cofx* ~cofx-id ~value-form ~cs-form)
                     ;; 1-arity routes through the 3-arity with the
                     ;; cofx/no-value sentinel so the call-site can
                     ;; ride; the 3-arity branch in cofx/inject-cofx
                     ;; detects the sentinel via `identical?` and takes
                     ;; the no-value path through the cofx fn body.
                     `(re-frame.core/inject-cofx* ~cofx-id re-frame.cofx/no-value ~cs-form))
           plain   (if value-form
                     `(re-frame.core/inject-cofx* ~cofx-id ~value-form)
                     `(re-frame.core/inject-cofx* ~cofx-id))]
       (gate stamped plain))))

;; ---- ->interceptor (definition-site coord capture, rf2-siheh) ------------
;;
;; `->interceptor` is the only interceptor constructor with a USER
;; definition site to jump to (the std interceptors `path` / `unwrap` and
;; the cofx injector are framework-built; the reg-event handler-wrappers
;; carry the event's own coord). Before rf2-siheh it was a plain fn, so an
;; interceptor map carried NO source-coord and the Xray Epoch INTERCEPTOR
;; row could render no jump-to-source chip (yz57h assumed a coord that
;; didn't exist — `handler-meta :interceptor` resolves nothing, interceptors
;; not being a registrar kind).
;;
;; The fix mirrors the `inject-cofx` (macro) / `inject-cofx*` (fn) pair:
;; `->interceptor` becomes a macro that captures `(meta &form)` →
;; `:source-coord` (riding the SAME `coords-form` / `prod-coords-form`
;; absolutise path — rf2-wvsxg — as every other reg-* / call-site coord)
;; and bakes it into the `:source-coord` kwarg of `->interceptor*`. The
;; coord then rides the interceptor map, the chain runner's error-record,
;; and the `:rf.error/interceptor-exception` trace, so the Epoch row gets
;; parity with EVENT HANDLER / SUBSCRIPTIONS / VIEWS. Unlike a registrar
;; lookup, the coord stays bound to the exact interceptor instance that
;; threw.
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
     fn-call so the coord literal DCEs. Per rf2-siheh."
     [form-meta ns-sym file kwargs]
     (let [cs-form (call-site-form form-meta ns-sym file)
           stamped `(re-frame.core/->interceptor* ~@kwargs :source-coord ~cs-form)
           plain   `(re-frame.core/->interceptor* ~@kwargs)]
       (gate stamped plain))))
