(ns re-frame.story.theme.status-vocab-cljs-test
  "The shared status vocabulary (spec/018 §12.6): nine statuses, each
  distinguishable by colour, glyph, shape and label, and the sidebar
  regions derived from it. The `#?(:cljs …)` tests reach `.cljs`
  sidebar and shell namespaces the JVM cannot load."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [re-frame.story.theme.status :as rf.story.theme.status]
            #?@(:cljs [[re-frame.story.ui.sidebar :as rf.story.ui.sidebar]
                       [re-frame.story.ui.sidebar-styles :refer [styles]]
                       [re-frame.story.ui.shell :as rf.story.ui.shell]
                       [re-frame.story.ui.state :as rf.story.ui.state]])))

(def expected-statuses
  #{:pending :running :pass :fail :error :cannot-run :blocked :dirty :redacted})

(deftest descriptors-match-the-documented-nine
  (is (= expected-statuses (set (keys rf.story.theme.status/descriptors))))
  (is (= expected-statuses (set rf.story.theme.status/order)))
  (is (= 9 (count rf.story.theme.status/order))))

(deftest every-descriptor-carries-all-four-discriminators
  (doseq [s expected-statuses
          :let [d (rf.story.theme.status/descriptor s)]]
    (is (every? #(and (string? (d %)) (not (str/blank? (d %))))
                [:fg :bg :border :glyph :label])
        (str s))
    (is (contains? #{:solid :outline :dashed :ring :half} (:shape d)) (str s))))

(deftest glyphs-are-distinct
  (let [glyphs (map #(:glyph (rf.story.theme.status/descriptor %)) expected-statuses)]
    (is (apply distinct? glyphs) (str "duplicate glyph in " (vec glyphs)))))

(deftest chip-style-shape-channel-discriminates
  (let [cs rf.story.theme.status/chip-style]
    (is (= {:background (rf.story.theme.status/bg :pass)
            :color      (rf.story.theme.status/fg :pass)}
           (cs :pass)))
    (is (str/starts-with? (:border (cs :error)) "1px solid"))
    (is (str/includes? (:border (cs :redacted)) "dashed"))
    (is (str/includes? (:border (cs :pending)) "double"))
    (is (contains? (cs :running) :border-left))
    (is (not (contains? (cs :running) :border)))))

(deftest rollup-surfaces-worst-member
  (is (= :fail    (rf.story.theme.status/rollup [:pass :pass :fail :pass])))
  (is (= :error   (rf.story.theme.status/rollup [:pass :fail :error :pending])))
  (is (= :pending (rf.story.theme.status/rollup []))))

(deftest unknown-status-degrades-to-pending
  (is (= (rf.story.theme.status/descriptor :pending) (rf.story.theme.status/descriptor :bogus)))
  (is (= (rf.story.theme.status/descriptor :pending) (rf.story.theme.status/descriptor nil))))

#?(:cljs
   (deftest sidebar-signal-status-derives-from-chip-style
     (doseq [[stat style-key] rf.story.ui.sidebar/status-signal->style-key]
       (is (= (rf.story.theme.status/chip-style stat) (get styles style-key))
           (str style-key " drifted from (rf.story.theme.status/chip-style " stat ")")))))

#?(:cljs
   (deftest sidebar-status-style-key-covers-all-nine
     (is (= expected-statuses (set (keys rf.story.ui.sidebar/status-signal->style-key))))))

#?(:cljs
   (deftest sidebar-status-dots-derive-from-fg
     (is (= {:background (rf.story.theme.status/fg :pass)} (rf.story.ui.sidebar/dot-style :pass)))
     (is (= {:background (rf.story.theme.status/fg :fail)} (rf.story.ui.sidebar/dot-style :fail)))
     (is (= (rf.story.theme.status/fg :running) (:background (rf.story.ui.sidebar/dot-style :running))))
     (is (= (str "1px solid " (:border (rf.story.theme.status/descriptor :cannot-run)))
            (:border (rf.story.ui.sidebar/dot-style :cannot-run))))
     (is (not= (rf.story.ui.sidebar/dot-style :pending) (rf.story.ui.sidebar/dot-style :cannot-run)))))

#?(:cljs
   (defn- walk-find-data-test
     "Collect every hiccup node whose props carry `:data-test` = `tag`."
     [tree tag]
     (let [hits (transient [])]
       (letfn [(walk [node]
                 (cond
                   (and (vector? node) (map? (second node))
                        (= tag (get (second node) :data-test)))
                   (do (conj! hits node) (doseq [c (drop 2 node)] (walk c)))
                   (vector? node) (doseq [c (rest node)] (walk c))
                   (seq? node)    (doseq [c node] (walk c))
                   :else nil))]
         (walk tree))
       (persistent! hits))))

#?(:cljs
   (deftest sidebar-status-chip-renders-the-glyph
     (let [tree (rf.story.ui.sidebar/signal-chips
                  {:args {:n 1} :network {[:get "/x"] {}}
                   :script [[:click "#go"]] :frame-binding :attached}
                  :fail)
           [[_ glyph-props glyph] :as glyphs] (walk-find-data-test tree "story-sidebar-signal-glyph")
           [_ chip-props & children] (first (walk-find-data-test tree "story-sidebar-signal-chip"))]
       (is (= 1 (count glyphs)) "only the status chip carries a glyph")
       (is (= (rf.story.theme.status/glyph :fail) glyph))
       (is (= "true" (:aria-hidden glyph-props)))
       (is (= "fail" (:data-value chip-props)))
       (is (str/includes? (:title chip-props) "status"))
       (is (some string? children) "the text label survives beside the glyph"))))

#?(:cljs
   (deftest story-canvas-empty-hook-renders
     (rf.story.ui.state/reset-shell-state!)
     (is (= 1 (count (walk-find-data-test (#'rf.story.ui.shell/main-pane) "story-canvas-empty"))))))
