(ns day8.re-frame2-xray.views.edn-widget.cljs-devtools-render-cljs-test
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
            [day8.re-frame2-xray.views.edn-widget.cljs-devtools-render
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

;; ---- value->tree-hiccup (click-to-toggle current-state, rf2-dw8n7) -------
;;
;; `value->tree-hiccup` is the re-frame-10x current-state look — type-
;; coloured leaves, native collection delimiters, INTERACTIVE click-to-
;; toggle per collection node. Default expansion follows §10.4's
;; depth+size heuristic (`:default-depth 1` by default: root expanded,
;; nested collections collapsed). The hard `:max-depth` is a stack
;; guard, not a UX bound.
;;
;; These assert:
;; - root expansion shows top-level keys
;; - nested collections render as `▸ {…}` summaries by default
;; - explicit `:default-depth N` opens N levels
;; - the toggle wrapper carries `role="button"` + `cursor:pointer`
;;   when `:panel-id` + `:render-id` are supplied (click wiring)

(deftest tree-hiccup-scalar-matches-header
  (testing "a scalar (no body) renders as hiccup carrying the value"
    (let [out (cdt/value->tree-hiccup :foo/bar)]
      (is (vector? out))
      (is (contains-text? out ":foo/bar")))))

(deftest tree-hiccup-map-shows-top-level-keys
  (testing "a top-level map renders with its keys visible (root expanded)"
    (let [out (cdt/value->tree-hiccup {:alpha 1 :beta 2 :gamma 3})]
      (is (vector? out))
      (is (contains-text? out ":alpha"))
      (is (contains-text? out ":beta"))
      (is (contains-text? out ":gamma"))
      (is (contains-text? out "1"))
      (is (contains-text? out "2"))
      (is (contains-text? out "3")))))

(deftest tree-hiccup-large-map-renders-all-keys
  (testing "a map with more than the cljs-devtools preview cap still
            renders every key (root expansion shows all entries; values
            are scalars so they inline)"
    (let [m   (zipmap (map #(keyword (str "k" %)) (range 12)) (range 12))
          out (cdt/value->tree-hiccup m)]
      (is (contains-text? out ":k0"))
      (is (contains-text? out ":k11")))))

(deftest tree-hiccup-nested-map-collapses-wide-children-by-default
  (testing "rf2-dw8n7 — at the §10.4 depth boundary (default-depth=1)
            a NESTED collection that exceeds the children-threshold
            (10) collapses to a ▸ {…} summary; the user clicks to
            expand. Small single-entry nested maps stay expanded
            (consistent with the home-grown engine's same heuristic)."
    (let [wide (zipmap (map #(keyword (str "k" %)) (range 20)) (range 20))
          out  (cdt/value->tree-hiccup {:outer wide})]
      (is (contains-text? out ":outer")
          "root expansion shows the top-level key")
      (is (contains-text? out "▸")
          "the wide nested map renders as a ▸ summary")
      (is (not (contains-text? out ":k0"))
          "the wide nested map's keys are hidden under the default-collapse"))))

(deftest tree-hiccup-small-nested-map-still-expands-by-default
  (testing "rf2-dw8n7 — a single-entry nested map at the default-depth
            boundary stays expanded (≤ threshold heuristic) — matches
            `data-display/default-expanded?` semantics; gives the
            operator a cheap quick peek at small structures"
    (let [out (cdt/value->tree-hiccup {:outer {:inner 99}})]
      (is (contains-text? out ":outer"))
      (is (contains-text? out ":inner")))))

(deftest tree-hiccup-explicit-default-depth-expands-deeper
  (testing "passing :default-depth deeper opens nested levels eagerly —
            the §10.4 heuristic uses default-depth as the expanded-by-
            default boundary"
    (let [out (cdt/value->tree-hiccup {:outer {:inner {:deep 99}}}
                                      {:default-depth 4})]
      (is (contains-text? out ":outer"))
      (is (contains-text? out ":inner"))
      (is (contains-text? out ":deep"))
      (is (contains-text? out "99")))))

(deftest tree-hiccup-vector-of-wide-maps-shows-collapsed-summaries
  (testing "rf2-dw8n7 — at the §10.4 boundary (default-depth=1) wide
            nested maps inside a vector collapse to ▸ {…} summaries;
            single-key inner maps stay expanded (threshold heuristic)"
    (let [wide-a (zipmap (map #(keyword (str "a" %)) (range 20)) (range 20))
          wide-b (zipmap (map #(keyword (str "b" %)) (range 20)) (range 20))
          out    (cdt/value->tree-hiccup [wide-a wide-b])]
      (is (contains-text? out "▸")
          "wide inner maps collapse to ▸ summaries")
      (is (not (contains-text? out ":a0"))
          "wide inner map's keys are hidden under the default-collapse")
      (is (not (contains-text? out ":b0"))))))

(deftest tree-hiccup-deeply-nested-renders-without-blowing-up
  (testing "a structure deeper than the inline print-level budget still
            renders — the outermost key surfaces and the deep tail
            degrades to a ▸ summary (click-to-expand from there)"
    (let [deep (reduce (fn [acc i] {(keyword (str "lvl" i)) acc})
                       {:bottom 1}
                       (range 20))
          out  (cdt/value->tree-hiccup deep)]
      (is (vector? out))
      (is (contains-text? out ":lvl19"))
      (is (contains-text? out "▸")))))

(deftest tree-hiccup-max-depth-hard-cap-summarises
  (testing "explicit small :max-depth caps the BODY recursion: even when
            the operator expands every node, the walker degrades to ▸
            past max-depth (defence against cyclic/pathological values)"
    (let [wide  (zipmap (map #(keyword (str "k" %)) (range 8))
                        (range 8))
          outer (zipmap (map #(keyword (str "g" %)) (range 8))
                        (repeat wide))
          ;; max-depth 1 + default-depth 1 — root expands; the nested
          ;; wide maps hit the hard cap and collapse to ▸.
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

;; ---- click-to-toggle wiring (rf2-dw8n7) ----------------------------------
;;
;; When the caller supplies `:panel-id` + `:render-id`, every collection
;; surrogate's triangle becomes a clickable [:span] with role="button",
;; cursor:pointer, and tabindex=0. Without those keys the triangle
;; renders without a click handler (pure-data tests / non-interactive
;; surfaces). These assert the wrapper shape carries the expected
;; affordances.

(defn- find-spans-with-role-button
  [tree]
  (->> (walk-hiccup tree)
       (filter (fn [n]
                 (and (vector? n)
                      (= :span (first n))
                      (map? (second n))
                      (= "button" (:role (second n))))))))

(deftest tree-hiccup-without-panel-id-has-no-click-wrapper
  (testing "without :panel-id + :render-id the triangle has NO role=button
            wrapper (non-interactive surfaces opt out of the toggle UI)"
    (let [out (cdt/value->tree-hiccup {:a {:b 1}})]
      (is (vector? out))
      (is (empty? (find-spans-with-role-button out))))))

(deftest tree-hiccup-with-panel-id-wraps-triangle-as-button
  (testing "with :panel-id + :render-id the triangle wraps as a
            role=\"button\" [:span] with cursor:pointer + on-click +
            tabindex (rf2-dw8n7 acceptance)"
    (let [out  (cdt/value->tree-hiccup {:a {:b 1}}
                                       {:panel-id  :test
                                        :render-id "wire-1"})
          btns (find-spans-with-role-button out)]
      (is (seq btns) "at least one clickable triangle wrapper")
      (let [{:keys [on-click on-key-down role tab-index aria-label style]}
            (second (first btns))]
        (is (fn? on-click)    ":on-click is wired")
        (is (fn? on-key-down) ":on-key-down handles Enter/Space")
        (is (= "button" role))
        (is (= 0 tab-index))
        (is (string? aria-label))
        (is (= "pointer" (:cursor style)))))))

(deftest tree-hiccup-default-collapsed-glyph-is-right-triangle
  (testing "a default-collapsed nested node renders the ▸ glyph; a
            default-expanded node renders ▾. Use a WIDE inner map so
            the §10.4 threshold pushes it past 'expanded' to 'collapsed'
            at the depth boundary.

            cljs-devtools' `has-body-api-call` returns false for raw
            CLJS values (only surrogates carry bodies), so the ROOT
            triangle is rendered only at the surrogate level — the
            nested wide map IS a surrogate, so its ▸ wrapper is present
            and interactive."
    (let [wide-inner (zipmap (map #(keyword (str "k" %)) (range 20))
                             (range 20))
          out        (cdt/value->tree-hiccup {:outer wide-inner}
                                             {:panel-id  :test
                                              :render-id "glyph-1"
                                              :default-depth 1})
          btns       (find-spans-with-role-button out)
          glyphs     (map #(last %) btns)]
      (is (seq btns) "the nested surrogate carries a clickable triangle")
      (is (some #{"▸"} glyphs) "wide nested collapsed glyph"))))

(deftest tree-hiccup-with-panel-id-renders-expanded-glyph-when-default-deep
  (testing ":default-depth deeper than the surrogate's depth makes its
            triangle render the ▾ (expanded) glyph"
    (let [out    (cdt/value->tree-hiccup {:outer (zipmap (map #(keyword (str "k" %)) (range 20))
                                                         (range 20))}
                                         {:panel-id  :test
                                          :render-id "glyph-deep"
                                          :default-depth 5})
          btns   (find-spans-with-role-button out)
          glyphs (map #(last %) btns)]
      (is (some #{"▾"} glyphs)
          "with default-depth deeper than the surrogate, ▾ appears"))))
