(ns re-frame.core-reg-view-macro
  "Helpers for the view-registration and frame-scope lexical macros —
  `reg-view`, `reg-machine`, `with-frame`, `frame-bound-fn`, `with-fx-
  overrides`, `with-managed-request-stubs`. Per Spec 004 §reg-view,
  Spec 005 §Source-coord stamping, Spec 002 §with-frame / §frame-bound-fn /
  §`:fx-overrides`, Spec 014 §Testing.

  Carved out of `re-frame.core` so the public namespace stays a thin
  facade focused on user-visible Var resolution rather than macro
  expansion bulk; this ns owns the cohesive responsibility of
  view-registration and frame-scope lexical expansion. The user-facing
  `defmacro reg-view` / `with-frame` / etc. shells live in
  `re-frame.core` itself (they MUST, so `rf/reg-view` resolves
  alias-qualified per Clojure's `ns-alias/Var` lookup); each shell is
  a one-line call into the matching `expand-…` plain fn here.

  The expander helpers stay plain CLJ fns so CLJS test files can also
  exercise them JVM-side."
  (:require [re-frame.error :as error]
            [re-frame.source-coords :as source-coords]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- reg-view expansion helpers ------------------------------------------

#?(:clj
   (defn parse-reg-view-args
     "Per Spec 004 §reg-view defn-shape. Parses (sym docstring? args body+)
     into {:sym :docstring :args :body}. Returns nil when shape is invalid."
     [more]
     (let [[a & rest1] more]
       (cond
         (vector? a)
         {:docstring nil :args a :body rest1}
         (and (string? a) (vector? (first rest1)))
         {:docstring a :args (first rest1) :body (next rest1)}
         :else nil))))

#?(:clj
   (defn describe-reg-view-bad-second-arg
     "Human-readable description of an invalid second-arg to reg-view, for
     the compile-error message. Per Spec 004 §reg-view compile-error
     contract: the rejected cases are Var-ref symbol, create-class call,
     and computed-fn call."
     [x]
     (cond
       (symbol? x)
       (str "a Var reference (" x ")")
       (and (seq? x) (symbol? (first x)) (= "create-class" (name (first x))))
       (str "a (" (first x) " …) call")
       (seq? x)
       (str "a (" (first x) " …) call")
       (nil? x)
       "nothing"
       :else
       (str "a " (.getSimpleName ^Class (type x)) " — " (pr-str x)))))

#?(:clj
   (defn- reagent-slim-form-tag
     "Classify body shape (Form-1 / Form-2) at compile time when reagent-
     slim is on the classpath. Returns a keyword form-tag or nil. Per
     rf2-yfbx — the compile-time fold sits in the canonical `reg-view`
     macro (no separate `defview`); the runtime detection in `reagent2.
     impl.component/wrap-render` is the load-bearing correctness path,
     this tag is an additive perf hint. `requiring-resolve` keeps core
     free of a static reagent-slim dep — UIx/Helix builds resolve nil."
     [body]
     (when-let [classifier (try (requiring-resolve
                                   'reagent2.impl.component/classify-form-body)
                                 (catch Exception _ nil))]
       (when (bound? classifier)
         (classifier body)))))

#?(:clj
   (defn expand-reg-view
     "Build the expansion form for a `reg-view` macro call. `form-meta` is
     `(meta &form)`; `current-ns-sym` is `(ns-name *ns*)`; `current-file`
     is `*file*` at expansion time. Captured as literals so the emitted
     form does not reference `*ns*` / `*file*` at runtime (required for
     CLJS, where `cljs.core/*ns*` is nil at runtime).

     When reagent-slim is on the classpath the body is classified
     (Form-1 / Form-2) at expansion time and the wrapper fn is stamped
     with `^{:reagent2/form ...}` meta — an additive perf hint; the
     runtime detection in `reagent2.impl.component/wrap-render` remains
     load-bearing for correctness."
     [form-meta current-ns-sym current-file sym more]
     (let [parsed   (parse-reg-view-args more)
           sym-meta (or (meta sym) {})
           id-meta  (:rf/id sym-meta)
           id       (or id-meta (keyword (str current-ns-sym) (str sym)))
           ;; Anything on the symbol other than :rf/id is treated as slot
           ;; metadata (matches Clojure's defn idiom: ^{:doc "..."} sym) —
           ;; EXCEPT the reader's source-position keys.
           ;;
           ;; Per rf2-quir9: the CLJS analyzer's indexing reader stamps
           ;; `:file` / `:line` / `:column` (+ `:source` / `:end-*`) onto
           ;; the view SYMBOL, and that `:file` is CLASSPATH-RELATIVE
           ;; (`"standard_epochs/core.cljs"`). If we let those ride into the
           ;; registry slot they become `user-meta` in
           ;; `source-coords/merge-coords`, where user keys WIN over the
           ;; pending coords — so the relative reader `:file` clobbers the
           ;; rf2-wvsxg-absolutised value bound into `*pending-coords*` (see
           ;; `coord-form` below), shipping a relative `:file` into
           ;; `(rf/handler-meta :view id)` and breaking Xray / IDE
           ;; open-in-editor for views. The `reg-event-*` path never hits
           ;; this because it splices args verbatim and contributes no
           ;; symbol-position meta as user-meta — its public `:file` is the
           ;; absolutised `*pending-coords*` value. Stripping the reader
           ;; position keys here makes `*pending-coords*` the SINGLE source
           ;; of source-coords for the view's public meta, symmetric with
           ;; `reg-event-*`. `:source` is the reader's whole-form-text key
           ;; (not a user slot) and is dropped alongside.
           slot-meta (dissoc sym-meta
                             :rf/id
                             :file :line :column :source :end-line :end-column)]
       (when (nil? parsed)
         (error/throw-error!
           :rf.error/reg-view-bad-args
           'rf/reg-view
           (str "reg-view's second argument must be an args vector "
                "(defn-shape: (reg-view sym [args] body)). Got "
                (describe-reg-view-bad-second-arg (first more))
                ". For runtime registration, use "
                "(re-frame.core/reg-view* :id render-fn).")
           {:recovery :fix-registration
            :extra    {:sym            sym
                       :got            (first more)
                       :args-after-sym (vec more)}}))
       (let [{:keys [docstring args body]} parsed
             form-tag (reagent-slim-form-tag body)
             def-form (if docstring
                        `(def ~sym ~docstring (re-frame.core/view ~id))
                        `(def ~sym (re-frame.core/view ~id)))
             full-slot-meta (cond-> slot-meta
                              docstring (assoc :doc docstring)
                              form-tag  (assoc :reagent2/form form-tag))
             ;; Per rf2-cry25 §Production elision: the view's dev source-
             ;; coord literal (WITH `:column`, via `coords-form`), reused
             ;; for the `*pending-coords*` binding in the `do` form below.
             ;; The injected frame-handle's `:dispatch-opts` /
             ;; `:subscribe-call-site` args ride their OWN outer
             ;; `interop/debug-enabled?` gate (see `dispatch-opts-form` /
             ;; `sub-coord-form`) so the `:rf.trace/call-site` keyword +
             ;; coord literal DCE wholesale under `:advanced` +
             ;; `goog.DEBUG=false` — mirroring `core-call-site-macros`'
             ;; `gate` (the elision probe's `rf.trace/call-site` sentinel
             ;; must stay ABSENT in the prod bundle). `:source :ui` is NOT
             ;; dev-only — it is the functional-origin classifier read
             ;; unconditionally by `dispatch!` (router.cljc) — so the prod
             ;; branch still carries `{:source :ui}`, just no call-site.
             coord-form `(if re-frame.interop/debug-enabled?
                           ~(source-coords/coords-form      form-meta current-file current-ns-sym)
                           ~(source-coords/prod-coords-form form-meta current-file current-ns-sym))
             dispatch-opts-form
             `(if re-frame.interop/debug-enabled?
                {:source :ui :rf.trace/call-site
                 ~(source-coords/coords-form form-meta current-file current-ns-sym)}
                {:source :ui})
             sub-coord-form
             `(if re-frame.interop/debug-enabled?
                ~(source-coords/coords-form form-meta current-file current-ns-sym)
                nil)
             ;; Wrapper fn carries form-tag on its own meta so renderers
             ;; reaching it via `(rf/view :id)` see the tag without a
             ;; registry-slot round-trip.
             ;;
             ;; Per rf2-kkut0 (frame-affordance redesign) + rf2-cry25
             ;; (Option A, Mike-ruled): the reg-view injection is now SUGAR
             ;; over a single `make-frame-handle` — the handle captures the
             ;; render-time frame ONCE, and the injected `dispatch` /
             ;; `subscribe` NOUNS are its `:dispatch` / `:subscribe` ops.
             ;; They shadow the coord-capturing `dispatch` / `subscribe`
             ;; MACROS for the whole view body, so a view's on-click
             ;; `#(dispatch [...])` would otherwise classify as
             ;; `:source :unknown` with no call-site. We pass the view's
             ;; coord into the handle's `:dispatch-opts` so the dispatch
             ;; carries `:source :ui` + the view's `:rf.trace/call-site`,
             ;; and `:subscribe-call-site` so the subscribe carries the
             ;; same call-site on its synchronous error path. The body
             ;; stays spliced VERBATIM (`~@body`) — no code-walking, no
             ;; rewriting of user view code; the blast radius is the
             ;; handle's opts only. Render-time frame capture is preserved
             ;; (`make-frame-handle` captures `(current-frame-id)` once).
             fn-body  `(fn ~sym ~args
                         (let [handle#    (re-frame.core/make-frame-handle
                                            (re-frame.core/current-frame-id)
                                            {:dispatch-opts       ~dispatch-opts-form
                                             :subscribe-call-site ~sub-coord-form})
                               ~'dispatch  (:dispatch handle#)
                               ~'subscribe (:subscribe handle#)]
                           ~@body))
             fn-form  (cond-> fn-body
                        form-tag (with-meta {:reagent2/form form-tag}))]
         ;; Per Conventions §`reg-*` return-value (rf2-hzos): every reg-*
         ;; macro returns its primary id. The trailing `~id` is load-
         ;; bearing — without it the `def` would be the last form and the
         ;; macro would return the Var, breaking the contract.
         ;; Per rf2-3un2g §Production elision: the binding-value rides
         ;; an outer `interop/debug-enabled?` gate so Closure DCEs the
         ;; dev coords (with `:column`) under `:advanced + goog.DEBUG=false`.
         ;; The bound coords are still captured at runtime via the
         ;; parallel `error-coords-by-id` registry (see
         ;; `re-frame.source-coords`).
         `(do
            (binding [re-frame.source-coords/*pending-coords* ~coord-form]
              (re-frame.core/reg-view* ~id
                ~full-slot-meta
                ~fn-form))
            ~def-form
            ~id)))))

;; ---- frame-scope lexical-binding macro expansions ------------------------
;;
;; `with-frame` and `with-new-frame` are two separate macros with
;; non-overlapping shapes:
;;
;;   `with-frame`     — pin to an existing frame-id:
;;                      `(with-frame :keyword body+)`.
;;                      Rejects vector argument at compile time.
;;
;;   `with-new-frame` — eval an expression, bind a local symbol, run
;;                      the body in the new frame, destroy on exit:
;;                      `(with-new-frame [sym expr] body+)`.
;;                      Rejects keyword argument at compile time.
;;
;; Per Spec 002 §with-frame.

#?(:clj
   (defn expand-with-frame
     "Expansion for `with-frame` — pin form only. Rejects a vector
     argument with `:rf.error/with-frame-vector-form` (the caller meant
     `with-new-frame`)."
     [frame-id body]
     (cond
       (vector? frame-id)
       (error/throw-error!
         :rf.error/with-frame-vector-form
         'rf/with-frame
         (str "with-frame pins to an existing frame-id (keyword). "
              "Got a vector — did you mean `with-new-frame`, "
              "which evals, binds, runs, and destroys?")
         {:recovery :use-with-new-frame
          :extra    {:got frame-id}})

       :else
       `(binding [re-frame.frame/*current-frame* ~frame-id]
          ~@body))))

#?(:clj
   (defn expand-with-new-frame
     "Expansion for `with-new-frame` — eval/destroy form only. Rejects
     anything other than a 2-element vector binding."
     [bindings body]
     (cond
       (and (vector? bindings) (= 2 (count bindings)))
       (let [[sym expr] bindings]
         `(let [~sym ~expr]
            (try
              (binding [re-frame.frame/*current-frame* ~sym]
                ~@body)
              (finally
                (re-frame.frame/destroy-frame! ~sym)))))

       ;; Common mistake: passed a keyword instead of `[sym expr]`. The
       ;; caller meant `with-frame` (pin form). Reject loudly.
       (keyword? bindings)
       (error/throw-error!
         :rf.error/with-new-frame-keyword-form
         'rf/with-new-frame
         (str "with-new-frame evals, binds, runs, and destroys. "
              "Got a keyword — did you mean `with-frame`, "
              "which pins to an existing frame-id?")
         {:recovery :use-with-frame
          :extra    {:got bindings}})

       ;; Vector but wrong arity — almost certainly a typo of the
       ;; binding form (e.g. `[]` or `[f x y]`). Reject at compile time —
       ;; per Spec 002 §with-frame.
       (vector? bindings)
       (error/throw-error!
         :rf.error/with-new-frame-bad-binding
         'rf/with-new-frame
         (str "with-new-frame's binding must be [sym expr]. "
              "Got " (count bindings) " element"
              (when-not (= 1 (count bindings)) "s") ".")
         {:recovery :fix-registration
          :extra    {:got   bindings
                     :count (count bindings)}})

       :else
       (error/throw-error!
         :rf.error/with-new-frame-bad-binding
         'rf/with-new-frame
         "with-new-frame's binding must be a 2-element vector [sym expr]."
         {:recovery :fix-registration
          :extra    {:got bindings}}))))

#?(:clj
   (defn expand-frame-bound-fn
     [argv body]
     (let [frame-sym (gensym "frame__")]
       `(let [~frame-sym (re-frame.core/current-frame-id)]
          (fn ~argv
            (binding [re-frame.frame/*current-frame* ~frame-sym]
              ~@body))))))
