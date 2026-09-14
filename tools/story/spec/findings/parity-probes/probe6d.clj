;; Probe 6d: map the dialog-route boundary on setup-bearing shapes (the tutorial's and the testbed's).
(ns probe6d
  (:require [re-frame.core :as rf] [re-frame.frame :as rf.frame] [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom] [re-frame.story :as rf.story]
            [re-frame.story.promotion :as rf.story.promotion] [re-frame.story.ui.promotion :as rf.story.ui.promotion]
            [re-frame.story.determinism :as rf.story.determinism] [re-frame.story.plan :as rf.story.plan] [re-frame.story.play :as rf.story.play]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events] [re-frame.epoch]))
(defn result [f] (let [r (deref f 15000 ::timeout)] (if (= r ::timeout) {:status :TIMEOUT} r)))
(defn line [& xs] (println (apply str xs)))
(rf.story/clear-all!) (rf.registrar/clear-all!) (reset! rf.frame/frames {})
(rf/init! rf.substrate.plain-atom/adapter) (reset! rf.story.play.runner-events/run-state {}) (rf.story/install-canonical-vocabulary!) (rf.frame/ensure-default-frame!)
(defn install-app! [fixed?]
  (rf/reg-event :probe/inc (fn [{:keys [db]} _] {:db (if fixed? (update db :n (fnil inc 0)) (assoc db :n 0))})))
(defn verdict [id] (let [r (result (rf.story/run id))] [(:status r) (count (:assertions r))]))
(defn fault-fix-fault [id]
  (install-app! false) (let [a (verdict id)] (install-app! true) (let [b (verdict id)] (install-app! false) [a b (verdict id)])))
(rf.story/reg-story* :story.probe {:doc "probe"})
(defn journey [label source-id body]
  (install-app! false)
  (rf.story/reg-variant* source-id body)
  (let [r  (result (rf.story/run source-id))
        pe (rf.story.play/variant-play-events source-id)
        art (rf.story.ui.promotion/result->artifact r pe)
        d (keyword "story.probe" (str (name source-id) "-dialog"))
        a (keyword "story.probe" (str (name source-id) "-api"))]
    (line label)
    (line "   source under fault: " (pr-str [(:status r) (count (:assertions r))]) "  variant-play-events=" (pr-str pe) "  result->artifact nil? " (nil? art))
    (rf.story.promotion/promote-run-artifact! art (rf.story.ui.promotion/draft->promote-opts {:variant-id d :tags #{:test} :setup-count 0 :extends source-id}))
    (rf.story.promotion/promote-run-artifact! (rf.story.determinism/->artifact (rf.story.plan/variant-plan source-id)) {:variant/id a})
    (doseq [[route id] [["dialog" d] ["api" a]]]
      (let [b (rf.story/handler-meta :variant id)]
        (line "   " route " body keys=" (pr-str (sort (keys b))) " :assertions=" (pr-str (:assertions b)) " :script=" (pr-str (:script b)) " :setup=" (pr-str (:setup b)))
        (line "   " route " fault/fix/fault = " (pr-str (fault-fix-fault id)))))))
(journey "P6d-5 TESTBED shape: :setup event + :script [[:assert …]] only" :story.probe/testbed-shape
         {:tags #{:test} :setup [[:probe/inc]] :script [[:assert [:rf.assert/path-equals [:n] 1]]]})
(journey "P6d-6 TUTORIAL shape: :setup event + :script [[:dispatch-sync [:rf.assert/…]]]" :story.probe/tutorial-shape
         {:tags #{:test} :setup [[:probe/inc]] :script [[:dispatch-sync [:rf.assert/path-equals [:n] 1]]]})
(journey "P6d-7 MIXED: :setup event + :script dispatch + declarative :assertions" :story.probe/mixed
         {:tags #{:test} :setup [[:probe/inc]] :script [[:dispatch [:probe/inc]]] :assertions [[:rf.assert/path-equals [:n] 2]]})
(line "DONE") (shutdown-agents) (System/exit 0)
