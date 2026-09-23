(ns re-frame.story.ui.docs-toc-test
  "JVM-portable regression net for the docs-mode TOC table (rf2-8c7tk).

  Surface covered:

  - `docs-toc-entries`     — canonical table shape
  - `visible-toc-entries`  — prose-conditional pruning vs always-on

  CLJS-side (IntersectionObserver wiring + scroll-into-view + reactive
  re-render) lives in `docs_toc_cljs_test.cljs` — this corpus pins the
  pure projection only."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.registrar :as rf.registrar]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.ui.docs :as rf.story.ui.docs]))

(deftest toc-table-shape
  (testing "canonical entry list (rf2-ba86n.14 — status + view-arg schema +
            evidence sections added)"
    (let [ids (mapv :id rf.story.ui.docs/docs-toc-entries)]
      (is (= ["docs-status" "docs-prose" "docs-args" "docs-schema"
              "docs-decorators" "docs-parameters" "docs-evidence" "docs-tags"]
             ids))))
  (testing "every entry carries the required slots"
    (doseq [entry rf.story.ui.docs/docs-toc-entries]
      (is (some? (:id entry)))
      (is (some? (:label entry)))
      (is (integer? (:level entry))))))

(deftest conditional-entries
  (testing "the conditional entries are status / prose / view-arg schema
            (rf2-ba86n.14); args / decorators / parameters / evidence / tags
            are unconditional"
    (is (= #{"docs-status" "docs-prose" "docs-schema"}
           (into #{} (map :id) (filter :conditional? rf.story.ui.docs/docs-toc-entries))))))

;; `visible-toc-entries` consults the live registrar for prose workspaces +
;; compiles the variant's plan for the status / view-arg-schema conditionals.
;; The JVM corpus exercises it with no registry → `prose-for-variant` returns
;; empty AND `variant-plan-quietly` returns nil (unknown variant), so the
;; prose / status / schema entries are all pruned.

(deftest visible-toc-prunes-conditionals-when-absent
  (testing "no prose workspace + uncompilable plan → prose / status / schema
            entries pruned; the unconditional entries remain"
    (let [out (rf.story.ui.docs/visible-toc-entries :story.fake/variant)]
      (is (not-any? #(#{"docs-prose" "docs-status" "docs-schema"} (:id %)) out))
      (is (= ["docs-args" "docs-decorators" "docs-parameters"
              "docs-evidence" "docs-tags"]
             (mapv :id out))))))

(deftest docs-plan-compiles-a-variant-that-leaves-props-to-its-story
  (testing "rf2-3x7nj.28.3: the flagship authoring pattern — required props on
            the story's :args, a variant overriding one — compiles for Docs
            the way it does for a run, so the status and schema sections show"
    (rf.story.registrar/clear-all!)
    (rf.registrar/clear-kind! :view)
    (try
      (rf.registrar/register! :view :views/docs-button
                              {:rf/props   [:map [:label :string]
                                            [:variant [:enum :primary :danger]]
                                            [:size [:int {:min 8 :max 64}]]]
                               :handler-fn (fn [_] nil)})
      (rf.story.registrar/reg-story* :story.docs.button
                                     {:component :views/docs-button
                                      :args      {:label "Go" :variant :primary :size 16}})
      (rf.story.registrar/reg-variant* :story.docs.button/danger {:args {:variant :danger}})
      (let [plan (rf.story.ui.docs/variant-plan-quietly :story.docs.button/danger)
            ids  (set (map :id (rf.story.ui.docs/visible-toc-entries
                                 :story.docs.button/danger)))]
        (is (= {:label "Go" :variant :danger :size 16}
               (get-in plan [:world :effective-args]))
            "the plan compiled over the story's args with the variant's override")
        (is (contains? ids "docs-status"))
        (is (contains? ids "docs-schema")))
      (finally
        (rf.story.registrar/clear-all!)
        (rf.registrar/clear-kind! :view)))))
