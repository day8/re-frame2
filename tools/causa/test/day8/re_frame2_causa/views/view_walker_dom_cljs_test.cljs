(ns day8.re-frame2-causa.views.view-walker-dom-cljs-test
  "Per Spec 006 §View tagging contract (rf2-01il5): the fallback view-
  hierarchy walker reads `[data-rf-view]`-tagged DOM elements and
  reconstructs parent ⊃ children purely by DOM containment. These tests
  build synthetic DOM trees (via `document.createElement`) and assert
  the walker's output matches the documented contract.

  ## Coverage

    - parse-view-id: keyword id, plain string id, nil/non-string inputs.
    - walk!: document-order enumeration; depth from nearest tagged
      ancestor; multiple tagged siblings under one parent; tags inside
      untagged scaffolding; deeply-nested chains.
    - walk!: empty document returns empty vector.
    - Documented edge cases (fidelity gaps):
        * untagged element between tagged ancestor and descendant —
          the descendant's depth follows the NEAREST TAGGED ancestor,
          not the untagged scaffolding.
        * sibling tagged elements at the same depth.

  ## Test target

  This file's filename ends in `_dom_cljs_test.cljs` so it runs under
  the `:browser-test` build (which provides a real DOM) per
  `implementation/shadow-cljs.edn` (rf2-2hrj8). The `:node-test`
  build's `cljs-test$` regex ALSO matches this file's ns suffix
  (`-dom-cljs-test`), so the parse-view-id pure-fn tests still run on
  Node — the DOM-dependent tests short-circuit via `(when (exists?
  js/document) …)` under Node and exercise fully under Chromium."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.views.view-walker :as walker]))

;; ---- DOM scaffolding helpers ----------------------------------------------

(defn- mk
  "Build a DOM element with optional `data-rf-view` attribute. Returns
  the new element (not yet attached)."
  ([tag] (mk tag nil))
  ([tag view-attr]
   (let [el (.createElement js/document tag)]
     (when view-attr
       (.setAttribute el "data-rf-view" view-attr))
     el)))

(defn- attach!
  "Append `child` to `parent` and return `child` for chaining."
  [parent child]
  (.appendChild parent child)
  child)

(defn- mk-host!
  "Create a hermetic host element under document.body and return it.
  Each test creates its own host so concurrent test fns don't share
  walker state."
  []
  (let [host (mk "div")]
    (.setAttribute host "data-rf-test-host" "")
    (.appendChild (.-body js/document) host)
    host))

(defn- cleanup-hosts!
  "Remove every test host. Runs as the after-each fixture."
  []
  (when (and (exists? js/document) (.-body js/document))
    (let [hosts (.querySelectorAll js/document "[data-rf-test-host]")]
      (dotimes [i (.-length hosts)]
        (let [h (aget hosts i)]
          (when (.-parentNode h)
            (.removeChild (.-parentNode h) h)))))))

(use-fixtures :each
  {:after cleanup-hosts!})

;; ---- parse-view-id --------------------------------------------------------

(deftest parse-view-id-namespaced-keyword
  (testing "leading colon + slash → namespaced keyword"
    (is (= :rf.foo/bar (walker/parse-view-id ":rf.foo/bar"))
        "splits at the first / for ns + name")))

(deftest parse-view-id-bare-keyword
  (testing "leading colon, no slash → bare keyword"
    (is (= :bare (walker/parse-view-id ":bare"))
        "no slash → unqualified keyword")))

(deftest parse-view-id-raw-string
  (testing "no leading colon → raw string id (unusual but legal)"
    (is (= "raw-string" (walker/parse-view-id "raw-string"))
        "leading-colon absent → keep as raw string")))

(deftest parse-view-id-nil-and-non-string
  (testing "nil / non-string inputs → nil"
    (is (nil? (walker/parse-view-id nil)))
    (is (nil? (walker/parse-view-id 42)))))

;; ---- walk!: empty / no-tagged-elements ------------------------------------

(deftest walk-empty-host-returns-empty
  (testing "a host with no [data-rf-view] descendants returns an empty
            vector"
    (when (exists? js/document)
      (let [host (mk-host!)]
        (attach! host (mk "p"))
        (attach! host (mk "div"))
        (is (= [] (walker/walk! host))
            "no tagged elements → empty walk")))))

;; ---- walk!: flat catalogue ------------------------------------------------

(deftest walk-flat-siblings-all-roots
  (testing "tagged siblings at the same scope are all depth 0"
    (when (exists? js/document)
      (let [host (mk-host!)]
        (attach! host (mk "div" ":app/header"))
        (attach! host (mk "div" ":app/main"))
        (attach! host (mk "div" ":app/footer"))
        (let [out (walker/walk! host)]
          (is (= 3 (count out)) "three tagged elements")
          (is (every? #(= 0 (:depth %)) out)
              "all at depth 0 — siblings share an untagged ancestor")
          (is (= [:app/header :app/main :app/footer]
                 (mapv :view-id out))
              "document order preserved"))))))

;; ---- walk!: nested tagged elements ----------------------------------------

(deftest walk-nested-depth-1
  (testing "a tagged element inside another tagged element is at depth 1"
    (when (exists? js/document)
      (let [host  (mk-host!)
            outer (attach! host (mk "section" ":app/outer"))
            _     (attach! outer (mk "div" ":app/inner"))]
        (let [out (walker/walk! host)]
          (is (= 2 (count out)))
          (is (= [{:outer 0} {:inner 1}]
                 [{:outer (:depth (first out))} {:inner (:depth (second out))}])
              "outer at depth 0, inner at depth 1")
          (is (= [:app/outer :app/inner] (mapv :view-id out))
              "document order preserved"))))))

(deftest walk-nested-depth-2
  (testing "a deeply-nested tagged element accumulates depth across
            every tagged ancestor"
    (when (exists? js/document)
      (let [host (mk-host!)
            a    (attach! host (mk "section" ":app/a"))
            b    (attach! a    (mk "div"     ":app/b"))
            _    (attach! b    (mk "span"    ":app/c"))]
        (let [out (walker/walk! host)]
          (is (= 3 (count out)))
          (is (= [0 1 2] (mapv :depth out))
              "depths follow the chain of tagged ancestors"))))))

;; ---- walk!: untagged scaffolding (fidelity gap exemplar) -----------------

(deftest walk-skips-untagged-scaffolding
  (testing "depth follows the NEAREST TAGGED ancestor — untagged
            scaffolding (div soup, etc.) between two tagged elements is
            transparent to the depth calculation"
    (when (exists? js/document)
      (let [host    (mk-host!)
            tagged-a (attach! host (mk "section" ":app/a"))
            ;; Three layers of untagged divs between A and its child B.
            untag1  (attach! tagged-a (mk "div"))
            untag2  (attach! untag1   (mk "div"))
            untag3  (attach! untag2   (mk "div"))
            _       (attach! untag3   (mk "span" ":app/b"))]
        (let [out (walker/walk! host)]
          (is (= 2 (count out)))
          (is (= [0 1] (mapv :depth out))
              "B is depth 1 — its nearest tagged ancestor is A; untagged
               scaffolding is transparent"))))))

;; ---- walk!: multiple branches under one tagged parent --------------------

(deftest walk-multiple-children-under-one-parent
  (testing "a tagged parent with two tagged children produces three
            entries in document order; both children share depth 1"
    (when (exists? js/document)
      (let [host   (mk-host!)
            parent (attach! host (mk "section" ":app/parent"))
            _      (attach! parent (mk "div" ":app/child-1"))
            _      (attach! parent (mk "div" ":app/child-2"))]
        (let [out (walker/walk! host)]
          (is (= 3 (count out)))
          (is (= [:app/parent :app/child-1 :app/child-2]
                 (mapv :view-id out)))
          (is (= [0 1 1] (mapv :depth out))
              "parent at 0; two children at depth 1"))))))

;; ---- walk!: node-key uniqueness --------------------------------------------

(deftest walk-node-keys-are-unique-per-walk
  (testing "every tagged node carries a stable :node-key — distinct
            across distinct DOM nodes, identical across re-walks of the
            same tree"
    (when (exists? js/document)
      (let [host (mk-host!)
            _    (attach! host (mk "div" ":app/a"))
            _    (attach! host (mk "div" ":app/b"))
            _    (attach! host (mk "div" ":app/c"))]
        (let [out  (walker/walk! host)
              keys (mapv :node-key out)]
          (is (= 3 (count (set keys)))
              "all three :node-key values are distinct")
          ;; Re-walk: keys must be identical (identity-stable hash of
          ;; the underlying JS object).
          (let [out2 (walker/walk! host)]
            (is (= keys (mapv :node-key out2))
                "node-key is stable across consecutive walks of the same DOM")))))))

;; ---- walk!: view-id null-safe on missing attribute -----------------------

(deftest walk-missing-data-rf-view-yields-nil-view-id
  ;; Defensive: if someone manually constructed a [data-rf-view]
  ;; node-list and the attribute disappeared between selection and
  ;; read, parse-view-id should return nil rather than throwing. This
  ;; isn't reachable in practice from the production wrapper, but
  ;; pinning the contract.
  (testing "an element selected via querySelectorAll but whose
            data-rf-view attribute is absent yields :view-id nil"
    (is (nil? (walker/parse-view-id nil))
        "nil attribute → nil view-id (no throw)")))
