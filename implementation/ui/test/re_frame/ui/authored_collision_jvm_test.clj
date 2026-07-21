(ns re-frame.ui.authored-collision-jvm-test
  "rf2-x1nbv (98.1 slice c — audit obligation #2), JVM side: `static-form` obeys
  the AUTHORED-OWN-VALUE-WINS collision law. When a view author supplies a
  host-root attr spelled like the annotation, the JVM structural tree preserves
  the authored value — the compiler evidence only FILLS a key the author omitted.

  BEFORE the fix, `static-form` `assoc`'d the annotation onto `:static`
  unconditionally, so an authored LITERAL host-root value was overwritten by the
  framework value. A DYNAMIC / `ui/spread` authored value already won on the JVM
  (it layers over `:static` at runtime), so this pins the remaining static path
  and the cross-host agreement with the CLJS set-if-absent law."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.tree :as tree]))

(defview static-collision
  []
  [:div.host {:data-rf-view "authored-view"
              :data-rf2-source-coord "authored-coord"}
   "body"])

(defview no-collision
  []
  [:div.plain "x"])

(defn- host-root-attrs
  "The `:attrs` of the outermost element node in the rendered structural tree —
  the view's compiler-owned host root."
  [tree]
  (->> (tree-seq map? #(filter map? (:children %)) tree)
       (filter :tag)
       first
       :attrs))

(deftest a-literal-authored-value-wins-on-the-jvm-tree
  (let [attrs (host-root-attrs (tree/render static-collision {}))]
    (testing "the author's literal own values survive on the host root"
      (is (= "authored-view" (:data-rf-view attrs))
          "RED before the static-form guard — the annotation overwrote it")
      (is (= "authored-coord" (:data-rf2-source-coord attrs))))
    (testing "the canonical evidence did not leak in"
      (is (not= "re-frame.ui.authored-collision-jvm-test/static-collision"
                (:data-rf-view attrs))))))

(deftest no-collision-still-carries-the-canonical-evidence
  (let [attrs (host-root-attrs (tree/render no-collision {}))]
    (is (= ":re-frame.ui.authored-collision-jvm-test/no-collision"
           (:data-rf-view attrs))
        "an uncollided host root still gains the honest view id")
    (is (string? (:data-rf2-source-coord attrs))
        "and the source coordinate")))
