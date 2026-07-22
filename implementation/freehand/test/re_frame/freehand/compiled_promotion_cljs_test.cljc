(ns re-frame.freehand.compiled-promotion-cljs-test
  "THE PROMOTION PROOF — `{:compiled true}` changes the declaration and
  nothing else.

  This is the assertion the compiled tier exists to earn. A compiled
  substrate that is a *different substrate* — different call spelling,
  different props contract, different output, a port rather than a
  marker — is a second framework wearing the first one's name, and the
  cost of that shows up on the day someone has to move a view across the
  line under deadline.

  So the claim is made the hard way. The compiled twins in
  [[re-frame.freehand.compiled-views]] are asserted against the SAME
  `FH-STRUCT-006` fixture rows the interpreted views are pinned to — the
  same call vectors, the same literal expected trees, unedited — with one
  mechanical substitution: a view id names the namespace a declaration
  LIVES in, and the twins live in different namespaces because two views
  of one name cannot share one. Nothing else is adjusted, and the test
  proves the substitution is the only one by ALSO rendering the
  interpreted originals against the untouched rows in the same run.

  Running on both hosts is the cross-host half: the compiled lowering is
  `.cljc` and its output is asserted against one fixture value in two
  runtimes, exactly as the interpreted corpus is."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand :as v]
            [re-frame.freehand.compiled-views :as compiled]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.tree :as tree]
            [re-frame.freehand.tree-views :as views]))

(def struct-006 (conf/fixture :FH-STRUCT-006))

(defn- realize [form]
  (walk/postwalk #(if (= :fh/fn %) (fn [_] nil) %) form))

(def ^:private interpreted-ns "re-frame.freehand.tree-views")
(def ^:private compiled-ns "re-frame.freehand.compiled-views")

(defn- as-compiled-ids
  "Rewrite the fixture's expected `:view-id`s onto the compiled twins'
  namespace. The ONLY difference promotion is allowed to make, and it is
  not a difference promotion makes at all — it is a difference LIVING IN
  ANOTHER FILE makes."
  [tree]
  (walk/postwalk
    (fn [x]
      (if (and (keyword? x) (= interpreted-ns (namespace x)))
        (keyword compiled-ns (name x))
        x))
    tree))

(deftest promotion-changes-the-declaration-and-nothing-else
  (testing "Per FH-STRUCT-006, rendered TWICE: the compiled twins produce
            the trees the interpreted originals are pinned to. Same call
            vectors, same fixture, same expectations — the marker is the
            whole delta."
    (is (seq (:cases struct-006)) "the fixture's case table loaded")
    (doseq [{:keys [note view args tree]} (:cases struct-006)]
      (let [interpreted (get views/by-name view)
            promoted    (get compiled/by-name view)
            call        (into [] (realize args))]
        (is (some? interpreted) (str "the fixture names an interpreted view: " view))
        (is (some? promoted) (str "the fixture names a promoted twin: " view))
        (is (= tree (tree/render (into [interpreted] call)))
            (str note " (interpreted)"))
        (is (= (as-compiled-ids tree) (tree/render (into [promoted] call)))
            (str note " (compiled — the same tree, from the same call)"))))))

(deftest the-proof-is-not-vacuous
  (testing "Both halves of the comparison must actually be the two modes.
            A promotion proof where both sides are interpreted proves
            nothing at all, so the lowering each descriptor reports is
            asserted before its output is trusted."
    (doseq [[view-name interpreted] views/by-name]
      (let [promoted (get compiled/by-name view-name)]
        (is (= :interpreted (:lowering (v/describe interpreted)))
            (str view-name " is declared interpreted"))
        (is (= :compiled (:lowering (v/describe promoted)))
            (str view-name " is declared compiled"))))))

(deftest a-compiled-view-carries-no-interpreted-body
  (testing "The two lowerings are exclusive. A compiled declaration has
            NO interpreted render body to fall back to — which is what
            makes 'no hidden interpreted fallback' a property of the
            value rather than a promise about the code."
    (doseq [[view-name promoted] compiled/by-name]
      (is (nil? (v/render-body promoted))
          (str view-name " has no interpreted body"))
      (is (some? (v/structural-body promoted))
          (str view-name " carries its compiled structural body")))
    (doseq [[view-name interpreted] views/by-name]
      (is (some? (v/render-body interpreted))
          (str view-name " keeps its interpreted body"))
      (is (nil? (v/structural-body interpreted))
          (str view-name " carries no compiled body")))))

(deftest the-descriptor-is-the-same-shape-either-way
  (testing "Everything a caller, tool or registry reads off a descriptor
            is unchanged by promotion except the lowering it reports.
            `describe` is the public projection, so this is the surface
            that decides whether promotion is visible to anyone but the
            author."
    (doseq [[view-name interpreted] views/by-name]
      (let [promoted (get compiled/by-name view-name)
            without  #(-> (v/describe %) (dissoc :lowering :view-id :source))]
        (is (= (without interpreted) (without promoted))
            (str view-name ": promotion changes only the lowering"))
        (is (= (name view-name) (name (:view-id (v/describe promoted))))
            (str view-name ": the view id keeps its name"))))))

(v/defview crossing
  "A COMPILED parent mounting a still-INTERPRETED child, by its ordinary
  name, through the ordinary call spelling."
  {:compiled true}
  [{:keys [items]}]
  [:ul.rows
   (for [i items]
     [views/row {:key i :label i}])])

(deftest a-compiled-parent-mounts-an-interpreted-child
  (testing "Promotion is per-declaration and NOT transitive. The escape
            D010 names for a body that will not compile is 'extract a
            declared child — it may stay interpreted', and that recovery
            is worthless if the crossing does not work. So it is proven,
            not assumed."
    (let [t    (tree/render [crossing {:items [1 2]}])
          rows (->> (tree-seq map? :children t)
                    (filter #(= :re-frame.freehand.tree-views/row (:view-id %))))]
      (is (= 2 (count rows))
          "both interpreted row boundaries are in the compiled parent's tree")
      (is (= [{:tag :li :attrs {:class "row"} :children ["1"]}
              {:tag :li :attrs {:class "row"} :children ["2"]}]
             (mapv #(first (:children %)) rows))
          "each interpreted child rendered its own markup inside the compiled parent")
      (is (= (dissoc (tree/render [views/row {:label 1}]) :rf.ui/tree-version)
             (dissoc (first rows) :key))
          "the crossing does not change what the interpreted child produces"))))
