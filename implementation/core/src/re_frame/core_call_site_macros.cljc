(ns re-frame.core-call-site-macros
  "Helpers for the call-site-capturing macros — `dispatch`, `dispatch-
  sync`, `subscribe`, `inject-cofx`. Per rf2-ts1a each user-facing
  surface ships as a macro + `*`-fn pair (Q1=C: existing-name macro,
  fn-form gets the `*` suffix; same convention as `reg-view` /
  `reg-view*` and `reg-machine` / `reg-machine*` per Conventions
  §`*`-suffix naming).

  Carved out of `re-frame.core` per rf2-4rnui so the public namespace
  stays under the 250-LoC leaf ceiling (rf2-zkca8). The user-facing
  `defmacro dispatch` / `subscribe` / etc. shells live in
  `re-frame.core` itself (they MUST, so `rf/dispatch` resolves alias-
  qualified per Clojure's standard `ns-alias/Var` lookup); each shell
  is a one-line call into the `build-…-form` plain fns here.

  Each shell emits an `(if interop/debug-enabled? <stamping> <plain>)`
  branch around the matching `*`-fn call. Under `:advanced` +
  `goog.DEBUG=false` the closure compiler constant-folds the gate to
  false and the entire stamping branch — including the literal
  `:rf.trace/call-site` map — DCEs. Per Q3=B dev-only elision.

  `emit-error!` reads `trace/*handler-scope*`'s `:call-site` slot and
  attaches the value as `:rf.trace/call-site` (Q2=A flat sibling of
  `:rf.trace/trigger-handler`) on the emitted event. The `coords-form`
  helper is reused from `re-frame.source-coords` so the literal map
  carries the same `{:ns :file :line :column}` shape as registration-
  site coords (rf2-mdjp `:file` resolution rules apply identically).

  File naming uses the flat dash-form (per rf2-2vbm)."
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

;; ---- per-macro form builders ---------------------------------------------
;;
;; Each `build-…-form` is a plain CLJ fn invoked from the matching
;; `defmacro` shell in `re-frame.core`. The shell passes `(meta &form)`
;; / `*ns*` / `*file*` through; we emit the same gated expansion the
;; original inlined `defmacro` body produced.

#?(:clj
   (defn build-dispatch-form
     [form-meta ns-sym file event-vec opts-form]
     (let [cs-form (call-site-form form-meta ns-sym file)]
       (if opts-form
         `(if re-frame.interop/debug-enabled?
            (re-frame.core/dispatch* ~event-vec
                                     (assoc ~opts-form :rf.trace/call-site ~cs-form))
            (re-frame.core/dispatch* ~event-vec ~opts-form))
         `(if re-frame.interop/debug-enabled?
            (re-frame.core/dispatch* ~event-vec {:rf.trace/call-site ~cs-form})
            (re-frame.core/dispatch* ~event-vec))))))

#?(:clj
   (defn build-dispatch-sync-form
     [form-meta ns-sym file event-vec opts-form]
     (let [cs-form (call-site-form form-meta ns-sym file)]
       (if opts-form
         `(if re-frame.interop/debug-enabled?
            (re-frame.core/dispatch-sync* ~event-vec
                                          (assoc ~opts-form :rf.trace/call-site ~cs-form))
            (re-frame.core/dispatch-sync* ~event-vec ~opts-form))
         `(if re-frame.interop/debug-enabled?
            (re-frame.core/dispatch-sync* ~event-vec {:rf.trace/call-site ~cs-form})
            (re-frame.core/dispatch-sync* ~event-vec))))))

#?(:clj
   (defn build-subscribe-form
     [form-meta ns-sym file frame-form query-v]
     (let [cs-form (call-site-form form-meta ns-sym file)]
       (if frame-form
         `(if re-frame.interop/debug-enabled?
            (re-frame.trace/with-call-site ~cs-form
              (re-frame.core/subscribe* ~frame-form ~query-v))
            (re-frame.core/subscribe* ~frame-form ~query-v))
         `(if re-frame.interop/debug-enabled?
            (re-frame.trace/with-call-site ~cs-form
              (re-frame.core/subscribe* ~query-v))
            (re-frame.core/subscribe* ~query-v))))))

#?(:clj
   (defn build-inject-cofx-form
     [form-meta ns-sym file cofx-id value-form]
     (let [cs-form (call-site-form form-meta ns-sym file)]
       (if value-form
         `(if re-frame.interop/debug-enabled?
            (re-frame.core/inject-cofx* ~cofx-id ~value-form ~cs-form)
            (re-frame.core/inject-cofx* ~cofx-id ~value-form))
         ;; 1-arity routes through the 3-arity with the cofx/no-value
         ;; sentinel so the call-site can ride; the 3-arity branch in
         ;; cofx/inject-cofx detects the sentinel via `identical?` and
         ;; takes the no-value path through the cofx fn body.
         `(if re-frame.interop/debug-enabled?
            (re-frame.core/inject-cofx* ~cofx-id re-frame.cofx/no-value ~cs-form)
            (re-frame.core/inject-cofx* ~cofx-id))))))
