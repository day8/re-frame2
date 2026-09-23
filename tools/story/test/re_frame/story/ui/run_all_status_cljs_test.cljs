(ns re-frame.story.ui.run-all-status-cljs-test
  "rf2-3x7nj.28.1 — the sidebar's Run all and watch mode record the run's
  `:status`, as the Tests pane does.

  Both write a variant's dot through `record-test-run`, where an explicit
  run `:status` wins and a missing one is derived from the assertion counts.
  The Tests pane's `store-result!` threads the status; the sidebar's
  `run-one-test!` — behind Run all and `watch-rerun!` — did not. So a run
  the agreement floor fails while every assertion that ran passes (here a
  thrown fx after the `:db` commits) recorded a green `:pass` dot on Run
  all and a red one on the pane's Re-run.

  Drives the real `run-one-test!` and reads the dot it records; the JVM
  replica of its pipeline is `re-frame.story-ui-test`
  §`run-all-records-the-run-level-status`.

  Pure `.cljs`: the `async` tests need cljs.test MAP fixtures, which a
  `.cljc` may not use (`re-frame.story.meta-fixtures-test`)."
  (:require [cljs.test :refer [async deftest is use-fixtures]]
            [re-frame.core :as rf]
            ;; Loaded for its epoch tape, which carries the fx's error row the
            ;; run's floor reads.
            [re-frame.epoch]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.story :as rf.story]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.ui.sidebar :as rf.story.ui.sidebar]
            [re-frame.story.ui.state :as rf.story.ui.state]))

(def ^:private run-one-test! @#'rf.story.ui.sidebar/run-one-test!)

(defn- reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.adapter.reagent/adapter)
       (catch :default _ nil))
  (rf.story.loaders/clear-watchers!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

(defn- reg-variants! []
  (rf/reg-fx :story.rstat/boom
    (fn [_ _] (throw (ex-info "rf2-3x7nj.28.1 probe fx" {}))))
  (rf/reg-fx :story.rstat/quiet (fn [_ _] nil))
  (rf/reg-event :story.rstat/set-n
    (fn [{:keys [db]} [_ fx-id]] {:db (assoc db :n 1) :fx [[fx-id true]]}))
  ;; The db commits and the one assertion passes; the fx throws after it.
  (rf.story/reg-variant :story.rstat/floor
    {:tags   #{:test}
     :script [[:dispatch-sync [:story.rstat/set-n :story.rstat/boom]]
              [:assert [:rf.assert/path-equals [:n] 1]]]})
  ;; Control: the same shape with an fx that does not throw.
  (rf.story/reg-variant :story.rstat/clean
    {:tags   #{:test}
     :script [[:dispatch-sync [:story.rstat/set-n :story.rstat/quiet]]
              [:assert [:rf.assert/path-equals [:n] 1]]]}))

(deftest run-all-records-the-run-level-status
  (async done
    (reg-variants!)
    (-> (js/Promise.all #js [(run-one-test! :story.rstat/floor)
                             (run-one-test! :story.rstat/clean)])
        (.then (fn [_]
                 (let [runs (get-in (rf.story.ui.state/get-state) [:tests :runs])]
                   (is (= [1 0] ((juxt :passed :failed) (:story.rstat/floor runs)))
                       "precondition: every assertion that ran passed, so the counts alone read green")
                   (is (= :fail (get-in runs [:story.rstat/floor :status]))
                       "the dot takes the run's verdict — the thrown fx fails it")
                   (is (= :pass (get-in runs [:story.rstat/clean :status]))
                       "control: the quiet fx's run is green"))
                 (done)))
        (.catch (fn [e]
                  (is false (str "a Run-all run rejected: " e))
                  (done))))))
