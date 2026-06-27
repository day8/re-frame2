(ns hooks.re-frame.core
  "clj-kondo macro hook for `re-frame.core/reg-view`.

  Per spec/004-Views.md and rf2-kkut0 (frame-affordance redesign),
  `reg-view` is a defn-shape macro that auto-injects two lexical
  bindings — `dispatch` and `subscribe` — into the body, sourced from a
  single `capture-frame` (the keystone OPERATION BUNDLE):
  `(:dispatch (re-frame.core/make-capture-frame ...))` and
  `(:subscribe (re-frame.core/make-capture-frame ...))`. clj-kondo doesn't
  macroexpand by default, so without this hook the body's `dispatch` /
  `subscribe` references all read as `Unresolved symbol`.

  The hook rewrites `(reg-view sym [args] body)` into

      (defn sym [args]
        (let [handle    (re-frame.core/make-capture-frame
                          (re-frame.core/current-frame-id) {})
              dispatch  (:dispatch handle)
              subscribe (:subscribe handle)]
          body))

  preserving any leading docstring + optional attr-map, so kondo's
  built-in `defn` analysis covers arglist + arity + lexical bindings."
  (:require [clj-kondo.hooks-api :as api]))

(defn with-frame
  "clj-kondo macro hook for `re-frame.core/with-frame` — pin form only
  (per rf2-twoc5):

      (with-frame :keyword body+)        ;; pin to existing frame

  Rewrites to `(do <frame-id> body)` so kondo sees the body in
  sequential context."
  [{:keys [node]}]
  (let [[_with-frame frame-id & body] (:children node)]
    {:node (api/list-node
             (list* (api/token-node 'do)
                    frame-id
                    body))}))

(defn with-new-frame
  "clj-kondo macro hook for `re-frame.core/with-new-frame` — eval-bind-
  run-destroy form (per rf2-twoc5):

      (with-new-frame [sym expr] body+) ;; eval, bind, run, destroy

  Rewrites to `(let [sym expr] body)` so kondo sees `sym` as a lexical
  binding for the body."
  [{:keys [node]}]
  (let [[_with-new-frame bindings & body] (:children node)]
    {:node (api/list-node
             (list* (api/token-node 'let)
                    bindings
                    body))}))

(defn with-trace-recorder!
  "clj-kondo macro hook for `re-frame.test-support/with-trace-recorder!`
  (rf2-64iuw):

      (with-trace-recorder! [recs-sym opts?] body+)

  Rewrites to `(let [recs-sym (atom [])] opts? body+)` so kondo sees
  `recs-sym` as a lexical binding for the body AND the optional opts
  map's values (predicate / shape fn references) get scanned for
  unused-binding analysis — emitting the opts map as a no-op body form
  lets `:unresolved-symbol` + `:unused-binding` reach references inside
  it."
  [{:keys [node]}]
  (let [[_with-trace-recorder! bindings & body] (:children node)
        bindings-children (:children bindings)
        recs-sym (first bindings-children)
        opts     (second bindings-children)
        body-with-opts (if opts (cons opts body) body)]
    {:node (api/list-node
             (list* (api/token-node 'let)
                    (api/vector-node
                      [recs-sym
                       (api/list-node
                         [(api/token-node 'clojure.core/atom)
                          (api/vector-node [])])])
                    body-with-opts))}))

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
                               [(api/keyword-node :dispatch)
                                (api/list-node
                                  [(api/token-node 're-frame.core/make-capture-frame)
                                   (api/list-node
                                     [(api/token-node 're-frame.core/current-frame-id)])
                                   (api/map-node [])])])
                             (api/token-node 'subscribe)
                             (api/list-node
                               [(api/keyword-node :subscribe)
                                (api/list-node
                                  [(api/token-node 're-frame.core/make-capture-frame)
                                   (api/list-node
                                     [(api/token-node 're-frame.core/current-frame-id)])
                                   (api/map-node [])])])])
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
