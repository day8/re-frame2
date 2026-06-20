(ns re-frame2-pair-mcp.snapshot-test
  "Unit tests for the snapshot tool's argument parsing — the shape we
  send to `re-frame2-pair.runtime/snapshot-state` over nREPL.

  The live end-to-end coverage lives in `test/stdio-roundtrip.js` (the
  degraded-mode dispatch path) and the manual live-nREPL integration
  test against a real shadow-cljs build. The CLJS layer here just
  pins the MCP-arg→runtime-opts translation so accidental renames or
  case slips break the test rather than silently shipping a broken
  contract.

  Tests require the public parsers directly from
  `re-frame2-pair-mcp.tools.args` — the source ns is the contract.

  Production-form integration coverage for the elision walk lives in
  `re-frame2-pair-mcp.elision-test` (via the `build-snapshot-form`
  mirror); see the note at the bottom of this file."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [cljs.reader]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.eval-form :as ef]))

(deftest frames-default-is-app-not-all
  ;; The DEFAULT scope (absent arg) is `:app` (app frames only, reserved
  ;; :rf/* tool frames excluded), NOT `:all`. Explicit "all" is the
  ;; opt-in to tool-frame state.
  (is (= :app (args/parse-frames-arg nil))
      "absent frames arg defaults to :app (app frames only)")
  (is (= :all (args/parse-frames-arg "all"))
      "explicit \"all\" opts into ALL frames incl. reserved tool frames")
  (is (= :all (args/parse-frames-arg :all)))
  (is (= :app (args/parse-frames-arg "app"))
      "explicit \"app\" is the app-frames-only scope")
  (testing "unrecognised scalar collapses to the safe :app default"
    (is (= :app (args/parse-frames-arg 42)))
    (is (= :app (args/parse-frames-arg "al")))))

(deftest frames-array-coerces-to-keywords
  (let [arr #js [":rf/default" ":stories"]]
    (is (= [:rf/default :stories] (args/parse-frames-arg arr)))))

(deftest frames-vector-coerces-to-keywords
  (is (= [:rf/default :stories]
         (args/parse-frames-arg [":rf/default" ":stories"]))))

(deftest include-default-is-full-slice-set
  (is (= [:app-db :sub-cache :machines :epochs :traces]
         (args/parse-include-arg nil)))
  (is (= [:app-db :sub-cache :machines :epochs :traces]
         (args/parse-include-arg #js []))))

(deftest include-filters-unknown-slices
  (testing "unknown slices fall away, known stay in order"
    (is (= [:app-db :epochs]
           (args/parse-include-arg #js ["app-db" "garbage" "epochs"]))))
  (testing "all-unknown falls back to the full list"
    (is (= [:app-db :sub-cache :machines :epochs :traces]
           (args/parse-include-arg #js ["garbage" "more-garbage"])))))

(deftest include-accepts-subset
  (is (= [:app-db :sub-cache] (args/parse-include-arg #js ["app-db" "sub-cache"]))))

(deftest snapshot-state-form-is-edn-readable
  ;; The MCP server lifts the opts map into the eval-form DSL, which
  ;; renders `(re-frame2-pair.runtime/snapshot-state
  ;; {:frames :all :include [...]})`. Assert against the parsed opts
  ;; map rather than regex-matching the source string.
  (let [opts {:frames :all
              :include (args/parse-include-arg nil)}
        form (ef/emit (ef/rt-call 'snapshot-state opts))
        edn  (cljs.reader/read-string form)]
    (is (= 're-frame2-pair.runtime/snapshot-state (first edn)))
    (is (= :all (-> edn second :frames)))
    (is (= [:app-db :sub-cache :machines :epochs :traces]
           (-> edn second :include)))))

;; ---------------------------------------------------------------------------
;; Note on elision integration coverage:
;;
;; Eval-form composition for the snapshot tool (walking BOTH `:app-db`
;; and `:sub-cache` through `re-frame.core/elide-wire-value`, threading
;; `:include-sensitive` into the walker's opt) is pinned in
;; `re-frame2-pair-mcp.elision-test` via the production `build-snapshot-form`
;; mirror — see `snapshot-form-walks-both-app-db-and-sub-cache` and
;; `snapshot-form-threads-include-sensitive`.
;;
;; Async stub isolation: tests that stub a tool fn restore it from a
;; `use-fixtures` `:after` step rather than a Promise `.finally`, so
;; cleanup is Promise-chain-independent and a `.finally` can't outrun a
;; test's `(done)` and leak the stub into the next async test. The
;; mirror approach here keeps this suite stub-free for full isolation.
;; ---------------------------------------------------------------------------
