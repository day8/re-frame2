(ns re-frame.story.ui.test-mode.stepper-view-cljs-test
  "CLJS render-shape tests for the play step-debugger view (spec/009
  §Play step-debugger).

  Renders the stepper-section component with synthetic local state and
  asserts the hiccup tree carries the documented `data-test` selectors,
  the correct controls for each state, and the disabled-button rules.
  No reagent mounting — we deref the component fn directly."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.story.ui.test-mode.stepper-state :as rf.story.ui.test-mode.stepper-state]
            [re-frame.story.ui.test-mode.stepper-view  :as rf.story.ui.test-mode.stepper-view]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-results! [] (reset! rf.story.ui.test-mode.stepper-state/results-atom {}))

(use-fixtures :each {:before reset-results! :after reset-results!})

;; ---- hiccup walking helper ----------------------------------------------

(defn- find-by-data-test
  "Walk a hiccup tree and return every element whose props carry
  `:data-test` equal to `tag`. The stepper view emits Reagent forms that
  use both keyword tags and component fn references; we accept either."
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
    (persistent! hits)))

(defn- render
  "Use the extracted pure `render-section` fn (variant-id + slot value
  → hiccup). Bypasses Reagent's class lifecycle so the hiccup is
  directly inspectable."
  [variant-id]
  (rf.story.ui.test-mode.stepper-view/render-section variant-id (get @rf.story.ui.test-mode.stepper-state/results-atom variant-id)))

;; ---- inactive state ------------------------------------------------------

(deftest inactive-shows-start-and-hint
  (let [tree (render :story.x/inactive)]
    (is (= "false" (get (second (first (find-by-data-test tree "story-stepper-section")))
                        :data-active)))
    (is (some? (first (find-by-data-test tree "story-stepper-start"))))
    (is (re-find #"no :script has no steps"
                 (last (first (find-by-data-test tree "story-stepper-inactive"))))
        "the hint does not promise a :script to a variant without one")
    (is (nil? (first (find-by-data-test tree "story-stepper-step")))
        "no Step button while inactive")))

;; ---- active state -------------------------------------------------------

(defn- seed-slot!
  [variant-id slot]
  (swap! rf.story.ui.test-mode.stepper-state/results-atom assoc variant-id slot))

(deftest active-shows-all-controls
  (let [vid :story.x/active]
    (seed-slot! vid {:active? true :auto-playing? false :cursor 1 :total 3})
    (let [tree (render vid)]
      (is (some? (first (find-by-data-test tree "story-stepper-stop")))
          "Stop replaces Start when active")
      (is (some? (first (find-by-data-test tree "story-stepper-resume")))
          "Play shows while not auto-playing")
      (is (some? (first (find-by-data-test tree "story-stepper-rewind"))))
      (is (some? (first (find-by-data-test tree "story-stepper-progress")))))))

(deftest pause-button-when-auto-playing
  (let [vid :story.x/playing]
    (seed-slot! vid {:active? true :auto-playing? true :cursor 1 :total 3})
    (let [tree (render vid)]
      (is (some? (first (find-by-data-test tree "story-stepper-pause"))))
      (is (nil? (first (find-by-data-test tree "story-stepper-resume")))
          "Pause replaces Play while auto-playing"))))

(deftest step-button-disabled-at-end
  (let [vid :story.x/at-end]
    (seed-slot! vid {:active? true :auto-playing? false :cursor 2 :total 2})
    (is (true? (get (second (first (find-by-data-test (render vid) "story-stepper-step")))
                    :disabled)))))

(deftest step-back-disabled-at-start
  (let [vid :story.x/at-start]
    (seed-slot! vid {:active? true :auto-playing? false :cursor 0 :total 2})
    (is (true? (get (second (first (find-by-data-test (render vid) "story-stepper-step-back")))
                    :disabled)))))

;; ---- breakpoint affordance ----------------------------------------------

(deftest breakpoint-chip-aria-pressed
  (let [vid :story.x/bp]
    (seed-slot! vid {:active?  true
                     :statuses [{:index 0 :position :current :breakpoint? false}
                                {:index 1 :position :pending :breakpoint? true}]})
    (is (= ["false" "true"]
           (mapv #(get (second %) :aria-pressed)
                 (find-by-data-test (render vid) "story-stepper-bp-toggle")))
        "one chip per row, aria-pressed tracking the row's breakpoint")))
