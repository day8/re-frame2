(ns re-frame.seven-guis-cells-parser-cljs-test
  "Parser/evaluator boundary regression for the Cells example (rf2-4n4ds3).

  The Cells grid is 26×100 (A1..Z100), but `cell-re` is only a *syntactic* gate
  (`^[A-Z]\\d{1,3}$`) that also admits out-of-grid refs like A0, A101, or Z999.
  Before the fix those parsed as `{:cell …}` nodes and evaluated as empty cells
  (a silent 0); now `in-grid?` / `cell-ref?` reject them, and a formula carrying
  one fails loud at parse time.

  These belong in the framework test tree, NOT under examples/ (examples stay
  test-free per rf2-8cevm). They exercise the example's PURE parser/evaluator
  fns by requiring `seven-guis.cells.core` — a Reagent-coupled `.cljs`-only
  namespace — so they run under the consolidated `:node-test` CLJS build, which
  has `../examples/reagent` on its source paths and is the only runtime where
  this example's ns-load + handlers actually execute (mirrors the classpath
  reach of `re-frame.example-frame-scoping-cljs-test`)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [seven-guis.cells.core :as cells]))

;; A cell entry as `:cells/commit` would store it — raw text plus the parsed
;; AST for formulas. Enough for `evaluate-cell`, which reads :raw/:formula?/:ast.
(defn- cell-entry [raw]
  (let [formula? (= \= (first raw))
        ast      (when formula? (cells/parse-formula raw))]
    {:raw raw :formula? formula? :ast ast :deps #{}}))

;; ===========================================================================
;; parse-cell-id / cell-ref? / in-grid? — coordinate parsing with grid bounds
;; ===========================================================================

(deftest parse-cell-id-accepts-in-grid-refs
  (testing "corners + edges of the 26×100 grid parse to their coordinate"
    (doseq [[s coord] {"A1"   [0 1]        ;; top-left
                       "Z1"   [25 1]       ;; top-right
                       "A100" [0 100]      ;; bottom-left — load-bearing row 100
                       "Z100" [25 100]     ;; bottom-right
                       "A99"  [0 99]
                       "M50"  [12 50]}]
      (is (= coord (cells/parse-cell-id s))
          (str s " should parse to " coord)))))

(deftest parse-cell-id-rejects-out-of-grid-refs
  (testing "shaped like a ref but off the grid → nil (not a silent coordinate)"
    (doseq [s ["A0"     ;; row 0 — below the first row
               "A00"    ;; row 0 spelled with two digits
               "Z0"
               "A101"   ;; row 101 — one past the last row
               "Z101"
               "A999"   ;; 3-digit row well past 100
               "A000"]]
      (is (nil? (cells/parse-cell-id s))
          (str s " is outside A1..Z100 and must not parse"))))
  (testing "not even shaped like a ref → nil"
    (doseq [s ["AA1"    ;; two letters — no column past Z
               "1A"
               "a1"     ;; lowercase
               "A"
               ""
               "A1B"]]
      (is (nil? (cells/parse-cell-id s))
          (str s " is not a cell id and must not parse")))))

(deftest cell-ref?-tracks-parse-cell-id
  (testing "in-grid refs are cell refs"
    (doseq [s ["A1" "Z100" "A100" "Z1"]]
      (is (true? (cells/cell-ref? s)) s)))
  (testing "out-of-grid / malformed refs are not"
    (doseq [s ["A0" "A101" "A999" "AA1" "a1" ""]]
      (is (false? (cells/cell-ref? s)) s))))

(deftest in-grid?-guards-both-axes
  (is (true?  (cells/in-grid? 0 1))     "top-left corner")
  (is (true?  (cells/in-grid? 25 100))  "bottom-right corner")
  (is (false? (cells/in-grid? 0 0))     "row below the first")
  (is (false? (cells/in-grid? 0 101))   "row past the last")
  (is (false? (cells/in-grid? -1 1))    "column below the first")
  (is (false? (cells/in-grid? 26 1))    "column past Z"))

;; ===========================================================================
;; parse-formula — an out-of-grid ref inside a formula fails loud at parse time
;; ===========================================================================

(deftest parse-formula-rejects-out-of-grid-refs
  (testing "a formula referencing an off-grid cell is a parse error"
    (doseq [raw ["=(+ A101 1)"   ;; row past the bottom
                 "=(+ A0 1)"     ;; row before the top
                 "=A999"         ;; bare out-of-range ref
                 "=(* Z101 2)"]]
      (is (cells/parse-error? (cells/parse-formula raw))
          (str raw " must fail to parse"))))
  (testing "the message names the offending ref, the range, and 'outside the grid'"
    (let [msg (cells/parse-error-text (cells/parse-formula "A1" "=(+ A101 1)"))]
      (is (string? msg))
      (is (re-find #"A101" msg)          "names the offending ref")
      (is (re-find #"outside the grid" msg) "explains it is out of range")
      (is (re-find #"A1\.\.Z100" msg)    "states the valid range"))))

(deftest parse-formula-accepts-in-grid-refs
  (testing "valid refs — including row 100 — still parse to a real AST"
    (doseq [raw ["=(+ A1 B2)"
                 "=(+ A100 1)"    ;; row 100 is a real cell
                 "=(* Z100 2)"
                 "=42"]]
      (is (not (cells/parse-error? (cells/parse-formula raw)))
          (str raw " should parse cleanly")))))

;; ===========================================================================
;; evaluate-cell — row 100 is real; an off-grid ref surfaces the error, not 0
;; ===========================================================================

(deftest evaluate-uses-real-row-100-cells
  (testing "a literal in row 100 evaluates to its value"
    (is (= 5 (cells/evaluate-cell "A100" {"A100" (cell-entry "5")} #{}))))
  (testing "a formula reading row 100 picks up that cell"
    (let [cells {"A100" (cell-entry "6")
                 "A1"   (cell-entry "=(+ A100 1)")}]
      (is (= 7 (cells/evaluate-cell "A1" cells #{}))))))

(deftest evaluate-out-of-grid-ref-fails-loud-not-silent-zero
  (testing "an off-grid ref surfaces the parse error — it is NOT silently
            treated as an empty cell (the rf2-4n4ds3 bug)"
    (let [v (cells/evaluate-cell "A1" {"A1" (cell-entry "=(+ A101 1)")} #{})]
      ;; Before the fix A101 parsed as {:cell "A101"} → empty cell 0 → v = 1.
      ;; After: the formula fails to parse, so the value is the parse-error
      ;; pair, never the misleading 1.
      (is (cells/parse-error? v))
      (is (not= 1 v)))))

;; ===========================================================================
;; evaluate-cell — cycle guard (rf2-1r1dm7)
;; ===========================================================================
;;
;; The example's docstring promises "Cycles (A1 -> B1 -> A1) caught by a
;; visited-set walk, not a stack overflow." A regression here would blow the
;; stack and take the whole grid render down — silently green — so pin it.

(deftest evaluate-cell-cycle-guard-yields-error-not-stack-overflow
  (testing "a two-cell cycle A1 -> B1 -> A1 is caught by the visited-set walk"
    (let [cells {"A1" (cell-entry "=(+ B1 1)")
                 "B1" (cell-entry "=(+ A1 1)")}]
      (is (= :error/cycle (cells/evaluate-cell "A1" cells #{})))
      (is (= :error/cycle (cells/evaluate-cell "B1" cells #{})))))
  (testing "a self-cycle A1 -> A1 is also caught (never recurses forever)"
    (is (= :error/cycle
           (cells/evaluate-cell "A1" {"A1" (cell-entry "=(+ A1 1)")} #{})))))

;; ===========================================================================
;; evaluate-cell / evaluate-ast — the typed error taxonomy (rf2-1r1dm7)
;; ===========================================================================
;;
;; "Bad arithmetic turned into typed error markers — the evaluator never
;; throws." Each bad shape must surface as its own keyword marker, not an
;; exception and not a misleading number.

(deftest evaluate-cell-division-by-zero-marks-error
  (testing "a literal /0 yields :error/div-by-zero (never throws)"
    (is (= :error/div-by-zero
           (cells/evaluate-cell "A1" {"A1" (cell-entry "=(/ 1 0)")} #{}))))
  (testing "a divisor that COMPUTES to zero is caught too"
    (is (= :error/div-by-zero
           (cells/evaluate-cell "A1" {"A1" (cell-entry "=(/ 10 (- 3 3))")} #{}))))
  (testing "a non-zero division still computes"
    (is (= 3 (cells/evaluate-cell "A1" {"A1" (cell-entry "=(/ 6 2)")} #{})))))

(deftest evaluate-cell-text-in-arithmetic-marks-type-error
  (testing "feeding a text cell into arithmetic yields :error/type"
    (let [cells {"A1" (cell-entry "hi")
                 "B1" (cell-entry "=(+ A1 2)")}]
      (is (= :error/type (cells/evaluate-cell "B1" cells #{})))))
  (testing "a plain (non-formula) text cell evaluates to its own raw text"
    (is (= "hi" (cells/evaluate-cell "A1" {"A1" (cell-entry "hi")} #{})))))

(deftest evaluate-ast-marks-unknown-op-and-uncomputable
  (testing "an operator outside + - * / is :error/unknown-op"
    (is (= :error/unknown-op (cells/evaluate-ast [(symbol "%") 1 2] {} #{})))
    (is (= :error/unknown-op (cells/evaluate-ast [(symbol "mod") 4 2] {} #{}))))
  (testing "an AST node that is neither number, cell ref, nor list is
            :error/eval (the belt-and-suspenders marker)"
    (is (= :error/eval (cells/evaluate-ast "not-an-ast-node" {} #{})))
    (is (= :error/eval (cells/evaluate-ast true {} #{})))))

;; ===========================================================================
;; evaluate-cell / evaluate-ast — arithmetic + empty-cell semantics
;; ===========================================================================

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
  (testing "an untouched cell reads as 0 (empty-cell semantics)"
    (is (= 0 (cells/evaluate-cell "Z9" {} #{})))
    (is (= 1 (cells/evaluate-cell "B1" {"B1" (cell-entry "=(+ A1 1)")} #{}))
        "a formula over an empty referent folds it in as 0")))

;; ===========================================================================
;; parse-num — the ^…$ anchored strict numeric gate
;; ===========================================================================

(deftest parse-num-strict-anchored
  (testing "a wholly-numeric string parses (sign / decimal / exponent)"
    (is (= 42    (cells/parse-num "42")))
    (is (= 3.14  (cells/parse-num "3.14")))
    (is (= -1500 (cells/parse-num "-1.5e3")))
    (is (= 0.5   (cells/parse-num ".5")))
    (is (= 7     (cells/parse-num "  7  "))))          ;; trims before parsing
  (testing "partially-numeric junk is rejected by the ^…$ anchors — NOT read
            as the leading numeric run js/parseFloat would greedily accept"
    (doseq [s ["1abc" "1.2.3" "abc" "" "  " "1 2" "0x10"]]
      (is (nil? (cells/parse-num s))
          (str (pr-str s) " must not parse as a number")))))

;; ===========================================================================
;; tokenise / parse-tokens / parse-formula — the parse boundary (rf2-1r1dm7)
;; ===========================================================================

(deftest tokenise-splits-parens-and-atoms
  (testing "parens fall out as their own tokens without needing spaces"
    (is (= ["(" "+" "A1" "B2" ")"] (cells/tokenise "(+ A1 B2)")))
    (is (= ["(" "+" "1" "(" "*" "2" "3" ")" ")"]
           (cells/tokenise "(+ 1 (* 2 3))")))
    (is (= ["42"] (cells/tokenise "42")))))

(deftest parse-tokens-throws-on-unbalanced-parens
  (testing "an unclosed '(' throws, tagging the offending token"
    (let [e (try (cells/parse-tokens ["(" "+" "1"]) nil (catch :default e e))]
      (is (some? e) "an unclosed '(' throws")
      (is (= "(" (:token (ex-data e))) "the throw names the '(' token")))
  (testing "an extra ')' throws, tagging it"
    (let [e (try (cells/parse-tokens [")"]) nil (catch :default e e))]
      (is (some? e) "an extra ')' throws")
      (is (= ")" (:token (ex-data e))) "the throw names the ')' token"))))

(deftest parse-formula-never-throws-returns-error-pair
  (testing "parse-formula catches parse throws and returns an [:error/parse msg]
            pair — the evaluator boundary never throws across it"
    (doseq [raw ["=(+ 1 2"       ;; unclosed '('
                 "=(+ 1 2))"      ;; extra ')'
                 "=(% 1 2)"       ;; unknown token
                 "=(+ 1 2) 3"]]   ;; trailing tokens after the expression
      (let [ast (cells/parse-formula raw)]
        (is (cells/parse-error? ast)
            (str raw " must parse to an [:error/parse …] pair"))
        (is (string? (cells/parse-error-text ast))
            (str raw " carries an actionable message")))))
  (testing "a well-formed formula does NOT parse to an error pair"
    (is (not (cells/parse-error? (cells/parse-formula "=(+ 1 2)"))))))
