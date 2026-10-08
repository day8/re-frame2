(ns re-frame.story.ui.canvas-run-status-cljs-test
  "The canvas records the settled verdict of its own run and stamps it on
  the canvas section as `data-run-status`, for every play shape — the
  local visual-review recipe (docs/story/08) settles each capture on it.
  The stamp names the run in view by run-key AND generation, so a return
  to a variant under an identical run-key does not show the previous
  visit's verdict while the fresh generation is in flight.

  Runs go through the canvas's own `run-if-needed!`, so no `with-redefs`
  routes around the code under test. Pure `.cljs`: the `async` tests need
  cljs.test MAP fixtures, which a `.cljc` may not use."
  (:require [cljs.test :refer [async deftest is use-fixtures]]
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

(defn- stamp
  "The `data-run-status` the canvas section renders for `variant-id` under
  run-key `k`, read the way the canvas render reads it."
  [variant-id k]
  (:data-run-status
    (section-props k nil (get @run-settled variant-id)
                   (rf.story.runtime/current-generation variant-id))))

(deftest the-settled-verdict-is-recorded-for-every-play-shape
  (async done
    (reg-variants!)
    (let [runs (mapv canvas-run! [:story.mc87a/scripted
                                  :story.mc87a/declarative
                                  :story.mc87a/manual-play
                                  :story.mc87a/declarative-fail])]
      (-> (js/Promise.all (into-array (map (fn [[_ _ p]] p) runs)))
          (.then (fn [_]
                   (is (= {:story.mc87a/scripted         :pass
                           :story.mc87a/declarative      :pass
                           :story.mc87a/manual-play      :pass
                           :story.mc87a/declarative-fail :fail}
                          (statuses @run-settled))
                       "a play that auto-runs, a declarative variant, a play that does not auto-run, and a failing control")
                   nil))
          (.catch (fn [e]
                    (is false (str "a canvas run rejected: " e))
                    nil))
          (.then (fn [_] (done)))))))

(deftest a-superseded-run-records-nothing
  (async done
    (reg-variants!)
    (let [[vid _ p] (canvas-run! :story.mc87a/declarative)]
      ;; A newer prepare claims a fresh generation before the first run's
      ;; settlement reaches the canvas.
      (rf.story.runtime/prepare-run! vid {:run-key ::newer})
      (-> p
          (.then (fn [result]
                   (is (:superseded? result)
                       "precondition: the runtime settled the first run as superseded")
                   (is (nil? (get @run-settled vid))
                       "the superseded run's verdict is not recorded")
                   nil))
          (.catch (fn [e]
                    (is false (str "the canvas run rejected: " e))
                    nil))
          (.then (fn [_] (done)))))))

(deftest a-return-to-the-same-run-key-is-unstamped-until-its-fresh-run-settles
  ;; A settles, B runs, then A is selected again under the SAME run-key with
  ;; the canvas still mounted. A's previous verdict must not stand for the
  ;; fresh run.
  (async done
    (reg-variants!)
    (let [a         :story.mc87a/scripted
          [_ ka pa] (canvas-run! a)]
      (-> pa
          (.then (fn [_]
                   (is (= "pass" (stamp a ka))
                       "precondition: A's first run settled and is stamped")
                   (nth (canvas-run! :story.mc87a/declarative) 2)))
          (.then (fn [_]
                   ;; Reselecting A: the shell's selection edge prepares A
                   ;; (`ensure-variant-frame!`) before the canvas re-renders.
                   (rf.story.runtime/prepare-run! a {:active-modes   (:active-modes ka)
                                                     :cell-overrides (:cell-overrides ka)
                                                     :substrate      (:substrate ka)
                                                     :run-key        ka})
                   (is (nil? (stamp a ka))
                       "no stamp in the render between the reselection and the canvas's run")
                   (let [p (nth (canvas-run! a) 2)]
                     (is (nil? (get @run-settled a))
                         "starting the fresh run drops the previous verdict")
                     p)))
          (.then (fn [_]
                   (is (= "pass" (stamp a ka))
                       "the fresh generation's verdict is stamped once it settles")
                   nil))
          (.catch (fn [e]
                    (is false (str "a canvas run rejected: " e))
                    nil))
          (.then (fn [_] (done)))))))

(deftest a-rerun-is-stamped-with-one-execution
  ;; `runtime/rerun!` re-prepares in place under the SAME run-key, so the
  ;; canvas starts no run of its own and hears the verdict only through
  ;; `listen-runs!`. Setup reads `n`, so the Re-run's verdict differs from
  ;; the first run's; setup runs once per prepare, so `boots` counts runs.
  (async done
    (let [n     (atom 1)
          boots (atom 0)
          vid   :story.iftxj/rerun]
      (rf/reg-event :iftxj/boot (fn [{:keys [db]} _]
                                  (swap! boots inc)
                                  {:db (assoc db :n @n)}))
      (rf.story/reg-variant vid
        {:setup  [[:iftxj/boot]]
         :script [[:assert [:rf.assert/path-equals [:n] 1]]]})
      (let [[_ k p] (canvas-run! vid)]
        (-> p
            (.then (fn [_]
                     (reset! n 2)
                     (let [rerun (rf.story.runtime/rerun! vid {:play nil})]
                       ;; The canvas's own lifecycle call after a Re-run.
                       (run-if-needed! vid k)
                       rerun)))
            (.then (fn [_]
                     (is (= "fail" (stamp vid k)) "the Re-run's verdict is stamped")
                     (is (= 2 @boots) "one execution for each run, none duplicated")
                     nil))
            (.catch (fn [e]
                      (is false (str "a run rejected: " e))
                      nil))
            (.then (fn [_] (done))))))))

(deftest the-section-carries-the-status-only-for-its-own-run
  (let [rk      {:variant-id :story.mc87a/declarative :hot-reload-tick 0}
        settled {:run-key rk :generation 1 :status :pass}]
    (is (= {:data-test-variant  ":story.mc87a/declarative"
            :data-snapshot-hash "abc123"
            :data-run-status    "pass"}
           (select-keys (section-props rk {:content-hash "abc123"} settled 1)
                        [:data-test-variant :data-snapshot-hash :data-run-status])))
    (is (nil? (:data-run-status (section-props (assoc rk :hot-reload-tick 1) nil settled 1)))
        "a previous run-key's verdict never stands for the run in flight")
    (is (nil? (:data-run-status (section-props rk nil settled 2)))
        "a previous generation's verdict never stands for a fresh run under the same run-key")))
