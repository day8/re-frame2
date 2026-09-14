;; Probe 7 (second sibling review, at trunk): Explain carries story-level args; run results carry
;; :plan-hash / :run-hash, present and stable across two identical runs.
(ns probe7
  (:require [re-frame.core :as rf] [re-frame.frame :as rf.frame] [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as pa] [re-frame.story :as story]
            [re-frame.story.play.runner-events :as re] [re-frame.epoch]))
(defn result [f] (let [r (deref f 15000 ::timeout)] (if (= r ::timeout) {:status :TIMEOUT} r)))
(defn line [& xs] (println (apply str xs)))
(story/clear-all!) (rf.registrar/clear-all!) (reset! rf.frame/frames {})
(rf/init! pa/adapter) (reset! re/run-state {}) (story/install-canonical-vocabulary!) (rf.frame/ensure-default-frame!)
(rf/reg-event :probe/set (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
(story/reg-story* :story.probe {:doc "probe" :args {:heading "Sign in"}})
(story/reg-variant* :story.probe/noargs {:setup [[:probe/set 1]]})
(story/reg-variant* :story.probe/ownargs {:setup [[:probe/set 1]] :args {:heading "Own"}})
(let [ex (story/explain :story.probe/noargs)]
  (line "P7 explain keys=" (pr-str (sort (keys ex))))
  (line "   :args=" (pr-str (:args ex)) " :effective-args=" (pr-str (:effective-args ex))
        " resolve-args=" (pr-str (story/resolve-args :story.probe/noargs))))
(let [ex (story/explain :story.probe/ownargs)]
  (line "   ownargs :effective-args=" (pr-str (:effective-args ex)) " (variant must win: Own)"))
(let [r1 (result (story/run :story.probe/noargs)) r2 (result (story/run :story.probe/noargs))]
  (line "P7 run keys=" (pr-str (sort (keys r1))))
  (line "   :plan-hash present? " (some? (:plan-hash r1)) " stable? " (= (:plan-hash r1) (:plan-hash r2))
        "  :run-hash present? " (some? (:run-hash r1)) " stable? " (= (:run-hash r1) (:run-hash r2))))
(line "DONE") (shutdown-agents) (System/exit 0)
