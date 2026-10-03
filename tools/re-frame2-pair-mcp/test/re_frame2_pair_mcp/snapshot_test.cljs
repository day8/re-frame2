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
  `re-frame2-pair-mcp.egress-elision-test`, which captures the form the
  real tool ships; see the note at the bottom of this file."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame2-pair-mcp.tools.args :as args]))

(deftest parse-frames-arg-scopes-and-frame-lists
  ;; The DEFAULT scope (absent arg) is `:app` (app frames only, reserved
  ;; :rf/* tool frames excluded), NOT `:all`. Explicit "all" is the
  ;; opt-in to tool-frame state.
  (doseq [[input expected note]
          [[nil :app "absent frames arg defaults to :app (app frames only)"]
           ["all" :all "explicit \"all\" opts into ALL frames incl. reserved tool frames"]
           [:all :all "the keyword form is the same opt-in"]
           ["app" :app "explicit \"app\" is the app-frames-only scope"]
           [42 :app "an unrecognised scalar collapses to the safe :app default"]
           ["al" :app "a near-miss string collapses to the safe :app default"]
           [#js [":rf/default" ":stories"] [:rf/default :stories]
            "a JS array of frame ids coerces to keywords"]
           [[":rf/default" ":stories"] [:rf/default :stories]
            "a vector of frame ids coerces to keywords"]]]
    (is (= expected (args/parse-frames-arg input)) note)))

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

;; ---------------------------------------------------------------------------
;; Note on elision integration coverage:
;;
;; Eval-form composition for the snapshot tool (walking BOTH `:app-db`
;; and `:sub-cache` through `re-frame.core/project-egress`, threading
;; `:include-sensitive` into the walker's opt) is pinned against the real
;; tool form by the conformance corpus's `:raw-state/snapshot-*` fixtures
;; and by `re-frame2-pair-mcp.egress-elision-test`'s `snapshot-*` tests.
;;
;; Async stub isolation: tests that stub a tool fn restore it from a
;; `use-fixtures` `:after` step rather than a Promise `.finally`, so
;; cleanup is Promise-chain-independent and a `.finally` can't outrun a
;; test's `(done)` and leak the stub into the next async test. This
;; suite stubs nothing, so it needs no such fixture.
;; ---------------------------------------------------------------------------
