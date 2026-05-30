(ns re-frame.story.ui.docs-toc-test
  "JVM-portable regression net for the docs-mode TOC table (rf2-8c7tk).

  Surface covered:

  - `docs-toc-entries`     — canonical table shape
  - `visible-toc-entries`  — prose-conditional pruning vs always-on

  CLJS-side (IntersectionObserver wiring + scroll-into-view + reactive
  re-render) lives in `docs_toc_cljs_test.cljs` — this corpus pins the
  pure projection only."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.ui.docs :as docs]))

(deftest toc-table-shape
  (testing "canonical entry list (rf2-ba86n.14 — status + view-arg schema +
            evidence sections added)"
    (let [ids (mapv :id docs/docs-toc-entries)]
      (is (= ["docs-status" "docs-prose" "docs-args" "docs-schema"
              "docs-decorators" "docs-parameters" "docs-evidence" "docs-tags"]
             ids))))
  (testing "every entry carries the required slots"
    (doseq [entry docs/docs-toc-entries]
      (is (some? (:id entry)))
      (is (some? (:label entry)))
      (is (integer? (:level entry))))))

(deftest conditional-entries
  (testing "the conditional entries are status / prose / view-arg schema
            (rf2-ba86n.14); args / decorators / parameters / evidence / tags
            are unconditional"
    (is (= #{"docs-status" "docs-prose" "docs-schema"}
           (into #{} (map :id) (filter :conditional? docs/docs-toc-entries))))))

;; `visible-toc-entries` consults the live registrar for prose workspaces +
;; compiles the variant's plan for the status / view-arg-schema conditionals.
;; The JVM corpus exercises it with no registry → `prose-for-variant` returns
;; empty AND `variant-plan-quietly` returns nil (unknown variant), so the
;; prose / status / schema entries are all pruned.

(deftest visible-toc-prunes-conditionals-when-absent
  (testing "no prose workspace + uncompilable plan → prose / status / schema
            entries pruned; the unconditional entries remain"
    (let [out (docs/visible-toc-entries :story.fake/variant)]
      (is (not-any? #(#{"docs-prose" "docs-status" "docs-schema"} (:id %)) out))
      (is (= ["docs-args" "docs-decorators" "docs-parameters"
              "docs-evidence" "docs-tags"]
             (mapv :id out))))))
