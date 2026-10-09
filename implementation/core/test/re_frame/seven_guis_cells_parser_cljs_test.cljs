(ns re-frame.seven-guis-cells-parser-cljs-test
  "Parser and evaluator of the Cells example (`seven-guis.cells.core`), a
  Reagent-coupled `.cljs` namespace that loads only under the `:node-test`
  build. The grid is 26×100 (A1..Z100), but `cell-re` is only a syntactic gate
  that also admits off-grid refs (A0, A101); `in-grid?` rejects them, so a
  formula carrying one fails at parse time instead of reading an empty cell
  as 0."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [seven-guis.cells.core :as cells]))

;; A cell entry as `:cells/commit` stores it: raw text plus the parsed AST for
;; formulas. Enough for `evaluate-cell`, which reads :raw/:formula?/:ast.
(defn- cell-entry [raw]
  (let [formula? (= \= (first raw))
        ast      (when formula? (cells/parse-formula raw))]
    {:raw raw :formula? formula? :ast ast :deps #{}}))

(deftest parse-cell-id-accepts-in-grid-refs
  (doseq [[s coord] {"A1" [0 1] "Z1" [25 1] "A100" [0 100] "Z100" [25 100]}]
    (is (= coord (cells/parse-cell-id s)) s)))

(deftest parse-cell-id-rejects-out-of-grid-refs
  (doseq [s ["A0"     ;; row below the first
             "A101"   ;; row one past the last
             "AA1"    ;; no column past Z
             "a1"]]   ;; lowercase
    (is (nil? (cells/parse-cell-id s)) s)))

(deftest evaluate-uses-real-row-100-cells
  (is (= 7 (cells/evaluate-cell "A1" {"A100" (cell-entry "6")
                                      "A1"   (cell-entry "=(+ A100 1)")} #{}))
      "a formula reading row 100 picks up that cell"))

(deftest evaluate-out-of-grid-ref-fails-loud-not-silent-zero
  (let [v (cells/evaluate-cell "A1" {"A1" (cell-entry "=(+ A101 1)")} #{})]
    (is (cells/parse-error? v)
        "an off-grid ref surfaces the parse error, never an empty cell read as 0")
    (is (re-find #"A101" (cells/parse-error-text v)) "the message names the offending ref")
    (is (re-find #"outside the grid" (cells/parse-error-text v)))))

(deftest evaluate-cell-division-by-zero-marks-error
  (is (= :error/div-by-zero
         (cells/evaluate-cell "A1" {"A1" (cell-entry "=(/ 1 0)")} #{}))))

(deftest evaluate-cell-text-in-arithmetic-marks-type-error
  (testing "feeding a text cell into arithmetic yields :error/type"
    (let [cells {"A1" (cell-entry "hi")
                 "B1" (cell-entry "=(+ A1 2)")}]
      (is (= :error/type (cells/evaluate-cell "B1" cells #{})))))
  (testing "a plain (non-formula) text cell evaluates to its own raw text"
    (is (= "hi" (cells/evaluate-cell "A1" {"A1" (cell-entry "hi")} #{})))))

(deftest value->display-maps-every-error-marker-to-a-hash-code
  (is (= ["#CYCLE" "#EVAL" "#TYPE" "#DIV/0" "#OP?" "#PARSE" "42" "hi"]
         (mapv cells/value->display
               [:error/cycle :error/eval :error/type :error/div-by-zero :error/unknown-op
                [:error/parse "boom"] 42 "hi"]))
      "every typed marker shows a hash-code, never its raw keyword"))

(deftest unknown-op-formula-renders-hash-not-raw-keyword
  (is (= "#OP?" (cells/value->display
                  (cells/evaluate-cell "A1" {"A1" (cell-entry "=(1 2)")} #{})))
      "=(1 2), a list headed by a number, evaluates to :error/unknown-op and shows #OP?"))

(deftest evaluate-cell-arithmetic-operators
  (testing "variadic + - * / fold across all their arguments"
    (is (= 6  (cells/evaluate-cell "A1" {"A1" (cell-entry "=(+ 1 2 3)")} #{})))
    (is (= 5  (cells/evaluate-cell "A1" {"A1" (cell-entry "=(- 10 3 2)")} #{})))
    (is (= 24 (cells/evaluate-cell "A1" {"A1" (cell-entry "=(* 2 3 4)")} #{})))
    (is (= 10 (cells/evaluate-cell "A1" {"A1" (cell-entry "=(/ 100 5 2)")} #{}))))
  (testing "nested formulas reduce inner expressions first"
    (let [cells {"A1" (cell-entry "5")
                 "B2" (cell-entry "4")
                 "C1" (cell-entry "=(+ A1 (* B2 3))")}]
      (is (= 17 (cells/evaluate-cell "C1" cells #{})))))
  (testing "a formula over an untouched cell reads it as 0"
    (is (= 1 (cells/evaluate-cell "B1" {"B1" (cell-entry "=(+ A1 1)")} #{})))))

(deftest parse-num-strict-anchored
  (testing "a wholly-numeric string parses (sign, decimal, exponent, leading dot, padding)"
    (is (= -1500 (cells/parse-num "-1.5e3")))
    (is (= 0.5   (cells/parse-num ".5")))
    (is (= 7     (cells/parse-num "  7  "))))
  (testing "partially-numeric junk is rejected, not read as the leading numeric
            run js/parseFloat would accept"
    (doseq [s ["1abc" "1.2.3" ""]]
      (is (nil? (cells/parse-num s)) (pr-str s)))))

(deftest parse-formula-never-throws-returns-error-pair
  (doseq [raw ["=(+ 1 2"      ;; unclosed '('
               "=(+ 1 2))"    ;; extra ')'
               "=(% 1 2)"]]   ;; unknown token
    (is (cells/parse-error? (cells/parse-formula raw))
        (str raw " must parse to an [:error/parse …] pair"))))

;; ---------------------------------------------------------------------------
;; Settled dependencies are reused. Without reuse a chain where each cell
;; doubles the one before (A2 =(+ A1 A1), …) re-walks every subtree, so A21
;; alone costs 2,097,151 visits. Counting map lookups — one per visited cell —
;; makes the check a deterministic operation count, not a clock.

(defn- counting-cells
  "`m` behind an `ILookup` that bumps `counter` on every lookup."
  [m counter]
  (reify ILookup
    (-lookup [_ k] (swap! counter inc) (get m k))
    (-lookup [_ k not-found] (swap! counter inc) (get m k not-found))))

(defn- chain
  "A1 holds `a1`; each An for n in 2..`depth` is `(formula-for prev-id)`."
  [depth a1 formula-for]
  (into {"A1" (cell-entry (str a1))}
        (for [n (range 2 (inc depth))]
          [(str "A" n) (cell-entry (formula-for (str "A" (dec n))))])))

(defn- doubling [prev] (str "=(+ " prev " " prev ")"))

(deftest evaluate-cell-reuses-settled-dependencies
  (testing "the 21-cell doubling chain yields 2^20 in ONE visit per cell"
    (let [lookups (atom 0)
          v       (cells/evaluate-cell "A21" (counting-cells (chain 21 1 doubling) lookups) #{})]
      (is (= 1048576 v))
      (is (= 21 @lookups)
          "each of the 21 cells is read once — an exponential re-walk would read 2,097,151")))
  (testing "a new snapshot recomputes from its own cells — nothing leaks between snapshots"
    (let [before (chain 21 1 doubling)
          after  (assoc before "A1" (cell-entry "2"))]
      (is (= 1048576 (cells/evaluate-cell "A21" before #{})))
      (is (= 2097152 (cells/evaluate-cell "A21" after #{})) "editing A1 moves A21")))
  (testing "closing the chain into a loop reads :error/cycle, in one visit per cell"
    (let [lookups (atom 0)
          looped  (assoc (chain 21 1 doubling) "A1" (cell-entry "=(+ A21 1)"))]
      (is (= :error/cycle
             (cells/evaluate-cell "A21" (counting-cells looped lookups) #{})))
      (is (= 21 @lookups)))))
