(ns hooks.re-frame.hicasso
  "clj-kondo hooks for the Hicasso authoring surface (rf2-hic-022; reduced to
  macro shapes under rf2-r3r00).

  One job. `defview`, `event` and `defhost` are `defn`-, `fn`- and `def`-shaped
  macros, so the hooks rewrite them into those forms and let kondo's own
  analysis do the work — arglists, arity, lexical bindings, unused bindings.
  Without this a view's name and every destructured prop read as
  `Unresolved symbol`. This is the same shape as the repo-root
  `hooks.re-frame.core` hook for `reg-view`, and is deliberately no cleverer.

  No behavioral findings are registered here. The six bespoke
  `:re-frame.hicasso/*` checks this file once carried were retired under
  rf2-r3r00: behavior is the runtime's law, refused loudly at its execution
  boundary, and a lint layer that re-derives it is a second grammar to
  maintain."
  (:require [clj-kondo.hooks-api :as api]))

(defn defview
  "`(defview sym doc? [props] body+)` -> `(defn sym doc? [props] body+)`."
  [{:keys [node]}]
  (let [[_defview sym & more] (:children node)
        [doc more]            (if (api/string-node? (first more))
                                [(first more) (rest more)]
                                [nil more])
        [argv & body]         more]
    {:node (api/list-node
             (concat [(api/token-node 'clojure.core/defn) sym]
                     (when doc [doc])
                     [argv]
                     body))}))

(defn event
  "`(event [args] body+)` -> `(fn [args] body+)`."
  [{:keys [node]}]
  (let [[_event argv & body] (:children node)]
    {:node (api/list-node
             (list* (api/token-node 'clojure.core/fn) argv body))}))

(defn defhost
  "`(defhost sym doc? component opts?)` -> a `def` of the minted head.

  Not `defn`: a host declares no argument vector, because the props it
  accepts are the foreign component's business rather than the
  declaration's. `opts` is analysed as an ordinary expression so a
  reference inside `:fallback` / `:callbacks` is neither unresolved nor unused."
  [{:keys [node]}]
  (let [[_defhost sym & more] (:children node)
        [doc more]            (if (api/string-node? (first more))
                                [(first more) (rest more)]
                                [nil more])
        [component opts]      more
        ;; `do` and `def` are SPECIAL FORMS, so they are spelled bare: a
        ;; qualified `clojure.core/do` reads as a var that does not exist and
        ;; kondo says so, at the call site, in the consumer's file.
        value                 (if opts
                                (api/list-node
                                  [(api/token-node 'do) opts component])
                                component)]
    {:node (api/list-node
             (concat [(api/token-node 'def) sym]
                     (when doc [doc])
                     [value]))}))
