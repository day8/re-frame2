(ns re-frame.core-call-site-macros
  "Call-site source-coord-capturing macro EMITTERS (per rf2-4rnui split
  from `re-frame.core`).

  The defmacro entrypoints live in `re-frame.core` so CLJS macro
  propagation works for `(:require [re-frame.core :as rf])` users
  (same-name rule). This namespace owns the EMITTER FNS that each
  re-frame.core `defmacro` delegates to.

  Per rf2-ts1a — each user-facing call surface ships as a macro + `*`-
  fn pair (Q1=C: existing-name macro, fn-form gets the `*` suffix):

    `dispatch`       — macro entrypoint in re-frame.core; emitter here.
                        Captures `(meta &form)` and routes through
                        `dispatch*` with the call-site stamped on `opts`.
    `dispatch*`      — runtime-callable fn (delegates to
                        `router/dispatch!`), defined in `re-frame.core`.

  Same shape for `dispatch-sync` / `dispatch-sync*`, `subscribe` /
  `subscribe*`, `inject-cofx` / `inject-cofx*`.

  The compile-time `:rf.trace/call-site` map elides under `goog.DEBUG=
  false` (Q3=B dev-only elision) — each emitter wraps the call in
  `(if interop/debug-enabled? <stamping-call> <plain-call>)` so the
  entire stamping branch (including the literal map) DCE's.
  `emit-error!` reads `trace/*handler-scope*`'s `:call-site` slot and
  attaches the value as `:rf.trace/call-site` (Q2=A flat sibling of
  `:rf.trace/trigger-handler`) on the emitted event.

  The `coords-form` helper is reused from `re-frame.source-coords` so
  the literal map carries the same `{:ns :file :line :column}` shape as
  registration-site coords."
  (:require [re-frame.source-coords :as source-coords]))

#?(:clj
   (defn ^:private call-site-form
     "Build the literal call-site cond-> map for a callable's macro
     form."
     [form-meta ns-sym file]
     (source-coords/coords-form form-meta file ns-sym)))

;; ---- dispatch / dispatch-sync emitters -----------------------------------
;;
;; Both wrap an opts-key-assoc when an `opts` arg is supplied;
;; otherwise they emit the bare 1-arg form. The
;; `interop/debug-enabled?` gate stays at the outermost level so the
;; closure compiler folds the stamping branch under
;; `goog.DEBUG=false`.

#?(:clj
   (defn emit-dispatch
     "Build the expansion form for `re-frame.core/dispatch`. `delegate-
     sym` is the fully-qualified `re-frame.core/dispatch*` symbol; the
     emitter does not assume the consumer's namespace aliases re-
     frame.core."
     [form-meta ns-sym file delegate-sym event-vec opts-form has-opts?]
     (let [cs-form (call-site-form form-meta ns-sym file)]
       (if has-opts?
         `(if re-frame.interop/debug-enabled?
            (~delegate-sym ~event-vec
                           (assoc ~opts-form :rf.trace/call-site ~cs-form))
            (~delegate-sym ~event-vec ~opts-form))
         `(if re-frame.interop/debug-enabled?
            (~delegate-sym ~event-vec {:rf.trace/call-site ~cs-form})
            (~delegate-sym ~event-vec))))))

;; ---- subscribe emitter ---------------------------------------------------
;;
;; subscribe binds the call-site to `re-frame.trace/*call-site*` via
;; `with-call-site` so layer-N sub bodies (which run inside the
;; binding) carry the call-site through. Differs from dispatch's
;; opts-key shape because subscribe doesn't take an opts map.

#?(:clj
   (defn emit-subscribe
     "Build the expansion form for `re-frame.core/subscribe`. Two-arity
     forms differ only in the args spliced into the delegate call;
     `args-form` is the user-supplied arg list (`[query-v]` or
     `[frame-id query-v]`)."
     [form-meta ns-sym file delegate-sym args-form]
     (let [cs-form (call-site-form form-meta ns-sym file)]
       `(if re-frame.interop/debug-enabled?
          (re-frame.trace/with-call-site ~cs-form
            (~delegate-sym ~@args-form))
          (~delegate-sym ~@args-form)))))

;; ---- inject-cofx emitter -------------------------------------------------
;;
;; The 1-arity routes through the 3-arity with the
;; `re-frame.cofx/no-value` sentinel so the call-site rides through;
;; the 3-arity branch in `cofx/inject-cofx` detects the sentinel via
;; `identical?` and takes the no-value path through the cofx body.

#?(:clj
   (defn emit-inject-cofx
     "Build the expansion form for `re-frame.core/inject-cofx`."
     [form-meta ns-sym file delegate-sym cofx-id value-form has-value?]
     (let [cs-form (call-site-form form-meta ns-sym file)]
       (if has-value?
         `(if re-frame.interop/debug-enabled?
            (~delegate-sym ~cofx-id ~value-form ~cs-form)
            (~delegate-sym ~cofx-id ~value-form))
         `(if re-frame.interop/debug-enabled?
            (~delegate-sym ~cofx-id re-frame.cofx/no-value ~cs-form)
            (~delegate-sym ~cofx-id))))))
