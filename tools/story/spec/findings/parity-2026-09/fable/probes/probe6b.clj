;; Probe 6b: what does the JVM run artifact actually carry? (diagnostic for probe6's zero)
(ns probe6b
  (:require [re-frame.core :as rf] [re-frame.frame :as rf.frame] [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom] [re-frame.story :as rf.story]
            [re-frame.story.promotion :as rf.story.promotion]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events] [re-frame.epoch]))
(defn result [f] (let [r (deref f 15000 ::timeout)] (if (= r ::timeout) {:status :TIMEOUT} r)))
(defn line [& xs] (println (apply str xs)))
(rf.story/clear-all!) (rf.registrar/clear-all!) (reset! rf.frame/frames {})
(rf/init! rf.substrate.plain-atom/adapter) (reset! rf.story.play.runner-events/run-state {}) (rf.story/install-canonical-vocabulary!) (rf.frame/ensure-default-frame!)
(rf/reg-event :probe/set (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
(rf.story/reg-story* :story.probe {:doc "probe"})
(rf.story/reg-variant* :story.probe/terminal {:setup [[:probe/set 1]] :assertions [[:rf.assert/path-equals [:v] 42]]})
(let [r   (result (rf.story/run :story.probe/terminal))
      art (rf.story/make-run-artifact r)]
  (line "P6b result :variant/id=" (pr-str (:variant/id r)) " epoch-tape=" (count (:epoch-tape r)) " status=" (:status r))
  (line "   artifact keys=" (pr-str (sort (keys art))))
  (line "   artifact :event-program=" (pr-str (:event-program art)))
  (line "   artifact source-ish=" (pr-str (select-keys art [:variant/id :source :source-variant :source-variant-id :variant-id :story/variant-id :provenance])))
  (line "   rf.story.promotion/source-variant-id=" (pr-str (try (rf.story.promotion/source-variant-id art) (catch Throwable e (str "THREW " (.getMessage e))))))
  (line "   artifact->variant-body=" (pr-str (rf.story.promotion/artifact->variant-body art))))
(line "DONE") (shutdown-agents) (System/exit 0)
