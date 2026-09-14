;; Probe 6c (second sibling review, at trunk): the promotion journey astra asks for, executed on
;; BOTH promotion routes (Test-dialog capture; spec/017 API from the compiled plan) and FOUR source
;; shapes. fault -> promote -> promoted FAILS (same assertion count) -> fix app -> PASSES -> refault -> FAILS.
(ns probe6c
  (:require [re-frame.core :as rf] [re-frame.frame :as rf.frame] [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as pa] [re-frame.story :as story]
            [re-frame.story.promotion :as promo] [re-frame.story.ui.promotion :as uip]
            [re-frame.story.determinism :as det] [re-frame.story.plan :as plan] [re-frame.story.play :as play]
            [re-frame.story.play.runner-events :as re] [re-frame.epoch]))
(defn result [f] (let [r (deref f 15000 ::timeout)] (if (= r ::timeout) {:status :TIMEOUT} r)))
(defn line [& xs] (println (apply str xs)))
(story/clear-all!) (rf.registrar/clear-all!) (reset! rf.frame/frames {})
(rf/init! pa/adapter) (reset! re/run-state {}) (story/install-canonical-vocabulary!) (rf.frame/ensure-default-frame!)

;; the app: :probe/inc should increment :n. The FAULT: it pins :n to 0.
(defn install-app! [fixed?]
  (rf/reg-event :probe/inc (fn [{:keys [db]} _] {:db (if fixed? (update db :n (fnil inc 0)) (assoc db :n 0))})))
(defn verdict [id] (let [r (result (story/run id))] [(:status r) (count (:assertions r))]))
(defn fault-fix-fault [id]
  (install-app! false) (let [a (verdict id)] (install-app! true) (let [b (verdict id)] (install-app! false) [a b (verdict id)])))

(story/reg-story* :story.probe {:doc "probe"})

(defn dialog-promote! [source-id r promoted-id]
  (promo/promote-run-artifact! (uip/result->artifact r (play/variant-play-events source-id))
                               (uip/draft->promote-opts {:variant-id promoted-id :tags #{:test} :setup-count 0 :extends source-id})))
(defn api-promote! [source-id promoted-id]
  (promo/promote-run-artifact! (det/->artifact (plan/variant-plan source-id)) {:variant/id promoted-id}))

(defn journey [label source-id body]
  (install-app! false)
  (story/reg-variant* source-id body)
  (let [r (result (story/run source-id))
        d (keyword "story.probe" (str (name source-id) "-dialog"))
        a (keyword "story.probe" (str (name source-id) "-api"))]
    (line label)
    (line "   source under fault: " (pr-str [(:status r) (count (:assertions r))]))
    (dialog-promote! source-id r d)
    (api-promote! source-id a)
    (doseq [[route id] [["dialog" d] ["api" a]]]
      (let [b (story/handler-meta :variant id)]
        (line "   " route " promoted body: keys=" (pr-str (sort (keys b))) " :assertions=" (pr-str (:assertions b)) " :script=" (pr-str (:script b)) " :setup=" (pr-str (:setup b)))
        (line "   " route " fault/fix/fault = " (pr-str (fault-fix-fault id)) "   (want [[:fail 1] [:pass 1] [:fail 1]])")))))

(journey "P6c-1 DECLARATIVE :assertions beside a :script dispatch" :story.probe/declared
         {:tags #{:test} :script [[:dispatch [:probe/inc]]] :assertions [[:rf.assert/path-equals [:n] 1]]})
(journey "P6c-2 IN-PROGRAM [:assert] checkpoint" :story.probe/checkpoint
         {:tags #{:test} :script [[:dispatch [:probe/inc]] [:assert [:rf.assert/path-equals [:n] 1]]]})
(journey "P6c-3 DISPATCHED :rf.assert/* event (the shape that survived before the fix)" :story.probe/dispatched
         {:tags #{:test} :script [[:dispatch [:probe/inc]] [:dispatch [:rf.assert/path-equals [:n] 1]]]})
(journey "P6c-4 SETUP-ONLY program + declarative :assertions (the tutorial's shape; not in the merged tests)" :story.probe/setup-only
         {:tags #{:test} :setup [[:probe/inc]] :assertions [[:rf.assert/path-equals [:n] 1]]})
(line "DONE") (shutdown-agents) (System/exit 0)
