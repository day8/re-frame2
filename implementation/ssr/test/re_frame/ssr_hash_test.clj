(ns re-frame.ssr-hash-test
  "Spec 011 §Hydration-mismatch detection — canonical-edn / render-tree-hash
  contract. The hash crosses the wire between server and client of the same
  implementation; any structural-equivalence rule the spec pins MUST be honoured
  on both sides or apps see spurious `:rf.ssr/hydration-mismatch` traces.

  Per Spec 011 §Hydration-mismatch detection (line 295):
    'FNV-1a 32-bit over a canonical EDN serialisation of the render-tree
     (depth-first traversal; attribute maps in sorted-key order; nil pruned).'

  This file pins the **nil pruning** rule (rf2-6djjl). Without it, the
  ubiquitous `{:class (when condition? :selected)}` shape produces
  `{:class nil}` on one side and `{}` on the other, and the trees hash
  differently despite being structurally equivalent.

  The hash-stability and order-sensitivity rules are pinned in
  smoke_test.clj `render-tree-hash-is-stable`; the JVM↔CLJS parity smoke
  lives in hash_check_cljs_test.cljs. This namespace focuses on
  pruning-equivalence."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.hash :as hash]))

;; ---- canonical-edn ---------------------------------------------------------

(deftest canonical-edn-prunes-nil-from-maps
  (testing "map entries with nil values are pruned — {:class nil} ≡ {}"
    (is (= (hash/canonical-edn {})
           (hash/canonical-edn {:class nil}))
        "lone nil-valued entry is pruned")
    (is (= (hash/canonical-edn {:id "x"})
           (hash/canonical-edn {:id "x" :class nil}))
        "nil-valued entry pruned alongside live entries")
    (is (= (hash/canonical-edn {:id "x"})
           (hash/canonical-edn {:id "x" :class nil :title nil :hidden nil}))
        "multiple nil-valued entries all pruned"))

  (testing "nil map keys ARE preserved (the spec prunes nil VALUES, not keys)"
    ;; Hiccup attribute maps don't have nil keys in practice, but the
    ;; spec is explicit about pruning nil values — leaves keys alone.
    (is (not= (hash/canonical-edn {})
              (hash/canonical-edn {nil "x"}))
        "nil-keyed entry is NOT pruned")))

(deftest canonical-edn-prunes-nil-from-sequences
  (testing "vectors: nil child elements are pruned"
    (is (= (hash/canonical-edn [:p "text"])
           (hash/canonical-edn [:p "text" nil]))
        "trailing nil element pruned")
    (is (= (hash/canonical-edn [:p "text"])
           (hash/canonical-edn [:p nil "text" nil]))
        "interior + trailing nil elements pruned"))

  (testing "lists: nil child elements are pruned"
    (is (= (hash/canonical-edn '(:p "text"))
           (hash/canonical-edn '(:p "text" nil)))
        "trailing nil element pruned in a list")))

(deftest canonical-edn-returns-nil-for-nil
  (testing "bare nil input — sentinel for parent pruning"
    (is (nil? (hash/canonical-edn nil))
        "canonical-edn returns nil for nil input — parent's keep/remove prunes it")))

(deftest canonical-edn-non-nil-fast-path-unchanged
  (testing "trees with no nil values produce byte-identical canonical EDN
            to the pre-fix behaviour — important because the JVM↔CLJS
            parity test (hash_check_cljs_test.cljs) pins '9d7457ef' for
            [:div {:class \"x\"} [:p \"hi\"]]."
    (is (= "[:div {:class \"x\"} [:p \"hi\"]]"
           (hash/canonical-edn [:div {:class "x"} [:p "hi"]]))
        "no-nil tree serialises to the pre-fix canonical form")))

;; ---- render-tree-hash ------------------------------------------------------

(deftest render-tree-hash-equates-nil-attr-with-absent-attr
  (testing "{:class nil} and {} hash identically per Spec 011 §Hydration-mismatch"
    (is (= (hash/render-tree-hash [:div {:class nil}])
           (hash/render-tree-hash [:div {}]))
        "the common (when condition? :class) shape no longer triggers spurious mismatch")
    (is (= (hash/render-tree-hash [:div {:id "x" :class nil}])
           (hash/render-tree-hash [:div {:id "x"}]))
        "nil-valued attr pruned alongside live attrs")))

(deftest render-tree-hash-equates-nil-child-with-absent-child
  (testing "nil children are pruned — [:p \"text\" nil] ≡ [:p \"text\"]"
    (is (= (hash/render-tree-hash [:p "text" nil])
           (hash/render-tree-hash [:p "text"]))
        "trailing nil child pruned")
    (is (= (hash/render-tree-hash [:div [:p "a"] nil [:p "b"]])
           (hash/render-tree-hash [:div [:p "a"] [:p "b"]]))
        "interior nil child pruned")))

(deftest render-tree-hash-prunes-nil-deeply
  (testing "pruning is recursive — nil-pruning applies at every level"
    (is (= (hash/render-tree-hash
             [:section {:class "wrap"}
              [:header {:role "banner" :hidden nil}
               [:h1 "Title" nil]]
              [:article {:class nil}
               [:p "Body" nil]]])
           (hash/render-tree-hash
             [:section {:class "wrap"}
              [:header {:role "banner"}
               [:h1 "Title"]]
              [:article {}
               [:p "Body"]]]))
        "a deeply nested tree with nils at multiple levels hashes identically
         to the same tree with all nils pruned")))
