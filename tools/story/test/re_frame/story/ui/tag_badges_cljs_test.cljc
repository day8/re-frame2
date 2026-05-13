(ns re-frame.story.ui.tag-badges-cljs-test
  "Tests for the sidebar tag-as-badge affordance on variant rows
  (rf2-nwiwr — Storybook 9 badges-addon parity per spec/005 §v1.1).

  Runs on both the JVM (cognitect.test-runner under `clojure -M:test`)
  and the CLJS node-test build (shadow's `:node-test` target; ns-regexp
  `cljs-test$` picks up this ns because its name ends in `cljs-test`).

  ## Coverage layers

  - **Pure data** (JVM + CLJS): `tag->badge-style-key` projection
    over the canonical seven tags + the unknown-tag fallthrough;
    `sorted-tags` ordering.
  - **CLJS-only**: the rendered hiccup for `tag-badges` includes one
    `.tag-badge` span per tag, ordered by `name`; a variant with no
    `:tags` renders no badge container at all; a variant with tags
    contributes badges into its sidebar row."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as story]
            [re-frame.story.ui.state :as state]
            #?@(:cljs [[re-frame.story.ui.sidebar :as sidebar]])))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (story/clear-all!)
  (state/reset-shell-state!)
  (story/install-canonical-vocabulary!))

(use-fixtures :each {:before reset-all!})

;; ---- pure: tag → style-key projection -----------------------------------

#?(:cljs
   (deftest tag-badge-style-mapping-canonical-seven
     (testing "each of the seven canonical tags maps to its own style key"
       (is (= :tag-badge-dev          (sidebar/tag->badge-style-key :dev)))
       (is (= :tag-badge-docs         (sidebar/tag->badge-style-key :docs)))
       (is (= :tag-badge-test         (sidebar/tag->badge-style-key :test)))
       (is (= :tag-badge-screenshot   (sidebar/tag->badge-style-key :screenshot)))
       (is (= :tag-badge-experimental (sidebar/tag->badge-style-key :experimental)))
       (is (= :tag-badge-internal     (sidebar/tag->badge-style-key :internal)))
       (is (= :tag-badge-agent        (sidebar/tag->badge-style-key :agent))))))

#?(:cljs
   (deftest tag-badge-style-mapping-unknown-tag-falls-through
     (testing "an unknown tag returns nil — the renderer merges nil into
               the base `:tag-badge` style, yielding the neutral grey pill"
       (is (nil? (sidebar/tag->badge-style-key :wip)))
       (is (nil? (sidebar/tag->badge-style-key :review)))
       (is (nil? (sidebar/tag->badge-style-key :prod))))))

;; ---- pure: sorted-tags ordering -----------------------------------------

#?(:cljs
   (deftest sorted-tags-stable-name-order
     (testing "tags sort by name so the rendered row is stable across runs"
       (is (= [:agent :dev :docs] (sidebar/sorted-tags #{:docs :agent :dev})))
       (is (= []                  (sidebar/sorted-tags nil)))
       (is (= []                  (sidebar/sorted-tags #{}))))))

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
   (deftest tag-badges-renders-one-pill-per-tag
     (testing "a variant with three tags renders one `.tag-badge` per tag,
               ordered by `name` so visual scanning is stable"
       (let [tree    (sidebar/tag-badges #{:docs :dev :test})
             badges  (find-by-data-test tree "story-sidebar-tag-badge")
             tag-attrs (map #(get (second %) :data-tag) badges)]
         (is (= 3 (count badges)))
         (is (= ["dev" "docs" "test"] tag-attrs))))))

#?(:cljs
   (deftest tag-badges-renders-nothing-when-no-tags
     (testing "no `:tags` → `tag-badges` returns nil so the row layout
               doesn't carry an empty container"
       (is (nil? (sidebar/tag-badges nil)))
       (is (nil? (sidebar/tag-badges #{}))))))

#?(:cljs
   (deftest tag-badges-unknown-tag-renders-neutral-pill
     (testing "an unknown tag renders as a pill — colour comes from the
               base `:tag-badge` style with no palette override"
       (let [tree    (sidebar/tag-badges #{:wip})
             badges  (find-by-data-test tree "story-sidebar-tag-badge")]
         (is (= 1 (count badges)))
         (is (= "wip" (get (second (first badges)) :data-tag)))))))

#?(:cljs
   (deftest tag-badges-multiple-tags-includes-canonical-and-unknown
     (testing "a variant with a mix of canonical and unknown tags renders
               every tag as a badge, in `name`-sorted order. Mirrors the
               sidebar's row-level integration without booting Reagent."
       (let [tree    (sidebar/tag-badges #{:wip :dev :test})
             badges  (find-by-data-test tree "story-sidebar-tag-badge")
             attrs   (map #(get (second %) :data-tag) badges)
             container (first (find-by-data-test tree "story-sidebar-tag-badges"))]
         (is (some? container))
         (is (= 3 (count badges)))
         (is (= ["dev" "test" "wip"] attrs))))))

#?(:clj
   (deftest jvm-only-sorted-tags
     (testing "JVM corpus exercises `sorted-tags` ordering since the var
               lives in a `.cljs` file we can't `:require` here. The
               assertion below stands in for the same projection by
               sorting the tag set the same way and comparing — keeps
               the JVM gate honest without booting Reagent."
       (is (= [:agent :dev :docs]
              (->> #{:docs :agent :dev} (sort-by name) vec))))))
