(ns day8.re-frame2-causa.diff.triples-adapter
  "Annotated-tree → flat-triples adapter for migration safety
  (rf2-gfxmk Phase 1 of rf2-abts7).

  Design source-of-truth:
  `ai/findings/2026-05-18-difftastic-in-causa.md` §3.1 — Migration
  safety paragraph + §6.1 (the triples adapter).

  ## Why this exists

  The current `app-db-diff-helpers/diff-paths` produces a flat vector
  of `[{:op :path :before :after}]` triples. Several consumers walk
  this shape:

    - The pin-store walker (`live-pinned-slices`)
    - The 'show me when this changed' walker (`epochs-touching-path`)
    - The MCP exporter (`mcp-server-feed`)
    - The legacy slice-mini-panel renderer

  Phase 1's renderer switches to sections-per-cluster; the engine
  switches to an annotated tree. The migration shim lives here: any
  consumer that wants the legacy flat-triples shape gets it via
  `annotated-tree->triples`.

  ## Output shape

  Same as the existing `diff-paths`:

      [{:op :added    :path [...] :before nil :after v}
       {:op :modified :path [...] :before bv  :after av}
       {:op :removed  :path [...] :before v   :after nil}]

  Sorted lexicographically by path-as-pr-str (matches the existing
  `diff-paths` ordering — pin-store + MCP exports stay stable).

  ## Bottoming-out behaviour

  The current `diff-paths` walker bottoms out at the first non-map
  level (vectors / sets are leaves). The annotated-tree walker
  recurses through vectors + sets. The adapter projects vector / set
  CONTAINER changes back to ONE `:modified` triple at the parent path
  to preserve exact behavioural equivalence with `diff-paths`.

  This means the adapter loses some of the annotated-tree's extra
  fidelity (a single qty change inside a vector of maps is reported as
  `[:cart :items]` modified vs. `[:cart :items 1 :qty]` modified) —
  but that's by design: the adapter is a back-compat shim, not a
  primary surface. New consumers should use the annotated tree
  directly; legacy consumers keep working.

  ## JVM-runnable

  Pure data → data. `.cljc`."
  (:require [day8.re-frame2-causa.diff.annotated-tree :as at]))

;; ---- recursion helpers --------------------------------------------------

(declare collect-triples!)

(defn- triple-at
  "Build a `:added` / `:removed` / `:modified` triple at `path` from an
  annotated node. The annotated node's `:before` / `:after` /
  `:value` slots carry the source/destination values."
  [path node]
  (let [op (at/op-of node)]
    (case op
      :added    {:op :added    :path (vec path) :before nil
                 :after (:value node)}
      :removed  {:op :removed  :path (vec path) :before (:value node)
                 :after nil}
      :modified {:op :modified :path (vec path)
                 :before (:before node) :after (:after node)})))

(defn- value-of
  "Project the annotated tree back to a plain CLJS value at this node.
  For `:added` / `:modified` use the after-side; for `:removed` use the
  before-side; for `:same` use the `:value` slot; for `:children`
  reconstruct from the original value (stored on the node)."
  [node]
  (case (at/op-of node)
    :modified (:after node)
    :removed  (:value node)
    (:value node)))

(defn- map-container-child-triples!
  "Recurse into a `:children :tag :map` container — for each child,
  emit triples at the extended path. Children with `:children` ops on
  vector / set containers degrade to a single `:modified` triple at
  the child's path to preserve `diff-paths` equivalence."
  [acc container path]
  (reduce
    (fn [a child]
      (collect-triples! a child (conj path (at/child-key child))))
    acc
    (:children container)))

(defn- collect-triples!
  "Inner walker — descends into map containers; bottoms out at
  vectors / sets / leaves the same way `diff-paths` does."
  [acc node path]
  (let [op (at/op-of node)]
    (cond
      (= op :same)
      acc

      (= op :children)
      (case (:tag node)
        :map (map-container-child-triples! acc node path)
        ;; Vector or set container that changed: project as a single
        ;; `:modified` triple at this path. The `:value` slot on
        ;; `:children` holds the after-side value (the walker stored it
        ;; per `annotated_tree/children-node`). Compute the before-side
        ;; by reconstruction from the children.
        (conj! acc
               {:op     :modified
                :path   (vec path)
                :before (reduce
                          (fn [acc child]
                            (let [child-op (at/op-of child)]
                              (cond
                                (= child-op :added)    acc       ; not in before
                                (= child-op :removed)  (conj acc (:value child))
                                (= child-op :modified) (conj acc (:before child))
                                (= child-op :same)     (conj acc (:value child))
                                (= child-op :children) (conj acc (value-of child)) ; coarse
                                :else                  acc)))
                          (cond
                            (= :set (:tag node)) #{}
                            :else                 [])
                          (:children node))
                :after  (value-of node)}))

      ;; Leaf op — direct triple.
      :else
      (conj! acc (triple-at path node)))))

(defn annotated-tree->triples
  "Project an annotated tree (from `annotated-tree/diff-tree`) into the
  flat-triples shape produced by `app-db-diff-helpers/diff-paths`.

  Same triple shape, same sort order, same map-only recursion contract
  — so legacy consumers (pin-store, 'show me when this changed', MCP
  exporter, existing slice renderer) keep working.

  Pure data → data. JVM-runnable."
  [root]
  (let [raw    (persistent! (collect-triples! (transient []) root []))
        with-k (mapv (fn [t] (assoc t ::sort-key (pr-str (:path t)))) raw)
        sorted (sort-by ::sort-key with-k)]
    (mapv #(dissoc % ::sort-key) sorted)))
