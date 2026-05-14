(ns re-frame-pair2-mcp.snapshot-test
  "Unit tests for the snapshot tool's argument parsing — the shape we
  send to `re-frame-pair2.runtime/snapshot-state` over nREPL.

  The live end-to-end coverage lives in `test/stdio-roundtrip.js` (the
  degraded-mode dispatch path) and the manual live-nREPL integration
  test against a real shadow-cljs build. The CLJS layer here just
  pins the MCP-arg→runtime-opts translation so accidental renames or
  case slips break the test rather than silently shipping a broken
  contract.

  Tests require the public parsers directly from
  `re-frame-pair2-mcp.tools.args` — the source ns is the contract.

  Production-form integration coverage for rf2-vflrg lives in
  `re-frame-pair2-mcp.elision-test` (via the `build-snapshot-form`
  mirror); see the note at the bottom of this file."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [cljs.reader]
            [re-frame-pair2-mcp.tools.args :as args]
            [re-frame-pair2-mcp.tools.eval-form :as ef]))

(deftest frames-default-is-all
  (is (= :all (args/parse-frames-arg nil)))
  (is (= :all (args/parse-frames-arg "all"))))

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
  ;; The MCP server lifts the opts map into the eval-form DSL
  ;; (rf2-dpzpe), which renders `(re-frame-pair2.runtime/snapshot-state
  ;; {:frames :all :include [...]})`. Assert against the parsed opts
  ;; map rather than regex-matching the source string.
  (let [opts {:frames :all
              :include (args/parse-include-arg nil)}
        form (ef/emit (ef/rt-call 'snapshot-state opts))
        edn  (cljs.reader/read-string form)]
    (is (= 're-frame-pair2.runtime/snapshot-state (first edn)))
    (is (= :all (-> edn second :frames)))
    (is (= [:app-db :sub-cache :machines :epochs :traces]
           (-> edn second :include)))))

;; ---------------------------------------------------------------------------
;; Note on rf2-vflrg integration coverage:
;;
;; Eval-form composition for the snapshot tool (now walking BOTH `:app-db`
;; and `:sub-cache` through `re-frame.core/elide-wire-value`, threading
;; `:include-sensitive?` into the walker's opt) is pinned in
;; `re-frame-pair2-mcp.elision-test` via the production `build-snapshot-form`
;; mirror — see `snapshot-form-walks-both-app-db-and-sub-cache` and
;; `snapshot-form-threads-include-sensitive`.
;;
;; Historical note: `invoke-test` used to stub `snapshot/snapshot-tool`
;; via direct `set!` with `.finally` restoration; the `.finally` could
;; outrun the test's `(done)`, leaking the stub into the next async
;; test (rf2-wb06a). Fixed by moving restoration to a `use-fixtures`
;; `:after` step — cleanup is now Promise-chain-independent. The
;; mirror approach here is still preferred for isolation, but it is
;; no longer a race-safety necessity.
;; ---------------------------------------------------------------------------
