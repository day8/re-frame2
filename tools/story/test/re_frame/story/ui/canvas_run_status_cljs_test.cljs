(ns re-frame.story.ui.canvas-run-status-cljs-test
  "rf2-mc87a: the canvas records the settled verdict of its own run, and
  stamps it on the canvas section as `data-run-status`, for every play shape.

  The local visual-review recipe (docs/story/08) settled each capture on the
  play-status chip reaching a terminal status. A declarative variant
  (`:assertions` / `:checks`, no play) never renders that chip, and a play
  that does not auto-run leaves it `idle`, so the recipe waited out its
  timeout on both. The unified run result's `:status` settles for all of
  them, which is what the canvas now records.

  The runs here go through the canvas's own `run-if-needed!` — the same
  prepare + resume the canvas performs post-commit — so no `with-redefs`
  routes around the code under test. Assertions chain off the promise it
  returns rather than counting microtask turns.

  Pure `.cljs`: the `async` tests need cljs.test MAP fixtures, which a
  `.cljc` may not use (`re-frame.story.meta-fixtures-test`)."
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.story :as rf.story]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.runtime :as rf.story.runtime]
            [re-frame.story.ui.canvas :as rf.story.ui.canvas]
            [re-frame.story.ui.state :as rf.story.ui.state]))

(def ^:private run-if-needed! @#'rf.story.ui.canvas/run-if-needed!)
(def ^:private section-props @#'rf.story.ui.canvas/section-props)
(def ^:private canvas-last-run-key @#'rf.story.ui.canvas/canvas-last-run-key)
(def ^:private run-settled @#'rf.story.ui.canvas/run-settled)

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
  (reset! run-settled {}))

(use-fixtures :each {:before reset-all!})

(defn- reg-variants! []
  (rf/reg-event :mc87a/boot (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
  ;; A play that auto-runs.
  (rf.story/reg-variant :story.mc87a/scripted
    {:setup  [[:mc87a/boot]]
     :script [[:assert [:rf.assert/path-equals [:n] 1]]]})
  ;; Declarative: terminal assertions, no play.
  (rf.story/reg-variant :story.mc87a/declarative
    {:setup      [[:mc87a/boot]]
     :assertions [[:rf.assert/path-equals [:n] 1]]})
  ;; A play that does not auto-run.
  (rf.story/reg-variant :story.mc87a/manual-play
    {:setup  [[:mc87a/boot]]
     :script {:script    [[:assert [:rf.assert/path-equals [:n] 1]]]
              :auto-run? false}})
  ;; Control: the recorded status is the verdict, not a constant.
  (rf.story/reg-variant :story.mc87a/declarative-fail
    {:setup      [[:mc87a/boot]]
     :assertions [[:rf.assert/path-equals [:n] 2]]}))

(defn- canvas-run!
  "Run `variant-id` the way the canvas does, under its run-key. Returns
  `[variant-id run-key promise]`."
  [variant-id]
  (let [k (rf.story.ui.canvas/run-key (rf.story.ui.state/get-state) variant-id)]
    [variant-id k (run-if-needed! variant-id k)]))

(defn- statuses [settled]
  (into {} (map (fn [[vid entry]] [vid (:status entry)])) settled))

(deftest the-settled-verdict-is-recorded-for-every-play-shape
  (async done
    (reg-variants!)
    (let [runs (mapv canvas-run! [:story.mc87a/scripted
                                  :story.mc87a/declarative
                                  :story.mc87a/manual-play
                                  :story.mc87a/declarative-fail])]
      (is (every? (fn [[_ _ p]] (some? p)) runs)
          "each canvas run claimed its generation and returned its promise")
      (is (= {} @run-settled)
          "nothing is recorded before a run settles")
      (-> (js/Promise.all (into-array (map (fn [[_ _ p]] p) runs)))
          (.then (fn [_]
                   (is (= {:story.mc87a/scripted         :pass
                           :story.mc87a/declarative      :pass
                           :story.mc87a/manual-play      :pass
                           :story.mc87a/declarative-fail :fail}
                          (statuses @run-settled))
                       "a play that auto-runs, a declarative variant, a play that does not auto-run, and a failing control")
                   (doseq [[vid k] runs]
                     (is (= k (get-in @run-settled [vid :run-key]))
                         (str vid " is recorded under the run-key it ran for")))
                   (done)))
          (.catch (fn [e]
                    (is false (str "a canvas run rejected: " e))
                    (done)))))))

(deftest a-superseded-run-records-nothing
  (async done
    (reg-variants!)
    (let [[vid _ p] (canvas-run! :story.mc87a/declarative)]
      ;; A newer prepare claims a fresh generation before the first run's
      ;; settlement reaches the canvas. Without the generation guard the
      ;; superseded result (`:cannot-run`) would be recorded.
      (rf.story.runtime/prepare-run! vid {:run-key ::newer})
      (-> p
          (.then (fn [result]
                   (is (:superseded? result)
                       "precondition: the runtime settled the first run as superseded")
                   (is (nil? (get @run-settled vid))
                       "the superseded run's verdict is not recorded")
                   (done)))
          (.catch (fn [e]
                    (is false (str "the canvas run rejected: " e))
                    (done)))))))

(deftest the-section-carries-the-status-only-for-its-own-run-key
  (testing "rf2-mc87a: `data-run-status` stamps the settled verdict of the run in view"
    (let [rk      {:variant-id :story.mc87a/declarative :hot-reload-tick 0}
          newer   (assoc rk :hot-reload-tick 1)
          snap    {:content-hash "abc123"}]
      (is (= "pass" (:data-run-status (section-props rk snap {:run-key rk :status :pass}))))
      (is (= "fail" (:data-run-status (section-props rk snap {:run-key rk :status :fail}))))
      (is (nil? (:data-run-status (section-props rk snap nil)))
          "no stamp while the run is in flight")
      (is (nil? (:data-run-status (section-props newer snap {:run-key rk :status :pass})))
          "a previous run-key's verdict never stands for the run in flight")
      (is (= ":story.mc87a/declarative" (:data-test-variant (section-props rk snap nil)))
          "the existing test hooks are unchanged")
      (is (= "abc123" (:data-snapshot-hash (section-props rk snap nil))))
      (is (nil? (:data-run-status (section-props {:variant-id nil} nil {:run-key {:variant-id nil} :status :pass})))
          "no variant selected, no stamp"))))
