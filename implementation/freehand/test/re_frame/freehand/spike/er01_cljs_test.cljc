(ns re-frame.freehand.spike.er01-cljs-test
  "SPIKE SCAFFOLDING — ER-01, the CLJS half. Deleted before this bead's PR.

  The JVM suite proves the three arms agree on one host. This proves they
  agree on the OTHER engine, over the same declarations and the same
  fixture — which is what makes 'the arms are the same template' a
  cross-host claim rather than a JVM claim in a `.cljc` file."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand.spike.er01.compiled :as arm-c]
            [re-frame.freehand.spike.er01.interpreted :as arm-i]
            [re-frame.freehand.spike.er01.vdollar :as arm-d]
            [re-frame.freehand.spike.er01.vdollar-keyed :as arm-dk]
            [re-frame.freehand.tree :as tree]))

(defn- anonymous
  "Drop the boundary view-id — the one thing four declarations in four
  namespaces cannot share. Everything else is compared verbatim."
  [t]
  (walk/postwalk (fn [x] (if (map? x) (dissoc x :view-id) x)) t))

(defn- fixture [rows cols]
  {:cols cols
   :rows (mapv (fn [i] {:id (str "r" i) :index i}) (range rows))})

(deftest three-arms-render-the-same-tree-on-cljs
  (doseq [[rows cols] [[1 1] [3 4] [40 8]]]
    (let [f (fixture rows cols)
          i (anonymous (tree/render [arm-i/table f]))]
      (testing (str rows "x" cols)
        (is (= i (anonymous (tree/render [arm-c/table f]))) "interpreted vs compiled")
        (is (= i (anonymous (tree/render [arm-d/table f]))) "interpreted vs $")
        (is (= i (anonymous (tree/render [arm-dk/table f]))) "interpreted vs $ + keyed-run")))))
