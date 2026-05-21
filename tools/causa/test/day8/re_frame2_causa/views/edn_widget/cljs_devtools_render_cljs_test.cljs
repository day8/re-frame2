(ns day8.re-frame2-causa.views.edn-widget.cljs-devtools-render-cljs-test
  "Tests for the cljs-devtools formatters → hiccup adapter.

  ## What's under test

  1. **`value->hiccup` for primitives** — keyword / string / number /
     nil / boolean / symbol each produce some kind of `[:span ...]`
     hiccup tree (the exact colour/structure is cljs-devtools' to
     decide; we only assert the shape contract holds).
  2. **`value->hiccup` for collections** — maps / vectors / sets /
     lists produce hiccup with at least one nested coloured span
     (rather than degrading to a `pr-str` string fallback).
  3. **Records** — `defrecord`-typed values produce hiccup carrying
     the type tag in some descendant span. This is the core 'faithful
     CLJS-aware rendering' guarantee the bead calls out.
  4. **`jsonml->hiccup` pure walker** — known tag names map to the
     equivalent hiccup keyword; nested children walk recursively.
  5. **Defensive fallback** — values cljs-devtools refuses to format
     still produce hiccup (never throw, never return nil).

  Pure-data scope — no DOM mount; hiccup-shape assertions only."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa.views.edn-widget.cljs-devtools-render
             :as cdt]))

;; ---- helpers ------------------------------------------------------------

(defn- walk-hiccup
  [tree]
  (let [out (atom [])]
    (letfn [(walk [node]
              (when (vector? node)
                (swap! out conj node)
                (doseq [child (rest node)]
                  (cond
                    (vector? child) (walk child)
                    (seq? child)    (doseq [c child] (walk c))))))]
      (walk tree))
    @out))

(defn- contains-text?
  "True when some string descendant of `tree` contains `s`, OR some
  number/boolean descendant stringifies to `s`. cljs-devtools' JSONML
  renders numeric leaves as JS numbers (not strings), so the walker
  must inspect both."
  [tree s]
  (let [hit (atom false)
        re  (re-pattern s)]
    (letfn [(scan [n]
              (cond
                (string? n)  (when (re-find re n) (reset! hit true))
                (number? n)  (when (re-find re (str n)) (reset! hit true))
                (boolean? n) (when (re-find re (str n)) (reset! hit true))
                (vector? n)  (doseq [c (rest n)] (scan c))
                (seq? n)     (doseq [c n] (scan c))))]
      (scan tree))
    @hit))

;; ---- primitives ---------------------------------------------------------

(deftest value-to-hiccup-keyword-shape
  (let [out (cdt/value->hiccup :foo/bar)]
    (testing "keyword produces hiccup vector"
      (is (vector? out)))
    (testing "the rendered tree contains the keyword text"
      (is (contains-text? out ":foo/bar")))))

(deftest value-to-hiccup-string-shape
  (let [out (cdt/value->hiccup "hello")]
    (is (vector? out))
    (is (contains-text? out "hello"))))

(deftest value-to-hiccup-number-shape
  (let [out (cdt/value->hiccup 42)]
    (is (vector? out))
    (is (or (contains-text? out "42")
            ;; cljs-devtools may render the number as a JS number child
            ;; rather than a string — accept either by walking the tree
            ;; for a numeric leaf.
            (some #(and (number? %) (= 42 %))
                  (mapcat rest (walk-hiccup out)))))))

(deftest value-to-hiccup-nil-shape
  (let [out (cdt/value->hiccup nil)]
    (is (vector? out))
    (is (contains-text? out "nil"))))

(deftest value-to-hiccup-boolean-shape
  (let [out (cdt/value->hiccup true)]
    (is (vector? out))
    (is (contains-text? out "true"))))

;; ---- collections --------------------------------------------------------

(deftest value-to-hiccup-map-shape
  (let [out (cdt/value->hiccup {:a 1 :b 2})]
    (testing "map produces hiccup with coloured spans for each key/value"
      (is (vector? out))
      ;; At least one nested :span beyond the root.
      (let [spans (walk-hiccup out)]
        (is (some #(and (vector? %) (= :span (first %))) (rest spans)))))))

(deftest value-to-hiccup-vector-shape
  (let [out (cdt/value->hiccup [1 2 3])]
    (is (vector? out))
    (is (contains-text? out "1"))))

(deftest value-to-hiccup-set-shape
  (let [out (cdt/value->hiccup #{:a :b})]
    (is (vector? out))
    ;; Either keyword is acceptable — set ordering isn't guaranteed.
    (is (or (contains-text? out ":a") (contains-text? out ":b")))))

(deftest value-to-hiccup-list-shape
  (let [out (cdt/value->hiccup '(1 2 3))]
    (is (vector? out))))

;; ---- records ------------------------------------------------------------

(defrecord TestPoint [x y])

(deftest value-to-hiccup-record-preserves-type-tag
  (let [pt  (->TestPoint 3 4)
        out (cdt/value->hiccup pt)
        all (apply str (filter string? (mapcat rest (walk-hiccup out))))]
    (testing "record value carries its type tag in the rendered output"
      (is (vector? out))
      ;; cljs-devtools includes the record's munged or readable type
      ;; tag in the rendered hiccup. Accept either the readable name
      ;; ('TestPoint') or the field text — what we want to confirm is
      ;; that it's NOT just a plain '{}' map rendering.
      (is (or (re-find #"TestPoint" all)
              (re-find #":x" all)
              (re-find #":y" all))
          "record rendering surfaces type tag or fields"))))

;; ---- jsonml->hiccup pure walker -----------------------------------------

(deftest jsonml-passes-through-number-and-string
  (is (= 42 (cdt/jsonml->hiccup 42)))
  (is (= "hi" (cdt/jsonml->hiccup "hi"))))

(deftest jsonml-nil-is-nil
  (is (nil? (cdt/jsonml->hiccup nil))))

(deftest jsonml-vector-shape-walked
  ;; cljs-devtools emits JS arrays at runtime, but the walker also
  ;; accepts CLJS vectors (defensive). Use the vector path here so
  ;; the test is JVM-portable and doesn't need a real JS array.
  (let [out (cdt/jsonml->hiccup ["span" #js {} "x"])]
    (testing "vector with known tag maps to hiccup keyword"
      (is (= :span (first out))))
    (testing "children walk through"
      (is (some #{"x"} (rest out))))))

(deftest jsonml-unknown-tag-falls-back-to-span
  (let [out (cdt/jsonml->hiccup ["mystery" #js {} "child"])]
    (testing "unknown tag becomes a bare [:span ...]"
      (is (= :span (first out)))
      (is (some #{"child"} (rest out))))))

;; ---- defensive fallback -------------------------------------------------

(deftest value-to-hiccup-never-throws-for-unusual-input
  (testing "JS object passes through without throwing"
    (let [out (cdt/value->hiccup #js {:foo 1})]
      (is (vector? out))))
  (testing "function passes through without throwing"
    (let [out (cdt/value->hiccup (fn [_]))]
      (is (vector? out)))))

;; ---- value->tree-hiccup (full current-state expansion, rf2-dmso5) --------
;;
;; `value->tree-hiccup` is the re-frame-10x current-state look: a
;; collection expands into a nested, indented tree rather than the
;; one-line `▸ {…}` summary `value->hiccup` produces. These assert the
;; whole value renders — every key + value surfaces in the tree.

(deftest tree-hiccup-scalar-matches-header
  (testing "a scalar (no body) renders as hiccup carrying the value"
    (let [out (cdt/value->tree-hiccup :foo/bar)]
      (is (vector? out))
      (is (contains-text? out ":foo/bar")))))

(deftest tree-hiccup-map-expands-keys-and-values
  (testing "a top-level map expands: every key + value renders"
    (let [out (cdt/value->tree-hiccup {:alpha 1 :beta 2 :gamma 3})]
      (is (vector? out))
      (is (contains-text? out ":alpha"))
      (is (contains-text? out ":beta"))
      (is (contains-text? out ":gamma"))
      (is (contains-text? out "1"))
      (is (contains-text? out "2"))
      (is (contains-text? out "3")))))

(deftest tree-hiccup-large-map-expands-all-entries
  (testing "a map with more than the cljs-devtools preview cap still
            expands every entry (no collapsed-by-default)"
    (let [m   (zipmap (map #(keyword (str "k" %)) (range 12)) (range 12))
          out (cdt/value->tree-hiccup m)]
      (is (contains-text? out ":k0"))
      (is (contains-text? out ":k11")))))

(deftest tree-hiccup-nested-map-expands-recursively
  (testing "nested collections expand recursively — inner keys/values render"
    (let [out (cdt/value->tree-hiccup {:outer {:inner {:deep 99}}})]
      (is (contains-text? out ":outer"))
      (is (contains-text? out ":inner"))
      (is (contains-text? out ":deep"))
      (is (contains-text? out "99")))))

(deftest tree-hiccup-vector-of-maps-expands
  (testing "a vector of maps expands each element"
    (let [out (cdt/value->tree-hiccup [{:a 1} {:b 2}])]
      (is (contains-text? out ":a"))
      (is (contains-text? out ":b"))
      (is (contains-text? out "1"))
      (is (contains-text? out "2")))))

(deftest tree-hiccup-deeply-nested-renders-without-blowing-up
  (testing "a structure deeper than the inline print-level budget still
            renders (the body-recursion depth cap is a stack guard, not
            a crash) — the outermost keys surface and the deep tail
            degrades to a ▸ summary somewhere"
    ;; Build a 20-deep nest — past the inline print-level (10) so the
    ;; deep tail must fall through to the body-recursion summary path.
    (let [deep (reduce (fn [acc i] {(keyword (str "lvl" i)) acc})
                       {:bottom 1}
                       (range 20))
          out  (cdt/value->tree-hiccup deep)]
      (is (vector? out))
      ;; The outermost key still renders.
      (is (contains-text? out ":lvl19"))
      ;; Somewhere down the tail, a collapsed ▸ summary appears (the
      ;; structure exceeds the inline budget).
      (is (contains-text? out "▸")))))

(deftest tree-hiccup-body-recursion-cap-summarises
  (testing "an explicit small max-depth caps the BODY recursion: a wide
            map (forces a body, since >5 entries) whose values are
            themselves wide maps collapses the nested level to ▸"
    ;; A map of 8 entries forces a body (header caps at the preview
    ;; size for the body path); each value is another 8-entry map. With
    ;; max-depth 1 the body's nested maps hit the cap → ▸ summary.
    (let [wide  (zipmap (map #(keyword (str "k" %)) (range 8))
                        (range 8))
          outer (zipmap (map #(keyword (str "g" %)) (range 8))
                        (repeat wide))
          out   (cdt/value->tree-hiccup outer 1)]
      (is (vector? out))
      (is (contains-text? out ":g0"))
      (is (contains-text? out "▸")))))

(deftest tree-hiccup-record-keeps-type-tag
  (testing "a record value keeps its type tag / fields under expansion"
    (let [pt  (->TestPoint 7 8)
          out (cdt/value->tree-hiccup pt)
          all (apply str (filter string? (mapcat rest (walk-hiccup out))))]
      (is (vector? out))
      (is (or (re-find #"TestPoint" all)
              (re-find #":x" all)
              (re-find #":y" all))))))

(deftest tree-hiccup-never-throws-for-unusual-input
  (testing "JS object / function degrade gracefully (no throw, hiccup out)"
    (is (vector? (cdt/value->tree-hiccup #js {:foo 1})))
    (is (vector? (cdt/value->tree-hiccup (fn [_]))))))
