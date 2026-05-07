(ns re-frame-2.views-macros
  "Compile-time macros that go alongside re-frame-2.views (CLJS).

  Per Spec 002 §View ergonomics and Spec 004 §The h macro / §reg-view
  defs the Var by default. The macros expand into runtime calls against
  re-frame-2.core (for the JVM-shared dispatcher / subscriber / dynamic
  *current-frame*) and re-frame-2.views (for the React-context bridge).

  These macros are JVM-loadable (this is a .clj file) and consumed by
  ClojureScript compilation via:

    (ns my-app.core
      (:require        [re-frame-2.views :as v])
      (:require-macros [re-frame-2.views-macros :refer [reg-view with-frame
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
         (binding [re-frame-2.frame/*current-frame* ~sym]
           ~@body)))

    :else
    `(binding [re-frame-2.frame/*current-frame* ~bindings]
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
    `(let [~frame-sym (re-frame-2.core/current-frame)]
       (fn ~argv
         (binding [re-frame-2.frame/*current-frame* ~frame-sym]
           ~@body)))))

;; ---- reg-view ------------------------------------------------------------
;;
;; Per Spec 004 §reg-view defs the Var by default: the macro auto-defs a
;; local Var named after the keyword's name so the call site can refer to
;; the view as a value (Reagent's idiomatic Form-1).
;;
;; Forms supported:
;;   (reg-view :id render-fn)
;;   (reg-view :id metadata render-fn)

(defmacro reg-view
  "Register a view by keyword id and def a same-named local Var bound
  to the wrapped render fn. The local Var is the call-site reference;
  the registered view is what frame-aware tooling (SSR, get-view) reads."
  ([id render-fn]
   `(reg-view ~id {} ~render-fn))
  ([id metadata render-fn]
   (let [sym (-> id name symbol)]
     `(def ~sym
        (re-frame-2.views/reg-view* ~id ~metadata ~render-fn)))))

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
      `[(re-frame-2.core/get-view ~k) ~@(map rewrite-hiccup args)])

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
