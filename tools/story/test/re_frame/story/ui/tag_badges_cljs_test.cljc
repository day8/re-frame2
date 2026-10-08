(ns re-frame.story.ui.tag-badges-cljs-test
  "Tests for the sidebar tag-as-badge affordance on variant rows
  (Storybook 9 badges-addon parity per spec/005 §v1.1).

  Every test here is CLJS-only: `re-frame.story.ui.sidebar` is a `.cljs`
  file the JVM cannot `:require`. The CLJS node-test build runs them; the
  JVM runner loads the namespace and finds no tests in it."
  #?(:cljs
     (:require [clojure.test :refer [deftest is testing use-fixtures]]
               [re-frame.story :as rf.story]
               [re-frame.story.ui.state :as rf.story.ui.state]
               [re-frame.story.ui.sidebar :as rf.story.ui.sidebar]
               [re-frame.test-helpers :as rf.test-helpers])))

;; ---- fixtures ------------------------------------------------------------

#?(:cljs
   (defn reset-all! []
     (rf.story/clear-all!)
     (rf.story.ui.state/reset-shell-state!)
     (rf.story/install-canonical-vocabulary!)))

#?(:cljs
   (use-fixtures :each (fn [t] (reset-all!) (t))))

;; ---- pure: tag → style-key projection -----------------------------------

#?(:cljs
   (deftest tag-badge-style-mapping-canonical-seven
     (testing "each of the seven canonical tags maps to its own style key"
       (is (= [:tag-badge-dev :tag-badge-docs :tag-badge-test :tag-badge-screenshot
               :tag-badge-experimental :tag-badge-internal :tag-badge-agent]
              (mapv rf.story.ui.sidebar/tag->badge-style-key
                    [:dev :docs :test :screenshot :experimental :internal :agent]))))))

;; ---- rendered hiccup -----------------------------------------------------

#?(:cljs
   (deftest tag-badges-renders-nothing-when-no-tags
     (testing "no `:tags` → no badge container, so the row layout stays tight"
       (is (nil? (rf.story.ui.sidebar/tag-badges nil))))))

#?(:cljs
   (deftest tag-badges-multiple-tags-includes-canonical-and-unknown
     (testing "canonical and unknown tags alike render as badges, in
               `name`-sorted order"
       (let [tree (rf.story.ui.sidebar/tag-badges #{:wip :dev :test})]
         (is (some? (rf.test-helpers/find-by-attr tree :data-test "story-sidebar-tag-badges")))
         (is (= ["dev" "test" "wip"]
                (map #(get (second %) :data-tag)
                     (rf.test-helpers/find-all-by-attr tree :data-test "story-sidebar-tag-badge"))))))))

#?(:cljs
   (deftest tag-badge-names-its-tag-in-text-and-title
     (testing "a badge's visible text is the tag's `name` and its `title`
               the full keyword (spec/014 §Rendering contract)"
       (let [[badge] (rf.test-helpers/find-all-by-attr (rf.story.ui.sidebar/tag-badges #{:test})
                                                       :data-test "story-sidebar-tag-badge")]
         (is (= ["test" ":test"] [(nth badge 2) (:title (second badge))]))))))
