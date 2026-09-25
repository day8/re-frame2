(ns day8.re-frame2-xray.panels.epoch.root-lifecycle-source-form-cljs-test
  "The Epoch cascade renders a machine root's own `:entry` / `:exit` row from
  the root's own co-located metadata: its source body is the inline fn's
  code the macro keys under the root's `:source-code`, and its source link
  is the root's `:source-coords`.

  Every row comes from the producer: a literal machine is registered through
  the macro, runs, and its traces for one dispatch project through
  `proj/machine-cascade-rows`. The view's `cascade-row-source-form` and
  `cascade-row-coord` are the two lookups `cascade-row-view` renders the row
  from. The link assertion places a coord on the registered root so it does
  not depend on which `:file` the reader positions carry."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]
            [day8.re-frame2-xray.panels.epoch.view :as view]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- cascade-of
  "Dispatch `event` synchronously and return the machine cascade rows the
  producer's traces for that one dispatch project to."
  [event]
  (let [evs (atom [])]
    (rf.trace.tooling/register-listener! ::capture (fn [ev] (swap! evs conj ev)))
    (try
      (rf/dispatch-sync event)
      (finally
        (rf.trace.tooling/unregister-listener! ::capture)))
    (proj/machine-cascade-rows @evs)))

(defn- action-row
  "The `:action` cascade row whose action is `f`."
  [rows f]
  (first (filter #(and (= :action (:kind %)) (identical? f (:action-id %))) rows)))

(deftest root-lifecycle-rows-render-the-root-source
  (rf/reg-machine :rlsf/flat
    {:initial :a
     :entry   (fn [_] {:data {:at :root-in}})
     :exit    (fn [_] {:data {:at :root-out}})
     :states  {:a    {:on {:fin :done}}
               :done {:final? true}}})
  (let [machine-meta (rf/handler-meta {:source :store :kind :event :id :rlsf/flat})
        spec         (:rf/machine machine-meta)
        placed       (assoc-in machine-meta [:rf/machine :source-coords]
                               {:file "placed/root.cljs" :line 7 :column 3})
        entry        (action-row (cascade-of [:rlsf/flat [:rf.machine/start]]) (:entry spec))
        exit         (action-row (cascade-of [:rlsf/flat [:fin]]) (:exit spec))]
    (is (some? entry) "the start ran the root's :entry")
    (is (some? exit) "finality ran the root's :exit")
    (testing "the root's own :entry row"
      (is (= "(fn [_] {:data {:at :root-in}})"
             (#'view/cascade-row-source-form machine-meta entry))
          "the source body is the inline fn's code, not the compiled fn")
      (is (= {:file "placed/root.cljs" :line 7}
             (#'view/cascade-row-coord placed entry))
          "the source link is the root's coord"))
    (testing "the root's own :exit row, at teardown"
      (is (= "(fn [_] {:data {:at :root-out}})"
             (#'view/cascade-row-source-form machine-meta exit)))
      (is (= {:file "placed/root.cljs" :line 7}
             (#'view/cascade-row-coord placed exit))))))
