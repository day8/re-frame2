(ns day8.re-frame2-machines-viz.region-on-done-scope-cljs-test
  "A region body's own `:on-done` resolves WITHIN its region, in every emitter.

  The engine takes a region's done with the region body's `:on-done` and
  resolves its target at decl-path `[]` against that region's `:states`,
  exactly as it resolves the region's own `:on`: a keyword names one of the
  region's top-level states, a vector is an in-region path, and a sibling
  region's name is refused at registration. The chart, Mermaid and SCXML
  must draw the same edge, and SCXML must read it back as written.

  The fixtures give region `:a` a top-level state named `:b` beside a
  sibling region also named `:b`, so the in-region reading and a
  sibling-region reading of `:on-done :b` name DIFFERENT nodes."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.mermaid :as mermaid]
            [day8.re-frame2-machines-viz.scxml :as scxml]))

(defn- with-region-on-done
  "Region `:a` (top-level states `:a1`, `:fin`, `:b` and compound `:p`) with
  `on-done` as its own `:on-done`, beside a sibling region `:b`."
  [on-done]
  {:type    :parallel
   :regions {:a {:initial :a1
                 :on-done on-done
                 :states  {:a1  {:on {:go :fin}}
                           :fin {:final? true}
                           :b   {}
                           :p   {:initial :q :states {:q {}}}}}
             :b {:initial :b1 :states {:b1 {}}}}})

(defn- region-on-done-edge [definition]
  (let [edges (filter :region-on-done? (:edges (layout/project-definition definition)))]
    (is (= 1 (count edges)) "exactly one region completion edge")
    (first edges)))

(defn- mermaid-body [definition]
  (mermaid/emit definition {:fenced? false :header-comment? false}))

;; ---- chart ------------------------------------------------------------------

(deftest chart-region-on-done-lands-inside-its-region
  (testing "a keyword target lands on the region's own state, not the sibling
            region of the same name"
    (let [e (region-on-done-edge (with-region-on-done :b))]
      (is (= (layout/region-node-id :a) (:source e)))
      (is (= (layout/region-scoped-id :a [:b]) (:target e)))
      (is (not= (layout/region-node-id :b) (:target e)))
      (is (= [:b] (:to-path e)) "the in-region path")
      (is (= [:a] (:done-path e)) "the done node is the region itself")
      (is (= "✓ done" (:event-label e)))))

  (testing "a one-segment vector target is the same in-region path"
    (is (= (layout/region-scoped-id :a [:b])
           (:target (region-on-done-edge (with-region-on-done [:b]))))))

  (testing "a deeper vector target is an in-region path"
    (is (= (layout/region-scoped-id :a [:p :q])
           (:target (region-on-done-edge (with-region-on-done [:p :q]))))))

  (testing "every target names a node the chart draws"
    (doseq [target [:b [:b] [:p :q] :a1]
            :let [graph (layout/project-definition (with-region-on-done target))
                  ids   (set (map :id (:nodes graph)))]]
      (is (every? #(contains? ids (:target %)) (filter :region-on-done? (:edges graph)))
          (str "target " (pr-str target) " lands on a drawn node")))))

(deftest chart-region-on-done-self-anchored-forms
  (testing ":same-state names the region body, so the edge loops on the region's
            container"
    (let [e (region-on-done-edge (with-region-on-done :same-state))]
      (is (= (layout/region-node-id :a) (:source e) (:target e)))
      (is (not (:internal? e)))))

  (testing "an action-only :on-done self-anchors on the region's container"
    (let [e (region-on-done-edge (with-region-on-done {:action :log}))]
      (is (true? (:internal? e)))
      (is (= (layout/region-node-id :a) (:source e) (:target e))))))

;; ---- Mermaid ----------------------------------------------------------------

(deftest mermaid-region-on-done-lands-inside-its-region
  (testing "the completion edge targets the region's own state id"
    (let [out (mermaid-body (with-region-on-done :b))]
      (is (str/includes? out "a --> a__b : ✓ done"))
      (is (not (str/includes? out "a --> b :")) "never the sibling region")))

  (testing "a region completing into its own initial state draws r --> r__a,
            never an edge to an undeclared node a"
    (let [out (mermaid-body {:type    :parallel
                             :regions {:r {:initial :a :on-done :a
                                           :states  {:a {} :done {:final? true}}}}})]
      (is (str/includes? out "r --> r__a : ✓ done"))
      (is (not (str/includes? out "r --> a :")))))

  (testing "a deeper vector target is prefixed with the region"
    (is (str/includes? (mermaid-body (with-region-on-done [:p :q])) "a --> a__p__q : ✓ done"))))

;; ---- SCXML ------------------------------------------------------------------

(defn- declared-ids [xml]
  (set (map second (re-seq #"<(?:state|final|parallel|history) id=\"([^\"]+)\"" xml))))

(deftest scxml-region-on-done-lands-inside-its-region
  (testing "the done.state transition targets the region's own state id"
    (let [xml (scxml/spec->scxml (with-region-on-done :b))]
      (is (str/includes? xml "event=\"done.state.a\" target=\"a___b\""))
      (is (contains? (declared-ids xml) "a___b") "the target is a declared state"))))

(deftest scxml-region-on-done-round-trips
  (testing "an in-region target reads back as written"
    (doseq [target [:b [:p :q] :same-state {:target :b :guard :ready?}]
            :let [m (with-region-on-done target)]]
      (is (= m (scxml/scxml->spec (scxml/spec->scxml m)))
          (str "round trip of " (pr-str target))))))

;; ---- the parallel root's completion is unchanged -----------------------------

(deftest parallel-root-completion-is-unchanged
  (testing "the whole-parallel :on-done stays an action-only completion on the
            parallel root beside a region's own :on-done"
    (let [m     (assoc (with-region-on-done :b) :on-done {:action :announce})
          graph (layout/project-definition m)
          root  (filter :parallel-root? (:edges graph))
          xml   (scxml/spec->scxml m)]
      (is (= 1 (count root)))
      (is (true? (:internal? (first root))))
      (is (= (:source (first root)) (:target (first root))))
      (is (= [] (:done-path (first root))))
      (is (str/includes? xml (str "event=\"done.state." layout/parallel-root-done-state-id "\">")))
      (is (str/includes? (mermaid-body m) "on-done: ✓ done / announce"))
      (is (= m (scxml/scxml->spec xml)) "both completions read back"))))
