(ns day8.re-frame2-causa.panels.app-db-diff-format-cljs-test
  "Per-leaf smoke test for `app-db-diff-format` (rf2-nb8if).

  The format leaf carries pure display-only formatters — `format-edn`,
  `display-value`, `format-display-edn`, `truncate`, plus the
  `op->border` / `op->label` lookup tables and the
  `display-large-string-threshold` ceiling. CLJS-only because the
  underlying leaf is `.cljs` (parent panel is CLJS-only)."
  (:require [cljs.test :refer-macros [deftest is]]
            [day8.re-frame2-causa.panels.app-db-diff-format :as f]))

(deftest format-leaf-smoke
  (is (= "[:cart :items]" (f/format-edn [:cart :items])))
  (is (= "abc…" (f/truncate "abcdef" 4))
      "truncate trims and appends an ellipsis at the cap")
  (is (contains? f/op->border :added))
  (is (contains? f/op->label :modified))
  (let [big (apply str (repeat (inc f/display-large-string-threshold) "x"))
        v   (f/display-value {:k big})]
    (is (map? (:rf.size/large-elided (:k v)))
        "large strings collapse to the elision marker")))
