(ns re-frame.ui.shadow-compile-model
  "Test-only model of Shadow 3.4.10's compile phase for the build-hook
  provenance fixtures (rf2-suz5b).

  A real compile does exactly two things to build state that re-frame.ui can
  observe. It REPLACES the source's whole `[:output rid]` map with a fresh
  compiler-shaped map — dropping re-frame.ui's per-pass marker — and it drives
  the build-state's installed `[:analyzer-passes]` over that source's forms. The
  second is what re-frame.ui's per-pass compile witness records, and these
  fixtures drive it through pinned ClojureScript's OWN calling convention rather
  than by inserting an expected id into a corroborating set:

    ;; cljs.analyzer/analyze*, ClojureScript 1.12.145
    (reduce (fn [ast pass] (pass env ast opts)) ast passes)

  `analyze!` below is that reduction verbatim. `compile-ns!` applies it to the
  `:ns` AST node `cljs.analyzer`'s `parse 'ns` produces — the ONLY form a
  viewless zero-declaration recompile still has, and therefore the strictest
  case. The build-hook provenance fixtures drive this model, and the real-Shadow
  warm-watch gate exercises the same witness end-to-end against a live daemon.

  Modelling the analyzer instead of Shadow's `[:shadow.build/build-info
  :compiled]` set is the whole point of rf2-suz5b: pinned Shadow derives that set
  in `shadow.build/resources-compiled-recently` as

    (and (not cached) (number? compiled-at) (> compiled-at compile-start))

  so a fixture that hands `:compiled` the resource id it expects assumes exactly
  the wall-clock fact under dispute — and hides that a genuine same-millisecond
  recompile is missing from it."
  (:require [clojure.test :refer [is]]))

(defn ns-node
  "The shape `cljs.analyzer`'s `parse 'ns` returns for `(ns <ns-sym> …)`.

  Shadow analyzes a source's leading `ns` form while its compile state still
  reads `cljs.user` (`do-analyze-cljs-string` advances the ns only in
  `post-analyze`, AFTER the passes have run), so the node's own `:name` — not the
  analyzer env — carries the declaring namespace here."
  [ns-sym]
  {:op :ns
   :name ns-sym
   :form (list 'ns ns-sym)
   :env {:ns {:name 'cljs.user}}
   :children []})

(defn analyze!
  "Run `build-state`'s installed analyzer passes over `ast` exactly as
  `cljs.analyzer/analyze*` does. Returns the (possibly rewritten) ast."
  [build-state env ast]
  (reduce (fn [ast pass] (pass env ast {}))
          ast
          (:analyzer-passes build-state)))

(defn compile-ns!
  "Model Shadow's compiler ANALYZING the source that declares `ns-sym` — the
  causal event re-frame.ui's compile witness observes. Side-effecting on the
  witness only; returns `build-state` unchanged so it composes in a `reduce`."
  [build-state ns-sym]
  (analyze! build-state {:ns {:name 'cljs.user}} (ns-node ns-sym))
  build-state)

(defn resources-compiled-recently
  "`shadow.build/resources-compiled-recently`, verbatim from pinned Shadow
  3.4.10 (`shadow/build.clj` L72-83) — the derivation behind
  `[:shadow.build/build-info :compiled]`:

    (filter (fn [src-id]
              (let [{:keys [cached compiled-at]} (get-in state [:output src-id])]
                (and (not cached) (number? compiled-at)
                     (> compiled-at compile-start)))))

  Reproduced here ONLY to prove what that set does NOT contain — that a genuine
  recompile stamped on or before `compile-start` is absent from it, so the
  corroboration rf2-8nn5k built on it silently disappears. Nothing in
  `re-frame.ui` calls it or anything like it."
  [{:keys [build-sources compile-start] :as state}]
  (into #{}
        (filter (fn [src-id]
                  (let [{:keys [cached compiled-at]} (get-in state [:output src-id])]
                    (and (not cached)
                         (number? compiled-at)
                         (> compiled-at compile-start)))))
        build-sources))

(defn assert-no-timestamp-corroboration!
  "Assert that pinned Shadow's timestamp-derived compile record is EMPTY for
  `rid` in `state` — i.e. that this fixture's genuine recompile would have been
  invisible to rf2-8nn5k's corroboration, so any correct verdict it reaches must
  come from the causal witness instead."
  [state rid label]
  (is (not (contains? (resources-compiled-recently state) rid))
      (str label
           ": pinned Shadow's (> compiled-at compile-start) record omits this "
           "genuine recompile — the verdict cannot come from a timestamp")))
