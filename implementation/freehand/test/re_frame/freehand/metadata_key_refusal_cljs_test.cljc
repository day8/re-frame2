(ns re-frame.freehand.metadata-key-refusal-cljs-test
  "`^{:key …}` is the REAGENT spelling, and it is refused — in both modes,
  on both hosts, and by both interpreted walks.

  It used to be silently DROPPED interpreted. The structural tree carries
  no metadata contract, nothing on either walk looked for one, and the
  result was a well-formed tree with the wrong content: no `:key`, no
  diagnostic, and a list whose only symptom was React reusing the wrong
  rows several interactions later. The compiled tier meanwhile refused the
  identical source as a hard build failure — and reported it as a MISSING
  key, while the author was looking at one.

  So the assertions below never settle for \"something went wrong\". A
  refusal is asserted beside the ATTRIBUTED RESULT of the spelling that
  works: the props-slot key really is on the node, at the same site, in
  the same walk. An assertion that only proved a throw would have passed
  against the defect just as happily as against the fix, because the
  defective path threw nothing and rendered fine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.tree :as tree]
            #?@(:cljs [[re-frame.freehand.react :as fr]])))

(defn- refusal
  "The `ex-message` of the `:rf.error/ui-tree-malformed` `thunk` raises, or
  nil when it raises nothing — so a SILENT pass is distinguishable from a
  refusal, which is the whole point here."
  [thunk]
  (try
    (thunk)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) ex
      (ex-message ex))))

;; ---------------------------------------------------------------------------
;; The structural walk — both hosts
;; ---------------------------------------------------------------------------

(deftest the-structural-walk-refuses-a-metadata-key
  (testing "The spelling a Reagent-shaped hand reaches for first is
            refused rather than dropped."
    (let [msg (refusal #(tree/render [:ul ^{:key "a"} [:li "a"]]))]
      (is (some? msg) "the walk refuses it — it does not render an unkeyed list")
      (is (str/includes? msg "METADATA") "the sentence names metadata")
      (is (str/includes? msg "[:li {:key k} …]")
          "and gives the props-slot spelling")))
  (testing "The ATTRIBUTED RESULT of the recovery — not merely that
            something threw. The props spelling puts the key on the very
            node the metadata spelling left bare."
    (let [tree (tree/render [:ul [:li {:key "a"} "a"] [:li {:key "b"} "b"]])]
      (is (= [{:tag :li :key "a" :children ["a"]}
              {:tag :li :key "b" :children ["b"]}]
             (:children tree))
          "each row carries the key it was written with")))
  (testing "and an ordinary unkeyed child is untouched — the refusal is
            about the metadata, not about keys being compulsory"
    (is (= [{:tag :li :children ["a"]}]
           (:children (tree/render [:ul [:li "a"]]))))))

(deftest the-refusal-reaches-every-child-position
  (testing "A `for` row is where this is written, but the rule is about
            the SPELLING and not about lists — the walk folds every child
            through one collector."
    (is (some? (refusal #(tree/render [:ul (for [x ["a" "b"]]
                                             (with-meta [:li x] {:key x}))])))
        "a keyed-by-metadata for row")
    (is (some? (refusal #(tree/render ^{:key "r"} [:div "solo"])))
        "and the root form itself"))
  (testing "the recovery for a for row, attributed"
    (is (= [{:tag :li :key "a" :children ["a"]}
            {:tag :li :key "b" :children ["b"]}]
           (:children (tree/render [:ul (for [x ["a" "b"]] [:li {:key x} x])])))
        "the rows are keyed, by the value the row was built from")))

;; ---------------------------------------------------------------------------
;; The interpreted React walk — the other emitter, ClojureScript only
;; ---------------------------------------------------------------------------
;;
;; The two interpreted walks are separate implementations by design, so a
;; refusal proved on one of them is not a refusal on the other. This is the
;; browser half, and it raises the SAME sentence because it calls the same
;; refusal rather than restating it.

#?(:cljs
   (deftest the-react-walk-refuses-the-same-spelling
     (testing "the browser emitter refuses it too, with the same sentence"
       (let [msg (refusal #(fr/element [:ul ^{:key "a"} [:li "a"]]))]
         (is (some? msg) "the React walk refuses it")
         (is (str/includes? msg "METADATA") "with the shared sentence")))
     (testing "and the props spelling reaches React's own key at the same site"
       (let [row (.. (fr/element [:ul [:li {:key "a"} "a"]]) -props -children)]
         (is (= "a" (.-key row))
             "the row's React key is the key the props map carried")))))
