;; Probe 4: does promotion preserve the terminal assertion that made the source fail? (sibling claim)
(ns probe4
  (:require [re-frame.core :as rf] [re-frame.frame :as rf.frame] [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom] [re-frame.story :as rf.story]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events] [re-frame.epoch]))
(defn result [f] (let [r (deref f 15000 ::timeout)] (if (= r ::timeout) {:status :TIMEOUT} r)))
(defn line [& xs] (println (apply str xs)))
(rf.story/clear-all!) (rf.registrar/clear-all!) (reset! rf.frame/frames {})
(rf/init! rf.substrate.plain-atom/adapter) (reset! rf.story.play.runner-events/run-state {}) (rf.story/install-canonical-vocabulary!) (rf.frame/ensure-default-frame!)
(rf/reg-event :probe/set (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
(rf.story/reg-story* :story.probe {:doc "probe"})
(rf.story/reg-variant* :story.probe/failing {:setup [[:probe/set 1]] :assertions [[:rf.assert/path-equals [:v] 42]]})
(let [r (result (rf.story/run :story.probe/failing))]
  (line "P4 source status=" (:status r) " assertions=" (count (:assertions r)))
  (let [art (rf.story/make-run-artifact r)]
    (rf.story/promote-run-artifact! art {:variant/id :story.probe/promoted})
    (let [body (rf.story/handler-meta :variant :story.probe/promoted)
          r2   (result (rf.story/run :story.probe/promoted))]
      (line "   promoted body keys=" (pr-str (sort (keys body))) " has :assertions? " (contains? body :assertions) " has :expect? " (contains? body :expect))
      (line "   promoted run status=" (:status r2) " assertions=" (count (:assertions r2)) " (sibling claim: :pass with 0 — expectation dropped)"))))
;; control: assertion carried as a dispatched event in :script survives?
(rf.story/reg-variant* :story.probe/failing-script {:setup [[:probe/set 1]] :script [[:assert [:rf.assert/path-equals [:v] 42]]]})
(let [r (result (rf.story/run :story.probe/failing-script))]
  (rf.story/promote-run-artifact! (rf.story/make-run-artifact r) {:variant/id :story.probe/promoted2})
  (let [r2 (result (rf.story/run :story.probe/promoted2))]
    (line "P4 control (assert in :script) source=" (:status r) " promoted=" (:status r2) " assertions=" (count (:assertions r2)))))
(line "DONE") (shutdown-agents) (System/exit 0)
