(ns re-frame.story.ui.canvas-unrelated-write-dom-cljs-test
  "DOM-mount regression: a shell-state write that cannot change the
  variant's run must not re-render the canvas.

  The canvas's outer render reads the `run-key` through `r/track` and hands
  it to `canvas-inner`, so only a change to a run input (selection,
  hot-reload tick, modes, cell overrides, substrate) reaches either render.
  Counted at the two seams each render crosses exactly once:
  `rf.story.runtime/snapshot-identity` (outer) and
  `rf.story.render/resolve-render-sub-overrides` (inner). The hot-reload
  tick is the control — a run input, so it MUST still reach both, which
  keeps the `= 0` assertions from passing on a spy the render never calls.

  The probe is an events-only variant selected BEFORE the canvas mounts,
  so the first commit also witnesses that `canvas-inner` ensures the frame
  during render: without it `frame-provider` fails loud on the absent frame
  and the precondition below never paints.

  Ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` build mounts
  real DOM; `:node-test` also loads it, where the body self-gates on
  `(browser?)` and records a visible skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.story :as rf.story]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.render :as rf.story.render]
            [re-frame.story.runtime :as rf.story.runtime]
            [re-frame.story.ui.canvas :as rf.story.ui.canvas]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.subs :as rf.subs]))

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
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf.story.ui.canvas/reset-first-rendered!))

(use-fixtures :each {:before reset-all!})

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (let [node (js/document.createElement "div")]
    (js/document.body.appendChild node)
    node))

(defn- write-and-flush! [f & args]
  (react-dom/flushSync
    (fn []
      (apply rf.story.ui.state/swap-state! f args)
      (r/flush))))

(deftest unrelated-shell-writes-do-not-re-render-the-canvas
  (testing "rail-width, panel-visibility and tag-filter writes
            leave the canvas alone; a hot-reload tick still re-renders it"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (let [variant-id :story.ohc5/probe]
        (rf/reg-event :ohc5/seed (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
        (rf/reg-sub :ohc5/n (fn [db _] (:n db)))
        (rf/reg-view* :views/ohc5-probe
          (fn [_] [:div {:data-test "ohc5-probe"} (str "n=" @(rf/subscribe [:ohc5/n]))]))
        (rf.story/reg-variant variant-id
          {:component :views/ohc5-probe
           :setup     [[:ohc5/seed 3]]})
        ;; Pre-select BEFORE mount: no selection-watcher edge allocates the
        ;; frame, so the canvas's first render has to.
        (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant variant-id)
        (let [mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)
              inner      (atom 0)
              outer      (atom 0)
              resolve    rf.story.render/resolve-render-sub-overrides
              snapshot   rf.story.runtime/snapshot-identity]
          (try
            (react-dom/flushSync
              (fn [] (rdc/render root [rf.story.ui.canvas/canvas])))
            (is (= "n=3" (some-> (.querySelector mount-node "[data-test=\"ohc5-probe\"]")
                                 .-textContent))
                "the pre-selected events-only variant painted its seeded state on the first commit")
            ;; Fixed arities, not `[& args]`: the canvas calls both fns at a
            ;; known arity, which compiles to a direct-arity dispatch that a
            ;; variadic stand-in does not answer — the render would throw and
            ;; every count would read a vacuous 0.
            (with-redefs [rf.story.render/resolve-render-sub-overrides
                          (fn [a b] (swap! inner inc) (resolve a b))
                          rf.story.runtime/snapshot-identity
                          (fn ([a] (swap! outer inc) (snapshot a))
                              ([a b] (swap! outer inc) (snapshot a b)))]
              (write-and-flush! assoc :rail-widths {:left 300 :right 320})
              (write-and-flush! rf.story.ui.state/toggle-panel :controls)
              (write-and-flush! rf.story.ui.state/toggle-tag-filter :ohc5-tag)
              (is (= 0 @outer) "no unrelated write re-rendered the outer canvas")
              (is (= 0 @inner) "no unrelated write re-rendered canvas-inner")
              ;; Control: the tick IS a run input.
              (write-and-flush! rf.story.ui.state/bump-hot-reload-tick)
              (is (pos? @outer) "control: a hot-reload tick re-renders the outer canvas")
              (is (pos? @inner) "control: a hot-reload tick re-renders canvas-inner"))
            (finally
              (try (.unmount root) (catch :default _ nil)))))))))
