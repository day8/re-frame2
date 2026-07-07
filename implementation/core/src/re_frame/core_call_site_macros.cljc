(ns re-frame.core-call-site-macros
  "Helpers for the call-site-capturing macros — `dispatch`,
  `dispatch-sync`, `subscribe`. Each user-facing macro's expansion reaches
  straight through to its OWNING ns fn — `re-frame.router/dispatch!`,
  `re-frame.router/dispatch-sync!`, `re-frame.subs/subscribe` — no
  `re-frame.core` `*`-suffixed twin (rf2-m90brg retired `dispatch*` /
  `dispatch-sync*` / `subscribe*`; the CLJS same-name `def`-alias in
  `re-frame.core` — Convention A, per that ns's docstring — covers the HoF /
  programmatic case). (`->interceptor` / `->interceptor*` is a SEPARATE
  internal-lowering pair, not part of this call-site-capturing family;
  `inject-cofx` is REMOVED in EP-0017 — it survives only as a throwing stub
  macro/fn pair, no longer a call-site-capturing surface.)

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

;; API-shrink #1 (rf2-csbbwu): the 2-arity dispatch surface is `[event-vec
;; opts]` ONLY — no runtime shape-discrimination, no frame-first sugar. The
;; stamped path always keeps the 2-arity `(dispatch! event-vec (assoc opts
;; :rf.trace/call-site cs))` shape (so a `(with-redefs [router/dispatch!
;; (fn [ev _opts] …)])` tools-test stub reaches every real dispatch, macro or
;; HoF alike — rf2-m90brg moved this stub target from the retired
;; `re-frame.core/dispatch*` facade twin to the OWNING ns fn the macro always
;; called) — the call-site keyword stays a macro-literal, never reaching the
;; production-reachable owning-ns fn body. The debug-gate stays OUTERMOST so
;; the whole stamped branch (the `cs` literal) DCEs under `:advanced` +
;; `goog.DEBUG=false`. `arg1`/`arg2` are the user's two forms verbatim.

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

;; `build-inject-cofx-form` was retired with `inject-cofx` (EP-0017 slice
;; A.3): there was no call-site interceptor to build, so the dedicated builder
;; is gone. rf2-w9xyx1 then removed the `inject-cofx` macro / `inject-cofx*`
;; fn from the public facade entirely (the migration alarm survives on the
;; private thrower `re-frame.cofx/inject-cofx`).

;; ---- ->interceptor (definition-site coord capture, rf2-siheh) ------------
;;
;; `->interceptor` is the only interceptor constructor with a USER
;; definition site to jump to (the std interceptor `path` and
;; the cofx injector are framework-built; the reg-event handler-wrappers
;; carry the event's own coord). Before rf2-siheh it was a plain fn, so an
;; interceptor map carried NO source-coord and the Xray Epoch INTERCEPTOR
;; row could render no jump-to-source chip (yz57h assumed a coord that
;; didn't exist — `handler-meta :interceptor` resolves nothing, interceptors
;; not being a registrar kind).
;;
;; The fix mirrors the (historical) macro / `*`-fn pair pattern this facade
;; used across `dispatch` / `subscribe` before rf2-m90brg retired those
;; twins: `->interceptor` becomes a macro that captures `(meta &form)` →
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
