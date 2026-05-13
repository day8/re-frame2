(ns re-frame.core-reg-view-macro
  "Helpers for the view-registration and frame-scope lexical macros —
  `reg-view`, `reg-machine`, `with-frame`, `bound-fn`, `with-fx-
  overrides`, `with-managed-request-stubs`. Per Spec 004 §reg-view,
  Spec 005 §Source-coord stamping, Spec 002 §with-frame / §bound-fn /
  §`:fx-overrides`, Spec 014 §Testing.

  Carved out of `re-frame.core` per rf2-4rnui so the public namespace
  stays under the 250-LoC leaf ceiling (rf2-zkca8). The user-facing
  `defmacro reg-view` / `with-frame` / etc. shells live in
  `re-frame.core` itself (they MUST, so `rf/reg-view` resolves alias-
  qualified per Clojure's standard `ns-alias/Var` lookup); each shell
  is a one-line call into the matching `expand-…` plain fn here.

  The expander helpers stay plain CLJ fns so CLJS test files can also
  exercise them JVM-side.

  File naming uses the flat dash-form (per rf2-2vbm)."
  (:require [re-frame.source-coords :as source-coords]))

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
       (classifier body))))

#?(:clj
   (defn expand-reg-view
     "Build the expansion form for a `reg-view` macro call. `form-meta` is
     `(meta &form)`; `current-ns-sym` is `(ns-name *ns*)`; `current-file`
     is `*file*` at expansion time. Captured as literals so the emitted
     form does not reference `*ns*` / `*file*` at runtime (required for
     CLJS, where `cljs.core/*ns*` is nil at runtime).

     Per rf2-yfbx: when reagent-slim is on the classpath the body is
     classified (Form-1 / Form-2) at expansion time and the wrapper fn
     is stamped with `^{:reagent2/form ...}` meta — an additive perf
     hint; the runtime detection in `reagent2.impl.component/wrap-
     render` remains load-bearing for correctness."
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
             ;; Wrapper fn carries form-tag on its own meta so renderers
             ;; reaching it via `(rf/view :id)` see the tag without a
             ;; registry-slot round-trip.
             fn-body  `(fn ~sym ~args
                         (let [~'dispatch  (re-frame.core/dispatcher)
                               ~'subscribe (re-frame.core/subscriber)]
                           ~@body))
             fn-form  (cond-> fn-body
                        form-tag (with-meta {:reagent2/form form-tag}))]
         ;; Per Conventions §`reg-*` return-value (rf2-hzos): every reg-*
         ;; macro returns its primary id. The trailing `~id` is load-
         ;; bearing — without it the `def` would be the last form and the
         ;; macro would return the Var, breaking the contract.
         `(do
            (binding [re-frame.source-coords/*pending-coords*
                      ~(source-coords/coords-form form-meta current-file current-ns-sym)]
              (re-frame.core/reg-view* ~id
                ~full-slot-meta
                ~fn-form))
            ~def-form
            ~id)))))

;; ---- frame-scope lexical-binding macro expansions ------------------------
;;
;; `with-frame` discriminates on first-arg shape: a 2-element vector
;; `[sym expr]` triggers Shape 2 (eval, bind, run, destroy); anything
;; else is Shape 1 (bind an existing frame-id). Per Spec 002 §with-frame.

#?(:clj
   (defn expand-with-frame
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

       :else
       `(binding [re-frame.frame/*current-frame* ~bindings]
          ~@body))))

#?(:clj
   (defn expand-bound-fn
     [argv body]
     (let [frame-sym (gensym "frame__")]
       `(let [~frame-sym (re-frame.core/current-frame)]
          (fn ~argv
            (binding [re-frame.frame/*current-frame* ~frame-sym]
              ~@body))))))
