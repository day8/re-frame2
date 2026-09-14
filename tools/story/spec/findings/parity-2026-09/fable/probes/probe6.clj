;; Probe 6 (second sibling review, at trunk): the full journey astra asks for —
;; failing variant -> promote -> promoted FAILS for the same reason -> fix the app -> promoted PASSES
;; -> reintroduce the fault -> promoted FAILS again. Three source shapes: terminal :assertions,
;; a [:assert ...] script step, and an :expect-free control that must stay :pass.
(ns probe6
  (:require [re-frame.core :as rf] [re-frame.frame :as rf.frame] [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom] [re-frame.story :as rf.story]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events] [re-frame.epoch]))
(defn result [f] (let [r (deref f 15000 ::timeout)] (if (= r ::timeout) {:status :TIMEOUT} r)))
(defn line [& xs] (println (apply str xs)))
(rf.story/clear-all!) (rf.registrar/clear-all!) (reset! rf.frame/frames {})
(rf/init! rf.substrate.plain-atom/adapter) (reset! rf.story.play.runner-events/run-state {}) (rf.story/install-canonical-vocabulary!) (rf.frame/ensure-default-frame!)

;; the "app": :probe/set writes what it is told. The FAULT is that the app writes 1 where the spec wants 42.
(defn install-app! [faulty?]
  (rf/reg-event :probe/set (fn [{:keys [db]} [_ v]] {:db (assoc db :v (if faulty? v 42))})))
(install-app! true)

(rf.story/reg-story* :story.probe {:doc "probe"})

(defn journey [label source-id body]
  (rf.story/reg-variant* source-id body)
  (let [promoted-id (keyword "story.probe" (str (name source-id) "-promoted"))
        r0 (result (rf.story/run source-id))]
    (line label " source run: status=" (:status r0) " assertions=" (count (:assertions r0)))
    (rf.story/promote-run-artifact! (rf.story/make-run-artifact r0) {:variant/id promoted-id})
    (let [pbody (rf.story/handler-meta :variant promoted-id)]
      (line "   promoted body keys=" (pr-str (sort (keys pbody)))
            " :assertions=" (pr-str (:assertions pbody)) " :script=" (pr-str (:script pbody))))
    (let [r1 (result (rf.story/run promoted-id))]
      (line "   [fault present]   promoted status=" (:status r1) " assertions=" (count (:assertions r1))
            " first=" (pr-str (select-keys (first (:assertions r1)) [:status :expected :actual]))))
    (install-app! false)
    (let [r2 (result (rf.story/run promoted-id))]
      (line "   [app fixed]       promoted status=" (:status r2) " assertions=" (count (:assertions r2))))
    (install-app! true)
    (let [r3 (result (rf.story/run promoted-id))]
      (line "   [fault restored]  promoted status=" (:status r3) " assertions=" (count (:assertions r3))))))

(journey "P6a terminal :assertions" :story.probe/terminal
         {:setup [[:probe/set 1]] :assertions [[:rf.assert/path-equals [:v] 42]]})
(journey "P6b [:assert] script step" :story.probe/scripted
         {:setup [[:probe/set 1]] :script [[:assert [:rf.assert/path-equals [:v] 42]]]})
(journey "P6c control, no expectation (must stay :pass throughout)" :story.probe/noexpect
         {:setup [[:probe/set 1]]})
(line "DONE") (shutdown-agents) (System/exit 0)
