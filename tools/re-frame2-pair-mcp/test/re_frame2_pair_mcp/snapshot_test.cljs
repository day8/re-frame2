(ns re-frame2-pair-mcp.snapshot-test
  "The snapshot tool's MCP-arg parsing: `frames` and `include`, as
  translated into the opts sent to `re-frame2-pair.runtime/snapshot-state`."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame2-pair-mcp.tools.args :as args]))

(deftest parse-frames-arg-scopes-and-frame-lists
  ;; The DEFAULT scope is `:app` (reserved :rf/* tool frames excluded),
  ;; NOT `:all`; explicit "all" opts into tool-frame state.
  (doseq [[input expected note]
          [[nil :app "absent frames arg defaults to :app (app frames only)"]
           ["all" :all "explicit \"all\" opts into ALL frames incl. reserved tool frames"]
           ["al" :app "a near-miss string collapses to the safe :app default"]
           [#js [":rf/default" ":stories"] [:rf/default :stories]
            "a JS array of frame ids coerces to keywords"]]]
    (is (= expected (args/parse-frames-arg input)) note)))

(deftest parse-include-arg-resolution
  (doseq [[input expected note]
          [[nil [:app-db :sub-cache :machines :epochs :traces] "absent ⇒ the full slice set"]
           [#js ["app-db" "garbage" "epochs"] [:app-db :epochs]
            "unknown slices fall away, known stay in order"]
           [#js ["garbage" "more-garbage"] [:app-db :sub-cache :machines :epochs :traces]
            "all-unknown falls back to the full set"]]]
    (is (= expected (args/parse-include-arg input)) note)))
