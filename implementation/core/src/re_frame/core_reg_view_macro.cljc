(ns re-frame.core-reg-view-macro
  "View registration + frame-scope macro EMITTERS (per rf2-4rnui split
  from `re-frame.core`). JVM-only — the `defmacro` entrypoints live in
  `re-frame.core` so CLJS macro propagation works for users who
  `(:require [re-frame.core :as rf])`; this namespace owns the emitter
  pipelines.

  Surface (each defmacro lives in `re-frame.core` as a thin shell):

    `reg-view`        — defn-shape view registration (Spec 004). Auto-
                         derives id from `(keyword (str *ns*) (str sym))`,
                         injects lexical `dispatch` / `subscribe`, defs
                         the sym, registers under the id.
    `with-frame`      — Spec 002 §with-frame; lexical frame binding. Two
                         shapes (bare keyword / let-binding).
    `bound-fn`        — Spec 002 §bound-fn; captures `*current-frame*`
                         at definition time, restores it at call time.
    `with-fx-overrides` — Spec 002 §`:fx-overrides`; lexical scope.
    `with-managed-request-stubs` — Spec 014; install stubs, run body,
                         uninstall.

  The reg-view expansion pipeline (parse-reg-view-args /
  describe-reg-view-bad-second-arg / reagent-slim-form-tag /
  expand-reg-view) lives here as plain CLJ helpers so the defmacro in
  `re-frame.core` stays a one-line delegation and CLJS test files can
  also exercise the helpers JVM-side."
  (:require [re-frame.source-coords :as source-coords]))

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
       (str "a " (some-> x type .getSimpleName) " — " (pr-str x)))))

#?(:clj
   (defn- reagent-slim-form-tag
     "Classify the user's body shape (Form-1 / Form-2) at compile time,
     when `day8/reagent-slim` is on the classpath. Returns a keyword
     form-tag or nil when the helper isn't available. Per rf2-yfbx the
     compile-time fold sits in the canonical `reg-view` — there is no
     separate `defview` macro.

     Lookup goes through `requiring-resolve` so core does not statically
     depend on reagent-slim."
     [body]
     (when-let [classifier (try (requiring-resolve
                                  'reagent2.impl.component/classify-form-body)
                                (catch Exception _ nil))]
       (classifier body))))

#?(:clj
   (defn expand-reg-view
     "Build the expansion form for a reg-view macro call. `form-meta` is
     `(meta &form)` from the calling macro; `current-ns-sym` is
     `(ns-name *ns*)` at expansion time; `current-file` is `*file*` at
     expansion time. Both are captured as literals in the expansion so
     the emitted form does not reference `*ns*` / `*file*` at runtime —
     necessary for CLJS, where `cljs.core/*ns*` is nil at runtime.

     Per rf2-yfbx: when reagent-slim is on the classpath, the body is
     structurally classified (Form-1 / Form-2) at expansion time and
     the wrapper fn is stamped with `^{:reagent2/form ...}` meta. The
     reagent-slim runtime path reads this tag to skip the Form-1/2
     cond on the hot path. The runtime detection remains load-bearing
     for correctness; this is an additive perf hint."
     [form-meta current-ns-sym current-file sym more]
     (let [parsed   (parse-reg-view-args more)
           sym-meta (or (meta sym) {})
           id-meta  (:rf/id sym-meta)
           id       (or id-meta (keyword (str current-ns-sym) (str sym)))
           ;; Anything on the symbol other than :rf/id is treated as slot
           ;; metadata (matches Clojure's defn idiom: ^{:doc "..."} sym).
           slot-meta (dissoc sym-meta :rf/id)]
       (when (nil? parsed)
         (throw (ex-info
                  (str "reg-view second argument must be an args vector "
                       "(defn-shape: (reg-view sym [args] body)). Got "
                       (describe-reg-view-bad-second-arg (first more))
                       ". For runtime registration, use "
                       "(re-frame.core/reg-view* :id render-fn).")
                  {:sym sym :got (first more) :args-after-sym (vec more)})))
       (let [{:keys [docstring args body]} parsed
             form-tag (reagent-slim-form-tag body)
             def-form (if docstring
                        `(def ~sym ~docstring (re-frame.core/view ~id))
                        `(def ~sym (re-frame.core/view ~id)))
             full-slot-meta (cond-> slot-meta
                              docstring (assoc :doc docstring)
                              form-tag  (assoc :reagent2/form form-tag))
             ;; The wrapper fn carries the form-tag as its own meta too
             ;; so renderers that take the fn alone (e.g. directly via
             ;; (rf/view :id)) can still read it without round-tripping
             ;; through the registry slot.
             fn-form  (if form-tag
                        (with-meta
                          `(fn ~sym ~args
                             (let [~'dispatch  (re-frame.core/dispatcher)
                                   ~'subscribe (re-frame.core/subscriber)]
                               ~@body))
                          {:reagent2/form form-tag})
                        `(fn ~sym ~args
                           (let [~'dispatch  (re-frame.core/dispatcher)
                                 ~'subscribe (re-frame.core/subscriber)]
                             ~@body)))]
         ;; Per Conventions §`reg-*` return-value convention: every
         ;; `reg-*` macro returns its primary id. The auto-def is a side
         ;; effect; the macro's terminal value is `id`. Per rf2-hzos.
         `(do
            (binding [re-frame.source-coords/*pending-coords*
                      ~(source-coords/coords-form form-meta current-file current-ns-sym)]
              (re-frame.core/reg-view* ~id
                ~full-slot-meta
                ~fn-form))
            ~def-form
            ~id)))))

;; ---- frame-scope emitters -----------------------------------------------
;;
;; with-frame two shapes:
;;
;;   Shape 1 — bare keyword (operate on an existing frame):
;;     (with-frame :scratch body...)
;;     => (binding [frame/*current-frame* :scratch] body...)
;;
;;   Shape 2 — let-binding (create, use, destroy):
;;     (with-frame [f (make-frame opts)] body...)
;;     => (let [f (make-frame opts)]
;;          (try
;;            (binding [frame/*current-frame* f] body...)
;;            (finally (destroy-frame f))))
;;
;; The discriminator is the first argument: a vector triggers Shape 2;
;; anything else is Shape 1.

#?(:clj
   (defn emit-with-frame
     "Build the expansion form for `re-frame.core/with-frame`."
     [bindings body]
     (cond
       (and (vector? bindings) (= 2 (count bindings)))
       (let [[sym expr] bindings]
         `(let [~sym ~expr]
            (try
              (binding [re-frame.frame/*current-frame* ~sym]
                ~@body)
              (finally
                (re-frame.core/destroy-frame ~sym)))))

       :else
       `(binding [re-frame.frame/*current-frame* ~bindings]
          ~@body))))

#?(:clj
   (defn emit-bound-fn
     "Build the expansion form for `re-frame.core/bound-fn` — captures
     the current frame at definition time and re-binds
     `*current-frame*` inside the body."
     [argv body]
     (let [frame-sym (gensym "frame__")]
       `(let [~frame-sym (re-frame.core/current-frame)]
          (fn ~argv
            (binding [re-frame.frame/*current-frame* ~frame-sym]
              ~@body))))))
