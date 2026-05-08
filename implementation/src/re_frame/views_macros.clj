(ns re-frame.views-macros
  "Compile-time macros that go alongside re-frame.views (CLJS).

  Per Spec 002 §View ergonomics and Spec 004 §The h macro / §reg-view
  defs the Var by default. The macros expand into runtime calls against
  re-frame.core (for the JVM-shared dispatcher / subscriber / dynamic
  *current-frame*) and re-frame.views (for the React-context bridge).

  These macros are JVM-loadable (this is a .clj file) and consumed by
  ClojureScript compilation via:

    (ns my-app.core
      (:require        [re-frame.views :as v])
      (:require-macros [re-frame.views-macros :refer [reg-view with-frame
                                                         bound-fn h]]))"
  ;; bound-fn shadows clojure.core/bound-fn — the v2 surface deliberately
  ;; reuses the name (per Spec 002 §bound-fn) for the frame-capturing form.
  (:refer-clojure :exclude [bound-fn]))

;; ---- with-frame ----------------------------------------------------------
;;
;; Sugar over (binding [*current-frame* fid] body). Supports two shapes:
;;   (with-frame :foo body)        — pin to a literal/keyword frame.
;;   (with-frame [sym expr] body)  — name and bind in one form (matches
;;                                    Clojure's let-shape conventions).

(defmacro with-frame
  "Bind *current-frame* around `body`. The bound frame is the resolution
  source for dispatcher / subscriber / and (in CLJS) the React-context
  fallback inside the body."
  [bindings & body]
  (cond
    (and (vector? bindings) (= 2 (count bindings)))
    (let [[sym expr] bindings]
      `(let [~sym ~expr]
         (binding [re-frame.frame/*current-frame* ~sym]
           ~@body)))

    :else
    `(binding [re-frame.frame/*current-frame* ~bindings]
       ~@body)))

;; ---- bound-fn ------------------------------------------------------------
;;
;; Captures *current-frame* at definition time, restores it at call time.
;; Useful for closures called from async boundaries (timers, http-handlers,
;; promises) where dynamic-var binding has been lost.

(defmacro bound-fn
  "Return a fn that captures the current frame and re-binds *current-frame*
  inside its body. Per Spec 002 §bound-fn."
  [argv & body]
  (let [frame-sym (gensym "frame__")]
    `(let [~frame-sym (re-frame.core/current-frame)]
       (fn ~argv
         (binding [re-frame.frame/*current-frame* ~frame-sym]
           ~@body)))))

;; ---- reg-view ------------------------------------------------------------
;;
;; Per Spec 004 §reg-view: defn-shape macro. Two surfaces emit the same
;; expansion — re-frame.core/reg-view (the canonical home) and the
;; legacy re-frame.views-macros/reg-view (kept so existing
;; `(:require-macros [re-frame.views-macros :refer [reg-view]])` imports
;; keep working without an across-the-board sweep of example imports).
;;
;; The macro logic lives here as plain helpers so both defmacros can
;; share it without circular load issues.

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
      :else nil)))

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
    (str "a " (some-> x type .getSimpleName) " — " (pr-str x))))

(defn expand-reg-view
  "Build the expansion form for a reg-view macro call. Used by both
  re-frame.core/reg-view and re-frame.views-macros/reg-view. `form-meta`
  is `(meta &form)` from the calling macro; `current-ns-sym` is
  `(ns-name *ns*)` at expansion time."
  [form-meta current-ns-sym sym more]
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
          line (:line form-meta)
          col  (:column form-meta)
          def-form (if docstring
                     `(def ~sym ~docstring (re-frame.core/get-view ~id))
                     `(def ~sym (re-frame.core/get-view ~id)))
          full-slot-meta (cond-> slot-meta
                           docstring (assoc :doc docstring))]
      `(do
         (binding [re-frame.source-coords/*pending-coords*
                   (cond-> {:ns (ns-name *ns*)}
                     *file* (assoc :file *file*)
                     ~line  (assoc :line ~line)
                     ~col   (assoc :column ~col))]
           (re-frame.core/reg-view* ~id
             ~full-slot-meta
             (fn ~sym ~args
               (let [~'dispatch  (re-frame.core/dispatcher)
                     ~'subscribe (re-frame.core/subscriber)]
                 ~@body))))
         ~def-form))))

(defmacro reg-view
  "Register a view as a defn-shape macro. Per Spec 004 §reg-view.

  Shape:
    (reg-view sym [args] body+)
    (reg-view sym docstring [args] body+)
    (reg-view ^{:rf/id :explicit/id} sym [args] body+)

  Auto-derives the id from `(keyword (str *ns*) (str sym))`. Override
  via `^{:rf/id :explicit/id}` metadata on the symbol. Auto-injects
  lexical bindings `dispatch` and `subscribe`. Defs the symbol to the
  registered render fn.

  Compile-time error if the second arg (after optional docstring) is
  not a vector. For runtime registration with computed ids or
  non-defn-shape bodies (e.g. Form-3 / `create-class`), use
  `re-frame.core/reg-view*` instead.

  This is the legacy import path; new code should
  `:require-macros [re-frame.core :refer [reg-view]]` directly. Both
  surfaces emit the same expansion."
  {:arglists '([sym args body+] [sym docstring args body+])}
  [sym & more]
  (expand-reg-view (meta &form) (ns-name *ns*) sym more))

;; ---- h --------------------------------------------------------------------
;;
;; Per Spec 004 §The h macro: walk a hiccup tree at compile time and rewrite
;; bare keyword head positions that name registered views into runtime
;; (rf/get-view :keyword) calls. HTML tag-name keywords (`:div`, `:p`, …)
;; are NOT rewritten — they remain DOM tags.
;;
;; Heuristic for "this keyword names a view":
;;   - It's namespaced (`:my-app/widget`) — host DOM tags are never
;;     namespaced.
;;
;; This is a coarse approximation; applications can override by passing
;; an explicit symbol head.

(defn- view-keyword? [x]
  (and (keyword? x) (some? (namespace x))))

(defn- rewrite-hiccup [form]
  (cond
    (and (vector? form)
         (view-keyword? (first form)))
    (let [k     (first form)
          args  (rest form)]
      `[(re-frame.core/get-view ~k) ~@(map rewrite-hiccup args)])

    (vector? form)
    (mapv rewrite-hiccup form)

    (map? form)
    (reduce-kv (fn [m kk vv] (assoc m kk (rewrite-hiccup vv))) {} form)

    :else form))

(defmacro h
  "Compile-time hiccup walker that rewrites [:my-ns/widget args] into
  [(rf/get-view :my-ns/widget) args]. Bare keyword heads (`:div`,
  `:span`, …) are left alone — they're DOM tags. Per Spec 004 §The h
  macro: an opt-in escape hatch; the canonical view-call form remains
  the local Var defined by reg-view."
  [hiccup]
  (rewrite-hiccup hiccup))
