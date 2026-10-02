(ns re-frame.story.ui.tag-badges-cljs-test
  "Tests for the sidebar tag-as-badge affordance on variant rows
  (Storybook 9 badges-addon parity per spec/005 §v1.1).

  Every test here is CLJS-only: `re-frame.story.ui.sidebar` is a `.cljs`
  file the JVM cannot `:require`. The CLJS node-test build (shadow's
  `:node-test` target; ns-regexp `cljs-test$`) runs them; the JVM runner
  loads the namespace and finds no tests in it.

  ## Coverage layers

  - **Pure data**: `tag->badge-style-key` projection over the canonical
    seven tags + the unknown-tag fallthrough; `sorted-tags` ordering.
  - **Rendered hiccup**: `tag-badges` renders one `.tag-badge` span per
    tag, canonical and unknown alike, ordered by `name`; a variant with
    no `:tags` renders no badge container at all."
  #?(:cljs
     (:require [clojure.test :refer [deftest is testing use-fixtures]]
               [re-frame.story :as rf.story]
               [re-frame.story.ui.state :as rf.story.ui.state]
               [re-frame.story.ui.sidebar :as rf.story.ui.sidebar])))

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
       (is (= :tag-badge-dev          (rf.story.ui.sidebar/tag->badge-style-key :dev)))
       (is (= :tag-badge-docs         (rf.story.ui.sidebar/tag->badge-style-key :docs)))
       (is (= :tag-badge-test         (rf.story.ui.sidebar/tag->badge-style-key :test)))
       (is (= :tag-badge-screenshot   (rf.story.ui.sidebar/tag->badge-style-key :screenshot)))
       (is (= :tag-badge-experimental (rf.story.ui.sidebar/tag->badge-style-key :experimental)))
       (is (= :tag-badge-internal     (rf.story.ui.sidebar/tag->badge-style-key :internal)))
       (is (= :tag-badge-agent        (rf.story.ui.sidebar/tag->badge-style-key :agent))))))

#?(:cljs
   (deftest tag-badge-style-mapping-unknown-tag-falls-through
     (testing "an unknown tag returns nil — the renderer merges nil into
               the base `:tag-badge` style, yielding the neutral grey pill"
       (is (nil? (rf.story.ui.sidebar/tag->badge-style-key :wip)))
       (is (nil? (rf.story.ui.sidebar/tag->badge-style-key :review)))
       (is (nil? (rf.story.ui.sidebar/tag->badge-style-key :prod))))))

;; ---- pure: sorted-tags ordering -----------------------------------------

#?(:cljs
   (deftest sorted-tags-stable-name-order
     (testing "tags sort by name so the rendered row is stable across runs"
       (is (= [:agent :dev :docs] (rf.story.ui.sidebar/sorted-tags #{:docs :agent :dev})))
       (is (= []                  (rf.story.ui.sidebar/sorted-tags nil)))
       (is (= []                  (rf.story.ui.sidebar/sorted-tags #{}))))))

;; ---- CLJS-only: rendered hiccup -----------------------------------------

#?(:cljs
   (defn- find-by-data-test
     "Walk a hiccup tree and return every element whose props map has
     `:data-test` equal to `tag`. Mirrors the helper in
     `test_widget_cljs_test.cljc` so each test ns stays self-contained."
     [tree tag]
     (let [hits (transient [])]
       (letfn [(walk [node]
                 (cond
                   (and (vector? node)
                        (map? (second node))
                        (= tag (get (second node) :data-test)))
                   (do (conj! hits node)
                       (doseq [c (drop 2 node)] (walk c)))

                   (vector? node)
                   (doseq [c (rest node)] (walk c))

                   (seq? node)
                   (doseq [c node] (walk c))

                   :else nil))]
         (walk tree))
       (persistent! hits))))

#?(:cljs
   (deftest tag-badges-renders-nothing-when-no-tags
     (testing "no `:tags` → `tag-badges` returns nil so the row layout
               doesn't carry an empty container"
       (is (nil? (rf.story.ui.sidebar/tag-badges nil)))
       (is (nil? (rf.story.ui.sidebar/tag-badges #{}))))))

#?(:cljs
   (deftest tag-badges-multiple-tags-includes-canonical-and-unknown
     (testing "a variant with a mix of canonical and unknown tags renders
               every tag as a badge, in `name`-sorted order. Mirrors the
               sidebar's row-level integration without booting Reagent."
       (let [tree    (rf.story.ui.sidebar/tag-badges #{:wip :dev :test})
             badges  (find-by-data-test tree "story-sidebar-tag-badge")
             attrs   (map #(get (second %) :data-tag) badges)
             container (first (find-by-data-test tree "story-sidebar-tag-badges"))]
         (is (some? container))
         (is (= 3 (count badges)))
         (is (= ["dev" "test" "wip"] attrs))))))

#?(:cljs
   (deftest tag-badge-names-its-tag-in-text-and-title
     (testing "a badge's visible text is the tag's `name` and its `title`
               the full keyword (spec/014 §Rendering contract)"
       (let [[badge] (find-by-data-test (rf.story.ui.sidebar/tag-badges #{:test})
                                        "story-sidebar-tag-badge")]
         (is (= ["test" ":test"] [(nth badge 2) (:title (second badge))]))))))
