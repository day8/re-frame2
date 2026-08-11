(ns re-frame.hicasso.examples.todo.require-graph
  "READ THIS APPLICATION'S DEPENDENCY EDGES OFF THE ANALYZER (rf2-hic-086).

  The bead's acceptance is *zero `impl` imports in app code, enforced by
  a test*. A test can enforce that in three ways and only one of them is
  worth having:

  - **Read the `:require` list and believe it.** That is not a test.
  - **Grep the source text.** Better, and still a claim about
    characters: a runtime `(require …)`, an alias introduced by a
    `:refer`, or a namespace reached through `:use-macros` all evade a
    regex over the `ns` form, and a comment mentioning `impl` trips one.
  - **Ask the compiler.** ClojureScript's analyzer holds, for every
    analysed namespace, the set of namespaces it actually depends on —
    `:requires`, `:require-macros`, `:uses`, `:use-macros`. That set is
    what the build was built from. It is the same instrument
    `re-frame.api-manifest.cljs-publics` reads the live public surface
    from, and it is read the same way: from a `.clj` macro, off
    `cljs.env/*compiler*`, emitted into the calling ClojureScript as a
    literal.

  ## Why the emitted value cannot go stale

  Inlining at macro-expansion time normally hides the inlined thing from
  the build. It does not here, and the reason is structural rather than
  careful: the calling test namespace `:require`s every namespace it
  asks about, so shadow-cljs already has a dependency edge from the test
  to each of them. Change what `views.cljs` requires and `views` is
  recompiled, and the test namespace that depends on it is recompiled
  too — with this macro re-expanded against the new graph.

  ## This file is a DUPLICATE, and the duplication is temporary

  `re-frame.hicasso.examples.slice.require-graph` is the same mechanism,
  written first, for the sibling witness application. The two beads ran
  in parallel off one base and neither branch could `:require` the
  other's namespace, so this one carries its own copy rather than
  depending on a file that did not exist when it was written. Lifting
  both to one `re-frame.hicasso.examples.require-graph` once both have
  landed is rf2-urgk.

  ## Scope

  Macro-expansion only, and JVM-only by construction (a `.clj`). It
  matches no test `ns-regexp` — `:node-test` selects `cljs-test$`,
  `:browser-test` selects `-dom-cljs-test$` — so it is compiled as a
  dependency of its one consumer and run nowhere."
  (:require [cljs.analyzer.api :as ana-api]
            [cljs.env :as env]))

(defn dependency-edges
  "The set of namespace symbols `ns-sym` depends on, read off the
  analyzer compilation env `state`.

  All four edge kinds, unioned: `:requires` and `:uses` are the
  ClojureScript ones, `:require-macros` and `:use-macros` the Clojure
  ones a `:require-macros` / `(:require … :refer-macros)` establishes. A
  check that read only `:requires` would be blind to exactly the door a
  macro reaches through — which matters here, because `h/defview` IS a
  macro and the door is reached both ways.

  Three edges are dropped, and all three are the COMPILER's rather than
  the author's: the namespace's own name (the analyzer records a
  self-edge for the alias-less form), `cljs.core`, and `goog` — the
  Closure Library root every analysed ClojureScript namespace carries
  whether or not a line of its source mentions it. Nothing else is
  filtered; in particular no `re-frame.*` edge is, which is the half a
  filter could quietly hollow out."
  [state ns-sym]
  (let [info (ana-api/find-ns state ns-sym)]
    (into (sorted-set)
          (remove #{ns-sym 'cljs.core 'cljs.core$macros 'goog})
          (concat (vals (:requires info))
                  (vals (:require-macros info))
                  (vals (:uses info))
                  (vals (:use-macros info))))))

(defmacro emit-dependency-graph
  "Expand to a literal `{\"<namespace>\" [\"<dependency>\" …]}` map for
  each namespace in `ns-syms` (a quoted vector of symbols), read from the
  ClojureScript analyzer's compilation environment.

  Strings rather than symbols so the emitted value is plain data a
  failing assertion prints legibly, and sorted so a report is stable."
  [ns-syms]
  (let [syms (if (and (seq? ns-syms) (= 'quote (first ns-syms)))
               (second ns-syms)
               ns-syms)]
    (into (sorted-map)
          (map (fn [s] [(str s) (mapv str (dependency-edges env/*compiler* s))]))
          syms)))
