(ns re-frame.story.ui.test-mode.stepper-view-cljs-test
  "CLJS render-shape tests for the play step-debugger view (rf2-ulw5m +
  spec/009 §Play step-debugger).

  Renders the stepper-section component with synthetic local state and
  asserts the hiccup tree carries the documented `data-test` selectors,
  the correct controls for each state, and the disabled-button rules.
  No reagent mounting — we deref the component fn directly."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.story.ui.test-mode.stepper-state :as st]
            [re-frame.story.ui.test-mode.stepper-view  :as sv]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-results! [] (reset! st/results-atom {}))

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
  (sv/render-section variant-id (get @st/results-atom variant-id)))

;; ---- inactive state ------------------------------------------------------

(deftest renders-section-with-data-test
  (testing "the section root carries data-test='story-stepper-section'"
    (let [tree    (render :story.x/inactive)
          section (first (find-by-data-test tree "story-stepper-section"))]
      (is (some? section) "section is present")
      (is (= "false" (get (second section) :data-active))
          "data-active='false' when no slot exists"))))

(deftest inactive-shows-start-and-hint
  (testing "inactive state renders Start button + the inactive hint"
    (let [tree     (render :story.x/inactive2)
          start    (first (find-by-data-test tree "story-stepper-start"))
          hint     (first (find-by-data-test tree "story-stepper-inactive"))
          step-btn (first (find-by-data-test tree "story-stepper-step"))]
      (is (some? start)    "Start button is present")
      (is (some? hint)     "inactive hint is present")
      (is (nil?  step-btn) "Step button is NOT present in inactive state"))))

;; ---- active state -------------------------------------------------------

(defn- seed-slot!
  [variant-id slot]
  (swap! st/results-atom assoc variant-id slot))

(deftest active-shows-all-controls
  (testing "active state renders step / step-back / rewind / play /
            stop controls + the step list"
    (let [vid  :story.x/active]
      (seed-slot! vid
                  {:variant-id    vid
                   :active?       true
                   :auto-playing? false
                   :cursor        1
                   :total         3
                   :play-events   [[:e/a] [:e/b] [:e/c]]
                   :statuses      [{:index 0 :label ":e/a" :position :done
                                    :outcome :event :breakpoint? false}
                                   {:index 1 :label ":e/b" :position :current
                                    :outcome nil   :breakpoint? false}
                                   {:index 2 :label ":e/c" :position :pending
                                    :outcome nil   :breakpoint? false}]
                   :breakpoints   #{}
                   :epoch-stack   [:epoch/seed :epoch/a]
                   :interval-id   nil
                   :tick-ms       100})
      (let [tree     (render vid)
            stop     (first (find-by-data-test tree "story-stepper-stop"))
            step     (first (find-by-data-test tree "story-stepper-step"))
            back     (first (find-by-data-test tree "story-stepper-step-back"))
            resume   (first (find-by-data-test tree "story-stepper-resume"))
            rewind   (first (find-by-data-test tree "story-stepper-rewind"))
            rows     (find-by-data-test tree "story-stepper-row")
            progress (first (find-by-data-test tree "story-stepper-progress"))]
        (is (some? stop)     "Stop button replaces Start when active")
        (is (some? step)     "Step button is present")
        (is (some? back)     "Step-back button is present")
        (is (some? resume)   "Play button is present (auto-playing? false)")
        (is (some? rewind)   "Rewind button is present")
        (is (some? progress) "progress label is present")
        (is (= 3 (count rows)) "one row per step")))))

(deftest pause-button-when-auto-playing
  (testing "when :auto-playing? is true the Pause button replaces Play"
    (let [vid :story.x/playing]
      (seed-slot! vid
                  {:variant-id    vid
                   :active?       true
                   :auto-playing? true
                   :cursor        1
                   :total         3
                   :play-events   [[:e/a] [:e/b] [:e/c]]
                   :statuses      [{:index 0 :label ":e/a" :position :done
                                    :outcome :event :breakpoint? false}
                                   {:index 1 :label ":e/b" :position :current
                                    :outcome nil   :breakpoint? false}
                                   {:index 2 :label ":e/c" :position :pending
                                    :outcome nil   :breakpoint? false}]
                   :breakpoints   #{}
                   :epoch-stack   [:epoch/seed :epoch/a]
                   :interval-id   42
                   :tick-ms       100})
      (let [tree   (render vid)
            pause  (first (find-by-data-test tree "story-stepper-pause"))
            resume (first (find-by-data-test tree "story-stepper-resume"))]
        (is (some? pause) "Pause button is present")
        (is (nil?  resume) "Play button is NOT present while auto-playing")))))

(deftest step-button-disabled-at-end
  (testing "Step button renders disabled when cursor = total"
    (let [vid :story.x/at-end]
      (seed-slot! vid
                  {:variant-id    vid
                   :active?       true
                   :auto-playing? false
                   :cursor        2
                   :total         2
                   :play-events   [[:e/a] [:e/b]]
                   :statuses      [{:index 0 :label ":e/a" :position :done
                                    :outcome :event :breakpoint? false}
                                   {:index 1 :label ":e/b" :position :done
                                    :outcome :event :breakpoint? false}]
                   :breakpoints   #{}
                   :epoch-stack   [:epoch/seed :epoch/a :epoch/b]
                   :interval-id   nil
                   :tick-ms       100})
      (let [tree (render vid)
            step (first (find-by-data-test tree "story-stepper-step"))]
        (is (some? step) "Step button is rendered")
        (is (true? (get (second step) :disabled))
            "the Step button is disabled at the end of the sequence")))))

(deftest step-back-disabled-at-start
  (testing "Step-back button renders disabled at cursor=0"
    (let [vid :story.x/at-start]
      (seed-slot! vid
                  {:variant-id    vid
                   :active?       true
                   :auto-playing? false
                   :cursor        0
                   :total         2
                   :play-events   [[:e/a] [:e/b]]
                   :statuses      [{:index 0 :label ":e/a" :position :current
                                    :outcome nil :breakpoint? false}
                                   {:index 1 :label ":e/b" :position :pending
                                    :outcome nil :breakpoint? false}]
                   :breakpoints   #{}
                   :epoch-stack   [:epoch/seed]
                   :interval-id   nil
                   :tick-ms       100})
      (let [tree (render vid)
            back (first (find-by-data-test tree "story-stepper-step-back"))]
        (is (true? (get (second back) :disabled))
            "the Step-back button is disabled at step 0")))))

;; ---- breakpoint affordance ----------------------------------------------

(deftest breakpoint-chip-aria-pressed
  (testing "each row carries a BP toggle chip; aria-pressed reflects
            whether the index is in :breakpoints"
    (let [vid :story.x/bp]
      (seed-slot! vid
                  {:variant-id    vid
                   :active?       true
                   :auto-playing? false
                   :cursor        0
                   :total         2
                   :play-events   [[:e/a] [:e/b]]
                   :statuses      [{:index 0 :label ":e/a" :position :current
                                    :outcome nil :breakpoint? false}
                                   {:index 1 :label ":e/b" :position :pending
                                    :outcome nil :breakpoint? true}]
                   :breakpoints   #{1}
                   :epoch-stack   [:epoch/seed]
                   :interval-id   nil
                   :tick-ms       100})
      (let [tree  (render vid)
            chips (find-by-data-test tree "story-stepper-bp-toggle")]
        (is (= 2 (count chips)) "one chip per row")
        (is (= "false" (get (second (first chips)) :aria-pressed)))
        (is (= "true"  (get (second (second chips)) :aria-pressed)))))))
