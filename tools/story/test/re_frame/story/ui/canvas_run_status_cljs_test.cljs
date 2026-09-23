(ns re-frame.story.ui.canvas-run-status-cljs-test
  "rf2-mc87a: the canvas records the settled verdict of its own run, and
  stamps it on the canvas section as `data-run-status`, for every play shape.

  The local visual-review recipe (docs/story/08) settled each capture on the
  play-status chip reaching a terminal status. A declarative variant
  (`:assertions` / `:checks`, no play) never renders that chip, and a play
  that does not auto-run leaves it `idle`, so the recipe waited out its
  timeout on both. The unified run result's `:status` settles for all of
  them, which is what the canvas now records.

  rf2-oovoq: the stamp names the run in view by run-key AND generation, so
  returning to a variant under an identical run-key does not show the
  previous visit's verdict while the fresh generation is in flight.

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

(deftest a-return-to-the-same-run-key-is-unstamped-until-its-fresh-run-settles
  ;; rf2-oovoq: A settles, B runs, then A is selected again with the same
  ;; modes, overrides and tick, so under the SAME run-key. The canvas stays
  ;; mounted, so A's previous entry is still recorded, while the revisit
  ;; claims a fresh generation and resets A's frame. The previous verdict
  ;; must not stand for that run.
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
                   (let [old-gen (rf.story.runtime/current-generation a)]
                     ;; Reselecting A: the shell's selection edge prepares A
                     ;; (`ensure-variant-frame!`) before the canvas re-renders,
                     ;; with the same opts the canvas's own prepare passes.
                     (rf.story.runtime/prepare-run! a {:active-modes   (:active-modes ka)
                                                       :cell-overrides (:cell-overrides ka)
                                                       :substrate      (:substrate ka)
                                                       :run-key        ka})
                     (is (> (rf.story.runtime/current-generation a) old-gen)
                         "precondition: reselecting A claimed a fresh generation")
                     (is (nil? (stamp a ka))
                         "no stamp in the render between the reselection and the canvas's run")
                     (let [[_ k p] (canvas-run! a)]
                       (is (= ka k)
                           "precondition: the revisit runs under the identical run-key")
                       (is (some? p)
                           "precondition: the canvas run claimed the fresh generation")
                       (is (nil? (get @run-settled a))
                           "starting the fresh run drops the previous verdict")
                       (is (nil? (stamp a ka))
                           "no stamp while the fresh generation is in flight")
                       p))))
          (.then (fn [result]
                   (is (= :pass (:status result))
                       "precondition: the fresh generation settled pass")
                   (is (= "pass" (stamp a ka))
                       "the fresh generation's verdict is stamped once it settles")
                   (done)))
          (.catch (fn [e]
                    (is false (str "a canvas run rejected: " e))
                    (done)))))))

;; rf2-iwl02: every author-triggered run goes through `runtime/rerun!`, which
;; re-prepares the variant in place under the SAME run-key, so the canvas sees
;; no key change and runs nothing itself. Only the run's starter — the play
;; chip — held its promise, so the canvas never heard the verdict and its
;; stamp vanished instead of following the new run. Setup reads `rerun-n`, so
;; a flip between runs makes the re-run's verdict differ from the first.

(def ^:private rerun-n (atom 1))

(deftest a-rerun-from-the-play-chip-is-stamped-when-it-settles
  (async done
    (reset! rerun-n 1)
    (rf/reg-event :iwl02/boot (fn [{:keys [db]} _] {:db (assoc db :n @rerun-n)}))
    (rf.story/reg-variant :story.iwl02/rerun
      {:setup  [[:iwl02/boot]]
       :script [[:assert [:rf.assert/path-equals [:n] 1]]]})
    (let [vid       :story.iwl02/rerun
          [_ k p]   (canvas-run! vid)]
      (-> p
          (.then (fn [_]
                   (is (= "pass" (stamp vid k))
                       "precondition: the canvas's own run settled and is stamped")
                   (reset! rerun-n 2)
                   (let [old-gen (rf.story.runtime/current-generation vid)
                         ;; What the play chip's Re-run does.
                         rerun   (rf.story.runtime/rerun! vid {:play nil})]
                     (is (> (rf.story.runtime/current-generation vid) old-gen)
                         "precondition: the Re-run claimed a fresh generation")
                     (is (nil? (get @run-settled vid))
                         "the previous verdict is dropped as the Re-run starts, which
                          re-renders the section without the stamp")
                     (is (nil? (stamp vid k))
                         "no stamp while the Re-run is in flight")
                     rerun)))
          (.then (fn [result]
                   (is (= :fail (:status result))
                       "precondition: the Re-run settled fail on its flipped setup")
                   (is (= "fail" (stamp vid k))
                       "the canvas stamps the Re-run's verdict, under the canvas's run-key")
                   (done)))
          (.catch (fn [e]
                    (is false (str "a run rejected: " e))
                    (done)))))))

(deftest the-section-carries-the-status-only-for-its-own-run
  (testing "rf2-mc87a / rf2-oovoq: `data-run-status` stamps the settled verdict of the run in view"
    (let [rk      {:variant-id :story.mc87a/declarative :hot-reload-tick 0}
          newer   (assoc rk :hot-reload-tick 1)
          snap    {:content-hash "abc123"}]
      (is (= "pass" (:data-run-status (section-props rk snap {:run-key rk :generation 1 :status :pass} 1))))
      (is (= "fail" (:data-run-status (section-props rk snap {:run-key rk :generation 1 :status :fail} 1))))
      (is (nil? (:data-run-status (section-props rk snap nil 1)))
          "no stamp while the run is in flight")
      (is (nil? (:data-run-status (section-props newer snap {:run-key rk :generation 1 :status :pass} 1)))
          "a previous run-key's verdict never stands for the run in flight")
      (is (nil? (:data-run-status (section-props rk snap {:run-key rk :generation 1 :status :pass} 2)))
          "a previous generation's verdict never stands for a fresh run under the same run-key")
      (is (= ":story.mc87a/declarative" (:data-test-variant (section-props rk snap nil 1)))
          "the existing test hooks are unchanged")
      (is (= "abc123" (:data-snapshot-hash (section-props rk snap nil 1))))
      (is (nil? (:data-run-status (section-props {:variant-id nil} nil {:run-key {:variant-id nil} :generation 0 :status :pass} 0)))
          "no variant selected, no stamp"))))
