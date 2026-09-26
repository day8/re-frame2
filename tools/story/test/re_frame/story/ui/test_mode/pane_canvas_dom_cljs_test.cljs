(ns re-frame.story.ui.test-mode.pane-canvas-dom-cljs-test
  "DOM-mount regression: the Tests pane renders the variant's framed canvas
  inside itself, so a variant whose script drives the DOM runs against the
  mounted view — and its Re-run keeps that view and that frame.

  Two symptoms, both measured through the pane as the shell mounts it:

  - The pane ran the variant headless while no view was mounted, so a
    `[:click …]` step either refused as `:cannot-run` or, given a runner
    with `:dom`, failed to find its element. Here the pane holds the shell's
    framed canvas, the canvas's run selects `:dom`, and the click reaches
    the view's button.
  - The pane's Re-run destroyed the canvas's frame and re-ran the play
    headless, leaving the canvas on a failed play. Here the Re-run runs
    through the canvas's one run owner, the view stays mounted inside the
    pane, and the canvas stamps the Re-run's own passing verdict.

  `re-frame.story.ui.test-mode.one-run-owner-cljs-test` pins the runner and
  frame ownership on the node lane.

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` build mounts
  real DOM; `:node-test` also loads it, where the body self-gates on
  `(browser?)` and records a visible skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.story :as rf.story]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.runtime :as rf.story.runtime]
            [re-frame.story.ui.canvas :as rf.story.ui.canvas]
            [re-frame.story.ui.shell :as rf.story.ui.shell]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.ui.test-mode.state :as rf.story.ui.test-mode.state]
            [re-frame.story.ui.test-mode.view :as rf.story.ui.test-mode.view]
            [re-frame.subs :as rf.subs]))

(def ^:private framed-canvas @#'rf.story.ui.shell/framed-canvas)
(def ^:private ensure-variant-frame! @#'rf.story.ui.shell/ensure-variant-frame!)
(def ^:private canvas-last-run-key @#'rf.story.ui.canvas/canvas-last-run-key)

;; ---- fixture --------------------------------------------------------------

(defn- reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.adapter.reagent/adapter)
       (catch :default _ nil))
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.runtime/reset-run-owner!)
  (reset! canvas-last-run-key nil)
  (rf.story.ui.canvas/reset-first-rendered!)
  (reset! rf.story.ui.test-mode.state/results-atom {})
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- helpers ----------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- poll-until
  "Call `(f value)` once `(probe)` returns a truthy value, or with nil once
  `timeout-ms` has passed. Polls on `setTimeout`, so Reagent's render queue
  gets to run between probes."
  [probe timeout-ms f]
  (let [deadline (+ (.now js/Date) timeout-ms)]
    (letfn [(poll []
              (let [v (probe)]
                (cond
                  v                           (f v)
                  (> (.now js/Date) deadline) (f nil)
                  :else                       (js/setTimeout poll 25))))]
      (poll))))

(defn- slot-result [vid]
  (get-in @rf.story.ui.test-mode.state/results-atom [vid :result]))

(defn- reg-dom-variant!
  "A variant whose script clicks the view's button and then reads the
  count the click produced off the DOM."
  [vid]
  (rf/reg-event :o9jja-dom/seed (fn [{:keys [db]} _] {:db (assoc db :n 0)}))
  (rf/reg-event :o9jja-dom/inc (fn [{:keys [db]} _] {:db (update db :n inc)}))
  (rf/reg-sub :o9jja-dom/n (fn [db _] (:n db)))
  (rf/reg-view* :views/o9jja-dom-probe
    (fn [_]
      ;; The click handler runs outside render, so it dispatches to the
      ;; frame captured at render time — the variant's.
      (let [frame-id (rf/current-frame-id)]
        [:div
         [:button {:data-test "o9jja-inc"
                   :type      "button"
                   :on-click  #(rf/dispatch [:o9jja-dom/inc] {:frame frame-id})}
          "inc"]
         [:span {:data-test "o9jja-n"} (str "n=" @(rf/subscribe [:o9jja-dom/n]))]])))
  (rf.story/reg-variant vid
    {:component :views/o9jja-dom-probe
     :setup     [[:o9jja-dom/seed]]
     :script    [[:click "[data-test=\"o9jja-inc\"]"]
                 [:assert-dom "[data-test=\"o9jja-n\"]" :text "n=1"]]}))

(defn- dom-run-ok?
  "The pane's result shows the DOM step ran against the view: the run
  selected `:dom`, refused nothing, and passed."
  [result label]
  (is (= :dom (:runner result)) (str label ": the run selected :dom"))
  (is (empty? (:cannot-run result)) (str label ": no step or assertion was refused"))
  (is (= :pass (:status result)) (str label ": the click reached the view and the count read n=1")))

;; ---- the regression ---------------------------------------------------------

(deftest the-pane-runs-a-dom-variant-against-its-own-canvas
  (testing "the pane holds the framed canvas; the canvas's run and the pane's
            Re-run both drive the mounted view"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (let [vid :story.o9jja-dom/click]
        (reg-dom-variant! vid)
        (async done
          (let [node   (js/document.createElement "div")
                _      (js/document.body.appendChild node)
                root   (rdc/create-root node)
                finish (fn []
                         (try (.unmount root) (catch :default _ nil))
                         (.remove node)
                         (done))
                pane-q (fn [sel] (.querySelector node (str "[data-test=\"story-test-view\"] " sel)))]
            ;; Select the variant and prepare its run, as the shell's
            ;; selection edge does, then mount the pane as the shell's
            ;; main pane renders it on the :test tab.
            (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant vid)
            (ensure-variant-frame! vid)
            (react-dom/flushSync
              (fn [] (rdc/render root [rf.story.ui.test-mode.view/test-view vid
                                       [framed-canvas]])))
            (is (some? (pane-q "[data-test=\"story-canvas-frame\"]"))
                "the pane renders the framed canvas inside itself")
            (poll-until
              #(slot-result vid)
              10000
              (fn [first-result]
                (is (some? first-result) "the canvas's run reached the pane")
                (when first-result
                  (dom-run-ok? first-result "the canvas's run"))
                (.click (pane-q "[data-test=\"story-test-rerun\"]"))
                (poll-until
                  #(let [r (slot-result vid)]
                     (when-not (identical? r first-result) r))
                  10000
                  (fn [rerun-result]
                    (is (some? rerun-result) "the Re-run reached the pane")
                    (when rerun-result
                      (dom-run-ok? rerun-result "the Re-run"))
                    (is (some? (pane-q "[data-test=\"o9jja-n\"]"))
                        "the view is still mounted inside the pane after the Re-run")
                    (poll-until
                      #(some-> (pane-q "[data-run-status]") (.getAttribute "data-run-status"))
                      3000
                      (fn [stamp]
                        (is (= "pass" stamp)
                            "the canvas reads the Re-run's own verdict, not a failed play")
                        (finish)))))))))))))
