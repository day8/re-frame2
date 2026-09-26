(ns re-frame.story.ui.test-mode.one-run-owner-cljs-test
  "The Tests pane and the canvas share the variant's one run owner.

  - The canvas's run carries `:runner :auto`, whichever of its preparers
    goes first — the canvas itself or the shell's selection edge — so a
    variant with a DOM step selects a runner that has `:dom`, instead of
    the `:headless` default refusing the step for a capability it lacks.
  - The pane's Re-run re-prepares the canvas's frame IN PLACE through that
    owner: the frame is never destroyed, the run is a fresh generation of
    the same owner, and it selects the same runner as the canvas's run.
  - The pane stores every settled run of the variant it shows, whoever
    started it, and leaves the runs of other variants alone.

  Node lane: no DOM, so the DOM step itself still refuses here (`no DOM`);
  what is pinned is the runner each run SELECTS and who owns the frame.
  `re-frame.story.ui.test-mode.pane-canvas-dom-cljs-test` runs the same
  variant shape in a real DOM, through the pane.

  Pure `.cljs`: the `async` tests need cljs.test MAP fixtures, which a
  `.cljc` may not use (`re-frame.story.meta-fixtures-test`)."
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.story :as rf.story]
            [re-frame.story.frames :as rf.story.frames]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.runtime :as rf.story.runtime]
            [re-frame.story.ui.canvas :as rf.story.ui.canvas]
            [re-frame.story.ui.shell :as rf.story.ui.shell]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.ui.test-mode.state :as rf.story.ui.test-mode.state]))

(def ^:private run-if-needed! @#'rf.story.ui.canvas/run-if-needed!)
(def ^:private canvas-last-run-key @#'rf.story.ui.canvas/canvas-last-run-key)
(def ^:private ensure-variant-frame! @#'rf.story.ui.shell/ensure-variant-frame!)

(defn- reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.adapter.reagent/adapter)
       (catch :default _ nil))
  (rf.story.loaders/clear-watchers!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf.story.runtime/reset-run-owner!)
  (rf.story.ui.canvas/reset-first-rendered!)
  (reset! canvas-last-run-key nil)
  (reset! rf.story.ui.test-mode.state/results-atom {})
  (rf.story.ui.test-mode.state/show-variant! nil))

(use-fixtures :each {:before reset-all!})

(defn- reg-dom-variant!
  "A variant whose script clicks an element, so it requires `:dom`."
  [vid]
  (rf/reg-event :o9jja/boot (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
  (rf.story/reg-variant vid
    {:setup  [[:o9jja/boot]]
     :script [[:click "[data-test=\"o9jja-button\"]"]
              [:assert [:rf.assert/path-equals [:n] 1]]]}))

(defn- canvas-run!
  "Run `vid` the way the canvas does, under its run-key. Returns the
  resume's promise."
  [vid]
  (run-if-needed! vid (rf.story.ui.canvas/run-key (rf.story.ui.state/get-state) vid)))

(defn- capability-refusals
  "The run's refusals for a capability the selected runner lacks."
  [result]
  (filterv #(= :runner-lacks-capability (:reason %)) (:cannot-run result)))

(defn- fail! [done]
  (fn [e]
    (is false (str "a run rejected: " e))
    (done)))

(deftest the-canvas-run-selects-a-runner-with-dom
  (async done
    (reg-dom-variant! :story.o9jja/canvas)
    (-> (canvas-run! :story.o9jja/canvas)
        (.then (fn [result]
                 (is (contains? (:required-runner result) :dom)
                     "precondition: the click step requires :dom")
                 (is (= :dom (:runner result))
                     "the canvas's run selects :dom, the cheapest runner covering its steps")
                 (is (empty? (capability-refusals result))
                     "no step is refused for a capability the runner lacks")
                 (done)))
        (.catch (fail! done)))))

(deftest the-selection-edge-prepare-carries-the-same-runner
  (testing "the shell's selection edge may prepare the canvas's run before
            the canvas does; the canvas's prepare then dedupes onto it, so
            the selection edge must pass the canvas's opts too"
    (async done
      (let [vid :story.o9jja/selected]
        (reg-dom-variant! vid)
        (ensure-variant-frame! vid)
        (let [gen (rf.story.runtime/current-generation vid)
              p   (canvas-run! vid)]
          (is (= gen (rf.story.runtime/current-generation vid))
              "precondition: the canvas's prepare deduped onto the selection edge's")
          (-> p
              (.then (fn [result]
                       (is (= :dom (:runner result))
                           "the run the selection edge prepared selects :dom")
                       (is (empty? (capability-refusals result)))
                       (done)))
              (.catch (fail! done))))))))

(deftest the-pane-re-run-keeps-the-canvas-frame
  (async done
    (let [vid       :story.o9jja/rerun
          destroyed (atom 0)
          destroy!  rf.story.frames/destroy!]
      (reg-dom-variant! vid)
      (-> (canvas-run! vid)
          (.then (fn [_]
                   (let [old-gen (rf.story.runtime/current-generation vid)]
                     (with-redefs [rf.story.frames/destroy! (fn [id]
                                                              (swap! destroyed inc)
                                                              (destroy! id))]
                       (let [rerun (rf.story.ui.test-mode.state/run-variant-pane! vid)]
                         (is (zero? @destroyed)
                             "the Re-run never destroys the frame the canvas renders")
                         (is (> (rf.story.runtime/current-generation vid) old-gen)
                             "the Re-run is a fresh generation of the canvas's run owner")
                         rerun)))))
          (.then (fn [result]
                   (is (= :dom (:runner result))
                       "the Re-run selects the runner the canvas's run selects")
                   (is (empty? (capability-refusals result)))
                   (is (= result (get-in @rf.story.ui.test-mode.state/results-atom [vid :result]))
                       "the pane stored the Re-run's result")
                   (is (false? (get-in @rf.story.ui.test-mode.state/results-atom [vid :running?])))
                   (done)))
          (.catch (fail! done))))))

(deftest the-pane-follows-the-runs-of-the-variant-it-shows
  (async done
    (let [shown :story.o9jja/shown
          other :story.o9jja/other]
      (reg-dom-variant! shown)
      (reg-dom-variant! other)
      (rf.story.ui.test-mode.state/show-variant! shown)
      (-> (canvas-run! shown)
          (.then (fn [result]
                   (is (= result (get-in @rf.story.ui.test-mode.state/results-atom [shown :result]))
                       "the canvas's own run of the shown variant lands in the pane")
                   (canvas-run! other)))
          (.then (fn [result]
                   (is (some? result) "precondition: the other variant ran")
                   (is (nil? (get @rf.story.ui.test-mode.state/results-atom other))
                       "a run of a variant the pane does not show leaves the pane alone")
                   (done)))
          (.catch (fail! done))))))
