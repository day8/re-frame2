(ns hooks.re-frame.core
  "clj-kondo macro hook for `re-frame.core/reg-view`.

  Per spec/004-Views.md, `reg-view` is a defn-shape macro that auto-
  injects two lexical bindings — `dispatch` (from `(re-frame.core/
  dispatcher)`) and `subscribe` (from `(re-frame.core/subscriber)`) —
  into the body. clj-kondo doesn't macroexpand by default, so without
  this hook the body's `dispatch` / `subscribe` references all read as
  `Unresolved symbol`.

  The hook rewrites `(reg-view sym [args] body)` into

      (defn sym [args]
        (let [dispatch  (re-frame.core/dispatcher)
              subscribe (re-frame.core/subscriber)]
          body))

  preserving any leading docstring + optional attr-map, so kondo's
  built-in `defn` analysis covers arglist + arity + lexical bindings."
  (:require [clj-kondo.hooks-api :as api]))

(defn with-frame
  "clj-kondo macro hook for `re-frame.core/with-frame`.

  Two shapes — per Spec 002 §with-frame:

      (with-frame :keyword body+)        ;; pin to existing frame
      (with-frame [sym expr] body+)      ;; lexical bind, run, dispose

  Rewrites:
    - keyword shape → `(do body)`
    - binding shape → `(let [sym expr] body)`"
  [{:keys [node]}]
  (let [[_with-frame discriminator & body] (:children node)]
    (cond
      ;; `(with-frame [sym expr] body+)` — binding-vector shape.
      (api/vector-node? discriminator)
      {:node (api/list-node
               (list* (api/token-node 'let)
                      discriminator
                      body))}
      ;; `(with-frame :keyword body+)` — keyword shape; the keyword is
      ;; eval'd as an expression (a no-op) and the body runs sequentially.
      :else
      {:node (api/list-node
               (list* (api/token-node 'do)
                      discriminator
                      body))})))

(defn reg-view
  "Hook entry — see ns doc."
  [{:keys [node]}]
  (let [[_reg-view & rest-children] (:children node)
        sym-node (first rest-children)
        tail     (rest rest-children)
        ;; Split off optional docstring + attr-map (per `reg-view` parse).
        [docstring tail] (if (and (seq tail) (api/string-node? (first tail)))
                           [(first tail) (rest tail)]
                           [nil tail])
        [attr-map tail]  (if (and (seq tail) (api/map-node? (first tail)))
                           [(first tail) (rest tail)]
                           [nil tail])
        args-node (first tail)
        body      (rest tail)
        defn-children
        (cond-> [(api/token-node 'clojure.core/defn) sym-node]
          docstring (conj docstring)
          attr-map  (conj attr-map)
          true      (conj args-node)
          true      (conj
                      (api/list-node
                        (list*
                          (api/token-node 'let)
                          (api/vector-node
                            [(api/token-node 'dispatch)
                             (api/list-node
                               [(api/token-node 're-frame.core/dispatcher)])
                             (api/token-node 'subscribe)
                             (api/list-node
                               [(api/token-node 're-frame.core/subscriber)])])
                          ;; Insert a leading no-op reference to
                          ;; `dispatch` / `subscribe` so kondo's
                          ;; unused-binding linter sees both bindings
                          ;; referenced (a view body that only uses one
                          ;; of the injections would otherwise trip the
                          ;; linter on the unused side). The leading
                          ;; position is harmless to kondo's body-shape
                          ;; analysis — only the LAST form is the
                          ;; return value, which is the user's body.
                          (cons
                            (api/list-node
                              [(api/token-node 'do)
                               (api/token-node 'dispatch)
                               (api/token-node 'subscribe)])
                            body)))))]
    {:node (api/list-node defn-children)}))
