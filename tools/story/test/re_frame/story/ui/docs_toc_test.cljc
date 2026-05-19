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
  (testing "canonical entry list"
    (let [ids (mapv :id docs/docs-toc-entries)]
      (is (= ["docs-prose" "docs-args" "docs-decorators"
              "docs-parameters" "docs-tags"]
             ids))))
  (testing "every entry carries the required slots"
    (doseq [entry docs/docs-toc-entries]
      (is (some? (:id entry)))
      (is (some? (:label entry)))
      (is (integer? (:level entry))))))

(deftest prose-is-conditional
  (testing "only the prose entry is conditional"
    (is (= [{:id "docs-prose" :label "Prose" :level 2 :conditional? true}]
           (vec (filter :conditional? docs/docs-toc-entries))))))

;; `visible-toc-entries` consults the live registrar for prose
;; workspaces. The JVM corpus exercises it with no registrar →
;; `prose-for-variant` returns empty, so the prose entry should be
;; pruned.

(deftest visible-toc-prunes-prose-when-absent
  (testing "no prose workspace registered → prose entry pruned"
    (let [out (docs/visible-toc-entries :story.fake/variant)]
      (is (not-any? #(= "docs-prose" (:id %)) out))
      (is (= ["docs-args" "docs-decorators" "docs-parameters" "docs-tags"]
             (mapv :id out))))))
