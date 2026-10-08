(ns re-frame.story.ui.element-inspector-cljs-test
  "Tests for the element-level click-to-code inspector. The pure
  `coord->handler-keyword` runs on the JVM and CLJS; the mode toggle, chip,
  overlay and `resolve-source-coord` are CLJS-only. `parse-coord` aliases
  `re-frame.source-coords/parse-source-coord`, whose own tests pin it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            #?@(:cljs [[clojure.string :as str]
                       [re-frame.source-coords :as rf.source-coords]])
            [re-frame.story.ui.element-inspector :as rf.story.ui.element-inspector]))

;; ---- pure (JVM + CLJS) ---------------------------------------------------

(deftest coord->handler-keyword-shape
  (testing "a parsed coord round-trips to the registered view-id keyword"
    (is (= :counter.core/counter-buttons
           (rf.story.ui.element-inspector/coord->handler-keyword
             {:ns "counter.core" :handler-id "counter-buttons"
              :line 47 :col 11}))))
  (testing "a nil coord (a malformed attribute) → nil"
    (is (nil? (rf.story.ui.element-inspector/coord->handler-keyword nil)))))

;; ---- CLJS-only: mode toggle + chip + overlay ----------------------------

#?(:cljs
   (defn reset-inspector! []
     (rf.story.ui.element-inspector/set-active! false)
     (reset! rf.story.ui.element-inspector/state {:active? false :hover nil})))

#?(:cljs
   (use-fixtures :each (fn [t] (reset-inspector!) (t) (reset-inspector!))))

#?(:cljs
   (deftest mode-toggle-flips-active-flag
     (testing "set-active! + toggle! drive the active? predicate"
       (rf.story.ui.element-inspector/set-active! true)
       (is (true? (rf.story.ui.element-inspector/active?)))
       (rf.story.ui.element-inspector/toggle!)
       (is (false? (rf.story.ui.element-inspector/active?))))))

#?(:cljs
   (deftest set-active-false-clears-hover
     (testing "turning the inspector OFF clears any pending hover snapshot so
               a stale outline doesn't survive the toggle"
       (swap! rf.story.ui.element-inspector/state assoc
              :active? true
              :hover {:coord-attr "x:y:1:1"
                      :handler-id :x/y
                      :rect {:top 0 :left 0 :width 10 :height 10}})
       (rf.story.ui.element-inspector/set-active! false)
       (is (nil? (:hover @rf.story.ui.element-inspector/state))))))

#?(:cljs
   (deftest inspect-chip-renders-toggle-state
     (testing "the chip uses `aria-haspopup` + `aria-expanded`, never
               `aria-pressed` — the toolbar reset assertion counts
               [aria-pressed=\"true\"] and this chip must not trip it"
       (let [hiccup (rf.story.ui.element-inspector/inspect-chip)
             props  (second hiccup)]
         (is (= :button (first hiccup)))
         (is (= "story-toolbar-inspect" (:data-test props)))
         (is (= "true" (:aria-haspopup props)))
         (is (= "false" (:aria-expanded props)) "off-state aria-expanded")
         (is (not (contains? props :aria-pressed)))
         (is (fn? (:on-click props)))))
     (testing "aria-expanded flips once inspect mode is on"
       (rf.story.ui.element-inspector/set-active! true)
       (is (= "true" (:aria-expanded (second (rf.story.ui.element-inspector/inspect-chip))))))))

#?(:cljs
   (deftest overlay-renders-nothing-unless-active-and-hovering
     (testing "off, even with a hover snapshot left behind → nil"
       (swap! rf.story.ui.element-inspector/state assoc
              :active? false
              :hover {:coord-attr "counter.core:counter:47:11"
                      :handler-id :counter.core/counter
                      :parsed     {:ns "counter.core" :handler-id "counter"
                                   :line 47 :col 11}
                      :rect       {:top 100 :left 200 :width 300 :height 40}})
       (is (nil? (rf.story.ui.element-inspector/overlay))))
     (testing "active but no hover → nil"
       (swap! rf.story.ui.element-inspector/state dissoc :hover)
       (rf.story.ui.element-inspector/set-active! true)
       (is (nil? (rf.story.ui.element-inspector/overlay))))))

#?(:cljs
   (deftest resolve-source-coord-file-falls-back-to-error-coords
     (testing "with no view meta to read, `:file` comes from the error-coords
               registry; the DOM attribute's line+col beat the meta side"
       (let [view-id :rf.inspector-test/sample-view]
         (rf.source-coords/remember-error-coords!
           :view view-id
           {:ns "rf.inspector-test" :file "src/sample.cljs"
            :line 12 :column 4})
         (try
           (is (= {:file "src/sample.cljs" :line 42 :column 7}
                  (select-keys (rf.story.ui.element-inspector/resolve-source-coord
                                 (rf.story.ui.element-inspector/parse-coord
                                   "rf.inspector-test:sample-view:42:7"))
                               [:file :line :column])))
           (finally
             (rf.source-coords/forget-error-coords!)))))))

#?(:cljs
   (deftest resolve-source-coord-defaults-when-attr-degraded
     (testing "programmatic-registration coords arrive as `?:?` — the resolver
               falls through to the meta-side line/col"
       (let [view-id :rf.inspector-test/degraded]
         (rf.source-coords/remember-error-coords!
           :view view-id
           {:ns "rf.inspector-test" :file "src/d.cljs"
            :line 99 :column 3})
         (try
           (is (= [99 3]
                  ((juxt :line :column)
                   (rf.story.ui.element-inspector/resolve-source-coord
                     (rf.story.ui.element-inspector/parse-coord
                       "rf.inspector-test:degraded:?:?")))))
           (finally
             (rf.source-coords/forget-error-coords!)))))))

#?(:cljs
   (deftest overlay-renders-outline-and-tooltip-when-hovering
     (testing "active + hover snapshot present → outline + tooltip"
       (swap! rf.story.ui.element-inspector/state assoc
              :active? true
              :hover {:coord-attr "counter.core:counter:47:11"
                      :handler-id :counter.core/counter
                      :parsed     {:ns "counter.core"
                                   :handler-id "counter"
                                   :line 47 :col 11}
                      :rect       {:top 100 :left 200
                                   :width 300 :height 40}})
       (let [root (rf.story.ui.element-inspector/overlay)]
         (is (= :div (first root)))
         (is (= "story-element-inspector-overlay"
                (:data-test (second root))))
         (is (= 4 (count root)) "root + props + outline + tooltip")
         (let [outline (nth root 2)
               tooltip (nth root 3)]
           (is (= :div (first outline)))
           (is (= "story-element-inspector-tooltip"
                  (:data-test (second tooltip))))
           (is (= ":counter.core/counter"
                  (:data-handler-id (second tooltip))))
           (is (str/includes? (nth tooltip 2) "47")
               "the tooltip names the line so the user can check before clicking"))))))
